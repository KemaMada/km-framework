package com.km.desktop.ui

/**
 * Lo que una tecla QUIERE decir en una pantalla.
 *
 * Es un tipo propio y no un [ShellAction] porque una tecla no puede fabricar un
 * evento del motor ni un resultado de efecto: esos vienen de fuera. Un `Intent`
 * es la unica frontera entre "que tecla" y "que transicion".
 */
sealed interface Intent {
    data object None : Intent

    /** Texto visible que va al campo con foco. */
    data class Insert(val text: String) : Intent

    data object DeleteBack : Intent
    data object NextField : Intent
    data object PrevField : Intent
    data object MoveUp : Intent
    data object MoveDown : Intent
    data object PageUp : Intent
    data object PageDown : Intent
    data object PageStart : Intent
    data object PageEnd : Intent
    data object Activate : Intent
    data object Back : Intent
    data object Help : Intent
    data object Redraw : Intent
    data object Cancel : Intent
    data object Quit : Intent

    data object Filter : Intent
    data object NewContact : Intent
    data object Share : Intent
    data object Logs : Intent
    data object Diagnostics : Intent
    data object PhraseScreen : Intent
    data object RestoreScreen : Intent
    data object Identity : Intent

    data object Retry : Intent
    data object Discard : Intent
    data object Reveal : Intent
    data object Hide : Intent
    data object Copy : Intent
    data object SaveKm : Intent
    data object ShowText : Intent

    data object CycleFilter : Intent
    data object ExportLogs : Intent
    data object Paste : Intent

    data object SourceText : Intent
    data object SourceFile : Intent
    data object SourceOwnQr : Intent

    /**
     * Cambiar de pestaña raiz.
     *
     * Es un `Intent` y no un `ShellAction` por la misma razon que el resto: una
     * tecla no fabrica eventos del motor. La pestaña viaja en el dato para que
     * el reducer no tenga que adivinar de que tecla vino.
     */
    data class TabTo(val tab: RootTab) : Intent
}

/** Una fila del keymap, con su etiqueta y su descripcion para `?`. */
data class Binding(
    val stroke: KeyStroke,
    val label: String,
    val intent: Intent,
    val description: String,
)

/**
 * Las teclas de cada pantalla, consistentes con la TUI Android.
 *
 * ## DOS TABLAS, NO UNA
 *
 * [global] son las teclas que existen en todas partes (foco, mover, Enter, Esc,
 * ayuda). [perScreen] son las de una pantalla. La resolucion es: primero la
 * pantalla, despues lo global. Asi `c` en COMPARTIR copia, aunque en otro sitio
 * pudiera significar otra cosa, sin renunciar a `Tab` ni a `Esc`.
 *
 * ## TECLA IMPRIMIBLE = DATO CUANDO SE ESCRIBE
 *
 * En un campo de texto, `r` es la letra r. El keymap NO adivina: recibe
 * `editing` (que sale de [ShellState.editingText]) y, cuando es `true`, solo
 * considera mandos las teclas no imprimibles (Enter, Esc, Tab, Ctrl-...). Es la
 * diferencia entre un compositor usable y uno en el que pulsar `r` reintenta un
 * mensaje en vez de escribir.
 *
 * ## `?` ES LA AYUDA, Y SALE DE ESTA MISMA TABLA
 *
 * [help] se genera recorriendo [bindings]. Una ayuda escrita a mano se
 * desincroniza del keymap a la primera tecla nueva.
 */
object Keymap {

    private val global: List<Binding> = listOf(
        Binding(KeyStroke(Key.Tab), "Tab", Intent.NextField, "siguiente campo"),
        Binding(KeyStroke.shift(Key.Tab), "Shift-Tab", Intent.PrevField, "campo anterior"),
        Binding(KeyStroke(Key.Up), "Arriba", Intent.MoveUp, "subir"),
        Binding(KeyStroke.char('k'), "k", Intent.MoveUp, "subir"),
        Binding(KeyStroke(Key.Down), "Abajo", Intent.MoveDown, "bajar"),
        Binding(KeyStroke.char('j'), "j", Intent.MoveDown, "bajar"),
        // PgUp/PgDn ya llegaban decodificados de `5~`/`6~` y no tenian binding:
        // una tecla que el terminal manda y la TUI ignora.
        Binding(KeyStroke(Key.PageUp), "RePág", Intent.PageUp, "pagina arriba"),
        Binding(KeyStroke(Key.PageDown), "AvPág", Intent.PageDown, "pagina abajo"),
        Binding(KeyStroke(Key.Home), "Inicio", Intent.PageStart, "al principio"),
        Binding(KeyStroke.char('g'), "g", Intent.PageStart, "al principio"),
        Binding(KeyStroke(Key.End), "Fin", Intent.PageEnd, "al final"),
        Binding(KeyStroke.char('G'), "G", Intent.PageEnd, "al final"),
        Binding(KeyStroke(Key.Enter), "Enter", Intent.Activate, "activar"),
        Binding(KeyStroke(Key.Escape), "Esc", Intent.Back, "volver"),
        Binding(KeyStroke(Key.Backspace), "Retroceso", Intent.DeleteBack, "borrar"),
        Binding(KeyStroke(Key.Delete), "Supr", Intent.DeleteBack, "borrar"),
        Binding(KeyStroke.char('?'), "?", Intent.Help, "ayuda y keymap"),
        Binding(KeyStroke.ctrl('l'), "Ctrl-L", Intent.Redraw, "redibujar"),
        Binding(KeyStroke.ctrl('v'), "Ctrl-V", Intent.Paste, "pegar"),
        Binding(KeyStroke.ctrl('c'), "Ctrl-C", Intent.Cancel, "cancelar (dos veces salir)"),
        // Las tres raices, con la MISMA tecla en toda la app. `Alt-N` no es un
        // atajo de pantalla: es navegacion, y por eso vive en la tabla global.
        Binding(KeyStroke.alt('1'), "Alt-1", Intent.TabTo(RootTab.INICIO), "pestana INICIO"),
        Binding(KeyStroke.alt('2'), "Alt-2", Intent.TabTo(RootTab.FRASE), "pestana FRASE"),
        Binding(KeyStroke.alt('3'), "Alt-3", Intent.TabTo(RootTab.DIAGNOSTICO), "pestana DIAGNOSTICO"),
    )

