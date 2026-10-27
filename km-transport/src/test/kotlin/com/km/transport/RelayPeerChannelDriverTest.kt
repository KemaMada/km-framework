package com.km.transport

import com.km.node.PeerContext
import com.km.node.PeerTransportManager
import com.km.node.PeerTransportState
import com.km.node.TransportError
import com.km.node.TransportResult
import com.km.transport.SocketSecureWorld.Device
import com.km.transport.SocketSecureWorld.IdentityRoot
import com.km.transport.SocketSecureWorld.awaitOrFail
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList

/**
 * PC — `RelayPeerChannelDriver`: la aceptacion de la TAREA 3.3.
 *
 * ## QUE SE DEMUESTRA Y POR QUE NECESITA UN RELE DE VERDAD
 *
 * El conductor existe por una razon muy concreta y comprobable:
 * `PeerTransportManager.sendData` exige un binding `CONNECTED`, y
 * `markConnected` (`PeerTransportManager.kt:384-394`) llama a
 * `PeerTransportBinding.withConnected()` SIN capturar, y ese `require` solo
 * admite `OFFER_SENT`, `ANSWER_RECEIVED` o `NEGOTIATING`. Un binding recien
 * creado esta `NEW` y el salto lanzaria. El rele no negocia nada —no hay SDP, no
 * hay ICE—, asi que el ciclo hay que recorrerlo a proposito y con SDP vacio.
 *
 * Y el conductor tiene una segunda puerta que una unidad pura no podria mirar:
 * `intentarConectar` consulta `engine.isPeerPresent(remotePeerId)`, que es
 * `stateRef == ONLINE && remotePeerId in onlinePeers`. Ni el estado ni la lista
 * se pueden poner desde fuera: solo los deja el `AUTH_OK` y el `PEER_ONLINE` de
 * un rele de verdad. Un doble dejaria el test probando al doble, que es el modo
 * de fallo que este repositorio ya ha pagado.
 *
 * ## LOS DOS ORDENES, Y POR QUE SON DOS ESCENARIOS Y NO UNO
 *
 * El `PEER_ONLINE` del rele llega en cuanto el par se conecta, y el aviso de
 * binding lo da quien lo crea. No hay contrato que diga cual va antes, y de hecho
 * los dos ocurren segun el orden de las conexiones. Un solo test que ejercite un
 * orden deja el otro sin cubrir, y el fallo del orden equivocado es el mas
 * silencioso de todos: el conductor simplemente nunca conecta y el envio falla
 * como `INVALID_STATE_TRANSITION` sin que nada diga por que.
 *
 * ## EL DEFECTO QUE PC-08..PC-12 CIERRAN
 *
 * `onPeerOffline` saca al par de `conectados` pero deja el binding en
 * `CONNECTED` —correcto: el binding es del manager—, y la version anterior
 * volvia a presentar presencia metia al par en `markConnected` desde
 * `CONNECTED`, que `withConnected` rechaza. Ese `IllegalArgumentException`
 * moria en el frame-loop y el conductor se quedaba diciendo que no habia
 * enlace con el binding `CONNECTED`. Los casos PC-08 (duplicado sobre
 * `CONNECTED`), PC-09/PC-10 (`CLOSED` y `FAILED`) y PC-12 (reauth
 * OFFLINE->ONLINE) fijan el contrato nuevo; PC-01..PC-07 siguen fijando el
 * anterior sin cambios.
 */
class RelayPeerChannelDriverTest {

    private val trace = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private var relay: LocalRelay? = null
    private val open = CopyOnWriteArrayList<JvmRelayTransport>()

    @AfterEach
    fun tearDown() {
        open.forEach { runCatching { it.close() } }
        open.clear()
        relay?.shutdown()
        relay = null
    }

    // ------------------------------------------------------------------
    // Utilidades de montaje
    // ------------------------------------------------------------------

    /**
     * Motor REAL contra el rele REAL, con la identidad de su RAIZ.
     *
     * La clave de FIRMA que el motor anuncia es la de la raiz, de modo que el
     * `from` que el rele autentica coincide con el `remotePeerId` que deriva el
     * bundle de contacto. Esa costura —identidad de RELE frente a RAIZ del
     * contacto— es una decision cross-fase declarada en el design (D-G), y
     * [RelaySocketSecureE2ETest] la usa con el mismo motivo y la explica alli.
     */
    private fun node(root: IdentityRoot): JvmRelayTransport {
        val engine = JvmRelayTransport(
            identity = root.identityId,
            ed25519PublicKey = root.keyPair.publicKey,
            ed25519PrivateKey = root.keyPair.privateKey,
            endpoint = requireNotNull(relay).endpoint(),
            relayIdentity = requireNotNull(relay).relayIdentityId,
            ed25519 = SocketSecureWorld.ed25519,
            handshakeTimeoutMs = 20_000,
            log = { message -> trace.add("${root.identityId.value.take(6)}: $message") },
        )
        open.add(engine)
        return engine
    }

