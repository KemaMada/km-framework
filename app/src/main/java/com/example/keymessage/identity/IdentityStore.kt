package com.example.keymessage.identity

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.identity.KmIds
import com.km.model.IdentityId
import com.example.keymessage.util.toHex

/**
 * La identidad guardada no se puede leer, y por que.
 *
 * Se lanza en vez de devolver basura porque un `IdentityId("")` o una semilla de
 * ceros son DOSValores que el resto del sistema no puede distinguir de una
 * identidad de verdad: el usuario veria su id en la pantalla de inicio y no
 * podria falar con nadie, sin ningun error. Un fallo de almacenamiento tiene que
 * PARAR, no continuar.
 */
class CorruptIdentityException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * 1.2 — Donde vive la clave de FIRMA de la app.
 *
 * Es una INTERFAZ, no una clase de Android, por dos razones: la implementacion
 * de [AndroidIdentityStore] necesita un `Context` y no se puede ejercitar en un
 * test de JVM, y la logica de lectura/escritura —que es donde puede estar el
 * fallo— esta en [IdentityCodec], que es pura y si se prueba.
 */
interface IdentityStore {
    /** La identidad guardada, o `null` si todavia no hay ninguna. */
    fun load(): IdentityMaterial?

    /** Guarda [material], sustituyendo lo que hubiera. */
    fun save(material: IdentityMaterial)

    /** Borra la identidad guardada. */
    fun clear()

    /**
     * 6.2 — Si el usuario ya ha visto (o aceptado ver) su frase de recuperacion.
     *
     * ## QUE ES Y QUE NO ES
     *
     * Es un booleano, y vive en el MISMO `km_identity.xml` que la semilla. No es
     * la frase: la frase no se guarda nunca, vive en memoria mientras se muestra.
     * Lo que se guarda es "ya te han mostrado esto", que es lo unico que hace
     * falta para el arranque.
     *
     * ## POR QUE `save()` LO PONE A `false`
     *
     * Porque cambiar de identidad cambia la frase. Si el flag sobreviviera, una
     * identidad restaurada llegaria al usuario sin ver su frase y sin el aviso
     * de exposicion, que es justo lo que el arranque tiene que garantizar.
     */
    fun phraseAcknowledged(): Boolean

    /** Marca (o desmarca) que el usuario ya vio su frase. */
    fun setPhraseAcknowledged(acknowledged: Boolean)
}

/**
 * 1.2 — Serializacion de la identidad, y su comprobacion.
 *
 * ## QUE SE PERSISTE
 *
 * La SEMILLA de 32 bytes y nada mas. La clave publica y el `identityId` se
 * pueden derivar de ella siempre, y guardar los tres es guardar tres cosas que
 * pueden dejar de cuadrar entre si.
 *
 * ## QUE SE COMPRUEBA AL LEER
 *
 * Que la semilla sea de 32 bytes, que la clave publica guardada sea la que
 * deriva de la semilla, y que el `identityId` sea el que deriva de esa clave
 * publica. Cualquiera de las tres cosas distinta significa que el almacen se
 * toco o se copio de otra identidad, y en los tres casos devolver el valor
 * guardado seria inventar una identidad.
 *
 * Formato: cuatro lineas — version, `identityId`, semilla y clave publica, las
 * dos ultimas en base64. Es texto plano y legible a proposito: un almacen
 * corrupto tiene que poder leerse para que el fallo diga QUE esta mal, y un
 * sobre binario solo permitiria decir "no se pudo descifrar".
 */
object IdentityCodec {

    private const val VERSION = "KM-IDENTITY-1"
    private const val SEED_BYTES = 32

    /** Serializa [material] a texto. */
    fun encode(material: IdentityMaterial): String {
        val seed = material.keyPair.privateKey
        require(seed.size == SEED_BYTES) { "la semilla debe tener $SEED_BYTES bytes, tiene ${seed.size}" }
        return listOf(
            VERSION,
            material.identityId.value,
            base64(seed),
            base64(material.keyPair.publicKey),
        ).joinToString("\n")
    }

    /**
     * Deserializa y VERIFICA.
     *
     * @throws CorruptIdentityException si el texto no tiene el formato, si la
     *   semilla no mide 32 bytes, o si lo guardado no cuadra con lo derivado.
     */
    fun decode(text: String, ed25519: Ed25519 = Ed25519Impl()): IdentityMaterial {
        val partes = text.trim().split("\n").map { it.trim() }
        if (partes.size != 4 || partes[0] != VERSION) {
            throw CorruptIdentityException(
                "formato de identidad desconocido: ${partes.size} lineas, version=${partes.firstOrNull()}",
            )
        }
        val identityIdText = partes[1]
        val seed = unbase64(partes[2], "semilla")
        val publicKey = unbase64(partes[3], "clave publica")

        if (seed.size != SEED_BYTES) {
            throw CorruptIdentityException("la semilla debe medir $SEED_BYTES bytes, mide ${seed.size}")
        }

        val derivado = ed25519.keyPairFromSeed(seed)
        if (!derivado.publicKey.contentEquals(publicKey)) {
            throw CorruptIdentityException(
                "la clave publica guardada no es la que deriva de la semilla guardada",
            )
        }
        val identityId = IdentityId(KmIds.identityId(derivado.publicKey).toHex())
        if (identityId.value != identityIdText) {
            throw CorruptIdentityException(
                "el identityId guardado no es el que deriva de la clave publica guardada",
            )
        }
        return IdentityMaterial(identityId, derivado)
    }

