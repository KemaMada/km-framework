package com.keymessage.core.node

import com.keymessage.core.auth.AuthChallenge
import com.keymessage.core.auth.AuthOk
import com.keymessage.core.auth.AuthSession
import com.keymessage.core.auth.Clock
import com.keymessage.core.auth.TranscriptBuilder
import com.keymessage.core.crypto.Ed25519
import com.keymessage.core.crypto.Ed25519Impl
import com.keymessage.core.crypto.KeyPair
import com.keymessage.core.crypto.provider.BcChaCha20Poly1305
import com.keymessage.core.crypto.provider.BcHkdfSha256
import com.keymessage.core.crypto.provider.BcX25519
import com.keymessage.core.messaging.SecureMessagingSession
import com.keymessage.core.messaging.SendResult
import com.keymessage.core.model.IdentityId
import com.keymessage.core.model.MessageId
import com.keymessage.core.model.NodeAnnouncement
import com.keymessage.core.model.NodeAnnouncementJsonCodec
import com.keymessage.core.model.NodeIdentity
import com.keymessage.core.ratchet.DoubleRatchetSession
import com.keymessage.core.ratchet.RatchetSessionBootstrap
import com.keymessage.core.sf.SecureFrameProtector
import com.keymessage.core.sf.SecureRatchetProtocol
import com.keymessage.core.x3dh.BootstrapPrekeys
import com.keymessage.core.x3dh.InitiatorKeyMaterial
import com.keymessage.core.x3dh.ResponderKeyMaterial
import com.keymessage.core.x3dh.X3dh
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContentEquals

/**
 * 3Q.2 — `TransportChain`: enrutar bytes sin tocar la criptografia.
 *
 * Lo que se demuestra, en orden de importancia:
 *
 *  1. La cadena cambia el MEDIO y nada mas. Los MISMOS bytes cifrados salen
 *     por el eslabon que funciona (CHAIN-08).
 *  2. `permitsFallback` es la frontera normativa: solo un fallo del MEDIO
 *     cambia de ruta. Seguridad, protocolo y estado se detienen.
 *  3. El orden configurado se respeta literalmente.
 *
 * Lo que NO se demuestra, y no debe asumirse: que un envio fallido signifique
 * que el mensaje no llego. Ver la nota de `TransportChain`.
 */
class TransportChainTest {

    private val ed25519: Ed25519 = Ed25519Impl()
    private val aliceKP = ed25519.generateKeyPair()
    private val bobKP = ed25519.generateKeyPair()
    private val aliceId = IdentityId(deriveId(aliceKP.publicKey))
    private val bobId = IdentityId(deriveId(bobKP.publicKey))
    private val clock: Clock = Clock { 1000L }

    // ===================================================================
    // Helpers
    // ===================================================================

    private fun peerContext(remoteKp: KeyPair, remoteId: IdentityId, localKp: KeyPair, localId: IdentityId): PeerContext {
        val session = AuthSession.initiator(localKp, localId.value, ed25519, clock = clock)
        session.receiveChallenge(AuthChallenge(ByteArray(16), 1000L, 3, remoteId.value)).getOrThrow()
        session.markResponseSent()
        val sid = "a".repeat(40)
        val snonce = ByteArray(16) { 3 }
        val transcript = TranscriptBuilder.serverAuthTranscript(sid, snonce, localId.value)
        val ok = AuthOk(sid, snonce, localKp.publicKey, ed25519.sign(localKp.privateKey, transcript).bytes)
        session.receiveAuthOk(ok, localId.value).getOrThrow()
        val identity = NodeIdentity(remoteId, remoteKp.publicKey)
        val partial = NodeAnnouncement(
            messageId = MessageId(UUID.randomUUID()),
            timestamp = 0L,
            protocolVersion = "1.0",
            identity = identity,
            endpoints = emptyList(),
            capabilities = emptySet(),
        )
        val ann = partial.copy(signature = ed25519.sign(remoteKp.privateKey, NodeAnnouncementJsonCodec.signableJson(partial)).bytes)
        return PeerContext.authenticated(remoteId, remoteKp.publicKey, ann, session)
    }

    private fun aliceContext() = peerContext(bobKP, bobId, aliceKP, aliceId)

    private fun chain(vararg names: String): TransportChain =
        TransportChain(names.map { ScriptableTransportBackend(it) })

