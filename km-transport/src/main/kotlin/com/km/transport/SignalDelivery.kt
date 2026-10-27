package com.km.transport

import com.km.model.IdentityId
import com.km.model.RelayErrorCode
import com.km.model.RelayErrorNotice

/**
 * El veredicto de un envio de senalizacion.
 *
 * ## POR QUE UN TIPO Y NO UN `Boolean`
 *
 * Porque "no se pudo" tiene cuatro finales DISTINTOS, y el llamante tiene que
 * poder distinguirlos para no mentirle al usuario:
 *
 * - [Delivered]: el rele acepto y ruteo la senal.
 * - [Failed] con `CONTACT_EXCHANGE_PEER_OFFLINE`: el par no estaba en el rele y
 *   se agotaron los reintentos.
 * - [Failed] con `CONTACT_EXCHANGE_NOT_CONNECTED`: este cliente no estaba
 *   conectado. Reintentar no arregla eso.
 * - [Failed] con `CONTACT_EXCHANGE_REFUSED`: el rele rechazo la senal. Reintentar
 *   es futile.
 * - [Failed] con `CONTACT_EXCHANGE_FAILED`: cualquier otra cosa.
 *
 * Un `Boolean` mas un `String` suelto obligaria a los dos a mantener la misma
 * tabla de codigos, y es exactamente el tipo de duplicacion que diverge.
 */
sealed class SignalOutcome {

    /**
     * La senal salio y el rele la acepto.
     *
     * ## LO QUE ESTO NO AFIRMA
     *
     * Que el par la RECIBIERA. El rele no encola senales: las enruta a un par
     * presente, y si el par no esta, contesta con un `RELAY_ERROR`. Asi que
     * `Delivered` quiere decir exactamente "el rele la acepto y la enruto (o
     * nadie le contesto un error dentro de la ventana de veredicto)", y NADA
     * mas. No es un acuse de recibo, y no debe leerse como tal.
     */
    class Delivered(val attempts: Int) : SignalOutcome() {
        override fun toString(): String = "Delivered(attempts=$attempts)"
    }

    /** La senal no salio, o el rele la rechazo. [code] es ESTABLE. */
    class Failed(
        val code: String,
        val attempts: Int,
        val detail: String? = null,
    ) : SignalOutcome() {
        override fun toString(): String = "Failed(code=$code, attempts=$attempts, detail=$detail)"
    }
}

/**
 * El seam por el que [SignalDelivery] habla con el transporte.
 *
 * ## POR QUE UNA INTERFAZ Y NO EL TRANSPORTE DIRECTO
 *
 * Porque la POLITICA de reintentos se prueba sin red, sin sockets y sin
 * esperas reales: el reloj y el dormir son inyectados, y el "que contesto el
 * rele" lo decide el doble. Un test que espera 4,4 s de verdad para comprobar
 * un backoff es un test que no se ejecuta.
 */
interface SignalSink {

    /** Si el transporte esta ONLINE. */
    fun isConnected(): Boolean

    /**
     * Escribe la senal.
     *
     * @return `true` si el frame salio al cable, `false` si no.
     */
    fun send(payload: ByteArray): Boolean

    /**
     * Espera el veredicto del rele.
     *
     * @return el `RELAY_ERROR` COMPLETO que llego —codigo y mensaje—, o `null`
     *   si no llego ninguno dentro de [timeoutMs].
     *
     * Se devuelve el aviso entero y no solo el codigo (D2/TS-3) porque el
     * mensaje es el unico sitio donde hoy se puede leer POR QUE el rele dijo
     * `RELAY_NOT_AUTHORIZED`: el wire agrupa sesion expirada, senalizacion
     * desactivada, falta de `AUTH_OK` y spoofing bajo el mismo codigo. Perder el
     * mensaje al cruzar este seam dejaria el diagnostico fuera del alcance de
     * quien decide el codigo estable.
     */
    fun awaitVerdict(timeoutMs: Long): RelayErrorNotice?
}

