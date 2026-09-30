package com.keymessage.core.x3dh

import com.keymessage.core.crypto.Aead
import com.keymessage.core.crypto.AeadAuthenticationException
import com.keymessage.core.crypto.Kdf
import com.keymessage.core.crypto.X25519
import com.keymessage.core.crypto.provider.BcChaCha20Poly1305
import com.keymessage.core.crypto.provider.BcHkdfSha256
import com.keymessage.core.crypto.provider.BcX25519
import com.keymessage.core.sf.BinarySecureFrameCodec
import com.keymessage.core.sf.FrameType
import com.keymessage.core.sf.RatchetHeader
import com.keymessage.core.sf.SecureFrame
import com.keymessage.core.sf.SecureFrameProtector
import com.keymessage.core.sf.SecureFrameSpec
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.SecureRandom
import kotlin.test.assertContentEquals

/**
 * 3O.5.0 — X3DH (KM-0006 v0.3).
 *
 * Cubre las cuatro DH, la separacion de dominios, la clave F, la
 * transaccionalidad del OPK y los tests arquitectonicos X3DH-IK-*.
 */
class X3dhTest {

    private lateinit var x25519: X25519
    private lateinit var kdf: Kdf
    private lateinit var aead: Aead
    private lateinit var x3dh: X3dh
    private lateinit var protector: SecureFrameProtector

    private val random = SecureRandom()

    @BeforeEach
    fun setUp() {
        x25519 = BcX25519()
        kdf = BcHkdfSha256()
        aead = BcChaCha20Poly1305()
        x3dh = X3dh(x25519, kdf)
        protector = SecureFrameProtector(aead, kdf)
    }

    private fun randomF() = ByteArray(32).also { random.nextBytes(it) }

    /**
     * Material de un dispositivo: IK, SPK y OPK opcional.
     *
     * Las claves publicas deben ser REALES: una clave publica de todo cero
     * es un punto de bajo orden y X25519 la rechaza correctamente.
     */
    private inner class Device(ik: com.keymessage.core.crypto.X25519KeyPair) {
        val ik = ik
        val deviceId: ByteArray = ByteArray(32) { it.toByte() }
        val spk = x25519.generateKeyPair()
        var opk: com.keymessage.core.crypto.X25519KeyPair? = null

        fun prekeys(opkId: Long? = null): BootstrapPrekeys = BootstrapPrekeys(
            deviceId = deviceId,
            identityAgreementKey = ik.publicKey,
            signedPreKey = spk.publicKey,
            signedPreKeyId = 1L,
            oneTimePreKey = opk?.publicKey,
            oneTimePreKeyId = opkId,
        )

        fun responder(opkId: Long? = null): ResponderKeyMaterial = ResponderKeyMaterial(
            deviceId = deviceId,
            identityAgreementKey = ik,
            signedPreKey = spk,
            oneTimePreKey = opk,
            oneTimePreKeyId = opkId,
        )

        fun initiator(): InitiatorKeyMaterial =
            InitiatorKeyMaterial(deviceId, ik)
    }

    private fun newDevice() = Device(x25519.generateKeyPair())

    // ===================================================================
    // Simetria: ambos extremos derivan el mismo SK
    // ===================================================================

    @Test
    @DisplayName("X3DH-01 Alice y Bob derivan el mismo SK sin OPK")
    fun `X3DH-01 SK coincide sin OPK`() {
        val alice = newDevice()
        val bob = newDevice()
        val f = randomF()

        val init = x3dh.initiate(alice.initiator(), bob.prekeys(), f)
        val prep = x3dh.respond(bob.responder(), alice.prekeys(), init.ephemeralPublic)

        assertContentEquals(init.sharedKey, prep.sharedKey, "SK debe coincidir")
        assertContentEquals(init.preKeyKey, prep.preKeyKey, "K_prekey debe coincidir")
    }

    @Test
    @DisplayName("X3DH-02 Alice y Bob derivan el mismo SK con OPK")
    fun `X3DH-02 SK coincide con OPK`() {
        val alice = newDevice()
        val bob = newDevice()
        bob.opk = x25519.generateKeyPair()
        val f = randomF()

        val init = x3dh.initiate(alice.initiator(), bob.prekeys(opkId = 7L), f)
        val prep = x3dh.respond(bob.responder(opkId = 7L), alice.prekeys(), init.ephemeralPublic)

        assertContentEquals(init.sharedKey, prep.sharedKey)
        assertEquals(7L, init.usedOneTimePreKeyId)
        assertEquals(7L, prep.usedOneTimePreKeyId)
    }

