package com.km.desktop

import com.km.identity.KmIds
import com.km.model.IdentityId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Direccion de claves de FIRMA de los peers conocidos: `IdentityId -> Ed25519 publica`.
 *
 * POR QUE ESTA CLASE EXISTE
 *
 * `KeyMessageCoreImpl` recibe un `peerPublicKeyResolver: (IdentityId) -> ByteArray?`
 * y se lo pasa a `AckManagerImpl`, que sin el devuelve `success` sin verificar y
 * sin procesar: el `ACK` llega, y el emisor se queda esperando uno que ya tiene.
 * El valor por defecto es `{ null }` justamente para no romper a quien construye
 * el core sin claves.
 *
 * El problema nunca fue el resolver: era QUE NO HAY DONDE VIVIR LA CLAVE DEL PEER.
 * `IdentityKeystore` guarda la identidad LOCAL y nada mas — ni un roster, ni un
 * bundle, ni una sesion X3DH. Sin una direccion, la unica forma de "configurar"
 * el resolver era devolver una clave adivinada, que es peor que no devolver
 * ninguna: autentica contra la identidad equivocada en vez de no autenticar.
 *
 * QUE ES Y QUE NO ES
 *
 * Esto NO es criptografia y NO implementa nada de KM-ID-0001. Es el almacen de
 * la mitad PUBLICA de una relacion que ya se establecio en otro sitio. Lo unico
 * que hace es comprobar, al registrar, que la clave que se guarda sea
 * coherente con la identidad a la que se guarda —la misma comprobacion que hace
 * `IdentityKeystore.readIdentity` con la suya— y dejarla consultable por
 * `identityId`.
 *
 * LA COHERENCIA SE RECALCULA, NO SE COPIA
 *
 * `identityId = SHA-256("KM-ID-IDENTITY" || clave)` (KM-ID-0001 seccion 6), y esa
 * derivacion la hace `KmIds.identityId` de `km-core`. Un registro cuyo
 * `identityId` no derive de la clave guardada es un archivo manipulado o escrito
 * a mano con una clave que no corresponde, y aceptarlo haria que se autenticara
 * un `ACK` firmado por una clave ajena a la identidad que lo emitio.
 *
 * SOBRE EL `ContactBundle`
 *
 * Un `ContactBundle` trae `subjectSigningKey`, pero esa es una clave de FIRMA DE
 * DISPOSITIVO dentro de un roster, y su `deviceId` deriva de otro dominio
 * (`KM-ID-DEVICE`). No es la clave de identidad de la que deriva el
 * `identityId` del cable. Por eso [registerFromContactBundle] existe y por eso
 * pasa por la MISMA comprobacion que [register]: si la clave del bundle no deriva
 * el `identityId` que se le pasa, no se guarda. No es una limitacion que se
 * haya dejado por convenience: es la unica forma de que un bundle no acabe
 * autenticando ACKs de una identidad que no es la suya.
 *
 * PERSISTENCIA
 *
 * JSON plano junto al almacen de identidad, en Base64 estandar, con la MISMA
 * comprobacion de coherencia al leer. Un registro ilegible o incoherente se
 * salta y se cuenta en [skipped]: un peers.json corrupto deja de autenticar
 * esos peers, que es el comportamiento documentado, y no tumba el arranque de
 * un cliente que si podria entregar mensajes.
 */
