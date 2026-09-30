package com.km.messaging

import com.km.auth.AuthChallenge
import com.km.auth.AuthOk
import com.km.auth.AuthSession
import com.km.auth.Clock
import com.km.auth.TranscriptBuilder
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.crypto.Hash
import com.km.crypto.HashImpl
import com.km.crypto.Kdf
import com.km.crypto.KeyPair
import com.km.crypto.X25519KeyPair
import com.km.crypto.provider.BcChaCha20Poly1305
import com.km.crypto.provider.BcHkdfSha256
import com.km.crypto.provider.BcX25519
import com.km.unit.BinaryKm52UnitCodec
import com.km.unit.Km52UnitCodec
import com.km.unit.OutboundDeliveryState
import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.model.NodeAnnouncement
import com.km.model.NodeAnnouncementJsonCodec
import com.km.model.NodeIdentity
import com.km.node.EstablishmentResult
import com.km.node.EstablishmentState
import com.km.node.PeerContext
import com.km.node.PeerTransportManager
import com.km.node.ScriptableEstablishment
import com.km.node.ScriptableTransportBackend
import com.km.node.TransportBackend
import com.km.node.TransportChain
import com.km.node.TransportError
import com.km.node.TransportEstablishment
import com.km.node.TransportEventCallbacks
import com.km.node.TransportResult
import com.km.ratchet.DoubleRatchetSession
import com.km.ratchet.DoubleRatchetSnapshot
import com.km.frame.SecureFrameProtector
import com.km.frame.SecureFrameSpec
import com.km.frame.SecureRatchetProtocol
import com.km.transmit.FileTransmitUnitStore
import com.km.transmit.PreparedSend
import com.km.transmit.SecureTransmitJournal
import com.km.transmit.X25519Determinista
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/* ==================================================================== *
 * UTILIDADES DE BYTES A NIVEL DE ARCHIVO.
 *
 * Fuera de la clase de test por el motivo de siempre: las clases que arman
 * guiones NO ven los miembros de su clase externa, y su "unresolved reference"
 * es indistinguible del de un tipo inexistente. Dos fallos que se confunden.
 * ==================================================================== */

private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

/** Los 16 bytes canonicos de un UUID (RFC 4122, orden de red). */
private fun uuidCanónico(u: UUID): ByteArray {
    val out = ByteArray(16)
    ByteBuffer.wrap(out).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits)
    return out
}

/** Indice de la primera aparicion de `aguja` en `paja`, o `-1`. */
private fun indiceDe(aguja: ByteArray, paja: ByteArray): Int {
    if (aguja.isEmpty() || paja.size < aguja.size) return -1
    for (i in 0..(paja.size - aguja.size)) {
        var igual = true
        for (j in aguja.indices) {
            if (paja[i + j] != aguja[j]) {
                igual = false
                break
            }
        }
        if (igual) return i
    }
    return -1
}

/* ==================================================================== *
 * EL MEDIO: UN RELAY QUE NO PUEDE INTERPRETAR NADA.
 * ==================================================================== *
 *
 * ## POR QUE ESTE DOBLE Y NO `ScriptableTransportBackend`
 *
 * Porque el requisito que hay que medir es NEGATIVO —el relay NO interpreta
 * `messageId`, ni ACK, ni plaintext, ni estado del ratchet— y un doble que
 * solo sabe registrar bytes no puede demostrar una AUSENCIA: solo puede
 * demostrar que los bytes que registró son los correctos.
 *
 * Este doble es opaco POR ESTRUCTURA, y esa opacidad es lo que se mide:
 *
 *  1. Su unico estado observable son `ByteArray` VERBATIM.
 *  2. [sendData] copia y nada mas: no hay `if`, ni parseo, ni validacion, ni
 *     re-cifrado, ni decision posible sobre bytes opacos.
 *  3. Por (2), [entregar] devuelve EXACTAMENTE lo que se le dio, y eso incluye
 *     un frame manipulado. Un relay que interpretara algo tendria que DECIDIR,
 *     y una decision sobre un frame roto se notaria.
 *
 * [parsesIntentados] existe y esta siempre en cero porque el doble NO tiene
 * forma de incrementarlo. Por eso la prueba NO se apoya en el: se apoya en
 * (3), con un ejemplo MUERTO. Un clasificador que no se dispara con un ejemplo
 * muerto no puede decir que no ha visto nada en la medicion de verdad.
 *
 * ## POR QUE NO ES `RelayServiceImpl`
 *
 * Porque `RelayServiceImpl` es OTRA CAPA (KM-0004) y este checkpoint no la
 * toca. Aqui el relay es lo que la cadena de transporte necesita: un medio
 * mas, opaco, determinista, sin red, sin `TimeUnit` y sin esperas.
 * ==================================================================== */

class RelayDeTransporte(
    override val name: String = "Relay",
) : TransportBackend {

    override var eventCallbacks: TransportEventCallbacks = TransportEventCallbacks()

    /** Los payloads, BYTE A BYTE y en orden. Lo unico que este relay sabe. */
    val recibidos = CopyOnWriteArrayList<ByteArray>()

    /**
     * Siempre cero: el doble no tiene ninguna rama que lo incremente.
     *
     * Es un testigo NEGATIVO DECLARADO, no una medicion. La medicion es el
     * ejemplo muerto de la cabecera.
     */
    val parsesIntentados: Int get() = 0

    override fun initialize(): TransportResult<Unit> = TransportResult.Success(Unit)

    override fun onCreateBinding(localIdentity: IdentityId, remotePeerId: IdentityId) =
        TransportResult.Success(Unit)

    override fun onLocalSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String) =
        TransportResult.Success(Unit)

    override fun onRemoteSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String) =
        TransportResult.Success(Unit)

    override fun onIceCandidate(localIdentity: IdentityId, remotePeerId: IdentityId, candidate: String) =
        TransportResult.Success(Unit)

    override fun onCloseBinding(localIdentity: IdentityId, remotePeerId: IdentityId) =
        TransportResult.Success(Unit)

    override fun sendData(remotePeerId: IdentityId, data: ByteArray): TransportResult<Unit> {
        recibidos.add(data.copyOf())
        return TransportResult.Success(Unit)
    }

    override fun shutdown(): TransportResult<Unit> = TransportResult.Success(Unit)

    /** Lo que el relay ha recibido, VERBATIM. No lo toca. */
    fun entregar(indice: Int = 0): ByteArray = recibidos[indice].copyOf()
}

/* ==================================================================== *
 * UN ESTABLECIMIENTO QUE EXPLOTA.
 * ==================================================================== *
 *
 * ## POR QUE HACE FALTA Y POR QUE NO REUTILIZA `ScriptableEstablishment`
 *
 * Porque el invariante que hay que medir es el de una EXCEPCION durante el
 * establecimiento —"una excepcion del establecimiento no debe dejar una sesion
 * criptografica parcialmente mutada"— y `ScriptableEstablishment` solo sabe
 * FALLAR con honestidad: devuelve un `TransportResult.Failure`. Una
 * implementacion que LANZA es otra clase de defecto, y es la que puede tirar
 * una ronda entera. `TransportChain.runEstablishment` la captura a proposito;
 * este doble existe para poder provocarla.
 *
 * Es una clase final, como `ScriptableEstablishment`, y por eso no se puede
 * heredar de el: hace falta una implementacion propia de [TransportEstablishment].
 * ==================================================================== */

class EstablecimientoQueExplota(
    override val transportName: String,
    /** Fase en la que se lanza: `initialize`, `createBinding`, `negotiate` o `awaitReady`. */
    private val explotaEn: String = "negotiate",
) : TransportEstablishment {

    override var state: EstablishmentState = EstablishmentState.CREATED
        private set

    val fases = CopyOnWriteArrayList<String>()

    @Volatile var abortos: Int = 0
        private set

    /** Cuando es `true`, el propio `abort()` revienta. */
    @Volatile var abortExplota: Boolean = false

    /**
     * Se ejecuta justo ANTES de lanzar, y es el gancho de verificacion.
     *
     * Existe para el ARNES DE MUTACION: un establecimiento al que se le hace
     * ademas tocar la sesion criptografica. Es el defecto que `MIG-06` tiene que
     * cazar, y no se puede provocar sin un sitio donde colgarlo —porque el doble
     * por defecto no tiene por que saber nada de la sesion, que es justamente lo
     * que lo hace honesto.
     */
    @Volatile var alMorir: (() -> Unit)? = null

    private fun fase(nombre: String, destino: EstablishmentState): TransportResult<Unit> {
        fases.add(nombre)
        if (nombre == explotaEn) {
            alMorir?.invoke()
            throw IllegalStateException("$transportName revienta en $nombre")
        }
        state = destino
        return TransportResult.Success(Unit)
    }

    override fun initialize(): TransportResult<Unit> = fase("initialize", EstablishmentState.CREATED)

    override fun createBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> =
        fase("createBinding", EstablishmentState.CREATED)

    override fun negotiate(): TransportResult<Unit> = fase("negotiate", EstablishmentState.NEGOTIATING)

    override fun awaitReady(timeoutMs: Long): TransportResult<Unit> =
        fase("awaitReady", EstablishmentState.READY)

    override fun abort() {
        abortos++
        if (abortExplota) throw IllegalStateException("$transportName no se puede cerrar")
        state = EstablishmentState.CLOSED
    }
}

/* ==================================================================== *
 * EL GUION CRIPTOGRAFICO DE LA MIGRACION.
 * ==================================================================== *
 *
 * ## POR QUE UN GUION PROPIO Y NO `GuionDeterminista`
 *
 * Porque `GuionDeterminista` deja `alice` y `aliceSession` en privado, y el
 * experimento necesita a ALICE como la sesion que migra: es la que produce
 * `ciphertext_pre` Y la que tiene el inbound pendiente. Usar su
 * `aliceCifra()` y sus frames seria usar una sesion DISTINTA de la que
 * `SecureMessagingSession` envuelve, y "la sesion que migra" dejaria de ser
 * una sola.
 *
 * Lo que SI se reutiliza, y es lo caro: [X25519Determinista], que quita el
 * azar de la generacion de claves sin tocar ni una linea de produccion. Con
 * el, dos ejecuciones parten del MISMO estado previo, que es la precondicion
 * de INV-07.
 *
 * ## QUE DEJA EL GUION
 *
 * ```
 *   ALICE                         BOB
 *   epoca 1: a0(N=0) ----------->  abre la cadena 1  (Nr=1)
 *            a1(N=1)  [retenida]  clave saltada N=1
 *            a2(N=2)  [retenida]  clave saltada N=2
 *   epoca 2:        <-----------  b0(N=0)   ALICE abre la epoca 2 (DH #1)
 *            a3(N=0) ----------->  BOB abre la epoca 2    (DH #2)
 *            a4(N=1)  [suelta]   pendiente; BOB SI la podria abrir
 * ```
 *
 * Contador de claves generadas al terminar: 3 —`initiateEpoch` de ALICE, el
 * giro de ALICE en `b0` y el de BOB en `a3`. Los frames que quedan SUELTOS no
 * abren ninguna epoca, asi que despues de `dosEpochs()` el contador sigue en 3
 * y todos los clones hechos en ese punto comparten el generador en el MISMO
 * punto. Es lo que hace comparables sus `encrypt()`.
 *
 * ## LO QUE DEJA LA FOTO DE ALICE
 *
 * ```
 *   receiveChains = { DH original de BOB: Nr=3 }        <- UNA sola cadena
 *   sendMessageNumber = 2   (a3=0 y a4=1 ya emitidos)
 *   previousChainLength = 3
 *   dhRemote = DH original de BOB
 * ```
 *
 * El siguiente frame de BOB lleva una DH NUEVA, que ALICE no tiene en ninguna
 * cadena: abrirlo crearia una SEGUNDA cadena. Eso es lo que hace observable
 * "el pendiente no se ha abierto" sin depender de ningun instrumento raro.
 * ==================================================================== */

class GuionDeMigracion(
    /**
     * Prefijo de las semillas. Dos guiones con prefijos distintos son dos
     * sesiones DISTINTAS con la misma forma de estado: es el contraejemplo que
     * da poder de discriminacion a la comparacion de bytes.
     */
    private val semilla: String = "MIG",
) {
    private val hashReal: Hash = HashImpl()

    val x25519: X25519Determinista = X25519Determinista(BcX25519(), hashReal, "MIG/$semilla/DH")
    val kdf: Kdf = BcHkdfSha256()
    val protector: SecureFrameProtector = SecureFrameProtector(BcChaCha20Poly1305(), kdf)
    val hash: Hash = hashReal

    private val rootKey = ByteArray(32) { ((it + 1) and 0xFF).toByte() }

    /** Par DH derivado de una etiqueta. Misma etiqueta, mismos bytes. */
    fun par(etiqueta: String): X25519KeyPair {
        val sk = hash.sha256("MIG/$semilla/$etiqueta".toByteArray())
        return X25519KeyPair(sk, x25519.publicKey(sk))
    }

    fun sesion(dhSelf: X25519KeyPair, dhRemote: ByteArray?): DoubleRatchetSession =
        DoubleRatchetSession(
            rootKey = rootKey,
            dhSelf = dhSelf,
            dhRemote = dhRemote,
            sendChainKey = ByteArray(32) { ((it * 3 + 1) and 0xFF).toByte() },
            receiveChainKey = ByteArray(32) { ((it * 5 + 2) and 0xFF).toByte() },
            x25519 = x25519,
            kdf = kdf,
        )

    val aliceSession: DoubleRatchetSession = sesion(par("alice"), par("bob").publicKey)
    val bobSession: DoubleRatchetSession = sesion(par("bob"), null)

    val alice: SecureRatchetProtocol = SecureRatchetProtocol(aliceSession, protector)
    val bob: SecureRatchetProtocol = SecureRatchetProtocol(bobSession, protector)

    /** Los frames de ALICE, en orden de emision. */
    var frames: List<ByteArray> = emptyList()
        private set

    init {
        aliceSession.initiateEpoch()
    }

    fun codec(): Km52UnitCodec = BinaryKm52UnitCodec(x25519, hash)

    /** El guion completo. Ver el diagrama de la cabecera. */
    fun dosEpochs() {
        frames = listOf(
            enviar("a0", alice, bob),
            alice.encrypt("a1".toByteArray()),   // N=1, epoca 1: RETENIDA en BOB
            alice.encrypt("a2".toByteArray()),   // N=2, epoca 1: RETENIDA en BOB
            enviar("b0", bob, alice),            // ALICE abre la epoca 2
            enviar("b1", bob, alice),
            enviar("b2", bob, alice),
            enviar("a3", alice, bob),            // BOB abre su epoca 2
            alice.encrypt("a4".toByteArray()),   // epoca 2, N=1: SUELTA
        )
    }

    private fun enviar(texto: String, de: SecureRatchetProtocol, a: SecureRatchetProtocol): ByteArray {
        val w = de.encrypt(texto.toByteArray())
        val r = a.decrypt(w)
        check(r is SecureRatchetProtocol.DecryptResult.Ok) { "esperaba Ok en '$texto', fue $r" }
        return w
    }
}

