package com.km.transport.contact

/**
 * Los tres niveles de una traza de intercambio de contactos.
 *
 * No se reusa `KMLog` (vive en la app) ni SLF4J: km-transport no imprime. Quien
 * compone decide DONDE va cada linea; el modulo solo dice que pasa.
 */
enum class ContactLogLevel { INFO, WARN, ERROR }

/**
 * El sumidero de trazas del intercambio, INYECTADO.
 *
 * ## POR QUE UNA INTERFAZ Y NO UN `PrintStream`
 *
 * Porque km-transport no puede escribir en la terminal de nadie: en el
 * escritorio esa terminal la posee un solo hilo (el renderer) y un `println` de
 * un handler desde otro hilo la corrompe. El consumidor decide si la traza va a
 * un buffer de diagnostico, a un fichero o a ningun sitio.
 */
fun interface ContactLogger {
    fun log(level: ContactLogLevel, message: String)

    companion object {
        /** Sumidero que no escribe nada. Es el DEFAULT: un modulo no loguea por su cuenta. */
        val NONE: ContactLogger = ContactLogger { _, _ -> }
    }
}
