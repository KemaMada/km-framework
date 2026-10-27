package com.km.transport.contact

import com.km.identity.ContactImportException
import com.km.identity.ContactIntake
import com.km.identity.ContactRegistry
import com.km.identity.RegistrationResult
import com.km.identity.RejectionCodes
import com.km.identity.VerifiedContact
import com.km.model.IdentityId

/**
 * El contacto que LLEGA por senalizacion, registrado por el camino unico.
 *
 * ## QUE HACE
 *
 * Cuando llega un `SIGNAL/contact_exchange` de un par que no esta en el
 * registro, el receptor puede agregarlo automaticamente SI cuenta con su bundle
 * verificado. El registro pasa SIEMPRE por [ContactIntake], de modo que un bundle
 * que no verifica no entra, igual que en la importacion manual.
 *
 * ## Y SI LA RAIZ NO ES LA DEL EMISOR, NO HAY CONTACTO
 *
 * El `from` lo autentica el rele contra la sesion; el bundle lo firma su raiz.
 * Si no coinciden, el bundle es de OTRO: [RejectionCodes.BUNDLE_ROOT_MISMATCH] y
 * ningun contacto. Con `null` no se podria distinguir un bundle corrupto de un
 * intento de suplantar a un tercero, y los dos merecen codigos distintos.
 *
 * ## NO ABRE SESIONES
 *
 * Registrar no es establecer. El PREKEY retenido se drena por fuera, con
 * [ContactExchangeCoordinator.onContactoRegistrado].
 */
class InboundContactRegistrar(
    private val registry: ContactRegistry,
    private val intake: ContactIntake,
    private val logger: ContactLogger = ContactLogger.NONE,
) {

    /** Registra [bundleText] si [from] no estaba ya registrado. `null` si se rechazo. */
    fun registerIfUnknown(from: IdentityId, bundleText: String): VerifiedContact? =
        register(from, bundleText).contactOrNull

    /**
     * Registra [bundleText] y dice POR QUE si no puede.
     *
     * No lanza: esta clase corre en el hilo de recepcion, y un bundle raro es un
     * rechazo, no un fallo del motor.
     */
    fun register(from: IdentityId, bundleText: String): RegistrationResult = try {
        val resultado = intake.inbound(from, bundleText)
        when (resultado) {
            is RegistrationResult.Accepted -> {
                if (resultado.isNew) {
                    logger.log(
                        ContactLogLevel.INFO,
                        "[CONTACT] auto-registrado root=${resultado.contact.identityRoot.value.take(16)}",
                    )
                }
                resultado
            }
            is RegistrationResult.Rejected -> {
                logger.log(
                    ContactLogLevel.WARN,
                    "[CONTACT] auto-registro rechazado codigo=${resultado.code} emisor=${from.value.take(16)}",
                )
                resultado
            }
        }
    } catch (e: ContactImportException) {
        logger.log(
            ContactLogLevel.WARN,
            "[CONTACT] auto-registro rechazado codigo=${e.code} emisor=${from.value.take(16)}",
        )
        RegistrationResult.Rejected(e.code)
    } catch (e: Exception) {
        val code = RejectionCodes.of(e)
        logger.log(
            ContactLogLevel.WARN,
            "[CONTACT] auto-registro rechazado codigo=$code emisor=${from.value.take(16)}",
        )
        RegistrationResult.Rejected(code)
    }
}
