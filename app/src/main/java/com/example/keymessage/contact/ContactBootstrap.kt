package com.example.keymessage.contact

import com.example.keymessage.util.KMLog
import com.km.transport.SignalOutcome
import com.km.transport.contact.ContactLogLevel
import com.km.transport.contact.ContactLogger

/**
 * La SECUENCIA del emisor: portador propio -> SIGNAL -> sesion y PREKEY.
 *
 * ## QUE SE MUDO AL FRAMEWORK
 *
 * La clase entera, a `com.km.transport.contact.ContactExchangeBootstrap`: el
 * escritorio necesita exactamente el mismo orden y el mismo retry acotado, y dos
 * secuencias son dos oportunidades de que una se quede sin el reenvio tras la
 * re-autenticacion (#398). Aqui queda la API que usa [com.example.keymessage.AppContainer].
 *
 * ## EL ORDEN ES LA GARANTIA
 *
 * Publicar el bundle PRIMERO le da al receptor la posibilidad de registrar al
 * emisor antes de que llegue el PREKEY. Si el intercambio falla, NO hay sesion:
 * una sesion abierta con un PREKEY que el otro no puede aceptar parece viva y no
 * envia nada.
 *
 * ## UN REENVIO ACOTADO TRAS LA RE-AUTENTICACION
 *
 * SOLO ante `CONTACT_EXCHANGE_NOT_CONNECTED`, se espera la re-autenticacion y se
 * reenvia el MISMO portador UNA vez. Ni re-exportar, ni reabrir X3DH, ni segundo
 * PREKEY, ni reintentar PEER_NOT_FOUND/REFUSED.
 */
class ContactBootstrap(
    private val exportarPortador: () -> ByteArray,
    private val enviarPortador: (ByteArray) -> SignalOutcome,
    private val abrirSesionYEntregar: () -> Result<Unit>,
    private val esperarReauth: suspend (timeoutMs: Long) -> Boolean = { false },
    private val timeoutReauthMs: Long = DEFAULT_TIMEOUT_REAUTH_MS,
    private val maxReenvios: Int = DEFAULT_MAX_REENVIOS,
) {

    private val shared = com.km.transport.contact.ContactExchangeBootstrap(
        exportarPortador = exportarPortador,
        enviarPortador = enviarPortador,
        abrirSesionYEntregar = abrirSesionYEntregar,
        esperarReauth = esperarReauth,
        timeoutReauthMs = timeoutReauthMs,
        maxReenvios = maxReenvios,
        logger = ContactLogger { level, message -> log(level, message) },
    )

    /** Ejecuta la secuencia. Ver la clase compartida para el contrato completo. */
    suspend fun ejecutar(): Result<Unit> = shared.ejecutar()

    private fun log(level: ContactLogLevel, message: String) {
        KMLog.event(
            canalDe(message),
            message,
            when (level) {
                ContactLogLevel.INFO -> KMLog.Level.INFO
                ContactLogLevel.WARN -> KMLog.Level.WARN
                ContactLogLevel.ERROR -> KMLog.Level.ERROR
            },
        )
    }

    /** El canal sale del prefijo `[SIGNAL]`/`[SESSION]` que ya trae el mensaje. */
    private fun canalDe(message: String): String =
        if (message.startsWith("[SESSION]")) "[SESSION]" else "[SIGNAL]"

    companion object {
        /** El portador propio no se pudo exportar. */
        const val CODE_EXPORT_FAILED: String = com.km.transport.contact.ContactExchangeBootstrap.CODE_EXPORT_FAILED

        /** Plazo total de la espera de re-auth, en milisegundos. */
        const val DEFAULT_TIMEOUT_REAUTH_MS: Long =
            com.km.transport.contact.ContactExchangeBootstrap.DEFAULT_TIMEOUT_REAUTH_MS

        /** Reenvios extra: uno cubre la ventana de re-auth, dos son tormenta. */
        const val DEFAULT_MAX_REENVIOS: Int =
            com.km.transport.contact.ContactExchangeBootstrap.DEFAULT_MAX_REENVIOS
    }
}
