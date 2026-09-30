package com.keymessage.core.km7

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests de mutacion KM-0007 — M-7-01 a M-7-10.
 */
class Km7MutationTest {

    // ===================================================================
    // M-7-01: seleccionar algoritmo no ofrecido
    // ===================================================================

    @Test
    fun `M-7-01 no se puede seleccionar algoritmo no ofrecido por peer`() {
        val local = CapabilitySet(listOf(Capability("crypto", 1)))
        val peer = CapabilitySet(listOf(Capability("other", 1))) // peer no ofrece "crypto"
        val result = Km7Negotiation.negotiate(local, peer)
        assertNull(result.capabilities["crypto"], "no debe negociar lo que peer no ofrece")
    }

    // ===================================================================
    // M-7-02: seleccionar capacidad no soportada
    // ===================================================================

    @Test
    fun `M-7-02 no se negocia capacidad que local no soporta`() {
        val local = CapabilitySet(listOf(Capability("a", 1)))
        val peer = CapabilitySet(listOf(Capability("a", 1), Capability("b", 1)))
        val result = Km7Negotiation.negotiate(local, peer)
        assertEquals(setOf("a"), result.capabilities.keys, "b no debe negociarse")
    }

    // ===================================================================
    // M-7-03: alterar prioridad
    // ===================================================================

    @Test
    fun `M-7-03 orden local determina seleccion en interseccion multiple`() {
        // Ambas partes soportan zstd y gzip, local prefiere zstd
        val local = listOf("zstd", "gzip")
        val peer = setOf("gzip", "zstd")
        val first = Km7Negotiation.selectFirstCommon(local, peer)
        assertEquals("zstd", first, "orden local debe prevalecer")
    }

    // ===================================================================
    // M-7-04: eliminar capacidad obligatoria
    // ===================================================================

    @Test
    fun `M-7-04 capacidad obligatoria ausente produce failure`() {
        val local = CapabilitySet(listOf(Capability("required", 1)))
        val peer = CapabilitySet(emptyList())
        val result = Km7Negotiation.negotiate(local, peer, requiredCategories = setOf("required"))
        assertTrue(result.isFailure)
    }

    // ===================================================================
    // M-7-05: introducir capacidad desconocida
    // ===================================================================

    @Test
    fun `M-7-05 capacidad desconocida se ignora sin error`() {
        val local = CapabilitySet(listOf(
            Capability("known", 1),
            Capability("unknown-future", 99, mapOf("x" to KceValue.VLong(1))),
        ))
        val peer = CapabilitySet(listOf(Capability("known", 1)))
        val result = Km7Negotiation.negotiate(local, peer)
        assertEquals(setOf("known"), result.capabilities.keys)
    }

    // ===================================================================
    // M-7-06: downgrade de version
    // ===================================================================

    @Test
    fun `M-7-06 version seleccionada es la minima comun, no la maxima`() {
        val local = CapabilitySet(listOf(Capability("x", 5)))
        val peer = CapabilitySet(listOf(Capability("x", 3)))
        val result = Km7Negotiation.negotiate(local, peer)
        assertEquals(3, result.capabilities["x"]?.version,
            "downgrade a 3, no 5 (min(5,3) = 3)")
    }

    @Test
    fun `M-7-06b downgrade bloqueado por negotiation hash`() {
        // Version 5 -> negociado con peer version 3
        val local5 = CapabilitySet(listOf(Capability("x", 5)))
        val peer3 = CapabilitySet(listOf(Capability("x", 3)))
        val r53 = Km7Negotiation.negotiate(local5, peer3)
        val h53 = Km7Negotiation.negotiationHash(r53)

        // Version 3 -> negociado con peer version 5 (downgrade simulado)
        val local3 = CapabilitySet(listOf(Capability("x", 3)))
        val peer5 = CapabilitySet(listOf(Capability("x", 5)))
        val r35 = Km7Negotiation.negotiate(local3, peer5)
        val h35 = Km7Negotiation.negotiationHash(r35)

        // Ambos negocian x:3, hashes deben ser iguales
        assertTrue(h53.contentEquals(h35))

        // Si un atacante fuerza x:2, el hash cambia
        val peer2 = CapabilitySet(listOf(Capability("x", 2)))
        val r52 = Km7Negotiation.negotiate(local5, peer2)
        val h52 = Km7Negotiation.negotiationHash(r52)
        assertTrue(!h53.contentEquals(h52), "hash debe cambiar si cambia la version")
    }

    // ===================================================================
    // M-7-07: compresion negociada ≠ compresion usada
    // ===================================================================

    @Test
    fun `M-7-07 negociacion explicita de compression`() {
        val selected = Km7Negotiation.selectFirstCommon(
            listOf("zstd", "gzip", "none"),
            setOf("zstd"),
        )
        assertEquals("zstd", selected)
        // Si se selecciona zstd, es porque ambas partes lo soportan
        // No se puede activar compresion sin negociacion explicita
    }

    @Test
    fun `M-7-07b compression no se activa implicitamente`() {
        // peer no ofrece compression, local si
        val selected = Km7Negotiation.selectFirstCommon(
            listOf("zstd", "none"),
            setOf("none"), // peer solo none
        )
        assertEquals("none", selected, "no debe activarse zstd sin soporte mutuo")
    }

