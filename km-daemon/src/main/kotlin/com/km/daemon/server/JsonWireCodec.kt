package com.km.daemon.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.km.auth.Base64Url
import com.km.auth.Dialect
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
         * Dialecto por defecto/legacy que el daemon habla (§17.1). Ya NO es un
         * literal duplicado: referencia la fuente unica `Dialect` (R34). El
         * cliente lo anuncia como el string `"2.0"`; no es la `version`
         * numerica 3 de KM-0002, que viaja dentro del challenge. Son campos
         * distintos (§9.7).
         *
         * Se conserva como alias de compatibilidad; el codigo nuevo usa
         * `Dialect` directamente.
         */
        val DIALECT_VERSION: String = Dialect.V2_0.wire

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

    // --- AUTH_OK (§9.6) -----------------------------------------------------

    /**
     * Campos canonicos de un `AUTH_OK` leidos del cable (§9.6).
     *
     * LADO DE LECTURA: el daemon EMITE el `AUTH_OK` con [authOk]; este metodo
     * existe para consumirlo —lo necesita el gate de conformidad de vectores P6
     * (task 1.4), que debe probar que el codec del daemon LEE los MISMOS bytes
     * del artefacto que los otros tres componentes. Solo valida la FORMA (64
     * hex, 40 hex, 16/32/64 bytes); la decision de dialecto y el alias legacy
     * `identityId` viven en `AuthJsonCodec.parseAuthOkForDialect` (km-core), que
     * es el UNICO sitio que resuelve el nombre canonico y su alias.
     *
     * `protocolVersion` se devuelve como el string que vino (o `null`), sin
     * convertir: es dialecto, nunca la `version` numerica de KM-0002 (§9.7).
     */
    fun parseAuthOk(frame: JsonNode): AuthOkWireFields? {
        val initiator = frame.string("initiatorIdentityId") ?: return null
        if (initiator.length != IDENTITY_ID_HEX || !initiator.all { it in HEX }) return null
        val responder = frame.string("responderIdentityId") ?: return null
        if (responder.length != IDENTITY_ID_HEX || !responder.all { it in HEX }) return null
        val sessionId = frame.string("sessionId") ?: return null
        if (sessionId.length != SESSION_ID_HEX || !sessionId.all { it in HEX }) return null
        val serverNonce = frame.string("serverNonce")
            ?.let { runCatching { Base64Url.decode(it) }.getOrNull() } ?: return null
        if (serverNonce.size != NONCE_BYTES) return null
        val responderPublicKey = frame.string("responderPublicKey")
            ?.let { runCatching { Base64Url.decode(it) }.getOrNull() } ?: return null
        if (responderPublicKey.size != ED25519_PUBLIC_KEY_BYTES) return null
        val serverSignature = frame.string("serverSignature")
            ?.let { runCatching { Base64Url.decode(it) }.getOrNull() } ?: return null
        if (serverSignature.size != ED25519_SIGNATURE_BYTES) return null
        return AuthOkWireFields(
            protocolVersion = frame.string("protocolVersion"),
            initiatorIdentityId = initiator.lowercase(),
            responderIdentityId = responder.lowercase(),
            sessionId = sessionId.lowercase(),
            serverNonce = serverNonce,
            responderPublicKey = responderPublicKey,
            serverSignature = serverSignature,
        )
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

    // --- ACK (seccion 11.1) ------------------------------------------------

    /**
     * `ACK` entrante: SOLO `from` y `to`, y SOLO para rutear.
     *
     * Deliberadamente minimal, y la minimalidad es el requisito (§11.1 ya
     * enmendada): de los seis campos con nombre canonico del `ACK` —`from`,
     * `to`, `originalMessageId`, `status`, `timestamp`, `signature`— este
     * metodo devuelve los DOS de transporte y ninguno de los cuatro de
     * aplicacion. No hay forma de que quien llama llegue a `originalMessageId`
     * por aqui, porque este objeto no lo devuelve.
     *
     * La firma NO se lee. No se verifica, no se descarta su ausencia, no se
     * guarda: el relé no tiene clave de sesion de aplicacion y no deberia
     * tenerla. Un `ACK` mal firmado llega al destinatario igual que uno bien
     * firmado, y es el receptor quien lo rechaza (§11.1, MUST NOT 1).
     */
    fun parseAckRoute(frame: JsonNode): AckRoute? {
        val from = frame.identityId("from") ?: return null
        val to = frame.identityId("to") ?: return null
        return AckRoute(from = from, to = to)
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

    /**
     * `AUTH_CHALLENGE` (§9.3, §19.2).
     *
     * `protocolVersion` SI viaja (task 1.1, N3): es la confirmacion que el
     * cliente verifica ANTES de firmar. El valor es SIEMPRE el dialecto
     * efectivo del handshake, un string (`Dialect.wire`), nunca un numero
     * (§9.7). Se emite el dialecto que el cliente DECLARO y que este rele
     * sabe servir: ni se sube ni se baja de dialecto por iniciativa del rele.
     */
    fun authChallenge(
        nonce: ByteArray,
        timestamp: Long,
        responderIdentityId: String,
        dialect: String,
    ): String =
        mapper.writeValueAsString(
            frame {
                put("type", "AUTH_CHALLENGE")
                put("messageId", newMessageId().toString())
                put("timestamp", timestamp)
                put("protocolVersion", dialect)
                // Base64URL sin padding: entra en el transcript de 152 B y no
                // puede llevar '=' (§9.3, RFC 4648 §5).
                put("nonce", Base64Url.encode(nonce))
                put("responderIdentityId", responderIdentityId)
            }
        )

    /**
     * `AUTH_OK` (§9.6, §19.4). Campos canonicos (R15/R16): `protocolVersion`,
     * `initiatorIdentityId`, `responderIdentityId`, `sessionId`, `serverNonce`,
     * `responderPublicKey`, `serverSignature`.
     *
     * `serverSignature` es la firma de `RelayServer` sobre el transcript de
     * 120 B: `sessionId || serverNonce || initiatorIdentityId`. Los DOS campos
     * que antes se omitian y dejaban la firma sin material verificable viajan ya
     * (task 1.2): `serverNonce` (Base64URL, 16 B) y `responderPublicKey`
     * (Base64URL, 32 B Ed25519). Que viajen NO significa que el cliente los
     * verifique todavia; la verificacion de `serverSignature` es P6 (4.1/5.2).
     *
     * El nombre del iniciador es SIEMPRE `initiatorIdentityId` (canonico) al
     * emitir; el rele nunca escribe el alias legacy `identityId` en el cable.
     * La ACEPTACION de ese alias es cosa de la LECTURA: `2.0` lo admite y `2.1`
     * lo retira **en cualquier forma** —solo, o junto al canonico, con valor
     * igual o distinto— con `UNKNOWN_PROTOCOL` (task 1.3, R2/R3, ruling del
     * owner), regla que vive en `AuthOkAliasRules` /
     * `AuthJsonCodec.parseAuthOkForDialect`.
     *
     * `protocolVersion` SI viaja: es la confirmacion que §17.2.3 pide para que
     * un cliente antiguo y un rele nuevo no se finjan entendidos. Su valor es
     * el dialecto EFECTIVO del handshake (el que el challenge confirmo), un
     * string, nunca un numero (§9.7). Un cliente que no implemente ese dialecto
     * DEBE fallar cerrado (nunca ONLINE).
     */
    fun authOk(
        sessionId: String,
        serverSignature: ByteArray,
        initiatorIdentityId: String,
        responderIdentityId: String,
        dialect: String,
        serverNonce: ByteArray,
        responderPublicKey: ByteArray,
    ): String =
        mapper.writeValueAsString(
            frame {
                put("type", "AUTH_OK")
                put("messageId", newMessageId().toString())
                put("timestamp", now())
                put("protocolVersion", dialect)
                put("initiatorIdentityId", initiatorIdentityId)
                put("responderIdentityId", responderIdentityId)
                put("sessionId", sessionId)
                // Base64URL sin padding: entran en el transcript de 120 B y no
                // pueden llevar '=' (§9.6, RFC 4648 §5).
                put("serverNonce", Base64Url.encode(serverNonce))
                put("responderPublicKey", Base64Url.encode(responderPublicKey))
                put("serverSignature", Base64Url.encode(serverSignature))
            }
        )

    /**
     * `AUTH_FAIL` (§9.6, §19.9).
     *
     * `protocolVersion` es OPCIONAL y puramente diagnostico (N6): si se
     * incluye, es el dialecto que el rele intento usar, y NUNCA debe
     * interpretarse como prueba de que el dialecto fue aceptado.
     */
    fun authFail(errorCode: String, errorMessage: String?, dialect: String? = null): String =
        mapper.writeValueAsString(
            frame {
                put("type", "AUTH_FAIL")
                put("messageId", newMessageId().toString())
                put("timestamp", now())
                dialect?.let { put("protocolVersion", it) }
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

/**
 * Campos canonicos de un `AUTH_OK` leidos del cable (§9.6), ya validados en su
 * FORMA. El nombre canonico del iniciador es `initiatorIdentityId`; el alias
 * legacy `identityId` NO se resuelve aqui (lo hace `AuthJsonCodec` en km-core,
 * con la regla del dialecto).
 */
data class AuthOkWireFields(
    /** Dialecto literal del frame, o `null` si no vino. Nunca se castea a Int. */
    val protocolVersion: String?,
    val initiatorIdentityId: String,
    val responderIdentityId: String,
    val sessionId: String,
    val serverNonce: ByteArray,
    val responderPublicKey: ByteArray,
    val serverSignature: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is AuthOkWireFields &&
            protocolVersion == other.protocolVersion &&
            initiatorIdentityId == other.initiatorIdentityId &&
            responderIdentityId == other.responderIdentityId &&
            sessionId == other.sessionId &&
            serverNonce.contentEquals(other.serverNonce) &&
            responderPublicKey.contentEquals(other.responderPublicKey) &&
            serverSignature.contentEquals(other.serverSignature)

    override fun hashCode(): Int {
        var result = protocolVersion?.hashCode() ?: 0
        result = 31 * result + initiatorIdentityId.hashCode()
        result = 31 * result + responderIdentityId.hashCode()
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + serverNonce.contentHashCode()
        result = 31 * result + responderPublicKey.contentHashCode()
        result = 31 * result + serverSignature.contentHashCode()
        return result
    }
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
 * Destino de un `ACK` entrante: quien lo manda y a quien va.
 *
 * Solo eso. La ausencia de `originalMessageId`, `status`, `timestamp` y
 * `signature` en este tipo no es descuido: es la forma que tiene la regla de
 * §11.1 de ser comprobable. Si alguien necesitara el `originalMessageId` para
 * reenviar el `ACK`, tendria que anadir el campo aqui, y el primer sitio donde
 * se veria es la lista de prohibiciones del spec.
 */
data class AckRoute(
    val from: IdentityId,
    val to: IdentityId,
)

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
const val SESSION_ID_HEX: Int = 40
const val NONCE_BYTES: Int = 16
const val ED25519_PUBLIC_KEY_BYTES: Int = 32
const val ED25519_SIGNATURE_BYTES: Int = 64
private val HEX = "0123456789abcdefABCDEF".toSet()

const val SIGNAL_SDP_OFFER: String = "sdp_offer"
const val SIGNAL_SDP_ANSWER: String = "sdp_answer"
const val SIGNAL_ICE_CANDIDATE: String = "ice_candidate"
const val SIGNAL_CONTACT_EXCHANGE: String = "contact_exchange"
