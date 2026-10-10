package com.km.daemon.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * A1.2 — Carga de `bootstrapRelays` (semillas de reles) desde configuracion.
 *
 * El daemon NO interpreta estas semillas: las carga y las deja disponibles
 * como configuracion. El parseo de su SEMANTICA (clasificar `.onion`,
 * registrarlas como `RelaySeed`) ocurre en el cliente, y solo con
 * `km.roles.capabilities` encendido.
 */
class DaemonConfigBootstrapTest {

    private fun sinEnv(name: String): String? = null

    @Test
    @DisplayName("A1.2-11 por defecto no hay semillas")
    fun porDefectoVacio() {
        val cfg = ConfigLoader.load(env = ::sinEnv, configPath = Paths.get("/no/existe/km-daemon.conf.json"))
        assertEquals(emptyList<String>(), cfg.bootstrapRelays)
    }

    @Test
    @DisplayName("A1.2-12 la variable de entorno se parte por comas y descarta vacios")
    fun desdeEntorno() {
        val env: (String) -> String? = { name ->
            if (name == DaemonConfig.ENV_BOOTSTRAP_RELAYS) "100.64.0.1:8080, relay.onion:443 ,, " else null
        }
        val cfg = ConfigLoader.load(env = env, configPath = Paths.get("/no/existe/km-daemon.conf.json"))
        assertEquals(listOf("100.64.0.1:8080", "relay.onion:443"), cfg.bootstrapRelays)
    }

    @Test
    @DisplayName("A1.2-13 una variable vacia es una lista vacia, no un error")
    fun entornoVacio() {
        val env: (String) -> String? = { name ->
            if (name == DaemonConfig.ENV_BOOTSTRAP_RELAYS) "   " else null
        }
        val cfg = ConfigLoader.load(env = env, configPath = Paths.get("/no/existe/km-daemon.conf.json"))
        assertTrue(cfg.bootstrapRelays.isEmpty())
    }

    @Test
    @DisplayName("A1.2-14 el fichero JSON acepta un array de semillas")
    fun desdeFicheroArray() {
        val file = Files.createTempFile("km-daemon-bootstrap", ".json")
        try {
            Files.writeString(file, """{"bootstrapRelays":["100.64.0.1:8080","relay.onion:443",""]}""")
            val cfg = ConfigLoader.load(env = ::sinEnv, configPath = file)
            assertEquals(listOf("100.64.0.1:8080", "relay.onion:443"), cfg.bootstrapRelays)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    @DisplayName("A1.2-15 el fichero JSON acepta una cadena separada por comas")
    fun desdeFicheroString() {
        val file = Files.createTempFile("km-daemon-bootstrap", ".json")
        try {
            Files.writeString(file, """{"bootstrapRelays":"a:1, b:2"}""")
            val cfg = ConfigLoader.load(env = ::sinEnv, configPath = file)
            assertEquals(listOf("a:1", "b:2"), cfg.bootstrapRelays)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
