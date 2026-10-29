package com.example.keymessage.build

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File

/**
 * 0.2 — La app se compila por su cuenta, y el repositorio no la arrastra.
 *
 * ## QUE AFIRMA
 *
 * Tres cosas que tienen que ser verdad a la vez, porque cada una por separado
 * deja pasar el fallo que este test existe para impedir:
 *
 *  1. El `settings.gradle.kts` RAIZ del repositorio NO incluye `:app`. Si lo
 *     hiciera, `app/build.gradle.kts` volveria a ser un modulo de este build y
 *     `project(":km-core")` —o cualquier otra forma de atarlo al grafo— volveria
 *     a ser posible: dos copias de km-core con el mismo
 *     group/artifactId/version y `Duplicate class`.
 *  2. `app/settings.gradle.kts` EXISTE y es un build de verdad: fija raiz,
 *     repositorios (con `mavenLocal`, de donde salen los artefactos) y el
 *     catalogo de version compartido.
 *  3. `app/build.gradle.kts` consume el framework POR ARTEFACTO. Cero
 *     `project(":...")`, y los dos artefactos nombrados.
 *
 * ## POR QUE ES UNA GUARDA Y NO UNA LECTURA
 *
 * La condicion es de CONSTRUCCION, no de comportamiento: no hay ningun programa
 * que falle cuando se rompe, solo un `BUILD FAILED` con un mensaje de Gradle. Por
 * eso el fallo tiene que hacerse visible aqui, y no el dia que alguien pierde
 * media hora de compilacion.
 *
 * ## CONTROL NEGATIVO
 *
 * [detectorDeInclude] y [atadurasPorProyecto] son funciones PURAS y los tests
 * `control*` las alimentan con fuentes sinteticas que contienen la fuga y
 * afirman que la ven, y con fuentes limpias y afirman que no la inventan. Un
 * detector que nunca dispara da falsa confianza.
 */
class RootBuildIsolationTest {

