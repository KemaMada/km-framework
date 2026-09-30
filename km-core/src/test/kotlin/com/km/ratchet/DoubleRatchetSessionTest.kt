package com.km.ratchet

import com.km.crypto.AeadAuthenticationException
import com.km.crypto.Kdf
import com.km.crypto.X25519
import com.km.crypto.provider.BcHkdfSha256
import com.km.crypto.provider.BcX25519
import com.km.crypto.provider.BcChaCha20Poly1305
import com.km.frame.BinarySecureFrameCodec
import com.km.frame.FrameType
import com.km.frame.RatchetHeader
import com.km.frame.SecureFrame
import com.km.frame.SecureFrameProtector
import com.km.frame.SecureFrameSpec
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals

/**
 * 3O.3 — DH / Root Ratchet: KDF_RK, ratchet DH, PN y atomicidad.
 *
 * Cubre la transicion completa:
 *   SecureFrame(dhPublicKey, PN, N) -> DHRecv -> SymmetricRatchet -> AEAD
 */
class DoubleRatchetSessionTest {

    private lateinit var x25519: X25519
    private lateinit var kdf: Kdf
    private lateinit var protector: SecureFrameProtector

    private val rootKey = ByteArray(32) { (it + 1).toByte() }

    @BeforeEach
    fun setUp() {
        x25519 = BcX25519()
        kdf = BcHkdfSha256()
        protector = SecureFrameProtector(BcChaCha20Poly1305(), kdf)
    }

    /**
     * Sesion con un par DH propio EXPLICITO.
     *
     * Imprescindible: la clave que Alice conoce como `dhRemote` debe ser
     * exactamente el `dhSelf` actual de Bob. Si son claves distintas, ambos
     * derivan secretos DH distintos y las claves de cadena no coinciden.
     */
    private fun newSessionWithDh(
        dhSelf: com.km.crypto.X25519KeyPair,
        dhRemote: ByteArray? = null,
    ): DoubleRatchetSession = DoubleRatchetSession(
        rootKey = rootKey,
        dhSelf = dhSelf,
        dhRemote = dhRemote,
        sendChainKey = ByteArray(32) { (it * 3).toByte() },
        receiveChainKey = ByteArray(32) { (it * 5).toByte() },
        x25519 = x25519,
        kdf = kdf,
    )

    private fun newSession(dhRemote: ByteArray? = null): DoubleRatchetSession {
        val dhSelf = x25519.generateKeyPair()
        val sendCk = ByteArray(32) { (it * 3).toByte() }
        val recvCk = ByteArray(32) { (it * 5).toByte() }
        return DoubleRatchetSession(
            rootKey = rootKey,
            dhSelf = dhSelf,
            dhRemote = dhRemote,
            sendChainKey = sendCk,
            receiveChainKey = recvCk,
            x25519 = x25519,
            kdf = kdf,
        )
    }

    // ===================================================================
    // KDF_RK
    // ===================================================================

    @Test
    @DisplayName("RK-01 KDF_RK es determinista")
    fun `RK-01 KDF_RK es determinista`() {
        val s = newSession()
        val dhOut = ByteArray(32) { (it and 0xFF).toByte() }
        val a = s.kdfRk(rootKey, dhOut)
        val b = s.kdfRk(rootKey, dhOut)
        assertContentEquals(a.newRootKey, b.newRootKey)
        assertContentEquals(a.newChainKey, b.newChainKey)
        assertEquals(32, a.newRootKey.size)
        assertEquals(32, a.newChainKey.size)
    }

    @Test
    @DisplayName("RK-02 rootKey y chainKey nunca son iguales")
    fun `RK-02 root y chain nunca iguales`() {
        val s = newSession()
        val d = s.kdfRk(rootKey, ByteArray(32) { 7 })
        assertFalse(d.newRootKey.contentEquals(d.newChainKey))
    }

