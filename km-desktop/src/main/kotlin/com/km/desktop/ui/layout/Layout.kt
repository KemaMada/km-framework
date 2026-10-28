package com.km.desktop.ui.layout

import com.km.desktop.ui.Screen
import com.km.desktop.ui.Size

/**
 * La GEOMETRIA de la TUI: viewport -> bandas y ancho de contenido.
 *
 * ## QUE ES
 *
 * Funciones puras, en celdas, sin terminal ni ANSI. Miden; no pintan. Eso
 * permite que el mismo calculo sirva para pintar, para saber cuantas filas tiene
 * una lista (scroll) y —en el change de raton— para el hit-test: una sola
 * verdad sobre donde esta cada cosa.
 *
 * ## LO QUE NO HACE
 *
 * No guarda rectangulos en el estado: se recalcula por frame. No decide colores
 * ni textos. No necesita JLine, ni un pty, ni el reloj.
 *
 * ## ANCHO COMPLETO (change A, correccion post-archivo)
 *
 * INICIO, CONVERSACION y DIAGNOSTICO ocupan TODO el ancho del terminal, como una
 * lista de chats de escritorio: el centrado con `maxWidth` hacia que la pantalla
 * flotara en el medio y no se pareciera a la app Android. El `maxWidth` queda
 * reservado a los formularios (FRASE, RESTAURAR) y a los dialogos, que si son
 * piezas centradas de ancho fijo. COMPARTIR e IMPORTAR nunca se limitaron: el QR
 * exige 85 columnas sin marco y 89 con marco (R7).
 *
 * ## PADDING
 *
 * Un solo caracter a cada lado: la lista necesita el ancho, pero el texto pegado
 * al borde de la terminal se corta con el marco de la ventana. Con 0 o 1
 * columnas no hay margen posible.
 *
 * ## ESTADO EN M1/M2
 *
 * `chrome(screen)` es [Chrome.BOX] para las siete pantallas: M2 exige PARIDAD y
 * la caja actual es la geometria que hay que reproducir. [Chrome.BANDS] y
 * [content] ya existen y estan probados, pero no los usa la pintura hasta F2;
 * usarlos antes cambiaria lo que ve el usuario, que es justo lo que M2 prohibe.
 */
object Layout {

    /** Ancho maximo de los formularios (frase, restaurar): piezas centradas. */
    const val MAX_WIDTH_FORM: Int = 60

    /** Padding horizontal normal: 1 celda a cada lado. */
    const val PADDING: Int = 1

    /**
     * Padding horizontal adaptativo.
     *
     * Una sola celda de margen: la lista es de ancho completo, pero el texto
     * pegado al borde se pierde con el marco de la ventana. Por debajo de dos
     * columnas no hay margen que dar.
     */
    fun padding(columns: Int): Int = if (columns < 2 * PADDING) 0 else PADDING

    /**
     * El ancho maximo de cada pantalla, o `null` si no se limita.
     *
     * `null` es ANCHO COMPLETO: las tres pantallas de trabajo (INICIO,
     * CONVERSACION, DIAGNOSTICO) son listas, y una lista de escritorio se lee de
     * borde a borde. Solo los formularios se limitan.
     */
    fun maxWidth(screen: Screen): Int? = when (screen) {
        Screen.INICIO, Screen.CONVERSACION, Screen.DIAGNOSTICO -> null
        Screen.FRASE, Screen.RESTAURAR -> MAX_WIDTH_FORM
        // El QR manda sobre el ancho: 89 con marco, 85 sin el.
        Screen.COMPARTIR, Screen.IMPORTAR -> null
    }

    /**
     * La region de contenido: centrada si hay [maxWidth], de ancho completo si no.
     *
     * `x` es simetrico (`(columns - w) / 2`) cuando el panel se limita, de modo
     * que un formulario de 60 en 120 columnas deja 30 a cada lado. Con
     * `maxWidth = null` el panel usa todo el ancho menos el padding.
     */
    fun content(viewport: Size, maxWidth: Int?): Rect {
        val pad = padding(viewport.columns)
        val disponible = (viewport.columns - 2 * pad).coerceAtLeast(0)
        val ancho = maxWidth?.coerceAtMost(disponible) ?: disponible
        val x = (viewport.columns - ancho) / 2
        return Rect(x, 0, ancho, viewport.rows)
    }

    /** [content] con el maximo de la pantalla y la excepcion del QR. */
    fun contentFor(screen: Screen, viewport: Size): Rect = when (screen) {
        // El QR manda sobre el ancho: 89 con marco y 85 sin el. Un padding de 2
        // dejaria 85 columnas en 89 y romperia el umbral que R7 promete.
        Screen.COMPARTIR, Screen.IMPORTAR -> Rect(0, 0, viewport.columns, viewport.rows)
        else -> content(viewport, maxWidth(screen))
    }