    // ===================================================================
    // CHAIN-01..07 — Politica de fallback
    // ===================================================================

    @Test
    @DisplayName("CHAIN-01 P2P tiene exito: los demas no se consultan")
    fun `CHAIN-01 exito en el primero`() {
        val c = chain("P2P", "Relay", "Tor")
        val attempts = CopyOnWriteArrayList<ChainAttempt>()
        c.onAttempt { attempts.add(it) }

        val result = c.sendData(bobId, "hola".toByteArray())

        assertTrue(result.isSuccess, "el primer eslabon deberia bastar: $result")
        assertEquals(1, attempts.size, "no se debe consultar un eslabon que no hace falta")
        assertEquals("P2P", attempts.first().transport)
        assertTrue(attempts.first().delivered)
        // Y los otros dos no recibieron nada.
        assertEquals(0, c.transports[1].let { (it as ScriptableTransportBackend).sendCount })
        assertEquals(0, (c.transports[2] as ScriptableTransportBackend).sendCount)
    }

    @Test
    @DisplayName("CHAIN-02 fallo de MEDIO en P2P: Relay entrega")
    fun `CHAIN-02 fallback por fallo de medio`() {
        val c = chain("P2P", "Relay", "Tor")
        (c.transports[0] as ScriptableTransportBackend).failWithTransport()
        val attempts = CopyOnWriteArrayList<ChainAttempt>()
        c.onAttempt { attempts.add(it) }

        val payload = "hola".toByteArray()
        val result = c.sendData(bobId, payload)

        assertTrue(result.isSuccess, "Relay deberia absorber el fallo del medio: $result")
        assertEquals(listOf("P2P", "Relay"), attempts.map { it.transport }, "se consulta P2P y luego Relay, y se detiene")
        assertFalse(attempts[0].delivered)
        assertTrue(attempts[1].delivered)
        // El MISMO payload, no uno re-cifrado ni re-empaquetado.
        val relay = c.transports[1] as ScriptableTransportBackend
        assertEquals(1, relay.sentPayloads.size)
        assertContentEquals(payload, relay.sentPayloads.first())
    }

    @Test
    @DisplayName("CHAIN-03 fallo de SEGURIDAD: la cadena se detiene")
    fun `CHAIN-03 seguridad detiene`() {
        for (error in listOf(
            TransportError.PEER_CONTEXT_NOT_AUTHENTICATED,
            TransportError.SESSION_MISMATCH,
            TransportError.PEER_ID_MISMATCH,
            TransportError.NEGOTIATION_HASH_MISMATCH,
        )) {
            val c = chain("P2P", "Relay", "Tor")
            (c.transports[0] as ScriptableTransportBackend).failWithTransport(error, "suplantacion")
            val attempts = CopyOnWriteArrayList<ChainAttempt>()
            c.onAttempt { attempts.add(it) }

            val result = c.sendData(bobId, byteArrayOf(1)) as TransportResult.Failure

            assertEquals(error, result.error, "debe propagar la causa, no enmascararla")
            assertEquals(1, attempts.size, "un fallo de seguridad no debe consultar el siguiente medio: $attempts")
            assertEquals(0, (c.transports[1] as ScriptableTransportBackend).sendCount, "Relay intacto")
        }
    }

    @Test
    @DisplayName("CHAIN-04 fallo de PROTOCOLO: la cadena se detiene")
    fun `CHAIN-04 protocolo detiene`() {
        for (error in listOf(TransportError.INVALID_SDP, TransportError.INVALID_ICE_CANDIDATE)) {
            val c = chain("P2P", "Relay")
            (c.transports[0] as ScriptableTransportBackend).failWithTransport(error, "sdp invalida")
            val attempts = CopyOnWriteArrayList<ChainAttempt>()
            c.onAttempt { attempts.add(it) }

            val result = c.sendData(bobId, byteArrayOf(1)) as TransportResult.Failure

            assertEquals(error, result.error)
            assertEquals(1, attempts.size, "los mismos bytes fallarian igual en otro medio")
        }
    }