    @Test
    @DisplayName("RK-03 dominios ROOT y CHAIN separados")
    fun `RK-03 dominios ROOT y CHAIN separados`() {
        assertNotEquals(RootRatchetSpec.INFO_ROOT, RootRatchetSpec.INFO_CHAIN)
        val s = newSession()
        val dhOut = ByteArray(32) { 9 }
        val root = kdf.hkdf(rootKey, dhOut, RootRatchetSpec.INFO_ROOT.toByteArray(), 32)
        val chain = kdf.hkdf(rootKey, dhOut, RootRatchetSpec.INFO_CHAIN.toByteArray(), 32)
        assertFalse(root.contentEquals(chain))
    }

    @Test
    @DisplayName("RK-04 distinto dhOutput -> distinto raiz")
    fun `RK-04 distinto DH cambia la raiz`() {
        val s = newSession()
        val a = s.kdfRk(rootKey, ByteArray(32) { 1 })
        val b = s.kdfRk(rootKey, ByteArray(32) { 2 })
        assertFalse(a.newRootKey.contentEquals(b.newRootKey))
    }

    @Test
    @DisplayName("RK-05 mismo DH con distinta raiz -> distinto resultado")
    fun `RK-05 mismo DH distinta raiz`() {
        val s = newSession()
        val dhOut = ByteArray(32) { 3 }
        val a = s.kdfRk(rootKey, dhOut)
        val b = s.kdfRk(ByteArray(32) { 200.toByte() }, dhOut)
        assertFalse(a.newRootKey.contentEquals(b.newRootKey))
    }

    @Test
    @DisplayName("RK-06 dominios del Root Ratchet distintos de los del Simetrico")
    fun `RK-06 dominios separados entre ratchets`() {
        assertNotEquals(SymmetricRatchetSpec.INFO_MESSAGE, RootRatchetSpec.INFO_ROOT)
        assertNotEquals(SymmetricRatchetSpec.INFO_CHAIN, RootRatchetSpec.INFO_CHAIN)
    }

    // ===================================================================
    // DH
    // ===================================================================

    @Test
    @DisplayName("DH-01 X25519 produce el mismo secreto en ambos extremos")
    fun `DH-01 DH simetrico`() {
        val a = x25519.generateKeyPair()
        val b = x25519.generateKeyPair()
        assertContentEquals(
            x25519.agree(a.privateKey, b.publicKey),
            x25519.agree(b.privateKey, a.publicKey),
        )
    }

    @Test
    @DisplayName("DH-02 la clave DH propia es efimera y distinta de la identidad")
    fun `DH-02 la clave DH es del ratchet, no de identidad`() {
        val s = newSession()
        val dhPub = s.selfDhPublicKey()
        assertEquals(32, dhPub.size)
        // No es una clave de firma Ed25519: son primitivas distintas.
        val identityHex = rootKey.joinToString("") { "%02x".format(it) }
        assertNotEquals(identityHex, dhPub.joinToString("") { "%02x".format(it) })
    }

    @Test
    @DisplayName("DH-03 el DH ratchet genera una clave publica nueva")
    fun `DH-03 nueva clave DH en cada ratchet`() {
        val remote = x25519.generateKeyPair()
        val s = newSession(dhRemote = remote.publicKey)
        val before = s.selfDhPublicKey()
        // Simula la recepcion de un mensaje con DH distinta.
        val otherDh = x25519.generateKeyPair().publicKey
        s.previewReceive(otherDh, 0u, 0u)
        s.commitReceive()
        assertFalse(before.contentEquals(s.selfDhPublicKey()), "debe generar DH nueva")
    }

    @Test
    @DisplayName("DH-04 el header lleva la clave publica DH propia")
    fun `DH-04 header lleva DH propia`() {
        val s = newSession()
        val preview = s.previewSend()
        assertContentEquals(s.selfDhPublicKey(), preview.dhPublicKey)
    }

    // ===================================================================
    // Sesion completa Alice <-> Bob
    // ===================================================================

