package com.km.messaging

import com.km.auth.AuthChallenge
import com.km.auth.AuthOk
import com.km.auth.AuthSession
import com.km.auth.Clock
import com.km.auth.TranscriptBuilder
import com.km.crypto.AeadAuthenticationException
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.crypto.X25519
import com.km.crypto.provider.BcChaCha20Poly1305
import com.km.crypto.provider.BcHkdfSha256
import com.km.crypto.provider.BcX25519
import com.km.frame.SecureFrameFormatException
import com.km.frame.SecureFrameProtector
import com.km.frame.SecureFrameSpec
import com.km.identity.ContactBundle
import com.km.identity.KmTestDevice
import com.km.identity.KmTestIdentityRoot
import com.km.model.MessageId
import com.km.model.NodeAnnouncement
import com.km.model.NodeAnnouncementJsonCodec
import com.km.model.NodeCapability
import com.km.model.NodeIdentity
import com.km.node.FakeTransportBackend
import com.km.node.PeerContext
import com.km.node.PeerTransportManager
import com.km.node.PeerTransportState
import com.km.ratchet.RatchetSessionBootstrap
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 3 — `SecureSessionEstablishment`: el punto de entrada UNICO, en los
 * DOS lados.
 *
 * Un iniciador sin respondedor no demuestra nada, asi que el nucleo de esta
 * suite es el viaje completo: A abre con el bundle verificado de B, B acepta
 * el frame PREKEY, A envia y B lee el texto original.
 *
 * El material criptografico sale entero de las primitivas del framework
 * (`KmTestDevice`: roster y bundle firmados de verdad, claves X25519 reales).
 * No hay ningun byte fabricado.
 */
class SecureSessionEstablishmentTest {

    private val ed25519: Ed25519 = Ed25519Impl()
    private val x25519: X25519 = BcX25519()
    private val kdf = BcHkdfSha256()
    private val protector = SecureFrameProtector(BcChaCha20Poly1305(), kdf)

    // ===================================================================
    // Topologia
    // ===================================================================

    private inner class Party(val device: KmTestDevice, val root: KmTestIdentityRoot) {
        val transport = PeerTransportManager(FakeTransportBackend(), now = { 0L })

        fun initiatorKeys() = LocalInitiatorKeys(device.deviceId, device.agreementKey)

        fun responderKeys(opkId: Long?) = LocalResponderKeys(
            deviceId = device.deviceId,
            agreementKey = device.agreementKey,
            signedPreKey = device.signedPreKey,
            oneTimePreKey = opkId?.let { requireNotNull(device.oneTimePreKey(it)) { "OPK $it sin emitir" } },
            oneTimePreKeyId = opkId,
        )

        /** Conduce SU binding a CONNECTED por el API del manager. */
        fun driveConnected(session: SecureMessagingSession) {
            val m = session.transportManager
            val ctx = session.peerContext
            m.sendOffer(session.localIdentity, ctx, "sdp-offer", session.remotePeerId).getOrThrow()
            m.receiveAnswer(session.localIdentity, session.remotePeerId, "sdp-answer").getOrThrow()
            m.markConnected(session.localIdentity, ctx).getOrThrow()
        }
    }

