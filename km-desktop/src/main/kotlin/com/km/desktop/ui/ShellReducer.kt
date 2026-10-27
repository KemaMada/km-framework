package com.km.desktop.ui

import com.km.desktop.client.ClientEvent
import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.model.MessageState

/**
 * El resultado de reducir una accion: el estado siguiente y lo que hay que hacer
 * fuera.
 *
 * Los efectos son una LISTA y no un solo elemento porque una transicion puede
 * necesitar dos pasos ordenados (importar y luego pedir historial). El bucle de
 * la Fase 3 los ejecuta en ese orden.
 */
data class Reduced(
    val state: ShellState,
    val effects: List<ShellEffect> = emptyList(),
)

/**
 * TRANSICIONES PURAS de la TUI. Cero IO, cero reloj, cero terminal.
 *
 * ## QUE GARANTIZA "PURO"
 *
 * Que [reduce] es una funcion de `(estado, accion)`. No lee ficheros, no abre
 * sockets, no mira `System.currentTimeMillis`, no escribe en `System.out`. Todo
 * lo que viene de fuera entra como [ShellAction.Event] o [ShellAction.EffectDone],
 * y todo lo que hay que hacer fuera sale como [ShellEffect]. Por eso la
 * navegacion, el foco, los estados por mensaje y las confirmaciones se prueban
 * en JVM sin un pty.
 *
 * ## LAS CUATRO REGLAS QUE NO SE NEGOCIAN
 *
 * 1. `Esc` en INICIO no sale: pide confirmacion. Salir de una conversacion con
 *    una cola local sin avisar es perder mensajes en silencio.
 * 2. Importar con exito SUSTITUYE la cima (no apila): el `Esc` desde el chat
 *    vuelve a INICIO en un paso (spec R4).
 * 3. `D` (descartar) SIEMPRE pide confirmacion; `R` (reintentar) no, porque no
 *    borra nada y conserva el `messageId`.
 * 4. La frase se pide al servicio SOLO tras confirmar el reveal y se borra del
 *    estado al ocultar o salir. El reducer no deriva palabras ni valida checksum:
 *    eso es `RecoveryService`; aqui solo se guarda el resultado.
 */
object ShellReducer {

    /** La palabra que hay que escribir para restaurar la identidad. */
    const val RESTORE_CONFIRMATION: String = "SOBRESCRIBIR"

    /** Cuantas lineas de log se conservan en pantalla. */
    const val MAX_LOGS: Int = 200

    /** El canal `[X]` al principio de una linea de `DiagLog`, si lo hay. */
    private val CHANNEL = Regex("^\\[([A-Z]+)\\]")

    /** Lo que la TUI pide al arrancar: la lista de contactos. */
    fun initialEffects(): List<ShellEffect> = listOf(ShellEffect.LoadContacts())

    fun reduce(state: ShellState, action: ShellAction): Reduced = when (action) {
        is ShellAction.Key -> onKey(state, action.stroke)
        is ShellAction.Event -> onEvent(state, action.event)
        is ShellAction.Resize -> Reduced(state.copy(viewport = action.viewport))
        is ShellAction.EffectDone -> onEffectDone(state, action.result)
    }

    // ------------------------------------------------------------------
    // Teclado
    // ------------------------------------------------------------------

    private fun onKey(state: ShellState, stroke: KeyStroke): Reduced {
        val intent = Keymap.resolve(state.screen, state.editingText, stroke)
        return applyIntent(state, intent)
    }