    /**
     * Ejecuta un intercambio completo: un mensaje con epoch de DH nuevo.
     *
     * El emisor envia con su DH propia; el receptor la ve como nueva, hace
     * el DH ratchet y obtiene la misma clave de mensaje.
     */
    private fun exchange(
        sender: DoubleRatchetSession,
        receiver: DoubleRatchetSession,
        plaintext: ByteArray,
        senderPN: UInt = sender.currentPreviousChainLength(),
    ): Boolean {
        val send = sender.previewSend()
        val frame = protector.protect(
            FrameType.MESSAGE,
            RatchetHeader(send.dhPublicKey, send.previousChainLength, send.messageNumber),
            send.messageKey,
            plaintext,
        )
        val decoded = BinarySecureFrameCodec.decode(BinarySecureFrameCodec.encode(frame))

        val out = receiver.previewReceive(
            decoded.ratchetHeader.dhPublicKey,
            decoded.ratchetHeader.previousChainLength,
            decoded.ratchetHeader.messageNumber,
        )
        if (out !is DoubleRatchetSession.ReceiveResult.Ready) return false
        val got = protector.unprotect(decoded, out.messageKey)
        receiver.commitReceive()
        sender.commitSend()
        return got.contentEquals(plaintext)
    }

    @Test
    @DisplayName("SES-01 intercambio completo Alice -> Bob con epoch DH")
    fun `SES-01 intercambio completo`() {
        val bobPre = x25519.generateKeyPair()
        val alice = newSession(dhRemote = bobPre.publicKey)
        val bob = newSessionWithDh(bobPre)
        // Alice inicia la epoca: KDF_RK con DH(alicePriv, bobPrePub).
        // Bob, al ver la DH nueva, usa SU DH actual (bobPre) y coincide.
        alice.initiateEpoch()
        assertTrue(exchange(alice, bob, "hola".toByteArray()), "el mensaje debe descifrar")
    }

    @Test
    @DisplayName("SES-02 varios mensajes en la misma epoch")
    fun `SES-02 varios mensajes misma epoch`() {
        val bobPre = x25519.generateKeyPair()
        val alice = newSession(dhRemote = bobPre.publicKey)
        val bob = newSessionWithDh(bobPre)
        alice.initiateEpoch()
        // El primer mensaje dispara el ratchet DH en Bob.
        assertTrue(exchange(alice, bob, "m0".toByteArray()))
        // Los siguientes usan la misma DH.
        for (i in 1..4) {
            assertTrue(exchange(alice, bob, "m$i".toByteArray()), "mensaje $i")
        }
    }

    @Test
    @DisplayName("SES-03 respuesta de ida y vuelta")
    fun `SES-03 ida y vuelta`() {
        val bobPre = x25519.generateKeyPair()
        val alice = newSession(dhRemote = bobPre.publicKey)
        val bob = newSessionWithDh(bobPre)
        alice.initiateEpoch()
        assertTrue(exchange(alice, bob, "a->b".toByteArray()))
        // Bob responde usando su propia cadena de envio (ya derivada).
        assertTrue(exchange(bob, alice, "b->a".toByteArray()))
    }

    // ===================================================================
    // Atomicidad — la propiedad transversal mas importante
    // ===================================================================

    @Test
    @DisplayName("AT-01 descifrado correcto -> commit avanza el estado")
    fun `AT-01 commit tras exito`() {
        val bobPre = x25519.generateKeyPair()
        val alice = newSession(dhRemote = bobPre.publicKey)
        val bob = newSessionWithDh(bobPre)
        alice.initiateEpoch()
        assertTrue(exchange(alice, bob, "ok".toByteArray()))
        assertEquals(1u, alice.currentSendMessageNumber())
    }

