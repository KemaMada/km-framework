package com.example.keymessage.ui

import com.example.keymessage.ui.components.etiquetaConexion
import com.example.keymessage.ui.components.etiquetaScroll
import com.example.keymessage.ui.components.etiquetaSinNucleo
import com.km.protocol.ConnectionState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1 — La barra de estado NO miente sobre la conexion.
 *
 * ## QUE AFIRMA
 *
 * Que `etiquetaConexion` —el texto de la barra— solo dice `ONLINE` cuando el
 * `ConnectionState` REAL es `ConnectionState.ONLINE`. En cualquier otro estado
 * dice `SIN CONEXION`, `CONECTANDO` o `RECONECTANDO` con el codigo real.
 *
 * ## POR QUE ES UNA FUNCION PURA Y NO UN COMPOSABLE
 *
 * Porque no hay `ComposeTestRule` en el modulo: dentro de un composable no se
 * puede afirmar nada en JVM. La regla que importa —"ONLINE solo con ONLINE"— es
 * la funcion, y la funcion se prueba aqui.
 *
 * ## CONTROL NEGATIVO
 *
 * Los tests `control*` afirman que `ONLINE` se DETECTA como subcadena cuando
 * esta (para que el detector de arriba no pase por vacio) y que NO aparece
 * cuando el estado no es online.
 */
class TuiComponentsGeometryTest {

    @Test
    fun `ONLINE solo con el estado ONLINE`() {
        assertEquals(
            "ONLINE  wss://relay",
            etiquetaConexion(ConnectionState.ONLINE, "wss://relay", null),
        )
    }

    @Test
    fun `DISCONNECTED dice SIN CONEXION con el codigo real`() {
        val etiqueta = etiquetaConexion(ConnectionState.DISCONNECTED, "wss://relay", "BOOTSTRAP_ALL_FAILED")
        assertTrue(etiqueta.startsWith("SIN CONEXION"), "empieza por SIN CONEXION: $etiqueta")
        assertTrue(etiqueta.contains("BOOTSTRAP_ALL_FAILED"), "lleva el codigo real: $etiqueta")
        assertFalse(etiqueta.contains("ONLINE"), "NO dice ONLINE estando desconectado: $etiqueta")
    }

    @Test
    fun `CONNECTING y RECONNECTING NO dicen ONLINE`() {
        for (estado in listOf(ConnectionState.CONNECTING, ConnectionState.RECONNECTING)) {
            val etiqueta = etiquetaConexion(estado, "wss://relay", null)
            assertFalse(
                etiqueta.contains("ONLINE"),
                "el estado $estado no puede decir ONLINE: $etiqueta",
            )
        }
    }

    @Test
    fun `sin endpoint el ONLINE no inventa un endpoint`() {
        val etiqueta = etiquetaConexion(ConnectionState.ONLINE, null, null)
        assertTrue(etiqueta.contains("ONLINE"), "es online: $etiqueta")
        assertTrue(
            etiqueta.contains("desconocido"),
            "sin endpoint, lo dice en vez de dejar un hueco: $etiqueta",
        )
    }

    @Test
    fun `el caso sin nucleo dice SIN CONEXION con estado y codigo`() {
        val etiqueta = etiquetaSinNucleo("DISCONNECTED", "NO_CORE")
        assertTrue(etiqueta.contains("NO_CORE"), "lleva el codigo: $etiqueta")
        assertFalse(etiqueta.contains("ONLINE"), "no finge online: $etiqueta")
    }

    @Test
    fun `control positivo ONLINE se detecta como subcadena cuando esta`() {
        val etiqueta = etiquetaConexion(ConnectionState.ONLINE, "wss://x", null)
        assertTrue(
            etiqueta.contains("ONLINE"),
            "si el detector no viera ONLINE aqui, los tests de arriba no probarian nada",
        )
    }

    @Test
    fun `el indicador de scroll con lista vacia es 0 de 0`() {
        assertEquals("[0/0]", etiquetaScroll(1, 0))
        assertEquals("[0/0]", etiquetaScroll(0, 0))
    }

    @Test
    fun `el indicador de scroll acota la posicion al rango`() {
        assertEquals("[1/5]", etiquetaScroll(1, 5))
        assertEquals("[5/5]", etiquetaScroll(9, 5), "no pasa de total")
        assertEquals("[1/5]", etiquetaScroll(-3, 5), "no baja de 1")
    }
}
