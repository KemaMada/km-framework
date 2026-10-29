package com.km.desktop.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * S1 — `P2pMode`: tri-estado con compatibilidad booleana y default seguro.
 *
 * `KmFlags.DESKTOP_P2P` no se toca: su default booleano sigue siendo `false` y
 * `DesktopP2pFlagTest` lo fija. Este test comprueba la traduccion que vive
 * encima, que es la que el escritorio consume.
 */
class P2pModeTest {

    @Test
    @DisplayName("1.3-11 sin variable el modo es OFF")
    fun sinEntornoEsOff() {
        assertEquals(P2pMode.OFF, P2pMode.fromEnv { null })
        assertEquals(P2pMode.OFF, P2pMode.parse(null))
        assertEquals(P2pMode.OFF, P2pMode.parse("   "))
    }

    @Test
    @DisplayName("1.3-12 off/false/0/no son OFF")
    fun apagados() {
        for (raw in listOf("off", "OFF", " false ", "0", "no", "disabled")) {
            assertEquals(P2pMode.OFF, P2pMode.parse(raw), "valor: '$raw'")
        }
    }

    @Test
    @DisplayName("1.3-13 auto/true/1/yes son AUTO (compatibilidad booleana)")
    fun autoYCompatibilidad() {
        for (raw in listOf("auto", "AUTO", "true", "TRUE", "1", "yes", "enabled")) {
            assertEquals(P2pMode.AUTO, P2pMode.parse(raw), "valor: '$raw'")
        }
    }

    @Test
    @DisplayName("1.3-14 on es ON, y NO cae en la tabla booleana")
    fun onEsEstricto() {
        assertEquals(P2pMode.ON, P2pMode.parse("on"))
        assertEquals(P2pMode.ON, P2pMode.parse("ON"))
    }

    @Test
    @DisplayName("1.3-15 un valor no reconocido cae a OFF, no enciende nada")
    fun valorDesconocidoEsOff() {
        assertEquals(P2pMode.OFF, P2pMode.parse("quizas"))
        assertEquals(P2pMode.OFF, P2pMode.parse("2"))
    }

    @Test
    @DisplayName("1.3-16 fromEnv lee KM_DESKTOP_P2P y solo esa variable")
    fun leeLaVariable() {
        assertEquals(P2pMode.ON, P2pMode.fromEnv { name ->
            if (name == "KM_DESKTOP_P2P") "on" else null
        })
        assertEquals(P2pMode.OFF, P2pMode.fromEnv { name ->
            if (name == "KM_DESKTOP_P2P_OTRO") "on" else null
        })
        assertEquals("KM_DESKTOP_P2P", P2pMode.ENV_VAR)
    }

    @Test
    @DisplayName("1.3-17 solo AUTO y ON intentan P2P")
    fun attemptsP2p() {
        assertFalse(P2pMode.OFF.attemptsP2p)
        assertTrue(P2pMode.AUTO.attemptsP2p)
        assertTrue(P2pMode.ON.attemptsP2p)
        assertEquals(listOf("off", "auto", "on"), P2pMode.entries.map { it.wireName })
    }
}
