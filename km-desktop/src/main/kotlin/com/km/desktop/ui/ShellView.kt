package com.km.desktop.ui

import com.km.desktop.ui.layout.Chrome
import com.km.desktop.ui.layout.HitMap
import com.km.desktop.ui.layout.HitRegion
import com.km.desktop.ui.layout.Layout
import com.km.desktop.ui.layout.Rect
import com.km.desktop.ui.render.BodyRow
import com.km.desktop.ui.render.ComposedFrame
import com.km.desktop.ui.render.FrameComposer
import com.km.desktop.ui.render.Grid
import com.km.desktop.ui.render.OverlayButton
import com.km.desktop.ui.render.OverlayFrame
import com.km.desktop.ui.render.StyledSpan
import com.km.desktop.ui.screen.CompartirView
import com.km.desktop.ui.screen.ConversacionView
import com.km.desktop.ui.screen.DiagnosticoView
import com.km.desktop.ui.screen.FraseView
import com.km.desktop.ui.screen.ImportarView
import com.km.desktop.ui.screen.InicioView
import com.km.desktop.ui.screen.RestaurarView
import com.km.desktop.ui.theme.Attr
import com.km.desktop.ui.theme.Style
import com.km.desktop.ui.widget.ActionBar
import com.km.desktop.ui.widget.DialogView
import com.km.desktop.ui.widget.StatusBar
import com.km.desktop.ui.widget.TabsView
import com.km.desktop.ui.widget.ToastView

/**
 * El KERNEL de pintura: estado -> rejilla -> lineas.
 *
 * ## EL CAMINO
 *
 * `Layout` mide las bandas -> la cabecera pinta marca y pestañas -> la vista de
 * la pantalla llena el cuerpo -> la barra de estado y el pie -> el overlay
 * (ventaneado) -> una rejilla de ancho exacto -> `RenderedLine[]`.
 *
 * Cada pieza hace una cosa y ninguna lee el terminal, el reloj ni el sistema de
 * ficheros. [com.km.desktop.shell.ScreenWriter] sigue siendo el unico que
 * escribe.
 *
 * ## M5: JERARQUIA
 *
 * Cabecera de dos filas (marca, seccion y pestañas), cuerpo sin marco,
 * barra de estado con identidad/relay/presencia/contadores/volatilidad y pie
 * con las teclas globales. Los separadores son rotulos escasos (`▌ SECCION ───`),
 * no una caja alrededor de todo. El ancho lo decide [Layout]: INICIO,
 * CONVERSACION y DIAGNOSTICO son de ANCHO COMPLETO; solo los formularios
 * (FRASE, RESTAURAR) y los dialogos se centran a un ancho fijo.
 *
 * ## LO QUE NO CAMBIA
 *
 * - El QR conserva sus tres bandas de tamano: 85x43..88x46 completo y SIN marco,
 *   89x47..99x49 completo CON marco compacto (sin pestanas ni barra) y 100x50 o
 *   mas por el camino normal, con las bandas completas.
 * - Por debajo de 60x20 se devuelve el aviso de minimo, no una pantalla adaptada.
 * - Todo texto no confiable se sanea en [Grid.text] antes de medirse.
 * - El renderer no lee el reloj: recibe [RenderClock] y compara.
 */
object ShellView {

    /** Columnas que come el marco compacto del QR (borde izquierdo y derecho). */
    private const val QR_MARCO_COLUMNAS: Int = 4

    /** Filas que come el marco compacto del QR (borde, cabecera, acciones, borde). */
    private const val QR_MARCO_FILAS: Int = 4

    /** Viewport minimo en el que, ademas del QR con marco, caben las bandas completas. */
    private const val QR_BANDAS_COLUMNAS: Int = 100
    private const val QR_BANDAS_FILAS: Int = 50

    /** Las acciones del QR compacto y del portador suelto (no hay pie de bandas). */
    private const val ACCIONES_COMPARTIR: String = "[C] copiar  [W] .km  [T] texto  [ESC] volver"

