package com.example.keymessage.ui

import com.example.keymessage.build.NoFrameworkReimplementationTest
import com.example.keymessage.ui.insets.MecanismoInset
import com.example.keymessage.ui.insets.mecanismoInset
import com.example.keymessage.ui.nav.Pantalla
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * R1 / R2 — El inset inferior tiene UN SOLO dueno por pantalla.
 *
 * ## EL DEFECTO QUE VIGILA
 *
 * La raiz consume `safeDrawing` solo Top+Horizontal y NINGUNA pantalla reservaba
 * la parte baja: NUEVO / COMPARTIR QR / CONFIG quedaban debajo de la barra de
 * navegacion en API28 con 3 botones. El arreglo es por pantalla (D1/D2), y el
 * riesgo contrario es el doble hueco: sumar el inset donde ya lo consume el
 * compositor del chat.
 *
 * ## POR QUE ESTRUCTURAL
 *
 * No hay `ComposeTestRule`: el layout no se mide en JVM. Aqui se cierra la
 * FORMA (quien reserva y cuantas veces); el comportamiento tactil se confirma
 * por ADB y se reporta en `apply-progress`.
 *
 * ## CONTROL NEGATIVO
 *
 * [consumidores] cuenta ocurrencias y se alimenta con muestras con 0 y con 2:
 * las dos tienen que salir del invariante "exactamente 1".
 */
class InsetInferiorGuardTest {