    // ===================================================================
    // M-7-08: crypto suite negociada ≠ crypto suite aplicada
    // ===================================================================

    @Test
    fun `M-7-08 crypto suite negociada es explicita`() {
        val local = CapabilitySet(listOf(
            Capability(KnownCapabilities.ENCRYPTION, 1,
                mapOf("algorithm" to KceValue.VString(KnownCapabilities.ENC_XCHACHA20_POLY1305))),
        ))
        val peer = CapabilitySet(listOf(
            Capability(KnownCapabilities.ENCRYPTION, 1,
                mapOf("algorithm" to KceValue.VString(KnownCapabilities.ENC_XCHACHA20_POLY1305))),
        ))
        val result = Km7Negotiation.negotiate(local, peer)
        val enc = result.capabilities[KnownCapabilities.ENCRYPTION]
        assertNotNull(enc)
        assertEquals(KnownCapabilities.ENC_XCHACHA20_POLY1305,
            (enc.parameters["algorithm"] as KceValue.VString).value)
    }

    // ===================================================================
    // M-7-09: negociacion asimetrica A/B
    // ===================================================================

    @Test
    fun `M-7-09 negociacion asimetrica A vs B es consistente`() {
        // Alice tiene {a, b}, Bob tiene {b, c}
        val aliceLocal = CapabilitySet(listOf(Capability("a", 1), Capability("b", 2)))
        val bobLocal = CapabilitySet(listOf(Capability("b", 2), Capability("c", 3)))

        val aliceResult = Km7Negotiation.negotiate(aliceLocal, bobLocal)
        val bobResult = Km7Negotiation.negotiate(bobLocal, aliceLocal)

        // Alice negocia desde su perspectiva: interseccion con Bob es {b}
        assertEquals(setOf("b"), aliceResult.capabilities.keys)

        // Bob negocia desde su perspectiva: interseccion con Alice es {b}
        assertEquals(setOf("b"), bobResult.capabilities.keys)

        // Ambos ven la misma version negociada para "b"
        assertEquals(2, aliceResult.capabilities["b"]?.version)
        assertEquals(2, bobResult.capabilities["b"]?.version)
    }

    // ===================================================================
    // M-7-10: negociacion alterada despues de autenticacion
    // ===================================================================

    @Test
    fun `M-7-10 negotiation hash protege contra manipulacion post-auth`() {
        // Negociacion original
        val local = CapabilitySet(listOf(Capability("serialization", 1)))
        val peer = CapabilitySet(listOf(Capability("serialization", 1)))
        val result = Km7Negotiation.negotiate(local, peer)
        val originalHash = Km7Negotiation.negotiationHash(result)

        // Atacante fuerza downgrade de serialization
        val tamperedLocal = CapabilitySet(listOf(Capability("serialization", 0))) // version invalida
        // Pero Capability no permite version 0 directamente, la negociacion no lo permitiria
        // Si el atacante manipula el peer reportando version 0:
        val tamperedPeer = CapabilitySet(listOf(Capability("serialization", 0)))
        val tamperedResult = Km7Negotiation.negotiate(local, tamperedPeer)
        // Version 0 no es valida en el modelo, pero la negociacion usaria min(1, 0) = 0
        // El negotiationHash capturaria esta diferencia
        val tamperedHash = Km7Negotiation.negotiationHash(tamperedResult)
        assertTrue(!originalHash.contentEquals(tamperedHash),
            "hash debe cambiar si la negociacion fue alterada")
    }

    // ===================================================================
    // Mutaciones adicionales
    // ===================================================================

    @Test
    fun `M-7-11 negociacion identica produce hash identico`() {
        val a = CapabilitySet(listOf(Capability("x", 1)))
        val b = CapabilitySet(listOf(Capability("x", 1)))
        val r1 = Km7Negotiation.negotiate(a, b)
        val r2 = Km7Negotiation.negotiate(a, b)
        assertTrue(Km7Negotiation.negotiationHash(r1).contentEquals(
            Km7Negotiation.negotiationHash(r2)))
    }

    @Test
    fun `M-7-12 capacidad con parametros opcionales`() {
        val cs = CapabilitySet(listOf(
            Capability("flexible", 1, mapOf(
                "optional" to KceValue.VString("present"),
            )),
        ))
        val result = Km7Negotiation.negotiate(cs, cs)
        assertEquals("present",
            (result.capabilities["flexible"]?.parameters?.get("optional") as KceValue.VString?)?.value)
    }

    @Test
    fun `M-7-13 required vacio no produce failure con interseccion vacia`() {
        val local = CapabilitySet(listOf(Capability("a", 1)))
        val peer = CapabilitySet(listOf(Capability("b", 1)))
        val result = Km7Negotiation.negotiate(local, peer, requiredCategories = emptySet())
        assertTrue(!result.isFailure)
        assertTrue(result.capabilities.isEmpty())
    }

    @Test
    fun `M-7-14 multiple required categories deben estar todas presentes`() {
        val local = CapabilitySet(listOf(Capability("a", 1), Capability("b", 1)))
        val peer = CapabilitySet(listOf(Capability("a", 1))) // peer no tiene "b"
        val result = Km7Negotiation.negotiate(local, peer, requiredCategories = setOf("a", "b"))
        assertTrue(result.isFailure)
    }
}