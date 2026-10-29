package com.example.keymessage.ui

import com.example.keymessage.ui.log.LogExport
import com.example.keymessage.util.KMLog
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F5 — El filtro y el volcado del LogPanel.
 *
 * ## QUE AFIRMA
 *
 * Que `filtrarPorCanal` selecciona por canal sin reescribir la linea, que
 * `TODOS` devuelve el buffer entero, que un canal INEXISTENTE no lanza y
 * devuelve vacio, y que el buffer vacio se exporta como la cadena vacia.
 *
 * ## CONTROL NEGATIVO
 *
 * El test del canal inexistente ES el control: si `filtrarPorCanal` lanzara o
 * devolviera todo, el filtro mentiria (diria "no hay lineas de [AUTH]" o
 * "aqui tienes todas"). Y el de "no mutacion" comprueba que la linea filtrada
 * es BYTE A BYTE la original.
 */
class LogExportTest {

    @Before
    fun setUp() {
        KMLog.clear()
    }

    /** Lineas sinteticas con dos canales distintos. */
    private fun lineas() = listOf(
        "14:03:22 [RELAY] conectado",
        "14:03:25 [SEND] msg=a1b2 -> SENT",
        "14:03:26 [RECV] msg=c3d4 from=9f8e",
        "14:03:27 [SEND] msg=e5f6 -> DELIVERED",
    )

    @Test
    fun `TODOS devuelve el buffer entero`() {
        assertEquals(lineas(), LogExport.filtrarPorCanal(lineas(), LogExport.TODOS))
    }

    @Test
    fun `filtra por canal`() {
        val send = LogExport.filtrarPorCanal(lineas(), "[SEND]")
        assertEquals(2, send.size, "hay dos lineas de [SEND]")
        assertTrue(send.all { it.contains("[SEND]") }, "todas son de [SEND]: $send")
    }

    @Test
    fun `el filtro NO reescribe la linea`() {
        val original = lineas()
        val filtradas = LogExport.filtrarPorCanal(original, "[SEND]")
        assertEquals(
            original.filter { it.contains("[SEND]") },
            filtradas,
            "la linea filtrada es la MISMA que la del buffer",
        )
    }

    @Test
    fun `control negativo un canal inexistente no lanza y devuelve vacio`() {
        val filtradas = LogExport.filtrarPorCanal(lineas(), "[NO_EXISTE]")
        assertEquals(emptyList<String>(), filtradas, "un canal sin lineas da vacio, no todo ni error")
    }

    @Test
    fun `un canal vacio devuelve todo (es TODOS)`() {
        assertEquals(lineas(), LogExport.filtrarPorCanal(lineas(), ""))
    }

    @Test
    fun `el buffer vacio se exporta como cadena vacia`() {
        assertEquals("", LogExport.textoExport(emptyList()))
    }

    @Test
    fun `textoExport une por salto de linea`() {
        assertEquals("a\nb\nc", LogExport.textoExport(listOf("a", "b", "c")))
    }

    @Test
    fun `el filtro se puede alimentar del buffer real de KMLog`() {
        KMLog.event("[RELAY]", "conectado")
        KMLog.event("[SEND]", "msg=x -> SENT")
        val soloRelay = LogExport.filtrarPorCanal(KMLog.lines(), "[RELAY]")
        assertEquals(1, soloRelay.size, "solo la linea de [RELAY]")
        assertTrue(soloRelay.single().contains("[RELAY]"))
    }
}
