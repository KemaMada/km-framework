package com.example.keymessage.ui

import com.example.keymessage.build.NoFrameworkReimplementationTest
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * El look final es TUI: no queda ningun control Material redondeado.
 *
 * ## EL DEFECTO QUE VIGILA
 *
 * La app YA era terminal-ish (paleta `Terminal*`, monoespaciada, `[X]/[ ]`) pero
 * convivian con ella `Button` (pildora por defecto), `OutlinedTextField` (borde
 * redondeado), `TextButton` y `HorizontalDivider`. El rediseno sustituye esos
 * controles por el set propio `Tui*` (esquinas 0dp, borde 1dp). Sin un guard, el
 * siguiente que toque una pantalla puede reintroducir un `Button` y el look se
 * deshace sin que ningun test lo note.
 *
 * ## EL AGUJERO QUE ESTE GUARD TENIA (verify #381 W1)
 *
 * La lista `MIGRADOS` era EXPLICITA y no incluia `ui/MainActivity.kt`, donde
 * viven IMPORTAR, DIAGNOSTICO, ROOT e INICIO en sus ramas migradas. La mutacion
 * M5 (`RoundedCornerShape(8.dp)` a nivel de fichero en `MainActivity.kt`) seguia
 * VERDE: un control redondeado ahi pasaba inadvertido.
 *
 * ## COMO SE CIERRA
 *
 * El scope ya no es una lista que se pueda olvidar: se DESCUBRE el arbol
 * `app/src/main/java/.../ui` completo (incluye `MainActivity.kt`, `screens/`,
 * `components/`, `theme/`, `nav/`, `permisos/`). Un fichero nuevo entra solo.
 * El test [el scope cubre MainActivity y las pantallas migradas] afirma que el
 * descubrimiento VE esos ficheros: si un refactor los mueve y el walk deja de
 * encontrarlos, el guard falla en vez de pasar por vacio.
 *
 * ## POR QUE SIGUE SIENDO ESTRUCTURAL Y NO CONDUCTUAL
 *
 * Porque en este modulo no hay `ComposeTestRule` (ni Robolectric) y el look no
 * se puede EJECUTAR en JVM: no hay forma de renderizar un `@Composable` en
 * `testDebugUnitTest`. Lo que si se puede afirmar es que la fuente no contiene
 * el constructor prohibido, y eso se prueba con [hasRounded] —una funcion PURA—
 * alimentada con muestras CON y SIN material. Los controles negativos incluyen
 * la FORMA EXACTA de la mutacion M5 (un `RoundedCornerShape` de nivel de
 * fichero), no solo un `Button(` dentro de una funcion.
 *
 * Cuando exista instrumentacion (Compose UI Test), el reemplazo natural es un
 * screenshot/árbol de semantica; hasta entonces este es el invariante
 * disponible y esta declarado como tal.
 */
class NoRoundedControlsGuardTest {

