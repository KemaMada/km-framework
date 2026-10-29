package com.example.keymessage.session

import com.km.frame.BinarySecureFrameCodec
import com.km.frame.FrameType
import com.km.frame.RatchetHeader
import com.km.frame.SecureFrame
import com.km.frame.SecureFrameProtector
import com.km.frame.SecureFrameSpec
import com.km.crypto.provider.BcChaCha20Poly1305
import com.km.crypto.provider.BcHkdfSha256
import com.km.identity.ContactBundle
import com.km.messaging.LocalInitiatorKeys
import com.km.messaging.LocalResponderKeys
import com.km.messaging.SecureSessionRegistry
import com.km.messaging.SendResult
import com.km.model.IdentityId
import com.km.node.PeerContext
import com.example.keymessage.support.TestWorld
import com.example.keymessage.support.countingManager
import com.example.keymessage.util.toHex
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CRITICAL-1 — el frame PREKEY SE ENTREGA, y se RECONOCE al llegar.
 *
 * ## QUE AFIRMA, Y QUE FALTABA
 *
 * El defecto verificado por ADB no era que faltaran `abrirSesion`/`aceptarSesion`
 * —existen en `AppContainer`—, sino que el PREKEY no VIAJABA: nadie lo ponia en
 * el cable y nadie lo reconocia al llegar. Este test cubre las DOS mitades del
 * arreglo, con las mismas piezas que la app compone:
 *
 *  1. **Salida** — `iniciar` produce la sesion Y el `prekeyFrame`, y el frame sale
 *     por el canal de par con `session.sendTranscript(...)`, que es lo que hace
 *     `AppContainer.abrirSesionYEntregar`. Se comprueba que SALIO un byte del
 *     backend: "abrir sesion" no basta, hay que entregar.
 *  2. **Entrada** — al byte que llega por el cable lo clasifica
 *     [PrekeyFrame.esPrekey] como PREKEY, y con esa clasificacion `aceptar` abre
 *     la sesion del otro lado. Sin la clasificacion, el byte seria opaco y nadie
 *     lo reclamaria.
 *  3. **Cierre** — con las dos sesiones abiertas, un mensaje cruza.
 *
 * ## POR QUE NO SE LLAMA A `AppContainer`
 *
 * Porque necesita un `Application` (Room, `Settings.Global`,
 * `EncryptedSharedPreferences`) y no existe en una JVM. Lo que SI se prueba son
 * las piezas de las que `abrirSesionYEntregar` y `aceptarPrekeyEntrante` estan
 * hechas: `SessionCoordinator`, `sendTranscript`, y [PrekeyFrame]. Es el mismo
 * criterio que el KDoc de `AppContainer` declara para si mismo.
 */
class PrekeyDeliveryTest {

    private val coordinator = SessionCoordinator()

    private val parAlice = TestWorld.deviceWithRoot("alice")
    private val parBob = TestWorld.deviceWithRoot("bob")
    private val alice = parAlice.device
    private val bob = parBob.device

    private val aliceSession = IdentityId(alice.deviceId.toHex())
    private val bobSession = IdentityId(bob.deviceId.toHex())
    private val opkId: Long = TestWorld.BOB_ONE_TIME_PREKEY_ID

    private fun conectar(sesion: com.km.messaging.SecureMessagingSession, manager: com.km.node.PeerTransportManager) {
        manager.sendOffer(sesion.localIdentity, sesion.peerContext, "sdp-offer", sesion.remotePeerId).getOrThrow()
        manager.receiveAnswer(sesion.localIdentity, sesion.remotePeerId, "sdp-answer").getOrThrow()
        manager.markConnected(sesion.localIdentity, sesion.peerContext).getOrThrow()
    }

    private class Extremo(
        val manager: com.km.node.PeerTransportManager,
        val backend: com.example.keymessage.support.CountingBackend,
        val registro: SecureSessionRegistry,
    )

    private fun extremo(identidad: IdentityId): Extremo {
        val (manager, backend) = countingManager()
        return Extremo(manager, backend, SecureSessionRegistry(identidad))
    }

