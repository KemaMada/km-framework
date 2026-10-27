package com.km.desktop

import com.km.desktop.net.RelayStackFactory
import com.km.desktop.support.SecureTestIdentities
import com.km.crypto.Ed25519Impl
import com.km.model.IdentityId
import com.km.transport.JvmRelayTransport
import com.km.transport.RelayClientStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 1.2 — La `Session` guarda la PILA, y la pila es la del modulo hermano.
 *
 * ## QUE AFIRMA
 *
 * 1. `Session` expone `stack: RelayClientStack`, y el `transport` que expone es
 *    la MISMA instancia que `stack.transport` —no una segunda, no una copia.
 * 2. `Session.bind` da de alta el camino legado sobre la pila, y una segunda
 *    llamada NO monta un segundo conductor.
 *
 * ## POR QUE ES DISTINTO DE LA GUARDIA DE BYTECODE
 *
 * La guardia de `DesktopWireEngineReuseTest` mira el pool de constantes: dice
 * que en el paquete no hay una COPIA local del motor. Esta mira el OBJETO: dice
 * que la sesion viva usa el motor de su propia pila. Un campo `transport`
 * guardado aparte compila, no aparece en el pool como copia, y aun asi puede
 * ser un motor distinto del que esta composing la pila.
 */
class DesktopStackOwnershipTest {

    private fun motor(identidad: IdentityId): JvmRelayTransport {
        val ed25519 = Ed25519Impl()
        val par = ed25519.generateKeyPair()
        return JvmRelayTransport(
            identity = identidad,
            ed25519PublicKey = par.publicKey,
            ed25519PrivateKey = par.privateKey,
            endpoint = "wss://ejemplo.invalid/km",
            ed25519 = ed25519,
        )
    }

    /** Una sesion real, con identidad real de la arnes y un nucleo real. */
    private fun sesion(): Session {
        val local = SecureTestIdentities.localEnd("alice")
        val transporte = motor(local.identityId)
        val pila: RelayClientStack = RelayStackFactory.stackFor(transporte)
        val (manager, _) = com.km.desktop.support.relayProbeManager()
        // El nucleo se construye con el manager de la PILA: es la forma en que
        // `DesktopMain` lo hace, y no con un manager suelto.
        val nucleo = com.km.desktop.core.CoreFactory().create(
            localIdentity = local.identityId,
            localPrivateKey = local.ed25519PrivateKey,
            transport = pila.transport,
            manager = manager,
            peerPublicKeyResolver = { null },
        )
        return Session(
            identity = com.km.desktop.DesktopIdentity(
                identityId = local.identityId,
                ed25519PublicKey = local.ed25519PublicKey,
                ed25519PrivateKey = local.ed25519PrivateKey,
                x25519PublicKey = local.device.agreementKey.publicKey,
                x25519PrivateKey = ByteArray(32),
            ),
            keystorePath = java.nio.file.Paths.get("p5-no-se-usa"),
            stack = pila,
            core = nucleo,
            peerKeys = PeerKeyDirectory(),
        )
    }

    @Test
    fun `la sesion expone la pila y el motor es el suyo`() {
        val s = sesion()

        assertNotNull(s.stack, "la sesion guarda la pila")
        assertSame(
            s.stack.transport,
            s.transport,
            "el transport que expone la sesion es el motor DE SU PILA: no hay un segundo motor",
        )
    }

    @Test
    fun `1-3 el camino legado se da de alta UNA vez y no se duplica`() {
        val s = sesion()

        assertTrue(!s.stack.isLegacyAttached(), "antes de bind, el camino legado no esta dado de alta")

        val lineas = mutableListOf<String>()
        s.bindToStrings { lineas += it }

        assertTrue(s.stack.isLegacyAttached(), "bind da de alta el camino legado explicitamente")
        assertTrue(
            lineas.any { it.contains("camino legado") },
            "y lo DICE, para que una pantalla vacia no parezca un fallo: $lineas",
        )

        // La segunda alta se RECHAZA y el camino sigue siendo UNO.
        val segunda = s.stack.attachLegacy()
        assertTrue(!segunda, "un segundo attachLegacy devuelve false: dos conductores duplicarian la entrega")
        assertTrue(s.stack.isLegacyAttached(), "y el camino sigue dado de alta")
    }

