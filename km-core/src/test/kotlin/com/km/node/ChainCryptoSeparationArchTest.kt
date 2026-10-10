package com.km.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 0.2 — Guardia estatica del invariante R12: separacion cripto/transporte.
 *
 * La superficie de transporte-en-cadena SOLO decide POR DONDE salen los bytes.
 * Nunca decide QUE bytes, ni crea una sesion, ni cifra. Si alguno de estos
 * archivos referencia un paquete de criptografia, la frontera se rompio y este
 * test debe fallar — incluso si el codigo compila y los tests funcionales
 * pasan.
 *
 * ## Que se comprueba
 *
 * Cero referencias a los prefijos prohibidos en la superficie de cadena:
 *  - `com.km.crypto`   (primitivas, AEAD, KDF, firmas)
 *  - `com.km.ratchet`  (Double Ratchet)
 *  - `com.km.frame`    (SecureFrame)
 *  - `com.km.x3dh`     (X3DH)
 *
 * ## Por que un barrido de FUENTE y no de bytecode
 *
 * Un `import` no usado no aparece en el `.class` (el compilador de Kotlin lo
 * elimina), asi que un barrido de bytecode no veria exactamente la clase de
 * fuga mas probable: el import que alguien anade "por si acaso". El barrido de
 * fuente detecta import y referencia cualificada por igual.
 *
 * ## Ambito (allowlist explicita, no todo `com.km.node`)
 *
 * `NodeRuntimeImpl` y `RelayServer` SI importan `com.km.crypto` con
 * legitimidad: firman challenges/anuncios. Son runtime/relay, no el router.
 * El invariante R12 es sobre la superficie de enrutamiento: por eso la lista
 * es explicita y esta documentada, en vez de un glob del paquete.
 *
 * ## Control negativo
 *
 * Un detector que nunca dispara es peor que ninguno: da falsa confianza. Los
 * tests `control*` alimentan al detector con una fuente sintetica que contiene
 * una fuga real y afirman que la detecta; y con una fuente limpia y afirman
 * que no la inventa. El control compila porque opera sobre strings, y prueba
 * que el detector mira de verdad.
 */
class ChainCryptoSeparationArchTest {

    companion object {
        /** Prefijos de paquete que jamas pueden aparecer en la superficie de cadena. */
        val FORBIDDEN_PREFIXES: List<String> = listOf(
            "com.km.crypto",
            "com.km.ratchet",
            "com.km.frame",
            "com.km.x3dh",
        )

        /**
         * Superficie de transporte-en-cadena sujeta a R12.
         *
         * Cada entrada es un archivo dentro de `com/km/node/`. Se enumeran por
         * nombre para que el test falle con un mensaje claro si un archivo se
         * renombra o desaparece (en vez de saltarse en silencio).
         */
        val CHAIN_SURFACE_FILES: List<String> = listOf(
            "TransportChain.kt",          // el router: decide el MEDIO
            "ChainSupervisor.kt",         // A4.1/A4.2: failover observable, solo elige MEDIO
            "PeerTransportManager.kt",    // enlaza peer -> transporte
            "PeerTransportBinding.kt",    // estado del binding peer/transporte
            "TransportBackend.kt",        // contrato del backend de transporte
            "TransportEstablishment.kt",  // contrato de establecimiento
            "RelayTransport.kt",          // contrato del transporte relay
            "RelayDecision.kt",           // decision de relay (medio, no sesion)
            "FailureCodeMapper.kt",       // mapeo de errores de relay
            "FakeTransportBackend.kt",    // doble de test del backend
        )
    }

    // --- Detector puro, reutilizable por los controles ---

    /**
     * Elimina comentarios de linea y de bloque preservando el numero de
     * lineas, para que una mencion en KDoc a un paquete prohibido NO cuente
     * como fuga, pero una linea real si conserve su numero.
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

    /**
     * Devuelve las fugas detectadas en [source], una por linea.
     *
     * Un "leak" es una linea (sin comentarios) que contiene algun prefijo de
     * [FORBIDDEN_PREFIXES]. Devuelve la linea original para que el mensaje de
     * fallo muestre el texto tal cual lo escribio el autor.
     */
    internal fun findCryptoLeaks(fileName: String, source: String): List<String> {
        val stripped = stripComments(source).split("\n")
        val original = source.split("\n")
        val leaks = mutableListOf<String>()
        stripped.forEachIndexed { idx, line ->
            val found = FORBIDDEN_PREFIXES.firstOrNull { line.contains(it) }
            if (found != null) {
                val text = original.getOrElse(idx) { line }.trim()
                leaks += "$fileName:${idx + 1}: [$found] $text"
            }
        }
        return leaks
    }