    /**
     * Un mundo con A y B, cada uno con su PeerContext de CONTACTO y el bundle
     * VERIFICADO del otro ya vinculado.
     *
     * El bundle entra por [PeerContext.forContact], que solo acepta un
     * `VerifiedContactBundle`: no hay forma de colar un documento sin verificar
     * en el camino del establecimiento.
     *
     * UNA RAIZ POR PAR. Antes se creaba una raiz nueva en CADA llamada, lo que
     * hacia que el mismo par obtuviera dos `remotePeerId` distintos entre
     * `initiate` y `accept`, y dos contextos que no hablan. Con la clave derivada
     * de la raiz, hace falta que la raiz sea estable: es la identidad que firman
     * los bundles y la que indexa la sesion.
     */
    private inner class World(bobOpkIds: List<Long>) {
        val alice = Party(KmTestDevice("alice"), KmTestIdentityRoot())
        val bob = Party(KmTestDevice("bob"), KmTestIdentityRoot())
        val establishment = SecureSessionEstablishment(x25519, kdf, protector)

        val aliceToBob: PeerContext
        val bobToAlice: PeerContext

        init {
            bobOpkIds.forEach { bob.device.issueOneTimePreKey(it) }
            aliceToBob = alice.contextTowards(bob, bob.root, bobOpkIds)
            bobToAlice = bob.contextTowards(alice, alice.root, emptyList())
        }

        fun handshake(oneTimePrekeyIndex: Int? = 0, driveToConnected: Boolean = true): Handshake {
            val initiated = establishment.initiate(
                localIdentity = alice.device.identityId,
                peerContext = aliceToBob,
                localKeys = alice.initiatorKeys(),
                transport = alice.transport,
                oneTimePrekeyIndex = oneTimePrekeyIndex,
            )
            // B responde con el OPK que A dijo haber usado: el `keyId` es lo
            // que permite emparejar el publico del bundle con la clave privada.
            val accepted = establishment.accept(
                localIdentity = bob.device.identityId,
                peerContext = bobToAlice,
                localKeys = bob.responderKeys(initiated.usedOneTimePreKeyId),
                prekeyFrameWire = initiated.prekeyFrame,
                transport = bob.transport,
            )
            if (driveToConnected) {
                alice.driveConnected(initiated.session)
                bob.driveConnected(accepted.session)
            }
            return Handshake(initiated, accepted)
        }
    }

    private class Handshake(
        val initiated: InitiatedSession,
        val accepted: AcceptedSession,
    ) {
        val a: SecureMessagingSession get() = initiated.session
        val b: SecureMessagingSession get() = accepted.session
    }

    /**
     * `PeerContext` de CONTACTO de esta parte hacia `other`: la autoridad es el
     * bundle de [other], verificado de verdad.
     *
     * No hay `AuthSession` ni `NodeAnnouncement` en ninguna linea: con la
     * autoridad de contacto no hacen falta, y colarlos aqui seria volver a
     * fabricar en nombre del par el material de nodo que en produccion exige el
     * handshake completo.
     */
    private fun Party.contextTowards(other: Party, otherRoot: KmTestIdentityRoot, otherOpkIds: List<Long>): PeerContext =
        PeerContext.forContact(
            ContactBundle.verified(otherRoot.bundleFor(listOf(other.device), other.device, otherOpkIds)),
        )

    /**
     * `PeerContext` de NODO hacia `other`, con handshake completo de verdad.
     *
     * Existe para los escenarios "sin bundle verificado": un contexto de nodo es
     * exactamente un contexto SIN material de contacto, y es la unica forma
     * honesta de expresarlo ahora que `contactBundle` es derivado de la
     * autoridad y no un campo que se pueda poner a `null` con `copy`.
     */
    private fun Party.nodeContextTowards(other: Party): PeerContext {
        val session = AuthSession.initiator(
            keyPair = device.signingKeyPair,
            identityId = device.identityId.value,
            ed25519 = ed25519,
            clock = Clock { 1_000L },
        )
        session.receiveChallenge(
            AuthChallenge(ByteArray(16) { 7 }, 0L, 3, other.device.identityId.value),
        ).getOrThrow()
        session.markResponseSent()

        val sid = MessageDigest.getInstance("SHA-256")
            .digest("sid-${device.name}".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(40)
        val nonce = ByteArray(16) { 9 }
        val transcript = TranscriptBuilder.serverAuthTranscript(sid, nonce, session.localIdentityId)
        session.receiveAuthOk(
            AuthOk(sid, nonce, other.device.signingKeyPair.publicKey, other.device.signWith(transcript)),
            device.identityId.value,
        ).getOrThrow()

        val partial = NodeAnnouncement(
            messageId = MessageId(UUID.randomUUID()),
            timestamp = 0L,
            protocolVersion = "1.0",
            identity = NodeIdentity(other.device.identityId, other.device.signingKeyPair.publicKey),
            endpoints = emptyList(),
            capabilities = setOf(NodeCapability.CLIENT),
        )
        val announcement = partial.copy(
            signature = other.device.signWith(NodeAnnouncementJsonCodec.signableJson(partial)),
        )
        return PeerContext.authenticated(
            remotePeerId = other.device.identityId,
            publicKey = other.device.signingKeyPair.publicKey,
            announcement = announcement,
            authSession = session,
        )
    }

    /** Los bytes que salen de [session] por su transporte, sin entregarlos a nadie. */
    private fun capture(session: SecureMessagingSession): MutableList<ByteArray> {
        val captured = mutableListOf<ByteArray>()
        session.transportManager.onData { _, data -> captured.add(data) }
        return captured
    }

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        for (i in 0..size - needle.size) {
            var match = true
            for (j in needle.indices) if (this[i + j] != needle[j]) { match = false; break }
            if (match) return true
        }
        return false
    }

