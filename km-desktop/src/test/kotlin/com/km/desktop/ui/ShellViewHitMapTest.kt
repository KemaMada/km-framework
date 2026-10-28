package com.km.desktop.ui

import com.km.desktop.ui.layout.Layout
import com.km.desktop.ui.render.FrameComposer
import com.km.desktop.ui.render.Grid
import com.km.desktop.ui.render.OverlayButton
import com.km.desktop.ui.render.OverlayFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * B2 — El hit-map sale de los frames REALES de las siete pantallas.
 *
 * ## LO QUE SE AFIRMA
 *
 * - una ficha de INICIO resuelve a `SelectContact(i)` y su regla tambien;
 * - las pestañas resuelven a `Act(TabTo)`, el pie a `Act(Intent)` y el
 *   compositor a `FocusComposer`;
 * - el overlay GANA: ninguna celda del fondo devuelve el contacto tapado;
 * - un boton truncado no emite region;
 * - EXHAUSTIVIDAD: toda accion del hit-map tiene tecla equivalente en el
 *   keymap de su pantalla (R31: nada solo-raton).
 */
class ShellViewHitMapTest {

    private fun frame(state: ShellState) =
        ShellView.renderFrame(state, ascii = false, color = true, clock = GoldenHarness.FIXED_CLOCK)

    /** Columna en celdas del primer [needle] de [linea]. */
    private fun columna(linea: String, needle: String): Int {
        val i = linea.indexOf(needle)
        assertTrue(i >= 0, "'$needle' no esta en '${linea}'")
        return Sanitizer.width(linea.substring(0, i))
    }

    private fun filaDe(filas: List<RenderedLine>, needle: String): Int {
        val i = filas.indexOfFirst { it.text.contains(needle) }
        assertTrue(i >= 0, "'$needle' no se pinto:\n" + filas.joinToString("\n") { it.text })
        return i
    }

    @Test
    fun `una ficha de INICIO y su regla abren el contacto`() {
        val f = frame(GoldenHarness.stateFor(Screen.INICIO, Size(80, 24)))
        val fila = filaDe(f.lines, "ana")
        val col = columna(f.lines[fila].text, "ana")
        assertEquals(MouseIntent.SelectContact(0), f.hitMap.at(col, fila))
        assertEquals(
            MouseIntent.SelectContact(0),
            f.hitMap.at(5, fila + 1),
            "la regla bajo la ficha pertenece al mismo contacto",
        )
        assertEquals(
            MouseIntent.SelectContact(1),
            f.hitMap.at(col, filaDe(f.lines, "pedro")),
            "la segunda ficha apunta a su indice",
        )
    }

    @Test
    fun `la ficha es una region de ancho completo`() {
        val size = Size(80, 24)
        val f = frame(GoldenHarness.stateFor(Screen.INICIO, size))
        val fila = filaDe(f.lines, "ana")
        val region = f.hitMap.regions().first { it.rect.y == fila }
        assertEquals(Layout.contentFor(Screen.INICIO, size).w, region.rect.w, "de borde a borde del contenido")
        assertEquals(region.rect.x, 1, "empieza en el padding, no en la columna 0")
    }

    @Test
    fun `las pestanas cambian de raiz con Act TabTo`() {
        val f = frame(GoldenHarness.stateFor(Screen.INICIO, Size(120, 40)))
        val barra = f.lines[1].text
        assertEquals(
            MouseIntent.Act(Intent.TabTo(RootTab.INICIO)),
            f.hitMap.at(columna(barra, "INICIO"), 1),
        )
        assertEquals(
            MouseIntent.Act(Intent.TabTo(RootTab.FRASE)),
            f.hitMap.at(columna(barra, "FRASE"), 1),
        )
        assertEquals(
            MouseIntent.Act(Intent.TabTo(RootTab.DIAGNOSTICO)),
            f.hitMap.at(columna(barra, "DIAGNOSTICO"), 1),
        )
    }

    @Test
    fun `el pie ejecuta la accion de la pantalla`() {
        val f = frame(GoldenHarness.stateFor(Screen.INICIO, Size(120, 40)))
        val pie = f.lines.last()
        val fila = f.lines.lastIndex
        assertEquals(
            MouseIntent.Act(Intent.Share),
            f.hitMap.at(columna(pie.text, "[C] compartir"), fila),
        )
        assertEquals(
            MouseIntent.Act(Intent.Quit),
            f.hitMap.at(columna(pie.text, "[Q]uit"), fila),
        )
    }

    @Test
    fun `conversacion selecciona mensaje y enfoca el compositor`() {
        val f = frame(GoldenHarness.stateFor(Screen.CONVERSACION, Size(80, 24)))
        val mensaje = filaDe(f.lines, "nos vemos a las 8")
        assertEquals(
            MouseIntent.SelectMessage(1),
            f.hitMap.at(columna(f.lines[mensaje].text, "nos vemos"), mensaje),
        )
        val compositor = filaDe(f.lines, "> hola")
        assertEquals(
            MouseIntent.FocusComposer,
            f.hitMap.at(columna(f.lines[compositor].text, "> hola"), compositor),
        )
    }

