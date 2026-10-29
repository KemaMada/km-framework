package com.example.keymessage.ui

import com.example.keymessage.build.NoFrameworkReimplementationTest
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * La lista de contactos de la UI esta VIVA, y no congelada por la pantalla.
 *
 * ## EL DEFECTO
 *
 * `MainActivity` tenia `remember(pantalla) { container.contactBook.all()... }`:
 * una clave que solo cambia al cambiar de pestana. El resultado era que el
 * contacto auto-registrado por el `contact_exchange` NO aparecia en la lista
 * hasta que el usuario navegaba a CONFIG y volvia (obs #326, D1).
 *
 * ## QUE AFIRMA
 *
 * Que la UI de la app:
 *
 *  - COLECTA el flujo del libro (`contactBook.contacts`), y no lo fotografia;
 *  - MAPEA con [mapearContactos], que es una funcion pura de nivel superior;
 *  - NO vuelve a congelar la lista con la clave de la pantalla.
 *
 * ## POR QUE ES ESTRUCTURAL Y NO CONDUCTUAL
 *
 * Porque el modulo no tiene `ComposeTestRule` y no se va a meter uno por una
 * lista. Lo que se puede EJERCITAR en JVM es el contrato del libro
 * (`InMemoryContactBookTest`: emite, no emite cuando se rechaza, es atomico) y
 * el mapeo (`MapearContactosTest`). Lo que falta —que la UI ESTE SUSCRITA al
 * flujo— no se puede ejecutar, asi que se mira CODIGO, y se declara el limite:
 * una colecta escondida detras de otro nombre no la veria. Por eso el
 * comportamiento se prueba ademas por su camino real (ADB).
 *
 * ## CONTROL NEGATIVO
 *
 * [congelaciones] es una funcion PURA: los tests `control*` la alimentan con
 * una muestra sintetica CON la clave congelada y afirman que la ve, y con una
 * muestra limpia y afirman que no inventa. Explicar el bug en un KDoc NO es
 * cometerlo: por eso los comentarios se quitan ANTES de buscar.
 */
class ContactListReactiveGuardTest {

    companion object {

        /** La clave que CONGELA la lista: solo cambia al cambiar de pestana. */
        const val CLAVE_CONGELADA: String = "remember(pantalla"

        /** La lectura reactiva del libro. */
        const val FLUJO_DEL_LIBRO: String = "contactBook.contacts"

        /** La funcion pura de mapeo. */
        const val MAPEO: String = "mapearContactos("

        /** La suscripcion con lifecycle. */
        const val COLECCION: String = "collectAsStateWithLifecycle()"

        /** El fichero de la UI que dibuja la lista. */
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
         * Lineas de [fuente] que congelan la lista con la clave de la pantalla.
         *
         * Los comentarios se quitan ANTES de buscar: un KDoc que explica el
         * bug no es el bug.
         */
        fun congelaciones(fuente: String): List<String> {
            val limpio = NoFrameworkReimplementationTest.sinComentarios(fuente).split("\n")
            return limpio.mapIndexedNotNull { idx, linea ->
                if (linea.contains(CLAVE_CONGELADA)) "${idx + 1}: ${linea.trim()}" else null
            }
        }
    }

    @Test
    fun `la UI COLECTA el flujo del libro`() {
        val fuente = fuenteUi()

        assertTrue(
            fuente.contains(FLUJO_DEL_LIBRO),
            "la UI tiene que leer el FLUJO del libro ($FLUJO_DEL_LIBRO), no una fotografia: " +
                "si lee `contactBook.all()` dentro de un `remember`, la lista se congela",
        )
        assertTrue(
            fuente.contains(COLECCION),
            "y la suscripcion tiene que ser con lifecycle ($COLECCION)",
        )
    }

    @Test
    fun `la UI MAPEA con la funcion pura`() {
        val fuente = fuenteUi()

        assertTrue(
            fuente.contains(MAPEO),
            "el mapeo a `Contact` sale de $MAPEO, que es una funcion pura de nivel superior",
        )
        assertTrue(
            fuente.contains("fun mapearContactos("),
            "y la funcion tiene que existir en el codigo, no solo ser llamada",
        )
    }

    @Test
    fun `la UI NO congela la lista con la clave de la pantalla`() {
        val culpables = congelaciones(fuenteUi())

        assertEquals(
            emptyList<String>(),
            culpables,
            "LISTA CONGELADA: `remember(pantalla)` solo recalcula al cambiar de pestana, y el " +
                "contacto auto-registrado no aparece hasta que el usuario navega: " +
                culpables.joinToString(" | "),
        )
    }

    @Test
    fun `control negativo el detector ve la clave congelada`() {
        val conClave = """
            @Composable
            private fun RootUi(container: AppContainer) {
                var pantalla by remember { mutableStateOf(Pantalla.INICIO) }
                val contactos = remember(pantalla) {
                    container.contactBook.all().map { it }
                }
            }
        """.trimIndent()

        assertEquals(1, congelaciones(conClave).size, "el detector VE la lista congelada")
    }

    @Test
    fun `control negativo una mencion en un KDoc NO es la lista congelada`() {
        val limpio = """
            @Composable
            private fun RootUi(container: AppContainer) {
                /**
                 * Antes: remember(pantalla) { container.contactBook.all()... }, que era el bug.
                 */
                val contactos by container.contactBook.contacts.collectAsStateWithLifecycle()
            }
        """.trimIndent()

        assertEquals(
            emptyList<String>(),
            congelaciones(limpio),
            "una mencion en un KDoc no es una llamada, y el codigo de ahora no tiene la clave",
        )
    }

    @Test
    fun `control positivo el detector no inventa congelaciones`() {
        val limpio = """
            @Composable
            private fun RootUi(container: AppContainer) {
                val contactos = remember { mapearContactos(container.contactBook.contacts.value) }
            }
        """.trimIndent()

        assertFalse(congelaciones(limpio).isNotEmpty(), "una lista sin la clave no esta congelada")
    }
}