    // ===================================================================
    // 1. Ida y vuelta A -> B
    // ===================================================================

    @Test
    fun `A inicia B acepta y el mensaje llega con su texto original`() {
        val h = World(listOf(1000, 1001)).handshake()
        assertEquals(1000L, h.initiated.usedOneTimePreKeyId, "A uso el OPK de la posicion 0")

        val capturado = capture(h.a)
        assertTrue(h.a.send("hola Bob".toByteArray()) is SendResult.Ok, "A debe poder enviar")
        assertEquals(1, capturado.size, "el transporte de A recibio un frame")

        // Los bytes viajan opacos; B los descifra por su sesion.
        h.b.onTransportBytes(capturado.single())
        val recibido = assertNotNull(h.b.drainOutbound().firstOrNull(), "B debe entregar plaintext")
        assertContentEquals("hola Bob".toByteArray(), recibido)
    }

    @Test
    fun `el transporte de A no ve plaintext y el wire mide 44 + N + tag`() {
        val h = World(listOf(1000)).handshake()
        val capturado = capture(h.a)
        val texto = "contenido-secreto".toByteArray()
        h.a.send(texto)

        val wire = capturado.single()
        assertFalse(wire.containsBytes(texto), "el wire no puede contener el plaintext")
        // SecureFrame v1 CONGELADO: header fijo de 44 bytes + ciphertext + tag.
        assertEquals(
            SecureFrameSpec.HEADER_LENGTH + texto.size + SecureFrameSpec.TAG_LENGTH,
            wire.size,
            "el wire no es un SecureFrame v1 de tamano correcto",
        )
    }

    @Test
    fun `varios mensajes y en ambos sentidos`() {
        val h = World(listOf(1000)).handshake()
        val deA = capture(h.a)
        val deB = capture(h.b)

        // El orden es el del mundo real: quien responde ha recibido primero.
        for (i in 0..2) h.a.send("a$i".toByteArray())
        for (w in deA) h.b.onTransportBytes(w)
        assertEquals(listOf("a0", "a1", "a2"), h.b.drainOutbound().map { String(it) })

        for (i in 0..2) h.b.send("b$i".toByteArray())
        for (w in deB) h.a.onTransportBytes(w)
        assertEquals(listOf("b0", "b1", "b2"), h.a.drainOutbound().map { String(it) })
    }

    @Test
    fun `el frame PREKEY no se descifra como mensaje de la sesion`() {
        val h = World(listOf(1000)).handshake()
        // `F` viaja con `K_prekey`, que es un dominio DISTINTO del de los
        // mensajes. Una sesion abierta no sabe abrirlo: el AEAD no verifica.
        val resultado = h.b.receive(h.initiated.prekeyFrame)
        assertTrue(
            resultado is ReceiveResult.Rejected,
            "el PREKEY no es un mensaje de la sesion, fue $resultado",
        )
        assertEquals(ReceiveReject.NOT_AUTHENTICATED_FRAME, (resultado as ReceiveResult.Rejected).reason)
        assertEquals(0, h.b.drainOutbound().size, "no debe entregarse nada")
    }