    private fun applyIntent(state: ShellState, intent: Intent): Reduced {
        // Cualquier tecla que no sea el segundo Ctrl-C desarma la salida: el
        // "dos veces" tiene que ser deliberado, no un residuo de hace un minuto.
        val base = if (intent != Intent.Cancel && state.quitArmed) {
            state.copy(quitArmed = false)
        } else {
            state
        }
        return when (intent) {
            Intent.None -> Reduced(base)
            is Intent.Insert -> insert(base, intent.text)
            Intent.DeleteBack -> deleteBack(base)
            Intent.NextField -> cycleField(base, forward = true)
            Intent.PrevField -> cycleField(base, forward = false)
            Intent.MoveUp -> move(base, -1)
            Intent.MoveDown -> move(base, +1)
            Intent.PageStart -> jump(base, start = true)
            Intent.PageEnd -> jump(base, start = false)
            Intent.Activate -> activate(base)
            Intent.Back -> back(base)
            Intent.Help -> Reduced(base.copy(helpVisible = !base.helpVisible))
            Intent.Redraw -> Reduced(base, listOf(ShellEffect.Redraw))
            Intent.Cancel -> cancel(base)
            Intent.Quit -> quit(base)
            Intent.Filter -> Reduced(base.copy(focus = Focus(FocusField.FIELD, 0)))
            Intent.NewContact -> openImport(base)
            Intent.Share -> openShare(base)
            Intent.Logs -> openScreen(base, Screen.DIAGNOSTICO)
            Intent.Diagnostics -> openScreen(base, Screen.DIAGNOSTICO)
            Intent.PhraseScreen -> openPhrase(base)
            Intent.RestoreScreen -> openRestore(base)
            Intent.Identity -> Reduced(
                base.copy(
                    banner = Banner(
                        LogLevel.INFO,
                        "identidad " + (base.status.localFingerprint ?: "desconocida"),
                    ),
                ),
            )
            Intent.Retry -> retry(base)
            Intent.Discard -> discard(base)
            Intent.Reveal -> reveal(base)
            Intent.Hide -> Reduced(
                base.copy(
                    phrase = base.phrase.copy(revealed = false, words = emptyList()),
                ),
            )
            Intent.Copy -> copyIntent(base)
            Intent.SaveKm -> saveKm(base)
            Intent.ShowText -> Reduced(
                base.copy(share = base.share?.copy(showText = !(base.share?.showText ?: false))),
            )
            Intent.CycleFilter -> Reduced(base.copy(logFilter = nextFilter(base.logFilter)))
            Intent.ExportLogs -> Reduced(base, listOf(ShellEffect.ExportLogs(base.logFilter)))
            Intent.Paste -> Reduced(base, listOf(ShellEffect.Paste))
            Intent.SourceText -> setSource(base, ImportSource.TEXT)
            Intent.SourceFile -> setSource(base, ImportSource.FILE)
            Intent.SourceOwnQr -> ownQr(base)
        }
    }

    /**
     * Inserta [text] en el campo con foco.
     *
     * El texto puede ser una tecla o un pegado entero: la misma ruta, para que
     * pegar no tenga reglas distintas de teclear.
     */
    private fun insert(state: ShellState, text: String): Reduced {
        return when (state.screen) {
        Screen.INICIO ->
            Reduced(state.copy(filter = state.filter + text, focus = Focus(FocusField.FIELD, 0)))

        Screen.IMPORTAR -> {
            val draft = state.importDraft ?: ImportDraft()
            if (state.focus.field != FocusField.FIELD || draft.source == ImportSource.OWN_QR) {
                return Reduced(state)
            }
            val next = when (draft.source) {
                ImportSource.TEXT -> draft.copy(text = draft.text + text)
                ImportSource.FILE -> draft.copy(path = draft.path + text)
                ImportSource.OWN_QR -> draft
            }
            Reduced(state.copy(importDraft = next))
        }

        Screen.CONVERSACION -> {
            val conv = state.conversation ?: return Reduced(state)
            Reduced(state.copy(conversation = conv.copy(composer = conv.composer + text)))
        }

        Screen.RESTAURAR -> {
            val phrase = state.phrase
            if (state.focus.field == FocusField.CONFIRM) {
                Reduced(
                    state.copy(
                        phrase = phrase.copy(confirmation = phrase.confirmation + text),
                    ),
                )
            } else {
                val entrada = phrase.restoreInput + text
                Reduced(
                    state.copy(
                        phrase = phrase.copy(restoreInput = entrada, validation = null),
                    ),
                    listOf(ShellEffect.CheckPhrase(entrada)),
                )
            }
        }

        Screen.COMPARTIR, Screen.FRASE, Screen.DIAGNOSTICO -> Reduced(state)
        }
    }

    private fun deleteBack(state: ShellState): Reduced {
        return when (state.screen) {
        Screen.INICIO -> Reduced(state.copy(filter = state.filter.dropLast(1)))

        Screen.IMPORTAR -> {
            val draft = state.importDraft ?: return Reduced(state)
            if (state.focus.field != FocusField.FIELD) return Reduced(state)
            val next = when (draft.source) {
                ImportSource.TEXT -> draft.copy(text = draft.text.dropLast(1))
                ImportSource.FILE -> draft.copy(path = draft.path.dropLast(1))
                ImportSource.OWN_QR -> draft
            }
            Reduced(state.copy(importDraft = next))
        }

        Screen.CONVERSACION -> {
            val conv = state.conversation ?: return Reduced(state)
            Reduced(state.copy(conversation = conv.copy(composer = conv.composer.dropLast(1))))
        }

        Screen.RESTAURAR -> {
            val phrase = state.phrase
            if (state.focus.field == FocusField.CONFIRM) {
                Reduced(state.copy(phrase = phrase.copy(confirmation = phrase.confirmation.dropLast(1))))
            } else {
                val entrada = phrase.restoreInput.dropLast(1)
                Reduced(
                    state.copy(phrase = phrase.copy(restoreInput = entrada, validation = null)),
                    listOf(ShellEffect.CheckPhrase(entrada)),
                )
            }
        }

        Screen.COMPARTIR, Screen.FRASE, Screen.DIAGNOSTICO -> Reduced(state)
        }
    }

