package com.km.daemon

import com.fasterxml.jackson.databind.ObjectMapper
import com.km.auth.Base64Url
import com.km.auth.TranscriptBuilder
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.crypto.KeyPair
import com.km.daemon.config.DaemonConfig
import com.km.daemon.server.AuthAdapter
import com.km.daemon.server.JsonWireCodec
import com.km.daemon.server.MessageRouter
import com.km.daemon.server.RelayWebSocket
import com.km.identity.KmIds
import com.km.model.IdentityId
import com.km.node.RelayServer
import com.km.node.RelayServiceImpl
import com.km.storage.InMemoryRelayStore
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.enums.Opcode
import org.java_websocket.enums.ReadyState
import org.java_websocket.framing.Framedata
import org.java_websocket.protocols.IProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.AbstractLogger
import org.slf4j.helpers.MessageFormatter
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.UUID
import javax.net.ssl.SSLSession

/**
 * p4/R-3 — SNAPSHOT DE PRESENCIA al autenticarse.
 *
 * ## EL HUECO QUE ESTE TEST CIERRA
 *
 * `RelayServer.registerPeerHandler` avisa a los que YA estaban de que el peer
 * que acaba de entrar esta presente (`onlineHandlers` -> `broadcast(PEER_ONLINE)`).
 * El que ENTRA, en cambio, no se enteraba de quien ya estaba: `PEER_ONLINE` es
 * un evento, y el evento de los presentes se emitio antes de que este llegara.
 *
 * Con un solo consumidor eso se tolera. Con dos que se necesitan mutuamente
 * —A envia a B y B confirma con `ACK` a A— se vuelve un fallo silencioso: el
 * SEGUNDO en conectarse queda ciego a la presencia del PRIMERO, su
 * `RelayPeerChannelDriver` nunca alcanza `CONNECTED` (la presencia es la
 * autoridad: [com.km.transport.JvmRelayTransport.isPeerPresent]), y su envio
 * falla con `INVALID_STATE_TRANSITION` sin que nada explique por que. El E2E de
 * socket real lo destapo.
 *
 * ## QUE SE FIJA AQUI
 *
 *  1. Al segundo en entrar se le manda el estado ACTUAL de presencia (uno por
 *     peer ya presente), ademas del `AUTH_OK` de su propio handshake.
 *  2. El primero sigue recibiendo el `PEER_ONLINE` del segundo: el snapshot es
 *     ADITIVO, no sustituye al aviso que ya existia.
 *  3. El recien llegado NO se recibe a si mismo: la presencia es de otros.
 *
 * ## POR QUE CON EL ENTRAMADO REAL
 *
 * Las conexiones se autentican por el camino de verdad del daemon
 * (`AUTH_REQUEST` -> `AUTH_CHALLENGE` -> `AUTH_RESPONSE` -> `AUTH_OK`), igual que
 * en `AckForwardingTest`. `establishSession` —donde vive el snapshot— solo corre
 * tras un `AUTH_OK` real, asi que un `peerId` inventado no probaria este codigo.
 * El unico doble es el `WebSocket`, que es una interfaz de Java-WebSocket.
 */
class PresenceSnapshotTest {

    private val mapper = ObjectMapper()
    private val ed25519: Ed25519 = Ed25519Impl()

    private val relayKp = ed25519.generateKeyPair()
    private val aliceKp = ed25519.generateKeyPair()
    private val bobKp = ed25519.generateKeyPair()

    private val relayId = identityOf(relayKp)
    private val aliceId = identityOf(aliceKp)
    private val bobId = identityOf(bobKp)

    private val log = PresenceLogger()

    private val relayServer = RelayServer(
        relayKeyPair = relayKp,
        ed25519 = ed25519,
        relayService = RelayServiceImpl(relayStore = InMemoryRelayStore(now = { NOW }), now = { NOW }),
        relayIdentityId = relayId,
        now = { NOW },
    )

    private val codec = JsonWireCodec()
    private val router = MessageRouter(
        relayServer = relayServer,
        codec = codec,
        authAdapter = AuthAdapter(relayServer, relayId, codec),
        relayIdentityId = relayId,
        config = DaemonConfig(),
        logger = log,
        now = { NOW },
    )

    // =====================================================================
    // PRES-01 — el que entra recibe el estado actual
    // =====================================================================

