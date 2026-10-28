package com.km.desktop.ui

import com.km.desktop.ui.layout.Chrome
import com.km.desktop.ui.layout.Layout
import com.km.desktop.ui.render.OverlayWindow
import com.km.desktop.ui.widget.DialogView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * M5 — La SUPERFICIE del kernel por bandas.
 *
 * ## QUE SE AFIRMA
 *
 * - Las siete pantallas llenan el viewport EXACTO (filas y ancho por celdas) en
 *   los tres tamanos del gate, sin un solo ESC en el texto.
 * - La jerarquia existe: marca + seccion en la cabecera, pestanas, barra de
 *   estado con identidad/enlace/presencia/contadores/volatilidad y pie.
 * - El QR conserva sus tres bandas (85x43..88x46 sin marco, 89x47..99x49 con
 *   marco compacto, 100x50 con bandas completas) y su contraste lo marca la
 *   paleta. El codigo NUNCA se recorta.
 * - La ayuda se VENTANEA: nunca devuelve mas filas que la terminal.
 * - El modal destructivo arranca marcando `No`.
 *
 * La paridad contra la implementacion congelada ya no aplica: M5 es un cambio
 * visual deliberado. Los 21 goldens son ahora la referencia del aspecto.
 */
class ShellViewTest {

    private fun kernel(state: ShellState, ascii: Boolean = false, color: Boolean = true) =
        ShellView.renderLines(state, ascii, color, GoldenHarness.FIXED_CLOCK)

    private fun ancho(lineas: List<RenderedLine>, columns: Int, etiqueta: String) {
        lineas.forEachIndexed { i, linea ->
            assertEquals(columns, Sanitizer.width(linea.text), "$etiqueta linea $i: '${linea.text}'")
            assertFalse(linea.text.contains('\u001b'), "$etiqueta linea $i lleva ESC")
        }
    }

    @TestFactory
    fun `las 7 pantallas x 3 tamanos llenan el viewport`(): List<DynamicTest> =
        GoldenHarness.cases().map { caso ->
            DynamicTest.dynamicTest(caso.name) {
                val lineas = kernel(caso.state())
                assertEquals(caso.size.rows, lineas.size, "${caso.name}: filas")
                ancho(lineas, caso.size.columns, caso.name)
            }
        }

    @Test
    fun `la cabecera lleva marca, seccion y pestanas`() {
        val lineas = kernel(GoldenHarness.stateFor(Screen.DIAGNOSTICO, Size(80, 24)))
        assertTrue(lineas[0].text.contains("KEYMESSAGE"), lineas[0].text)
        assertTrue(lineas[0].text.contains("DIAGNOSTICO"), lineas[0].text)
        assertTrue(lineas[1].text.contains("INICIO"), lineas[1].text)
        assertTrue(lineas[1].text.contains("FRASE"), lineas[1].text)
        assertTrue(lineas[1].text.contains("DIAGNOSTICO"), lineas[1].text)
        assertTrue(lineas[0].spans.any { it.style == com.km.desktop.ui.theme.Style.BRAND })
    }

    @Test
    fun `la barra de estado dice identidad enlace par y volatilidad`() {
        val texto = kernel(GoldenHarness.stateFor(Screen.INICIO, Size(120, 40)))
            .dropLast(1).last().text
        assertTrue(texto.contains("yo "), texto)
        assertTrue(texto.contains("P2P[o] conectado"), texto)
        assertTrue(texto.contains("par[?]"), "no inventa presencia del par: $texto")
        assertTrue(texto.contains("S1 K2"), texto)
        assertTrue(texto.contains("VOL"), texto)
        assertFalse(texto.contains("ONLINE"), "el estado no se llama ONLINE: $texto")
    }