    @Test
    @DisplayName("AT-02 AEAD falla -> rollback completo, sin consumir estado")
    fun `AT-02 rollback tras fallo AEAD`() {
        val bobPre = x25519.generateKeyPair()
        val alice = newSession(dhRemote = bobPre.publicKey)
        val bob = newSessionWithDh(bobPre)
        alice.initiateEpoch()

        // Un frame MANIPULADO: el DH ratchet se ejecutaria, pero el AEAD falla.
        val send = alice.previewSend()
        val goodFrame = protector.protect(
            FrameType.MESSAGE,
            RatchetHeader(send.dhPublicKey, send.previousChainLength, send.messageNumber),
            send.messageKey,
            "secreto".toByteArray(),
        )
        // Se altera el ciphertext: la autenticacion debe fallar.
        val badCt = goodFrame.ciphertext.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val tampered = goodFrame.copy(ciphertext = badCt)

        val out = bob.previewReceive(
            tampered.ratchetHeader.dhPublicKey,
            tampered.ratchetHeader.previousChainLength,
            tampered.ratchetHeader.messageNumber,
        )
        assertTrue(out is DoubleRatchetSession.ReceiveResult.Ready)
        org.junit.jupiter.api.assertThrows<AeadAuthenticationException> {
            protector.unprotect(tampered, (out as DoubleRatchetSession.ReceiveResult.Ready).messageKey)
        }
        // NO se confirma: se descarta.
        bob.discardReceive()

        // Ahora el mensaje BUENO debe descifrar como si el malo nunca hubiera llegado.
        alice.discardSend()
        assertTrue(exchange(alice, bob, "secreto".toByteArray()),
            "el mensaje valido debe descifrar tras el ataque")
    }

    @Test
    @DisplayName("AT-03 tras un ataque, la sesion sigue sincronizada")
    fun `AT-03 la sesion sigue sincronizada tras un ataque`() {
        val bobPre = x25519.generateKeyPair()
        val alice = newSession(dhRemote = bobPre.publicKey)
        val bob = newSessionWithDh(bobPre)
        alice.initiateEpoch()

        // Mensaje malicioso con DH arbitraria.
        val evil = x25519.generateKeyPair().publicKey
        val out = bob.previewReceive(evil, 0u, 0u)
        if (out is DoubleRatchetSession.ReceiveResult.Ready) {
            bob.discardReceive()
        }
        // Y el mensaje legitimo funciona.
        assertTrue(exchange(alice, bob, "legitimo".toByteArray()))
    }

    @Test
    @DisplayName("AT-04 la clave publica DH propia no cambia sin commit")
    fun `AT-04 la DH no cambia sin commit`() {
        val bobPre = x25519.generateKeyPair()
        val alice = newSession(dhRemote = bobPre.publicKey)
        val bob = newSessionWithDh(bobPre)
        val before = bob.selfDhPublicKey()
        bob.previewReceive(x25519.generateKeyPair().publicKey, 0u, 0u)
        assertContentEquals(before, bob.selfDhPublicKey(), "preview no debe mutar la DH")
    }

    // ===================================================================
    // PN
    // ===================================================================

    @Test
    @DisplayName("PN-01 PN registra la longitud de la cadena de envio anterior")
    fun `PN-01 PN registra la cadena anterior`() {
        val bobPre = x25519.generateKeyPair()
        val alice = newSession(dhRemote = bobPre.publicKey)
        val bob = newSessionWithDh(bobPre)
        alice.initiateEpoch()

        // Alice inicia la epoch; Bob responde y envia 3 mensajes.
        assertTrue(exchange(alice, bob, "a0".toByteArray()))
        for (i in 0..2) {
            assertTrue(exchange(bob, alice, "b$i".toByteArray()), "b$i")
        }
        assertEquals(3u, bob.currentSendMessageNumber())
        assertEquals(0u, bob.currentPreviousChainLength(), "PN es 0 antes de rotar")

        // Bob inicia una nueva epoch: PN debe registrar su cadena de 3.
        bob.initiateEpoch()
        assertEquals(3u, bob.currentPreviousChainLength(),
            "PN debe registrar los 3 mensajes de la cadena de envio anterior")
        assertEquals(0u, bob.currentSendMessageNumber(), "la nueva cadena reinicia N")
    }