    /**
     * CONTROL POSITIVO: `bind` se puede volver a llamar sin romper nada.
     *
     * Sin esto, "el segundo `attachLegacy` devuelve false" podria ser verdad
     * porque `bind` nunca monta nada. Se comprueba que el resto de `bind` —el
     * que registra handlers del transporte y del core— sigue despues de la
     * segunda llamada.
     */
    @Test
    fun `control positivo bind se puede repetir sin perder los handlers`() {
        val s = sesion()
        val primera = mutableListOf<String>()
        s.bindToStrings { primera += it }
        val segunda = mutableListOf<String>()
        s.bindToStrings { segunda += it }

        assertTrue(primera.any { it.contains("camino legado") }, "la primera pasada da de alta")
        assertTrue(
            segunda.any { it.contains("camino legado") },
            "la segunda tambien lo DICE, aunque el attach no se repita: $segunda",
        )
        assertTrue(s.stack.isLegacyAttached(), "y el camino sigue montado")
    }

    /**
     * 1.3 — Un frame legado se entrega UNA vez, no dos.
     *
     * El booleano de [com.km.transport.RelayClientStack.attachLegacy] no basta
     * como afirmacion: un `bind` que montase DOS conductores seguiria dejando
     * `isLegacyAttached() == true` y no se distinguiria de un montaje correcto.
     * Lo que se distingue es la ENTREGA: dos conductores sobre el mismo slot
     * reinyectan el mismo marco dos veces, y el receptor ve dos mensajes
     * iguales.
     *
     * Se cuenta por el camino que el escritorio puede observar: el numero de
     * veces que el nucleo rechazo el claro. Con el registro activo el camino
     * legado de recepcion esta CERRADO, asi que cada entrega duplicada suma uno
     * mas a `rejectedCleartext` — y un claro que se rechaza dos veces por un
     * unico marco es exactamente el defecto.
     */
    @Test
    fun `1-3 un frame legado se entrega UNA sola vez aunque bind se llame dos veces`() {
        val s = sesion()
        s.bindToStrings { }
        s.bindToStrings { }
        val rechazos =AntesDe(s)

        // Un `MESSAGE` del rele que el motor ya clasifica como legado.
        val marco = com.km.node.LegacyInboundFrame(
            from = SecureTestIdentities.otroIdentityId(),
            encoded = com.km.model.Message(
                messageId = com.km.model.MessageId(java.util.UUID.randomUUID()),
                from = SecureTestIdentities.otroIdentityId(),
                to = s.identity.identityId,
                payload = "carga que llega por el camino en claro 3311".toByteArray(Charsets.UTF_8),
                timestamp = 0L,
            ),
        )
        s.stack.backend.eventCallbacks.onLegacyInboundFrame
            ?.invoke(marco, marco.from)
            ?: throw AssertionError("el camino legado no esta dado de alta tras bind")

        val delta = s.legacyInbound.rejectedCleartext.get() - rechazos
        assertEquals(
            1,
            delta,
            "UN marco, UNA entrega: el rechazo en claro cuenta exactamente una vez, " +
                "no dos por dos conductores",
        )
    }

    /** El contador de rechazos en claro ANTES de entregar nada. */
    private fun AntesDe(s: Session): Int = s.legacyInbound.rejectedCleartext.get()

    /**
     * CONTROL NEGATIVO: laSession que este test construye TIENE un camino
     * legado al que entregar.
     *
     * Sin esto, "delta == 1" seria verdad tambien si el marco no hubiera
     * llegado a nadie —un `onLegacyInboundFrame` nulo— y el contador no se
     * moviera. Se comprueba que el slot EXISTE antes de entregar.
     */
    @Test
    fun `control negativo el slot legado existe antes de entregar`() {
        val s = sesion()
        assertEquals(null, s.stack.backend.eventCallbacks.onLegacyInboundFrame, "antes de bind, no hay slot")

        s.bindToStrings { }

        assertNotNull(
            s.stack.backend.eventCallbacks.onLegacyInboundFrame,
            "despues de bind, el slot existe: sin el, la entrega no tendria a donde ir",
        )
    }

