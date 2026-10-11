package com.km.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 1.2 — Guardia estatica permanente: `km-core` NO depende de `km-webrtc`.
 *
 * La dependencia es de UN SOLO SENTIDO y va en el sentido correcto:
 * `km-webrtc` depende de `km-core` (implementa su contrato de transporte);
 * `km-core` nunca depende de `km-webrtc`. Es la condicion de frontera DP4/SC15
 * y el soporte del invariante crypto/transporte R12: si `km-core` pudiera
 * enlazar el backend WebRTC real, la capa de cadena tendria acceso al medio
 * concreto y la separacion dejaria de ser estructural.
 *
 * ## Que se comprueba (tres angulos, porque uno solo no basta)
 *
 *  1. **Fuente**: cero referencias a `com.km.webrtc` en `km-core/src/main`. Un
 *     `import` sin usar lo elimina el compilador, asi que un barrido de
 *     bytecode no veria la fuga mas probable; el barrido de FUENTE si.
 *  2. **Build script**: `km-core/build.gradle.kts` no declara ninguna
 *     dependencia `km-webrtc` (ni `implementation`, ni `compileOnly`, ni
 *     `api`). Esto cubre el caso `compileOnly`, que no apareceria en la
 *     classpath de ejecucion.
 *  3. **Classpath**: ninguna entrada de la classpath del test contiene
 *     `km-webrtc`. La classpath de test (`testRuntimeClasspath`) es
 *     superconjunto de la de compilacion para dependencias `implementation`;
 *     combinada con (2), fija la ausencia en `compileClasspath`.
 *
 * ## Control negativo
 *
 * Un detector que nunca dispara da falsa confianza. Los tests `control*`
 * alimentan los detectores puros con una fuente/clasepath sinteticos que
 * contienen la fuga real y afirman que la ven; y con entradas limpias y
 * afirman que no la inventan.
 */
class KmCoreNoWebRtcDependencyTest {

    companion object {
        /** Paquete prohibido en `km-core` para siempre. */
        const val WEBRTC_PACKAGE: String = "com.km.webrtc"

        /** Nombre del artefacto/modulo cuyo jar jamas debe estar en la classpath. */
        const val WEBRTC_ARTIFACT: String = "km-webrtc"
    }

    // --- Resolucion del proyecto km-core ---

