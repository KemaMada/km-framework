package com.example.keymessage.ui

import com.example.keymessage.build.NoFrameworkReimplementationTest
import com.example.keymessage.ui.boot.OVERLAY_DURACION_MAXIMA_MS
import com.example.keymessage.ui.boot.OVERLAY_REVELADO_LINEA_MS
import com.example.keymessage.ui.boot.debeMostrarOverlay
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R5 / R6 — El overlay de arranque: acotado, omitible, una vez por proceso.
 *
 * ## QUE CIERRA EN JVM
 *
 *  - el GATE es una funcion pura ([debeMostrarOverlay]): arranque en frio, no
 *    reclamado antes, y fuera del onboarding de FRASE;
 *  - la cota es 2000 ms y el revelado 60 ms por linea (constantes, no magia);
 *  - hay UN solo host del overlay y es el arranque de la Activity;
 *  - no hay `%` ni secretos en el texto del overlay;
 *  - CONTACTOS se completa con la carga LOCAL (sin esperar al relay).
 *
 * El comportamiento real (2 s, tap, OMITIR, rotacion) se comprueba por ADB.
 *
 * ## CONTROL NEGATIVO
 *
 * [hostsDeOverlay] se alimenta con una muestra de DOS hosts y una de cero: las
 * dos salen del invariante "exactamente uno".
 */
class BootOverlayGuardTest {

    companion object {

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

        /** Los ficheros de `src/main` que COMPONEN el overlay (no lo declaran). */
        fun hostsDeOverlay(fuentes: Map<String, String>): List<String> =
            fuentes.filterValues { texto ->
                // `\b` delante: `reclamarOverlayArranque(` NO es un host. El
                // camelCase no tiene frontera de palabra dentro del nombre, y el
                // nombre del metodo del contenedor termina en "Arranque", no en
                // una llamada al composable.
                Regex("\\bOverlayArranque\\(").containsMatchIn(texto)
            }.keys.toList()

        fun porcentajes(texto: String): Int = Regex("%").findAll(texto).count()
    }

    private val overlay = "src/main/java/com/example/keymessage/ui/boot/OverlayArranque.kt"
    private val main = "src/main/java/com/example/keymessage/ui/MainActivity.kt"
    private val container = "src/main/java/com/example/keymessage/AppContainer.kt"

    // ===================================================================
    // Gate puro
    // ===================================================================

    @Test
    fun `el overlay solo se muestra en frio, sin reclamar y con FRASE avisada`() {
        assertTrue(
            debeMostrarOverlay(inicioFrio = true, yaReclamado = false, fraseAvisada = true),
            "el caso normal del arranque",
        )
        assertFalse(
            debeMostrarOverlay(inicioFrio = false, yaReclamado = false, fraseAvisada = true),
            "una rotacion NO vuelve a mostrar el overlay",
        )
        assertFalse(
            debeMostrarOverlay(inicioFrio = true, yaReclamado = true, fraseAvisada = true),
            "una sola vez por proceso",
        )
        assertFalse(
            debeMostrarOverlay(inicioFrio = true, yaReclamado = false, fraseAvisada = false),
            "el onboarding de FRASE manda: sin aviso no hay overlay",
        )
    }

    @Test
    fun `la cota es 2000 ms y el revelado 60 ms por linea`() {
        assertEquals(2_000L, OVERLAY_DURACION_MAXIMA_MS, "R5: 2 s como maximo")
        assertEquals(60L, OVERLAY_REVELADO_LINEA_MS, "revelado presentacional")
        assertTrue(
            OVERLAY_REVELADO_LINEA_MS * 20 < OVERLAY_DURACION_MAXIMA_MS,
            "el revelado no puede comerse la cota",
        )
    }

    // ===================================================================
    // Forma del overlay
    // ===================================================================

    @Test
    fun `el overlay se cierra con tap y con OMITIR`() {
        val fuente = fuente(overlay)

        assertTrue(fuente.contains("clickable(onClick = onCerrar)"), "tap en cualquier punto cierra")
        assertTrue(fuente.contains("\"[ OMITIR ]\""), "y hay un OMITIR explicito")
        assertTrue(fuente.contains("OVERLAY_DURACION_MAXIMA_MS"), "la cota se aplica de verdad")
        assertTrue(fuente.contains("delay(OVERLAY_DURACION_MAXIMA_MS)"), "con un `delay`, no un cronometro paralelo")
    }