/* ==================================================================== *
 * 3Q.5.3 FASE 4 — LA MIGRACION DE TRANSPORTE.
 * ==================================================================== *
 *
 * Prefijo `MIG-`. Es la ULTIMA frontera del checkpoint: las fases 1 y 3
 * demuestran que la bandeja `PendingInbound` EXISTE y que PERSISTE. Lo que
 * faltaba era que la bandeja PERTENEZCA a la sesion que migra.
 *
 * ## EL EXPERIMENTO
 *
 * ```
 * Sesion criptografica S  = la sesion de ALICE
 *         |
 *         +-- ciphertext_pre                      <- lo que S cifro, por P2P
 *         |
 *         +-- inbound pendiente recibido por P2P  <- llego, TODAVIA NO descifrado
 *         v
 * P2P falla o muere
 *         v
 * persistencia KM52 v2
 *         v
 * restauracion de la MISMA sesion
 *         v
 * Relay
 *         +-- ciphertext_post == ciphertext_pre   (byte a byte)
 *         +-- Bob decrypt() == el mensaje original
 * ```
 *
 * ## LO QUE NO SE HACE, Y POR QUE
 *
 *  - **No se crea una API `migrate()`.** [TransportChain.establish] YA
 *    expresa la semantica: una ronda nueva invalida la seleccion anterior,
 *    concede autoridad a un solo medio y cierra a los perdedores. `MIG-09` lo
 *    comprueba EJERCICANDOLO y ademas leyendo el codigo, con el lector
 *    auditado a si mismo para que su verde no pueda ser el de un lector que
 *    no lee.
 *  - **No se toca `SecureMessagingSession`.** La cadena es UN
 *    [TransportBackend] de la lista, no un objeto por medio: cambiar de P2P a
 *    Relay cambia el ESLABON CON AUTORIDAD, no el backend. `MIG-10` deja
 *    constancia, incluida la lectura del fuente de la sesion.
 *  - **No se toca `ratchet/`, ni el codec, ni la bandeja, ni el journal.**
 *    Esta fase solo anade pruebas.
 *
 * ## LO QUE ESTA FUERA DE ALCANCE Y SE DEJA DICHO
 *
 * `SecureMessagingSession` sigue guardando los bytes entrantes en un
 * `ArrayDeque<ByteArray>` propio, y ese deque NO es la [PendingInbox] que
 * persiste el journal. Aqui la instancia compartida se construye a mano y se
 * pasa a los dos, que es lo que declara [SecureTransmitJournal]. Cablear la
 * bandeja DENTRO de la sesion es un cambio de la sesion que este checkpoint no
 * pide, y no se hace a escondidas: `MIG-11` lo deja escrito y demostrado.
 * ==================================================================== */

class TransportMigrationTest {

    @TempDir
    lateinit var tmp: File

    private lateinit var ed25519: Ed25519

    /** La BANDEJA COMPARTIDA entre la sesion y el journal. */
    private lateinit var inbox: PendingInbox

    private val reloj: Clock = Clock { 1_000L }

    @BeforeEach
    fun setUp() {
        ed25519 = Ed25519Impl()
        inbox = PendingInbox()
    }

    // ===================================================================
    // IDENTIDADES Y CONTEXTOS AUTENTICADOS (KM-0002 real, sin atajos)
    // ===================================================================

    /**
     * El `IdentityId` de una clave publica, con la derivacion que exige
     * `AuthSession`: `SHA-256("KM-ID-IDENTITY" || publicKey)` en hex.
     *
     * No es un detalle: `AuthSession.initiator` RECHAZA una identidad que no
     * derive de la clave, y un `SHA-256` a secas pasaria por aqui y reventaria
     * mas abajo con un diagnostico que no senala el origen.
     */
    private fun idDe(kp: KeyPair): IdentityId = IdentityId(
        MessageDigest.getInstance("SHA-256").let { d ->
            d.update("KM-ID-IDENTITY".toByteArray())
            d.update(kp.publicKey)
            d.digest().joinToString("") { "%02x".format(it) }
        },
    )

    private fun anuncioDe(kp: KeyPair, id: IdentityId): NodeAnnouncement {
        val partial = NodeAnnouncement(
            messageId = MessageId.from(UUID(0, 1)),
            timestamp = 0L,
            protocolVersion = "1.0",
            identity = NodeIdentity(id, kp.publicKey),
            endpoints = emptyList(),
            capabilities = emptySet(),
        )
        return partial.copy(
            signature = ed25519.sign(
                kp.privateKey,
                NodeAnnouncementJsonCodec.signableJson(partial),
            ).bytes,
        )
    }

    private fun contexto(
        localKp: KeyPair,
        localId: IdentityId,
        remoteKp: KeyPair,
        remoteId: IdentityId,
    ): PeerContext {
        val session = AuthSession.initiator(localKp, localId.value, ed25519, clock = reloj)
        session.receiveChallenge(AuthChallenge(ByteArray(16) { 5 }, 1_000L, 3, remoteId.value)).getOrThrow()
        session.markResponseSent()
        val sid = "b".repeat(40)
        val nonce = ByteArray(16) { 6 }
        val transcript = TranscriptBuilder.serverAuthTranscript(sid, nonce, localId.value)
        val ok = AuthOk(sid, nonce, localKp.publicKey, ed25519.sign(localKp.privateKey, transcript).bytes)
        session.receiveAuthOk(ok, localId.value).getOrThrow()
        return PeerContext.authenticated(remoteId, remoteKp.publicKey, anuncioDe(remoteKp, remoteId), session)
    }

    // ===================================================================
    // LOS EXTREMOS: cadena, manager y sesion de mensajeria
    // ===================================================================

    /**
     * Un extremo con su CADENA de dos medios y su manager.
     *
     * [p2p] es un `ScriptableTransportBackend`, que sabe fallar y por eso es el
     * que modela la muerte del enlace. [relay] es un [RelayDeTransporte], que
     * es opaco por estructura.
     */
    private inner class Extremo {
        val kp: KeyPair = ed25519.generateKeyPair()
        val id: IdentityId = idDe(kp)

        val p2p = ScriptableTransportBackend("P2P")
        val relay = RelayDeTransporte("Relay")
        val chain: TransportChain = TransportChain(listOf(p2p, relay))
        val manager: PeerTransportManager = PeerTransportManager(transportBackend = chain, now = { 0L })

        fun contextTowards(otro: Extremo): PeerContext = contexto(kp, id, otro.kp, otro.id)
    }

    /**
     * Un par de extremos con los bindings en `CONNECTED`, las dos sesiones de
     * mensajeria sobre el ratchet del guion, y las RONDAS de establecimiento que
     * este archivo necesita.
     *
     * Todas son EJERCICIOS de [TransportChain.establish], no una API de
     * migracion. Y hay dos maneras distintas de morir, porque dan estados
     * finales distintos: [p2pRevienta] mata a P2P en la negociacion, y
     * [p2pNoAbreCanal] lo deja negociar bien con el canal sin abrir. Las dos
     * tienen que acabar igual para la sesion criptografica.
     */
    private inner class Par(val guion: GuionDeMigracion) {
        val aliceNodo = Extremo()
        val bobNodo = Extremo()

        val ctxAlice: PeerContext = aliceNodo.contextTowards(bobNodo)
        val ctxBob: PeerContext = bobNodo.contextTowards(aliceNodo)

        /** La sesion que migra: ALICE. Ver el diagrama de la cabecera. */
        val session: SecureMessagingSession = SecureMessagingSession(
            localIdentity = aliceNodo.id,
            peerContext = ctxAlice,
            protocol = guion.alice,
            transport = aliceNodo.manager,
        )

        /** El extremo que descifra al final. */
        val bobSession: SecureMessagingSession = SecureMessagingSession(
            localIdentity = bobNodo.id,
            peerContext = ctxBob,
            protocol = guion.bob,
            transport = bobNodo.manager,
        )

        init {
            // El manager exige PeerContext AUTHENTICATED al abrir el binding.
            aliceNodo.manager.createBinding(aliceNodo.id, ctxAlice).getOrThrow()
            bobNodo.manager.createBinding(bobNodo.id, ctxBob).getOrThrow()
            conectar(session, aliceNodo.manager, ctxAlice)
            conectar(bobSession, bobNodo.manager, ctxBob)
        }

        private fun establish(fabrica: (TransportBackend) -> TransportEstablishment): EstablishmentResult =
            aliceNodo.chain.establish(aliceNodo.id, bobNodo.id, factory = fabrica)

        /** Ronda en la que P2P gana: el medio activo inicial. */
        fun p2pGana(): EstablishmentResult =
            establish { ScriptableEstablishment(it.name, it) }

        /** Ronda en la que P2P REVIENTA en la negociacion y Relay gana. */
        fun p2pRevienta(): EstablishmentResult =
            establish {
                if (it.name == "P2P") EstablecimientoQueExplota("P2P", "negotiate") else ScriptableEstablishment(it.name, it)
            }

        /** Ronda en la que P2P negocia bien pero su canal NO abre. */
        fun p2pNoAbreCanal(): EstablishmentResult =
            establish { backend ->
                ScriptableEstablishment(backend.name, backend).also {
                    if (backend.name == "P2P") {
                        it.awaitReadyOutcome = TransportResult.Failure(TransportError.BINDING_CLOSED, "el canal no abrio")
                    }
                }
            }

        /** Encola un frame como PENDIENTE, sin descifrarlo. */
        fun llegaPendiente(wire: ByteArray): Boolean = inbox.encolar(wire)
    }

    private fun conectar(s: SecureMessagingSession, m: PeerTransportManager, ctx: PeerContext) {
        m.sendOffer(s.localIdentity, ctx, "offer", s.remotePeerId).getOrThrow()
        m.receiveAnswer(s.localIdentity, s.remotePeerId, "answer").getOrThrow()
        m.markConnected(s.localIdentity, ctx).getOrThrow()
    }

    // ===================================================================
    // EL ESCENARIO, ARMADO HASTA EL INSTANTE JUSTO ANTERIOR A LA MIGRACION
    // ===================================================================

    private val textoDelMensaje = "el mensaje que cruza la migracion".toByteArray()

    private fun mensajeId(n: Int) =
        MessageId.from(UUID(0x5151_5151_5151_5100L + n, 0x0a9a_9a9a_9a9a_9a00L + n))

    /**
     * El escenario, hasta un instante antes de la migracion.
     *
     * ## POR QUE ESTE METODO Y NO UN `@BeforeEach`
     *
     * Porque el ORDEN es el invariante. Un guion armado en un `@BeforeEach` con
     * una foto en cualquier sitio seria el error de `KM52V2-06` repetido: una
     * foto tomada DESPUES de la operacion que se quiere congelar. Aqui el orden
     * esta escrito una sola vez, y el paso que congela es el ultimo.
     */
    private class Escena(
        val par: Par,
        /** Lo que la sesion cifro, tal y como el MEDIO lo recibio. */
        val ciphertextPre: ByteArray,
        /** Los frames que LLEGARON por P2P y quedaron sin abrir, EN ORDEN. */
        val pendientes: List<ByteArray>,
    )