    private fun startRelay() {
        if (relay == null) relay = LocalRelay.start("pc")
    }

    /** Conecta [engine] y espera a que quede `ONLINE`. */
    private fun JvmRelayTransport.connectAndAwait() {
        connect().getOrThrow()
        awaitOrFail("el motor debe quedar ONLINE", trace = { trace.toString() }) { isOnline() }
    }

    /** `PeerContext` de contacto REAL, con bundle verificado y firmado de verdad. */
    private fun contextTowards(subject: Device, opkIds: List<Long>, root: IdentityRoot): PeerContext =
        SocketSecureWorld.contextTowards(subject, opkIds, root)

    /**
     * Bytes NO constantes, y distintos por test: una comparacion que solo mirara
     * la longitud confundiria un payload entero con uno truncado.
     */
    private fun wire(seed: Int): ByteArray = ByteArray(64) { ((it * 31 + seed) and 0xFF).toByte() }

    // ==================================================================
    // PC-01 — presencia ANTES que el aviso de binding
    // ==================================================================

    @Test
    @Timeout(180)
    fun `PC-01 presencia antes que binding alcanza CONNECTED`() {
        startRelay()
        val bobRoot = IdentityRoot()
        node(bobRoot).connectAndAwait()

        val aliceRoot = IdentityRoot()
        val aliceEngine = node(aliceRoot)
        val aliceManager = PeerTransportManager(aliceEngine)
        val aliceToBob = contextTowards(Device("bob"), emptyList(), bobRoot)
        aliceEngine.connectAndAwait()

        val driver = RelayPeerChannelDriver(aliceManager, aliceEngine, aliceEngine.relayIdentityId())
            .forPeer(aliceToBob)

        // El rele ANUNCA la presencia de verdad: no se maquilla `onlinePeers`.
        awaitOrFail("el rele debe anunciar a Bob", trace = { trace.toString() }) {
            aliceEngine.isPeerPresent(bobRoot.identityId)
        }
        assertNull(
            aliceManager.getBinding(aliceRoot.identityId, bobRoot.identityId),
            "sin presencia todavia no debe existir binding",
        )

        // Orden 1: la PRESENCIA primero. No hay binding, y el conductor lo crea
        // y recorre el ciclo entero.
        driver.onPeerPresent(bobRoot.identityId)

        assertTrue(driver.isConnected(bobRoot.identityId), "presencia + conductor debe alcanzar CONNECTED")
        assertEquals(
            PeerTransportState.CONNECTED,
            aliceManager.getBinding(aliceRoot.identityId, bobRoot.identityId)?.state,
            "el manager debe ver el binding en CONNECTED, no solo el conductor",
        )
        assertTrue(
            aliceManager.sendData(aliceRoot.identityId, aliceToBob, wire(0x11)).isSuccess,
            "con el binding CONNECTED el envio debe salir",
        )
    }

    // ==================================================================
    // PC-02 — el aviso de binding ANTES que la presencia
    // ==================================================================

    @Test
    @Timeout(180)
    fun `PC-02 binding antes que presencia tambien alcanza CONNECTED`() {
        startRelay()
        val bobRoot = IdentityRoot()
        node(bobRoot).connectAndAwait()

        val aliceRoot = IdentityRoot()
        val aliceEngine = node(aliceRoot)
        val aliceManager = PeerTransportManager(aliceEngine)
        val aliceToBob = contextTowards(Device("bob"), emptyList(), bobRoot)
        aliceEngine.connectAndAwait()

        val driver = RelayPeerChannelDriver(aliceManager, aliceEngine, aliceEngine.relayIdentityId())
            .forPeer(aliceToBob)

        // Orden 2, el inverso: el AVISO DE BINDING primero. Este aviso solo deja
        // constancia de que aun no se puede hacer nada; lo que se mide es que no
        // se conecte de mas, y que cuando llegue la presencia conecte igual.
        driver.onBindingCreated(bobRoot.identityId)
        assertFalse(
            driver.isConnected(bobRoot.identityId),
            "sin presencia, ningun aviso de binding puede abrir el enlace",
        )
        assertNull(
            aliceManager.getBinding(aliceRoot.identityId, bobRoot.identityId),
            "un aviso de binding sin presencia no crea binding",
        )

        awaitOrFail("el rele debe anunciar a Bob", trace = { trace.toString() }) {
            aliceEngine.isPeerPresent(bobRoot.identityId)
        }
        driver.onPeerPresent(bobRoot.identityId)

        assertTrue(driver.isConnected(bobRoot.identityId), "el orden inverso tambien debe alcanzar CONNECTED")
        assertEquals(
            PeerTransportState.CONNECTED,
            aliceManager.getBinding(aliceRoot.identityId, bobRoot.identityId)?.state,
        )
        assertTrue(
            aliceManager.sendData(aliceRoot.identityId, aliceToBob, wire(0x22)).isSuccess,
            "con el binding CONNECTED el envio debe salir",
        )
    }

