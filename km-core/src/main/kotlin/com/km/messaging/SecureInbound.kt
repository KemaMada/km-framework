package com.km.messaging

import com.km.model.IdentityId
import com.km.model.Message
import com.km.node.PeerTransportManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Puente de RECEPCION cifrada: `SecureFrame` entrante -> `Message` de km-core.
 *
 * ## POR QUE ESTO NO ES "DESCIFRAR EL WIRE"
 *
 * [SecureMessagingSession] YA esta suscrito al `PeerTransportManager` y YA
 * descifra en su propia suscripcion, filtrando por par. Si este puente
 * descifrara tambien, el mismo frame pasaria dos veces por el ratchet: el
 * primer intento consume el `N` y el segundo es un replay que se descarta. El
 * mensaje se pierde, y peor, el estado del ratchet queda tocado por un camino
 * que no debia.
 *
 * Asi que el puente hace lo contrario de lo que parece: **no descifra**, decide
 * si lo que la sesion acaba de descifrar es para la aplicacion y lo convierte en
 * `Message`. El unico caso en que el puente mira el wire entero es para
 * CLASIFICARLO (D-1) y para decidir a quien pertenece.
 *
 * ## LA GARANTIA DE ORDEN, Y DE DONDE SALE
 *
 * `PeerTransportManager.onData` ejecuta los handlers en el orden de registro.
 * El de la sesion se registro en su `init`, o sea antes de que la sesion
 * pudiera aparecer en el registro de nadie. El de este puente se registra en
 * [attach], que ocurre cuando la sesion entra en el [SecureSessionRegistry] —
 * o, si ya estaba dentro, inmediatamente despues. Por eso el handler del puente
 * se ejecuta DESPUES y ve el plaintext que la sesion acaba de producir. Ese
 * orden es la premisa de todo este fichero, y por eso la suscripcion se hace
 * por sesion instalada y no contra el manager en general.
 *
 * ## LO QUE ENTREGA
 *
 * Un `Message` con `from` = identidad que entrego el transporte, `to` =
 * identidad local, `messageId` y `payload` los del SOBRE, no los del
 * `SecureFrame`: el frame no lleva ninguno de los dos, y el sobre va dentro del
 * plaintext cifrado precisamente para que no puedan alterarse sin que la AEAD
 * lo note.
 *
 * **`timestamp` es la hora de LLEGADA del receptor**, y no la de envio: el
 * `MessageEnvelope` v1 no lleva tiempo, asi que no hay forma de reconstruir
 * cuando se escribio. Lo que si se sabe —y es lo unico que estos bytes
 * respaldan— es cuando llegaron. Antes se ponia `0`, y con todos los mensajes
 * empatados el orden de la conversacion lo decidia el rowid de SQLite: el orden
 * dependia del almacenamiento y no del tiempo. Se declara hora de recepcion, no
 * hora de envio, porque es lo unico que el formato permite afirmar (R9).
 */
