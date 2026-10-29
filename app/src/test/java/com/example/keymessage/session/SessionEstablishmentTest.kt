package com.example.keymessage.session

import com.km.identity.ContactBundle
import com.km.messaging.LocalInitiatorKeys
import com.km.messaging.LocalResponderKeys
import com.km.messaging.SecureMessagingSession
import com.km.messaging.SendResult
import com.km.messaging.SecureSessionRegistry
import com.km.model.IdentityId
import com.km.node.PeerContext
import com.km.node.PeerTransportManager
import com.km.node.PeerTransportState
import com.example.keymessage.support.TestWorld
import com.example.keymessage.support.countingManager
import com.example.keymessage.util.toHex
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 1.4 — Abrir sesion con un contacto, de punta a punta y dentro de la app.
 *
 * ## QUE AFIRMA
 *
 * 1. El frame PREKEY que produce `iniciar` lo acepta `aceptar` en el otro
 *     extremo, y el payload que A envia lo recibe B **byte a byte**.
 * 2. La clave de ruteo de la conversacion es la RAIZ del contacto
 *     (`PeerContext.forContact`), y la app no tiene por donde elegirla.
 * 3. El enlace queda `CONNECTED` y la sesion queda INSTALADA en el registro de
 *     cada lado. Sin eso, `send` rechazaria, y ese rechazo seria otra causa
 *     distinta de la que este test descarta.
 *
 * ## POR QUE NO SE USA UN DOBLE DE TRANSPORTE PARA LA ENTREGA
 *
 * Porque el wire se captura de la salida REAL del manager, se entrega a la
 * sesion del otro extremo y se comprueba el plaintext. Un doble que se pasara el
 * byte a si mismo probaria que dos objetos se hablan, no que dos extremos
 * comparten sesion.
 */
class SessionEstablishmentTest {

    private val coordinator = SessionCoordinator()

    private val parAlice = TestWorld.deviceWithRoot("alice")
    private val parBob = TestWorld.deviceWithRoot("bob")

    private val alice = parAlice.device
    private val bob = parBob.device

    private val aliceSessionIdentity = IdentityId(alice.deviceId.toHex())
    private val bobSessionIdentity = IdentityId(bob.deviceId.toHex())

    private val opkId: Long = TestWorld.BOB_ONE_TIME_PREKEY_ID

    /** Bundle de B, firmado por su raiz, con el OPK emitido. */
    private fun bundleDeBob(): Map<String, Any?> {
        bob.issueOneTimePreKey(opkId)
        return parBob.root.bundleMapFor(listOf(bob), bob, listOf(opkId))
    }

    /** El `PeerContext` que la app construye hacia un bundle verificado. */
    private fun contextoHaciaBob(): PeerContext =
        coordinator.contextoHacia(ContactBundle.verified(bundleDeBob()))

    private class Par(
        val registroA: SecureSessionRegistry,
        val registroB: SecureSessionRegistry,
        val sesionA: SecureMessagingSession,
        val sesionB: SecureMessagingSession,
        val managerA: PeerTransportManager,
        val managerB: PeerTransportManager,
    )

    /** Establece la sesion en LOS DOS extremos y conduce el enlace a CONNECTED. */
    private fun emparejar(): Par {
        val (managerA, _) = countingManager()
        val (managerB, _) = countingManager()
        val registroA = SecureSessionRegistry(aliceSessionIdentity)
        val registroB = SecureSessionRegistry(bobSessionIdentity)

        val iniciada = coordinator.iniciar(
            localIdentity = aliceSessionIdentity,
            peerContext = contextoHaciaBob(),
            localKeys = LocalInitiatorKeys(alice.deviceId, alice.agreementKey),
            manager = managerA,
            registry = registroA,
        )
        val aceptada = coordinator.aceptar(
            localIdentity = bobSessionIdentity,
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
            prekeyFrameWire = iniciada.prekeyFrame,
            manager = managerB,
            registry = registroB,
        )

        conectar(iniciada.session, managerA)
        conectar(aceptada.session, managerB)
        return Par(registroA, registroB, iniciada.session, aceptada.session, managerA, managerB)
    }

