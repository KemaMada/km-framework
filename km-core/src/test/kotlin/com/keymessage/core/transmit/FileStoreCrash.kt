package com.keymessage.core.transmit

import com.keymessage.core.km52.Km52Unit
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/* ==================================================================== *
 * 3Q.5.3 FASE 3 — EL ARNÉS DE CHOQUE DEL MEDIO FÍSICO.
 *
 * Este archivo NO contiene pruebas: contiene el `guion` que
 * `FileTransmitUnitStoreTest` ejecuta, el arnés que lanza OTRO proceso y lo
 * mata con `SIGKILL`, y el proceso que muere.
 *
 * ==================================================================== *
 * POR QUÉ OTRO PROCESO Y NO UNA EXCEPCIÓN
 *
 * El motivo está escrito en `CrashScenario.kt` y se repite aquí porque es el
 * que decide si estas pruebas miden algo: una excepción es una CAUSA y se
 * DESENROLLA. Se ejecutan los `catch` que encajen y se ejecuta `finally`
 * SIEMPRE. Un proceso al que el núcleo mata con `SIGKILL` no ejecuta nada de
 * eso.
 *
 * Para el MEDIO la diferencia es aún más dura, porque el medio tiene estado
 * en el DISCO y el desenrollado de la pila da la oportunidad de limpiarlo:
 *
 *  - un `finally { store.discardPending() }` en el camino de escritura
 *    borraría el temporal a medias y la recuperación vería un medio limpio,
 *    que es el final feliz, y la prueba estaría midiendo el `finally`;
 *  - un `close()` ordenado cerraría los descriptores con calma, y un
 *    `flush()` en ese `close` bajaría los bytes a disco: la durabilidad
 *    pasaría a depender de un cierre que un corte real no ejecuta.
 *
 * Por eso `morir()` NO es una excepción ni un retorno: escribe su informe,
 * avisa por un fichero de señal y se queda esperando. Lo único que lo saca de
 * ahí es la señal del padre.
 *
 * ==================================================================== *
 * POR QUÉ EL HIJO MANIPULA LOS HUECOS DEL MEDIO Y NO SÓLO LLAMA A LA API
 *
 * Porque hay DOS cortes que no tienen método público, y son los que más
 * importan:
 *
 *  - el temporal escrito ENTERO y el `rename` todavía no hecho;
 *  - el temporal escrito a MEDIAS.
 *
 * Entre el `write()` y el `rename` no hay ninguna llamada del contrato en la
 * que un arnés pueda meterse sin convertir al MEDIO en su propio doble: si
 * `FileTransmitUnitStore` tuviera un gancho de corte, la prueba mediría el
 * gancho y no el fichero.
 *
 * La solución no es un gancho: es que el hijo llama a la API REAL y luego
 * deshace, con `rename` en el sentido contrario, la ÚLTIMA operación del
 * medio. Los bytes del temporal son los que escribió el código de
 * producción —con su `flush` y su `fsync`—, y el estado que queda en el
 * disco es exactamente el que dejaría un escritor muerto en ese instante.
 * Lo que se mide después, desde el padre, es el MEDIO y la recuperación, que
 * es lo que hay que medir.
 *
 * Los nombres de los huecos son constantes PUBLICAS del medio y no un
 * detalle privado: donde está el fichero es parte del contrato con quien
 * opera el almacén, y es lo único que permite auditar el disco desde fuera.
 * ==================================================================== */

/** Donde muere el proceso, dentro de la costura del MEDIO. */
enum class PuntoDeCorteDelMedio(val orden: Int) {
    /** Sin corte: la operacion se completa. Es el CONTROL. */
    NINGUNO(-1),

    /** 1. El proceso muere sin tocar el medio. */
    ANTES_DE_ESCRIBIR(1),

    /** 2. El temporal queda a MEDIAS y el proceso muere. */
    DURANTE_LA_ESCRITURA(2),

    /**
     * 3. El temporal esta ESCRITO ENTERO y el `rename` NO ha ocurrido.
     *
     * Es el corte que separa "temporal" de "unidad": antes de este punto no
     * hay ninguna unidad, y despues la hay entera. Un `rename` no atomico o
     * un temporal que se publica sin mas son las dos formas de que este
     * punto deje de ser una frontera.
     */
    ANTES_DEL_RENAME(3),

    /** 4. La unidad esta EN el hueco pendiente y NADIE la ha releido. */
    DESPUES_DE_ESCRIBIR(4),

    /** 5. La lectura EMPEZO y devolvio; la comparacion no llego a hacerse. */
    DURANTE_LA_VERIFICACION(5),

    /** 6. La unidad esta escrita, releida y COMPARADA, y no promovida. */
    DESPUES_DE_VERIFICAR(6),

    /** 7. La unidad esta promovida y `persistir` ha vuelto: el proceso muere. */
    DESPUES_DEL_COMMIT(7),
    ;

    val esCorte: Boolean get() = this != NINGUNO
}

