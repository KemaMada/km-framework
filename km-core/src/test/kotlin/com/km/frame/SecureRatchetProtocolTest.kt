package com.km.frame

import com.km.crypto.provider.BcChaCha20Poly1305
import com.km.crypto.provider.BcHkdfSha256
import com.km.crypto.provider.BcX25519
import com.km.crypto.X25519
import com.km.crypto.Kdf
import com.km.ratchet.DoubleRatchetSession
import com.km.ratchet.RejectReason
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals

/**
 * 3O.4 — Double Ratchet + SecureFrame E2E.
 *
 * Cada mensaje recorre obligatoriamente:
 *   plaintext -> ratchet -> messageKey -> SecureFrame -> AEAD -> wire
 *   wire -> decode -> ratchet -> messageKey -> AEAD -> plaintext
 *
 * No hay ningun camino de prueba que llame al ratchet evitando SecureFrame.
 */
class SecureRatchetProtocolTest {

    private lateinit var x25519: X25519
    private lateinit var kdf: Kdf
    private lateinit var protector: SecureFrameProtector

    private val rootKey = ByteArray(32) { (it + 1).toByte() }

    /** Bob: la clave DH que Alice conoce como su prekey. */
    private lateinit var bobDh: com.km.crypto.X25519KeyPair

    @BeforeEach
    fun setUp() {
        x25519 = BcX25519()
        kdf = BcHkdfSha256()
        protector = SecureFrameProtector(BcChaCha20Poly1305(), kdf)
        bobDh = x25519.generateKeyPair()
    }

    private fun sessionFor(dhSelf: com.km.crypto.X25519KeyPair, dhRemote: ByteArray?) =
        DoubleRatchetSession(
            rootKey = rootKey,
            dhSelf = dhSelf,
            dhRemote = dhRemote,
            sendChainKey = ByteArray(32) { (it * 3).toByte() },
            receiveChainKey = ByteArray(32) { (it * 5).toByte() },
            x25519 = x25519,
            kdf = kdf,
        )

    /** Alice conoce la DH publica de Bob; Bob usa ESE MISMO par. */
    private fun newPair(): Pair<SecureRatchetProtocol, SecureRatchetProtocol> {
        val aliceSession = sessionFor(x25519.generateKeyPair(), bobDh.publicKey)
        val bobSession = sessionFor(bobDh, null)
        val alice = SecureRatchetProtocol(aliceSession, protector)
        val bob = SecureRatchetProtocol(bobSession, protector)
        alice.initiateEpoch()
        return alice to bob
    }

    private fun ok(r: SecureRatchetProtocol.DecryptResult): ByteArray {
        assertTrue(r is SecureRatchetProtocol.DecryptResult.Ok, "esperaba Ok, fue $r")
        return (r as SecureRatchetProtocol.DecryptResult.Ok).plaintext
    }

    // ===================================================================
    // E2E basico
    // ===================================================================

    @Test
    @DisplayName("DR-01 Alice -> Bob extremo a extremo")
    fun `DR-01 Alice a Bob`() {
        val (alice, bob) = newPair()
        val wire = alice.encrypt("hola".toByteArray())
        assertContentEquals("hola".toByteArray(), ok(bob.decrypt(wire)))
    }

    @Test
    @DisplayName("DR-02 bidireccional")
    fun `DR-02 bidireccional`() {
        val (alice, bob) = newPair()
        assertContentEquals("a1".toByteArray(), ok(bob.decrypt(alice.encrypt("a1".toByteArray()))))
        assertContentEquals("b1".toByteArray(), ok(alice.decrypt(bob.encrypt("b1".toByteArray()))))
    }

    @Test
    @DisplayName("DR-03 6 mensajes en la misma epoch")
    fun `DR-03 seis mensajes misma epoch`() {
        val (alice, bob) = newPair()
        for (i in 0..5) {
            val msg = "m$i".toByteArray()
            assertContentEquals(msg, ok(bob.decrypt(alice.encrypt(msg))), "m$i")
        }
    }