    fun renderLines(
        state: ShellState,
        ascii: Boolean = false,
        color: Boolean = true,
        clock: RenderClock = RenderClock.UTC_EPOCH,
    ): List<RenderedLine> = renderFrame(state, ascii, color, clock).lines

    /**
     * El frame COMPLETO: lineas + hit-map del MISMO layout (change B).
     *
     * ## POR QUE UNA SOLA PASADA
     *
     * El hit-map no se recalcula por fuera: `FrameComposer` emite cada region
     * mientras pinta su fila. Asi "donde esta el contacto 3" y "donde se pinto
     * el contacto 3" son la misma respuesta, y no hay una segunda geometria que
     * se quede vieja al redimensionar.
     *
     * El raton NO pinta nada: [lines] es exactamente lo que devolvia
     * [renderLines], y los 21 goldens `f2-*` son el gate de esa promesa.
     */
    fun renderFrame(
        state: ShellState,
        ascii: Boolean = false,
        color: Boolean = true,
        clock: RenderClock = RenderClock.UTC_EPOCH,
    ): RenderedFrame {
        val tooSmall = Box.tooSmall(state.viewport)
        if (tooSmall != null) {
            return RenderedFrame(
                tooSmall.split("; ").map { parte ->
                    RenderedLine(Sanitizer.pad(parte, state.viewport.columns))
                },
                HitMap.EMPTY,
            )
        }
        qrPantalla(state, ascii, color)?.let { return it }

        val columns = state.viewport.columns.coerceAtLeast(Box.MIN_WIDTH)
        val rows = state.viewport.rows.coerceAtLeast(3)
        val viewport = Size(columns, rows)
        val chrome = Layout.chrome(state.screen)
        val frame = Layout.frame(viewport, chrome)
        val content = Layout.contentFor(state.screen, viewport)
        val listRows = Layout.listRows(state.screen, viewport)
        // Las pantallas con lista ya entregan SOLO su ventana (M3). Las que no
        // tienen lista no se desplazan: si alguna linea sobrara se corta al
        // alto de la banda. M6 elimino `Truncation.LEGACY`, que sustituia el
        // centro por `… (N lineas)` y escondia justo lo que hay que leer.
        val cuerpoCompleto = body(state, ascii, color, clock, listRows, content.w)
        val cuerpo = cuerpoCompleto.take(frame.body.h)

        val compuesto: ComposedFrame = FrameComposer.composeWithHits(
            frame = frame,
            chrome = chrome,
            content = content,
            body = cuerpo,
            header = header(state, content.w, ascii),
            status = status(state, content.w, ascii, clock.nowMs),
            footer = footer(state, content.w, ascii),
            banner = null,
            overlay = overlayFrame(state, ascii),
            ascii = ascii,
        )
        return RenderedFrame(toLines(compuesto.grid), compuesto.hitMap)
    }

    // ------------------------------------------------------------------
    // Cabecera, estado y pie
    // ------------------------------------------------------------------

    private fun header(state: ShellState, width: Int, ascii: Boolean): List<BodyRow> {
        val g = com.km.desktop.ui.theme.Glyphs(ascii)
        // La marca va en MAYUSCULAS, como la cabecera de Android: es el nombre
        // del producto, no una frase.
        val marca = "KEYMESSAGE"
        val seccion = sectionTitle(state)
        val izquierda = marca + " " + g.longVertical + " " + seccion
        val derecha = state.status.glyph + " " + state.status.link.label
        val primera = twoColumns(izquierda, derecha, width)
        // Las pestañas son clicables (`Act(TabTo)`) pero el FOCO de la barra se
        // pinta igual que siempre: pasarle un `Focus()` vacio conserva el
        // aspecto y los goldens; el reverse de la barra no es parte de B.
        val tabs = TabsView.row(state.tabs, ascii).copy(style = Style.MUTED)
        return listOf(
            BodyRow(primera, style = Style.BRAND),
            tabs,
        )
    }