    @Test
    fun `ASCII conserva layout y no usa glifos Unicode de caja`() {
        listOf(Screen.INICIO, Screen.CONVERSACION).forEach { pantalla ->
            GoldenHarness.SIZES.forEach { tamano ->
                val estado = GoldenHarness.stateFor(pantalla, tamano)
                val lineas = kernel(estado, ascii = true)
                ancho(lineas, tamano.columns, "$pantalla $tamano ascii")
                val texto = lineas.joinToString("\n") { it.text }
                assertFalse(texto.contains("\u250c") || texto.contains("\u2502") || texto.contains("\u2500"), texto)
                assertFalse(texto.contains("\u258c"), "sin marcador Unicode: $texto")
            }
        }
    }

    @Test
    fun `sin color el QR se declara y no se pintan bloques`() {
        val estado = GoldenHarness.stateFor(Screen.COMPARTIR, Size(100, 50))
        val texto = kernel(estado, color = false).joinToString("\n") { it.text }
        assertTrue(texto.contains("el QR necesita color"), texto)
        assertFalse(texto.contains("\u2588"), texto)
    }

    private fun portador(): String = buildString { while (length < 469) append("aB3-_") }.take(469)

    private fun compartir(viewport: Size, showText: Boolean = false) = GoldenHarness
        .stateFor(Screen.INICIO, viewport)
        .copy(
            tabs = TabStacks.of(Screen.INICIO, Screen.COMPARTIR),
            share = ShareView(
                carrier = portador(),
                byteLength = 469,
                oneTimePreKeys = 0,
                showText = showText,
            ),
        )

    @Test
    fun `el QR sin marco a 85x43 es completo y con paleta`() {
        val estado = compartir(Size(85, 43))
        val lineas = kernel(estado)
        assertEquals(43, lineas.size)
        assertTrue(lineas.all { it.palette == LinePalette.QR }, "las 43 filas son QR")
        assertTrue(lineas.none { it.text.contains("\u250c") }, "sin marco al minimo")
        ancho(lineas, 85, "85x43")
    }

    @Test
    fun `el QR a 85x43 es completo y sin marco`() {
        val estado = compartir(Size(85, 43))
        val lineas = kernel(estado)
        assertEquals(43, lineas.size)
        assertEquals(43, lineas.count { it.palette == LinePalette.QR }, "43 filas de QR")
        assertTrue(lineas.none { it.text.contains("\u250c") }, "sin marco al minimo")
        assertFalse(lineas.joinToString("\n") { it.text }.contains("KEYMESSAGE"), "sin bandas")
        ancho(lineas, 85, "85x43")
    }

    @Test
    fun `el QR a 88x46 sigue completo y sin marco`() {
        val estado = compartir(Size(88, 46))
        val lineas = kernel(estado)
        assertEquals(46, lineas.size)
        assertEquals(43, lineas.count { it.palette == LinePalette.QR }, "43 filas de QR intactas")
        assertTrue(lineas.none { it.text.contains("\u250c") }, "sin marco hasta 88x46")
        assertTrue(lineas.joinToString("\n") { it.text }.contains("portador 469 B"), "dice el portador")
        ancho(lineas, 88, "88x46")
    }

    @Test
    fun `el QR a 89x47 vuelve CON marco compacto y sin bandas`() {
        val estado = compartir(Size(89, 47))
        val lineas = kernel(estado)
        val texto = lineas.joinToString("\n") { it.text }
        assertEquals(47, lineas.size)
        assertEquals(43, lineas.count { it.palette == LinePalette.QR }, "43 filas de QR intactas")
        assertTrue(texto.contains("\u250c"), "el marco vuelve a 89x47: $texto")
        assertTrue(texto.contains("\u2514"), "marco cerrado por abajo")
        assertTrue(texto.contains("portador 469 B"), texto)
        assertTrue(texto.contains("[C] copiar"), texto)
        assertFalse(texto.contains("KEYMESSAGE"), "marco compacto: sin cabecera de bandas")
        assertFalse(texto.contains("Alt-1"), "sin barra de pestanas ni pie")
        ancho(lineas, 89, "89x47")
    }

