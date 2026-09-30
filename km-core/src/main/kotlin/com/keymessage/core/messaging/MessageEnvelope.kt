package com.keymessage.core.messaging

import com.keymessage.core.kmid.Kce
import com.keymessage.core.model.MessageId
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Tipo de lo que viaja dentro del sobre (3Q.5.1a).
 *
 * Convencion del proyecto: 1-based, `0` reservado para "desconocido". Un
 * `0` en el wire significa que el receptor no conoce el tipo, y debe
 * rechazar en vez de adivinar.
 */
enum class EnvelopeType(val code: ULong) {
    USER_MESSAGE(1UL),
    DELIVERY_ACK(2UL),
    READ_RECEIPT(3UL),
    ;

    companion object {
        fun fromCode(code: ULong): EnvelopeType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Sobre de mensaje v1 (3Q.5.1a).
 *
 * ## Donde vive, y por que
 *
 * El `messageId` va DENTRO del plaintext que cifra el ratchet, no en el
 * header de [com.keymessage.core.sf.SecureFrame]. La auditoria 3Q.5.0 lo fijo:
 *
 *  - el header v1 son 44 bytes versionados: anadir un campo rompe la v1;
 *  - y, peor, el AAD es el header COMPLETO, asi que un `messageId` en el
 *    header quedaria FUERA de la autenticacion: un intermediario podria
 *    alterarlo sin que la AEAD lo detecte.
 *
 * Ahi dentro queda autenticado, opaco para el relay, e inmutable frente a la
 * red.
 *
 * ## Por que esto habilita la retransmision
 *
 * El mismo ciphertext lleva siempre el mismo `messageId`. Reenviar el frame
 * exacto conserva la identidad de entrega, que es lo que KM-0004 exige
 * ("mismo `messageId`, mismo `payload`"). Re-cifrar NO serviria: el ratchet
 * avanza y produce un frame distinto con otro `N` (auditoria 3Q.5.0 A-5).
 *
 * El ratchet NO conoce el `messageId`. Solo protege y ordena estado
 * criptografico; la semantica de entrega es de la capa de arriba.
 */
class MessageEnvelope(
    val version: ULong,
    val messageId: MessageId,
    val type: EnvelopeType,
    val payload: ByteArray,
) {
    override fun toString(): String =
        "MessageEnvelope(v$version, id=${messageId.value}, type=$type, payload=${payload.size}B)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageEnvelope) return false
        return version == other.version &&
            messageId == other.messageId &&
            type == other.type &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var r = version.hashCode()
        r = 31 * r + messageId.hashCode()
        r = 31 * r + type.hashCode()
        r = 31 * r + payload.contentHashCode()
        return r
    }

    companion object {
        /** Crea un sobre de aplicacion con la version vigente. */
        fun userMessage(messageId: MessageId, payload: ByteArray): MessageEnvelope =
            MessageEnvelope(
                version = MessageEnvelopeCodec.VERSION,
                messageId = messageId,
                type = EnvelopeType.USER_MESSAGE,
                payload = payload.copyOf(),
            )

        /** Crea un ACK de entrega dentro de un sobre. */
        fun deliveryAck(messageId: MessageId, originalMessageId: MessageId, status: ULong): MessageEnvelope =
            MessageEnvelope(
                version = MessageEnvelopeCodec.VERSION,
                messageId = messageId,
                type = EnvelopeType.DELIVERY_ACK,
                payload = MessageEnvelopeCodec.encodeAckBody(originalMessageId, status),
            )

        /** Extrae el `originalMessageId` de un sobre de ACK. */
        fun ackOriginalMessageId(envelope: MessageEnvelope): MessageId? {
            if (envelope.type != EnvelopeType.DELIVERY_ACK) return null
            return MessageEnvelopeCodec.decodeAckBody(envelope.payload)
        }
    }
}

/**
 * Codec del sobre v1.
 *
 * KCE, no CBOR suelto: este sobre va DENTRO de un AEAD, y la determinacion
 * byte-a-byte es lo que permite que un vector de prueba congele el formato.
 * Dos implementaciones que produzcan el mismo sobre deben producir los
 * MISMOS bytes.
 */
object MessageEnvelopeCodec {

    const val VERSION: ULong = 1UL

    /**
     * Tope del sobre codificado.
     *
     * Derivado del limite del wire, no estimado: `SecureFrame` admite como
     * mucho 65535 B de ciphertext, y el sobre va dentro del plaintext. Un
     * sobre que no quepa en el frame es un error de emisor, no del receptor.
     */
    const val MAX_ENVELOPE_BYTES: Int = 60_000

