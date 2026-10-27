package com.km.desktop.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * 1.1 — La ruta de configuracion se resuelve por PLATAFORMA.
 *
 * ## QUE SE AFIRMA
 *
 * - Linux conserva XDG y cae a `~/.config` (comportamiento de G1).
 * - Windows usa `APPDATA` y cae a `USERPROFILE\AppData\Roaming`.
 * - macOS usa `~/Library/Application Support`.
 * - Sin variables no se inventa una ruta: se falla.
 *
 * ## POR QUE SE PUEDEN PROBAR LAS TRES EN LINUX
 *
 * Porque `osName` y `env` son parametros. La rama de Windows no se publica sin
 * ejecutarse: se ejecuta aqui, sobre el mismo JVM, con un entorno simulado. Un
 * test que solo corriera en Windows no existiria en CI.
 */
class ConfigPathsTest {

    private val linux = mapOf("XDG_CONFIG_HOME" to "/tmp/cfg", "HOME" to "/home/u")
    private val windows = mapOf("APPDATA" to "/tmp/appdata", "USERPROFILE" to "/home/u")
    private val mac = mapOf("HOME" to "/home/u")

    @Test
    fun `linux usa XDG_CONFIG_HOME`() {
        assertEquals(
            Path.of("/tmp/cfg/keymessage"),
            ConfigPaths.resolve(linux, "Linux"),
        )
        assertEquals(
            Path.of("/tmp/cfg/keymessage/identity.kmkey"),
            ConfigPaths.identityPath(linux, "Linux"),
        )
        assertEquals(
            Path.of("/tmp/cfg/keymessage/peers.json"),
            ConfigPaths.peersPath(linux, "Linux"),
        )
    }

    @Test
    fun `linux sin XDG cae a DOT config`() {
        assertEquals(
            Path.of("/home/u/.config/keymessage/identity.kmkey"),
            ConfigPaths.identityPath(mapOf("HOME" to "/home/u"), "Linux"),
        )
    }

    @Test
    fun `windows usa APPDATA`() {
        assertEquals(
            Path.of("/tmp/appdata/keymessage/identity.kmkey"),
            ConfigPaths.identityPath(windows, "Windows 10"),
        )
    }

    @Test
    fun `windows sin APPDATA cae a USERPROFILE AppData Roaming`() {
        assertEquals(
            Path.of("/home/u/AppData/Roaming/keymessage/identity.kmkey"),
            ConfigPaths.identityPath(mapOf("USERPROFILE" to "/home/u"), "Windows 11"),
        )
    }

    @Test
    fun `macOS usa Library Application Support`() {
        assertEquals(
            Path.of("/home/u/Library/Application Support/keymessage/identity.kmkey"),
            ConfigPaths.identityPath(mac, "Mac OS X"),
        )
        // `Darwin` es el nombre del kernel y algunos JVM lo usan como os.name.
        assertEquals(
            Path.of("/home/u/Library/Application Support/keymessage"),
            ConfigPaths.resolve(mac, "Darwin"),
        )
    }

    @Test
    fun `sin variables de entorno se falla y no se inventa una ruta`() {
        val fallo = assertThrows(IllegalStateException::class.java) {
            ConfigPaths.resolve(emptyMap(), "Linux")
        }
        assertEquals(true, fallo.message?.contains("XDG_CONFIG_HOME") == true)

        assertThrows(IllegalStateException::class.java) {
            ConfigPaths.resolve(emptyMap(), "Windows 10")
        }
        assertThrows(IllegalStateException::class.java) {
            ConfigPaths.resolve(emptyMap(), "Mac OS X")
        }
    }

    /**
     * CONTROL NEGATIVO: las ramas no se pisan.
     *
     * En Linux, tener `APPDATA` no puede cambiar la ruta: si `family` clasificara
     * mal, este test seria verde con los dos caminos mezclados.
     */
    @Test
    fun `control negativo en Linux APPDATA no se usa`() {
        val soloWindows = mapOf("APPDATA" to "/tmp/appdata")
        assertThrows(IllegalStateException::class.java) {
            ConfigPaths.resolve(soloWindows, "Linux")
        }
        assertEquals(
            Path.of("/tmp/appdata/keymessage"),
            ConfigPaths.resolve(soloWindows, "Windows 10"),
            "y en Windows SI se usa: el control demuestra que la rama existe",
        )
    }
}
