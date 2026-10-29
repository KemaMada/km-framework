package com.km.desktop.ui

import com.km.desktop.ui.layout.ListId
import com.km.desktop.ui.layout.ScrollState
import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.model.MessageState

/**
 * El ESTADO INMUTABLE de la TUI de pantallas.
 *
 * ## POR QUE TODO ES UN `data class` SIN NADA MAS
 *
 * Porque el reducer tiene que poder calcular el estado siguiente sin tocar una
 * terminal, un fichero ni un reloj. Un campo que se muta en sitio convierte
 * "que se pinta" en una funcion del historial de llamadas; un estado inmutable
 * lo convierte en una funcion del estado y la accion, que es lo unico que se
 * puede probar en JVM sin un pty.
 *
 * ## LO QUE NO HAY AQUI
 *
 * Ni servicios, ni sockets, ni `PrintWriter`, ni JLine. Los datos que vienen del
 * exterior entran como [com.km.desktop.client.ClientEvent] o como
 * [EffectResult]; lo que hay que hacer fuera se describe como [ShellEffect] y lo
 * ejecuta el bucle de la Fase 3.
 *
 * ## POR QUE LOS TEXTOS NO SE SANEAN AQUI
 *
 * Porque sanear depende del ANCHO en el que se va a medir, y eso es pintura.
 * [Sanitizer] se aplica al componer cada linea, antes de truncar. Guardar el
 * payload crudo en el estado es lo que permite repintar a otro ancho sin perder
 * informacion; guardarlo ya truncado seria una segunda verdad.
 */

/** Las pantallas de la TUI. Las mismas rutas que la app Android (spec R4). */
enum class Screen {
    INICIO,
    IMPORTAR,
    COMPARTIR,
    CONVERSACION,
    FRASE,
    RESTAURAR,
    DIAGNOSTICO,
}

/**
 * El viewport medido en celdas.
 *
 * [usable] es la puerta de R1: por debajo de 60x20 la TUI NO se pinta y el
 * llamador cae a modo lineas. No se "adapta" una pantalla a 40 columnas: se
 * pierde informacion y el usuario no sabe que la esta perdiendo.
 */
data class Size(
    val columns: Int = DEFAULT_COLUMNS,
    val rows: Int = DEFAULT_ROWS,
) {
    init {
        require(columns >= 0) { "columnas negativas: $columns" }
        require(rows >= 0) { "filas negativas: $rows" }
    }

    /** Cabe la TUI de pantallas. */
    val usable: Boolean get() = columns >= MIN_COLUMNS && rows >= MIN_ROWS

    companion object {
        const val MIN_COLUMNS: Int = 60
        const val MIN_ROWS: Int = 20
        const val DEFAULT_COLUMNS: Int = 80
        const val DEFAULT_ROWS: Int = 24
    }
}

/** Que campo tiene el foco. `Tab`/`Shift-Tab` lo mueven; el glifo `>` lo dice. */
enum class FocusField {
    /** Lista de contactos, mensajes o logs. */
    LIST,

    /** Compositor de la conversacion. */
    COMPOSER,

    /** Campo de texto de la pantalla (filtro, portador, ruta, frase). */
    FIELD,

    /** Campo de confirmacion escrita (restaurar identidad). */
    CONFIRM,

    /**
     * La barra de pestañas raiz.
     *
     * Es un campo focusable y no un atajo escondido: `Tab` tiene que poder
     * llegar a la barra para quien no usa `Alt-1..3`, y `Enter` activa la
     * pestaña apuntada por [Focus.index]. El indice es el ordinal de
     * [RootTab], no un indice de lista.
     */
    TABS,
}

/**
 * El foco: campo e indice DENTRO de la lista o de los campos de la pantalla.
 *
 * [index] es un cursor de seleccion, no un scroll. Se recorta contra el tamano
 * de la lista al moverlo, de modo que un estado nunca apunta fuera.
 */
data class Focus(
    val field: FocusField = FocusField.LIST,
    val index: Int = 0,
) {
    init {
        require(index >= 0) { "indice de foco negativo: $index" }
    }
}

/**
 * Presencia DECLARADA por el rele.
 *
 * ## POR QUE NO SE LLAMA ONLINE
 *
 * Porque "el rele dice que esta" no es "puede recibir un mensaje": el rele no
 * guarda, y sin sesion instalada un envio se rechaza. El glifo lo dice y la
 * leyenda de DIAGNOSTICO lo repite. `RELAY_ABSENT` es una ausencia declarada,
 * tampoco una promesa de que no exista.
 */
