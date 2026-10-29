package com.example.keymessage.identity

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.km.model.IdentityId

/**
 * El material PRIVADO del dispositivo local: el tipo COMPARTIDO de km-core.
 *
 * ## QUE SE MUDO AL FRAMEWORK
 *
 * La definicion del material, el generador (con las primitivas X25519 del
 * framework), el codec `KM-LOCALKEYS-1` y la interfaz de almacen. Estaban solo
 * aqui y el escritorio no tenia como fabricar su SPK/OPK sin duplicar
 * criptografia. El texto del codec es el MISMO que ya escribia la app, asi que
 * el material guardado se sigue leyendo sin migracion.
 *
 * ## LO QUE SE QUEDA
 *
 * [AndroidLocalKeyStore], que es la unica pieza que necesita un `Context`. La
 * politica —comprobar al leer que el `deviceId` derive de la clave de firma— vive
 * en el codec compartido y se aplica igual en las dos plataformas.
 */

/** El material local del dispositivo, en el vocabulario de la app. */
typealias LocalKeyMaterial = com.km.identity.LocalDeviceKeys

/** El generador de material, con `X25519` del framework. */
typealias LocalKeyFactory = com.km.identity.LocalDeviceKeyFactory

/** Donde viven las claves locales. */
typealias LocalKeyStore = com.km.identity.LocalDeviceKeyStore

/** Serializacion `KM-LOCALKEYS-1` y verificacion del `deviceId` al leer. */
typealias LocalKeyCodec = com.km.identity.LocalDeviceKeyCodec

/**
 * [LocalKeyStore] sobre `EncryptedSharedPreferences`.
 *
 * El mismo almacen cifrado que la identidad, por la misma razon: es material que
 * no puede estar en claro en reposo.
 */
class AndroidLocalKeyStore(context: Context) : LocalKeyStore {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "km_local_keys",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun load(): LocalKeyMaterial? = prefs.getString(KEY, null)?.let { LocalKeyCodec.decode(it) }

    override fun save(material: LocalKeyMaterial) {
        prefs.edit().putString(KEY, LocalKeyCodec.encode(material)).commit()
    }

    private companion object {
        const val KEY = "local_keys"
    }
}

/** [LocalKeyStore] en memoria: para pruebas y para procesos sin Android. */
typealias InMemoryLocalKeyStore = com.km.identity.InMemoryLocalDeviceKeyStore

/** El `IdentityId` de la RAIZ, que es como el framework enruta a ESTE dispositivo. */
fun localRootId(material: IdentityMaterial): IdentityId = material.identityId
