package com.example.keymessage.ui

import com.example.keymessage.ui.components.IndicadorP2P
import com.example.keymessage.ui.components.indicadorP2P
import com.example.keymessage.ui.theme.TerminalCyan
import com.example.keymessage.ui.theme.TerminalGray30
import com.example.keymessage.ui.theme.TerminalGreen
import com.example.keymessage.ui.theme.TerminalRed
import com.km.protocol.ConnectionState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1 — El indicador `P2P[simbolo]` habla del RELAY, no del contacto.
 *
 * ## QUE AFIRMA
 *
 *  - verde (`o`) SOLO con [ConnectionState.ONLINE];
 *  - cian (`~`) con CONNECTING / AUTHENTICATING / RECONNECTING (transicion);
 *  - rojo (`x`) con DISCONNECTED;
 *  - gris (`?`) para cualquier estado no reconocido.
 *
 * ## POR QUE ES UNA FUNCION PURA
 *
 * Porque este modulo NO tiene `ComposeTestRule`: un `when` dentro de un
 * composable no se puede afirmar en JVM. La regla de producto —"verde solo con
 * ONLINE"— vive en [indicadorP2P], y una funcion se prueba.
 *
 * ## LA LEYENDA
 *
 * [IndicadorP2P.LEYENDA] es la unica frase que fija que `P2P` mide el
 * TRANSPORTE. Sin ella, `P2P[o]` se leeria como "el contacto esta online", que
 * es justo lo que la app NO puede afirmar (no hay fuente de presencia por peer).
 */
class IndicadorP2PTest {

    @Test
    fun `ONLINE es el UNICO estado que pinta verde`() {
        val indicador = indicadorP2P(ConnectionState.ONLINE)

        assertEquals(IndicadorP2P.GLIFO_ONLINE, indicador.glifo, "el glifo ONLINE es `o`")
        assertEquals(TerminalGreen, indicador.color, "y es VERDE: el relay esta enlazado")
        assertEquals("P2P[o]", indicador.etiqueta, "la etiqueta compacta une prefijo y glifo")
    }

    @Test
    fun `los estados de transicion pintan cian`() {
        val transitorios = listOf(
            ConnectionState.CONNECTING,
            ConnectionState.AUTHENTICATING,
            ConnectionState.RECONNECTING,
        )

        transitorios.forEach { estado ->
            val indicador = indicadorP2P(estado)
            assertEquals(IndicadorP2P.GLIFO_TRANSICION, indicador.glifo, "`~` para $estado")
            assertEquals(TerminalCyan, indicador.color, "y CIAN: no es exito todavia")
        }
    }

    @Test
    fun `DISCONNECTED pinta rojo`() {
        val indicador = indicadorP2P(ConnectionState.DISCONNECTED)

        assertEquals(IndicadorP2P.GLIFO_OFFLINE, indicador.glifo, "el glifo offline es `x`")
        assertEquals(TerminalRed, indicador.color, "y es ROJO: no hay enlace")
    }

    @Test
    fun `control negativo ningun estado que no sea ONLINE pinta verde`() {
        // El control negativo de verdad: se recorren los CINCO estados y se
        // afirma que solo ONLINE pinta el glifo/color verde.
        val verdes = ConnectionState.entries.filter { indicadorP2P(it).color == TerminalGreen }

        assertEquals(
            listOf(ConnectionState.ONLINE),
            verdes,
            "solo ONLINE puede pintar verde; cualquier otro seria presencia inventada",
        )
    }

    @Test
    fun `la descripcion NUNCA menciona al contacto`() {
        ConnectionState.entries.forEach { estado ->
            val descripcion = indicadorP2P(estado).descripcion.lowercase()
            assertFalse(
                descripcion.contains("contacto"),
                "la descripcion de $estado no puede hablar del peer: solo del relay",
            )
            assertFalse(descripcion.contains("peer"), "ni del peer")
            assertTrue(
                descripcion.contains("relay"),
                "y SI dice que habla del enlace al relay ($estado)",
            )
        }
    }

    @Test
    fun `la leyenda dice que P2P es el transporte y NO la presencia del contacto`() {
        val leyenda = IndicadorP2P.LEYENDA

        assertTrue(leyenda.contains("relay"), "la leyenda nombra el relay")
        assertTrue(
            leyenda.contains("NO indica", ignoreCase = true),
            "y NIEGA explicitamente que diga si el contacto esta conectado",
        )
    }

    @Test
    fun `el glifo y color de reserva NO son los de exito`() {
        // La rama `else` de [indicadorP2P] existe para no inventar un verde
        // optimista si el core anade un estado. El contrato del reserva es que
        // NO coincide con el de ONLINE: ni el glifo ni el color.
        assertTrue(
            IndicadorP2P.GLIFO_DESCONOCIDO != IndicadorP2P.GLIFO_ONLINE,
            "el glifo de reserva no puede ser el de exito",
        )
        assertTrue(
            TerminalGray30 != TerminalGreen,
            "el color de reserva (gris honesto) no puede ser el verde de exito",
        )
    }
}
