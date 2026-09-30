package com.keymessage.core.auth

/**
 * Construccion PURA de los authentication transcripts de KM-0002.
 *
 * 3F.2.
 *
 * Authentication Transcript (KM-0002 seccion 9.2):
 *
 *     nonce (16 raw) || uint64_be(timestamp) || responderIdentityId (64 ascii) || identityId (64 ascii)
 *     0        15        16        23        24                    87        88                    151
 *
 *     total = 16 + 8 + 64 + 64 = 152 bytes
 *
 * Server Authentication Transcript (KM-0002 seccion 10.4):
 *
 *     sessionId (40 ascii) || serverNonce (16 raw) || initiatorIdentityId (64 ascii)
 *     0           39       40           55       56                             119
 *
 *     total = 40 + 16 + 64 = 120 bytes
 *
 * INVARIANTE: la funcion es determinista y los campos viajan EN ESTE ORDEN.
 * El timestamp es el del Challenge (uint64 big-endian), NO el de la cabecera
 * comun del Protocol Message.
 */
object TranscriptBuilder {

    const val AUTH_TRANSCRIPT_LEN: Int = 152
    const val SERVER_AUTH_TRANSCRIPT_LEN: Int = 120

    private const val NONCE_BYTES: Int = 16
    private const val ID_HEX: Int = 64
    private const val SESSION_HEX: Int = 40
    private const val UINT64_MAX: Long = -1L // 0xFFFF_FFFF_FFFF_FFFF
    private val HEX_CHARS = "0123456789abcdefABCDEF".toSet()

    fun authTranscript(
        nonce: ByteArray,
        timestampMillis: Long,
        responderIdentityId: String,
        identityId: String,
    ): ByteArray {
        require(nonce.size == NONCE_BYTES) {
            "nonce debe tener $NONCE_BYTES bytes (128 bits), recibidos ${nonce.size}"
        }
        require(timestampMillis >= 0) { "timestamp debe ser unsigned uint64 (recibido $timestampMillis)" }
        requireHex(responderIdentityId, ID_HEX, "responderIdentityId")
        requireHex(identityId, ID_HEX, "identityId")

        val out = ByteArray(AUTH_TRANSCRIPT_LEN)
        nonce.copyInto(out, 0)
        for (i in 0 until 8) {
            out[16 + i] = (timestampMillis ushr (8 * (7 - i))).toByte()
        }
        responderIdentityId.encodeToByteArray().copyInto(out, 24)
        identityId.encodeToByteArray().copyInto(out, 88)
        return out
    }

    fun serverAuthTranscript(
        sessionId: String,
        serverNonce: ByteArray,
        initiatorIdentityId: String,
    ): ByteArray {
        requireHex(sessionId, SESSION_HEX, "sessionId")
        require(serverNonce.size == NONCE_BYTES) {
            "serverNonce debe tener $NONCE_BYTES bytes, recibidos ${serverNonce.size}"
        }
        requireHex(initiatorIdentityId, ID_HEX, "initiatorIdentityId")

        val out = ByteArray(SERVER_AUTH_TRANSCRIPT_LEN)
        sessionId.encodeToByteArray().copyInto(out, 0)
        serverNonce.copyInto(out, 40)
        initiatorIdentityId.encodeToByteArray().copyInto(out, 56)
        return out
    }

    private fun requireHex(value: String, len: Int, name: String) {
        require(value.length == len) { "$name debe tener exactamente $len caracteres hex ascii" }
        require(value.all { it in HEX_CHARS }) { "$name contiene caracteres no hex" }
    }
}