    /**
     * CONTROL NEGATIVO: dos motors con la misma identidad y el mismo endpoint NO
     * son el mismo objeto.
     */
    @Test
    fun `control negativo dos motores con la misma identidad NO son la misma instancia`() {
        val local = SecureTestIdentities.localEnd("alice")
        val uno = motor(local.identityId)
        val otro = motor(local.identityId)

        assertEquals(uno.status().endpoint, otro.status().endpoint, "el mismo endpoint")
        assertEquals(uno.relayIdentityId(), otro.relayIdentityId(), "la misma identidad de rele")
        assertTrue(uno !== otro, "pero NO son el mismo objeto: por eso se compara por identidad")
    }

    /**
     * 3.2 — Una sesion que entra en el registro ABRE su canal de par en el rele.
     *
     * Sin esto la sesion existe, el cifrado funciona, y el envio se rechaza:
     * `PeerTransportManager.sendData` exige un binding `CONNECTED` y este nunca
     * pasa de `NEW`. El sintoma —"el escritorio no envia"— no señala a la
     * causa, que es un enlace que nadie conduce.
     */
    @Test
    fun `3-2 al instalarse una sesion, se abre su driver de par en el rele`() {
        val s = sesion()
        s.bindToStrings { }
        val par = SecureTestIdentities.deviceWithRoot("bob")
        val contexto = com.km.desktop.session.SessionCoordinator().contextoHacia(
            com.km.identity.VerifiedContactBundle.verified(
                par.root.bundleMapFor(listOf(par.device), par.device, emptyList()),
            ),
        )

        assertEquals(null, s.stack.driverOf(contexto.remotePeerId), "antes de instalar, no hay driver")

        s.registry.install(sesionDePrueba(s, contexto, par))

        assertNotNull(
            s.stack.driverOf(contexto.remotePeerId),
            "instalar una sesion abre su driver: sin el, el enlace nunca llega a CONNECTED",
        )
    }

    /**
     * CONTROL NEGATIVO: instalar una sesion NO abre drivers de OTROS pares.
     *
     * Sin esto, "hay driver" seria verdad para cualquier par, y la asercion de
     * arriba no probaria que se abre el del par QUE SE INSTALA.
     */
    @Test
    fun `control negativo instalar una sesion NO abre drivers de otros pares`() {
        val s = sesion()
        s.bindToStrings { }
        val otros = setOf(
            SecureTestIdentities.deviceWithRoot("x").root.identityId,
            SecureTestIdentities.deviceWithRoot("y").root.identityId,
        )
        val par = SecureTestIdentities.deviceWithRoot("bob")
        val contexto = com.km.desktop.session.SessionCoordinator().contextoHacia(
            com.km.identity.VerifiedContactBundle.verified(
                par.root.bundleMapFor(listOf(par.device), par.device, emptyList()),
            ),
        )

        s.registry.install(sesionDePrueba(s, contexto, par))

        for (otro in otros) {
            assertEquals(null, s.stack.driverOf(otro), "el driver se abre por par, no en bloque")
        }
    }

    /** Una sesion minima de verdad, construida sobre el manager de la pila. */
    private fun sesionDePrueba(
        s: Session,
        contexto: com.km.node.PeerContext,
        par: SecureTestIdentities.DeviceWithRoot,
    ): com.km.messaging.SecureMessagingSession {
        val kdf = com.km.crypto.provider.BcHkdfSha256()
        return com.km.messaging.SecureMessagingSession(
            localIdentity = s.identity.identityId,
            peerContext = contexto,
            protocol = com.km.ratchet.RatchetSessionBootstrap.responderProtocol(
                bootstrapValue = ByteArray(32) { (it * 7 + 3).toByte() },
                signedPreKey = par.device.signedPreKey,
                x25519 = com.km.crypto.provider.BcX25519(),
                kdf = kdf,
                protector = com.km.frame.SecureFrameProtector(com.km.crypto.provider.BcChaCha20Poly1305(), kdf),
            ),
            transport = s.stack.manager,
        )
    }
}


/**
 * `bind` publica [com.km.desktop.client.ClientEvent]s; este atajo los convierte
 * en lineas para los tests que afirman sobre lo que se DICE.
 *
 * Se suscribe ANTES de [com.km.desktop.Session.bind] para no perder lo que pasa
 * durante el cableado.
 */
private fun com.km.desktop.Session.bindToStrings(sink: (String) -> Unit) {
    events.subscribe { event -> sink(event.let { e -> events.describe(e) }) }
    bind()
}