    // ===================================================================
    // 2. B sin sesion establecida no descifra
    // ===================================================================

    @Test
    fun `sin establecimiento el mensaje de A no se descifra`() {
        val mundo = World(listOf(1000))
        val h = mundo.handshake()
        val capturado = capture(h.a)
        h.a.send("secreto".toByteArray())
        val wire = capturado.single()

        // B SIN sesion establecida: no tiene el `F` de este arranque, asi que su
        // `RootKey` sale de otra cosa. Es exactamente lo que significa no haber
        // hecho el handshake, y el fallo tiene que ser el de verdad.
        val sinSesion = SecureMessagingSession(
            localIdentity = mundo.bob.device.identityId,
            peerContext = mundo.bobToAlice,
            protocol = RatchetSessionBootstrap.responderProtocol(
                bootstrapValue = ByteArray(32) { 0x7E },
                signedPreKey = mundo.bob.device.signedPreKey,
                x25519 = x25519,
                kdf = kdf,
                protector = protector,
            ),
            transport = mundo.bob.transport,
        )

        val resultado = sinSesion.receive(wire)
        assertTrue(resultado is ReceiveResult.Rejected, "sin handshake no hay plaintext, fue $resultado")
        assertEquals(ReceiveReject.NOT_AUTHENTICATED_FRAME, (resultado as ReceiveResult.Rejected).reason)
        assertEquals(0, sinSesion.drainOutbound().size, "no se entrega nada a la aplicacion")
    }

    @Test
    fun `un frame PREKEY alterado no abre sesion ni consume el OPK`() {
        val mundo = World(listOf(1000))
        val h = mundo.handshake()
        val alterado = h.initiated.prekeyFrame.copyOf()
        alterado[alterado.size - 1] = (alterado[alterado.size - 1] + 1).toByte()

        val e = assertFailsWith<AeadAuthenticationException> {
            mundo.establishment.accept(
                localIdentity = mundo.bob.device.identityId,
                peerContext = mundo.bobToAlice,
                localKeys = mundo.bob.responderKeys(1000),
                prekeyFrameWire = alterado,
                transport = mundo.bob.transport,
            )
        }
        assertTrue(e.message!!.isNotEmpty(), "la excepcion dice por que no abrio")
        // El OPK sigue disponible: `accept` abre `F` ANTES de confirmar, que es
        // el contrato de `X3dhSessionPreparation.commit`.
        assertNotNull(mundo.bob.device.oneTimePreKey(1000), "el OPK no puede haberse consumido")
    }

    @Test
    fun `bytes que no son un SecureFrame se rechazan con el tipo real`() {
        val mundo = World(listOf(1000))
        mundo.handshake()
        assertFailsWith<SecureFrameFormatException> {
            mundo.establishment.accept(
                localIdentity = mundo.bob.device.identityId,
                peerContext = mundo.bobToAlice,
                localKeys = mundo.bob.responderKeys(1000),
                prekeyFrameWire = ByteArray(64) { 0x01 },
                transport = mundo.bob.transport,
            )
        }
    }

    @Test
    fun `un frame MESSAGE no se acepta como establecimiento`() {
        val h = World(listOf(1000)).handshake()
        val capturado = capture(h.a)
        h.a.send("no soy un prekey".toByteArray())

        val e = assertFailsWith<IllegalArgumentException> {
            SecureSessionEstablishment(x25519, kdf, protector).accept(
                localIdentity = h.b.localIdentity,
                peerContext = h.b.peerContext,
                localKeys = LocalResponderKeys(
                    deviceId = h.b.localIdentity.value.let { ByteArray(32) },
                    agreementKey = x25519.generateKeyPair(),
                    signedPreKey = x25519.generateKeyPair(),
                ),
                prekeyFrameWire = capturado.single(),
                transport = h.b.transportManager,
            )
        }
        assertTrue(e.message!!.contains("PREKEY"), "el mensaje dice que esperaba un PREKEY: ${e.message}")
    }

