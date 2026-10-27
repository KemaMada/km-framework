package com.km.desktop.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Una linea YA pintada, con la marca de foco.
 *
 * `focus` NO es color: es la instruccion de que esa fila lleva el foco de la
 * pantalla. El escritor del terminal la puede pintar con reverse video, pero el
 * glifo `>` va en el TEXTO, de modo que una terminal sin color siga diciendo
 * donde esta el foco. Ese es el requisito R1: nunca solo color.
 */
data class RenderedLine(
    val text: String,
    val focus: Boolean = false,
)

/**
 * El RENDERER puro de la TUI: estado -> lineas.
 *
 * ## QUE NO HACE
 *
 * No escribe, no lee, no abre ficheros, no toca JLine, no mira el reloj. Recibe
 * el estado (que ya trae el viewport) y devuelve lineas de ancho EXACTO. Eso es
 * lo que permite probar la pintura en JVM: se construye un `ShellState`, se
 * llama a [render] y se afirma sobre las lineas, sin un pty.
 *
 * ## EL INVARIANTE
 *
 * Toda linea mide `viewport.columns` celdas segun [Sanitizer.width]. El texto no
 * confiable (payload, alias, error del rele) pasa por [Sanitizer] ANTES de
 * medirse: una secuencia `ESC[2J` en un mensaje no puede borrar la pantalla.
 *
 * ## FALLBACKS
 *
 * - `--ascii`: cajas con `+ - |`, sin glifos Unicode. Los glifos de presencia se
 *   mantienen como ASCII (`?`, `-`, `S`, `K`).
 * - `--accessible` NO usa este renderer: es salida lineal.
 * - Por debajo de 60x20 se devuelve el aviso de minimo, no una pantalla
 *   "adaptada" que esconde datos.
 */
object ShellRenderer {

    private val HORA: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    // ------------------------------------------------------------------
    // Entrada
    // ------------------------------------------------------------------

    fun render(state: ShellState, ascii: Boolean = false): List<RenderedLine> {
        val tooSmall = Box.tooSmall(state.viewport)
        if (tooSmall != null) {
            // No se recorta el aviso: si se truncara a 40 columnas, "usa --no-tui"
            // desaparece justo cuando es la salida que el usuario necesita.
            return tooSmall.split("; ").map { parte ->
                RenderedLine(Sanitizer.pad(parte, state.viewport.columns))
            }
        }
        // El QR es el UNICO contenido que no puede pasar por la caja: si el
        // viewport lo admite pero el marco no, se pinta solo. Ver [qrSinMarco].
        qrSinMarco(state, ascii)?.let { return it }
        val base = when (state.screen) {
            Screen.INICIO -> inicio(state, ascii)
            Screen.IMPORTAR -> importar(state, ascii)
            Screen.COMPARTIR -> compartir(state, ascii)
            Screen.CONVERSACION -> conversacion(state, ascii)
            Screen.FRASE -> frase(state, ascii)
            Screen.RESTAURAR -> restaurar(state, ascii)
            Screen.DIAGNOSTICO -> diagnostico(state, ascii)
        }
        return decorate(base, state, ascii)
    }

    // ------------------------------------------------------------------
    // Pantallas
    // ------------------------------------------------------------------