enum class Presence(val glyph: String) {
    UNKNOWN("?"),
    RELAY_PRESENT("\u00b7"),
    RELAY_ABSENT("-"),
}

/**
 * El enlace AL RELAY, que es lo unico que el escritorio puede medir.
 *
 * [glyph] es la notacion de la spec R9: `P2P[o]` enlace, `P2P[~]` intentando,
 * `P2P[x]` sin enlace, `P2P[?]` desconocido. Nunca describe al par.
 */
enum class LinkState(val glyph: String, val label: String) {
    UNKNOWN("P2P[?]", "desconocido"),
    DISCONNECTED("P2P[x]", "sin conexion"),
    CONNECTING("P2P[~]", "conectando"),
    ONLINE("P2P[o]", "conectado"),
    RECONNECTING("P2P[~]", "reconectando"),

    ;

    companion object {
        /**
         * Traduce el nombre de `ConnectionState` que viaja en el evento.
         *
         * Se compara por NOMBRE y no se importa el enum del protocolo: el
         * cliente envia un `String` y un valor nuevo del framework tiene que
         * degradar a [UNKNOWN], no romper la compilacion de la UI.
         */
        fun of(raw: String?): LinkState = when (raw?.trim()?.uppercase()) {
            "ONLINE" -> ONLINE
            "RECONNECTING" -> RECONNECTING
            "CONNECTING", "AUTHENTICATING" -> CONNECTING
            "DISCONNECTED" -> DISCONNECTED
            else -> UNKNOWN
        }
    }
}

/**
 * Una fila de la lista de contactos.
 *
 * `hasSession` (`S`) y `hasAckKey` (`K`) son dos hechos distintos: tener sesion
 * decide si un envio sale; tener clave de firma decide si sus `ACK` se pueden
 * autenticar. Mezclarlos en un solo indicador deja al usuario buscando el
 * problema en el sitio equivocado.
 */
data class ContactRow(
    val identityId: IdentityId,
    val fingerprint: String,
    val displayName: String = "",
    val hasSession: Boolean = false,
    val hasAckKey: Boolean = false,
    val presence: Presence = Presence.UNKNOWN,
    val lastMessage: String? = null,
    val lastAt: Long? = null,
) {
    /** Lo que se pinta como nombre: el puesto por el usuario o la huella. */
    val label: String get() = displayName.ifBlank { fingerprint }
}

/** De quien es la fila de un mensaje. */
enum class MessageDirection { IN, OUT }

/**
 * Una fila de la conversacion.
 *
 * ## LAS ETIQUETAS NO SON ADORNO
 *
 * [label] es la unica traduccion de `MessageState` a texto, y dice lo que el
 * estado SIGNIFICA: `SENT` es `OUT` (bytes aceptados por el socket), no entrega;
 * `DELIVERED` exige `ACK` verificado; `EXPIRED`/`FAILED` conservan la fila y son
 * las unicas reintentables o descartables.
 */
data class MessageRow(
    val messageId: MessageId,
    val direction: MessageDirection,
    val at: Long,
    val text: String,
    val state: MessageState,
    val failureCode: String? = null,
) {
    /** La etiqueta textual del estado. Nunca se sustituye por color. */
    val label: String
        get() = when {
            direction == MessageDirection.IN -> "IN"
            state == MessageState.SENT -> "OUT"
            state == MessageState.DELIVERED -> "ENTREGADO"
            state == MessageState.EXPIRED -> "EXPIRADO" + (failureCode?.let { " $it" } ?: "")
            state == MessageState.FAILED -> "FALLO " + (failureCode ?: "sin motivo")
            state == MessageState.CREATED -> "CREADO"
            state == MessageState.QUEUED -> "EN COLA"
            state == MessageState.SENDING -> "ENVIANDO"
            else -> state.name
        }

    /** Solo un tombstone propio se puede reintentar o descartar. */
    val retryable: Boolean
        get() = direction == MessageDirection.OUT &&
            (state == MessageState.EXPIRED || state == MessageState.FAILED)
}

/** La conversacion abierta, con su compositor. */
data class ConversationView(
    val peer: IdentityId,
    val title: String,
    val messages: List<MessageRow> = emptyList(),
    val composer: String = "",
    val peerPresence: Presence = Presence.UNKNOWN,
    val hasSession: Boolean = false,
    /**
     * Hay un envio en vuelo.
     *
     * Existe para que dos `Enter` seguidos no creen dos mensajes: el reducer no
     * tiene el `messageId` hasta que el servicio responde, asi que la unica
     * forma de no duplicar es no volver a pedir mientras el anterior no vuelve.
     */
    val sending: Boolean = false,
    /**
     * Mensajes que llegaron con el hilo desplazado hacia arriba.
     *
     * Se cuentan para decirlo (`[N nuevos ↓]`) y se ponen a cero al volver al
     * final. NO se usa para "marcar leido" ni para nada que prometa entrega: es
     * solo lo que el usuario se ha perdido de vista.
     */
    val unread: Int = 0,
)