    private const val K_VERSION = "version"
    private const val K_ID = "messageId"
    private const val K_TYPE = "type"
    private const val K_PAYLOAD = "payload"

    /** Longitud de un `messageId` en el wire: 16 bytes crudos del UUID. */
    const val MESSAGE_ID_LENGTH: Int = 16

    fun encode(envelope: MessageEnvelope): ByteArray {
        // KCE rechaza `ULong` deliberadamente: su tipado estricto impide que
        // un tipo no reproducible por otra implementacion llegue al wire. El
        // mapeo a `Long` es explicito y la conversion inversa tambien, de modo
        // que ningun valor pueda truncarse en silencio.
        val bytes = try {
            Kce.encode(
                mapOf(
                    K_VERSION to envelope.version.toLong(),
                    K_ID to uuidBytes(envelope.messageId),
                    K_TYPE to envelope.type.code.toLong(),
                    K_PAYLOAD to envelope.payload,
                )
            )
        } catch (e: Kce.KceException) {
            // El contrato publico de este codec es `EnvelopeFormatException`:
            // quien lo usa no debe conocer los codigos internos de KCE.
            throw EnvelopeFormatException("KCE no pudo codificar el sobre: ${e.code}")
        }
        if (bytes.size > MAX_ENVELOPE_BYTES) {
            throw EnvelopeFormatException(
                "sobre de ${bytes.size}B excede el maximo de $MAX_ENVELOPE_BYTES",
            )
        }
        return bytes
    }

    fun decode(bytes: ByteArray): MessageEnvelope {
        if (bytes.size > MAX_ENVELOPE_BYTES) {
            throw EnvelopeFormatException("sobre de ${bytes.size}B excede el maximo")
        }
        val map = try {
            Kce.decode(bytes) as? Map<*, *>
                ?: throw EnvelopeFormatException("el sobre no es un mapa")
        } catch (e: Kce.KceException) {
            throw EnvelopeFormatException("KCE invalido: ${e.code}")
        }
        val version = (map[K_VERSION] as? Long)?.toULong()
            ?: throw EnvelopeFormatException("falta 'version'")
        if (version != VERSION) {
            // Version desconocida: rechazo TOTAL. Adivinar seria interpretar
            // bytes con una gramática que no es la suya.
            throw EnvelopeFormatException("version $version no soportada (esperada $VERSION)")
        }
        val idBytes = map[K_ID] as? ByteArray
            ?: throw EnvelopeFormatException("falta 'messageId'")
        if (idBytes.size != MESSAGE_ID_LENGTH) {
            throw EnvelopeFormatException("messageId de ${idBytes.size}B, esperado $MESSAGE_ID_LENGTH")
        }
        val typeCode = (map[K_TYPE] as? Long)?.toULong()
            ?: throw EnvelopeFormatException("falta 'type'")
        val type = EnvelopeType.fromCode(typeCode)
            ?: throw EnvelopeFormatException("tipo $typeCode desconocido")
        val payload = map[K_PAYLOAD] as? ByteArray
            ?: throw EnvelopeFormatException("falta 'payload'")

        return MessageEnvelope(version, uuidFromBytes(idBytes), type, payload.copyOf())
    }

    // --- Cuerpo del ACK: messageId original + status, sin depender de JSON ---

    /** 16 B del messageId original + 1 B de status. */
    const val ACK_BODY_LENGTH: Int = 17

    fun encodeAckBody(originalMessageId: MessageId, status: ULong): ByteArray {
        if (status > 255uL) throw EnvelopeFormatException("status $status no cabe en 1 byte")
        return uuidBytes(originalMessageId) + byteArrayOf(status.toByte())
    }

    fun decodeAckBody(body: ByteArray): MessageId? {
        if (body.size != ACK_BODY_LENGTH) return null
        return uuidFromBytes(body.copyOfRange(0, MESSAGE_ID_LENGTH))
    }

    // --- UUID <-> 16 bytes, sin depender de la representacion textual ---

    fun uuidBytes(id: MessageId): ByteArray {
        val u = id.value
        return ByteBuffer.allocate(MESSAGE_ID_LENGTH)
            .putLong(u.mostSignificantBits)
            .putLong(u.leastSignificantBits)
            .array()
    }

    fun uuidFromBytes(bytes: ByteArray): MessageId {
        require(bytes.size == MESSAGE_ID_LENGTH) { "messageId debe tener $MESSAGE_ID_LENGTH bytes" }
        val bb = ByteBuffer.wrap(bytes)
        return MessageId(UUID(bb.long, bb.long))
    }
}

/** El sobre no cumple el formato: se rechaza, no se interpreta. */
class EnvelopeFormatException(message: String) : RuntimeException(message)