    /**
     * Los campos que `Tab` recorre en [screen].
     *
     * En CONVERSACION el compositor va PRIMERO a proposito: al abrir un chat se
     * escribe, no se navega. `Tab` lleva a la lista, que es donde `R`/`D` tienen
     * sentido.
     */
    private fun fieldsFor(screen: Screen): List<FocusField> = when (screen) {
        Screen.INICIO -> listOf(FocusField.LIST, FocusField.FIELD)
        Screen.IMPORTAR -> listOf(FocusField.FIELD, FocusField.LIST)
        Screen.CONVERSACION -> listOf(FocusField.COMPOSER, FocusField.LIST)
        Screen.RESTAURAR -> listOf(FocusField.FIELD, FocusField.CONFIRM)
        Screen.COMPARTIR, Screen.FRASE, Screen.DIAGNOSTICO -> listOf(FocusField.LIST)
    }

    private fun cycleField(state: ShellState, forward: Boolean): Reduced {
        val fields = fieldsFor(state.screen)
        if (fields.size <= 1) return Reduced(state)
        val actual = fields.indexOf(state.focus.field)
        val siguiente = if (forward) {
            (actual + 1).mod(fields.size)
        } else {
            (actual - 1 + fields.size).mod(fields.size)
        }
        return Reduced(state.copy(focus = Focus(fields[siguiente], 0)))
    }

    private fun listSize(state: ShellState): Int = when (state.screen) {
        Screen.INICIO ->
            if (state.focus.field == FocusField.FIELD) 0 else state.filteredContacts().size
        Screen.CONVERSACION ->
            if (state.focus.field == FocusField.LIST) {
                state.conversation?.messages?.size ?: 0
            } else {
                0
            }
        Screen.DIAGNOSTICO -> state.logs.size
        Screen.IMPORTAR, Screen.COMPARTIR, Screen.FRASE, Screen.RESTAURAR -> 0
    }

    private fun move(state: ShellState, delta: Int): Reduced {
        val size = listSize(state)
        if (size <= 0) return Reduced(state)
        val siguiente = (state.focus.index + delta).coerceIn(0, size - 1)
        return Reduced(state.copy(focus = state.focus.copy(index = siguiente)))
    }

    private fun jump(state: ShellState, start: Boolean): Reduced {
        val size = listSize(state)
        if (size <= 0) return Reduced(state)
        return Reduced(state.copy(focus = state.focus.copy(index = if (start) 0 else size - 1)))
    }

    // ------------------------------------------------------------------
    // Abrir pantallas
    // ------------------------------------------------------------------

    private fun openScreen(state: ShellState, screen: Screen): Reduced {
        val focus = when (screen) {
            Screen.CONVERSACION -> Focus(FocusField.COMPOSER, 0)
            Screen.IMPORTAR, Screen.RESTAURAR -> Focus(FocusField.FIELD, 0)
            else -> Focus(FocusField.LIST, 0)
        }
        return Reduced(
            state.copy(
                screens = state.screens.push(screen),
                focus = focus,
                overlay = null,
                banner = null,
                helpVisible = false,
            ),
        )
    }

    private fun openImport(state: ShellState): Reduced {
        val abierta = openScreen(state, Screen.IMPORTAR)
        return Reduced(abierta.state.copy(importDraft = ImportDraft()))
    }

    private fun openShare(state: ShellState): Reduced {
        val abierta = openScreen(state, Screen.COMPARTIR)
        // Se exporta UNA vez al abrir: fabricar el bundle en cada repintado
        // gastaria OPK y haria que el portador cambiara bajo el QR.
        return Reduced(
            abierta.state.copy(share = ShareView()),
            listOf(ShellEffect.ExportOwn(0)),
        )
    }

    private fun openPhrase(state: ShellState): Reduced {
        val abierta = openScreen(state, Screen.FRASE)
        return Reduced(
            abierta.state.copy(
                phrase = PhraseView(identityNow = abierta.state.status.localFingerprint),
            ),
        )
    }

    private fun openRestore(state: ShellState): Reduced {
        val abierta = openScreen(state, Screen.RESTAURAR)
        return Reduced(
            abierta.state.copy(
                phrase = PhraseView(identityNow = abierta.state.status.localFingerprint),
            ),
        )
    }

    // ------------------------------------------------------------------
    // Acciones de pantalla
    // ------------------------------------------------------------------

    private fun activate(state: ShellState): Reduced {
        state.overlay?.let { overlay ->
            return when (overlay) {
                is Overlay.Confirm -> confirmOverlay(state, overlay)
                is Overlay.Offline -> Reduced(state.copy(overlay = null))
            }
        }
        return when (state.screen) {
            Screen.INICIO -> {
                val contacto = state.filteredContacts().getOrNull(state.focus.index)
                    ?: return Reduced(state)
                openConversation(state, contacto.identityId)
            }
            Screen.IMPORTAR -> doImport(state)
            Screen.CONVERSACION -> send(state)
            Screen.RESTAURAR -> restore(state)
            Screen.COMPARTIR, Screen.FRASE, Screen.DIAGNOSTICO -> Reduced(state)
        }
    }

