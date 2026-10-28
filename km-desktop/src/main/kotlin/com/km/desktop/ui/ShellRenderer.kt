package com.km.desktop.ui

/**
 * Paleta con la que el escritor debe pintar una [RenderedLine].
 *
 * ## POR QUE HACE FALTA UNA MARCA Y NO "DETECTAR EL QR"
 *
 * El QR se pinta con glifos de bloque (`█ ▀ ▄`) que representan modulos
 * OSCUROS. Si el escritor los pinta con el color de primer plano del terminal
 * (habitualmente claro) sobre su fondo (habitualmente oscuro), el QR sale
 * INVERTIDO y ninguna camara lo detecta. La unica forma de arreglarlo es que la
 * linea DIGA que es un QR, para que el escritor fije el contraste
 * (negro sobre blanco) en vez de heredar el tema del usuario.
 *
 * [DEFAULT] es el tema del terminal; [QR] exige negro sobre blanco.
 */
enum class LinePalette {
    DEFAULT,
    QR,
}

/**
 * Una linea YA pintada, con la marca de foco.
 *
 * `focus` NO es color: es la instruccion de que esa fila lleva el foco de la
 * pantalla. El escritor del terminal la puede pintar con reverse video, pero el
 * glifo `>` va en el TEXTO, de modo que una terminal sin color siga diciendo
 * donde esta el foco. Ese es el requisito R1: nunca solo color.
 *
 * `palette` viaja hasta [com.km.desktop.shell.ScreenWriter] sin meter ANSI en
 * [text]: el texto sigue midiendo lo mismo y `Sanitizer`/`Box` no ven secuencias.
 */
data class RenderedLine(
    val text: String,
    val focus: Boolean = false,
    val palette: LinePalette = LinePalette.DEFAULT,
    /**
     * Tramos con estilo semantico (roles). Vacio = linea sin color.
     *
     * No lleva ANSI: [com.km.desktop.shell.ScreenWriter] traduce los roles con
     * `shell/Sgr` segun la capacidad. MONO no emite nada.
     */
    val spans: List<com.km.desktop.ui.render.StyledSpan> = emptyList(),
)

/**
 * Un frame completo: las lineas que se pintan y el hit-map del mismo layout.
 *
 * ## POR QUE VIAJAN JUNTOS
 *
 * Porque el hit-map se construye DENTRO de la misma pasada que pinta: si se
 * pidiera en una segunda llamada, un resize entre las dos produciria
 * coordenadas de un layout y texto de otro. El escritor consume [lines]; el
 * bucle del shell guarda [hitMap] para resolver el siguiente clic.
 */
data class RenderedFrame(
    val lines: List<RenderedLine>,
    val hitMap: com.km.desktop.ui.layout.HitMap = com.km.desktop.ui.layout.HitMap.EMPTY,
)

/**
 * El renderer de la TUI: estado -> lineas.
 *
 * ## UNA SOLA IMPLEMENTACION (M6)
 *
 * M0-M5 convivieron el kernel nuevo y la implementacion congelada para poder
 * compararlos. Con el gate verde se retiro `ShellRendererLegacy`: ya no hay dos
 * pinturas, ni una fachada que elija. Volver atras es recuperar el fichero del
 * historial, no cambiar un `when`.
 *
 * ## INVARIANTES
 *
 * Toda linea mide `viewport.columns` celdas segun [Sanitizer.width], el texto no
 * confiable pasa por [Sanitizer] antes de medirse, el foco va en el texto (`>`,
 * `▌`) y no solo en color, y por debajo de 60x20 se devuelve el aviso de minimo.
 *
 * ## EL RELOJ
 *
 * [RenderClock] se INYECTA: el renderer no consulta `ZoneId.systemDefault()` ni
 * `System.currentTimeMillis()`. El valor por defecto es fijo (UTC epoch 0), no
 * el reloj del sistema.
 */
object ShellRenderer {

    fun render(
        state: ShellState,
        ascii: Boolean = false,
        color: Boolean = true,
        clock: RenderClock = RenderClock.UTC_EPOCH,
    ): List<RenderedLine> = ShellView.renderFrame(state, ascii, color, clock).lines

    /** El frame con su hit-map: es el camino de produccion desde el change B. */
    fun renderFrame(
        state: ShellState,
        ascii: Boolean = false,
        color: Boolean = true,
        clock: RenderClock = RenderClock.UTC_EPOCH,
    ): RenderedFrame = ShellView.renderFrame(state, ascii, color, clock)
}
