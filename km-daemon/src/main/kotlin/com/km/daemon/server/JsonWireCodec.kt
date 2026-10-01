package com.km.daemon.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.km.auth.Base64Url
import com.km.daemon.util.Base64Standard
import com.km.model.IdentityId
import java.util.UUID

/**
 * Codec del dialecto B: JSON de nivel superior sobre frames de texto UTF-8.
 *
 * La referencia normativa es `docs/protocol/KM-WIRE-RELAY.md`. Este objeto es
 * el UNICO sitio donde se escriben y leen esos campos, para que el nombre
 * canonico de cada uno aparezca en un unico fichero y no repartido por el
 * router.
 *
 * Reglas de parseo que implementa (§7.1):
 *
 * 1. Cada frame es un objeto JSON de nivel superior.
 * 2. `type` esta presente y es un string.
 * 3. Los campos desconocidos se ignoran.
 * 4. Un frame que no cumpla 1 o 2 se descarta SIN matar la conexion.
 *
 * Sobre el punto 4: el codec no lanza. Devuelve `null` o un `Result` con
 * fallo, y quien despacha decide. La conexion se mantiene porque el
 * destinatario del contrato (`RelayClient`) tambien la mantiene ante un frame
 * ilegible (`RelayClient.kt:167-168`).
 *
 * El codec NO sabe nada de sesiones, de sockets ni de limites de conexion: es
 * una funcion pura de bytes a bytes.
 */
