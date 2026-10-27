package com.km.desktop.client

import com.km.desktop.util.DiagLog
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.createTempDirectory
import java.nio.file.Files

/**
 * 3.9 — Diagnostico y logs: solo el buffer redactado, filtro por canal y export
 * que no inventa lineas.
 *
 * ## EL CENTINELA
 *
 * Se emite por `DiagLog` una linea con una frase de paso en forma de asignacion.
 * `DiagLog.redact` la sustituye antes de guardarla, y el export no tiene otra
 * fuente: si el centinela apareciera, el export estaria leyendo algo que no es el
 * buffer.
 */
class DiagnosticsServiceTest {

    @AfterEach
    fun limpiar() {
        DiagLog.clear()
        DiagLog.install(com.km.desktop.util.StreamDiagSink())
    }

    @Test
    fun `el export son EXACTAMENTE las lineas del buffer`() {
        DiagLog.clear()
        DiagLog.event("[RELAY]", "conexion ONLINE")
        DiagLog.event("[SEND]", "msg=a1b2 peer=9f8e -> SENT")

        val servicio = DiagnosticsService()
        val lineas = servicio.exportLines()
        assertEquals(2, lineas.size)
        assertTrue(lineas[0].startsWith("[RELAY]"))
        assertTrue(lineas[1].startsWith("[SEND]"))
        assertEquals(lineas.joinToString("\n") + "\n", servicio.exportText())
    }

    @Test
    fun `el filtro por canal deja solo ese canal`() {
        DiagLog.clear()
        DiagLog.event("[RELAY]", "conexion ONLINE")
        DiagLog.event("[AUTH]", "AUTH_OK")

        val servicio = DiagnosticsService()
        val soloAuth = servicio.exportLines("[AUTH]")
        assertEquals(1, soloAuth.size)
        assertTrue(soloAuth.single().contains("AUTH_OK"))
    }

    @Test
    fun `el centinela de la frase de paso NO aparece en el export`() {
        DiagLog.clear()
        DiagLog.event("[AUTH]", "passphrase=SUPERSECRETO123 abriendo almacen")

        val servicio = DiagnosticsService()
        val texto = servicio.exportText()
        assertFalse(texto.contains("SUPERSECRETO123"), "el export no puede llevar la frase: $texto")
        assertTrue(texto.contains("<redactado>"), texto)
    }

    @Test
    fun `las filas llevan canal y texto con hora del log`() {
        DiagLog.clear()
        DiagLog.event("[SESSION]", "instalada peer=abc")

        val fila = DiagnosticsService().rows().single()
        assertEquals("[SESSION]", fila.channel)
        assertTrue(fila.text.startsWith("[SESSION] "), fila.text)
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}").containsMatchIn(fila.text), fila.text)
    }

    @Test
    fun `el export usa LF y no depende del sistema`() {
        DiagLog.clear()
        DiagLog.event("[RELAY]", "uno")
        DiagLog.event("[RELAY]", "dos")

        val texto = DiagnosticsService().exportText()
        assertTrue(texto.endsWith("\n"))
        assertFalse(texto.contains("\r\n"), "LF, no CRLF")
    }

    @Test
    fun `ExportService guarda el km en ISO-8859-1 exacto y el log aparte`() {
        val dir = createTempDirectory("km-export")
        try {
            val servicio = ExportService(dir)
            // 0xE9 = 'é' en ISO-8859-1; en UTF-8 serian dos bytes y el .km
            // dejaria de verificar.
            val portador = "ab\u00e9cd"
            val ruta = servicio.saveCarrier(portador)
            assertTrue(ruta.fileName.toString().endsWith(".km"))
            assertEquals(5, Files.size(ruta))
            assertEquals(portador, String(Files.readAllBytes(ruta), Charsets.ISO_8859_1))

            val log1 = servicio.saveLogs("[RELAY] uno\n", null)
            val log2 = servicio.saveLogs("[RELAY] uno\n", "[RELAY]")
            assertTrue(log1.fileName.toString().startsWith("km-logs-"))
            assertTrue(log2.fileName.toString().contains("relay"))
            assertFalse(log1 == log2, "dos exports no se pisan")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
