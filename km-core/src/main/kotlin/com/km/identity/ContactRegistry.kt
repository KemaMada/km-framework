package com.km.identity

/**
 * El CODIGO ESTABLE de un rechazo, sea cual sea el tipo de fallo.
 *
 * ## POR QUE UN MAPEO TOTAL Y NO UN CAST
 *
 * Porque un import tiene MAS finales que "la firma no cuadra": sobre vacio
 * (`IllegalArgumentException`), sobre malformado (`Kce.KceException`),
 * incumplimiento de esquema (`KmIdSchemaException`), fichero demasiado grande o
 * vacio ([CarrierFileCodec]) y material local ausente. Con un cast, los que no
 * traen codigo propio quedaban en `null` y la pantalla los pinta sin detalle:
 * el usuario pulsa IMPORTAR y no pasa NADA visible.
 *
 * Aqui el mapeo es EXPLICITO y total. El codigo de los que no traen uno se
 * deriva del TIPO de fallo, no del mensaje (que no forma parte del contrato).
 */
object RejectionCodes {

    /** El sobre no tiene contenido. */
    const val EMPTY_ENVELOPE: String = "EMPTY_ENVELOPE"

    /** El fichero supera [CarrierFileCodec.MAX_BYTES]. */
    const val FILE_TOO_LARGE: String = "FILE_TOO_LARGE"

    /** La raiz del bundle NO es el emisor del `contact_exchange`. */
    const val BUNDLE_ROOT_MISMATCH: String = "BUNDLE_ROOT_MISMATCH"

    /** No hay identidad local: no se puede importar ni anunciar, y no se escribe nada. */
    const val MISSING_LOCAL_IDENTITY: String = "MISSING_LOCAL_IDENTITY"

    /** Cualquier otro fallo de importacion. */
    const val IMPORT_FAILED: String = "IMPORT_FAILED"

    /**
     * El codigo estable de [failure].
     *
     * @param failure lo que lanzo el importador o el codec del portador.
     */
    fun of(failure: Throwable): String = when (failure) {
        is KmIdVerificationException -> failure.code
        is KmIdSchemaException -> failure.code
        is Kce.KceException -> failure.code
        is LocalDeviceKeyException -> failure.code
        is CarrierFileCodec.CarrierFileException -> when (failure.code) {
            CarrierFileCodec.CODE_TOO_LARGE -> FILE_TOO_LARGE
            CarrierFileCodec.CODE_EMPTY -> EMPTY_ENVELOPE
            else -> failure.code
        }
        is ContactImportException -> failure.code
        is IllegalArgumentException -> EMPTY_ENVELOPE
        else -> IMPORT_FAILED
    }
}

/** Un rechazo del registro de contactos, con CODIGO ESTABLE. */
class ContactImportException(val code: String, message: String) : Exception(message)

/** De donde llega un contacto: el usuario lo importa o llega por `contact_exchange`. */
enum class ContactImportSource {
    /** Pegado/fichero/QR: la raiz del bundle es la que vale, no hay `from` que exigir. */
    MANUAL,

    /** `SIGNAL/contact_exchange`: el `from` lo autentica el rele y tiene que ser la raiz. */
    INBOUND,
}

/** El resultado de intentar registrar un contacto. */
sealed class RegistrationResult {

    /** El par quedo registrado, o ya estaba. */
    data class Accepted(val contact: VerifiedContact, val isNew: Boolean) : RegistrationResult()

    /** No se registro. [code] es ESTABLE. */
    data class Rejected(val code: String) : RegistrationResult()

    /** El contacto, si lo hay. */
    val contactOrNull: VerifiedContact?
        get() = (this as? Accepted)?.contact
}

/**
 * El registro de contactos VERIFICADOS.
 *
 * Interfaz y no clase concreta: quien consume el registro necesita buscarlo,
 * listarlo, guardarlo y borrarlo, no saber DONDE se guarda. La implementacion de
 * cada cliente decide el almacen (memoria hoy en ambos), y la POLITICA de
 * verificacion vive en [ContactIntake], no en el almacen.
 *
 * La clave es la RAIZ: es la que firma el bundle y la que gobierna sesiones. Dos
 * dispositivos de la misma raiz son el mismo interlocutor; deduplicar por
 * cualquier otra cosa partiria una conversacion en dos.
 */
interface ContactRegistry {
    fun all(): List<VerifiedContact>
    fun find(identityRoot: com.km.model.IdentityId): VerifiedContact?
    fun upsert(contact: VerifiedContact)
    fun remove(identityRoot: com.km.model.IdentityId)
}

/**
 * [ContactRegistry] en memoria, thread-safe y sin flujo.
 *
 * El escritorio es un REPL de un hilo de UI y otro del motor: alcanza con
 * sincronizar las mutaciones y devolver copias. Un `StateFlow` habria metido
 * `kotlinx-coroutines` en km-core, que no la declara y no la necesita.
 *
 * ## REVERIFICA AL GUARDAR
 *
 * [upsert] no confia en el objeto que llega: vuelve a pasar su `bundleText` por
 * el punto unico ([CarrierCodec.decode]) antes de escribir. Un contacto
 * construido a mano o mutado en memoria no es material verificado.
 */
class InMemoryContactRegistry : ContactRegistry {

    private val porRaiz = LinkedHashMap<String, VerifiedContact>()
    private val candado = Any()

    override fun all(): List<VerifiedContact> = synchronized(candado) { porRaiz.values.toList() }

    override fun find(identityRoot: com.km.model.IdentityId): VerifiedContact? =
        synchronized(candado) { porRaiz[identityRoot.value] }

