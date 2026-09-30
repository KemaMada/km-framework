package com.keymessage.core.transmit

import com.keymessage.core.crypto.Hash
import com.keymessage.core.crypto.HashImpl
import com.keymessage.core.crypto.Kdf
import com.keymessage.core.crypto.X25519
import com.keymessage.core.crypto.X25519KeyPair
import com.keymessage.core.crypto.provider.BcChaCha20Poly1305
import com.keymessage.core.crypto.provider.BcHkdfSha256
import com.keymessage.core.crypto.provider.BcX25519
import com.keymessage.core.km52.BinaryKm52UnitCodec
import com.keymessage.core.km52.ChainUseOrdinal
import com.keymessage.core.km52.Km52BadMagic
import com.keymessage.core.km52.Km52ChecksumMismatch
import com.keymessage.core.km52.Km52FormatException
import com.keymessage.core.km52.Km52RetentionLimits
import com.keymessage.core.km52.Km52Spec
import com.keymessage.core.km52.Km52Truncated
import com.keymessage.core.km52.Km52Unit
import com.keymessage.core.km52.OutboundDeliveryState
import com.keymessage.core.km52.OutboundRecord
import com.keymessage.core.km52.ReceiveChainRetention
import com.keymessage.core.km52.RetentionBudgetExceeded
import com.keymessage.core.km52.RetentionRule
import com.keymessage.core.messaging.DeliveryRecordEntry
import com.keymessage.core.messaging.DeliveryRecordTable
import com.keymessage.core.messaging.FrameIdentity
import com.keymessage.core.model.MessageId
import com.keymessage.core.ratchet.DoubleRatchetSession
import com.keymessage.core.ratchet.DoubleRatchetSnapshot
import com.keymessage.core.sf.SecureFrameProtector
import com.keymessage.core.sf.SecureFrameSpec
import com.keymessage.core.sf.SecureRatchetProtocol
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/* ==================================================================== *
 * UTILIDADES DE BYTES A NIVEL DE ARCHIVO.
 *
 * Fuera de la clase de test porque las clases anidadas que arman las
 * unidades NO ven los miembros de su clase externa, y su error al no
 * verlos es el mismo "unresolved reference" que el de un tipo que no
 * existe: dos fallos que se confunden.
 * ==================================================================== */