    @Test
    @DisplayName("X3DH-03 con OPK el SK difiere del SK sin OPK")
    fun `X3DH-03 OPK cambia el SK`() {
        val alice = newDevice()
        val bob = newDevice()
        val f = randomF()
        val sinOpk = x3dh.initiate(alice.initiator(), bob.prekeys(), f)

        bob.opk = x25519.generateKeyPair()
        val conOpk = x3dh.initiate(alice.initiator(), bob.prekeys(opkId = 1L), f)

        assertFalse(sinOpk.sharedKey.contentEquals(conOpk.sharedKey),
            "añadir un OPK debe cambiar el secreto")
    }

    @Test
    @DisplayName("X3DH-04 F distinto produce SK distinto con el mismo SPK")
    fun `X3DH-04 F varía el SK`() {
        val alice = newDevice()
        val bob = newDevice()
        val a = x3dh.initiate(alice.initiator(), bob.prekeys(), randomF())
        val b = x3dh.initiate(alice.initiator(), bob.prekeys(), randomF())
        // Mismo SPK, distintas sesiones: F es la mitad del secreto.
        assertFalse(a.sharedKey.contentEquals(b.sharedKey),
            "sin F, dos sesiones contra el mismo SPK seriam indistinguibles")
    }

    // ===================================================================
    // Dominios
    // ===================================================================

    @Test
    @DisplayName("X3DH-DOM-01 dominios DH1..DH4 y SK distintos")
    fun `X3DH-DOM-01 dominios distintos`() {
        val domains = listOf(
            X3dhSpec.INFO_DH1, X3dhSpec.INFO_DH2, X3dhSpec.INFO_DH3,
            X3dhSpec.INFO_DH4, X3dhSpec.INFO_SK, X3dhSpec.INFO_PREKEY,
        )
        assertEquals(domains.size, domains.toSet().size, "todos los dominios deben ser unicos")
        assertTrue(domains.all { it.startsWith("KM-0006/X3DH/V1/") })
    }

    @Test
    @DisplayName("X3DH-DOM-02 un mismo secreto con dominios distintos da material distinto")
    fun `X3DH-DOM-02 material distinto por dominio`() {
        val secret = ByteArray(32) { 7 }
        val byDh1 = kdf.hkdf(ByteArray(32), secret, X3dhSpec.INFO_DH1.toByteArray(), 32)
        val byDh2 = kdf.hkdf(ByteArray(32), secret, X3dhSpec.INFO_DH2.toByteArray(), 32)
        val bySk = kdf.hkdf(ByteArray(32), secret, X3dhSpec.INFO_SK.toByteArray(), 32)
        assertFalse(byDh1.contentEquals(byDh2))
        assertFalse(byDh1.contentEquals(bySk))
    }

    @Test
    @DisplayName("X3DH-DOM-03 SK no se usa como clave AEAD directamente")
    fun `X3DH-DOM-03 SK != K_prekey`() {
        val alice = newDevice()
        val bob = newDevice()
        val init = x3dh.initiate(alice.initiator(), bob.prekeys(), randomF())
        assertFalse(init.sharedKey.contentEquals(init.preKeyKey),
            "K_prekey debe ser una derivacion distinta de SK")
    }

    // ===================================================================
    // F
    // ===================================================================

    @Test
    @DisplayName("X3DH-F-01 F tiene 32 bytes y se rechaza si no")
    fun `X3DH-F-01 F de 32 bytes`() {
        val alice = newDevice()
        val bob = newDevice()
        for (bad in listOf(0, 16, 31, 33, 64)) {
            assertThrows<IllegalArgumentException>("F=$bad") {
                x3dh.initiate(alice.initiator(), bob.prekeys(), ByteArray(bad))
            }
        }
        val ok = x3dh.initiate(alice.initiator(), bob.prekeys(), ByteArray(32))
        assertEquals(32, ok.bootstrapValue.size)
    }

    @Test
    @DisplayName("X3DH-F-02 F nunca aparece en claro en el wire")
    fun `X3DH-F-02 F viaja cifrada`() {
        val alice = newDevice()
        val bob = newDevice()
        val f = randomF()
        val init = x3dh.initiate(alice.initiator(), bob.prekeys(), f)

        // El emisor construye el SecureFrame PREKEY con F cifrado, usando
        // K_prekey como messageKey (el protector deriva clave y nonce).
        val frame = protector.protect(
            FrameType.PREKEY,
            RatchetHeader(init.ephemeralPublic, 0u, 0u),
            init.preKeyKey,
            f,
        )
        val wire = BinarySecureFrameCodec.encode(frame)

        // F no debe aparecer en claro en el wire.
        assertFalse(indexOf(wire, f) >= 0, "F no debe viajar en claro")

        // Y el receptor la recupera con SU K_prekey, recalculada desde SK.
        val prep = x3dh.respond(bob.responder(), alice.prekeys(), init.ephemeralPublic)
        val recovered = protector.unprotect(frame, prep.preKeyKey)
        assertContentEquals(f, recovered, "el receptor debe recuperar F")
    }

