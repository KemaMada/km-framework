package com.km.messaging

import com.km.crypto.X25519
import com.km.crypto.provider.BcChaCha20Poly1305
import com.km.crypto.provider.BcHkdfSha256
import com.km.crypto.provider.BcX25519
import com.km.frame.SecureFrameProtector
import com.km.identity.ContactBundle
import com.km.identity.KmTestDevice
import com.km.identity.KmTestIdentityRoot
import com.km.node.FakeTransportBackend
import com.km.node.PeerContext
import com.km.node.PeerTransportManager
import com.km.node.PeerTransportState
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CS — el camino de CONTACTO, de punta a punta.
 *
 * ## LO QUE AFIRMA
 *
 * Que un contacto con un `ContactBundle` verificado abre sesion E2E, envia y
 * descifra, SIN el handshake de nodo. Un `AuthSession` no aparece en ninguna
 * linea del guion de CS-01, CS-02 y CS-03: eso no es estilo, es la afirmacion.
 * Si estos tests fueran verdes con un `AuthSession` fabricado en nombre del par,
 * no estarian midiendo el camino de contacto.
 *
 * ## POR QUE NO SE REUSA `SecureWorld`
 *
 * `SecureWorld` es aditiva y muy comoda, pero su `KeyMessageCoreImpl` trae
 * ademas el camino LEGADO de mensajeria en claro. Un guion que pasa por ahi
 * mide mas cosas de las que dice medir. Aqui esta solo lo que hace falta para
 * el establecimiento, el envio y la recepcion.
 *
 * ## RAIZ Y DISPOSITIVO SON DOS CLAVES DISTINTAS
 *
 * La raiz firma los bundles y es la que indexa la sesion; el dispositivo aporta
 * el SPK y los OPK que X3DH necesita. Que sean distintas no es decoracion: si
 * coincidieran, el id de enrutado dejaria de depender del material con el que
 * se cifra.
 */
class ContactSessionE2ETest {

    private val x25519: X25519 = BcX25519()
    private val kdf = BcHkdfSha256()
    private val protector = SecureFrameProtector(BcChaCha20Poly1305(), kdf)
    private val establishment = SecureSessionEstablishment(x25519, kdf, protector)

    // ===================================================================
    // Topologia
    // ===================================================================

    /**
     * Un extremo del mundo: un dispositivo, SU raiz, y el manager de par.
     *
     * `transport` es de ESTE extremo, luego dos extremos de pruebas distintas
     * no comparten binding y pueden convivir sesiones de la misma raiz.
     */
    private class Lado(val device: KmTestDevice, val root: KmTestIdentityRoot) {

        val transport = PeerTransportManager(FakeTransportBackend(), now = { 0L })

        /** Identidad local de la CAPA DE SESION: la del dispositivo (asimetria D-C). */
        val identidadDeSesion = device.identityId

        fun initiatorKeys() = LocalInitiatorKeys(device.deviceId, device.agreementKey)

        fun responderKeys(opkId: Long?) = LocalResponderKeys(
            deviceId = device.deviceId,
            agreementKey = device.agreementKey,
            signedPreKey = device.signedPreKey,
            oneTimePreKey = opkId?.let {
                requireNotNull(device.oneTimePreKey(it)) { "OPK $it sin emitir en ${device.name}" }
            },
            oneTimePreKeyId = opkId,
        )

        /**
         * `PeerContext` de CONTACTO hacia [otro], con SU bundle verificado.
         *
         * No hay `AuthSession` ni `NodeAnnouncement`: la autoridad es el bundle.
         */
        fun ctxHacia(otro: Lado, opkIds: List<Long> = emptyList()): PeerContext =
            PeerContext.forContact(
                ContactBundle.verified(otro.root.bundleFor(listOf(otro.device), otro.device, opkIds)),
            )

        /** Conduce SU binding a CONNECTED por el API del manager. */
        fun conectar(sesion: SecureMessagingSession) {
            val m = sesion.transportManager
            val ctx = sesion.peerContext
            m.sendOffer(sesion.localIdentity, ctx, "sdp-offer", sesion.remotePeerId).getOrThrow()
            m.receiveAnswer(sesion.localIdentity, sesion.remotePeerId, "sdp-answer").getOrThrow()
            m.markConnected(sesion.localIdentity, ctx).getOrThrow()
        }

        /** Los bytes que salen por el manager, sin entregarlos a nadie. */
        fun captura(): MutableList<ByteArray> {
            val capturados = mutableListOf<ByteArray>()
            transport.onData { _, data -> capturados.add(data) }
            return capturados
        }
    }

    private class Par(
        val a: Lado,
        val b: Lado,
        val deA: SecureMessagingSession,
        val deB: SecureMessagingSession,
    )

