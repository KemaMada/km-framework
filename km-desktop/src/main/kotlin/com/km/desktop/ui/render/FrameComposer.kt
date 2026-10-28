package com.km.desktop.ui.render

import com.km.desktop.ui.Box
import com.km.desktop.ui.LinePalette
import com.km.desktop.ui.MouseIntent
import com.km.desktop.ui.Sanitizer
import com.km.desktop.ui.layout.Chrome
import com.km.desktop.ui.layout.Frame
import com.km.desktop.ui.layout.HitMap
import com.km.desktop.ui.layout.HitRegion
import com.km.desktop.ui.layout.Rect
import com.km.desktop.ui.theme.Attr
import com.km.desktop.ui.theme.Glyphs
import com.km.desktop.ui.theme.Style

/**
 * Lo que se pinta ENCIMA de una pantalla: la ayuda o un dialogo.
 *
 * El modelo de que hay capa modal (`Overlay` en el estado) no vive aqui: aqui
 * solo va la pintura ya resuelta (titulo y lineas). [offset] es la ventana de
 * un contenido mas alto que la pantalla (la ayuda en 60x20/80x24).
 *
 * [buttons] son los botones del dialogo con su intencion de raton. No llevan
 * coordenadas: el compositor las calcula al pintar, que es el unico momento en
 * que el ancho de la caja y la ventana del overlay se conocen.
 */
data class OverlayFrame(
    val title: String,
    val content: List<String>,
    val offset: Int = 0,
    /** Un dialogo pinta backdrop y ancho 60/88; la ayuda, no. */
    val dialog: Boolean = false,
    val buttons: List<OverlayButton> = emptyList(),
)

/** Un boton del overlay: el texto que se pinta y lo que hace al pulsarlo. */
data class OverlayButton(
    val label: String,
    val intent: MouseIntent,
)

/** El resultado del compositor: la rejilla y su hit-map, del MISMO recorrido. */
data class ComposedFrame(
    val grid: Grid,
    val hitMap: HitMap,
)

/**
 * El COMPOSITOR: bandas + filas de pantalla + estado + pie + overlay.
 *
 * ## POR QUE UN SOLO SITIO COMPONE
 *
 * Porque el marco, la barra de estado, el pie y el overlay tienen que caer en
 * celdas exactas y en un orden concreto. Si cada pantalla se pintara su marco,
 * habria siete copias del calculo de bordes y siete oportunidades de anchos
 * distintos.
 *
 * ## BANDS NO DIBUJA CAJA
 *
 * La jerarquia de F2 no es una caja unica: cabecera (marca + pestañas), cuerpo,
 * barra de estado y pie. El ancho lo decide `Layout`: las listas (INICIO,
 * CONVERSACION, DIAGNOSTICO) son de ancho completo y solo los formularios se
 * centran; aqui se escribe dentro de [content] y el resto queda en blanco. Que
 * no haya marco es el cambio visual, no una regresion.
 *
 * ## EL BLANCO DEL QR SON CELDAS, NO UNA LINEA
 *
 * Una fila marcada como QR se centra y solo sus celdas llevan [Style.QR]. El
 * resto de la fila queda en el estilo por defecto: ni el relleno de la rejilla
 * ni el `ESC[K` del escritor pueden convertir el margen del codigo en una banda
 * blanca de borde a borde.
 *
 * ## ORDEN DE PINTADO (y de hit-map)
 *
 * pantalla -> estado/pie -> overlay. El overlay GANA: mientras esta abierto lo
 * que se lee es esa capa. El hit-map se construye en el MISMO bucle que pinta y
 * [HitMap.at] devuelve la ULTIMA region que contiene: el dialogo gana a la fila
 * tapada. Con overlay NO se conservan las regiones de fondo: `Dismiss` a
 * pantalla completa y los botones encima.
 */
object FrameComposer {

    /**
     * Compone el frame completo.
     *
     * [body] ya viene RECORTADO al alto de la banda: el compositor no decide
     * que se pierde. [header] son las filas de la cabecera (titulo y pestañas).
     */
    fun compose(
        frame: Frame,
        chrome: Chrome,
        content: Rect,
        body: List<BodyRow>,
        header: List<BodyRow> = emptyList(),
        status: BodyRow? = null,
        footer: BodyRow? = null,
        banner: BodyRow? = null,
        overlay: OverlayFrame? = null,
        ascii: Boolean = false,
    ): Grid = composeWithHits(
        frame = frame,
        chrome = chrome,
        content = content,
        body = body,
        header = header,
        status = status,
        footer = footer,
        banner = banner,
        overlay = overlay,
        ascii = ascii,
    ).grid

