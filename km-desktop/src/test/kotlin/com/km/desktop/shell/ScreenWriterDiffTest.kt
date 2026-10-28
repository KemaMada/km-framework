package com.km.desktop.shell

import com.km.desktop.support.RecordingTerminal
import com.km.desktop.ui.LinePalette
import com.km.desktop.ui.RenderedLine
import com.km.desktop.ui.render.StyledSpan
import com.km.desktop.ui.theme.ColorCapability
import com.km.desktop.ui.theme.Style
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * El DIFF POR FILAS del unico escritor (M7).
 *
 * ## QUE SE AFIRMA, Y POR QUE CADA COSA
 *
 * - Un frame identico escribe **cero bytes**: si la pantalla no cambio, no hay
 *   nada que repintar y nada que pueda parpadear.
 * - Un cambio escribe **una** fila, posicionada con `ESC[<n>;1H`: no se reescribe
 *   el frame entero por una tecla.
 * - Una fila que solo cambia de ESTILO se repinta. Es el error clasico del diff:
 *   comparar texto deja el color viejo en pantalla. Este test es el que usa el
 *   control negativo del milestone.
 * - Una fila que se ACORTA emite `ESC[K`: sin el, quedan restos de la linea
 *   anterior a la derecha.
 * - Resize, cambio de alto, `invalidate()` (tema, capacidad, `--ascii`) y
 *   entrar/salir de la pantalla alternativa fuerzan frame COMPLETO.
 *
 * ## LO QUE NO SE AFIRMA AQUI
 *
 * El texto del frame: eso son los goldens. Este fichero mide BYTES, que es lo
 * que ningun golden ve.
 */
class ScreenWriterDiffTest {

    private fun filas(vararg textos: String): List<RenderedLine> =
        textos.map { RenderedLine(it) }

    /** Cuantas veces aparece `ESC[<n>;1H` en [salida]. */
    private fun posiciones(salida: String): List<String> =
        Regex("\u001b\\[\\d+;1H").findAll(salida).map { it.value }.toList()

    private fun escritor(fake: RecordingTerminal, attributes: Boolean = true) =
        ScreenWriter(fake.terminal, attributes = attributes)

    @Test
    fun `un frame identico no escribe ni un byte`() {
        val fake = RecordingTerminal()
        val writer = escritor(fake)
        val frame = filas("uno", "dos", "tres")

        writer.draw(frame)
        fake.limpiar()
        writer.draw(frame)

        assertEquals(0, fake.bytesEscritos(), "identico = cero bytes: '${fake.texto()}'")
    }

    @Test
    fun `un cambio de una fila posiciona y escribe SOLO esa fila`() {
        val fake = RecordingTerminal()
        val writer = escritor(fake)
        writer.draw(filas("aaa", "bbb", "ccc"))
        fake.limpiar()

        writer.draw(filas("aaa", "bXb", "ccc"))
        val salida = fake.texto()

        assertEquals(listOf(ScreenWriter.cursor(1)), posiciones(salida), "solo la fila 1: $salida")
        assertTrue(salida.contains("bXb"))
        assertFalse(salida.contains("aaa"), "las filas sin cambios no se reescriben")
        assertFalse(salida.contains("ccc"), "las filas sin cambios no se reescriben")
    }

    /**
     * El caso que un diff mal hecho pierde: mismo texto, distinto color.
     */
    @Test
    fun `una fila que solo cambia de estilo se repinta`() {
        val fake = RecordingTerminal()
        val writer = escritor(fake)
        val plano = RenderedLine("alerta")
        val alerta = RenderedLine(
            text = "alerta",
            spans = listOf(StyledSpan("alerta", Style.DANGER)),
        )

        writer.draw(listOf(plano))
        fake.limpiar()
        writer.draw(listOf(alerta))
        val salida = fake.texto()

        assertTrue(salida.isNotEmpty(), "un cambio SOLO de estilo tiene que escribir bytes")
        assertEquals(listOf(ScreenWriter.cursor(0)), posiciones(salida), "y repintar esa fila")
        assertTrue(
            salida.contains(Sgr.of(Style.DANGER, ColorCapability.ANSI16)),
            "con el estilo nuevo, no con el anterior: $salida",
        )
    }

