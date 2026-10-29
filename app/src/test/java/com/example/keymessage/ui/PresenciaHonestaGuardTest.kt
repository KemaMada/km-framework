package com.example.keymessage.ui

import com.example.keymessage.build.NoFrameworkReimplementationTest
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La UI no PINTA presencia falsa.
 *
 * ## EL DEFECTO QUE VIGILA
 *
 * `Contact.isOnline` esta en `false` por defecto y `mapearContactos` NO lo
 * rellena: no existe hoy ninguna fuente de presencia en el codigo de la app. La
 * fila de contacto pintaba `[X]` cuando `isOnline` era true —y `[ ]` cuando
 * false—, de modo que la lista parecia decir "todos offline" cuando en realidad
 * decia "no lo se". Pintar `[X]` seria una mentira (spec R3.2, R10.1).
 *
 * ## QUE AFIRMA
 *
 * Que el codigo de las pantallas de contactos NO deriva `[X]` ni `ONLINE` de
 * `isOnline`. La presencia se muestra como `[?]` desconocido o se omite.
 *
 * ## POR QUE ES ESTRUCTURAL
 *
 * Porque la lista necesita un `AppContainer` con una `Activity`: no hay test de
 * JVM que la pinte (no hay `ComposeTestRule`). El comportamiento se cierra por
 * ADB, y aqui se cierra la FORMA.
 *
 * ## CONTROL NEGATIVO
 *
 * [presenciaFalsa] es una funcion PURA. Se alimenta con una muestra con literal
 * `[X]` inline (tiene que verlo) y con una muestra con `[?]` (tiene que devolver
 * vacio), para que el guard no pase por vacio.
 */
class PresenciaHonestaGuardTest {