    @Test
    fun `el registro dice que no hay sesion con un par sin establecer`() {
        val registro = SecureSessionRegistry(KmTestDevice("yo").identityId)
        val remoto = KmTestDevice("remote").identityId
        assertNull(registro.find(remoto), "sin sesion no hay nada que devolver")
        val e = assertFailsWith<IllegalStateException> { registro.require(remoto) }
        assertTrue(e.message!!.contains(remoto.value), "el mensaje nombra al par: ${e.message}")
    }

    // ===================================================================
    // 3. Sin bundle verificado no hay establecimiento
    // ===================================================================

    @Test
    fun `iniciar sin bundle verificado LANZA`() {
        val mundo = World(listOf(1000))
        // Un contexto de NODO no lleva bundle: no hay material remoto que
        // verificar, y por tanto no hay nada que arrancar.
        val sinBundle = mundo.alice.nodeContextTowards(mundo.bob)
        assertNull(sinBundle.contactBundle)

        val e = assertFailsWith<IllegalArgumentException> {
            mundo.establishment.initiate(
                localIdentity = mundo.alice.device.identityId,
                peerContext = sinBundle,
                localKeys = mundo.alice.initiatorKeys(),
                transport = mundo.alice.transport,
            )
        }
        assertTrue(e.message!!.contains("verificado"), "el mensaje dice que falta lo verificado: ${e.message}")
    }

    @Test
    fun `aceptar sin el bundle verificado del emisor LANZA`() {
        // Sin `IK_A` no hay `DH1` (KM-0006 §7.3): el header de 44 bytes no la
        // lleva. El tipo de excepcion es el mismo que en el resto del motor.
        val mundo = World(listOf(1000))
        val h = mundo.handshake()
        val e = assertFailsWith<IllegalArgumentException> {
            mundo.establishment.accept(
                localIdentity = mundo.bob.device.identityId,
                peerContext = mundo.bob.nodeContextTowards(mundo.alice),
                localKeys = mundo.bob.responderKeys(1000),
                prekeyFrameWire = h.initiated.prekeyFrame,
                transport = mundo.bob.transport,
            )
        }
        assertTrue(e.message!!.contains("DH1"), "el mensaje nombra el motivo: ${e.message}")
    }

    // ===================================================================
    // 4/5. One-time prekey: el contrato elegido
    // ===================================================================

    @Test
    fun `sin one-time prekey la sesion tambien funciona`() {
        val h = World(listOf(1000, 1001)).handshake(oneTimePrekeyIndex = null)
        assertNull(h.initiated.usedOneTimePreKeyId, "sin DH4 no hay OPK que reportar")

        val capturado = capture(h.a)
        h.a.send("sin opk".toByteArray())
        h.b.onTransportBytes(capturado.single())
        assertContentEquals("sin opk".toByteArray(), h.b.drainOutbound().single())
    }

    @Test
    fun `el receptor elige el OPK por el keyId que reporto el emisor`() {
        val h = World(listOf(1000, 1001)).handshake(oneTimePrekeyIndex = 1)
        assertEquals(1001L, h.initiated.usedOneTimePreKeyId)

        val capturado = capture(h.a)
        h.a.send("con el segundo".toByteArray())
        h.b.onTransportBytes(capturado.single())
        assertContentEquals("con el segundo".toByteArray(), h.b.drainOutbound().single())
    }

    @Test
    fun `un bundle sin one-time prekeys funciona pasando null`() {
        val h = World(emptyList()).handshake(oneTimePrekeyIndex = null)
        assertNull(h.initiated.usedOneTimePreKeyId)
        val capturado = capture(h.a)
        h.a.send("bundle sin opk".toByteArray())
        h.b.onTransportBytes(capturado.single())
        assertContentEquals("bundle sin opk".toByteArray(), h.b.drainOutbound().single())
    }