    private fun sectionTitle(state: ShellState): String = when (state.screen) {
        Screen.INICIO -> "INICIO"
        Screen.IMPORTAR -> "NUEVO CONTACTO"
        Screen.COMPARTIR -> "MI CONTACTO"
        Screen.CONVERSACION -> "CONVERSACION" +
            (state.conversation?.title?.let { " \u00b7 " + Sanitizer.text(it) } ?: "")
        Screen.FRASE -> "FRASE DE RECUPERACION"
        Screen.RESTAURAR -> "RESTAURAR IDENTIDAD"
        Screen.DIAGNOSTICO -> "DIAGNOSTICO"
    }

    /**
     * Izquierda y derecha en una fila.
     *
     * Si no caben las dos se conserva la IZQUIERDA (la seccion) y se recorta el
     * estado: saber donde estas pesa mas que el detalle del enlace.
     */
    private fun twoColumns(left: String, right: String, width: Int): String {
        val l = Sanitizer.text(left)
        val r = Sanitizer.text(right)
        val hueco = width - Sanitizer.width(l) - Sanitizer.width(r)
        return when {
            hueco >= 1 -> l + " ".repeat(hueco) + r
            width <= 0 -> ""
            else -> Sanitizer.truncate(l, width)
        }
    }

    /**
     * La barra de estado de la banda, o el aviso que la sustituye.
     *
     * Prioridad: salida armada > toast > banner > ayuda abierta > estado. En
     * todos los casos es UNA fila del mismo frame: no hay segundo escritor.
     */
    private fun status(
        state: ShellState,
        width: Int,
        ascii: Boolean,
        nowMs: Long,
    ): BodyRow {
        if (state.quitArmed) {
            return BodyRow("Ctrl-C otra vez para salir", style = Style.WARNING)
        }
        state.visibleToast(nowMs)?.let { return ToastView.row(it, ascii) }
        state.banner?.let { banner ->
            val glifo = if (banner.level == LogLevel.ERROR) "! " else "\u00b7 "
            val estilo = when (banner.level) {
                LogLevel.ERROR -> Style.DANGER
                LogLevel.WARN -> Style.WARNING
                LogLevel.INFO -> Style.MUTED
            }
            return BodyRow(glifo + Sanitizer.text(banner.text), style = estilo)
        }
        if (state.helpVisible) {
            val g = com.km.desktop.ui.theme.Glyphs(ascii)
            return BodyRow("? ayuda abierta " + g.bullet + " PgUp/PgDn desplaza", style = Style.MUTED)
        }
        return StatusBar.row(state, width, ascii, nowMs)
    }

    /**
     * El PIE: la barra de acciones de la pantalla, como la de Android.
     *
     * Antes eran las mismas cuatro teclas globales en todas las pantallas. El pie
     * ahora dice lo que se puede hacer AQUI (`[N] nuevo`, `[R] reintentar`,
     * `[X] exportar`), que es lo que hacia la barra inferior de la app. `?`
     * sigue abriendo el keymap completo.
     */
    private fun footer(state: ShellState, width: Int, ascii: Boolean): BodyRow =
        ActionBar.row(actionsFor(state.screen), width, ascii)