    @Test
    fun `el QR a 100x50 usa las bandas completas`() {
        val estado = compartir(Size(100, 50))
        val lineas = kernel(estado)
        val texto = lineas.joinToString("\n") { it.text }
        assertEquals(50, lineas.size)
        assertEquals(43, lineas.count { it.palette == LinePalette.QR }, "43 filas de QR intactas")
        assertTrue(texto.contains("KEYMESSAGE"), "cabecera de bandas: $texto")
        assertTrue(texto.contains("portador 469 B"), texto)
        ancho(lineas, 100, "100x50")
    }

    @Test
    fun `por debajo del minimo declara 85x43 y no pinta bloques`() {
        val estado = compartir(Size(84, 42))
        val lineas = kernel(estado)
        val texto = lineas.joinToString("\n") { it.text }
        assertTrue(texto.contains("85x43"), texto)
        assertTrue(texto.contains(".km"), texto)
        assertFalse(listOf("\u2588", "\u2580", "\u2584").any { texto.contains(it) }, texto)
        ancho(lineas, 84, "84x42")
    }

    @Test
    fun `el QR por texto al minimo pinta el portador sin bloques`() {
        val lineas = kernel(compartir(Size(85, 43), showText = true))
        val texto = lineas.joinToString("\n") { it.text }
        assertTrue(texto.contains("aB3-_"), texto)
        assertFalse(listOf("\u2588", "\u2580", "\u2584").any { texto.contains(it) }, texto)
        assertEquals(43, lineas.size)
    }

    @Test
    fun `la ayuda cabe a 60x20 y 80x24 y se puede desplazar`() {
        listOf(Size(60, 20), Size(80, 24)).forEach { size ->
            val base = GoldenHarness.stateFor(Screen.INICIO, size)
            val abierta = base.copy(helpVisible = true)
            val lineas = kernel(abierta)
            assertEquals(size.rows, lineas.size, "la ayuda no crece a $size")
            ancho(lineas, size.columns, "ayuda $size")
            val texto = lineas.joinToString("\n") { it.text }
            assertTrue(texto.contains("AYUDA"), texto)
            assertTrue(texto.contains("PgUp/PgDn"), "declara que hay mas y como moverse: $texto")

            val total = Keymap.help(Screen.INICIO).size + 2
            val max = OverlayWindow.maxOffset(size.rows, total)
            val desplazada = kernel(abierta.copy(helpOffset = max))
            assertEquals(size.rows, desplazada.size)
            val abajo = desplazada.joinToString("\n") { it.text }
            assertTrue(abajo.contains("[?] cerrar"), abajo)
        }
    }

    @Test
    fun `el QR se centra y el blanco cubre solo sus celdas`() {
        listOf(Size(100, 50), Size(120, 50), Size(160, 50), Size(89, 47), Size(88, 46)).forEach { size ->
            val lineas = kernel(compartir(size))
            val qr = lineas.filter { it.palette == LinePalette.QR }
            assertEquals(43, qr.size, "$size: 43 filas de QR")
            qr.forEachIndexed { fila, linea ->
                val (ini, fin) = celdasQr(linea)
                assertTrue(ini > 0, "$size fila $fila: el QR empieza en $ini, no se centra")
                assertTrue(fin < size.columns - 1, "$size fila $fila: el QR acaba en $fin")
                // A la derecha (y a la izquierda) del codigo no hay ni una celda
                // con el fondo del QR: el blanco no forma banda.
                assertTrue(
                    celdasFuera(linea, ini, fin),
                    "$size fila $fila: hay celdas QR fuera del codigo: '${linea.text}'",
                )
            }
        }
    }

    @Test
    fun `a 60x20 el contenido deja margen y el QR declara su minimo`() {
        // El terminal minimo no puede ensenar el QR: lo dice y no pinta bloques.
        val chico = kernel(compartir(Size(60, 20))).joinToString("\n") { it.text }
        assertTrue(chico.contains("85x43"), chico)
        assertFalse(chico.contains("\u2588"), "sin bloques a 60x20")

        // Y el contenido de una pantalla de lista NO se pega al borde: x > 0.
        val inicio = kernel(GoldenHarness.stateFor(Screen.INICIO, Size(60, 20)))
        assertTrue(inicio.drop(2).first { it.text.isNotBlank() }.text.startsWith(" "), inicio[2].text)
    }

