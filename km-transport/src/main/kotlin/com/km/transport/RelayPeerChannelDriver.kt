package com.km.transport

import com.km.model.IdentityId
import com.km.node.PeerContext
import com.km.node.PeerTransportManager
import com.km.node.PeerTransportState

/**
 * Conduce un binding del manager a `CONNECTED` cuando el rele dice que el par
 * esta PRESENTE, y solo entonces.
 *
 * ## POR QUE HACE FALTA
 *
 * `PeerTransportManager.sendData` exige un binding `CONNECTED`, y un binding
 * recien creado esta `NEW`. [PeerTransportManager.markConnected] llama a
 * [com.km.node.PeerTransportBinding.withConnected] SIN capturar, y ese `require`
 * solo admite `OFFER_SENT`, `ANSWER_RECEIVED` o `NEGOTIATING`: desde `NEW` LANZA
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

    /** Pares ya conducidos a `CONNECTED`. */
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

    /** El backend creo el binding del par. */
    fun onBindingCreated(remotePeerId: IdentityId) {
        intentarConectar(remotePeerId)
    }

    /** El rele dijo que el par esta presente. */
    fun onPeerPresent(remotePeerId: IdentityId) {
        presenciaVistas.add(remotePeerId)
        intentarConectar(remotePeerId)
    }

    /** Como [onPeerPresent]: en linea es presencia. */
    fun onPeerOnline(remotePeerId: IdentityId) = onPeerPresent(remotePeerId)

    /** El rele dijo que el par se fue: se olvida su presencia y su enlace. */
    fun onPeerOffline(remotePeerId: IdentityId) {
        presenciaVistas.remove(remotePeerId)
        conectados.remove(remotePeerId)
    }

    /** Si el par esta `CONNECTED` a traves de este conductor. */
    fun isConnected(remotePeerId: IdentityId): Boolean = conectados.contains(remotePeerId)

    /**
     * Intenta el enlace si estan las DOS piezas: presencia y binding.
     *
     * IDEMPOTENTE: volver a_llamar con el par ya conectado no vuelve a crear el
     * binding ni a recorrer el ciclo. Y `createBinding` devuelve
     * `BINDING_ALREADY_EXISTS` si alguien se adelanta, en cuyo caso se respeta
     * el binding que hay en lugar de pelearse con el.
     */
    private fun intentarConectar(remotePeerId: IdentityId) {
        if (!::peerContext.isInitialized) return
        if (remotePeerId != peerContext.remotePeerId) return
        if (!presenciaVistas.contains(remotePeerId)) return
        if (conectados.contains(remotePeerId)) return
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
        // `withConnected`, que es el fallo que D-0.2 senala. Por eso el ciclo se
        // recorre SEGUN EL ESTADO REAL, y no segun una secuencia fija.
        var binding = manager.getBinding(localIdentity, remotePeerId)
        if (binding == null) {
            binding = when (val creado = manager.createBinding(localIdentity, peerContext)) {
                is com.km.node.TransportResult.Failure -> return
                is com.km.node.TransportResult.Success -> creado.value
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
            PeerTransportState.ANSWER_RECEIVED, PeerTransportState.NEGOTIATING, PeerTransportState.CONNECTED -> Unit
            // CLOSED y FAILED no se reconducen: un binding cerrado necesita uno
            // nuevo, y `createBinding` lo creara en el proximo intento.
            PeerTransportState.CLOSED, PeerTransportState.FAILED -> return
        }

        if (manager.markConnected(localIdentity, peerContext).isSuccess) {
            conectados.add(remotePeerId)
        }
    }

    /**
     * Por que [onBindingCreated] se LLAMA en vez de suscribirse solo.
     *
     * `TransportBackend` no tiene un slot para "han creado un binding":
     * `onCreateBinding` es un METODO que el manager llama, y su resultado lo
     * ignora. El manager, ademas, no expone un evento de creacion de binding:
     * `onStateChange` y `onConnectionStateChange` se disparan al `CONNECTED`, que
     * es justo lo que este conductor todavia no ha alcanzado.
     *
     * Inventar un cable para eso —envolver `onCreateBinding`, componer un slot
     * que no existe— seria mas codigo del que ahorra y dejaria al driver
     *dependiente de un hook que otro podria pisar. Se llama explicitamente y se
     * dice de donde sale: de quien cree el binding. Es una condicion de uso, no
     * un misterio.
     */

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