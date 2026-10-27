package com.km.transport

import com.km.model.IdentityId
import com.km.node.PeerContext
import com.km.node.PeerTransportManager
import com.km.node.PeerTransportState
import com.km.node.TransportResult

/**
 * Conduce un binding del manager a `CONNECTED` cuando el rele dice que el par
 * esta PRESENTE, y solo entonces.
 *
 * ## POR QUE HACE FALTA
 *
 * `PeerTransportManager.sendData` exige un binding `CONNECTED`, y un binding
 * recien creado esta `NEW`. [PeerTransportManager.markConnected] llama a
 * [com.km.node.PeerTransportBinding.withConnected] y ese `require` solo admite
 * `OFFER_SENT`, `ANSWER_RECEIVED` o `NEGOTIATING`: desde `NEW` LANZA
 * `IllegalArgumentException`. El rele no negocia —no hay SDP, no hay ICE—, asi
 * que nadie recorre ese ciclo por el.
 *
 * La secuencia de aqui es, por tanto, la del CORPUS y no una invencion:
 * `createBinding` -> `sendOffer` -> `receiveAnswer` -> `markConnected`, con SDP
 * vacio como representacion de "el medio acepto el enlace". Es exactamente lo
 * que ya hacen `RealRelayTransportTest`, `SecureWorld` y
 * `SecureSessionEstablishment`, de modo que no se abre una via que el resto del
 * framework no conozca.
 *
 * ## EL BINDING ES LA AUTORIDAD; EL DRIVER ES UN RECONCILIADOR
 *
 * La version anterior guardaba un memo de "ya conectados" y llamaba a
 * `markConnected` siempre que el binding no estuviera en `CLOSED`/`FAILED`.
 * Eso producia dos defectos, los dos reproducidos en el log del dispositivo:
 *
 *  1. **`PEER_ONLINE` con el binding ya `CONNECTED`** —lo que emite cada
 *     reautenticacion del par, porque `MessageRouter` desregistra y vuelve a
 *     registrar sus handlers— caia en `markConnected` desde `CONNECTED`, que
 *     lanza. El frame-loop lo absorbia por frame y el memo quedaba
 *     desactualizado: [isConnected] decia `false` con el binding `CONNECTED`.
 *     Ahora esa rama es IDEMPOTENTE: reanuda el memo de conectados y retorna,
 *     sin segunda transicion.
 *  2. **Binding `CLOSED` o `FAILED`** retornaba en silencio, dejando al par sin
 *     canal hasta reiniciar el proceso. Ahora se crea un binding NUEVO —el
 *     manager solo rechaza reemplazar uno ACTIVO— y se recorre el ciclo
 *     completo en la misma llamada.
 *
 * Por eso [isConnected] ya no es el memo solo: es la CONJUNCION del memo y el
 * estado real del binding. El memo conserva PC-04 (`onPeerOffline` lo olvida) y
 * la conjuncion impide que el driver afirme un enlace que el manager cerro por
 * fuera.
 *
 * ## UN INTENTO A LA VEZ POR PAR
 *
 * El aviso de binding y el de presencia llegan por hilos distintos (el manager
 * y el frame-loop), y dos intentos concurrentes podian crear dos bindings o
 * recorrer dos veces el ciclo. Todos los puntos de entrada estan bajo el mismo
 * monitor —que es POR DRIVER, y el driver es por par— y dentro del intento se
 * RELEEN el binding y la presencia del motor. No hay llamada del manager hacia
 * el driver, asi que no hay reentrada ni lock invertido.
 *
 * ## LAS DOS ALTERNATIVAS DESCARTADAS, Y POR QUE
 *
 * (a) Relajar `withConnected` para admitir `NEW -> CONNECTED`. Se descarta
 * porque cambiaria la semantica de una clase que tres checkpoints ya dieron por
 * buena y que `PeerTransportBindingTest` fija, a cambio de ahorrar tres
 * llamadas. Si algun dia hace falta, se hace con un test que lo demuestre.
 *
 * (b) Anadir `markConnectedDirectly` al manager. Se descarta porque es API NUEVA
 * en la autoridad de estado, y un atajo de medio no es una autoridad de estado.
 *
 * ## POR QUE NO CONECTA NI DESCONECTA
 *
 * La conectividad es del motor. Esta clase no llama a `connect()` ni a
 * `disconnect()`: hacerlo convertiria un driver de enlace en un segundo dueno
 * del socket, que es el defecto que la doble fachada evita.
 *
 * ## LOS DOS ORDENES, Y POR QUE UN CONDUCTOR SOLO
 *
 * La presencia puede llegar ANTES de que exista el binding (el `PEER_ONLINE` del
 * rele llega en cuanto se conecta) o DESPUES (`onCreateBinding` se invoca al
 * crear el enlace). Se escuchan los dos eventos y se recuerda cual se ha visto:
 * por eso alcanza `CONNECTED` en cualquiera de los dos casos.
 */