    @Test
    @DisplayName("CHAIN-05 fallo de ESTADO: la cadena se detiene")
    fun `CHAIN-05 estado detiene`() {
        for (error in listOf(TransportError.INVALID_STATE_TRANSITION, TransportError.BINDING_ALREADY_EXISTS)) {
            val c = chain("P2P", "Relay")
            (c.transports[0] as ScriptableTransportBackend).failWithTransport(error, "estado invalido")
            val attempts = CopyOnWriteArrayList<ChainAttempt>()
            c.onAttempt { attempts.add(it) }

            c.sendData(bobId, byteArrayOf(1))

            assertEquals(1, attempts.size, "reintentar no cambia el estado: $attempts")
        }
    }

    @Test
    @DisplayName("CHAIN-06 los tres medios caen: el ultimo decides y Tor entrega")
    fun `CHAIN-06 cadena completa`() {
        val c = chain("P2P", "Relay", "Tor")
        (c.transports[0] as ScriptableTransportBackend).failWithTransport(TransportError.BINDING_CLOSED)
        (c.transports[1] as ScriptableTransportBackend).failWithTransport(TransportError.BACKEND_SEND_FAILED)
        val attempts = CopyOnWriteArrayList<ChainAttempt>()
        c.onAttempt { attempts.add(it) }

        val result = c.sendData(bobId, "hola".toByteArray())

        assertTrue(result.isSuccess)
        assertEquals(listOf("P2P", "Relay", "Tor"), attempts.map { it.transport })
    }

    @Test
    @DisplayName("CHAIN-07 todos los medios caen: se devuelve el fallo del ULTIMO")
    fun `CHAIN-07 todos fallan`() {
        val c = chain("P2P", "Relay", "Tor")
        (c.transports[0] as ScriptableTransportBackend).failWithTransport(TransportError.BINDING_CLOSED, "p2p caido")
        (c.transports[1] as ScriptableTransportBackend).failWithTransport(TransportError.BINDING_NOT_FOUND, "relay caido")
        (c.transports[2] as ScriptableTransportBackend).failWithTransport(TransportError.BACKEND_SEND_FAILED, "tor caido")
        val attempts = CopyOnWriteArrayList<ChainAttempt>()
        c.onAttempt { attempts.add(it) }

        val result = c.sendData(bobId, "hola".toByteArray()) as TransportResult.Failure

        assertEquals(TransportError.BACKEND_SEND_FAILED, result.error, "se devuelve el fallo del ultimo eslabon")
        assertEquals("tor caido", result.message)
        assertEquals(3, attempts.size, "deben consultarse los tres")
    }

    // ===================================================================
    // El orden configurado es normativo
    // ===================================================================

    @Test
    @DisplayName("CHAIN-08 el orden configurado se respeta literalmente")
    fun `CHAIN-08 el orden manda`() {
        // [Tor, P2P, Relay] debe agotar Tor ANTES de tocar P2P. Si la cadena
        // "optimizara" reordenando por disponibilidad, este test fallaria.
        val c = chain("Tor", "P2P", "Relay")
        (c.transports[0] as ScriptableTransportBackend).failWithTransport()
        val attempts = CopyOnWriteArrayList<ChainAttempt>()
        c.onAttempt { attempts.add(it) }

        c.sendData(bobId, byteArrayOf(1))

        assertEquals(listOf("Tor", "P2P"), attempts.map { it.transport })
    }

    @Test
    @DisplayName("CHAIN-09 una cadena de un solo medio no inventa unRelay implicito")
    fun `CHAIN-09 cadena de un medio`() {
        // [P2P] significa P2P y nada mas. Si falla, la cadena termina.
        val c = chain("P2P")
        (c.transports[0] as ScriptableTransportBackend).failWithTransport()
        val attempts = CopyOnWriteArrayList<ChainAttempt>()
        c.onAttempt { attempts.add(it) }

        val result = c.sendData(bobId, byteArrayOf(1)) as TransportResult.Failure

        assertEquals(TransportError.BACKEND_SEND_FAILED, result.error)
        assertEquals(1, attempts.size, "no existe un segundo medio que probar")
        assertEquals(1, c.transports.size)
    }

