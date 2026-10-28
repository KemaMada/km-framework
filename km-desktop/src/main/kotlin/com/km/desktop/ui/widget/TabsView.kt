package com.km.desktop.ui.widget

import com.km.desktop.ui.Focus
import com.km.desktop.ui.FocusField
import com.km.desktop.ui.Intent
import com.km.desktop.ui.MouseIntent
import com.km.desktop.ui.RootTab
import com.km.desktop.ui.Sanitizer
import com.km.desktop.ui.TabStacks
import com.km.desktop.ui.layout.RowRegion
import com.km.desktop.ui.render.BodyRow

/**
 * La BARRA de pestañas raiz, como widget puro.
 *
 * ## QUE ES Y QUE NO
 *
 * Genera el TEXTO de la barra (una fila) a partir de [TabStacks] y del foco. No
 * navega, no decide que pestaña esta activa y no mide el terminal: eso ya lo
 * dicen el estado y el layout. Un widget que ademas cambiara de pestaña seria
 * una segunda ruta de navegacion, y por ahi se pierden los borradores.
 *
 * ## POR QUE AUN NO SE PINTA (M4)
 *
 * La barra pertenece a la cabecera de `Chrome.BANDS` (M5). Pintarla dentro de la
 * caja actual obligaria a meter una fila nueva en las siete pantallas y a
 * reescribir los 21 goldens de paridad de M2, que son el gate de esta fase:
 * el rediseño visual llega con la cabecera, no antes. Dejar el widget listo y
 * probado evita que M5 tenga que inventarlo.
 *
 * ## TEXTO, NO COLOR
 *
 * La pestaña activa se marca con `▌` (`>` en `--ascii`) ADEMAS de quedar primero
 * en la lectura. En MONO se sigue distinguiendo sin atributos, como exige R15.
 */
object TabsView {

    /** Separacion entre pestañas: dos celdas, la misma que usa la cabecera. */
    const val GAP: String = "  "

    /** Marca de la pestaña activa; con `--ascii`, `>`. */
    fun activeMarker(ascii: Boolean = false): String = if (ascii) ">" else "\u258c"

    /** Marca de una pestaña inactiva: dos celdas para que el texto no se mueva. */
    const val INACTIVE_MARKER: String = "  "

    /** Las etiquetas de la barra, activa incluida. */
    fun labels(tabs: TabStacks, ascii: Boolean = false): List<String> =
        RootTab.entries.map { tab ->
            val marca = if (tab == tabs.active) activeMarker(ascii) else INACTIVE_MARKER
            marca + tab.title
        }

    /**
     * La barra en una linea.
     *
     * Devuelve el texto SIN rellenar: quien la pinte la recorta al ancho de su
     * banda con `Grid.text`, que es la unica puerta de texto del kernel.
     */
    fun line(tabs: TabStacks, ascii: Boolean = false): String =
        labels(tabs, ascii).joinToString(GAP)

    /**
     * La barra como fila semantica.
     *
     * [focus] con [FocusField.TABS] enciende el foco de la fila (reverse), pero
     * el glifo de la pestaña activa ya va en el texto: el reverse es refuerzo,
     * nunca la unica señal.
     */
    fun row(tabs: TabStacks, ascii: Boolean = false, focus: Focus = Focus()): BodyRow =
        BodyRow(line(tabs, ascii), focus = focus.field == FocusField.TABS, regions = regions(tabs, ascii))

    /**
     * Las regiones de cada pestaña, en columnas relativas a la barra.
     *
     * Cada etiqueta es una zona clicable que emite `Act(TabTo(tab))`: el clic
     * cambia de pestaña por la MISMA ruta que `Alt-N`/`Tab`+`Enter`, sin tocar
     * borradores. La marca de la pestaña activa forma parte de la etiqueta, de
     * modo que su ancho es el que se pinta.
     */
    fun regions(tabs: TabStacks, ascii: Boolean = false): List<RowRegion> {
        val etiquetas = labels(tabs, ascii)
        val regiones = ArrayList<RowRegion>(etiquetas.size)
        var cursor = 0
        RootTab.entries.forEachIndexed { i, tab ->
            val ancho = Sanitizer.width(etiquetas[i])
            regiones += RowRegion(cursor, ancho, MouseIntent.Act(Intent.TabTo(tab)))
            cursor += ancho + Sanitizer.width(GAP)
        }
        return regiones
    }

    /** La pestaña que apunta el cursor de la barra, o `null` si el foco no esta en ella. */
    fun cursor(focus: Focus): RootTab? =
        if (focus.field == FocusField.TABS) RootTab.at(focus.index) else null
}
