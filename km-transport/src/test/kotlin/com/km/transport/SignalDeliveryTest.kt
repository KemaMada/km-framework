package com.km.transport

import com.km.model.MessageId
import com.km.model.RelayErrorCode
import com.km.model.RelayErrorNotice
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * La politica de reintentos de una `SIGNAL`.
 *
 * ## POR QUE NADA DE ESTO TOCA LA RED
 *
 * Porque el reloj, el dormir y la respuesta del rele son inyectados. Un test
 * que esperase 4,4 s de verdad para comprobar un backoff seria un test que no
 * se ejecuta, y un backoff que nadie mide es un backoff que nadie sabe.
 */
class SignalDeliveryTest {

    /** Un `RELAY_ERROR` completo, como el que decodifica el motor. */
    private fun aviso(code: RelayErrorCode, detalle: String? = null): RelayErrorNotice =
        RelayErrorNotice(
            messageId = MessageId(UUID.randomUUID()),
            timestamp = 0L,
            errorCode = code,
            errorMessage = detalle,
        )

    /**
     * El rele falso.
     *
     * [guion] es la lista de veredictos: uno por envio. `null` significa "el
     * rele no contesto nada", que es el caso de exito.
     */
    private class FakeSink(
        private val guion: List<RelayErrorNotice?>,
        private val conectado: Boolean = true,
    ) : SignalSink {
        val enviados = AtomicInteger(0)
        val esperas = mutableListOf<Long>()

        override fun isConnected(): Boolean = conectado

        override fun send(payload: ByteArray): Boolean {
            enviados.incrementAndGet()
            return true
        }

        override fun awaitVerdict(timeoutMs: Long): RelayErrorNotice? {
            esperas += timeoutMs
            val i = enviados.get() - 1
            return if (i < guion.size) guion[i] else guion.lastOrNull()
        }
    }

    @Test
    fun `sin error del rele la senal se da por ENTREGADA`() {
        val sink = FakeSink(listOf(null))
        val resultado = SignalDelivery(sink, sleep = {}).deliver(byteArrayOf(1))

        assertIs<SignalOutcome.Delivered>(resultado)
        assertEquals(1, (resultado as SignalOutcome.Delivered).attempts)
        assertEquals(1, sink.enviados.get(), "un envio basta cuando el rele no dice nada")
    }

    @Test
    fun `PEER_NOT_FOUND se REINTENTA y al agotarse da PEER_OFFLINE`() {
        val sink = FakeSink(List(8) { aviso(RelayErrorCode.PEER_NOT_FOUND) })
        val dormidas = mutableListOf<Long>()
        val resultado = SignalDelivery(sink, sleep = { dormidas += it }).deliver(byteArrayOf(1))

        assertIs<SignalOutcome.Failed>(resultado)
        val fallo = resultado as SignalOutcome.Failed
        assertEquals(SignalDelivery.CODE_PEER_OFFLINE, fallo.code)
        assertEquals(4, fallo.attempts, "cuatro intentos: uno mas tres reintentos")
        assertEquals(4, sink.enviados.get())
        assertEquals(listOf(400L, 800L, 1600L), dormidas, "el backoff es 400/800/1600")
    }

    @Test
    fun `sin conexion NO se reintenta y el codigo es NOT_CONNECTED`() {
        val sink = FakeSink(listOf(null), conectado = false)
        val resultado = SignalDelivery(sink, sleep = {}).deliver(byteArrayOf(1))

        assertIs<SignalOutcome.Failed>(resultado)
        assertEquals(SignalDelivery.CODE_NOT_CONNECTED, (resultado as SignalOutcome.Failed).code)
        assertEquals(0, sink.enviados.get(), "sin socket no se escribe nada")
    }

    @Test
    fun `RELAY_NOT_AUTHORIZED es NOT_CONNECTED y NUNCA REFUSED`() {
        // D2/TS-4. El aviso real del caso HONOR (sesion expirada a los ~10 min):
        // la sesion esta muerta y el transporte ya la esta renegociando. Para
        // el llamante es "no habia sesion", no "el par rechazo el intercambio".
        // REFUSED haria que la UI contara un rechazo de negocio que nunca
        // ocurrio.
        val sink = FakeSink(
            List(8) { aviso(RelayErrorCode.RELAY_NOT_AUTHORIZED, "sesion de 1e9a73f expirada") },
        )
        val dormidas = mutableListOf<Long>()
        val resultado = SignalDelivery(sink, sleep = { dormidas += it }).deliver(byteArrayOf(1))

        assertIs<SignalOutcome.Failed>(resultado)
        val fallo = resultado as SignalOutcome.Failed
        assertEquals(SignalDelivery.CODE_NOT_CONNECTED, fallo.code)
        assertNotEquals(
            SignalDelivery.CODE_REFUSED,
            fallo.code,
            "un RELAY_NOT_AUTHORIZED no es un rechazo del intercambio",
        )
        assertEquals(1, fallo.attempts, "sin sesion util no se reintenta en el acto")
        assertEquals(1, sink.enviados.get())
        assertTrue(dormidas.isEmpty(), "y no se duerme entre intentos")
        assertTrue(
            fallo.detail.orEmpty().contains("expirada"),
            "el mensaje del aviso se conserva como detalle: ${fallo.detail}",
        )
    }