    /**
     * Alice entra y su socket queda vacio. Bob entra DESPUES: se le debe enviar
     * `PEER_ONLINE(alice)` aunque el evento de la presencia de Alice se emitiera
     * cuando Bob no estaba. Sin esta rama del codigo, `bob.socket.sent` no
     * contendria ningun `PEER_ONLINE`.
     */
    @Test
    fun `PRES-01 el recien llegado recibe el estado actual de presencia`() {
        connectAs(aliceKp)

        val bob = connectAs(bobKp)

        val presencia = peerOnlineIds(bob)
        assertEquals(
            listOf(aliceId),
            presencia,
            "bob debe recibir a alice por snapshot: es el fallo que el E2E destapo",
        )
    }

    // =====================================================================
    // PRES-02 — el que ya estaba sigue siendo avisado (aditivo)
    // =====================================================================

    /**
     * El snapshot NO sustituye al aviso previo: Alice, que ya estaba, sigue
     * recibiendo `PEER_ONLINE(bob)` cuando Bob entra. Si el cambio hubiera
     * reemplazado el broadcast por el snapshot, este caso caeria.
     */
    @Test
    fun `PRES-02 el que ya estaba sigue recibiendo PEER_ONLINE del que llega`() {
        val alice = connectAs(aliceKp)

        connectAs(bobKp)

        assertEquals(
            listOf(bobId),
            peerOnlineIds(alice),
            "alice debe recibir PEER_ONLINE(bob): el snapshot es ADITIVO",
        )
    }

    // =====================================================================
    // PRES-03 — la presencia es de OTROS
    // =====================================================================

    /**
     * Un peer NUNCA se recibe a si mismo en el snapshot: la lista que manda el
     * rele excluye a la identidad que acaba de entrar. Un `PEER_ONLINE(propio)`
     * haria que un cliente se creyera un par de si mismo.
     */
    @Test
    fun `PRES-03 el snapshot no incluye al propio recien llegado`() {
        connectAs(aliceKp)
        connectAs(bobKp)

        val carol = connectAs(ed25519.generateKeyPair())

        val presencia = peerOnlineIds(carol)
        assertTrue(
            carol.identity !in presencia,
            "el recien llegado no se recibe a si mismo en el snapshot: $presencia",
        )
        assertEquals(
            setOf(aliceId, bobId),
            presencia.toSet(),
            "y si recibe a los DOS que ya estaban",
        )
    }

    // =====================================================================
    // Utilería — entramado real
    // =====================================================================

    /**
     * `PEER_ONLINE` recibidos por un socket, como identificadores.
     *
     * Se excluye la identidad del PROPIO peer: `registerPeerHandler` hace un
     * `broadcast` que incluye al peer que acaba de registrarse —comportamiento
     * pre-existente—, y el cliente lo ignora (`onPeerPresence`: `if (peer ==
     * identity) return`). Aqui se mide la presencia de OTROS, que es lo que el
     * consumidor de verdad usa.
     */
    private fun peerOnlineIds(peer: Peer): List<IdentityId> =
        peer.socket.sent.asSequence()
            .map { mapper.readTree(it) }
            .filter { it["type"].asText() == "PEER_ONLINE" }
            .map { IdentityId(it["identityId"].asText()) }
            .filter { it != peer.identity }
            .toList()

    /**
     * Conecta una identidad por el handshake REAL del daemon.
     *
     * Devuelve el socket SIN vaciar `sent`: a diferencia de otros tests, aqui
     * lo que el handshake deja escrito —el snapshot de presencia de
     * `establishSession`— ES el objeto de la medida, asi que no se puede
     * limpiar sin borrar la evidencia.
     */
    private fun connectAs(kp: KeyPair): Peer {
        val socket = PresenceSocket()
        val connection = RelayWebSocket(socket, log)
        router.register(socket, connection)

        val identity = identityOf(kp)
        router.onText(
            connection,
            mapper.writeValueAsString(
                mapper.createObjectNode().apply {
                    put("type", "AUTH_REQUEST")
                    put("messageId", UUID.randomUUID().toString())
                    put("timestamp", NOW)
                    put("protocolVersion", JsonWireCodec.DIALECT_VERSION)
                    put("identityId", identity.value)
                    put("publicKey", Base64Url.encode(kp.publicKey))
                    put("responderIdentityId", relayId.value)
                },
            ),
        )

        val challenge = socket.sent.map { mapper.readTree(it) }
            .single { it["type"].asText() == "AUTH_CHALLENGE" }
        val transcript = TranscriptBuilder.authTranscript(
            nonce = Base64Url.decode(challenge["nonce"].asText()),
            timestampMillis = challenge["timestamp"].asLong(),
            responderIdentityId = challenge["responderIdentityId"].asText(),
            identityId = identity.value,
        )
        val signature = ed25519.sign(kp.privateKey, transcript).bytes
        router.onText(
            connection,
            mapper.writeValueAsString(
                mapper.createObjectNode().apply {
                    put("type", "AUTH_RESPONSE")
                    put("messageId", UUID.randomUUID().toString())
                    put("timestamp", NOW)
                    put("signature", Base64Url.encode(signature))
                },
            ),
        )

        assertTrue(connection.isAuthenticated, "el handshake debe completar para ${identity.value.take(8)}")
        return Peer(identity, connection, socket)
    }

