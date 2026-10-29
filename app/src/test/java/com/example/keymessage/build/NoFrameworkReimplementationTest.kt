package com.example.keymessage.build

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 5.2 — La app no reimplementa el framework: ni criptografia, ni el paquete que
 * ya no existe.
 *
 * ## QUE AFIRMA
 *
 * En `app/src/main`:
 *
 *  - CERO referencias a `com.keymessage.core`: el paquete que el cliente legacy
 *    importaba y que **no existe** en km-core (que es `com.km`). Una referencia
 *    a el no es "codigo viejo", es codigo que no compila.
 *  - CERO `javax.crypto.Cipher` y CERO `java.security.KeyPairGenerator`: la
 *    criptografia vive en el framework. El cliente legacy tenia RSA 2048 en
 *    `model/Identity.kt` y AES-GCM casero en `crypto/Crypto.kt`.
 *  - CERO `dev.onvoid` y CERO `org.webrtc`.
 *
 * ## POR QUE ES ESTRUCTURAL Y NO CONDUCTUAL
 *
 * Porque "la app no tiene criptografia propia" no es un comportamiento que se
 * pueda observar desde fuera: no hay entrada de usuario que produzca una
 * asercion. Es una condicion de COMPOSICION. Escribir `Cipher.getInstance` no
 * rompe ningun test del mundo, y por eso hace falta un test que lo mire.
 *
 * El limite se declara: esto mira CODIGO, y una reimplementacion escondida en un
 * fichero con el texto cifrado no la veria. No es un guard que pretenda mas de lo
 * que puede.
 *
 * ## CONTROL NEGATIVO
 *
 * El detector se alimenta con una muestra sintetica que contiene la fuga y se
 * afirma que la ve; y con una muestra limpia y se afirma que no la inventa.
 */
class NoFrameworkReimplementationTest {

