package com.example.keymessage.ui

import com.example.keymessage.build.NoFrameworkReimplementationTest
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R14 — UN SOLO NUCLEO VIVO POR IDENTIDAD, Y UN REPOSITORIO QUE NO LO CAPTURA.
 *
 * ## EL DEFECTO
 *
 * `AppContainer.connectWith` construia un `KeyMessageCoreImpl` NUEVO por
 * conexion y `parar()` hacia `core?.stop()` sin `retryManager.shutdown()`: el
 * scheduler del nucleo viejo seguia vivo. Ademas `MainActivity` hacia
 * `remember(nucleo) { ConversationRepository(core = nucleo, ...) }`, de modo que
 * el repositorio y su `ViewModel` (recordado por `identityRoot`) seguian
 * enviando por un nucleo CERRADO. Una rafaga podia partirse en dos colas.
 *
 * ## QUE AFIRMA
 *
 * Que el reemplazo tiene UN punto de asignacion bajo `lifecycleLock`, que el
 * nucleo anterior se cierra ANTES, que `parar()` cierra (no solo desconecta), y
 * que la UI construye el repositorio con un PROVIDER, no con el nucleo.
 *
 * ## CONTROL NEGATIVO
 *
 * [asignacionesDeCore] y [usosDeRememberNucleo] son funciones PURAS: se les da
 * una muestra con el patron viejo y tienen que VERLO. Un guard que no muerde no
 * guarda nada.
 */
class CoreOwnershipGuardTest {

    companion object {

        const val ASIGNACION_CANONICA: String = "core = nucleo.core"
        const val CIERRE_ANTERIOR: String = "anterior.close()"
        const val CIERRE_AL_PARAR: String = "nucleo?.close()"
        const val PROVIDER_UI: String = "coreProvider = {"

        fun appRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                if (File(dir, "src/main").isDirectory && File(dir, "build.gradle.kts").isFile) return dir
                dir = dir.parentFile
            }
            error("No se encontro el modulo app desde user.dir=${System.getProperty("user.dir")}")
        }

        fun fuente(relativa: String): String {
            val fichero = File(appRoot(), relativa)
            assertTrue(fichero.isFile, "no se encontro $relativa")
            return NoFrameworkReimplementationTest.sinComentarios(fichero.readText())
        }

        /** Cuenta las asignaciones del campo `core` a un `.core` (nucleo nuevo). */
        fun asignacionesDeCore(fuente: String): Int =
            Regex("core\\s*=\\s*\\w+\\.core").findAll(fuente).count()

        /** Cuenta `remember(nucleo)`, el patron que captura un nucleo muerto. */
        fun usosDeRememberNucleo(fuente: String): Int =
            Regex("remember\\s*\\(\\s*nucleo\\s*\\)").findAll(fuente).count()
    }

    @Test
    fun `el reemplazo tiene UN solo punto de asignacion y cierra antes de publicar`() {
        val contenedor = fuente("src/main/java/com/example/keymessage/AppContainer.kt")

        assertEquals(
            1,
            asignacionesDeCore(contenedor),
            "debe haber UNA sola asignacion de `$ASIGNACION_CANONICA`: dos puntos = dos duenos",
        )
        assertTrue(
            contenedor.contains("synchronized(lifecycleLock)"),
            "el swap y el cierre tienen que serializarse bajo `lifecycleLock`",
        )
        assertTrue(contenedor.contains(CIERRE_ANTERIOR), "el nucleo anterior se cierra antes de construir el sucesor")
        assertTrue(
            contenedor.indexOf(CIERRE_ANTERIOR) < contenedor.indexOf(ASIGNACION_CANONICA),
            "orden obligatorio: close(viejo) -> build(nuevo) -> swap",
        )
    }

    @Test
    fun `parar cierra el nucleo y no se limita a desconectar`() {
        val contenedor = fuente("src/main/java/com/example/keymessage/AppContainer.kt")

        assertTrue(contenedor.contains(CIERRE_AL_PARAR), "`parar()` tiene que cerrar: apaga el scheduler del nucleo")
        assertFalse(
            contenedor.contains("core?.stop()"),
            "`stop()` solo desconecta: dejaba el `retryManager` vivo sobre un nucleo muerto",
        )
    }

    @Test
    fun `la UI recuerda el contenedor y resuelve el nucleo por provider`() {
        val actividad = fuente("src/main/java/com/example/keymessage/ui/MainActivity.kt")

        assertEquals(0, usosDeRememberNucleo(actividad), "`remember(nucleo)` captura un nucleo que puede morir")
        assertTrue(
            actividad.contains("remember(container)"),
            "el repositorio se recuerda con el contenedor, que sobrevive al reemplazo",
        )
        assertTrue(actividad.contains(PROVIDER_UI), "y se construye con un provider del nucleo VIGENTE")
    }

    @Test
    fun `el repositorio declara un provider y no un nucleo capturado`() {
        val repositorio = fuente("src/main/java/com/example/keymessage/data/ConversationRepository.kt")
        assertTrue(
            repositorio.contains("coreProvider: () -> KeyMessageCore?"),
            "sin provider, el repositorio muere con el nucleo de la conexion",
        )
        assertFalse(
            repositorio.contains("private val core: KeyMessageCore,"),
            "un nucleo capturado en el constructor es exactamente el bug de R14",
        )
    }

    // ===================================================================
    // Control negativo: los detectores muerden el patron VIEJO
    // ===================================================================

    @Test
    fun `control negativo el detector ve DOS asignaciones y el remember de nucleo`() {
        val viejo = """
            fun connectWith() {
                core = nucleo.core
                core = otroNucleo.core
            }
            val repo = remember(nucleo) { ConversationRepository(core = nucleo, messageDao = dao) }
        """.trimIndent()

        assertEquals(2, asignacionesDeCore(viejo), "el detector VE las dos asignaciones")
        assertEquals(1, usosDeRememberNucleo(viejo), "y VE el `remember(nucleo)`")
    }

    @Test
    fun `control negativo el patron nuevo no dispara los detectores`() {
        val nuevo = """
            fun connectWith() {
                synchronized(lifecycleLock) {
                    core?.let { anterior -> anterior.close() }
                    core = nucleo.core
                }
            }
            val repo = remember(container) {
                ConversationRepository(coreProvider = { runCatching { container.requireCore() }.getOrNull() }, messageDao = dao)
            }
        """.trimIndent()

        assertEquals(1, asignacionesDeCore(nuevo), "una sola asignacion, la canonica")
        assertEquals(0, usosDeRememberNucleo(nuevo), "y ningun `remember(nucleo)`")
    }
}
