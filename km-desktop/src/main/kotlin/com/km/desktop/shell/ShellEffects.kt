package com.km.desktop.shell

import com.km.desktop.Session
import com.km.desktop.client.RecoveryService
import com.km.desktop.ui.ContactRow
import com.km.desktop.ui.EffectResult
import com.km.desktop.ui.LogLevel
import com.km.desktop.ui.MessageDirection
import com.km.desktop.ui.MessageRow
import com.km.desktop.ui.Presence
import com.km.desktop.ui.ShellEffect
import com.km.identity.ContactImportException
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageState
import com.km.transport.contact.ContactExchangeException

/**
 * Ejecuta los [ShellEffect] contra los servicios de G1.
 *
 * ## ESTA CLASE ES EL UNICO SITIO CON IO DEL BUCLE
 *
 * Corre en UN hilo de trabajo, nunca en el hilo de pintura. Cada efecto devuelve
 * un [EffectResult] (o `null` si el efecto se resuelve con eventos del bus, como
 * `Announce`) y el bucle lo encola como accion. Ni un `println`: lo que se ve es
 * el estado reducido.
 *
 * ## LOS FALLOS SON CODIGOS
 *
 * Toda excepcion se traduce a un codigo estable: el texto de una excepcion no es
 * un contrato y puede arrastrar un payload. El detalle, si hace falta, va al
 * log por el canal de siempre.
 *
 * ## LO QUE NO HACE
 *
 * No decide navegacion, no pinta y no toca el terminal. Si aqui hubiera un
 * `when(state.screen)`, la logica estaria en dos sitios.
 */