    /**
     * Las acciones del pie de [screen], con el MISMO texto de siempre.
     *
     * Cada accion es clicable (`Act`) y cada `Intent` existe tambien en el
     * keymap: el pie no gana capacidades, gana un atajo. El orden es el que ya
     * estaba, para que el pie y el raton no cuenten dos historias.
     */
    private fun actionsFor(screen: Screen): List<ActionBar.Action> = when (screen) {
        Screen.INICIO -> listOf(
            ActionBar.Action("[N] nuevo", Intent.NewContact),
            ActionBar.Action("[C] compartir", Intent.Share),
            ActionBar.Action("[D] diag", Intent.Diagnostics),
            ActionBar.Action("[F] frase", Intent.PhraseScreen),
            ActionBar.Action("[I] id", Intent.Identity),
            ActionBar.Action("[Q]uit", Intent.Quit),
            ActionBar.Action("[?] ayuda", Intent.Help),
        )

        Screen.CONVERSACION -> listOf(
            ActionBar.Action("[ENTER] enviar", Intent.Activate),
            ActionBar.Action("[R] reintentar", Intent.Retry),
            ActionBar.Action("[D] descartar", Intent.Discard),
            ActionBar.Action("[?] ayuda", Intent.Help),
        )

        Screen.DIAGNOSTICO -> listOf(
            ActionBar.Action("[F] filtrar", Intent.CycleFilter),
            ActionBar.Action("[C] copiar", Intent.Copy),
            ActionBar.Action("[X] exportar", Intent.ExportLogs),
            ActionBar.Action("[?] ayuda", Intent.Help),
        )

        Screen.COMPARTIR -> listOf(
            ActionBar.Action("[C] copiar", Intent.Copy),
            ActionBar.Action("[W] .km", Intent.SaveKm),
            ActionBar.Action("[T] texto", Intent.ShowText),
            ActionBar.Action("[?] ayuda", Intent.Help),
        )

        Screen.IMPORTAR -> listOf(
            ActionBar.Action("[1] texto", Intent.SourceText),
            ActionBar.Action("[2] fichero .km", Intent.SourceFile),
            ActionBar.Action("[3] mi QR", Intent.SourceOwnQr),
            ActionBar.Action("[?] ayuda", Intent.Help),
        )

        Screen.FRASE -> listOf(
            ActionBar.Action("[V] ver", Intent.Reveal),
            ActionBar.Action("[C] copiar", Intent.Copy),
            ActionBar.Action("[O] ocultar", Intent.Hide),
            ActionBar.Action("[R] restaurar", Intent.RestoreScreen),
            ActionBar.Action("[?] ayuda", Intent.Help),
        )

        Screen.RESTAURAR -> listOf(
            ActionBar.Action("[?] ayuda", Intent.Help),
        )
    }

    // ------------------------------------------------------------------
    // Pantallas
    // ------------------------------------------------------------------

    private fun body(
        state: ShellState,
        ascii: Boolean,
        color: Boolean,
        clock: RenderClock,
        rows: Int,
        width: Int,
    ): List<BodyRow> = when (state.screen) {
        Screen.INICIO -> InicioView.body(state, ascii, clock, rows, width)
        Screen.IMPORTAR -> ImportarView.body(state, ascii, color)
        Screen.COMPARTIR -> CompartirView.body(state, ascii, color)
        Screen.CONVERSACION -> ConversacionView.body(state, ascii, clock, rows, width)
        Screen.FRASE -> FraseView.body(state, ascii)
        Screen.RESTAURAR -> RestaurarView.body(state, ascii)
        Screen.DIAGNOSTICO -> DiagnosticoView.body(state, ascii, rows, width)
    }

    // ------------------------------------------------------------------
    // QR: tres bandas de tamano
    // ------------------------------------------------------------------

