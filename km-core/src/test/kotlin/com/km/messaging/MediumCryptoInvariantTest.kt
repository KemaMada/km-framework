package com.km.messaging

import com.km.frame.SecureFrameSpec
import com.km.model.MessageState
import com.km.node.TransportBackend
import com.km.node.TransportChain
import com.km.node.TransportEventCallbacks
import com.km.node.TransportResult
import com.km.model.IdentityId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * S10 — EL INVARIANTE CRIPTOGRAFICO frente al cambio de medio.
 *
 * ## Que se afirma
 *
 * 1. El frame que viaja por P2P es el MISMO `SecureFrame` v1 que produce la
 *    sesion: cambiar de medio no re-cifra, no ejecuta X3DH y no toca
 *    `messageId`.
 * 2. El `ACK` viaja por el RELAY (el `Transport` legado de la fixture) y, solo
 *    tras verificarse su firma, el mensaje pasa a `DELIVERED`.
 * 3. Un `ACK` manipulado NO promueve el estado: la correlacion es por
 *    `originalMessageId` y la verificacion es de firma, no de forma.
 *
 * ## Que NO se afirma
 *
 * No se levanta WebRTC. El "medio P2P" aqui es un `TransportBackend` que
 * RECIBE los bytes y los cuenta; lo que se prueba es la INVARIANTE de la capa
 * de sesion (mismos bytes, mismo id, ACK por el otro camino), no el traversal.
 * El traversal real esta en `RealIceItTest` (gateado por `KM_ICE_IT`) y en la
 * verificacion manual de `docs/developer/p2p.md`.
 */
class MediumCryptoInvariantTest {

    /** Backend que cuenta lo que recibe y nunca falla. */
    private class Medium(override val name: String) : TransportBackend {
        override var eventCallbacks: TransportEventCallbacks = TransportEventCallbacks()
        val recibidos = mutableListOf<ByteArray>()

        override fun initialize() = TransportResult.Success(Unit)
        override fun onCreateBinding(localIdentity: IdentityId, remotePeerId: IdentityId) = TransportResult.Success(Unit)
        override fun onLocalSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String) = TransportResult.Success(Unit)
        override fun onRemoteSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String) = TransportResult.Success(Unit)
        override fun onIceCandidate(localIdentity: IdentityId, remotePeerId: IdentityId, candidate: String) = TransportResult.Success(Unit)

        override fun sendData(remotePeerId: IdentityId, data: ByteArray): TransportResult<Unit> {
            recibidos += data
            return TransportResult.Success(Unit)
        }

        override fun onCloseBinding(localIdentity: IdentityId, remotePeerId: IdentityId) = TransportResult.Success(Unit)
        override fun shutdown() = TransportResult.Success(Unit)
    }

    @Test
    fun `el frame es el mismo por P2P y el ACK por relay deja DELIVERED`() {
        val mundo = SecureWorld.open()
        val recibidos = mutableListOf<com.km.model.Message>()
        mundo.bob.core.registerMessageHandler { recibidos += it }
        val capturados = mundo.alice.captureOutbound()

        val carga = "mismo frame en cualquier medio".toByteArray()
        val creado = mundo.alice.core.createMessage(mundo.bobRoot.identityId, carga).getOrThrow()
        mundo.alice.core.sendMessage(creado).getOrThrow()

        val wire = capturados.single()
        assertEquals(SecureFrameSpec.VERSION.toByte(), wire[0], "el frame es SecureFrame v1")
        assertNotEquals(
            MessageState.DELIVERED,
            mundo.alice.messageStore.get(creado.messageId).getOrThrow()?.state,
            "sin ACK no se puede afirmar entrega",
        )

        // El medio P2P recibe EXACTAMENTE el wire que produjo la sesion. La
        // cadena solo cae al relay si el primero falla; aqui no falla.
        val p2p = Medium("p2p")
        val relayDatos = Medium("relay")
        val cadena = TransportChain(listOf(p2p, relayDatos))
        assertTrue(cadena.sendData(mundo.bobRoot.identityId, wire).isSuccess)
        assertEquals(1, p2p.recibidos.size, "los datos salieron por P2P")
        assertTrue(relayDatos.recibidos.isEmpty(), "y NO se duplicaron por el relay")
        assertTrue(p2p.recibidos.single().contentEquals(wire), "el frame no se re-cifro al cambiar de medio")

        // B recibe esos bytes por su canal de par: descifra, entrega el MISMO
        // messageId y emite su ACK firmado.
        mundo.bob.deliverInboundAs(mundo.bobToAlice, p2p.recibidos.single())
        assertEquals(1, recibidos.size)
        assertEquals(creado.messageId, recibidos.single().messageId, "mismo messageId")
        assertTrue(recibidos.single().payload.contentEquals(carga))
        assertTrue(mundo.bob.transport.sentAcks.isNotEmpty(), "B emitio el ACK por su Transport de relay")

        // El ACK llega a A por el RELAY y, verificado, cierra el ciclo.
        mundo.alice.transport.deliverLegacyAck(mundo.bob.transport.sentAcks.last())
        assertEquals(
            MessageState.DELIVERED,
            mundo.alice.messageStore.get(creado.messageId).getOrThrow()?.state,
            "DELIVERED solo tras el ACK verificado",
        )
    }

    @Test
    fun `un ACK manipulado NO promueve a DELIVERED`() {
        val mundo = SecureWorld.open()
        mundo.bob.core.registerMessageHandler { }
        val capturados = mundo.alice.captureOutbound()

        val creado = mundo.alice.core.createMessage(mundo.bobRoot.identityId, "ack falsificado".toByteArray()).getOrThrow()
        mundo.alice.core.sendMessage(creado).getOrThrow()
        mundo.bob.deliverInboundAs(mundo.bobToAlice, capturados.single())

        val ackReal = mundo.bob.transport.sentAcks.last()
        val manipulado = ackReal.copyOf()
        manipulado[manipulado.size - 3] = (manipulado[manipulado.size - 3].toInt() xor 0x01).toByte()
        mundo.alice.transport.deliverLegacyAck(manipulado)

        assertNotEquals(
            MessageState.DELIVERED,
            mundo.alice.messageStore.get(creado.messageId).getOrThrow()?.state,
            "un ACK manipulado no cierra el ciclo",
        )

        mundo.alice.transport.deliverLegacyAck(ackReal)
        assertEquals(
            MessageState.DELIVERED,
            mundo.alice.messageStore.get(creado.messageId).getOrThrow()?.state,
            "y el ACK autentico si",
        )
        assertFalse(
            mundo.alice.transport.sent.any { it === ackReal },
            "el ACK no se reenvia por el camino de datos",
        )
    }
}