class RelayPeerChannelDriver(
    private val manager: PeerTransportManager,
    /** El motor, que es la autoridad sobre quien esta presente. */
    private val engine: JvmRelayTransport,
    /**
     * Identidad que ABRE el enlace: la mitad local de la clave del binding.
     *
     * No se deduce ni se busca. La lleva el motor —es su identidad de rele— y
     * `RelayClientStack` la pasa; deducirla seria inventar una fuente de verdad
     * donde ya hay una.
     */
    private val localIdentity: IdentityId,
) {
    /** Contexto ya AUTORIZADO del par. El driver no lo fabrica: no es su trabajo. */
    private lateinit var peerContext: PeerContext

    /** Pares cuya presencia ya se vio y que aun no estan conectados. */
    private val presenciaVistas = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<IdentityId, Boolean>())

    /**
     * Pares ya conducidos a `CONNECTED`.
     *
     * Es un MEMO, no la autoridad: [isConnected] lo cruza con el binding real.
     * `onPeerOffline` lo olvida (PC-04) para que una presencia vieja no pueda
     * dar por vivo un enlace que el rele ya no anuncia.
     */
    private val conectados = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<IdentityId, Boolean>())

    /**
     * Declara a quien se va a enlazar.
     *
     * Se llama ANTES de observar: sin autoridad que autorice la sesion no hay
     * enlace que crear, y `PeerTransportManager.createBinding` lo rechazaria
     * exactamente igual que si el driver se lo inventara.
     */
    fun forPeer(peerContext: PeerContext): RelayPeerChannelDriver {
        this.peerContext = peerContext
        return this
    }

    /**
     * El backend creo el binding del par.
     *
     * Se LLAMA y no se observa: `TransportBackend` no tiene un slot para "han
     * creado un binding" —`onCreateBinding` es un metodo que el manager llama y
     * cuyo resultado ignora—, y el manager tampoco expone un evento de
     * creacion. `onStateChange` se dispara al `CONNECTED`, que es justo lo que
     * este conductor todavia no ha alcanzado. Inventar ese cable seria mas
     * codigo del que ahorra y dejaria al driver pendiente de un hook que otro
     * podria pisar. Se llama desde quien crea el binding: es una condicion de
     * uso, no un misterio.
     */
    @Synchronized
    fun onBindingCreated(remotePeerId: IdentityId) {
        intentarConectar(remotePeerId)
    }

    /** El rele dijo que el par esta presente. */
    @Synchronized
    fun onPeerPresent(remotePeerId: IdentityId) {
        presenciaVistas.add(remotePeerId)
        intentarConectar(remotePeerId)
    }

    /** Como [onPeerPresent]: en linea es presencia. */
    @Synchronized
    fun onPeerOnline(remotePeerId: IdentityId) = onPeerPresent(remotePeerId)

    /**
     * El rele dijo que el par se fue: se olvida su presencia y su enlace.
     *
     * NO se toca el binding: cerrarlo es del manager, y el binding `CONNECTED`
     * que queda es justo lo que la rama idempotente de [intentarConectar]
     * reencuentra en la reautenticacion (PC-04 conservado).
     */
    @Synchronized
    fun onPeerOffline(remotePeerId: IdentityId) {
        presenciaVistas.remove(remotePeerId)
        conectados.remove(remotePeerId)
    }

    /**
     * Si el par tiene canal a traves de este conductor.
     *
     * La CONJUNCION es deliberada: el memo solo diria "el driver lo condujo",
     * que puede haber dejado de ser cierto si el manager cerro o fallo el
     * binding por fuera; el binding solo romperia PC-04, porque tras
     * `onPeerOffline` el binding sigue `CONNECTED` y el conductor TIENE que
     * decir que no. Con las dos condiciones, ni sobre-afirma ni olvida de mas.
     */
    fun isConnected(remotePeerId: IdentityId): Boolean {
        if (!::peerContext.isInitialized) return false
        if (!conectados.contains(remotePeerId)) return false
        return manager.getBinding(localIdentity, remotePeerId)?.state == PeerTransportState.CONNECTED
    }

    /**
     * Reconcilia el binding del par con la presencia, UNA vez.
     *
     * IDEMPOTENTE por construccion: cada rama dice que hace falta segun el
     * estado RELEIDO bajo el lock, y la rama `CONNECTED` no renegocia nada.
     *
     * @return nada: el exito y el fallo se leen en [isConnected] y en el
     *   binding del manager, que son las dos superficies que ya existian.
     */
    @Synchronized
    private fun intentarConectar(remotePeerId: IdentityId) {
        if (!::peerContext.isInitialized) return
        if (remotePeerId != peerContext.remotePeerId) return
        if (!presenciaVistas.contains(remotePeerId)) return
        if (conectados.contains(remotePeerId)) {
            val actual = manager.getBinding(localIdentity, remotePeerId)
            if (actual?.state == PeerTransportState.CONNECTED) return
            // El memo quedo obsoleto: el binding se cerro o fallo por fuera
            // —`closeBinding`, `failBinding`, un cierre del medio—. Se suelta y
            // se reconcilia abajo; quedarse con el memo bloquearia para siempre
            // la recuperacion del par, que es justo el defecto de R3.
            conectados.remove(remotePeerId)
        }
        // El aviso de presencia es un INDICIO; la autoridad es el motor. Sin
        // esta comprobacion, un `PEER_ONLINE` viejo —o uno que llega antes de
        // que el motor lo haya registrado— abriria un enlace hacia un par que
        // no esta, y el fallo apareceria como un envio que no llega en lugar de
        // como "el par no esta".
        if (!engine.isPeerPresent(remotePeerId)) return

        // El binding puede YA existir en `NEW`: `SecureSessionEstablishment` crea
        // uno al abrir la sesion, y ese binding nunca se conduce a CONNECTED.
        // Escribir el ciclo a ciegas —crear, ofrecer, responder, conectar— falla
        // en cuanto encuentra uno preexistente, y falla con la excepcion de
        // `withConnected`. Por eso el ciclo se recorre SEGUN EL ESTADO REAL, y
        // no segun una secuencia fija.
        var binding = manager.getBinding(localIdentity, remotePeerId)
        if (binding == null || !binding.isActive) {
            // CLOSED y FAILED no son "terminales": un binding inactivo se puede
            // REEMPLAZAR (`createBinding` solo rechaza uno activo). Retornar en
            // silencio aqui es el defecto que dejaba al par sin canal hasta
            // reiniciar el proceso.
            binding = when (val creado = manager.createBinding(localIdentity, peerContext)) {
                is TransportResult.Success -> creado.value
                is TransportResult.Failure -> {
                    // Carrera perdida contra otro creador: si hay uno ACTIVO, se
                    // respeta el que hay en vez de pelearse con el.
                    val actual = manager.getBinding(localIdentity, remotePeerId)
                    if (actual != null && actual.isActive) actual else return
                }
            }
        }

        when (binding.state) {
            PeerTransportState.NEW -> {
                if (manager.sendOffer(localIdentity, peerContext, SDP_VACIO, remotePeerId).isFailure) return
                if (manager.receiveAnswer(localIdentity, remotePeerId, SDP_VACIO).isFailure) return
            }
            PeerTransportState.OFFER_SENT -> {
                if (manager.receiveAnswer(localIdentity, remotePeerId, SDP_VACIO).isFailure) return
            }
            PeerTransportState.ANSWER_RECEIVED, PeerTransportState.NEGOTIATING -> Unit
            PeerTransportState.CONNECTED -> {
                // La reautenticacion del par deja el binding `CONNECTED` y
                // vuelve a anunciar `PEER_ONLINE`. No hay nada que negociar: se
                // reanuda el memo y se retorna. Llamar a `markConnected` desde
                // aqui era el `IllegalArgumentException` que el frame-loop
                // absorbia mientras [isConnected] mentia `false`.
                conectados.add(remotePeerId)
                return
            }
            // Inalcanzable por construccion —se acaba de recrear arriba—, pero
            // el `when` es exhaustivo y el `return` deja el contrato escrito.
            PeerTransportState.CLOSED, PeerTransportState.FAILED -> return
        }

        when (val conectado = manager.markConnected(localIdentity, peerContext)) {
            is TransportResult.Success -> conectados.add(remotePeerId)
            is TransportResult.Failure -> {
                // Sin memo: el binding no llego a CONNECTED, asi que el driver
                // no puede decir que hay canal. Se DICE por el log; no se lanza
                // ni se reintenta a ciegas aqui.
                engine.logLine(
                    "[PEER-CHANNEL] markConnected rechazado para ${remotePeerId.value.take(8)}: " +
                        "${conectado.error} (${conectado.message})",
                )
            }
        }
    }

    private companion object {
        /**
         * SDP vacio.
         *
         * No es una simulacion: es la ausencia de SDP, que es lo que de verdad
         * hay. El rele no la produce, y poner algo inventado seria afirmar una
         * negociacion que no ocurrio.
         */
        const val SDP_VACIO = ""
    }
}
