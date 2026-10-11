package com.km.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 0.1 — El registro [KmFlags] tiene un default seguro por cada flag y resuelve
 * entorno y overrides con la precedencia declarada.
 *
 * Lo que se demuestra:
 *  1. Todo flag del diseño existe, con default y con env var no vacios.
 *  2. Sin entorno ni override, cada flag vale su default (todos OFF).
 *  3. El parsing booleano acepta las formas habituales y cae al default ante
 *     basura, sin lanzar.
 *  4. La precedencia es override > entorno > default.
 *  5. `km.signaling.legacy3001` nace OFF y su env var es `KM_LEGACY_3001`.
 *
 * No se demuestra nada sobre el comportamiento detras de cada flag: encender
 * el flag no prueba que la feature exista (eso es de cada slice A1-A5).
 */
class KmFlagsDefaultsTest {

    /** Entorno falso: mapa de variables. Cualquier otra variable -> null. */
    private fun envOf(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { name -> map[name] }
    }

    private val sinEntorno: (String) -> String? = { null }

    @Test
    @DisplayName("0.1-01 el registro cubre exactamente los flags del diseño")
    fun registroCubreFlagsDelDiseno() {
        val claves = KmFlags.all.map { it.key }.toSet()
        assertEquals(
            setOf(
                "km.roles.capabilities",
                "km.signaling.registry",
                "km.contactfile.enabled",
                "km.relay.queue.strict",
                "km.chain.probe",
                "km.desktop.p2p",
                "km.tor.mode",
                "km.signaling.legacy3001",
            ),
            claves,
        )
    }

    @Test
    @DisplayName("0.1-02 todo flag tiene default documentado, env var y descripcion")
    fun todoFlagTieneDefaultYDocumentacion() {
        assertTrue(KmFlags.all.isNotEmpty(), "el registro no puede estar vacio")
        for (flag in KmFlags.all) {
            assertTrue(flag.key.startsWith("km."), "clave fuera del espacio km.*: ${flag.key}")
            assertTrue(flag.envVar.startsWith("KM_"), "env var fuera del espacio KM_*: ${flag.envVar}")
            assertTrue(flag.description.isNotBlank(), "falta descripcion para ${flag.key}")
            assertFalse(flag.default, "el default de ${flag.key} debe ser OFF (seguro)")
        }
    }

    @Test
    @DisplayName("0.1-03 sin entorno ni override cada flag vale su default")
    fun sinEntornoValeDefault() {
        for (flag in KmFlags.all) {
            assertEquals(
                flag.default,
                KmFlags.isEnabled(flag, env = sinEntorno, overrides = emptyMap()),
                "flag ${flag.key} no devolvio su default sin entorno",
            )
        }
    }

    @Test
    @DisplayName("0.1-04 el parsing booleano acepta formas habituales")
    fun parsingBooleanoAceptaFormasHabituales() {
        for (raw in listOf("true", "TRUE", " 1 ", "yes", "on", "enabled")) {
            assertTrue(KmFlags.parseBoolean(raw, default = false), "deberia ser true: '$raw'")
        }
        for (raw in listOf("false", "FALSE", "0", "no", "off", "disabled")) {
            assertFalse(KmFlags.parseBoolean(raw, default = true), "deberia ser false: '$raw'")
        }
    }

    @Test
    @DisplayName("0.1-05 valor invalido o ausente cae al default sin lanzar")
    fun valorInvalidoCaeAlDefault() {
        assertFalse(KmFlags.parseBoolean(null, default = false))
        assertTrue(KmFlags.parseBoolean(null, default = true))
        assertFalse(KmFlags.parseBoolean("", default = false))
        assertFalse(KmFlags.parseBoolean("banana", default = false))
        assertTrue(KmFlags.parseBoolean("banana", default = true))
    }

    @Test
    @DisplayName("0.1-06 el entorno enciende un flag por su KM_*")
    fun elEntornoEnciendeFlag() {
        val flag = KmFlags.CHAIN_PROBE
        assertTrue(
            KmFlags.isEnabled(flag, env = envOf(flag.envVar to "true")),
            "el entorno deberia encender ${flag.key}",
        )
        assertFalse(
            KmFlags.isEnabled(flag, env = envOf(flag.envVar to "off")),
            "el entorno deberia apagar ${flag.key}",
        )
    }

    @Test
    @DisplayName("0.1-07 el override gana sobre el entorno")
    fun overrideGanaSobreEntorno() {
        val flag = KmFlags.TOR_MODE
        // Entorno ON, override OFF -> OFF.
        assertFalse(
            KmFlags.isEnabled(flag, env = envOf(flag.envVar to "true"), overrides = mapOf(flag.key to false)),
        )
        // Entorno OFF, override ON -> ON.
        assertTrue(
            KmFlags.isEnabled(flag, env = envOf(flag.envVar to "false"), overrides = mapOf(flag.key to true)),
        )
    }

    @Test
    @DisplayName("0.1-08 legacy3001 nace OFF con env var KM_LEGACY_3001")
    fun legacy3001NaceApagado() {
        val flag = KmFlags.SIGNALING_LEGACY3001
        assertEquals("km.signaling.legacy3001", flag.key)
        assertEquals("KM_LEGACY_3001", flag.envVar)
        assertFalse(flag.default)
        assertFalse(KmFlags.isEnabled(flag, env = sinEntorno))
        assertTrue(KmFlags.isEnabled(flag, env = envOf("KM_LEGACY_3001" to "true")))
    }

    @Test
    @DisplayName("0.1-09 los indices por clave y env var coinciden con el registro")
    fun indicesCoincidenConRegistro() {
        assertEquals(KmFlags.all.size, KmFlags.byKey.size)
        assertEquals(KmFlags.all.size, KmFlags.byEnvVar.size)
        for (flag in KmFlags.all) {
            assertEquals(flag, KmFlags.byKey[flag.key])
            assertEquals(flag, KmFlags.byEnvVar[flag.envVar])
        }
    }
}
