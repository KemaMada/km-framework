package com.example.keymessage.ui

import com.example.keymessage.build.NoFrameworkReimplementationTest
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El dialogo de importar tiene UN dueno del evento, y su estado es POR APERTURA.
 *
 * ## LOS DOS DEFECTOS
 *
 * - **D2**: `viewModel()` resolvia `LocalViewModelStoreOwner.current` = la
 *   Activity, asi que el `ImportContactViewModel` vivia en el store de la
 *   Activity y SOBREVIVIA a la navegacion. Al reabrir el dialogo, su
 *   `state.contact` seguia NO nulo y el `LaunchedEffect(state.contact)`
 *   re-disparaba `onImported` con el contacto ANTERIOR: el intercambio entero
 *   se relanzaba (obs #326).
 *
 * - **El segundo dueno**: el evento "importado" lo disparaban DOS —el
 *   `ImportContactViewModel` al importar y el `LaunchedEffect(state.contact)`
 *   del composable—, y por eso una sola importacion podia entregar el
 *   `[SIGNAL]` de contacto mas de una vez.
 *
 * ## QUE AFIRMA
 *
 * Que la UI de la app:
 *
 *  - NO tiene el `LaunchedEffect(state.contact)`: el dueno es el `ViewModel`;
 *  - PROVEE un `ViewModelStoreOwner` ANIDADO, propio de la apertura;
 *  - LIBERA ese store al salir (`clear()`), para que el estado Muera con el
 *    dialogo.
 *
 * ## POR QUE ES ESTRUCTURAL Y NO CONDUCTUAL
 *
 * Porque el modulo no tiene `ComposeTestRule`. Lo que se EJERCITA en JVM es
 * que el `ViewModel` dispara el evento una vez (`ImportContactViewModelTest`);
 * lo que no se puede ejecutar es el ciclo de vida de la composicion, asi que
 * se mira CODIGO, y se declara el limite. El comportamiento completo se
 * comprueba por ADB.
 *
 * ## CONTROL NEGATIVO
 *
 * [duenosDelEvento] es una funcion PURA: los tests `control*` la alimentan con
 * una muestra sintetica CON el segundo dueno y afirman que la ve, y con una
 * muestra limpia y afirman que no inventa. Los comentarios se quitan ANTES de
 * buscar: un KDoc que explica el bug no es el bug.
 */
class ImportOwnerGuardTest {

    companion object {

        /** El segundo dueno del evento "importado". */
        const val SEGUNDO_DUENO: String = "LaunchedEffect(state.contact)"

        /** El owner anidado, propio de la apertura. */
        const val OWNER_ANIDADO: String = "LocalViewModelStoreOwner provides"

        /** La liberacion del store al salir. */
        const val LIBERACION: String = "viewModelStore.clear()"

        /** El fichero de la UI que pinta el dialogo. */
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

        /** Lineas de [fuente] que tienen un SEGUNDO dueno del evento. */
        fun duenosDelEvento(fuente: String): List<String> {
            val limpio = NoFrameworkReimplementationTest.sinComentarios(fuente).split("\n")
            return limpio.mapIndexedNotNull { idx, linea ->
                if (linea.contains(SEGUNDO_DUENO)) "${idx + 1}: ${linea.trim()}" else null
            }
        }
    }

    @Test
    fun `la UI NO tiene el segundo dueno del evento`() {
        val culpables = duenosDelEvento(fuenteUi())

        assertEquals(
            emptyList<String>(),
            culpables,
            "DOS DUENOS DEL EVENTO: el `ImportContactViewModel` ya dispara `onImported` al " +
                "importar; un `LaunchedEffect(state.contact)` lo repite y relanza el intercambio " +
                "con el contacto anterior: ${culpables.joinToString(" | ")}",
        )
    }

    @Test
    fun `la UI PROVEE un owner anidado y LO LIBERA al salir`() {
        val fuente = fuenteUi()

        assertTrue(
            fuente.contains(OWNER_ANIDADO),
            "el `ViewModel` de import tiene que vivir en un owner PROPIO de la apertura " +
                "($OWNER_ANIDADO): con el de la Activity sobrevive a la navegacion",
        )
        assertTrue(
            fuente.contains(LIBERACION),
            "y ese store se tiene que LIBERAR al salir ($LIBERACION): si no, el estado sobrevive",
        )
    }

    @Test
    fun `control negativo el detector ve el segundo dueno`() {
        val conDueno = """
            @Composable
            private fun DialogoDeImportacion() {
                val state by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.contact) {
                    if (state.contact != null) onImported(requireNotNull(state.contact))
                }
            }
        """.trimIndent()

        assertEquals(1, duenosDelEvento(conDueno).size, "el detector VE el segundo dueno")
    }

    @Test
    fun `control negativo una mencion en un KDoc NO es un dueno`() {
        val limpio = """
            @Composable
            private fun DialogoDeImportacion() {
                /**
                 * Antes: LaunchedEffect(state.contact), que era el segundo dueno.
                 */
                val state by vm.state.collectAsStateWithLifecycle()
            }
        """.trimIndent()

        assertEquals(
            emptyList<String>(),
            duenosDelEvento(limpio),
            "una mencion en un KDoc no es un dueno, y el codigo de ahora no lo tiene",
        )
    }
}
