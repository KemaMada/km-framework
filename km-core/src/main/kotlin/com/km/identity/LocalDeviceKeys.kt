package com.km.identity

import com.km.crypto.X25519
import com.km.crypto.X25519KeyPair
import com.km.crypto.provider.BcX25519
import com.km.messaging.LocalInitiatorKeys
import com.km.messaging.LocalResponderKeys

/**
 * El material PRIVADO del dispositivo local: lo que X3DH necesita de verdad.
 *
 * ## QUE ES
 *
 * `deviceId` (derivado de la clave de FIRMA), la clave de ACUERDO (`IK`), el
 * signed prekey (`SPK`) y los one-time prekeys (`OPK`). Es material de SESION
 * del dispositivo, no la identidad del usuario: la identidad dura toda la vida
 * de la cuenta; esto se genera una vez por dispositivo y se guarda.
 *
 * ## POR QUE VIVE EN km-core
 *
 * Porque el escritorio necesitaba exactamente lo mismo que la app y no lo tenia.
 * Duplicarlo alli habria significado dos generadores de claves, dos formatos y
 * dos formas de marcar un OPK como gastado. Aqui se GENERA con las primitivas
 * del framework (`X25519`), se serializa con [LocalDeviceKeyCodec] y cada
 * plataforma solo aporta su [LocalDeviceKeyStore].
 *
 * ## LA ASIMETRIA QUE ESTE TIPO MANTIENE
 *
 * `deviceId` se deriva de la clave de FIRMA; `agreementKey` es una clave de
 * ACUERDO. No se intercambian: X3DH rechaza una clave de firma donde espera una
 * de acuerdo, y el fallo aparece como una excepcion de longitud.
 */
class LocalDeviceKeys(
    /** `deviceId` derivado de la clave de firma del dispositivo. */
    val deviceId: ByteArray,
    /** La `IK` de X3DH. */
    val agreementKey: X25519KeyPair,
    /** El `SPK` que se publica en el bundle del contacto. */
    val signedPreKey: X25519KeyPair,
    /** Los OPKs emitidos, por `keyId`. */
    val oneTimePreKeys: Map<Long, X25519KeyPair> = emptyMap(),
) {

    /** Material de INICIAR, en el tipo del framework. */
    fun initiatorKeys(): LocalInitiatorKeys = LocalInitiatorKeys(deviceId, agreementKey)

    /**
     * Material de RESPONDER con el OPK [oneTimePreKeyId], o sin `DH4` si `null`.
     *
     * Un `keyId` que no esta NO se degrada en silencio a "sin DH4": eso
     * cambiaria el `SK` derivado sin que nadie lo anuncie, y el frame no
     * abriria. Se entrega `null` explicito.
     */
    fun responderKeys(oneTimePreKeyId: Long?): LocalResponderKeys = LocalResponderKeys(
        deviceId = deviceId,
        agreementKey = agreementKey,
        signedPreKey = signedPreKey,
        oneTimePreKey = oneTimePreKeyId?.let { oneTimePreKeys[it] },
        oneTimePreKeyId = oneTimePreKeyId,
    )

    /**
     * Este material SIN el OPK [keyId].
     *
     * Un OPK que se vuelve a ofrecer es material reutilizado: el que lo consumio
     * podria repetir `DH4` con la misma clave. El framework DEVUELVE que OPK se
     * gasto (`consumedOneTimePreKeyId`) y no lo marca: marcarlo y persistirlo es
     * de quien guarda el material, y eso es [LocalDeviceKeyStore.consumeOneTimePreKey].
     */
    fun withoutOneTimePreKey(keyId: Long?): LocalDeviceKeys =
        if (keyId == null || !oneTimePreKeys.containsKey(keyId)) this
        else LocalDeviceKeys(deviceId, agreementKey, signedPreKey, oneTimePreKeys - keyId)

    override fun toString(): String =
        "LocalDeviceKeys(<${deviceId.size}B deviceId, ${oneTimePreKeys.size} OPK)"
}