class PeerKeyDirectory(
    private val path: Path? = null,
) {

    private val keys = ConcurrentHashMap<String, ByteArray>()

    /** Registros leidos de [path] que no pasaron la comprobacion de coherencia. */
    var skipped: Int = 0
        private set

    /**
     * Registra la clave publica de FIRMA de [identityId].
     *
     * Devuelve `false` —y NO guarda nada— si la clave no deriva ese
     * `identityId`. Volver a registrar la misma identidad con la misma clave es
     * idempotente; con OTRA clave, la equivoca, y se rechaza por la misma
     * comprobacion.
     */
    fun register(identityId: IdentityId, ed25519PublicKey: ByteArray): Boolean {
        if (!derives(identityId, ed25519PublicKey)) return false
        keys[identityId.value] = ed25519PublicKey.copyOf()
        return true
    }

    /**
     * Registra desde un `ContactBundle` decodificado.
     *
     * El bundle es un `Map` —lo que produce `Kce.decode` o el parser de
     * `km-core`—, y la clave que interesa es `subjectSigningKey`. Se SOMETE a
     * [register], de modo que un bundle cuyo `subjectSigningKey` no derive el
     * `identityId` indicado queda rechazado. Ver la nota de clase.
     */
    fun registerFromContactBundle(bundle: Map<*, *>, identityId: IdentityId): Boolean {
        val key = bundle["subjectSigningKey"] as? ByteArray
            ?: (bundle["subjectSigningKey"] as? String)?.let { base64OrNull(it) }
            ?: return false
        return register(identityId, key)
    }

    /** Clave de firma de [identityId], o `null` si no se conoce. */
    fun signingKeyOf(identityId: IdentityId): ByteArray? = keys[identityId.value]?.copyOf()

    /** Identidades con clave conocida. */
    fun known(): Set<IdentityId> = keys.keys.map { IdentityId(it) }.toSet()

    /** El resolver que consume `KeyMessageCoreImpl`. */
    fun resolver(): (IdentityId) -> ByteArray? = ::signingKeyOf

    /**
     * Registra un peer a partir de su `identityId` en hex y su clave en Base64.
     *
     * Es la forma que usa la TUI, donde pegar un bundle entero no es pratico.
     * Devuelve `null` si el par no es legible, y `false` si es legible pero
     * incoherente: son dos fallos distintos y el usuario distingue uno de otro.
     */
    fun registerEncoded(identityIdHex: String, base64PublicKey: String): String? {
        val key = base64OrNull(base64PublicKey.trim())
            ?: return "la clave publica no es Base64 valido"
        if (key.size != PUBKEY_BYTES) {
            return "la clave publica debe tener $PUBKEY_BYTES bytes, tiene ${key.size}"
        }
        val hex = identityIdHex.trim().lowercase()
        if (hex.length != RelayDataCodec.IDENTITY_ID_HEX || hex.any { it !in "0123456789abcdef" }) {
            return "el identityId no son ${RelayDataCodec.IDENTITY_ID_HEX} caracteres hex"
        }
        return if (register(IdentityId(hex), key)) null
        else "esa clave NO deriva ese identityId: no se guarda"
    }

    // ------------------------------------------------------------------
    // Persistencia
    // ------------------------------------------------------------------

    /**
     * Lee [path] si existe y registra lo que sea coherente.
     *
     * Un archivo ausente no es un error: es la primera ejecucion. Uno ilegible
     * tampoco tumba nada, y se cuenta en [skipped].
     */
    fun load(): PeerKeyDirectory {
        val file = path ?: return this
        if (!Files.isRegularFile(file)) return this
        val text = runCatching { Files.readString(file, StandardCharsets.UTF_8) }.getOrNull() ?: return this
        val root = runCatching { JSON.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return this
        root["peers"]?.let { peers ->
            // `JsonArray` a proposito: una lista de pares, no un objeto indexado,
            // para que anadir un peer no reescriba las claves de los demas.
            (peers as? JsonArray)?.forEach { element ->
                val entry = element as? JsonObject ?: run { skipped++; return@forEach }
                val hex = entry.stringOrNull("identityId") ?: run { skipped++; return@forEach }
                val key = entry.stringOrNull("publicKey")?.let { base64OrNull(it) } ?: run { skipped++; return@forEach }
                if (!register(IdentityId(hex.lowercase()), key)) skipped++
            }
        }
        return this
    }

    /** Escribe el directorio en [path], si lo tiene. Un directorio sin ruta no se guarda. */
    fun save(): Boolean {
        val file = path ?: return false
        return runCatching {
            val document = buildJsonObject {
                put("format", FORMAT)
                put("version", VERSION)
                putJsonArray("peers") {
                    keys.entries.sortedBy { it.key }.forEach { (hex, key) ->
                        add(
                            buildJsonObject {
                                put("identityId", hex)
                                put("publicKey", B64.encodeToString(key))
                            }
                        )
                    }
                }
            }
            file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            Files.writeString(file, document.toString(), StandardCharsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * `identityId` deriva de [publicKey]?
     *
     * La comparacion es en minúsculas porque `IdentityId` se normaliza al
     * construirse y un archivo escrito a mano puede traer mayusculas.
     */
    private fun derives(identityId: IdentityId, publicKey: ByteArray): Boolean {
        if (publicKey.size != PUBKEY_BYTES) return false
        val derived = KmIds.identityId(publicKey).joinToString("") { "%02x".format(it) }
        return derived.equals(identityId.value, ignoreCase = true)
    }

    private fun base64OrNull(encoded: String): ByteArray? =
        runCatching { B64_DECODER.decode(encoded) }.getOrNull()

    private fun JsonObject.stringOrNull(field: String): String? =
        this[field]?.toString()?.trim('"')?.takeIf { it.isNotEmpty() }

    companion object {
        const val FORMAT: String = "km-desktop-peers"
        const val VERSION: Int = 1

        /** `KmIdConstants.PUBKEY_BYTES`: una clave Ed25519 publica son 32 B. */
        const val PUBKEY_BYTES: Int = 32

        private val JSON = Json { ignoreUnknownKeys = true }
        private val B64: Base64.Encoder = Base64.getEncoder()
        private val B64_DECODER: Base64.Decoder = Base64.getDecoder()
    }
}