    /**
     * El QR cuando las bandas completas no le dejan sitio.
     *
     * ## TRES BANDAS, NO DOS
     *
     * El QR NUNCA se recorta ni se sustituyen filas: si no cabe el marco, se
     * pinta el codigo entero y se sacrifica el adorno. Los umbrales conservan el
     * criterio de siempre —85x43 sin marco, 89x47 con marco— y anaden el
     * intermedio:
     *
     * | Viewport      | Que se pinta                                                        |
     * |---------------|---------------------------------------------------------------------|
     * | 85x43..88x46  | QR completo, SIN marco (las 43 filas intactas)                      |
     * | 89x47..99x49  | QR completo CON marco compacto: borde, portador y acciones; sin pestanas ni barra |
     * | >=100x50      | camino normal: bandas completas (cabecera, estado y pie) + marco    |
     * | <85x43        | `null`: la pantalla declara el minimo y ofrece texto/`.km`          |
     *
     * El marco compacto gasta cuatro filas (borde, cabecera, acciones, borde) y
     * dos columnas, de modo que 89x47 es el minimo en el que vuelve. No es un
     * umbral nuevo: es el mismo `qrColumns + 4` / `qrRows + 4` de la
     * implementacion anterior, ahora SEPARADO del umbral de las bandas. Antes
     * ambos se confundian y a 89x47 el QR se quedaba sin marco en silencio.
     */
    private fun qrPantalla(state: ShellState, ascii: Boolean, color: Boolean): RenderedFrame? {
        if (ascii || !color) return null
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

        if (share.showText) return portadorSuelto(state, share, qrColumns, qrRows, columns, rows)

        val cabeMarco = columns >= qrColumns + QR_MARCO_COLUMNAS &&
            rows >= qrRows + QR_MARCO_FILAS
        if (!cabeMarco) return qrSuelto(share, qr, columns, rows)
        // Con 100x50 caben las bandas completas ademas del marco: ahi manda el
        // camino normal y este atajo no aplica.
        if (columns >= QR_BANDAS_COLUMNAS && rows >= QR_BANDAS_FILAS) return null
        return qrConMarco(share, qr, columns, rows)
    }

    /**
     * El portador en modo texto cuando las bandas no caben.
     *
     * El texto se envuelve (nunca se trunca: un `.km` cortado no verifica) y se
     * pinta sin marco. Si cabe junto a las bandas, devuelve `null` para que siga
     * el camino normal.
     */
    private fun portadorSuelto(
        state: ShellState,
        share: ShareView,
        qrColumns: Int,
        qrRows: Int,
        columns: Int,
        rows: Int,
    ): RenderedFrame? {
        // El umbral no depende del texto: si el QR mas su marco no caben, las
        // bandas tampoco, y el portador se pinta suelto aunque sus lineas quepan.
        val overhead = if (Layout.chrome(state.screen) == Chrome.BANDS) 6 else 4
        if (columns >= qrColumns && rows >= qrRows + overhead) return null
        val envuelto = Sanitizer.lines(share.carrier).flatMap { envolver(it, columns) }
        if (envuelto.size > rows) return null
        return suelto(envuelto, share, columns, rows, texto = true)
    }

    /** QR completo SIN marco: la banda 85x43..88x46 (y cualquier alto justo). */
    private fun qrSuelto(
        share: ShareView,
        qr: List<String>,
        columns: Int,
        rows: Int,
    ): RenderedFrame = suelto(qr, share, columns, rows, texto = false)

    /**
     * QR completo CON marco compacto: borde, portador, codigo y acciones.
     *
     * Cuatro filas de chrome y dos columnas de borde. No es el marco de las
     * bandas (`Chrome.BANDS` gasta cuatro filas mas sin dejar sitio): es lo
     * minimo que conserva la lectura de "esto es un QR con su portador y sus
     * salidas" sin tocar el codigo.
     */
    private fun qrConMarco(
        share: ShareView,
        qr: List<String>,
        columns: Int,
        rows: Int,
    ): RenderedFrame {
        val interior = Sanitizer.width(qr.first())
        val ancho = interior + 2
        val x = ((columns - ancho) / 2).coerceAtLeast(0)
        val cabecera = "portador " + share.byteLength + " B \u00b7 opk " + share.oneTimePreKeys +
            " \u00b7 QR completo: marco compacto"
        val acciones = ACCIONES_COMPARTIR
        val filas = qr.size + QR_MARCO_FILAS
        val grid = Grid.blank(columns, rows)
        val y0 = ((rows - filas) / 2).coerceAtLeast(0)

        grid.put(x, y0, "\u250c" + "\u2500".repeat(interior) + "\u2510")
        var y = y0 + 1
        lado(grid, x, y, cabecera, interior, Style.NONE)
        y++
        qr.forEach { fila ->
            lado(grid, x, y, fila, interior, Style.QR)
            y++
        }
        val filaAcciones = y
        lado(grid, x, y, acciones, interior, Style.NONE)
        y++
        grid.put(x, y, "\u2514" + "\u2500".repeat(interior) + "\u2518")
        // El marco compacto NO tiene pie de bandas: sus acciones son el pie.
        return RenderedFrame(toLines(grid), HitMap(regionesDeAcciones(grid.rowText(filaAcciones), filaAcciones)))
    }