/** Resultado de validar la frase en el servicio (nunca en el reducer). */
data class PhraseValidation(
    val wordCount: Int,
    val checksumOk: Boolean,
) {
    val ok: Boolean get() = wordCount == WORD_COUNT && checksumOk

    companion object {
        const val WORD_COUNT: Int = 24
    }
}

/**
 * La pantalla de frase y la de restauracion comparten este estado.
 *
 * ## LA FRASE NO SE GUARDA HASTA QUE SE REVELA
 *
 * `words` vacio es el estado normal: se pide al servicio solo tras confirmar el
 * reveal, y se BORRA al ocultar o al salir de la pantalla. Un buffer que sobrevive
 * a la pantalla es una frase esperando a un volcado de memoria.
 */
data class PhraseView(
    val words: List<String> = emptyList(),
    val revealed: Boolean = false,
    val restoreInput: String = "",
    val confirmation: String = "",
    val validation: PhraseValidation? = null,
    /** Identidad ANTES de restaurar, para comparar. */
    val identityNow: String? = null,
)

/** Que confirmacion esta esperando el usuario. */
enum class ConfirmKind {
    EXIT,
    DISCARD_MESSAGE,
    REVEAL_PHRASE,
    RESTORE_IDENTITY,
}

/**
 * Capa que se pinta SOBRE la pantalla actual, sin apilar.
 *
 * No es una pantalla: el foco y la navegacion de debajo se conservan, que es lo
 * que la spec R9 pide del overlay sin conexion.
 */
sealed interface Overlay {

    /** Confirmacion explicita. [messageId] solo para [ConfirmKind.DISCARD_MESSAGE]. */
    data class Confirm(
        val kind: ConfirmKind,
        val title: String,
        val detail: String? = null,
        val messageId: MessageId? = null,
        /**
         * Boton marcado. Las acciones destructivas —y las que exponen un secreto,
         * como revelar la frase— arrancan en [DialogChoice.SECONDARY]: un Enter
         * reflejo no borra nada ni muestra la frase (R17). La seguridad vive en el
         * reducer, no en la vista.
         */
        val selection: DialogChoice = DialogChoice.PRIMARY,
    ) : Overlay

    /** Sin conexion. Declara que el relay NO guarda y que la cola es local. */
    data class Offline(
        val link: LinkState,
        val attempt: Int = 0,
        val nextRetryInMs: Long? = null,
        val lastError: String? = null,
    ) : Overlay
}

/**
 * La barra de estado: enlace al relay, endpoint, sesiones y claves.
 *
 * `sessions` y `ackKeys` son CONTADORES, no listas: la cabecera solo necesita
 * saber si hay alguna. La lista completa vive en [ShellState.contacts].
 */
data class StatusLine(
    val link: LinkState = LinkState.UNKNOWN,
    val endpoint: String? = null,
    val relay: String? = null,
    val attempt: Int = 0,
    val nextRetryInMs: Long? = null,
    val lastError: String? = null,
    val localFingerprint: String? = null,
    /**
     * `identityId` local completo.
     *
     * Hace falta para saber si un mensaje entrante es propio (eco de otro
     * dispositivo) o del par: [Message.from] por si solo no lo dice sin la
     * identidad local.
     */
    val localIdentityId: String? = null,
    val sessions: Int = 0,
    val ackKeys: Int = 0,
    /**
     * F3 — frames de pares DESCONOCIDOS descartados por el intercambio.
     *
     * Es el sintoma de "el par no esta registrado aqui": el frame cifrado no es
     * un PREKEY, no se puede retener y se tira. Un cero es lo normal; un numero
     * que crece dice que el escritorio esta perdiendo trafico de un par que no
     * conoce.
     */
    val unknownPeerFrames: Int = 0,
    /**
     * Medio de DATOS (`P2P` o `RELAY`), o `null` mientras nadie lo declare.
     *
     * Es un campo distinto de [link]: el glifo describe el enlace al relay y
     * este describe por donde salen los bytes de sesion. El ACK sigue por relay.
     */
    val medium: String? = null,
    /** Motivo estable del ultimo intento P2P (`p2p_ready`, `p2p_timeout`, ...). */
    val mediumReason: String? = null,
    /** Modo P2P efectivo (`off`, `auto`, `on`), para DIAGNOSTICO. */
    val p2pMode: String? = null,
    /** URLs ICE ya redactadas. Nunca llevan credencial. */
    val iceUrls: List<String> = emptyList(),
    /** Si la configuracion ICE es host-only (lista vacia), para decirlo sin inventar. */
    val iceHostOnly: Boolean = true,
) {
    /** El indicador de la cabecera. Nunca se pinta sin etiqueta textual al lado. */
    val glyph: String get() = link.glyph
}