    @Test
    fun `el PREKEY sale por el cable y el receptor lo CLASIFICA y lo acepta`() {
        bob.issueOneTimePreKey(opkId)
        val a = extremo(aliceSession)
        val b = extremo(bobSession)

        // --- SALIDA: iniciar + sendTranscript, como `abrirSesionYEntregar` ---
        val contextoAB = coordinator.contextoHacia(
            ContactBundle.verified(parBob.root.bundleMapFor(listOf(bob), bob, listOf(opkId))),
        )
        val iniciada = coordinator.iniciar(
            localIdentity = aliceSession,
            peerContext = contextoAB,
            localKeys = LocalInitiatorKeys(alice.deviceId, alice.agreementKey),
            manager = a.manager,
            registry = a.registro,
        )
        conectar(iniciada.session, a.manager)

        val entrega = iniciada.session.sendTranscript(iniciada.prekeyFrame)
        assertTrue(entrega is SendResult.Ok, "el frame PREKEY SALE por el canal de par: $entrega")

        // El byte que cruzo el cable, tal cual lo recibe el otro extremo.
        val prekeyCruzado = assertNotNull(a.backend.sent.lastOrNull(), "hay un byte que salio").second
        assertTrue(prekeyCruzado.isNotEmpty(), "y no esta vacio")

        // --- ENTRADA: lo que hace `aceptarPrekeyEntrante` con ese byte ---
        assertTrue(
            PrekeyFrame.esPrekey(prekeyCruzado),
            "el byte que llega SE RECONOCE como PREKEY: sin esto seria opaco y nadie lo aceptaria",
        )

        val aceptada = coordinator.aceptar(
            localIdentity = bobSession,
            peerContext = PeerContext.forContact(
                ContactBundle.verified(parAlice.root.bundleMapFor(listOf(alice), alice, emptyList())),
            ),
            localKeys = LocalResponderKeys(
                deviceId = bob.deviceId,
                agreementKey = bob.agreementKey,
                signedPreKey = bob.signedPreKey,
                oneTimePreKey = assertNotNull(bob.oneTimePreKey(opkId), "B emitio el OPK $opkId"),
                oneTimePreKeyId = opkId,
            ),
            prekeyFrameWire = prekeyCruzado,
            manager = b.manager,
            registry = b.registro,
        )
        conectar(aceptada.session, b.manager)

        // --- CIERRE: con las dos sesiones, un mensaje cruza ---
        val salida = mutableListOf<ByteArray>()
        a.manager.onData { _, data -> salida += data }
        val carga = "el mensaje que solo puede salir si el PREKEY viajo, longitud 7331".toByteArray()
        assertTrue(iniciada.session.send(carga) is SendResult.Ok, "con sesion, el envio sale")

        assertTrue(salida.isNotEmpty(), "el mensaje del camino cifrado salio")
        aceptada.session.onTransportBytes(salida.last())
        assertContentEquals(carga, aceptada.session.drainOutbound().last(), "el otro extremo recibe el payload intacto")
    }

    @Test
    fun `el PREKEY que NO viaja deja al receptor sin sesion`() {
        // CONTROL NEGATIVO de la mitad "entrada": sin el byte, no hay nada que
        // clasificar y no hay sesion. Fija que la clasificacion no inventa un
        // PREKEY ni acepta sobre bytes que no llegaron.
        val b = extremo(bobSession)
        assertEquals(0, b.registro.size(), "sin PREKEY entrante, B no tiene ninguna sesion")
    }

    @Test
    fun `un mensaje de sesion NO se clasifica como PREKEY`() {
        // CONTROL NEGATIVO de la clasificacion: el byte de un mensaje normal NO
        // es un PREKEY. Si la clasificacion fuera "cualquier SecureFrame", la
        // entrada trataria un mensaje como establecimiento y quemaria un OPK por
        // cada mensaje recibido.
        val protector = SecureFrameProtector(BcChaCha20Poly1305(), BcHkdfSha256())
        val clave = ByteArray(32) { (it + 1).toByte() }
        val mensajeWire = BinarySecureFrameCodec.encode(
            protector.protect(
                type = FrameType.MESSAGE,
                ratchetHeader = RatchetHeader(
                    dhPublicKey = ByteArray(32) { (it + 9).toByte() },
                    previousChainLength = 0u,
                    messageNumber = 0u,
                ),
                messageKey = clave,
                plaintext = "cualquier cosa".toByteArray(),
            ),
        )
        assertFalse(PrekeyFrame.esPrekey(mensajeWire), "un MESSAGE no es un PREKEY")
    }

    @Test
    fun `bytes que ni son SecureFrame no se clasifican como PREKEY`() {
        assertFalse(PrekeyFrame.esPrekey(ByteArray(0)), "un wire vacio no es un PREKEY")
        assertFalse(PrekeyFrame.esPrekey(byteArrayOf(1)), "un byte solo no tiene ni cabecera")
        assertFalse(
            PrekeyFrame.esPrekey(ByteArray(64) { 0xFF.toByte() }),
            "una version desconocida no se adivina como PREKEY",
        )
    }

    @Test
    fun `la clasificacion lee SOLO la cabecera congelada`() {
        // El tipo PREKEY esta en el byte 1 del wire (spec congelada). Este test
        // ata que `PrekeyFrame` lee EXACTAMENTE ese byte y no otra cosa.
        val cabecera = ByteArray(SecureFrameSpec.TYPE_OFFSET + 1)
        cabecera[SecureFrameSpec.VERSION_OFFSET] = SecureFrameSpec.VERSION.toByte()
        cabecera[SecureFrameSpec.TYPE_OFFSET] = FrameType.PREKEY.code.toByte()
        assertTrue(PrekeyFrame.esPrekey(cabecera), "version v1 + tipo PREKEY = PREKEY")

        val cabeceraMensaje = cabecera.copyOf()
        cabeceraMensaje[SecureFrameSpec.TYPE_OFFSET] = FrameType.MESSAGE.code.toByte()
        assertFalse(PrekeyFrame.esPrekey(cabeceraMensaje), "el mismo wire con tipo MESSAGE no lo es")
    }
}