    /**
     * Establece la sesion en LOS DOS lados con las piezas de verdad.
     *
     * @param opkId `keyId` del one-time prekey de B, o `null` para una sesion
     *   sin `DH4`.
     */
    private fun emparejar(a: Lado, b: Lado, opkId: Long? = null): Par {
        opkId?.let { b.device.issueOneTimePreKey(it) }
        val iniciada = establishment.initiate(
            localIdentity = a.identidadDeSesion,
            peerContext = a.ctxHacia(b, listOfNotNull(opkId)),
            localKeys = a.initiatorKeys(),
            transport = a.transport,
            oneTimePrekeyIndex = opkId?.let { 0 },
        )
        val aceptada = establishment.accept(
            localIdentity = b.identidadDeSesion,
            peerContext = b.ctxHacia(a),
            localKeys = b.responderKeys(iniciada.usedOneTimePreKeyId),
            prekeyFrameWire = iniciada.prekeyFrame,
            transport = b.transport,
        )
        a.conectar(iniciada.session)
        b.conectar(aceptada.session)
        return Par(a, b, iniciada.session, aceptada.session)
    }

    // ===================================================================
    // CS-01 — la autoridad de contacto abre sesion, sin AuthSession
    // ===================================================================

    @Test
    fun `CS-01 la autoridad de contacto abre sesion sin AuthSession`() {
        val par = emparejar(
            Lado(KmTestDevice("alice"), KmTestIdentityRoot()),
            Lado(KmTestDevice("bob"), KmTestIdentityRoot()),
            opkId = 1000L,
        )
        val ctx = par.deA.peerContext

        assertEquals(par.b.root.identityId, ctx.remotePeerId, "el par remoto es la RAIZ del contacto")
        assertNull(ctx.authSession, "y no hay sesion de autenticacion de nodo")
        assertNull(ctx.announcement, "ni anuncio de nodo: no se fabrico ninguno en nombre del par")
        assertNotNull(ctx.contactBundle, "la autoridad es el bundle verificado")
        assertTrue(ctx.authority.authorizesPeerSession(), "y una autoridad de contacto autoriza la sesion")

        // El pretesto comprueba que raiz y dispositivo NO son lo mismo.
        assertNotEquals(
            par.b.device.identityId,
            par.b.root.identityId,
            "el pretesto usa claves de verdad distintas para raiz y dispositivo",
        )
        assertEquals(PeerTransportState.CONNECTED, par.deA.bindingState(), "el enlace queda CONNECTED")
    }

    // ===================================================================
    // CS-02 / CS-03 — el contacto envia, y el otro extremo descifra
    // ===================================================================

    @Test
    fun `CS-02 el contacto envia y el otro extremo descifra`() {
        val par = emparejar(
            Lado(KmTestDevice("alice"), KmTestIdentityRoot()),
            Lado(KmTestDevice("bob"), KmTestIdentityRoot()),
            opkId = 2000L,
        )
        val capturados = par.a.captura()

        // Contenido NO constante y de longitud propia: un wire de tamano
        // inesperado haria que la afirmacion fuera sobre una coincidencia.
        val texto = "primer mensaje de contacto, con carga propia 4409".toByteArray()
        val enviado = par.deA.send(texto)
        assertTrue(enviado is SendResult.Ok, "un contacto envia y NO recibe NOT_AUTHENTICATED: fue $enviado")
        assertEquals(1, capturados.size, "un unico frame en el cable")

        par.deB.onTransportBytes(capturados.single())
        assertContentEquals(texto, par.deB.drainOutbound().single(), "el otro extremo descifra el texto original")
    }

    @Test
    fun `CS-03 sendTranscript de un contacto pasa el mismo guard`() {
        val par = emparejar(
            Lado(KmTestDevice("alice"), KmTestIdentityRoot()),
            Lado(KmTestDevice("bob"), KmTestIdentityRoot()),
            opkId = 3000L,
        )
        val capturados = par.a.captura()

        // Existe precisamente porque `sendTranscript` tiene SU PROPIO guard, en
        // `SecureMessagingSession`, distinto del de `send`. CS-02 no lo toca:
        // sin CS-03 podria ocurrir que `send` autorizara y el reintento no.
        val wire = "segundo mensaje por el camino de reintento, mas largo 8813".toByteArray()
        val r = par.deA.sendTranscript(wire)
        assertTrue(r is SendResult.Ok, "el reintento de un contacto tambien pasa el guard: fue $r")
        assertEquals(1, capturados.size, "y sale por el cable exactamente una vez")
    }

    // ===================================================================
    // CS-04 / CS-05 — R-4: la clave de registro es la de la RAIZ
    // ===================================================================