/** Lo que el proceso deja escrito en el instante en que se le mata. */
class InformeDelMedio(
    val punto: PuntoDeCorteDelMedio,
    private val lineas: Map<String, String>,
    private val salida: String,
    private val codigo: Int,
) {
    fun n(clave: String): Int = lineas[clave]?.toIntOrNull()
        ?: error("el hijo no informo de '$clave' (informe completo: $lineas)")

    /** `true` si el proceso ha muerto por SEÑAL y no ha salido por su cuenta. */
    val muertoPorSenal: Boolean get() = codigo == 137 || codigo == -9

    /** El estado LOGICO del proceso en el instante del corte. */
    val libroEnElCorte: Int?
        get() = lineas["testigo"]?.split(';')?.firstOrNull { it.startsWith("libro-") }
            ?.removePrefix("libro-")?.toIntOrNull()

    /** Que huecos del medio occupant el proceso al morir. */
    fun hueco(nombre: String): String? = lineas["hueco-$nombre"]

    val informe: String = buildString {
        append("punto: ").append(punto.name).append('\n')
        append("codigo de salida: ").append(codigo).append('\n')
        append(lineas.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }).append('\n')
        append("salida del hijo:\n").append(salida.ifBlank { "(vacia)" })
    }
}

/**
 * Lanza el proceso, espera a que alcance el punto y lo mata con `SIGKILL`.
 *
 * El reloj vive AQUI, en el arnés, y no en el codigo de produccion: lo que
 * decide es cuando matar al hijo, no nada sobre el estado de la sesion.
 */
fun ejecutarHastaElChoqueDelMedio(
    dir: File,
    punto: PuntoDeCorteDelMedio,
    limiteMs: Long = 120_000,
): InformeDelMedio {
    dir.mkdirs()
    for (nombre in listOf(FileStoreCrashChild.LISTO, FileStoreCrashChild.ESTADO, FileStoreCrashChild.SIN_MORIR)) {
        File(dir, nombre).delete()
    }
    File(dir, FileStoreCrashChild.SALIDA).delete()

    val java = File(System.getProperty("java.home"), "bin/java").absolutePath
    val proceso = ProcessBuilder(
        java,
        "-cp", System.getProperty("java.class.path"),
        "com.keymessage.core.transmit.FileStoreCrashChild",
        dir.absolutePath,
        punto.name,
    )
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(File(dir, FileStoreCrashChild.SALIDA)))
        .start()

    val limite = System.currentTimeMillis() + limiteMs
    var alcanzado: File? = null
    while (System.currentTimeMillis() < limite) {
        alcanzado = File(dir, FileStoreCrashChild.LISTO).takeIf { it.isFile }
        if (alcanzado != null) break
        if (!proceso.isAlive) break
        Thread.sleep(20)
    }

    if (alcanzado == null) {
        proceso.destroyForcibly()
        proceso.waitFor(30, TimeUnit.SECONDS)
        error(
            "el hijo no alcanzó el punto ${punto.name}.\n" +
                "  sin morir: ${File(dir, FileStoreCrashChild.SIN_MORIR).isFile}\n" +
                "  salida: ${File(dir, FileStoreCrashChild.SALIDA).takeIf { it.isFile }?.readText() ?: "(vacia)"}",
        )
    }

    // EL CORTE. `destroyForcibly()` es `SIGKILL` en POSIX: el nucleo mata el
    // hilo donde esta, sin desenrollar la pila, sin ejecutar `finally` y sin
    // lanzar los ganchos de cierre de la JVM.
    proceso.destroyForcibly()
    check(proceso.waitFor(60, TimeUnit.SECONDS)) { "el hijo no muere ni con SIGKILL" }

    val lineas = File(dir, FileStoreCrashChild.ESTADO)
        .takeIf { it.isFile }
        ?.readLines()
        ?.filter { it.contains('=') }
        ?.associate { it.substringBefore('=') to it.substringAfter('=') }
        .orEmpty()
    val salida = File(dir, FileStoreCrashChild.SALIDA).takeIf { it.isFile }?.readText().orEmpty()

    return InformeDelMedio(punto, lineas, salida, proceso.exitValue())
}

/* ==================================================================== *
 * EL PROCESO QUE SE MATA.
 *
 * Es una clase con `main` y no un metodo del test porque tiene que ser OTRO
 * PROCESO: un `SIGKILL` al hilo del test mataria tambien al arnés, y una
 * excepcion en un hilo seguiria ejecutando los `finally` de ese hilo.
 * ==================================================================== */
object FileStoreCrashChild {