    private fun openConversation(state: ShellState, peer: IdentityId): Reduced {
        val row = state.contact(peer) ?: return Reduced(state)
        val vista = ConversationView(
            peer = peer,
            title = row.label,
            peerPresence = row.presence,
            hasSession = row.hasSession,
        )
        val abierta = openScreen(state, Screen.CONVERSACION)
        return Reduced(
            abierta.state.copy(conversation = vista),
            listOf(ShellEffect.LoadHistory(peer)),
        )
    }

    private fun doImport(state: ShellState): Reduced {
        val draft = state.importDraft ?: return Reduced(state)
        val expected = draft.expectedRoot.trim().ifBlank { null }
        return when (draft.source) {
            ImportSource.TEXT -> {
                val portador = draft.text.trim()
                if (portador.isEmpty()) {
                    Reduced(state.copy(banner = Banner(LogLevel.WARN, "portador vacio")))
                } else {
                    Reduced(
                        state.copy(importDraft = draft.copy(rejection = null)),
                        listOf(ShellEffect.ImportText(portador, expected)),
                    )
                }
            }
            ImportSource.FILE -> {
                val ruta = draft.path.trim()
                if (ruta.isEmpty()) {
                    Reduced(state.copy(banner = Banner(LogLevel.WARN, "ruta vacia")))
                } else {
                    Reduced(
                        state.copy(importDraft = draft.copy(rejection = null)),
                        listOf(ShellEffect.ImportFile(ruta)),
                    )
                }
            }
            ImportSource.OWN_QR -> Reduced(state)
        }
    }

    private fun send(state: ShellState): Reduced {
        val conv = state.conversation ?: return Reduced(state)
        if (conv.sending) return Reduced(state)
        val texto = conv.composer
        if (texto.isBlank()) {
            return Reduced(state.copy(banner = Banner(LogLevel.WARN, "nada que enviar")))
        }
        // El compositor NO se limpia aqui: si el envio se rechaza, el texto del
        // usuario sigue donde estaba. Se limpia cuando el servicio confirma que
        // creo el mensaje.
        return Reduced(
            state.copy(conversation = conv.copy(sending = true)),
            listOf(ShellEffect.Send(conv.peer, texto)),
        )
    }

    private fun restore(state: ShellState): Reduced {
        val phrase = state.phrase
        val validacion = phrase.validation
        if (validacion == null || !validacion.ok) {
            return Reduced(
                state.copy(
                    banner = Banner(LogLevel.WARN, "frase invalida: 24 palabras y checksum"),
                ),
            )
        }
        if (phrase.confirmation.trim() != RESTORE_CONFIRMATION) {
            return Reduced(
                state.copy(
                    banner = Banner(LogLevel.WARN, "escribe $RESTORE_CONFIRMATION para confirmar"),
                ),
            )
        }
        return Reduced(
            state.copy(
                overlay = Overlay.Confirm(
                    ConfirmKind.RESTORE_IDENTITY,
                    "restaurar identidad",
                    "se pierde la identidad actual, sus sesiones y sus contactos en memoria",
                ),
            ),
        )
    }

    private fun retry(state: ShellState): Reduced {
        val row = state.selectedMessage()
            ?: return Reduced(state.copy(banner = Banner(LogLevel.WARN, "selecciona un mensaje con Tab")))
        if (!row.retryable) {
            return Reduced(state.copy(banner = Banner(LogLevel.WARN, "solo se reintenta un tombstone")))
        }
        return Reduced(state, listOf(ShellEffect.Resend(row.messageId)))
    }

    private fun discard(state: ShellState): Reduced {
        val row = state.selectedMessage()
            ?: return Reduced(state.copy(banner = Banner(LogLevel.WARN, "selecciona un mensaje con Tab")))
        if (!row.retryable) {
            return Reduced(state.copy(banner = Banner(LogLevel.WARN, "solo se descarta un tombstone")))
        }
        return Reduced(
            state.copy(
                overlay = Overlay.Confirm(
                    ConfirmKind.DISCARD_MESSAGE,
                    "descartar mensaje",
                    "se borra la fila; no se puede deshacer",
                    messageId = row.messageId,
                ),
            ),
        )
    }

    private fun reveal(state: ShellState): Reduced {
        if (state.phrase.revealed) return Reduced(state)
        return Reduced(
            state.copy(
                overlay = Overlay.Confirm(
                    ConfirmKind.REVEAL_PHRASE,
                    "revelar la frase",
                    "restaura SOLO la identidad: ni contactos, ni sesiones, ni mensajes",
                ),
            ),
        )
    }