/**
 * Genera material local a partir de la clave de FIRMA, con las primitivas del framework.
 *
 * No hay aleatoriedad propia ni derivacion propia: `X25519` es la implementacion
 * inyectada (BouncyCastle por defecto) y `deviceId` sale de [KmIds.deviceId].
 */
class LocalDeviceKeyFactory(
    private val signingPublicKey: ByteArray,
    private val x25519: X25519 = BcX25519(),
    private val deviceId: ByteArray = KmIds.deviceId(signingPublicKey),
) {

    /** Material NUEVO con [oneTimePreKeyCount] OPKs. */
    fun generate(oneTimePreKeyCount: Int = 3): LocalDeviceKeys {
        require(oneTimePreKeyCount > 0) {
            "un dispositivo sin OPK no puede responder a un solo establecimiento"
        }
        val opks = (0 until oneTimePreKeyCount).associate { it.toLong() to x25519.generateKeyPair() }
        return LocalDeviceKeys(
            deviceId = deviceId,
            agreementKey = x25519.generateKeyPair(),
            signedPreKey = x25519.generateKeyPair(),
            oneTimePreKeys = opks,
        )
    }
}

/**
 * Donde vive el material local en cada plataforma.
 *
 * La interfaz existe por la misma razon que el resto de costuras del framework:
 * Android necesita un `Context` y el escritorio un almacen cifrado en disco, y
 * ninguno de los dos debe filtrarse a la politica.
 */
interface LocalDeviceKeyStore {

    /** El material guardado, o `null` si aun no hay. */
    fun load(): LocalDeviceKeys?

    /** Guarda [keys] completo. */
    fun save(keys: LocalDeviceKeys)

    /**
     * Marca como GASTADO el OPK [keyId] y persiste el resultado.
     *
     * @return el material ya sin ese OPK, o `null` si no habia nada guardado.
     *   Volver a consumir el mismo `keyId` es idempotente.
     */
    fun consumeOneTimePreKey(keyId: Long?): LocalDeviceKeys? {
        val actual = load() ?: return null
        val nuevo = actual.withoutOneTimePreKey(keyId)
        if (nuevo !== actual) save(nuevo)
        return nuevo
    }
}

/** [LocalDeviceKeyStore] en memoria: pruebas y procesos sin almacen propio. */
class InMemoryLocalDeviceKeyStore(initial: LocalDeviceKeys? = null) : LocalDeviceKeyStore {
    private var text: String? = initial?.let { LocalDeviceKeyCodec.encode(it) }

    override fun load(): LocalDeviceKeys? = text?.let { LocalDeviceKeyCodec.decode(it) }

    override fun save(keys: LocalDeviceKeys) {
        text = LocalDeviceKeyCodec.encode(keys)
    }
}

/**
 * Serializacion y VERIFICACION del material local: `KM-LOCALKEYS-1`.
 *
 * ## EL FORMATO ES EL QUE YA EXISTE
 *
 * Una linea de version, el `deviceId`, dos pares `publica:privada` (acuerdo y
 * SPK) y una linea `opk:<keyId>:<publica>:<privada>` por OPK. Es el mismo texto
 * que escribia la app: un material guardado por ella se puede leer aqui, y
 * migrar no es un paso de este change.
 *
 * ## LA COMPROBACION AL LEER
 *
 * Si el llamante pasa la clave de FIRMA de su identidad, el `deviceId` guardado
 * tiene que derivar de ella ([KmIds.deviceId]). Dos locales que no coinciden son
 * dos dispositivos distintos, y seguir adelante con una mezcla de los dos seria
 * hablar con el mundo con la clave de otro.
 */
object LocalDeviceKeyCodec {

    /** Version del texto. Cualquier otra se rechaza. */
    const val VERSION: String = "KM-LOCALKEYS-1"