    /** Una fila interior del marco compacto: `│ contenido │`. */
    private fun lado(grid: Grid, x: Int, y: Int, texto: String, interior: Int, style: Style) {
        grid.put(x, y, "\u2502")
        grid.put(x + 1, y, texto, style, interior)
        grid.put(x + interior + 1, y, "\u2502")
    }

    /** El QR o el portador a pantalla completa, sin marco. */
    private fun suelto(
        contenido: List<String>,
        share: ShareView,
        columns: Int,
        rows: Int,
        texto: Boolean,
    ): RenderedFrame {
        val cabecera = "portador " + share.byteLength + " B \u00b7 opk " + share.oneTimePreKeys +
            if (texto) {
                " \u00b7 texto del portador ([T] vuelve al QR)"
            } else {
                " \u00b7 QR completo: al minimo se pinta sin marco"
            }
        val acciones = ACCIONES_COMPARTIR
        val extra = (rows - contenido.size).coerceAtLeast(0)
        val usaCabecera = if (extra >= 2) 1 else 0
        val usaAcciones = if (extra - usaCabecera >= 1) 1 else 0
        val relleno = (rows - contenido.size - usaCabecera - usaAcciones).coerceAtLeast(0)
        val rellenoArriba = relleno / 2

        val frame = Layout.frame(Size(columns, rows), Chrome.NONE)
        val grid = Grid.blank(frame.body.w, frame.body.h)
        var y = frame.body.y
        repeat(rellenoArriba) { y++ }

        /**
         * Pinta [linea] centrada cuando es el QR, y a la izquierda cuando es el
         * portador en texto.
         *
         * ## POR QUE CENTRADO
         *
         * El QR se justificaba a la izquierda y el resto de la fila quedaba
         * blanco hasta el borde: el usuario lo reprodujo como "una barra blanca
         * horizontal que cubre todo el alto del QR". El codigo va centrado y el
         * blanco solo cubre sus celdas ([Grid.putExact]).
         *
         * El portador en TEXTO no se centra: es una cadena larga que se envuelve
         * y se copia a mano; centrarla solo dificulta leerla.
         */
        fun pintar(linea: String, estilo: Style) {
            val recortada = Sanitizer.truncate(linea, frame.body.w)
            val ancho = Sanitizer.width(recortada)
            val x = if (texto) 0 else ((frame.body.w - ancho) / 2).coerceAtLeast(0)
            grid.putExact(x, y, recortada, estilo, ancho)
            y++
        }

        if (usaCabecera == 1) pintar(cabecera, Style.NONE)
        val estiloQr = if (texto) Style.NONE else Style.QR
        contenido.forEach { linea -> pintar(linea, estiloQr) }
        val filaAcciones = y
        if (usaAcciones == 1) pintar(acciones, Style.NONE)
        val regiones = if (usaAcciones == 1) {
            regionesDeAcciones(grid.rowText(filaAcciones), filaAcciones)
        } else {
            emptyList()
        }
        return RenderedFrame(toLines(grid), HitMap(regiones))
    }