    @Test
    @DisplayName("DR-04 numeracion N correcta y monotona")
    fun `DR-04 numeracion N`() {
        val (alice, bob) = newPair()
        repeat(4) { alice.encrypt(byteArrayOf(1)) }
        assertEquals(4u, alice.sentCount())
    }

    @Test
    @DisplayName("DR-05 el wire contiene un SecureFrame valido")
    fun `DR-05 el wire es un SecureFrame`() {
        val (alice, _) = newPair()
        val wire = alice.encrypt("contenido".toByteArray())
        val frame = BinarySecureFrameCodec.decode(wire)
        assertEquals(1, frame.version.toInt())
        assertEquals(44 + "contenido".length + 16, wire.size)
        // N=0 en la primera epoch.
        assertEquals(0u, frame.ratchetHeader.messageNumber)
    }

    // ===================================================================
    // La prueba larga de la sesion completa
    // ===================================================================

    @Test
    @DisplayName("DR-06 sesion completa: A1..A6, B1..B3 con epoch changes")
    fun `DR-06 sesion completa`() {
        val (alice, bob) = newPair()

        // A1, A2, A3  (epoch de Alice)
        val a1 = alice.encrypt("A1".toByteArray())
        val a2 = alice.encrypt("A2".toByteArray())
        val a3 = alice.encrypt("A3".toByteArray())
        assertContentEquals("A1".toByteArray(), ok(bob.decrypt(a1)))
        assertContentEquals("A2".toByteArray(), ok(bob.decrypt(a2)))
        assertContentEquals("A3".toByteArray(), ok(bob.decrypt(a3)))

        // B1 (Bob responde en su propia cadena)
        val b1 = bob.encrypt("B1".toByteArray())
        assertContentEquals("B1".toByteArray(), ok(alice.decrypt(b1)))

        // A4, A5 (misma epoch de Alice)
        val a4 = alice.encrypt("A4".toByteArray())
        val a5 = alice.encrypt("A5".toByteArray())
        assertContentEquals("A4".toByteArray(), ok(bob.decrypt(a4)))
        assertContentEquals("A5".toByteArray(), ok(bob.decrypt(a5)))

        // B2, B3
        val b2 = bob.encrypt("B2".toByteArray())
        val b3 = bob.encrypt("B3".toByteArray())
        assertContentEquals("B2".toByteArray(), ok(alice.decrypt(b2)))
        assertContentEquals("B3".toByteArray(), ok(alice.decrypt(b3)))

        // A6
        val a6 = alice.encrypt("A6".toByteArray())
        assertContentEquals("A6".toByteArray(), ok(bob.decrypt(a6)))
    }

    @Test
    @DisplayName("DR-07 PN refleja la cadena anterior en el header")
    fun `DR-07 PN en el header`() {
        val (alice, bob) = newPair()
        // Bob recibe 3 mensajes de Alice (eso NO cuenta como su N de envio).
        repeat(3) { bob.decrypt(alice.encrypt("a".toByteArray())) }
        assertEquals(0u, bob.sentCount(), "recibir no avanza la cadena de envio")

        // Bob responde 3 veces: su N de envio llega a 3.
        repeat(3) { bob.encrypt("b".toByteArray()) }
        assertEquals(3u, bob.sentCount())
        bob.initiateEpoch()
        assertEquals(3u, bob.previousChainLength(), "PN=3 en la nueva epoch")
    }

    // ===================================================================
    // Fuera de orden y skipped keys
    // ===================================================================