    private fun copyIntent(state: ShellState): Reduced = when (state.screen) {
        Screen.FRASE -> {
            if (!state.phrase.revealed || state.phrase.words.isEmpty()) {
                Reduced(
                    state.copy(
                        banner = Banner(LogLevel.WARN, "revela la frase antes de copiarla"),
                    ),
                )
            } else {
                Reduced(
                    state,
                    listOf(
                        ShellEffect.CopyText(
                            text = state.phrase.words.joinToString(" "),
                            what = "frase",
                            // OSC 52 no es confirmable y la frase es lo mas
                            // sensible del cliente: el llamador tiene que avisar.
                            warn = true,
                        ),
                    ),
                )
            }
        }
        Screen.COMPARTIR -> {
            val portador = state.share?.carrier
            if (portador.isNullOrEmpty()) {
                Reduced(state.copy(banner = Banner(LogLevel.WARN, "no hay portador exportado")))
            } else {
                Reduced(state, listOf(ShellEffect.CopyText(portador, "portador")))
            }
        }
        Screen.DIAGNOSTICO -> Reduced(state, listOf(ShellEffect.CopyLogs(state.logFilter)))
        Screen.INICIO, Screen.IMPORTAR, Screen.CONVERSACION, Screen.RESTAURAR -> Reduced(state)
    }

    private fun saveKm(state: ShellState): Reduced {
        val portador = state.share?.carrier
        if (portador.isNullOrEmpty()) {
            return Reduced(state.copy(banner = Banner(LogLevel.WARN, "no hay portador exportado")))
        }
        return Reduced(state, listOf(ShellEffect.SaveCarrier(portador)))
    }

    private fun setSource(state: ShellState, source: ImportSource): Reduced {
        val draft = state.importDraft ?: ImportDraft()
        return Reduced(
            state.copy(
                importDraft = draft.copy(source = source, rejection = null),
                focus = Focus(FocusField.FIELD, 0),
            ),
        )
    }

    private fun ownQr(state: ShellState): Reduced {
        val draft = state.importDraft ?: ImportDraft()
        return Reduced(
            state.copy(
                importDraft = draft.copy(source = ImportSource.OWN_QR),
                share = ShareView(),
                focus = Focus(FocusField.LIST, 0),
            ),
            listOf(ShellEffect.ExportOwn(0)),
        )
    }

    private fun nextFilter(actual: String?): String? {
        // Los canales son los de DiagLog; el reducer no los importa para no
        // acoplar el modelo a la implementacion del log. Null = TODOS.
        val canales = listOf("[BOOTSTRAP]", "[RELAY]", "[AUTH]", "[CONTACT]", "[SESSION]", "[SEND]", "[RECV]", "[INTEGRITY]")
        val i = actual?.let { canales.indexOf(it) } ?: -1
        return canales.getOrNull(i + 1)
    }

    // ------------------------------------------------------------------
    // Volver, salir, confirmar
    // ------------------------------------------------------------------

    private fun back(state: ShellState): Reduced {
        if (state.helpVisible) return Reduced(state.copy(helpVisible = false))
        state.overlay?.let { return Reduced(state.copy(overlay = null)) }
        if (state.screen == Screen.INICIO) {
            if (state.focus.field == FocusField.FIELD) {
                return Reduced(state.copy(focus = Focus(FocusField.LIST, 0), filter = ""))
            }
            return Reduced(
                state.copy(
                    overlay = Overlay.Confirm(
                        ConfirmKind.EXIT,
                        "salir de KeyMessage",
                        "la cola es local y vive mientras el proceso siga abierto; el relay no guarda",
                    ),
                ),
            )
        }
        val popped = state.screens.pop() ?: return Reduced(state)
        return Reduced(
            state.copy(
                screens = popped,
                focus = Focus(FocusField.LIST, 0),
                overlay = null,
                banner = null,
                conversation = if (popped.current == Screen.CONVERSACION) state.conversation else null,
                importDraft = if (popped.current == Screen.IMPORTAR) state.importDraft else null,
                share = if (popped.current == Screen.COMPARTIR) state.share else null,
                // Al salir de FRASE/RESTAURAR la frase se BORRA: un buffer que
                // sobrevive a su pantalla es una frase esperando un volcado.
                phrase = PhraseView(identityNow = state.status.localFingerprint),
            ),
        )
    }

    private fun quit(state: ShellState): Reduced = Reduced(
        state.copy(
            overlay = Overlay.Confirm(
                ConfirmKind.EXIT,
                "salir de KeyMessage",
                "la cola es local y vive mientras el proceso siga abierto; el relay no guarda",
            ),
        ),
    )

