package com.km.transmit

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * 3Q.5.3 FASE 3 — EL MEDIO FISICO: `FileTransmitUnitStore`.
 *
 * ## LA INVARIANTE QUE GOBIERNA ESTA CLASE
 *
 * > Un fallo fisico no puede convertirse en un nuevo estado criptografico.
 *
 * ## D-2 (spec rev5, obs #35): DONDE ESTA EL LIMITE
 *
 * El medio persiste **exactamente** los bytes que le da
 * [SecureTransmitJournal.persistir], sin interpretarlos, y solo se leen en
 * `restaurar`/`verificar`. Ninguna otra capa toca el medio.
 *
 * ## LOS DOS HUECOS, Y POR QUE SON DOS
 *
 * ```
 *   PENDIENTE   lo que se ha escrito y todavia nadie ha comprobado
 *   CONFIRMADA  lo unico que una recuperacion puede leer
 * ```
 *
 * Y hay un TERCER nombre, que NO es un hueco: `PENDIENTE_TMP` es el temporal
 * del propio medio, un archivo que nunca es una unidad y que ningun lector
 * mira. Es lo que separa una escritura INTERRUMPIDA de una escritura HECHA.
 *
 * ## LA DISCIPLINA DE PUBLICACION, Y POR QUE ES ESTA
 *
 * ```
 *   escribir el TEMPORAL -> sincronizar -> renombrar sobre el hueco
 *                                        -> sincronizar el DIRECTORIO
 * ```
 *
 * Un `writeBytes` dentro del hueco NO cumple el contrato: trunca el fichero
 * destino ANTES de escribir, de modo que un corte convierte una escritura a
 * medias en una unidad aparentemente entera. Escribiendo en un temporal que
 * nadie lee, el unico estado que el corte puede dejar es "no hay unidad" —que
 * es un estado ANTERIOR y no uno nuevo— y, como maximo, un temporal a medias.
 *
 * ## POR QUE `commit` NO COPIA Y BORRA
 *
 * Un `copyTo` seguido de `delete` tiene una ventana en la que se puede perder
 * la unidad nueva **y** la anterior: si el corte cae entre el `delete` y el
 * fin de la copia, no queda ninguna copia, y eso es el unico resultado en el
 * que un fallo fisico se convierte en perdida en vez de en rechazo. El
 * `rename` no tiene esa ventana porque no hay instante en el que el hueco
 * confirmado no exista.
 *
 * ## POR QUE EL HUECO CONFIRMADO NO SE BORRA JAMAS POR CODIGO
 *
 * Porque no se puede instalar el nuevo sin pasar por el. La promocion es un
 * unico `rename` que SUSTITUYE, y un corte deja siempre una de las dos
 * unidades, nunca ninguna. Por eso [discardPending] solo toca lo pendiente y
 * por eso un temporal sobrante de un corte se retira sin tocar lo confirmado.
 *
 * ## UN `.tmp` SOBRANTE NUNCA ES UNA UNIDAD
 *
 * Solo se renombran ficheros completos a su sitio, asi que cualquier fichero
 * que exista en un hueco esta ENTERO. Un temporal que sobra de un corte se
 * limpia al escribir (lo consume el `rename`) o al [discardPending], y
 * mientras tanto no lo lee nadie: [readBack] mira los dos huecos de unidad y
 * solo ellos. Leer nunca escribe, y por eso ninguna lectura retira el
 * temporal: si lo hiciera, `restaurar` dejaria de ser una operacion de solo
 * lectura.
 *
 * ## LO QUE NO SE HACE AQUI
 *
 * No se degrada `rename` a copia cuando no esta soportado: esa degradacion
 * seria exactamente el defecto que esta clase existe para evitar, asi que un
 * `ATOMIC_MOVE` que no se puede hacer es un fallo del medio
 * ([TransmitStoreFailure]), no una excusa.
 */
class FileTransmitUnitStore(private val dir: File) : TransmitUnitStore {