    @Test
    @DisplayName("DR-08 out-of-order: B1, B3, B2")
    fun `DR-08 out of order`() {
        val (alice, bob) = newPair()
        assertContentEquals("A1".toByteArray(), ok(bob.decrypt(alice.encrypt("A1".toByteArray()))))
        // Bob responde 3 veces.
        val b1 = bob.encrypt("B1".toByteArray())
        val b2 = bob.encrypt("B2".toByteArray())
        val b3 = bob.encrypt("B3".toByteArray())
        // Llega en orden B1, B3, B2.
        assertContentEquals("B1".toByteArray(), ok(alice.decrypt(b1)))
        assertContentEquals("B3".toByteArray(), ok(alice.decrypt(b3)))
        assertContentEquals("B2".toByteArray(), ok(alice.decrypt(b2)))
    }

    @Test
    @DisplayName("DR-09 B3 antes que B2 usa skipped key")
    fun `DR-09 skipped key`() {
        val (alice, bob) = newPair()
        ok(bob.decrypt(alice.encrypt("A1".toByteArray())))
        val b1 = bob.encrypt("B1".toByteArray())
        val b2 = bob.encrypt("B2".toByteArray())
        val b3 = bob.encrypt("B3".toByteArray())
        ok(alice.decrypt(b1))
        // B3 salta por encima de B2 -> B2 queda retenida.
        // B3 avanza una posicion: N=0->2, retiene N=1 (B2). B3 es en orden.
        val r3 = alice.decrypt(b3)
        assertTrue(r3 is SecureRatchetProtocol.DecryptResult.Ok)
        assertFalse((r3 as SecureRatchetProtocol.DecryptResult.Ok).fromSkipped, "B3 es en orden")
        // B2 llega despues: se sirve desde skipped.
        val r2 = alice.decrypt(b2)
        assertTrue(r2 is SecureRatchetProtocol.DecryptResult.Ok)
        assertTrue((r2 as SecureRatchetProtocol.DecryptResult.Ok).fromSkipped, "B2 desde skipped")
        assertContentEquals("B2".toByteArray(), r2.plaintext)
    }

    @Test
    @DisplayName("DR-10 replay de un mensaje consumido se rechaza")
    fun `DR-10 replay rechazado`() {
        val (alice, bob) = newPair()
        ok(bob.decrypt(alice.encrypt("A1".toByteArray())))
        val b1 = bob.encrypt("B1".toByteArray())
        ok(alice.decrypt(b1))
        val before = alice.stateFingerprint()
        val replay = alice.decrypt(b1)
        assertTrue(replay is SecureRatchetProtocol.DecryptResult.Rejected, "replay debe rechazarse")
        assertContentEquals(before, alice.stateFingerprint(), "el replay no debe mutar el estado")
    }

    // ===================================================================
    // Rechazos sin mutacion de estado
    // ===================================================================

    @Test
    @DisplayName("DR-11 ciphertext alterado se rechaza sin mutar estado")
    fun `DR-11 ciphertext alterado`() {
        val (alice, bob) = newPair()
        bob.decrypt(alice.encrypt("sync".toByteArray()))
        val before = bob.stateFingerprint()
        val wire = alice.encrypt("secreto".toByteArray()).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val r = bob.decrypt(wire)
        assertTrue(r is SecureRatchetProtocol.DecryptResult.Unauthenticated, "$r")
        assertContentEquals(before, bob.stateFingerprint(), "no debe mutar")
    }

    @Test
    @DisplayName("DR-12 header alterado se rechaza sin mutar estado")
    fun `DR-12 header alterado`() {
        val (alice, bob) = newPair()
        bob.decrypt(alice.encrypt("sync".toByteArray()))
        val before = bob.stateFingerprint()
        val wire = alice.encrypt("secreto".toByteArray())
        // Alterar el messageNumber (offset 40..44).
        wire[40] = (wire[40] + 1).toByte()
        val r = bob.decrypt(wire)
        assertTrue(
            r is SecureRatchetProtocol.DecryptResult.Unauthenticated ||
                r is SecureRatchetProtocol.DecryptResult.Rejected, "$r",
        )
        assertContentEquals(before, bob.stateFingerprint(), "no debe mutar")
    }