/**
 * La politica de entrega de una `SIGNAL` con reintentos.
 *
 * ## POR QUE HACE FALTA REINTENTAR
 *
 * Porque el rele NO encola senales: si el par no esta presente en el instante
 * del envio, contesta `PEER_NOT_FOUND` y la senal se pierde. El par puede
 *arse un momento despues, y 400/800/1600 ms es lo que tarda en aparecer un
 * movil que estaba con la pantalla apagada.
 *
 * ## LA VENTANA DE VEREDICTO
 *
 * El rele no acuse la senal. El unico veredicto posible es un `RELAY_ERROR`, y
 * ese `RELAY_ERROR` no trae `originalMessageId`: no se puede correlacionar con
 * esta senal en concreto. Por eso hay UNA senal en vuelo (el candado de
 * [deliver]) y el error se atribuye a ella: duplicar el envio es idempotente
 * porque el rele enruta la senal o no la enruta, y reenviar la misma no cambia
 * el resultado.
 *
 * ## LOS CODIGOS
 *
 * - `PEER_NOT_FOUND` -> REINTENTA. Es el caso normal: el par aun no esta.
 * - `RELAY_NOT_AUTHORIZED` -> `CONTACT_EXCHANGE_NOT_CONNECTED`, y TERMINAL. El
 *   rele acaba de declarar muerta la sesion (o la senalizacion esta
 *   desactivada, o el `from` no cuadra): el transporte ya la esta
 *   renegociando, asi que para el llamante esto es "no hay sesion util", NO un
 *   rechazo del intercambio. Antes se devolvia `CONTACT_EXCHANGE_REFUSED`, que
 *   le decia al usuario que el otro lado habia rechazado algo que nunca llego a
 *   mirar. Reintentar en el acto es gastar 4,4 s contra el mismo socket muerto.
 * - cualquier otro -> TERMINAL con `CONTACT_EXCHANGE_FAILED`.
 * - sin conexion -> `CONTACT_EXCHANGE_NOT_CONNECTED`, sin reintentar: no hay
 *   socket al que escribir.
 * - reintentos agotados -> `CONTACT_EXCHANGE_PEER_OFFLINE`.
 */
