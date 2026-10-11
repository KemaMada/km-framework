package com.km.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertInstanceOf
import kotlin.test.assertTrue

/**
 * Fuente unica de verdad del dialecto (R34) y verificacion del dialecto en la
 * direccion de respuesta (R4, R31, R32, R33 / S19, S21, S22, S23, S24).
 *
 * Cubre los cuatro rechazos que la task 1.1 exige: ausente, desconocido,
 * inconsistente intra-handshake y no implementado, con el codigo exacto del
 * catalogo.
 */
class DialectTest {

    // ===================================================================
    // Dialect: fuente unica de verdad
    // ===================================================================

    @Test
    fun `el dialecto es un string con los valores canonicos`() {
        assertEquals("2.0", Dialect.V2_0.wire)
        assertEquals("2.1", Dialect.V2_1.wire)
    }

    @Test
    fun `la politica de transicion es el conjunto aprobado por el owner`() {
        assertEquals(setOf(Dialect.V2_0, Dialect.V2_1), Dialect.TRANSITIONAL)
    }

    @Test
    fun `fromWireOrNull reconoce los dos dialectos y rechaza el resto`() {
        assertEquals(Dialect.V2_0, Dialect.fromWireOrNull("2.0"))
        assertEquals(Dialect.V2_1, Dialect.fromWireOrNull("2.1"))
        assertNull(Dialect.fromWireOrNull(null))
        assertNull(Dialect.fromWireOrNull("2.2"))
        assertNull(Dialect.fromWireOrNull("9.9"))
        assertNull(Dialect.fromWireOrNull("2"))
        assertNull(Dialect.fromWireOrNull(""))
    }

    // ===================================================================
    // checkChallenge — frame AUTH_CHALLENGE
    // ===================================================================

    @Test
    fun `challenge con el dialecto implementado se acepta`() {
        val accepted = assertInstanceOf(DialectCheck.Accepted::class.java, DialectNegotiation.checkChallenge(Dialect.V2_0, "2.0"))
        assertEquals(Dialect.V2_0, accepted.dialect)
    }

    @Test
    fun `challenge sin protocolVersion es MALFORMED_MESSAGE`() {
        val rejected = assertInstanceOf(DialectCheck.Rejected::class.java, DialectNegotiation.checkChallenge(Dialect.V2_0, null))
        assertEquals(DialectError.MALFORMED_MESSAGE, rejected.error)
        assertEquals("MALFORMED_MESSAGE", rejected.error.wireCode)
    }

    @Test
    fun `challenge con dialecto desconocido es UNSUPPORTED_PROTOCOL_VERSION`() {
        for (bogus in listOf("9.9", "2.2", "3.0", "twenty", "2")) {
            val rejected = assertInstanceOf(
                DialectCheck.Rejected::class.java,
                DialectNegotiation.checkChallenge(Dialect.V2_0, bogus),
                "el dialecto '$bogus' debe rechazarse",
            )
            assertEquals(DialectError.UNSUPPORTED_PROTOCOL_VERSION, rejected.error)
        }
    }

    @Test
    fun `un cliente 2_0 rechaza un challenge 2_1 sin firmar`() {
        // S24/P6-R: cliente que implementa solo 2.0 ante una confirmacion 2.1.
        val rejected = assertInstanceOf(DialectCheck.Rejected::class.java, DialectNegotiation.checkChallenge(Dialect.V2_0, "2.1"))
        assertEquals(DialectError.UNSUPPORTED_PROTOCOL_VERSION, rejected.error)
    }

    // ===================================================================
    // checkAuthOk — frame AUTH_OK
    // ===================================================================

    @Test
    fun `authOk consistente con el challenge y el dialecto implementado se acepta`() {
        val accepted = assertInstanceOf(
            DialectCheck.Accepted::class.java,
            DialectNegotiation.checkAuthOk(Dialect.V2_0, Dialect.V2_0, "2.0"),
        )
        assertEquals(Dialect.V2_0, accepted.dialect)
    }

    @Test
    fun `authOk sin protocolVersion es MALFORMED_MESSAGE`() {
        val rejected = assertInstanceOf(
            DialectCheck.Rejected::class.java,
            DialectNegotiation.checkAuthOk(Dialect.V2_0, Dialect.V2_0, null),
        )
        assertEquals(DialectError.MALFORMED_MESSAGE, rejected.error)
    }

    @Test
    fun `authOk con dialecto desconocido es UNSUPPORTED_PROTOCOL_VERSION`() {
        val rejected = assertInstanceOf(
            DialectCheck.Rejected::class.java,
            DialectNegotiation.checkAuthOk(Dialect.V2_0, Dialect.V2_0, "9.9"),
        )
        assertEquals(DialectError.UNSUPPORTED_PROTOCOL_VERSION, rejected.error)
    }

    @Test
    fun `inconsistencia intra-handshake challenge 2_0 vs authOk 2_1 se rechaza`() {
        // S21/P6-O: NO es un upgrade de negociacion; es un handshake que mezcla
        // dialectos y se rechaza.
        val rejected = assertInstanceOf(
            DialectCheck.Rejected::class.java,
            DialectNegotiation.checkAuthOk(Dialect.V2_0, Dialect.V2_0, "2.1"),
        )
        assertEquals(DialectError.UNSUPPORTED_PROTOCOL_VERSION, rejected.error)
        assertTrue(rejected.detail.contains("inconsistencia"), rejected.detail)
    }

    @Test
    fun `authOk que confirma un dialecto no implementado se rechaza`() {
        // El challenge ya venia en 2.1 (inconsistente con el 2.0 declarado);
        // aunque aqui se pasara, el AUTH_OK tampoco puede confirmarlo.
        val rejected = assertInstanceOf(
            DialectCheck.Rejected::class.java,
            DialectNegotiation.checkAuthOk(Dialect.V2_0, Dialect.V2_1, "2.1"),
        )
        assertEquals(DialectError.UNSUPPORTED_PROTOCOL_VERSION, rejected.error)
    }

    @Test
    fun `los codigos wire coinciden con el catalogo de errores`() {
        assertEquals("MALFORMED_MESSAGE", DialectError.MALFORMED_MESSAGE.wireCode)
        assertEquals("UNSUPPORTED_PROTOCOL_VERSION", DialectError.UNSUPPORTED_PROTOCOL_VERSION.wireCode)
    }
}