class JsonWireCodec(
    private val mapper: ObjectMapper = defaultMapper(),
    private val newMessageId: () -> UUID = UUID::randomUUID,
    private val now: () -> Long = System::currentTimeMillis,
) {

    companion object {
        /**
         * Version de dialecto que el daemon habla (§17.1). El cliente la
         * anuncia como el string `"2.0"`; no es la `version` numerica 3 de
         * KM-0002, que viaja dentro del challenge. Son campos distintos.
         */
        const val DIALECT_VERSION: String = "2.0"

        private fun defaultMapper(): ObjectMapper = ObjectMapper()
            // El codec usa la API de arbol, no el binding a data classes: el
            // modulo Kotlin queda declarado por el classpath de km-core, no
            // ejercido aqui.
            .registerKotlinModule()
            // Declarado y registrado por el perfil de dependencias del
            // modulo. El MVP serializa tiempos como `int64` de milisegundos
            // (§9.2, §10), no como tipos `java.time`, asi que todavia no lo
            // ejercita.
            .registerModule(JavaTimeModule())
    }

    // =====================================================================
    // Lectura: tipos de frame
    // =====================================================================

    /**
     * Objeto JSON de nivel superior, o `null` si el frame no es un objeto
     * (§7.1.1). No distingo "no es JSON" de "no es un objeto": el resultado
     * para el router es el mismo, descartar.
     */
    fun readObject(text: String): JsonNode? = runCatching { mapper.readTree(text) }
        .getOrNull()
        ?.takeIf { it.isObject }

    /** Discriminante `type`, o `null` si falta o no es string (§7.1.2). */
    fun typeOf(frame: JsonNode): String? =
        frame.get("type")?.takeIf { it.isTextual }?.asText()

    // --- AUTH_REQUEST (§9.2) -----------------------------------------------

    /**
     * `identityId` y `publicKey` de un `AUTH_REQUEST`, con su `protocolVersion`
     * literal.
     *
     * `protocolVersion` se devuelve como el string que vino, sin convertir: el
     * dialecto lo fija como `"2.0"` (§9.7) y la comparacion es literal. Una
     * version distinta NO se castea a int para "intentar entenderla": se
     * rechaza con un codigo explicito (§17.2.2).
     *
     * No se comprueba aqui que `identityId` derive de `publicKey`: esa es una
     * verificacion criptografica y la hace `AuthVerifier` a traves de
     * `RelayServer.authenticate`. Aqui solo se valida la FORMA, que es
     * parseo.
     */
    fun parseAuthRequest(frame: JsonNode): AuthRequestFields? {
        val identityId = frame.string("identityId") ?: return null
        if (identityId.length != IDENTITY_ID_HEX || !identityId.all { it in HEX }) return null
        val publicKey = frame.string("publicKey")?.let { runCatching { Base64Url.decode(it) }.getOrNull() }
            ?: return null
        if (publicKey.size != ED25519_PUBLIC_KEY_BYTES) return null
        val protocolVersion = frame.string("protocolVersion") ?: return null
        return AuthRequestFields(
            identityId = IdentityId(identityId.lowercase()),
            publicKey = publicKey,
            protocolVersion = protocolVersion,
            responderIdentityId = frame.string("responderIdentityId"),
        )
    }

    // --- AUTH_RESPONSE (§9.4) ---------------------------------------------

    /**
     * Firma de un `AUTH_RESPONSE`, en bytes.
     *
     * `identityId` y `publicKey` NO se reenvian aqui: viajan en el
     * `AUTH_REQUEST` y el transcript los vincula por posicion fija (§9.4). El
     * daemon los recuerda de la peticion, que es la unica forma de que ambos
     * extremos signing transcript coincidan.
     */
    fun parseAuthResponseSignature(frame: JsonNode): ByteArray? {
        val encoded = frame.string("signature") ?: return null
        val signature = runCatching { Base64Url.decode(encoded) }.getOrNull() ?: return null
        return signature.takeIf { it.size == ED25519_SIGNATURE_BYTES }
    }

    // --- MESSAGE (§10) ------------------------------------------------------

    /**
     * `MESSAGE` entrante: destinatario y carga ya decodificada.
     *
     * La carga viaja en Base64 estandar y se decodifica UNA vez, aqui. El
     * resultado es atómico (§14.1): si el Base64 no es valido, `null`, y el
     * router descarta el frame entero. No hay camino que entregue bytes
     * parciales.
     */
    fun parseMessage(frame: JsonNode): IncomingMessage? {
        val from = frame.identityId("from") ?: return null
        val to = frame.identityId("to") ?: return null
        val encoded = frame.string("data") ?: return null
        val data = Base64Standard.decode(encoded).getOrNull() ?: return null
        return IncomingMessage(from = from, to = to, data = data)
    }

    // --- extension SIGNAL (fuera de §8) ------------------------------------

    /**
     * Señal entrante del tipo `SIGNAL`.
     *
     * extension, NO un tipo de §8: el dialecto normativo no define como
     * viajan SDP/ICE, y no se inventa uno. Los campos son los de
     * `RelaySignalMessage`, en el mismo vocabulario.
     */
    fun parseSignal(frame: JsonNode): IncomingSignal? {
        val kind = frame.string("kind") ?: return null
        val to = frame.identityId("to") ?: return null
        return when (kind) {
            SIGNAL_SDP_OFFER -> frame.string("sdp")?.let { IncomingSignal(kind, to, it, null) }
            SIGNAL_SDP_ANSWER -> frame.string("sdp")?.let { IncomingSignal(kind, to, it, null) }
            SIGNAL_ICE_CANDIDATE -> frame.string("candidate")?.let { IncomingSignal(kind, to, it, null) }
            SIGNAL_CONTACT_EXCHANGE -> frame.string("contactBundle")
                ?.let { Base64Standard.decode(it).getOrNull() }
                ?.let { IncomingSignal(kind, to, null, it) }

            else -> null
        }
    }

    // =====================================================================
    // Escritura: tipos de frame
    // =====================================================================

    /** `AUTH_CHALLENGE` (§9.3, §19.2). */
    fun authChallenge(nonce: ByteArray, timestamp: Long, responderIdentityId: String): String =
        mapper.writeValueAsString(
            frame {
                put("type", "AUTH_CHALLENGE")
                put("messageId", newMessageId().toString())
                put("timestamp", timestamp)
                // Base64URL sin padding: entra en el transcript de 152 B y no
                // puede llevar '=' (§9.3, RFC 4648 §5).
                put("nonce", Base64Url.encode(nonce))
                put("responderIdentityId", responderIdentityId)
            }
        )

    /**
     * `AUTH_OK` (§9.6, §19.4).
     *
     * `serverSignature` es la firma de `RelayServer` sobre el transcript de
     * 120 B: `sessionId || serverNonce || initiatorIdentityId`. El
     * `serverNonce` NO viaja: §9.6 no lo lista, y la tabla es la norma. En la
     * practica eso deja la firma sin material verificable para el cliente, que
     * ya hoy no la comprueba (P6 de §18.2); no se resuelve aqui.
     *
     * `protocolVersion` SI viaja: es la confirmacion que §17.2.3 pide para que
     * un cliente antiguo y un rele nuevo no se finjan entendidos.
     */
    fun authOk(
        sessionId: String,
        serverSignature: ByteArray,
        initiatorIdentityId: String,
        responderIdentityId: String,
    ): String =
        mapper.writeValueAsString(
            frame {
                put("type", "AUTH_OK")
                put("messageId", newMessageId().toString())
                put("timestamp", now())
                put("protocolVersion", DIALECT_VERSION)
                put("identityId", initiatorIdentityId)
                put("responderIdentityId", responderIdentityId)
                put("sessionId", sessionId)
                put("serverSignature", Base64Url.encode(serverSignature))
            }
        )

    /** `AUTH_FAIL` (§9.6, §19.9). */
    fun authFail(errorCode: String, errorMessage: String?): String =
        mapper.writeValueAsString(
            frame {
                put("type", "AUTH_FAIL")
                put("messageId", newMessageId().toString())
                put("timestamp", now())
                put("errorCode", errorCode)
                errorMessage?.let { put("errorMessage", it) }
            }
        )

    /** `PEER_ONLINE` / `PEER_OFFLINE` (§12.1, §19.7). */
    fun peerPresence(type: String, identityId: IdentityId): String =
        mapper.writeValueAsString(
            frame {
                put("type", type)
                put("identityId", identityId.value)
            }
        )

    /** `PING` de aplicacion (§12.2, §19.7). */
    fun ping(messageId: String = newMessageId().toString()): String =
        mapper.writeValueAsString(
            frame {
                put("type", "PING")
                put("messageId", messageId)
                put("timestamp", now())
            }
        )

    /** `PONG` de aplicacion, correlacionado con el `PING` recibido (§12.2). */
    fun pong(originalMessageId: String): String =
        mapper.writeValueAsString(
            frame {
                put("type", "PONG")
                put("messageId", newMessageId().toString())
                put("timestamp", now())
                put("originalMessageId", originalMessageId)
            }
        )

    /**
     * `MESSAGE` saliente (§10, §19.6).
     *
     * `data` es el payload CODIFICADO y no se toca. Los unicos campos que
     * cambian respecto al frame que entro son `messageId` y `timestamp`, que
     * son metadatos del envelope: el primero porque es del transporte, el
     * segundo porque lo emite el rele, no el cliente (§6.2).
     */
    fun message(from: IdentityId, to: IdentityId, payload: ByteArray): String =
        mapper.writeValueAsString(
            frame {
                put("type", "MESSAGE")
                put("messageId", newMessageId().toString())
                put("timestamp", now())
                put("from", from.value)
                put("to", to.value)
                put("data", Base64Standard.encode(payload))
            }
        )

    /** `SIGNAL` saliente: el mismo shape que entra, en el otro sentido. */
    fun signal(
        from: IdentityId,
        to: IdentityId,
        kind: String,
        sdp: String? = null,
        candidate: String? = null,
        contactBundle: ByteArray? = null,
    ): String =
        mapper.writeValueAsString(
            frame {
                put("type", "SIGNAL")
                put("messageId", newMessageId().toString())
                put("timestamp", now())
                put("from", from.value)
                put("to", to.value)
                put("kind", kind)
                sdp?.let { put("sdp", it) }
                candidate?.let { put("candidate", it) }
                contactBundle?.let { put("contactBundle", Base64Standard.encode(it)) }
            }
        )

    // =====================================================================
    // helpers
    // =====================================================================

    private inline fun frame(block: ObjectNode.() -> Unit): ObjectNode =
        mapper.createObjectNode().apply(block)

    private fun JsonNode.string(field: String): String? = get(field)?.takeIf { it.isTextual }?.asText()

    private fun JsonNode.identityId(field: String): IdentityId? =
        string(field)
            ?.takeIf { it.length == IDENTITY_ID_HEX && it.all { c -> c in HEX } }
            ?.let { IdentityId(it.lowercase()) }
}