    /**
     * Las acciones del portador suelto, con su intencion.
     *
     * Se localizan en la linea YA pintada (y por tanto ya recortada): una
     * etiqueta que el ancho se comio no se emite. Son las mismas teclas del pie
     * de COMPARTIR, asi que el raton no anade nada que el teclado no tenga.
     */
    private fun regionesDeAcciones(linea: String, fila: Int): List<HitRegion> {
        val acciones = listOf(
            "[C] copiar" to Intent.Copy,
            "[W] .km" to Intent.SaveKm,
            "[T] texto" to Intent.ShowText,
            "[ESC] volver" to Intent.Back,
        )
        val regiones = ArrayList<HitRegion>(acciones.size)
        var desde = 0
        acciones.forEach { (etiqueta, intent) ->
            val indice = linea.indexOf(etiqueta, desde)
            if (indice < 0) return@forEach
            val inicio = Sanitizer.width(linea.substring(0, indice))
            regiones += HitRegion(Rect(inicio, fila, Sanitizer.width(etiqueta), 1), MouseIntent.Act(intent))
            desde = indice + etiqueta.length
        }
        return regiones
    }

    /**
     * Envuelve una linea YA saneada a [columns] celdas sin partir un caracter.
     *
     * Solo se usa para el portador en modo texto al minimo: `Sanitizer.truncate`
     * cortaria el portador y el `.km` copiado a mano dejaria de verificar.
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

    // ------------------------------------------------------------------
    // Overlay
    // ------------------------------------------------------------------

    /** La capa modal, ya resuelta: que se pinta encima y con que titulo. */
    private fun overlayFrame(state: ShellState, ascii: Boolean): OverlayFrame? {
        if (state.helpVisible) {
            // La linea del raton sustituye al separador vacio: el numero de
            // lineas no cambia y `[?] cerrar` sigue siendo la ultima (la ventana
            // de la ayuda se calcula con ese total).
            val raton = "raton " + (if (state.mouse) "ON" else "OFF") +
                " \u00b7 clic y rueda; hover/doble clic fuera"
            val contenido = Keymap.help(state.screen) + listOf(raton, "[?] cerrar")
            return OverlayFrame("AYUDA", contenido, state.helpOffset, dialog = false)
        }
        return when (val overlay = state.overlay) {
            is Overlay.Confirm -> OverlayFrame(
                "CONFIRMAR",
                DialogView.confirm(overlay, ascii),
                dialog = true,
                buttons = listOf(
                    OverlayButton(DialogView.side(DialogChoice.PRIMARY, ascii), MouseIntent.Dialog(DialogChoice.PRIMARY)),
                    OverlayButton(DialogView.side(DialogChoice.SECONDARY, ascii), MouseIntent.Dialog(DialogChoice.SECONDARY)),
                ),
            )
            is Overlay.Offline -> OverlayFrame(
                "SIN CONEXION",
                DialogView.offline(overlay, ascii),
                dialog = true,
            )
            null -> null
        }
    }

    // ------------------------------------------------------------------
    // Rejilla -> lineas
    // ------------------------------------------------------------------

    /**
     * Convierte la rejilla en las lineas que el escritor ya sabe pintar.
     *
     * - `focus`: cualquier celda de la fila con reverse. El glifo del foco ya va
     *   en el texto; el reverse es el refuerzo, nunca la unica senal.
     * - `palette`: QR si alguna celda pide el contraste fijo. Es el MISMO
     *   contrato que consumia la implementacion anterior (`30;47`).
     * - `spans`: tramos de texto con el mismo [Style]. `ui/` nombra roles;
     *   `shell/Sgr` los traduce a SGR y MONO no emite ninguno.
     */
    private fun toLines(grid: Grid): List<RenderedLine> =
        (0 until grid.height).map { fila ->
            val celdas = grid.row(fila)
            val spans = ArrayList<StyledSpan>()
            celdas.forEach { celda ->
                val ultimo = spans.lastOrNull()
                if (ultimo != null && ultimo.style == celda.style) {
                    spans[spans.lastIndex] = ultimo.copy(text = ultimo.text + celda.text)
                } else {
                    spans += StyledSpan(celda.text, celda.style)
                }
            }
            RenderedLine(
                text = celdas.joinToString("") { it.text },
                focus = celdas.any { Attr.REVERSE in it.style.attrs },
                palette = if (celdas.any { it.style.isQr }) LinePalette.QR else LinePalette.DEFAULT,
                spans = spans,
            )
        }
}
