package com.km.identity

import com.km.crypto.Ed25519Impl
import com.km.crypto.Signature
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Ed25519 contrastado con RFC 8032 seccion 7.1.
 *
 * La compatibilidad de net.i2p.crypto:eddsa 0.3.0 con RFC 8032 se verifico
 * antes de escribir estos tests (derivacion de clave publica desde semilla y
 * firma). Estos tests la mantienen verificada, y son la evidencia de que la
 * capa de derivacion de IDs descansa sobre una primitiva correcta y no sobre
 * una suposicion.
 */
class Ed25519Rfc8032Test {

    private val ed = Ed25519Impl()
    private fun hex(s: String) = KmIdFixtures.unhex(s)

    @Test
    fun `clave publica desde semilla, vector 1`() {
        val kp = ed.keyPairFromSeed(
            hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        )
        assertEquals(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            KmIdFixtures.hex(kp.publicKey)
        )
        // La clave privada devuelta ES la semilla, no el escalar expandido.
        assertContentEquals(
            hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"),
            kp.privateKey
        )
    }

    @Test
    fun `clave publica desde semilla, vector 2`() {
        val kp = ed.keyPairFromSeed(
            hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        )
        assertEquals(
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
            KmIdFixtures.hex(kp.publicKey)
        )
    }

    @Test
    fun `firma sobre mensaje vacio, vector 1`() {
        val kp = ed.keyPairFromSeed(
            hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        )
        val sig = ed.sign(kp.privateKey, ByteArray(0))
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555" +
                "fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            KmIdFixtures.hex(sig.bytes)
        )
    }

    @Test
    fun `firma sobre el mensaje del vector 2`() {
        val kp = ed.keyPairFromSeed(
            hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        )
        val msg = hex("72")
        val sig = ed.sign(kp.privateKey, msg)
        assertEquals(
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
            KmIdFixtures.hex(sig.bytes)
        )
        assertTrue(ed.verify(kp.publicKey, msg, sig))
    }

    @Test
    fun `la verificacion rechaza una firma alterada`() {
        val kp = ed.keyPairFromSeed(ByteArray(32) { it.toByte() })
        val msg = "KM-ID".toByteArray()
        val sig = ed.sign(kp.privateKey, msg)
        assertTrue(ed.verify(kp.publicKey, msg, sig))

        val roto = sig.bytes.copyOf()
        roto[0] = (roto[0].toInt() xor 0xFF).toByte()
        assertTrue(
            !ed.verify(kp.publicKey, msg, Signature(roto)),
            "una firma alterada debe rechazarse"
        )
    }

    @Test
    fun `la firma es determinista`() {
        val kp = ed.keyPairFromSeed(ByteArray(32) { 7 })
        val a = ed.sign(kp.privateKey, "mismo mensaje".toByteArray())
        val b = ed.sign(kp.privateKey, "mismo mensaje".toByteArray())
        assertContentEquals(a.bytes, b.bytes)
    }
}

/**
 * Derivacion de identificadores contra los vectores congelados.
 *
 * Aqui se cierra la cadena propuesta: las MISMAS semillas del MANIFEST
 * producen los MISMOS identificadores en Python y en Kotlin.
 */
class KmIdsGoldenVectorsTest {

    private val ed = Ed25519Impl()

    private fun seedOf(label: String): ByteArray {
        val node = KmIdFixtures.manifest()["seeds"][label]
            ?: error("semilla desconocida en el MANIFEST: $label")
        return KmIdFixtures.unhex(node.asText())
    }

    private fun derived(gid: String) = KmIdFixtures.text("vectors/$gid/derived.txt").trim()

    @Test
    fun `G01 identityId`() {
        val pub = ed.keyPairFromSeed(seedOf("root.alice.v1")).publicKey
        assertEquals(derived("G01"), KmIdFixtures.hex(KmIds.identityId(pub)))
    }