    private fun identityOf(kp: KeyPair): IdentityId =
        IdentityId(KmIds.identityId(kp.publicKey).joinToString("") { "%02x".format(it) })

    private inner class Peer(
        val identity: IdentityId,
        val connection: RelayWebSocket,
        val socket: PresenceSocket,
    )

    private companion object {
        const val NOW: Long = 1_700_000_000_000L
    }
}

/** `WebSocket` de Java-WebSocket 1.5.6: interfaz, doble de dos metodos vivos. */
private class PresenceSocket : WebSocket {
    val sent = mutableListOf<String>()
    var open = true

    override fun send(text: String) {
        sent += text
    }

    override fun isOpen(): Boolean = open

    private fun unused(): Nothing =
        throw UnsupportedOperationException("metodo de WebSocket no usado por km-daemon")

    override fun close(code: Int, reason: String?) { open = false }
    override fun close(code: Int) { open = false }
    override fun close() { open = false }
    override fun closeConnection(code: Int, reason: String?) { open = false }
    override fun send(byteBuffer: ByteBuffer?) = unused()
    override fun send(data: ByteArray?) = unused()
    override fun sendFrame(framedata: Framedata?) = unused()
    override fun sendFrame(frames: MutableCollection<Framedata>?) = unused()
    override fun sendPing() = unused()
    override fun sendFragmentedFrame(opcode: Opcode?, buffer: ByteBuffer?, fin: Boolean) = unused()
    override fun hasBufferedData(): Boolean = false
    override fun getRemoteSocketAddress(): InetSocketAddress? = null
    override fun getLocalSocketAddress(): InetSocketAddress? = null
    override fun isClosing(): Boolean = !open
    override fun isFlushAndClose(): Boolean = false
    override fun isClosed(): Boolean = !open
    override fun getDraft(): Draft = unused()
    override fun getReadyState(): ReadyState = if (open) ReadyState.OPEN else ReadyState.CLOSED
    override fun getResourceDescriptor(): String = "ws://doble/relay"
    override fun <T : Any?> setAttachment(attachment: T?) = unused()
    override fun <T : Any?> getAttachment(): T = unused()
    override fun hasSSLSupport(): Boolean = false
    override fun getSSLSession(): SSLSession? = null
    override fun getProtocol(): IProtocol = unused()
}

/** `Logger` del router que guarda lo registrado, para poder afirmarlo. */
private class PresenceLogger : AbstractLogger() {
    val entries = mutableListOf<Pair<Level, String>>()

    init {
        name = "PresenceSnapshotTest"
    }

    override fun getFullyQualifiedCallerName(): String? = PresenceLogger::class.java.name

    override fun handleNormalizedLoggingCall(
        level: Level,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any>?,
        throwable: Throwable?,
    ) {
        entries += level to MessageFormatter.basicArrayFormat(messagePattern ?: "", arguments ?: emptyArray())
    }

    override fun isTraceEnabled(): Boolean = true
    override fun isTraceEnabled(marker: Marker?): Boolean = true
    override fun isDebugEnabled(): Boolean = true
    override fun isDebugEnabled(marker: Marker?): Boolean = true
    override fun isInfoEnabled(): Boolean = true
    override fun isInfoEnabled(marker: Marker?): Boolean = true
    override fun isWarnEnabled(): Boolean = true
    override fun isWarnEnabled(marker: Marker?): Boolean = true
    override fun isErrorEnabled(): Boolean = true
    override fun isErrorEnabled(marker: Marker?): Boolean = true
}