    // ==================================================================
    // PC-03 — doble presencia y doble binding: UNA sola conexion
    // ==================================================================

    @Test
    @Timeout(180)
    fun `PC-03 doble presencia y doble binding conectan una sola vez y no lanzan`() {
        startRelay()
        val bobRoot = IdentityRoot()
        node(bobRoot).connectAndAwait()

        val aliceRoot = IdentityRoot()
        val aliceEngine = node(aliceRoot)
        val aliceManager = PeerTransportManager(aliceEngine)
        val aliceToBob = contextTowards(Device("bob"), emptyList(), bobRoot)
        aliceEngine.connectAndAwait()

        val driver = RelayPeerChannelDriver(aliceManager, aliceEngine, aliceEngine.relayIdentityId())
            .forPeer(aliceToBob)

        // Los estados observados por el manager: "exactamente una vez" tiene que
        // ser un numero, no una sensacion de que funciono.
        val estados = CopyOnWriteArrayList<PeerTransportState>()
        aliceManager.onStateChange { _, state -> estados.add(state) }

        awaitOrFail("el rele debe anunciar a Bob", trace = { trace.toString() }) {
            aliceEngine.isPeerPresent(bobRoot.identityId)
        }

        // Los DOS avisos, y ademas el alias `onPeerOnline`, que es la misma
        // puerta. El rele puede repetir un `PEER_ONLINE` y el consumidor puede
        // avisar dos veces del mismo binding: si eso abriera dos enlaces o
        // lanzara, aqui se veria.
        driver.onBindingCreated(bobRoot.identityId)
        driver.onBindingCreated(bobRoot.identityId)
        driver.onPeerPresent(bobRoot.identityId)
        driver.onPeerOnline(bobRoot.identityId)
        driver.onBindingCreated(bobRoot.identityId)

        assertTrue(driver.isConnected(bobRoot.identityId), "sigue conectado")
        assertEquals(
            1,
            estados.count { it == PeerTransportState.CONNECTED },
            "el binding debe pasar a CONNECTED UNA sola vez: $estados",
        )
        assertEquals(
            1,
            aliceManager.bindingsForLocalIdentity(aliceRoot.identityId).size,
            "no debe haber un segundo binding para el mismo par",
        )
    }

    // ==================================================================
    // PC-04 — `onPeerOffline` olvida presencia y enlace
    // ==================================================================

    @Test
    @Timeout(180)
    fun `PC-04 peerOffline olvida la presencia y el enlace`() {
        startRelay()
        val bobRoot = IdentityRoot()
        node(bobRoot).connectAndAwait()

        val aliceRoot = IdentityRoot()
        val aliceEngine = node(aliceRoot)
        val aliceManager = PeerTransportManager(aliceEngine)
        val aliceToBob = contextTowards(Device("bob"), emptyList(), bobRoot)
        aliceEngine.connectAndAwait()

        val driver = RelayPeerChannelDriver(aliceManager, aliceEngine, aliceEngine.relayIdentityId())
            .forPeer(aliceToBob)

        awaitOrFail("el rele debe anunciar a Bob", trace = { trace.toString() }) {
            aliceEngine.isPeerPresent(bobRoot.identityId)
        }
        driver.onPeerPresent(bobRoot.identityId)
        assertTrue(driver.isConnected(bobRoot.identityId), "precondicion: debe estar conectado")

        driver.onPeerOffline(bobRoot.identityId)

        assertFalse(driver.isConnected(bobRoot.identityId), "tras peerOffline no debe seguir conectado")

        // "La presencia se ha olvidado" se mide por su CONSECUENCIA: un aviso de
        // binding nuevo, sin una presencia nueva, NO puede reconectar. Si el
        // olvido no fuera real, este `onBindingCreated` bastaria.
        //
        // Y el rele sigue anunciando a Bob —el par no se ha ido de verdad—, asi
        // que esto mide OLVIDO, no disponibilidad: son dos hechos distintos y
        // confundirlos seria el defecto.
        assertTrue(
            aliceEngine.isPeerPresent(bobRoot.identityId),
            "el rele sigue anunciando a Bob: lo que se olvido es la memoria del conductor",
        )
        driver.onBindingCreated(bobRoot.identityId)
        assertFalse(
            driver.isConnected(bobRoot.identityId),
            "sin una presencia nueva el enlace no debe reabrirse",
        )
    }

    // ==================================================================
    // PC-05 — un par AJENO no abre el enlace de este conductor
    //
    // OJO: este caso fija el CONTRATO pero NO tiene dientes contra M3-c-bis; el
    // motivo esta en [PC-07], que si la detecta y explica por que.
    // ==================================================================

    @Test
    @Timeout(180)
    fun `PC-05 la presencia de un par ajeno no conecta el enlace de otro`() {
        startRelay()
        val bobRoot = IdentityRoot()
        val carolRoot = IdentityRoot()
        node(bobRoot).connectAndAwait()
        node(carolRoot).connectAndAwait()

        val aliceRoot = IdentityRoot()
        val aliceEngine = node(aliceRoot)
        val aliceManager = PeerTransportManager(aliceEngine)
        // El conductor de Alice esta declarado hacia BOB, y solo hacia Bob.
        val aliceToBob = contextTowards(Device("bob"), emptyList(), bobRoot)
        aliceEngine.connectAndAwait()

        val driver = RelayPeerChannelDriver(aliceManager, aliceEngine, aliceEngine.relayIdentityId())
            .forPeer(aliceToBob)

        awaitOrFail("el rele debe anunciar a los dos pares", trace = { trace.toString() }) {
            aliceEngine.isPeerPresent(bobRoot.identityId) && aliceEngine.isPeerPresent(carolRoot.identityId)
        }

        // La presencia de Carol es REAL: el rele la anuncia y el motor la tiene
        // en `onlinePeers`. Lo que no debe pasar es que el conductor la use.
        //
        // ATENCION, y esta medicion es la que obliga a decirlo: este caso NO
        // distingue el conductor CON guarda de par del conductor SIN ella
        // (mutacion M3-c-bis), y el motivo esta escrito entero en [PC-07]: en
        // `NEW` lo que para el intento es `manager.sendOffer`, con su
        // `PEER_ID_MISMATCH`. Este test fija el CONTRACTO —un par ajeno no se
        // conecta ni genera binding— y el que tiene dientes es [PC-07].
        driver.onPeerPresent(carolRoot.identityId)
        driver.onBindingCreated(carolRoot.identityId)

        assertFalse(
            driver.isConnected(carolRoot.identityId),
            "el conductor no declara a Carol: no puede decir que esta conectada",
        )
        assertFalse(
            driver.isConnected(bobRoot.identityId),
            "la presencia de Carol no puede abrir el enlace de Bob",
        )
        assertNull(
            aliceManager.getBinding(aliceRoot.identityId, carolRoot.identityId),
            "no debe existir binding para un par que el PeerContext no declara",
        )
    }

    // ==================================================================
    // PC-06 — con binding pero SIN presencia, el envio no puede salir
    // ==================================================================

    @Test
    @Timeout(180)
    fun `PC-06 sin presencia el envio falla INVALID_STATE_TRANSITION`() {
        startRelay()
        val bobRoot = IdentityRoot()
        // Bob NO se conecta: el rele no lo anuncia y no hay presencia posible.
        node(bobRoot)

        val aliceRoot = IdentityRoot()
        val aliceEngine = node(aliceRoot)
        val aliceManager = PeerTransportManager(aliceEngine)
        val aliceToBob = contextTowards(Device("bob"), emptyList(), bobRoot)
        aliceEngine.connectAndAwait()

        val driver = RelayPeerChannelDriver(aliceManager, aliceEngine, aliceEngine.relayIdentityId())
            .forPeer(aliceToBob)

        // El binding EXISTE y esta en `NEW`: es lo que deja
        // `SecureSessionEstablishment.openSession` al abrir la sesion. Que exista
        // no significa que se pueda enviar, y el fallo tiene que ser observable
        // y del tipo correcto.
        assertTrue(
            aliceManager.createBinding(aliceRoot.identityId, aliceToBob).isSuccess,
            "el PeerContext es de contacto verificado: autorizar el binding no es el problema",
        )
        assertFalse(
            aliceEngine.isPeerPresent(bobRoot.identityId),
            "Bob no se ha conectado: no hay presencia que el rele haya anunciado",
        )

        driver.onBindingCreated(bobRoot.identityId)
        driver.onPeerPresent(bobRoot.identityId)

        assertFalse(
            driver.isConnected(bobRoot.identityId),
            "sin presencia del rele el enlace no se abre, aunque el aviso diga que si",
        )
        assertEquals(
            PeerTransportState.NEW,
            aliceManager.getBinding(aliceRoot.identityId, bobRoot.identityId)?.state,
            "el binding se queda en NEW: la presencia es la que lo moveria",
        )

        val resultado = aliceManager.sendData(aliceRoot.identityId, aliceToBob, wire(0x33))
        assertTrue(resultado is TransportResult.Failure, "sin binding conectado no puede salir nada: $resultado")
        assertEquals(
            TransportError.INVALID_STATE_TRANSITION,
            (resultado as TransportResult.Failure).error,
            "el binding existe pero no esta CONNECTED: el fallo es de ESTADO",
        )
    }

    // ==================================================================
    // PC-07 — la presencia de OTRO no cierra el enlace de otro
    // ==================================================================

    /**
     * El UNICO escenario donde la guarda de par del conductor se nota.
     *
     * La medicion de una primera version de este test salio `NO DETECTADA`
     * contra la mutacion M3-c-bis (conductor sin `remotePeerId !=
     * peerContext.remotePeerId`), y el motivo —leido, no supuesto— es que el
     * MANAGER para el intento antes de que la guarda pueda importar:
     *
     *  - Si el par ajeno no tiene binding, `getBinding(local, ajeno)` sale
     *    `null`, y `createBinding(local, peerContext)` crea el binding DEL PAR
     *    DECLARADO en `NEW`; despues `sendOffer(local, ctx, "", ajeno)` falla con
     *    `PEER_ID_MISMATCH`. La guarda del conductor es ahi redundante.
     *  - Si el par ajeno tiene ya un binding activo pero en `NEW`,
     *    `createBinding` devuelve `BINDING_ALREADY_EXISTS` y el conductor sale
     *    antes de tocar nada.
     *
     * Lo que faltaba era el tercer caso: que el par ajeno tenga SU PROPIO binding
     * en `ANSWER_RECEIVED`. Ahi `getBinding(local, ajeno)` lo encuentra, la rama
     * `when` cae en `Unit` —porque a partir de `ANSWER_RECEIVED` el conductor ya
     * no vuelve a pasar por `sendOffer`— y lo unico que impide el cierre es la
     * guarda. Sin ella, la presencia de Carol cerraria el binding de BOB, que es
     * exactamente el fallo: un enlace `CONNECTED` hacia un par que no esta en el
     * rele.
     *
     * `ANSWER_RECEIVED` no es un estado artificial: es donde queda el binding de
     * cualquier conductor o consumidor que ofrecio y recibio respuesta antes de
     * la presencia. Aqui se monta con el API del manager —el mismo patron de
     * `SecureWorld.driveConnected` sin su ultima linea— porque el estado
     * intermedio no tiene otro constructor. Lo que NO se monta a mano es la
     * PRESENCIA, que la anuncia el rele de verdad.
     */
    @Test
    @Timeout(180)
    fun `PC-07 la presencia de un par con binding propio NO cierra el enlace de otro`() {
        startRelay()
        val bobRoot = IdentityRoot()
        val carolRoot = IdentityRoot()
        // BOB no se conecta: el rele no lo anuncia y su presencia es imposible.
        node(bobRoot)
        // Carol si, y su presencia es real.
        node(carolRoot).connectAndAwait()

        val aliceRoot = IdentityRoot()
        val aliceEngine = node(aliceRoot)
        val aliceManager = PeerTransportManager(aliceEngine)
        val aliceToBob = contextTowards(Device("bob"), emptyList(), bobRoot)
        val aliceToCarol = contextTowards(Device("carol"), emptyList(), carolRoot)
        aliceEngine.connectAndAwait()

        // Los DOS bindings, cada uno a MEDIO CICLO, que es el estado que solo
        // puede dejar un conductor o un consumidor. El de Bob es el que el
        // conductor declara; el de Carol es el que hace posible el caso.
        listOf(bobRoot.identityId to aliceToBob, carolRoot.identityId to aliceToCarol).forEach { (remote, ctx) ->
            assertTrue(
                aliceManager.createBinding(aliceRoot.identityId, ctx).isSuccess,
                "el PeerContext de ${remote.value.take(8)} es de contacto verificado",
            )
            assertTrue(
                aliceManager.sendOffer(aliceRoot.identityId, ctx, "", remote).isSuccess,
                "oferta vacia: el rele no produce SDP",
            )
            assertTrue(
                aliceManager.receiveAnswer(aliceRoot.identityId, remote, "").isSuccess,
                "respuesta vacia: el rele no produce SDP",
            )
        }
        assertEquals(
            PeerTransportState.ANSWER_RECEIVED,
            aliceManager.getBinding(aliceRoot.identityId, bobRoot.identityId)?.state,
            "precondicion: el enlace de Bob espera solo a que llegue SU presencia",
        )

        val driver = RelayPeerChannelDriver(aliceManager, aliceEngine, aliceEngine.relayIdentityId())
            .forPeer(aliceToBob)

        awaitOrFail("el rele debe anunciar a Carol", trace = { trace.toString() }) {
            aliceEngine.isPeerPresent(carolRoot.identityId)
        }
        assertFalse(
            aliceEngine.isPeerPresent(bobRoot.identityId),
            "Bob no esta en el rele: su presencia no existe, y eso es justo lo que se comprueba",
        )

        driver.onPeerPresent(carolRoot.identityId)
        driver.onBindingCreated(carolRoot.identityId)

        assertFalse(
            driver.isConnected(carolRoot.identityId),
            "el conductor esta declarado hacia Bob: la presencia de Carol no lo conecta a ella",
        )
        assertFalse(
            driver.isConnected(bobRoot.identityId),
            "la presencia de Carol NO puede cerrar el enlace de Bob, que no esta en el rele",
        )
        assertEquals(
            PeerTransportState.ANSWER_RECEIVED,
            aliceManager.getBinding(aliceRoot.identityId, bobRoot.identityId)?.state,
            "el enlace de Bob se queda a medio ciclo: a quien no esta, no se le conecta",
        )
        assertEquals(
            PeerTransportState.ANSWER_RECEIVED,
            aliceManager.getBinding(aliceRoot.identityId, carolRoot.identityId)?.state,
            "y el de Carol tampoco: el conductor no gobierna el enlace de Carol",
        )
    }

    // ==================================================================
    // PC-08 — R1: duplicado sobre un binding que YA esta CONNECTED
    // ==================================================================

    /**
     * El escenario exacto del defecto: el binding esta `CONNECTED` y el memo
     * del conductor no lo sabe.
     *
     * Lo produce cualquier cosa que conecte el binding fuera de este conductor
     * —una segunda instancia de la pila, la sesion segura que abrio el binding,
     * o la reautenticacion que se prueba en PC-12—, y con el codigo anterior
     * esta llamada entraba en `markConnected` desde `CONNECTED` y lanzaba
     * dentro del frame-loop. Aqui el binding se conduce con el API del MANAGER,
     * que es el mismo ciclo que usa el conductor, y despues se le manda el
     * `PEER_ONLINE` duplicado.
     */
    @Test
    @Timeout(180)
    fun `PC-08 PEER_ONLINE duplicado con binding CONNECTED es idempotente - R1`() {
        val m = montaje()
        val estados = CopyOnWriteArrayList<PeerTransportState>()
        m.manager.onStateChange { _, state -> estados.add(state) }

        // Un binding CONNECTED que el conductor no ha conducido.
        assertTrue(m.manager.createBinding(m.alice, m.context).isSuccess)
        assertTrue(m.manager.sendOffer(m.alice, m.context, "", m.bob).isSuccess)
        assertTrue(m.manager.receiveAnswer(m.alice, m.bob, "").isSuccess)
        assertTrue(m.manager.markConnected(m.alice, m.context).isSuccess)
        val binding = m.manager.getBinding(m.alice, m.bob)
        assertEquals(PeerTransportState.CONNECTED, binding?.state, "precondicion: binding CONNECTED")
        assertFalse(m.driver.isConnected(m.bob), "sin memo no hay enlace declarado, aunque el binding este CONNECTED")

        awaitOrFail("el rele debe anunciar a Bob", trace = { trace.toString() }) {
            m.engine.isPeerPresent(m.bob)
        }
        m.driver.onPeerPresent(m.bob)
        // El duplicado que emitia cada reautenticacion.
        m.driver.onPeerOnline(m.bob)

        assertTrue(m.driver.isConnected(m.bob), "el conductor debe reconocer el canal que YA existe")
        assertSame(binding, m.manager.getBinding(m.alice, m.bob), "el binding no se recrea: es el mismo")
        assertEquals(1, m.manager.bindingsForLocalIdentity(m.alice).size, "un solo binding por par")
        assertEquals(
            1,
            estados.count { it == PeerTransportState.CONNECTED },
            "UN solo evento CONNECTED en todo el escenario: $estados",
        )
    }

    // ==================================================================
    // PC-09 — R3: binding CLOSED se reabre con uno NUEVO
    // ==================================================================

    @Test
    @Timeout(180)
    fun `PC-09 un binding CLOSED se reemplaza y vuelve a CONNECTED - R3`() {
        val m = montaje()
        awaitOrFail("el rele debe anunciar a Bob", trace = { trace.toString() }) {
            m.engine.isPeerPresent(m.bob)
        }
        m.driver.onPeerPresent(m.bob)
        assertTrue(m.driver.isConnected(m.bob), "precondicion: conectado")
        val cerrado = m.manager.getBinding(m.alice, m.bob)

        assertTrue(m.manager.closeBinding(m.alice, m.bob).isSuccess)
        assertFalse(
            m.driver.isConnected(m.bob),
            "binding CLOSED: la conjuncion memo+binding no puede seguir afirmando enlace",
        )

        m.driver.onPeerOnline(m.bob)

        val nuevo = m.manager.getBinding(m.alice, m.bob)
        assertNotSame(cerrado, nuevo, "CLOSED se REEMPLAZA: createBinding no reabre el inactivo")
        assertEquals(PeerTransportState.CONNECTED, nuevo?.state, "el par recupera canal sin reiniciar el proceso")
        assertTrue(m.driver.isConnected(m.bob))
        assertEquals(1, m.manager.bindingsForLocalIdentity(m.alice).size, "un solo binding, el nuevo")
    }

    // ==================================================================
    // PC-10 — R3: binding FAILED se reabre con uno NUEVO
    // ==================================================================

    @Test
    @Timeout(180)
    fun `PC-10 un binding FAILED se reemplaza y vuelve a CONNECTED - R3`() {
        val m = montaje()
        awaitOrFail("el rele debe anunciar a Bob", trace = { trace.toString() }) {
            m.engine.isPeerPresent(m.bob)
        }
        m.driver.onPeerPresent(m.bob)
        assertTrue(m.driver.isConnected(m.bob), "precondicion: conectado")
        val fallido = m.manager.getBinding(m.alice, m.bob)

        assertTrue(m.manager.failBinding(m.alice, m.bob, "timeout ICE simulado").isSuccess)
        assertFalse(m.driver.isConnected(m.bob))

        m.driver.onPeerOnline(m.bob)

        val nuevo = m.manager.getBinding(m.alice, m.bob)
        assertNotSame(fallido, nuevo, "FAILED se REEMPLAZA: no se reabre el fallido")
        assertEquals(PeerTransportState.CONNECTED, nuevo?.state)
        assertTrue(m.driver.isConnected(m.bob))
    }

    // ==================================================================
    // PC-11 — R1/R3: aviso de binding y presencia concurrentes
    // ==================================================================

    /**
     * El aviso del manager y el frame de presencia llegan por hilos distintos.
     * Dos intentos a la vez no pueden producir dos bindings ni dos
     * `CONNECTED`: el monitor del conductor es por par y dentro se relee el
     * estado.
     */
    @Test
    @Timeout(180)
    fun `PC-11 install y presencia concurrentes producen UN handshake - R1`() {
        val m = montaje()
        val estados = CopyOnWriteArrayList<PeerTransportState>()
        m.manager.onStateChange { _, state -> estados.add(state) }
        awaitOrFail("el rele debe anunciar a Bob", trace = { trace.toString() }) {
            m.engine.isPeerPresent(m.bob)
        }

        val salida = java.util.concurrent.CountDownLatch(1)
        val hilos = listOf(
            kotlin.concurrent.thread(name = "install") { salida.await(); m.driver.onBindingCreated(m.bob) },
            kotlin.concurrent.thread(name = "presence") { salida.await(); m.driver.onPeerPresent(m.bob) },
        )
        salida.countDown()
        hilos.forEach { it.join(120_000) }
        hilos.forEach { assertFalse(it.isAlive, "un intento no puede quedarse colgado del lock") }

        assertTrue(m.driver.isConnected(m.bob), "el resultado converge a conectado")
        assertEquals(1, m.manager.bindingsForLocalIdentity(m.alice).size, "un solo binding")
        assertEquals(
            1,
            estados.count { it == PeerTransportState.CONNECTED },
            "un solo handshake: $estados",
        )
    }