    @Test
    fun `diagnostico selecciona la linea de log`() {
        val f = frame(GoldenHarness.stateFor(Screen.DIAGNOSTICO, Size(120, 40)))
        val fila = filaDe(f.lines, "[AUTH] identidad")
        assertEquals(
            MouseIntent.SelectLog(0),
            f.hitMap.at(columna(f.lines[fila].text, "[AUTH]"), fila),
        )
    }

    @Test
    fun `fuera de toda region el hit-map es null`() {
        val f = frame(GoldenHarness.stateFor(Screen.INICIO, Size(80, 24)))
        assertNull(f.hitMap.at(0, 5), "la columna de padding no pertenece a la ficha")
        assertNull(f.hitMap.at(79, 1), "el final de la cabecera no es una pestana")
    }

    @Test
    fun `el overlay gana y el fondo no responde`() {
        val base = GoldenHarness.stateFor(Screen.INICIO, Size(80, 24))
        val modal = base.copy(
            overlay = Overlay.Confirm(
                ConfirmKind.EXIT,
                "salir de KeyMessage",
                "la cola es local",
                selection = DialogChoice.SECONDARY,
            ),
        )
        val f = frame(modal)
        val filaContacto = filaDe(f.lines, "ana")
        val colContacto = columna(f.lines[filaContacto].text, "ana")
        assertEquals(
            MouseIntent.Dismiss,
            f.hitMap.at(colContacto, filaContacto),
            "el clic sobre la fila tapada NO abre el contacto",
        )
        val no = filaDe(f.lines, "[ No ]")
        assertEquals(
            MouseIntent.Dialog(DialogChoice.SECONDARY),
            f.hitMap.at(columna(f.lines[no].text, "[ No ]"), no),
        )
        val si = filaDe(f.lines, "[ S\u00ed ]")
        assertEquals(
            MouseIntent.Dialog(DialogChoice.PRIMARY),
            f.hitMap.at(columna(f.lines[si].text, "[ S\u00ed ]"), si),
        )
    }

    @Test
    fun `un boton que no cabe entero no emite region`() {
        val largo = "[" + "X".repeat(70) + "]"
        val compuesto = FrameComposer.paintOverlayHits(
            Grid.blank(60, 20),
            OverlayFrame(
                "CONFIRMAR",
                listOf("detalle", "", largo),
                dialog = true,
                buttons = listOf(OverlayButton(largo, MouseIntent.Act(Intent.Copy))),
            ),
            ascii = false,
        )
        assertEquals(0, compuesto.hitMap.size, "la etiqueta truncada no es clicable")
    }

    @Test
    fun `el overlay de ayuda tambien captura el clic para cerrarse`() {
        val f = frame(GoldenHarness.stateFor(Screen.INICIO, Size(120, 40)).copy(helpVisible = true))
        assertEquals(MouseIntent.Dismiss, f.hitMap.at(10, 10))
        val texto = f.lines.joinToString("\n") { it.text }
        assertTrue(texto.contains("raton OFF"), "la ayuda declara el valor efectivo: $texto")
        assertTrue(texto.contains("hover/doble clic fuera"), texto)
    }

    /**
     * R31 — EXHAUSTIVIDAD region -> tecla.
     *
     * Se enumeran las regiones de los frames reales de las siete pantallas y se
     * exige que CADA intencion tenga su tecla: `Act` esta en el keymap de la
     * pantalla; seleccionar fila usa flechas+Enter; foco usa Tab; dialogo usa
     * Tab+Enter/Esc. Ninguna capacidad es solo-raton.
     */
    @TestFactory
    fun `toda region del hit-map tiene tecla equivalente`(): List<DynamicTest> =
        Screen.entries.flatMap { screen ->
            GoldenHarness.SIZES.map { size ->
                DynamicTest.dynamicTest("$screen $size") {
                    val estado = GoldenHarness.stateFor(screen, size)
                    val f = frame(estado)
                    val intents = Keymap.bindings(screen).map { it.intent }.toSet()
                    assertNotNull(f.hitMap, "hit-map")
                    assertFalse(f.hitMap.regions().isEmpty(), "sin regiones accionables en $screen $size")
                    f.hitMap.regions().forEach { region ->
                        val tecla = when (val i = region.intent) {
                            is MouseIntent.Act -> i.intent in intents
                            is MouseIntent.SelectContact, is MouseIntent.SelectMessage,
                            is MouseIntent.SelectLog,
                            -> Intent.MoveDown in intents && Intent.Activate in intents

                            MouseIntent.FocusComposer, MouseIntent.FocusFilter -> Intent.NextField in intents
                            is MouseIntent.Dialog ->
                                Intent.NextField in intents && Intent.Activate in intents && Intent.Back in intents

                            MouseIntent.Dismiss -> Intent.Back in intents
                        }
                        assertTrue(tecla, "$screen $size: ${region.intent} no tiene tecla equivalente")
                    }
                }
            }
        }
}