    private fun inicio(state: ShellState, ascii: Boolean): List<Cell> {
        val status = state.status
        val filas = state.filteredContacts()
        val cells = ArrayList<Cell>()
        cells += Cell(
            "yo " + (status.localFingerprint?.take(16) ?: "sin identidad") +
                "   relay " + Sanitizer.truncate(status.endpoint ?: "sin destino", 34) +
                "   sesiones " + status.sessions + "   claves " + status.ackKeys,
        )
        if (state.focus.field == FocusField.FIELD) {
            cells += Cell("> filtro: " + state.filter + "\u2588", focus = true)
        } else if (state.filter.isNotEmpty()) {
            cells += Cell("filtro: " + state.filter + "   (Esc lo limpia)")
        }
        cells += Cell("CONTACTOS", separator = true)
        when {
            state.contactsError != null ->
                cells += Cell("no se pudo cargar la lista codigo=" + state.contactsError)

            !state.contactsLoaded ->
                cells += Cell("cargando contactos\u2026")

            filas.isEmpty() && state.filter.isNotBlank() ->
                cells += Cell("ningun contacto coincide con el filtro")

            filas.isEmpty() -> {
                cells += Cell("sin contactos: [N]uevo importa uno por texto, .km o QR")
                cells += Cell("la lista esta SOLO EN MEMORIA: se pierde al reiniciar")
            }

            else -> filas.forEachIndexed { i, fila ->
                val foco = state.focus.field == FocusField.LIST && i == state.focus.index
                val marca = if (foco) "> " else "  "
                val hora = fila.lastAt?.let { " " + hora(it) } ?: ""
                val sesion = if (fila.hasSession) "S" else "-"
                val clave = if (fila.hasAckKey) "K" else "-"
                val ultimo = fila.lastMessage?.let { "  " + Sanitizer.truncate(it, 30) } ?: "  (sin mensajes)"
                cells += Cell(
                    marca + Sanitizer.truncate(fila.label, 20).padEnd(20) +
                        " " + fila.presence.glyph + " " + sesion + " " + clave + hora + ultimo,
                    focus = foco,
                )
            }
        }
        cells += Cell(
            "? presencia desconocida \u00b7 relay-presente S sesion K clave",
        )
        cells += Cell("CONTACTOS SOLO EN MEMORIA: se pierden al reiniciar")
        cells += Cell("[N]uevo [C]ompartir [L]ogs [D]iag [F]rase [I]d [Q]uit")
        return cells
    }

    private fun importar(state: ShellState, ascii: Boolean): List<Cell> {
        val draft = state.importDraft ?: ImportDraft()
        val cells = ArrayList<Cell>()
        cells += Cell("1 texto  2 fichero .km  3 ver mi QR")
        cells += Cell("origen: " + when (draft.source) {
            ImportSource.TEXT -> "TEXTO (pega una linea o un bloque)"
            ImportSource.FILE -> "FICHERO .km (ISO-8859-1, tope 64 KiB, sin recortar)"
            ImportSource.OWN_QR -> "MI QR (esta pantalla no escanea QR)"
        }, separator = true)
        when (draft.source) {
            ImportSource.TEXT -> {
                cells += Cell("portador:", focus = state.focus.field == FocusField.FIELD)
                val texto = draft.text
                if (texto.isEmpty()) {
                    cells += Cell("> ", focus = state.focus.field == FocusField.FIELD)
                } else {
                    Sanitizer.lines(texto).forEachIndexed { i, linea ->
                        cells += Cell((if (i == 0) "> " else "  ") + linea)
                    }
                    cells += Cell("(" + texto.length + " caracteres)")
                }
            }

            ImportSource.FILE -> {
                cells += Cell("ruta:", focus = state.focus.field == FocusField.FIELD)
                cells += Cell("> " + draft.path + "\u2588", focus = state.focus.field == FocusField.FIELD)
                cells += Cell("se lee tal cual: sin trim y con tope de 64 KiB en LECTURA")
            }

            ImportSource.OWN_QR -> {
                cells += Cell("tu portador (0 OPK) para que lo lea el movil")
                cells += qrCells(state, ascii)
            }
        }
        draft.rejection?.let { cells += Cell("RECHAZADO " + it) }
        cells += Cell("[ENTER] importar   [ESC] volver")
        return cells
    }

    private fun compartir(state: ShellState, ascii: Boolean): List<Cell> {
        val share = state.share
        val cells = ArrayList<Cell>()
        if (share == null || share.carrier.isEmpty()) {
            cells += Cell("generando portador\u2026")
            cells += Cell("[ESC] volver")
            return cells
        }
        cells += Cell("portador " + share.byteLength + " B \u00b7 opk " + share.oneTimePreKeys)
        if (ascii) {
            cells += Cell("QR desactivado en --ascii: usa [C] copiar o [W] guardar .km")
        } else {
            cells += qrCells(state, ascii)
        }
        if (share.showText) {
            Sanitizer.lines(share.carrier).forEach { cells += Cell(it) }
        }
        cells += Cell("[C] copiar (OSC 52, no confirmable)  [W] .km  [T] texto  [ESC] volver")
        return cells
    }