    @Test
    fun `G02 deviceId`() {
        val pub = ed.keyPairFromSeed(seedOf("alice.mobile.sign")).publicKey
        assertEquals(derived("G02"), KmIdFixtures.hex(KmIds.deviceId(pub)))
    }

    @Test
    fun `G03 nodeId`() {
        val pub = ed.keyPairFromSeed(seedOf("node.identity")).publicKey
        assertEquals(derived("G03"), KmIdFixtures.hex(KmIds.nodeId(pub)))
    }

    @Test
    fun `los tres identificadores son distintos entre si`() {
        val i = KmIds.identityId(ed.keyPairFromSeed(seedOf("root.alice.v1")).publicKey)
        val d = KmIds.deviceId(ed.keyPairFromSeed(seedOf("alice.mobile.sign")).publicKey)
        val n = KmIds.nodeId(ed.keyPairFromSeed(seedOf("node.identity")).publicKey)

        assertNotEquals(KmIdFixtures.hex(i), KmIdFixtures.hex(d), "identityId y deviceId colisionan")
        assertNotEquals(KmIdFixtures.hex(i), KmIdFixtures.hex(n), "identityId y nodeId colisionan")
        assertNotEquals(KmIdFixtures.hex(d), KmIdFixtures.hex(n), "deviceId y nodeId colisionan")
    }

    @Test
    fun `cada dispositivo tiene su propio deviceId`() {
        val movil = KmIds.deviceId(ed.keyPairFromSeed(seedOf("alice.mobile.sign")).publicKey)
        val pc = KmIds.deviceId(ed.keyPairFromSeed(seedOf("alice.pc.sign")).publicKey)
        val bob = KmIds.deviceId(ed.keyPairFromSeed(seedOf("bob.mobile.sign")).publicKey)
        assertNotEquals(KmIdFixtures.hex(movil), KmIdFixtures.hex(pc))
        assertNotEquals(KmIdFixtures.hex(movil), KmIdFixtures.hex(bob))
    }

    @Test
    fun `la rotacion de raiz produce un identityId nuevo`() {
        val v1 = KmIds.identityId(ed.keyPairFromSeed(seedOf("root.alice.v1")).publicKey)
        val v2 = KmIds.identityId(ed.keyPairFromSeed(seedOf("root.alice.v2")).publicKey)
        assertNotEquals(KmIdFixtures.hex(v1), KmIdFixtures.hex(v2))
    }

    @Test
    fun `la derivacion rechaza claves de tamano incorrecto`() {
        // Rejectar, no truncar: truncar haria que dos claves distintas con
        // prefijos compartidos produjeran el mismo identificador.
        assertFailsWith<IllegalArgumentException> { KmIds.deviceId(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { KmIds.deviceId(ByteArray(33)) }
        assertFailsWith<IllegalArgumentException> { KmIds.identityId(ByteArray(0)) }
    }

    @Test
    fun `deviceId depende solo de la clave de firma`() {
        // La clave de acuerdo X25519 es material opaco en KM-ID-0001. La
        // invariante es ESTRUCTURAL: KmIds.deviceId no acepta ningun segundo
        // argumento, de modo que no cabe la duda.
        val signing = ed.keyPairFromSeed(seedOf("alice.mobile.sign")).publicKey
        val agreement = seedOf("alice.mobile.agr")
        assertEquals(32, agreement.size, "la clave de acuerdo debe ser material opaco de 32 bytes")
        assertEquals(derived("G02"), KmIdFixtures.hex(KmIds.deviceId(signing)))
    }

    @Test
    fun `los identificadores tienen 32 bytes`() {
        val pub = ed.keyPairFromSeed(seedOf("root.alice.v1")).publicKey
        assertEquals(32, KmIds.identityId(pub).size)
        assertEquals(32, KmIds.deviceId(pub).size)
        assertEquals(32, KmIds.nodeId(pub).size)
    }
}
