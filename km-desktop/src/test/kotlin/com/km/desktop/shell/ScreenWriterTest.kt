package com.km.desktop.shell

import com.km.desktop.ui.LinePalette
import com.km.desktop.ui.RenderedLine
import com.km.desktop.ui.Screen
import com.km.desktop.ui.ScreenStack
import com.km.desktop.ui.TabStacks
import com.km.desktop.ui.ShareView
import com.km.desktop.ui.ShellRenderer
import com.km.desktop.ui.ShellState
import com.km.desktop.ui.Size
import org.jline.terminal.Terminal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Proxy

/**
 * El CONTRASTE del QR se fija en el unico escritor, no en el tema del terminal.
 *
 * ## EL BUG QUE CIERRA
 *
 * `QrRenderer` pinta los modulos OSCUROS con `█ ▀ ▄`. El escritor emitia ese
 * texto con los colores del usuario: primer plano claro sobre fondo oscuro, es
 * decir un QR INVERTIDO que la camara no detecta (el usuario lo reprodujo
 * invirtiendo una captura a mano). La linea marcada como [LinePalette.QR] se
 * pinta negro sobre blanco. El `ESC[K` va DESPUES del reset: dentro del estilo
 * pintaba de blanco hasta el borde de la terminal (banda blanca), no solo la
 * zona de silencio del codigo.
 *
 * ## EL TERMINAL DE PRUEBA
 *
 * Un proxy de [Terminal] que solo responde `writer()` y `getSize()`: el test
 * mide BYTES emitidos, no una terminal. Implementar la interfaz entera seria
 * ruido que no prueba nada.
 */
class ScreenWriterTest {

    private class FakeTerminal(columns: Int = 100, rows: Int = 50) {
        val salida = StringWriter()
        val terminal: Terminal = Proxy.newProxyInstance(
            Terminal::class.java.classLoader,
            arrayOf(Terminal::class.java),
        ) { _, metodo, _ ->
            when (metodo.name) {
                "writer" -> PrintWriter(salida, true)
                "getSize" -> org.jline.terminal.Size(columns, rows)
                "toString" -> "FakeTerminal"
                "hashCode" -> 1
                "equals" -> false
                else -> porDefecto(metodo.returnType)
            }
        } as Terminal

        fun texto(): String = salida.toString()
    }

    private fun portadorReal(): String =
        buildString { while (length < 469) append("aB3-_") }.take(469)

    private fun estadoCompartir(viewport: Size = Size(100, 50)) = ShellState(
        tabs = TabStacks.of(Screen.INICIO, Screen.COMPARTIR),
        viewport = viewport,
        share = ShareView(carrier = portadorReal(), byteLength = 469, oneTimePreKeys = 0),
    )

    /**
     * El QR del frame real: el `ESC[K` NUNCA va con el fondo blanco activo.
     *
     * ## EL DEFECTO QUE CIERRA
     *
     * El escritor emitia `ESC[K` dentro del estilo del QR, asi que el borrado de
     * fin de linea pintaba de blanco hasta el borde derecho de la terminal: una
     * banda blanca de ancho completo a la derecha del codigo. Ahora el borrado va
     * con los atributos reseteados y el blanco son celdas de la rejilla.
     */
    @Test
    fun `ningun ESC K se emite con el estilo del QR activo`() {
        val lineas = ShellRenderer.render(estadoCompartir(Size(120, 50)))
        assertEquals(43, lineas.count { it.palette == LinePalette.QR }, "43 filas de QR")

        val fake = FakeTerminal(columns = 120, rows = 50)
        ScreenWriter(fake.terminal, attributes = true).draw(lineas)
        val salida = fake.texto()

        assertEquals(
            0,
            Regex("\\u001b\\[30;47m[^\\u001b]*\\u001b\\[K").findAll(salida).count(),
            "ESC[K emitido con el fondo del QR: ${salida.escape()}",
        )
        assertTrue(
            Regex("\\u001b\\[K").findAll(salida).count() >= 43,
            "cada fila se limpia al final, pero ya sin blanco",
        )
    }

    @Test
    fun `el QR se emite negro sobre blanco y el relleno va dentro del estilo`() {
        val fake = FakeTerminal()
        val writer = ScreenWriter(fake.terminal, attributes = true)
        val textoQr = "\u2588\u2580  \u2584"
        writer.draw(
            listOf(
                RenderedLine("cabecera"),
                RenderedLine(textoQr, palette = LinePalette.QR),
                RenderedLine("pie"),
            ),
        )

        val salida = fake.texto()
        val inicioEstilo = salida.indexOf(ScreenWriter.QR_STYLE)
        val inicioTexto = salida.indexOf(textoQr)
        val finTexto = inicioTexto + textoQr.length
        val finEstilo = salida.indexOf(ScreenWriter.RESET, finTexto)
        val limpiar = salida.indexOf("\u001b[K", finTexto)

        assertTrue(inicioEstilo >= 0, "no se emitio ${ScreenWriter.QR_STYLE}: ${salida.escape()}")
        assertTrue(inicioEstilo < inicioTexto, "el SGR va ANTES del texto")
        assertTrue(limpiar >= finTexto, "ESC[K va DESPUES del texto")
        assertTrue(limpiar > finEstilo, "ESC[K va DESPUES del reset: sin fondo blanco")
        assertEquals(
            inicioEstilo,
            salida.lastIndexOf(ScreenWriter.QR_STYLE),
            "solo la linea QR lleva el estilo; la cabecera conserva el tema",
        )
        assertTrue(
            salida.substring(finEstilo).contains("\r\n"),
            "el reset cierra la linea antes del salto",
        )
    }