    /**
     * El QR, o el aviso de que no cabe.
     *
     * Este es el camino CON MARCO. Cuando el QR entra en el viewport pero no en
     * la caja, [qrSinMarco] lo pinta antes de llegar aqui; en ese caso estas
     * celdas nunca se recortan porque no se usan.
     */
    private fun qrCells(state: ShellState, ascii: Boolean): List<Cell> {
        val carrier = state.share?.carrier ?: return listOf(Cell("aun no hay portador"))
        if (ascii) return listOf(Cell("QR no disponible en --ascii"))
        return when (val layout = QrRenderer.layout(carrier, state.viewport)) {
            is QrLayout.Fits -> layout.lines.map { Cell(it) }
            is QrLayout.TooLarge -> listOf(
                Cell(
                    "el QR no cabe: minimo " + layout.minColumns + "x" + layout.minRows +
                        "; usa [C] copiar o [W] guardar .km",
                ),
            )
        }
    }

    /**
     * El QR a pantalla completa cuando el marco no cabe: el camino "QR sin caja".
     *
     * ## EL FALLO QUE CIERRA
     *
     * [QrRenderer.layout] mide contra el VIEWPORT, pero [decorate] pinta dentro
     * de una caja: 4 columnas de marco y padding, 2 filas de bordes y ademas la
     * cabecera y la barra de acciones del cuerpo. A 85x43 el QR "cabe" segun el
     * layout y NO cabe en el marco: `Box.line` lo recortaba a 81 columnas y
     * [fit] sustituia filas por `… (N lineas)`. El usuario veia un QR corrupto y
     * la pantalla no decia que no cabia.
     *
     * Por eso, cuando el QR entra en el viewport pero no en la caja, se pinta
     * SOLO el QR: sin bordes, sin titulo y sin barra de acciones. El minimo
     * efectivo sigue siendo 85x43 y el QR llega COMPLETO: lo que se sacrifica es
     * el marco, nunca el QR. A partir de 89x47 (85+4 columnas, 43+4 filas)
     * vuelve el camino normal con marco.
     *
     * ## QUE NO ENTRA AQUI
     *
     * - `--ascii` no pinta QR: no hay nada que preservar.
     * - Por debajo del minimo, [QrRenderer.layout] da [QrLayout.TooLarge] y la
     *   pantalla con marco declara el minimo y ofrece texto/`.km`.
     * - Ayuda y overlay son MODALES: mientras estan abiertos se pinta la capa
     *   con marco, porque lo que el usuario esta leyendo es esa capa, no el QR.
     * - Con `[T]` (texto) activo se pinta el PORTADOR envuelto y no el QR: en el
     *   minimo no caben los dos y el usuario pidio el texto.
     *
     * Devuelve `null` cuando no aplica, y entonces el llamador sigue con la caja
     * normal. El resultado mide SIEMPRE `viewport.rows` x `viewport.columns`.
     */
    private fun qrSinMarco(state: ShellState, ascii: Boolean): List<RenderedLine>? {
        if (ascii) return null
        if (state.helpVisible || state.overlay != null) return null
        val mirandoQr = state.screen == Screen.COMPARTIR ||
            (state.screen == Screen.IMPORTAR && state.importDraft?.source == ImportSource.OWN_QR)
        if (!mirandoQr) return null
        val share = state.share ?: return null
        if (share.carrier.isEmpty()) return null

        val columns = state.viewport.columns.coerceAtLeast(Box.MIN_WIDTH)
        val rows = state.viewport.rows.coerceAtLeast(3)
        val qr = when (val layout = QrRenderer.layout(share.carrier, state.viewport)) {
            is QrLayout.Fits -> layout.lines
            is QrLayout.TooLarge -> return null
        }
        val qrColumns = Sanitizer.width(qr.first())
        val qrRows = qr.size
        // Cabecera + acciones: el cuerpo gasta 2 filas, y los bordes otras 2.
        val cabeConMarco = columns >= qrColumns + 4 && rows >= qrRows + 4
        if (cabeConMarco) return null

        val contenido: List<String> = if (share.showText) {
            val envuelto = Sanitizer.lines(share.carrier).flatMap { envolver(it, columns) }
            // Si el texto no cabe entero, se cede al camino con marco: alli se
            // dice cuantas lineas se pierden en vez de cortar en silencio.
            if (envuelto.size > rows) return null
            envuelto
        } else {
            qr
        }

        val cabecera = "portador " + share.byteLength + " B \u00b7 opk " + share.oneTimePreKeys +
            if (share.showText) {
                " \u00b7 texto del portador ([T] vuelve al QR)"
            } else {
                " \u00b7 QR completo: al minimo se pinta sin marco"
            }
        val acciones = "[C] copiar  [W] .km  [T] texto  [ESC] volver"
        val extra = (rows - contenido.size).coerceAtLeast(0)
        val usaCabecera = if (extra >= 2) 1 else 0
        val usaAcciones = if (extra - usaCabecera >= 1) 1 else 0
        val relleno = (rows - contenido.size - usaCabecera - usaAcciones).coerceAtLeast(0)
        val rellenoArriba = relleno / 2

        val salida = ArrayList<RenderedLine>(rows)
        repeat(rellenoArriba) { salida += RenderedLine(" ".repeat(columns)) }
        if (usaCabecera == 1) salida += RenderedLine(Sanitizer.pad(cabecera, columns))
        contenido.forEach { salida += RenderedLine(Sanitizer.pad(it, columns)) }
        if (usaAcciones == 1) salida += RenderedLine(Sanitizer.pad(acciones, columns))
        while (salida.size < rows) salida += RenderedLine(" ".repeat(columns))
        return salida
    }