    // ===================================================================
    // Transaccionalidad
    // ===================================================================

    @Test
    @DisplayName("X3DH-AT-01 respond no consume el OPK hasta commit")
    fun `X3DH-AT-01 OPK no se consume antes de commit`() {
        val alice = newDevice()
        val bob = newDevice()
        bob.opk = x25519.generateKeyPair()
        val init = x3dh.initiate(alice.initiator(), bob.prekeys(opkId = 7L), randomF())

        val prep = x3dh.respond(bob.responder(opkId = 7L), alice.prekeys(), init.ephemeralPublic)
        assertEquals(7L, prep.usedOneTimePreKeyId, "prepare conoce el OPK")
        // La sesion solo se confirma explicitamente.
        val session = prep.commit()
        assertEquals(7L, session.consumedOneTimePreKeyId, "commit marca el OPK consumido")
    }

    @Test
    @DisplayName("X3DH-AT-02 respond es determinista")
    fun `X3DH-AT-02 respond determinista`() {
        val alice = newDevice()
        val bob = newDevice()
        val init = x3dh.initiate(alice.initiator(), bob.prekeys(), randomF())
        val a = x3dh.respond(bob.responder(), alice.prekeys(), init.ephemeralPublic)
        val b = x3dh.respond(bob.responder(), alice.prekeys(), init.ephemeralPublic)
        assertContentEquals(a.sharedKey, b.sharedKey)
    }

    // ===================================================================
    // Validacion de entradas
    // ===================================================================

    @Test
    @DisplayName("X3DH-10 rechaza claves de acuerdo de tamano invalido")
    fun `X3DH-10 tamano de clave invalido`() {
        val alice = newDevice()
        val bob = newDevice()
        val bad = bob.prekeys().copy(identityAgreementKey = ByteArray(16))
        assertThrows<IllegalArgumentException> {
            x3dh.initiate(alice.initiator(), bad, randomF())
        }
    }

    @Test
    @DisplayName("X3DH-11 rechaza efimera de tamano invalido al responder")
    fun `X3DH-11 efimera invalida`() {
        val alice = newDevice()
        val bob = newDevice()
        assertThrows<IllegalArgumentException> {
            x3dh.respond(bob.responder(), alice.prekeys(), ByteArray(8))
        }
    }

    // ===================================================================
    // TESTS ARQUITECTONICOS (KM-0006 §12)
    // ===================================================================

    @Test
    @DisplayName("X3DH-IK-01 IK es siempre X25519 de 32 bytes")
    fun `X3DH-IK-01 IK es X25519`() {
        val alice = newDevice()
        assertEquals(32, alice.prekeys().identityAgreementKey.size)
        // Y una clave de tamaño distinto se rechaza.
        val bad = alice.prekeys().copy(signedPreKey = ByteArray(31))
        assertThrows<IllegalArgumentException> { x3dh.initiate(newDevice().initiator(), bad, randomF()) }
    }

    @Test
    @DisplayName("X3DH-IK-02/03 no existe conversion Ed25519 -> X25519 en la API")
    fun `X3DH-IK-03 sin conversion Ed25519`() {
        val pkg = X3dh::class.java.`package`.name
        val sourceDir = java.io.File("src/main/kotlin/" + pkg.replace('.', '/'))
        assertTrue(sourceDir.exists(), "debe existir el paquete $pkg en $sourceDir")
        // Se revisa el CODIGO, no la documentacion: la KDoc si explica por que
        // Ed25519 queda excluida, pero no debe haber ninguna referencia ejecutable.
        val offenders = sourceDir.walkTopDown().filter { it.extension == "kt" }
            .filter { f ->
                f.readLines()
                    .filterNot {
                        val t = it.trimStart()
                        t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
                    }
                    .any { it.contains("Ed25519") }
            }.map { it.name }.toList()
        assertTrue(offenders.isEmpty(), "el paquete X3DH no debe usar Ed25519 en codigo: $offenders")
    }

    @Test
    @DisplayName("X3DH-IK-04/05 la API de X3DH no expone claves de firma")
    fun `X3DH-IK-05 la API no expone signingKey`() {
        // Ninguna clase de X3DH debe tener un campo de clave de FIRMA.
        val classes = listOf(
            BootstrapPrekeys::class.java,
            InitiatorKeyMaterial::class.java,
            ResponderKeyMaterial::class.java,
        )
        for (c in classes) {
            val fields = c.declaredFields.map { it.name.lowercase() }
            assertFalse(
                fields.any { it.contains("signing") },
                "${c.simpleName} no debe exponer claves de firma: $fields",
            )
        }
    }

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    private fun indexOf(h: ByteArray, n: ByteArray): Int {
        if (n.isEmpty() || n.size > h.size) return -1
        outer@ for (i in 0..h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }
}
