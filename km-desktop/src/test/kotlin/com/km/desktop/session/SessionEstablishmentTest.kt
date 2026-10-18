package com.km.desktop.session

import com.km.desktop.support.RelayProbe
import com.km.desktop.support.SecureTestIdentities
import com.km.desktop.support.relayProbeManager
import com.km.identity.VerifiedContactBundle
import com.km.messaging.LocalInitiatorKeys
import com.km.messaging.LocalResponderKeys
import com.km.messaging.SendResult
import com.km.messaging.SecureMessagingSession
import com.km.messaging.SecureSessionRegistry
import com.km.model.IdentityId
import com.km.node.PeerContext
import com.km.node.PeerTransportManager
import com.km.node.PeerTransportState
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 2.2 — Abrir sesion con un contacto, de punta a punta y en el escritorio.
 *
 * ## QUE AFIRMA
 *
 * 1. El frame PREKEY que produce `iniciar` CRUZA EL BACKEND de prueba: sale por
 *    `sendData` como cualquier otro byte, y no por una llamada directa entre
 *    objetos.
 * 2. `aceptar` lo acepta en el otro extremo, y un payload NO CONSTANTE que A
 *    envia lo recibe B **byte a byte**.
 * 3. La clave de ruteo es la RAIZ del contacto (`PeerContext.forContact`), y el
 *    escritorio no tiene por donde elegirla.
 * 4. El enlace queda `CONNECTED` y la sesion queda INSTALADA en el registro de
 *    cada lado.
 *
 * ## POR QUE EL PREKEY CRUZA EL BACKEND Y NO UNA LLAMADA DIRECTA
 *
 * Porque el wire se captura de la SALIDA REAL del manager y se entrega por el
 * cable de ENTRADA del otro extremo. Un doble que se pasara el byte a si mismo
 * probaria que dos objetos se hablan, no que dos extremos comparten sesion.
 */
class SessionEstablishmentTest {

    private val coordinator = SessionCoordinator()

    private val parAlice = SecureTestIdentities.deviceWithRoot("alice")
    private val parBob = SecureTestIdentities.deviceWithRoot("bob")

    private val alice: SecureTestIdentities.Device get() = parAlice.device
    private val bob: SecureTestIdentities.Device get() = parBob.device

    /** Identidad de CABLE de cada extremo: la de su RAIZ, como en el escritorio. */
    private val aliceSessionIdentity: IdentityId = parAlice.root.identityId
    private val bobSessionIdentity: IdentityId = parBob.root.identityId

    private val opkId: Long = SecureTestIdentities.ONE_TIME_PREKEY_ID

    private fun bundleDeBob(): VerifiedContactBundle {
        bob.issueOneTimePreKey(opkId)
        return VerifiedContactBundle.verified(parBob.root.bundleMapFor(listOf(bob), bob, listOf(opkId)))
    }

    private fun bundleDeAlice(): VerifiedContactBundle =
        VerifiedContactBundle.verified(parAlice.root.bundleMapFor(listOf(alice), alice, emptyList()))

    private class Par(
        val registroA: SecureSessionRegistry,
        val registroB: SecureSessionRegistry,
        val sesionA: SecureMessagingSession,
        val sesionB: SecureMessagingSession,
        val managerA: PeerTransportManager,
        val managerB: PeerTransportManager,
        val probeA: RelayProbe,
        val probeB: RelayProbe,
        /** El frame PREKEY tal y como CRUZÓ el backend de A. */
        val prekeyCruzado: ByteArray,
    )

    /**
     * Empareja los dos extremos.
     *
     * El `bob.issueOneTimePreKey` ocurre UNA vez por instancia de test —el
     * `bob` es una propiedad de la clase de test, no del metodo—, asi que el
     * OPK existe para todo el test y `getOrPut` devuelve el mismo material.
     */
    private fun emparejar(): Par {
        bob.issueOneTimePreKey(opkId)

        val (managerA, probeA) = relayProbeManager()
        val (managerB, probeB) = relayProbeManager()
        val registroA = SecureSessionRegistry(aliceSessionIdentity)
        val registroB = SecureSessionRegistry(bobSessionIdentity)

        val iniciada = coordinator.iniciar(
            localIdentity = aliceSessionIdentity,
            peerContext = coordinator.contextoHacia(bundleDeBob()),
            localKeys = LocalInitiatorKeys(alice.deviceId, alice.agreementKey),
            manager = managerA,
            registry = registroA,
        )

        // El frame PREKEY NO se entrega solo: `initiate` lo produce y quien tiene
        // el rele abierto lo manda. Para mandarlo por el camino REAL hace falta
        // el enlace `CONNECTED`, asi que A se conecta antes —el mismo orden que
        // en el corpus— y luego entrega el wire YA CONSTRUIDO con
        // `sendTranscript`, que lo reenvia sin re-cifrar.
        conectar(iniciada.session, managerA)
        val entregado = iniciada.session.sendTranscript(iniciada.prekeyFrame)
        assertTrue(entregado is SendResult.Ok, "el PREKEY sale por el backend: $entregado")
        check(probeA.enviados.isNotEmpty()) {
            "el frame PREKEY debe salir por el backend: si no sale, no hay camino de entrega"
        }
        val prekeyCruzado = probeA.enviados.last().second

        val aceptada = coordinator.aceptar(
            localIdentity = bobSessionIdentity,
            peerContext = coordinator.contextoHacia(bundleDeAlice()),
            localKeys = LocalResponderKeys(
                deviceId = bob.deviceId,
                agreementKey = bob.agreementKey,
                signedPreKey = bob.signedPreKey,
                oneTimePreKey = requireNotNull(bob.oneTimePreKey(opkId)) { "B emitio el OPK $opkId" },
                oneTimePreKeyId = opkId,
            ),
            prekeyFrameWire = prekeyCruzado,
            manager = managerB,
            registry = registroB,
        )

        conectar(aceptada.session, managerB)
        return Par(
            registroA, registroB, iniciada.session, aceptada.session,
            managerA, managerB, probeA, probeB, prekeyCruzado,
        )
    }