    /**
     * Envuelve una linea YA saneada a [columns] celdas sin partir un caracter.
     *
     * Solo se usa para el portador en modo texto al minimo, donde el ancho de la
     * celda manda: `Sanitizer.truncate` cortaria el portador y el `.km` copiado
     * a mano dejaria de verificar.
     */
    private fun envolver(linea: String, columns: Int): List<String> {
        if (columns <= 0) return emptyList()
        val limpia = Sanitizer.text(linea)
        if (Sanitizer.width(limpia) <= columns) return listOf(limpia)
        val salida = ArrayList<String>()
        val sb = StringBuilder()
        var usado = 0
        var i = 0
        while (i < limpia.length) {
            val cp = limpia.codePointAt(i)
            val glifo = String(Character.toChars(cp))
            val ancho = Sanitizer.width(glifo)
            if (usado + ancho > columns && sb.isNotEmpty()) {
                salida += sb.toString()
                sb.setLength(0)
                usado = 0
                continue
            }
            sb.append(glifo)
            usado += ancho
            i += Character.charCount(cp)
        }
        if (sb.isNotEmpty()) salida += sb.toString()
        return salida
    }

    private fun conversacion(state: ShellState, ascii: Boolean): List<Cell> {
        val conv = state.conversation ?: return listOf(Cell("sin conversacion abierta"))
        val cells = ArrayList<Cell>()
        val sesion = if (conv.hasSession) "S sesion" else "sin sesion: el envio se rechazara"
        cells += Cell(conv.peerPresence.glyph + " " + presencia(conv.peerPresence) + " \u00b7 " + sesion)
        cells += Cell("MENSAJES", separator = true)
        if (conv.messages.isEmpty()) {
            cells += Cell("sin mensajes (la cola vive en memoria; el relay no guarda)")
        } else {
            conv.messages.forEachIndexed { i, fila ->
                val foco = state.focus.field == FocusField.LIST && i == state.focus.index
                val marca = if (foco) "> " else "  "
                val quien = if (fila.direction == MessageDirection.OUT) "yo " else "el "
                val etiqueta = " [" + Sanitizer.text(fila.label) + "]"
                cells += Cell(
                    marca + hora(fila.at) + " " + quien + "> " +
                        Sanitizer.truncate(fila.text, 34) + etiqueta,
                    focus = foco,
                )
            }
        }
        cells += Cell("", separator = true)
        val focoComp = state.focus.field == FocusField.COMPOSER
        cells += Cell("> " + conv.composer + "\u2588", focus = focoComp)
        val seleccionado = state.selectedMessage()
        cells += Cell(
            when {
                conv.sending -> "enviando\u2026 si el nucleo acepta, la fila pasa a OUT (no es entrega)"
                seleccionado?.retryable == true ->
                    "[R] reintentar (mismo id)   [D] descartar (confirma)   [ENTER] enviar"
                else -> "[ENTER] enviar   [Tab] lista/compositor   [ESC] volver"
            },
        )
        return cells
    }

