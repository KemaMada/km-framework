package com.keymessage.core.transmit

import com.keymessage.core.km52.Km52BadMagic
import com.keymessage.core.km52.Km52ChecksumMismatch
import com.keymessage.core.km52.Km52FormatException
import com.keymessage.core.km52.Km52Spec
import com.keymessage.core.km52.Km52Truncated
import com.keymessage.core.km52.Km52Unit
import com.keymessage.core.messaging.FrameIdentity
import com.keymessage.core.messaging.PendingInbox
import com.keymessage.core.ratchet.DoubleRatchetSession
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * 3Q.5.3 FASE 3 — EL MEDIO FÍSICO: `FileTransmitUnitStore`.
 *
 * ## LA INVARIANTE QUE GOBIERNA TODO ESTE ARCHIVO
 *
 * > **Un fallo físico no puede convertirse en un nuevo estado criptográfico.**
 *
 * De ahi sale todo lo demas: si el disco falla, el proceso muere o el fichero
 * se corrompe, la consecuencia NUNCA puede ser un estado nuevo —o se recupera
 * el anterior, o se rechaza y la sesion sigue intacta—, y jamas una MEZCLA.
 *
 * ## LO QUE ESTE ARCHIVO NO HACE
 *
 * No toca la criptografia, no decide entregas y no migra transporte. El medio
 * ve **bytes opacos** de `KM52` y no los interpreta; quien los interpreta es
 * el codec, y quien decide es [SecureTransmitJournal]. Por eso el paquete de
 * `ratchet/` no aparece ni una vez (MEDIO-11).
 *
 * ## POR QUÉ SE COMPRA EL MEDIO CON `SIGKILL` Y NO CON EXCEPCIONES
 *
 * Ver la cabecera de `FileStoreCrash.kt`. Una excepcion desenrolla la pila:
 * ejecuta `finally`, cierra ficheros con calma y da al medio la oportunidad
 * de limpiarse. Un corte real no hace nada de eso. Si la durabilidad del
 * medio dependiera de un cierre ordenado, una prueba con excepciones pasaria
 * midiendo el `finally`.
 *
 * ## EL AISLAMIENTO ENTRE PRUEBAS
 *
 * Cada prueba recibe su propio directorio y [dir] EXIGE que no exista antes de
 * crearlo. Sin esa exigencia, un fichero que una prueba deja en el disco
 * haria pasar a la siguiente por una razon equivocada, aqui y en la maquina
 * de al lado. El determinismo no vale: vale la reproducibilidad.
 */
class FileTransmitUnitStoreTest {

    @TempDir
    lateinit var tmp: File

    /**
     * Punto del generador determinista en el que se hace TODO comparaison.
     *
     * El guion genera unas pocas claves DH; 1000 esta muy por encima, asi que
     * dos sesiones comparadas aqui coinciden SI Y SOLO SI su estado coincide.
     */
    private val PUNTO: Int = 1000

    @BeforeEach
    fun `el directorio de trabajo esta vacio`() {
        // Si `@TempDir` devolviera un directorio ya poblado, cualquier prueba
        // podria pasar con el estado de otra. Se dice aqui, no en cada prueba.
        val sobrantes = tmp.listFiles()?.filter { it.isFile }.orEmpty()
        assertTrue(
            sobrantes.isEmpty(),
            "el directorio de trabajo tiene ficheros de otra prueba: ${sobrantes.map { it.name }}",
        )
    }

    // ===================================================================
    // UTILIDADES
    // ===================================================================

    /** Un directorio NUEVO para esta prueba, exigido como tal. */
    private fun dir(nombre: String): File {
        val d = tmp.resolve(nombre)
        assertFalse(d.exists(), "el directorio de '$nombre' ya existe: el estado de otra prueba se cuela")
        d.mkdirs()
        return d
    }

    private fun guion() = GuionDeterminista().also { it.dosEpochs() }

    /**
     * Un guion que ha AVANZADO de verdad.
     *
     * Hace falta para que "la unidad anterior" y "la unidad nueva" no sean el
     * mismo byte-string: sin esto, un `restore()` que devolviera lo que ya
     * habia pasaria por la razon equivocada.
     */
    private fun guionAvanzado(): GuionDeterminista = GuionDeterminista().also {
        it.dosEpochs()
        it.bobEnviaYAliceRecibe("b3")
        it.aliceEnviaYBobRecibe("a5")
    }

    /** Los bytes que la unidad lleva al medio. Se calculan, no se leen. */
    private fun bytesDe(g: GuionDeterminista, indice: Int = 1, ordinal: ULong = 1uL, n: Int = 1): ByteArray {
        val foto = g.bobSession.snapshot()
        return g.codec().serialize(
            Km52Unit(
                foto,
                ChainRetentionBook().tablaPara(foto),
                g.envio(indice, ordinal = ordinal, n = n).aRegistro(),
            ),
        )
    }

    private fun journal(s: DoubleRatchetSession, store: TransmitUnitStore, g: GuionDeterminista) =
        SecureTransmitJournal(s, store, g.codec(), TransmitLedger(), ChainRetentionBook())

    /** Cifra de control en un PUNTO FIJO del generador, sobre un CLON. */
    private fun frameEnPunto(s: DoubleRatchetSession, g: GuionDeterminista, punto: Int = PUNTO): ByteArray {
        g.x25519.contador = punto
        return frameDeControl(clonDe(s, g), g)
    }

    private fun frameEsperado(g: GuionDeterminista): ByteArray = frameEnPunto(g.bobSession, g)

    /** Una recuperacion montada SOBRE UNA CENTINELA, con su clon ANTERIOR. */
    private class Recuperacion(
        val j: SecureTransmitJournal,
        val sesion: DoubleRatchetSession,
        val antes: DoubleRatchetSession,
    )

    /**
     * Monta la recuperacion y EXIGE, ANTES de recuperar, que la centinela NO
     * esta ya en el estado que va a llegar.
     *
     * Sin esta exigencia, "el estado recuperado es el correcto" y "el estado
     * nunca se ha movido" serian la misma comprobacion, y un
     * `session.restore()` suprimido pasaria el archivo entero.
     */
    private fun recuperarSobre(store: TransmitUnitStore, g: GuionDeterminista, donde: String): Recuperacion {
        val sesion = g.sesionSentinel()
        val antes = clonDe(sesion, g)
        val j = journal(sesion, store, g)
        assertFalse(
            frameEsperado(g).contentEquals(frameEnPunto(sesion, g)),
            "$donde: la centinela TIENE que estar en un estado distinto del que se va a recuperar",
        )
        return Recuperacion(j, sesion, antes)
    }

    /** El estado VIVO de una sesion, en comparable, con criptografia. */
    private fun firmaDeLaUnidad(bytes: ByteArray, g: GuionDeterminista): String {
        val u = g.codec().deserialize(bytes)
        return buildString {
            append("ns=").append(u.snapshot.sendMessageNumber)
            append(" pn=").append(u.snapshot.previousChainLength)
            append(" cadenas=").append(u.snapshot.receiveChains.size)
            append(" retenidas=").append(u.snapshot.receiveChains.sumOf { it.ratchet.skipped.size })
            append(" envio=").append(u.outbound?.messageId ?: "-")
            append(" bandeja=").append(u.pendingInbound.size)
        }
    }

    /**
     * LOS DOS HUECOS del medio, tal y como estan en el disco.
     *
     * El temporal NO sale aqui, y es deliberado: un escritor cortado deja un
     * temporal a medias, y eso es lo ESPERADO. Comparar la foto entera antes y
     * despues de un corte mediria el temporal —que no es una unidad— en vez
     * de medir el estado.
     */
    private fun fotoDeLosHuecos(dir: File): String = buildString {
        for (n in listOf(FileTransmitUnitStore.CONFIRMADA, FileTransmitUnitStore.PENDIENTE)) {
            val f = File(dir, n)
            append(n).append('=').append(
                when {
                    !f.exists() -> "vacio"
                    !f.isFile -> "NO-ES-UN-FICHERO"
                    else -> "${f.length()}"
                },
            ).append(' ')
        }
    }

    /** El temporal del medio, que NUNCA es una unidad. */
    private fun elTemporal(dir: File): File = File(dir, FileTransmitUnitStore.PENDIENTE_TMP)

    /** El informe completo cuando algo falla: el mensaje solo no basta. */
    private fun porque(ch: InformeDelMedio, dir: File): String =
        "\n${ch.informe}\n  huecos: ${fotoDeLosHuecos(dir)}"

    // ===================================================================
    // MEDIO-01 — ESCRITURA Y LECTURA BYTE A BYTE
    // ===================================================================