class ShellEffects(
    private val session: Session,
    /** Escribe en el terminal de forma controlada (OSC 52). Devuelve si se emitio. */
    private val copy: (String) -> Boolean,
    /** Aviso para el usuario cuando una copia NO es confirmable. */
    private val warn: (String) -> Unit = {},
) {

    /**
     * @return el resultado a encolar, o `null` si el efecto se resolvio con
     *   eventos del bus (o es local al bucle: `Quit`, `Redraw`).
     */
    fun execute(effect: ShellEffect): EffectResult? = when (effect) {
        is ShellEffect.LoadContacts -> loadContacts()
        is ShellEffect.LoadHistory -> loadHistory(effect.peer)
        is ShellEffect.ImportText -> importText(effect.text)
        is ShellEffect.ImportFile -> importFile(effect.path)
        is ShellEffect.ExportOwn -> exportOwn(effect.oneTimePreKeys)
        is ShellEffect.SaveCarrier -> saveCarrier(effect.carrier)
        is ShellEffect.CopyText -> copyText(effect.text, effect.what, effect.warn)
        is ShellEffect.Send -> send(effect.peer, effect.text)
        is ShellEffect.Resend -> resend(effect.messageId)
        is ShellEffect.Discard -> discard(effect.messageId)
        is ShellEffect.Announce -> announce(effect.peer)
        is ShellEffect.OpenSession -> openSession(effect.peer)
        ShellEffect.LoadPhrase -> loadPhrase()
        is ShellEffect.CheckPhrase -> checkPhrase(effect.input)
        is ShellEffect.RestoreIdentity -> restore(effect.words, effect.confirmation)
        is ShellEffect.ExportLogs -> exportLogs(effect.filter)
        is ShellEffect.CopyLogs -> copyLogs(effect.filter)
        ShellEffect.Paste -> EffectResult.Failed(
            "PASTE_UNSUPPORTED",
            "OSC 52 no permite LEER el portapapeles: pega con el atajo del terminal o usa --no-tui",
        )
        ShellEffect.Redraw, ShellEffect.Quit -> null
    }

    // ------------------------------------------------------------------
    // Contactos
    // ------------------------------------------------------------------

    private fun loadContacts(): EffectResult = try {
        val enLinea = session.onlinePeers()
        val sesiones = session.sessionPeers()
        val claves = session.peerKeys.known()
        val filas = session.contactService.all().map { contacto ->
            val ultimo = session.chatService.history(contacto.identityRoot).lastOrNull()
            ContactRow(
                identityId = contacto.identityRoot,
                fingerprint = contacto.fingerprint,
                displayName = contacto.displayName,
                hasSession = sesiones.contains(contacto.identityRoot),
                hasAckKey = claves.contains(contacto.identityRoot),
                presence = if (enLinea.contains(contacto.identityRoot)) {
                    Presence.RELAY_PRESENT
                } else {
                    Presence.UNKNOWN
                },
                lastMessage = ultimo?.let { String(it.payload, Charsets.UTF_8) },
                lastAt = ultimo?.timestamp,
            )
        }
        EffectResult.ContactsLoaded(filas)
    } catch (e: Exception) {
        EffectResult.ContactsFailed(codeOf(e, "CONTACTS_FAILED"))
    }

    private fun loadHistory(peer: IdentityId): EffectResult = try {
        val local = session.identity.identityId
        val filas = session.chatService.history(peer).map { mensaje ->
            fila(mensaje, local)
        }
        EffectResult.HistoryLoaded(peer, filas)
    } catch (e: Exception) {
        EffectResult.Failed(codeOf(e, "HISTORY_FAILED"))
    }

    private fun fila(mensaje: Message, local: IdentityId): MessageRow {
        val estado = session.chatService.stateOf(mensaje.messageId) ?: mensaje.state
        return MessageRow(
            messageId = mensaje.messageId,
            direction = if (mensaje.from == local) MessageDirection.OUT else MessageDirection.IN,
            at = mensaje.timestamp,
            text = String(mensaje.payload, Charsets.UTF_8),
            state = estado,
            failureCode = mensaje.failureCode?.name,
        )
    }

    private fun importText(text: String): EffectResult = try {
        val contacto = session.contactService.importText(text)
        EffectResult.Imported(row(contacto))
    } catch (e: ContactImportException) {
        EffectResult.ImportRejected(e.code)
    } catch (e: Exception) {
        EffectResult.ImportRejected(codeOf(e, "IMPORT_FAILED"))
    }

    private fun importFile(path: String): EffectResult = try {
        val contacto = session.contactService.importFile(java.nio.file.Paths.get(path))
        EffectResult.Imported(row(contacto))
    } catch (e: ContactImportException) {
        EffectResult.ImportRejected(e.code)
    } catch (e: Exception) {
        EffectResult.ImportRejected(codeOf(e, "IMPORT_FAILED"))
    }

    private fun row(contacto: com.km.identity.VerifiedContact): ContactRow = ContactRow(
        identityId = contacto.identityRoot,
        fingerprint = contacto.fingerprint,
        displayName = contacto.displayName,
        hasSession = session.sessionPeers().contains(contacto.identityRoot),
        hasAckKey = session.peerKeys.known().contains(contacto.identityRoot),
        presence = if (session.onlinePeers().contains(contacto.identityRoot)) {
            Presence.RELAY_PRESENT
        } else {
            Presence.UNKNOWN
        },
    )

    // ------------------------------------------------------------------
    // Portadores
    // ------------------------------------------------------------------

    private fun exportOwn(opk: Int): EffectResult = try {
        val exportado = session.contactService.exportOwn(opk)
        EffectResult.Exported(exportado.text, exportado.byteLength, exportado.oneTimePreKeyCount)
    } catch (e: ContactImportException) {
        EffectResult.Failed(e.code, e.message)
    } catch (e: Exception) {
        EffectResult.Failed(codeOf(e, "EXPORT_FAILED"), e.message)
    }

    private fun saveCarrier(carrier: String): EffectResult = try {
        EffectResult.Saved(session.exportService.saveCarrier(carrier).toString())
    } catch (e: Exception) {
        EffectResult.Failed(codeOf(e, "SAVE_FAILED"), e.message)
    }

    private fun copyText(text: String, what: String, warnSensitive: Boolean): EffectResult {
        if (warnSensitive) {
            warn("OSC 52 no es confirmable: el terminal puede ignorarlo. Usa tambien guardar .km")
        }
        return if (copy(text)) EffectResult.Copied(what) else EffectResult.Failed("CLIPBOARD_FAILED")
    }

    // ------------------------------------------------------------------
    // Mensajes
    // ------------------------------------------------------------------

    private fun send(peer: IdentityId, text: String): EffectResult =
        when (val resultado = session.chatService.send(peer, text)) {
            is com.km.desktop.client.ChatService.SendOutcome.Accepted -> {
                val at = session.chatService.history(peer)
                    .firstOrNull { it.messageId == resultado.messageId }?.timestamp
                    ?: 0L
                EffectResult.Sent(resultado.messageId, resultado.state, text, at)
            }
            is com.km.desktop.client.ChatService.SendOutcome.Failed ->
                EffectResult.SendRejected(resultado.code)
        }

    private fun resend(messageId: com.km.model.MessageId): EffectResult =
        session.chatService.resend(messageId).fold(
            onSuccess = { EffectResult.Resent(messageId) },
            onFailure = { EffectResult.Failed("RESEND_FAILED", it.message) },
        )

    private fun discard(messageId: com.km.model.MessageId): EffectResult =
        session.chatService.discard(messageId).fold(
            onSuccess = { EffectResult.Discarded(messageId) },
            onFailure = { EffectResult.Failed("DISCARD_FAILED", it.message) },
        )

    // ------------------------------------------------------------------
    // Senalizacion
    // ------------------------------------------------------------------

    private fun announce(peer: IdentityId): EffectResult? {
        val contacto = session.contactService.find(peer)
            ?: return EffectResult.Failed("NOT_A_CONTACT")
        return kotlinx.coroutines.runBlocking {
            session.contactService.announce(contacto)
        }.fold(
            // El servicio ya publico `Announced` y, si abrio sesion, `SessionOpened`.
            onSuccess = { null },
            onFailure = { fallo ->
                EffectResult.Failed(
                    (fallo as? ContactExchangeException)?.code ?: "CONTACT_EXCHANGE_FAILED",
                    fallo.message,
                )
            },
        )
    }

    private fun openSession(peer: IdentityId): EffectResult? {
        val contacto = session.contactService.find(peer)
            ?: return EffectResult.Failed("NOT_A_CONTACT")
        return session.contactService.openAndDeliver(contacto).fold(
            onSuccess = { null },
            onFailure = { EffectResult.Failed("SESSION_FAILED", it.message) },
        )
    }

    // ------------------------------------------------------------------
    // Frase
    // ------------------------------------------------------------------

    private fun loadPhrase(): EffectResult = try {
        EffectResult.PhraseLoaded(session.recovery.words())
    } catch (e: Exception) {
        EffectResult.Failed(codeOf(e, "PHRASE_FAILED"))
    }

    private fun checkPhrase(input: String): EffectResult {
        if (input.isBlank()) return EffectResult.PhraseChecked(0, checksumOk = false)
        val validacion = session.recovery.validate(input)
        return EffectResult.PhraseChecked(validacion.wordCount, validacion.checksumOk)
    }

    private fun restore(words: String, confirmation: String): EffectResult =
        when (val resultado = session.recovery.restore(words, confirmation)) {
            is RecoveryService.RestoreOutcome.Restored ->
                EffectResult.Restored(resultado.identityId)
            is RecoveryService.RestoreOutcome.Rejected ->
                EffectResult.RestoreRejected(resultado.code)
        }

    // ------------------------------------------------------------------
    // Logs
    // ------------------------------------------------------------------

    private fun exportLogs(filter: String?): EffectResult = try {
        val path = session.exportService.saveLogs(session.diagnostics.exportText(filter), filter)
        EffectResult.LogsExported(path.toString())
    } catch (e: Exception) {
        EffectResult.Failed(codeOf(e, "LOG_EXPORT_FAILED"), e.message)
    }

    private fun copyLogs(filter: String?): EffectResult =
        if (copy(session.diagnostics.exportLines(filter).joinToString("\n"))) {
            EffectResult.Copied("log")
        } else {
            EffectResult.Failed("CLIPBOARD_FAILED")
        }

    // ------------------------------------------------------------------

    private fun codeOf(e: Throwable, porDefecto: String): String =
        e::class.simpleName?.uppercase()?.takeIf { it.isNotBlank() } ?: porDefecto
}