    private fun frase(state: ShellState, ascii: Boolean): List<Cell> {
        val phrase = state.phrase
        val cells = ArrayList<Cell>()
        cells += Cell("ALCANCE: restaura la IDENTIDAD solamente.")
        cells += Cell("NO restaura contactos, sesiones ni mensajes.")
        cells += Cell("Los contactos se vuelven a intercambiar (QR/.km).", separator = true)
        if (!phrase.revealed) {
            cells += Cell("la frase esta OCULTA; [V] la muestra tras confirmar")
        } else if (phrase.words.isEmpty()) {
            cells += Cell("cargando frase\u2026")
        } else {
            val columnas = 4
            val filas = (phrase.words.size + columnas - 1) / columnas
            for (fila in 0 until filas) {
                val sb = StringBuilder()
                for (col in 0 until columnas) {
                    val i = col * filas + fila
                    val palabra = phrase.words.getOrNull(i) ?: continue
                    sb.append((i + 1).toString().padStart(2, '0')).append(' ')
                        .append(Sanitizer.truncate(palabra, 13).padEnd(14))
                }
                cells += Cell(sb.toString())
            }
        }
        cells += Cell("la frase NO va a logs, historial ni .km", separator = true)
        cells += Cell("[V] ver (confirmar)  [C] copiar (aviso)  [O] ocultar  [R] restaurar  [ESC] volver")
        return cells
    }

    private fun restaurar(state: ShellState, ascii: Boolean): List<Cell> {
        val phrase = state.phrase
        val cells = ArrayList<Cell>()
        cells += Cell("restaura SOLO la identidad; las sesiones y contactos se pierden")
        cells += Cell("pega la frase (24 palabras):", separator = true)
        val entrada = phrase.restoreInput
        cells += Cell("> " + Sanitizer.truncate(entrada, state.viewport.columns - 6) + "\u2588", focus = state.focus.field == FocusField.FIELD)
        val validez = phrase.validation
        cells += Cell(
            if (validez == null) "valida: sin comprobar"
            else "valida: " + validez.wordCount + "/24 \u00b7 checksum " + if (validez.checksumOk) "OK" else "FALLA",
        )
        cells += Cell("identidad actual: " + (phrase.identityNow ?: "desconocida"))
        cells += Cell("escribe SOBRESCRIBIR para confirmar:", separator = true)
        cells += Cell(
            "> " + Sanitizer.truncate(phrase.confirmation, state.viewport.columns - 6) + "\u2588",
            focus = state.focus.field == FocusField.CONFIRM,
        )
        cells += Cell("[ENTER] restaurar   [ESC] cancelar")
        return cells
    }