    /**
     * Compone y devuelve tambien el hit-map.
     *
     * Es el camino de produccion (change B). [compose] existe para los tests y
     * los llamadores que solo quieren la rejilla.
     */
    fun composeWithHits(
        frame: Frame,
        chrome: Chrome,
        content: Rect,
        body: List<BodyRow>,
        header: List<BodyRow> = emptyList(),
        status: BodyRow? = null,
        footer: BodyRow? = null,
        banner: BodyRow? = null,
        overlay: OverlayFrame? = null,
        ascii: Boolean = false,
    ): ComposedFrame {
        val grid = Grid.blank(frame.body.right.coerceAtLeast(0), alto(frame))
        val hits = ArrayList<HitRegion>()
        if (chrome == Chrome.BOX) {
            paintBox(grid, frame, header.firstOrNull()?.text.orEmpty(), banner, body, ascii)
            val pintado = if (overlay != null) {
                paintOverlay(grid, overlay, ascii, hits)
            } else {
                grid
            }
            if (overlay != null) {
                hits.add(0, HitRegion(Rect(0, 0, pintado.width, pintado.height), MouseIntent.Dismiss))
            }
            return ComposedFrame(pintado, HitMap(hits))
        }
        val g = Glyphs(ascii)
        paintBand(grid, frame.header, header, content, g, hits)
        paintBodyInto(grid, frame, chrome, content, body, g, hits)
        status?.let { paintBandRow(grid, frame.status, it, content, g, hits) }
        footer?.let { paintBandRow(grid, frame.footer, it, content, g, hits) }
        val pintado = if (overlay != null) {
            // El fondo NO se conserva: mientras hay capa modal, lo de debajo no
            // responde. Se pinta el overlay y se emiten SOLO sus regiones.
            hits.clear()
            val conOverlay = paintOverlay(grid, overlay, ascii, hits)
            hits.add(0, HitRegion(Rect(0, 0, conOverlay.width, conOverlay.height), MouseIntent.Dismiss))
            conOverlay
        } else {
            grid
        }
        return ComposedFrame(pintado, HitMap(hits))
    }

    /** Alto total del frame: la banda mas baja que ocupa celdas. */
    private fun alto(frame: Frame): Int =
        frame.bands.filter { !it.isEmpty }.maxOfOrNull { it.bottom } ?: 0

    // ------------------------------------------------------------------
    // BOX: la caja de M2, conservada para los tests del layout
    // ------------------------------------------------------------------

    private fun paintBox(
        grid: Grid,
        frame: Frame,
        title: String,
        banner: BodyRow?,
        body: List<BodyRow>,
        ascii: Boolean,
    ) {
        val ancho = grid.width
        if (frame.header.h > 0) grid.putRow(frame.header.y, Box.top(title, ancho, ascii))
        for (fila in frame.body.y until frame.body.bottom) {
            grid.putRow(fila, Box.line("", ancho, ascii))
        }
        if (frame.status.h > 0) grid.putRow(frame.status.y, Box.bottom(ancho, ascii))
        body.forEachIndexed { i, fila ->
            val y = frame.body.y + i
            if (y >= frame.body.bottom) return@forEachIndexed
            val texto = if (fila.separator) {
                Box.separator(ancho, fila.text, ascii)
            } else {
                Box.line(fila.text, ancho, ascii)
            }
            grid.put(frame.body.x, y, texto, styleOf(fila))
        }
        if (banner != null && !frame.body.isEmpty) {
            val y = frame.body.bottom - 1
            val texto = if (banner.separator) {
                Box.separator(ancho, banner.text, ascii)
            } else {
                Box.line(banner.text, ancho, ascii)
            }
            grid.put(frame.body.x, y, texto, styleOf(banner))
        }
    }

    // ------------------------------------------------------------------
    // BANDS
    // ------------------------------------------------------------------

    private fun paintBand(
        grid: Grid,
        band: Rect,
        rows: List<BodyRow>,
        content: Rect,
        glyphs: Glyphs,
        hits: MutableList<HitRegion>?,
    ) {
        rows.take(band.h).forEachIndexed { i, fila ->
            paintBandRow(grid, band.copy(y = band.y + i, h = 1), fila, content, glyphs, hits)
        }
    }

