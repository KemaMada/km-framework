package com.example.keymessage.build

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File

/**
 * Release 1.1 (C1) — el arranque en frio publica en invocaciones SEPARADAS.
 *
 * ## QUE AFIRMA
 *
 * Que `km` (POSIX) y `km.cmd` (Windows) piden, en procesos distintos y en este
 * orden: `:km-core:publishToMavenLocal`, `:km-webrtc:publishToMavenLocal`,
 * `:km-transport:publishToMavenLocal`, `:km-desktop:shadowJar`.
 *
 * `km-webrtc` entra porque `km-desktop` lo declara POR ARTEFACTO (tier P2P):
 * publicar solo core y transport deja `Could not find com.km:km-webrtc` en un
 * mavenLocal vacio, que es exactamente el arranque en frio que se arregla.
 *
 * ## POR QUE
 *
 * `km-transport` declara `api("com.km:km-core:...")` y `km-desktop` consume los
 * dos artefactos, pero `publishToMavenLocal` no es dependencia de los
 * `compileKotlin` del mismo build. Con mavenLocal vacio, pedir las tareas juntas
 * deja que el scheduler compile km-transport antes de que exista km-core y la
 * resolucion falla con "Could not find com.km:km-core" (reproducido 4/5, 3/3
 * con ~/.m2 vaciado). El orden solo se garantiza con invocaciones separadas.
 *
 * ## CONTROL NEGATIVO
 *
 * [invocaciones] es PURA: el test `control*` le pasa la forma COMBINADA (la que
 * estaba rota) y afirma que la detecta como una sola invocacion.
 */
class LauncherColdStartTest {

    companion object {
        const val CORE = ":km-core:publishToMavenLocal"
        const val WEBRTC = ":km-webrtc:publishToMavenLocal"
        const val TRANSPORT = ":km-transport:publishToMavenLocal"
        const val DESKTOP = ":km-desktop:shadowJar"
        val ORDEN = listOf(CORE, WEBRTC, TRANSPORT, DESKTOP)

        /** Une continuaciones POSIX (`\` al final de linea) antes de parsear. */
        fun unirContinuaciones(text: String): String =
            text.replace(Regex("\\\\\\s*\\n\\s*"), " ")

        /**
         * Tareas `:km-*:tarea` pedidas en cada linea que invoca [marcas] (el
         * lanzador POSIX llama a su funcion `gradle_tareas`, que por dentro usa
         * `gradlew`; `km.cmd` llama a `gradlew.bat`). Devuelve una lista por
         * invocacion, en orden.
         */
        fun invocaciones(text: String, marcas: List<String>): List<List<String>> =
            unirContinuaciones(text).split("\n")
                .map { it.substringBefore("rem ").substringBefore("#") }
                .filter { line -> marcas.any { line.contains(it) } }
                .map { line ->
                    Regex(":(km-[a-z]+):([A-Za-z0-9]+)").findAll(line)
                        .map { ":${it.groupValues[1]}:${it.groupValues[2]}" }
                        .toList()
                }
                .filter { it.isNotEmpty() }

        /** Invocaciones que piden alguna de [ORDEN], reducidas a la tarea pedida. */
        fun secuencia(text: String, marcas: List<String>): List<String> =
            invocaciones(text, marcas).mapNotNull { tareas ->
                if (tareas.size > 1) "COMBINADA(${tareas.joinToString(" ")})" else tareas.singleOrNull()
            }
    }

    private fun lector(nombre: String): String {
        val f = File(RootBuildIsolationTest.repoRoot(), nombre)
        assertTrue(f.isFile, "no existe $nombre")
        return f.readText()
    }

    @Test
    fun `km publica core y transport en procesos separados y en orden`() {
        val secuencia = secuencia(lector("km"), listOf("gradlew", "gradle_tareas"))
        assertEquals(
            ORDEN,
            secuencia,
            "el lanzador POSIX no hace cuatro invocaciones separadas en orden: $secuencia",
        )
    }

    @Test
    fun `km cmd publica core y transport en procesos separados y en orden`() {
        val secuencia = secuencia(lector("km.cmd"), listOf("gradlew.bat"))
        assertEquals(
            ORDEN + ORDEN,
            secuencia,
            "km.cmd no hace cuatro invocaciones separadas en cada rama (con/sin KM_GRADLE_JAVA_HOME): $secuencia",
        )
    }

    @Test
    fun `ninguna invocacion mezcla publicacion y compilacion`() {
        listOf("km" to listOf("gradlew", "gradle_tareas"), "km.cmd" to listOf("gradlew.bat")).forEach { (archivo, marcas) ->
            invocaciones(lector(archivo), marcas).forEach { tareas ->
                assertEquals(
                    1,
                    tareas.size,
                    "SEGURIDAD DEL ARRANQUE: $archivo pide varias tareas en una invocacion: $tareas",
                )
            }
        }
    }

    @Test
    fun `control negativo el detector ve la forma combinada`() {
        val roto = """
            #!/bin/sh
            ./gradlew --quiet :km-core:publishToMavenLocal :km-webrtc:publishToMavenLocal \
                :km-transport:publishToMavenLocal :km-desktop:shadowJar
        """.trimIndent()
        assertEquals(
            listOf("COMBINADA($CORE $WEBRTC $TRANSPORT $DESKTOP)"),
            secuencia(roto, listOf("gradlew")),
            "el detector no vio la invocacion combinada que rompia el arranque en frio",
        )
    }
}