    private fun unbase64(texto: String, que: String): ByteArray = try {
        java.util.Base64.getDecoder().decode(texto)
    } catch (e: IllegalArgumentException) {
        throw CorruptIdentityException("el $que guardado no es base64 valido", e)
    }

    private fun base64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
}

/**
 * 1.2 — Implementacion de Android, sobre `EncryptedSharedPreferences`.
 *
 * La clave de FIRMA en reposo no puede vivir en preferencias en claro: quien
 * tenga acceso al fichero tendria la identidad, y con ella podria firmar como
 * este dispositivo. `EncryptedSharedPreferences` es lo que evita eso, y es
 * tambien lo que deja de funcionar si el `MasterKey` se invalida —motivo por el
 * que [IdentityStore.load] propaga el fallo en vez de regenerar una identidad en
 * silencio.
 */
class AndroidIdentityStore(
    context: Context,
    private val ed25519: Ed25519 = Ed25519Impl(),
    private val fileName: String = "km_identity",
) : IdentityStore {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        fileName,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun load(): IdentityMaterial? {
        val text = prefs.getString(KEY_BLOB, null) ?: return null
        return try {
            IdentityCodec.decode(text, ed25519)
        } catch (e: CorruptIdentityException) {
            throw e
        } catch (e: Exception) {
            // El almacen cifrado devuelve una excepcion PROPIA si la clave maestra
            // se invalido. Traducirla a CorruptIdentityException es lo que hace
            // que el llamante tenga un solo tipo de fallo que tratar.
            throw CorruptIdentityException("el almacen cifrado de identidad no se pudo abrir", e)
        }
    }

    override fun save(material: IdentityMaterial) {
        // Sincrono: la identidad tiene que quedar en disco ANTES de que el
        // llamante siga, porque si el proceso muere entre el `apply()` asincrono
        // y el flush, el siguiente arranque regenera otra identidad (y dos
        // dispositivos acabarian con claves distintas o, peor, reutilizando una
        // stale). `.commit()` bloquea hasta que el fichero esta escrito.
        //
        // El aviso de la frase se reinicia en la MISMA operacion: la identidad
        // que se acaba de guardar tiene una frase distinta de la que el usuario
        // vio, y darsela por vista seria mentira.
        prefs.edit()
            .putString(KEY_BLOB, IdentityCodec.encode(material))
            .putBoolean(KEY_PHRASE_ACK, false)
            .commit()
    }

    override fun clear() {
        prefs.edit().remove(KEY_BLOB).remove(KEY_PHRASE_ACK).commit()
    }

    override fun phraseAcknowledged(): Boolean = prefs.getBoolean(KEY_PHRASE_ACK, false)

    override fun setPhraseAcknowledged(acknowledged: Boolean) {
        // `commit()` por el mismo motivo que en `save()`: si el proceso muere
        // antes del flush, el usuario volveria a ver el aviso de exposicion.
        // Volver a verlo es molesto; que no aparezca nunca, no.
        prefs.edit().putBoolean(KEY_PHRASE_ACK, acknowledged).commit()
    }

    private companion object {
        const val KEY_BLOB = "identity"
        const val KEY_PHRASE_ACK = "phrase_ack"
    }
}

/**
 * 1.2 — Implementacion en memoria, para pruebas y para un proceso sin Android.
 *
 * Guarda el TEXTO, no el objeto: asi el round-trip pasa por [IdentityCodec] y
 * una corrupcion introducida a proposito se detecta igual que en Android.
 */
class InMemoryIdentityStore(
    private val ed25519: Ed25519 = Ed25519Impl(),
    initialText: String? = null,
    acknowledged: Boolean = false,
) : IdentityStore {

    @Volatile
    var text: String? = initialText
        private set

    /**
     * El aviso de la frase, con la MISMA invariante que el almacen de Android:
     * [save] lo pone a `false` porque la identidad nueva es otra frase.
     */
    @Volatile
    var acknowledged: Boolean = acknowledged
        private set

    override fun load(): IdentityMaterial? = text?.let { IdentityCodec.decode(it, ed25519) }

    override fun save(material: IdentityMaterial) {
        text = IdentityCodec.encode(material)
        acknowledged = false
    }

    override fun clear() {
        text = null
        acknowledged = false
    }

    override fun phraseAcknowledged(): Boolean = acknowledged

    override fun setPhraseAcknowledged(acknowledged: Boolean) {
        this.acknowledged = acknowledged
    }
}