    companion object {

        /** Los controles Material redondeados que el look final no admite. */
        val PROHIBIDOS: List<String> = listOf(
            // Familias de boton Material (todas con forma de pildora por defecto).
            "Button(",
            "OutlinedButton(",
            "FilledTonalButton(",
            "ElevatedButton(",
            "TextButton(",
            "IconButton(",
            // Campos con forma de Material (borde/relleno redondeado).
            "OutlinedTextField(",
            "TextField(",
            // Contenedores y separadores con forma.
            "Card(",
            "HorizontalDivider(",
            // Formas explicitas: incluye la mutacion M5 de verify #381.
            "RoundedCornerShape(",
            "CircleShape(",
            "MaterialTheme.shapes",
        )

        /**
         * El patron de un control prohibido: el nombre PRECEDIDO por un limite
         * de palabra.
         *
         * `TuiButton(` NO es un `Button(` y `BasicTextField(` NO es un
         * `TextField(`: sin el limite, el guard marcaria el set propio y se
         * desactivaria solo. El `(?<![A-Za-z0-9_])` exige que delante no haya
         * letra ni digito.
         */
        private fun patronDe(prohibido: String): Regex =
            Regex("(?<![A-Za-z0-9_])" + Regex.escape(prohibido))

        /** Raiz del modulo `app/`. */
        fun appRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                if (File(dir, "src/main").isDirectory && File(dir, "build.gradle.kts").isFile) return dir
                dir = dir.parentFile
            }
            error("No se encontro el modulo app desde user.dir=${System.getProperty("user.dir")}")
        }

        /**
         * TODOS los ficheros Kotlin del paquete `ui`, relativos a `app/`.
         *
         * Se descubre el arbol; no hay lista que mantener a mano. Esto es lo que
         * cierra el agujero de W1.
         */
        fun ficherosUi(): List<String> {
            val raiz = File(appRoot(), "src/main/java/com/example/keymessage/ui")
            assertTrue(raiz.isDirectory, "no existe el paquete ui: ${raiz.absolutePath}")
            return raiz.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { it.relativeTo(appRoot()).invariantSeparatorsPath }
                .sorted()
                .toList()
        }

        /**
         * Los controles prohibidos que aparecen en [fuente], sin comentarios.
         *
         * Los comentarios se quitan ANTES de buscar: este mismo KDoc menciona
         * `Button(` y `OutlinedTextField(` para explicar el defecto, y un KDoc
         * que explica el bug no es el bug.
         */
        fun hasRounded(fuente: String): List<String> {
            val limpio = NoFrameworkReimplementationTest.sinComentarios(fuente)
            return PROHIBIDOS.filter { patronDe(it).containsMatchIn(limpio) }
        }
    }

    @Test
    fun `todo el paquete ui no tiene controles Material redondeados`() {
        val ficheros = ficherosUi()
        assertTrue(ficheros.size >= 15, "el descubrimiento encontro muy pocos ficheros: $ficheros")

        val culpables = ficheros.flatMap { rel ->
            val fichero = File(appRoot(), rel)
            assertTrue(fichero.isFile, "no se encontro el fichero del scope $rel")
            hasRounded(fichero.readText()).map { "$rel -> $it" }
        }

        assertEquals(
            emptyList<String>(),
            culpables,
            "CONTROL REDONDEADO EN EL LOOK FINAL: el paquete ui usa Material redondeado " +
                "(esquina != 0): ${culpables.joinToString(" | ")}",
        )
    }

    @Test
    fun `el scope cubre MainActivity y las pantallas migradas`() {
        val ficheros = ficherosUi()
        val obligatorios = listOf(
            "src/main/java/com/example/keymessage/ui/MainActivity.kt",
            "src/main/java/com/example/keymessage/ui/screens/MainScreen.kt",
            "src/main/java/com/example/keymessage/ui/screens/ChatScreen.kt",
            "src/main/java/com/example/keymessage/ui/screens/ShareQrScreen.kt",
            "src/main/java/com/example/keymessage/ui/screens/RecoveryPhraseScreen.kt",
            "src/main/java/com/example/keymessage/ui/components/TuiFrame.kt",
        )
        for (rel in obligatorios) {
            assertTrue(
                ficheros.contains(rel),
                "el scope del guard NO cubre $rel: un control redondeado ahi pasaria inadvertido " +
                    "(agujero W1 de verify #381). Descubiertos: $ficheros",
            )
        }
    }

    /**
     * La forma EXACTA de la mutacion M5: un `RoundedCornerShape` de nivel de
     * fichero (no dentro de un `@Composable`), como el que verify #381 inyecto
     * en `MainActivity.kt` y que el guard anterior dejaba pasar.
     */
    @Test
    fun `control negativo el detector ve la mutacion M5 en MainActivity`() {
        val mutado = """
            package com.example.keymessage.ui

            import androidx.compose.foundation.shape.RoundedCornerShape
            import androidx.compose.ui.unit.dp

            private val formaMala = RoundedCornerShape(8.dp)

            @Composable
            private fun Pantalla() {
                TuiButton(texto = "OK", onClick = {})
            }
        """.trimIndent()

        assertTrue(
            hasRounded(mutado).contains("RoundedCornerShape("),
            "el detector VE el RoundedCornerShape de nivel de fichero: sin esto M5 volveria a pasar",
        )
    }

    @Test
    fun `control negativo el detector ve un OutlinedButton`() {
        val conOutlined = """
            @Composable
            private fun Pantalla() {
                OutlinedButton(onClick = {}) { Text("OK") }
            }
        """.trimIndent()

        assertTrue(
            hasRounded(conOutlined).contains("OutlinedButton("),
            "el detector VE un OutlinedButton( (familia de Button que el guard anterior no miraba)",
        )
    }

    @Test
    fun `control negativo el detector NO inventa con el set Tui`() {
        val limpio = """
            @Composable
            private fun Pantalla() {
                TuiButton(texto = "OK", onClick = {})
                TuiField(value = "", onValueChange = {})
                TuiAction(texto = "Volver", onClick = {})
                TuiRule()
                BasicTextField(value = "", onValueChange = {})
            }
        """.trimIndent()

        assertEquals(
            emptyList<String>(),
            hasRounded(limpio),
            "una pantalla ya TUI no tiene controles redondeados (y BasicTextField no es TextField)",
        )
    }

    @Test
    fun `control negativo una mencion en un KDoc NO es un control redondeado`() {
        val conKdoc = """
            /**
             * Antes esto era un Button( y un OutlinedTextField( redondeados.
             */
            @Composable
            private fun Pantalla() {
                TuiButton(texto = "OK", onClick = {})
            }
        """.trimIndent()

        assertEquals(
            emptyList<String>(),
            hasRounded(conKdoc),
            "explicar el defecto en un KDoc no es cometerlo",
        )
    }

    @Test
    fun `un fichero del scope que desaparece rompe el test`() {
        // Afirma el contrato del descubrimiento: si el walk devuelve vacio, el
        // guard no esta vigilando nada y tiene que fallar.
        val ficheros = ficherosUi()
        assertFalse(
            ficheros.isEmpty(),
            "el walk del paquete ui no encontro ficheros: el guard estaria pasando por vacio",
        )
    }
}