class SecureInbound(
    private val localIdentity: IdentityId,
    private val registry: SecureSessionRegistry,
    private val peerTransportManager: PeerTransportManager?,
    private val onMessage: (Message) -> Unit,
) {

    /** SecureFrames que llegaron de un par sin sesion establecida. */
    val unknownPeerFrames = AtomicInteger(0)

    /** Bytes que NO eran `SecureFrame` v1 y llegaron por la ruta de par. */
    val legacyBytesOnPeerChannel = AtomicInteger(0)

    /**
     * SecureFrames que la sesion recibio y NO pudo descifrar.
     *
     * Tag alterado, replay o salto de epoch: son rechazos CON SU TIPO, no
     * excepciones, y por eso son contables.
     */
    val rejectedFrames = AtomicInteger(0)

    /** Mensajes entregados a la aplicacion. */
    val delivered = AtomicInteger(0)

    private var stopObserving: (() -> Unit)? = null

    /**
     * Empieza a mirar los bytes de las sesiones del registro.
     *
     * Se suscribe POR SESION y no solo contra el `PeerTransportManager` en
     * general porque el handler de entrega tiene que ejecutarse DESPUES del de
     * la sesion, y la sesion se suscribio en su `init`. Suscribiendose aqui, en
     * el instante en que la sesion entra en el registro, ese orden queda
     * garantizado; suscribiendose al manager por adelantado, no.
     * [SecureSessionRegistry.onInstall] entrega tambien las que ya estaban
     * dentro, que son justo las que se suscribieron antes.
     *
     * Ademas se deja UN observador global, que no entrega nada: existe para que
     * "llego algo que no es mio" sea un hecho CONTABLE y no un silencio. Sin
     * el, un SecureFrame de un par sin sesion seria invisible, que es
     * exactamente lo que hace indistinguible de que nunca llego.
     *
     * ## SESIONES DE REEMPLAZO
     *
     * La observacion es POR SESION y no una vez por par: cuando el registro
     * sustituye la sesion de un par por otra, esa otra se observa tambien. Ver
     * [observed], donde el motivo esta escrito entero.
     */
    fun attach() {
        stopObserving?.invoke()
        stopObserving = registry.onInstall { session -> observe(session) }
        peerTransportManager?.onData { from, wire -> classifyOnly(from, wire) }
    }

    /** Clasifica y CUENTA. Nunca entrega, nunca descifra, nunca decodifica. */
    private fun classifyOnly(from: IdentityId, wire: ByteArray) {
        if (!isSecureFrameWire(wire)) {
            legacyBytesOnPeerChannel.incrementAndGet()
            return
        }
        if (registry.find(from) == null) unknownPeerFrames.incrementAndGet()
    }

    /**
     * La sesion VIGENTE observada por par, y no un conjunto de pares ya vistos.
     *
     * La diferencia importa cuando un par instala una sesion NUEVA —un re-key—:
     * `SecureSessionRegistry.install` SUSTITUYE, y un conjunto de identidades ya
     * vistas se quedaria diciendo "este par ya esta observado" mientras el
     * handler sigue apuntando a la sesion VIEJA. Los mensajes posteriores
     * llegarian a la sesion vieja, que no los descifra, y se perderian en
     * silencio: sin excepcion, sin error y sin entrega. El mapa guarda la sesion
     * vigente, de modo que la sustitucion es justo el caso que SI se re-observa.
     *
     * Y guarda la sesion, no el par, porque es la sesion lo que hay que volver
     * a suscribir. Volver a suscribir la MISMA sesion dos veces seria un
     * defecto de otro tipo: cada byte pasaria dos veces por el drains y la
     * segunda vez contaria como rechazo de un frame que si descifro.
     */
    private val observed = java.util.concurrent.ConcurrentHashMap<IdentityId, SecureMessagingSession>()

    private fun observe(session: SecureMessagingSession) {
        val anterior = observed.put(session.remotePeerId, session)
        if (anterior === session) return
        // Se suscribe al manager de la PROPIA sesion: es el unico al que la
        // sesion esta suscrito, y por tanto el unico donde el orden de
        // registro significa algo.
        session.transportManager.onData { from, wire -> onPeerBytes(session, from, wire) }
    }

    private fun onPeerBytes(session: SecureMessagingSession, from: IdentityId, wire: ByteArray) {
        // 1. Aislamiento: este handler ve TODOS los bytes del manager, no solo
        //    los de su par. Los de otro par los descifra la sesion de ese otro.
        if (from != session.remotePeerId) return

        // 1.b Una sesion SUSTITUIDA deja de hablar por aqui. Su suscripcion
        //     sigue viva —`PeerTransportManager.onData` no devuelve un token
        //     para retirarse— y sus bytes seguirian llegando a este handler;
        //     si no se cortara aqui, drenaria una sesion que ya no es la del par
        //     y contaria un rechazo por cada frame de verdad que llega. El
        //     registro ya dijo que esta sesion no manda, asi que aqui tampoco.
        if (observed[session.remotePeerId] !== session) return

        // 2. D-1: lo que no empieza por la version de SecureFrame v1 no es un
        //    SecureFrame y no se toca como si lo fuera. Aqui solo se corta: el
        //    contador lo lleva el observador global de [attach], que ve el
        //    manager entero, y si lo subiera tambien este handler el mismo byte
        //    se contaria una vez por cada sesion viva.
        if (!isSecureFrameWire(wire)) return

        // 3. El frame ya fue descifrado por la sesion, que va antes en la lista
        //    de handlers. Aqui esta el UNICO punto del framework que consume el
        //    plaintext de una sesion para la aplicacion, y depende por entero
        //    de ese orden: si este handler se ejecutara antes, el outbox estaria
        //    vacio y el mensaje se perderia en silencio. Por eso la suscripcion
        //    se hace por sesion instalada. Un rechazo de la sesion --tag
        //    alterado, replay, salto de epoch-- no es una excepcion: es un
        //    resultado con su propio tipo, y aqui se cuenta.
        val plaintext = session.drainOutbound().lastOrNull()
        if (plaintext == null) {
            rejectedFrames.incrementAndGet()
            return
        }

        // 4. El plaintext es el SOBRE. Un sobre que no sea de esta version no
        //    se interpreta con otra gramatica: se rechaza.
        val envelope = try {
            MessageEnvelopeCodec.decode(plaintext)
        } catch (e: EnvelopeFormatException) {
            rejectedFrames.incrementAndGet()
            return
        }
        if (envelope.type != EnvelopeType.USER_MESSAGE) {
            rejectedFrames.incrementAndGet()
            return
        }

        delivered.incrementAndGet()
        onMessage(
            Message(
                messageId = envelope.messageId,
                from = from,
                to = localIdentity,
                payload = envelope.payload,
                // R9: el sobre v1 NO lleva tiempo, y por eso aqui NO se puede
                // reconstruir el instante en que se escribio. Lo que SI se sabe
                // es cuando LLEGO: se sella la hora de recepcion del dispositivo.
                // Antes se ponia 0 y la conversacion se ordenaba por un empate
                // que resolvia el rowid de SQLite: el orden dependia del
                // almacenamiento, no del tiempo. Se declara hora de RECEPCION,
                // no hora de envio, porque es lo unico que estos bytes respaldan.
                timestamp = System.currentTimeMillis(),
            ),
        )
    }

    /** Deja de mirar. No cierra las sesiones: no son de este puente. */
    fun detach() {
        stopObserving?.invoke()
        stopObserving = null
    }
}