    private fun diagnostico(state: ShellState, ascii: Boolean): List<Cell> {
        val status = state.status
        val cells = ArrayList<Cell>()
        cells += Cell(
            "transporte " + status.link.glyph + " " + status.link.label +
                "  endpoint " + Sanitizer.truncate(status.endpoint ?: "\u2014", 40),
        )
        cells += Cell(
            "relay " + (status.relay?.take(16) ?: "\u2014") +
                "  intento " + status.attempt +
                "  sesiones " + status.sessions +
                "  claves " + status.ackKeys,
        )
        status.nextRetryInMs?.let { cells += Cell("proximo intento en " + it + " ms") }
        status.lastError?.let { cells += Cell("ultimo error " + Sanitizer.truncate(it, 48)) }
        cells += Cell("cola: local en memoria (sin consulta desde la UI); el relay NO guarda")
        cells += Cell("ALCANCE: P2P es el enlace AL RELAY; NO es presencia del par.", separator = true)
        val filtro = state.logFilter ?: "TODOS"
        cells += Cell("LOGS  filtro: " + filtro + "   [" + state.logs.size + "/" + ShellReducer.MAX_LOGS + "]", separator = true)
        val visibles = state.logs.filter { filtro == "TODOS" || it.channel == filtro }
        if (visibles.isEmpty()) {
            cells += Cell("sin lineas para este filtro")
        } else {
            visibles.forEach { cells += Cell(Sanitizer.truncate(it.text, state.viewport.columns - 6)) }
        }
        cells += Cell("sin payload, sin claves, sin frase", separator = true)
        cells += Cell("[F] filtrar  [C] copiar  [X] exportar  [ESC] volver")
        return cells
    }

    // ------------------------------------------------------------------
    // Marco, overlay, foco
    // ------------------------------------------------------------------

    private data class Cell(
        val text: String,
        val focus: Boolean = false,
        val separator: Boolean = false,
    )

    private fun decorate(body: List<Cell>, state: ShellState, ascii: Boolean): List<RenderedLine> {
        val columns = state.viewport.columns.coerceAtLeast(Box.MIN_WIDTH)
        val rows = state.viewport.rows.coerceAtLeast(3)
        val titulo = titulo(state)
        val presupuesto = rows - 2
        val ajustado = fit(body, presupuesto)
        val lineas = ArrayList<RenderedLine>(rows)
        lineas += RenderedLine(Box.top(titulo, columns, ascii))
        ajustado.forEach { cell ->
            val texto = if (cell.separator) {
                Box.separator(columns, cell.text, ascii)
            } else {
                Box.line(cell.text, columns, ascii)
            }
            lineas += RenderedLine(texto, cell.focus)
        }
        while (lineas.size < rows - 1) lineas.add(RenderedLine(Box.line("", columns, ascii)))
        lineas += RenderedLine(Box.bottom(columns, ascii))

        val conAviso = conBanner(lineas, state, columns, ascii)
        val conOverlay = withOverlay(conAviso, state, columns, rows, ascii)
        return conOverlay
    }

    private fun titulo(state: ShellState): String {
        val nombre = when (state.screen) {
            Screen.INICIO -> "KeyMessage"
            Screen.IMPORTAR -> "NUEVO CONTACTO"
            Screen.COMPARTIR -> "MI CONTACTO"
            Screen.CONVERSACION -> state.conversation?.title ?: "CONVERSACION"
            Screen.FRASE -> "FRASE DE RECUPERACION"
            Screen.RESTAURAR -> "RESTAURAR IDENTIDAD"
            Screen.DIAGNOSTICO -> "DIAGNOSTICO"
        }
        val estado = when (state.screen) {
            Screen.INICIO, Screen.CONVERSACION, Screen.DIAGNOSTICO ->
                "  " + state.status.glyph + " " + state.status.link.label
            else -> ""
        }
        return Sanitizer.text(nombre + estado)
    }

    /** Aviso efimero en la ultima fila de contenido, si lo hay. */
    private fun conBanner(
        lineas: List<RenderedLine>,
        state: ShellState,
        columns: Int,
        ascii: Boolean,
    ): List<RenderedLine> {
        val banner = state.banner
        val aviso = when {
            state.quitArmed -> Cell("Ctrl-C otra vez para salir")
            banner != null -> Cell((if (banner.level == LogLevel.ERROR) "! " else "\u00b7 ") + banner.text)
            state.helpVisible -> Cell("? ayuda abierta")
            else -> return lineas
        }
        if (lineas.size < 2) return lineas
        val copia = lineas.toMutableList()
        copia[copia.size - 2] = RenderedLine(Box.line(aviso.text, columns, ascii))
        return copia
    }