    /**
     * Escribe la unidad COMPLETA en el temporal, la sincroniza y la publica
     * en el hueco pendiente con un unico `rename`.
     *
     * Los bytes NO llegan nunca al hueco pendiente de otra forma: un corte
     * durante esta operacion deja, como mucho, un temporal a medias, que no es
     * una unidad y que ninguna lectura mira.
     *
     * @throws TransmitStoreFailure con [TransmitStage.WRITE_AHEAD].
     */
    override fun writeAhead(bytes: ByteArray) {
        comprobarHuecos(TransmitStage.WRITE_AHEAD, HUECOS_DE_UNIDAD + PENDIENTE_TMP)
        prepararDirectorio()
        val temporal = File(dir, PENDIENTE_TMP)
        try {
            FileChannel.open(
                temporal.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { canal ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) canal.write(buffer)
                canal.force(true)
            }
            Files.move(
                temporal.toPath(),
                File(dir, PENDIENTE).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: TransmitStoreFailure) {
            throw e
        } catch (e: Exception) {
            throw TransmitStoreFailure(TransmitStage.WRITE_AHEAD, "no se pudo escribir la unidad pendiente", e)
        }
        sync(dir.toPath())
    }

    /**
     * Lo pendiente si lo hay, y si no lo confirmado. El temporal no sale
     * nunca: no es una unidad.
     *
     * Solo se comprueba el hueco que se devuelve, y eso es deliberado: es la
     * lectura de la VERIFICACION, y su trabajo es devolver lo que se acaba de
     * escribir para compararlo. Si el otro hueco esta danado, la unidad
     * pendiente sigue siendo lo que hay en el medio y taparla con un fallo
     * impediria promoverla —y el reintento, que es lo que se puede hacer—.
     *
     * @throws TransmitStoreFailure con [TransmitStage.READ_BACK] si el hueco
     * del que se lee esta danado.
     */
    override fun readBack(): ByteArray? = leerHueco(PENDIENTE) ?: leerHueco(CONFIRMADA)

    /**
     * Lo CONFIRMADO, y nada mas.
     *
     * Esta si es la lectura de la RECUPERACION, y por eso comprueba el medio
     * ENTERO antes de devolver nada: devolver `null` de aqui es decir "el
     * medio esta vacio", que es una AFIRMACION sobre el estado y no un fallo.
     * Un hueco danado se DECLARA, nunca se lee como si no estuviera.
     *
     * @throws TransmitStoreFailure con [TransmitStage.READ_BACK].
     */
    override fun readCommitted(): ByteArray? {
        comprobarHuecos(TransmitStage.READ_BACK, HUECOS_DE_UNIDAD)
        return leerHueco(CONFIRMADA)
    }

    /**
     * PROMUEVE lo pendiente a confirmado con un unico `rename`.
     *
     * El hueco confirmado NO se borra antes de instalar el nuevo: la
     * sustitucion la hace el propio `rename`, y por eso un corte deja siempre
     * una de las dos unidades.
     *
     * @throws TransmitStoreFailure con [TransmitStage.COMMIT].
     */
    override fun commit() {
        comprobarHuecos(TransmitStage.COMMIT, HUECOS_DE_UNIDAD)
        val pendiente = File(dir, PENDIENTE)
        if (!pendiente.isFile) {
            throw TransmitStoreFailure(TransmitStage.COMMIT, "no hay ninguna unidad pendiente que confirmar")
        }
        try {
            Files.move(
                pendiente.toPath(),
                File(dir, CONFIRMADA).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: Exception) {
            throw TransmitStoreFailure(TransmitStage.COMMIT, "no se pudo promover la unidad a confirmada", e)
        }
        sync(dir.toPath())
    }

    /**
     * Retira lo pendiente Y el temporal sobrante. Nunca toca lo confirmado.
     */
    override fun discardPending() {
        for (nombre in listOf(PENDIENTE, PENDIENTE_TMP)) {
            try {
                Files.deleteIfExists(File(dir, nombre).toPath())
            } catch (e: Exception) {
                throw TransmitStoreFailure(TransmitStage.DISCARD, "no se pudo retirar el hueco '$nombre'", e)
            }
        }
    }

    /** `true` si hay una unidad pendiente de confirmar. El temporal no cuenta. */
    override fun hasPending(): Boolean = File(dir, PENDIENTE).isFile

    // ===================================================================
    // EL MEDIO POR DEBAJO
    // ===================================================================

    /**
     * Un hueco que existe y no es un fichero es MEDIO DANADO, no un estado.
     *
     * Se comprueba ANTES de tocar nada, para que el fallo llegue antes de que
     * el medio haya cambiado, y antes de que un `null` pudiera convertirse en
     * "el medio esta vacio".
     */
    private fun comprobarHuecos(stage: TransmitStage, nombres: List<String>) {
        for (nombre in nombres) {
            val f = File(dir, nombre)
            if (f.exists() && !f.isFile) {
                throw TransmitStoreFailure(stage, "el hueco '$nombre' del medio esta danado: no es un fichero")
            }
        }
    }

    /**
     * El contenido de un hueco, o `null` si esta vacio. Nunca el temporal.
     *
     * Un hueco que existe y no es un fichero NO cuenta como vacio: se declara
     * como medio danado, porque "no hay nada" es una afirmacion sobre el estado
     * que un hueco danado no puede sostener.
     */
    private fun leerHueco(nombre: String): ByteArray? {
        val f = File(dir, nombre)
        if (!f.exists()) return null
        if (!f.isFile) {
            throw TransmitStoreFailure(TransmitStage.READ_BACK, "el hueco '$nombre' del medio esta danado: no es un fichero")
        }
        return try {
            f.readBytes()
        } catch (e: Exception) {
            throw TransmitStoreFailure(TransmitStage.READ_BACK, "no se pudo leer el hueco '$nombre'", e)
        }
    }

    private fun prepararDirectorio() {
        if (dir.isDirectory) return
        dir.mkdirs()
        if (!dir.isDirectory) {
            throw TransmitStoreFailure(TransmitStage.WRITE_AHEAD, "el medio no tiene directorio: ${dir.absolutePath}")
        }
    }

    /**
     * `force(true)` del DIRECTORIO, para que el renombrado sobreviva a un
     * corte de energia.
     *
     * Si la plataforma no deja sincronizar el directorio se sigue adelante: el
     * renombrado ya ha ocurrido, no se puede deshacer, y el `rename` ya es
     * atomico. FINGIR un fallo aqui seria mentir sobre un cambio que ya esta
     * en el medio, que es la forma de convertir un corte en estado nuevo.
     */
    private fun sync(p: Path) {
        try {
            FileChannel.open(p, StandardOpenOption.READ).use { it.force(true) }
        } catch (e: Exception) {
            // Sin `fsync` de directorio esta plataforma no ofrece mas.
        }
    }

    companion object {
        const val PENDIENTE = "pendiente.bin"
        const val CONFIRMADA = "confirmada.bin"
        const val PENDIENTE_TMP = "pendiente.tmp"

        /** Donde puede haber una UNIDAD. El temporal no esta: no es un hueco. */
        private val HUECOS_DE_UNIDAD = listOf(PENDIENTE, CONFIRMADA)
    }
}
