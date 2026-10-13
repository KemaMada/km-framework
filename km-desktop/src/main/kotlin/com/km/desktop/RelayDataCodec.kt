package com.km.desktop

import com.km.codec.JsonDecoder
import com.km.codec.JsonEncoder
import com.km.model.Ack
import com.km.model.AckStatus
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.UUID

/**
 * Codec del dialecto KM-WIRE-RELAY para los dos frames con carga: `MESSAGE`
 * (seccion 10) y `ACK` (seccion 11.2).
 *
 * POR QUE EXISTE Y POR QUE NO USA `km-core` ENTERO
 *
 * El dialecto del rele NO es `JsonEncoder`. `JsonEncoder.encodeMessage` produce
 * un campo `payload`, y el `MESSAGE` de KM-WIRE-RELAY se llama `data` (seccion
 * 10.1, con la divergencia abierta en P5). Mezclarlos seria convertir una
 * divergencia documentada en un bug silencioso.
 *
 * Al otro lado, `KeyMessageCoreImpl` si habla el vocabulario de `km-core`: su
 * `messageHandler` espera `JsonDecoder.decodeMessage`, o sea
 * `{messageId, from, to, payload, timestamp}`, y su `ackHandler` espera
 * `JsonDecoder.decodeAck`. Por eso este codec declara DOS formatos y una
 * traduccion explicita entre el frame del cable y el objeto que el core
 * entiende. Traducir es trabajo; decidir cual de los dos dialectos es el
 * correcto sin una base normativa seria inventar el contrato.
 *
 * `STORED` y `RELAY_EXPIRED` NO se decodifican aqui: los produce y los consume
 * `JsonRelayControlCodec` de `km-core`, que es el unico sitio donde sus campos
 * estan escritos.
 *
 * REGLAS DE PARSEO (seccion 7.1)
 *
 * 1. Cada frame es un objeto JSON de nivel superior.
 * 2. `type` esta presente y es un string.
 * 3. Los campos desconocidos se ignoran.
 * 4. Un frame que no cumpla 1 o 2 se descarta SIN matar la conexion.
 *
 * Aqui, "descartar" es devolver `null`: el codec no lanza.
 *
 * QUE CAMPO ES EL IDENTIFICADOR DE APLICACION
 *
 * El `messageId` del envelope del cable (seccion 10) NO lo es. El rele lo
 * reemite y `MessageRouter` lo vuelve a acuñar al reenviar, asi que cambia en
 * cada salto. El identificador de aplicacion es el que viaja DENTRO de la
 * carga, en el `messageId` que escribe `JsonEncoder`, y ese si es estable de
 * extremo a extremo: es contra el que el emisor llama `expectAck` y contra el
 * que el receptor marca el duplicado.
 */
