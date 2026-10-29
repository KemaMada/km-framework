package com.example.keymessage.util

import android.util.Log
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 4.1 — El contrato del sumidero: un TAG, el canal entre corchetes y ningun
 * canal inventado.
 *
 * ## POR QUE `AndroidLogSink` SE PRUEBA CON UN ESCRITOR INYECTADO
 *
 * Porque `android.util.Log` en un test de JVM es un STUB que devuelve 0 sin
 * registrar nada. Inyectar el escritor no es sustituir el sumidero por un doble:
 * es el MISMO codigo que en produccion llama a `Log.println`, con la llamada
 * observable. Lo que queda fuera de aqui, y se declara, es que `logcat` muestre
 * de verdad lo que se le pasa: eso es la etapa ADB.
 */
class LogSinkContractTest {

    private data class Linea(val priority: Int, val tag: String, val message: String)

    private val emitidas = mutableListOf<Linea>()

    @Before
    fun setUp() {
        emitidas.clear()
        KMLog.clear()
        KMLog.install(AndroidLogSink { priority, tag, message -> emitidas += Linea(priority, tag, message) })
    }

    @Test
    fun `cada evento llega con el TAG unico y el canal entre corchetes`() {
        KMLog.event("[CONFIG]", "endpoints=[wss://relay.invalid/km] origen=DEFAULT")

        assertEquals(1, emitidas.size, "un evento, una linea")
        val linea = emitidas.single()
        assertEquals("KeyMessage", linea.tag, "el TAG es unico y estable: es lo que filtra `adb logcat -s KeyMessage`")
        assertTrue(linea.message.contains("[CONFIG]"), "el canal va entre corchetes: ${linea.message}")
        assertEquals(Log.INFO, linea.priority, "y el nivel por defecto es INFO")
    }

    @Test
    fun `un canal desconocido se RECHAZA`() {
        val fallo = runCatching { KMLog.event("[LOGIN]", "esto no es uno de los declarados") }.exceptionOrNull()

        assertTrue(
            fallo is IllegalArgumentException,
            "un canal mal escrito es un bug de la app, no un aviso: ${fallo?.message}",
        )
        assertTrue(emitidas.isEmpty(), "y no llega nada al sumidero")
    }

    @Test
    fun `el nivel se traduce a la prioridad de Android`() {
        KMLog.event("[RELAY]", "conectado", KMLog.Level.DEBUG)
        KMLog.event("[RELAY]", "fallo", KMLog.Level.WARN)
        KMLog.event("[BOOTSTRAP_ALL_FAILED]", "probados=[]", KMLog.Level.ERROR)

        assertEquals(
            listOf(Log.DEBUG, Log.WARN, Log.ERROR),
            emitidas.map { it.priority },
            "cada nivel llega como la prioridad que android.util.Log entiende",
        )
    }

    @Test
    fun `los diez canales se aceptan`() {
        KMLog.CHANNELS.forEachIndexed { i, canal -> KMLog.event(canal, "evento $i") }

        assertEquals(KMLog.CHANNELS.size, emitidas.size, "los diez canales emiten")
        KMLog.CHANNELS.forEachIndexed { i, canal ->
            val linea = emitidas[i]
            assertTrue(linea.message.contains(canal), "la linea $i lleva su canal: " + linea.message)
        }
    }

    @Test
    fun `el buffer de diagnostico conserva las lineas y esta acotado`() {
        repeat(KMLog.MAX_BUFFERED_LINES + 25) { i -> KMLog.event("[SEND]", "mensaje numero $i") }

        val lineas = KMLog.lines()
        assertEquals(KMLog.MAX_BUFFERED_LINES, lineas.size, "el buffer esta acotado: grows sin limite en un proceso largo")
        assertTrue(
            lineas.last().contains("mensaje numero ${KMLog.MAX_BUFFERED_LINES + 24}"),
            "y lo que se conserva es lo mas reciente",
        )
    }

    @Test
    fun `los identificadores se truncan`() {
        KMLog.event("[SEND]", "msg=${KMLog.truncar("0123456789abcdef0123456789abcdef")}")

        val esperada = "0123456789ab..."
        assertTrue(
            emitidas.single().message.contains(esperada),
            "un identificador completo no hace falta para correlacionar: ${emitidas.single().message}",
        )
    }

    @Test
    fun `control el buffer de diagnostico y el sumideron ven LO MISMO`() {
        KMLog.event("[AUTH]", "AUTH_OK dialecto=KM-WIRE-RELAY")

        assertEquals(
            emitidas.single().message,
            KMLog.lines().single(),
            "lo que se ve en la pantalla de Diagnostico es exactamente lo que va a logcat",
        )
    }
}