    private fun escena(): Escena {
        val guion = GuionDeMigracion()
        val par = Par(guion)

        // 1. El guion criptografico. ALICE queda en la epoca 2 con `a4` (N=1)
        //    sin entregar, y BOB tiene dos cadenas con retenidas de verdad.
        guion.dosEpochs()

        // 2. LA RAFAGA DE BOB, y la razon de abrir el ULTIMO.
        //
        // BOB manda tres frames seguidos. ALICE abre el TERCERO, de golpe, y deja
        // los dos primeros SIN ABRIR: el hueco obliga al ratchet a RETENER las
        // claves de los dos, y los dos pendientes son exactamente los frames
        // que solo se pueden abrir con esas claves retenidas.
        //
        // Es la forma mas fuerte del invariante de D-5: el pendiente y las claves
        // que lo abren viajan en la MISMA unidad `KM52`, asi que si uno se
        //iguera al otro, el otro no se puede recuperar. Con un pendiente
        // abrible en cualquier momento, la parte criptografica de la afirmacion
        // seria vana.
        val primero = guion.bob.encrypt("el primero de la rafaga".toByteArray())
        val segundo = guion.bob.encrypt("el segundo de la rafaga".toByteArray())
        val tercero = guion.bob.encrypt("el tercero de la rafaga".toByteArray())
        par.session.onTransportBytes(tercero)
        val retenidas = retenidasDe(guion.aliceSession)
        assertEquals(
            2, retenidas.size,
            "abrir el tercero deja retenidas las claves de los dos primeros: el invariante de retenidas " +
                "no puede comprobarse sobre una sesion que no tiene ninguna",
        )
        assertEquals(
            listOf(FrameIdentity.fromWire(primero).messageNumber, FrameIdentity.fromWire(segundo).messageNumber),
            retenidas.map { it.second }.sorted(),
            "y son las de los dos PENDIENTES, no unas cualesquiera",
        )
        val pendientes = listOf(primero, segundo)

        // La entrega que YA ha ocurrido se vacia aqui, y se comprueba antes: a
        // partir de este punto el `outbox` de la sesion esta limpio en TODAS las
        // pruebas, y `drainOutbound()` mide de verdad lo que se entregue DESPUES
        // de la migracion y no lo que el guion dejo a medias.
        val entregadoPorElGuion = par.session.drainOutbound()
        assertEquals(1, entregadoPorElGuion.size, "la rafaga se abre el ultimo y entrega su texto")
        assertContentEquals(
            "el tercero de la rafaga".toByteArray(), entregadoPorElGuion.single(),
            "y es el que BOB cifro: el hueco ha dejado retenidas las claves de los otros dos",
        )

        // 3. ALICE envia el mensaje que cruza la migracion, por su sesion de
        //    mensajeria de verdad. Lo que se congela NO es lo que el ratchet
        //    devolvio, sino lo que el MEDIO recibio: son dos cosas distintas, y
        //    solo la segunda es la que sale por el cable.
        val enviado = par.session.send(textoDelMensaje)
        assertTrue(enviado is SendResult.Ok, "el envio tiene que salir: $enviado")
        val ciphertextPre = par.aliceNodo.p2p.sentPayloads.single().copyOf()

        // Y el frame es un SecureFrame v1 BIEN FORMADO, con su header entero
        // como AAD. Sin esto, "los mismos bytes" podrian ser bytes sin sentido.
        assertEquals(
            SecureFrameSpec.CIPHERTEXT_OFFSET + textoDelMensaje.size + SecureFrameSpec.TAG_LENGTH,
            ciphertextPre.size,
            "el frame tiene que medir header + carga + tag: el AAD es el header COMPLETO",
        )

        return Escena(par, ciphertextPre, pendientes)
    }

    /** Reenvia los bytes PREPARADOS por la cadena, sin volver a cifrar. */
    private fun reenviar(e: Escena, wire: ByteArray): TransportResult<Unit> =
        e.par.aliceNodo.manager.sendData(e.par.aliceNodo.id, e.par.ctxAlice, wire)

    /** Un envio ya emitido, tal y como lo deja la sesion. */
    private fun envio(e: Escena, n: Int, ordinal: ULong, deliveryState: OutboundDeliveryState) =
        PreparedSend(
            messageId = mensajeId(n),
            wireFrame = e.ciphertextPre,
            deliveryState = deliveryState,
            createdOrdinal = ordinal,
        )

    /** Un journal que comparte la bandeja con la sesion. */
    private fun journal(e: Escena, nombre: String, sesion: DoubleRatchetSession = e.par.guion.aliceSession) =
        SecureTransmitJournal(
            session = sesion,
            store = FileTransmitUnitStore(dir(nombre)),
            codec = e.par.guion.codec(),
            inbox = inbox,
        )

    /** Un journal NUEVO sobre el MISMO disco: el proceso que vuelve a arrancar. */
    private fun journalNuevo(e: Escena, nombre: String, sesion: DoubleRatchetSession) =
        SecureTransmitJournal(
            session = sesion,
            store = FileTransmitUnitStore(tmp.resolve(nombre)),
            codec = e.par.guion.codec(),
            inbox = PendingInbox(),
        )

    // ===================================================================
    // FOTOGRAFIAS Y COMPARACIONES
    // ===================================================================

    /**
     * Las claves retenidas de una sesion, como `(chainId en hex, N)`.
     *
     * Es una foto COMPARABLE, no una huella: si una retenida apareciera o
     * desapareciera, la lista cambia y el mensaje lo dice. Un hash no diria
     * cual.
     */
    private fun retenidasDe(s: DoubleRatchetSession): List<Pair<String, UInt>> =
        s.snapshot().receiveChains.flatMap { c ->
            c.ratchet.skipped.keys.map { hex(c.chainId) to it.second }
        }

    /** Una sesion CLONADA, con el mismo estado y las MISMAS primitivas. */
    private fun clonDe(s: DoubleRatchetSession, g: GuionDeMigracion): DoubleRatchetSession =
        DoubleRatchetSession.restore(s.snapshot(), g.x25519.copiaEnElMismoPunto(), g.kdf)

    /** Cifra con una sesion suelta, sin pasar por la sesion de mensajeria. */
    private fun cifra(s: DoubleRatchetSession, g: GuionDeMigracion, carga: ByteArray): ByteArray =
        SecureRatchetProtocol(s, g.protector).encrypt(carga)

    /** Descifra con una sesion suelta. */
    private fun abre(s: DoubleRatchetSession, g: GuionDeMigracion, wire: ByteArray) =
        SecureRatchetProtocol(s, g.protector).decrypt(wire)

    /**
     * Compara dos fotos DEL RATCHET campo a campo.
     *
     * @return `null` si son la misma, o la descripcion del primer campo que no
     *   lo es. Devuelve una cadena en vez de lanzar para que el mensaje diga QUE
     *   campo fallo: "el estado no es el mismo" sin mas no localiza nada.
     *
     * ## POR QUE NUNCA `stateFingerprint()`
     *
     * Por dos razones, y las dos son de este archivo. La primera esta escrita
     * en el proyecto desde `KM52-01b`. La segunda esta MEDIDA en `MIG-08`, que
     * construye dos sesiones con la MISMA clave publica DH y DISTINTO escalar
     * privado: su `stateFingerprint()` es IDENTICA y solo una de las dos puede
     * hablar con el otro extremo. La huella no ve el escalar privado, y el
     * escalar privado es justo la mitad que decide si la sesion migrada puede
     * seguir con la conversacion.
     */
    private fun diferencia(a: DoubleRatchetSnapshot, b: DoubleRatchetSnapshot): String? {
        if (!a.rootKey.contentEquals(b.rootKey)) return "la raiz del ratchet"
        if (!a.dhSelf.privateKeyBytes().contentEquals(b.dhSelf.privateKeyBytes())) {
            return "el ESCALAR DH propio, que la huella de la sesion no cubre"
        }
        if (!a.dhSelf.publicKeyBytes().contentEquals(b.dhSelf.publicKeyBytes())) return "la clave publica DH propia"
        if (!contenidoIgual(a.dhRemote, b.dhRemote)) return "la DH remota"
        if (a.sendMessageNumber != b.sendMessageNumber) return "Ns ${a.sendMessageNumber} contra ${b.sendMessageNumber}"
        if (a.previousChainLength != b.previousChainLength) {
            return "PN ${a.previousChainLength} contra ${b.previousChainLength}"
        }
        if (!a.sendChain.sendChainKey.contentEquals(b.sendChain.sendChainKey)) return "la CKs de la cadena de envio"
        if (a.sendChain.sendMessageNumber != b.sendChain.sendMessageNumber) return "el Ns de la cadena de envio"
        if (a.sendChain.receiveMessageNumber != b.sendChain.receiveMessageNumber) {
            return "el Nr de la cadena de envio"
        }
        if (a.sendChain.skipped.keys != b.sendChain.skipped.keys) return "las retenidas de la cadena de envio"
        // LA `CKr` DE LA CADENA DE ENVIO NO SE COMPARA, Y ES A PROPOSITO.
        //
        // El layout de la unidad no la guarda: al leer se deriva como copia de la
        // `CKs`, y en una sesion viva las dos mitades de la cadena de envio dejan
        // de coincidir en cuanto envia su primer mensaje. Compararla haria que
        // CUALQUIER ida y vuelta por disco pareciese un cambio de estado —que es
        // exactamente el falso positivo que `SecureTransmitJournal.compararFoto`
        // evita— y el invariante que se comprueba en su lugar es que la mitad
        // perdida es INERTE: indice de recepcion cero y sin retenidas.
        if (b.sendChain.receiveMessageNumber != 0u) {
            return "la cadena de envio leida tiene Nr=${b.sendChain.receiveMessageNumber}: una cadena de envio nunca recibe"
        }
        if (b.sendChain.skipped.isNotEmpty()) {
            return "la cadena de envio leida lleva ${b.sendChain.skipped.size} retenidas"
        }
        if (a.receiveChains.size != b.receiveChains.size) {
            return "${a.receiveChains.size} cadenas de recepcion contra ${b.receiveChains.size}"
        }
        // Orden canonico por `chainId`: el recorrido de la lista viva no tiene
        // por que coincidir con el de la foto, y una diferencia de orden no es
        // una diferencia de estado.
        val x = a.receiveChains.sortedBy { hex(it.chainId) }
        val y = b.receiveChains.sortedBy { hex(it.chainId) }
        for (i in x.indices) {
            if (!x[i].chainId.contentEquals(y[i].chainId)) return "la cadena $i no es la misma"
            if (!x[i].ratchet.sendChainKey.contentEquals(y[i].ratchet.sendChainKey)) return "cadena $i: CKs"
            if (x[i].ratchet.sendMessageNumber != y[i].ratchet.sendMessageNumber) return "cadena $i: Ns"
            if (!x[i].ratchet.receiveChainKey.contentEquals(y[i].ratchet.receiveChainKey)) return "cadena $i: CKr"
            if (x[i].ratchet.receiveMessageNumber != y[i].ratchet.receiveMessageNumber) return "cadena $i: Nr"
            if (x[i].ratchet.skipped.keys != y[i].ratchet.skipped.keys) {
                return "cadena $i: ${x[i].ratchet.skipped.size} retenidas contra ${y[i].ratchet.skipped.size}"
            }
            for ((k, v) in x[i].ratchet.skipped) {
                if (!v.contentEquals(y[i].ratchet.skipped[k])) return "cadena $i: la retenida N=${k.second}"
            }
        }
        return null
    }