    companion object {

        /**
         * Las fuentes de contacto MIGRADAS que vigila el guard.
         *
         * Se amplia POR FASE, igual que [NoRoundedControlsGuardTest.MIGRADOS].
         */
        val VIGILADAS: List<String> = listOf(
            "src/main/java/com/example/keymessage/ui/screens/MainScreen.kt",
            "src/main/java/com/example/keymessage/ui/screens/ChatScreen.kt",
            "src/main/java/com/example/keymessage/ui/MainActivity.kt",
        )

        /** El token de presencia falsa: la `X` en caja de un contacto "presente". */
        const val X_FALSA: String = "[X]"

        /** El token honesto: desconocido. */
        const val DESCONOCIDO: String = "[?]"

        /** Raiz del modulo `app/`. */
        fun appRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                if (File(dir, "src/main").isDirectory && File(dir, "build.gradle.kts").isFile) return dir
                dir = dir.parentFile
            }
            error("No se encontro el modulo app desde user.dir=${System.getProperty("user.dir")}")
        }

        /**
         * Las lineas de [fuente] que pintan presencia falsa.
         *
         * Se quitan los comentarios ANTES de buscar, y se exige que la linea
         * derive de `isOnline` O sea un literal de `[X]` pintado como texto de
         * fila: un literal suelto en un KDoc no cuenta.
         */
        fun presenciaFalsa(fuente: String): List<String> {
            val limpio = NoFrameworkReimplementationTest.sinComentarios(fuente).split("\n")
            return limpio.mapIndexedNotNull { idx, linea ->
                val pinta = linea.contains("\"$X_FALSA\"") || linea.contains("\"$X_FALSA ")
                val deriva = linea.contains("isOnline") && linea.contains(X_FALSA)
                if (pinta || deriva) "${idx + 1}: ${linea.trim()}" else null
            }
        }

        /**
         * F1 — las lineas que atan `P2P` a la presencia de un CONTACTO.
         *
         * Es presencia falsa por otra via: el glifo nace del transporte, y solo
         * `ConnectionState` puede alimentarlo. Se busca una linea que mencione
         * `TuiP2PBadge`/`indicadorP2P` Y derive de algo del contacto (`isOnline`,
         * `.presence`, `contact.`), no del `connectionState`.
         */
        fun p2pFalso(fuente: String): List<String> {
            val limpio = NoFrameworkReimplementationTest.sinComentarios(fuente).split("\n")
            return limpio.mapIndexedNotNull { idx, linea ->
                val nombraP2p = linea.contains("TuiP2PBadge") || linea.contains("indicadorP2P(")
                val delContacto = linea.contains("isOnline") ||
                    linea.contains(".presence") ||
                    Regex("\\bcontact[o]?\\.").containsMatchIn(linea)
                val delTransporte = linea.contains("connectionState") || linea.contains("ConnectionState")
                if (nombraP2p && delContacto && !delTransporte) "${idx + 1}: ${linea.trim()}" else null
            }
        }
    }

    @Test
    fun `las pantallas de contacto NO pintan X ni derivan presencia de isOnline`() {
        val culpables = VIGILADAS.flatMap { rel ->
            val fichero = File(appRoot(), rel)
            assertTrue(fichero.isFile, "no se encontro el fichero vigilado $rel")
            presenciaFalsa(fichero.readText()).map { "$rel -> $it" }
        }

        assertEquals(
            emptyList<String>(),
            culpables,
            "PRESENCIA FALSA: la UI pinta `[X]` o lo deriva de `isOnline`, que no tiene fuente. " +
                "Usa `[?]` desconocido: ${culpables.joinToString(" | ")}",
        )
    }

    @Test
    fun `control negativo el detector ve una X falsa inline`() {
        val conX = """
            @Composable
            private fun ContactRow(contact: Contact) {
                Text(text = if (contact.isOnline) "[X]" else "[ ]")
            }
        """.trimIndent()

        assertTrue(
            presenciaFalsa(conX).isNotEmpty(),
            "el detector VE el `[X]` derivado de `isOnline`: sin esto el guard pasaria por vacio",
        )
    }

    @Test
    fun `control negativo el desconocido honesto no es presencia falsa`() {
        val honesto = """
            @Composable
            private fun ContactRow(contact: Contact) {
                Text(text = "[?]", color = TerminalGray30)
            }
        """.trimIndent()

        assertEquals(
            emptyList<String>(),
            presenciaFalsa(honesto),
            "`[?]` es honesto: no hay fuente de presencia y la UI lo dice",
        )
    }

    @Test
    fun `control negativo una mencion en un KDoc NO es presencia falsa`() {
        val conKdoc = """
            /**
             * Antes la fila pintaba [X] cuando isOnline era true.
             */
            @Composable
            private fun ContactRow() {
                Text(text = "[?]")
            }
        """.trimIndent()

        assertEquals(
            emptyList<String>(),
            presenciaFalsa(conKdoc),
            "explicar el defecto en un KDoc no es cometerlo",
        )
    }

    // ===================================================================
    // F1 — P2P es del RELAY, no del contacto
    // ===================================================================

    @Test
    fun `NINGUNA pantalla deriva P2P de la presencia de un contacto`() {
        // La regla dura de F1: `P2P[...]` mide el TRANSPORTE. Si una pantalla
        // pasase `isOnline` (del peer) o el estado de un contacto al badge,
        // `P2P[o]` se leeria como "el contacto esta online": presencia inventada.
        val culpables = VIGILADAS.flatMap { rel ->
            val fichero = File(appRoot(), rel)
            assertTrue(fichero.isFile, "no se encontro el fichero vigilado $rel")
            p2pFalso(fichero.readText()).map { "$rel -> $it" }
        }

        assertEquals(
            emptyList<String>(),
            culpables,
            "P2P FALSO: una pantalla ata `P2P` a la presencia de un contacto. " +
                "Solo `ConnectionState` (el transporte) puede alimentarlo: ${culpables.joinToString(" | ")}",
        )
    }

    @Test
    fun `control negativo el detector ve un P2P alimentado por isOnline`() {
        val malo = """
            @Composable
            private fun Fila(contact: Contact) {
                TuiP2PBadge(estado = if (contact.isOnline) ONLINE else DISCONNECTED)
            }
        """.trimIndent()

        assertTrue(
            p2pFalso(malo).isNotEmpty(),
            "el detector VE el `P2P` derivado de `isOnline`: sin esto pasaria por vacio",
        )
    }

    @Test
    fun `control negativo un P2P alimentado por ConnectionState es honesto`() {
        val honesto = """
            @Composable
            private fun Cabecera(connectionState: ConnectionState) {
                TuiP2PBadge(estado = connectionState)
            }
        """.trimIndent()

        assertEquals(
            emptyList<String>(),
            p2pFalso(honesto),
            "el transporte es la fuente legitima del indicador",
        )
    }

    @Test
    fun `la cabecera de INICIO NO pinta la huella del peer junto al alias`() {
        // El roce de verify: la fila con la huella repetida. Desde F2 la fila de
        // contacto usa `ResumenContacto`, cuyo campo es solo `alias`, y el badge
        // `P2P` es del transporte. Se afirma que la fila NO recibe un
        // `VerifiedContact` crudo (cuya `fingerprint` acabaria pintada DOS veces).
        val fuente = NoFrameworkReimplementationTest.sinComentarios(
            File(
                appRoot(),
                "src/main/java/com/example/keymessage/ui/screens/MainScreen.kt",
            ).readText(),
        )

        val fila = fuente.lines().filter { it.contains("private fun ContactRow") }
        assertEquals(1, fila.size, "existe UNA fila de contacto")
        assertTrue(
            !fila.first().contains("VerifiedContact"),
            "la fila NO recibe el contacto crudo: usa ResumenContacto (alias UNA vez)",
        )
        assertTrue(
            !fuente.contains(".fingerprint"),
            "la pantalla de INICIO NO pinta la huella cruda: el id completo vive en el detalle `[i]`",
        )
    }
}
