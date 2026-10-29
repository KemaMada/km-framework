package com.km.desktop.ui

import com.km.desktop.client.ClientEvent
import com.km.desktop.ui.layout.Layout
import com.km.desktop.ui.layout.ListId
import com.km.desktop.ui.layout.Scroll
import com.km.desktop.ui.layout.ScrollState
import com.km.desktop.ui.render.OverlayWindow
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

    /** Duracion de un aviso informativo o de exito. */
    const val INFO_TOAST_MS: Long = 4_000

    /** Un error se queda el doble: hay que poder leerlo. */
    const val ERROR_TOAST_MS: Long = 8_000

    /** El canal `[X]` al principio de una linea de `DiagLog`, si lo hay. */
    private val CHANNEL = Regex("^\\[([A-Z]+)\\]")

    /** Lo que la TUI pide al arrancar: la lista de contactos. */
    fun initialEffects(): List<ShellEffect> = listOf(ShellEffect.LoadContacts())

    fun reduce(state: ShellState, action: ShellAction): Reduced = when (action) {
        is ShellAction.Key -> onKey(state, action.stroke)
        is ShellAction.Event -> onEvent(state, action.event)
        is ShellAction.Resize -> Reduced(ensureVisible(state.copy(viewport = action.viewport)))
        is ShellAction.EffectDone -> onEffectDone(state, action.result)
        is ShellAction.ScrollBy -> scrollBy(state, action.delta)
        is ShellAction.Tick -> tick(state, action.nowMs)
        is ShellAction.Pointer -> onPointer(state, action.intent)
    }

    /**
     * El reloj entra como DATO.
     *
     * Se guarda el instante y se caduca el toast. No hay `System.currentTimeMillis`
     * aqui: dos ejecuciones con los mismos ticks producen el mismo estado, que
     * es lo que permite probar la expiracion sin esperar cuatro segundos.
     */
    private fun tick(state: ShellState, nowMs: Long): Reduced {
        val toast = state.toast
        val limpio = if (toast != null && nowMs >= toast.expiresAtMs) null else toast
        return Reduced(state.copy(nowMs = nowMs, toast = limpio))
    }

    /** Un toast nuevo, con la duracion que fija el tipo (error dura el doble). */
    private fun toast(
        state: ShellState,
        variant: ToastVariant,
        title: String,
        message: String? = null,
    ): Reduced {
        val duracion = if (variant == ToastVariant.ERROR) ERROR_TOAST_MS else INFO_TOAST_MS
        return Reduced(
            state.copy(
                toast = Toast(variant, title, message, expiresAtMs = state.nowMs + duracion),
            ),
        )
    }

    // ------------------------------------------------------------------
    // Teclado
    // ------------------------------------------------------------------

    private fun onKey(state: ShellState, stroke: KeyStroke): Reduced {
        val intent = Keymap.resolve(state.screen, state.editingText, stroke)
        return applyIntent(state, intent)
    }

    // ------------------------------------------------------------------
    // Raton (change B)
    // ------------------------------------------------------------------

    /**
     * Un clic o la rueda, ya resuelto por el hit-map.
     *
     * ## LA CAPA SUPERIOR MANDA
     *
     * Con un modal abierto, el fondo no responde: un `Dialog` marca y activa su
     * opcion (equivale a Tab+Enter), y `Dismiss` cierra igual que `Esc`. Sin
     * overlay, cada intencion reusa la tecla que ya existia: fila -> abrir chat,
     * pestana -> `TabTo`, pie -> `Act(Intent)`, foco -> campo.
     *
     * `Dismiss` sin capa modal NO sale de la app: el clic en una zona inerte no
     * puede tener un efecto destructivo que ninguna tecla tenga.
     */
    private fun onPointer(state: ShellState, pointer: MouseIntent): Reduced {
        val base = if (state.quitArmed) state.copy(quitArmed = false) else state
        (base.overlay as? Overlay.Confirm)?.let { overlay ->
            return when (pointer) {
                is MouseIntent.Dialog -> {
                    val marcado = base.copy(overlay = overlay.copy(selection = pointer.choice))
                    if (pointer.choice == DialogChoice.PRIMARY) {
                        confirmOverlay(marcado, marcado.overlay as Overlay.Confirm)
                    } else {
                        Reduced(marcado.copy(overlay = null))
                    }
                }

                MouseIntent.Dismiss -> Reduced(base.copy(overlay = null))
                else -> Reduced(base)
            }
        }
        if (base.overlay is Overlay.Offline) {
            return if (pointer == MouseIntent.Dismiss) Reduced(base.copy(overlay = null)) else Reduced(base)
        }
        if (base.helpVisible) {
            return if (pointer == MouseIntent.Dismiss || pointer == MouseIntent.Act(Intent.Back)) {
                Reduced(base.copy(helpVisible = false, helpOffset = 0))
            } else {
                Reduced(base)
            }
        }
        return when (pointer) {
            is MouseIntent.SelectContact -> pointerContact(base, pointer.index)
            is MouseIntent.SelectMessage -> pointerMessage(base, pointer.index)
            is MouseIntent.SelectLog -> pointerLog(base, pointer.index)
            MouseIntent.FocusComposer -> if (base.screen == Screen.CONVERSACION) {
                Reduced(base.copy(focus = Focus(FocusField.COMPOSER, 0)))
            } else {
                Reduced(base)
            }

            MouseIntent.FocusFilter -> if (base.screen == Screen.INICIO) {
                Reduced(ensureVisible(base.copy(focus = Focus(FocusField.FIELD, 0))))
            } else {
                Reduced(base)
            }

            is MouseIntent.Act -> applyIntent(base, pointer.intent)
            is MouseIntent.Dialog -> Reduced(base)
            MouseIntent.Dismiss -> Reduced(base)
        }
    }

    /** Clic en una ficha de INICIO: abre su chat, igual que flechas+Enter. */
    private fun pointerContact(state: ShellState, index: Int): Reduced {
        if (state.screen != Screen.INICIO) return Reduced(state)
        val filas = state.filteredContacts()
        val fila = filas.getOrNull(index) ?: return Reduced(state)
        val conFoco = ensureVisible(state.copy(focus = Focus(FocusField.LIST, index)))
        return openConversation(conFoco, fila.identityId)
    }

    /** Clic en una burbuja: mueve el foco, NO envia ni abre nada. */
    private fun pointerMessage(state: ShellState, index: Int): Reduced {
        if (state.screen != Screen.CONVERSACION) return Reduced(state)
        val mensajes = state.conversation?.messages ?: return Reduced(state)
        if (mensajes.isEmpty()) return Reduced(state)
        val destino = index.coerceIn(0, mensajes.lastIndex)
        return Reduced(afterMove(state.copy(focus = Focus(FocusField.LIST, destino))))
    }

    /** Clic en una linea de DIAGNOSTICO: mueve el foco de la lista de logs. */
    private fun pointerLog(state: ShellState, index: Int): Reduced {
        if (state.screen != Screen.DIAGNOSTICO) return Reduced(state)
        val logs = state.visibleLogs()
        if (logs.isEmpty()) return Reduced(state)
        val destino = index.coerceIn(0, logs.lastIndex)
        return Reduced(ensureVisible(state.copy(focus = Focus(FocusField.LIST, destino))))
    }

    private fun applyIntent(state: ShellState, intent: Intent): Reduced {
        // Cualquier tecla que no sea el segundo Ctrl-C desarma la salida: el
        // "dos veces" tiene que ser deliberado, no un residuo de hace un minuto.
        val base = if (intent != Intent.Cancel && state.quitArmed) {
            state.copy(quitArmed = false)
        } else {
            state
        }
        // FOCO ATRAPADO: con un Confirm abierto nada del fondo responde. Tab y
        // las flechas mueven el boton marcado; Enter acepta lo marcado; Esc
        // cancela. Es lo que hace que un Enter reflejo no borre nada.
        (base.overlay as? Overlay.Confirm)?.let { return modal(base, it, intent) }
        // La ayuda se VENTANEA con las mismas teclas de la lista, sin tocar el
        // foco de la pantalla de debajo.
        if (base.helpVisible) helpScroll(base, intent)?.let { return it }
        return when (intent) {
            Intent.None -> Reduced(base)
            is Intent.Insert -> insert(base, intent.text)
            Intent.DeleteBack -> deleteBack(base)
            Intent.NextField -> cycleField(base, forward = true)
            Intent.PrevField -> cycleField(base, forward = false)
            Intent.MoveUp -> move(base, -1)
            Intent.MoveDown -> move(base, +1)
            Intent.PageUp -> page(base, -1)
            Intent.PageDown -> page(base, +1)
            Intent.PageStart -> jump(base, start = true)
            Intent.PageEnd -> jump(base, start = false)
            Intent.Activate -> activate(base)
            Intent.Back -> back(base)
            is Intent.TabTo -> tabTo(base, intent.tab)
            Intent.Help -> Reduced(base.copy(helpVisible = !base.helpVisible, helpOffset = 0))
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
            Intent.CycleFilter -> Reduced(ensureVisible(base.copy(logFilter = nextFilter(base.logFilter))))
            Intent.ExportLogs -> Reduced(base, listOf(ShellEffect.ExportLogs(base.logFilter)))
            Intent.Paste -> Reduced(base, listOf(ShellEffect.Paste))
            Intent.SourceText -> setSource(base, ImportSource.TEXT)
            Intent.SourceFile -> setSource(base, ImportSource.FILE)
            Intent.SourceOwnQr -> ownQr(base)
        }
    }

    /**
     * Las teclas de un modal CONFIRM.
     *
     * No es una tabla nueva: reusa los intents que ya existen. Lo que cambia es
     * que SOLO estos responden y que la seleccion por defecto de una accion
     * destructiva es la NO destructiva.
     */
    private fun modal(
        state: ShellState,
        overlay: Overlay.Confirm,
        intent: Intent,
    ): Reduced = when (intent) {
        Intent.NextField, Intent.PrevField, Intent.MoveUp, Intent.MoveDown ->
            Reduced(state.copy(overlay = overlay.copy(selection = other(overlay.selection))))

        Intent.Activate ->
            if (overlay.selection == DialogChoice.PRIMARY) {
                confirmOverlay(state, overlay)
            } else {
                Reduced(state.copy(overlay = null))
            }

        Intent.Back, Intent.Cancel -> Reduced(state.copy(overlay = null))
        else -> Reduced(state)
    }

    private fun other(choice: DialogChoice): DialogChoice =
        if (choice == DialogChoice.PRIMARY) DialogChoice.SECONDARY else DialogChoice.PRIMARY

    /**
     * Desplaza la ayuda con las teclas de navegacion.
     *
     * Devuelve `null` si el intent no es de navegacion: `?` y `Esc` siguen
     * cerrando la ayuda por la ruta de siempre.
     */
    private fun helpScroll(state: ShellState, intent: Intent): Reduced? {
        val total = Keymap.help(state.screen).size + 2
        val max = OverlayWindow.maxOffset(state.viewport.rows, total)
        val paso = OverlayWindow.visibleRows(state.viewport.rows, total).coerceAtLeast(1)
        val destino = when (intent) {
            Intent.MoveUp -> state.helpOffset - 1
            Intent.MoveDown -> state.helpOffset + 1
            Intent.PageUp -> state.helpOffset - paso
            Intent.PageDown -> state.helpOffset + paso
            Intent.PageStart -> 0
            Intent.PageEnd -> max
            else -> return null
        }
        return Reduced(state.copy(helpOffset = destino.coerceIn(0, max)))
    }

    /**
     *
     * El texto puede ser una tecla o un pegado entero: la misma ruta, para que
     * pegar no tenga reglas distintas de teclear.
     */
    private fun insert(state: ShellState, text: String): Reduced {
        return when (state.screen) {
        Screen.INICIO ->
            // Al filtrar la lista se acorta: el offset viejo puede apuntar al
            // final de una lista que ya no existe. Se vuelve arriba.
            Reduced(
                ensureVisible(
                    state.copy(filter = state.filter + text, focus = Focus(FocusField.FIELD, 0)),
                ),
            )

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
        Screen.INICIO ->
            Reduced(ensureVisible(state.copy(filter = state.filter.dropLast(1))))

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
     *
     * [FocusField.TABS] va AL FINAL: la barra de pestañas tiene que ser
     * alcanzable sin teclas de funcion, pero meterla delante cambiaria el orden
     * que el usuario ya conoce (compositor -> lista) y el primer `Tab` de un
     * chat dejaria de llevar al hilo.
     */
    private fun fieldsFor(screen: Screen): List<FocusField> = when (screen) {
        Screen.INICIO -> listOf(FocusField.LIST, FocusField.FIELD, FocusField.TABS)
        Screen.IMPORTAR -> listOf(FocusField.FIELD, FocusField.LIST, FocusField.TABS)
        Screen.CONVERSACION -> listOf(FocusField.COMPOSER, FocusField.LIST, FocusField.TABS)
        Screen.RESTAURAR -> listOf(FocusField.FIELD, FocusField.CONFIRM, FocusField.TABS)
        Screen.COMPARTIR, Screen.FRASE, Screen.DIAGNOSTICO ->
            listOf(FocusField.LIST, FocusField.TABS)
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
        val campo = fields[siguiente]
        // En la barra el indice es la pestaña, no una posicion de lista: al
        // entrar con Tab el cursor cae en la pestaña activa.
        val indice = if (campo == FocusField.TABS) state.tabs.active.ordinal else 0
        return Reduced(state.copy(focus = Focus(campo, indice)))
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
        Screen.DIAGNOSTICO -> state.visibleLogs().size
        Screen.IMPORTAR, Screen.COMPARTIR, Screen.FRASE, Screen.RESTAURAR -> 0
    }

    /**
     * Cuantos elementos tiene la lista VISIBLE, mire donde mire el foco.
     *
     * No es [listSize]: mientras se escribe en el filtro o en el compositor el
     * cursor de movimiento no aplica, pero la lista se sigue pintando y su
     * ventana tiene que seguir cuadrando. Mezclar las dos cosas hacia que el
     * hilo saltara al principio en cuanto se tabulaba al compositor.
     */
    private fun listTotal(state: ShellState): Int = when (state.screen) {
        Screen.INICIO -> state.filteredContacts().size
        Screen.CONVERSACION -> state.conversation?.messages?.size ?: 0
        Screen.DIAGNOSTICO -> state.visibleLogs().size
        Screen.IMPORTAR, Screen.COMPARTIR, Screen.FRASE, Screen.RESTAURAR -> 0
    }

    /** La lista que se desplaza en [screen], o `null` si no hay lista. */
    private fun listId(screen: Screen): ListId? = when (screen) {
        Screen.INICIO -> ListId.CONTACTS
        Screen.CONVERSACION -> ListId.MESSAGES
        Screen.DIAGNOSTICO -> ListId.LOGS
        Screen.IMPORTAR, Screen.COMPARTIR, Screen.FRASE, Screen.RESTAURAR -> null
    }

    private fun withScroll(
        state: ShellState,
        id: ListId,
        offset: Int,
        followEnd: Boolean,
    ): ShellState {
        val nuevo = ScrollState(offset.coerceAtLeast(0), followEnd)
        return if (state.scrollOf(id) == nuevo) state else state.copy(scroll = state.scroll + (id to nuevo))
    }

    /**
     * Deja la fila enfocada dentro de la ventana.
     *
     * El alto sale de [Layout.listRows], que es puro: el estado NO guarda
     * geometria. Con `followEnd` en la conversacion el ancla es el ultimo
     * mensaje, de modo que un dato nuevo no se queda debajo del borde.
     */
    private fun ensureVisible(state: ShellState): ShellState {
        val id = listId(state.screen) ?: return state
        val total = listTotal(state)
        val rows = Layout.listRows(state.screen, state.viewport)
        val actual = state.scrollOf(id)
        val ancla = if (id == ListId.MESSAGES && actual.followEnd && total > 0) {
            total - 1
        } else {
            state.focus.index
        }
        val offset = Scroll.ensure(ancla, actual.offset, rows, total)
        return withScroll(state, id, offset, actual.followEnd)
    }

    /**
     * Ajusta scroll y `followEnd` despues de mover el cursor.
     *
     * Tocar el final del hilo vuelve a engancharse al final y limpia el
     * contador; subir lo desengancha. En las demas listas solo se garantiza
     * que el foco se vea, sin inventar un "seguir el final" que no aplica.
     */
    private fun afterMove(state: ShellState): ShellState {
        if (state.screen != Screen.CONVERSACION) return ensureVisible(state)
        val total = listTotal(state)
        val alFinal = total > 0 && state.focus.index >= total - 1
        val conv = state.conversation
        val sinPendientes = if (alFinal && conv != null && conv.unread != 0) {
            state.copy(conversation = conv.copy(unread = 0))
        } else {
            state
        }
        val enganchado = withScroll(sinPendientes, ListId.MESSAGES, sinPendientes.scrollOf(ListId.MESSAGES).offset, alFinal)
        return ensureVisible(enganchado)
    }

    /**
     * Ajusta la ventana del hilo cuando cambia su coleccion.
     *
     * Tres casos, y la diferencia es la razon de existir del metodo:
     *  - `followEnd` activo: se sigue el final aunque el hilo tenga 500 filas.
     *  - mensaje ENTRANTE y el usuario esta leyendo mas arriba: NO se salta y
     *    se cuenta (`[N nuevos ↓]`). Moverle la vista mientras lee seria
     *    perderle el sitio.
     *  - cualquier otro cambio (historial cargado, mensaje propio, descarte):
     *    solo se garantiza que el foco siga visible.
     */
    private fun afterMessageArrival(state: ShellState, peer: IdentityId, incoming: Boolean): ShellState {
        val conv = state.conversation ?: return state
        if (conv.peer != peer) return state
        val id = ListId.MESSAGES
        val actual = state.scrollOf(id)
        val enPantalla = state.screen == Screen.CONVERSACION
        return when {
            actual.followEnd -> ensureVisible(state)
            incoming && enPantalla -> state.copy(conversation = conv.copy(unread = conv.unread + 1))
            else -> ensureVisible(state)
        }
    }

    private fun move(state: ShellState, delta: Int): Reduced {
        if (state.focus.field == FocusField.TABS) {
            val destino = (state.focus.index + delta).coerceIn(0, RootTab.entries.size - 1)
            return Reduced(state.copy(focus = state.focus.copy(index = destino)))
        }
        val size = listSize(state)
        if (size <= 0) return Reduced(state)
        val siguiente = (state.focus.index + delta).coerceIn(0, size - 1)
        val movido = state.copy(focus = state.focus.copy(index = siguiente))
        return Reduced(afterMove(movido))
    }

    /** PgUp/PgDn: una pantalla de lista de golpe, con el mismo `ensure` que las flechas. */
    private fun page(state: ShellState, direction: Int): Reduced {
        if (state.focus.field == FocusField.TABS) return move(state, direction)
        val rows = Layout.listRows(state.screen, state.viewport)
        return move(state, direction * rows)
    }

    /** Rueda (change B): mueve la seleccion para no romper "el foco siempre visible". */
    private fun scrollBy(state: ShellState, delta: Int): Reduced =
        if (delta == 0) Reduced(ensureVisible(state)) else move(state, delta)

    private fun jump(state: ShellState, start: Boolean): Reduced {
        if (state.focus.field == FocusField.TABS) {
            return Reduced(
                state.copy(
                    focus = state.focus.copy(index = if (start) 0 else RootTab.entries.size - 1),
                ),
            )
        }
        val size = listSize(state)
        if (size <= 0) return Reduced(state)
        val movido = state.copy(focus = state.focus.copy(index = if (start) 0 else size - 1))
        return Reduced(afterMove(movido))
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
        val abierta = ensureVisible(
            state.copy(
                tabs = state.tabs.push(screen),
                focus = focus,
                overlay = null,
                banner = null,
                helpVisible = false,
            ),
        )
        return Reduced(abierta)
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
        if (state.focus.field == FocusField.TABS) {
            val tab = RootTab.at(state.focus.index) ?: return Reduced(state)
            return tabTo(state, tab)
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
        // Un hilo se abre ENGANCHADO al final: lo ultimo es lo que se quiere
        // leer. El offset de la conversacion anterior no se hereda.
        val limpia = withScroll(abierta.state, ListId.MESSAGES, 0, followEnd = true)
        return Reduced(
            ensureVisible(limpia.copy(conversation = vista)),
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
                    selection = DialogChoice.SECONDARY,
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
                    selection = DialogChoice.SECONDARY,
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
                    // Arranca en NO: un Enter reflejo mostraria las 24 palabras, que
                    // son el secreto mas valioso del cliente. Misma regla que salir,
                    // descartar y sobrescribir identidad.
                    selection = DialogChoice.SECONDARY,
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
        if (state.helpVisible) return Reduced(state.copy(helpVisible = false, helpOffset = 0))
        state.overlay?.let { return Reduced(state.copy(overlay = null)) }
        if (state.screen == Screen.INICIO) {
            if (state.focus.field == FocusField.FIELD) {
                return Reduced(ensureVisible(state.copy(focus = Focus(FocusField.LIST, 0), filter = "")))
            }
            return Reduced(
                state.copy(
                    overlay = Overlay.Confirm(
                        ConfirmKind.EXIT,
                        "salir de KeyMessage",
                        "la cola es local y vive mientras el proceso siga abierto; el relay no guarda",
                        selection = DialogChoice.SECONDARY,
                    ),
                ),
            )
        }
        // `pop` retrocede dentro de la pestaña y, en su raiz, vuelve a la
        // pestaña desde la que se entro. El estado que NO es de la pila
        // (borradores del tab INICIO) se conserva.
        val popped = state.tabs.pop() ?: return Reduced(state)
        return Reduced(
            ensureVisible(
                state.copy(
                    tabs = popped,
                    focus = Focus(FocusField.LIST, 0),
                    overlay = null,
                    banner = null,
                    conversation = if (popped.screen == Screen.CONVERSACION) state.conversation else null,
                    importDraft = if (popped.screen == Screen.IMPORTAR) state.importDraft else null,
                    share = if (popped.screen == Screen.COMPARTIR) state.share else null,
                    // Al salir de FRASE/RESTAURAR la frase se BORRA: un buffer que
                    // sobrevive a su pantalla es una frase esperando un volcado.
                    phrase = PhraseView(identityNow = state.status.localFingerprint),
                ),
            ),
        )
    }

    /**
     * Cambia de pestaña SIN tocar borradores.
     *
     * Es la unica ruta de cambio de raiz. No limpia `filter`, `conversation`,
     * `importDraft`, `share` ni `phrase`: si lo hiciera, ir a DIAGNOSTICO y
     * volver perderia lo escrito, que es exactamente lo que R16 prohibe. Solo
     * se cierra la ayuda, porque la ayuda es de la pantalla que se deja.
     */
    private fun tabTo(state: ShellState, tab: RootTab): Reduced {
        val tabs = state.tabs.activate(tab)
        val focus = if (state.focus.field == FocusField.TABS) {
            Focus(FocusField.TABS, tab.ordinal)
        } else {
            state.focus
        }
        val cambiado = state.copy(
            tabs = tabs,
            focus = focus,
            helpVisible = false,
            helpOffset = 0,
        )
        return Reduced(ensureVisible(cambiado))
    }

    private fun quit(state: ShellState): Reduced = Reduced(
        state.copy(
            overlay = Overlay.Confirm(
                ConfirmKind.EXIT,
                "salir de KeyMessage",
                "la cola es local y vive mientras el proceso siga abierto; el relay no guarda",
                selection = DialogChoice.SECONDARY,
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
            Reduced(
                afterMessageArrival(
                    state.copy(contacts = contacts, conversation = conv),
                    peer,
                    incoming = !mio,
                ),
            )
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

        // El medio de DATOS cambia SIN tocar el enlace al relay. Se guarda el
        // motivo estable tal cual: traducirlo en el reducer haria que dos
        // pantallas dijeran cosas distintas del mismo intento.
        is ClientEvent.MediumChanged -> {
            val status = state.status.copy(
                medium = event.medium,
                mediumReason = event.reason,
            )
            note(
                state.copy(status = status),
                LogLevel.INFO,
                "medio ${event.medium} con ${short(event.peer)} motivo=${event.reason}",
            )
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
                if (it.peer == result.peer) {
                    it.copy(messages = result.messages, unread = 0)
                } else {
                    it
                }
            }
            // Cargar el historial de un hilo enganchado al final tiene que
            // dejar el final a la vista (R8: 500 mensajes y se ve el ultimo).
            Reduced(afterMessageArrival(state.copy(conversation = conv), result.peer, incoming = false))
        }

        is EffectResult.Imported -> {
            val contacts = upsert(state.contacts, result.contact)
            // SUSTITUIR, no apilar: el Esc desde el chat vuelve a INICIO en un
            // paso (R4). El portador se limpia con el borrador.
            val abierto = state.copy(
                tabs = state.tabs.substitute(Screen.CONVERSACION),
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
            )
            Reduced(
                ensureVisible(withScroll(abierto, ListId.MESSAGES, 0, followEnd = true)),
                listOf(ShellEffect.LoadHistory(result.contact.identityId)),
            )
        }

        is EffectResult.ImportRejected -> {
            val draft = state.importDraft?.copy(rejection = result.code)
            toast(
                note(
                    state.copy(importDraft = draft),
                    LogLevel.ERROR,
                    "portador rechazado codigo=${result.code}",
                ).state,
                ToastVariant.ERROR,
                "portador rechazado",
                result.code,
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

        is EffectResult.Saved ->
            toast(note(state, LogLevel.INFO, "guardado en ${result.path}").state, ToastVariant.SUCCESS, "guardado", result.path)

        is EffectResult.Copied ->
            toast(
                note(state, LogLevel.INFO, "copiado ${result.what}").state,
                ToastVariant.SUCCESS,
                "copiado " + result.what,
                "OSC 52 no confirmable",
            )

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
            val conFila = state.copy(
                conversation = conv.copy(
                    messages = upsertMessage(conv.messages, fila),
                    composer = composer,
                    sending = false,
                ),
            )
            // Un mensaje PROPIO no cuenta como "nuevo": si el usuario esta
            // leyendo mas arriba, no se le salta el hilo ni se le anuncia.
            toast(
                afterMessageArrival(conFila, conv.peer, incoming = false),
                ToastVariant.INFO,
                "OUT \u00b7 en cola local",
                "sin ACK: no es entrega",
            )
        }

        is EffectResult.SendRejected -> {
            val conv = state.conversation?.copy(sending = false)
            toast(
                note(
                    state.copy(conversation = conv),
                    LogLevel.ERROR,
                    "envio rechazado codigo=${result.code}",
                ).state,
                ToastVariant.ERROR,
                "envio rechazado",
                result.code,
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
                ensureVisible(state.copy(conversation = conv)),
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

        is EffectResult.Restored -> toast(
            state.copy(
                tabs = TabStacks.initial(),
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
                helpOffset = 0,
                scroll = emptyMap(),
                status = state.status.copy(
                    localFingerprint = abreviar(result.identityId),
                    localIdentityId = result.identityId,
                ),
                banner = Banner(LogLevel.INFO, "identidad restaurada; contactos y sesiones se pierden"),
            ),
            ToastVariant.INFO,
            "identidad restaurada",
            "reinicia para usar la nueva",
        ).copy(effects = listOf(ShellEffect.LoadContacts("restaurada")))

        is EffectResult.RestoreRejected ->
            note(state, LogLevel.ERROR, "restauracion rechazada codigo=${result.code}")

        is EffectResult.Pasted -> insert(state, result.text)

        is EffectResult.LogsExported ->
            toast(
                note(state, LogLevel.INFO, "log exportado en ${result.path}").state,
                ToastVariant.SUCCESS,
                "log exportado",
                result.path,
            )

        // El buffer de `DiagLog` reemplaza lo que la UI hubiera ido anotando: es
        // la fuente real, ya redactada y con hora. No se fusiona con las notas
        // del reducer, que son un resumen de lo mismo.
        is EffectResult.LogsLoaded -> Reduced(
            ensureVisible(state.copy(logs = result.rows.takeLast(MAX_LOGS))),
        )

        is EffectResult.Failed ->
            toast(
                note(
                    state,
                    LogLevel.ERROR,
                    "fallo ${result.code}" + (result.detail?.let { ": $it" } ?: ""),
                ).state,
                ToastVariant.ERROR,
                "fallo " + result.code,
                result.detail,
            )
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