    @Test
    fun `el overlay es legible y sin porcentajes ni secretos`() {
        val fuente = fuente(overlay)

        assertTrue(fuente.contains("contentDescription"), "R6: el bloque se anuncia")
        assertTrue(fuente.contains("verticalScroll"), "con font scale alta el texto scrollea, no se solapa")
        assertEquals(0, porcentajes(fuente), "ni un `%` en el overlay")
        listOf("fraseDeRecuperacion", "mnemonic", "privateKey", "apiKey", "api_key").forEach { token ->
            assertFalse(fuente.contains(token), "sin secretos: '$token' no puede aparecer")
        }
    }

    @Test
    fun `el overlay reserva la barra de navegacion para que OMITIR sea tocable`() {
        val fuente = fuente(overlay)

        assertEquals(
            1,
            InsetInferiorGuardTest.consumidores(fuente),
            "el overlay es su propio dueno del inset inferior: la raiz no lo reserva",
        )
        assertTrue(
            Regex("navigationBarsPadding\\(\\)\\s*\\n\\s*\\.padding\\(16\\.dp\\)").containsMatchIn(fuente),
            "y lo reserva ANTES del padding del contenido",
        )
    }

    @Test
    fun `el overlay no toca la conexion`() {
        val fuente = fuente(overlay)

        assertFalse(fuente.contains("arrancar("), "el overlay no arranca ni reintenta la conexion")
        assertFalse(fuente.contains("AppContainer"), "no depende del contenedor: recibe lineas ya formadas")
    }

    // ===================================================================
    // Un solo host y el gate en la Activity
    // ===================================================================

    @Test
    fun `el overlay se compone en UN solo host`() {
        val main = "src/main"

        val fuentes = File(appRoot(), main).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(appRoot()).path to NoFrameworkReimplementationTest.sinComentarios(it.readText()) }

        val hosts = hostsDeOverlay(fuentes)

        assertEquals(
            listOf(
                "src/main/java/com/example/keymessage/ui/MainActivity.kt",
                "src/main/java/com/example/keymessage/ui/boot/OverlayArranque.kt",
            ).sorted(),
            hosts.sorted(),
            "un host (MainActivity) y la declaracion (OverlayArranque.kt), nada mas: $hosts",
        )
    }

    @Test
    fun `el host reclama una vez por proceso y pasa el arranque en frio`() {
        val fuente = fuente(main)

        assertTrue(fuente.contains("inicioFrio = savedInstanceState == null"), "D5: solo un arranque del proceso")
        assertTrue(fuente.contains("debeMostrarOverlay("), "el gate es la funcion pura, no un `if` suelto")
        assertTrue(fuente.contains("reclamarOverlayArranque()"), "y el reclamo es atomico en el contenedor")
        assertTrue(
            fuente.contains("lineasBoot("),
            "las lineas salen de la funcion pura (entorno + fases + enlace reales)",
        )
    }

    @Test
    fun `el reclamo es atomico`() {
        val fuente = fuente(container)

        assertTrue(
            fuente.contains("compareAndSet(false, true)"),
            "un `var` con `if` permitiria dos overlays en una recreacion rapida",
        )
    }

    // ===================================================================
    // CONTACTOS: local, no relay
    // ===================================================================

    @Test
    fun `CONTACTOS se completa con la carga local y no en connectWith`() {
        val fuente = fuente(container)

        assertEquals(
            1,
            Regex(Regex.escape("FaseArranque.CONTACTOS")).findAll(fuente).count(),
            "un UNICO punto completa CONTACTOS: el `init` del contenedor",
        )
        assertTrue(
            Regex("init \\{[^}]*FaseArranque\\.CONTACTOS").containsMatchIn(fuente),
            "y ese punto es el `init`, cuando el libro local ya existe",
        )
        assertTrue(
            fuente.contains("contactBook.contacts.value.size"),
            "la nota lleva el conteo REAL del libro",
        )
        assertTrue(
            fuente.contains("en memoria (no persistido)"),
            "y el alcance real: no hay persistencia todavia",
        )
    }

    // ===================================================================
    // Control negativo
    // ===================================================================

    @Test
    fun `control negativo el detector VE cero y dos hosts`() {
        val cero = mapOf("Vacio.kt" to "fun nada() {}")
        val dos = mapOf(
            "Uno.kt" to "OverlayArranque(lineas = a, onCerrar = {})",
            "Dos.kt" to "OverlayArranque(lineas = b, onCerrar = {})",
        )

        assertEquals(emptyList<String>(), hostsDeOverlay(cero))
        assertEquals(2, hostsDeOverlay(dos).size, "con dos hosts el guard tiene que verlos: $dos")
        assertEquals(1, porcentajes("ARRANQUE 100%"), "y el detector de `%` no es ciego")
    }
}