/** De donde sale el portador que se esta importando. */
enum class ImportSource { TEXT, FILE, OWN_QR }

/**
 * Borrador de importacion.
 *
 * El portador es material PUBLICO (no lleva secretos), pero se limpia al salir
 * igual: un buffer que sobrevive a la pantalla acaba en el portapapeles
 * equivocado o en una captura.
 *
 * [rejection] es el codigo ESTABLE del verificador cuando el portador no entro.
 * El exito no se guarda aqui: navega a la conversacion y su cabecera muestra el
 * `identityId` verificado, que es el mismo dato sin una segunda copia.
 */
data class ImportDraft(
    val source: ImportSource = ImportSource.TEXT,
    val text: String = "",
    val path: String = "",
    val expectedRoot: String = "",
    val rejection: String? = null,
)

/** Estado de la pantalla COMPARTIR. El QR se calcula en la Fase 3. */
data class ShareView(
    val carrier: String = "",
    val byteLength: Int = 0,
    val oneTimePreKeys: Int = 0,
    val showText: Boolean = false,
)

/** Nivel de una linea de la pantalla de logs. */
enum class LogLevel { INFO, WARN, ERROR }

/**
 * Una linea de la pantalla de logs.
 *
 * SIN marca temporal: el reducer es puro y no puede leer el reloj. La hora real
 * la pone `DiagLog` (que ya la estampa) y la Fase 3 la trae en el resultado de
 * carga. Inventarla aqui seria una hora que no coincide con el fichero.
 */
data class LogRow(
    val level: LogLevel,
    val channel: String?,
    val text: String,
)

/** Aviso efimero de la ultima accion. */
data class Banner(
    val level: LogLevel,
    val text: String,
)

/** Tono de un toast. Nunca es la unica señal: el glifo va delante. */
enum class ToastVariant { INFO, SUCCESS, WARNING, ERROR }

/**
 * Un aviso EFIMERO, con su caducidad ya calculada por el reducer.
 *
 * [expiresAtMs] es una marca absoluta: el renderer solo compara su `nowMs` con
 * ella y no lee el reloj. El bucle inyecta el instante con [ShellAction.Tick].
 */
data class Toast(
    val variant: ToastVariant,
    val title: String,
    val message: String? = null,
    val expiresAtMs: Long,
)

/** Cual de los dos botones de un modal esta marcado. */
enum class DialogChoice { PRIMARY, SECONDARY }


/**
 * El estado completo de la TUI.
 *
 * ## LOS CAMPOS DE PANTALLA SON OPCIONALES
 *
 * `conversation`, `importDraft` y `share` existen solo mientras su pantalla esta
 * abierta. Al volver se limpian, de modo que un `Esc` no deja un compositor con
 * texto que ya no se ve.
 */