    private fun conectar(sesion: SecureMessagingSession, manager: PeerTransportManager) {
        manager.sendOffer(sesion.localIdentity, sesion.peerContext, "sdp-offer", sesion.remotePeerId).getOrThrow()
        manager.receiveAnswer(sesion.localIdentity, sesion.remotePeerId, "sdp-answer").getOrThrow()
        manager.markConnected(sesion.localIdentity, sesion.peerContext).getOrThrow()
    }

    @Test
    fun `el contexto del escritorio se ancla a la RAIZ del contacto`() {
        val contexto = coordinator.contextoHacia(bundleDeBob())

        assertEquals(parBob.root.identityId, contexto.remotePeerId, "la clave de ruteo es la RAIZ del bundle")
        assertNotNull(contexto.contactBundle, "y la autoridad es el bundle verificado")
        assertNotEquals(
            bob.deviceId.joinToString("") { "%02x".format(it) },
            contexto.remotePeerId.value,
            "NO el deviceId del sujeto: es el error de ruteo que esta puerta evita",
        )
    }

    @Test
    fun `2-2 A envia, B recibe, y el payload llega byte a byte`() {
        val par = emparejar()
        // Lo que ya ha salido por el cable de A: el frame PREKEY.
        val yaEnviados = par.probeA.enviados.size

        // Contenido NO constante y de longitud propia: un wire de tamano
        // inesperado haria que la afirmacion fuera sobre una coincidencia.
        val carga = "el mensaje del escritorio que tiene que volver intacto 4711".toByteArray(Charsets.UTF_8)
        val resultado = par.sesionA.send(carga)
        assertTrue(resultado is SendResult.Ok, "con sesion establecida se envia: $resultado")

        // El wire sale por el TRANSPORTE, que es lo que en produccion lleva el
        // rele: `drainOutbound` de la sesion es el buzon de TEXTO PLANO que se
        // descifra, no el de salida.
        assertEquals(
            yaEnviados + 1,
            par.probeA.enviados.size,
            "un unico SecureFrame nuevo en el cable de A",
        )
        val wire = par.probeA.enviados.last().second

        par.probeB.deliver(aliceSessionIdentity, wire)

        val recibidos = par.sesionB.drainOutbound()
        assertEquals(1, recibidos.size, "y B lo recibe UNA vez")
        assertArrayEquals(carga, recibidos.single(), "el otro extremo devuelve el texto original")
    }

    @Test
    fun `el SecureFrame del mensaje NO lleva el payload en claro`() {
        val par = emparejar()
        val carga = "contenido que no puede aparecer en el cable del escritorio".toByteArray(Charsets.UTF_8)

        par.sesionA.send(carga)

        val wire = par.probeA.enviados.last().second
        assertTrue(
            !String(wire, Charsets.ISO_8859_1).contains("no puede aparecer"),
            "el texto en claro no aparece en ningun byte del cable",
        )
        assertTrue(wire.size > carga.size, "y el frame es mas grande que el payload: hay cabecera y sobre")
    }

    @Test
    fun `2-2 las dos sesiones quedan INSTALADAS en sus registros`() {
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
            "sin CONNECTED el envio se rechazaria, y seria otra causa distinta",
        )
    }

    /**
     * CONTROL POSITIVO: el PREKEY que cruzo tiene contenido, y el mensaje sale
     * por el MISMO cable y no por una llamada aparte.
     *
     * Sin esto, "el PREKEY cruza el backend" seria verdad por construccion del
     * arnes y no mediria nada.
     */
    @Test
    fun `control positivo el PREKEY y el mensaje salen por el MISMO cable, y son distintos`() {
        val par = emparejar()
        val antes = par.probeA.enviados.size
        assertEquals(1, antes, "antes del mensaje solo ha salido el PREKEY")

        par.sesionA.send("carga del control con longitud propia 8123".toByteArray(Charsets.UTF_8))

        assertEquals(2, par.probeA.enviados.size, "el mensaje sale por el mismo backend")
        assertTrue(par.prekeyCruzado.isNotEmpty(), "el PREKEY tiene contenido")
        assertNotEquals(
            par.probeA.enviados[0].second.toList(),
            par.probeA.enviados[1].second.toList(),
            "y el PREKEY y el mensaje NO son el mismo frame",
        )
    }

    /**
     * CONTROL NEGATIVO: lo que llega al buzon de B es exactamente UN mensaje y
     * con el contenido exacto.
     *
     * Sin esta prueba, "llega byte a byte" seria verdad tambien para un buzon
     * vacio, si `assertArrayEquals` se comparara contra algo trivial.
     */
    @Test
    fun `control negativo lo que B recibe NO es ni vacio ni un eco`() {
        val par = emparejar()
        val carga = "contenido que solo puede venir de A 6408".toByteArray(Charsets.UTF_8)
        par.sesionA.send(carga)

        // Nada entregado todavia: el buzon esta VACIO, no "casi lleno".
        assertEquals(0, par.sesionB.drainOutbound().size, "sin frame, el buzon de B esta vacio")

        par.probeB.deliver(aliceSessionIdentity, par.probeA.enviados.last().second)

        val recibido = par.sesionB.drainOutbound().single()
        assertEquals(carga.size, recibido.size, "y el tamano es el del payload, no el del frame")
        assertTrue(
            recibido.toList() != carga.reversedArray().toList(),
            "y no es el contenido invertido: seria un 'casi'",
        )
    }
}