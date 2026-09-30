package com.keymessage.core.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals

/**
 * Tests de TranscriptBuilder (3F.2).
 *
 * Verifica:
 * - Longitud exacta (152 + 120 bytes)
 * - Offsets de cada campo
 * - Mutaciones: orden incorrecto, campos intercambiados
 * - Timestamp uint64 big-endian
 */
class TranscriptsTest {

    // Vectores deterministicos (mismos valores toda la vida)
    private val nonce = ByteArray(16) { it.toByte() }
    private val ts: Long = 1_721_827_200_000L
    private val responderId = "f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0a1b2c3d4e5f6a7b8c9d0e1f2"
    private val initiatorId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2"
    private val sessionId = "b0a9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1"
    private val serverNonce = ByteArray(16) { (it + 16).toByte() }

    @Test
    fun `auth transcript mide 152 bytes exactos`() {
        val t = TranscriptBuilder.authTranscript(nonce, ts, responderId, initiatorId)
        assertEquals(152, t.size)
    }

    @Test
    fun `server auth transcript mide 120 bytes exactos`() {
        val t = TranscriptBuilder.serverAuthTranscript(sessionId, serverNonce, initiatorId)
        assertEquals(120, t.size)
    }

    @Test
    fun `auth transcript offsets nonce 0-15`() {
        val t = TranscriptBuilder.authTranscript(nonce, ts, responderId, initiatorId)
        val extracted = t.copyOfRange(0, 16)
        assertContentEquals(nonce, extracted)
    }

    @Test
    fun `auth transcript offsets timestamp 16-23 uint64 big-endian`() {
        val t = TranscriptBuilder.authTranscript(nonce, ts, responderId, initiatorId)
        val extracted = t.copyOfRange(16, 24)
        // ts = 1_721_827_200_000 = 0x190_0B5_FD00 (40 bits caben en long)
        val expected = ByteArray(8)
        for (i in 0 until 8) {
            expected[i] = (ts ushr (8 * (7 - i))).toByte()
        }
        assertContentEquals(expected, extracted)
    }

    @Test
    fun `auth transcript offsets responderIdentityId 24-87`() {
        val t = TranscriptBuilder.authTranscript(nonce, ts, responderId, initiatorId)
        val extracted = t.copyOfRange(24, 88)
        assertContentEquals(responderId.encodeToByteArray(), extracted)
    }

    @Test
    fun `auth transcript offsets identityId 88-151`() {
        val t = TranscriptBuilder.authTranscript(nonce, ts, responderId, initiatorId)
        val extracted = t.copyOfRange(88, 152)
        assertContentEquals(initiatorId.encodeToByteArray(), extracted)
    }

    @Test
    fun `server auth transcript offsets sessionId 0-39`() {
        val t = TranscriptBuilder.serverAuthTranscript(sessionId, serverNonce, initiatorId)
        val extracted = t.copyOfRange(0, 40)
        assertContentEquals(sessionId.encodeToByteArray(), extracted)
    }

    @Test
    fun `server auth transcript offsets serverNonce 40-55`() {
        val t = TranscriptBuilder.serverAuthTranscript(sessionId, serverNonce, initiatorId)
        val extracted = t.copyOfRange(40, 56)
        assertContentEquals(serverNonce, extracted)
    }

    @Test
    fun `server auth transcript offsets initiatorIdentityId 56-119`() {
        val t = TranscriptBuilder.serverAuthTranscript(sessionId, serverNonce, initiatorId)
        val extracted = t.copyOfRange(56, 120)
        assertContentEquals(initiatorId.encodeToByteArray(), extracted)
    }

    // ===================================================================
    // Determinismo: mismo input → mismo output
    // ===================================================================

    @Test
    fun `auth transcript es determinista`() {
        val a = TranscriptBuilder.authTranscript(nonce, ts, responderId, initiatorId)
        val b = TranscriptBuilder.authTranscript(nonce, ts, responderId, initiatorId)
        assertContentEquals(a, b)
    }

    @Test
    fun `server auth transcript es determinista`() {
        val a = TranscriptBuilder.serverAuthTranscript(sessionId, serverNonce, initiatorId)
        val b = TranscriptBuilder.serverAuthTranscript(sessionId, serverNonce, initiatorId)
        assertContentEquals(a, b)
    }

    // ===================================================================
    // Mutation: cambio de orden debe romper los vectores
    // ===================================================================

    @Test
    fun `intercambiar responderId e initiatorId en auth transcript rompe el resultado`() {
        val correcto = TranscriptBuilder.authTranscript(nonce, ts, responderId, initiatorId)
        val mutado = TranscriptBuilder.authTranscript(nonce, ts, initiatorId, responderId) // orden cambiado
        assertFalse(correcto.contentEquals(mutado))
    }

    @Test
    fun `intercambiar sessionId y serverNonce rompe el resultado`() {
        val correcto = TranscriptBuilder.serverAuthTranscript(sessionId, serverNonce, initiatorId)
        // No hay intercambio directo, pero si cambiamos sessionId por initiatorId:
        val mutado = TranscriptBuilder.serverAuthTranscript(
            initiatorId.substring(0, 40), serverNonce, sessionId + initiatorId.substring(40)
        )
        assertFalse(correcto.contentEquals(mutado))
    }

    @Test
    fun `nonce de distinto origen rompe`() {
        val nonce2 = ByteArray(16) { (it + 32).toByte() }
        val correcto = TranscriptBuilder.authTranscript(nonce, ts, responderId, initiatorId)
        val mutado = TranscriptBuilder.authTranscript(nonce2, ts, responderId, initiatorId)
        assertFalse(correcto.contentEquals(mutado))
    }

    // ===================================================================
    // Validacion de entrada
    // ===================================================================

    @Test
    fun `nonce de tamanio incorrecto lanza`() {
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptBuilder.authTranscript(ByteArray(8), ts, responderId, initiatorId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptBuilder.authTranscript(ByteArray(32), ts, responderId, initiatorId)
        }
    }

    @Test
    fun `identityId de longitud incorrecta lanza`() {
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptBuilder.authTranscript(nonce, ts, responderId, initiatorId.dropLast(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptBuilder.authTranscript(nonce, ts, responderId, "a1b2")
        }
    }

    @Test
    fun `sessionId de longitud incorrecta lanza`() {
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptBuilder.serverAuthTranscript(sessionId.dropLast(1), serverNonce, initiatorId)
        }
    }

    @Test
    fun `serverNonce de tamanio incorrecto lanza`() {
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptBuilder.serverAuthTranscript(sessionId, ByteArray(8), initiatorId)
        }
    }

    @Test
    fun `caracteres no hex en identityId lanzan`() {
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptBuilder.authTranscript(nonce, ts, responderId, "z1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2")
        }
    }

    @Test
    fun `timestamp negativo lanza`() {
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptBuilder.authTranscript(nonce, -1L, responderId, initiatorId)
        }
    }
}