    @Test
    fun `cada fila del QR abre y cierra su propio estilo`() {
        val fake = FakeTerminal()
        ScreenWriter(fake.terminal, attributes = true).draw(
            listOf(
                RenderedLine("\u2588\u2588", palette = LinePalette.QR),
                RenderedLine("\u2580 ", palette = LinePalette.QR),
            ),
        )
        val salida = fake.texto()
        var desde = 0
        repeat(2) {
            val sgr = salida.indexOf(ScreenWriter.QR_STYLE, desde)
            val reset = salida.indexOf(ScreenWriter.RESET, sgr)
            assertTrue(sgr >= 0, "fila $it sin estilo")
            assertTrue(reset > sgr, "fila $it sin reset")
            desde = reset + 1
        }
        assertEquals(
            2,
            salida.windowed(ScreenWriter.QR_STYLE.length).count { it == ScreenWriter.QR_STYLE },
            "ni un estilo de mas",
        )
    }

    @Test
    fun `sin atributos no se emite SGR`() {
        val fake = FakeTerminal()
        ScreenWriter(fake.terminal, attributes = false).draw(
            listOf(RenderedLine("texto normal", focus = true)),
        )
        val salida = fake.texto()
        assertFalse(salida.contains("\u001b[7m"), "ni reverse video")
        assertFalse(salida.contains(ScreenWriter.QR_STYLE), "ni contraste de QR")
    }

    @Test
    fun `MONO no emite ni un SGR aunque haya estilos semanticos`() {
        val fake = FakeTerminal()
        val writer = ScreenWriter(
            fake.terminal,
            attributes = true,
            capability = com.km.desktop.ui.theme.ColorCapability.MONO,
        )
        writer.draw(
            listOf(
                RenderedLine(
                    "error",
                    spans = listOf(
                        com.km.desktop.ui.render.StyledSpan(
                            "error",
                            com.km.desktop.ui.theme.Style.DANGER,
                        ),
                    ),
                ),
                RenderedLine("foco", focus = true),
            ),
        )
        val salida = fake.texto()
        assertFalse(salida.contains("\u001b[1;"), "sin negrita: ${salida.escape()}")
        assertFalse(salida.contains("\u001b[31m"), "sin rojo: ${salida.escape()}")
        assertFalse(salida.contains("\u001b[7m"), "sin reverse: ${salida.escape()}")
        assertFalse(salida.contains(ScreenWriter.QR_STYLE), "sin QR: ${salida.escape()}")
        assertTrue(salida.contains("error"))
        assertTrue(salida.contains("foco"))
    }

    /**
     * Sin color no hay QR. El renderer ya no produce bloques (`color = false`) y
     * el escritor sin atributos no puede inventar el contraste: el resultado es
     * un aviso honesto, no un QR invertido.
     */
    @Test
    fun `sin atributos no se pintan bloques de QR ni se emite estilo`() {
        val lineas = ShellRenderer.render(estadoCompartir(), color = false)
        val fake = FakeTerminal()
        ScreenWriter(fake.terminal, attributes = false).draw(lineas)

        val salida = fake.texto()
        assertFalse(salida.contains("\u2588"), "sin bloques de QR")
        assertFalse(salida.contains("\u2580"), "sin medios bloques de QR")
        assertFalse(salida.contains(ScreenWriter.QR_STYLE), "sin SGR de QR")
        assertFalse(salida.contains("\u001b[7m"), "sin reverse video")
        assertTrue(salida.contains("el QR necesita color"), "aviso honesto: $salida")
    }

    /**
     * CONTROL POSITIVO del camino real: el renderer marca, el escritor pinta.
     * Si cualquiera de los dos pierde la paleta, este test se pone rojo.
     */
    @Test
    fun `el frame real de COMPARTIR lleva contraste en todas las filas del QR`() {
        val lineas = ShellRenderer.render(estadoCompartir())
        val qr = lineas.filter { it.palette == LinePalette.QR }
        assertEquals(43, qr.size, "43 filas marcadas por el renderer")

        val fake = FakeTerminal()
        ScreenWriter(fake.terminal, attributes = true).draw(lineas)
        val salida = fake.texto()

        assertEquals(
            qr.size,
            salida.windowed(ScreenWriter.QR_STYLE.length).count { it == ScreenWriter.QR_STYLE },
            "una apertura de estilo por fila del QR",
        )
        assertTrue(
            salida.windowed(ScreenWriter.RESET.length).count { it == ScreenWriter.RESET } >= qr.size,
            "al menos un reset por fila del QR; la cabecera y la barra usan roles propios",
        )
    }
}

private fun String.escape(): String = replace("\u001b", "ESC")

/** Valor por defecto de un tipo, para metodos que el test no invoca. */
private fun porDefecto(tipo: Class<*>): Any? = when (tipo) {
    java.lang.Boolean.TYPE -> false
    java.lang.Byte.TYPE -> 0.toByte()
    java.lang.Short.TYPE -> 0.toShort()
    java.lang.Integer.TYPE -> 0
    java.lang.Long.TYPE -> 0L
    java.lang.Float.TYPE -> 0f
    java.lang.Double.TYPE -> 0.0
    java.lang.Character.TYPE -> ' '
    else -> null
}
