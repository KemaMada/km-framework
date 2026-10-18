package com.km.desktop.util

import java.io.PrintStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * LOS NIVELES, con su equivalencia para un sumidero real.
 *
 * Es un enum y no un entero porque un sumidero tiene que poder TRADUCIR el
 * nivel a lo que su destino espere —una prioridad de Android, un nivel de
 * syslog, una fila de un fichero— y eso es una decision de cada sumidero, no
 * del nucleo.
 */
enum class DiagLevel(val syslog: Int) {
    DEBUG(7),
    INFO(6),
    WARN(4),
    ERROR(3),
}

/**
 * Un UNICO sumidero de diagnostico, inyectable, y con TAG estable.
 *
 * ## POR QUE EXISTE
 *
 * `km-desktop | tee log` es la forma de que un operador vea lo que pasa dentro
 * del cliente sin un dispositivo. Con el sumidero embebido en cada llamada, esa
 * forma no tendria nada que mirar. Aqui el sumidero es una INTERFAZ: en
 * produccion escribe por stderr, y en un test de JVM se inyecta uno que graba —
 * que es lo que permite afirmar el contrato sin ejecutar el binario.
 *
 * ## POR QUE STDERR Y NO STDOUT
 *
 * Porque stdout es el PROMPT de la TUI. Una linea de log en stdout se
 * intercala con el prompt y con lo que el usuario escribe, y en un pipe deja el
 * log inservible. Es la misma razon por la que `slf4j-simple` esta en
 * `runtimeOnly` con su salida a stderr.
 *
 * ## QUE NO SE ESCRIBE JAMAS
 *
 * Ni payload, ni texto plano, ni claves privadas, ni claves de sesion, ni
 * material del ratchet. Los identificadores van TRUNCADOS ([DiagLog.truncar]),
 * y las funciones que registran mensajes NO reciben el payload como parametro:
 * la garantia no es una regla de estilo, es que la API no tiene por donde
 * filtrarlo.
 */
interface DiagSink {
    fun log(level: DiagLevel, tag: String, message: String)
}

/** Sumidero que escribe en un `PrintStream`: por defecto, `System.err`. */
class StreamDiagSink(
    private val salida: PrintStream = System.err,
) : DiagSink {
    override fun log(level: DiagLevel, tag: String, message: String) {
        salida.println("[$tag] ${level.name} $message")
    }
}

/** Sumidero que graba en memoria. Lo usan los tests y la pantalla de diagnostico. */
class MemoryDiagSink : DiagSink {
    private val lineas = CopyOnWriteArrayList<String>()

    override fun log(level: DiagLevel, tag: String, message: String) {
        lineas += "${level.name}|$tag|$message"
    }

    /** Las lineas grabadas, en orden. */
    fun lines(): List<String> = lineas.toList()

    /**
     * Las lineas cuyo CANAL es [canal].
     *
     * El canal va al PRINCIPIO del mensaje, no en un campo propio: el formato
     * grabado es `nivel|TAG|mensaje`, y el mensaje empieza por `[CANAL]`. Se
     * busca ahi porque es lo que un `grep` del log real encontraria, y un sumidero
     * que hiciera aparecer el canal en un campo aparte estaria midiendo una
     * convencion que el fichero de log no tiene.
     */
    fun deCanal(canal: String): List<String> =
        lineas.filter { it.split("|").getOrNull(2)?.startsWith(canal) == true }

    fun clear() {
        lineas.clear()
    }
}