/** Campos de un `AUTH_REQUEST` ya validados en su forma. */
data class AuthRequestFields(
    val identityId: IdentityId,
    val publicKey: ByteArray,
    val protocolVersion: String,
    /** Declarado por el cliente. `null` si no vino; se valida aparte. */
    val responderIdentityId: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is AuthRequestFields &&
            identityId == other.identityId &&
            publicKey.contentEquals(other.publicKey) &&
            protocolVersion == other.protocolVersion &&
            responderIdentityId == other.responderIdentityId

    override fun hashCode(): Int {
        var result = identityId.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + protocolVersion.hashCode()
        result = 31 * result + (responderIdentityId?.hashCode() ?: 0)
        return result
    }
}

/** `MESSAGE` entrante con la carga YA en bytes. */
data class IncomingMessage(
    val from: IdentityId,
    val to: IdentityId,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is IncomingMessage && from == other.from && to == other.to && data.contentEquals(other.data)

    override fun hashCode(): Int {
        var result = from.hashCode()
        result = 31 * result + to.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * `SIGNAL` entrante.
 *
 * [text] es el campo de texto que corresponda a [kind]: `sdp` en ofertas y
 * respuestas, `candidate` en ICE. Se unifican en un campo porque son el
 * mismo tipo de carga y separarlos obligaria a un segundo `when` identico en
 * cada extremo. [contactBundle] es el caso binario, en Base64 estándar como
 * `data`.
 */
data class IncomingSignal(
    val kind: String,
    val to: IdentityId,
    val text: String?,
    val contactBundle: ByteArray?,
) {
    override fun equals(other: Any?): Boolean =
        other is IncomingSignal &&
            kind == other.kind &&
            to == other.to &&
            text == other.text &&
            contactBundleEquals(other.contactBundle)

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + to.hashCode()
        result = 31 * result + (text?.hashCode() ?: 0)
        result = 31 * result + (contactBundle?.contentHashCode() ?: 0)
        return result
    }

    private fun contactBundleEquals(other: ByteArray?): Boolean = when {
        contactBundle == null -> other == null
        other == null -> false
        else -> contactBundle.contentEquals(other)
    }
}

// Constantes del dialecto. Vivas aqui, no dispersas: el nombre canonico de un
// campo del cable se declara en un solo fichero.
const val IDENTITY_ID_HEX: Int = 64
const val ED25519_PUBLIC_KEY_BYTES: Int = 32
const val ED25519_SIGNATURE_BYTES: Int = 64
private val HEX = "0123456789abcdefABCDEF".toSet()

const val SIGNAL_SDP_OFFER: String = "sdp_offer"
const val SIGNAL_SDP_ANSWER: String = "sdp_answer"
const val SIGNAL_ICE_CANDIDATE: String = "ice_candidate"
const val SIGNAL_CONTACT_EXCHANGE: String = "contact_exchange"