    /** Primer y ultimo indice de celda con el fondo del QR. */
    private fun celdasQr(linea: RenderedLine): Pair<Int, Int> {
        var col = 0
        var ini = -1
        var fin = -1
        linea.spans.forEach { span ->
            val ancho = Sanitizer.width(span.text)
            if (span.style.bg == com.km.desktop.ui.theme.ColorRole.QR_BG) {
                if (ini < 0) ini = col
                fin = col + ancho - 1
            }
            col += ancho
        }
        return ini to fin
    }

    /** `true` si ninguna celda fuera de `[ini, fin]` lleva el estilo del QR. */
    private fun celdasFuera(linea: RenderedLine, ini: Int, fin: Int): Boolean {
        var col = 0
        var limpio = true
        linea.spans.forEach { span ->
            val ancho = Sanitizer.width(span.text)
            val fuera = col + ancho - 1 < ini || col > fin
            if (fuera && span.style.bg == com.km.desktop.ui.theme.ColorRole.QR_BG) limpio = false
            col += ancho
        }
        return limpio
    }

    @Test
    fun `INICIO es una lista de ancho completo con ficha y separador`() {
        val size = Size(120, 40)
        val lineas = kernel(GoldenHarness.stateFor(Screen.INICIO, size))
        val cuerpo = lineas.drop(2).dropLast(2).map { it.text }
        assertTrue(cuerpo.first().contains("buscar"), cuerpo.first())
        assertTrue(cuerpo.any { it.contains("CONTACTOS 3") }, "cabecera de la lista")
        val ficha = cuerpo.first { it.contains("ana") }
        assertEquals(120, Sanitizer.width(ficha), "la ficha ocupa el ancho completo")
        assertTrue(ficha.startsWith(" "), "solo el padding de una celda: '$ficha'")
        assertTrue(ficha.contains("hasta luego"), ficha)
        assertTrue(ficha.contains("22:12"), ficha)
        assertTrue(ficha.contains("SK"), "S y K en su columna: $ficha")
        assertTrue(cuerpo.any { it.isNotBlank() && it.trim().all { c -> c == '\u2500' } }, "separador fino")
        assertFalse(
            cuerpo.any { it.contains("CONTACTOS SOLO EN MEMORIA") },
            "el aviso de volatilidad vive en la barra de estado, no roba filas",
        )
    }

    @Test
    fun `CONVERSACION alinea por autor y conserva las etiquetas`() {
        val size = Size(80, 24)
        val lineas = kernel(GoldenHarness.stateFor(Screen.CONVERSACION, size))
        val texto = lineas.joinToString("\n") { it.text }
        assertTrue(texto.contains("[IN]"), texto)
        assertTrue(texto.contains("[EXPIRADO ACK_TIMEOUT]"), texto)
        assertTrue(texto.contains("[OUT]"), texto)

        val delPar = lineas.first { it.text.contains("hola, \u00bfque tal?") }
        assertTrue(delPar.text.trimStart().startsWith("{"), "el par va a la izquierda: '${delPar.text}'")
        assertEquals(1, delPar.text.indexOf('{'), "sin sangria: '${delPar.text}'")

        val propio = lineas.first { it.text.contains("nos vemos a las 8") }
        assertTrue(propio.text.indexOf('[') > 1, "lo propio va a la derecha: '${propio.text}'")
        assertTrue(propio.text.trimEnd().endsWith("]"), propio.text)

        // El compositor esta enmarcado y ocupa el ancho completo.
        val compositor = lineas.first { it.text.contains("> hola") }
        assertEquals(80, Sanitizer.width(compositor.text), compositor.text)
        assertTrue(
            compositor.text.trimStart().startsWith("\u2502") &&
                compositor.text.trimEnd().endsWith("\u2502"),
            "el compositor va enmarcado y a todo el ancho: '${compositor.text}'",
        )
    }