    @Test
    fun `el resto de errores del rele sigue SIN cambiar de codigo`() {
        // Control de no-regresion de TS-4: solo cambia la proyeccion de
        // RELAY_NOT_AUTHORIZED. PEER_NOT_FOUND sigue reintentando y cualquier
        // otro error sigue dando FAILED.
        val presencia = SignalDelivery(FakeSink(listOf(aviso(RelayErrorCode.PEER_NOT_FOUND))), sleep = {})
            .deliver(byteArrayOf(1))
        assertEquals(SignalDelivery.CODE_PEER_OFFLINE, (presencia as SignalOutcome.Failed).code)
        val otro = SignalDelivery(FakeSink(listOf(aviso(RelayErrorCode.STORAGE_FULL))), sleep = {})
            .deliver(byteArrayOf(1))
        assertEquals(SignalDelivery.CODE_FAILED, (otro as SignalOutcome.Failed).code)
    }

    @Test
    fun `cualquier otro error del rele da FAILED`() {
        val sink = FakeSink(List(8) { aviso(RelayErrorCode.RATE_LIMITED) })
        val resultado = SignalDelivery(sink, sleep = {}).deliver(byteArrayOf(1))

        assertIs<SignalOutcome.Failed>(resultado)
        assertEquals(SignalDelivery.CODE_FAILED, (resultado as SignalOutcome.Failed).code)
        assertEquals(1, sink.enviados.get(), "un error que no es de presencia no se reintenta")
    }

    @Test
    fun `un PEER_NOT_FOUND seguido de silencio da por ENTREGADA`() {
        // El caso real: el par no estaba, se reintenta, y en el segundo intento
        // el rele lo rutea. La senal sale con el segundo, no con el primero.
        val sink = FakeSink(listOf(aviso(RelayErrorCode.PEER_NOT_FOUND), null))
        val resultado = SignalDelivery(sink, sleep = {}).deliver(byteArrayOf(1))

        assertIs<SignalOutcome.Delivered>(resultado)
        assertEquals(2, (resultado as SignalOutcome.Delivered).attempts)
    }

    @Test
    fun `la ventana de veredicto son 400 ms en cada intento`() {
        val sink = FakeSink(List(8) { aviso(RelayErrorCode.PEER_NOT_FOUND) })
        SignalDelivery(sink, sleep = {}).deliver(byteArrayOf(1))
        assertEquals(listOf(400L, 400L, 400L, 400L), sink.esperas, "400 ms por intento")
    }

    @Test
    fun `la expiry es terminal para UNA senal y el segundo deliver empieza de cero`() {
        // El contrato de TS-R2: `deliver` no cambia. La expiry es terminal para
        // la senal EN VUELO, y una segunda llamada es una senal NUEVA con su
        // propia ventana de veredicto y su propio intento 1. La politica de
        // "esperar la renegociacion" vive en la app, no aqui.
        val sink = FakeSink(
            listOf(
                aviso(RelayErrorCode.RELAY_NOT_AUTHORIZED, "sesion expirada"),
                null,
            ),
        )
        val delivery = SignalDelivery(sink, sleep = {})

        val primero = delivery.deliver(byteArrayOf(1))
        assertIs<SignalOutcome.Failed>(primero)
        assertEquals(SignalDelivery.CODE_NOT_CONNECTED, (primero as SignalOutcome.Failed).code)
        assertEquals(1, primero.attempts, "terminal en el primer intento")

        val segundo = delivery.deliver(byteArrayOf(1))
        assertIs<SignalOutcome.Delivered>(segundo)
        assertEquals(1, (segundo as SignalOutcome.Delivered).attempts, "la segunda senal empieza de cero")
        assertEquals(2, sink.enviados.get(), "un envio por senal, ni uno mas")
    }

    @Test
    fun `solo hay UNA senal en vuelo`() {
        // `RELAY_ERROR` no trae `originalMessageId`: el unico criterio para
        // atribuirlo a una senal concreta es que no haya dos. Este test mide
        // eso: nunca hay dos envios simultaneos.
        val enVuelo = AtomicInteger(0)
        val maximo = AtomicInteger(0)
        val sink = object : SignalSink {
            override fun isConnected(): Boolean = true
            override fun send(payload: ByteArray): Boolean {
                val actual = enVuelo.incrementAndGet()
                maximo.updateAndGet { m -> maxOf(m, actual) }
                Thread.sleep(5)
                enVuelo.decrementAndGet()
                return true
            }
            override fun awaitVerdict(timeoutMs: Long): RelayErrorNotice =
                aviso(RelayErrorCode.PEER_NOT_FOUND)
        }

        val delivery = SignalDelivery(sink, sleep = {})
        val hilos = (1..4).map {
            Thread { delivery.deliver(byteArrayOf(1)) }
        }
        // `start()` ANTES de `join()`: un hilo que no arranca muere al instante
        // y `join()` devuelve sin esperar a nada. Sin esta linea, el test
        // midiria CERO envios y pasaria sin comprobar nada.
        hilos.forEach { it.start() }
        hilos.forEach { it.join() }

        assertEquals(1, maximo.get(), "nunca puede haber dos senales en vuelo a la vez")
        assertTrue(maximo.get() >= 1, "hubo al menos un envio: $maximo")
    }
}