    companion object {

        fun appRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                if (File(dir, "src/main").isDirectory && File(dir, "build.gradle.kts").isFile) return dir
                dir = dir.parentFile
            }
            error("No se encontro el modulo app desde user.dir=${System.getProperty("user.dir")}")
        }

        fun fuente(relativa: String): String {
            val fichero = File(appRoot(), relativa)
            assertTrue(fichero.isFile, "no se encontro $relativa")
            return NoFrameworkReimplementationTest.sinComentarios(fichero.readText())
        }

        /** Cuantos consumidores de `navigationBarsPadding` hay en una fuente. */
        fun consumidores(texto: String): Int =
            Regex(Regex.escape(".navigationBarsPadding()")).findAll(texto).count()
    }

    private val main = "src/main/java/com/example/keymessage/ui/MainActivity.kt"
    private val mainScreen = "src/main/java/com/example/keymessage/ui/screens/MainScreen.kt"
    private val shareQr = "src/main/java/com/example/keymessage/ui/screens/ShareQrScreen.kt"
    private val recovery = "src/main/java/com/example/keymessage/ui/screens/RecoveryPhraseScreen.kt"
    private val chat = "src/main/java/com/example/keymessage/ui/screens/ChatScreen.kt"

    // ===================================================================
    // Control negativo
    // ===================================================================

    @Test
    fun `control negativo el detector VE cero y dos consumidores`() {
        val sinNadie = "Column(modifier = Modifier.fillMaxSize()) { }"
        val doble = """
            Row(modifier = Modifier.navigationBarsPadding().navigationBarsPadding())
        """.trimIndent()

        assertEquals(0, consumidores(sinNadie), "sin dueno se ve 0")
        assertEquals(2, consumidores(doble), "con doble padding se ve 2")
        assertNotEquals(1, consumidores(sinNadie))
        assertNotEquals(1, consumidores(doble))
    }

    // ===================================================================
    // Politica pura
    // ===================================================================

    @Test
    fun `el mapping de mecanismos es exhaustivo y el del diseno`() {
        Pantalla.entries.forEach { pantalla ->
            // Sin excepcion: toda ruta tiene un mecanismo declarado.
            mecanismoInset(pantalla)
        }

        assertEquals(MecanismoInset.FOOTER_FIJO, mecanismoInset(Pantalla.INICIO))
        assertEquals(MecanismoInset.CONTENEDOR_FIJO, mecanismoInset(Pantalla.DIAGNOSTICO))
        assertEquals(MecanismoInset.FOOTER_FIJO, mecanismoInset(Pantalla.COMPARTIR))
        assertEquals(MecanismoInset.CONTENEDOR_SCROLL, mecanismoInset(Pantalla.FRASE))
        assertEquals(MecanismoInset.CONTENEDOR_SCROLL, mecanismoInset(Pantalla.RESTAURAR))
        assertEquals(MecanismoInset.COMPOSITOR_IME, mecanismoInset(Pantalla.CONVERSACION))

        // R1 no enumera IMPORTAR: queda fuera de alcance y se dice.
        assertEquals(MecanismoInset.NINGUNO, mecanismoInset(Pantalla.IMPORTAR))
    }

    @Test
    fun `control negativo IMPORTAR no se cuela como consumidor`() {
        assertNotEquals(
            MecanismoInset.FOOTER_FIJO,
            mecanismoInset(Pantalla.IMPORTAR),
            "IMPORTAR no esta en R1: si alguien lo mapea, este guard lo dice",
        )
    }

    // ===================================================================
    // Un solo consumidor por pantalla
    // ===================================================================

    @Test
    fun `HOME reserva el inset en su footer y en el detalle`() {
        assertEquals(
            2,
            consumidores(fuente(mainScreen)),
            "INICIO tiene DOS consumidores de pantalla: BottomBar (FOOTER_FIJO) y DetalleContacto (OVERLAY_INTERNO)",
        )
    }

    @Test
    fun `DIAGNOSTICO reserva el inset una sola vez en su contenedor`() {
        val main = fuente(main)

        assertEquals(
            1,
            consumidores(main),
            "DIAGNOSTICO es CONTENEDOR_FIJO: un unico `navigationBarsPadding()` en MainActivity",
        )
        assertTrue(
            Regex("navigationBarsPadding\\(\\)\\s*\\n\\s*\\.padding\\(12\\.dp\\)").containsMatchIn(main),
            "el inset va ANTES del padding de contenido de DIAGNOSTICO",
        )
    }

    @Test
    fun `COMPARTIR tiene cuerpo que cede espacio y footer fijo`() {
        val fuente = fuente(shareQr)

        assertEquals(1, consumidores(fuente), "COMPARTIR: un solo dueno")
        assertTrue(
            fuente.contains(".verticalScroll(rememberScrollState())"),
            "el cuerpo scrollea por dentro para que el QR y los botones quepan",
        )
        assertTrue(fuente.contains("weight(1f)"), "y CEDE espacio en vez de empujar al footer")
    }

    @Test
    fun `FRASE y RESTAURAR reservan ANTES del scroll`() {
        val fuente = fuente(recovery)

        assertEquals(2, consumidores(fuente), "las dos pantallas de recuperacion, una vez cada una")
        assertEquals(
            2,
            Regex("navigationBarsPadding\\(\\)\\s*\\n\\s*\\.verticalScroll\\(").findAll(fuente).count(),
            "en las dos, el inset queda FUERA del viewport del scroll",
        )
    }

    @Test
    fun `CHAT no gana un segundo consumidor`() {
        val chat = fuente(chat)

        assertEquals(
            1,
            consumidores(chat),
            "CONVERSACION ya era COMPOSITOR_IME: un unico `navigationBarsPadding()`, " +
                "junto a `imePadding` (sin doble hueco)",
        )
        assertTrue(chat.contains(".imePadding()"), "el compositor sigue reservando el IME")
    }

    // ===================================================================
    // La raiz sigue sin consumir el bottom
    // ===================================================================

    @Test
    fun `la raiz consume solo Top y Horizontal`() {
        val main = fuente(main)

        assertTrue(
            main.contains("WindowInsetsSides.Top") && main.contains("WindowInsetsSides.Horizontal"),
            "la raiz reserva status bar y lados",
        )
        assertTrue(
            !main.contains("WindowInsetsSides.Bottom") && !main.contains("WindowInsetsSides.Vertical"),
            "y NO la parte baja: si la reservara, el chat tendria doble hueco",
        )
    }
}
