package com.example.keymessage.build

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File

/**
 * Release 1.1 (seguridad) — las credenciales de firma NO viven en el repo.
 *
 * ## QUE AFIRMA
 *
 *  1. `app/build.gradle.kts` no contiene NINGUNA password hardcodeada: ni
 *     `storePassword = "..."` ni `keyPassword = "..."` con literal.
 *  2. Las lee de gradle property o variable de entorno, con los nombres
 *     `KM_RELEASE_*`, y el alias es configurable con default `keymessage`.
 *  3. El keystore sigue siendo `app/keymessage-release.jks`, y el `.gitignore`
 *     lo excluye (no se versiona).
 *
 * ## POR QUE ES UNA GUARDA
 *
 * El fallo original (`storePassword = "<literal>"`) no rompia ninguna compilacion:
 * pasaba desapercibido y acababa publicado. Ningun test de comportamiento puede
 * verlo, asi que tiene que verlo uno que lea el build.
 *
 * ## CONTROL NEGATIVO
 *
 * [passwordsHardcodeadas] es PURA: los tests `control*` le pasan un build con el
 * literal y afirman que lo ve, y uno limpio y afirman que no lo inventa.
 */
class ReleaseSigningConfigTest {

    companion object {
        /**
         * `storePassword = "x"` / `keyPassword = "x"` sobre una linea de CODIGO.
         *
         * Exige COMILLAS: `storePassword = p.orNull` es la forma correcta (provider)
         * y no es un literal; solo el string entrecomillado es la fuga.
         */
        fun passwordsHardcodeadas(buildText: String): List<String> =
            buildText.split("\n").mapIndexedNotNull { idx, raw ->
                val code = raw.substringBefore("//")
                if (Regex("(store|key)Password\\s*=\\s*\"").containsMatchIn(code)) {
                    "build:${idx + 1}: ${code.trim()}"
                } else {
                    null
                }
            }
    }

    private fun buildText(): String {
        val build = File(RootBuildIsolationTest.repoRoot(), "app/build.gradle.kts")
        assertTrue(build.isFile, "no existe app/build.gradle.kts: $build")
        return build.readText()
    }

    @Test
    fun `el build de la app no hardcodea passwords`() {
        val literales = passwordsHardcodeadas(buildText())
        assertEquals(
            emptyList<String>(),
            literales,
            "SEGURIDAD: hay passwords de firma hardcodeadas en el repo: $literales",
        )
    }

    @Test
    fun `las credenciales se leen de property o entorno`() {
        val text = buildText()
        val code = text.split("\n").map { it.substringBefore("//") }.joinToString("\n")

        assertTrue(
            code.contains("KM_RELEASE_STORE_PASSWORD"),
            "el build no lee KM_RELEASE_STORE_PASSWORD (property/entorno)",
        )
        assertTrue(
            code.contains("KM_RELEASE_KEY_PASSWORD"),
            "el build no lee KM_RELEASE_KEY_PASSWORD (property/entorno)",
        )
        assertTrue(
            code.contains("providers.gradleProperty") && code.contains("providers.environmentVariable"),
            "las credenciales no se leen via providers (gradle property o variable de entorno)",
        )
        assertTrue(
            code.contains("KM_RELEASE_KEY_ALIAS") && code.contains("orElse(\"keymessage\")"),
            "el alias no es configurable con default keymessage",
        )
    }

    @Test
    fun `el keystore sigue en app y esta ignorado por git`() {
        val root = RootBuildIsolationTest.repoRoot()
        assertTrue(
            buildText().contains("file(\"keymessage-release.jks\")"),
            "el storeFile ya no es app/keymessage-release.jks",
        )

        val gitignore = File(root, ".gitignore")
        assertTrue(gitignore.isFile, "no existe .gitignore en la raiz")
        val ignorados = gitignore.readLines().map { it.trim() }
        assertTrue(
            ignorados.any { it == "keymessage-release.jks" || it == "*.jks" },
            "el keystore de release ya no esta ignorado por git: $ignorados",
        )
    }

    @Test
    fun `control negativo el detector ve una password literal`() {
        val sucio = """
            signingConfigs {
                create("release") {
                    // storePassword = "comentario" no cuenta
                    storePassword = "secreto"
                    keyPassword = "secreto"
                }
            }
        """.trimIndent()
        val detectados = passwordsHardcodeadas(sucio)
        assertEquals(2, detectados.size, "debio ver las dos passwords literales: $detectados")
    }

    @Test
    fun `control positivo el detector no inventa con providers`() {
        val limpio = """
            val p = providers.gradleProperty("KM_RELEASE_STORE_PASSWORD")
            signingConfigs {
                create("release") {
                    storePassword = p.orNull
                    keyPassword = q.orNull
                }
            }
        """.trimIndent()
        assertFalse(passwordsHardcodeadas(limpio).isNotEmpty(), "invento una password donde no la hay")
    }
}