    private val perScreen: Map<Screen, List<Binding>> = mapOf(
        Screen.INICIO to listOf(
            Binding(KeyStroke.char('/'), "/", Intent.Filter, "filtrar contactos"),
            Binding(KeyStroke.char('n'), "n", Intent.NewContact, "nuevo contacto"),
            Binding(KeyStroke.char('c'), "c", Intent.Share, "compartir mi contacto"),
            Binding(KeyStroke.char('l'), "l", Intent.Logs, "logs"),
            Binding(KeyStroke.char('d'), "d", Intent.Diagnostics, "diagnostico"),
            Binding(KeyStroke.char('f'), "f", Intent.PhraseScreen, "frase de recuperacion"),
            Binding(KeyStroke.char('i'), "i", Intent.Identity, "identidad local"),
            Binding(KeyStroke.char('q'), "q", Intent.Quit, "salir"),
        ),
        Screen.IMPORTAR to listOf(
            Binding(KeyStroke.char('1'), "1", Intent.SourceText, "pegar/texto"),
            Binding(KeyStroke.char('2'), "2", Intent.SourceFile, "fichero .km"),
            Binding(KeyStroke.char('3'), "3", Intent.SourceOwnQr, "ver mi QR"),
        ),
        Screen.COMPARTIR to listOf(
            Binding(KeyStroke.char('c'), "c", Intent.Copy, "copiar portador"),
            Binding(KeyStroke.char('w'), "w", Intent.SaveKm, "guardar .km"),
            Binding(KeyStroke.char('t'), "t", Intent.ShowText, "ver texto"),
            Binding(KeyStroke.char('q'), "q", Intent.Back, "ocultar QR"),
        ),
        Screen.CONVERSACION to listOf(
            Binding(KeyStroke.char('r'), "R", Intent.Retry, "reintentar tombstone"),
            Binding(KeyStroke.char('d'), "D", Intent.Discard, "descartar (confirmar)"),
            Binding(KeyStroke.alt(Key.Enter), "Alt-Enter", Intent.Insert("\n"), "salto de linea"),
        ),
        Screen.FRASE to listOf(
            Binding(KeyStroke.char('v'), "v", Intent.Reveal, "revelar frase (confirmar)"),
            Binding(KeyStroke.char('c'), "c", Intent.Copy, "copiar (aviso)"),
            Binding(KeyStroke.char('o'), "o", Intent.Hide, "ocultar"),
            Binding(KeyStroke.char('r'), "r", Intent.RestoreScreen, "restaurar desde frase"),
        ),
        Screen.RESTAURAR to emptyList(),
        Screen.DIAGNOSTICO to listOf(
            Binding(KeyStroke.char('f'), "f", Intent.CycleFilter, "ciclar filtro de canal"),
            Binding(KeyStroke.char('c'), "c", Intent.Copy, "copiar log filtrado"),
            Binding(KeyStroke.char('x'), "x", Intent.ExportLogs, "exportar log"),
        ),
    )

    /**
     * Los bindings de [screen]: los suyos primero y despues los globales que no
     * esten ya cogidos. El orden es el de la ayuda.
     */
    fun bindings(screen: Screen): List<Binding> {
        val propios = perScreen[screen].orEmpty()
        val ocupadas = propios.map { it.stroke }.toSet()
        return propios + global.filter { it.stroke !in ocupadas }
    }

    /**
     * Resuelve [stroke] en [screen].
     *
     * @param editing `true` si la tecla imprimible debe ir al campo de texto.
     * @return [Intent.None] si la tecla no hace nada aqui. No se inventa un
     *   mando por defecto.
     */
    fun resolve(screen: Screen, editing: Boolean, stroke: KeyStroke): Intent {
        val match = bindings(screen).firstOrNull {
            it.stroke == stroke && !(editing && it.stroke.printable)
        }
        if (match != null) return match.intent
        if (editing && stroke.printable) return Intent.Insert(stroke.text!!)
        return Intent.None
    }

    /** Las lineas de ayuda de [screen], generadas desde la tabla. */
    fun help(screen: Screen): List<String> =
        bindings(screen).map { "${it.label.padEnd(10)} ${it.description}" }

    /** Todas las pantallas, para tests y para la ayuda completa. */
    fun screens(): List<Screen> = Screen.entries.toList()
}
