package com.km.desktop.ui

import java.util.Base64

/**
 * Portapapeles por OSC 52: se ESCRIBE la secuencia, no se invoca un binario.
 *
 * ## POR QUE OSC 52 Y NO `xclip` / `wl-copy`
 *
 * Porque un `Runtime.exec` a un binario del escritorio es una dependencia no
 * declarada, no existe en Windows y puede no existir en un servidor sin X. La
 * secuencia OSC 52 la entiende el TERMINAL (y, si no la entiende, la ignora sin
 * romper nada). Es la unica via que funciona igual en Linux, macOS y Windows
 * con un emulador moderno.
 *
 * ## LO QUE NO ES
 *
 * No es confirmable: ni la secuencia tiene respuesta, ni el terminal esta
 * obligado a hacer caso. Por eso el efecto que copia la frase lleva `warn` y la
 * pantalla SIEMPRE ofrece guardar `.km` como salida verificable.
 *
 * ## EL LIMITE
 *
 * Muchos terminales recortan el portapapeles a ~100 KiB de base64. Se comprueba
 * ANTES de emitir, para que un portador grande falle con un codigo en vez de
 * dejar a medias una secuencia que el terminal trunca en silencio.
 */
object Osc52 {

    /** Tope de seguridad del payload base64 (100 KiB, el comun de los terminales). */
    const val MAX_BASE64: Int = 100 * 1024

    /**
     * La secuencia OSC 52 para copiar [text], o `null` si excede [MAX_BASE64].
     *
     * Forma: `ESC ] 52 ; c ; <base64> BEL`. La seleccion `c` es el portapapeles
     * de la aplicacion, que es donde pega el usuario.
     */
    fun sequence(text: String): String? {
        val encoded = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        if (encoded.length > MAX_BASE64) return null
        return "\u001b]52;c;$encoded\u0007"
    }

    /**
     * `false`, siempre.
     *
     * Existe como constante con nombre para que la UI no escriba una suposicion
     * de confirmacion: no hay forma de saber si el terminal copio.
     */
    fun isConfirmable(): Boolean = false
}