data class ShellState(
    /**
     * Las pilas de las pestañas raiz, con la activa dentro.
     *
     * [screens] sigue siendo la pila que se navega; el cambio es que ahora hay
     * una por pestaña. `Esc` retrocede dentro de la pestaña activa y, en su
     * raiz, vuelve a la pestaña desde la que se entro; INICIO es la raiz del
     * programa y alli `Esc` pide confirmacion de salida.
     */
    val tabs: TabStacks = TabStacks.initial(),
    val focus: Focus = Focus(),
    val contacts: List<ContactRow> = emptyList(),
    /** Se pidio la lista y ya volvio (aunque este vacia). */
    val contactsLoaded: Boolean = false,
    /** Codigo estable si la lista no se pudo cargar. */
    val contactsError: String? = null,
    val filter: String = "",
    val conversation: ConversationView? = null,
    val phrase: PhraseView = PhraseView(),
    val overlay: Overlay? = null,
    val status: StatusLine = StatusLine(),
    val viewport: Size = Size(),
    val importDraft: ImportDraft? = null,
    val share: ShareView? = null,
    val logs: List<LogRow> = emptyList(),
    val logFilter: String? = null,
    val banner: Banner? = null,
    val helpVisible: Boolean = false,
    /** Se pulso Ctrl-C una vez: la segunda sale. */
    val quitArmed: Boolean = false,
    val quitting: Boolean = false,
    /**
     * El desplazamiento de cada lista.
     *
     * Se indexa por [ListId] y no por pantalla: el offset de los mensajes no se
     * pierde al irse a DIAGNOSTICO, y el de contactos no se mezcla con el de
     * logs. Un id ausente significa "arriba del todo", salvo MESSAGES, que
     * arranca con `followEnd` al abrir un hilo.
     */
    val scroll: Map<ListId, ScrollState> = emptyMap(),
    /**
     * Instante del ultimo [ShellAction.Tick].
     *
     * El reducer no lee el reloj: el bucle se lo inyecta. Es la base con la que
     * se calcula `expiresAtMs`, de modo que dos ejecuciones con los mismos
     * ticks producen exactamente el mismo estado.
     */
    val nowMs: Long = 0L,
    /** Aviso efimero; el renderer lo esconde al pasar `nowMs >= expiresAtMs`. */
    val toast: Toast? = null,
    /** Desplazamiento del overlay de ayuda (M5: ventana, no recorte). */
    val helpOffset: Int = 0,
    /**
     * Valor EFECTIVO del tracking del raton en este frame (change B).
     *
     * No es el flag de la linea de comandos: `auto` ya se resolvio contra la
     * viabilidad de la TUI y el soporte del terminal. `?` lo muestra tal cual, y
     * el renderer NO pinta nada distinto por el (el raton no anade pixeles).
     */
    val mouse: Boolean = false,
) {

    /** La pila de la pestaña activa. La navegacion sigue siendo una [ScreenStack]. */
    val screens: ScreenStack get() = tabs.current

    /** La pantalla que se pinta. */
    val screen: Screen get() = screens.current

    /** No cabe la TUI: el llamador debe caer a modo lineas (R1). */
    val tooSmall: Boolean get() = !viewport.usable

    /**
     * Una tecla imprimible es DATO y no un mando.
     *
     * El keymap no puede saber si `r` significa "reintentar" o "letra r" sin
     * mirar el foco. Esta propiedad es la unica fuente de esa decision, y por
     * eso el keymap la recibe como parametro en lugar de adivinarla.
     */
    val editingText: Boolean
        get() = when (screen) {
            Screen.INICIO -> focus.field == FocusField.FIELD
            Screen.IMPORTAR ->
                focus.field == FocusField.FIELD && importDraft?.source != ImportSource.OWN_QR
            Screen.CONVERSACION -> focus.field == FocusField.COMPOSER
            Screen.RESTAURAR -> true
            Screen.COMPARTIR, Screen.FRASE, Screen.DIAGNOSTICO -> false
        }

    /**
     * Hace falta otro `Tick` para que la pantalla siga siendo verdad.
     *
     * Solo hay temporizador si hay un toast vivo (o pendiente de limpiar), un
     * envio en vuelo o una conexion intentandose. Sin eso la TUI sigue dirigida
     * por eventos: ni un repintado de mas.
     */
    val needsTick: Boolean
        get() = toast != null ||
            conversation?.sending == true ||
            status.link == LinkState.CONNECTING ||
            status.link == LinkState.RECONNECTING

    /** El toast que aun no ha caducado segun [nowMs]. */
    fun visibleToast(nowMs: Long): Toast? = toast?.takeIf { nowMs < it.expiresAtMs }

    /** La lista de INICIO ya filtrada por [filter], sin tocar el estado. */
    fun filteredContacts(): List<ContactRow> {
        val needle = filter.trim()
        if (needle.isEmpty()) return contacts
        return contacts.filter { row ->
            row.label.contains(needle, ignoreCase = true) ||
                row.identityId.value.contains(needle, ignoreCase = true)
        }
    }

    /** El contacto con [peer], o `null`. */
    fun contact(peer: IdentityId): ContactRow? =
        contacts.firstOrNull { it.identityId == peer }

    /** El scroll de [id]; el de por defecto es "arriba del todo". */
    fun scrollOf(id: ListId): ScrollState = scroll[id] ?: ScrollState()

    /**
     * Las lineas de log que pasan el filtro de canal.
     *
     * La lista, el foco y el scroll tienen que mirar la MISMA coleccion o el
     * cursor apuntaria a una fila que no se pinta. Por eso el filtro vive aqui y
     * no en la vista.
     */
    fun visibleLogs(): List<LogRow> {
        val filtro = logFilter ?: return logs
        return logs.filter { it.channel == filtro }
    }

    /** El mensaje seleccionado en la conversacion, si el foco esta en la lista. */
    fun selectedMessage(): MessageRow? {
        val conv = conversation ?: return null
        if (focus.field != FocusField.LIST) return null
        return conv.messages.getOrNull(focus.index)
    }
}