class SignalDelivery(
    private val sink: SignalSink,
    /**
     * El backoff entre intentos, en milisegundos.
     *
     * Con los valores por defecto son 400, 800 y 1600 ms: cuatro intentos y tres
     * esperas suman 4,4 s con la ventana de veredicto. Es un tope CORTO a
     * proposito: el usuario esta mirando la pantalla, y un minuto de espera sin
     * explicacion es un minuto de silencio.
     */
    private val backoffMs: List<Long> = listOf(400L, 800L, 1600L),
    /** Cuanto se espera el veredicto del rele tras cada envio. */
    private val verdictWindowMs: Long = 400L,
    /** El dormir, inyectado: los tests NO esperan de verdad. */
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {

    /**
     * El candado de la senal en vuelo.
     *
     * Reintentar en paralelo abriria dos ventanas de veredicto simultaneas, y
     * un `RELAY_ERROR` no se podria atribuir a ninguna. Con este candado hay
     * COMO MUCHO una senal en vuelo, que es la precondicion de la atribucion.
     */
    private val enVuelo = java.util.concurrent.locks.ReentrantLock()

    /**
     * Envia [payload] esperando a que el rele la acepte.
     *
     * @return [SignalOutcome.Delivered] si el rele no la rechazo dentro de la
     *   ventana de veredicto, o [SignalOutcome.Failed] con el codigo estable del
     *   motivo.
     */
    fun deliver(payload: ByteArray): SignalOutcome {
        enVuelo.lock()
        try {
            if (!sink.isConnected()) {
                return SignalOutcome.Failed(CODE_NOT_CONNECTED, 0, "el transporte no esta ONLINE")
            }
            var intento = 0
            while (intento < backoffMs.size + 1) {
                if (!sink.send(payload)) {
                    return SignalOutcome.Failed(CODE_FAILED, intento + 1, "la senal no salio al cable")
                }
                intento++
                when (val veredicto = sink.awaitVerdict(verdictWindowMs)) {
                    null -> return SignalOutcome.Delivered(intento)
                    is RelayErrorNotice -> when (veredicto.errorCode) {
                        RelayErrorCode.PEER_NOT_FOUND -> {
                            // El par no esta: es el caso reintentable.
                            if (intento > backoffMs.size) break
                            sleep(backoffMs[intento - 1])
                        }
                        RelayErrorCode.RELAY_NOT_AUTHORIZED -> {
                            // D2/TS-4: la sesion del rele esta muerta y el
                            // transporte ya la suelta y renegocia. Para quien
                            // llama esto es "no habia sesion", no "el par
                            // rechazo el intercambio". El mensaje del aviso se
                            // conserva en el detalle: es el unico sitio donde
                            // consta la causa que el wire hoy no distingue.
                            return SignalOutcome.Failed(
                                CODE_NOT_CONNECTED,
                                intento,
                                "sesion del rele no autorizada; el transporte renegocia: ${veredicto.errorMessage ?: veredicto.errorCode}",
                            )
                        }
                        else -> {
                            return SignalOutcome.Failed(
                                CODE_FAILED,
                                intento,
                                "el rele respondio ${veredicto.errorCode}",
                            )
                        }
                    }
                }
            }
            return SignalOutcome.Failed(CODE_PEER_OFFLINE, intento, "el par no estaba en el rele")
        } finally {
            enVuelo.unlock()
        }
    }

    companion object {
        /** El par no estaba en el rele y se agotaron los reintentos. */
        const val CODE_PEER_OFFLINE: String = "CONTACT_EXCHANGE_PEER_OFFLINE"

        /** Este cliente no estaba conectado: no hay socket al que escribir. */
        const val CODE_NOT_CONNECTED: String = "CONTACT_EXCHANGE_NOT_CONNECTED"

        /** El rele rechazo la senal. Reintentar es futile. */
        const val CODE_REFUSED: String = "CONTACT_EXCHANGE_REFUSED"

        /** Cualquier otro fallo. */
        const val CODE_FAILED: String = "CONTACT_EXCHANGE_FAILED"
    }
}

/**
 * El [SignalSink] que habla con el motor de verdad.
 *
 * ## POR QUE VACIA LA COLA DE ERRORES ANTES DE ENVIAR
 *
 * Porque el `RELAY_ERROR` no trae `originalMessageId`: no se puede correlacionar
 * con una senal concreta, y el unico criterio disponible es TEMPORAL. Un error
 * que llego hace un minuto no es el veredicto de la senal de ahora, asi que la
 * cola se vacia justo antes de escribir. Sin esto, un `PEER_NOT_FOUND` de un
 * envio anterior rechazaria el envio nuevo que si podia deliverarse.
 */
class RelaySignalSink(
    private val transport: JvmRelayTransport,
    private val to: IdentityId,
    private val kind: String = RelayDataCodec.KIND_CONTACT_EXCHANGE,
) : SignalSink {

    override fun isConnected(): Boolean = transport.isOnline()

    override fun send(payload: ByteArray): Boolean {
        transport.drainRelayErrors()
        return transport.sendSignal(to, kind, payload).isSuccess
    }

    /**
     * Devuelve el aviso COMPLETO, no solo el codigo (D2/TS-3).
     *
     * El `RELAY_ERROR` ya se decodifica entero en el motor; descartar el
     * mensaje aqui era la unica razon por la que `SignalDelivery` no podia
     * distinguir una sesion expirada de un rechazo de negocio.
     */
    override fun awaitVerdict(timeoutMs: Long): RelayErrorNotice? =
        transport.awaitRelayError(timeoutMs)
}
