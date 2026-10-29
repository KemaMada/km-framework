package com.example.keymessage.build

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 3.3 — Sin endpoints incrustados: ni IPs literales, ni `stun:`/`turn:`/`ws://` con
 * host escrito a mano.
 *
 * ## QUE ES Y QUE NO ES
 *
 * Es una guarda ESTRUCTURAL, y se declara como tal: lee el codigo fuente de
 * `app/src/main`. La razon por la que aun asi es correcta esta escrita en
 * `NoFrameworkReimplementationTest` —una aparecion en un fichero Kotlin es una
 * decision, y lo que se busca es que esa decision no se pueda tomar por descuido—
 * pero conviene no disfrazarla: esto no demuestra nada que un usuario pueda ver.
 *
 * ## POR QUE BUSCA EN `src/main` Y NO EN EL REPOSITORIO
 *
 * El repositorio tiene tests que citan `ws://127.0.0.1` y muestras sinteticas de
 * guardia, y esos ficheros NO son codigo de la app. Un guard que escanea de mas
 * teaching a la gente a relajar el guard.
 *
 * ## CONTROL NEGATIVO
 *
 * El detector se ejercita con una muestra sintetica que contiene la fuga —una IP
 * literal y un `ws://` con host— y se afirma que la ve; y con una muestra limpia
 * y se afirma que no la inventa. Un detector que nunca dispara daria verde para
 * siempre.
 */
class NoHardcodedEndpointsTest {

    companion object {
        /** IPv4 literal: cuatro grupos de 1 a 3 digitos separados por puntos. */
        val IPV4 = Regex("""(?<![\w.])\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(?![\w.])""")

        /** Esquemas con host literal que la app no debe escribir. */
        val ESQUEMAS_PROHIBIDAS = listOf("stun:", "stuns:", "turn:", "turns:", "ws://")

        /** Extensiones Kotlin de la app. */
        val EXTENSIONES = setOf("kt", "java")

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
                .filter { it.isFile && it.extension in EXTENSIONES }
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
        /** Lineas de [fuente] que traen una IP literal o un esquema prohibido. */
        fun offenders(nombre: String, fuente: String): List<String> {
            val original = fuente.split("\n")
            return sinComentarios(fuente).split("\n").mapIndexedNotNull { idx, linea ->
                val codigo = original.getOrElse(idx) { linea }.trim()
                when {
                    IPV4.containsMatchIn(linea) -> "$nombre:${idx + 1}: IP literal en [$codigo]"
                    ESQUEMAS_PROHIBIDAS.any { linea.contains(it) } ->
                        "$nombre:${idx + 1}: esquema prohibido en [$codigo]"
                    else -> null
                }
            }
        }
    }

    @Test
    fun `la app no tiene IPs literales ni esquemas con host`() {
        val ficheros = fuentes()
        assertTrue(ficheros.isNotEmpty(), "no se encontro ninguna fuente en src/main de la app")

        val culpables = ficheros.flatMap { f -> offenders(f.relativeTo(appRoot()).path, f.readText()) }
        assertEquals(
            emptyList<String>(),
            culpables,
            "FRONTERA VIOLADA: la app escribe infraestructura en el codigo: " + culpables.joinToString(" | "),
        )
    }

    @Test
    fun `el default del motor no esta escrito en la app`() {
        // El endpoint por defecto sale de `JvmRelayTransport.DEFAULT_ENDPOINT`.
        // Si apareciera escrito en la app, habria dos copias y el motor no seria
        // el UNICO dueño del wire.
        val culpable = fuentes().filter { f ->
            sinComentarios(f.readText()).contains("keymessage.xyz")
        }
        assertEquals(
            emptyList<File>(),
            culpable,
            "el endpoint por defecto se LEE del motor, no se escribe aqui: " +
                culpable.joinToString { it.name },
        )
    }

    @Test
    fun `control negativo el detector ve una IP literal`() {
        val fuga = """
            package com.example.keymessage.net
            object Legacy {
                val relay = "ws://13.140.155.230:3001/ws"
            }
        """.trimIndent()
        val visto = offenders("Legacy.kt", fuga)
        assertTrue(visto.isNotEmpty(), "el detector tiene que ver la muestra sintetica")
        assertTrue(visto.any { it.contains("IP literal") }, "y decir QUE vio: $visto")
    }

    @Test
    fun `control negativo el detector ve stun y turn`() {
        val muestra = sinComentarios(
            """
            package com.example.keymessage.ui
            class Ice {
                val stun = "stun:stun.l.google.com:19302"
                val turn = "turn:1.2.3.4:3478"
                val ws = "ws://relay.invalid/km"
            }
            """.trimIndent(),
        )
        val visto = offenders("Ice.kt", muestra)
        assertEquals(3, visto.size, "debio ver los tres esquemas: $visto")
    }

    @Test
    fun `control positivo el detector no inventa y no lee comentarios`() {
        val limpio = """
            package com.example.keymessage.config
            // El relé por defecto era ws://10.0.2.2:8080/ws en el cliente legacy.
            /* stun:stun.ejemplo.invalid:3478 tambien se documento aqui */
            class Config {
                val clave = "km_relay_ws_url"
                val version = 36
            }
        """.trimIndent()
        assertEquals(
            emptyList<String>(),
            offenders("Config.kt", limpio),
            "una mencion en prosa no es infraestructura incrustada",
        )
        assertTrue(limpio.contains("10.0.2.2"), "pretesto: la muestra SI contiene las dos menciones, en comentarios")
    }
}