    @Test
    fun `CS-04 dos dispositivos de la MISMA raiz comparten clave de registro`() {
        val raizDeBob = KmTestIdentityRoot()
        val bob1 = KmTestDevice("bob-1")
        val bob2 = KmTestDevice("bob-2")
        assertNotEquals(bob1.identityId, bob2.identityId, "el pretesto usa dos dispositivos de verdad distintos")

        val ctx1 = PeerContext.forContact(
            ContactBundle.verified(raizDeBob.bundleFor(listOf(bob1), bob1, emptyList())),
        )
        val ctx2 = PeerContext.forContact(
            ContactBundle.verified(raizDeBob.bundleFor(listOf(bob2), bob2, emptyList())),
        )
        assertEquals(ctx1.remotePeerId, ctx2.remotePeerId, "dos dispositivos de una raiz: la MISMA clave")
        assertEquals(raizDeBob.identityId, ctx1.remotePeerId, "y esa clave es la de la RAIZ")
        assertNotEquals(bob1.identityId, ctx2.remotePeerId, "no es la del dispositivo, ni la del otro")

        // Y el efecto observable en el registro: una sesion por raiz, y la
        // segunda sustituye a la primera. Dos lados de A distintos porque el
        // binding es por `(local, remotePeerId)`: si compartieran manager, el
        // segundo rebotaria con `BINDING_ALREADY_EXISTS` ANTES de llegar aqui.
        val primera = emparejar(
            Lado(KmTestDevice("alice"), KmTestIdentityRoot()),
            Lado(bob1, raizDeBob),
        ).deA
        val segunda = emparejar(
            Lado(KmTestDevice("alice"), KmTestIdentityRoot()),
            Lado(bob2, raizDeBob),
        ).deA
        assertEquals(primera.remotePeerId, segunda.remotePeerId, "las dos sesiones cuelgan de la misma clave")

        val registro = SecureSessionRegistry(primera.localIdentity)
        registro.install(primera)
        registro.install(segunda)
        assertEquals(1, registro.size(), "una sesion por raiz: la segunda SUSTITUYE a la primera")
        assertSame(segunda, registro.find(raizDeBob.identityId), "y la que queda es la ultima instalada")
    }

    /**
     * CS-05 — dos RAICES distintas conviven en el MISMO registro.
     *
     * La version anterior de este test afirmaba "raices distintas, claves
     * distintas" comparando dos `remotePeerId` sueltos y luego installando en un
     * registro. Eso era un test FALSO por una razon concreta: comparar dos
     * `IdentityId` entre si no dice nada sobre cual es la clave de registro,
     * porque los DISPOSITIVOS de Bob y de Carol tambien son distintos. La
     * asercion pasaria igual con el codigo indexando por `subjectDeviceId`, que
     * es justo lo que R-4 prohibe. Un test que no puede caerse no mide.
     *
     * Por eso aqui la unica evidencia es el OBSERVADOR REAL: se instalan las dos
     * sesiones en un `SecureSessionRegistry` de verdad y se busca por RAIZ. Si la
     * clave fuera el dispositivo, `find(raiz)` daria `null` y el test caeria.
     */
    @Test
    fun `CS-05 dos raices distintas conviven en el mismo registro, indexadas por su raiz`() {
        val alice = Lado(KmTestDevice("alice"), KmTestIdentityRoot())
        val bob = Lado(KmTestDevice("bob"), KmTestIdentityRoot())
        val carol = Lado(KmTestDevice("carol"), KmTestIdentityRoot())

        // El pretesto tiene que DOLER: sin esto, "distintas" seria una palabra
        // vacia y el test pasaria con tres identidadesdegeneradas.
        assertNotEquals(alice.root.identityId, bob.root.identityId, "raices de verdad distintas")
        assertNotEquals(bob.root.identityId, carol.root.identityId, "raices de verdad distintas")
        assertNotEquals(bob.device.identityId, carol.device.identityId, "dispositivos de verdad distintos")

        // UNA identidad local y UN manager para las dos sesiones: es el
        // escenario real de una aplicacion con dos contactos, y es el unico donde
        // "conviven" significa algo. Con managers separados cada registro tendria
        // una sesion y la afirmacion no tendria contenido.
        val manager = PeerTransportManager(FakeTransportBackend(), now = { 0L })

        val conBob = establishment.initiate(
            localIdentity = alice.device.identityId,
            peerContext = alice.ctxHacia(bob),
            localKeys = alice.initiatorKeys(),
            transport = manager,
            oneTimePrekeyIndex = null,
        ).session
        val conCarol = establishment.initiate(
            localIdentity = alice.device.identityId,
            peerContext = alice.ctxHacia(carol),
            localKeys = alice.initiatorKeys(),
            transport = manager,
            oneTimePrekeyIndex = null,
        ).session

        val registro = SecureSessionRegistry(alice.device.identityId)
        registro.install(conBob)
        registro.install(conCarol)

        // --- LO QUE SE AFIRMA: el registro, no dos identidades sueltas. ---
        assertEquals(2, registro.size(), "las dos conversaciones conviven en un registro")
        assertSame(conBob, registro.find(bob.root.identityId), "la de Bob se recupera por la RAIZ de Bob")
        assertSame(conCarol, registro.find(carol.root.identityId), "la de Carol, por la RAIZ de Carol")

        // Y el reverso, que es lo que separa "indexa por raiz" de "indexa por
        // algo": el id del DISPOSITIVO no recupera nada.
        assertNull(registro.find(bob.device.identityId), "el id del dispositivo NO es la clave de registro")
        assertNull(registro.find(carol.device.identityId), "tampoco en el otro sentido")

        // Y no es una asercion sobre una copia: las dos sesiones son las que
        // `initiate` devolvio, no dos objetos parecidos.
        assertNotSame(conBob, conCarol, "dos sesiones de verdad, no una releida")
    }
}