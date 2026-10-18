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
import org.junit.jupiter.api.Assertions.assertNull
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
 * ## LO QUE ESTA FUERA, Y SE DICE
 *
 * El ciclo `onPeerOffline` -> nueva presencia esta DECLARADO como defecto
 * abierto: `onPeerOffline` saca al par de `conectados` pero deja el binding en
 * `CONNECTED`, y volver a presentar presencia mete al par en `markConnected` desde
 * `CONNECTED`, que `withConnected` rechaza. Ver el CHANGELOG. Este fichero
 * comprueba lo que el contrato SI dice —que la presencia se olvida— y no fija
 * como invariante un comportamiento que es un fallo.
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
}
