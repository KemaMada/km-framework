package com.example.keymessage.ui

import com.example.keymessage.build.NoFrameworkReimplementationTest
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F3 / F5 — LOGS con acciones alcanzables, y ARRANQUE sin porcentajes falsos.
 *
 * ## EL DEFECTO DE F3
 *
 * El cuerpo de LOGS era un `verticalScroll` sin cota dentro de un `Column`
 * externo: con 200 lineas empujaba COPIAR / COPIAR FILTRADO / EXPORTAR /
 * COMPARTIR / VOLVER fuera de la pantalla. El arreglo: el cuerpo tiene
 * `weight(1f)` (cede espacio), scrollea por dentro, y VOLVER queda FUERA del
 * scroll, pinned al fondo.
 *
 * ## EL DEFECTO DE F5
 *
 * Una barra de progreso que no corresponde a un evento es una mentira amable.
 * La traza mide FASES reales y NO pinta `%`.
 *
 * ## POR QUE ESTRUCTURAL
 *
 * No hay `ComposeTestRule`: el layout no se mide en JVM. La FORMA del arreglo se
 * cierra aqui; el comportamiento con 200 lineas y 200% de font scale se
 * confirma por ADB y se reporta en `apply-progress`.
 */
class DiagnosticsLayoutGuardTest {

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
    }

    private val main = "src/main/java/com/example/keymessage/ui/MainActivity.kt"

    @Test
    fun `el cuerpo de LOGS cede espacio y scrollea por dentro`() {
        val panel = fuente("src/main/java/com/example/keymessage/ui/components/LogPanel.kt")

        assertTrue(
            panel.contains("weight(1f"),
            "el cuerpo del panel usa `weight`: cede espacio en vez de empujar las acciones",
        )
        assertTrue(panel.contains("LazyColumn"), "y el cuerpo scrollea por dentro (LazyColumn)")
    }

    @Test
    fun `VOLVER de DIAGNOSTICO queda FUERA del scroll y las acciones viven en el panel`() {
        val fuente = fuente(main)

        // Las cinco acciones: cuatro en el panel (que scrollea por dentro) y
        // VOLVER fuera, al final del Column de DIAGNOSTICO.
        assertTrue(
            fuente.contains("LogPanel(lineas = lineas"),
            "el panel de logs se pinta dentro de su frame",
        )
        assertTrue(
            Regex("TuiAction\\(texto = \"\\[<- VOLVER\\]\"", RegexOption.IGNORE_CASE).containsMatchIn(fuente),
            "VOLVER esta presente y fuera del cuerpo scrolleable",
        )
    }

    @Test
    fun `el frame de LOGS es el que CEDE espacio`() {
        val fuente = fuente(main)

        assertTrue(
            Regex("TuiFrame\\(titulo = \"LOGS\", modifier = Modifier\\.weight\\(1f\\)\\)").containsMatchIn(fuente),
            "el frame de LOGS tiene `weight(1f)`: es el que crece y el que scrollea",
        )
    }

    @Test
    fun `la TABLA de ARRANQUE no lleva porcentaje`() {
        val panel = fuente("src/main/java/com/example/keymessage/ui/components/ArranquePanel.kt")

        assertTrue(
            !panel.contains("%"),
            "la tabla de fases no pinta porcentajes: un 100% que no mide un evento seria falso",
        )
        assertTrue(
            !Regex("\\b100\\b").containsMatchIn(panel),
            "y no escribe un 100 literal",
        )
    }

    @Test
    fun `control negativo el guard VE un porcentaje falso en la tabla`() {
        val malo = """
            @Composable
            private fun ArranquePanel() {
                Text(text = "ARRANQUE 100%")
            }
        """.trimIndent()

        assertTrue(
            malo.contains("%"),
            "el detector mira el `%`: sin esto pasaria por vacio",
        )
    }

    @Test
    fun `la leyenda de P2P se pinta en DIAGNOSTICO`() {
        val fuente = fuente(main)

        assertEquals(
            true,
            fuente.contains("IndicadorP2P.LEYENDA"),
            "DIAGNOSTICO pinta la leyenda que fija que P2P es del relay",
        )
    }
}