    private fun contenidoIgual(a: ByteArray?, b: ByteArray?): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        else -> a.contentEquals(b)
    }

    /** Las entradas de la bandeja, en orden, como texto comparable. */
    private fun bandejaComparable(b: PendingInbox): List<String> =
        b.entradas().map { "${it.frameIdentity}|${hex(it.wireFrame)}" }

    private fun dir(nombre: String): File {
        val d = tmp.resolve(nombre)
        assertFalse(d.exists(), "el directorio de '$nombre' ya existe: el estado de otra prueba se cuela")
        d.mkdirs()
        return d
    }

    /**
     * Palabras que jamas pueden aparecer en el CODIGO EJECUTABLE de una sesion.
     *
     * Los medios concretos, porque la sesion es transportemente ignorant (IGNORANCIA
     * DEL TRANSPORTE), y los mecanismos de reat, porque la migracion la expresa
     * `TransportChain.establish()` y una API nueva seria un concepto duplicado.
     */
    private val MEDIOS_Y_REAT = listOf(
        "P2P", "Relay", "Tor",
        "WebRtcTransport", "RelayTransport", "TransportChain",
        "reattach", "establecer", "migrate",
    )

    // ===================================================================
    // KILOMETRO 1 — EL EXPERIMENTO COMPLETO
    // ===================================================================

    @Test
    @DisplayName("MIG-01 la sesion que migra conserva estado, bandeja y bytes, de P2P a Relay")
    fun `MIG-01 el experimento completo`() {
        val e = escena()
        val par = e.par
        val guion = par.guion
        val p2p = par.aliceNodo.p2p
        val relay = par.aliceNodo.relay

        // ------------------------------------------------------------------
        // 0. LO QUE SE CONGELA, Y ANTES DE QUE LA MIGRACION TOQUE NADA
        // ------------------------------------------------------------------
        //
        // El orden es el invariante, y es el que `KM52V2-06` dejo escrito: la
        // foto se toma DESPUES del envio —que es lo que se va a persistir— y
        // ANTES de la migracion, que es lo que se quiere congelar. Una foto
        // tomada despues de `establish()` no probaria nada: probaria que la
        // cadena no se movio por la foto, que es otra cosa.
        val fotoAntes = guion.aliceSession.snapshot()
        val retenidasAntes = retenidasDe(guion.aliceSession)
        val nsAntes = guion.aliceSession.currentSendMessageNumber()
        val pnAntes = guion.aliceSession.currentPreviousChainLength()
        val cadenasAntes = guion.aliceSession.receiveChainCount()
        val controlAntes = clonDe(guion.aliceSession, guion)

        // Los dos frames de BOB LLEGAN por P2P y se encolan SIN descifrar.
        for (p in e.pendientes) {
            assertTrue(e.par.llegaPendiente(p), "el frame entrante tiene que entrar en la bandeja")
        }
        assertEquals(e.pendientes.size, inbox.tamano(), "y ser los UNICOS pendientes")
        assertEquals(0, par.session.drainOutbound().size, "nadie ha entregado nada todavia")
        assertTrue(retenidasAntes.isNotEmpty(), "y la sesion tiene claves retenidas de verdad")

        // Y la foto se vuelve a tomar DESPUES de encolar, para congelar el
        // estado con la bandeja ya puesta. El inbox no es estado del ratchet, y
        // la prueba lo dice: las dos fotos tienen que salir iguales.
        assertNull(
            diferencia(fotoAntes, guion.aliceSession.snapshot()),
            "ENCOLAR un frame no puede mover el ratchet: la bandeja no es estado del ratchet",
        )

        // ------------------------------------------------------------------
        // 1. P2P GANA LA PRIMERA RONDA Y LUEGO FALLA
        // ------------------------------------------------------------------
        //
        // La ronda inicial no es adorno: `establish()` invalida la seleccion
        // previa, y sin una seleccion previa que invalidar, la segunda ronda no
        // seria una MIGRACION sino una apertura.
        val inicial = par.p2pGana()
        assertTrue(inicial is EstablishmentResult.Ready, "P2P tiene que ganar la primera ronda: $inicial")
        assertEquals("P2P", inicial.activeTransport, "el medio inicial es P2P")
        p2p.failWithTransport(TransportError.BINDING_CLOSED, "el DataChannel cayo")

        // ------------------------------------------------------------------
        // 2. LA MIGRACION ES `establish()`. NO HAY API `migrate()`
        // ------------------------------------------------------------------
        val migrado = par.p2pRevienta()
        assertTrue(migrado is EstablishmentResult.Ready, "Relay tiene que absorber la muerte de P2P: $migrado")
        assertEquals("Relay", migrado.activeTransport, "el medio activo pasa a ser Relay")
        assertEquals(
            listOf("P2P", "Relay"),
            migrado.attempts.map { it.transport },
            "P2P se intenta y falla, y Relay gana: el orden configurado se respeta literalmente",
        )
        assertTrue(par.aliceNodo.chain.isActive("Relay"), "Relay tiene autoridad")
        assertFalse(par.aliceNodo.chain.isActive("P2P"), "y P2P NO: un medio retirado que siguiera entregando seria un segundo camino oculto")
        assertFalse(par.aliceNodo.chain.acceptsInbound("P2P"), "P2P ya no puede inyectar datos")
        assertTrue(par.aliceNodo.chain.acceptsInbound("Relay"), "Relay si puede")

        // ------------------------------------------------------------------
        // 3. LA MIGRACION NO HA TOCADO LA SESION CRIPTOGRAFICA
        // ------------------------------------------------------------------
        assertNull(
            diferencia(fotoAntes, guion.aliceSession.snapshot()),
            "cambiar de medio no puede cambiar el estado del ratchet, campo a campo",
        )
        assertEquals(retenidasAntes, retenidasDe(guion.aliceSession), "ni las claves retenidas")
        assertEquals(nsAntes, guion.aliceSession.currentSendMessageNumber(), "ni N")
        assertEquals(pnAntes, guion.aliceSession.currentPreviousChainLength(), "ni PN")
        assertEquals(cadenasAntes, guion.aliceSession.receiveChainCount(), "ni el numero de cadenas")
        assertContentEquals(
            controlAntes.snapshot().dhSelf.privateKeyBytes(),
            guion.aliceSession.snapshot().dhSelf.privateKeyBytes(),
            "ni el escalar DH propio, que es la mitad que no se puede regenerar",
        )
        // Y la sesion de mensajeria sigue siendo la MISMA instancia, montada
        // sobre el mismo ratchet: no hay reat ni reconstruccion.
        assertTrue(
            par.session.transportManager === par.aliceNodo.manager,
            "la sesion que migra no ha cambiado de gestor de transporte",
        )

        // ------------------------------------------------------------------
        // 4. PERSISTENCIA `KM52` v2 — con la bandeja en la MISMA unidad
        // ------------------------------------------------------------------
        val envio = envio(e, n = 1, ordinal = 1uL, deliveryState = OutboundDeliveryState.PENDIENTE)
        val medio = FileTransmitUnitStore(dir("unidad"))
        SecureTransmitJournal(
            session = guion.aliceSession,
            store = medio,
            codec = guion.codec(),
            inbox = inbox,
        ).persistir(envio)

        val unidad = guion.codec().deserialize(
            assertNotNull(medio.readCommitted(), "la unidad tiene que estar confirmada"),
        )
        assertNull(
            diferencia(fotoAntes, unidad.snapshot),
            "la unidad lleva el estado de la sesion QUE MIGRA, no el de antes de migrar",
        )
        assertContentEquals(
            envio.ciphertext,
            assertNotNull(unidad.outbound, "la unidad lleva el envio").ciphertext,
            "y el ciphertext ORIGINAL del envio, sin re-cifrar",
        )
        assertEquals(e.pendientes.size, unidad.pendingInbound.size, "y la bandeja viaja en la MISMA unidad atomica")
        assertEquals(
            e.pendientes.map { FrameIdentity.fromWire(it) },
            unidad.pendingInbound.map { it.frameIdentity },
            "con las identidades de los pendientes, EN ORDEN",
        )
        for (i in e.pendientes.indices) {
            assertContentEquals(
                e.pendientes[i], unidad.pendingInbound[i].wireFrame,
                "con los bytes EXACTOS del frame pendiente $i, sin reescribirlo",
            )
        }
        // Y la unidad lleva las RETENIDAS que los abren: si el pendiente y su
        // clave no viajan juntos, el pendiente es irrecuperable.
        assertEquals(
            retenidasAntes.size,
            unidad.snapshot.receiveChains.sumOf { it.ratchet.skipped.size },
            "las claves retenidas que abren los pendientes van en la MISMA unidad: sin ellas no habria nada que abrir",
        )

        // ------------------------------------------------------------------
        // 5. RESTAURACION DE LA MISMA SESION
        // ------------------------------------------------------------------
        //
        // Se restaura sobre una sesion que ha AVANZADO de verdad, no sobre la
        // que ya esta bien: restaurar sobre el estado correcto no distinguiria
        // un `restore()` de un no-op. Se mueve, se comprueba que se movio, y
        // solo entonces se restaura.
        val trasladada = clonDe(guion.aliceSession, guion)
        cifra(trasladada, guion, "ruido que hay que deshacer".toByteArray())
        assertNotNull(
            diferencia(fotoAntes, trasladada.snapshot()),
            "la trasladada TIENE que estar en un estado distinto antes de restaurar: si no, esto no mide nada",
        )

        val journalNuevo = journalNuevo(e, "unidad", trasladada)
        journalNuevo.restaurar()
        // LA BANDEJA SE LEE DESPUES DE RESTAURAR, y no antes.
        //
        // `SecureTransmitJournal.inbox` es un `var` que `restaurar()` SUSTITUYE
        // por una instancia rehidratada. Capturarlo antes se queda con la
        // bandeja VACIA de partida y la prueba pasa luego a medir la bandeja
        // equivocada —el mismo error de clase que `KM52V2-06`: tomar la foto
        // antes del paso que la produce. Aqui se lee la propiedad DESPUES.
        val bandejaRestaurada = journalNuevo.inbox

        assertNull(
            diferencia(fotoAntes, trasladada.snapshot()),
            "tras restaurar, la sesion vuelve a ser la de ANTES de migrar, campo a campo",
        )
        // Y la bandeja sobrevive, con su contenido y SU ORDEN.
        assertEquals(e.pendientes.size, bandejaRestaurada.tamano(), "los frames pendientes sobreviven a la restauracion")
        for (i in e.pendientes.indices) {
            assertContentEquals(
                e.pendientes[i], bandejaRestaurada.entradas()[i].wireFrame,
                "el pendiente $i conserva los bytes: lo que se conserva es lo que LLEGO, no lo que se rehizo",
            )
        }
        assertEquals(
            e.pendientes.map { FrameIdentity.fromWire(it) },
            bandejaRestaurada.entradas().map { it.frameIdentity },
            "y en el MISMO orden, con la identidad leida del header sin descifrar",
        )

        // ------------------------------------------------------------------
        // 6. RELAY: LOS MISMOS BYTES, POR EL OTRO MEDIO
        // ------------------------------------------------------------------
        //
        // Se reenvia el frame PREPARADO, no el plaintext: el reenvio de una
        // sesion migrada tiene que ser una COPIA del cable, y volver a cifrar
        // seria un envio nuevo con N+1.
        val reenvio = reenviar(e, envio.wireFrame)
        assertTrue(reenvio.isSuccess, "el reenvio tiene que salir por Relay: $reenvio")
        assertEquals(1, p2p.sentPayloads.size, "P2P solo recibio el envio ORIGINAL: el reenvio no le llega")
        assertEquals(2, p2p.sendCount, "pero si se intento, y fallo: el fallback es el que devuelve el envio")
        assertEquals(1, relay.recibidos.size, "y Relay ha recibido UN payload")

        // *** LA EVIDENCIA CENTRAL: BYTE A BYTE ***
        val ciphertextPost = relay.entregar()
        assertContentEquals(
            e.ciphertextPre, ciphertextPost,
            "ciphertext_post tiene que ser IDENTICO byte a byte a ciphertext_pre",
        )

        // Y la identidad del frame lo dice de otra manera, y con mas detalle:
        // un re-cifrado habria avanzado N, y N va DENTRO del frame.
        val identidadPre = FrameIdentity.fromWire(e.ciphertextPre)
        val identidadPost = FrameIdentity.fromWire(ciphertextPost)
        assertEquals(identidadPre, identidadPost, "la identidad del frame es la misma: DH, PN y N")
        assertEquals(nsAntes - 1u, identidadPost.messageNumber, "y N es el MISMO que envio, no el siguiente")
        assertTrue(
            identidadPost.messageNumber < nsAntes,
            "el frame NO se ha re-cifrado: si se hubiera re-cifrado, su N seria el N actual de la sesion ($nsAntes)",
        )

        // ------------------------------------------------------------------
        // 7. BOB DESCRIFRA
        // ------------------------------------------------------------------
        par.bobSession.onTransportBytes(ciphertextPost)
        val entregado = par.bobSession.drainOutbound()
        assertEquals(1, entregado.size, "Bob entrega exactamente un plaintext")
        assertContentEquals(textoDelMensaje, entregado.single(), "y es el mensaje ORIGINAL")

        // ------------------------------------------------------------------
        // 8. Y LOS PENDIENTES, YA MIGRADOS, SE ABREN
        // ------------------------------------------------------------------
        //
        // Los frames que llegaron por P2P y no se abrieron se abren DESPUES de
        // migrar, y sale el texto que BOB cifro. Es la parte de D-5 que faltaba:
        // la bandeja no solo persiste, PERTENECE a la sesion que migra.
        //
        // Se abren sobre la sesion RESTAURADA —el objeto que recibio la bandeja
        // rehidratada— y no sobre la viva: si se abrieran sobre la viva, la
        // prueba pasaria aunque la bandeja no tuviera nada que ver con ella.
        //
        // Y se abren DESDE LAS CLAVES RETENIDAS, que Restore tambien devolvio en
        // la misma unidad. Un pendiente que sobreviviera sin su clave seria
        // indistinguible de un frame corrupto, y por eso el orden de las
        // aserciones importa: primero que las retenidas siguen ahi, luego que
        // abren.
        assertEquals(
            retenidasAntes.size, retenidasDe(trasladada).size,
            "la sesion restaurada conserva las claves retenidas que abren los pendientes",
        )
        val textos = mutableListOf<String>()
        for (entrada in bandejaRestaurada.entradas()) {
            val abierto = abre(trasladada, guion, entrada.wireFrame)
            assertTrue(
                abierto is SecureRatchetProtocol.DecryptResult.Ok,
                "el pendiente ${entrada.frameIdentity} se abre DESDE UNA CLAVE RETENIDA: $abierto",
            )
            textos.add(String(abierto.plaintext))
        }
        assertEquals(
            listOf("el primero de la rafaga", "el segundo de la rafaga"),
            textos,
            "y cada uno entrega SU texto, en el orden de llegada: la bandeja es FIFO",
        )
        // Y al abrirlos se consumen las retenidas: la posicion del ratchet se
        // avanza una vez por cada uno, y no se genera nada nuevo.
        assertEquals(
            0, retenidasDe(trasladada).size,
            "y las retenidas se han consumido: los pendientes ya no hacen falta",
        )
        assertEquals(
            fotoAntes.sendMessageNumber, trasladada.currentSendMessageNumber(),
            "abrir pendientes NO avanza la cadena de envio: abrir y enviar son cosas distintas",
        )
    }

    // ===================================================================
    // KILOMETRO 2 — LA MIGRACION NO REGENERA NI REINICIA NADA
    // ===================================================================

    @Test
    @DisplayName("MIG-02 la migracion no regenera claves, no reinicia el ratchet y no cambia N/PN/DH")
    fun `MIG-02 la migracion no toca la sesion`() {
        val e = escena()
        val guion = e.par.guion

        val fotoAntes = guion.aliceSession.snapshot()
        val retenidasAntes = retenidasDe(guion.aliceSession)
        val nsAntes = guion.aliceSession.currentSendMessageNumber()
        val pnAntes = guion.aliceSession.currentPreviousChainLength()
        val control = clonDe(guion.aliceSession, guion)

        // El guion tiene que dejar retenidas y cadenas DE VERDAD, o "no las toco"
        // seria vacio: con cero retenidas, una comprobacion de retenidas no mide
        // nada. Sin esto, el rojo siguiente no significaria nada.
        assertTrue(retenidasAntes.size >= 2, "el guion tiene que dejar retenidas reales: ${retenidasAntes.size}")
        assertTrue(guion.aliceSession.receiveChainCount() >= 1, "y cadenas de recepcion")

        e.par.p2pGana()
        e.par.aliceNodo.p2p.failWithTransport(TransportError.BACKEND_SEND_FAILED, "cayo")
        val migrado = e.par.p2pNoAbreCanal()
        assertTrue(migrado is EstablishmentResult.Ready, "la migracion tiene que ocurrir: $migrado")
        assertEquals("Relay", migrado.activeTransport, "gana Relay: P2P negocio bien pero su canal no abrio")

        // --- 1. El estado, campo a campo. ---
        assertNull(diferencia(fotoAntes, guion.aliceSession.snapshot()), "el estado es el mismo, campo a campo")

        // --- 2. Y la lista de retenidas, que es una foto COMPARABLE. ---
        assertEquals(retenidasAntes, retenidasDe(guion.aliceSession), "las retenidas son las mismas")

        // --- 3. Los contadores, por su API de produccion. ---
        assertEquals(nsAntes, guion.aliceSession.currentSendMessageNumber(), "N")
        assertEquals(pnAntes, guion.aliceSession.currentPreviousChainLength(), "PN")
        assertContentEquals(fotoAntes.dhSelf.publicKeyBytes(), guion.aliceSession.selfDhPublicKey(), "la DH publica propia")
        assertContentEquals(
            fotoAntes.dhRemote ?: ByteArray(0),
            guion.aliceSession.remoteDhPublicKey() ?: ByteArray(0),
            "la DH remota vista por ultima vez",
        )

        // --- 4. Y el ESCALAR privado, que es el punto de todo. ---
        assertContentEquals(
            control.snapshot().dhSelf.privateKeyBytes(),
            guion.aliceSession.snapshot().dhSelf.privateKeyBytes(),
            "el escalar DH propio es el MISMO, y es el que la huella de la sesion no cubre",
        )

        // --- 5. Y la prueba FUNCIONAL de los cuatro puntos. ---
        //
        // Comparar campos dice que no cambiaron; CIFRAR dice que la sesion sigue
        // pudiendo hacer lo que hacia. Y el contraejemplo es lo que convierte
        // esto en evidencia y no en tautologia: dos sesiones con el MISMO estado
        // producen el MISMO frame, y dos sesiones con estado DISTINTO producen
        // frames distintos.
        val carga = "carga de control de la migracion".toByteArray()
        val frameDelControl = cifra(control, guion, carga)
        assertContentEquals(
            frameDelControl,
            cifra(guion.aliceSession, guion, carga),
            "el frame siguiente sale IDENTICO: la sesion migrada continua donde estaba",
        )

        val otra = GuionDeMigracion(semilla = "OTRA")
        otra.dosEpochs()
        assertFalse(
            cifra(otra.aliceSession, otra, carga).contentEquals(frameDelControl),
            "otra sesion, con otra raiz y otras claves de cadena, produce OTRO frame: si la comparacion no " +
                "puede distinguirlas, la coincidencia de arriba no prueba nada",
        )
    }

    // ===================================================================
    // KILOMETRO 3 — LA IDENTIDAD DE SESION, SIN `stateFingerprint()`
    // ===================================================================

    @Test
    @DisplayName("MIG-03 la identidad de sesion se demuestra con la conyuncion, no con stateFingerprint()")
    fun `MIG-03 identidad de sesion sin la huella`() {
        val e = escena()
        val guion = e.par.guion

        // LAS CUATRO PATAS DE LA CONJUNCION. Ninguna es `stateFingerprint()`.
        //
        //  1. Foto completa comparada, campo a campo.
        //  2. Ciphertext determinista byte a byte, contra un testigo tomado
        //     ANTES de migrar.
        //  3. Continuidad funcional del ratchet: la sesion sigue descifrando y
        //     cifrando, y el otro extremo abre lo que produce.
        //  4. Poder de discriminacion del instrumento: otra sesion NO da el
        //     mismo frame, y el control de BOB SI abre el de esta.
        val carga = "la carga cuya salida se compara byte a byte".toByteArray()

        val fotoAntes = guion.aliceSession.snapshot()
        val control = clonDe(guion.aliceSession, guion)
        // El testigo se produce ANTES de la migracion. Un testigo tomado
        // despues no diria nada: compararia el estado consigo mismo.
        val frameDelControl = cifra(control, guion, carga)

        // LA MIGRACION.
        e.par.p2pGana()
        e.par.aliceNodo.p2p.failWithTransport(TransportError.BACKEND_SEND_FAILED, "cayo")
        assertTrue(e.par.p2pRevienta() is EstablishmentResult.Ready, "migrar a Relay")

        // --- 1. ---
        assertNull(
            diferencia(fotoAntes, guion.aliceSession.snapshot()),
            "1. la foto completa coincide campo a campo, escalar DH privado incluido",
        )

        // --- 2. ---
        val framePost = cifra(guion.aliceSession, guion, carga)
        assertEquals(frameDelControl.size, framePost.size, "2. el frame post tiene que medir lo mismo que el testigo: si no, bastaria una longitud")
        assertContentEquals(frameDelControl, framePost, "2. el frame siguiente es IDENTICO byte a byte al del control previo a la migracion")

        // --- 3. Continuidad funcional DESPUES: lo que salio tras migrar lo abre
        //    BOB. Eso es criptografia, no contabilidad. Y la sesion envia por el
        //    medio nuevo, de principio a fin.
        assertTrue(e.par.session.send(carga) is SendResult.Ok, "3. la sesion envia por Relay")
        assertEquals(1, e.par.aliceNodo.relay.recibidos.size, "3. y Relay ha recibido el frame")
        e.par.bobSession.onTransportBytes(e.par.aliceNodo.relay.entregar())
        val entregado = e.par.bobSession.drainOutbound()
        assertEquals(1, entregado.size, "3. BOB abre lo que salio tras migrar")
        assertContentEquals(carga, entregado.single(), "3. y es el texto original: la sesion no se ha reconstruido")
        // Y el frame que llego sin abrir se abre ahora, y su plaintext es el de
        // BOB: la sesion no ha perdido nada. Los dos van en la rafaga, y salen
        // desde las claves retenidas.
        for (p in e.pendientes) {
            assertTrue(e.par.llegaPendiente(p), "3. el pendiente entra en la bandeja")
        }
        e.par.session.onTransportBytes(e.pendientes.first())
        assertContentEquals(
            "el primero de la rafaga".toByteArray(), e.par.session.drainOutbound().single(),
            "3. y el frame que habia llegado sin abrir se abre ahora, desde una clave retenida",
        )

        // --- 4. Y el instrumento tiene PODER de discriminacion. ---
        val otra = GuionDeMigracion(semilla = "OTRA3")
        otra.dosEpochs()
        assertFalse(
            cifra(otra.aliceSession, otra, carga).contentEquals(framePost),
            "4. otra sesion produce OTRO frame: por eso la coincidencia del punto 2 significa algo",
        )
    }

    // ===================================================================
    // KILOMETRO 4 — EL PENDIENTE: SOBREVIVE, Y NO SE ABRE ANTES
    // ===================================================================

    @Test
    @DisplayName("MIG-04 el pendiente sobrevive a la migracion y NO se descifra antes de migrar")
    fun `MIG-04 el pendiente sobrevive y no se abre antes`() {
        val e = escena()
        val guion = e.par.guion
        val fotoAlEncolar = guion.aliceSession.snapshot()
        val identidades = e.pendientes.map { FrameIdentity.fromWire(it) }

        // --- 1. LLEGA Y SE ENCOLA, SIN ABRIRSE. ---
        for (p in e.pendientes) {
            assertTrue(e.par.llegaPendiente(p), "el frame entra en la bandeja")
        }
        assertEquals(e.pendientes.size, inbox.tamano(), "y hay exactamente esos pendientes")
        assertEquals(0, e.par.session.drainOutbound().size, "nada se ha entregado a la aplicacion")

        // LA PRUEBA DE QUE NO SE HAN ABIERTO, y es la misma en tres niveles:
        //
        //  a) las CLAVES RETENIDAS de esos frames siguen ahi. Abrir un frame desde
        //     una clave retenida la CONSUME, asi que su presencia es la prueba
        //     directa de que nadie los abrio;
        //  b) el `Nr` de la cadena de recepcion no ha avanzado;
        //  c) la foto completa del ratchet no ha cambiado.
        val retenidasAlEncolar = retenidasDe(guion.aliceSession)
        assertTrue(retenidasAlEncolar.isNotEmpty(), "el guion deja retenidas de verdad: sin ellas esto seria vacio")
        val cadenaDeLosPendientes = fotoAlEncolar.receiveChains.first {
            it.chainId.contentEquals(identidades.first().dhPublicKey)
        }
        val nrAlEncolar = cadenaDeLosPendientes.ratchet.receiveMessageNumber
        assertEquals(
            setOf(identidades.first().messageNumber, identidades.last().messageNumber),
            cadenaDeLosPendientes.ratchet.skipped.keys.map { it.second }.toSet(),
            "las retenidas son exactamente las de los pendientes: por eso solo ellos pueden abrirlos, y por eso " +
                "el Nr esta en $nrAlEncolar y no en el ultimo",
        )
        assertNull(
            diferencia(fotoAlEncolar, guion.aliceSession.snapshot()),
            "ENCOLAR no toca el ratchet: la bandeja no es estado del ratchet",
        )

        // --- 2. LA MIGRACION. ---
        e.par.p2pGana()
        e.par.aliceNodo.p2p.failWithTransport(TransportError.BACKEND_SEND_FAILED, "cayo")
        assertTrue(e.par.p2pRevienta() is EstablishmentResult.Ready, "migrar")
        assertEquals(
            retenidasAlEncolar, retenidasDe(guion.aliceSession),
            "y MIGRAR tampoco los ha abierto: las claves que los abren siguen sin consumir",
        )

        // --- 3. PERSISTIR Y RESTAURAR. ---
        journal(e, "m4").persistir(envio(e, n = 2, ordinal = 1uL, deliveryState = OutboundDeliveryState.PENDIENTE))

        val trasladada = clonDe(guion.aliceSession, guion)
        val journalNuevo = journalNuevo(e, "m4", trasladada)
        journalNuevo.restaurar()
        // La bandeja se lee DESPUES: `restaurar()` SUSTITUYE la instancia, y
        // capturar la anterior seria medir la bandeja vacia de partida.
        val bandeja = journalNuevo.inbox

        // --- 4. SOBREVIVE: contenido y ORDEN. ---
        assertEquals(e.pendientes.size, bandeja.tamano(), "los pendientes sobreviven")
        assertEquals(identidades, bandeja.entradas().map { it.frameIdentity }, "en el MISMO orden, con su identidad")
        for (i in e.pendientes.indices) {
            assertContentEquals(e.pendientes[i], bandeja.entradas()[i].wireFrame, "el pendiente $i conserva los bytes")
        }
        // Y sus claves siguen retenidas al otro lado: pendiente y clave viajan en
        // la MISMA unidad, y por eso el pendiente es recuperable.
        assertEquals(
            retenidasAlEncolar, retenidasDe(trasladada),
            "y la sesion restaurada conserva las claves que los abren: sin esto el pendiente no serviria",
        )

        // La deduplicacion por IDENTIDAD, que es de capa del ratchet y no de
        // aplicacion: un repetido se RECHAZA y no sustituye al que ya estaba.
        assertFalse(bandeja.encolar(e.pendientes.first()), "el repetido se rechaza por identidad")
        assertEquals(2, bandeja.tamano(), "y no ocupa una segunda plaza")
        assertContentEquals(
            e.pendientes.first(), bandeja.entradas().first().wireFrame,
            "el original sigue intacto: rechazar no es sustituir",
        )

        // --- 5. SE ABREN, EN ORDEN, Y SALEN LOS TEXTOS. ---
        val textos = mutableListOf<String>()
        for (entrada in bandeja.entradas()) {
            val abierto = abre(trasladada, guion, entrada.wireFrame)
            assertTrue(abierto is SecureRatchetProtocol.DecryptResult.Ok, "se abre '${entrada.frameIdentity}': $abierto")
            textos.add(String(abierto.plaintext))
        }
        assertEquals(
            listOf("el primero de la rafaga", "el segundo de la rafaga"),
            textos,
            "los pendientes se abren, en orden, y cada uno entrega su texto",
        )
        assertTrue(retenidasDe(trasladada).isEmpty(), "y al abrirlos se consumen las retenidas")

        // --- 6. Y ABRIRLOS DE NUEVO ES UN REPLAY. ---
        //
        // Esta comprobacion es la que demuestra que abrirlos CONSUMIO la posicion
        // del ratchet. Si el primer descifrado no la hubiera tomado, este segundo
        // seria un rechazo por otra razon y el paso 5 habria pasado por la razon
        // equivocada.
        val otraVez = abre(trasladada, guion, e.pendientes.first())
        assertTrue(
            otraVez is SecureRatchetProtocol.DecryptResult.Rejected,
            "un frame ya consumido se rechaza por replay: $otraVez",
        )
    }

    // ===================================================================
    // KILOMETRO 5 — CONTINUIDAD EN EL CAMINO QUE USA EL ESCALAR DH
    // ===================================================================

    @Test
    @DisplayName("MIG-05 tras migrar la sesion sigue acordando el DH: el siguiente frame no se mueve")
    fun `MIG-05 continuidad funcional con DH`() {
        val e = escena()
        val guion = e.par.guion
        val fotoAntes = guion.aliceSession.snapshot()

        e.par.p2pGana()
        e.par.aliceNodo.p2p.failWithTransport(TransportError.BACKEND_SEND_FAILED, "cayo")
        assertTrue(e.par.p2pRevienta() is EstablishmentResult.Ready, "migrar")
        assertNull(
            diferencia(fotoAntes, guion.aliceSession.snapshot()),
            "primero: migrar no ha movido el ratchet, asi que la sesion migrada parte del mismo sitio",
        )

        // --- EL TESTIGO: UN SEGUNDO GUION IDENTICO QUE NO MIGRA ---
        //
        // `GuionDeMigracion` con la MISMA semilla es la MISMA sesion: mismas claves,
        // mismo generador en el MISMO punto, mismo estado. Por eso se puede
        // reproducir una continuacion completa y comparar el resultado byte a byte.
        //
        // Y se elige un guion ENTERO, y no un par de clones, por una razon concreta:
        // `initiateEpoch` —y el giro reactivo— GENERAN una clave desde el generador
        // de la sesion. Un clon recibe una COPIA del generador en el punto del clon,
        // asi que si el guion real genera antes que el clon, los dos entregan claves
        // DISTINTAS y la comparacion mediria el contador en vez del estado. Con dos
        // guiones independientes de la misma semilla, los dos generadores arrancan
        // y avanzan en paralelo por construccion.
        val testigo = GuionDeMigracion(semilla = "MIG")
        testigo.dosEpochs()
        val rafagaTestigo = listOf(
            testigo.bob.encrypt("el primero de la rafaga".toByteArray()),
            testigo.bob.encrypt("el segundo de la rafaga".toByteArray()),
            testigo.bob.encrypt("el tercero de la rafaga".toByteArray()),
        )
        SecureRatchetProtocol(testigo.aliceSession, testigo.protector).decrypt(rafagaTestigo.last())
        val enviadoTestigo =
            SecureRatchetProtocol(testigo.aliceSession, testigo.protector).encrypt(textoDelMensaje)
        // Y hay que replicar el ULTIMO paso del guion real: `escena()` envia por la
        // SESION DE MENSAJERIA, no por el protocolo a pelo, y eso tiene un efecto
        // medible que un `encrypt()` directo no produce.
        //
        // `SecureMessagingSession` se registra como handler de `onData` del manager,
        // y `PeerTransportManager.sendData` notifica a los handlers con los bytes
        // que acaba de ENVIAR. La sesion los recibe por su propio `onTransportBytes`,
        // intenta descifrarlos y —como su propia cadena de envio va por delante de su
        // cadena de recepcion— el intento ABRE UNA EPOCA NUEVA y genera una clave.
        //
        // Se ha MEDIDO: el contador de claves generadas pasa de 5 a 6 al enviar por la
        // sesion, y se queda en 5 con un `encrypt()` directo. Por eso el testigo tiene
        // que hacer lo mismo, o el punto 2 compararia dos guiones con un numero de
        // claves distinto y mediria el guion.
        SecureRatchetProtocol(testigo.aliceSession, testigo.protector).decrypt(enviadoTestigo)

        // Y la comprobacion de que el testigo es de verdad el MISMO estado, con el
        // MISMO frame ya en el cable. Sin esta, la comparacion final mediria dos
        // guiones DISTINTOS y no la migracion.
        assertNull(
            diferencia(fotoAntes, testigo.aliceSession.snapshot()),
            "el testigo reproduce el estado EXACTO del que migro: si no, la comparacion mediria el guion",
        )
        assertContentEquals(
            e.ciphertextPre, enviadoTestigo,
            "y produjo el MISMO frame de salida: el guion es determinista de verdad",
        )

        // --- EL CAMINO QUE EJERCITA EL ESCALAR PRIVADO ---
        //
        // Abrir una epoca obliga a la sesion a hacer `agree` con la DH que viene del
        // header y a GENERAR un par nuevo. Ese es el unico camino donde el escalar
        // privado propio se usa de verdad, y por eso es el que hay que mirar: un
        // `restore()` que hubiera perdido la mitad privada pasaria un simple
        // `encrypt()` y fallaria aqui.
        //
        // EL ORDEN ES EL DEL RATCHET DH, Y ES REACTIVO, ASI QUE HAY QUE RESPETARLO:
        // una epoca se abre cuando uno de los dos ve una DH que no tenia. Al abrir
        // la epoca de BOB con el tercer frame de la rafaga, ALICE genero una clave
        // REPLY propia que BOB todavia no ha visto. Ese frame es el que hace girar a
        // BOB, y la respuesta de BOB es la que lleva una DH que ALICE no conocia y
        // por tanto la que obliga a hacer `agree` aqui.
        //
        // ## POR QUE NO SE USA `initiateEpoch()`
        //
        // Porque su propia documentacion dice que solo es correcta en el
        // establecimiento: hace `agree` con `dhRemote`, que tras varios giros es una
        // clave VIEJA, y las dos mitades no derivarian el mismo secreto. Se ha
        // medido —la sesion que lo invoca falla el AEAD de su propio par— y por eso
        // esta continuacion usa el camino natural del ratchet, que es el que un
        // transporte migrado recorria de verdad.
        // 1. ALICE manda un frame con la DH que BOB no ha visto: BOB gira.
        val testigoDelGiro = "abre la epoca para el otro".toByteArray()
        val giroReal = SecureRatchetProtocol(guion.aliceSession, guion.protector).encrypt(testigoDelGiro)
        val giroTestigo = SecureRatchetProtocol(testigo.aliceSession, testigo.protector).encrypt(testigoDelGiro)
        assertContentEquals(giroTestigo, giroReal, "1. el frame que hace girar a BOB sale IDENTICO en los dos")
        assertFalse(
            fotoAntes.receiveChains.any { it.chainId.contentEquals(FrameIdentity.fromWire(giroReal).dhPublicKey) },
            "1. y su DH es una que BOB no tenia: por eso el frame abre epoca y no solo avanza la cadena",
        )
        val giroEnBob = SecureRatchetProtocol(guion.bobSession, guion.protector).decrypt(giroReal)
        val giroEnElTestigo = SecureRatchetProtocol(testigo.bobSession, testigo.protector).decrypt(giroTestigo)
        assertTrue(giroEnBob is SecureRatchetProtocol.DecryptResult.Ok, "1. BOB ve la epoca nueva: $giroEnBob")
        assertTrue(giroEnElTestigo is SecureRatchetProtocol.DecryptResult.Ok, "1. y el BOB testigo tambien: $giroEnElTestigo")
        assertContentEquals(giroEnElTestigo.plaintext, giroEnBob.plaintext, "1. y el mismo plaintext")

        // 2. BOB responde, y su frame lleva una DH QUE ALICE NO CONOCE.
        val respuesta = "abre la epoca en la otra direccion".toByteArray()
        val nuevaReal = SecureRatchetProtocol(guion.bobSession, guion.protector).encrypt(respuesta)
        val nuevaTestigo = SecureRatchetProtocol(testigo.bobSession, testigo.protector).encrypt(respuesta)
        assertContentEquals(nuevaTestigo, nuevaReal, "2. los dos BOB producen el MISMO frame: el estado es el mismo")
        assertFalse(
            FrameIdentity.fromWire(nuevaReal).dhPublicKey
                .contentEquals(FrameIdentity.fromWire(giroReal).dhPublicKey),
            "2. y lleva una DH DISTINTA a la de antes: es una epoca nueva de verdad, no un reenvio",
        )
        assertFalse(
            fotoAntes.receiveChains.any { it.chainId.contentEquals(FrameIdentity.fromWire(nuevaReal).dhPublicKey) },
            "2. que ALICE no tiene en ninguna cadena: abrirlo es lo que obliga a hacer `agree`",
        )

        val enLaSesion = SecureRatchetProtocol(guion.aliceSession, guion.protector).decrypt(nuevaReal)
        val enElTestigo = SecureRatchetProtocol(testigo.aliceSession, testigo.protector).decrypt(nuevaTestigo)
        assertTrue(enLaSesion is SecureRatchetProtocol.DecryptResult.Ok, "3. la sesion migrada hace `agree` y abre la epoca: $enLaSesion")
        assertTrue(enElTestigo is SecureRatchetProtocol.DecryptResult.Ok, "3. y el testigo tambien: $enElTestigo")
        assertContentEquals(enElTestigo.plaintext, enLaSesion.plaintext, "3. y el mismo plaintext")

        // Y los dos estados resultantes son el MISMO, campo a campo, con la clave
        // NUEVA incluida: el escalar privado de la sesion migrada es el de antes.
        assertNull(
            diferencia(guion.aliceSession.snapshot(), testigo.aliceSession.snapshot()),
            "4. la sesion migrada y el testigo convergen byte a byte, clave DH nueva incluida",
        )
        // Y el escalar converge CON EL TESTIGO, que es la comparacion que importa.
        assertContentEquals(
            testigo.aliceSession.snapshot().dhSelf.privateKeyBytes(),
            guion.aliceSession.snapshot().dhSelf.privateKeyBytes(),
            "4. y el escalar DH propio es el MISMO que el del guion que no migro: mismo estado, misma clave",
        )
        // OJO CON LA ATRIBUCION, que aqui es facil equivocarse: el escalar NO es
        // inmutable a lo largo de la conversacion. Abrir una epoca ROTA `dhSelf` a
        // proposito —`previewReceive` genera un `replyDhSelf`— asi que despues del
        // `agree` el escalar es OTRO, y eso es lo correcto.
        //
        // Lo que la migracion no puede hacer es cambiarlo, y eso se comprueba ANTES,
        // en el primer paso de este test, donde la foto se compara justo despues de
        // `establish()`. Aqui lo unico que se afirma es que el cambio lo
        // produjo la APERTURA DE LA EPOCA y no el cambio de medio: para eso esta el
        // testigo, que no migro y giro igual.
        assertNotEquals(
            hex(fotoAntes.dhSelf.privateKeyBytes()),
            hex(guion.aliceSession.snapshot().dhSelf.privateKeyBytes()),
            "4. y el escalar SI ha cambiado, y por la apertura de la epoca, no por la migracion",
        )

        // Y el siguiente frame de los dos sale identico.
        val carga = "tras el agree".toByteArray()
        assertContentEquals(
            SecureRatchetProtocol(testigo.aliceSession, testigo.protector).encrypt(carga),
            SecureRatchetProtocol(guion.aliceSession, guion.protector).encrypt(carga),
            "5. el frame siguiente es IDENTICO byte a byte en los dos",
        )

        // Y el agree OCURRIO de verdad y con la mitad correcta: si no, los dos
        // estados serian identicos porque NO PASARIA NADA, y la comparacion de
        // arriba no probaria nada.
        assertNotNull(
            diferencia(fotoAntes, guion.aliceSession.snapshot()),
            "sanidad: el agree ha MOVIDO el estado; comparar dos cosas iguales no probaria nada",
        )
        assertTrue(
            guion.aliceSession.receiveChainCount() > fotoAntes.receiveChains.size,
            "sanidad: y se ha ABIERTO una cadena de recepcion nueva: la epoca es real",
        )
    }

    // ===================================================================
    // KILOMETRO 6 — UNA EXCEPCION NO DEJA LA SESION A MEDIAS
    // ===================================================================

    @Test
    @DisplayName("MIG-06 una excepcion en el establecimiento no deja la sesion criptografica a medias")
    fun `MIG-06 excepcion en el establecimiento`() {
        val e = escena()
        val guion = e.par.guion
        val node = e.par.aliceNodo

        val fotoAntes = guion.aliceSession.snapshot()
        val retenidasAntes = retenidasDe(guion.aliceSession)
        val nsAntes = guion.aliceSession.currentSendMessageNumber()
        val pnAntes = guion.aliceSession.currentPreviousChainLength()
        val control = clonDe(guion.aliceSession, guion)
        val bandejaAntes = bandejaComparable(inbox)
        assertTrue(retenidasAntes.isNotEmpty(), "el guion tiene que dejar retenidas: sin ellas el invariante seria vacio")

        // Una ronda donde TODOS los medios revientan, y ademas el `abort()` de uno
        // de ellos revienta tambien: el camino de error no puede fallar.
        val p2pExplota = EstablecimientoQueExplota("P2P", "negotiate")
        val relayExplota = EstablecimientoQueExplota("Relay", "awaitReady")
        relayExplota.abortExplota = true
        val resultado = node.chain.establish(node.id, e.par.bobNodo.id) { backend ->
            if (backend.name == "P2P") p2pExplota else relayExplota
        }

        // 1. La ronda NO se tumba: la excepcion se convierte en fallo tipado.
        assertTrue(resultado is EstablishmentResult.Exhausted, "una excepcion agota la cadena, no la tumba: $resultado")
        assertEquals(2, resultado.attempts.size, "los dos medios se intentan")
        val fallo = resultado.attempts.last().result as TransportResult.Failure
        assertEquals(
            TransportError.BACKEND_ERROR, fallo.error,
            "y el fallo es de la categoria TRANSPORT, que es lo que es una implementacion rota",
        )
        // Y el diagnostico NOMBRA el medio y la fase: un fallo sin decir donde no
        // sirve para nada.
        assertTrue(
            fallo.message.contains("Relay") && fallo.message.contains("awaitReady"),
            "el fallo dice que medio y en que fase revento: ${fallo.message}",
        )

        // 2. NINGUN medio conserva autoridad.
        assertNull(node.chain.activeTransport, "no hay medio activo: la ronda no dejo a nadie entregando")
        assertFalse(node.chain.acceptsInbound("P2P"), "P2P no puede inyectar")
        assertFalse(node.chain.acceptsInbound("Relay"), "Relay tampoco")

        // 3. Y los perdedores se cerraron, incluso el que no se podia cerrar.
        assertEquals(1, p2pExplota.abortos, "P2P se aborta")
        assertEquals(1, relayExplota.abortos, "Relay se aborta aunque su abort revienta: un fallo de limpieza no puede ser una fuga")
        assertEquals(EstablishmentState.CLOSED, p2pExplota.state, "y P2P queda CERRADO, no READY")

        // 4. LA SESION CRIPTOGRAFICA ESTA INTACTA. Este es el invariante.
        assertNull(diferencia(fotoAntes, guion.aliceSession.snapshot()), "el estado es el mismo, campo a campo")
        assertEquals(retenidasAntes, retenidasDe(guion.aliceSession), "las retenidas son las mismas")
        assertEquals(nsAntes, guion.aliceSession.currentSendMessageNumber(), "N")
        assertEquals(pnAntes, guion.aliceSession.currentPreviousChainLength(), "PN")
        assertContentEquals(
            control.snapshot().dhSelf.privateKeyBytes(),
            guion.aliceSession.snapshot().dhSelf.privateKeyBytes(),
            "y el escalar DH propio",
        )

        // 5. Y la BANDEJA tampoco se ha tocado: la migracion no es la que decide
        //    que pendientes hay.
        assertEquals(bandejaAntes, bandejaComparable(inbox), "la bandeja sigue como estaba")

        // 6. Y LA PRUEBA DE QUE NO HAY "SESION PARCIALMENTE MUTADA": la sesion
        //    sigue pudiendo hacer lo que hacia, byte a byte.
        val carga = "despues del establecimiento que revienta".toByteArray()
        assertContentEquals(
            cifra(control, guion, carga),
            cifra(guion.aliceSession, guion, carga),
            "el frame siguiente sale IDENTICO: la excepcion no dejo el ratchet a medias",
        )

        // 7. Y LA SESION SIGUE SIENDO USABLE: se puede migrar DESPUES, con medios
        //    sanos. Un fallo de transporte no puede dejar la sesion inservible, que
        //    es lo que haria un estado a medias.
        val sano = e.par.p2pNoAbreCanal()
        assertTrue(sano is EstablishmentResult.Ready, "y una ronda posterior con medios sanos si funciona: $sano")
        assertEquals("Relay", sano.activeTransport, "y gana Relay")
    }

    // ===================================================================
    // KILOMETRO 7 — LA FRONTERA DEL RELAY
    // ===================================================================

    @Test
    @DisplayName("MIG-07 el relay recibe solo bytes de transporte: ni messageId, ni ACK, ni plaintext, ni claves")
    fun `MIG-07 la frontera del relay`() {
        val e = escena()
        val par = e.par
        val guion = par.guion
        val relay = par.aliceNodo.relay

        // Lo que el relay va a recibir: un frame PREPARADO, con un `messageId` y un
        // estado de entrega que existen en la CAPA DE APLICACION y que se van a
        // persistir en la UNIDAD.
        val envio = envio(e, n = 7, ordinal = 3uL, deliveryState = OutboundDeliveryState.IN_FLIGHT)

        par.p2pGana()
        par.aliceNodo.p2p.failWithTransport(TransportError.BACKEND_SEND_FAILED, "cayo")
        assertTrue(par.p2pRevienta() is EstablishmentResult.Ready, "migrar")
        assertTrue(reenviar(e, envio.wireFrame).isSuccess, "el reenvio sale por Relay")

        assertEquals(1, relay.recibidos.size, "el relay ha recibido UN payload")
        val recibido = relay.entregar()

        // --- 1. Recibe BYTE A BYTE lo que produjo la sesion, sin tocarlo. ---
        assertContentEquals(e.ciphertextPre, recibido, "el relay recibe el frame VERBATIM")
        assertEquals(
            SecureFrameSpec.CIPHERTEXT_OFFSET + textoDelMensaje.size + SecureFrameSpec.TAG_LENGTH,
            recibido.size,
            "el payload mide EXACTAMENTE header + carga + tag: el relay no anade ni quita un byte, y no " +
                "transporta ningun campo de aplicacion",
        )

        // --- 2. NO ve el plaintext. ---
        assertEquals(-1, indiceDe(textoDelMensaje, recibido), "el plaintext no viaja en el cable")
        assertEquals(
            -1, indiceDe("el primero de la rafaga".toByteArray(), recibido),
            "ni el texto de la rafaga que quedo pendiente",
        )

        // --- 3. NO ve el `messageId` de la capa de aplicacion. ---
        //
        // El `messageId` son 16 bytes. Que no aparezcan en un frame de ~60 B por
        // azar es de 2^-128, y por eso la comprobacion es util; no lo seria con un
        // identificador corto, y por eso se comprueban 16.
        val idBytes = uuidCanónico(envio.messageId.value)
        assertEquals(16, idBytes.size, "el messageId son 16 bytes canonicos")
        assertEquals(-1, indiceDe(idBytes, recibido), "el relay no ve el messageId: es de otra capa")

        // --- 4. NO ve material de CLAVES del ratchet. ---
        //
        // Aqui hay que ser precisos, porque el frame SI lleva en claro la identidad
        // de su propio AAD —`DH`, `PN` y `N`—: es el wire format v1, congelado, y no
        // es interpretacion. Lo que el relay NO puede tener es material
        // criptografico, y eso se comprueba con agujas de 32 bytes, donde el azar es
        // de 2^-256.
        val foto = guion.aliceSession.snapshot()
        assertEquals(-1, indiceDe(foto.rootKey, recibido), "la raiz del ratchet no viaja por el cable")
        assertEquals(-1, indiceDe(foto.sendChain.sendChainKey, recibido), "ni la CKs de la cadena de envio")
        assertEquals(-1, indiceDe(foto.sendChain.receiveChainKey, recibido), "ni la CKr de la cadena de envio")
        for (cadena in foto.receiveChains) {
            assertEquals(-1, indiceDe(cadena.ratchet.sendChainKey, recibido), "ni una CKs de recepcion")
            assertEquals(-1, indiceDe(cadena.ratchet.receiveChainKey, recibido), "ni una CKr de recepcion")
        }
        assertEquals(
            SecureFrameSpec.DH_PUBLIC_KEY_OFFSET, 4,
            "lo unico en claro es la identidad del AAD, que empieza en el offset 4 del header: es el wire format, no una interpretacion",
        )
        assertContentEquals(
            FrameIdentity.fromWire(recibido).dhPublicKey, recibido.copyOfRange(4, 4 + 32),
            "y son los 32 bytes que ocupa en el header: el relay los mueve, no los lee",
        )

        // --- 5. Y el estado de entrega vive en la UNIDAD, no en el cable. ---
        //
        // Las dos mitades de la frontera, una de cada lado: el estado de entrega se
        // escribe en el medio de transmision, y el cable lleva el SecureFrame y
        // nada mas. NO se comprueba "el byte del enum no esta en el frame" porque
        // un byte suelto SI sale por azar en un frame de 60 B y la comprobacion
        // seria ruido.
        val medio = FileTransmitUnitStore(dir("m7"))
        SecureTransmitJournal(
            session = guion.aliceSession,
            store = medio,
            codec = guion.codec(),
            inbox = inbox,
        ).persistir(envio)
        val unidad = guion.codec().deserialize(
            assertNotNull(FileTransmitUnitStore(tmp.resolve("m7")).readCommitted()),
        )
        val registro = assertNotNull(unidad.outbound, "la unidad lleva el envio")
        assertEquals(envio.deliveryState, registro.deliveryState, "el estado de entrega —la capa del ACK— vive en la UNIDAD")
        assertEquals(envio.messageId, registro.messageId, "y el messageId tambien, no en el cable")
        assertEquals(0, relay.parsesIntentados, "el relay no interpreta: cero parseos, y no puede haber mas")

        // --- 6. Y EL CONTRASTE VIVO: el ejemplo MUERTO. ---
        //
        // Un relay que interpretara algo tendria que DECIDIR sobre los bytes, y una
        // decision sobre un frame manipulado se notaria. Se le da un frame con un
        // byte del CUERPO tocado y se exige que lo devuelva igual: lo unico que
        // puede hacer con bytes opacos es copiarlos. Sin este paso, el punto 5
        // seria el de un clasificador que no se ha disparo nunca.
        val manipulado = recibido.copyOf()
        val dondeCuerpo = recibido.size - SecureFrameSpec.TAG_LENGTH - 1
        manipulado[dondeCuerpo] = (manipulado[dondeCuerpo] + 1).toByte()
        assertFalse(manipulado.contentEquals(recibido), "sanidad: el ejemplo muerto tiene que estar muerto de verdad")
        assertTrue(
            SecureRatchetProtocol(guion.bobSession, guion.protector).decrypt(manipulado) !is SecureRatchetProtocol.DecryptResult.Ok,
            "sanidad: y un frame con el cuerpo roto no lo abre BOB, de modo que la rotura es real y detectable",
        )
        val control = RelayDeTransporte("Relay-control")
        assertTrue(control.sendData(par.bobNodo.id, manipulado).isSuccess, "el relay de control acepta el frame roto")
        assertContentEquals(manipulado, control.entregar(), "y lo devuelve TAL CUAL: no valida, no repara, no rechaza")
        assertEquals(1, control.recibidos.size, "y no ha perdido nada: lo guarda todo, sin juzgar")
    }

    // ===================================================================
    // KILOMETRO 8 — POR QUE `stateFingerprint()` NO SIRVE COMO INSTRUMENTO
    // ===================================================================

    @Test
    @DisplayName("MIG-08 stateFingerprint() no ve el escalar DH privado: misma huella, y solo una puede hablar")
    fun `MIG-08 la huella no ve el escalar privado`() {
        val g = GuionDeMigracion(semilla = "HUELLA")

        // Dos sesiones con la MISMA clave publica DH y DISTINTOS escalares privados.
        // Se construyen a mano y la incoherencia es DELIBERADA: es lo que hace
        // falta para separar lo que la huella ve de lo que no.
        val publica = g.par("alice").publicKey
        val coherente = g.sesion(X25519KeyPair(g.par("alice").privateKey, publica), g.par("bob").publicKey)
        val incoherente = g.sesion(X25519KeyPair(g.par("otro").privateKey, publica), g.par("bob").publicKey)

        // --- 1. LA HUELLA DICE QUE SON LA MISMA SESION. ---
        assertContentEquals(
            coherente.stateFingerprint(), incoherente.stateFingerprint(),
            "la huella es IDENTICA: misma clave publica DH y mismo resto del estado",
        )

        // --- 2. Y EL ESCALAR PRIVADO, QUE ES LO QUE DEBERIA DISTINGUIRLAS, DIFIERE. ---
        assertFalse(
            coherente.snapshot().dhSelf.privateKeyBytes().contentEquals(g.par("otro").privateKey),
            "el escalar de la coherente NO es el de la incoherente",
        )
        assertNotEquals(
            hex(coherente.snapshot().dhSelf.privateKeyBytes()),
            hex(g.par("otro").privateKey),
            "y se ven a simple vista: son escalares distintos",
        )
        // Y la foto que se PERSISTE se niega a representar la incoherente: el guard
        // de `DerivedX25519KeyPair` exige que la publica sea la derivacion de la
        // privada. El punto 4 lo comprueba con `assertThrows`; aqui se deja escrito
        // por que el par se construye a proposito de esa manera.
        assertNotNull(coherente.snapshot(), "la coherente si se puede fotografiar y persistir")

        // --- 3. Y LA CEGUERA NO ES COSMETICA: LOS DOS ESTADOS NO PUEDEN ACORDAR. ---
        //
        // Aqui esta la carga de la prueba, y es donde `stateFingerprint()` deja de
        // servir. El escalar privado no es un dato mas del estado: es la mitad que
        // decide si DOS sesiones pueden entenderse. Dos mitades con la misma clave
        // publica y distinto escalar NO derivan el mismo secreto, y por eso las dos
        // sesiones de arriba —que la huella declara identicas— no pueden hablar.
        //
        // Y la propiedad por la que el ratchet SI funciona se comprueba al lado,
        // para que el contraste no sea "todo falla": con el escalar bien, la
        // propiedad se cumple y el ratchet abre las cadenas.
        val publicaDeAlice = g.par("alice").publicKey
        val skAlice = g.par("alice").privateKey
        val skOtro = g.par("otro").privateKey

        assertContentEquals(
            g.x25519.agree(skAlice, g.par("bob").publicKey),
            g.x25519.agree(g.par("bob").privateKey, publicaDeAlice),
            "con el escalar BIEN, la propiedad que el ratchet usa se cumple: agree(A, pkB) == agree(skB, pkA)",
        )
        assertFalse(
            g.x25519.agree(skAlice, publicaDeAlice).contentEquals(g.x25519.agree(skOtro, publicaDeAlice)),
            "pero dos mitades con la MISMA publica y DISTINTO escalar NO derivan el mismo secreto: " +
                "estos dos estados no pueden entenderse nunca",
        )

        // --- 4. Y LA CAPA DE PERSISTENCIA LO DICE ALTO Y CLARO, DONDE LA HUELLA NO. ---
        //
        // `snapshot()` construye un `DerivedX25519KeyPair` con `from()`, que exige
        // que la publica sea la derivacion de la privada. Una sesion cuya huella
        // daria por buena no puede ni FOTOGRAFIARSE, y por tanto tampoco
        // persistirse. Es la version practica del mismo punto: el instrumento que
        // de verdad se usa para probar la identidad de sesion es la foto
        // completa, y su comparacion mira el escalar PRIVADO —que es lo que hace
        // `diferencia()` en este archivo y `SecureTransmitJournal.compararFoto` en
        // produccion— y jamas la huella.
        assertThrows<IllegalArgumentException> { incoherente.snapshot() }
        assertNotNull(coherente.snapshot(), "la coherente si se puede fotografiar y persistir")

        // Y la huella, AUN ASI, sigue diciendo que son la misma, DESPUES de que la
        // capa de persistencia haya rechazado una de las dos.
        assertContentEquals(
            coherente.stateFingerprint(), incoherente.stateFingerprint(),
            "la huella no se ha enterado de nada: por eso no puede ser el instrumento de la identidad de sesion",
        )
    }

    // ===================================================================
    // KILOMETRO 9 — `establish()` ES EL MECANISMO, Y NO HAY `migrate()`
    // ===================================================================

    @Test
    @DisplayName("MIG-09 el reestablecimiento es establish(): no existe una API migrate()")
    fun `MIG-09 no hay API migrate`() {
        val base = File("src/main/kotlin/com/km")
        assertTrue(base.exists(), "no se encuentra ${base.path} desde ${File(".").absolutePath}")

        // --- 1. NO HAY `migrate` EN CODIGO EJECUTABLE DE km-core. ---
        //
        // El filtro de comentarios importa: un KDoc que mencione una idea no es una
        // API, y sin el filtro un rojo aqui no significaria lo que parece. Y el
        // recorrido se audita A SI MISMO: un frontier check que no lee las lineas
        // que dice leer no esta comprobando nada, y su verde es indistinguible del
        // de uno que si las lee.
        val decision = listOf("migrate", "migrar", "switchTransport", "cambiarTransporte", "rebind")
        val ficheros = base.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val enCodigo = mutableListOf<String>()
        var lineasEscaneadas = 0
        for (f in ficheros) {
            f.readLines().forEachIndexed { i, linea ->
                val t = linea.trimStart()
                if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) return@forEachIndexed
                lineasEscaneadas++
                for (token in decision) {
                    if (Regex("(?i)\\b" + Regex.escape(token) + "\\b").containsMatchIn(linea)) {
                        enCodigo += "${f.name}:${i + 1} contiene '$token' -> $t"
                    }
                }
            }
        }
        val lineasTotales = ficheros.sumOf { it.readLines().size }
        assertTrue(
            lineasEscaneadas > lineasTotales / 2,
            "el escaner tiene que recorrer km-core entero: escaneo $lineasEscaneadas de $lineasTotales lineas",
        )
        assertTrue(ficheros.size > 20, "y tiene que ver el arbol de verdad: ${ficheros.size} ficheros")
        assertTrue(
            enCodigo.isEmpty(),
            "la migracion de transporte se expresa con establish(); una API nueva seria un concepto duplicado:\n" +
                enCodigo.joinToString("\n"),
        )

        // --- 2. Y `establish()` TIENE LA SEMANTICA QUE HARIA FALTA `migrate()`. ---
        //
        // No se comprueba leyendo el codigo: se comprueba EJERCICANDOLO. Las tres
        // cosas que una `migrate()` tendria que hacer estan aqui, y las tres salen de
        // `establish()` sin escribir una linea.
        val e = escena()
        val node = e.par.aliceNodo

        // 2a. INVALIDA LA SELECCION ANTERIOR: una ronda nueva deja a P2P sin
        //     autoridad aunque el objeto siga existiendo.
        val p2pEstable = ScriptableEstablishment("P2P", node.p2p)
        val relayEstable = ScriptableEstablishment("Relay", node.relay)
        val primera = node.chain.establish(node.id, e.par.bobNodo.id) { backend ->
            if (backend.name == "P2P") p2pEstable else relayEstable
        }
        assertTrue(primera is EstablishmentResult.Ready, "primera ronda: $primera")
        assertEquals("P2P", primera.activeTransport, "P2P gana y tiene autoridad")
        assertEquals(0, p2pEstable.abortCount, "y todavia no se ha cerrado: su permiso es el de ESTA ronda")

        val p2pPerdedor = ScriptableEstablishment("P2P", node.p2p).apply {
            awaitReadyOutcome = TransportResult.Failure(TransportError.BINDING_CLOSED, "el canal no abrio")
        }
        val relayGanador = ScriptableEstablishment("Relay", node.relay)
        val segunda = node.chain.establish(node.id, e.par.bobNodo.id) { backend ->
            if (backend.name == "P2P") p2pPerdedor else relayGanador
        }

        assertTrue(segunda is EstablishmentResult.Ready, "segunda ronda: $segunda")
        assertEquals("Relay", segunda.activeTransport, "Relay gana")
        assertFalse(node.chain.isActive("P2P"), "y P2P ha perdido la autoridad EN LA MISMA RONDA: la anterior se invalida")
        // 2b. CIERRA A LOS PERDEDORES, Y DEJA AL GANADOR VIVO.
        assertEquals(1, p2pEstable.abortCount, "el establecimiento de P2P de la ronda anterior se cierra")
        assertEquals(EstablishmentState.CLOSED, p2pEstable.state, "y queda CERRADO: un perdedor vivo seguiria teniendo el objeto")
        assertEquals(1, p2pPerdedor.abortCount, "el perdedor de ESTA ronda tambien")
        assertEquals(EstablishmentState.CLOSED, p2pPerdedor.state, "y cerrado")
        assertEquals(0, relayGanador.abortCount, "el ganador no se cierra: sigue siendo el que entrega")
        assertEquals(EstablishmentState.READY, relayGanador.state, "y esta READY")

        // 2c. LA ENTREGA SALE POR EL MEDIO CON AUTORIDAD, Y LOS BYTES TAL CUAL.
        //
        // HAY QUE SER PRECISOS CON ESTO, y es un hallazgo de esta fase, no una
        // suposicion. `TransportChain.sendData` NO consulta `selected`: recorre el
        // ORDEN CONFIGURADO y devuelve en cuanto un eslabon acepta. Lo que `selected`
        // gobierna es `acceptsInbound` —la ENTRADA— y las operaciones de
        // establecimiento de `foldAll`. La salida la gobierna el ORDEN mas el fallo
        // del medio.
        //
        // Es coherente con la semantica de 3Q.2 —NO existe una sesion por medio, y
        // los mismos bytes salen por el siguiente— pero significa que un medio cuyo
        // `sendData` siguiera ACEPTANDO despues de perder el establecimiento
        // recibiria los bytes. Por eso el enlace muerto se programa como fallo de
        // MEDIO, que es lo que hace un DataChannel cerrado, y no como un
        // `selected` distinto. Sin ese paso, esta comprobacion mediria que el doble
        // acepta, no que la cadena elige.
        assertFalse(node.chain.acceptsInbound("P2P"), "P2P ya no puede INYECTAR datos: `selected` gobierna la entrada")
        assertTrue(node.chain.acceptsInbound("Relay"), "Relay si")
        node.p2p.failWithTransport(TransportError.BINDING_CLOSED, "el enlace cayo: su canal ya no escribe")

        val carga = "bytes opacos de transporte".toByteArray()
        val enviosDeP2p = node.p2p.sendCount
        assertTrue(node.manager.sendData(node.id, e.par.ctxAlice, carga).isSuccess, "el envio sale")
        assertEquals(enviosDeP2p + 1, node.p2p.sendCount, "P2P se intenta PRIMERO, por el orden configurado")
        assertEquals(1, node.relay.recibidos.size, "y como su medio fallo, los bytes salen por Relay")
        assertContentEquals(carga, node.relay.entregar(), "el medio entrega EXACTAMENTE lo que se le dio: no re-cifra, no envuelve, no anade")
    }

    // ===================================================================
    // KILOMETRO 10 — `SecureMessagingSession` NO HA NECESITADO CAMBIOS
    // ===================================================================

    @Test
    @DisplayName("MIG-10 la sesion no se reata a ningun transporte nuevo: la cadena es un solo backend")
    fun `MIG-10 la sesion no se reata`() {
        val e = escena()
        val par = e.par
        val node = par.aliceNodo

        // La sesion se construyo UNA vez, con el manager. Y ese manager tiene la
        // CADENA como backend, no un medio: la cadena es la que decide por donde
        // salen los bytes.
        assertEquals(2, node.chain.transports.size, "la cadena tiene dos eslabones: P2P y Relay")
        assertTrue(node.chain.transports.contains(node.p2p), "y el primero es P2P")
        assertTrue(node.chain.transports.contains(node.relay), "y el segundo es Relay")
        assertTrue(node.p2p as TransportBackend !== node.relay as TransportBackend, "son objetos distintos: no existe una sesion por medio")

        val gestorAntes: PeerTransportManager = par.session.transportManager
        val bindingAntes = par.session.bindingState()

        par.p2pGana()
        node.p2p.failWithTransport(TransportError.BACKEND_SEND_FAILED, "cayo")
        assertTrue(par.p2pRevienta() is EstablishmentResult.Ready, "migrar")

        // Tras migrar, la sesion sigue montada sobre el MISMO manager. No hay reat,
        // ni `migrate()`, ni reconstruccion: lo unico que ha cambiado es que eslabon
        // de la cadena tiene autoridad.
        assertTrue(par.session.transportManager === gestorAntes, "el gestor de transporte es el MISMO objeto")
        assertEquals(
            bindingAntes, par.session.bindingState(),
            "y el binding es el mismo: cambiar de medio no abre otro binding ni pierde el contexto autenticado",
        )

        // Y la sesion envia, y el medio NUEVO recibe, con el frame entero, y BOB lo
        // abre: el mensaje cruza la migracion de principio a fin.
        val carga = "a traves del medio nuevo".toByteArray()
        val enviosDeP2p = node.p2p.sendCount
        assertTrue(par.session.send(carga) is SendResult.Ok, "la sesion envia por el medio nuevo")
        assertEquals(enviosDeP2p + 1, node.p2p.sendCount, "P2P se intento y fallo: no recibio nada")
        assertEquals(0, node.p2p.sentPayloads.size - 1, "y su contador de payloads sigue en el del envio original")
        assertEquals(1, node.relay.recibidos.size, "y Relay ha recibido el frame")
        assertEquals(
            SecureFrameSpec.CIPHERTEXT_OFFSET + carga.size + SecureFrameSpec.TAG_LENGTH,
            node.relay.recibidos.last().size,
            "el frame que sale lleva el header COMPLETO: 44 B, con el AAD entero",
        )
        par.bobSession.onTransportBytes(node.relay.entregar())
        val entregado = par.bobSession.drainOutbound()
        assertEquals(1, entregado.size, "BOB abre lo que salio por Relay")
        assertContentEquals(carga, entregado.single(), "y es el texto original: el medio nuevo lleva el MISMO mensaje")

        // Y el codigo lo dice: la sesion no puede RAMIFICAR sobre un medio, y no
        // puede conocer ningun mecanismo de reat. `MIG-09` ya prohibe `migrate` en
        // todo km-core; aqui se comprueba el otro lado, que es el de la sesion.
        //
        // El filtro de comentarios NO es cosmetico: el KDoc de la sesion nombra
        // `P2P / Relay / Tor` en el diagrama de capas, que es documentacion y no
        // frontera, y un `contains` a pelo pondria en rojo un archivo que CUMPLE la
        // regla. Un rojo aqui tiene que significar "hay una rama sobre un medio en
        // codigo", y nada mas.
        val fuente = File("src/main/kotlin/com/km/messaging/SecureMessagingSession.kt")
        assertTrue(fuente.exists(), "falta ${fuente.path}")
        val offences = mutableListOf<String>()
        var lineasDeCodigo = 0
        fuente.readLines().forEachIndexed { i, linea ->
            val t = linea.trimStart()
            if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) return@forEachIndexed
            lineasDeCodigo++
            for (token in MEDIOS_Y_REAT) {
                if (Regex("\\b" + Regex.escape(token) + "\\b").containsMatchIn(linea)) {
                    offences += "${i + 1} nombra '$token' -> $t"
                }
            }
        }
        assertTrue(
            lineasDeCodigo > 40,
            "el escaner tiene que recorrer el codigo ejecutable de la sesion: $lineasDeCodigo lineas",
        )
        assertTrue(
            offences.isEmpty(),
            "la sesion no puede conocer un medio concreto ni un mecanismo de reat:\n" + offences.joinToString("\n"),
        )
    }

    // ===================================================================
    // KILOMETRO 11 — LO QUE ESTA FUERA DE ALCANCE, DICHO Y DEMOSTRADO
    // ===================================================================

    @Test
    @DisplayName("MIG-11 la bandeja que persiste es la del journal, no el deque privado de la sesion")
    fun `MIG-11 la bandeja de la sesion sigue siendo un deque`() {
        // `SecureMessagingSession` sigue guardando los bytes entrantes en un
        // `ArrayDeque<ByteArray>` propio, y ese deque NO es la `PendingInbox` que
        // persiste `SecureTransmitJournal`. Cablearlos es un cambio de la sesion que
        // este checkpoint no pide, y no se hace a escondidas: se deja escrito, con la
        // evidencia de que SIGUEN SIENDO DOS.
        //
        // La consecuencia PRACTICA es la que importa: si la sesion encolara en su
        // deque, ese frame no llegaria a la unidad. Lo que sobrevive a la migracion es
        // la bandeja COMPARTIDA, que es la que journal y sesion declaran compartir en
        // la cabecera de `SecureTransmitJournal`.
        val fuente = File("src/main/kotlin/com/km/messaging/SecureMessagingSession.kt")
        assertTrue(fuente.exists(), "falta ${fuente.path}")
        val texto = fuente.readText()
        assertTrue(
            texto.contains("ArrayDeque<ByteArray>"),
            "la sesion sigue con su deque: si esto deja de ser cierto, MIG-11 tiene que reescribirse",
        )
        assertFalse(
            texto.contains("PendingInbox"),
            "y NO conoce la bandeja que persiste: por eso la instancia compartida se pasa a mano",
        )

        // Y el lado del journal, que es el que si la rehidrata.
        val journalTexto = File("src/main/kotlin/com/km/transmit/SecureTransmitJournal.kt").readText()
        assertTrue(
            journalTexto.contains("PendingInbox.rehidratada(unidad.pendingInbound)"),
            "el journal rehidrata la bandeja desde la unidad: es el lado que la hace sobrevivir",
        )

        // Y el HECHO, no solo el codigo: las dos estructuras CUENTAN COSAS
        // DISTINTAS, y el numero que mas dice es el DELTA.
        //
        // El guion de `escena()` ya entrego a la sesion un frame por
        // `onTransportBytes` —el tercero de la rafaga— asi que el deque de la
        // sesion NO empieza en cero, y afirmar eso seria medir el guion y no la
        // frontera. Lo que se mide es que encolar en la bandeja COMPARTIDA no
        // cambia ese numero: son dos estructuras, y tocar una no toca la otra.
        val e = escena()
        val dequeAntes = e.par.session.pendingInboundCount()
        assertTrue(dequeAntes > 0, "sanidad: el guion entrego al menos un frame a la sesion, asi que el delta es observable")

        for (p in e.pendientes) {
            assertTrue(e.par.llegaPendiente(p), "el frame entra en la bandeja compartida")
        }
        assertEquals(2, inbox.tamano(), "la bandeja compartida tiene los DOS pendientes")
        assertEquals(
            dequeAntes, e.par.session.pendingInboundCount(),
            "encolar en la bandeja compartida NO toca el deque de la sesion: son DOS estructuras, " +
                "y lo que sobrevive a la migracion es la compartida",
        )
        // Y la sesion no ha entregado el pendiente: abrirlo exigiria llamar a
        // `onTransportBytes`, que es justo el camino que la bandeja compartida NO
        // usa. Por eso el pendiente de la bandeja es invisible para la sesion.
        assertEquals(0, e.par.session.drainOutbound().size, "la sesion no ha entregado nada nuevo: el pendiente no le ha llegado")
    }
}