class RelayDataCodec(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val encoder: Base64.Encoder = Base64.getEncoder(),
    private val decoder: Base64.Decoder = Base64.getDecoder(),
    private val newUuid: () -> UUID = UUID::randomUUID,
    private val now: () -> Long = System::currentTimeMillis,
    private val kmEncoder: JsonEncoder = JsonEncoder(),
    private val kmDecoder: JsonDecoder = JsonDecoder(),
) {

    /** `MESSAGE` entrante, con la carga YA en bytes. */
    class InboundMessage(
        val from: IdentityId,
        val to: IdentityId,
        val payload: ByteArray,
        /**
         * `messageId` del ENVELOPE del cable, o `null` si el frame no lo traia.
         *
         * NO es el identificador de aplicacion: es metadato de transporte, y el
         * rele lo reemite (§6.2). Vive aqui solo como ultimo recurso, para una
         * carga que no se pueda leer como `Message` de `km-core`. Si la carga SI
         * se puede leer, manda la de la carga, y este campo se ignora.
         */
        val envelopeMessageId: String? = null,
    ) {
        override fun equals(other: Any?): Boolean = other is InboundMessage &&
            from == other.from && to == other.to && payload.contentEquals(other.payload)

        override fun hashCode(): Int {
            var result = from.hashCode()
            result = 31 * result + to.hashCode()
            result = 31 * result + payload.contentHashCode()
            return result
        }

        override fun toString(): String =
            "InboundMessage(from=${from.value.take(8)}…, to=${to.value.take(8)}…, payload=${payload.size}B)"
    }

    // ------------------------------------------------------------------
    // Salida
    // ------------------------------------------------------------------

    /**
     * `MESSAGE` saliente (seccion 10).
     *
     * `data` es el payload CODIFICADO y no se toca: el rele lo transcodifica y lo
     * entrega byte a byte (secciones 6.1 y 6.2).
     *
     * `messageId` es el identificador de APLICACION del mensaje que viaja en
     * [data], escrito por defecto con el que trae la carga. R1.1: un `MESSAGE`
     * que sale con un `messageId` nuevo es un mensaje cuyo `ACK` no lo puede
     * confirmar nadie, porque el `originalMessageId` que volvera sera el de la
     * carga y no el del envelope. Se puede forzar con el ultimo parametro, y solo
     * para quien sepa lo que hace: el default es lo correcto.
     */
    fun encodeMessage(
        from: IdentityId,
        to: IdentityId,
        payload: ByteArray,
        messageId: String = applicationMessageId(payload)?.value?.toString() ?: newUuid().toString(),
    ): String =
        buildJsonObject {
            put("type", TYPE_MESSAGE)
            put("messageId", messageId)
            put("timestamp", now())
            put("from", from.value)
            put("to", to.value)
            put("data", encoder.encodeToString(payload))
        }.toString()

    /**
     * El `messageId` de APLICACION que viaja dentro de [payload], o `null`.
     *
     * La carga de un `MESSAGE` emitido por `km-core` es un `Message` de `km-core`
     * codificado por `JsonEncoder`, y ahi dentro esta el identificador contra el
     * que el emisor empareja el `ACK` (`SenderImpl` -> `expectAck`). Sacarlo es
     * una lectura de ese vocabulario, no una interpretacion del protocolo: lo
     * entiende `km-core`, no este modulo.
     *
     * `null` significa "esta carga no es un `Message` de `km-core`", que es un
     * caso legitimo: un consumidor puede enviar una carga opaca. Por eso el
     * valor por defecto de [encodeMessage] cae en un identificador nuevo en vez
     * de inventar uno.
     */
    fun applicationMessageId(payload: ByteArray): MessageId? =
        kmDecoder.decodeMessage(payload).getOrNull()?.messageId

    /** `ACK` saliente (seccion 11.2). */
    fun encodeAck(ack: Ack): String =
        buildJsonObject {
            put("type", TYPE_ACK)
            put("from", ack.from.value)
            put("to", ack.to.value)
            put("originalMessageId", ack.originalMessageId.value.toString())
            put("status", ack.status.name)
            put("timestamp", ack.timestamp)
            put("signature", encoder.encodeToString(ack.signature))
        }.toString()

    // ------------------------------------------------------------------
    // Entrada
    // ------------------------------------------------------------------

    /**
     * `MESSAGE` entrante, o `null` si el frame no cumple el contrato.
     *
     * El `data` se decodifica UNA vez, aqui, y de forma ATOMICA: si el Base64 no
     * es valido se devuelve `null` y el frame entero se descarta (seccion 14.1).
     * No hay camino que entregue bytes parciales, ni que "repare" la entrada, ni
     * que la reenvie a pelo.
     */
    fun decodeMessage(frame: JsonObject): InboundMessage? {
        val from = frame.identityId("from") ?: return null
        val to = frame.identityId("to") ?: return null
        val encoded = frame.string("data") ?: return null
        val payload = runCatching { decoder.decode(encoded) }.getOrNull() ?: return null
        // `messageId` es OPCIONAL en la entrada: §7.1.3 dice que los campos
        // desconocidos se ignoran, y un `MESSAGE` sin `messageId` sigue siendo
        // un `MESSAGE` legible. Se lee solo como ultimo recurso, para una carga
        // que no sea un `Message` de `km-core`.
        return InboundMessage(from, to, payload, frame.string("messageId"))
    }

    /**
     * `ACK` entrante, o `null`.
     *
     * "No es un ACK" y "no se pudo entender" son el mismo `null`: las dos cosas
     * las decide el llamante, que es quien sabe si el `type` del frame era `ACK`
     * y si venia con todos los campos.
     */
    fun decodeAck(frame: JsonObject): Ack? {
        val from = frame.identityId("from") ?: return null
        val to = frame.identityId("to") ?: return null
        val original = frame.string("originalMessageId")
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return null
        val status = frame.string("status")
            ?.let { runCatching { AckStatus.valueOf(it) }.getOrNull() }
            ?: return null
        val timestamp = frame.long("timestamp") ?: return null
        val signature = frame.string("signature")
            ?.let { runCatching { decoder.decode(it) }.getOrNull() }
            ?: return null
        return Ack(from, to, MessageId(original), status, timestamp, signature)
    }

    // ------------------------------------------------------------------
    // Traduccion al vocabulario de km-core
    // ------------------------------------------------------------------

    /**
     * Bytes que `KeyMessageCoreImpl` mete en `messageStore`.
     *
     * R1.1/R1.2: el `messageId` de la carga ES el del mensaje, y se conserva.
     *
     * Esto era el defecto que hacia que ningun `ACK` correlateda. Aqui se
     * fabricaba un `Message` con `newUuid()`, se descartaba el `messageId` que
     * el emisor habia guardado y ya habia metido en `expectAck`, y el
     * `originalMessageId` que volvia era el de un mensaje que el emisor jamas
     * habia visto. `AckManagerImpl.onAckReceived` lo buscaba, no lo encontraba,
     * y devolvia `success`: el emisor se quedaba esperando un `ACK` que ya
     * habia llegado.
     *
     * El `messageId` del ENVELOPE del cable no se usa para esto, y no por
     * descuido: el rele lo reemite (§6.2) y `MessageRouter` lo vuelve a acuñar
     * al reenviar, de modo que es un identificador de transporte que cambia en
     * cada salto. Correlacionar con el seria correlacionar con algo que el
     * receptor no controla. El identificador estable es el de la carga, que es
     * donde `SenderImpl` pone `expectAck` y donde `ReceiverImpl` marca el
     * duplicado.
     *
     * Si la carga NO se puede leer como `Message` de `km-core` —una carga opaca
     * de un consumidor que no use este core— no hay identificador de aplicacion
     * que deducir. Se cae entonces al `messageId` del envelope, y solo si tampoco
     * ese sirve, a uno nuevo: es el unico caso en el que la correlacion no se
     * puede garantizar, y ocurre cuando no hay nada que correlacionar.
     */
    fun toCoreMessageBytes(inbound: InboundMessage): ByteArray? {
        kmDecoder.decodeMessage(inbound.payload).getOrNull()?.let { carried ->
            // Se re-codifica desde el objeto leido, no se devuelve el `ByteArray`
            // crudo: lo que sale son los campos que se entendieron, no una cadena
            // de texto que solo pareciese un `Message`.
            return kmEncoder.encodeMessage(carried).getOrNull()
        }
        val recovered = inbound.envelopeMessageId
            ?.let { runCatching { MessageId(UUID.fromString(it)) }.getOrNull() }
        val message = Message(
            messageId = recovered ?: MessageId(newUuid()),
            from = inbound.from,
            to = inbound.to,
            payload = inbound.payload,
            timestamp = now(),
        )
        return kmEncoder.encodeMessage(message).getOrNull()
    }

    /** Bytes que `KeyMessageCoreImpl` entrega a `ackManager`. */
    fun toCoreAckBytes(ack: Ack): ByteArray? = kmEncoder.encodeAck(ack).getOrNull()

    // ------------------------------------------------------------------
    // Lectura de frames
    // ------------------------------------------------------------------

    /** Objeto JSON de nivel superior, o `null` (seccion 7.1.1). */
    fun parseObject(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject

    /** Discriminante `type`, o `null` si falta o no es string (seccion 7.1.2). */
    fun typeOf(frame: JsonObject): String? = frame.string("type")

    companion object {
        const val TYPE_MESSAGE: String = "MESSAGE"
        const val TYPE_ACK: String = "ACK"

        /** Longitud de un `identityId`: 32 bytes en hex. */
        const val IDENTITY_ID_HEX: Int = 64

        private val HEX = "0123456789abcdefABCDEF".toSet()
    }
}

// ----------------------------------------------------------------------
// Lectores de campos del dialecto.
//
// Son de TOP NIVEL y no metodos de `RelayDataCodec` a proposito: quien parsea
// frames es `JvmRelayTransport`, y una extension de miembro exigiria tener el
// codec como receptor de despacho en cada llamada, que es una forma de dejar de
// poder usarla desde el transporte sin inventar un receptor falso.
// ----------------------------------------------------------------------

/** Campo string, o `null` si falta o no es texto. */
internal fun JsonObject.string(field: String): String? = this[field]?.jsonPrimitive?.contentOrNull

/** Campo entero largo, o `null`. */
internal fun JsonObject.long(field: String): Long? = this[field]?.jsonPrimitive?.long

/** Campo entero, o `null`. */
internal fun JsonObject.int(field: String): Int? = this[field]?.jsonPrimitive?.int

/** Campo objeto, o `null`. */
internal fun JsonObject.obj(field: String): JsonObject? = this[field]?.jsonObject

/**
 * `IdentityId` de 64 hex, o `null`.
 *
 * La longitud y el alfabeto se validan AQUI y no se delegan: es una regla de
 * FORMA del dialecto, y aceptarla despues significaria emitir un `PEER_ONLINE`
 * con una identidad que ningun transcript puede vincular.
 */
internal fun JsonObject.identityId(field: String): IdentityId? {
    val value = string(field) ?: return null
    if (value.length != RelayDataCodec.IDENTITY_ID_HEX) return null
    if (value.any { it !in "0123456789abcdefABCDEF" }) return null
    return IdentityId(value.lowercase())
}

/**
 * `responderIdentityId` de un `AUTH_CHALLENGE` o un `AUTH_OK`, normalizado a
 * minuscula, o `null` si no viene o no tiene forma valida.
 *
 * Vive fuera de las dos clases a proposito. En `AUTH_OK` conviven
 * `initiatorIdentityId` —el iniciador, o sea el cliente— y `responderIdentityId`
 * —el rele—, y confundirlos
 * no falla en el primer arranque: el primer `AUTH_REQUEST` ya ocurrio antes de
 * leer el `AUTH_OK`. Falla en la PRIMERA reconexion, que se anuncia con la
 * identidad equivocada y recibe `AUTH_FAIL RELAY_IDENTITY_MISMATCH`. Una funcion
 * con nombre propio y un test es la defensa contra eso.
 */
internal fun JsonObject.responderIdentity(): String? {
    val raw = string("responderIdentityId") ?: return null
    if (raw.length != RelayDataCodec.IDENTITY_ID_HEX) return null
    if (raw.any { it !in "0123456789abcdefABCDEF" }) return null
    return raw.lowercase()
}