    private fun cancel(state: ShellState): Reduced = when {
        state.overlay is Overlay.Confirm -> Reduced(state.copy(overlay = null))
        state.quitArmed -> Reduced(state.copy(quitArmed = false, quitting = true), listOf(ShellEffect.Quit))
        else -> Reduced(
            state.copy(
                quitArmed = true,
                banner = Banner(LogLevel.WARN, "Ctrl-C otra vez para salir"),
            ),
        )
    }

    private fun confirmOverlay(state: ShellState, overlay: Overlay.Confirm): Reduced =
        when (overlay.kind) {
            ConfirmKind.EXIT ->
                Reduced(state.copy(overlay = null, quitting = true), listOf(ShellEffect.Quit))

            ConfirmKind.DISCARD_MESSAGE -> {
                val id = overlay.messageId
                    ?: return Reduced(state.copy(overlay = null))
                Reduced(state.copy(overlay = null), listOf(ShellEffect.Discard(id)))
            }

            ConfirmKind.REVEAL_PHRASE -> {
                val palabras = state.phrase.words
                Reduced(
                    state.copy(
                        overlay = null,
                        phrase = state.phrase.copy(revealed = true),
                    ),
                    if (palabras.isEmpty()) listOf(ShellEffect.LoadPhrase) else emptyList(),
                )
            }

            ConfirmKind.RESTORE_IDENTITY -> Reduced(
                state.copy(overlay = null),
                listOf(
                    ShellEffect.RestoreIdentity(
                        words = state.phrase.restoreInput.trim(),
                        confirmation = state.phrase.confirmation.trim(),
                    ),
                ),
            )
        }

    // ------------------------------------------------------------------
    // Eventos del motor
    // ------------------------------------------------------------------

    private fun onEvent(state: ShellState, event: ClientEvent): Reduced = when (event) {
        is ClientEvent.Info -> note(state, LogLevel.INFO, event.text)
        is ClientEvent.Warn -> note(state, LogLevel.WARN, event.text)
        is ClientEvent.Error -> note(state, LogLevel.ERROR, event.text)

        is ClientEvent.ContactImported -> {
            val contacto = event.contact
            val previo = state.contact(contacto.identityRoot)
            val fila = ContactRow(
                identityId = contacto.identityRoot,
                fingerprint = contacto.fingerprint,
                displayName = contacto.displayName,
                hasSession = previo?.hasSession ?: false,
                hasAckKey = true,
                presence = previo?.presence ?: Presence.UNKNOWN,
                lastMessage = previo?.lastMessage,
                lastAt = previo?.lastAt,
            )
            val anotado = note(
                state,
                LogLevel.INFO,
                (if (event.isNew) "contacto importado " else "contacto ya conocido ") + fila.fingerprint,
            )
            Reduced(
                anotado.state.copy(
                    contacts = upsert(anotado.state.contacts, fila),
                    contactsLoaded = true,
                    contactsError = null,
                ),
                anotado.effects,
            )
        }

        is ClientEvent.OwnBundleExported -> {
            if (state.share == null && state.screen != Screen.COMPARTIR) {
                note(state, LogLevel.INFO, "bundle propio ${event.exported.byteLength} B")
            } else {
                Reduced(
                    state.copy(
                        share = ShareView(
                            carrier = event.exported.text,
                            byteLength = event.exported.byteLength,
                            oneTimePreKeys = event.exported.oneTimePreKeyCount,
                        ),
                        banner = Banner(
                            LogLevel.INFO,
                            "portador ${event.exported.byteLength} B opk=${event.exported.oneTimePreKeyCount}",
                        ),
                    ),
                )
            }
        }

        is ClientEvent.Announced ->
            note(state, LogLevel.INFO, "anunciado a " + short(event.peer))

        is ClientEvent.SessionOpened -> {
            val contacts = state.contacts.map {
                if (it.identityId == event.peer) it.copy(hasSession = true) else it
            }
            val conv = state.conversation?.let {
                if (it.peer == event.peer) it.copy(hasSession = true) else it
            }
            Reduced(state.copy(contacts = contacts, conversation = conv))
        }

        is ClientEvent.MessageReceived -> {
            val message = event.message
            val local = state.status.localIdentityId
            val mio = local != null && message.from.value == local
            val peer = if (mio) message.to else message.from
            val fila = MessageRow(
                messageId = message.messageId,
                direction = if (mio) MessageDirection.OUT else MessageDirection.IN,
                at = message.timestamp,
                text = String(message.payload, Charsets.UTF_8),
                state = message.state,
                failureCode = message.failureCode?.name,
            )
            val texto = fila.text
            val contacts = state.contacts.map {
                if (it.identityId == peer) it.copy(lastMessage = texto, lastAt = message.timestamp) else it
            }
            val conv = state.conversation?.let {
                if (it.peer == peer) it.copy(messages = upsertMessage(it.messages, fila)) else it
            }
            Reduced(state.copy(contacts = contacts, conversation = conv))
        }

        // Los ACK verificados llegan tambien como `MessageStateChanged`. Aqui no
        // se cambia ninguna fila: dos fuentes para el mismo estado es como se
        // pinta ENTREGADO sin saber cual gano.
        is ClientEvent.AckReceived ->
            note(state, LogLevel.INFO, "ACK ${event.ack.status} de ${short(event.ack.from)}")

        is ClientEvent.MessageStateChanged -> {
            val conv = state.conversation?.let { vista ->
                val filas = vista.messages.map {
                    if (it.messageId == event.messageId) it.copy(state = event.state) else it
                }
                vista.copy(messages = filas)
            }
            note(state.copy(conversation = conv), LogLevel.INFO, "mensaje ${short(event.messageId)} -> ${event.state.name}")
        }

        is ClientEvent.TransportState -> {
            val link = LinkState.of(event.state)
            val status = state.status.copy(link = link)
            val overlay = when {
                link == LinkState.ONLINE -> state.overlay as? Overlay.Confirm
                state.overlay is Overlay.Confirm -> state.overlay
                else -> Overlay.Offline(link, status.attempt, status.nextRetryInMs, status.lastError)
            }
            Reduced(state.copy(status = status, overlay = overlay))
        }

        is ClientEvent.PeerPresence -> {
            val presencia = if (event.online) Presence.RELAY_PRESENT else Presence.RELAY_ABSENT
            val contacts = state.contacts.map {
                if (it.identityId == event.peer) it.copy(presence = presencia) else it
            }
            val conv = state.conversation?.let {
                if (it.peer == event.peer) it.copy(peerPresence = presencia) else it
            }
            Reduced(state.copy(contacts = contacts, conversation = conv))
        }
    }