    @Test
    @DisplayName("CHAIN-10 dos cadenas distintas con el mismo fallo dan ultima posicion distinta")
    fun `CHAIN-10 el ultimo fallo depende del orden`() {
        val a = chain("P2P", "Relay")
        (a.transports[0] as ScriptableTransportBackend).failWithTransport(TransportError.BINDING_CLOSED, "p2p")
        (a.transports[1] as ScriptableTransportBackend).failWithTransport(TransportError.BACKEND_SEND_FAILED, "relay")

        val b = chain("Relay", "P2P")
        (b.transports[0] as ScriptableTransportBackend).failWithTransport(TransportError.BINDING_CLOSED, "relay")
        (b.transports[1] as ScriptableTransportBackend).failWithTransport(TransportError.BACKEND_SEND_FAILED, "p2p")

        val ra = a.sendData(bobId, byteArrayOf(1)) as TransportResult.Failure
        val rb = b.sendData(bobId, byteArrayOf(1)) as TransportResult.Failure

        assertEquals("relay", ra.message)
        assertEquals("p2p", rb.message, "el ultimo eslabon es el que manda, segun el orden")
    }

    @Test
    @DisplayName("CHAIN-11 una cadena vacia es un error de configuracion, no un caso degenerado")
    fun `CHAIN-11 cadena vacia`() {
        assertThrows(IllegalArgumentException::class.java) { TransportChain(emptyList()) }
    }

    // ===================================================================
    // La cadena es un TransportBackend de primera clase
    // ===================================================================

    @Test
    @DisplayName("CHAIN-12 la cadena se puede usar como backend del manager")
    fun `CHAIN-12 drop-in en el manager`() {
        val c = chain("P2P", "Relay")
        val mgr = PeerTransportManager(transportBackend = c, now = { 0L })
        val ctx = aliceContext()
        mgr.createBinding(aliceId, ctx).getOrThrow()
        mgr.sendOffer(aliceId, ctx, "offer", bobId).getOrThrow()
        mgr.receiveAnswer(aliceId, bobId, "answer").getOrThrow()
        mgr.markConnected(aliceId, ctx).getOrThrow()
        (c.transports[0] as ScriptableTransportBackend).failWithTransport()

        val result = mgr.sendData(aliceId, ctx, "hola".toByteArray())

        assertTrue(result.isSuccess, "el manager debe ver el exito de Relay a traves de la cadena: $result")
        assertEquals(1, (c.transports[1] as ScriptableTransportBackend).sendCount)
    }

    @Test
    @DisplayName("CHAIN-13 el manager propaga el fallo si TODA la cadena cae")
    fun `CHAIN-13 cadena entera caida`() {
        val c = chain("P2P", "Relay")
        val mgr = PeerTransportManager(transportBackend = c, now = { 0L })
        val ctx = aliceContext()
        mgr.createBinding(aliceId, ctx).getOrThrow()
        mgr.sendOffer(aliceId, ctx, "offer", bobId).getOrThrow()
        mgr.receiveAnswer(aliceId, bobId, "answer").getOrThrow()
        mgr.markConnected(aliceId, ctx).getOrThrow()
        c.transports.forEach { (it as ScriptableTransportBackend).failWithTransport() }

        val result = mgr.sendData(aliceId, ctx, "hola".toByteArray()) as TransportResult.Failure

        assertTrue(result.error.permitsFallback, "un medio caido debe seguir autorizando otra ruta")
    }

    @Test
    @DisplayName("CHAIN-14 el cierre alcanza a TODOS los eslabones")
    fun `CHAIN-14 cierre propagado`() {
        // Cerrar solo el primario dejaria fugas: un eslabon que sigue vivo
        // podria recibir datos de un binding que el manager dio por cerrado.
        val c = chain("P2P", "Relay", "Tor")
        c.shutdown()
        c.transports.forEach { assertTrue((it as ScriptableTransportBackend).shutdownCalled, "no se cerro ${it.name}") }
    }

    @Test
    @DisplayName("CHAIN-15 el establecimiento va al PRIMER eslabon, sin fallback")
    fun `CHAIN-15 establecimiento al primario`() {
        // Intentar establecer Relay porque P2P fallo no es un fallback: exige
        // otro procedimiento de negociacion. Eso es 3Q.4.
        val c = chain("P2P", "Relay")
        c.onCreateBinding(aliceId, bobId)
        assertEquals(1, (c.transports[0] as ScriptableTransportBackend).establishmentOps.size)
        assertEquals(0, (c.transports[1] as ScriptableTransportBackend).establishmentOps.size)
    }