    companion object {
        /** Modulo que la raiz tiene que seguir SIN incluir. */
        const val APP_MODULE: String = ":app"

        /** Prefijo de una dependencia por artefacto de km-core/km-transport. */
        const val ARTEFACTO_KM: String = "com.km:"

        /**
         * Sube desde el directorio de trabajo del test (`app/`) hasta la raiz del
         * repositorio, exigiendo la firma del repositorio en cada salto.
         */
        fun repoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                if (File(dir, "settings.gradle.kts").isFile && File(dir, "gradle/libs.versions.toml").isFile) {
                    return dir
                }
                dir = dir.parentFile
            }
            error("No se encontro la raiz del repositorio desde user.dir=${System.getProperty("user.dir")}")
        }

        /**
         * Lineas SIN COMENTARIO que mencionan `include(...)`, con su numero.
         *
         * Una mencion en un comentario del settings raiz —"la app no se
         * incluye"— no es una inclusion, y contarla haria que este test aceptara
         * justo la situacion que el comentario describe.
         */
        fun detectorDeInclude(settingsText: String): List<String> {
            val original = settingsText.split("\n")
            return original.mapIndexedNotNull { idx, raw ->
                val code = raw.substringBefore("//")
                if (code.contains("include(")) "settings:${idx + 1}: ${code.trim()}" else null
            }
        }

        /**
         * Lineas sin comentario de [buildText] que atan la app al grafo del
         * repositorio con `project(":...")`.
         */
        fun atadurasPorProyecto(buildText: String): List<String> =
            buildText.split("\n").mapIndexedNotNull { idx, raw ->
                val code = raw.substringBefore("//")
                if (code.contains("project(\"")) "build:${idx + 1}: ${code.trim()}" else null
            }

        /** Artefactos `com.km:*` declarados en [buildText], sin contar comentarios. */
        fun dependenciasPorArtefacto(buildText: String): List<String> =
            buildText.split("\n")
                .map { it.substringBefore("//") }
                .filter { it.contains(ARTEFACTO_KM) }
                .map { line ->
                    val m = Regex("com\\.km:([A-Za-z0-9._-]+):([A-Za-z0-9._-]+)").find(line)
                    if (m != null) "${m.groupValues[1]}:${m.groupValues[2]}" else "NO-EXTRACTABLE: ${line.trim()}"
                }
                .distinct()
    }

    // --- Real 1: la raiz no incluye la app ---

    @Test
    fun `el settings raiz NO incluye la app`() {
        val settings = File(repoRoot(), "settings.gradle.kts")
        assertTrue(settings.isFile, "no existe el settings raiz: $settings")

        val includes = detectorDeInclude(settings.readText())
        assertTrue(
            includes.none { it.contains(APP_MODULE) },
            "el repositorio vuelve a incluir la app: $includes",
        )
    }

    // --- Real 2: la app tiene build propio ---

    @Test
    fun `la app tiene un settings propio y autonomo`() {
        val appSettings = File(repoRoot(), "app/settings.gradle.kts")
        assertTrue(appSettings.isFile, "no existe app/settings.gradle.kts: el build NO es autonomo")

        val text = appSettings.readText()
        val code = text.split("\n").map { it.substringBefore("//") }.joinToString("\n")

        assertTrue(code.contains("rootProject.name"), "el settings de la app no fija rootProject")
        assertTrue(
            code.contains("mavenLocal()"),
            "el settings de la app no declara mavenLocal: los artefactos com.km no resolverian",
        )
        assertTrue(
            code.contains("libs.versions.toml"),
            "el settings de la app no importa el catalogo compartido",
        )
        assertFalse(
            detectorDeInclude(text).any { it.contains(APP_MODULE) || it.contains(":km-") },
            "un build autonomo no puede 'include' modulos: la raiz ES la app",
        )
    }

    // --- Real 3: las deps del framework son por artefacto ---

    @Test
    fun `el build de la app consume km-core y km-transport por artefacto`() {
        val build = File(repoRoot(), "app/build.gradle.kts")
        assertTrue(build.isFile, "no existe app/build.gradle.kts")

        val text = build.readText()
        val ataduras = atadurasPorProyecto(text)
        assertEquals(
            emptyList<String>(),
            ataduras,
            "FRONTERA VIOLADA: la app se ata al grafo del repositorio con project(...): " +
                ataduras.joinToString(" | "),
        )

        val artefactos = dependenciasPorArtefacto(text)
        assertTrue(
            artefactos.any { it.startsWith("km-core:") },
            "km-core no se declara como artefacto (vistos: $artefactos)",
        )
        assertTrue(
            artefactos.any { it.startsWith("km-transport:") },
            "km-transport no se declara como artefacto (vistos: $artefactos)",
        )
    }

    // --- Controles: los detectores disparan y no inventan ---

    @Test
    fun `control negativo el detector de include ve un include de la app`() {
        val sucio = """
            // include(":app") en un comentario NO cuenta
            include(":km-core")
            include(":app") // tampoco este comentario lo salva
        """.trimIndent()
        val detectados = detectorDeInclude(sucio)
        assertEquals(2, detectados.size, "debio ver las dos lineas de codigo reales: $detectados")
        assertTrue(detectados.any { it.contains(APP_MODULE) })
    }

    @Test
    fun `control negativo el detector de ataduras ve un project en el build`() {
        val sucio = """
            dependencies {
                // project(":km-core") en comentario: no ata nada
                implementation(project(":km-core"))
            }
        """.trimIndent()
        val ataduras = atadurasPorProyecto(sucio)
        assertEquals(1, ataduras.size, "debio ver la atadura real: $ataduras")
    }

    @Test
    fun `control positivo los detectores no inventan`() {
        val limpio = """
            // El motor vive en km-transport y lo consume km-desktop y la app.
            include(":km-core")
            include(":km-transport")
        """.trimIndent()
        assertTrue(detectorDeInclude(limpio).none { it.contains(APP_MODULE) })

        val buildLimpio = """
            dependencies {
                implementation("com.km:km-core:0.1.0-SNAPSHOT")
                implementation("com.km:km-transport:0.1.0-SNAPSHOT")
            }
        """.trimIndent()
        assertEquals(
            emptyList<String>(),
            atadurasPorProyecto(buildLimpio),
            "un build limpio no tiene ataduras por proyecto",
        )
        assertEquals(
            listOf("km-core:0.1.0-SNAPSHOT", "km-transport:0.1.0-SNAPSHOT"),
            dependenciasPorArtefacto(buildLimpio),
            "los dos artefactos se extraen del build limpio",
        )
    }
}