    const val LISTO = "listo"
    const val ESTADO = "estado-medio.txt"
    const val SIN_MORIR = "sin-morir.txt"
    const val ERROR = "error-medio.txt"
    const val SALIDA = "salida-medio.txt"

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args[0])
        val punto = PuntoDeCorteDelMedio.valueOf(args[1])
        dir.mkdirs()

        val g = GuionDeterminista()
        g.dosEpochs()
        val store = FileTransmitUnitStore(dir)
        val journal = SecureTransmitJournal(
            g.bobSession, store, g.codec(), TransmitLedger(), ChainRetentionBook(),
        )
        // Los bytes que `persistir` llevaria al medio. Se calculan con el
        // MISMO guion determinista que usa el padre, de modo que el padre
        // puede compararlos sin tener que confiar en lo que el hijo dice.
        val bytes = g.codec().serialize(
            Km52Unit(
                g.bobSession.snapshot(),
                ChainRetentionBook().tablaPara(g.bobSession.snapshot()),
                g.envio(1, ordinal = 1uL, n = 1).aRegistro(),
            ),
        )

        /** El estado LOGICO, escrito en el instante del corte. */
        fun testigo() = "libro-${journal.ledger.tamanho()}"

        /**
         * El proceso deja de existir aqui.
         *
         * No es una excepcion y no es un retorno: escribe el informe —que el
         * padre lee DESPUES del `SIGKILL` y que por eso tiene que estar
         * escrito antes de morirse—, avisa, y espera a la senal.
         */
        fun morir() {
            File(dir, ESTADO).writeText(
                buildString {
                    append("testigo=").append(testigo()).append('\n')
                    append("hueco-pendiente=").append(hueco(dir, FileTransmitUnitStore.PENDIENTE)).append('\n')
                    append("hueco-confirmada=").append(hueco(dir, FileTransmitUnitStore.CONFIRMADA)).append('\n')
                    append("hueco-temporal=").append(hueco(dir, FileTransmitUnitStore.PENDIENTE_TMP)).append('\n')
                },
            )
            File(dir, LISTO).writeText(punto.name)
            Thread.sleep(Long.MAX_VALUE)
            throw IllegalStateException("el proceso deberia haber sido terminado en el punto ${punto.name}")
        }

        try {
            when (punto) {
                PuntoDeCorteDelMedio.NINGUNO ->
                    journal.persistir(g.envio(1, ordinal = 1uL, n = 1))

                PuntoDeCorteDelMedio.ANTES_DE_ESCRIBIR ->
                    // No se toca el medio. El que habia sigue siendo el unico
                    // estado que existe, y no se ha perdido nada.
                    morir()

                PuntoDeCorteDelMedio.DURANTE_LA_ESCRITURA -> {
                    store.writeAhead(bytes)
                    // Se deshace el `rename` del medio y se deja el temporal a
                    // medias: es el estado que deja un escritor cortado.
                    devolverAlTemporal(dir)
                    truncar(File(dir, FileTransmitUnitStore.PENDIENTE_TMP), (bytes.size / 2).toLong())
                    morir()
                }

                PuntoDeCorteDelMedio.ANTES_DEL_RENAME -> {
                    store.writeAhead(bytes)
                    // El temporal esta ENTERO —lo escribio y sincronizo el
                    // codigo de produccion— y el `rename` no ha ocurrido.
                    devolverAlTemporal(dir)
                    morir()
                }

                PuntoDeCorteDelMedio.DESPUES_DE_ESCRIBIR -> {
                    store.writeAhead(bytes)
                    // Nadie ha releido. La unidad esta ahi y no es estado.
                    morir()
                }

                PuntoDeCorteDelMedio.DURANTE_LA_VERIFICACION -> {
                    store.writeAhead(bytes)
                    // La lectura devuelvo y la COMPARACION no llego a hacerse.
                    check(store.readBack() != null) { "la lectura tiene que haber devuelto algo" }
                    morir()
                }

                PuntoDeCorteDelMedio.DESPUES_DE_VERIFICAR -> {
                    store.writeAhead(bytes)
                    val releido = checkNotNull(store.readBack())
                    check(releido.contentEquals(bytes)) { "la verificacion del hijo ha fallado: el medio no devolvio lo escrito" }
                    morir()
                }

                PuntoDeCorteDelMedio.DESPUES_DEL_COMMIT -> {
                    // Aqui corre el `persistir` ENTERO, con su verificacion y
                    // su `ledger.registrarDurable`. El corte es DESPUES de el:
                    // es el unico punto en el que el estado logico ha avanzado.
                    journal.persistir(g.envio(1, ordinal = 1uL, n = 1))
                    morir()
                }
            }
        } catch (e: Throwable) {
            if (e !is Exception) throw e
            File(dir, ERROR).writeText("${e::class.java.name}: ${e.message}\n${e.stackTraceToString()}")
        }

        File(dir, SIN_MORIR).writeText("el guion ha terminado con exito")
    }

    /** El `rename` del medio, deshecho: el hueco vuelve a ser un temporal. */
    private fun devolverAlTemporal(dir: File) {
        Files.move(
            File(dir, FileTransmitUnitStore.PENDIENTE).toPath(),
            File(dir, FileTransmitUnitStore.PENDIENTE_TMP).toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun truncar(f: File, n: Long) {
        java.io.RandomAccessFile(f, "rw").use { it.setLength(n) }
    }

    /** `vacio`, `N bytes` o `NO ES UN FICHERO`. Lo que el padre contrasta. */
    private fun hueco(dir: File, nombre: String): String {
        val f = File(dir, nombre)
        return when {
            !f.exists() -> "vacio"
            !f.isFile -> "NO-ES-UN-FICHERO"
            else -> "${f.length()} bytes"
        }
    }
}