/**
 * Los ocho canales del escritorio, y el sumidero activo.
 *
 * ## LOS CANALES
 *
 * `[BOOTSTRAP] [RELAY] [AUTH] [CONTACT] [SESSION] [SEND] [RECV] [INTEGRITY]`.
 *
 * Los ocho estan DECLARADOS aqui como [CHANNELS]. Escribirlos a mano en cada
 * sitio seria una segunda fuente de verdad, y esa se desincroniza sola: nadie
 * recuerda volver a la lista cuando anade un noveno.
 *
 * ## POR QUE OCHO Y NO MAS
 *
 * Porque son los ocho momentos en los que hay algo que mirar y que no se ve en
 * la pantalla: de donde se resolvio el bootstrap, que dice el rele, si el AUTH
 * fue, si un contacto verifica, si una sesion se abre, si un envio sale o se
 * rechaza, si un mensaje entra, y si el contenido integro coincide. Un noveno
 * canal suele ser "otro log", que es como empiezan a duplicarse.
 */
object DiagLog {

    /** TAG unico: el filtro de la etapa de observabilidad. */
    const val TAG: String = "KeyMessageDesktop"

    /** Los canales, en el orden en que aparecen en la especificacion. */
    val CHANNELS: List<String> = listOf(
        "[BOOTSTRAP]",
        "[RELAY]",
        "[AUTH]",
        "[CONTACT]",
        "[SESSION]",
        "[SEND]",
        "[RECV]",
        "[INTEGRITY]",
    )

    /** Lineas que se guardan en memoria para la pantalla de diagnostico. */
    const val MAX_BUFFERED_LINES: Int = 200

    @Volatile
    private var sink: DiagSink = StreamDiagSink()

    private val buffer = CopyOnWriteArrayList<String>()

    /** Sustituye el sumidero. Lo usan el arranque y los tests. */
    fun install(newSink: DiagSink) {
        sink = newSink
    }

    /** El sumidero activo, para poder afirmar sobre el. */
    fun active(): DiagSink = sink

    /**
     * Emite un evento del canal [channel] con [message].
     *
     * @throws IllegalArgumentException si [channel] no es uno de los ocho. Es una
     *   excepcion y no un `Log.w`: un canal mal escrito es un bug del cliente, y
     *   en un fichero de log se veria como un evento mas, indistinguible.
     */
    fun event(channel: String, message: String, level: DiagLevel = DiagLevel.INFO) {
        require(CHANNELS.contains(channel)) {
            "canal desconocido: $channel; los ocho son $CHANNELS"
        }
        val linea = "$channel $message"
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
     * Una `identityId` o un `messageId` enteros no son secretos, pero tampoco
     * hacen falta: 12 hex bastan para correlacionar dos lineas de la misma
     * sesion y no dejan una clave completa en un fichero de texto.
     */
    fun truncar(id: String, n: Int = 12): String =
        if (id.length <= n) id else id.take(n) + "…"

    /** `[SEND]` — resultado de un envio. */
    fun messageSent(messageId: String, peerId: String, resultado: String, failureCode: String? = null) {
        val sufijo = if (failureCode == null) "" else " failure=$failureCode"
        event("[SEND]", "msg=${truncar(messageId)} peer=${truncar(peerId)} -> $resultado$sufijo")
    }

    /**
     * `[RECV]` — mensaje entrante.
     *
     * Mismo contrato que [messageSent] y por el mismo motivo: la API NO tiene un
     * hueco por donde colar el payload. Un `LogNoLeakTest` con marcadores lo
     * comprueba, y la razon por la que no hace falta revisar esta llamada linea
     * a linea es precisamente que no hay linea por la que filtrar.
     */
    fun messageReceived(messageId: String, from: String) {
        event("[RECV]", "msg=${truncar(messageId)} from=${truncar(from)}")
    }

    /** `[INTEGRITY]` — una comprobacion de integridad, con su veredicto. */
    fun integrity(que: String, ok: Boolean) {
        event("[INTEGRITY]", "$que -> ${if (ok) "OK" else "FALLO"}")
    }

    /** `[BOOTSTRAP]` — resolucion de destinos, sin volcar la lista entera. */
    fun bootstrap(origen: String, cuantos: Int) {
        event("[BOOTSTRAP]", "origen=$origen destinos=$cuantos")
    }
}