    private fun conectar(sesion: SecureMessagingSession, manager: PeerTransportManager) {
        manager.sendOffer(sesion.localIdentity, sesion.peerContext, "sdp-offer", sesion.remotePeerId).getOrThrow()
        manager.receiveAnswer(sesion.localIdentity, sesion.remotePeerId, "sdp-answer").getOrThrow()
        manager.markConnected(sesion.localIdentity, sesion.peerContext).getOrThrow()
    }

    @Test
    fun `el contexto de la app se ancla a la RAIZ del contacto`() {
        val contexto = contextoHaciaBob()

        assertEquals(parBob.root.identityId, contexto.remotePeerId, "la clave de ruteo es la RAIZ del bundle")
        assertNotNull(contexto.contactBundle, "y la autoridad es el bundle verificado")
        assertNotEquals(
            bobSessionIdentity.value,
            contexto.remotePeerId.value,
            "NO el deviceId del sujeto: es el error de ruteo que este metodo evita",
        )
    }

    /** Los `SecureFrame` que salen por el manager, en orden. */
    private fun capturar(manager: PeerTransportManager): MutableList<ByteArray> {
        val capturados = mutableListOf<ByteArray>()
        manager.onData { _, data -> capturados += data }
        return capturados
    }

    @Test
    fun `A inicia, B acepta y el payload llega byte a byte`() {
        val par = emparejar()
        val salida = capturar(par.managerA)

        // Contenido NO constante y de longitud propia: un wire de tamano
        // inesperado haria que la afirmacion fuera sobre una coincidencia.
        val carga = "el mensaje de la app que tiene que volver intacto 4711".toByteArray()
        val resultado = par.sesionA.send(carga)
        assertTrue(resultado is SendResult.Ok, "con sesion establecida se envia: $resultado")

        // El wire sale por el TRANSPORTE, que es lo que en produccion lleva el
        // rele: `drainOutbound` de la sesion es el buzon de TEXTO PLANO que se
        // descifra, no el de salida.
        assertEquals(1, salida.size, "un unico SecureFrame en el cable")
        par.sesionB.onTransportBytes(salida.single())

        assertContentEquals(carga, par.sesionB.drainOutbound().single(), "el otro extremo devuelve el texto original")
    }

    @Test
    fun `las dos sesiones quedan instaladas en SUS registros`() {
        val par = emparejar()

        assertEquals(1, par.registroA.size(), "A tiene exactamente una sesion")
        assertEquals(1, par.registroB.size(), "y B tambien")
        assertEquals(
            par.sesionA,
            par.registroA.find(parBob.root.identityId),
            "el registro resuelve la sesion por la raiz del contacto",
        )
        assertEquals(
            par.sesionB,
            par.registroB.find(parAlice.root.identityId),
            "y por la raiz del otro lado",
        )
    }

    @Test
    fun `el enlace queda CONNECTED tras el establecimiento`() {
        val par = emparejar()

        assertEquals(
            PeerTransportState.CONNECTED,
            par.sesionA.bindingState(),
            "sin CONNECTED el envio se rechazaria, y seria otra causa",
        )
    }

    @Test
    fun `el SecureFrame del mensaje NO lleva el payload en claro`() {
        val par = emparejar()
        val salida = capturar(par.managerA)
        val carga = "contenido que no puede aparecer en el cable de la app".toByteArray()
        par.sesionA.send(carga)

        val wire = salida.single()
        assertTrue(
            !String(wire, Charsets.ISO_8859_1).contains("no puede aparecer"),
            "el texto en claro no aparece en ningun byte del cable",
        )
        assertTrue(wire.size > carga.size, "y el frame es mas grande que el payload: hay cabecera y sobre")
    }
}