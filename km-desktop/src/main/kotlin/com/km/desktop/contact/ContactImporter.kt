package com.km.desktop.contact

import com.km.identity.ContactBundle
import com.km.identity.KmIdVerificationException
import com.km.identity.KmIds
import com.km.identity.VerifiedContactBundle
import com.km.model.IdentityId

/**
 * Un contacto ya VERIFICADO, y el texto del que salio.
 *
 * ## POR QUE SE GUARDA EL TEXTO Y NO EL BUNDLE
 *
 * Un `VerifiedContactBundle` no se puede reconstruir desde sus accesores: solo
 * existe pasando por `ContactBundle.decodeAndVerify`. Guardar el sobre original
 * —el `.kmc`, que es lo que el usuario pega— permite **reverificar** al leer
 * del disco, en vez de confiar en lo que se guardo. Un contacto que sale del
 * disco sin pasar por el verificador seria material criptografico sin
 * verificar, que es justo lo que el tipo existe para impedir.
 *
 * ## POR QUE LA CLAVE DE FIRMA SE GUARDA EN HEX Y NO EN BYTES
 *
 * Porque el archivo de peers es JSON y porque es lo que se muestra. El valor
 * tiene que volver a `ByteArray` para entrar en `peerPublicKeyResolver`, y esa
 * conversion es explicita y falla en voz alta si el hex esta roto: un
 * `runCatching { hex }` que devolviera `null` en silencio AutorIZARIA al
 * contacto sin clave, que es peor que no autorizar a nadie.
 */
data class VerifiedContact(
    /** Identidad de la RAIZ que firma el bundle. Es la clave de ruteo. */
    val identityRoot: IdentityId,
    /** `deviceId` del dispositivo que describe el bundle. */
    val subjectDeviceId: String,
    /**
     * Clave de FIRMA del dispositivo del contacto, en hex.
     *
     * Sale del bundle VERIFICADO y es la que permite autenticar los `ACK` que
     * envie ese contacto. Sin ella, ningun `ACK` se procesa: la firma no se
     * puede comprobar y el motor lo dice.
     */
    val subjectSigningKey: String,
    /** El sobre tal cual se recibio. Se reverifica al cargarlo. */
    val bundleText: String,
    /** Nombre que el usuario le puso. No forma parte del bundle. */
    val displayName: String = "",
) {
    /** Huella corta para mostrar y comparar, sin volcar el bundle. */
    val fingerprint: String get() = if (identityRoot.value.length <= 16) {
        identityRoot.value
    } else {
        identityRoot.value.take(16) + "…"
    }

    override fun toString(): String = "VerifiedContact($fingerprint)"
}

/**
 * LA ENTRADA UNICA de un contacto al escritorio.
 *
 * ## POR QUE ES UNA FUNCION Y NO UN OBJETO CON ESTADO
 *
 * Importar es verificar y SOLO verificar. No hay cache, no hay "ya lo
 * importaste", no hay camino alternativo. Un importador con estado es un
 * importador que un dia tiene un atajo, y el atajo es donde entra un bundle sin
 * verificar.
 *
 * ## POR QUE `decodeAndVerify` Y NO `decode` SEGUIDO DE VERIFICAR
 *
 * Porque `decodeAndVerify` es el punto de entrada que km-core declara como
 * UNICO para clientes, y hace las dos cosas sin dejar hueco entre medias. Un
 * importador que descodificara por su cuenta tendria el `verifyBundle` en una
 * linea siguiente, y esa linea es la que un dia se comenta.
 */
object ContactImporter {

    /**
     * Importa el sobre [text] —un `.kmc` o el contenido de un QR—.
     *
     * @param expectedIdentityRoot raiz que se espera, o `null` para aceptar
     *   cualquiera. Pasar `null` es legitimo al importar el primer contacto; lo
     *   que no es legitimo es decidir mas tarde si el bundle "parecia" el que
     *   se buscaba.
     * @throws IllegalArgumentException si el sobre esta vacio.
     * @throws KmIdVerificationException si el bundle no verifica.
     * @throws com.km.identity.KceEnvelope.KceException si el sobre esta malformado.
     */
    fun import(text: String, expectedIdentityRoot: ByteArray? = null): VerifiedContact {
        require(text.isNotBlank()) { "el sobre del contacto esta vacio" }

        val bundle: VerifiedContactBundle = ContactBundle.decodeAndVerify(text, expectedIdentityRoot)

        // `bundle.identityRoot` es la clave publica de la raiz, NO su
        // identificador: el identificador sale de ella con `KmIds.identityId`,
        // que es KM-ID-0001 seccion 6. Confundir las dos cosas daria una clave de
        // ruteo que nadie mas puede derivar.
        return VerifiedContact(
            identityRoot = IdentityId(KmIds.identityId(bundle.identityRoot).toHex()),
            subjectDeviceId = bundle.subjectDeviceId.toHex(),
            subjectSigningKey = bundle.subjectSigningKey.toHex(),
            bundleText = text.trim(),
        )
    }
}

/**
 * El registro de contactos del escritorio.
 *
 * Interfaz, no clase concreta: lo que se decide aqui es la POLITICA —reverificar
 * al leer, deduplicar por raiz, no mezclar contactos con claves sueltas— y esa
 * politica es pura y testeable sin escritorio.
 *
 * ## POR QUE DEDUPLICA POR RAIZ Y NO POR DISPOSITIVO
 *
 * Porque la granularidad de una conversacion es la RAIZ, no el dispositivo: dos
 * dispositivos de la misma raiz dan la misma clave de ruteo, que es la que
 * gobierna el `SecureSessionRegistry` y el `PeerTransportBinding`. Deduplicar
 * por dispositivo daria dos conversaciones con el mismo interlocutor.
 */
interface ContactBook {
    /** Contactos guardados, en orden de insercion. */
    fun all(): List<VerifiedContact>

    /** El contacto de [identityRoot], o `null`. */
    fun find(identityRoot: IdentityId): VerifiedContact?

    /**
     * Guarda [contact], REVERIFICANDO su sobre.
     *
     * @throws KmIdVerificationException si el sobre guardado ya no verifica. Un
     *   contacto guardado que no verifica sale del almacen con una excepcion, no
     *   como si fuera valido.
     */
    fun upsert(contact: VerifiedContact)

    fun remove(identityRoot: IdentityId)
}

/** [ContactBook] en memoria. */
class InMemoryContactBook : ContactBook {

    private val porRaiz = LinkedHashMap<String, VerifiedContact>()

    override fun all(): List<VerifiedContact> = porRaiz.values.toList()

    override fun find(identityRoot: IdentityId): VerifiedContact? = porRaiz[identityRoot.value]

    override fun upsert(contact: VerifiedContact) {
        // Se reverifica lo que se guarda: el objeto que llega puede venir de un
        // almacen anterior y su texto haber cambiado por debajo.
        val verificado = ContactImporter.import(contact.bundleText)
        porRaiz[verificado.identityRoot.value] = contact.copy(
            identityRoot = verificado.identityRoot,
            subjectDeviceId = verificado.subjectDeviceId,
            subjectSigningKey = verificado.subjectSigningKey,
        )
    }

    override fun remove(identityRoot: IdentityId) {
        porRaiz.remove(identityRoot.value)
    }
}

/** Hex de un `ByteArray`, sin dependencias. */
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }