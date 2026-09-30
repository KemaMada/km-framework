package com.km.node

import com.km.model.IdentityId

/**
 * Callback para eventos generados por el backend de transporte.
 *
 * Estos eventos se disparan cuando el transporte subyacente (ej. libwebrtc)
 * genera informacion que debe ser manejada por [PeerTransportManager]:
 * - Candidatos ICE generados localmente → enviar via signaling.
 * - Datos recibidos del peer → invocar handlers del manager.
 * - Cambios de estado de la conexion → actualizar binding.
 */
data class TransportEventCallbacks(
    /** Candidato ICE generado localmente (backend → signaling). */
    val onIceCandidateGenerated: ((localIdentity: IdentityId, remotePeerId: IdentityId, candidate: String) -> Unit)? = null,
    /** Datos recibidos del DataChannel remoto (backend → manager). */
    val onDataReceived: ((remotePeerId: IdentityId, data: ByteArray) -> Unit)? = null,
    /** Cambio de estado de conexion WebRTC (backend → manager). */
    val onConnectionStateChange: ((localIdentity: IdentityId, remotePeerId: IdentityId, connected: Boolean) -> Unit)? = null,
    /** SDP local generada por el transporte (backend → signaling → peer). */
    val onLocalSdpGenerated: ((localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String) -> Unit)? = null,
    /** Error de transporte asincrono (backend → manager). */
    val onTransportError: ((localIdentity: IdentityId, remotePeerId: IdentityId, reason: String) -> Unit)? = null,
)

/**
 * Backend de transporte peer-to-peer.
 *
 * Abstraccion entre [PeerTransportManager] y la ejecucion real del transporte.
 * El manager controla el estado y la identidad; el backend solo ejecuta
 * operaciones de transporte (crear conexion, establecer SDP, enviar datos).
 *
 * INVARIANTE: el backend NO decide quien es el peer. Esa decision pertenece
 * exclusivamente a [PeerTransportManager] via [PeerContext].
 *
 * Implementaciones:
 * - [FakeTransportBackend]: determinista, usado en tests (641+ tests).
 * - `:km-webrtc` RealWebRtcTransport: integracion real con libwebrtc/JNI.
 *
 * NOTA: la implementacion real vive en el modulo `:km-webrtc`, que depende
 * de `:km-core` (nunca al reves). Asi `km-core` permanece libre de WebRTC.
 *
 * CONVENCION DE NOMBRES (3Q.0): todo parametro que designe al OTRO extremo
 * se llama `remotePeerId`, y el extremo propio `localIdentity`. No se usa un
 * `peerId` generico porque las dos convenciones opuestas convivieron en esta
 * frontera y costaron tres correcciones durante la integracion de 3P: el
 * signaling entrega al REMOTO mientras que `sendData` entrega al
 * DESTINATARIO. Un nombre explicito hace la direccion evidente al leer la
 * llamada, sin depender de documentacion.
 */
interface TransportBackend {

    /**
     * Nombre estable del transporte, para diagnostico.
     *
     * [TransportChain] registra que eslabon de la cadena fallo; sin un nombre
     * el diagnostico solo podria decir "el indice 1", que no ayuda a nadie
     * cuando hay P2P, Relay y Tor en juego.
     */
    val name: String get() = this::class.simpleName ?: "TransportBackend"

    /**
     * Callbacks registrados por [PeerTransportManager] para recibir eventos
     * del transporte subyacente.
     *
     * El manager los establece despues de crear el backend.
     * El backend los invoca cuando el transporte genera eventos
     * (ICE candidates, datos recibidos, cambios de estado).
     */
    var eventCallbacks: TransportEventCallbacks

    /**
     * Inicializa el backend. Se llama una vez al inicio.
     * Para el backend WebRTC real (:km-webrtc), aqui se crearia PeerConnectionFactory.
     */
    fun initialize(): TransportResult<Unit>

    /**
     * Crea una conexion de transporte para un par.
     * Llamado desde [PeerTransportManager.createBinding].
     *
     * @param localIdentity identidad local que crea el binding.
     * @param remotePeerId identidad del peer remoto (verificada por el manager).
     */
    fun onCreateBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit>

    /**
     * Procesa la oferta SDP local que se envia al peer.
     * Llamado desde [PeerTransportManager.sendOffer].
     *
     * Para el backend WebRTC real (:km-webrtc): establecer como descripcion local.
     * Para [FakeTransportBackend]: no operacion.
     */
    fun onLocalSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String): TransportResult<Unit>

    /**
     * Procesa la respuesta SDP remota recibida del peer.
     * Llamado desde [PeerTransportManager.receiveAnswer].
     *
     * Para el backend WebRTC real (:km-webrtc): establecer como descripcion remota.
     * Para [FakeTransportBackend]: no operacion.
     */
    fun onRemoteSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String): TransportResult<Unit>

    /**
     * Anade un candidato ICE remoto.
     * Llamado desde [PeerTransportManager.addIceCandidate].
     *
     * Para el backend WebRTC real (:km-webrtc): anadir via PeerConnection.addIceCandidate().
     * Para [FakeTransportBackend]: no operacion.
     */
    fun onIceCandidate(localIdentity: IdentityId, remotePeerId: IdentityId, candidate: String): TransportResult<Unit>

    /**
     * Envia datos al peer via el transporte subyacente.
     * Llamado desde [PeerTransportManager.sendData] despues de validar estado.
     *
     * Para el backend WebRTC real (:km-webrtc): escribir en el DataChannel.
     * Para [FakeTransportBackend]: no operacion (el manager maneja los handlers).
     *
     * NOTA: el manager ya invoco [PeerTransportManager.onData] handlers antes
     * de llamar a este metodo. El backend solo necesita la escritura real.
     *
     * @param remotePeerId identidad del peer destino.
     * @param datos a enviar.
     */
    fun sendData(remotePeerId: IdentityId, data: ByteArray): TransportResult<Unit>

    /**
     * Cierra la conexion de transporte con un par.
     * Llamado desde [PeerTransportManager.closeBinding] y
     * [PeerTransportManager.failBinding].
     *
     * @param localIdentity identidad local.
     * @param remotePeerId identidad del peer.
     */
    fun onCloseBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit>

    /**
     * Destruye el backend liberando recursos.
     * Llamado al finalizar el gestor.
     *
     * Para el backend WebRTC real (:km-webrtc): dispose de PeerConnectionFactory.
     */
    fun shutdown(): TransportResult<Unit>
}