    @Test
    fun `una fila que se acorta borra el resto con ESC K`() {
        val fake = RecordingTerminal()
        val writer = escritor(fake)
        writer.draw(listOf(RenderedLine("0123456789")))
        fake.limpiar()

        writer.draw(listOf(RenderedLine("0123")))
        val salida = fake.texto()

        assertTrue(salida.contains("0123\u001b[K"), "sin ESC[K quedan restos: $salida")
    }

    @Test
    fun `cambiar el tamano del terminal fuerza frame completo`() {
        val fake = RecordingTerminal(columns = 100, rows = 10)
        val writer = escritor(fake)
        val frame = filas("a", "b", "c")
        writer.draw(frame)
        fake.limpiar()

        fake.columns = 80
        writer.draw(frame)
        val salida = fake.texto()

        assertTrue(salida.startsWith("\u001b[H"), "repintado completo desde casa: $salida")
        assertTrue(salida.contains("\u001b[J"), "y limpia lo que sobra por abajo")
        assertEquals(0, posiciones(salida).size, "sin posicionamiento por filas: $salida")
    }

    @Test
    fun `un frame con distinto numero de filas fuerza frame completo`() {
        val fake = RecordingTerminal()
        val writer = escritor(fake)
        writer.draw(filas("a", "b", "c"))
        fake.limpiar()

        writer.draw(filas("a", "b"))
        val salida = fake.texto()

        assertTrue(salida.startsWith("\u001b[H"), "otra altura = geometria nueva: $salida")
        assertTrue(salida.contains("\u001b[J"))
    }

    /**
     * Tema, capacidad de color y `--ascii` no se ven en las lineas: el bucle los
     * declara con [ScreenWriter.invalidate].
     */
    @Test
    fun `invalidate repinta todo aunque el frame sea identico`() {
        val fake = RecordingTerminal()
        val writer = escritor(fake)
        val frame = filas("a", "b")
        writer.draw(frame)
        fake.limpiar()

        writer.invalidate()
        writer.draw(frame)

        assertTrue(fake.texto().startsWith("\u001b[H"), "invalidate = frame completo")
        assertTrue(fake.texto().contains("a") && fake.texto().contains("b"))
    }

    @Test
    fun `salir y volver a entrar repinta todo`() {
        val fake = RecordingTerminal()
        val writer = escritor(fake)
        val frame = filas("a", "b")
        writer.draw(frame)
        writer.exit()
        writer.enter()
        fake.limpiar()

        writer.draw(frame)

        assertTrue(
            fake.texto().startsWith("\u001b[H"),
            "la pantalla alternativa empieza limpia: ${fake.texto()}",
        )
    }

    /**
     * El QR sigue abriendo y cerrando su propio estilo en cada fila repintada.
     */
    @Test
    fun `una fila QR repintada conserva el contraste fijo`() {
        val fake = RecordingTerminal()
        val writer = escritor(fake)
        writer.draw(listOf(RenderedLine("x"), RenderedLine("\u2588\u2580", palette = LinePalette.QR)))
        fake.limpiar()

        writer.draw(
            listOf(RenderedLine("x"), RenderedLine("\u2588\u2584", palette = LinePalette.QR)),
        )
        val salida = fake.texto()

        assertEquals(1, posiciones(salida).size, "una fila: $salida")
        assertTrue(salida.contains(ScreenWriter.QR_STYLE + "\u2588\u2584"), "estilo fijo: $salida")
        assertTrue(salida.contains(ScreenWriter.RESET + "\u001b[K"), "ESC[K despues del reset")
    }

    /**
     * Sin atributos no hay SGR, pero el diff sigue funcionando: el escritor
     * compara lineas, no bytes.
     */
    @Test
    fun `sin atributos el diff sigue siendo cero bytes para un frame identico`() {
        val fake = RecordingTerminal()
        val writer = escritor(fake, attributes = false)
        val frame = filas("sin color")
        writer.draw(frame)
        fake.limpiar()
        writer.draw(frame)

        assertEquals(0, fake.bytesEscritos(), "identico sigue siendo cero")
    }
}
