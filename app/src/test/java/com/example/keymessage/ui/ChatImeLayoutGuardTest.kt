package com.example.keymessage.ui

import com.example.keymessage.build.NoFrameworkReimplementationTest
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F4 — El teclado (IME) no puede TAPAR el compositor ni el boton ENVIAR.
 *
 * ## QUE SE AFIRMA (la forma)
 *
 * Este modulo no tiene `ComposeTestRule`: no se puede pintar el layout en JVM y
 * ver que el teclado no tapa el compositor. Lo que SI se cierra en JVM es la
 * FORMA del arreglo:
 *
 *  - el manifiesto usa `adjustResize` (NO `adjustPan`, que empuja la vista y
 *    deja el compositor fuera);
 *  - la raiz consume SOLO los insets superiores y laterales (`safeDrawing.only
 *    (Top + Horizontal)`): NO consume el IME ni la parte baja, o habria doble
 *    hueco cuando el teclado sube;
 *  - el COMPOSITOR del chat SI reserva el IME (`imePadding`) y la barra de
 *    navegacion (`navigationBarsPadding`);
 *  - la lista se ancla al FONDO (`Arrangement.Bottom`).
 *
 * El comportamiento visible (el teclado sube y el compositor queda encima) se
 * confirma con captura en dispositivo, y esa captura se reporta en
 * `apply-progress`.
 */
class ChatImeLayoutGuardTest {

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
    }

    @Test
    fun `el manifiesto usa adjustResize y NO adjustPan`() {
        val manifiesto = fuente("src/main/AndroidManifest.xml")

        assertTrue(
            manifiesto.contains("adjustResize"),
            "la ventana tiene que REDIMENSIONARSE con el teclado",
        )
        assertTrue(
            !manifiesto.contains("adjustPan"),
            "`adjustPan` empuja la vista sin redimensionar: el compositor quedaria fuera",
        )
    }

    @Test
    fun `la raiz NO consume el IME ni la parte baja (sin doble hueco)`() {
        val main = fuente("src/main/java/com/example/keymessage/ui/MainActivity.kt")

        // La raiz consume SOLO top + horizontal. Si consumiera `Bottom` o
        // `WindowInsets.ime`, el compositor (que SI lo reserva) tendria doble
        // hueco cuando el teclado sube.
        assertTrue(
            main.contains("WindowInsetsSides.Top") && main.contains("WindowInsetsSides.Horizontal"),
            "la raiz consume top + horizontal",
        )
        assertTrue(
            !main.contains("WindowInsetsSides.Bottom") && !main.contains("WindowInsetsSides.Vertical"),
            "la raiz NO consume la parte baja: la reservan las barras y el compositor",
        )
    }

    @Test
    fun `el compositor del chat reserva IME y barra de navegacion`() {
        val chat = fuente("src/main/java/com/example/keymessage/ui/screens/ChatScreen.kt")

        assertTrue(chat.contains("imePadding()"), "el compositor reserva el IME: el teclado no lo tapa")
        assertTrue(
            chat.contains("navigationBarsPadding()"),
            "y tambien la barra de navegacion",
        )
    }

    @Test
    fun `la lista de mensajes se ancla al FONDO`() {
        val chat = fuente("src/main/java/com/example/keymessage/ui/screens/ChatScreen.kt")

        assertTrue(
            chat.contains("Arrangement.Bottom"),
            "al abrir una conversacion se lee de abajo arriba: la lista va anclada al fondo",
        )
    }

    @Test
    fun `el IME y el boton comparten UN solo camino de envio`() {
        val chat = fuente("src/main/java/com/example/keymessage/ui/screens/ChatScreen.kt")

        // La funcion PURA que usan los dos caminos: si el IME tuviera su propio
        // `onSend`, habria dos caminos y el doble submit volveria.
        assertTrue(
            chat.contains("textoTrasEnviar"),
            "IME y boton comparten `textoTrasEnviar` (un solo camino)",
        )
        assertTrue(chat.contains("ImeAction.Send"), "el IME ofrece Enviar")
        assertTrue(chat.contains("KeyboardActions"), "y la accion del IME esta cableada")
    }
}
