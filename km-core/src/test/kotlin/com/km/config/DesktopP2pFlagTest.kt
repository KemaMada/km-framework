package com.km.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 1.1 — El flag `km.desktop.p2p` (DP8) nace OFF, esta registrado y se
 * resuelve por la misma maquinaria que el resto del registro.
 *
 * Lo que se demuestra: el flag existe con su clave, su variable de entorno, su
 * default seguro y su descripcion; esta indexado en `all`/`byKey`/`byEnvVar`;
 * y `isEnabled` respeta la precedencia override > entorno > default.
 *
 * Lo que NO se demuestra: que el camino P2P de escritorio exista. Encender el
 * flag no prueba la feature: eso lo cubren los slices 3-7. Aqui solo se fija el
 * interruptor y su default apagado.
 */
class DesktopP2pFlagTest {

    private fun envOf(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { name -> map[name] }
    }

    private val sinEntorno: (String) -> String? = { null }

    @Test
    @DisplayName("1.1-01 el flag tiene clave, env var, default OFF y descripcion")
    fun definicionDelFlag() {
        val flag = KmFlags.DESKTOP_P2P
        assertEquals("km.desktop.p2p", flag.key)
        assertEquals("KM_DESKTOP_P2P", flag.envVar)
        assertFalse(flag.default, "el default debe ser OFF (seguro)")
        assertTrue(flag.description.isNotBlank(), "el flag debe documentar que gobierna")
    }

    @Test
    @DisplayName("1.1-02 esta registrado en all, byKey y byEnvVar")
    fun estaRegistrado() {
        assertTrue(KmFlags.all.contains(KmFlags.DESKTOP_P2P))
        assertEquals(KmFlags.DESKTOP_P2P, KmFlags.byKey["km.desktop.p2p"])
        assertEquals(KmFlags.DESKTOP_P2P, KmFlags.byEnvVar["KM_DESKTOP_P2P"])
        assertNotNull(KmFlags.byKey[KmFlags.DESKTOP_P2P.key])
    }

    @Test
    @DisplayName("1.1-03 sin entorno ni override vale su default (OFF)")
    fun sinEntornoValeDefault() {
        assertFalse(KmFlags.isEnabled(KmFlags.DESKTOP_P2P, env = sinEntorno, overrides = emptyMap()))
    }

    @Test
    @DisplayName("1.1-04 el entorno lo enciende por KM_DESKTOP_P2P")
    fun elEntornoLoEnciende() {
        assertTrue(KmFlags.isEnabled(KmFlags.DESKTOP_P2P, env = envOf("KM_DESKTOP_P2P" to "true")))
        assertFalse(KmFlags.isEnabled(KmFlags.DESKTOP_P2P, env = envOf("KM_DESKTOP_P2P" to "off")))
    }

    @Test
    @DisplayName("1.1-05 el override gana sobre el entorno")
    fun overrideGanaSobreEntorno() {
        val flag = KmFlags.DESKTOP_P2P
        assertFalse(
            KmFlags.isEnabled(flag, env = envOf(flag.envVar to "true"), overrides = mapOf(flag.key to false)),
            "override OFF debe ganar a entorno ON",
        )
        assertTrue(
            KmFlags.isEnabled(flag, env = envOf(flag.envVar to "false"), overrides = mapOf(flag.key to true)),
            "override ON debe ganar a entorno OFF",
        )
    }

    @Test
    @DisplayName("1.1-06 el acceso de conveniencia existe y no enciende por defecto")
    fun accesoDeConveniencia() {
        // El acceso lee el entorno del PROCESO; el runner de tests no define
        // KM_DESKTOP_P2P, asi que debe reflejar el default seguro.
        assertFalse(KmFlags.desktopP2p, "sin KM_DESKTOP_P2P el acceso debe ser OFF")
    }

    @Test
    @DisplayName("1.1-07 el flag NO cambia el default del resto del registro")
    fun noAlteraOtrosFlags() {
        for (flag in KmFlags.all) {
            assertFalse(flag.default, "todos los flags nacen OFF: ${flag.key}")
        }
        assertTrue(KmFlags.all.size >= 8, "el registro debe contener al menos el flag nuevo ademas de los 7 previos")
    }
}