    @Test
    @DisplayName("DR-13 dhPublicKey alterada se rechaza sin mutar estado")
    fun `DR-13 dh alterada`() {
        val (alice, bob) = newPair()
        bob.decrypt(alice.encrypt("sync".toByteArray()))
        val before = bob.stateFingerprint()
        val wire = alice.encrypt("secreto".toByteArray())
        // Alterar la clave publica DH (offset 4..36).
        wire[4] = (wire[4] + 1).toByte()
        val r = bob.decrypt(wire)
        assertTrue(
            r is SecureRatchetProtocol.DecryptResult.Unauthenticated ||
                r is SecureRatchetProtocol.DecryptResult.Rejected, "$r",
        )
        assertContentEquals(before, bob.stateFingerprint(), "no debe mutar")
    }

    @Test
    @DisplayName("DR-14 PN alterado se rechaza sin mutar estado")
    fun `DR-14 PN alterado`() {
        val (alice, bob) = newPair()
        bob.decrypt(alice.encrypt("sync".toByteArray()))
        val before = bob.stateFingerprint()
        val wire = alice.encrypt("secreto".toByteArray())
        // Alterar PN (offset 36..40). PN forma parte del AAD, asi que la
        // autenticacion AEAD lo detecta: el rechazo ocurre en la capa AEAD.
        wire[36] = 0x7F; wire[37] = 0xFF.toByte(); wire[38] = 0xFF.toByte(); wire[39] = 0xFF.toByte()
        val r = bob.decrypt(wire)
        assertTrue(
            r is SecureRatchetProtocol.DecryptResult.Unauthenticated ||
                r is SecureRatchetProtocol.DecryptResult.Rejected, "$r",
        )
        assertContentEquals(before, bob.stateFingerprint(), "no debe mutar")
    }

    @Test
    @DisplayName("DR-15 N excesivo se rechaza por MAX_SKIP")
    fun `DR-15 N excesivo`() {
        val (alice, bob) = newPair()
        bob.decrypt(alice.encrypt("sync".toByteArray()))
        val before = bob.stateFingerprint()
        val wire = alice.encrypt("secreto".toByteArray())
        // N = UInt.MAX_VALUE (offset 40..44).
        wire[40] = 0xFF.toByte(); wire[41] = 0xFF.toByte(); wire[42] = 0xFF.toByte(); wire[43] = 0xFF.toByte()
        val r = bob.decrypt(wire)
        assertTrue(r is SecureRatchetProtocol.DecryptResult.Rejected, "$r")
        assertEquals(RejectReason.SKIP_LIMIT_EXCEEDED, (r as SecureRatchetProtocol.DecryptResult.Rejected).reason)
        assertContentEquals(before, bob.stateFingerprint(), "no debe mutar")
    }

    @Test
    @DisplayName("DR-16 frame truncado o corrupto se rechaza")
    fun `DR-16 frame corrupto`() {
        val (alice, bob) = newPair()
        bob.decrypt(alice.encrypt("sync".toByteArray()))
        val before = bob.stateFingerprint()
        for (bad in listOf(
            ByteArray(0),
            ByteArray(10),
            ByteArray(44),
            alice.encrypt("x".toByteArray()).copyOf(60),
            alice.encrypt("x".toByteArray()).plus(byteArrayOf(0)),
        )) {
            val r = bob.decrypt(bad)
            assertTrue(
                r is SecureRatchetProtocol.DecryptResult.Rejected, "malformado aceptado: $r",
            )
        }
        assertContentEquals(before, bob.stateFingerprint(), "no debe mutar")
    }

    @Test
    @DisplayName("DR-17 version desconocida se rechaza sin descifrar")
    fun `DR-17 version desconocida`() {
        val (alice, bob) = newPair()
        bob.decrypt(alice.encrypt("sync".toByteArray()))
        val before = bob.stateFingerprint()
        val wire = alice.encrypt("secreto".toByteArray())
        wire[0] = 99  // version
        val r = bob.decrypt(wire)
        assertTrue(r is SecureRatchetProtocol.DecryptResult.Rejected, "$r")
        assertContentEquals(before, bob.stateFingerprint(), "no debe mutar")
    }

