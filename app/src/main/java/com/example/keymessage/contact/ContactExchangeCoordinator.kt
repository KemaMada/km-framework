package com.example.keymessage.contact

import com.example.keymessage.util.KMLog
import com.km.model.IdentityId
import com.km.transport.contact.ContactLogLevel
import com.km.transport.contact.ContactLogger

/**
 * La retencion ACOTADA de un PREKEY de un par que todavia no es contacto.
 *
 * ## QUE SE MUDO AL FRAMEWORK
 *
 * La clase entera, a `com.km.transport.contact.ContactExchangeCoordinator`: el
 * escritorio necesita la misma carrera resuelta (PREKEY antes del bundle), y dos
 * implementaciones con dos TTL acaban con dos comportamientos. Aqui queda la API
 * que ya usan [com.example.keymessage.AppContainer] y sus tests.
 *
 * ## LOS LIMITES
 *
 * 60 s de TTL y 16 emisores: la ventana en la que el emisor sigue intentando, y
 * un tope para que un tercero no pueda hacer crecer el mapa. Al llenarse sale el
 * MAS ANTIGUO.
 *
 * ## EL RELOJ ES INYECTADO
 *
 * Un TTL probado con el reloj real es un test que tarda 60 s o que no existe.
 */
class ContactExchangeCoordinator(
    private val conocido: (IdentityId) -> Boolean,
    private val ttlMs: Long = TTL_MS,
    private val maxRetenidos: Int = MAX_RETENIDOS,
    private val maxFrameBytes: Int = MAX_FRAME_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val shared = com.km.transport.contact.ContactExchangeCoordinator(
        conocido = conocido,
        ttlMs = ttlMs,
        maxRetenidos = maxRetenidos,
        maxFrameBytes = maxFrameBytes,
        esPrekey = { wire -> com.km.frame.PrekeyFrame.esPrekey(wire) },
        now = now,
        logger = ContactLogger { level, message ->
            KMLog.event(
                if (message.startsWith("[CONTACT]")) "[CONTACT]" else "[SESSION]",
                message,
                when (level) {
                    ContactLogLevel.INFO -> KMLog.Level.INFO
                    ContactLogLevel.WARN -> KMLog.Level.WARN
                    ContactLogLevel.ERROR -> KMLog.Level.ERROR
                },
            )
        },
    )

    /** Un PREKEY entrante. `true` si se entrego en el acto. */
    fun onPrekey(
        from: IdentityId,
        wire: ByteArray,
        entregar: (IdentityId, ByteArray) -> Unit,
    ): Boolean = shared.onPrekey(from, wire, entregar)

    /** El emisor ya es contacto: drena lo retenido. */
    fun onContactoRegistrado(from: IdentityId, entregar: (IdentityId, ByteArray) -> Unit): Int =
        shared.onContactoRegistrado(from, entregar)

    /** Descarta lo retenido. */
    fun parar() = shared.parar()

    /** Cuantos emisores tienen un PREKEY retenido. */
    fun retenidos(): Int = shared.retenidos()

    companion object {
        /** La ventana de retencion. */
        const val TTL_MS: Long = com.km.transport.contact.ContactExchangeCoordinator.TTL_MS

        /** Cuantos emisores distintos se retienen a la vez. */
        const val MAX_RETENIDOS: Int = com.km.transport.contact.ContactExchangeCoordinator.MAX_RETENIDOS

        /** El frame retenido no pasa de 8 KiB. */
        const val MAX_FRAME_BYTES: Int = com.km.transport.contact.ContactExchangeCoordinator.MAX_FRAME_BYTES
    }
}