    // ==================================================================
    // PC-12 — R2: reautenticacion (OFFLINE + ONLINE) con binding CONNECTED
    // ==================================================================

    /**
     * `MessageRouter.establishSession` desregistra y registra sus handlers en
     * cada reautenticacion, y eso emite `PEER_OFFLINE` + `PEER_ONLINE` con el
     * binding todavia `CONNECTED`.
     *
     * Aqui se mide el contrato: el memo se olvida (PC-04), el binding NO se
     * resetea ni se recrea, y el canal se restablece UNA vez. NO hay segundo
     * `CONNECTED` porque el binding nunca dejo de estarlo: la autoridad es el
     * binding y no hay transicion que hacer. Eso es idempotencia, no una
     * conexion nueva.
     */
    @Test
    @Timeout(180)
    fun `PC-12 reauth OFFLINE y ONLINE conserva UN binding y el canal - R2`() {
        val m = montaje()
        val estados = CopyOnWriteArrayList<PeerTransportState>()
        m.manager.onStateChange { _, state -> estados.add(state) }
        awaitOrFail("el rele debe anunciar a Bob", trace = { trace.toString() }) {
            m.engine.isPeerPresent(m.bob)
        }
        m.driver.onPeerPresent(m.bob)
        assertTrue(m.driver.isConnected(m.bob), "precondicion: conectado")
        val binding = m.manager.getBinding(m.alice, m.bob)
        assertEquals(1, estados.count { it == PeerTransportState.CONNECTED }, "precondicion: un CONNECTED")

        // Reauth: el rele dice que el par se fue...
        m.driver.onPeerOffline(m.bob)
        assertFalse(m.driver.isConnected(m.bob), "OFFLINE olvida el memo (PC-04)")
        assertEquals(
            PeerTransportState.CONNECTED,
            m.manager.getBinding(m.alice, m.bob)?.state,
            "OFFLINE no toca el binding: cerrarlo es del manager",
        )

        // ...y vuelve a anunciarlo.
        m.driver.onPeerOnline(m.bob)

        assertTrue(m.driver.isConnected(m.bob), "ONLINE restablece el canal")
        assertSame(binding, m.manager.getBinding(m.alice, m.bob), "el binding NO se resetea ni se recrea")
        assertEquals(1, m.manager.bindingsForLocalIdentity(m.alice).size, "un solo binding")
        assertEquals(
            1,
            estados.count { it == PeerTransportState.CONNECTED },
            "la reauth no re-negocia: un solo CONNECTED en total: $estados",
        )
        assertTrue(
            m.manager.sendData(m.alice, m.context, wire(0x44)).isSuccess,
            "el canal sigue sirviendo despues de la reautenticacion",
        )
    }

    // ------------------------------------------------------------------
    // Montaje comun de PC-08..PC-12
    // ------------------------------------------------------------------

    private class Montaje(
        val alice: com.km.model.IdentityId,
        val bob: com.km.model.IdentityId,
        val engine: JvmRelayTransport,
        val manager: PeerTransportManager,
        val driver: RelayPeerChannelDriver,
        val context: PeerContext,
    )

    /**
     * Alice y Bob en el rele real, con el conductor de Alice declarado hacia
     * Bob. Bob queda ONLINE antes de devolver, de modo que su presencia es un
     * hecho del rele y no una lista maquillada.
     */
    private fun montaje(): Montaje {
        startRelay()
        val bobRoot = IdentityRoot()
        node(bobRoot).connectAndAwait()

        val aliceRoot = IdentityRoot()
        val aliceEngine = node(aliceRoot)
        val manager = PeerTransportManager(aliceEngine)
        val context = contextTowards(Device("bob"), emptyList(), bobRoot)
        aliceEngine.connectAndAwait()

        val driver = RelayPeerChannelDriver(manager, aliceEngine, aliceEngine.relayIdentityId())
            .forPeer(context)

        return Montaje(
            alice = aliceRoot.identityId,
            bob = bobRoot.identityId,
            engine = aliceEngine,
            manager = manager,
            driver = driver,
            context = context,
        )
    }
}
