package com.km.desktop

import com.km.identity.LocalDeviceKeyStore
import com.km.identity.LocalDeviceKeys
import java.nio.file.Path

/**
 * El material del dispositivo, guardado DENTRO del almacen cifrado de identidad.
 *
 * ## POR QUE NO UN FICHERO APARTE
 *
 * Porque es material privado de sesion y merece el mismo cifrado en reposo
 * (Argon2id + AES-256-GCM) y la misma frase de paso que la identidad. Un fichero
 * hermano seria una segunda cosa que proteger, que perder y que puede quedar
 * desincronizada de la identidad a la que pertenece.
 *
 * ## EL ADAPTADOR ES FINO A PROPOSITO
 *
 * No genera claves ni las valida: eso es [com.km.identity.LocalDeviceKeyFactory]
 * y [com.km.identity.LocalDeviceKeyCodec]. Aqui solo se traduce "guarda esto" a
 * "reescribe el contenedor cifrado con esta passphrase", que es lo que ya sabe
 * hacer [IdentityKeystore].
 *
 * ## LA PHRASE VIVE EN MEMORIA
 *
 * Se copia al construir para que el llamante pueda borrar la suya. La copia se
 * limpia en [clear]; no hay un finalizer: cerrar el proceso limpia la memoria,
 * y depender de un finalizer para secretos es peor que no tenerlo.
 */
class KeystoreDeviceKeyStore(
    private val path: Path,
    passphrase: CharArray,
) : LocalDeviceKeyStore {

    private val phrase: CharArray = passphrase.copyOf()

    override fun load(): LocalDeviceKeys? = IdentityKeystore.loadDeviceKeys(path, phrase)

    override fun save(keys: LocalDeviceKeys) {
        IdentityKeystore.saveDeviceKeys(path, phrase, keys)
    }

    /** Borra la copia de la frase de paso. */
    fun clear() {
        phrase.fill('\u0000')
    }
}