    /**
     * Superpone el overlay SIN perder la pantalla de debajo.
     *
     * La caja se centra y se pinta encima; el foco de las filas de debajo NO se
     * mueve, que es lo que R9 pide del aviso sin conexion.
     */
    private fun withOverlay(
        lineas: List<RenderedLine>,
        state: ShellState,
        columns: Int,
        rows: Int,
        ascii: Boolean,
    ): List<RenderedLine> {
        val overlay = state.overlay
        val contenido: List<String> = when {
            state.helpVisible -> listOf("AYUDA Y KEYMAP") + Keymap.help(state.screen) +
                listOf("", "[?] cerrar")

            overlay is Overlay.Confirm -> listOf(
                overlay.title,
                overlay.detail ?: "",
                "",
                "[ENTER] confirmar   [ESC] cancelar",
            )

            overlay is Overlay.Offline -> listOf(
                "estado " + overlay.link.glyph + " " + overlay.link.label,
                "intento " + overlay.attempt +
                    (overlay.nextRetryInMs?.let { " \u00b7 proximo en " + it + " ms" } ?: ""),
                "ultimo error " + (overlay.lastError?.let { Sanitizer.truncate(it, 40) } ?: "\u2014"),
                "",
                "El relay NO guarda mensajes: la cola es local",
                "y vive mientras el proceso siga abierto.",
                "",
                "[ESC] seguir",
            )

            else -> return lineas
        }
        // La caja ocupa el ancho COMPLETO: asi cada fila sigue midiendo
        // `columns` y el repintado no deja restos de la pantalla de debajo.
        val ancho = columns.coerceAtLeast(Box.MIN_WIDTH)
        val caja = Box.frame(
            when {
                state.helpVisible -> "AYUDA"
                overlay is Overlay.Confirm -> "CONFIRMAR"
                else -> "SIN CONEXION"
            },
            contenido,
            ancho,
            ascii,
        )
        if (caja.size + 2 > rows) {
            // No cabe con marco: se muestran las lineas sin caja, sin recortar el
            // mensaje a la mitad.
            return caja.map { RenderedLine(Sanitizer.pad(it, columns)) }
        }
        val copia = lineas.toMutableList()
        val inicio = ((rows - caja.size) / 2).coerceAtLeast(1)
        caja.forEachIndexed { i, linea ->
            val destino = inicio + i
            if (destino < copia.size) {
                copia[destino] = RenderedLine(linea)
            }
        }
        return copia
    }

    /**
     * Recorta el cuerpo al presupuesto de filas conservando cabeza y cola.
     *
     * La cabeza es el contexto (cabecera/filtro) y la cola son las acciones: si
     * hay que perder algo, se pierde el centro, no el pie que dice como salir.
     */
    private fun fit(body: List<Cell>, presupuesto: Int): List<Cell> {
        if (presupuesto <= 0) return emptyList()
        if (body.size <= presupuesto) return body
        val cabeza = body.take(2).take(presupuesto)
        val cola = body.takeLast(3).take((presupuesto - cabeza.size).coerceAtLeast(0))
        val medio = body.drop(cabeza.size).dropLast(cola.size)
        val hueco = presupuesto - cabeza.size - cola.size - 1
        if (hueco <= 0) return cabeza + cola
        val recortado = medio.take(hueco)
        val perdidas = body.size - cabeza.size - recortado.size - cola.size
        return cabeza + recortado +
            Cell("\u2026 (" + perdidas.coerceAtLeast(1) + " lineas)") + cola
    }

    private fun hora(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(HORA)

    private fun presencia(presence: Presence): String = when (presence) {
        Presence.UNKNOWN -> "presencia desconocida"
        Presence.RELAY_PRESENT -> "relay-presente (NO es disponibilidad)"
        Presence.RELAY_ABSENT -> "ausente del relay"
    }
}