    @Test
    fun `el modal destructivo arranca marcando No y Esc no borra`() {
        val base = GoldenHarness.stateFor(Screen.CONVERSACION, Size(80, 24))
        val reducido = ShellReducer.reduce(base, ShellAction.Key(KeyStroke.char('d')))
        val overlay = reducido.state.overlay as Overlay.Confirm
        assertEquals(ConfirmKind.DISCARD_MESSAGE, overlay.kind)
        assertEquals(DialogChoice.SECONDARY, overlay.selection)

        val enter = ShellReducer.reduce(
            reducido.state,
            ShellAction.Key(KeyStroke(Key.Enter)),
        )
        assertTrue(enter.effects.isEmpty(), "Enter en No no descarta")
        assertEquals(null, enter.state.overlay)

        val linea = DialogView.choices(DialogChoice.SECONDARY)
        assertTrue(linea.contains("> [ No ]"), linea)
        assertFalse(linea.contains("> [ S\u00ed ]"), linea)
    }

    @Test
    fun `el modal se pinta con backdrop y ancho de dialogo`() {
        val base = GoldenHarness.stateFor(Screen.INICIO, Size(80, 24))
        val modal = base.copy(
            overlay = Overlay.Confirm(
                ConfirmKind.DISCARD_MESSAGE,
                "descartar mensaje",
                "no se puede deshacer",
                selection = DialogChoice.SECONDARY,
            ),
        )
        val lineas = kernel(modal)
        assertEquals(24, lineas.size)
        ancho(lineas, 80, "modal 80x24")
        val texto = lineas.joinToString("\n") { it.text }
        assertTrue(texto.contains("CONFIRMAR"), texto)
        assertTrue(texto.contains("> [ No ]"), "arranca en la opcion no destructiva: $texto")
        assertTrue(
            lineas.any { linea -> linea.spans.any { it.style.bg == com.km.desktop.ui.theme.ColorRole.BG_RAISED } },
            "el fondo del modal se atenua en color",
        )
        val caja = lineas.indexOfFirst { it.text.contains("\u250c CONFIRMAR") }
        assertTrue(caja >= 0)
        assertTrue(lineas[caja].text.indexOf('\u250c') > 0, "caja de 60 centrada en 80: '${lineas[caja].text}'")
    }

    @Test
    fun `un payload con ESC sigue inerte y mide igual`() {
        val estado = GoldenHarness.stateFor(Screen.CONVERSACION, Size(80, 24)).let { base ->
            base.copy(
                conversation = base.conversation?.copy(
                    messages = listOf(
                        MessageRow(
                            messageId = com.km.model.MessageId.random(),
                            direction = MessageDirection.IN,
                            at = GoldenHarness.FIXED_MS,
                            text = "hola\u001b[2J\u001b]52;c;c2VjcmV0\u0007mundo",
                            state = com.km.model.MessageState.DELIVERED,
                        ),
                    ),
                ),
            )
        }
        val lineas = kernel(estado)
        ancho(lineas, 80, "payload")
        val texto = lineas.joinToString("\n") { it.text }
        assertTrue(texto.contains("^[[2J"), "la secuencia se ve inerte")
        assertTrue(texto.contains("^[]52"), "OSC 52 se ve inerte")
    }

    @Test
    fun `las siete pantallas se pintan por bandas`() {
        Screen.entries.forEach { pantalla ->
            assertEquals(Chrome.BANDS, Layout.chrome(pantalla), "chrome de $pantalla")
        }
    }

    @Test
    fun `el cuerpo sale del layout y ninguna fila queda sin pintar`() {
        GoldenHarness.cases().forEach { caso ->
            val frame = Layout.frame(caso.size, Layout.chrome(caso.screen))
            assertEquals(
                caso.size.rows - 4,
                frame.body.h,
                "${caso.name}: cuerpo = alto - cabecera - estado - pie",
            )
            val filas = kernel(caso.state())
            assertEquals(caso.size.rows, filas.size)
            assertTrue(filas[frame.body.y].text.isNotBlank(), "${caso.name}: primera fila del cuerpo")
        }
    }
}
