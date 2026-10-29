package com.example.keymessage.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 4.1 — Un UNICO sumidero de log, inyectable, y con TAG estable.
 *
 * ## POR QUE EXISTE
 *
 * `adb logcat -s KeyMessage` es la forma de que la etapa final (ADB) vea lo que
 * pasa dentro de la app. El `LogBuffer` legacy escribia en fichero y en memoria
 * y NO llegaba a logcat, de modo que esa forma de verificar no tenia nada que
 * mirar. Aqui el `LogSink` es una INTERFAZ: en Android escribe en
 * `android.util.Log` con el TAG [KMLog.TAG], y en un test de JVM se inyecta uno
 * que graba —que es lo que permite afirmar el contrato sin un dispositivo.
 *
 * ## QUE NO SE ESCRIBE JAMAS
 *
 * Ni payload, ni texto plano, ni claves privadas, ni claves de sesion, ni
 * material del ratchet. Los identificadores que se registran van TRUNCADOS
 * ([KMLog.truncar]), y las funciones que registran mensajes
 * ([KMLog.messageSent], [KMLog.messageReceived]) no reciben el payload como
 * parametro: la garantia no es una regla de estilo, es que la API no tiene por
 * donde filtrarlo.
 */
interface LogSink {
    /**
     * Escribe una linea.
     *
     * @param level nivel, para que un sumidero real pueda filtrar.
     * @param tag siempre [KMLog.TAG] en toda la produccion de la app.
     * @param message la linea YA FORMATEADA, con el canal entre corchetes.
     */
    fun log(level: KMLog.Level, tag: String, message: String)
}

/**
 * Sumidero que escribe en `android.util.Log`.
 *
 * El escritor es inyectable y NO es un doble: es el seam por el que el mismo
 * codigo que llama a `android.util.Log` en produccion se puede observar en un
 * test de JVM, donde `android.util.Log` es un stub que devuelve 0 sin registrar
 * nada. Que el nivel se traduzca bien a la llamada de `android.util.Log` es algo
 * que se COMPRUEBA, no que se suponga.
 */
class AndroidLogSink(
    private val writer: (Int, String, String) -> Unit =
        { priority, tag, message -> Log.println(priority, tag, message) },
) : LogSink {

    override fun log(level: KMLog.Level, tag: String, message: String) {
        writer(level.androidPriority, tag, message)
    }
}

/**
 * 4.1 / 4.2 — Los diez canales de la app, y el sumidero activo.
 *
 * ## LOS CANALES
 *
 * `[CONFIG] [RELAY] [AUTH] [CONTACT] [FILE] [SIGNAL] [SESSION] [SEND] [RECV] [BOOTSTRAP_ALL_FAILED]`.
 *
 * Los diez estan DECLARADOS aqui como [CHANNELS] y `LogChannelCoverageTest`
 * comprueba que los diez se emiten desde codigo de la app y que el sumidero
 * recibe exactamente ese token. Declararlos en una lista y luego escribir
 * `"[AUTH] ..."` a mano en cada sitio seria dos fuentes de verdad, y la segunda
 * se desincroniza sola.
 */
object KMLog {

    /** TAG unico. `adb logcat -s KeyMessage` es el filtro de la etapa final. */
    const val TAG: String = "KeyMessage"

    /** Los canales, en el orden en que aparecen en la especificacion. */
    val CHANNELS: List<String> = listOf(
        "[CONFIG]",
        "[RELAY]",
        "[AUTH]",
        "[CONTACT]",
        "[FILE]",
        "[SIGNAL]",
        "[SESSION]",
        "[SEND]",
        "[RECV]",
        "[BOOTSTRAP_ALL_FAILED]",
    )

    enum class Level(val androidPriority: Int) {
        DEBUG(Log.DEBUG),
        INFO(Log.INFO),
        WARN(Log.WARN),
        ERROR(Log.ERROR),
    }

    /** Lineas que se guardan en memoria para la pantalla de diagnostico. */
    const val MAX_BUFFERED_LINES: Int = 200

    @Volatile
    private var sink: LogSink = AndroidLogSink()

    private val buffer = CopyOnWriteArrayList<String>()
    private val reloj = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Sustituye el sumidero. Lo usan la app y los tests. */
    fun install(newSink: LogSink) {
        sink = newSink
    }

    /** El sumidero activo, para poder afirmar sobre el. */
    fun active(): LogSink = sink

    /**
     * Emite un evento del canal [channel] con [message].
     *
     * @throws IllegalArgumentException si [channel] no es uno de los declarados. Es una
     *   excepcion y no un `Log.w`: un canal mal escrito es un bug de la app, y
     *   por `adb logcat` se veria como un evento mas, indistinguible.
     */
    fun event(channel: String, message: String, level: Level = Level.INFO) {
        require(CHANNELS.contains(channel)) {
            "canal desconocido: $channel; los declarados son $CHANNELS"
        }
        val linea = StringBuilder()
            .append(reloj.format(Date()))
            .append(' ')
            .append(channel)
            .append(' ')
            .append(message)
            .toString()
        buffer.add(linea)
        while (buffer.size > MAX_BUFFERED_LINES) buffer.removeAt(0)
        sink.log(level, TAG, linea)
    }

    /** Las ultimas lineas, para la pantalla de diagnostico. */
    fun lines(): List<String> = buffer.toList()

    fun clear() {
        buffer.clear()
    }

    // ------------------------------------------------------------------
    // Helpers de dominio: aqui es donde la no-fuga se vuelve ESTRUCTURAL
    // ------------------------------------------------------------------

    /**
     * Identificador para traza: los primeros [n] caracteres.
     *
     * Un `messageId` entero o una `identityId` entera no son secretos, pero
     * tampoco hacen falta: 12 hex bastan para correlacionar dos lineas de la
     * misma sesion y no dejan una clave completa en un fichero de texto.
     */
    fun truncar(id: String, n: Int = 12): String =
        if (id.length <= n) id else id.take(n) + "..."

    /**
     * `[SEND]` — resultado de un envio.
     *
     * No hay forma de pasar el payload por aqui: los parametros son el
     * identificador del mensaje, el par y el resultado. `LogNoLeakTest` lo
     * comprueba con marcadores, y la razon por la que no hace falta revisar
     * esta llamada linea a linea es que la API no tiene por donde filtrar.
     */
    fun messageSent(messageId: String, peerId: String, resultado: String, failureCode: String? = null) {
        val sufijo = if (failureCode == null) "" else " failure=$failureCode"
        event("[SEND]", "msg=${truncar(messageId)} peer=${truncar(peerId)} -> $resultado$sufijo")
    }

    /** `[RECV]` — mensaje entrante. Mismo contrato: sin payload. */
    fun messageReceived(messageId: String, from: String) {
        event("[RECV]", "msg=${truncar(messageId)} from=${truncar(from)}")
    }
}