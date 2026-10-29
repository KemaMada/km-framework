package com.example.keymessage.ui

import com.example.keymessage.ui.components.geometriaMarco
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C1 — La GEOMETRIA del marco TUI (verify #381: el borde existia y no se veia).
 *
 * ## QUE SE PUEDE Y QUE NO SE PUEDE PROBAR EN JVM
 *
 * Este modulo no tiene `ComposeTestRule` ni Robolectric: NO se puede renderizar
 * un `@Composable` ni capturar un pixel en `testDebugUnitTest`. Por eso este
 * test NO finge un render: prueba la parte del marco que es GEOMETRIA PURA
 * ([geometriaMarco]) —que el trazo quede dentro del lienzo y que sea visible—
 * y el fix del orden de dibujo se valida con captura en dispositivo (pendiente,
 * documentada en `apply-progress`).
 *
 * ## EL BUG QUE MOTIVA ESTOS ASSERTS
 *
 * `TuiFrame` dibujaba el borde con `drawBehind` y encadenaba DESPUES
 * `.background(TerminalBlack)`: el fondo se pintaba encima y el marco
 * desaparecia. El fix pinta el fondo primero y el borde al final con
 * `drawWithContent`. La geometria no cambia; lo que cambia es que ahora el rect
 * se dibuja, y estos tests fijan sus invariantes para que el rect siga siendo
 * el correcto cuando alguien lo mueva.
 */
class TuiFrameGeometryTest {

    @Test
    fun `el trazo queda dentro del lienzo`() {
        val r = geometriaMarco(ancho = 1080f, alto = 1920f, grosor = 3f)

        assertTrue(r.left >= 0f, "el trazo no puede salirse por la izquierda: $r")
        assertTrue(r.top >= 0f, "el trazo no puede salirse por arriba: $r")
        assertTrue(r.right <= 1080f, "el trazo no puede salirse por la derecha: $r")
        assertTrue(r.bottom <= 1920f, "el trazo no puede salirse por abajo: $r")
    }

    @Test
    fun `el marco tiene area visible`() {
        val r = geometriaMarco(ancho = 400f, alto = 300f, grosor = 2.75f)

        assertTrue(r.width > 0f && r.height > 0f, "un marco de area 0 no se ve: $r")
        assertEquals(400f - 2.75f, r.width, 0.01f)
        assertEquals(300f - 2.75f, r.height, 0.01f)
    }

    @Test
    fun `el trazo se centra en el borde para no recortarse`() {
        val r = geometriaMarco(ancho = 100f, alto = 100f, grosor = 4f)

        assertEquals(2f, r.left, 0.001f)
        assertEquals(2f, r.top, 0.001f)
        assertEquals(98f, r.right, 0.001f)
        assertEquals(98f, r.bottom, 0.001f)
    }

    @Test
    fun `control negativo un lienzo degenerado no produce un rect invertido`() {
        val r = geometriaMarco(ancho = 1f, alto = 1f, grosor = 8f)

        assertTrue(r.left <= r.right, "rect invertido en horizontal: $r")
        assertTrue(r.top <= r.bottom, "rect invertido en vertical: $r")
    }

    @Test
    fun `control negativo un grosor 0 no dibuja borde`() {
        val r = geometriaMarco(ancho = 100f, alto = 100f, grosor = 0f)

        assertEquals(100f, r.width, 0.001f)
        assertEquals(100f, r.height, 0.001f)
        assertEquals(0f, r.left, 0.001f)
    }
}