    @Test
    @DisplayName("MEDIO-01 lo que se persiste se recupera identico, y el fichero es EXACTAMENTE lo escrito")
    fun `MEDIO-01 escritura y lectura byte a byte`() {
        val d = dir("byte-a-byte")

        // --- 1. BYTES OPACOS: el medio no sabe que son ------------------------
        //
        // Se empieza por material que NO es una unidad. Un medio que
        // interpretara el formato tendria aqui su primera oportunidad de
        // rechazarlo, de truncarlo o de anadirle algo.
        val opacos = listOf(
            ByteArray(0),
            byteArrayOf(0),
            ByteArray(64) { it.toByte() },
            ByteArray(4096) { ((it * 7 + 13) % 251).toByte() },
            ByteArray(200_000) { ((it * 31 + 5) % 253).toByte() },
        )
        val store = FileTransmitUnitStore(d)
        for ((i, blob) in opacos.withIndex()) {
            store.writeAhead(blob)
            assertContentEquals(blob, store.readBack(), "el medio devuelve byte a byte el opaco #$i (${blob.size} B)")
            assertContentEquals(
                blob, File(d, FileTransmitUnitStore.PENDIENTE).readBytes(),
                "el fichero del hueco contiene EXACTAMENTE lo escrito, sin sobrecabecera ni cola",
            )
        }

        // --- 2. UNA UNIDAD REAL, Y EL MEDIO NO LA INTERPRETA ------------------
        val g = guion()
        val bytes = bytesDe(g)
        val esperada = g.codec().deserialize(bytes)
        assertNotNull(esperada.outbound, "la unidad de referencia trae envio: el guion, no el medio")

        val d2 = dir("unidad-real")
        val store2 = FileTransmitUnitStore(d2)
        store2.writeAhead(bytes)
        assertContentEquals(bytes, store2.readBack(), "la unidad vuelve byte a byte")
        assertContentEquals(bytes, File(d2, FileTransmitUnitStore.PENDIENTE).readBytes(), "y el fichero es la unidad, entera")

        // --- 3. LA PROMOCION NO CAMBIA NI UN BYTE -----------------------------
        store2.commit()
        assertContentEquals(bytes, store2.readCommitted(), "lo confirmado es lo mismo byte a byte")
        assertContentEquals(bytes, store2.readBack(), "y readBack sigue viendo lo mismo")
        assertFalse(store2.hasPending(), "promover deja el hueco pendiente vacio")
        assertContentEquals(
            bytes, File(d2, FileTransmitUnitStore.CONFIRMADA).readBytes(),
            "y el fichero confirmado es la unidad, entera",
        )
    }

    // ===================================================================
    // MEDIO-02 — EL MEDIO NO ESCRIBE SOLO: LO QUE SE LEE NO SE TOCA
    // ===================================================================

    @Test
    @DisplayName("MEDIO-02 leer y recuperar no escriben, y descartar no toca lo confirmado")
    fun `MEDIO-02 el medio no escribe por su cuenta`() {
        val d = dir("lectura-sin-escritura")
        val g = guion()
        val bytes = bytesDe(g)
        val store = FileTransmitUnitStore(d)

        // --- 1. LEER UN MEDIO VACIO NO CREA NADA ------------------------------
        assertNull(store.readBack(), "un medio vacio no tiene nada pendiente")
        assertNull(store.readCommitted(), "ni nada confirmado")
        assertFalse(store.hasPending(), "ni siquiera un resto")
        assertTrue(
            d.listFiles().orEmpty().isEmpty(),
            "y no se ha creado NINGUN fichero: leer no escribe. Encontrados: ${d.listFiles()?.toList()}",
        )

        // --- 2. RECUPERAR SIN NADA CONFIRMADO NO ESCRIBE NADA ---------------
        val r = recuperarSobre(store, g, "medio vacio")
        assertNull(r.j.restaurar(), "no hay nada que recuperar")
        assertEquals(0, r.j.ledger.tamanho(), "y no se registra nada")
        assertTrue(
            d.listFiles().orEmpty().isEmpty(),
            "y la recuperacion no ha escrito en el medio: ${d.listFiles()?.toList()}",
        )

        // --- 3. DESCARTAR LO PENDIENTE NO TOCA LO CONFIRMADO -----------------
        //
        // La unidad CONFIRMADA es la del guion que ya avanzo, y la PENDIENTE es
        // la de otro guion: si no fueran distintas, "no ha tocado lo
        // confirmado" pasaria aunque lo hubiera reescrito con lo mismo.
        val confirmada = bytesDe(guionAvanzado(), indice = 4, ordinal = 2uL, n = 2)
        store.writeAhead(confirmada)
        store.commit()
        val pendiente = bytesDe(guion())
        assertFalse(
            confirmada.contentEquals(pendiente),
            "las dos unidades tienen que ser DISTINTAS, o este caso no mide nada",
        )
        store.writeAhead(pendiente)
        assertTrue(store.hasPending(), "hay una unidad pendiente")

        store.discardPending()
        assertFalse(store.hasPending(), "descartar retira lo pendiente")
        assertContentEquals(
            confirmada, store.readCommitted(),
            "y NO ha tocado lo confirmado, byte a byte: descartar es de lo pendiente y solo de lo pendiente",
        )
        // Y descartar cuando no hay nada pendiente es un no-op, no un fallo:
        // el journal lo llama en cuanto ve un fallo, y que ahi se colgara
        // enmascararia el error ORIGINAL con uno de limpieza.
        store.discardPending()
        assertContentEquals(confirmada, store.readCommitted(), "descartar dos veces tampoco rompe nada")
    }

    // ===================================================================
    // MEDIO-03 — ATOMICIDAD: UNA ESCRITURA PARCIAL NUNCA ES UNA UNIDAD
    // ===================================================================

    @Test
    @DisplayName("MEDIO-03 una muerte antes del rename no publica nada y la unidad anterior sobrevive")
    fun `MEDIO-03 una muerte antes del rename no publica nada`() {
        // Se parte de un medio que YA tiene una unidad confirmada, y por una
        // unidad NUEVA y distinta. Las dos fotos son del estado PREVIO: la
        // primera antes de que el hijo escriba nada, la segunda antes de que
        // el proceso muera. Ninguna se toma despues.
        for ((punto, temporalEsperado) in listOf(
            PuntoDeCorteDelMedio.ANTES_DE_ESCRIBIR to null,
            PuntoDeCorteDelMedio.DURANTE_LA_ESCRITURA to "parcial",
            PuntoDeCorteDelMedio.ANTES_DEL_RENAME to "entero",
        )) {
            val d = dir("atomicidad-${punto.name}")

            // --- FOTO DEL MEDIO ANTES DE QUE EL HIJO TOQUE NADA ---------------
            val gViejo = guionAvanzado()
            val vieja = bytesDe(gViejo, indice = 4, ordinal = 2uL, n = 2)
            FileTransmitUnitStore(d).apply {
                writeAhead(vieja)
                commit()
            }
            assertContentEquals(vieja, File(d, FileTransmitUnitStore.CONFIRMADA).readBytes(), "medio preparado")
            val antesDeEscribir = fotoDeLosHuecos(d)
            val guionEsperado = firmaDeLaUnidad(vieja, gViejo)

            // --- EL CORTE, DE VERDAD ------------------------------------------
            val ch = ejecutarHastaElChoqueDelMedio(d, punto)
            assertTrue(ch.muertoPorSenal, "el hijo tiene que morir por SEÑAL (SIGKILL)${porque(ch, d)}")
            assertFalse(
                File(d, FileStoreCrashChild.SIN_MORIR).isFile,
                "el guion ha terminado sin morir: el corte no ocurrio${porque(ch, d)}",
            )

            // --- 1. LO PENDIENTE NO SE PUBLICA NUNCA --------------------------
            //
            // Ni entero ni a medias: el hueco pendiente NO EXISTE. Lo que hay
            // es el temporal, que es del medio y no es una unidad.
            assertFalse(
                File(d, FileTransmitUnitStore.PENDIENTE).exists(),
                "$punto: el hueco pendiente no puede existir: una escritura que no ha terminado no es " +
                    "una unidad${porque(ch, d)}",
            )
            assertFalse(FileTransmitUnitStore(d).hasPending(), "$punto: ni siquiera como resto")

            when (temporalEsperado) {
                null -> assertFalse(
                    elTemporal(d).exists(),
                    "$punto: sin escritura no hay ni temporal",
                )

                "parcial" -> {
                    val tmpF = elTemporal(d)
                    assertTrue(tmpF.isFile, "$punto: el temporal a medias tiene que estar ahi")
                    val nueva = bytesDe(guion())
                    assertTrue(
                        tmpF.length() in 1 until nueva.size.toLong(),
                        "$punto: y tiene que ser un PREFIJO (${tmpF.length()} de ${nueva.size}), no otra cosa",
                    )
                    assertContentEquals(
                        nueva.copyOf(tmpF.length().toInt()), tmpF.readBytes(),
                        "$punto: y es el principio de la unidad buena, byte a byte",
                    )
                    assertTrue(
                        runCatching { guion().codec().deserialize(tmpF.readBytes()) }.isFailure,
                        "$punto: un prefijo jamas se puede leer como unidad: por eso confirmar sin verificar " +
                            "seria confirmar basura",
                    )
                }

                "entero" -> assertContentEquals(
                    bytesDe(guion()), elTemporal(d).readBytes(),
                    "$punto: el temporal esta ENTERO, con los bytes que escribio el codigo de produccion",
                )
            }

            // --- 2. Y LA UNIDAD ANTERIOR SIGUE SIENDO LA UNICA ----------------
            //
            // El estado del medio es IDENTICO al que habia antes del corte: el
            // fallo no ha produzido ningun estado nuevo.
            assertEquals(
                antesDeEscribir, fotoDeLosHuecos(d),
                "$punto: los dos huecos tienen que quedar EXACTAMENTE como estaban antes de la escritura fallida",
            )
            assertContentEquals(
                vieja, File(d, FileTransmitUnitStore.CONFIRMADA).readBytes(),
                "$punto: la unidad anterior es la unica copia y sigue byte a byte",
            )

            // --- 3. Y LA RECUPERACION DEVUELVE EL ESTADO ANTERIOR -------------
            val g = guion()
            g.dosEpochs()
            val r = recuperarSobre(FileTransmitUnitStore(d), g, "$punto")
            val registro = assertNotNull(r.j.restaurar(), "$punto: lo confirmado se recupera")
            assertEquals(
                guionEsperado, firmaDeLaUnidad(assertNotNull(r.j.unidadConfirmada()), guionAvanzado()),
                "$punto: y es la unidad VIEJA, no una mezcla ni la nueva",
            )
            assertEquals(mensajeId(2), registro.messageId, "$punto: el envio es el de la unidad anterior")
            assertFalse(r.j.ledger.esDurable(mensajeId(1)), "$punto: el envio NUEVO no aparece: no se perdio nada")
        }
    }