    private fun paintBandRow(
        grid: Grid,
        band: Rect,
        fila: BodyRow,
        content: Rect,
        glyphs: Glyphs,
        hits: MutableList<HitRegion>?,
    ) {
        if (band.isEmpty) return
        val ancho = content.w.coerceAtMost(grid.width - content.x)
        if (ancho <= 0) return
        paintContentRow(grid, content.x, band.y, fila, ancho, glyphs, hits)
    }

    /**
     * Una fila de contenido dentro de su ancho.
     *
     * ## EL QR SE CENTRA Y SU BLANCO NO SE DESBORDA
     *
     * Dos reglas que el usuario reprodujo en la terminal real:
     *
     * 1. el codigo va CENTRADO en el ancho disponible (como el QR de Android),
     *    no justificado a la izquierda;
     * 2. el fondo blanco cubre SOLO las celdas del codigo —margen de silencio
     *    incluido—. Rellenar el resto de la fila con el estilo del QR (lo que
     *    hacia [Grid.put]) pintaba una barra blanca de borde a borde.
     */
    private fun paintContentRow(
        grid: Grid,
        x: Int,
        y: Int,
        fila: BodyRow,
        ancho: Int,
        glyphs: Glyphs,
        hits: MutableList<HitRegion>? = null,
    ) {
        val estilo = styleOf(fila)
        val texto = if (fila.separator) glyphs.sectionRule(fila.text, ancho) else fila.text
        if (!estilo.isQr) {
            grid.put(x, y, texto, estilo, ancho)
            emitirRegiones(hits, x, y, fila, ancho)
            return
        }
        val medido = Sanitizer.width(Sanitizer.text(texto)).coerceAtMost(ancho)
        if (medido <= 0) return
        val sangria = (ancho - medido) / 2
        val escrito = grid.putExact(x + sangria, y, texto, estilo, medido)
        // El resto de la fila queda con el estilo por defecto: ni una celda de
        // blanco fuera del codigo.
        val resto = ancho - sangria - escrito
        if (resto > 0) grid.putExact(x + sangria + escrito, y, " ".repeat(resto), Style.NONE, resto)
        emitirRegiones(hits, x, y, fila, ancho)
    }

    /**
     * Convierte las regiones SEMANTICAS de la fila en regiones de pantalla.
     *
     * Un ancho explicito solo se emite si la etiqueta declarada cabe ENTERA en
     * lo pintado: media palabra recortada no es un boton. `width = null` es el
     * resto de la fila (una fila de lista), que siempre cabe.
     */
    private fun emitirRegiones(
        hits: MutableList<HitRegion>?,
        x: Int,
        y: Int,
        fila: BodyRow,
        ancho: Int,
    ) {
        if (hits == null || fila.regions.isEmpty()) return
        fila.regions.forEach { region ->
            if (region.start < 0 || region.start >= ancho) return@forEach
            val disponible = ancho - region.start
            val w = when {
                region.width == null -> disponible
                region.width <= disponible -> region.width
                else -> return@forEach
            }
            if (w <= 0) return@forEach
            hits += HitRegion(Rect(x + region.start, y, w, 1), region.intent)
        }
    }

    /**
     * Pinta las filas de la pantalla dentro de la banda del cuerpo.
     *
     * En [Chrome.BOX] cada fila es `│ ... │`; en [Chrome.BANDS] el texto va sin
     * marco dentro del ancho de contenido. Un separador se convierte en rotulo
     * de seccion (`▌ CONTACTOS ───`), escaso y sin caja.
     */
    fun paintBody(
        grid: Grid,
        frame: Frame,
        chrome: Chrome,
        content: Rect,
        body: List<BodyRow>,
        glyphs: Glyphs,
    ) = paintBodyInto(grid, frame, chrome, content, body, glyphs, null)

    private fun paintBodyInto(
        grid: Grid,
        frame: Frame,
        chrome: Chrome,
        content: Rect,
        body: List<BodyRow>,
        glyphs: Glyphs,
        hits: MutableList<HitRegion>?,
    ) {
        body.forEachIndexed { i, fila ->
            val y = frame.body.y + i
            if (y >= frame.body.bottom) return@forEachIndexed
            if (chrome == Chrome.BOX) {
                val texto = if (fila.separator) {
                    Box.separator(grid.width, fila.text, glyphs.ascii)
                } else {
                    Box.line(fila.text, grid.width, glyphs.ascii)
                }
                grid.put(frame.body.x, y, texto, styleOf(fila))
                return@forEachIndexed
            }
            val ancho = content.w.coerceAtMost(grid.width - content.x)
            if (ancho <= 0) return@forEachIndexed
            paintContentRow(grid, content.x, y, fila, ancho, glyphs, hits)
        }
    }