    override fun upsert(contact: VerifiedContact) {
        val bundle = CarrierCodec.decode(contact.bundleText)
        val root = com.km.model.IdentityId(KmIds.identityId(bundle.identityRoot).toHex())
        val guardado = contact.copy(
            identityRoot = root,
            subjectDeviceId = bundle.subjectDeviceId.toHex(),
            subjectSigningKey = bundle.subjectSigningKey.toHex(),
        )
        synchronized(candado) { porRaiz[root.value] = guardado }
    }

    override fun remove(identityRoot: com.km.model.IdentityId) {
        synchronized(candado) { porRaiz.remove(identityRoot.value) }
    }

    /** Cuantos contactos hay. */
    fun size(): Int = synchronized(candado) { porRaiz.size }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/**
 * La PUERTA de entrada de un contacto, con las dos politicas que importan.
 *
 * ## MANUAL ACEPTA LA RAIZ
 *
 * Un import manual (pegar un `.km`, abrir el fichero, leer el QR) no tiene un
 * `from` autenticado contra el que comparar: la raiz del bundle ES la
 * identidad del contacto. Si el usuario paso una raiz esperada, la comprueba
 * [CarrierCodec.decode] al verificar.
 *
 * ## INBOUND EXIGE `root == from`
 *
 * En un `contact_exchange` el `from` lo autentica el rele contra la sesion y el
 * bundle lo firma su raiz. Si no coinciden, el bundle es de OTRO: se rechaza con
 * [RejectionCodes.BUNDLE_ROOT_MISMATCH] y no se crea ningun contacto. Los dos
 * casos —bundle corrupto y bundle ajeno— se rechazan igual pero se DICEN
 * distinto: solo uno es un intento.
 *
 * ## SIN IDENTIDAD LOCAL NO SE ESCRIBE NADA
 *
 * Importar un contacto es el primer paso para anunciarse: sin identidad local no
 * hay bundle propio que publicar ni sesion que abrir. El fallo es
 * [RejectionCodes.MISSING_LOCAL_IDENTITY] y ocurre ANTES de tocar el registro:
 * cero escritura parcial.
 */
class ContactIntake(
    private val registry: ContactRegistry,
    /**
     * La identidad local, o `null` si aun no hay.
     *
     * Es una funcion y no un valor para que un proceso que crea la identidad
     * despues de construir el intake siga viendo la actual.
     */
    private val localIdentity: () -> com.km.model.IdentityId?,
) {

    /**
     * Importa [text] a mano.
     *
     * @param expectedRoot raiz que el usuario espera, o `null` para aceptar la
     *   que firme el bundle.
     * @throws ContactImportException con [RejectionCodes.MISSING_LOCAL_IDENTITY]
     *   o con el codigo del rechazo de verificacion/portador.
     */
    fun manual(text: String, expectedRoot: ByteArray? = null): RegistrationResult {
        return registrar(text, from = null, expectedRoot = expectedRoot)
    }

    /**
     * Registra el bundle que llego por `contact_exchange` de [from].
     *
     * @throws ContactImportException con [RejectionCodes.BUNDLE_ROOT_MISMATCH] si
     *   la raiz no es [from], o con el codigo del rechazo.
     */
    fun inbound(from: com.km.model.IdentityId, text: String): RegistrationResult =
        registrar(text, from = from, expectedRoot = null)

    private fun registrar(
        text: String,
        from: com.km.model.IdentityId?,
        expectedRoot: ByteArray?,
    ): RegistrationResult {
        if (localIdentity() == null) {
            throw ContactImportException(
                RejectionCodes.MISSING_LOCAL_IDENTITY,
                "no hay identidad local: no se importa ni se anuncia, y no se escribe nada",
            )
        }
        if (text.isBlank()) {
            throw ContactImportException(
                RejectionCodes.EMPTY_ENVELOPE,
                "el sobre del contacto esta vacio",
            )
        }

        val bundle = try {
            CarrierCodec.decode(text, expectedRoot)
        } catch (e: Exception) {
            throw ContactImportException(RejectionCodes.of(e), e.message ?: RejectionCodes.of(e))
        }

        // `bundle.identityRoot` es la clave publica de la raiz, NO su
        // identificador: el identificador sale de ella con `KmIds.identityId`.
        val root = com.km.model.IdentityId(KmIds.identityId(bundle.identityRoot).toHex())
        if (from != null && root.value != from.value) {
            throw ContactImportException(
                RejectionCodes.BUNDLE_ROOT_MISMATCH,
                "la raiz del bundle no es el emisor del intercambio",
            )
        }

        registry.find(root)?.let { return RegistrationResult.Accepted(it, isNew = false) }

        val contacto = VerifiedContact(
            identityRoot = root,
            subjectDeviceId = bundle.subjectDeviceId.toHex(),
            subjectSigningKey = bundle.subjectSigningKey.toHex(),
            bundleText = text,
        )
        registry.upsert(contacto)
        return RegistrationResult.Accepted(contacto, isNew = true)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/** Convierte [bundle] en el [VerifiedContact] que se guarda. */
fun verifiedContactOf(bundle: VerifiedContactBundle, text: String): VerifiedContact = VerifiedContact(
    identityRoot = com.km.model.IdentityId(KmIds.identityId(bundle.identityRoot).toHex()),
    subjectDeviceId = bundle.subjectDeviceId.toHex(),
    subjectSigningKey = bundle.subjectSigningKey.toHex(),
    bundleText = text,
)

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
