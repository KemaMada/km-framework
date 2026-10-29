package com.km.desktop

import com.km.desktop.platform.OwnerPermissions
import com.km.identity.KmIds
import com.km.identity.VerifiedContactBundle
import com.km.model.IdentityId
// p4/R-8.a: `RelayDataCodec` vive en `:km-transport`, no aqui. Solo se usa su
// constante de forma (`IDENTITY_ID_HEX`); el codec completo no se duplica.
import com.km.transport.RelayDataCodec
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

    /**
     * Registra el bundle VERIFICADO de un contacto.
     *
     * ## QUE CAMBIA RESPECTO A [registerFromContactBundle]
     *
     * Aquella toma un `Map` crudo y exige que la clave derive el `identityId`
     * que se le pase —una identidad de IDENTIDAD—. Esta recibe un
     * [VerifiedContactBundle], que por construccion ya paso por
     * `ContactBundle.decodeAndVerify`, y guarda la clave de FIRMA del
     * DISPOSITIVO bajo la identidad de la RAIZ que lo firma.
     *
     * ## POR QUE ESO NO ES UNA INCONSISTENCIA
     *
     * Porque son dos dominios de derivacion distintos y ambos correctos:
     *
     *  - `registerEncoded` guarda la clave de IDENTIDAD, de la que deriva el
     *    `identityId` del cable por `KmIds.identityId`.
     *  - `registerFromVerifiedBundle` guarda la clave de FIRMA DE DISPOSITIVO,
     *    de la que deriva un `deviceId` por `KmIds.deviceId` (otro dominio), y
     *    la indexa por la RAIZ.
     *
     * La comprobacion de coherencia de [register] NO se aplica aqui a proposito:
     * exigir que la clave del dispositivo derive la identidad de la raiz seria
     * exigir que dos claves distintas derivaran el mismo identificador. El
     * vínculo que si importa —que el bundle FIRMA y que su `subjectSigningKey`
     * coincide con la del roster firmado— ya lo garantiza el verificador de
     * km-core antes de que este metodo exista.
     *
     * @return la identidad de la raiz, o `null` si el bundle no trae
     *   `identityRoot`. Un `null` aqui significa "no se guardo nada", no
     *   "se guardo sin clave".
     */
    fun registerFromVerifiedBundle(bundle: VerifiedContactBundle): IdentityId? {
        val root = runCatching { IdentityId(KmIds.identityId(bundle.identityRoot).toHex()) }.getOrNull()
            ?: return null
        keys[root.value] = bundle.subjectSigningKey.copyOf()
        return root
    }

    /**
     * La MISMA operacion, entrando por el texto del PORTADOR.
     *
     * Es la puerta que usa la TUI: el usuario pega un `.km` y lo que se guarda
     * es lo que el verificador dijo, nunca lo que el texto parecia decir.
     *
     * ## POR QUE YA NO USA `decodeAndVerify`
     *
     * Porque el portador real —el que produce Android y el que produce
     * [com.km.identity.SelfBundleFactory]— va COMPRIMIDO y en ISO-8859-1.
     * `decodeAndVerify` espera base64url sin comprimir: era el defecto de
     * interoperabilidad de G1. La lectura pasa por el punto unico compartido.
     *
     * @throws com.km.identity.Kce.KceException si el portador no se descomprime.
     * @throws com.km.identity.KmIdVerificationException si el sobre no verifica.
     */
    fun registerFromBundleText(text: String): IdentityId? =
        registerFromVerifiedBundle(com.km.identity.CarrierCodec.decode(text))

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
        // Un peers.json previo con permisos laxos se corrige al leerlo. Son
        // claves publicas, pero el fichero vive junto al almacen cifrado y no
        // hay motivo para dejarlo legible por otros usuarios.
        OwnerPermissions.DEFAULT.secureFile(file)
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

    /** Si este directorio tiene donde persistir. Un directorio sin ruta es solo memoria. */
    fun persistent(): Boolean = path != null

    /** Escribe el directorio en [path], si lo tiene. Un directorio sin ruta no se guarda. */
    fun save(): Boolean {        val file = path ?: return false
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
            file.toAbsolutePath().parent?.let {
                Files.createDirectories(it)
                OwnerPermissions.DEFAULT.secureDirectory(it)
            }
            Files.writeString(file, document.toString(), StandardCharsets.UTF_8)
            OwnerPermissions.DEFAULT.secureFile(file.toAbsolutePath())
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

    /** Hex de un `ByteArray`. */
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

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