    /**
     * La banda con la que se pinta cada pantalla.
     *
     * M5 activa [Chrome.BANDS] en las SIETE: cabecera de dos filas (marca +
     * pestañas), cuerpo, barra de estado y pie. La caja de M2 queda como
     * geometria de [frame] para los tests, no como pintura de la app.
     */
    fun chrome(screen: Screen): Chrome = Chrome.BANDS

    /**
     * Las bandas del viewport, en celdas.
     *
     * - [Chrome.BOX]: 1 + (rows-2) + 1, exactamente la caja actual.
     * - [Chrome.BANDS]: cabecera 2 + cuerpo + estado 1 + pie 1.
     * - [Chrome.NONE]: una sola region.
     *
     * Con pocas filas las bandas se degradan quitando las de menos valor
     * (primero el pie, luego el estado) en vez de producir alturas negativas: un
     * frame imposible es un `IllegalArgumentException` en produccion.
     */
    fun frame(viewport: Size, chrome: Chrome): Frame {
        val columns = viewport.columns
        val rows = viewport.rows
        return when (chrome) {
            Chrome.BOX -> {
                val arriba = if (rows >= 1) 1 else 0
                val abajo = if (rows >= 2) 1 else 0
                val cuerpo = (rows - arriba - abajo).coerceAtLeast(0)
                Frame(
                    header = Rect(0, 0, columns, arriba),
                    body = Rect(0, arriba, columns, cuerpo),
                    status = Rect(0, rows - abajo, columns, abajo),
                    footer = Rect(0, rows, columns, 0),
                )
            }

            Chrome.BANDS -> {
                val header = minOf(2, rows)
                val footer = if (rows - header >= 2) 1 else 0
                val status = if (rows - header - footer >= 2) 1 else 0
                val cuerpo = (rows - header - status - footer).coerceAtLeast(0)
                Frame(
                    header = Rect(0, 0, columns, header),
                    body = Rect(0, header, columns, cuerpo),
                    status = Rect(0, header + cuerpo, columns, status),
                    footer = Rect(0, header + cuerpo + status, columns, footer),
                )
            }

            Chrome.NONE -> Frame(
                header = Rect(0, 0, columns, 0),
                body = Rect(0, 0, columns, rows),
                status = Rect(0, rows, columns, 0),
                footer = Rect(0, rows, columns, 0),
            )
        }
    }

    /**
     * Filas utiles del cuerpo de [screen].
     *
     * Es la unica fuente del alto de una lista. El reducer la usa para
     * `ensureVisible` sin guardar geometria en el estado: si el alto viviera en
     * `ShellState` habria dos verdades y una se quedaria vieja al redimensionar.
     */
    fun bodyRows(screen: Screen, viewport: Size): Int =
        frame(viewport, chrome(screen)).body.h

    /**
     * Filas que una pantalla gasta en TODO menos su lista.
     *
     * Es una COTA SUPERIOR deliberada: incluye la linea de filtro aunque no se
     * pinte y las dos lineas opcionales de DIAGNOSTICO, de modo que la ventana
     * calculada aqui nunca sea mayor que la que la vista puede pintar. Si fuera
     * al reves, el reducer creeria visible una fila que el compositor recorta.
     *
     * En INICIO la unidad de lista es el CONTACTO (dos filas: la ficha y su
     * separador fino), no la fila de pantalla; ver [listRows].
     */
    fun listChrome(screen: Screen): Int = when (screen) {
        // buscador + regla de ancho completo bajo la cabecera de la lista.
        Screen.INICIO -> 2
        // presencia + regla + compositor + acciones.
        Screen.CONVERSACION -> 4
        // transporte + relay + intento + ultimo error + cola + 2 separadores + teclas.
        Screen.DIAGNOSTICO -> 9
        Screen.IMPORTAR, Screen.COMPARTIR, Screen.FRASE, Screen.RESTAURAR -> 0
    }

    /**
     * Elementos de LISTA que caben en [screen].
     *
     * La usan el reducer (`Scroll.ensure`) y la vista (`Scroll.window`): la
     * misma funcion, de modo que "visible" significa lo mismo en los dos lados.
     * Nunca baja de 1: una lista con cero filas no puede ensenar su foco, y un
     * `rows = 0` haria que `ensure` devolviera siempre 0 y el foco se perdiera.
     *
     * En INICIO cada contacto ocupa DOS filas (ficha de ancho completo y su
     * separador fino), igual que la lista de Android gasta una regla por fila.
     * Por eso la unidad se divide entre dos: devolver filas de pantalla haria que
     * la ventana pintara el doble de alto de lo que cabe.
     */
    fun listRows(screen: Screen, viewport: Size): Int {
        val filas = (bodyRows(screen, viewport) - listChrome(screen)).coerceAtLeast(1)
        return if (screen == Screen.INICIO) (filas / 2).coerceAtLeast(1) else filas
    }
}
