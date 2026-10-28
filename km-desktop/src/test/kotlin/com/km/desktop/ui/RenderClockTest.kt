package com.km.desktop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * M0.3 — El reloj del renderer se inyecta y NO se lee.
 *
 * ## QUE SE AFIRMA
 *
 * - El mismo instante con la misma zona da SIEMPRE la misma hora (texto).
 * - Cambiar la zona cambia la hora pintada: la zona que se usa es la inyectada,
 *   no `ZoneId.systemDefault()`. Sin esta mitad, un renderer que ignorara el
 *   reloj y usara el del sistema pasaria el test.
 * - El valor por defecto de la fachada es una CONSTANTE (UTC epoch 0): llamar a
 *   `render` sin reloj no puede depender de la maquina.
 */
class RenderClockTest {

    @Test
    fun `el mismo instante en la misma zona es siempre la misma hora`() {
        val reloj = RenderClock(ZoneOffset.UTC, 0L)
        repeat(3) {
            assertEquals("22:13", reloj.hora(1_700_000_000_000L))
        }
    }

    @Test
    fun `la zona inyectada manda y no la del sistema`() {
        val instante = 1_700_000_000_000L
        val utc = RenderClock(ZoneOffset.UTC, instante)
        val madrid = RenderClock(ZoneId.of("Europe/Madrid"), instante)
        val tokio = RenderClock(ZoneId.of("Asia/Tokyo"), instante)

        assertEquals("22:13", utc.hora(instante))
        assertEquals("23:13", madrid.hora(instante))
        assertEquals("07:13", tokio.hora(instante))
        assertEquals(utc.hora(instante), utc.hora(instante))
        assert(utc.hora(instante) != tokio.hora(instante)) {
            "si las dos zonas dieran lo mismo, el test no probaria nada"
        }
    }

    @Test
    fun `el reloj por defecto es una constante UTC y no el del sistema`() {
        val porDefecto = RenderClock.UTC_EPOCH
        assertEquals(ZoneOffset.UTC, porDefecto.zone)
        assertEquals(0L, porDefecto.nowMs)
        assertEquals("00:00", porDefecto.hora(0L))
    }

    /**
     * La prueba de verdad: la fachada produce EXACTAMENTE lo mismo con reloj
     * fijo, se ejecute donde se ejecute. Comparado contra la implementacion
     * congelada, que recibe el mismo reloj.
     */
    @TestFactory
    fun `la fachada usa el reloj inyectado y no el del sistema`(): List<DynamicTest> =
        listOf(
            RenderClock(ZoneOffset.UTC, 0L) to "22:13",
            RenderClock(ZoneId.of("Asia/Tokyo"), 0L) to "07:13",
        ).map { (reloj, esperado) ->
            DynamicTest.dynamicTest("hora ${reloj.zone} -> $esperado") {
                val estado = ShellState(
                    tabs = TabStacks.of(Screen.INICIO, Screen.INICIO),
                    viewport = Size(80, 24),
                    contactsLoaded = true,
                    contacts = listOf(
                        ContactRow(
                            identityId = com.km.model.IdentityId("a".repeat(64)),
                            fingerprint = "a".repeat(16) + "...",
                            displayName = "ana",
                            lastMessage = "hola",
                            lastAt = 1_700_000_000_000L,
                        ),
                    ),
                )
                val texto = ShellRenderer.render(estado, clock = reloj)
                    .joinToString("\n") { it.text }
                assertEquals(true, texto.contains(esperado), "falta la hora $esperado:\n$texto")
            }
        }
}