    // ===================================================================
    // MEDIO-04 — CRASH ALREDEDOR DE write -> verify -> commit
    // ===================================================================

    @Test
    @DisplayName("MEDIO-04 el corte alrededor de write verify commit nunca fabrica estado")
    fun `MEDIO-04 crash alrededor del commit`() {
        // Cada punto se mide con el medio VACIO primero —la sesion no puede
        // perder nada que no tuviera— y con un medio que ya tiene la unidad
        // anterior —la sesion no puede ganar nada que nadie confirmo.
        for (punto in listOf(
            PuntoDeCorteDelMedio.DESPUES_DE_ESCRIBIR,
            PuntoDeCorteDelMedio.DURANTE_LA_VERIFICACION,
            PuntoDeCorteDelMedio.DESPUES_DE_VERIFICAR,
            PuntoDeCorteDelMedio.DESPUES_DEL_COMMIT,
        )) {
            // --- SIN NADA PREVIO ------------------------------------------------
            run {
                val d = dir("vacio-${punto.name}")
                val ch = ejecutarHastaElChoqueDelMedio(d, punto)
                assertTrue(ch.muertoPorSenal, "vacio/$punto: muerte por senal${porque(ch, d)}")
                assertFalse(File(d, FileStoreCrashChild.SIN_MORIR).isFile, "vacio/$punto: no ha terminado${porque(ch, d)}")

                val nuevo = bytesDe(guion())
                val store = FileTransmitUnitStore(d)
                val confirmado = if (punto == PuntoDeCorteDelMedio.DESPUES_DEL_COMMIT) {
                    assertEquals(1, ch.libroEnElCorte, "vacio/$punto: el commit llego al estado logico")
                    assertContentEquals(nuevo, store.readCommitted(), "vacio/$punto: la unidad esta confirmada")
                    store.readCommitted()
                } else {
                    assertEquals(0, ch.libroEnElCorte, "vacio/$punto: el estado logico NO puede haber avanzado")
                    assertNull(store.readCommitted(), "vacio/$punto: y el medio no tiene nada confirmado")
                    assertTrue(store.hasPending(), "vacio/$punto: lo pendiente sigue pendiente")
                    null
                }

                val g = guion()
                g.dosEpochs()
                val r = recuperarSobre(store, g, "vacio/$punto")
                if (confirmado == null) {
                    assertNull(r.j.restaurar(), "vacio/$punto: lo no confirmado no se recupera: fabricar estado seria inventar claves")
                    assertEquals(0, r.j.ledger.tamanho(), "vacio/$punto: y no se registra nada")
                    assertContentEquals(
                        frameEnPunto(r.antes, g), frameEnPunto(r.sesion, g),
                        "vacio/$punto: la sesion intacta, con criptografia",
                    )
                } else {
                    val registro = assertNotNull(r.j.restaurar(), "vacio/$punto: lo confirmado SI se recupera")
                    assertEquals(mensajeId(1), registro.messageId, "vacio/$punto: con su envio")
                    assertEquals(
                        firmaDeLaUnidad(confirmado, guion()),
                        firmaDeLaUnidad(assertNotNull(r.j.unidadConfirmada()), guion()),
                        "vacio/$punto: y el estado recuperado es el de la unidad",
                    )
                }
            }

            // --- CON LA UNIDAD ANTERIOR YA EN PIE ------------------------------
            run {
                val d = dir("previo-${punto.name}")
                val gViejo = guionAvanzado()
                val vieja = bytesDe(gViejo, indice = 4, ordinal = 2uL, n = 2)
                FileTransmitUnitStore(d).apply { writeAhead(vieja); commit() }
                val firmaVieja = firmaDeLaUnidad(vieja, gViejo)

                val ch = ejecutarHastaElChoqueDelMedio(d, punto)
                assertTrue(ch.muertoPorSenal, "previo/$punto: muerte por senal${porque(ch, d)}")

                val store = FileTransmitUnitStore(d)
                val loConfirmado = store.readCommitted()
                assertNotNull(loConfirmado, "previo/$punto: la unidad anterior nunca se pierde")
                if (punto == PuntoDeCorteDelMedio.DESPUES_DEL_COMMIT) {
                    assertContentEquals(
                        bytesDe(guion()), loConfirmado,
                        "previo/$punto: con el commit SI se ha sustituido, y de una vez",
                    )
                } else {
                    assertContentEquals(
                        vieja, loConfirmado,
                        "previo/$punto: sin commit, la unidad ANTERIOR sigue siendo la vigente",
                    )
                    assertTrue(store.hasPending(), "previo/$punto: y lo nuevo esta pendiente, sin confirmar")
                }

                val g = guion()
                g.dosEpochs()
                val r = recuperarSobre(store, g, "previo/$punto")
                val registro = assertNotNull(r.j.restaurar(), "previo/$punto: se recupera lo confirmado")
                assertEquals(
                    if (punto == PuntoDeCorteDelMedio.DESPUES_DEL_COMMIT) mensajeId(1) else mensajeId(2),
                    registro.messageId,
                    "previo/$punto: el envio es el de la unidad vigente, no una mezcla",
                )
                assertEquals(1, r.j.ledger.tamanho(), "previo/$punto: y hay UN registro, no dos")
                assertEquals(
                    firmaDeLaUnidad(assertNotNull(r.j.unidadConfirmada()), guion()),
                    firmaDeLaUnidad(assertNotNull(loConfirmado), if (punto == PuntoDeCorteDelMedio.DESPUES_DEL_COMMIT) guion() else gViejo),
                    "previo/$punto: y el estado es exactamente el de esa unidad",
                )
            }
        }
    }

    // ===================================================================
    // MEDIO-05 — INTEGRIDAD: FICHERO CORRUPTO -> RECHAZO, NUNCA PARCIAL
    // ===================================================================

    @Test
    @DisplayName("MEDIO-05 un fichero danado se rechaza entero y la sesion no se toca")
    fun `MEDIO-05 integridad`() {
        val g0 = guion()
        val buena = bytesDe(g0)
        // El control, ANTES de danar nada: la unidad buena se lee y describe
        // el envio. Sin esto, "nunca restaura" pasaria aunque el guion estuviera
        // roto.
        assertEquals(
            mensajeId(1),
            assertNotNull(guion().codec().deserialize(buena).outbound, "el control trae envio").messageId,
            "el control: la unidad buena se lee y describe el envio",
        )

        val danadas = listOf(
            Triple(
                "checksum roto",
                buena.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() },
                Km52ChecksumMismatch::class.java,
            ),
            Triple(
                "cuerpo tocado",
                buena.copyOf().also { it[Km52Spec.SNAPSHOT_OFFSET + 3] = (it[Km52Spec.SNAPSHOT_OFFSET + 3] + 1).toByte() },
                Km52ChecksumMismatch::class.java,
            ),
            Triple("cola de basura", buena.copyOf(buena.size + 1).also { it[buena.size] = 0x7A }, Km52FormatException::class.java),
            Triple("truncada", buena.copyOf(buena.size / 2), Km52Truncated::class.java),
            Triple(
                "magic roto",
                buena.copyOf().also { it[Km52Spec.MAGIC_OFFSET] = 'X'.code.toByte() },
                Km52BadMagic::class.java,
            ),
        )
        assertEquals(5, danadas.size, "el guion tiene que cubrir cinco formas de unidad danada")

