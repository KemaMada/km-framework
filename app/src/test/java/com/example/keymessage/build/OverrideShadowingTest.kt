package com.example.keymessage.build

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 6.2 — Ningun `override` se llama a si mismo por shadowing.
 *
 * ## EL BUG
 *
 * `AppContainer` montaba el ciclo de recuperacion con un
 * `object : RecoveryFlow.Ciclo` cuyos overrides eran
 * `override fun parar() { parar() }` y `override fun arrancar() { arrancar() }`.
 * El miembro de la clase anonima tiene prioridad sobre el receptor externo, asi
 * que el `parar()` de dentro era el propio override: recursion infinita y
 * `StackOverflowError` en TODA restauracion de identidad (ADB v5, obs #285).
 *
 * ## POR QUE UN GUARD DE CODIGO Y NO SOLO UN TEST
 *
 * El test de integracion (`RecoveryCicloIntegrationTest`) ejerce el ciclo REAL y
 * falla si el ciclo real vuelve a delegar en si mismo. Lo que no puede cubrir es
 * un `object : Ciclo { ... }` NUEVO, escrito en otro fichero, donde el override
 * vuelve a tapar al de fuera. Eso no es un comportamiento: es una FORMA, y la
 * forma se mira.
 *
 * ## QUE MIRA Y QUE NO
 *
 * Mira `app/src/main`, sin comentarios, el patron `override fun f(...) { f(...)`
 * con la llamada desnuda: un cuerpo que empieza invocando su propio nombre. NO
 * juzga si la llamada es intencional —`override fun toString() { toString() }`
 * dentro de un `init` seria legitimo—, por eso el limite se declara: el guard
 * prohibe la forma, no la intencion.
 *
 * ## CONTROL NEGATIVO
 *
 * El detector se alimenta de una muestra sintetica con el bug dentro y se afirma
 * que lo ve; y de la misma muestra cualificada con `this@Contenedor` y se afirma
 * que NO lo ve, que es justo la forma que si se quiere escribir.
 */
class OverrideShadowingTest {

    companion object {

        /** El patron: un override cuyo cuerpo empieza llamando a su PROPIO nombre. */
        private val SOMBRA = Regex(
            """override\s+fun\s+(\w+)\s*\([^)]*\)\s*(?::\s*[^{\n]+)?\{\s*\n?\s*\1\s*\(""",
        )

        /** El mismo override, con el receptor externo escrito: NO es shadowing. */
        private val SIN_SOMBRA = """
            class Contenedor {
                fun parar() { }
                fun arrancar() = true
                val ciclo: Interfaz = object : Interfaz {
                    override fun parar() {
                        this@Contenedor.parar()
                    }
                    override fun arrancar() {
                        this@Contenedor.arrancar()
                    }
                }
                interface Interfaz { fun parar(); fun arrancar() }
            }
        """.trimIndent()

        /** La muestra con el bug de ADB v5 tal cual estaba escrito. */
        private val CON_SOMBRA = """
            class Contenedor {
                fun parar() { }
                fun arrancar() = true
                val ciclo: Interfaz = object : Interfaz {
                    override fun parar() {
                        parar()
                    }
                    override fun arrancar() {
                        arrancar()
                    }
                }
                interface Interfaz { fun parar(); fun arrancar() }
            }
        """.trimIndent()

        /** Raiz del modulo `app/`. */
        fun appRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                if (File(dir, "src/main").isDirectory && File(dir, "build.gradle.kts").isFile) return dir
                dir = dir.parentFile
            }
            error("No se encontro el modulo app desde user.dir=${System.getProperty("user.dir")}")
        }

        /** Fuentes Kotlin de `src/main` de la app. */
        fun fuentes(): List<File> =
            File(appRoot(), "src/main")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .toList()

        /** Offenders con fichero y linea, sobre el codigo SIN comentarios. */
        fun shadows(nombre: String, fuente: String): List<String> {
            val limpio = NoFrameworkReimplementationTest.sinComentarios(fuente)
            return SOMBRA.findAll(limpio).map { m ->
                val linea = limpio.take(m.range.first).count { it == '\n' } + 1
                "$nombre:$linea: override fun ${m.groupValues[1]}() { ${m.groupValues[1]}(...) }"
            }.toList()
        }
    }

    @Test
    fun `ningun override de src_main se llama a si mismo`() {
        val ficheros = fuentes()
        assertTrue(ficheros.isNotEmpty(), "no se encontro ninguna fuente en src/main de la app")

        val culpables = ficheros.flatMap { f -> shadows(f.relativeTo(appRoot()).path, f.readText()) }
        assertEquals(
            emptyList<String>(),
            culpables,
            "OVERRIDE EN SOMBRA: el miembro tapa al receptor externo y el cuerpo se llama a si mismo; " +
                "la forma buena es escribir el receptor entero (this@Contenedor.metodo()): " +
                culpables.joinToString(" | "),
        )
    }

    @Test
    fun `control negativo el detector ve el bug y no ve el receptor cualificado`() {
        assertEquals(
            2,
            shadows("muestra", CON_SOMBRA).size,
            "la muestra con el bug de ADB v5 tiene que dar DOS offenses: $CON_SOMBRA",
        )
        assertEquals(
            emptyList<String>(),
            shadows("muestra", SIN_SOMBRA),
            "y con el receptor externo escrito NO hay nada que ver: esa es la forma buena",
        )
    }
}