    companion object {
        /** Paquetes que la app no puede usar: el framework los posee todos. */
        val PAQUETES_PROHIBIDOS: List<String> = listOf(
            "com.keymessage.core",
            "javax.crypto.Cipher",
            "java.security.KeyPairGenerator",
            "dev.onvoid",
            "org.webrtc",
        )

        /** Raiz del modulo `app/`. */
        fun appRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                if (File(dir, "src/main").isDirectory && File(dir, "build.gradle.kts").isFile) return dir
                dir = dir.parentFile
            }
            error("No se encontro el modulo app desde user.dir=${System.getProperty("user.dir")}")
        }

        /** Fuentes de `src/main` de la app. */
        fun fuentes(): List<File> =
            File(appRoot(), "src/main")
                .walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .toList()

        /**
         * Sin comentarios, conservando el numero de linea.
         *
         * ## POR QUE HAY QUE ENTENDER LAS CADENAS
         *
         * Un `//` DENTRO de una cadena no es un comentario, y `ws://relay.invalid`
         * es justo el caso que este guard tiene que ver: un stripper ingenuo lo
         * borra y se queda con una linea vacia, o sea ciego justo en el caso que
         * vigila. Por eso el recorrido entiende las cadenas y solo ahi consume
         * comentarios.
         *
         * Una mencion en un KDoc no es infraestructura incrustada; una en codigo,
         * si. La linea se conserva para que el mensaje de fallo senale donde esta.
         */
        fun sinComentarios(fuente: String): String {
            val sb = StringBuilder(fuente.length)
            var i = 0
            var enBloque = false
            var enLinea = false
            var enCadena = false
            var enCadenaLarga = false
            var esc = false
            while (i < fuente.length) {
                val c = fuente[i]
                val n = if (i + 1 < fuente.length) fuente[i + 1] else ' '
                val triple = c == '"' && n == '"' && fuente.getOrNull(i + 2) == '"'
                when {
                    enLinea -> {
                        if (c == '\n') { enLinea = false; sb.append('\n') } else sb.append(' ')
                    }
                    enBloque -> {
                        if (c == '*' && n == '/') { enBloque = false; sb.append("  "); i += 2; continue }
                        sb.append(if (c == '\n') '\n' else ' ')
                    }
                    enCadena || enCadenaLarga -> {
                        sb.append(c)
                        when {
                            esc -> esc = false
                            c == '\\' -> esc = true
                            !enCadenaLarga && c == '"' -> enCadena = false
                            enCadenaLarga && c == '"' && n == '"' && fuente.getOrNull(i + 2) == '"' -> {
                                sb.append("\"\""); i += 3
                            }
                            enCadenaLarga && c == '"' -> enCadenaLarga = false
                        }
                    }
                    triple -> { enCadenaLarga = true; sb.append("   "); i += 3 }
                    c == '"' -> { enCadena = true; sb.append(c) }
                    c == '/' && n == '/' -> { enLinea = true; sb.append("  "); i += 2; continue }
                    c == '/' && n == '*' -> { enBloque = true; sb.append("  "); i += 2; continue }
                    else -> sb.append(c)
                }
                i++
            }
            return sb.toString()
        }
        /** Lineas de [nombre] que usan un paquete prohibido. */
        fun offenders(nombre: String, fuente: String): List<String> {
            val original = fuente.split("\n")
            return sinComentarios(fuente).split("\n").mapIndexedNotNull { idx, linea ->
                val paquete = PAQUETES_PROHIBIDOS.firstOrNull { linea.contains(it) } ?: return@mapIndexedNotNull null
                val codigo = original.getOrElse(idx) { linea }.trim()
                "$nombre:${idx + 1}: [$paquete] $codigo"
            }
        }
    }

    @Test
    fun `la app no reimplementa el framework`() {
        val ficheros = fuentes()
        assertTrue(ficheros.isNotEmpty(), "no se encontro ninguna fuente en src/main de la app")

        val culpables = ficheros.flatMap { f -> offenders(f.relativeTo(appRoot()).path, f.readText()) }
        assertEquals(
            emptyList<String>(),
            culpables,
            "FRONTERA VIOLADA: la app reimplementa criptografia o usa el paquete inexistente: " +
                culpables.joinToString(" | "),
        )
    }

    @Test
    fun `la app consume km-core y km-transport por sus paquetes de verdad`() {
        // El otro lado de la misma frontera: que lo que queda sea `com.km.*`, y no
        // que simplemente no haya nada.
        val usaComKm = fuentes().any { it.readText().contains("import com.km.") }
        assertTrue(usaComKm, "la app tiene que IMPORTAR el framework: sin esto, el test anterior seria vacio")
    }

    @Test
    fun `control negativo el detector ve la reimplementacion`() {
        val fuga = """
            package com.example.keymessage.crypto
            import javax.crypto.Cipher
            import java.security.KeyPairGenerator
            class Crypto {
                val clave = Cipher.getInstance("AES/GCM/NoPadding")
                val par = KeyPairGenerator.getInstance("RSA")
            }
        """.trimIndent()

        val visto = offenders("Crypto.kt", fuga)
        assertEquals(2, visto.size, "debio ver las dos reimplementaciones: $visto")
        assertTrue(visto.any { it.contains("javax.crypto.Cipher") })
        assertTrue(visto.any { it.contains("java.security.KeyPairGenerator") })
    }

    @Test
    fun `control negativo el detector ve el paquete inexistente`() {
        val fuga = """
            package com.example.keymessage.ui
            import com.keymessage.core.model.IdentityId
            class ViewModel {
                val id: IdentityId = IdentityId("x")
            }
        """.trimIndent()

        val visto = offenders("ConversationViewModel.kt", fuga)
        assertEquals(1, visto.size, "debio ver la referencia al paquete que no existe: $visto")
    }

    @Test
    fun `control positivo el detector no inventa y no lee comentarios`() {
        val limpio = """
            package com.example.keymessage.core
            import com.km.crypto.Ed25519Impl
            import com.km.model.IdentityId
            // Antes se importaba com.keymessage.core.model.IdentityId: el paquete no existe.
            /* javax.crypto.Cipher vivia en el Crypto.kt borrado */
            class CoreFactory {
                val ed = Ed25519Impl()
                fun id(): IdentityId = IdentityId("x")
            }
        """.trimIndent()

        assertEquals(
            emptyList<String>(),
            offenders("CoreFactory.kt", limpio),
            "una mencion en prosa o en un import de framework no es una reimplementacion",
        )
    }
}