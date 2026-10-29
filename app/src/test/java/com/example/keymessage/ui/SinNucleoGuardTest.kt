package com.example.keymessage.ui

import com.example.keymessage.build.NoFrameworkReimplementationTest
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Entrar en la conversacion SIN conexion no revienta la app.
 *
 * ## EL DEFECTO
 *
 * `MainActivity` hacia `ConversationRepository(core = container.requireCore(), ...)`
 * dentro de un `remember {}`. `requireCore()` LANZA si el nucleo todavia no
 * existe, y la excepcion salia de un `remember`: la app se caia al abrir la
 * conversacion sin rele. Con D2 arreglado, ese camino es alcanzable de verdad
 * —el segundo import sin conexion llega hasta aqui—, asi que la conversion
 * tiene que DEGRADAR.
 *
 * ## QUE AFIRMA
 *
 * Que la UI:
 *
 *  - consulta el nucleo con `runCatching` y `getOrNull()`, no a pelo;
 *  - NO vuelve a pasar `container.requireCore()` sin envolver;
 *  - tiene una rama de degradacion que MUESTRA el estado y el codigo.
 *
 * ## POR QUE ES ESTRUCTURAL
 *
 * Porque el camino que revienta necesita un `AppContainer` con una `Activity`:
 * no hay test de JVM que lo ejecute. El comportamiento se comprueba por ADB
 * (abrir el dialogo sin rele no revienta), y aqui se cierra la forma.
 *
 * ## CONTROL NEGATIVO
 *
 * [llamadasSinGuardar] es una funcion PURA, alimentada con una muestra
 * sintetica: el detector tiene que ver la llamada a pelo y NO ver la que va
 * dentro de un `runCatching`.
 */
class SinNucleoGuardTest {

    companion object {

        /** La llamada a pelo que revienta. */
        const val LLAMADA_A_PELO: String = "core = container.requireCore(),"

        /** La llamada GUARDADA. */
        const val LLAMADA_GUARDADA: String = "runCatching { container.requireCore() }"

        /** La rama que degrada. */
        const val DEGRADACION: String = "SinNucleoScreen("

        /** El fichero de la UI. */
        const val FICHERO_UI: String = "src/main/java/com/example/keymessage/ui/MainActivity.kt"

        /** Raiz del modulo `app/`. */
        fun appRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                if (File(dir, "src/main").isDirectory && File(dir, "build.gradle.kts").isFile) return dir
                dir = dir.parentFile
            }
            error("No se encontro el modulo app desde user.dir=${System.getProperty("user.dir")}")
        }

        /** La fuente de la UI, sin comentarios. */
        fun fuenteUi(): String {
            val fichero = File(appRoot(), FICHERO_UI)
            assertTrue(fichero.isFile, "no se encontro $FICHERO_UI")
            return NoFrameworkReimplementationTest.sinComentarios(fichero.readText())
        }

        /**
         * Lineas de [fuente] que piden el nucleo SIN envolver.
         *
         * La que va dentro de un `runCatching` no cuenta: en esa, el nucleo se
         * pide a proposito y el `null` se maneja.
         */
        fun llamadasSinGuardar(fuente: String): List<String> {
            val limpio = NoFrameworkReimplementationTest.sinComentarios(fuente).split("\n")
            return limpio.mapIndexedNotNull { idx, linea ->
                val tiene = linea.contains(LLAMADA_A_PELO)
                val guardada = linea.contains(LLAMADA_GUARDADA)
                if (tiene && !guardada) "${idx + 1}: ${linea.trim()}" else null
            }
        }
    }

    @Test
    fun `la UI consulta el nucleo con runCatching`() {
        val fuente = fuenteUi()

        assertTrue(
            fuente.contains(LLAMADA_GUARDADA),
            "el nucleo se pide con `$LLAMADA_GUARDADA`: a pelo, `requireCore()` lanza y la app se cae",
        )
        assertTrue(
            fuente.contains(DEGRADACION),
            "y hay una rama que degrada sin crash ($DEGRADACION)",
        )
    }

    @Test
    fun `la UI NO pide el nucleo a pelo`() {
        val culpables = llamadasSinGuardar(fuenteUi())

        assertEquals(
            emptyList<String>(),
            culpables,
            "NUCLEO SIN GUARDAR: `requireCore()` LANZA sin conexion, y dentro de un `remember` " +
                "eso tumba la app: ${culpables.joinToString(" | ")}",
        )
    }

    @Test
    fun `control negativo el detector ve la llamada a pelo`() {
        val conLlamada = """
            @Composable
            private fun Conversacion(container: AppContainer) {
                val repo = remember {
                    ConversationRepository(
                        core = container.requireCore(),
                        messageDao = container.messageDao,
                    )
                }
            }
        """.trimIndent()

        assertEquals(1, llamadasSinGuardar(conLlamada).size, "el detector VE la llamada a pelo")
    }

    @Test
    fun `control negativo la llamada GUARDADA no es una llamada a pelo`() {
        val guardada = """
            @Composable
            private fun Conversacion(container: AppContainer) {
                val nucleo = runCatching { container.requireCore() }.getOrNull()
            }
        """.trimIndent()

        assertEquals(
            emptyList<String>(),
            llamadasSinGuardar(guardada),
            "la llamada dentro de `runCatching` es la que hay que hacer",
        )
    }
}