private fun le16(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

private fun le32(b: ByteArray, off: Int): Int {
    var v = 0L
    for (i in 3 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
    return v.toInt()
}

private fun putLe16(b: ByteArray, off: Int, v: Int) {
    b[off] = (v and 0xFF).toByte()
    b[off + 1] = ((v shr 8) and 0xFF).toByte()
}

private fun putLe32(b: ByteArray, off: Int, v: Int) {
    b[off] = (v and 0xFF).toByte()
    b[off + 1] = ((v shr 8) and 0xFF).toByte()
    b[off + 2] = ((v shr 16) and 0xFF).toByte()
    b[off + 3] = ((v shr 24) and 0xFF).toByte()
}

private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

private const val COSTE_CLAVE_RETENIDA = 68

/**
 * DOBLE DE ALMACEN con inyeccion de fallos.
 *
 * ## POR QUE UN DOBLE Y NO UN FICHERO
 *
 * §8.1-G deja el medio fisico para 3Q.5.3+, y ademas un fichero real no puede
 * producir tres de los fallos que este checkpoint tiene que demostrar: un
 * `writeAhead` que devuelve exito habiendo escrito un PREFIJO, un medio que
 * devuelve una unidad DISTINTA de la escrita, y un corte de proceso entre el
 * write-ahead y el commit. Un doble si, y por eso existe.
 *
 * ## QUE FALLO MODELA CADA CONMUTADOR
 *
 * - [fallaWriteAheadAntes]: el medio rechaza la escritura. No hay nada
 *   escrito, ni pendiente ni confirmado.
 * - [fallaWriteAheadDespues]: el medio acepta, escribe un PREFIJO y falla. Lo
 *   que hay en el medio no es una unidad: exactamente el caso en el que
 *   confirmar sin verificar deja el estado logico adelantado sobre bytes
 *   inservibles.
 * - [writeAheadTruncadoYExito]: el medio escribe un PREFIJO y devuelve EXITO.
 *   Es el caso PEOR, porque no hay ningun fallo que detectar: solo la
 *   verificacion puede verlo, y por eso es el que separa "escribir" de
 *   "comprobar".
 * - [readBackNulo]: el medio dice que no tiene nada.
 * - [corruptReadBack]: el medio devuelve los bytes escritos con UN byte del
 *   CUERPO tocado. La FORMA de la unidad sigue siendo valida, asi que una
 *   comparacion de longitudes no lo ve.
 * - [unidadSustitutaEnReadBack]: el medio devuelve OTRA unidad valida, con su
 *   checksum correcto y describiendo otro estado.
 * - [fallaCommit]: el medio no puede promover a confirmado.
 * - [corteDeProceso]: el rollback NO se ejecuta. Modela el proceso muerto: los
 *   bytes ya estan en el medio y nadie va a limpiarlos.
 */
class FaultInjectingTransmitStore : TransmitUnitStore {

    var fallaWriteAheadAntes: Boolean = false
    var fallaWriteAheadDespues: Boolean = false
    var writeAheadTruncadoYExito: Boolean = false
    var fallaReadBack: Boolean = false
    var fallaCommit: Boolean = false
    var readBackNulo: Boolean = false
    var corruptReadBack: Boolean = false
    var unidadSustitutaEnReadBack: ByteArray? = null

    /** Si es `true`, [discardPending] no hace nada: el proceso ya no existe. */
    var corteDeProceso: Boolean = false

    private var pendiente: ByteArray? = null
    private var confirmado: ByteArray? = null

    /** Lo que se ha escrito, en orden. Diagnostico. */
    val escrituras = mutableListOf<ByteArray>()

    var commits: Int = 0
        private set

    var retiros: Int = 0
        private set

    /**
     * El ULTIMO prefijo que el medio dejo al fallar por la mitad.
     *
     * Se conserva a proposito: el journal retira lo pendiente en cuanto ve el
     * fallo, asi que sin esta copia el test no podria comprobar que lo que
     * queda en el medio no es una unidad —que es justamente la mitad
     * interesante del escenario.
     */
    var ultimoPrefijo: ByteArray? = null
        private set

    /** Deja el medio como si otro proceso hubiera escrito y confirmado. */
    fun sembrarConfirmado(bytes: ByteArray) {
        pendiente = null
        confirmado = bytes.copyOf()
    }

    override fun writeAhead(bytes: ByteArray) {
        if (writeAheadTruncadoYExito) {
            // El caso PEOR: el medio devuelve EXITO habiendo escrito un
            // prefijo. Nada falla, nada avisa, y la unidad no esta.
            pendiente = bytes.copyOf(bytes.size / 2)
            escrituras += bytes.copyOf()
            return
        }
        if (fallaWriteAheadAntes) {
            throw TransmitStoreFailure(TransmitStage.WRITE_AHEAD, "el medio no acepta la escritura")
        }
        if (fallaWriteAheadDespues) {
            // PREFIJO: el medio deja bytes y aun asi falla.
            pendiente = bytes.copyOf(bytes.size / 2)
            ultimoPrefijo = pendiente?.copyOf()
            escrituras += bytes.copyOf()
            throw TransmitStoreFailure(TransmitStage.WRITE_AHEAD, "el medio fallo a mitad de la escritura")
        }
        pendiente = bytes.copyOf()
        escrituras += bytes.copyOf()
    }

    override fun readBack(): ByteArray? {
        if (fallaReadBack) {
            throw TransmitStoreFailure(TransmitStage.READ_BACK, "el medio no deja leer")
        }
        if (readBackNulo) return null
        unidadSustitutaEnReadBack?.let { return it.copyOf() }
        val base = pendiente ?: return null
        if (!corruptReadBack) return base.copyOf()
        val out = base.copyOf()
        // Un byte del CUERPO, no del header: la forma de la unidad sigue
        // valida y lo unico que la delata es el checksum.
        val donde = Km52Spec.HEADER_LENGTH + 3
        out[donde] = (out[donde] + 1).toByte()
        return out
    }

    override fun readCommitted(): ByteArray? = confirmado?.copyOf()

    override fun commit() {
        if (fallaCommit) {
            throw TransmitStoreFailure(TransmitStage.COMMIT, "el medio no pudo confirmar")
        }
        confirmado = pendiente
        pendiente = null
        commits++
    }

    override fun discardPending() {
        retiros++
        if (corteDeProceso) return
        pendiente = null
    }

    override fun hasPending(): Boolean = pendiente != null

    /** Lo pendiente sin confirmar. Diagnostico. */
    fun pendienteBruto(): ByteArray? = pendiente?.copyOf()
}

/* ==================================================================== *
 * DOBLE DE ALMACEN QUE CORRUPPE LO PERSISTIDO.
 *
 * 3Q.5.2b-B, segundo guardian de `verify-before-commit`.
 *
 * ==================================================================== *
 * POR QUE UN SEGUNDO DOBLE Y NO BANDERAS EN `FaultInjectingTransmitStore`
 *
 * Porque los dos dobles NO MODELAN LO MISMO. `FaultInjectingTransmitStore`
 * inyecta FALLOS: hay banderas para que la escritura no acepte, para que falle a
 * medias, para que no deje leer. Aqui no hay ningun fallo posible:
 * [TransmitUnitStore.writeAhead] SIEMPRE tiene exito, siempre deja los bytes, y
 * [TransmitUnitStore.commit] SIEMPRE funciona. Lo unico que se rompe es lo que el
 * medio DEVUELVE al releer.
 *
 * Esa es la forma que importa para esta propiedad, porque es la unica que no
 * anuncia nada: no hay excepcion que capturar, no hay codigo de retorno que
 * mirar, no hay marca que el escritor pueda consultar. Escribo y devuelve
 * exito. Lo unico que la ve es comparar lo que hay con lo que se queria.
 *
 * ==================================================================== *
 * POR QUE UN `enum` Y NO MAS BANDERAS
 *
 * Los modos son MUTUAMENTE EXCLUYENTES y describen LUGARES de corrupcion
 * distintos: el prefijo, el cuerpo, el checksum, la identidad del envio, la
 * identidad del estado, la cola, el vacio. Con banderas, un doble puede acabar
 * en un estado imposible —truncado Y con el checksum roto, a la vez— y un caso
 * que combinase dos banderas no sabria que esta probando. Con un `enum`, cada
 * caso es UNA corrupcion, y el guion lo dice en el nombre.
 *
 * ==================================================================== *
 * LO QUE ESTE DOBLE DEJA VER
 *
 * - [devuelto]: lo que la ULTIMA [readBack] devolvio. El test lo usa para
 *   PROBAR que la corrupcion era real ANTES de exigir que se detectara. Un caso
 *   que dice corromper y no corrompe pasaria por la razon equivocada, que es el
 *   unico fallo que un arnes de este tipo no puede permitirse.
 * - [escritas]: los bytes que se le dieron, byte a byte.
 * - [lecturas]: cuantas veces se consulto lo pendiente. NO es una comprobacion de
 *   ORDEN —este doble no registra el orden de las llamadas, precisamente porque
 *   el segundo guardian no puede depender de el—, sino la evidencia de que lo
 *   persistido llego a SER MIRADO por alguien. Ahi esta el fallo si nadie lo
 *   mira: ahi se cuela el estado.
 * ==================================================================== */

/** DONDE, dentro de lo que el medio devuelve, se rompe la unidad. */
enum class ModoDeCorrupcion {
    /** Control: el medio devuelve exactamente los bytes que se le dieron. */
    NINGUNO,

    /** Solo la mitad. La escritura ha tenido exito y no ha habido ningun aviso. */
    PREFIJO_TRUNCADO,

    /**
     * Un byte del ULTIMO campo de la unidad, el ciphertext.
     *
     * La longitud del campo no cambia, asi que la FORMA de la unidad sigue
     * siendo valida y lo unico que la delata es la integridad.
     */
    CUERPO_TOCADO,

    /**
     * Los ultimos bytes del SHA-256 del cuerpo.
     *
     * El cuerpo queda INTACTO: es la corrupcion que solo existe para la
     * integridad, y la que una comprobacion de longitud no puede ver.
     */
    CHECKSUM_ROTO,

    /** Otra unidad VALIDA, de la MISMA longitud, con otro envio dentro. */
    OTRO_ENVIO,

    /** Otra unidad VALIDA, de la MISMA longitud, con otro estado de ratchet. */
    OTRO_ESTADO,

    /** La escritura ha tenido exito y el medio dice que no tiene nada. */
    NADA,

    /** La unidad entera y un byte de cola. */
    COLA_DE_BASURIA,
}

class CorruptingTransmitStore(
    /**
     * Lo que el medio devolvera en [ModoDeCorrupcion.OTRO_ENVIO] y en
     * [ModoDeCorrupcion.OTRO_ESTADO].
     */
    private val sustituta: ByteArray? = null,
) : TransmitUnitStore {

    var modo: ModoDeCorrupcion = ModoDeCorrupcion.NINGUNO

    /** Los bytes entregados a [writeAhead], en orden. */
    val escritas = mutableListOf<ByteArray>()

    var lecturas: Int = 0
        private set

    var commits: Int = 0
        private set

    var retiros: Int = 0
        private set

    /** Lo que la ULTIMA [readBack] devolvio, o `null` si no devolvio nada. */
    var devuelto: ByteArray? = null
        private set

    /**
     * Lo que [commit] ha promovido a confirmado, o `null` si no ha habido commit.
     *
     * Es distinto de [escritas] a proposito: si los dos no coinciden, el medio
     * ha confirmado ALGO QUE NO ERA lo que se le pidio escribir.
     */
    var promovido: ByteArray? = null
        private set

    private var pendiente: ByteArray? = null
    private var confirmado: ByteArray? = null

    override fun writeAhead(bytes: ByteArray) {
        // NUNCA falla, y NUNCA falla a medias. Ver la cabecera del doble: un
        // fallo de escritura seria otro defecto, y este doble no lo modela.
        escritas += bytes.copyOf()
        pendiente = bytes.copyOf()
    }

    override fun readBack(): ByteArray? {
        lecturas++
        devuelto = cuandoSeLee()
        return devuelto?.copyOf()
    }

    override fun readCommitted(): ByteArray? = confirmado?.copyOf()

    override fun commit() {
        // Un `commit` de este medio SIEMPRE funciona. Si el defecto que se
        // quisiera modelar estuviera aqui, el caso estaria probando otra cosa.
        promovido = pendiente?.copyOf()
        confirmado = pendiente
        pendiente = null
        commits++
    }

    override fun discardPending() {
        retiros++
        pendiente = null
    }

    override fun hasPending(): Boolean = pendiente != null

    /** Lo pendiente sin confirmar. Diagnostico. */
    fun pendienteBruto(): ByteArray? = pendiente?.copyOf()

    private fun cuandoSeLee(): ByteArray? = when (modo) {
        ModoDeCorrupcion.NADA -> null
        ModoDeCorrupcion.OTRO_ENVIO, ModoDeCorrupcion.OTRO_ESTADO ->
            requireNotNull(sustituta) { "el modo $modo necesita la unidad que el medio va a devolver" }
        else -> pendiente?.let { deformar(it) }
    }

    /** Aplica la corrupcion. La escritura, a estas alturas, ya ha tenido exito. */
    private fun deformar(base: ByteArray): ByteArray {
        val out = base.copyOf()
        when (modo) {
            ModoDeCorrupcion.NINGUNO,
            ModoDeCorrupcion.NADA,
            ModoDeCorrupcion.OTRO_ENVIO,
            ModoDeCorrupcion.OTRO_ESTADO,
            -> Unit

            ModoDeCorrupcion.PREFIJO_TRUNCADO ->
                return out.copyOf(out.size / 2)

            ModoDeCorrupcion.CUERPO_TOCADO -> {
                // El ultimo byte antes del checksum es el ULTIMO del ciphertext:
                // la longitud del campo no cambia, la forma sigue siendo valida y
                // lo unico que la delata es la integridad.
                val donde = out.size - Km52Spec.CHECKSUM_LENGTH - 1
                out[donde] = (out[donde] + 1).toByte()
            }

            ModoDeCorrupcion.CHECKSUM_ROTO -> {
                out[out.size - 1] = (out[out.size - 1] + 1).toByte()
            }

            ModoDeCorrupcion.COLA_DE_BASURIA -> {
                val conCola = out.copyOf(out.size + 1)
                conCola[out.size] = 0x7A
                return conCola
            }
        }
        return out
    }
}

/* ==================================================================== */

class SecureTransmitJournalTest {

    private lateinit var x25519: X25519
    private lateinit var kdf: Kdf
    private lateinit var protector: SecureFrameProtector
    private lateinit var hash: Hash

    private val rootKey = ByteArray(32) { (it + 1).toByte() }

    @BeforeEach
    fun setUp() {
        x25519 = BcX25519()
        kdf = BcHkdfSha256()
        protector = SecureFrameProtector(BcChaCha20Poly1305(), kdf)
        hash = HashImpl()
    }

    private fun codec() = BinaryKm52UnitCodec(x25519, hash)

    // ===================================================================
    // GUION CRIPTOGRAFICO
    //
    // El mismo arranque que `Km52CodecTest` y `DoubleRatchetSnapshotTest`,
    // porque las pruebas de este checkpoint necesitan sesiones REALES: un
    // snapshot sintetico no produce un ciphertext comparable con el de nadie,
    // y la intactitud se demuestra con el cable.
    // ===================================================================

    private fun session(dhSelf: X25519KeyPair, dhRemote: ByteArray?): DoubleRatchetSession =
        DoubleRatchetSession(
            rootKey = rootKey,
            dhSelf = dhSelf,
            dhRemote = dhRemote,
            sendChainKey = ByteArray(32) { (it * 3).toByte() },
            receiveChainKey = ByteArray(32) { (it * 5).toByte() },
            x25519 = x25519,
            kdf = kdf,
        )

    private inner class Wire {
        val bobDh = x25519.generateKeyPair()
        val aliceSession = session(x25519.generateKeyPair(), bobDh.publicKey)
        val bobSession = session(bobDh, null)
        val alice = SecureRatchetProtocol(aliceSession, protector)
        val bob = SecureRatchetProtocol(bobSession, protector)

        init {
            aliceSession.initiateEpoch()
        }
    }

    private fun exchange(from: SecureRatchetProtocol, to: SecureRatchetProtocol, text: String): ByteArray {
        val wire = from.encrypt(text.toByteArray())
        val r = to.decrypt(wire)
        assertTrue(r is SecureRatchetProtocol.DecryptResult.Ok, "esperaba Ok en '$text', fue $r")
        return wire
    }

    /**
     * Deja a BOB con DOS cadenas de recepcion y dos frames de la epoca VIEJA
     * sin entregar, que solo podran descifrarse desde claves retenidas.
     *
     * @return los cinco frames de Alice, en el orden en que se emitieron.
     */
    private fun dosEpochs(w: Wire): List<ByteArray> {
        val a0 = exchange(w.alice, w.bob, "a0")
        val a1 = w.alice.encrypt("a1".toByteArray())   // N=1, epoca 1: RETENIDA
        val a2 = w.alice.encrypt("a2".toByteArray())   // N=2, epoca 1: RETENIDA
        exchange(w.bob, w.alice, "b0")                 // Alice rota: nueva DH
        exchange(w.bob, w.alice, "b1")
        exchange(w.bob, w.alice, "b2")
        val a3 = exchange(w.alice, w.bob, "a3")        // DH nueva: 2a cadena, PN = 3
        val a4 = w.alice.encrypt("a4".toByteArray())   // epoca 2, N = 1: PENDIENTE
        return listOf(a0, a1, a2, a3, a4)
    }

    /**
     * Deja a BOB con CUATRO cadenas de recepcion REALES y claves retenidas
     * reales en tres de ellas, y a ALICE con un frame en vuelo sin entregar.
     *
     * El guion alterna `b->a` y `a->b` porque el ratchet DH es reactivo: BOB
     * rota su DH cuando ve una DH nueva de ALICE, y ALICE rota cuando ve una
     * DH nueva de BOB. Sin el `b->a` intermedio, ALICE no rotaria y el
     * `a->b` siguiente no abriria una epoca nueva.
     */
    private fun cuatroEpochs(w: Wire): List<ByteArray> {
        val a0 = exchange(w.alice, w.bob, "a0")
        val a1 = w.alice.encrypt("a1".toByteArray())   // epoca 1, N=1: RETENIDA
        val a2 = w.alice.encrypt("a2".toByteArray())   // epoca 1, N=2: RETENIDA
        exchange(w.bob, w.alice, "b0")                 // Alice -> epoca 2
        val a3 = exchange(w.alice, w.bob, "a3")        // cadena 2; salta [1,2] de la 1
        val a3r = w.alice.encrypt("a3r".toByteArray()) // epoca 2, N=1: RETENIDA
        exchange(w.bob, w.alice, "b1")                 // Alice -> epoca 3
        val a4 = exchange(w.alice, w.bob, "a4")        // cadena 3; salta [1] de la 2
        val a4r = w.alice.encrypt("a4r".toByteArray()) // epoca 3, N=1: RETENIDA
        exchange(w.bob, w.alice, "b2")                 // Alice -> epoca 4
        val a5 = exchange(w.alice, w.bob, "a5")        // cadena 4; salta [1] de la 3
        val a5r = w.alice.encrypt("a5r".toByteArray()) // epoca 4, N=1: EN VUELO
        return listOf(a0, a1, a2, a3, a3r, a4, a4r, a5, a5r)
    }

    /**
     * Deja a BOB con cinco cadenas y 4000 claves retenidas REALES.
     *
     * Cuatro rondas de mil mensajes sin entregar cada una. Es caro a proposito:
     * el presupuesto global son 200 KiB y 3011 claves, asi que ninguna sesion
     * pequena lo cruza. Las retenidas las produce el ratchet de verdad, con su
     * `MAX_SKIP` y su `SKIP_LIMIT_EXCEEDED`, no un generador de claves.
     */
    private fun cuatroCadenasLlenas(w: Wire) {
        exchange(w.alice, w.bob, "a0")                  // cadena 1
        for (ronda in 1..4) {
            repeat(1000) { w.alice.encrypt("x".toByteArray()) }
            exchange(w.bob, w.alice, "b$ronda")         // Alice rota: nueva DH
            exchange(w.alice, w.bob, "a$ronda")         // cadena 1+ronda, salta 1000
        }
    }

    private fun det(seed: Int) = X25519KeyPair(
        ByteArray(32) { ((it + seed) and 0xFF).toByte() },
        x25519.publicKey(ByteArray(32) { ((it + seed) and 0xFF).toByte() }),
    )

    // ===================================================================
    // ATAJOS
    // ===================================================================

    private fun mensaje(n: Int) = MessageId.from(UUID(0x0102030405060700L + n, 0x090a0b0c0d0e0f00L + n))

    private fun envio(frame: ByteArray, ordinal: ULong, n: Int) = PreparedSend(
        messageId = mensaje(n),
        wireFrame = frame,
        deliveryState = OutboundDeliveryState.PENDIENTE,
        createdOrdinal = ordinal,
    )

    private fun journal(
        sesion: DoubleRatchetSession,
        store: TransmitUnitStore,
        libro: ChainRetentionBook = ChainRetentionBook(),
    ) = SecureTransmitJournal(sesion, store, codec(), TransmitLedger(), libro)

    /** Tabla de retencion que cubre una foto, en orden canonico de `chainId`. */
    private fun retencionDe(foto: DoubleRatchetSnapshot, ordinal: (Int) -> ULong = { it.toULong() }) =
        ReceiveChainRetention(
            foto.receiveChains
                .mapIndexed { i, c -> ChainUseOrdinal(c.chainId, ordinal(i)) }
                .sortedBy { hex(it.chainId) },
        )

    private fun registroDe(frame: ByteArray, messageId: MessageId, ordinal: ULong) = OutboundRecord(
        recordVersion = PreparedSend.OUTBOUND_RECORD_VERSION,
        deliveryState = OutboundDeliveryState.PENDIENTE,
        messageId = messageId,
        frameIdentity = FrameIdentity.fromWire(frame),
        createdOrdinal = ordinal,
        ciphertext = frame.copyOfRange(SecureFrameSpec.CIPHERTEXT_OFFSET, frame.size),
    )

    /**
     * Una sesion CLONADA de [original], con el mismo estado y las mismas
     * primitivas.
     *
     * Es el patron de control de `KM52-01b` y `KM52-10`: el estado intacto se
     * demuestra con el CIPHERTEXT de un `encrypt()` posterior, nunca con
     * `stateFingerprint()`, porque la huella no cubre el escalar DH privado.
     */
    private fun clonDe(s: DoubleRatchetSession) = DoubleRatchetSession.restore(s.snapshot(), x25519, kdf)

    /** Cifrado de un frame de control con una sesion dada. */
    private fun frameDeControl(s: DoubleRatchetSession) =
        SecureRatchetProtocol(s, protector).encrypt("ctrl".toByteArray())

    /** Las claves retenidas de una sesion, como `(chainId en hex, N)`. */
    private fun retenidasDe(s: DoubleRatchetSession): List<Pair<String, UInt>> =
        s.snapshot().receiveChains.flatMap { c -> c.ratchet.skipped.keys.map { hex(c.chainId) to it.second } }

    // ===================================================================
    // KM52B-01 — El camino feliz completo
    // ===================================================================

    @Test
    @DisplayName("KM52B-01 la escritura completa termina en commit y el envio queda durable")
    fun `KM52B-01 escritura completa commit`() {
        val w = Wire()
        val a0 = dosEpochs(w)[0]
        val store = FaultInjectingTransmitStore()
        val j = journal(w.bobSession, store)
        val control = clonDe(w.bobSession)

        // El envio que se persiste: el frame REAL que produjo ALICE, con su
        // identidad y su ciphertext ledos de sus propios bytes.
        val registro = j.persistir(envio(a0, ordinal = 3uL, n = 1))

        // 1. Se ha escrito UNA vez, se ha confirmado UNA vez y no queda nada
        //    pendiente: el diagrama entero recorrido.
        assertEquals(1, store.escrituras.size, "una sola escritura")
        assertEquals(1, store.commits, "un solo commit")
        assertEquals(0, store.retiros, "ninguna retirada: no hubo fallo")
        assertFalse(store.hasPending(), "no puede quedar pendiente tras confirmar")
        assertNull(store.pendienteBruto(), "el medio queda limpio")

        // 2. Lo confirmado es la MISMA unidad que se escribio.
        val confirmado = assertNotNull(store.readCommitted())
        assertContentEquals(store.escrituras[0], confirmado, "lo confirmado es lo escrito")

        // 3. La unidad releida describe el estado criptografico ACTUAL de BOB y
        //    el envio recien hecho durable.
        val unidad = codec().deserialize(confirmado)
        assertContentEquals(
            w.bobSession.snapshot().rootKey, unidad.snapshot.rootKey,
            "la unidad lleva la raiz del ratchet, no una foto anterior",
        )
        assertContentEquals(
            w.bobSession.snapshot().dhSelf.privateKeyBytes(),
            unidad.snapshot.dhSelf.privateKeyBytes(),
            "y el escalar DH propio, que es la mitad que no se puede regenerar",
        )
        assertEquals(OutboundDeliveryState.PENDIENTE, registro.deliveryState, "sale PENDIENTE (§8.1-F)")
        assertEquals(mensaje(1), registro.messageId, "el messageId es el del envio")
        assertEquals(FrameIdentity.fromWire(a0), registro.frameIdentity, "la identidad se lee del frame")
        assertEquals(3uL, registro.createdOrdinal, "el ordinal de creacion se conserva")

        // 4. Y SOLO entonces el estado logico de transmision ha avanzado.
        assertTrue(j.ledger.esDurable(mensaje(1)), "el envio es durable")
        assertEquals(
            OutboundDeliveryState.PENDIENTE, j.ledger.estadoDe(mensaje(1)),
            "y su estado de entrega sigue siendo PENDIENTE: persistir no entrega",
        )
        assertEquals(1, j.ledger.tamanho(), "un solo registro en el libro")

        // 5. La sesion NO se ha movido por persistir, con criptografia.
        assertContentEquals(
            frameDeControl(control), frameDeControl(w.bobSession),
            "persistir no puede mover el ratchet ni un byte",
        )
    }

    // ===================================================================
    // KM52B-02 — Fallo ANTES de escribir
    // ===================================================================

    @Test
    @DisplayName("KM52B-02 un fallo antes de escribir deja el estado logico intacto")
    fun `KM52B-02 fallo antes de escribir`() {
        val w = Wire()
        val a0 = dosEpochs(w)[0]
        val store = FaultInjectingTransmitStore().apply { fallaWriteAheadAntes = true }
        val j = journal(w.bobSession, store)
        val control = clonDe(w.bobSession)
        val retenidasAntes = retenidasDe(w.bobSession)

        val e = assertThrows<TransmitStoreFailure> { j.persistir(envio(a0, ordinal = 1uL, n = 1)) }
        assertEquals(TransmitStage.WRITE_AHEAD, e.stage, "el fallo es de escritura, no de verificacion")

        // 1. NADA se ha escrito, y nada se ha confirmado.
        assertEquals(0, store.escrituras.size, "el medio no recibio nada: fallo antes de escribir")
        assertEquals(0, store.commits, "y no puede haber commit")
        assertNull(store.readCommitted(), "no hay unidad confirmada")
        assertFalse(store.hasPending(), "no queda nada pendiente")

        // 2. El estado LOGICO de transmision no ha avanzado: el envio no es
        //    durable, y por eso sigue sin estado de entrega.
        assertFalse(j.ledger.esDurable(mensaje(1)), "un envio no persistido no es durable")
        assertNull(j.ledger.estadoDe(mensaje(1)), "y no tiene estado de entrega")
        assertEquals(0, j.ledger.tamanho(), "el libro esta vacio")

        // 3. La sesion intacta, con comportamiento criptografico.
        assertEquals(retenidasAntes, retenidasDe(w.bobSession), "ninguna retenida puede haber cambiado")
        assertContentEquals(
            frameDeControl(control), frameDeControl(w.bobSession),
            "la sesion no se ha movido un byte",
        )

        // 4. Y el reintento, con el medio sano, funciona: el fallo fue del
        //    medio y no del estado.
        store.fallaWriteAheadAntes = false
        j.persistir(envio(a0, ordinal = 1uL, n = 1))
        assertTrue(j.ledger.esDurable(mensaje(1)), "reintentado con el medio sano, si es durable")
        assertEquals(1, store.commits, "y el commit ocurre UNA vez, en el reintento")
    }

    // ===================================================================
    // KM52B-03 — Fallo DURANTE la escritura
    // ===================================================================

    @Test
    @DisplayName("KM52B-03 un fallo a mitad de la escritura no deja commit")
    fun `KM52B-03 fallo durante la escritura`() {
        val w = Wire()
        val a0 = dosEpochs(w)[0]
        val store = FaultInjectingTransmitStore().apply { fallaWriteAheadDespues = true }
        val j = journal(w.bobSession, store)
        val control = clonDe(w.bobSession)

        // El prefijo se captura ANTES de que el rollback lo retire, para poder
        // comprobar que lo que queda en el medio no es una unidad.
        val e = assertThrows<TransmitStoreFailure> { j.persistir(envio(a0, ordinal = 1uL, n = 1)) }
        assertEquals(TransmitStage.WRITE_AHEAD, e.stage, "el fallo es del medio al escribir")
        // El journal ya ha retirado lo pendiente, asi que el prefijo se lee de
        // la copia que el medio guardo del bytes que si llegaron a escribirse.
        val prefijo = assertNotNull(store.ultimoPrefijo, "el medio dejo un prefijo antes de fallar")

        // El medio dejo bytes, que es lo que hay que impedir que se convierta
        // en estado.
        assertEquals(0, store.commits, "PERO NO HAY COMMIT: el envio no es durable")
        assertNull(store.readCommitted(), "y no hay nada confirmado")
        assertFalse(j.ledger.esDurable(mensaje(1)), "el estado logico no avanza")

        // El rollback se ejecuto: el medio queda limpio, que es lo que permite
        // reintentar sin arrastrar el prefijo.
        assertEquals(1, store.retiros, "el pendiente se retira")
        assertFalse(store.hasPending(), "y el medio queda limpio")

        // Y el prefijo, si alguien lo promotionase, no es una unidad.
        assertTrue(
            runCatching { codec().deserialize(prefijo) }.isFailure,
            "un prefijo no es una unidad: por eso confirmar sin verificar seria confirmar basura",
        )
        assertTrue(
            prefijo.size < store.escrituras[0].size,
            "el prefijo es de verdad mas corto que la unidad",
        )

        // La sesion intacta, con criptografia.
        assertContentEquals(
            frameDeControl(control), frameDeControl(w.bobSession),
            "la sesion no se ha movido un byte",
        )
    }

    // ===================================================================
    // KM52B-04 — Fallo de checksum o de verificacion al releer
    // ===================================================================

    @Test
    @DisplayName("KM52B-04 lo releido tiene que pasar el checksum: si no, no hay commit")
    fun `KM52B-04 checksum o verificacion al releer`() {
        val w = Wire()
        val a0 = dosEpochs(w)[0]

        // --- 4a. Un byte del CUERPO cambiado. La FORMA de la unidad sigue
        //         siendo valida: mismo magic, mismos longitudes, mismo
        //         totalLen. Un totalLen distinto lo delataria y no seria el
        //         caso dificil.
        run {
            val store = FaultInjectingTransmitStore().apply { corruptReadBack = true }
            val j = journal(w.bobSession, store)
            val e = assertThrows<Km52ChecksumMismatch> { j.persistir(envio(a0, ordinal = 1uL, n = 1)) }
            assertTrue(e.message!!.contains("checksum"), "el error dice de que va: $e")
            assertEquals(0, store.commits, "un checksum que no cuadra NO se confirma")
            assertNull(store.readCommitted(), "no hay nada confirmado")
            assertFalse(j.ledger.esDurable(mensaje(1)), "y el envio no es durable")
        }

        // --- 4b. El medio dice que no tiene nada, aunque la escritura haya
        //         devuelto exito.
        run {
            val store = FaultInjectingTransmitStore().apply { readBackNulo = true }
            val j = journal(w.bobSession, store)
            assertThrows<PersistedUnitMismatch> { j.persistir(envio(a0, ordinal = 1uL, n = 1)) }
            assertEquals(0, store.commits, "escribir y no releer no es haber escrito")
            assertFalse(j.ledger.esDurable(mensaje(1)), "el envio no es durable")
        }

        // --- 4c. El medio devuelve OTRA unidad: valida, con su checksum
        //         correcto, y describiendo OTRO estado. Es el caso que el
        //         checksum NO tapa —la unidad esta perfecta— y que solo la
        //         identidad detecta.
        run {
            val ajena = unidadAjena(w, a0, n = 99, ordinal = 9uL)
            assertEquals(
                mensaje(99),
                assertNotNull(codec().deserialize(ajena).outbound, "la unidad ajena trae su envio").messageId,
                "la unidad ajena es valida",
            )

            val store = FaultInjectingTransmitStore().apply { unidadSustitutaEnReadBack = ajena }
            val j = journal(w.bobSession, store)
            val e = assertThrows<PersistedUnitMismatch> { j.persistir(envio(a0, ordinal = 1uL, n = 1)) }
            assertTrue(e.motivo.isNotEmpty(), "el motivo dice que campo no es el nuestro: ${e.motivo}")
            assertEquals(0, store.commits, "una unidad ajena no se confirma")
            assertFalse(j.ledger.esDurable(mensaje(1)), "el envio no es durable")
        }

        // --- 4d. EL CASO PEOR: el medio devuelve EXITO habiendo escrito solo
        //         un prefijo. No hay ninguna excepcion por la que detectarlo:
        //         lo unico que lo ve es releer y comparar. Es la razon de que
        //         el COMMIT vaya despues de VERIFICAR y no despues de
        //         ESCRIBIR.
        run {
            val store = FaultInjectingTransmitStore().apply { writeAheadTruncadoYExito = true }
            val j = journal(w.bobSession, store)
            val e = assertThrows<Km52Truncated> { j.persistir(envio(a0, ordinal = 1uL, n = 1)) }
            assertTrue(e.message!!.contains("truncada"), "el error dice que lo que hay no es una unidad: $e")
            assertEquals(0, store.commits, "un prefijo NO se confirma, aunque la escritura haya devuelto exito")
            assertNull(store.readCommitted(), "y no hay nada confirmado")
            assertFalse(j.ledger.esDurable(mensaje(1)), "el envio no es durable")
        }

        // --- 4e. Y el fallo de LECTURA del medio, que es distinto del de
        //         escritura y se propaga tipado.
        run {
            val store = FaultInjectingTransmitStore().apply { fallaReadBack = true }
            val j = journal(w.bobSession, store)
            val e = assertThrows<TransmitStoreFailure> { j.persistir(envio(a0, ordinal = 1uL, n = 1)) }
            assertEquals(TransmitStage.READ_BACK, e.stage, "el fallo es al releer, no al escribir")
            assertEquals(0, store.commits, "y tampoco se confirma")
        }
    }

    /** Una unidad valida de OTRA sesion, con su checksum correcto. */
    private fun unidadAjena(w: Wire, frame: ByteArray, n: Int, ordinal: ULong): ByteArray {
        val otra = Wire()
        dosEpochs(otra)
        val foto = otra.bobSession.snapshot()
        return codec().serialize(Km52Unit(foto, retencionDe(foto), registroDe(frame, mensaje(n), ordinal)))
    }

    /** El CUERPO de una unidad: todo menos el checksum final. */
    private fun cuerpoDe(unidad: ByteArray): ByteArray =
        unidad.copyOfRange(0, unidad.size - Km52Spec.CHECKSUM_LENGTH)

    /** El CHECKSUM final de una unidad: los ultimos 32 bytes. */
    private fun checksumDe(unidad: ByteArray): ByteArray =
        unidad.copyOfRange(unidad.size - Km52Spec.CHECKSUM_LENGTH, unidad.size)

    // ===================================================================
    // KM52B-04b — EL SEGUNDO GUARDIAN DE `verify-before-commit`
    // ===================================================================

    @Test
    @DisplayName("KM52B-04b segundo guardian: una unidad no verificada no llega nunca a estado")
    fun `KM52B-04b segundo guardian de verify before commit`() {
        // ---- 0. LA SESION Y EL ENVIO ------------------------------------------
        //
        // Se persiste el frame `a1`, que queda RETENIDO: nadie lo ha entregado,
        // asi que el estado que este test tiene que proteger tiene claves
        // retenidas de verdad. Un envio sin retenidas seria un estado mas pobre
        // del que la propiedad protege.
        val w = Wire()
        val (a0, a1) = dosEpochs(w)
        val bob = w.bobSession
        val foto = bob.snapshot()
        val retenidasAntes = retenidasDe(bob)
        val cadenasAntes = bob.receiveChainCount()
        val nsAntes = bob.currentSendMessageNumber()
        val pnAntes = bob.currentPreviousChainLength()
        val control = clonDe(bob)

        // ---- 1. LO QUE EL MEDIO VA A DEVOLVER EN CADA CASO ---------------------
        val tabla = ChainRetentionBook().tablaPara(foto)
        val envioReal = registroDe(a1, mensaje(1), 1uL)
        val propio = codec().serialize(Km52Unit(foto, tabla, envioReal))

        // 1a. `OTRO_ENVIO`: la MISMA foto y la MISMA tabla, con un registro de
        //     salida DISTINTO. Se construye con un `ChainRetentionBook` nuevo
        //     para que la tabla de retencion sea la misma y la unica diferencia
        //     sea la identidad del envio: si la tabla tambien diferiera, el caso
        //     probaria el campo equivocado.
        val otroEnvio = codec().serialize(
            Km52Unit(foto, tabla, registroDe(a0, mensaje(9), 4uL)),
        )

        // 1b. `OTRO_ESTADO`: el MISMO envio y un estado de ratchet DISTINTO, de
        //     otra sesion recorrida con el MISMO guion. Es la sustituta mas
        //     peligrosa de las dos: describe el envio correcto, con el
        //     ciphertext correcto, y aun asi lo que se confirmaria restauraria
        //     unas claves que no son las de esta sesion.
        val otra = Wire()
        dosEpochs(otra)
        val fotoAjena = otra.bobSession.snapshot()
        val otroEstado = codec().serialize(
            Km52Unit(fotoAjena, retencionDe(fotoAjena), envioReal),
        )

        // ---- 2. LOS ESCENARIOS SON REALES, NO ETIQUETAS ------------------------
        //
        // ANTES de exigir que una corrupcion se detecte, se exige que la
        // corrupcion EXISTA y que sea la que el caso dice. Un caso que en
        // realidad no corrompe —o que corrompe en otro sitio del que anuncia—
        // haria pasar el test por la razon equivocada, que es el unico fallo que
        // un arnes de este tipo no puede permitirse.
        val leidoPropio = codec().deserialize(propio)
        val leidoOtroEnvio = codec().deserialize(otroEnvio)
        val leidoOtroEstado = codec().deserialize(otroEstado)

        assertEquals(a0.size, a1.size, "los dos frames del guion tienen que medir lo mismo")
        assertEquals(propio.size, otroEnvio.size, "`OTRO_ENVIO` tiene que ocupar lo MISMO: si no, bastaria una comparacion de longitudes y no probaria nada")
        assertEquals(propio.size, otroEstado.size, "`OTRO_ESTADO` tiene que ocupar lo MISMO, por la misma razon")
        // `outbound` es opcional en v2: se comprueba su presencia antes de leer
        // el campo. No es semantica nueva, es el `!!` de antes con mensaje.
        val regPropio = assertNotNull(leidoPropio.outbound, "la unidad propia trae su envio")
        val regOtroEnvio = assertNotNull(leidoOtroEnvio.outbound, "la unidad ajena trae su envio")
        val regOtroEstado = assertNotNull(leidoOtroEstado.outbound, "la unidad de otro estado trae su envio")
        assertEquals(mensaje(1), regPropio.messageId, "la unidad propia describe el envio que se persiste")
        assertEquals(mensaje(9), regOtroEnvio.messageId, "`OTRO_ENVIO` describe OTRO envio")
        assertEquals(mensaje(1), regOtroEstado.messageId, "`OTRO_ESTADO` describe el MISMO envio")
        assertContentEquals(
            regPropio.ciphertext, regOtroEstado.ciphertext,
            "y con el MISMO ciphertext: la unica diferencia que se busca es la del ratchet",
        )
        assertFalse(
            leidoPropio.snapshot.dhSelf.privateKeyBytes()
                .contentEquals(leidoOtroEstado.snapshot.dhSelf.privateKeyBytes()),
            "pero con OTRO estado criptografico de verdad, no el mismo con otro nombre: " +
                "por eso se compara el escalar privado y no la huella de la sesion",
        )
        assertFalse(propio.contentEquals(otroEstado), "y los bytes no son los mismos")

        // ---- 3. LA CONSECUENCIA QUE NO PUEDE HABER PASADO ----------------------
        //
        // ESTE es el nucleo del test, y lo que lo hace un SEGUNDO guardian y no
        // una copia de `KM52B-04`.
        //
        // `KM52B-04` comprueba QUE FALLA: espera la excepcion CONCRETA —un
        // `Km52ChecksumMismatch`, un `Km52Truncated`, un `PersistedUnitMismatch`—
        // y su criterio de paso es "ha saltado el error que yo preveo". Eso ata
        // el guardian a DONDE se detecta y a COMO se avisa. Si alguien
        // reescribiera la verificacion para que avise con un error propio,
        // `KM52B-04` se pondria rojo sin que hubiera ningun defecto; y si
        // alguien la reescribiera para que avise de OTRA manera, `KM52B-04` se
        // pondria verde por la razon equivocada. Los dos casos son el mismo
        // fallo de arnes: un test que depende de un MECANISMO y no de una
        // PROPIEDAD.
        //
        // Este comprueba QUE PASA. La unidad corrupta no puede acabar confirmada,
        // y da igual COMO se entere la costura: da igual que `verify()` sea un
        // metodo, una bandera, o codigo en linea; da igual que avise con una
        // excepcion, con un codigo de retorno o con un `check`; da igual que la
        // comparacion sea byte a byte, por CONTENIDO, o por checksum. Lo unico
        // que se exige es lo que la propiedad dice: lo no verificado no se
        // confirma. Por eso no se mira el tipo de la excepcion: se mira el
        // ESTADO QUE QUEDA DESPUES, y por eso sigue valiendo aunque la llamada a
        // `verify()` desaparezca entera.
        fun caso(modo: ModoDeCorrupcion, sustituta: ByteArray?, queSeRompe: String) {
            val store = CorruptingTransmitStore(sustituta).apply { this.modo = modo }
            val j = journal(bob, store)

            // El intento se hace SIN `assertThrows`. Si la costura no detectara
            // nada, `persistir` terminaria con exito, confirmaria, y serian estas
            // mismas aserciones las que lo denunciarian. Un test que espera una
            // excepcion da POR HECHO que la hay —y su propio criterio de paso es
            // la excepcion—; este comprueba que el estado no avanza, que es lo
            // unico que la propiedad exige.
            runCatching { j.persistir(envio(a1, ordinal = 1uL, n = 1)) }

            // --- LO QUE NO PUEDE HABER PASADO ---
            assertEquals(0, store.commits, "$queSeRompe: NO HAY COMMIT. Lo que no se ha verificado no se confirma.")
            assertNull(store.promovido, "$queSeRompe: el medio no ha promovido nada a confirmado.")
            assertNull(store.readCommitted(), "$queSeRompe: y no hay ninguna unidad confirmada.")
            assertFalse(j.ledger.esDurable(mensaje(1)), "$queSeRompe: el envio NO es durable.")
            assertNull(j.ledger.estadoDe(mensaje(1)), "$queSeRompe: y no tiene estado de entrega.")
            assertEquals(0, j.ledger.tamanho(), "$queSeRompe: el libro de transmision sigue vacio.")
            assertEquals(1, store.retiros, "$queSeRompe: lo pendiente se retira.")
            assertFalse(
                store.hasPending(),
                "$queSeRompe: y el medio NO puede quedar con una unidad sin verificar: " +
                    "un pendiente que nadie ha comprobado es estado que el siguiente proceso tendria que eliminar a mano",
            )

            // --- Y QUE EL ESCENARIO REALMENTE OCURRIO ---
            //
            // Esto va DESPUES a proposito. Las comprobaciones de arriba son la
            // propiedad; estas son la garantia de que la propiedad se ha probado
            // algo y no se ha pasado por el camino corto de un guion roto.
            assertEquals(1, store.escritas.size, "$queSeRompe: el guion tiene que LLEGAR a escribir; si no, la corrupcion no se ha probado.")
            assertTrue(
                store.lecturas >= 1,
                "$queSeRompe: lo persistido tiene que HABER SIDO RELEIDO. Si nadie lo relee, " +
                    "la mitad de la propiedad —releer ANTES de confirmar— no esta, por muy verde que salga el resto",
            )
            when (modo) {
                ModoDeCorrupcion.NADA ->
                    assertNull(store.devuelto, "$queSeRompe: el medio tiene que decir que no tiene nada")

                else -> {
                    val devuelto = assertNotNull(store.devuelto, "$queSeRompe: el guion tiene que devolver algo")
                    assertFalse(
                        devuelto.contentEquals(store.escritas.single()),
                        "$queSeRompe: lo devuelto tiene que ser OTRO byte-string. Si fuese el mismo, no habria nada que detectar",
                    )
                    // Y que la corrupcion sea LA DEL CASO, no otra. Un prefijo que
                    // ademas estuviera con el checksum roto no probaria el caso
                    // del prefijo.
                    val escrito = store.escritas.single()
                    when (modo) {
                        ModoDeCorrupcion.PREFIJO_TRUNCADO -> assertTrue(
                            devuelto.size < escrito.size,
                            "$queSeRompe: de verdad falta el final",
                        )

                        ModoDeCorrupcion.CUERPO_TOCADO -> {
                            assertEquals(escrito.size, devuelto.size, "$queSeRompe: la longitud no cambia")
                            assertFalse(
                                cuerpoDe(escrito).contentEquals(cuerpoDe(devuelto)),
                                "$queSeRompe: el cuerpo tiene que estar tocado",
                            )
                        }

                        ModoDeCorrupcion.CHECKSUM_ROTO -> {
                            assertEquals(escrito.size, devuelto.size, "$queSeRompe: la longitud no cambia")
                            assertContentEquals(
                                cuerpoDe(escrito), cuerpoDe(devuelto),
                                "$queSeRompe: el CUERPO esta intacto byte a byte: lo unico roto es la integridad",
                            )
                            assertFalse(
                                checksumDe(escrito).contentEquals(checksumDe(devuelto)),
                                "$queSeRompe: y el checksum es otro",
                            )
                        }

                        ModoDeCorrupcion.COLA_DE_BASURIA -> assertEquals(
                            escrito.size + 1, devuelto.size,
                            "$queSeRompe: sobra exactamente un byte",
                        )

                        ModoDeCorrupcion.OTRO_ENVIO -> {
                            assertEquals(escrito.size, devuelto.size, "$queSeRompe: cabe igual")
                            assertEquals(
                                mensaje(9),
                                assertNotNull(codec().deserialize(devuelto).outbound, "$queSeRompe: trae envio").messageId,
                                "$queSeRompe: y es una unidad VALIDA de otro envio",
                            )
                        }

                        ModoDeCorrupcion.OTRO_ESTADO -> {
                            assertEquals(escrito.size, devuelto.size, "$queSeRompe: cabe igual")
                            assertEquals(
                                mensaje(1),
                                assertNotNull(codec().deserialize(devuelto).outbound, "$queSeRompe: trae envio").messageId,
                                "$queSeRompe: y es una unidad VALIDA del MISMO envio",
                            )
                        }

                        ModoDeCorrupcion.NINGUNO -> error("`NINGUNO` no es un caso de corrupcion")
                        ModoDeCorrupcion.NADA -> error("`NADA` se comprueba fuera")
                    }
                }
            }
        }

        // 3a. El PREFIXIO: la forma mas burda, y la unica que no deja ni siquiera
        //     un checksum que comparar.
        caso(
            ModoDeCorrupcion.PREFIJO_TRUNCADO, null,
            "el medio escribio un prefijo y devolvio exito",
        )

        // 3b. El CUERPO: misma longitud, misma forma, un byte distinto. Lo unico
        //     que la delata es la integridad, no la medida.
        caso(
            ModoDeCorrupcion.CUERPO_TOCADO, null,
            "un byte del cuerpo cambio en el medio",
        )

        // 3c. SOLO EL CHECKSUM: el cuerpo esta intacto byte a byte. Es el caso
        //     que separa "comprobar la longitud" de "comprobar la unidad": una
        //     verificacion que solo mirase `size` pasaria este caso.
        caso(
            ModoDeCorrupcion.CHECKSUM_ROTO, null,
            "el checksum guardado no es el del cuerpo",
        )

        // 3d. LA COLA: la unidad entera y un byte de mas.
        caso(
            ModoDeCorrupcion.COLA_DE_BASURIA, null,
            "el medio devolvio una unidad con cola",
        )

        // 3e. OTRA UNIDAD, MISMA LONGITUD, OTRO ENVIO. Valida, con su checksum
        //     correcto, describiendo otra cosa. El checksum NO la delata: esta
        //     entera y bien formada.
        caso(
            ModoDeCorrupcion.OTRO_ENVIO, otroEnvio,
            "el medio guardo otro envio en su lugar",
        )

        // 3f. OTRA UNIDAD, MISMA LONGITUD, MISMO ENVIO, OTRO ESTADO DE RATCHET.
        //     La mas peligrosa: el envio es el que se queria y el ciphertext es
        //     el que salio, y aun asi lo que se confirmaria restauraria unas
        //     claves que no son las de esta sesion.
        caso(
            ModoDeCorrupcion.OTRO_ESTADO, otroEstado,
            "el medio guardo otro estado de ratchet en su lugar",
        )

        // 3g. Y el medio que dice que no tiene nada, aunque la escritura haya
        //     tenido exito.
        caso(
            ModoDeCorrupcion.NADA, null,
            "la escritura tuvo exito y el medio no tiene nada",
        )

        // ---- 4. EL CONTROL: EL MISMO GUION, CON UN MEDIO QUE NO CORROMPE -------
        //
        // Sin esto, "nunca se confirma" podria ser cierto porque el codigo no
        // confirma NUNCA, y el test pasaria por la razon equivocada. Aqui el
        // MISMO envio, la MISMA sesion y un medio que devuelve exactamente lo
        // que se le dio SI se confirman. El guion puede distinguir "corrompi y no
        // se confirmo" de "nunca se confirma nada", y sin esta seccion no
        // podria.
        run {
            val store = CorruptingTransmitStore().apply { modo = ModoDeCorrupcion.NINGUNO }
            val j = journal(bob, store)
            val registro = j.persistir(envio(a1, ordinal = 1uL, n = 1))
            assertEquals(1, store.commits, "con el medio sano SI se confirma: el guion no esta probando que nunca se confirme")
            assertContentEquals(store.escritas.single(), store.readCommitted(), "y lo confirmado es lo escrito, byte a byte")
            assertContentEquals(store.escritas.single(), store.promovido, "el medio ha promovido exactamente lo recibido")
            assertTrue(j.ledger.esDurable(mensaje(1)), "el envio queda durable")
            assertEquals(
                OutboundDeliveryState.PENDIENTE, j.ledger.estadoDe(mensaje(1)),
                "y con su estado de entrega: persistir no entrega",
            )
            assertEquals(mensaje(1), registro.messageId, "el registro devuelto es el del envio persistido")
            assertEquals(0, store.retiros, "y no hubo nada que retirar")
        }

        // ---- 5. LA SESION, INTACTA, CON CRIPTOGRAFIA Y NO CON LA HUELLA -------
        //
        // NO se comprueba con `stateFingerprint()`. La huella de
        // `DoubleRatchetSession` se construye con `dhSelf.publicKey` y NO cubre el
        // escalar DH privado: dos sesiones con la misma huella pueden tener
        // mitades privadas distintas y no poder hablar nunca. Un test que la
        // usaria pasaria por la razon equivocada —daria por intacta una sesion
        // cuyo escalar se ha roto, que es justo la unidad que no se debe
        // confirmar—. Lo que no miente es el CIPHERTEXT del `encrypt()` siguiente,
        // comparado byte a byte contra un CLON de la sesion. El precedente es
        // `KM52-01b` y `KM52B-10b`.
        assertEquals(cadenasAntes, bob.receiveChainCount(), "ninguna cadena puede aparecer o desaparecer")
        assertEquals(nsAntes, bob.currentSendMessageNumber(), "Ns intacto: persistir no consume la cadena de envio")
        assertEquals(pnAntes, bob.currentPreviousChainLength(), "PN intacto")
        assertEquals(retenidasAntes, retenidasDe(bob), "ninguna clave retenida ha cambiado")
        assertContentEquals(
            frameDeControl(control), frameDeControl(bob),
            "y el frame siguiente sale byte a byte IDENTICO: ni los siete casos ni el control han movido el ratchet un solo byte",
        )
    }
    // ===================================================================
    // KM52B-05 — Corte ANTES del commit
    // ===================================================================

    @Test
    @DisplayName("KM52B-05 un corte antes del commit no fabrica estado al restaurar")
    fun `KM52B-05 corte antes del commit`() {
        val w = Wire()
        val (a0, a1) = dosEpochs(w)
        val store = FaultInjectingTransmitStore()
        val j = journal(w.bobSession, store)

        // Un envio que se persistio y confirmo de verdad: es el estado bueno.
        j.persistir(envio(a0, ordinal = 1uL, n = 1))
        assertNotNull(store.readCommitted(), "la primera unidad si se confirmo")

        // --- EL CORTE. Otra sesion, otro medio. Se escribe la unidad
        // siguiente y el proceso muere ANTES de confirmar. Se escribe
        // directamente en el MEDIO porque un journal vivo SIEMPRE retira al
        // fallar: el corte se produce en el medio, que es donde ocurre, y lo
        // que queda es exactamente lo que un proceso muerto dejaria.
        val store2 = FaultInjectingTransmitStore().apply { corteDeProceso = true }
        val w2 = Wire()
        val b0 = dosEpochs(w2)[0]
        val control = clonDe(w2.bobSession)
        val retenidasAntes = retenidasDe(w2.bobSession)
        store2.writeAhead(
            codec().serialize(
                Km52Unit(w2.bobSession.snapshot(), retencionDe(w2.bobSession.snapshot()), registroDe(b0, mensaje(7), 7uL)),
            ),
        )
        assertTrue(store2.hasPending(), "el medio tiene la unidad PENDIENTE")
        assertNull(store2.readCommitted(), "y nada confirmado: el proceso murio antes")

        // --- EL PROCESO QUE ARRANCA DESPUES.
        val recuperado = journal(w2.bobSession, store2)
        assertNull(recuperado.restaurar(), "restaurar no fabrica estado a partir de lo no confirmado")
        assertFalse(recuperado.ledger.esDurable(mensaje(7)), "y el envio no aparece en el libro")
        assertEquals(0, recuperado.ledger.tamanho(), "el libro sigue vacio")

        // Y la sesion, ni tocada. Con criptografia, no con la huella.
        assertEquals(2, w2.bobSession.receiveChainCount(), "la sesion no ha cambiado de cadenas")
        assertEquals(retenidasAntes, retenidasDe(w2.bobSession), "ninguna retenida ha cambiado")
        assertContentEquals(
            frameDeControl(control), frameDeControl(w2.bobSession),
            "el frame siguiente sale IDENTICO: nadie ha movido el ratchet",
        )

        // Y lo que la unidad pendiente describia sigue siendo INUTILIZABLE:
        // esta bien escrita y describa bien el frame, pero no hay commit, y
        // sin commit no hay estado. Esa es la diferencia entre "esta escrito" y
        // "esta confirmado".
        val pendiente = assertNotNull(store2.pendienteBruto())
        assertContentEquals(
            b0.copyOfRange(SecureFrameSpec.CIPHERTEXT_OFFSET, b0.size),
            assertNotNull(codec().deserialize(pendiente).outbound, "la unidad pendiente trae su envio").ciphertext,
            "la unidad pendiente SI describe el frame, y aun asi no se puede usar",
        )
        assertEquals(
            mensaje(7),
            assertNotNull(codec().deserialize(pendiente).outbound, "y lo trae con su messageId").messageId,
            "y su messageId tambien",
        )

        // Y el envio de la PRIMERA sesion, ese si, sigue disponible: el corte
        // fue en la segunda.
        assertNotNull(store.readCommitted(), "la unidad confirmada de la primera sesion no se toca")
    }

    // ===================================================================
    // KM52B-06 — Corte DESPUES del commit
    // ===================================================================

    @Test
    @DisplayName("KM52B-06 un corte despues del commit reproduce el estado exacto")
    fun `KM52B-06 corte despues del commit`() {
        val w = Wire()
        val a1 = dosEpochs(w)[1]          // frame RETENIDO: describe a1 sin entregar
        val store = FaultInjectingTransmitStore()
        val j = journal(w.bobSession, store)

        // Un envio con historia real: el frame `a1` no lo ha entregado nadie,
        // asi que el estado que hay que recuperar tiene claves retenidas de
        // verdad, y recuperarlas es parte del contrato.
        val registro = j.persistir(envio(a1, ordinal = 5uL, n = 2))
        val esperado = frameDeControl(clonDe(w.bobSession))

        // --- EL CORTE. La sesion y el journal mueren; el medio no.
        val s = session(det(1), null)
        val recuperado = journal(s, store)
        val leido = assertNotNull(recuperado.restaurar(), "hay unidad confirmada: se restaura")

        // 1. El registro de salida es EXACTAMENTE el persistido.
        assertEquals(registro.messageId, leido.messageId, "messageId")
        assertEquals(registro.deliveryState, leido.deliveryState, "deliveryState")
        assertEquals(registro.frameIdentity, leido.frameIdentity, "frameIdentity")
        assertEquals(registro.createdOrdinal, leido.createdOrdinal, "createdOrdinal")
        assertContentEquals(registro.ciphertext, leido.ciphertext, "ciphertext")
        assertTrue(recuperado.ledger.esDurable(mensaje(2)), "el envio vuelve a ser durable tras el corte")

        // 2. El estado CRIPTOGRAFICO es el mismo, demostrado por el cable: el
        //    frame siguiente tiene que salir byte a byte igual.
        assertContentEquals(
            esperado, frameDeControl(s),
            "la sesion restaurada produce el MISMO frame que la que se murio",
        )

        // 3. Y el estado que NO viaja en el frame tambien se conserva: la clave
        //    retenida que `a1` necesita sigue ahi, y `a1` se descifra desde
        //    ella. Una sesion restaurada con la foto PODADA daria
        //    REPLAY_OR_UNKNOWN aqui, y este es el unico sitio donde se nota.
        val r = SecureRatchetProtocol(s, protector).decrypt(a1)
        assertTrue(r is SecureRatchetProtocol.DecryptResult.Ok, "el frame retenido tiene que entrar: $r")
        assertTrue(
            (r as SecureRatchetProtocol.DecryptResult.Ok).fromSkipped,
            "y desde la clave RETENIDA, no derivado de la cadena",
        )
        assertContentEquals("a1".toByteArray(), r.plaintext, "con el contenido correcto")

        // 4. Y el libro de retencion se ha rehidratado con lo que venia
        //    persistido: sin eso, la proxima escritura podaria victimas
        //    distintas de las que la unidad anterior daba por mas antiguas.
        val confirmadas = assertNotNull(store.readCommitted())
        val tabla = codec().deserialize(confirmadas).retention
        for (e in tabla.entries) {
            assertEquals(
                e.lastUseOrdinal, recuperado.retention.ordinalDe(e.chainId),
                "el ordinal de la cadena ${hex(e.chainId)} ha sobrevivido al corte",
            )
        }
    }

    // ===================================================================
    // KM52B-07 — El ciphertext ORIGINAL
    // ===================================================================

    @Test
    @DisplayName("KM52B-07 la unidad conserva el ciphertext original, no uno re-cifrado")
    fun `KM52B-07 el ciphertext original`() {
        val w = Wire()
        val store = FaultInjectingTransmitStore()
        val j = journal(w.bobSession, store)

        val f1 = exchange(w.alice, w.bob, "m1")
        val f2 = exchange(w.alice, w.bob, "m2")
        j.persistir(envio(f1, ordinal = 1uL, n = 1))
        j.persistir(envio(f2, ordinal = 2uL, n = 2))

        val segunda = assertNotNull(store.readCommitted())
        val u = codec().deserialize(segunda)

        // 1. La unidad lleva EXACTAMENTE los bytes de DESPUES del header del
        //    frame que se quiso persistir.
        assertContentEquals(
            f2.copyOfRange(SecureFrameSpec.CIPHERTEXT_OFFSET, f2.size),
            assertNotNull(u.outbound, "la unidad describe el envio recien persistido").ciphertext,
            "el ciphertext es el del frame, byte a byte",
        )
        // Y el de la unidad anterior es OTRO, o el test no distinguiria "se
        // conservo el ultimo" de "se conservo ESTE".
        assertFalse(
            f1.copyOfRange(SecureFrameSpec.CIPHERTEXT_OFFSET, f1.size)
                .contentEquals(assertNotNull(u.outbound, "la unidad describe el envio recien persistido").ciphertext),
            "no es el ciphertext de otro envio: es el de ESTE",
        )
        val identidad = assertNotNull(u.outbound, "la unidad describe el envio recien persistido").frameIdentity
        assertEquals(FrameIdentity.fromWire(f2), identidad, "la identidad tambien es la suya")
        assertEquals(
            FrameIdentity.fromWire(f2).messageNumber, identidad.messageNumber,
            "con SU numero de mensaje, no el de otro",
        )

        // 2. Y por que la re-cifracion no seria aceptable: NO PUEDE. Al cifrar
        //    "m2" otra vez desde el estado actual, ALICE produce `N+1`. Esa
        //    es la unidad que una re-cifracion escribiria.
        val reCifrado = w.alice.encrypt("m2".toByteArray())
        assertEquals(
            FrameIdentity.fromWire(f2).messageNumber + 1u,
            FrameIdentity.fromWire(reCifrado).messageNumber,
            "re-cifrar da N+1: por eso la unidad lleva el N del frame original",
        )

        // 3. Y el receptor NO PUEDE distinguirlo. El frame original se acepta
        //    una vez y la segunda vez cae en REPLAY_OR_UNKNOWN; el re-cifrado
        //    entra como si fuera el siguiente. Por eso un envio
        //    "retransmisible" con OTRO frame no es un reenvio: es corrupcion,
        //    y el receptor acepta el duplicado y pierde el mensaje real.
        val receptor = SecureRatchetProtocol(
            DoubleRatchetSession.restore(codec().deserialize(segunda).snapshot, x25519, kdf),
            protector,
        )
        assertTrue(
            receptor.decrypt(f2) is SecureRatchetProtocol.DecryptResult.Rejected,
            "el frame ya consumido es un replay",
        )
        val aceptado = receptor.decrypt(reCifrado)
        assertTrue(
            aceptado is SecureRatchetProtocol.DecryptResult.Ok,
            "y el frame re-cifrado entra como el siguiente: el receptor no lo distingue",
        )
        assertContentEquals("m2".toByteArray(), (aceptado as SecureRatchetProtocol.DecryptResult.Ok).plaintext)
    }

    // ===================================================================
    // KM52B-08 — El estado de entrega es DEL MISMO snapshot
    // ===================================================================

    @Test
    @DisplayName("KM52B-08 el estado de entrega y el snapshot son del MISMO envio")
    fun `KM52B-08 el estado de entrega y el snapshot`() {
        val w = Wire()
        dosEpochs(w)
        // El frame tiene que ser de BOB, la misma sesion cuyo snapshot se
        // persiste: comparar el `N` de un frame de ALICE con el `Ns` de BOB
        // no diria nada, porque son cadenas distintas.
        val propio = w.bob.encrypt("propio".toByteArray())
        val nAntesDelEnvio = w.bobSession.currentSendMessageNumber() - 1u
        assertEquals(FrameIdentity.fromWire(propio).messageNumber, nAntesDelEnvio, "el guion: el frame es el ultimo")
        val a1 = propio
        val store = FaultInjectingTransmitStore()
        val j = journal(w.bobSession, store)

        val registro = j.persistir(envio(a1, ordinal = 11uL, n = 5))
        val u = codec().deserialize(assertNotNull(store.readCommitted()))

        // 1. La identidad y el ciphertext del registro son del MISMO frame.
        val out = assertNotNull(u.outbound, "la unidad describe el envio persistido")
        assertEquals(FrameIdentity.fromWire(a1), out.frameIdentity, "identidad del frame")
        assertContentEquals(
            a1.copyOfRange(SecureFrameSpec.CIPHERTEXT_OFFSET, a1.size),
            out.ciphertext,
            "ciphertext del MISMO frame",
        )
        assertEquals(registro.frameIdentity, out.frameIdentity, "y el registro coincide con la unidad")

        // 2. El snapshot es el del estado en el MOMENTO del envio, no el de
        //    antes. La prueba es que la cadena de envio ya esta AVANZADA: el
        //    frame se produjo con la clave del numero de su cabecera, y la
        //    foto posterior tiene `Ns` una posicion mas alta. Una unidad con
        //    el `Ns` anterior repetiria ese numero.
        assertEquals(
            FrameIdentity.fromWire(a1).messageNumber + 1u,
            u.snapshot.sendChain.sendMessageNumber,
            "el snapshot lleva Ns DESPUES del frame: con el N anterior, la unidad repetiria ese numero",
        )
        assertEquals(0u, u.snapshot.sendChain.receiveMessageNumber, "y Nr de la cadena de envio a cero (§3.1)")

        // 3. El estado de entrega NO lo decide el ratchet: es el que le dio el
        //    emisor, y el ratchet no lo ha visto. Persistir el MISMO frame con
        //    otro estado de entrega cambia SOLO esa parte.
        val store2 = FaultInjectingTransmitStore()
        val j2 = journal(w.bobSession, store2)
        val inFlight = j2.persistir(
            PreparedSend(mensaje(5), a1, OutboundDeliveryState.IN_FLIGHT, 12uL),
        )
        val u2 = codec().deserialize(assertNotNull(store2.readCommitted()))
        val out2 = assertNotNull(u2.outbound, "la segunda unidad tambien describe su envio")
        assertEquals(OutboundDeliveryState.IN_FLIGHT, out2.deliveryState, "el estado de entrega viaja")
        assertContentEquals(u.snapshot.rootKey, u2.snapshot.rootKey, "pero la parte criptografica es la misma")
        assertContentEquals(out.ciphertext, out2.ciphertext, "y el frame es el mismo")
        assertEquals(FrameIdentity.fromWire(a1), out2.frameIdentity, "y la identidad tambien")
        assertEquals(11uL, out.createdOrdinal, "el ordinal 11 era del envio PENDIENTE")
        assertEquals(12uL, out2.createdOrdinal, "y el 12 del IN_FLIGHT: no se confunden")
        assertEquals(OutboundDeliveryState.IN_FLIGHT, inFlight.deliveryState, "el registro devuelto lo dice")

        // 4. Los dos libros distinguen los dos envios, y el estado del primero
        //    no se ha movido al persistir el segundo.
        assertEquals(OutboundDeliveryState.PENDIENTE, j.ledger.estadoDe(mensaje(5)), "el primero sigue PENDIENTE")
        assertEquals(OutboundDeliveryState.IN_FLIGHT, j2.ledger.estadoDe(mensaje(5)), "el segundo es IN_FLIGHT")
        assertEquals(
            listOf(11uL), j.ledger.registros().map { it.createdOrdinal },
            "y el log de salida ordena por `createdOrdinal` (§6.9.1), de forma determinista",
        )
    }

    // ===================================================================
    // KM52B-09 — Exceso AL ESCRIBIR: eviction determinista (§6.9.2)
    // ===================================================================

    @Test
    @DisplayName("KM52B-09 el exceso al escribir se poda con el orden de §6.9.1 y se escribe la unidad conforme")
    fun `KM52B-09 exceso al escribir eviction determinista`() {
        val w = Wire()
        cuatroCadenasLlenas(w)
        val bob = w.bobSession
        assertEquals(5, bob.receiveChainCount(), "el guion debe abrir cinco cadenas")
        val todas = retenidasDe(bob)
        assertEquals(4000, todas.size, "y retener 4000 claves: 989 por encima del techo")
        assertTrue(
            todas.size * COSTE_CLAVE_RETENIDA > Km52RetentionLimits.MAX_TOTAL_RETAINED_BUDGET,
            "el guion tiene que pasar de verdad el presupuesto",
        )

        // Ordinales CON INTENCION: dos cadenas empatan en 5 y tres en 7. Asi el
        // orden de §6.9.1 —`lastUseOrdinal` ASC, `N` ASC dentro de la cadena,
        // `chainId` ASC de desempate— tiene que resolverse entero, incluido el
        // desempate. Sin empate, el desempate por `chainId` no se ejercitaria
        // nunca y una parte de la regla quedaria sin probar.
        // Los dos ordinales bajos van a las dos cadenas que MAS retenidas
        // tienen, para que la pareja empatada contenga de sobra las victimas y
        // el reparto sea el que dice la regla y no el que toque al azar de los
        // identificadores.
        val porRetenidas = bob.snapshot().receiveChains
            .sortedWith(compareByDescending<com.keymessage.core.ratchet.ReceiveChainSnapshot> { it.ratchet.skipped.size }
                .thenBy { hex(it.chainId) })
        val conRetenidas = porRetenidas.map { hex(it.chainId) }
        val conOrdinal = conRetenidas.withIndex().associate { (i, h) -> h to if (i < 2) 5uL else 7uL }
        val porHex = conRetenidas.sorted()
        assertEquals(1000, porRetenidas[0].ratchet.skipped.size, "las dos cadenas de ordinal bajo tienen 1000 cada una")
        assertEquals(1000, porRetenidas[1].ratchet.skipped.size, "y por tanto la pareja empata con material de sobra")
        val libro = ChainRetentionBook.rehidratada(
            ReceiveChainRetention(porHex.map { h -> ChainUseOrdinal(hexABinary(h), conOrdinal.getValue(h)) }),
        )

        val store = FaultInjectingTransmitStore()
        val j = journal(bob, store, libro)
        val frame = exchange(w.alice, w.bob, "tras-el-exceso")
        j.persistir(envio(frame, ordinal = 1uL, n = 1))

        // 1. SE ESCRIBE, y la unidad escrita CABE. Al escribir el presupuesto
        //    es un limite de DATOS (§6.9.2), no de validacion.
        assertEquals(1, store.commits, "una unidad sobredimensionada no se escribe: se poda antes")
        val escrita = codec().deserialize(assertNotNull(store.readCommitted()))
        val supervivientes = escrita.snapshot.receiveChains.flatMap { c ->
            c.ratchet.skipped.keys.map { hex(c.chainId) to it.second }
        }.toSet()
        assertEquals(
            Km52RetentionLimits.MAX_TOTAL_RETAINED_KEYS, supervivientes.size,
            "la unidad escrita tiene exactamente el techo de claves",
        )
        assertTrue(
            supervivientes.size * COSTE_CLAVE_RETENIDA <= Km52RetentionLimits.MAX_TOTAL_RETAINED_BUDGET,
            "y su coste en bytes cabe en el presupuesto",
        )

        // 2. LAS VICTIMAS SON EXACTAMENTE LAS QUE DICE §6.9.1. La lista
        //    ordenada se recalcula aqui desde el estado de partida, sin usar
        //    nada de la produccion, y se comparan las dos.
        val ordenadas = todas.sortedWith(
            compareBy({ conOrdinal.getValue(it.first) }, { it.second }, { it.first }),
        )
        // §6.9.1 victimiza por `lastUseOrdinal` ASC: se cae PRIMERO lo mas
        // antiguo, asi que la lista de victimas es el PREFIJO del orden.
        val sobran = todas.size - Km52RetentionLimits.MAX_TOTAL_RETAINED_KEYS
        val victimadas = ordenadas.take(sobran).toSet()
        assertEquals(989, victimadas.size, "el techo son ${Km52RetentionLimits.MAX_TOTAL_RETAINED_KEYS} claves")
        assertEquals(victimadas, todas.toSet() - supervivientes, "las victimas son las del orden de §6.9.1")

        // 3. Y el guion NO se apoya en la casualidad: comprueba que la regla se
        //    ha resuelto entera.
        //    3a. `lastUseOrdinal` ASC manda sobre todo lo demas.
        assertTrue(
            ordenadas.take(victimadas.size).all { conOrdinal.getValue(it.first) == 5uL },
            "las ${victimadas.size} victimas salen todas de las dos cadenas de ordinal 5, antes que las de 7",
        )
        //    3b. `N` ASC dentro de la pareja empatada.
        assertEquals(1u, victimadas.minOf { it.second }, "la primera victima es la N mas baja")
        assertTrue(victimadas.any { it.second == 495u }, "y la ultima victima esta en la N que la cuenta dice")
        //    3c. `chainId` ASC desempata: con 989 victimas y 2 cadenas empatadas
        //    por N, la 989a es la de la cadena mas pequena, y la 988a la de la
        //    mayor. Sin desempate dependeria del recorrido del mapa.
        val enN495 = ordenadas.filter { it.second == 495u && conOrdinal.getValue(it.first) == 5uL }
        val victimadasEnN495 = victimadas.filter { it.second == 495u }.toList()
        assertEquals(2, enN495.size, "el guion tiene dos cadenas empatadas en esa N")
        assertEquals(1, victimadasEnN495.size, "y de ellas solo cae una")
        assertEquals(
            enN495.first().first, victimadasEnN495.single().first,
            "la que cae es la de `chainId` mas pequeño: el desempate de §6.9.1",
        )

        // 4. DETERMINISMO. El mismo estado, escrito otra vez con los mismos
        //    ordinales, da los mismos bytes (INV-07).
        val store2 = FaultInjectingTransmitStore()
        val j2 = journal(bob, store2, ChainRetentionBook.rehidratada(
            ReceiveChainRetention(porHex.map { h -> ChainUseOrdinal(hexABinary(h), conOrdinal.getValue(h)) }),
        ))
        // El MISMO envio, no uno equivalente: la afirmacion es que el mismo
        // estado produce los mismos BYTES, y un `messageId` distinto cambiaria
        // la unidad sin que el orden de victimizacion tuviera nada que ver.
        j2.persistir(PreparedSend(mensaje(1), frame, OutboundDeliveryState.PENDIENTE, 1uL))
        assertContentEquals(
            store.escrituras[0], store2.escrituras[0],
            "el mismo estado produce byte a byte la misma unidad",
        )
    }

    /** Deuelve los bytes de un `chainId` escrito en hexadecimal. */
    private fun hexABinary(h: String): ByteArray {
        val out = ByteArray(h.length / 2)
        for (i in out.indices) out[i] = h.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    // ===================================================================
    // KM52B-10 — Exceso AL LEER: rechazo total, NUNCA eviction
    //
    // LA RUTA DE ESTE GUARDIAN: una unidad de veras, con las CUATRO cadenas
    // reales de una sesion real, a la que se le ANADEN claves retenidas hasta
    // el tope por cadena. El exceso lo produce CONTAR mas, no quitar lo que el
    // codec podo — esa es la ruta de `KM52B-10b`, y son distintas.
    // ===================================================================

    @Test
    @DisplayName("KM52B-10 una unidad sobredimensionada en disco se rechaza ENTERA y no se poda")
    fun `KM52B-10 exceso al leer rechazo total`() {
        val w = Wire()
        val (_, a1) = cuatroEpochs(w)
        val store = FaultInjectingTransmitStore()
        val j = journal(w.bobSession, store)

        // Una unidad CONFORME, escrita y confirmada de verdad.
        j.persistir(envio(a1, ordinal = 1uL, n = 1))
        val buena = assertNotNull(store.readCommitted())
        val fotoBuena = codec().deserialize(buena).snapshot
        assertEquals(4, fotoBuena.receiveChains.size, "la sesion real tiene cuatro cadenas")
        assertTrue(
            fotoBuena.receiveChains.sumOf { it.ratchet.skipped.size } * COSTE_CLAVE_RETENIDA
                <= Km52RetentionLimits.MAX_TOTAL_RETAINED_BUDGET,
            "la foto real cabe en el presupuesto",
        )

        // La MISMA foto con las cadenas CRECIDAS hasta pasarlo (§6.9.5: unidad
        // antigua o manipulada).
        val base = codec().serialize(
            Km52Unit(fotoBuena, retencionDe(fotoBuena), registroDe(a1, mensaje(2), 1uL)),
        )
        val inflada = agrandando(base, porCadena = 1000)
        assertTrue(inflada.size > base.size, "la version inflada es mas grande que la que se escribiria")

        // Se confirma en el medio: es lo que un dispositivo se encuentra al
        // arrancar con una unidad que no cumple el contrato de la version.
        val store2 = FaultInjectingTransmitStore()
        store2.sembrarConfirmado(inflada)
        val recuperacion = journal(w.bobSession, store2)
        val control = clonDe(w.bobSession)
        val retenidasAntes = retenidasDe(w.bobSession)

        // 1. RECHAZO TOTAL, con su error tipado.
        val e = assertThrows<RetentionBudgetExceeded> { recuperacion.restaurar() }
        assertEquals(RetentionRule.TOTAL_BYTES, e.rule, "el motivo es el presupuesto global")
        assertEquals(4 * 1000L * COSTE_CLAVE_RETENIDA, e.actual, "cuantas claves retenidas traia")
        assertEquals(Km52RetentionLimits.MAX_TOTAL_RETAINED_BUDGET.toLong(), e.limit, "el techo")

        // 2. NADA se poda. Ni la sesion, ni el libro.
        assertEquals(0, recuperacion.ledger.tamanho(), "el libro de entrega sigue vacio: no hay estado parcial")
        assertEquals(retenidasAntes, retenidasDe(w.bobSession), "ninguna retenida ha desaparecido")
        assertContentEquals(
            frameDeControl(control), frameDeControl(w.bobSession),
            "la sesion no se ha movido un byte",
        )

        // 3. Y lo que una implementacion que "lee, poda y restaura lo
        //    restante" habria perdido, sigue en la sesion intacta: el frame
        //    retenido se descifra desde la clave que la poda habria
        //    descartado.
        val r = SecureRatchetProtocol(w.bobSession, protector).decrypt(a1)
        assertTrue(r is SecureRatchetProtocol.DecryptResult.Ok, "el frame retenido tiene que seguir entrando: $r")
        assertTrue(
            (r as SecureRatchetProtocol.DecryptResult.Ok).fromSkipped,
            "y desde la clave RETENIDA, no derivado de la cadena",
        )

        // 4. Y no es "esta unidad esta rota": la MISMA forma de unidad, un
        //    escalon por debajo del techo, si entra — y con TODAS sus claves.
        //    4 x 750 = 3000 x 68 = 204 000 <= 204 800.
        val cabe = agrandando(base, porCadena = 750)
        val leida = codec().deserialize(cabe)
        assertEquals(4, leida.snapshot.receiveChains.size, "por debajo del presupuesto entran las cuatro")
        assertEquals(3000, leida.snapshot.receiveChains.sumOf { it.ratchet.skipped.size }, "con TODAS sus claves")
    }

    // ===================================================================
    // KM52B-10b — EL SEGUNDO GUARDIAN DE M6
    // ===================================================================

    @Test
    @DisplayName("KM52B-10b segundo guardian de M6: el exceso por otra ruta y la sesion intacta")
    fun `KM52B-10b segundo guardian de M6`() {
        // ---- 0. LA SESION: REAL, y de verdad por encima del presupuesto ---------
        val w = Wire()
        cuatroCadenasLlenas(w)
        val bob = w.bobSession
        val propia = w.bob.encrypt("propio".toByteArray())

        // ---- 1. SESION CON ESTADO PREVIO RECONOCIBLE ----------------------------
        val cadenas = bob.receiveChainCount()
        val pn = bob.currentPreviousChainLength()
        val ns = bob.currentSendMessageNumber()
        assertEquals(5, cadenas, "el guion debe dejar CINCO cadenas reales de recepcion")
        val fotoPrevia = bob.snapshot()
        val todasLasRetenidas = retenidasDe(bob)
        assertEquals(4000, todasLasRetenidas.size, "y 4000 claves retenidas REALES, derivadas por el ratchet")
        val control = clonDe(bob)

        // ---- 2. LA RUTA DEL EXCESO, QUE NO ES LA DE `KM52-10` --------------------
        //
        // `KM52-10` ENSAMBLA la unidad a mano desde los valores por defecto de un
        // `Snap()` sintetico: escalar DH de relleno, sin DH remota, `chainId`
        // inventados y `messageKey` de relleno. Este test no ensambla NADA:
        //
        //   a) Deja que el ratchet construya 4000 claves retenidas REALES.
        //   b) Deja que `serialize()` escriba la unidad — y la PODA al techo,
        //      que es el comportamiento correcto de §6.9.2 al escribir.
        //   c) Identifica las 989 claves que la poda acabo de tirar, con sus
        //      bytes de verdad tomados del estado de la sesion.
        //   d) Las vuelve a meter en la unidad YA ESCRITA, escalando por el
        //      bloque SNAPSHOT con los offsets del layout (§3.1).
        //
        // El exceso, aqui, es LITERALMENTE el material que la propia escritura
        // descarto. Esa es una ruta que ni `KM52-10` ni `KM52B-10` recorren: los
        // dos fabrican claves, este las recupera de un estado real.
        val base = codec().serialize(
            Km52Unit(fotoPrevia, retencionDe(fotoPrevia), registroDe(propia, mensaje(2), 1uL)),
        )

        // 2a. La escritura REAL podo, y deja una unidad CONFORME.
        val escritas = codec().deserialize(base)
        val supervivientes = escritas.snapshot.receiveChains.flatMap { c ->
            c.ratchet.skipped.keys.map { hex(c.chainId) to it.second }
        }.toSet()
        assertEquals(
            Km52RetentionLimits.MAX_TOTAL_RETAINED_KEYS, supervivientes.size,
            "al escribir, el presupuesto es un limite de DATOS: la unidad sale podada al techo",
        )
        val evictionadas = todasLasRetenidas.toSet() - supervivientes
        assertEquals(989, evictionadas.size, "la escritura tiro 989 claves retenidas reales")

        // 2b. Sus bytes se recuperan del estado VIVO de la sesion, no de un
        //     generador: son las messageKey que el ratchet derivo de verdad.
        val porCadena = fotoPrevia.receiveChains.associate { cadena ->
            hex(cadena.chainId) to cadena.ratchet.skipped
        }
        val aReinsertar = evictionadas.groupBy({ it.first }, { (cadenaId, n) ->
            val bytes = hexABinary(cadenaId)
            val clave = requireNotNull(
                porCadena[cadenaId]?.get(com.keymessage.core.ratchet.ChainIdentifier(bytes) to n),
            ) { "la clave retenida ($cadenaId, $n) no esta en el estado de la sesion" }
            entrada(bytes, n, clave.copyOf())
        })
        assertEquals(4000, aReinsertar.values.sumOf { it.size } + supervivientes.size, "se reponen todas")

        // 2c. La unidad re-editada vuelve a pesar mas que el presupuesto, con el
        //     material REAL que la escritura habia descartado.
        val inflada = reeditar(base, aReinsertar)
        val retenidas = 4000L * COSTE_CLAVE_RETENIDA
        assertTrue(
            retenidas > Km52RetentionLimits.MAX_TOTAL_RETAINED_BUDGET,
            "el guion tiene que pasar de verdad el presupuesto: $retenidas",
        )
        for (cadena in aReinsertar) {
            val enLaUnidad = le16(inflada, offsetDeCadena(inflada, cadena.key) + Km52Spec.RECEIVE_CHAIN_LENGTH - 2)
            assertTrue(
                enLaUnidad <= Km52RetentionLimits.MAX_RETAINED_PER_CHAIN,
                "ninguna cadena pasa su propio tope: lo unico que se excede es el global",
            )
        }
        assertTrue(5 <= Km52RetentionLimits.MAX_RECEIVE_CHAINS, "ni el numero de cadenas")

        // 2d. La unidad re-editada conserva la FORMA correcta: mismo magic,
        //     mismos campos de longitud coherentes. Si la re-edicion no cuidara
        //     eso, el rechazo seria por un `Km52FormatException` cualquiera y
        //     el test pasaria por la razon equivocada. Se comprueba leyendo el
        //     byte-string, sin pasar por el codec.
        assertEquals("KM52", String(inflada, 0, 4, Charsets.US_ASCII), "sigue siendo una KM52")
        // La version NO es un literal: es la que escribe el codec. Fijada a `1`
        // en `v1`, congelaba aqui una.version que 3Q.5.3 cambio a `2` y hacia
        // fallar un guion cuyo objeto no es la version.
        assertEquals(
            Km52Spec.UNIT_VERSION.toInt(), inflada[4].toInt(),
            "declara la version que escribe el codec",
        )
        assertEquals(inflada.size, le32(inflada, Km52Spec.TOTAL_LEN_OFFSET), "su totalLen describe los bytes que tiene")
        assertEquals(
            le32(inflada, Km52Spec.OUTBOUND_LEN_OFFSET) - Km52Spec.OUTBOUND_FIXED_LENGTH,
            le32(inflada, Km52Spec.CIPHERTEXT_LEN_OFFSET),
            "el ciphertext sigue siendo el ultimo campo del bloque OUTBOUND",
        )
        assertEquals(5, le16(inflada, Km52Spec.HEADER_LENGTH + Km52Spec.SNAPSHOT_FIXED_LENGTH + Km52Spec.SEND_CHAIN_LENGTH), "anuncia cinco cadenas")

        // 2e. Y el material reinsertado esta, byte a byte, EN la unidad y es
        //     el de la sesion viva. Se lee la unidad como BYTES —el codec no
        //     puede leerla todavia, que es justo lo que se quiere demostrar— y
        //     se comprueba que cada entrada aparece integra y que su clave es
        //     la que el ratchet derivo.
        val enLaUnidad = aReinsertar.keys.associateWith { entradasDeCadena(inflada, it).toSet() }
        for ((cadenaHex, entradas) in aReinsertar) {
            val leidas = enLaUnidad.getValue(cadenaHex)
            for (e in entradas) {
                assertTrue(
                    leidas.any { it.contentEquals(e) },
                    "la entrada ($cadenaHex, ${le32(e, 32)}) esta integra en la unidad",
                )
                val n = le32(e, 32).toUInt()
                val real = requireNotNull(
                    porCadena[cadenaHex]?.get(com.keymessage.core.ratchet.ChainIdentifier(hexABinary(cadenaHex)) to n),
                )
                assertContentEquals(
                    e.copyOfRange(36, 68), real,
                    "y su messageKey es la que el ratchet derivo, no una imitacion",
                )
            }
        }

        // ---- 3. `restore()` Y SU RECHAZO ------------------------------------------
        val store = FaultInjectingTransmitStore()
        store.sembrarConfirmado(inflada)
        val j = journal(bob, store)
        val e = assertThrows<RetentionBudgetExceeded> { j.restaurar() }
        assertEquals(RetentionRule.TOTAL_BYTES, e.rule, "el motivo es el presupuesto global, no el de una cadena")
        assertEquals(retenidas, e.actual, "cuantas claves retenidas traia")
        assertEquals(Km52RetentionLimits.MAX_TOTAL_RETAINED_BUDGET.toLong(), e.limit, "el techo")

        // ---- 4. EL ESTADO PREVIO SIGUE INTACTO, CON CRIPTOGRAFIA ----------------
        //
        // NO se comprueba con `stateFingerprint()`: la huella de
        // `DoubleRatchetSession` se construye con `dhSelf.publicKey` y no cubre
        // el escalar privado, asi que pasaria aunque la sesion hubiera cambiado
        // de mitad DH. Lo que no miente es el ciphertext del `encrypt()`
        // siguiente. El precedente es `KM52-01b` y `KM52-10`.
        assertEquals(cadenas, bob.receiveChainCount(), "ninguna cadena puede aparecer o desaparecer")
        assertEquals(pn, bob.currentPreviousChainLength(), "PN intacto")
        assertEquals(ns, bob.currentSendMessageNumber(), "Ns intacto")
        assertEquals(4000, retenidasDe(bob).size, "ninguna clave retenida ha desaparecido: ESO es no podar")
        assertContentEquals(
            frameDeControl(control), frameDeControl(bob),
            "y el frame siguiente sale byte a byte IDENTICO: la sesion no se ha movido",
        )
        assertEquals(0, j.ledger.tamanho(), "el libro de entrega sigue vacio: no hay estado a medias")

        // ---- 5. Y LA PRUEBA ANTI-PODA, POR EL OTRO LADO --------------------------
        // La unidad que una implementacion que podara al leer habria restaurado
        // tendria 3011 claves. La sesion intacta tiene 4000. Si el restaurador
        // hubiera hecho algo con ellas, la cuenta habria cambiado.
        assertEquals(4000, fotoPrevia.receiveChains.sumOf { it.ratchet.skipped.size }, "la foto de partida tenia 4000")
        assertTrue(
            retenidasDe(bob).size > Km52RetentionLimits.MAX_TOTAL_RETAINED_KEYS,
            "y siguen TODAS, mas de las que la unidad inflada declara legitimas: no se ha podado nada",
        )

        // ---- 6. DETERMINISMO -------------------------------------------------------
        repeat(3) { intento ->
            val otro = assertThrows<RetentionBudgetExceeded> { codec().deserialize(inflada) }
            assertEquals(e.rule, otro.rule, "intento $intento")
            assertEquals(e.actual, otro.actual, "intento $intento")
        }
        assertContentEquals(inflada, reeditar(base, aReinsertar), "y la misma re-edicion da los mismos bytes")
    }

    /** Todas las entradas de 68 B de una cadena, leidas del byte-string. */
    private fun entradasDeCadena(unidad: ByteArray, chainHex: String): List<ByteArray> {
        val cuerpo = unidad.copyOf(le32(unidad, Km52Spec.TOTAL_LEN_OFFSET) - Km52Spec.CHECKSUM_LENGTH)
        val p = offsetDeCadena(unidad, chainHex)
        val count = le16(cuerpo, p + Km52Spec.RECEIVE_CHAIN_LENGTH - 2)
        val base = p + Km52Spec.RECEIVE_CHAIN_LENGTH
        return (0 until count).map {
            cuerpo.copyOfRange(base + it * Km52Spec.SKIPPED_ENTRY_LENGTH, base + (it + 1) * Km52Spec.SKIPPED_ENTRY_LENGTH)
        }
    }

    /** Offset, dentro del byte-string de la unidad, de la cabecera de una cadena. */
    private fun offsetDeCadena(unidad: ByteArray, chainHex: String): Int {
        val cuerpo = unidad.copyOf(le32(unidad, Km52Spec.TOTAL_LEN_OFFSET) - Km52Spec.CHECKSUM_LENGTH)
        var p = Km52Spec.HEADER_LENGTH + 32 + 32
        if (cuerpo[p].toInt() == 1) p += 33 else p += 1
        p += 8 + Km52Spec.SEND_CHAIN_LENGTH
        val numCadenas = le16(cuerpo, p)
        p += 2
        for (c in 0 until numCadenas) {
            if (hex(cuerpo.copyOfRange(p, p + 32)) == chainHex) return p
            p += Km52Spec.RECEIVE_CHAIN_LENGTH +
                le16(cuerpo, p + Km52Spec.RECEIVE_CHAIN_LENGTH - 2) * Km52Spec.SKIPPED_ENTRY_LENGTH
        }
        throw IllegalArgumentException("la unidad no tiene la cadena $chainHex")
    }

    // ===================================================================
    // KM52B-11 — Fallo de restore
    // ===================================================================

    @Test
    @DisplayName("KM52B-11 un restore que falla deja la sesion anterior intacta")
    fun `KM52B-11 fallo de restore`() {
        val w = Wire()
        val a1 = dosEpochs(w)[1]
        val store = FaultInjectingTransmitStore()
        val j = journal(w.bobSession, store)
        j.persistir(envio(a1, ordinal = 1uL, n = 1))
        val buena = assertNotNull(store.readCommitted())
        val control = clonDe(w.bobSession)
        val antes = frameDeControl(control)

        // Cuatro formas de CONFIRMADO que no se puede leer. Todas dejan la
        // sesion donde estaba, y todas se distinguen entre si por el error
        // tipado: un `catch (e: Exception)` que las tratara igual no podria
        // decir si el medio esta roto o la unidad es ilegible.
        val casos: List<Triple<String, ByteArray, Class<out Throwable>>> = listOf(
            Triple(
                "checksum roto",
                buena.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() },
                Km52ChecksumMismatch::class.java,
            ),
            Triple("magic distinto", buena.copyOf().also { it[0] = 'X'.code.toByte() }, Km52BadMagic::class.java),
            Triple("truncada", buena.copyOf(buena.size - 5), Km52Truncated::class.java),
            Triple("sobrante", buena.copyOf(buena.size + 1).also { it[buena.size] = 1 }, Km52FormatException::class.java),
            Triple(
                "longitud contradictoria",
                buena.copyOf().also { it[Km52Spec.TOTAL_LEN_OFFSET] = (it[Km52Spec.TOTAL_LEN_OFFSET] + 1).toByte() },
                Km52FormatException::class.java,
            ),
        )
        assertEquals(5, casos.size, "el guion tiene que cubrir cinco formas de unidad ilegible")

        for ((nombre, bytes, tipo) in casos) {
            val s = FaultInjectingTransmitStore().apply { sembrarConfirmado(bytes) }
            val j2 = journal(w.bobSession, s)
            val e = org.junit.jupiter.api.Assertions.assertThrows(tipo) { j2.restaurar() }
            assertTrue(e.message!!.isNotEmpty(), "$nombre: el error dice por que")
            assertEquals(0, j2.ledger.tamanho(), "$nombre: no se registra nada a medias")
            assertFalse(j2.ledger.esDurable(mensaje(2)), "$nombre: el envio no aparece")
            assertContentEquals(
                frameDeControl(clonDe(w.bobSession)), frameDeControl(w.bobSession),
                "$nombre: un clon de la sesion y la sesion dan el mismo frame: no se ha movido",
            )
        }

        // Y la sesion EN SI: intacta, por criptografia y no por huella. El
        // control es un CLON de la sesion viva justo antes de cifrar: si el
        // ratchet se hubiera movido, los dos frames serian distintos.
        assertEquals(2, w.bobSession.receiveChainCount(), "las cadenas siguen siendo dos")
        assertEquals(3u, w.bobSession.currentPreviousChainLength(), "PN sigue siendo 3")
        assertContentEquals(
            frameDeControl(clonDe(w.bobSession)), frameDeControl(w.bobSession),
            "y el frame siguiente sale IDENTICO: un restore que falla no mueve el ratchet",
        )

        // Y un fallo de LECTURA del medio, que no es un problema de la unidad
        // sino del almacen, y que tambien deja la sesion intacta.
        val s2 = object : TransmitUnitStore by FaultInjectingTransmitStore() {
            override fun readCommitted(): ByteArray? =
                throw TransmitStoreFailure(TransmitStage.READ_BACK, "el medio no deja leer lo confirmado")
        }
        val j3 = journal(w.bobSession, s2)
        val e = assertThrows<TransmitStoreFailure> { j3.restaurar() }
        assertEquals(TransmitStage.READ_BACK, e.stage, "el fallo es del medio")
        assertEquals(0, j3.ledger.tamanho(), "y no se registra nada")
        assertContentEquals(
            frameDeControl(clonDe(w.bobSession)), frameDeControl(w.bobSession),
            "la sesion sigue donde estaba",
        )
    }

    // ===================================================================
    // KM52B-12 — AUDITORIA DE FRONTERA
    // ===================================================================

    @Test
    @DisplayName("KM52B-12 el ratchet no conoce la persistencia, ni la entrega, ni el transporte")
    fun `KM52B-12 auditoria de frontera`() {
        val base = File("src/main/kotlin/com/keymessage/core")
        assertTrue(base.exists(), "no se encuentra $base desde ${File(".").absolutePath}")
        val ratchet = File(base, "ratchet")
        val fuentesRatchet = ratchet.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(fuentesRatchet.size >= 5, "el guion tiene que auditar el paquete entero del ratchet")

        // --- 12a. El ratchet NO IMPORTA nada de la persistencia. ------------------
        // Ni del codec de la parte A (`km52`) ni del paquete de transmision de
        // este checkpoint. La direccion prohibida es `ratchet -> persistencia`:
        // si el ratchet pudiera nombrar el formato o el almacen, ya sabria que
        // existe una unidad que lo contiene, y esa es la frontera de §1.
        val paquetesProhibidos = listOf("com.keymessage.core.km52", "com.keymessage.core.transmit")
        val ofensas = mutableListOf<String>()
        for (archivo in fuentesRatchet) {
            archivo.readLines().forEachIndexed { i, linea ->
                for (paquete in paquetesProhibidos) {
                    if (linea.contains(paquete)) {
                        ofensas += "${archivo.name}:${i + 1} nombra '$paquete' -> ${linea.trim()}"
                    }
                }
            }
        }
        assertTrue(
            ofensas.isEmpty(),
            "el ratchet no puede conocer la persistencia:\n" + ofensas.joinToString("\n") { "  - $it" },
        )

        // --- 12b. Y el ratchet no puede nombrar sus tipos, ni siquiera sin
        //          importar. Un nombre de tipo en un KDoc no rompe nada HOY y
        //          es exactamente por donde empieza la dependencia: alguien lo
        //          lee, lo toma como precedente y escribe el `import`.
        val tiposDePersistencia = listOf("Km52Unit", "Km52UnitCodec", "OutboundRecord", "PreparedSend", "SecureTransmitJournal", "TransmitUnitStore", "ChainRetentionBook", "TransmitLedger")
        val nombresDeTipo = mutableListOf<String>()
        for (archivo in fuentesRatchet) {
            archivo.readLines().forEachIndexed { i, linea ->
                for (tipo in tiposDePersistencia) {
                    if (linea.contains(tipo)) {
                        nombresDeTipo += "${archivo.name}:${i + 1} nombra '$tipo' -> ${linea.trim()}"
                    }
                }
            }
        }
        assertTrue(
            nombresDeTipo.isEmpty(),
            "el ratchet no puede conocer los tipos de la persistencia:\n" + nombresDeTipo.joinToString("\n") { "  - $it" },
        )

        // --- 12c. Ningun concepto de las capas SUPERIORES en el ratchet. ----------
        // La lista es la de `SNAP-04`, ampliada con lo que este checkpoint
        // anade. Limite de palabra: `ack` no puede aparecer dentro de
        // "package" ni de "stack", pero si como identificador.
        val prohibidos = listOf(
            "messageId", "ack", "delivery", "retransmit", "retry", "transport",
            "pendingMessage", "applicationDelivered", "delivered", "conversation", "peerId",
            "outbound", "writeAhead", "readBack", "RetentionBudget", "transmit",
        )
        val nombres = mutableListOf<String>()
        for (archivo in fuentesRatchet) {
            archivo.readLines().forEachIndexed { i, linea ->
                for (token in prohibidos) {
                    if (Regex("(?i)\\b" + Regex.escape(token) + "\\b").containsMatchIn(linea)) {
                        nombres += "${archivo.name}:${i + 1} contiene '$token' -> ${linea.trim()}"
                    }
                }
            }
        }
        assertTrue(
            nombres.isEmpty(),
            "la costura del ratchet no puede hablar de las capas superiores:\n" + nombres.joinToString("\n") { "  - $it" },
        )

        // --- 12d. LA DIRECCION INVERSA: la capa le da al ratchet SOLO estado
        //          criptografico. Se comprueba AUDITANDO LAS LLAMADAS, que es
        //          lo unico que el ratchet puede llegar a saber: no lo que la
        //          costura tiene guardado, sino a que le pregunta.
        val costura = File(base, "transmit")
        assertTrue(costura.exists(), "no se encuentra $costura")
        val llamadas = mutableSetOf<String>()
        val donde = mutableListOf<String>()
        for (archivo in costura.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            archivo.readLines().forEachIndexed { i, linea ->
                for (m in Regex("""\bsession\.(\w+)""").findAll(linea)) {
                    llamadas += m.groupValues[1]
                    donde += "${archivo.name}:${i + 1} session.${m.groupValues[1]}"
                }
            }
        }
        assertTrue(donde.isNotEmpty(), "el guion tiene que encontrar llamadas al ratchet")
        val permitidas = setOf("snapshot", "restore")
        val sobrantes = (llamadas - permitidas).sorted()
        assertTrue(
            sobrantes.isEmpty(),
            "la capa de transmision solo puede pedir `snapshot()` y `restore()` al ratchet, porque solo de eso " +
                "es dueno el ratchet. Se le llamo ademas: $sobrantes\n" + donde.joinToString("\n") { "  - $it" },
        )

        // --- 12e. Y la composicion ocurre ARRIBA, no en el ratchet. ---------------
        val compuestas = base.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                f.readLines().withIndex()
                    .filter { (_, l) -> l.contains("Km52Unit(") && !l.trimStart().startsWith("class") }
                    .map { (i, l) -> "${f.name}:${i + 1}" to l.trim() }
            }
            .toList()
        assertTrue(compuestas.isNotEmpty(), "el guion tiene que encontrar la composicion de la unidad")
        // Dos sitios y ninguno mas: el que COMPONE (la costura) y el que
        // DEVUELVE la unidad ya validada (el lector del codec). El segundo no
        // compone nada: construye exactamente lo que acaba de leer.
        val permitidos = setOf("SecureTransmitJournal.kt", "Km52Codec.kt")
        val fuera = compuestas.filterNot { it.first.substringBefore(":") in permitidos }
        assertTrue(
            fuera.isEmpty(),
            "la unidad se compone en la costura de transmision y en ningun otro sitio:\n" +
                fuera.joinToString("\n") { "  - $it" },
        )

        // --- 12f. Y `km-core` no depende de `km-webrtc` por ninguna via. ---------
        // No lo anade este checkpoint, pero es la condicion de que la
        // auditoria de arriba signifique algo: si el ratchet pudiera importar
        // transporte, "no conoce la persistencia" seria un detalle de nombre.
        val build = File("build.gradle.kts").readText()
        assertFalse(build.contains("webrtc"), "km-core no puede declarar una dependencia de km-webrtc")
        assertFalse(build.contains("dev.onvoid"), "km-core no puede depender de una libreria de WebRTC (R3-12)")
    }

    // ===================================================================
    // KM52B-13 — `DeliveryRecordTable.export()` (C-01)
    // ===================================================================

    @Test
    @DisplayName("KM52B-13 el registro de entrega se puede exportar con orden determinista (C-01)")
    fun `KM52B-13 export del registro de entrega`() {
        val t = DeliveryRecordTable(capacity = 4)
        val ids = (1..3).map { FrameIdentity.of(det(it).publicKey, it.toUInt(), (it * 2).toUInt()) }
        val mensajes = (0 until 3).map { mensaje(it) }

        // Se llena en un orden que NO es el de la identidad y se consulta en
        // otro, para que un `export` que devolviera el recorrido del mapa
        // saliera distinto del canonico.
        t.record(ids[2], mensajes[2])
        t.record(ids[0], mensajes[0])
        t.lookup(ids[0])          // toca el LRU: ids[0] pasa a ser el mas reciente
        t.record(ids[1], mensajes[1])

        val salida = t.export()
        assertEquals(3, salida.size, "export sale TODAS las entradas: el registro no es un callejon sin salida")
        // El orden canonico se CALCULA, no se supone: el guion mete las
        // identidades en un orden cualquiera y el esperado sale de ordenarlas.
        val esperado = listOf(
            ids[0] to mensajes[0],
            ids[1] to mensajes[1],
            ids[2] to mensajes[2],
        ).sortedBy { hex(it.first.bytes) }
        assertEquals(
            esperado.map { hex(it.first.bytes) },
            salida.map { hex(it.frameIdentity.bytes) },
            "y en orden canonico por identidad, no en el de insercion ni en el LRU",
        )
        assertEquals(
            esperado.map { it.second },
            salida.map { it.messageId },
            "cada entrada conserva la DIRECCION: identidad -> mensaje",
        )

        // Determinista: dos exportaciones seguidas son iguales, y una tercera
        // tras consultar otra cosa tambien. El recorrido de un `LinkedHashMap`
        // con `accessOrder` depende de QUE SE HA MIRADO, y una lista que se
        // persiste no puede depender de eso.
        assertEquals(salida, t.export(), "exportar dos veces da lo mismo")
        t.lookup(ids[2])
        assertEquals(salida, t.export(), "y consultar en medio no cambia el orden canonico")

        // Reimportacion: `clear()` + `record()`, que ya existian, bastan. Por
        // eso `export` es suficiente y no hace falta un metodo de mas.
        val copia = DeliveryRecordTable(capacity = 4)
        t.export().forEach { copia.record(it.frameIdentity, it.messageId) }
        assertEquals(t.export(), copia.export(), "la ida y la vuelta por el registro es fiel")
        assertEquals(mensajes[1], copia.lookup(ids[1]), "y se sigue encontrando por identidad")

        // Y el tipo de la entrada declara los dos campos: la persistencia
        // necesita saber cual es cual, y un `Pair` no lo dice.
        val e: DeliveryRecordEntry = salida[0]
        assertEquals(esperado[0].first, e.frameIdentity, "la entrada lleva la identidad")
        assertEquals(esperado[0].second, e.messageId, "y el mensaje")
        assertEquals(salida[0], e, "y una entrada con el mismo contenido es la misma entrada")
    }

    // ===================================================================
    // UTILIDAD DE LAS PRUEBAS: INFLAR UNA UNIDAD REAL
    // ===================================================================

    /**
     * REEDIFICAR una unidad real anadiendole entradas a sus cadenas.
     *
     * ## POR QUE ESTA FUNCION Y NO UN ENSAMBLADOR
     *
     * El codec NUNCA escribe una unidad sobredimensionada: al escribir poda
     * (§6.9.2). El byte-string que necesitan los escenarios de lectura no se
     * puede obtener del camino de escritura, asi que hay que construirlo. Lo
     * que se hace aqui es lo mas fiel posible: se parte de una unidad que
     * `serialize` ha producido, se recorre SU bloque SNAPSHOT con SUS offsets
     * del layout (§3.1), y se le anaden entradas de 68 B a las cadenas que
     * indique el llamante. El resto de la unidad —la cabecera, la clave raiz,
     * el par DH, la cadena de envio, el bloque OUTBOUND y el registro de
     * salida— es el que escribio el codec, byte a byte.
     *
     * El checksum se re-deriva con el mismo `Hash` de produccion, porque una
     * unidad con el checksum viejo seria rechazada por `Km52ChecksumMismatch` y
     * el escenario no probaria lo que dice probar.
     *
     * @param base una unidad producida por `serialize`.
     * @param anadidas por cada `chainId` en hexadecimal, las entradas de 68 B
     *   (`chainId + N + messageKey`) que se le anaden a ESA cadena. La entrada
     *   lleva su propio `chainId`: es la identidad de la clave, no una etiqueta.
     */
    private fun reeditar(base: ByteArray, anadidas: Map<String, List<ByteArray>>): ByteArray {
        val totalLen = le32(base, Km52Spec.TOTAL_LEN_OFFSET)
        val outboundLen = le32(base, Km52Spec.OUTBOUND_LEN_OFFSET)
        val inboundLen = le32(base, Km52Spec.PENDING_INBOUND_LEN_OFFSET)
        require(base.size - Km52Spec.CHECKSUM_LENGTH == totalLen - Km52Spec.CHECKSUM_LENGTH) {
            "no es una unidad canonica"
        }
        // El guion solo CAMBIA el SNAPSHOT. El bloque PENDING_INBOUND se copia
        // tal cual y DESPUES, porque `v2` lo pone entre el OUTBOUND y el
        // checksum: contarlo como si el OUTBOUND fuera el ultimo bloque —
        //como en `v1`— descuadraba el final del SNAPSHOT por los bytes que
        //ocupa la bandeja, que son los 4 del `count` cuando esta vacia.
        require(inboundLen >= Km52Spec.PENDING_INBOUND_COUNT_LENGTH) {
            "una unidad v2 siempre lleva el bloque PENDING_INBOUND: $inboundLen"
        }
        val cuerpo = base.copyOf(totalLen - Km52Spec.CHECKSUM_LENGTH)

        var p = Km52Spec.HEADER_LENGTH
        p += 32                                   // rootKey
        p += 32                                   // dhSelfScalar
        // `dhRemotePresent(1)` + `dhRemote(32 opt)`: los DOS campos cuentan, y
        // saltarse el byte de presencia descentraria todo lo que viene
        // despues —que es donde se lee el numero de cadenas.
        if (cuerpo[p].toInt() == 1) p += 33 else p += 1
        p += 8                                    // Ns, PN
        p += Km52Spec.SEND_CHAIN_LENGTH           // sendChain
        val numCadenas = le16(cuerpo, p)
        p += 2

        // El header se copia tal cual y se corrige al final: sus dos
        // longitudes describen el bloque que todavia no se ha construido.
        val out = ByteArrayOutputStream()
        out.write(cuerpo, 0, Km52Spec.HEADER_LENGTH)
        out.write(cuerpo, Km52Spec.HEADER_LENGTH, p - Km52Spec.HEADER_LENGTH)

        var anadidasTotales = 0
        for (c in 0 until numCadenas) {
            val chainId = cuerpo.copyOfRange(p, p + 32)
            val cabecera = cuerpo.copyOfRange(p, p + Km52Spec.RECEIVE_CHAIN_LENGTH)
            val actuales = le16(cuerpo, p + Km52Spec.RECEIVE_CHAIN_LENGTH - 2)
            val reales = (0 until actuales).map {
                cuerpo.copyOfRange(
                    p + Km52Spec.RECEIVE_CHAIN_LENGTH + it * Km52Spec.SKIPPED_ENTRY_LENGTH,
                    p + Km52Spec.RECEIVE_CHAIN_LENGTH + (it + 1) * Km52Spec.SKIPPED_ENTRY_LENGTH,
                )
            }
            p += Km52Spec.RECEIVE_CHAIN_LENGTH + actuales * Km52Spec.SKIPPED_ENTRY_LENGTH

            val extra = anadidas[hex(chainId)].orEmpty()
            val salidas = reales + extra
            require(salidas.size <= Km52RetentionLimits.MAX_RETAINED_PER_CHAIN) {
                "el guion no puede pasar la cota por cadena: el rechazo seria por otro motivo"
            }
            anadidasTotales += extra.size
            // El indice de recepcion tiene que quedar ESTRICTAMENTE por encima
            // de todas las N, o el lector rechazaria por otra causa y el
            // escenario no probaria lo que dice probar.
            // Una cadena sin retenidas no tiene N maxima; se le deja Nr = 0,
            // que es lo que ya traia y lo unico que el lector acepta sin claves.
            val maxN = if (salidas.isEmpty()) -1 else salidas.maxOf { le32(it, 32) }
            putLe32(cabecera, Km52Spec.RECEIVE_CHAIN_LENGTH - 6, maxN + 1)
            putLe16(cabecera, Km52Spec.RECEIVE_CHAIN_LENGTH - 2, salidas.size)
            out.write(cabecera)
            salidas.forEach { out.write(it) }
        }
        // Al terminar las cadenas, `p` marca el principio del bloque OUTBOUND:
        // el final del SNAPSHOT es donde empieza ese bloque, y despues viene el
        // PENDING_INBOUND (`v2`) antes del checksum.
        val finSnapshot = totalLen - Km52Spec.CHECKSUM_LENGTH - inboundLen - outboundLen
        require(p == finSnapshot) { "la relectura no consumio el bloque SNAPSHOT entero: $p != $finSnapshot" }
        require(anadidasTotales > 0) { "el guion tiene que anadir algo" }
        out.write(cuerpo, p, outboundLen)
        out.write(cuerpo, p + outboundLen, inboundLen.toInt())

        val nuevoCuerpo = out.toByteArray()
        val header = nuevoCuerpo.copyOf(Km52Spec.HEADER_LENGTH)
        putLe32(
            header, Km52Spec.SNAPSHOT_LEN_OFFSET,
            nuevoCuerpo.size - Km52Spec.HEADER_LENGTH - outboundLen - inboundLen,
        )
        putLe32(header, Km52Spec.TOTAL_LEN_OFFSET, nuevoCuerpo.size + Km52Spec.CHECKSUM_LENGTH)
        val completo = nuevoCuerpo.copyOf()
        System.arraycopy(header, 0, completo, 0, Km52Spec.HEADER_LENGTH)
        return completo + hash.sha256(completo)
    }

    /**
     * Una unidad real con [porCadena] claves retenidas en CADA cadena.
     *
     * Las N que ya estan se conservan con su clave REAL; las nuevas clonan la
     * clave real de la primera cadena con retenidas, variando un byte para que
     * no sean todas iguales: mil claves identicas no las produce ningun
     * ratchet, y el rechazo tendria dos causas.
     */
    private fun agrandando(base: ByteArray, porCadena: Int): ByteArray {
        val totalLen = le32(base, Km52Spec.TOTAL_LEN_OFFSET)
        val cuerpo = base.copyOf(totalLen - Km52Spec.CHECKSUM_LENGTH)
        var p = Km52Spec.HEADER_LENGTH + 32 + 32
        if (cuerpo[p].toInt() == 1) p += 33 else p += 1
        p += 8 + Km52Spec.SEND_CHAIN_LENGTH
        val numCadenas = le16(cuerpo, p)
        p += 2

        var plantilla: ByteArray? = null
        val porCadenaActual = LinkedHashMap<String, Int>()
        val maxNPorCadena = LinkedHashMap<String, Int>()
        val ids = LinkedHashMap<String, ByteArray>()
        for (c in 0 until numCadenas) {
            val chainId = cuerpo.copyOfRange(p, p + 32)
            val actuales = le16(cuerpo, p + Km52Spec.RECEIVE_CHAIN_LENGTH - 2)
            val h = hex(chainId)
            ids[h] = chainId
            porCadenaActual[h] = actuales
            var maxN = -1
            for (k in 0 until actuales) {
                val e = cuerpo.copyOfRange(
                    p + Km52Spec.RECEIVE_CHAIN_LENGTH + k * Km52Spec.SKIPPED_ENTRY_LENGTH,
                    p + Km52Spec.RECEIVE_CHAIN_LENGTH + (k + 1) * Km52Spec.SKIPPED_ENTRY_LENGTH,
                )
                if (plantilla == null) plantilla = e.copyOfRange(36, 68)
                maxN = maxOf(maxN, le32(e, 32))
            }
            maxNPorCadena[h] = maxN
            p += Km52Spec.RECEIVE_CHAIN_LENGTH + actuales * Km52Spec.SKIPPED_ENTRY_LENGTH
        }
        val baseClave = requireNotNull(plantilla) { "el guion necesita al menos una clave retenida real que clonar" }

        val anadidas = LinkedHashMap<String, List<ByteArray>>()
        for (h in porCadenaActual.keys) {
            val faltan = porCadena - porCadenaActual.getValue(h)
            require(faltan >= 0) { "la cadena $h ya tiene mas de $porCadena retenidas" }
            val nuevas = (0 until faltan).map { i ->
                val n = maxNPorCadena.getValue(h) + 1 + i
                val clave = baseClave.copyOf()
                clave[0] = (n.toLong() and 0xFF).toByte()
                entrada(ids.getValue(h), n.toUInt(), clave)
            }
            anadidas[h] = nuevas
        }
        return reeditar(base, anadidas)
    }

    private fun cuerpoAChequeo(bytes: ByteArray): Int = bytes.size - Km52Spec.CHECKSUM_LENGTH

    private fun entrada(chainId: ByteArray, n: UInt, clave: ByteArray): ByteArray {
        val e = ByteArray(Km52Spec.SKIPPED_ENTRY_LENGTH)
        System.arraycopy(chainId, 0, e, 0, 32)
        putLe32(e, 32, n.toInt())
        System.arraycopy(clave, 0, e, 36, 32)
        return e
    }
}