    @Test
    fun `un indice inexistente LANZA antes de gastar nada`() {
        val mundo = World(listOf(1000))
        assertFailsWith<IllegalArgumentException> {
            mundo.establishment.initiate(
                localIdentity = mundo.alice.device.identityId,
                peerContext = mundo.aliceToBob,
                localKeys = mundo.alice.initiatorKeys(),
                transport = mundo.alice.transport,
                oneTimePrekeyIndex = 5,
            )
        }
        // Y no se abrio ningun binding: el rechazo es anterior a la sesion.
        assertNull(
            mundo.alice.transport.getBinding(mundo.alice.device.identityId, mundo.bob.device.identityId),
            "un establecimiento fallido no puede dejar binding abierto",
        )
    }

    @Test
    fun `un OPK sin keyId no se puede registrar`() {
        // El consumo se registra por `keyId`. Un OPK sin el es material que se
        // gasta sin dejar rastro.
        val bob = KmTestDevice("bob")
        bob.issueOneTimePreKey(1000)
        assertFailsWith<IllegalArgumentException> {
            LocalResponderKeys(
                deviceId = bob.deviceId,
                agreementKey = bob.agreementKey,
                signedPreKey = bob.signedPreKey,
                oneTimePreKey = bob.oneTimePreKey(1000),
                oneTimePreKeyId = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LocalResponderKeys(
                deviceId = bob.deviceId,
                agreementKey = bob.agreementKey,
                signedPreKey = bob.signedPreKey,
                oneTimePreKey = null,
                oneTimePreKeyId = 1000L,
            )
        }
    }

    // ===================================================================
    // El binding
    // ===================================================================

    @Test
    fun `el establecimiento abre el binding pero no conduce el enlace`() {
        val h = World(listOf(1000)).handshake(driveToConnected = false)
        // Abrir el enlace es del transporte: hasta CONNECTED no se envia.
        assertEquals(PeerTransportState.NEW, h.a.bindingState())
        assertTrue(h.a.send("prematuro".toByteArray()) is SendResult.Rejected)
    }

    @Test
    fun `el establecimiento es idempotente ante un binding ya abierto`() {
        // Un consumidor que ya abrio el enlace antes de cifrar no debe ver
        // fallar el establecimiento por `BINDING_ALREADY_EXISTS`.
        val mundo = World(listOf(1000))
        mundo.alice.transport
            .createBinding(mundo.alice.device.identityId, mundo.aliceToBob)
            .getOrThrow()

        val iniciada = mundo.establishment.initiate(
            localIdentity = mundo.alice.device.identityId,
            peerContext = mundo.aliceToBob,
            localKeys = mundo.alice.initiatorKeys(),
            transport = mundo.alice.transport,
        )
        assertNotNull(iniciada.session)
    }

    // ===================================================================
    // El registro como anclaje (superficie para el slice 4)
    // ===================================================================

    @Test
    fun `el registro instala, encuentra y retira sesiones`() {
        val h = World(listOf(1000)).handshake()
        val registro = SecureSessionRegistry(h.a.localIdentity)
        assertEquals(0, registro.size())

        // La clave es el par REMOTO de la sesion, que sale del PeerContext.
        registro.install(h.a)
        assertEquals(h.a, registro.find(h.a.remotePeerId))
        assertEquals(h.a, registro.require(h.a.remotePeerId))
        assertEquals(setOf(h.a.remotePeerId), registro.peers())

        assertEquals(h.a, registro.remove(h.a.remotePeerId))
        assertNull(registro.find(h.a.remotePeerId))
        assertEquals(0, registro.size())

        registro.clear()
        assertEquals(0, registro.size())
    }

    @Test
    fun `instalar dos veces con el mismo par sustituye, no acumula`() {
        // El caso real: el mismo par, dos sesiones distintas (un `F` nuevo).
        // Su estado de ratchet no se puede mezclar con el viejo.
        val mundo = World(listOf(1000))
        val primera = mundo.handshake(driveToConnected = false).a
        val segunda = mundo.handshake(driveToConnected = false).a

        val registro = SecureSessionRegistry(primera.localIdentity)
        registro.install(primera)
        assertEquals(1, registro.size())
        registro.install(segunda)
        assertEquals(1, registro.size(), "el registro es un map con una sesion por par")
        assertEquals(segunda, registro.require(primera.remotePeerId))
    }
}