    private fun kmCoreProjectDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (dir.name == "km-core" && File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile
        }
        error("No se encontro el proyecto km-core partiendo de user.dir=${System.getProperty("user.dir")}")
    }

    // --- Detector puro de fuente (reutilizable por los controles) ---

    /**
     * Elimina comentarios de linea y de bloque preservando el numero de
     * lineas: una mencion en KDoc a `km-webrtc` no es una dependencia, pero el
     * texto real debe conservar su numero de linea para el mensaje de fallo.
     */
    internal fun stripComments(source: String): String {
        val sb = StringBuilder(source.length)
        var i = 0
        var inBlock = false
        var inLine = false
        while (i < source.length) {
            val c = source[i]
            val n = if (i + 1 < source.length) source[i + 1] else '\u0000'
            when {
                inLine -> {
                    if (c == '\n') { inLine = false; sb.append('\n') } else sb.append(' ')
                }
                inBlock -> {
                    if (c == '*' && n == '/') { inBlock = false; sb.append("  "); i += 2; continue }
                    sb.append(if (c == '\n') '\n' else ' ')
                }
                c == '/' && n == '/' -> { inLine = true; sb.append("  "); i += 2; continue }
                c == '/' && n == '*' -> { inBlock = true; sb.append("  "); i += 2; continue }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** Referencias a [WEBRTC_PACKAGE] en [source], una por linea, sin comentarios. */
    internal fun findWebRtcRefs(fileName: String, source: String): List<String> {
        val stripped = stripComments(source).split("\n")
        val original = source.split("\n")
        val refs = mutableListOf<String>()
        stripped.forEachIndexed { idx, line ->
            if (line.contains(WEBRTC_PACKAGE)) {
                val text = original.getOrElse(idx) { line }.trim()
                refs += "$fileName:${idx + 1}: [$WEBRTC_PACKAGE] $text"
            }
        }
        return refs
    }

    /** Entradas de [entries] que pertenecen al artefacto [WEBRTC_ARTIFACT]. */
    internal fun webRtcClasspathOffenders(entries: List<String>): List<String> =
        entries.filter { it.contains(WEBRTC_ARTIFACT) }

    // --- Test real 1: la fuente de km-core esta limpia ---

    @Test
    @DisplayName("1.2-01 km-core/src/main no referencia com.km.webrtc")
    fun fuenteSinWebRtc() {
        val main = File(kmCoreProjectDir(), "src/main/kotlin")
        assertTrue(main.isDirectory, "no existe el arbol de fuentes de km-core: $main")

        val sources = main.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .toList()
        assertTrue(sources.isNotEmpty(), "no se encontro ninguna fuente en $main")

        val refs = sources.flatMap { findWebRtcRefs(it.relativeTo(main).path, it.readText()) }
        assertEquals(
            emptyList<String>(),
            refs,
            "FRONTERA DP4 VIOLADA: km-core referencia el paquete WebRTC:\n" + refs.joinToString("\n"),
        )
    }

    // --- Test real 2: el build script no declara la dependencia ---

    @Test
    @DisplayName("1.2-02 km-core/build.gradle.kts no declara km-webrtc")
    fun buildScriptSinWebRtc() {
        val build = File(kmCoreProjectDir(), "build.gradle.kts")
        assertTrue(build.isFile, "no existe $build")
        // Sin comentarios: una mencion en un comentario no declara nada.
        val body = build.readText().lineSequence()
            .joinToString("\n") { it.substringBefore("//") }
        assertFalse(
            body.contains(WEBRTC_ARTIFACT),
            "km-core NO puede declarar la dependencia km-webrtc (ni implementation, ni compileOnly, ni api)",
        )
    }

    // --- Test real 3: la classpath no lleva el artefacto ---

    @Test
    @DisplayName("1.2-03 la classpath de km-core no contiene km-webrtc")
    fun classpathSinWebRtc() {
        val entries = System.getProperty("java.class.path")
            ?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() }
            .orEmpty()
        assertTrue(entries.isNotEmpty(), "java.class.path vacia: no se puede verificar la frontera")
        val offenders = webRtcClasspathOffenders(entries)
        assertEquals(
            emptyList<String>(),
            offenders,
            "km-webrtc NO debe estar en la classpath de km-core:\n" + offenders.joinToString("\n"),
        )
    }

    // --- Controles: los detectores disparan y no inventan ---

    @Test
    @DisplayName("1.2-04 control negativo: el detector de fuente dispara ante com.km.webrtc")
    fun controlFuenteDispara() {
        val fuga = """
            package com.km.node
            import ${WEBRTC_PACKAGE}.RealWebRtcTransport
            class TransportChain {
                val backend = ${WEBRTC_PACKAGE}.RealWebRtcTransport()
            }
        """.trimIndent()
        val refs = findWebRtcRefs("TransportChain.kt", fuga)
        assertEquals(2, refs.size, "el detector debio ver las dos referencias, vio: $refs")
        assertTrue(refs.all { it.contains(WEBRTC_PACKAGE) })
    }

    @Test
    @DisplayName("1.2-05 control negativo: el detector de classpath dispara ante km-webrtc")
    fun controlClasspathDispara() {
        val sucia = listOf(
            "/repo/km-core/build/classes/kotlin/main",
            "/repo/km-webrtc/build/libs/km-webrtc-0.1.0-SNAPSHOT.jar",
            "/repo/km-core/build/resources/main",
        )
        val offenders = webRtcClasspathOffenders(sucia)
        assertEquals(1, offenders.size, "el detector debio ver la entrada de km-webrtc: $offenders")
        assertTrue(offenders.single().contains(WEBRTC_ARTIFACT))
    }

    @Test
    @DisplayName("1.2-06 control positivo: fuente y classpath limpias no inventan fugas")
    fun controlNoInventa() {
        val limpia = """
            package com.km.node
            import com.km.model.IdentityId
            // En prosa: "km-webrtc depende de km-core, nunca al reves".
            /* Bloque: com.km.webrtc tampoco cuenta dentro de un comentario. */
            class TransportBackend {
                fun name(peer: IdentityId) = peer.toString()
            }
        """.trimIndent()
        assertTrue(findWebRtcRefs("TransportBackend.kt", limpia).isEmpty())
        assertTrue(
            webRtcClasspathOffenders(
                listOf("/repo/km-core/build/classes/kotlin/main", "/root/.m2/.../km-core-0.1.0-SNAPSHOT.jar"),
            ).isEmpty(),
        )
    }
}
