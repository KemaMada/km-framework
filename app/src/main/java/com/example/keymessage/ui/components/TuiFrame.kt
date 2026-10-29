package com.example.keymessage.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.keymessage.ui.theme.TerminalBlack
import com.example.keymessage.ui.theme.TerminalBorder
import com.example.keymessage.ui.theme.TerminalCyan
import com.example.keymessage.ui.theme.TuiTokens

/**
 * F1 — El marco TUI: un rectangulo DIBUJADO, esquinas 0, borde 1dp.
 *
 * ## POR QUE SE DIBUJA Y NO SE PINTAN GLIFOS `┌─┐`
 *
 * Porque los glifos box-drawing rompen en fuentes no monoespaciadas del sistema
 * y TalkBack los lee como ruido ("caja, guion, caja en esquina"). Un `Canvas`
 * da el borde EXACTO en cualquier dispositivo y deja el contenido como `Text`
 * real (spec R1.2). El glifo es decorativo; el borde, geometria.
 *
 * ## ORDEN DE DIBUJO (fix verify #381 C1 — el marco era INVISIBLE)
 *
 * El borde se pintaba con `drawBehind` y DESPUES se encadenaba
 * `.background(TerminalBlack)`: `background` es, tambien, un dibujo "detras del
 * contenido", y al ir despues en la cadena se pintaba ENCIMA del borde. El
 * resultado era un marco existente en el codigo e invisible en pantalla.
 *
 * Ahora el fondo va PRIMERO y el borde se dibuja al FINAL con
 * [drawWithContent]: primero se pinta el contenido (que puede traer sus propios
 * fondos opacos) y luego el trazo del marco, de modo que nada pueda taparlo.
 *
 * ## ACCESIBILIDAD
 *
 * El marco es DECORATIVO: `semantics { contentDescription = "" }` cuando no hay
 * titulo. El [titulo] es quien tiene significado, y se pinta como `Text` real
 * con el `contentDescription` del contenedor, de modo que se anuncia "Seccion
 * Contactos" y no caracteres de caja.
 *
 * @param titulo la etiqueta de la seccion; `null` deja un marco sin cabecera.
 * @param acento color del borde (cian por defecto, rojo para lo destructivo).
 */
@Composable
fun TuiFrame(
    titulo: String? = null,
    acento: Color = TerminalCyan,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val etiqueta = titulo.orEmpty()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = if (etiqueta.isBlank()) "" else "Seccion $etiqueta" }
            .background(TerminalBlack)
            .drawWithContent {
                drawContent()
                // El borde: 1dp de `acento`, esquinas 0, dibujado DESPUES del
                // contenido para que ningun fondo de un hijo lo tape.
                val r = geometriaMarco(size.width, size.height, TuiTokens.Border.toPx())
                drawRect(
                    color = acento,
                    topLeft = Offset(r.left, r.top),
                    size = r.size,
                    style = Stroke(width = TuiTokens.Border.toPx()),
                )
            },
    ) {
        if (etiqueta.isNotBlank()) {
            TuiHeader(texto = etiqueta, acento = acento)
        }
        content()
    }
}

/**
 * El rectangulo del marco, en px, para un lienzo [ancho] x [alto] y un trazo
 * de [grosor] px.
 *
 * ## POR QUE ES UNA FUNCION PURA
 *
 * Porque este modulo no tiene `ComposeTestRule` y el `DrawScope` no se puede
 * ejecutar en un test de JVM. La GEOMETRIA del marco —que el trazo quede dentro
 * del lienzo y que no se invierta— si es una funcion, y una funcion se prueba
 * ([com.example.keymessage.ui.TuiFrameGeometryTest]). El `drawWithContent` solo
 * aplica este rect.
 *
 * Se centra el trazo en el borde (`grosor / 2`) para que la mitad exterior no
 * se recorte, y se limita el grosor a la mitad del lienzo para que un tamano
 * degenerado no produzca un rect invertido.
 */
fun geometriaMarco(ancho: Float, alto: Float, grosor: Float): Rect {
    val g = grosor.coerceIn(0f, minOf(ancho, alto) / 2f)
    val mitad = g / 2f
    return Rect(
        left = mitad,
        top = mitad,
        right = (ancho - mitad).coerceAtLeast(mitad),
        bottom = (alto - mitad).coerceAtLeast(mitad),
    )
}

/**
 * F1 — La cabecera de una seccion: la linea `── TITULO ` con regla.
 *
 * Se pinta como una linea DIBUJADA a la derecha del texto —no con glifos
 * `─`— para que el ancho se adapte y el contraste del texto no dependa de
 * caracteres especiales. La regla tambien se dibuja DESPUES del contenido
 * (mismo defecto C1 que el marco: iba en `drawBehind` y el `background` la
 * tapaba).
 */
@Composable
fun TuiHeader(texto: String, acento: Color = TerminalCyan) {
    androidx.compose.material3.Text(
        text = texto,
        color = acento,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        fontSize = TuiTokens.FontSize.md,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalBlack)
            .drawWithContent {
                drawContent()
                // La regla: una linea de 1dp pegada al borde inferior.
                val g = TuiTokens.Border.toPx()
                drawLine(
                    color = TerminalBorder,
                    start = Offset(0f, size.height - g / 2f),
                    end = Offset(size.width, size.height - g / 2f),
                    strokeWidth = g,
                )
            },
    )
}