/**
 * R1 — RECEPCION: EL ESPEJO DEL ENVIO, Y LO QUE ESTO CUENTA.
 *
 * El envio tiene un punto de decision ([SecureOutbound]) y una verdad
 * observable (el estado `FAILED` del mensaje en el store). La recepcion no tenia
 * ninguna de las dos cosas: con el registro activo, un `MESSAGE` en claro por
 * el camino legado llegaba al handler de la aplicacion con la misma forma y el
 * mismo `from` que un mensaje E2E, y el consumidor no tenia forma de saber cual
 * de los dos acababa de leer. Eso es falsa sensacion de cifrado, y la forma de
 *taparlo no es una nota: es un numero.
 *
 * ## LO QUE ESTA CLASE DICE, Y CADA COSA EN SU LUGAR
 *
 * - [deliveredCleartext]: bytes que el camino legado entrego EN CLARO y la
 *   aplicacion recibio. Es la MARCA DE QUE NO ES E2E: un `Message` que suma aqui
 *   y NO suma en [SecureInbound.delivered] vino por el camino legado.
 * - [rejectedCleartext]: bytes EN CLARO que el camino legado RECHAZO por tener el
 *   registro activo. Es el rechazo, y es observable por API publica igual que el
 *   del envio: no es un log.
 * - [legacyReceiveEnabled]: si el camino legado de recepcion esta abierto. Con el
 *   registro ausente esta `true` —es el camino de COMPATIBILIDAD y se conserva—,
 *   y con el registro presente es `false` salvo que el consumidor pida
 *   explicitamente el claro.
 *
 * ## EL OPT-IN NO ES UNA EXCEPCION, ES UN MODO
 *
 * Quien activa `allowLegacyCleartextReceive` NO obtiene lo que "es el camino
 * E2E": obtiene un camino EN CLARO que ademas queda marcado en
 * [deliveredCleartext]. Esos dos hechos se declaran juntos porque separados
 * uno de los dos es la mitad de la mentira que este cambio cierra.
 */
class LegacyInboundObservation(
    /** Si el camino legado de RECEPCION entrega mensajes a la aplicacion. */
    val legacyReceiveEnabled: Boolean,
) {
    /** Bytes EN CLARO que el camino legado entrego y la aplicacion recibio. */
    val deliveredCleartext = AtomicInteger(0)

    /** Bytes EN CLARO que el camino legado rechazo por tener el registro activo. */
    val rejectedCleartext = AtomicInteger(0)

    internal fun markDeliveredCleartext() {
        deliveredCleartext.incrementAndGet()
    }

    internal fun rejectCleartext() {
        rejectedCleartext.incrementAndGet()
    }
}