    @Test
    @DisplayName("CHAIN-16 un eslabon que revienta queda AISLADO, no tumba la cadena")
    fun `CHAIN-16 excepcion aislada`() {
        // Un backend que lanza es un fallo del MEDIO, no de seguridad: los
        // demas eslabones siguen siendo validos. Y lo que mas importa, la
        // excepcion NO escapa. Sin este aislamiento, un P2P con un bug
        // tumbaria el envio entero del proceso.
        val c = chain("P2P", "Relay")
        (c.transports[0] as ScriptableTransportBackend).sendThrows = RuntimeException("boom")
        val attempts = CopyOnWriteArrayList<ChainAttempt>()
        c.onAttempt { attempts.add(it) }

        val payload = "hola".toByteArray()
        val result = c.sendData(bobId, payload)

        assertTrue(result.isSuccess, "Relay debe absorber el fallo de P2P: $result")
        assertEquals(2, attempts.size, "P2P se registro como fallido y Relay como exitoso")
        val p2pAttempt = attempts[0]
        assertFalse(p2pAttempt.delivered)
        assertTrue(p2pAttempt.result is TransportResult.Failure)
        val p2pError = (p2pAttempt.result as TransportResult.Failure).error
        assertEquals(TransportError.BACKEND_ERROR, p2pError)
        assertTrue(
            p2pError.permitsFallback,
            "una excepcion del backend no puede clasificarse como seguridad: $p2pError",
        )
        assertTrue(attempts[1].delivered)
        // Y los bytes que salen por Relay siguen siendo los mismos.
        assertContentEquals(payload, (c.transports[1] as ScriptableTransportBackend).sentPayloads.first())
    }

    @Test
    @DisplayName("CHAIN-17 los eventos Reach todos los eslabones")
    fun `CHAIN-17 propagacion de eventos`() {
        val c = chain("P2P", "Relay", "Tor")
        val cb = TransportEventCallbacks(onDataReceived = { _, _ -> })
        c.eventCallbacks = cb
        c.transports.forEach { assertSame(cb, it.eventCallbacks, "un eslabon quedaria mudo si no recibe los eventos") }
    }

    // ===================================================================
    // CHAIN-18 — EL INVARIANTE ARQUITECTONICO
    // ===================================================================