    /** El material falta, esta roto o no corresponde a la identidad. */
    const val CODE_CORRUPT: String = "LOCAL_KEYS_CORRUPT"

    /** El `deviceId` guardado no deriva de la clave de firma de la identidad. */
    const val CODE_DEVICE_MISMATCH: String = "LOCAL_KEYS_DEVICE_MISMATCH"

    fun encode(keys: LocalDeviceKeys): String {
        val lineas = mutableListOf(VERSION, keys.deviceId.toHex())
        lineas += par(keys.agreementKey)
        lineas += par(keys.signedPreKey)
        keys.oneTimePreKeys.toSortedMap().forEach { (keyId, key) ->
            lineas += "opk:$keyId:${key.publicKey.toHex()}:${key.privateKey.toHex()}"
        }
        return lineas.joinToString("\n")
    }

    /**
     * @param expectedSigningPublicKey clave de FIRMA de la identidad local, si se
     *   conoce. Si se pasa, el `deviceId` se COMPRUEBA contra ella.
     */
    fun decode(text: String, expectedSigningPublicKey: ByteArray? = null): LocalDeviceKeys {
        val lineas = text.trim().split("\n").map { it.trim() }
        if (lineas.size < 4 || lineas[0] != VERSION) {
            throw LocalDeviceKeyException(
                CODE_CORRUPT,
                "formato de material local desconocido: ${lineas.size} lineas",
            )
        }
        val deviceId = unhex(lineas[1], "deviceId")
        val agreementKey = unpar(lineas[2], "agreementKey")
        val signedPreKey = unpar(lineas[3], "signedPreKey")
        val opks = lineas.drop(4).filter { it.isNotEmpty() }.associate { linea ->
            val partes = linea.split(":")
            if (partes.size != 4 || partes[0] != "opk") {
                throw LocalDeviceKeyException(CODE_CORRUPT, "linea de OPK mal formada")
            }
            partes[1].toLongOrNull()?.let { keyId ->
                keyId to X25519KeyPair(
                    publicKey = unhex(partes[2], "OPK publica"),
                    privateKey = unhex(partes[3], "OPK privada"),
                )
            } ?: throw LocalDeviceKeyException(CODE_CORRUPT, "keyId de OPK no numerico")
        }
        if (expectedSigningPublicKey != null) {
            val esperado = KmIds.deviceId(expectedSigningPublicKey)
            if (!esperado.contentEquals(deviceId)) {
                throw LocalDeviceKeyException(
                    CODE_DEVICE_MISMATCH,
                    "el deviceId guardado no deriva de la clave de firma de la identidad",
                )
            }
        }
        return LocalDeviceKeys(deviceId, agreementKey, signedPreKey, opks)
    }

    private fun par(key: X25519KeyPair): String = "${key.publicKey.toHex()}:${key.privateKey.toHex()}"

    private fun unpar(linea: String, que: String): X25519KeyPair {
        val partes = linea.split(":")
        if (partes.size != 2) throw LocalDeviceKeyException(CODE_CORRUPT, "$que mal formado")
        return X25519KeyPair(
            publicKey = unhex(partes[0], que),
            privateKey = unhex(partes[1], que),
        )
    }

    private fun unhex(texto: String, que: String): ByteArray {
        if (texto.length % 2 != 0) {
            throw LocalDeviceKeyException(CODE_CORRUPT, "$que no es hex de longitud par")
        }
        return ByteArray(texto.length / 2) { i ->
            val alto = Character.digit(texto[i * 2], 16)
            val bajo = Character.digit(texto[i * 2 + 1], 16)
            if (alto < 0 || bajo < 0) throw LocalDeviceKeyException(CODE_CORRUPT, "$que no es hex")
            ((alto shl 4) or bajo).toByte()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/** Rechazo del material local, con CODIGO ESTABLE. */
class LocalDeviceKeyException(val code: String, message: String) : Exception(message)