    // ------------------------------------------------------------------
    // Resultados de efectos
    // ------------------------------------------------------------------

    private fun onEffectDone(state: ShellState, result: EffectResult): Reduced = when (result) {
        is EffectResult.ContactsLoaded -> Reduced(
            state.copy(
                contacts = result.contacts,
                contactsLoaded = true,
                contactsError = null,
            ),
        )

        is EffectResult.ContactsFailed ->
            note(state.copy(contactsError = result.code), LogLevel.ERROR, "contactos no cargados codigo=${result.code}")

        is EffectResult.HistoryLoaded -> {
            val conv = state.conversation?.let {
                if (it.peer == result.peer) it.copy(messages = result.messages) else it
            }
            Reduced(state.copy(conversation = conv))
        }

        is EffectResult.Imported -> {
            val contacts = upsert(state.contacts, result.contact)
            // SUSTITUIR, no apilar: el Esc desde el chat vuelve a INICIO en un
            // paso (R4). El portador se limpia con el borrador.
            Reduced(
                state.copy(
                    screens = state.screens.substitute(Screen.CONVERSACION),
                    contacts = contacts,
                    contactsLoaded = true,
                    contactsError = null,
                    importDraft = null,
                    conversation = ConversationView(
                        peer = result.contact.identityId,
                        title = result.contact.label,
                        peerPresence = result.contact.presence,
                        hasSession = result.contact.hasSession,
                    ),
                    focus = Focus(FocusField.COMPOSER, 0),
                    overlay = null,
                    // La cabecera del chat ya muestra el identityId verificado
                    // (la huella del contacto). El aviso lo dice con la palabra
                    // VERIFICADO para que el exito no se confunda con "se pego
                    // algo y no hubo error".
                    banner = Banner(LogLevel.INFO, "VERIFICADO " + result.contact.fingerprint),
                ),
                listOf(ShellEffect.LoadHistory(result.contact.identityId)),
            )
        }

        is EffectResult.ImportRejected -> {
            val draft = state.importDraft?.copy(rejection = result.code)
            note(
                state.copy(importDraft = draft),
                LogLevel.ERROR,
                "portador rechazado codigo=${result.code}",
            )
        }

        is EffectResult.Exported -> Reduced(
            state.copy(
                share = ShareView(
                    carrier = result.carrier,
                    byteLength = result.byteLength,
                    oneTimePreKeys = result.oneTimePreKeys,
                ),
            ),
        )

        is EffectResult.Saved -> note(state, LogLevel.INFO, "guardado en ${result.path}")

        is EffectResult.Copied -> note(state, LogLevel.INFO, "copiado ${result.what}")

        is EffectResult.Sent -> {
            val conv = state.conversation ?: return note(state, LogLevel.INFO, "mensaje ${short(result.messageId)}")
            val fila = MessageRow(
                messageId = result.messageId,
                direction = MessageDirection.OUT,
                at = result.at,
                text = result.text,
                state = result.state,
            )
            // El compositor se limpia solo si sigue teniendo el texto enviado:
            // si el usuario empezo a escribir otro mientras tanto, no se pisa.
            val composer = if (conv.composer == result.text) "" else conv.composer
            Reduced(
                state.copy(
                    conversation = conv.copy(
                        messages = upsertMessage(conv.messages, fila),
                        composer = composer,
                        sending = false,
                    ),
                ),
            )
        }

        is EffectResult.SendRejected -> {
            val conv = state.conversation?.copy(sending = false)
            note(
                state.copy(conversation = conv),
                LogLevel.ERROR,
                "envio rechazado codigo=${result.code}",
            )
        }

        is EffectResult.Resent -> {
            val conv = state.conversation?.let { vista ->
                vista.copy(
                    messages = vista.messages.map {
                        if (it.messageId == result.messageId) it.copy(state = MessageState.SENDING) else it
                    },
                )
            }
            note(
                state.copy(conversation = conv),
                LogLevel.INFO,
                "reintento ${short(result.messageId)}",
            )
        }

        is EffectResult.Discarded -> {
            val conv = state.conversation?.let { vista ->
                vista.copy(messages = vista.messages.filterNot { it.messageId == result.messageId })
            }
            note(
                state.copy(conversation = conv),
                LogLevel.INFO,
                "descartado ${short(result.messageId)}",
            )
        }

        is EffectResult.PhraseLoaded -> Reduced(
            state.copy(
                phrase = state.phrase.copy(
                    words = result.words,
                    revealed = true,
                ),
            ),
        )

        is EffectResult.PhraseChecked -> Reduced(
            state.copy(
                phrase = state.phrase.copy(
                    validation = PhraseValidation(result.wordCount, result.checksumOk),
                ),
            ),
        )

        is EffectResult.Restored -> Reduced(
            state.copy(
                screens = ScreenStack.initial(),
                focus = Focus(FocusField.LIST, 0),
                contacts = emptyList(),
                contactsLoaded = false,
                contactsError = null,
                filter = "",
                conversation = null,
                phrase = PhraseView(),
                overlay = null,
                share = null,
                importDraft = null,
                logs = emptyList(),
                helpVisible = false,
                status = state.status.copy(
                    localFingerprint = abreviar(result.identityId),
                    localIdentityId = result.identityId,
                ),
                banner = Banner(LogLevel.INFO, "identidad restaurada; contactos y sesiones se pierden"),
            ),
            listOf(ShellEffect.LoadContacts("restaurada")),
        )

        is EffectResult.RestoreRejected ->
            note(state, LogLevel.ERROR, "restauracion rechazada codigo=${result.code}")

        is EffectResult.Pasted -> insert(state, result.text)

        is EffectResult.LogsExported -> note(state, LogLevel.INFO, "log exportado en ${result.path}")

        // El buffer de `DiagLog` reemplaza lo que la UI hubiera ido anotando: es
        // la fuente real, ya redactada y con hora. No se fusiona con las notas
        // del reducer, que son un resumen de lo mismo.
        is EffectResult.LogsLoaded -> Reduced(state.copy(logs = result.rows.takeLast(MAX_LOGS)))

        is EffectResult.Failed ->
            note(state, LogLevel.ERROR, "fallo ${result.code}" + (result.detail?.let { ": $it" } ?: ""))
    }