    @Test
    @DisplayName("CHAIN-18 el fallback cambia el MEDIO y NO la sesion criptografica")
    fun `CHAIN-18 la sesion sobrevive al cambio de medio`() {
        // La prueba de que la cadena NO es dueña de la sesion: se construye
        // UNA sesion real (X3DH + Double Ratchet + SecureFrame), P2P falla y
        // Relay entrega. Si el fallback creara una sesion nueva, este frame no
        // descifraria en el receptor.
        val x25519 = BcX25519()
        val kdf = BcHkdfSha256()
        val protector = SecureFrameProtector(BcChaCha20Poly1305(), kdf)
        val x3dh = X3dh(x25519, kdf)
        val random = SecureRandom()

        val aliceDh = x25519.generateKeyPair()
        val bobDh = x25519.generateKeyPair()
        val bobSpk = x25519.generateKeyPair()
        val bobOpk = x25519.generateKeyPair()

        // --- Bootstrap: una sola vez, para ambos lados ---
        val f = ByteArray(32).also { random.nextBytes(it) }
        val bobPrekeys = BootstrapPrekeys(
            deviceId = ByteArray(32) { 1 },
            identityAgreementKey = bobDh.publicKey,
            signedPreKey = bobSpk.publicKey,
            signedPreKeyId = 1L,
            oneTimePreKey = bobOpk.publicKey,
            oneTimePreKeyId = 5L,
        )
        val alicePrekeys = BootstrapPrekeys(
            deviceId = ByteArray(32) { 2 },
            identityAgreementKey = aliceDh.publicKey,
            signedPreKey = aliceDh.publicKey,
            signedPreKeyId = 1L,
            oneTimePreKey = null,
            oneTimePreKeyId = null,
        )
        val init = x3dh.initiate(InitiatorKeyMaterial(ByteArray(32), aliceDh), bobPrekeys, f)
        val prep = x3dh.respond(
            ResponderKeyMaterial(
                deviceId = ByteArray(32) { 1 },
                identityAgreementKey = bobDh,
                signedPreKey = bobSpk,
                oneTimePreKey = bobOpk,
                oneTimePreKeyId = 5L,
            ),
            alicePrekeys,
            init.ephemeralPublic,
        )
        assertContentEquals(init.sharedKey, prep.sharedKey, "X3DH debe derivar la misma SK")
        prep.commit()

        val aliceProtocol = RatchetSessionBootstrap.initiatorProtocol(
            bootstrapValue = f,
            ephemeral = init.ephemeral,
            remoteSignedPreKey = bobSpk.publicKey,
            signedPreKeyPrivate = bobSpk.privateKey,
            x25519 = x25519,
            kdf = kdf,
            protector = protector,
        )
        val bobProtocol = SecureRatchetProtocol(
            DoubleRatchetSession(
                rootKey = RatchetSessionBootstrap.rootKeyFrom(f, kdf),
                dhSelf = bobSpk,
                dhRemote = null,
                sendChainKey = ByteArray(32),
                receiveChainKey = ByteArray(32),
                x25519 = x25519,
                kdf = kdf,
            ),
            protector,
        )

        // --- Sesion UNICA, por encima de una cadena de dos medios ---
        val p2p = ScriptableTransportBackend("P2P")
        val relay = ScriptableTransportBackend("Relay")
        val chain = TransportChain(listOf(p2p, relay))
        p2p.failWithTransport(TransportError.BACKEND_SEND_FAILED, "P2P caido")

        val manager = PeerTransportManager(transportBackend = chain, now = { 0L })
        val aliceCtx = aliceContext()
        manager.createBinding(aliceId, aliceCtx).getOrThrow()
        manager.sendOffer(aliceId, aliceCtx, "offer", bobId).getOrThrow()
        manager.receiveAnswer(aliceId, bobId, "answer").getOrThrow()
        manager.markConnected(aliceId, aliceCtx).getOrThrow()

        val session = SecureMessagingSession(aliceId, aliceCtx, aliceProtocol, manager)
        val fingerprintAntes = session.ratchetFingerprint()

        val sent = session.send("hola".toByteArray())
        assertTrue(sent is SendResult.Ok, "el envio debe funcionar via Relay: $sent")

        // 1. P2P no recibio nada; Relay si.
        assertEquals(0, p2p.sentPayloads.size, "P2P estaba caido")
        assertEquals(1, relay.sentPayloads.size, "Relay debe haber recibido el frame")

        // 2. Lo que llego a Relay es el MISMO frame cifrado por la sesion
        //    unica, y descifra en el receptor sin renegociar nada.
        val wire = relay.sentPayloads.first()
        val received = bobProtocol.decrypt(wire)
        assertTrue(received is SecureRatchetProtocol.DecryptResult.Ok, "el frame debe descifrar en el receptor: $received")
        assertContentEquals("hola".toByteArray(), (received as SecureRatchetProtocol.DecryptResult.Ok).plaintext)

        // 3. La sesion NO se reinicio: el ratchet avanzo una vez, no dos.
        assertFalse(
            fingerprintAntes.contentEquals(session.ratchetFingerprint()),
            "el ratchet debe haber avanzado con el envio",
        )
        // Un solo frame por mensaje: no uno por medio. `bytes` es el tamano
        // del frame, no una cantidad de frames.
        assertEquals(wire.size, (sent as SendResult.Ok).bytes, "un unico frame, no uno por medio")
        assertEquals(1, relay.sentPayloads.size, "Relay recibio exactamente un frame")
    }

    @Test
    @DisplayName("CHAIN-19 la cadena no conoce la criptografia: no puede re-cifrar")
    fun `CHAIN-19 la cadena no toca la carga util`() {
        // Si la cadena re-cifrara o re-empaquetara, el payload del primer
        // intento fallido y el del segundo serian distintos. Son el mismo
        // array de bytes, y el backend receptor recibe una COPIA: la cadena no
        // puede mutar lo que el llamante entrego.
        val c = chain("P2P", "Relay")
        (c.transports[0] as ScriptableTransportBackend).failWithTransport()
        val payload = ByteArray(64) { it.toByte() }
        val snapshot = payload.copyOf()

        c.sendData(bobId, payload)

        assertContentEquals(snapshot, payload, "la cadena no debe mutar el payload del llamante")
        val relay = c.transports[1] as ScriptableTransportBackend
        assertContentEquals(snapshot, relay.sentPayloads.first(), "el frame debe salir intacto")
    }

    private fun deriveId(publicKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").let { d ->
            d.update("KM-ID-IDENTITY".toByteArray())
            d.update(publicKey)
            d.digest().joinToString("") { "%02x".format(it) }
        }
}
