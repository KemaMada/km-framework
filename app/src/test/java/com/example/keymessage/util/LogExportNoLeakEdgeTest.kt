package com.example.keymessage.util

import com.example.keymessage.ui.log.LogExport
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F5 — El EXPORT de logs no filtra material sensible.
 *
 * ## EL DEFECTO QUE ESTE TEST TENIA (verify #381 W2)
 *
 * El test anterior emitia eventos BENIGNOS y luego afirmaba que los marcadores
 * sensibles "no aparecian". Eso no probaba nada: los marcadores nunca entraban
 * por el camino real, y su "control negativo" afirmaba que el export DEJA PASAR
 * un marcador escrito a mano en el buffer. La mutacion M6 —anadir un mnemonic
 * literal dentro de `textoExport`— seguia VERDE: el guard era tautologico.
 *
 * ## QUE SE AFIRMA AHORA, Y POR QUE ES SENSIBLE
 *
 * 1. **Espejo exacto**: `textoExport(KMLog.lines())` es EXACTAMENTE el buffer
 *    unido por saltos de linea. Si alguien anade texto al export (la mutacion
 *    M6), o si alguien re-captura de otra fuente, el `assertEquals` falla.
 * 2. **Marcadores por el camino REAL**: los marcadores sensibles se pasan a la
 *    API de dominio (`messageSent`/`messageReceived`), que es por donde la app
 *    registra. La garantia no-fuga de `KMLog` es `truncar`: el texto completo
 *    NO puede salir, y el export lo demuestra sobre su salida real.
 * 3. **Control negativo del detector**: un marcador que SI esta en el buffer
 *    (via `event`, la unica API que acepta texto libre) aparece en el export.
 *    Sin esto, los tests 1-2 pasarian con un export vacio; con esto, se prueba
 *    que el export es un espejo y no un filtro magico.
 *
 * ## POR QUE EL EXPORT NO REDACTA
 *
 * Porque NO DEBE: "redactar" en el export seria un segundo formato y una falsa
 * sensacion de seguridad. El contrato (spec R6.3) es que la fuente —`KMLog`— no
 * tenga por donde recibir payload, y que el export solo lea `lines()`.
 * `LogNoLeakTest` se mantiene intacto para la API de dominio.
 */
class LogExportNoLeakEdgeTest {

    @Before
    fun setUp() {
        KMLog.clear()
    }

    /** Marcadores unicos y LARGOS (>12): `truncar` no puede dejarlos pasar. */
    private val fraseMnemonic = "yellow-submarine-mnemonic-marcador-9f13"
    private val clavePrivada = "PRIVKEY-marcador-de-32-bytes-0000000000000000"
    private val claveSesion = "SESSION-KEY-marcador-7c2a"
    private val estadoRatchet = "RATCHET-STATE-marcador-4b1d"

    @Test
    fun `el export es EXACTAMENTE el buffer de KMLog`() {
        KMLog.CHANNELS.forEach { canal -> KMLog.event(canal, "evento de $canal") }

        val lineas = KMLog.lines()
        val exportado = LogExport.textoExport(lineas)

        assertTrue(lineas.isNotEmpty(), "pretesto: hay algo que exportar")
        assertEquals(
            lineas.joinToString("\n"),
            exportado,
            "el export tiene que ser el buffer tal cual: cualquier texto anadido o " +
                "cualquier fuente distinta de KMLog.lines() rompe este assert",
        )
        assertEquals(
            lineas.size - 1,
            exportado.count { it == '\n' },
            "ni una linea de mas ni una de menos",
        )
    }

    @Test
    fun `un secreto pasado por la API de dominio no sale completo en el export`() {
        // El camino REAL de la app: el id y el par se registran truncados y no
        // existe parametro para el payload. Si `truncar` desapareciera, este
        // test veria el marcador completo en la salida.
        KMLog.messageSent(
            messageId = clavePrivada,
            peerId = estadoRatchet,
            resultado = "FAILED",
            failureCode = "SEND_REJECTED",
        )
        KMLog.messageReceived(messageId = claveSesion, from = fraseMnemonic)

        val exportado = LogExport.textoExport(KMLog.lines())

        assertTrue(exportado.isNotEmpty(), "pretesto: hay algo que exportar")
        for (marcador in listOf(fraseMnemonic, clavePrivada, claveSesion, estadoRatchet)) {
            assertFalse(
                exportado.contains(marcador),
                "el export contiene el secreto COMPLETO '$marcador': la API de dominio no trunco",
            )
        }
        // Y la parte SI legible (12 caracteres) sigue estando: el test no pasa
        // porque el export este vacio.
        assertTrue(
            exportado.contains(KMLog.truncar(clavePrivada)),
            "el export conserva el id TRUNCADO para poder correlacionar la linea",
        )
    }

    @Test
    fun `el filtro por canal tambien es un espejo exacto`() {
        KMLog.event("[RELAY]", "conectado")
        KMLog.event("[SEND]", "msg=a1b2 -> SENT")

        val soloRelay = LogExport.filtrarPorCanal(KMLog.lines(), "[RELAY]")

        assertEquals(
            soloRelay.joinToString("\n"),
            LogExport.textoExport(soloRelay),
            "filtrar SELECCIONA lineas; no reescribe su formato",
        )
        assertFalse(
            LogExport.textoExport(soloRelay).contains("[SEND]"),
            "el filtro [RELAY] no puede arrastrar lineas de [SEND]",
        )
    }

    @Test
    fun `control negativo el export SI refleja lo que esta en el buffer`() {
        // `event` es la unica API que acepta texto libre, y por eso el unico
        // camino por el que un marcador PODRIA llegar al buffer. El export es un
        // espejo: si esta, sale. Este control negativo prueba que los asserts de
        // arriba no pasan con un export vacio o ciego.
        val marcador = "MARCADOR-DE-CONTROL-d1c4"
        KMLog.event("[SEND]", "msg=$marcador")

        val exportado = LogExport.textoExport(KMLog.lines())

        assertTrue(
            exportado.contains(marcador),
            "el export es un ESPEJO del buffer: si el marcador esta, tiene que salir",
        )
    }
}