    // ------------------------------------------------------------------
    // Interno
    // ------------------------------------------------------------------

    /**
     * Anota una linea en la pantalla de logs y como aviso.
     *
     * NO lee el reloj: la hora de la linea la pone `DiagLog`. Aqui solo se
     * conserva el canal que el texto ya trae (`[RELAY] ...`), para que el filtro
     * de la pantalla funcione sin una segunda fuente de verdad.
     */
    private fun note(state: ShellState, level: LogLevel, text: String): Reduced {
        val canal = CHANNEL.find(text)?.groupValues?.get(1)?.let { "[$it]" }
        val fila = LogRow(level, canal, text)
        val logs = (state.logs + fila).takeLast(MAX_LOGS)
        return Reduced(state.copy(logs = logs, banner = Banner(level, text)))
    }

    private fun upsert(contacts: List<ContactRow>, row: ContactRow): List<ContactRow> {
        val existente = contacts.indexOfFirst { it.identityId == row.identityId }
        if (existente < 0) return contacts + row
        return contacts.toMutableList().also { it[existente] = row }
    }

    private fun upsertMessage(messages: List<MessageRow>, row: MessageRow): List<MessageRow> {
        val sinDuplicado = messages.filterNot { it.messageId == row.messageId }
        return (sinDuplicado + row).sortedBy { it.at }
    }

    private fun short(peer: IdentityId): String = abreviar(peer.value)

    private fun short(id: MessageId): String = id.value.toString().take(8) + "\u2026"

    private fun abreviar(id: String): String =
        if (id.length <= 16) id else id.take(16) + "\u2026"
}
