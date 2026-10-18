package com.km.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class RelayTransportCryptoSeparationTest {
    companion object {
        private val FORBIDDEN = listOf(
            "com.km.crypto",
            "com.km.ratchet",
            "com.km.frame",
            "com.km.x3dh",
            "km.desktop",
            "km.webrtc",
            "km.daemon",
            "dev.onvoid",
        )
        private val ALLOWED: Map<String, Set<String>> = mapOf(
            "JvmRelayTransport.kt" to setOf("com.km.crypto.Ed25519", "com.km.crypto.Ed25519Impl"),
        )
        private val DEFAULT_ENDPOINT = "km-transport/src/main/kotlin/com/km/transport"

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

        internal fun findLeaks(fileName: String, source: String): List<String> {
            val stripped = stripComments(source).split("\n")
            val original = source.split("\n")
            val leaks = mutableListOf<String>()
            stripped.forEachIndexed { idx, line ->
                val allowed = ALLOWED.getOrElse(fileName) { emptySet() }
                val found = FORBIDDEN.firstOrNull { forbidden ->
                    line.contains(forbidden) && !allowed.any { line.contains(it) && forbidden.startsWith("com.km") && it.startsWith(forbidden) }
                }
                if (found != null && !line.trim().startsWith("import com.km.node")) {
                    val text = original.getOrElse(idx) { line }.trim()
                    leaks += "$fileName:${idx + 1}: [$found] $text"
                }
            }
            return leaks
        }

        private fun sourceDir(): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                val candidate = File(dir, DEFAULT_ENDPOINT)
                if (candidate.isDirectory) return candidate
                dir = dir.parentFile
            }
            error("No se encontro '$DEFAULT_ENDPOINT'")
        }
    }

    @Test
    fun `guard de separacion cripto-transporte limpio`() {
        val dir = sourceDir()
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val leaks = files.flatMap { f -> findLeaks(f.name, f.readText()) }
        assertEquals(emptyList<String>(), leaks, leaks.joinToString("\n"))
    }

    @Test
    fun `control detecta una fuga sintetica`() {
        val src = "import com.km.crypto.Foo\nfun x() {}\n"
        assertTrue(findLeaks("X.kt", src).isNotEmpty())
    }

    @Test
    fun `control no inventa fugas en fuente limpia`() {
        val src = "package com.km.transport\nfun x() = 42\n"
        assertTrue(findLeaks("Y.kt", src).isEmpty())
    }
}