    // ------------------------------------------------------------------
    // Overlay
    // ------------------------------------------------------------------

    /**
     * Superpone [overlay] centrado y VENTANEADO.
     *
     * La caja nunca mide mas que la pantalla: si el contenido no cabe se
     * recorre con [offset] y se declara `[n/m] PgUp/PgDn`. El defecto anterior
     * (devolver la caja entera y dejar que el escritor recorte) partia el marco
     * y escondia la ultima parte sin avisar.
     */
    fun paintOverlay(base: Grid, overlay: OverlayFrame, ascii: Boolean): Grid =
        paintOverlayHits(base, overlay, ascii).grid

    /** [paintOverlay] con el hit-map de sus botones; `Dismiss` lo pone el llamador. */
    fun paintOverlayHits(base: Grid, overlay: OverlayFrame, ascii: Boolean): ComposedFrame {
        val hits = ArrayList<HitRegion>()
        val grid = paintOverlay(base, overlay, ascii, hits)
        return ComposedFrame(grid, HitMap(hits))
    }

    private fun paintOverlay(
        base: Grid,
        overlay: OverlayFrame,
        ascii: Boolean,
        hits: MutableList<HitRegion>,
    ): Grid {
        val ancho = when {
            overlay.dialog && base.width >= 88 -> 88
            overlay.dialog && base.width >= 64 -> 60
            overlay.dialog -> base.width
            else -> minOf(base.width, 88)
        }.coerceAtLeast(Box.MIN_WIDTH).coerceAtMost(base.width)
        val total = overlay.content.size
        val visible = OverlayWindow.window(base.height, total, overlay.offset)
        val lineas = ArrayList<String>(visible.count() + 2)
        for (i in visible) lineas += overlay.content[i]
        OverlayWindow.indicator(base.height, total, overlay.offset)?.let { lineas += it }
        val caja = Box.frame(overlay.title, lineas, ancho, ascii)

        val copia = if (overlay.dialog) backdrop(base) else base.copy()
        val x = (base.width - ancho) / 2
        val y = ((base.height - caja.size) / 2).coerceAtLeast(0)
        caja.forEachIndexed { i, linea ->
            val destino = y + i
            if (destino < base.height) copia.put(x, destino, linea, maxWidth = ancho)
        }

        // Botones: se localizan en la linea YA recortada/rellenada que pinta
        // `Box.line`. Si la etiqueta no aparece entera, no hay region.
        val interior = (ancho - 4).coerceAtLeast(0)
        if (overlay.buttons.isNotEmpty() && interior > 0) {
            visible.forEachIndexed { indiceVisible, indiceContenido ->
                val pintada = Sanitizer.pad(Sanitizer.text(overlay.content[indiceContenido]), interior)
                overlay.buttons.forEach { boton ->
                    val desde = pintada.indexOf(boton.label)
                    if (desde < 0) return@forEach
                    val inicio = Sanitizer.width(pintada.substring(0, desde))
                    val w = Sanitizer.width(boton.label)
                    if (inicio + w > interior) return@forEach
                    hits += HitRegion(
                        Rect(x + 2 + inicio, y + 1 + indiceVisible, w, 1),
                        boton.intent,
                    )
                }
            }
        }
        return copia
    }

    /** Fondo atenuado del modal; en MONO no emite nada y el marco ya lo dice. */
    private fun backdrop(base: Grid): Grid {
        val copia = base.copy()
        for (y in 0 until copia.height) {
            for (x in 0 until copia.width) {
                val actual = copia[x, y]
                copia.set(x, y, actual.copy(style = actual.style.copy(bg = com.km.desktop.ui.theme.ColorRole.BG_RAISED)))
            }
        }
        return copia
    }

    /**
     * El estilo de una fila semantica.
     *
     * El QR MANDA: su contraste es fijo (negro sobre blanco) y no puede
     * heredarse del tema. El foco es `REVERSE`, pero el glifo (`>`/`▌`) ya va en
     * el texto: sin color la pantalla sigue diciendo donde esta.
     */
    fun styleOf(row: BodyRow): Style = when {
        row.palette == LinePalette.QR -> Style.QR
        row.focus -> row.style.with(Attr.REVERSE)
        else -> row.style
    }

    /** Las filas de una banda, por comodidad de los tests. */
    fun linesOf(grid: Grid, band: Rect): List<String> =
        (band.y until band.bottom).map { grid.rowText(it) }
}
