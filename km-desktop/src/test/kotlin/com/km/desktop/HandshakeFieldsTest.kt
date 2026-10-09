package com.km.desktop

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pruebas del handshake KM-0002 tal y como lo ve el cliente.
 *
 * Estos casos nacieron de una ejecucion REAL contra `wss://keymessage.xyz/km`:
 * los dos fallos que fijan son los que el rele contesto de verdad, y ninguno de
 * los dos habria salido de leer el codigo con calma.
 */
class HandshakeFieldsTest {

    private val relay = "aa".repeat(32)
    private val client = "bb".repeat(32)

    @Test
    fun `responderIdentity sale de responderIdentityId, NO de identityId`() {
        // Forma REAL de un `AUTH_OK` del `km-daemon`: `identityId` es el
        // iniciador y `responderIdentityId` es el rele. Leer el primero
        // devolveria la identidad del propio cliente.
        val frame = RelayDataCodec().parseObject(
            """
            {"type":"AUTH_OK","messageId":"${UUID.randomUUID()}","timestamp":1790000001150,
             "protocolVersion":"2.0",
             "identityId":"$client",
             "responderIdentityId":"$relay",
             "sessionId":"8fc607fe9ab1423b8f61a9908ec3fe3174d4cd43",
             "serverSignature":"B2nQ4wE6rT8yU0iO2pA4sD6fG8hJ0kL2mN4pQ6rS8u"}
            """.trimIndent()
        )!!

        assertEquals(relay, frame.responderIdentity())
        assertEquals(client, frame.string("identityId"), "el iniciador es OTRO campo")
    }

    @Test
    fun `un AUTH_CHALLENGE da la misma identidad`() {
        val frame = RelayDataCodec().parseObject(
            """
            {"type":"AUTH_CHALLENGE","messageId":"${UUID.randomUUID()}","timestamp":1790000001042,
             "nonce":"AAECAwQFBgcICQoLDA0ODw","responderIdentityId":"$relay"}
            """.trimIndent()
        )!!
        assertEquals(relay, frame.responderIdentity())
    }

    @Test
    fun `se normaliza a minuscula`() {
        val frame = RelayDataCodec().parseObject(
            """{"type":"AUTH_OK","responderIdentityId":"${relay.uppercase()}"}"""
        )!!
        assertEquals(relay, frame.responderIdentity())
    }

    @Test
    fun `una identidad de forma invalida se devuelve como ausente`() {
        val corta = RelayDataCodec().parseObject("""{"responderIdentityId":"aa"}""")!!
        assertNull(corta.responderIdentity())

        val noHex = RelayDataCodec().parseObject("""{"responderIdentityId":"${"zz".repeat(32)}"}""")!!
        assertNull(noHex.responderIdentity())

        val ausente = RelayDataCodec().parseObject("""{"type":"AUTH_OK"}""")!!
        assertNull(ausente.responderIdentity())
    }

    @Test
    fun `sin responderIdentityId el valor anunciado es null, no una cadena vacia`() {
        // El `km-daemon` rechaza con `RELAY_IDENTITY_MISMATCH` cuando el campo
        // viene INFORMADO y no coincide. Una cadena vacia cuenta como informado:
        // anunciar "" hace fallar el primer `AUTH_REQUEST`. Lo correcto es
        // omitirlo, y eso es lo que hace el transporte cuando no hay valor.
        val frame = RelayDataCodec().parseObject("""{"type":"AUTH_REQUEST","identityId":"$client"}""")!!
        val announced: String? = frame.string("responderIdentityId")
        assertNull(announced, "ausente, no cadena vacia")
    }

    @Test
    fun `el sessionId del AUTH_OK son 40 hex`() {
        val frame = RelayDataCodec().parseObject(
            """{"type":"AUTH_OK","sessionId":"8fc607fe9ab1423b8f61a9908ec3fe3174d4cd43"}"""
        )!!
        val sessionId = frame.string("sessionId")
        assertEquals(JvmRelayTransport.SESSION_ID_HEX, sessionId!!.length)
    }

    @Test
    fun `el nonce del challenge son 16 bytes`() {
        // Base64URL sin padding de 16 bytes: 22 caracteres.
        val frame = RelayDataCodec().parseObject(
            """{"type":"AUTH_CHALLENGE","nonce":"AAECAwQFBgcICQoLDA0ODw"}"""
        )!!
        val nonce = com.km.auth.Base64Url.decode(frame.string("nonce")!!)
        assertEquals(JvmRelayTransport.NONCE_BYTES, nonce.size)
    }

    @Test
    fun `el relay-identity se valida al parsear`() {
        val ok = DesktopOptions.parse(arrayOf("--relay-identity", relay))
        assertEquals(relay, ok.relayIdentity?.value)

        var thrown: IllegalArgumentException? = null
        try {
            DesktopOptions.parse(arrayOf("--relay-identity", "corto"))
        } catch (e: IllegalArgumentException) {
            thrown = e
        }
        assert(thrown != null) { "una identidad corta debe fallar al parsear, no en el handshake" }
    }
}
