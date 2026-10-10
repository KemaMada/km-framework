package com.km.node

import com.km.messaging.FrameIdentity
import com.km.messaging.GuionDeMigracion
import com.km.messaging.RelayDeTransporte
import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.transmit.TransmitLedger
import com.km.unit.OutboundDeliveryState
import com.km.unit.OutboundRecord
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A4.1/A4.2 — Migracion P2P -> relay SIN tocar la criptografia (CHAIN-08/R12).
 *
 * Se demuestra lo que constituye el invariante del slice:
 *  - el MISMO `SessionKey`, el MISMO `messageId`, el MISMO contador de ratchet
 *    `N` y el MISMO ciphertext sobreviven a la migracion;
 *  - un fallo de transporte NO borra el `OutboundRecord` durable ni dispara un
 *    nuevo cifrado.
 *
 * Nota de nomenclatura: km-core no tiene un tipo `SessionKey`. El material de
 * clave de sesion equivalente es la `rootKey` del Double Ratchet MAS el escalar
 * DH propio (`dhSelf`), que es justo lo que la huella de sesion no cubre. La
 * prueba los compara byte a byte.
 */
class ChainSupervisorMigrationTest {

    private val local = IdentityId("a".repeat(64))
    private val remote = IdentityId("b".repeat(64))

    @Test
    @DisplayName("A4.1-40 p2p -> relay conserva SessionKey, messageId, N y el ciphertext byte a byte")
    fun migracionConservaTodo() {
        val guion = GuionDeMigracion("SUPMIG")

        // 1) El frame se cifra UNA sola vez, con el ratchet.
        val texto = "cruza la migracion sin re-cifrar".toByteArray()
        val frame = guion.alice.encrypt(texto)
        val nTrasCifrar = guion.aliceSession.currentSendMessageNumber()
        val fotoTrasCifrar = guion.aliceSession.snapshot()
        val sessionKeyMaterial = fotoTrasCifrar.rootKey.copyOf() +
            fotoTrasCifrar.dhSelf.privateKeyBytes() // "SessionKey" = raiz + escalar DH propio
        val ciphertextOriginal = frame.copyOf()

        // 2) El envio es DURABLE antes de salir (write-ahead).
        val messageId = MessageId(UUID.randomUUID())
        val record = OutboundRecord(
            recordVersion = 1u,
            deliveryState = OutboundDeliveryState.PENDIENTE,
            messageId = messageId,
            frameIdentity = FrameIdentity.fromWire(frame),
            createdOrdinal = 1uL,
            ciphertext = frame,
        )
        val ledger = TransmitLedger()
        ledger.registrarDurable(record)

        // 3) Cadena P2P + relay supervisada.
        val p2p = ScriptableTransportBackend("P2P")
        val relay = RelayDeTransporte("Relay")
        val sup = ChainSupervisor(
            chain = TransportChain(listOf(p2p, relay)),
            tiers = listOf(TransportTier.P2P, TransportTier.RELAY),
            enabled = true,
            torEnabled = false,
            now = { 0L },
            jitter = { 0.5 },
        )

        var p2pMuerto = false
        val factory: (TransportBackend) -> TransportEstablishment = { b ->
            ScriptableEstablishment(b.name, b).also {
                if (b.name == "P2P" && p2pMuerto) {
                    it.awaitReadyOutcome = TransportResult.Failure(TransportError.BINDING_CLOSED, "P2P cayo")
                }
            }
        }

        // 4) P2P queda ACTIVE y el primer envio sale por el.
        sup.establish(local, remote, factory = factory)
        assertEquals(ChainState.P2P_ACTIVE, sup.state)
        assertTrue(sup.sendData(remote, record.ciphertext) is TransportResult.Success)
        assertArrayEquals(ciphertextOriginal, p2p.sentPayloads.single(), "P2P vio el ciphertext del registro")

        // 5) P2P cae en caliente: un ICE failure levanta RetryP2P -> relay.
        p2pMuerto = true
        p2p.failWithTransport(TransportError.BINDING_CLOSED)
        assertTrue(sup.onConnectionStateChange(connected = false))
        assertEquals(ChainState.RELAY_ACTIVE, sup.state)
        assertEquals("Relay", sup.chain.activeTransport)

        // 6) Reenvio del MISMO registro: sale por relay, byte a byte.
        assertTrue(sup.sendData(remote, record.ciphertext) is TransportResult.Success)
        assertArrayEquals(
            ciphertextOriginal,
            relay.entregar(),
            "el relay recibe los MISMOS bytes; migrar no re-cifra",
        )

        // 7) Invariantes: nada de lo criptografico cambio.
        assertEquals(nTrasCifrar, guion.aliceSession.currentSendMessageNumber(), "el contador N no se movio")
        val fotoAhora = guion.aliceSession.snapshot()
        assertArrayEquals(fotoTrasCifrar.rootKey, fotoAhora.rootKey, "la raiz (SessionKey) es la MISMA")
        assertArrayEquals(
            fotoTrasCifrar.dhSelf.privateKeyBytes(),
            fotoAhora.dhSelf.privateKeyBytes(),
            "el escalar DH propio es el MISMO",
        )
        assertArrayEquals(
            sessionKeyMaterial,
            fotoAhora.rootKey + fotoAhora.dhSelf.privateKeyBytes(),
            "el material de clave de sesion (SessionKey) sobrevive intacto",
        )
        assertArrayEquals(ciphertextOriginal, record.ciphertext, "el registro no fue re-escrito")
        assertTrue(ledger.esDurable(messageId), "el fallo de transporte NO borra el registro durable")
        assertEquals(messageId, ledger.registros().single().messageId)
        assertArrayEquals(ciphertextOriginal, ledger.registros().single().ciphertext, "el durable conserva el ciphertext")
    }
}