    @Test
    @DisplayName("PN-02 la epoch siguiente reinicia N a cero")
    fun `PN-02 la nueva epoch reinicia N`() {
        val bobPre = x25519.generateKeyPair()
        val alice = newSession(dhRemote = bobPre.publicKey)
        val bob = newSessionWithDh(bobPre)
        alice.initiateEpoch()
        assertTrue(exchange(alice, bob, "a0".toByteArray()))

        // Bob contesta dos veces antes de rotar.
        assertTrue(exchange(bob, alice, "b0".toByteArray()))
        assertTrue(exchange(bob, alice, "b1".toByteArray()))
        assertEquals(2u, bob.currentSendMessageNumber())

        bob.initiateEpoch()
        assertEquals(0u, bob.currentSendMessageNumber())
        assertEquals(2u, bob.currentPreviousChainLength())
    }

    @Test
    @DisplayName("PN-03 PN viaja en el header del siguiente mensaje")
    fun `PN-03 PN viaja en el header`() {
        val bobPre = x25519.generateKeyPair()
        val alice = newSession(dhRemote = bobPre.publicKey)
        val bob = newSessionWithDh(bobPre)
        alice.initiateEpoch()
        assertTrue(exchange(alice, bob, "a0".toByteArray()))
        assertTrue(exchange(bob, alice, "b0".toByteArray()))
        assertTrue(exchange(bob, alice, "b1".toByteArray()))

        // Bob rota: su siguiente header debe llevar PN=2.
        bob.initiateEpoch()
        val send = bob.previewSend()
        assertEquals(2u, send.previousChainLength, "el header debe anunciar PN=2")
        assertEquals(0u, send.messageNumber, "N vuelve a 0 en la nueva cadena")
        bob.discardSend()
    }

    // ===================================================================
    // Replay / rebotes
    // ===================================================================

    @Test
    @DisplayName("RE-01 replay de un mensaje ya consumido se rechaza")
    fun `RE-01 replay se rechaza`() {
        val bobPre = x25519.generateKeyPair()
        val alice = newSession(dhRemote = bobPre.publicKey)
        val bob = newSessionWithDh(bobPre)
        alice.initiateEpoch()

        val send = alice.previewSend()
        val frame = protector.protect(
            FrameType.MESSAGE,
            RatchetHeader(send.dhPublicKey, send.previousChainLength, send.messageNumber),
            send.messageKey, "uno".toByteArray(),
        )
        val wire = BinarySecureFrameCodec.encode(frame)

        // Primera recepcion: OK.
        val d1 = BinarySecureFrameCodec.decode(wire)
        val r1 = bob.previewReceive(
            d1.ratchetHeader.dhPublicKey, d1.ratchetHeader.previousChainLength, d1.ratchetHeader.messageNumber,
        )
        assertTrue(r1 is DoubleRatchetSession.ReceiveResult.Ready)
        bob.commitReceive()
        alice.commitSend()

        // Replay del mismo wire: la DH es la misma, N=0 ya se consumio.
        val d2 = BinarySecureFrameCodec.decode(wire)
        val r2 = bob.previewReceive(
            d2.ratchetHeader.dhPublicKey, d2.ratchetHeader.previousChainLength, d2.ratchetHeader.messageNumber,
        )
        assertTrue(r2 is DoubleRatchetSession.ReceiveResult.Rejected, "el replay debe rechazarse")
    }

    // ===================================================================
    // MAX_SKIP conservado
    // ===================================================================

    @Test
    @DisplayName("LIMIT MAX_SKIP sigue siendo 1000")
    fun `LIMIT MAX_SKIP intacto`() {
        assertEquals(1000, SymmetricRatchetSpec.MAX_SKIP)
    }

}