    // ===================================================================
    // LA PRUEBA MAS VALIOSA: legitimo -> malicioso -> legitimo
    // ===================================================================

    @Test
    @DisplayName("DR-18 legitimo -> malicioso -> legitimo (estado intacto)")
    fun `DR-18 legitimo malicioso legitimo`() {
        val (alice, bob) = newPair()

        // A1 legitimo.
        assertContentEquals("A1".toByteArray(), ok(bob.decrypt(alice.encrypt("A1".toByteArray()))))
        // A2 legitimo.
        assertContentEquals("A2".toByteArray(), ok(bob.decrypt(alice.encrypt("A2".toByteArray()))))

        val stateBefore = bob.stateFingerprint()

        // A3 MODIFICADO: debe rechazarse.
        val a3wire = alice.encrypt("A3".toByteArray())
        val tampered = a3wire.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1] + 1).toByte()
        val r3 = bob.decrypt(tampered)
        assertTrue(
            r3 is SecureRatchetProtocol.DecryptResult.Unauthenticated ||
                r3 is SecureRatchetProtocol.DecryptResult.Rejected,
            "A3 manipulado debio rechazarse, fue $r3",
        )
        // El estado de Bob NO cambio.
        assertContentEquals(stateBefore, bob.stateFingerprint(),
            "el mensaje manipulado no debe mutar el estado de Bob")

        // A4 legitimo: debe descifrar normalmente.
        // Alice ya avanzo su contador al cifrar A3, asi que A4 usa la clave correcta.
        val a4wire = alice.encrypt("A4".toByteArray())
        assertContentEquals("A4".toByteArray(), ok(bob.decrypt(a4wire)),
            "A4 debe descifrar tras el ataque")
    }

    @Test
    @DisplayName("DR-19 ataque entre epochs no rompe la sesion")
    fun `DR-19 ataque entre epochs`() {
        val (alice, bob) = newPair()
        ok(bob.decrypt(alice.encrypt("A1".toByteArray())))
        val before = bob.stateFingerprint()

        // Un frame con DH publica arbitraria: dispara un ratchet DH que
        // debe descartarse al no autenticar.
        val evilDh = x25519.generateKeyPair().publicKey
        val evil = SecureRatchetProtocol(
            sessionFor(x25519.generateKeyPair(), bobDh.publicKey),
            protector,
        )
        val evilWire = evil.encrypt("ataque".toByteArray())
        // Reescribir la DH del frame con la de Bob para que parece legitima
        // pero sin la clave correcta.
        System.arraycopy(evilDh, 0, evilWire, 4, 32)

        val r = bob.decrypt(evilWire)
        assertTrue(
            r is SecureRatchetProtocol.DecryptResult.Unauthenticated ||
                r is SecureRatchetProtocol.DecryptResult.Rejected, "$r",
        )
        assertContentEquals(before, bob.stateFingerprint(), "no debe mutar")

        // Y la sesion legitima sigue viva.
        assertContentEquals("A2".toByteArray(), ok(bob.decrypt(alice.encrypt("A2".toByteArray()))))
    }

    // ===================================================================
    // Aislamiento
    // ===================================================================

    @Test
    @DisplayName("DR-20 el protocolo no expone el estado interno")
    fun `DR-20 no expone estado interno`() {
        val fields = SecureRatchetProtocol::class.java.declaredFields.map { it.name }
        // Solo contiene la sesion, el protector y el codec: ningun RK/CK/DH.
        assertFalse(fields.any { it.equals("rootKey", true) }, "$fields")
        assertFalse(fields.any { it.equals("chainKey", true) }, "$fields")
        assertFalse(fields.any { it.equals("dhSelf", true) }, "$fields")
        assertFalse(fields.any { it.equals("messageKey", true) }, "$fields")
    }
}
