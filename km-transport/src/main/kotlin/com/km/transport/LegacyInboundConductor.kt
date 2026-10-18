package com.km.transport

import com.km.model.IdentityId
import com.km.node.LegacyInboundFrame
import com.km.node.TransportBackend

/**
 * Da de alta y de baja la recepcion de frames LEGADOS en un
 * [TransportBackend], sin tocar jamas los bytes que salen.
 *
 * ## QUE ES Y POR QUE EXISTE
 *
 * p4 introduce una recepcion con DOS caminos: el de par (`onDataReceived`, que
 * alimenta al `PeerTransportManager` y de ahi a las sesiones cifradas) y el
 * legado (`messageHandler`, el camino en claro que el escritorio usa desde el
 * principio). Un backend que tenga el compositor puesto entrega lo LEGADO por el
 * camino de par —que es lo correcto— y lo opaco tambien, con lo que el camino
 * en claro se queda sin nada.
 *
 * Este compositor es la pieza que deja REPONER ese camino sin sacar el
 * compositor del backend, que es la operacion que el escritorio necesita y que
 * no puede expresarse sin perder el cable de las sesiones.
 *
 * ## POR QUE NO ES DUENO DEL SOCKET
 *
 * Porque no lo es. No abre, no cierra, no llama a `connect()`. Solo compone y
 * restaura UN slot. La conectividad es del motor ([JvmRelayTransport.connect]),
 * y un compositor que ademas conectara seria un segundo dueno del enlace —el
 * defecto de doble fachada que esta fase quiere evitar—.
 *
 * ## POR QUE `detach()` RESTAURA Y NO SOLO RETIRA
 *
 * `eventCallbacks` es un `var` con semantica de ASIGNACION: quien puso algo
 * antes que nosotros debe recuperarlo. Un `detach()` que dejara
 * `onLegacyInboundFrame = null` trataria al que vino antes exactamente igual que
 * un `attach()` que lo hubiera pisado, y ese es el fallo silencioso que la
 * mutacion anti-clobber persigue: una baja que parece una baja y en realidad
 * es una destruccion.
 *
 * ## POR QUE [reinyectar] RECIBE EL MARCO Y NO LOS BYTES
 *
 * Porque reinyectar es una ACCION, no una conversion: la conversion —de
 * `Message` a los bytes de km-core— es del motor, que es quien tiene el codec y
 * el `encoder`. Si el compositor hiciera la conversion, tendria que conocer el
 * motor, y el test tendria que fabricar uno entero para poder observar cuatro
 * lineas de logica. Reciendo el marco, el test observa exactamente lo que se
 * quiere observar: que el camino se repone, y que se repone UNA vez.
 */
class LegacyInboundConductor(
    private val backend: TransportBackend,
    /** Repone el camino legado: codifica el sobre y se lo entrega al motor. */
    private val reinyectar: (LegacyInboundFrame) -> Unit,
) {

    /** Lo que habia en el slot ANTES del primer `attach()`. */
    private var previo: ((LegacyInboundFrame, IdentityId) -> Unit)? = null

    /** Marcos entregados por el camino legado desde el `attach()` actual. */
    private var entregados = 0

    @Volatile
    private var dadoDeAlta = false

    /**
     * Da de alta el camino legado, encadenando lo que hubiera.
     *
     * IDEMPOTENTE por construccion: un segundo `attach()` sin `detach()` en medio
     * no hace nada, y por eso dos `attach()` seguidos no duplican la entrega.
     * Sin esa guarda, el `detach()` devolveria al `previo` del SEGUNDO attach —
     * que ya seria nuestro propio lambda— y el camino quedaria instalado para
     * siempre con dos suscripciones vivas.
     */
    fun attach() {
        if (dadoDeAlta) return
        dadoDeAlta = true
        val anterior = backend.eventCallbacks.onLegacyInboundFrame
        previo = anterior
        backend.eventCallbacks = backend.eventCallbacks.copy(
            onLegacyInboundFrame = { frame, peerId ->
                // Primero el que ya estaba. Es la MISMA regla que el cable de
                // B2 en el manager, y por el mismo motivo: componer no es
                // reemplazar.
                anterior?.invoke(frame, peerId)
                entregados++
                reinyectar(frame)
                Unit
            },
        )
    }

    /**
     * Da de baja el camino legado y RESTAURA el valor previo.
     *
     * @return `true` si estaba dado de alta. Un `detach()` sin `attach()` es un
     *   NO-OP y no un error: un consumidor que se da de baja en `finally` puede
     *   hacerlo aunque nunca se haya dado de alta.
     */
    fun detach(): Boolean {
        if (!dadoDeAlta) return false
        dadoDeAlta = false
        val restaurado = previo
        backend.eventCallbacks = backend.eventCallbacks.copy(onLegacyInboundFrame = restaurado)
        return true
    }

    /** Si el camino legado esta dado de alta ahora mismo. */
    fun isAttached(): Boolean = dadoDeAlta

    /** Cuantos marcos han pasado por el camino legado en total. */
    fun deliveredCount(): Int = entregados

    /**
     * El compositor que se usa con ESTE motor.
     *
     * Es la UNICA forma de construirlo contra el motor real, y evita que cada
     * consumidor tenga que acordarse de pasar el puente correcto: un compositor
     * mal cableado no falla, deja de entregar en silencio, y eso es peor que no
     * tener camino legado.
     */
    companion object {
        fun forEngine(engine: JvmRelayTransport): LegacyInboundConductor =
            LegacyInboundConductor(engine) { frame ->
                // El `envelopeMessageId` se deja a proposito en `null`: el marco
                // ya trae el `Message` DECODIFICADO, luego su `messageId` es el
                // de la carga —el estable—, y el del envelope solo se usaria
                // para una carga que no se puede leer, que no es el caso.
                val inbound = RelayDataCodec.InboundMessage(
                    from = frame.from,
                    to = frame.encoded.to,
                    payload = frame.encoded.payload,
                    envelopeMessageId = null,
                )
                val bytes = engine.relayCodec().toCoreMessageBytes(inbound)
                if (bytes == null) {
                    engine.logLine("LegacyInboundConductor: el sobre no se pudo codificar y se perdio")
                } else {
                    engine.deliverLegacyInbound(bytes)
                }
            }
    }
}