    private fun chainSourceDir(): File {
        val relative = "src/main/kotlin/com/km/node"
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("No se encontro '$relative' partiendo de user.dir=${System.getProperty("user.dir")}")
    }

    // --- Test real: la superficie de cadena esta limpia ---

    @Test
    @DisplayName("0.2-01 toda la superficie de cadena existe y esta libre de cripto")
    fun superficieDeCadenaLimpia() {
        val dir = chainSourceDir()
        val missing = CHAIN_SURFACE_FILES.filterNot { File(dir, it).isFile }
        assertTrue(
            missing.isEmpty(),
            "archivos de la superficie de cadena ausentes en $dir: $missing " +
                "(si se renombraron, actualiza CHAIN_SURFACE_FILES; no borres la guardia)",
        )

        val leaks = CHAIN_SURFACE_FILES.flatMap { name ->
            findCryptoLeaks(name, File(dir, name).readText())
        }
        assertEquals(
            emptyList<String>(),
            leaks,
            "R12 VIOLADO: la superficie de transporte referencio criptografia:\n" + leaks.joinToString("\n"),
        )
    }

    @Test
    @DisplayName("0.2-02 la superficie incluye los anclas TransportChain y PeerTransportManager")
    fun superficieIncluyeAnclas() {
        assertTrue(CHAIN_SURFACE_FILES.contains("TransportChain.kt"))
        assertTrue(CHAIN_SURFACE_FILES.contains("PeerTransportManager.kt"))
        assertTrue(CHAIN_SURFACE_FILES.size >= 5, "la superficie parece demasiado estrecha")
    }

    // --- Controles: el detector dispara y no inventa ---

    @Test
    @DisplayName("0.2-03 control negativo: el detector dispara ante un import de cripto")
    fun controlDetectorDispara() {
        val fuga = """
            package com.km.node
            import com.km.crypto.Ed25519
            class TransportChain
        """.trimIndent()
        val leaks = findCryptoLeaks("TransportChain.kt", fuga)
        assertEquals(1, leaks.size, "el detector debio ver exactamente una fuga, vio: $leaks")
        assertTrue(leaks.single().contains("com.km.crypto"), "la fuga debe citar el paquete: $leaks")
    }

    @Test
    @DisplayName("0.2-04 control negativo: el detector dispara ante una referencia cualificada")
    fun controlDetectorDisparaConReferencia() {
        val fuga = """
            package com.km.node
            class Chain {
                fun verify(k: com.km.ratchet.DoubleRatchetSession) = k
            }
        """.trimIndent()
        val leaks = findCryptoLeaks("Chain.kt", fuga)
        assertEquals(1, leaks.size, "el detector debio ver la referencia cualificada: $leaks")
        assertTrue(leaks.single().contains("com.km.ratchet"))
    }

    @Test
    @DisplayName("0.2-05 control positivo: el detector no inventa fugas en fuente limpia")
    fun controlDetectorNoInventa() {
        val limpia = """
            package com.km.node
            import com.km.model.IdentityId
            // mencion en prosa: ratchet, X3DH, frame, crypto — no es una fuga
            /* bloque: com.km.crypto tampoco cuenta dentro de un comentario */
            class TransportChain {
                fun route(peer: IdentityId) = Unit
            }
        """.trimIndent()
        assertTrue(findCryptoLeaks("TransportChain.kt", limpia).isEmpty())
    }

    @Test
    @DisplayName("0.2-06 el strip de comentarios ignora KDoc y conserva lineas")
    fun stripConservaLineas() {
        val src = "linea1\n/** com.km.crypto */\nlinea3\n"
        assertEquals(src.split("\n").size, stripComments(src).split("\n").size)
        assertFalse(stripComments(src).contains("com.km.crypto"))
        assertTrue(stripComments(src).contains("linea1"))
        assertTrue(stripComments(src).contains("linea3"))
    }
}