        for ((nombre, bytes, tipo) in danadas) {
            val d = dir("danada-${nombre.replace(' ', '-')}")
            val store = FileTransmitUnitStore(d)
            store.writeAhead(buena)
            store.commit()
            // El medio se daña POR FUERA, sin pasar por la API: es lo que
            // hace un sector que se pierde o una sustitucion de la capa
            // intermedia.
            File(d, FileTransmitUnitStore.CONFIRMADA).writeBytes(bytes)

            val g = guion()
            g.dosEpochs()
            val r = recuperarSobre(store, g, nombre)
            val e = org.junit.jupiter.api.Assertions.assertThrows(tipo) { r.j.restaurar() }
            assertTrue(e.message!!.isNotEmpty(), "$nombre: el error dice por que")
            assertEquals(0, r.j.ledger.tamanho(), "$nombre: no se registra nada a medias")
            assertFalse(r.j.ledger.esDurable(mensajeId(1)), "$nombre: y el envio no aparece")
            assertContentEquals(
                frameEnPunto(r.antes, g), frameEnPunto(r.sesion, g),
                "$nombre: la sesion sigue INTACTA, con criptografia y no con la huella",
            )
        }

        // Y el CONTROL, al final: la misma sesion, con la unidad buena, se
        // restaura. Sin esto, "nunca restaura" pasaria aunque lo que se
        // quisiera probar fuese que el guion esta roto.
        val d = dir("danada-control")
        val store = FileTransmitUnitStore(d)
        store.writeAhead(buena)
        store.commit()
        val g = guion()
        g.dosEpochs()
        val r = recuperarSobre(store, g, "control")
        assertNotNull(r.j.restaurar(), "con la unidad intacta, la recuperacion funciona")
        assertEquals(2, r.sesion.receiveChainCount(), "control: con las dos cadenas de la unidad")
        assertEquals(2, retenidasDe(r.sesion).size, "control: y las dos claves retenidas de la epoca vieja")
    }

    // ===================================================================
    // MEDIO-06 — EL MEDIO NO INTERPRETA LOS BYTES
    // ===================================================================

    @Test
    @DisplayName("MEDIO-06 el medio ve bytes opacos: no importa km52, ni el ratchet, ni nada")
    fun `MEDIO-06 el medio no interpreta`() {
        // --- 1. COMPORTAMIENTO: bytes que NO SON una unidad, sin_DRUIDA ------
        //
        // Un medio que interpretara el formato rechazaria esto, lo recortaria
        // o le anadiria algo. Lo devuelve tal cual, y el fichero es la copia
        // exacta: ni sobrecabecera, ni cola, ni reordenacion.
        val d = dir("opacos")
        val store = FileTransmitUnitStore(d)
        val cosas = listOf(
            "esto no es una unidad KM52" to "esto no es una unidad KM52".toByteArray(),
            "cabecera que miente" to ByteArray(32) { if (it < 4) 0x4B else 0x00 },
            "longitud imposible" to ByteArray(200_000),
            "unidad de verdad" to bytesDe(guion()),
        )
        for ((nombre, bytes) in cosas) {
            store.writeAhead(bytes)
            assertContentEquals(bytes, store.readBack(), "'$nombre' vuelve byte a byte")
            assertContentEquals(
                bytes, File(d, FileTransmitUnitStore.PENDIENTE).readBytes(),
                "'$nombre': el fichero es la copia EXACTA, sin nada anadido",
            )
            store.commit()
            assertContentEquals(
                bytes, File(d, FileTransmitUnitStore.CONFIRMADA).readBytes(),
                "'$nombre': y la promocion no le anade nada",
            )
            store.discardPending()
        }

        // --- 2. AUDITORIA: el medio no nombra lo que no puede conocer ---------
        //
        // Se audita el CODIGO y no la prosa: un KDoc puede decir "unidad
        // KM52" para explicar por que el medio no la toca, y eso es lo
        // correcto. Lo que no puede aparecer es un `import`, ni una constante
        // del formato, ni una llamada al codec.
        val fuente = File("src/main/kotlin/com/keymessage/core/transmit/FileTransmitUnitStore.kt")
        assertTrue(fuente.exists(), "no se encuentra $fuente desde ${File(".").absolutePath}")
        val codigo = codigoDe(fuente)
        assertTrue(codigo.isNotEmpty(), "el arnes tiene que encontrar codigo real en el medio")

        val paquetesProhibidos = listOf(
            "com.keymessage.core.km52",
            "com.keymessage.core.ratchet",
            "com.keymessage.core.sf",
            "com.keymessage.core.crypto",
            "com.keymessage.core.messaging",
            "com.keymessage.core.model",
        )
        val ofensas = mutableListOf<String>()
        for ((n, linea) in codigo) {
            for (p in paquetesProhibidos) if (linea.contains(p)) ofensas += "$n importa '$p' -> ${linea.trim()}"
        }
        assertTrue(
            ofensas.isEmpty(),
            "el medio no puede depender de nada que sepa interpretar la unidad:\n" +
                ofensas.joinToString("\n") { "  - $it" },
        )

        // Y ningun TIPO del formato, ni siquiera escrito en un comentario de
        // codigo: un nombre de tipo en el medio es por donde empieza la
        // interpretacion.
        val vocabulario = listOf(
            "Km52", "UNIT_VERSION", "HEADER_LENGTH", "CHECKSUM_LENGTH", "MAGIC", "OUTBOUND_FIXED_LENGTH",
            "PendingInbound", "OutboundRecord", "Km52UnitCodec", "BinaryKm52UnitCodec", "serialize",
            "deserialize", "retention", "snapshot",
        )
        val nombres = mutableListOf<String>()
        for ((n, linea) in codigo) {
            for (t in vocabulario) if (linea.contains(t)) nombres += "$n nombra '$t' -> ${linea.trim()}"
        }
        assertTrue(
            nombres.isEmpty(),
            "el medio no puede conocer el FORMATO: solo tiene delante bytes.\n" + nombres.joinToString("\n"),
        )
    }

    // ===================================================================
    // MEDIO-07 — SecureTransmitJournal SIGUE SIENDO EL UNICO ESCRITOR
    // ===================================================================

    @Test
    @DisplayName("MEDIO-07 el journal es el unico escritor: ninguna otra capa toca el medio")
    fun `MEDIO-07 un solo escritor`() {
        // --- 1. AUDITORIA: las llamadas al medio, en un solo fichero ---------
        //
        // Se buscan LLAMADAS, no declaraciones: implementar el contrato
        // declara los metodos, y lo que abriria una segunda ruta de
        // persistencia es INVOCARLOS.
        val base = File("src/main/kotlin/com/keymessage/core")
        assertTrue(base.exists(), "no se encuentra $base desde ${File(".").absolutePath}")
        val llamadas = mutableMapOf<String, MutableList<String>>()
        val patron = Regex("""\bstore\.(\w+)\(""")
        for (archivo in base.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            for ((n, linea) in codigoDe(archivo)) {
                for (m in patron.findAll(linea)) {
                    val metodo = m.groupValues[1]
                    val lista = llamadas.getOrPut(metodo) { mutableListOf() }
                    lista.add("${archivo.name}:$n")
                }
            }
        }
        assertTrue(llamadas.isNotEmpty(), "el arnes tiene que encontrar llamadas al medio")
        val fuera = mutableListOf<String>()
        for ((metodo, sitios) in llamadas) {
            for (sitio in sitios) {
                if (!sitio.startsWith("SecureTransmitJournal.kt:")) fuera += "$metodo en $sitio"
            }
        }
        assertTrue(
            fuera.isEmpty(),
            "el medio solo se toca desde SecureTransmitJournal: escribir por otra via abriria una segunda " +
                "ruta de persistencia.\n" + fuera.joinToString("\n") { "  - $it" },
        )

        // Y la misma auditoria para la lectura: `restaurar`/`verificar` son
        // las unicas que pueden mirar lo confirmado.
        for (metodo in listOf("readCommitted", "commit", "discardPending", "hasPending")) {
            val sitios = llamadas[metodo].orEmpty()
            assertTrue(sitios.isNotEmpty(), "el arnes tiene que encontrar llamadas a '$metodo'")
            assertTrue(
                sitios.all { it.startsWith("SecureTransmitJournal.kt:") },
                "'$metodo' solo se llama desde el journal, y se llama desde: $sitios",
            )
        }

        // --- 2. Y SOLO HAY UN MEDIO DE PRODUCCION ---------------------------
        val implementaciones = mutableListOf<String>()
        for (f in base.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            f.readLines().forEachIndexed { i, l ->
                val t = l.trim()
                if (t.startsWith("class ") && t.contains(": TransmitUnitStore")) {
                    implementaciones.add("${f.name}:${i + 1}")
                }
            }
        }
        assertEquals(
            1, implementaciones.size,
            "tiene que haber UN medio de produccion, y es el de fichero (encontrados: $implementaciones)",
        )
        assertTrue(
            implementaciones.single().startsWith("FileTransmitUnitStore.kt:"),
            "y es `FileTransmitUnitStore`, no otro: $implementaciones",
        )

        // --- 3. COMPORTAMIENTO: la LECTURA no deja escritura ---------------
        //
        // La auditoria dice quien llama a quien; esto dice que la operacion
        // EN SI no crea ficheros. Se mide con el medio ya poblado, para que un
        // `restore()` que escribiera de mas se notase en la foto.
        val d = dir("un-solo-escritor")
        val g = guion()
        val bytes = bytesDe(g)
        FileTransmitUnitStore(d).apply { writeAhead(bytes); commit() }
        val antes = fotoDeLosHuecos(d) + " temporal=" + elTemporal(d).exists()

        val g2 = guion()
        g2.dosEpochs()
        val r = recuperarSobre(FileTransmitUnitStore(d), g2, "un solo escritor")
        assertNotNull(r.j.restaurar(), "recuperar funciona")
        assertEquals(antes, fotoDeLosHuecos(d) + " temporal=" + elTemporal(d).exists(), "y no ha creado, movido ni borrado NINGUN fichero")

        // Y el estado LOGICO tampoco seicuso de mas: una unidad confirmada y
        // una recuperacion dan UN registro.
        assertEquals(1, r.j.ledger.tamanho(), "un solo registro: recuperar no duplica")
    }

    // ===================================================================
    // MEDIO-08 — LA MISMA UNIDAD LLEVA SNAPSHOT + OUTBOUND + BANDEJA
    // ===================================================================

    @Test
    @DisplayName("MEDIO-08 la MISMA unidad lleva el snapshot, el outbound singular y el PendingInbound")
    fun `MEDIO-08 la bandeja va en la misma unidad`() {
        val d = dir("bandeja")
        val g = guion()

        // --- 1. UNA UNIDAD CON BANDEJA, SNAPSHOT Y ENVIO -------------------
        val inbox = PendingInbox()
        val encolados = listOf(g.frames[1], g.frames[2], g.frames[4])
        for (f in encolados) assertTrue(inbox.encolar(f), "el frame se encola")

        val envio = g.envio(1, ordinal = 1uL, n = 1)
        val foto = g.bobSession.snapshot()
        val esperada = g.codec().serialize(
            Km52Unit(foto, ChainRetentionBook().tablaPara(foto), envio.aRegistro(), inbox.bloque()),
        )
        val esperadaLeida = g.codec().deserialize(esperada)

        val store = FileTransmitUnitStore(d)
        val j = SecureTransmitJournal(g.bobSession, store, g.codec(), TransmitLedger(), ChainRetentionBook())
        j.inbox = inbox
        val registro = j.persistir(envio)
        assertEquals(mensajeId(1), registro.messageId, "el envio se hace durable")

        // --- 2. LO QUE HAY EN EL MEDIO ES ESA MISMA UNIDAD ------------------
        val enElMedio = assertNotNull(store.readCommitted(), "el medio tiene la unidad confirmada")
        assertContentEquals(
            esperada, enElMedio,
            "el medio guarda EXACTAMENTE la unidad que el journal compuso, con bandeja incluida",
        )
        val leida = guion().codec().deserialize(enElMedio)
        assertEquals(3, leida.pendingInbound.size, "y la bandeja viaja en la misma unidad")
        assertEquals(
            esperadaLeida.pendingInbound.map { it.frameIdentity }, leida.pendingInbound.map { it.frameIdentity },
            "con las mismas identidades, EN ORDEN: el orden es FIFO y no es decorativo",
        )
        assertNotNull(leida.outbound, "y el outbound singular tambien")
        assertEquals(2, leida.snapshot.receiveChains.size, "y el snapshot entero")

        // --- 3. LA RECUPERACION REHIDRATA LA BANDEJA, NO LA VACIA -----------
        //
        // Un proceso NUEVO, con el objeto `FileTransmitUnitStore` NUEVO: es lo
        // que hace un arranque, y es donde una bandeja que se perdiera seria
        // silenciosa —el ratchet seguiria delante, solo faltarian los frames.
        val g2 = guion()
        g2.dosEpochs()
        val storeNuevo = FileTransmitUnitStore(d)
        val r = recuperarSobre(storeNuevo, g2, "bandeja")
        assertNotNull(r.j.restaurar(), "la unidad confirmada se recupera")
        assertEquals(3, r.j.inbox.tamano(), "la bandeja vuelve ENTERA desde el medio")
        assertEquals(
            leida.pendingInbound.map { it.frameIdentity }, r.j.inbox.entradas().map { it.frameIdentity },
            "con las mismas identidades y en el MISMO orden de llegada",
        )
        for (i in leida.pendingInbound.indices) {
            assertContentEquals(
                leida.pendingInbound[i].wireFrame, r.j.inbox.entradas()[i].wireFrame,
                "la entrada $i vuelve con sus bytes EXACTOS",
            )
        }
        assertEquals(
            2, r.sesion.receiveChainCount(),
            "y el estado criptografico tambien se recupera entero: no es una bandeja con su propio fichero",
        )
        assertEquals(
            3, encolados.map { FrameIdentity.fromWire(it) }.toSet().size,
            "el guion encolaba TRES frames con identidades distintas: si coincidieran, la bandeja seria una",
        )
    }

    // ===================================================================
    // MEDIO-09 — UN HUECO DANADO SE RECHAZA, NO SE SOBREESCRIBE
    // ===================================================================

    @Test
    @DisplayName("MEDIO-09 un hueco danado se rechaza, se declara, y la promocion fallida conserva lo anterior")
    fun `MEDIO-09 hueco danado`() {
        // --- 1. LEER UN HUECO DANADO FALLA, Y NO DEVUELVE "NADA" -----------
        //
        // Devolver `null` seria decir "el medio esta vacio", que es una
        // AFIRMACION sobre el estado y no un fallo: la sesion arrancaria sin
        // estado creyendo que no habia ninguno, y el fallo fisico se habria
        // convertido en estado NUEVO, que es justo lo que la invariante prohibe.
        for (hueco in listOf(FileTransmitUnitStore.CONFIRMADA, FileTransmitUnitStore.PENDIENTE)) {
            val d = dir("danado-lectura-${hueco.replace('.', '-')}")
            Files.createDirectory(File(d, hueco).toPath())
            val store = FileTransmitUnitStore(d)
            val e = assertThrows<TransmitStoreFailure> { store.readCommitted() }
            assertEquals(TransmitStage.READ_BACK, e.stage, "'$hueco': el error dice en que operacion fallo")
            assertTrue(e.message!!.isNotEmpty(), "'$hueco': y dice por que")
            assertFalse(store.hasPending(), "'$hueco': y no se inventa un pendiente")
        }

        // --- 2. ESCRIBIR CUANDO EL TEMPORAL NO SE PUEDE ESCRIBIR FALLA -----
        //
        // Y falla SIN TOCAR EL HUECO. Esta es la propiedad que separa "publica
        // a traves de un temporal" de "escribe dentro del hueco": un medio que
        // escribiera dentro del hueco se saltaria el temporal y dejaria aqui
        // una unidad donde no deberia haber ninguna.
        val d1 = dir("danado-temporal")
        Files.createDirectory(File(d1, FileTransmitUnitStore.PENDIENTE_TMP).toPath())
        val bytes = bytesDe(guion())
        val store1 = FileTransmitUnitStore(d1)
        val e1 = assertThrows<TransmitStoreFailure> { store1.writeAhead(bytes) }
        assertEquals(TransmitStage.WRITE_AHEAD, e1.stage, "el error dice en que operacion fallo")
        assertFalse(
            File(d1, FileTransmitUnitStore.PENDIENTE).exists(),
            "una escritura que no ha podido hacerse NO puede haber dejado una unidad en el hueco: un hueco " +
                "con bytes a medias no se distingue de uno entero",
        )
        assertNull(store1.readBack(), "y el medio sigue sin nada")

        // --- 3. CONFIRMAR SIN NADA PENDIENTE ES UN FALLO TIPADO -------------
        //
        // No una excepcion cruda del sistema de ficheros: quien reintenta un
        // envio necesita saber EN QUE OPERACION fallo el medio, y eso es lo
        // unico que dice `TransmitStoreFailure`.
        val d2 = dir("commit-sin-pendiente")
        val store2 = FileTransmitUnitStore(d2)
        val e2 = assertThrows<TransmitStoreFailure> { store2.commit() }
        assertEquals(TransmitStage.COMMIT, e2.stage, "el fallo es del COMMIT")
        assertNull(store2.readCommitted(), "y no se ha inventado nada confirmado")

        // --- 4. UNA PROMOCION QUE NO PUEDE SER ATOMICA NO DESTRUYE NADA ----
        //
        // El hueco confirmado es un DIRECTORIO VACIO: `rename()` de un fichero
        // sobre un directorio falla, y ese fallo es la forma real de que la
        // promocion no se pueda hacer. Se exige que `commit` FALLE y que la
        // unidad ANTERIOR siga ahi.
        val d3 = dir("promocion-imposible")
        val g = guionAvanzado()
        val vieja = bytesDe(g, indice = 4, ordinal = 2uL, n = 2)
        val store3 = FileTransmitUnitStore(d3)
        store3.writeAhead(vieja)
        Files.createDirectory(File(d3, FileTransmitUnitStore.CONFIRMADA).toPath())

        val e3 = assertThrows<TransmitStoreFailure> { store3.commit() }
        assertEquals(TransmitStage.COMMIT, e3.stage, "el fallo es del COMMIT")
        assertTrue(
            Files.isDirectory(File(d3, FileTransmitUnitStore.CONFIRMADA).toPath()),
            "el hueco danado NO se ha sobrescrito ni se ha borrado: un fallo del medio no puede destruir " +
                "la unica copia",
        )
        assertContentEquals(
            vieja, store3.readBack(),
            "y lo pendiente SIGUE pendiente: una promocion fallida deja la unidad nueva a salvo",
        )
        assertTrue(store3.hasPending(), "y se puede reintentar")

        // --- 5. Y SI LA PROMOCION SE PUEDE HACER, SE HACE ENTERA -----------
        //
        // El control: sin el directorio en medio, el `commit` funciona y deja
        // la unidad nueva confirmada byte a byte.
        val d4 = dir("promocion-posible")
        val store4 = FileTransmitUnitStore(d4)
        store4.writeAhead(vieja)
        store4.commit()
        assertContentEquals(
            vieja, store4.readCommitted(),
            "sin danio, la promocion deja la unidad CONFIRMADA byte a byte",
        )
        assertFalse(store4.hasPending(), "y no queda nada pendiente")
    }

    // ===================================================================
    // MEDIO-10 — AISLAMIENTO, DETERMINISMO Y CONTINUIDAD ENTRE INSTANCIAS
    // ===================================================================

    @Test
    @DisplayName("MEDIO-10 el mismo guion da el mismo medio, y otra instancia del medio ve lo mismo")
    fun `MEDIO-10 determinismo y continuidad`() {
        // --- 1. EL MEDIO ES UNA FUNCION PURA DE LOS BYTES -------------------
        val dA = dir("det-A")
        val dB = dir("det-B")
        for (d in listOf(dA, dB)) {
            val bytes = bytesDe(guionAvanzado(), indice = 4, ordinal = 2uL, n = 2)
            val store = FileTransmitUnitStore(d)
            store.writeAhead(bytes)
            store.commit()
        }
        assertContentEquals(
            File(dA, FileTransmitUnitStore.CONFIRMADA).readBytes(),
            File(dB, FileTransmitUnitStore.CONFIRMADA).readBytes(),
            "dos directorios limpios y el mismo guion dan el MISMO medio, byte a byte",
        )
        assertEquals(
            fotoDeLosHuecos(dA) + " temporal=" + elTemporal(dA).exists(),
            fotoDeLosHuecos(dB) + " temporal=" + elTemporal(dB).exists(),
            "y los dos medios quedan con la MISMA foto, temporales incluidos",
        )

        // --- 2. OTRA INSTANCIA DEL MEDIO VE LO MISMO ------------------------
        //
        // El estado del medio es del DISCO, no del objeto: si dependiera del
        // objeto, un reinicio perderia la unidad, que es justo lo que la
        // costura de `restaurar` promete que no pasa.
        val d = dir("continuidad")
        val g = guionAvanzado()
        val bytes = bytesDe(g, indice = 4, ordinal = 2uL, n = 2)
        val j1 = journal(g.bobSession, FileTransmitUnitStore(d), g)
        j1.persistir(g.envio(4, ordinal = 2uL, n = 2))

        val g2 = guion()
        g2.dosEpochs()
        val r = recuperarSobre(FileTransmitUnitStore(d), g2, "continuidad")
        assertNotNull(r.j.restaurar(), "una instancia NUEVA del medio ve la unidad de la anterior")
        assertEquals(3, r.sesion.receiveChainCount(), "con las TRES cadenas de la unidad confirmada")
        assertContentEquals(
            bytes, assertNotNull(r.j.unidadConfirmada()),
            "y la unidad es la MISMA, byte a byte",
        )
    }

    // ===================================================================
    // MEDIO-11 — LA FRONTERA: ratchet/ NO CONOCE ESTA CAPA
    // ===================================================================

    @Test
    @DisplayName("MEDIO-11 el ratchet no importa la capa del medio, ni la nombra")
    fun `MEDIO-11 frontera del ratchet`() {
        val ratchet = File("src/main/kotlin/com/keymessage/core/ratchet")
        assertTrue(ratchet.exists(), "no se encuentra $ratchet")
        val fuentes = ratchet.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(fuentes.size >= 5, "el guion tiene que auditar el paquete entero del ratchet")

        // El paquete del medio es NUEVO, asi que el veto tiene que incluirlo
        // por su nombre de tipo: un ratchet que nombrara
        // `FileTransmitUnitStore` en un KDoc ya sabria que existe una capa de
        // persistencia, y por ahi empiezan las dependencias.
        val tipos = listOf(
            "FileTransmitUnitStore", "FileStoreCrashChild", "TransmitUnitStore",
            "SecureTransmitJournal", "Km52Unit", "OutboundRecord",
        )
        val ofensas = mutableListOf<String>()
        for (archivo in fuentes) {
            archivo.readLines().forEachIndexed { i, linea ->
                for (t in tipos) if (linea.contains(t)) ofensas += "${archivo.name}:${i + 1} nombra '$t'"
            }
        }
        assertTrue(
            ofensas.isEmpty(),
            "el ratchet no puede conocer la capa del medio:\n" + ofensas.joinToString("\n") { "  - $it" },
        )

        // Y `km-core` sigue sin depender de `km-webrtc` ni de una libreria de
        // WebRTC: sin esto, "no conoce la persistencia" seria un detalle de
        // nombre.
        val build = File("build.gradle.kts").readText()
        assertFalse(build.contains("webrtc"), "km-core no puede declarar una dependencia de km-webrtc")
        assertFalse(build.contains("dev.onvoid"), "km-core no puede depender de una libreria de WebRTC (R3-12)")
    }

    // ===================================================================
    // MEDIO-12 — LA DISCIPLINA DE PUBLICACION DEL MEDIO
    // ===================================================================

    /**
     * MEDIO-12 — Como se publica un byte-string en el medio.
     *
     * ## POR QUE ESTA PRUEBA ES UNA AUDITORIA Y NO UN COMPORTAMIENTO
     *
     * Porque la atomicidad de un medio SOLO se ve cuando el proceso muere
     * DENTRO de la operacion del medio, y ahi no hay ninguna llamada del
     * contrato en la que un arnes pueda meterse sin convertir al medio en su
     * propio doble —un gancho de corte dentro de `FileTransmitUnitStore`
     * haria que la prueba midiese el gancho—. Lo que si se puede comprobar, y
     * es determinista, es la DISCIPLINA con la que se publica:
     *
     * ```
     *   escribir el TEMPORAL -> sincronizar -> renombrar sobre el hueco
     *                                           -> sincronizar el DIRECTORIO
     * ```
     *
     * MEDIO-03 y MEDIO-04 demuestran que la RECUPERACION acierta en cada uno
     * de los estados intermedios. Esta demuestra que el codigo de produccion
     * es el que produce esos estados y no otros, y por eso es el guardian de
     * "se escribe dentro del hueco" y de "se borra el hueco viejo antes de
     * instalar el nuevo": las dos cosas que ninguna prueba de comportamiento
     * puede ver sin una carrera.
     *
     * ## POR QUE LA SEGUNDA COMPROBACION ES LA PELIGROSA
     *
     * El hueco confirmado no se puede borrar jamas por codigo. Si se borrara,
     * un corte entre el borrado y la instalacion dejaria el medio SIN NINGUNA
     * copia: ni la anterior ni la nueva, que es el unico resultado en el que
     * un fallo fisico se convierte en perdida en vez de en rechazo.
     */
    @Test
    @DisplayName("MEDIO-12 el medio publica con temporal y renombrado atomico, y nunca borra el hueco confirmado")
    fun `MEDIO-12 la disciplina de publicacion`() {
        val fuente = File("src/main/kotlin/com/keymessage/core/transmit/FileTransmitUnitStore.kt")
        assertTrue(fuente.exists(), "no se encuentra $fuente desde ${File(".").absolutePath}")
        val codigo = codigoDe(fuente)
        assertTrue(codigo.size >= 40, "el arnes tiene que encontrar el cuerpo del medio, no un esqueleto")

        // --- 1. SE PUBLICA CON `rename` ATOMICO ------------------------------
        for (token in listOf("ATOMIC_MOVE", "REPLACE_EXISTING", "PENDIENTE_TMP", "sync(", "force(")) {
            val donde = codigo.filter { it.second.contains(token) }
            assertTrue(
                donde.isNotEmpty(),
                "el medio tiene que publicar con temporal y renombrado atomico: no aparece '$token' en su codigo",
            )
        }
        val atomicos = codigo.count { it.second.contains("ATOMIC_MOVE") }
        assertTrue(
            atomicos >= 2,
            "las DOS publicaciones —el temporal al hueco pendiente y la promocion— tienen que ser atomicas. " +
                "Aparece ATOMIC_MOVE $atomicos veces:\n" +
                codigo.filter { it.second.contains("ATOMIC_MOVE") }
                    .joinToString("\n") { "  ${it.first}: ${it.second.trim()}" },
        )
        assertFalse(
            codigo.any { it.second.contains("AtomicMoveNotSupportedException") },
            "el medio NO puede degradar a una copia no atomica cuando `rename` no esta soportado: eso seria " +
                "exactamente el defecto que se esta evitando",
        )

        // --- 2. Y EL HUECO CONFIRMADO NO SE BORRA JAMAS ---------------------
        val borrados = codigo.filter { (n, l) ->
            (l.contains("delete()") || l.contains("deleteIfExists")) && l.contains(FileTransmitUnitStore.CONFIRMADA)
        }
        assertTrue(
            borrados.isEmpty(),
            "el hueco CONFIRMADO no se puede borrar por codigo: un corte entre el borrado y la instalacion " +
                "dejaria el medio sin ninguna copia.\n" +
                borrados.joinToString("\n") { "  ${it.first}: ${it.second.trim()}" },
        )
        val pendientesPorBorrado = codigo.filter { (n, l) ->
            l.contains("delete") && l.contains(FileTransmitUnitStore.PENDIENTE) && !l.contains("PENDIENTE_TMP")
        }
        assertTrue(
            pendientesPorBorrado.isEmpty(),
            "el hueco pendiente solo desaparece por el renombrado que lo promueve, nunca por un borrado previo.\n" +
                pendientesPorBorrado.joinToString("\n") { "  ${it.first}: ${it.second.trim()}" },
        )
    }

    // ===================================================================
    // MEDIO-13 — LOS SEIS CRITERIOS DEL REPARTO, MEDIDOS AISLADOS
    // ===================================================================

    /**
     * MEDIO-13 — Por que esta seccion existe aparte de MEDIO-03/04 y MEDIO-12.
     *
     * MEDIO-03 y MEDIO-04 comprueban que la RECUPERACION acierta en cada estado
     * intermedio, y MEDIO-12 comprueba que el CODIGO publica con temporal y
     * `rename`. Los cuatro de aqui miden la PROPIEDAD DEL MEDIO por si misma,
     * sin `SIGKILL` ni arnes de otro proceso, que es lo que hace falta para
     * repetir la medicion en cualquier lado.
     */
    @Test
    @DisplayName("MEDIO-13-A la escritura normal publica la unidad entera y no deja rastro")
    fun `MEDIO-13-A la escritura normal`() {
        val d = dir("criterio-escritura")
        val bytes = bytesDe(guion())
        val store = FileTransmitUnitStore(d)

        // --- 1. LEER UN MEDIO VACIO NO CREA NADA -----------------------------
        assertNull(store.readBack(), "un medio vacio no tiene nada")
        assertTrue(
            d.listFiles().orEmpty().isEmpty(),
            "y ni siquiera un temporal: leer no escribe. Encontrados: ${d.listFiles()?.toList()}",
        )

        // --- 2. LA ESCRITURA PUBLICA LA UNIDAD ENTERA ------------------------
        store.writeAhead(bytes)
        assertTrue(store.hasPending(), "hay una unidad pendiente de confirmar")
        assertContentEquals(bytes, store.readBack(), "readBack devuelve lo escrito byte a byte")
        assertContentEquals(
            bytes, File(d, FileTransmitUnitStore.PENDIENTE).readBytes(),
            "el hueco pendiente es EXACTAMENTE la unidad, sin sobrecabecera ni cola",
        )
        assertFalse(
            elTemporal(d).exists(),
            "y no queda temporal: el renombrado se lo llevo. Un temporal vivo aqui seria un temporal " +
                "sobrante, y sobrar significa que algo se publico sin publicar",
        )

        // --- 3. Y LA PROMOCION NO CAMBIA NI UN BYTE ---------------------------
        store.commit()
        assertFalse(store.hasPending(), "no queda nada pendiente")
        assertContentEquals(bytes, store.readCommitted(), "lo confirmado es la misma unidad, byte a byte")
        assertContentEquals(bytes, store.readBack(), "y readBack sigue viendo lo mismo")
        assertFalse(elTemporal(d).exists(), "la promocion tampoco deja temporal")
        assertEquals(
            listOf(FileTransmitUnitStore.CONFIRMADA),
            d.listFiles().orEmpty().map { it.name }.sorted(),
            "el medio queda con EXACTAMENTE un fichero, el de la unidad confirmada: ${d.listFiles()?.toList()}",
        )
    }

    @Test
    @DisplayName("MEDIO-13-B un temporal sobrante no es una unidad: no se lee, y se limpia")
    fun `MEDIO-13-B un temporal no es una unidad`() {
        val d = dir("criterio-temporal")
        val buena = bytesDe(guion())
        val store = FileTransmitUnitStore(d)
        val tmp = elTemporal(d)

        // --- 1. UN TEMPORAL A MEDIAS, COMO LO DEJA UN ESCRITOR CORTADO --------
        Files.write(tmp.toPath(), buena.copyOf(buena.size / 2))
        val partida = tmp.readBytes()
        assertNull(store.readBack(), "el temporal NO es una unidad: readBack no lo ve")
        assertNull(store.readCommitted(), "ni lo ve como confirmada")
        assertFalse(store.hasPending(), "ni lo cuenta como pendiente")

        // --- 2. Y LEERLO NO LO RETIRA: LEER NO ESCRIBE -----------------------
        //
        // Si una lectura limpiara el temporal, `restaurar` dejaria de ser una
        // operacion de solo lectura y el corte que se quiere mirar se borraria
        // al mirarlo.
        assertNull(store.readCommitted(), "una segunda lectura sigue sin ver nada")
        assertContentEquals(
            partida, tmp.readBytes(),
            "y el temporal sigue INTACTO: nadie lo ha leido y nadie lo ha publicado",
        )

        // --- 3. NI SIQUIERA UN TEMPORAL ENTERO SE LEE COMO UNIDAD -------------
        Files.write(tmp.toPath(), buena)
        assertNull(
            store.readBack(),
            "ni entero: un temporal no es una unidad porque no ha pasado por el renombrado, no porque " +
                "le falten bytes",
        )
        assertNull(store.readCommitted(), "ni como confirmada")

        // --- 4. DESCARTAR LO PENDIENTE TAMBIEN LO RETIRA ---------------------
        store.discardPending()
        assertFalse(elTemporal(d).exists(), "descartar limpia el temporal sobrante, que no es estado")
        assertTrue(
            d.listFiles().orEmpty().isEmpty(),
            "y el medio queda limpio: ni temporal ni unidad. Encontrados: ${d.listFiles()?.toList()}",
        )

        // --- 5. Y LA ESCRITURA SIGUIENTE LO CONSUME --------------------------
        store.writeAhead(buena)
        assertFalse(elTemporal(d).exists(), "una escritura correcta se lleva el temporal consigo")
        assertContentEquals(buena, store.readBack(), "y publica la unidad, entera")
        assertTrue(store.hasPending(), "que queda pendiente, como siempre")
    }

    @Test
    @DisplayName("MEDIO-13-C el commit es un renombrado, no una copia: el fichero conserva su inodo")
    fun `MEDIO-13-C el commit es un renombrado`() {
        val d = dir("criterio-rename")
        val bytes = bytesDe(guionAvanzado(), indice = 4, ordinal = 2uL, n = 2)
        val store = FileTransmitUnitStore(d)
        store.writeAhead(bytes)

        val inodoPendiente = inodoDe(File(d, FileTransmitUnitStore.PENDIENTE))
        store.commit()
        val inodoConfirmada = inodoDe(File(d, FileTransmitUnitStore.CONFIRMADA))

        // --- 1. EL FICHERO IDENTICO CAMBIA DE NOMBRE -------------------------
        //
        // Un `rename` mueve el MISMO fichero: lo que cambia es el nombre. Una
        // copia crearia un fichero NUEVO y dejaria el viejo hasta el borrado, y
        // en esa ventana un corte se lleva las dos copias o ninguna.
        assertEquals(
            inodoPendiente, inodoConfirmada,
            "el commit es un renombrado: el hueco confirmado es el MISMO fichero que estaba pendiente",
        )
        assertFalse(
            File(d, FileTransmitUnitStore.PENDIENTE).exists(),
            "y no queda la copia de origen: si quedara, esto seria una copia y no un renombrado",
        )
        assertFalse(elTemporal(d).exists(), "ni un temporal de paso")
        assertContentEquals(
            bytes, File(d, FileTransmitUnitStore.CONFIRMADA).readBytes(),
            "el contenido es la unidad, entera",
        )

        // --- 2. Y EL CONTROL QUE LE DA SENTIDO A LO ANTERIOR -----------------
        //
        // Sin esta comprobacion, la de arriba pasaria tambien con un medio que
        // copiase: hay que ver medido que una copia de verdad cambia el inodo.
        File(d, FileTransmitUnitStore.CONFIRMADA).copyTo(File(d, "control.bin"))
        val inodoDeLaCopia = inodoDe(File(d, "control.bin"))
        assertNotEquals(
            inodoConfirmada, inodoDeLaCopia,
            "una copia de verdad tiene OTRO inodo: por eso el renombrado se distingue de la copia",
        )
        assertNotEquals(
            inodoPendiente, inodoDeLaCopia,
            "y no es que las unidades se parecieran: la copia parte del inodo confirmado",
        )
    }

    @Test
    @DisplayName("MEDIO-13-D un fallo justo antes del reemplazo conserva la unidad anterior")
    fun `MEDIO-13-D un fallo antes del reemplazo no destruye nada`() {
        // --- 1. LA PROMOCION IMPOSIBLE NO DESTRUYE NADA ----------------------
        //
        // El hueco confirmado es un DIRECTORIO: un `rename` de un fichero sobre
        // un directorio no se puede hacer. El fallo ocurre JUSTO antes del
        // reemplazo, y no hace falta un corte para que ocurra: es la
        // operacion real del medio la que falla.
        val d = dir("criterio-promocion-imposible")
        val vieja = bytesDe(guionAvanzado(), indice = 4, ordinal = 2uL, n = 2)
        val nueva = bytesDe(guion())
        assertFalse(
            vieja.contentEquals(nueva),
            "la unidad anterior y la nueva son DISTINTAS, o este caso no mide nada",
        )
        val store = FileTransmitUnitStore(d)
        store.writeAhead(vieja)
        store.commit()
        store.writeAhead(nueva)
        val inodoDeLoPendiente = inodoDe(File(d, FileTransmitUnitStore.PENDIENTE))

        val danado = File(d, FileTransmitUnitStore.CONFIRMADA)
        danado.delete()
        Files.createDirectory(danado.toPath())

        val e = assertThrows<TransmitStoreFailure> { store.commit() }
        assertEquals(TransmitStage.COMMIT, e.stage, "el fallo es del COMMIT")
        assertTrue(
            Files.isDirectory(danado.toPath()),
            "el hueco confirmado NO se ha sobrescrito ni se ha borrado: un fallo del medio no puede " +
                "destruir la unica copia",
        )
        assertEquals(
            inodoDeLoPendiente, inodoDe(File(d, FileTransmitUnitStore.PENDIENTE)),
            "y lo pendiente es el MISMO fichero: una promocion fallida no lo reescribe ni lo destruye",
        )
        assertContentEquals(nueva, store.readBack(), "la unidad nueva esta a salvo y se puede reintentar")
        assertTrue(store.hasPending(), "y sigue pendiente de confirmar")
    }

    @Test
    @DisplayName("MEDIO-13-E con la unidad ANTERIOR en pie, un commit imposible no la destruye")
    fun `MEDIO-13-E el estado anterior sobrevive al fallo del commit`() {
        // El fallo se provoca sobre el MEDIO REAL: se le quita el permiso de
        // escritura al directorio, con lo que la promocion ya no se puede
        // hacer. Sin dobles y sin ganchos: lo que falla es la operacion del
        // sistema de ficheros que de verdad publica la unidad.
        val d = dir("criterio-sin-permiso")
        val vieja = bytesDe(guionAvanzado(), indice = 4, ordinal = 2uL, n = 2)
        val nueva = bytesDe(guion())
        assertFalse(vieja.contentEquals(nueva), "las dos unidades son DISTINTAS, o esto no mide nada")

        val store = FileTransmitUnitStore(d)
        store.writeAhead(vieja)
        store.commit()
        val inodoDeLaAnterior = inodoDe(File(d, FileTransmitUnitStore.CONFIRMADA))
        store.writeAhead(nueva)
        val inodoDeLaNueva = inodoDe(File(d, FileTransmitUnitStore.PENDIENTE))
        assertContentEquals(vieja, store.readCommitted(), "medio preparado: la unidad anterior esta en pie")

        // Antes de medir nada se COMPRUEBA que el entorno impone el permiso: en
        // un `root` la promocion se haria y la prueba pasaria sin medir nada.
        val prueba = d.toPath().resolve("permiso.prueba")
        d.setWritable(false, false)
        val imponePermisos = runCatching { Files.createFile(prueba) }
        d.setWritable(true, false)
        Files.deleteIfExists(prueba)
        assumeTrue(
            imponePermisos.isFailure,
            "el entorno NO impone permisos de escritura: esta prueba pasaria sin medir nada",
        )

        d.setWritable(false, false)
        try {
            val e = assertThrows<TransmitStoreFailure> { store.commit() }
            assertEquals(TransmitStage.COMMIT, e.stage, "el fallo es del COMMIT")

            // La unidad ANTERIOR sigue siendo la vigente: byte a byte y con el
            // MISMO fichero. No se ha reescrito, no se ha movido y no se ha
            // borrado: el estado anterior no se destruye.
            assertContentEquals(
                vieja, store.readCommitted(),
                "la unidad anterior NO se destruye ante un fallo del commit: sigue siendo la vigente",
            )
            assertEquals(
                inodoDeLaAnterior, inodoDe(File(d, FileTransmitUnitStore.CONFIRMADA)),
                "y es el MISMO fichero, intacto: no se ha vuelto a escribir ni a copiar",
            )
            assertContentEquals(nueva, store.readBack(), "y lo pendiente sigue pendiente, a salvo")
            assertEquals(
                inodoDeLaNueva, inodoDe(File(d, FileTransmitUnitStore.PENDIENTE)),
                "el mismo fichero pendiente: la promocion se puede reintentar",
            )
            assertTrue(store.hasPending(), "y sigue habiendolo")
        } finally {
            d.setWritable(true, false)
        }

        // Y al reintentar con el medio en condiciones, la promocion se hace
        // ENTERA y de una vez: el hueco cambia de fichero por el renombrado
        // del fichero que ya estaba ahi, no por una copia de el.
        store.commit()
        assertContentEquals(nueva, store.readCommitted(), "el reintento publica la unidad nueva, entera")
        assertEquals(
            inodoDeLaNueva, inodoDe(File(d, FileTransmitUnitStore.CONFIRMADA)),
            "con el renombrado del MISMO fichero pendiente: la unidad anterior no llego a copiarse",
        )
        assertFalse(store.hasPending(), "y no queda nada pendiente")
    }

    // ===================================================================
    // UTILIDADES DE APOYO
    // ===================================================================

    /**
     * La identidad del FICHERO en el disco: lo que un `rename` conserva y una
     * copia no.
     *
     * Es la unica forma de distinguir las dos cosas desde FUERA del medio, sin
     * un gancho de corte dentro de el: el nombre lo cambian el `rename` y la
     * copia, pero el inodo solo lo conserva el `rename`.
     */
    private fun inodoDe(f: File): String = assertNotNull(
        Files.readAttributes(f.toPath(), BasicFileAttributes::class.java).fileKey(),
        "el sistema de ficheros no da identidad a '${f.name}': no se puede comprobar nada sobre el renombrado",
    ).toString()

    /**
     * Las lineas de CODIGO de un archivo, con su numero.
     *
     * Se salta la prosa porque un KDoc que explica la invariante es la
     * EXPLICACION de la frontera, no su infraccion.
     */
    private fun codigoDe(archivo: File): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var enBloque = false
        archivo.readLines().forEachIndexed { i, linea ->
            val t = linea.trim()
            if (enBloque) {
                if (t.contains("*/")) enBloque = false
                return@forEachIndexed
            }
            when {
                t.startsWith("/*") -> if (!t.contains("*/")) enBloque = true
                t.startsWith("*") || t.startsWith("//") -> Unit
                else -> out += (i + 1) to linea
            }
        }
        return out
    }
}
