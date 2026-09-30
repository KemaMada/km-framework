package com.km.negotiation

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests de negociacion KM-0007 (KM-0007.1 + KM-0007.2).
 *
 * 7.1 — Modelo de capacidades
 * 7.2 — Seleccion (interseccion, preferencia, determinismo)
 * 7.4 — Compression
 * 7.5 — Crypto negotiation
 */
class Km7NegotiationTest {

    // ===================================================================
    // 7.1 — Modelo de capacidades
    // ===================================================================

    @Test
    fun `CapabilitySet rechaza ids duplicados`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            CapabilitySet(listOf(
                Capability("a", 1),
                Capability("a", 2),
            ))
        }
    }

    @Test
    fun `CapabilitySet permite ids distintos`() {
        val cs = CapabilitySet(listOf(
            Capability("a", 1),
            Capability("b", 2),
        ))
        assertEquals(2, cs.capabilities.size)
    }

    @Test
    fun `CapabilitySet find y has funcionan`() {
        val cs = CapabilitySet(listOf(Capability("test", 1)))
        assertNotNull(cs.find("test"))
        assertNull(cs.find("unknown"))
        assertTrue(cs.has("test"))
    }

    @Test
    fun `Capability con parametros se construye`() {
        val cap = Capability("test", 1, mapOf(
            "key1" to KceValue.VString("val1"),
            "key2" to KceValue.VLong(42),
            "key3" to KceValue.VBool(true),
        ))
        assertEquals("test", cap.id)
        assertEquals(1, cap.version)
        assertEquals(3, cap.parameters.size)
    }

    // ===================================================================
    // 7.2 — Seleccion
    // ===================================================================

    @Test
    fun `interseccion completa produce capacidades comunes`() {
        val local = CapabilitySet(listOf(
            Capability(KnownCapabilities.SERIALIZATION, 1),
            Capability(KnownCapabilities.COMPRESSION, 1),
        ))
        val peer = CapabilitySet(listOf(
            Capability(KnownCapabilities.SERIALIZATION, 1),
            Capability(KnownCapabilities.COMPRESSION, 1),
        ))
        val result = Km7Negotiation.negotiate(local, peer)
        assertTrue(!result.isFailure)
        assertEquals(2, result.capabilities.size)
    }

    @Test
    fun `interseccion vacia para categoria obligatoria produce failure`() {
        val local = CapabilitySet(listOf(
            Capability(KnownCapabilities.SERIALIZATION, 1),
        ))
        val peer = CapabilitySet(emptyList()) // peer no soporta nada
        val result = Km7Negotiation.negotiate(local, peer, requiredCategories = setOf(KnownCapabilities.SERIALIZATION))
        assertTrue(result.isFailure)
        assertNotNull(result.failureReason)
    }

    @Test
    fun `interseccion vacia sin required no produce failure`() {
        val local = CapabilitySet(listOf(Capability("a", 1)))
        val peer = CapabilitySet(listOf(Capability("b", 1)))
        val result = Km7Negotiation.negotiate(local, peer)
        assertTrue(!result.isFailure)
        assertTrue(result.capabilities.isEmpty())
    }

    @Test
    fun `seleccion usa la version mas alta comun`() {
        val local = CapabilitySet(listOf(Capability("x", 3)))
        val peer = CapabilitySet(listOf(Capability("x", 2)))
        val result = Km7Negotiation.negotiate(local, peer)
        assertEquals(2, result.capabilities["x"]?.version) // min(3, 2) = 2
    }

    @Test
    fun `seleccion es determinista`() {
        val local = CapabilitySet(listOf(
            Capability("a", 1),
            Capability("b", 1),
        ))
        val peer = CapabilitySet(listOf(
            Capability("a", 1),
            Capability("b", 1),
        ))
        val r1 = Km7Negotiation.negotiate(local, peer)
        val r2 = Km7Negotiation.negotiate(local, peer)
        assertEquals(r1.capabilities.keys, r2.capabilities.keys)
    }

    @Test
    fun `capacidad desconocida del peer se ignora`() {
        val local = CapabilitySet(listOf(Capability("a", 1)))
        val peer = CapabilitySet(listOf(
            Capability("a", 1),
            Capability("unknown", 99), // peer ofrece algo que local no soporta
        ))
        val result = Km7Negotiation.negotiate(local, peer)
        assertEquals(setOf("a"), result.capabilities.keys)
    }

    @Test
    fun `capacidad desconocida del local no afecta`() {
        val local = CapabilitySet(listOf(
            Capability("a", 1),
            Capability("unknown-for-local", 99),
        ))
        val peer = CapabilitySet(listOf(Capability("a", 1)))
        val result = Km7Negotiation.negotiate(local, peer)
        assertEquals(setOf("a"), result.capabilities.keys)
    }

    @Test
    fun `parametros del local tienen prioridad sobre peer`() {
        val local = CapabilitySet(listOf(
            Capability("x", 1, mapOf("p1" to KceValue.VString("local"))),
        ))
        val peer = CapabilitySet(listOf(
            Capability("x", 1, mapOf("p1" to KceValue.VString("peer"))),
        ))
        val result = Km7Negotiation.negotiate(local, peer)
        assertEquals("local", (result.capabilities["x"]?.parameters?.get("p1") as KceValue.VString?)?.value)
    }

    @Test
    fun `parametros del peer complementan si local no los tiene`() {
        val local = CapabilitySet(listOf(Capability("x", 1)))
        val peer = CapabilitySet(listOf(
            Capability("x", 1, mapOf("peerParam" to KceValue.VLong(42))),
        ))
        val result = Km7Negotiation.negotiate(local, peer)
        assertEquals(42, (result.capabilities["x"]?.parameters?.get("peerParam") as KceValue.VLong?)?.value)
    }

    // ===================================================================
    // selectFirstCommon
    // ===================================================================

    @Test
    fun `selectFirstCommon devuelve primer match en orden local`() {
        val result = Km7Negotiation.selectFirstCommon(
            listOf("zstd", "gzip", "none"),
            setOf("none", "gzip"),
        )
        assertEquals("gzip", result) // gzip aparece primero en peer, pero orden local primero
    }

    @Test
    fun `selectFirstCommon devuelve null si no hay comun`() {
        val result = Km7Negotiation.selectFirstCommon(
            listOf("zstd", "gzip"),
            setOf("none"),
        )
        assertNull(result)
    }

    @Test
    fun `selectFirstCommon con unico match`() {
        val result = Km7Negotiation.selectFirstCommon(
            listOf("a", "b", "c"),
            setOf("b"),
        )
        assertEquals("b", result)
    }

    // ===================================================================
    // intersection
    // ===================================================================

    @Test
    fun `intersection preserva orden local`() {
        val result = Km7Negotiation.intersection(
            listOf("zstd", "gzip", "none"),
            setOf("none", "zstd"),
        )
        assertEquals(listOf("zstd", "none"), result)
    }

    @Test
    fun `intersection vacia`() {
        val result = Km7Negotiation.intersection(
            listOf("a", "b"),
            setOf("c"),
        )
        assertTrue(result.isEmpty())
    }

    // ===================================================================
    // 7.4 — Compression
    // ===================================================================

    @Test
    fun `compresion none es el valor por defecto`() {
        val selected = Km7Negotiation.selectFirstCommon(
            listOf("none", "zstd"),
            setOf("none"),
        )
        assertEquals("none", selected)
    }

    @Test
    fun `compresion no soportada por peer se degrada a none`() {
        val selected = Km7Negotiation.selectFirstCommon(
            listOf("zstd", "none"),
            setOf("none"), // peer solo soporta none
        )
        assertEquals("none", selected)
    }

    @Test
    fun `compresion mutuamente soportada se selecciona`() {
        val selected = Km7Negotiation.selectFirstCommon(
            listOf("zstd", "none"),
            setOf("zstd", "none"),
        )
        assertEquals("zstd", selected)
    }

    // ===================================================================
    // 7.5 — Crypto negotiation
    // ===================================================================

    @Test
    fun `crypto suite completa negociable`() {
        val local = CapabilitySet(listOf(
            Capability(KnownCapabilities.IDENTITY, 1, mapOf("algorithm" to KceValue.VString(KnownCapabilities.ID_ED25519))),
            Capability(KnownCapabilities.SIGNATURE, 1, mapOf("algorithm" to KceValue.VString(KnownCapabilities.SIG_ED25519))),
            Capability(KnownCapabilities.KEY_AGREEMENT, 1, mapOf("algorithm" to KceValue.VString(KnownCapabilities.KA_X25519))),
            Capability(KnownCapabilities.ENCRYPTION, 1, mapOf("algorithm" to KceValue.VString(KnownCapabilities.ENC_XCHACHA20_POLY1305))),
            Capability(KnownCapabilities.HASH, 1, mapOf("algorithm" to KceValue.VString(KnownCapabilities.HASH_SHA256))),
            Capability(KnownCapabilities.KDF, 1, mapOf("algorithm" to KceValue.VString(KnownCapabilities.KDF_HKDF_SHA256))),
        ))
        val peer = CapabilitySet(listOf(
            Capability(KnownCapabilities.IDENTITY, 1, mapOf("algorithm" to KceValue.VString(KnownCapabilities.ID_ED25519))),
            Capability(KnownCapabilities.SIGNATURE, 1, mapOf("algorithm" to KceValue.VString(KnownCapabilities.SIG_ED25519))),
            Capability(KnownCapabilities.KEY_AGREEMENT, 1, mapOf("algorithm" to KceValue.VString(KnownCapabilities.KA_X25519))),
            Capability(KnownCapabilities.ENCRYPTION, 1, mapOf("algorithm" to KceValue.VString(KnownCapabilities.ENC_XCHACHA20_POLY1305))),
            Capability(KnownCapabilities.HASH, 1, mapOf("algorithm" to KceValue.VString(KnownCapabilities.HASH_SHA256))),
            Capability(KnownCapabilities.KDF, 1, mapOf("algorithm" to KceValue.VString(KnownCapabilities.KDF_HKDF_SHA256))),
        ))
        val result = Km7Negotiation.negotiate(local, peer)
        assertEquals(6, result.capabilities.size)
        assertTrue(!result.isFailure)
    }

    @Test
    fun `identity algorithm y signature algorithm son independientes`() {
        val local = CapabilitySet(listOf(
            Capability(KnownCapabilities.IDENTITY, 1),
            Capability(KnownCapabilities.SIGNATURE, 1),
        ))
        // Peer solo soporta signature, no identity
        val peer = CapabilitySet(listOf(
            Capability(KnownCapabilities.SIGNATURE, 1),
        ))
        val result = Km7Negotiation.negotiate(local, peer)
        assertEquals(setOf(KnownCapabilities.SIGNATURE), result.capabilities.keys)
    }

    @Test
    fun `identity algorithm distinto de signature algorithm`() {
        // Verificar que los IDs de capacidad son DIFERENTES
        assertTrue(KnownCapabilities.IDENTITY != KnownCapabilities.SIGNATURE)
        assertTrue(KnownCapabilities.IDENTITY != KnownCapabilities.KEY_AGREEMENT)
        assertTrue(KnownCapabilities.SIGNATURE != KnownCapabilities.KEY_AGREEMENT)
        assertTrue(KnownCapabilities.ENCRYPTION != KnownCapabilities.HASH)
        assertTrue(KnownCapabilities.HASH != KnownCapabilities.KDF)
    }

    // ===================================================================
    // Negotiation hash (binding)
    // ===================================================================

    @Test
    fun `negotiationHash es determinista`() {
        val local = CapabilitySet(listOf(Capability("a", 1), Capability("b", 2)))
        val peer = CapabilitySet(listOf(Capability("a", 1), Capability("b", 2)))
        val result = Km7Negotiation.negotiate(local, peer)
        val h1 = Km7Negotiation.negotiationHash(result)
        val h2 = Km7Negotiation.negotiationHash(result)
        assertContentEquals(h1, h2)
    }

    @Test
    fun `negotiationHash cambia si cambian las capacidades negociadas`() {
        val local = CapabilitySet(listOf(Capability("a", 1)))
        val peerA = CapabilitySet(listOf(Capability("a", 1)))
        val peerB = CapabilitySet(listOf(Capability("b", 1))) // capacidad diferente
        val rA = Km7Negotiation.negotiate(local, peerA)
        val rB = Km7Negotiation.negotiate(local, peerB)
        val hA = Km7Negotiation.negotiationHash(rA)
        val hB = Km7Negotiation.negotiationHash(rB)
        assertTrue(!hA.contentEquals(hB), "hashes deben diferir con capacidades distintas")
    }
}

private fun assertFailsWith(block: () -> Unit) {
    try {
        block()
        throw AssertionError("se esperaba una excepcion")
    } catch (_: Exception) {
        // OK
    }
}