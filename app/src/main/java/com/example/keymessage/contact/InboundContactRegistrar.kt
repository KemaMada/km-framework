package com.example.keymessage.contact

import com.example.keymessage.util.KMLog
import com.km.identity.ContactImportException
import com.km.identity.ContactIntake
import com.km.identity.RejectionCodes
import com.km.identity.RegistrationResult
import com.km.model.IdentityId

/**
 * El contacto que llega por `SIGNAL/contact_exchange`, registrado por el camino unico.
 *
 * ## QUE HACE
 *
 * Delega en [ContactIntake] (km-core): verifica el bundle, exige que su RAIZ sea
 * el `from` del intercambio ([RejectionCodes.BUNDLE_ROOT_MISMATCH]) y lo guarda
 * con el registro compartido. Un bundle que no verifica no entra, igual que en
 * la importacion manual.
 *
 * ## POR QUE LA IDENTIDAD LOCAL ES UN PARAMETRO
 *
 * Importar un contacto es el primer paso para anunciarse, y sin identidad local
 * no hay bundle propio. [ContactIntake] falla con
 * [RejectionCodes.MISSING_LOCAL_IDENTITY] ANTES de escribir. En la app la
 * identidad siempre esta (se abre antes que la red), pero el contrato se cumple
 * igual y lo comprueba el framework.
 *
 * ## NO ABRE SESIONES
 *
 * Registrar no es establecer. El PREKEY retenido se drena por fuera, con
 * [ContactExchangeCoordinator.onContactoRegistrado].
 */
class InboundContactRegistrar(
    private val contactBook: ContactBook,
    private val localIdentity: () -> IdentityId? = { null },
) {

    private val intake = ContactIntake(contactBook) { localIdentity() }

    /** Registra [bundleText] si [from] no estaba ya registrado. `null` si se rechazo. */
    fun registerIfUnknown(from: IdentityId, bundleText: String): VerifiedContact? =
        register(from, bundleText).contactOrNull

    /**
     * Registra [bundleText] y dice POR QUE si no puede.
     *
     * No lanza: corre en el hilo de recepcion y un bundle raro es un rechazo, no
     * un fallo del motor.
     */
    fun register(from: IdentityId, bundleText: String): RegistrationResult = try {
        when (val resultado = intake.inbound(from, bundleText)) {
            is RegistrationResult.Accepted -> {
                KMLog.event(
                    "[CONTACT]",
                    "auto-registrado root=${KMLog.truncar(resultado.contact.identityRoot.value, 16)}",
                )
                resultado
            }
            is RegistrationResult.Rejected -> rechazado(resultado.code, from)
        }
    } catch (e: ContactImportException) {
        // `BUNDLE_ROOT_MISMATCH` tiene su propio log: es el unico rechazo que
        // distingue un bundle AJENO de un bundle roto, y el receptor (o quien lo
        // vigila) tiene que poder verlo.
        if (e.code == RejectionCodes.BUNDLE_ROOT_MISMATCH) {
            KMLog.event(
                "[CONTACT]",
                "auto-registro rechazado codigo=${e.code} emisor=${KMLog.truncar(from.value, 16)}",
                KMLog.Level.WARN,
            )
        } else {
            KMLog.event(
                "[CONTACT]",
                "auto-registro rechazado codigo=${e.code}",
                KMLog.Level.WARN,
            )
        }
        RegistrationResult.Rejected(e.code)
    } catch (e: Exception) {
        rechazado(rejectionCodeOf(e), from)
    }

    private fun rechazado(code: String, from: IdentityId): RegistrationResult {
        KMLog.event(
            "[CONTACT]",
            "auto-registro rechazado codigo=$code emisor=${KMLog.truncar(from.value, 16)}",
            KMLog.Level.WARN,
        )
        return RegistrationResult.Rejected(code)
    }
}
