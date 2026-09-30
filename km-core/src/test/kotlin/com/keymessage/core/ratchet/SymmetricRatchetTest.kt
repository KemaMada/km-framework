package com.keymessage.core.ratchet

import com.keymessage.core.crypto.Kdf
import com.keymessage.core.crypto.provider.BcHkdfSha256
import com.keymessage.core.sf.SecureFrame
import com.keymessage.core.sf.SecureFrameProtector
import com.keymessage.core.sf.FrameType
import com.keymessage.core.sf.RatchetHeader
import com.keymessage.core.sf.BinarySecureFrameCodec
import com.keymessage.core.crypto.provider.BcChaCha20Poly1305
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals

/**
 * 3O.2 — Simetrico Ratchet: KDF_CK, transaccionalidad, skipped keys, MAX_SKIP.
 *
 * 3O.2 NO incluye el DH ratchet. Aqui se valida unicamente la capa
 * simetrica: ChainKey, MessageKey, KDF_CK, N, skipped keys y MAX_SKIP.
 */
class SymmetricRatchetTest {

    private lateinit var kdf: Kdf
    private lateinit var protector: SecureFrameProtector

    @BeforeEach
    fun setUp() {
        kdf = BcHkdfSha256()
        protector = SecureFrameProtector(BcChaCha20Poly1305(), kdf)
    }

    private fun initialChainKey(seed: Int) = ByteArray(32) { ((it * 37 + seed) and 0xFF).toByte() }

    private fun newRatchet(seed: Int = 1) = SymmetricRatchet(initialChainKey(seed), kdf)

    private fun chainId(tag: Int) = ByteArray(32) { ((it + tag) and 0xFF).toByte() }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // ===================================================================
    // KDF_CK
    // ===================================================================

    @Test
    @DisplayName("KDF_CK produce MessageKey y ChainKey de 32 bytes")
    fun `O7-01 KDF_CK produce 32 mas 32 bytes`() {
        val r = newRatchet()
        val step = r.previewSend()
        assertEquals(32, step.messageKey.size)
        assertEquals(32, SymmetricRatchetSpec.MESSAGE_KEY_LENGTH)
        assertEquals(32, SymmetricRatchetSpec.CHAIN_KEY_LENGTH)
    }

    @Test
    @DisplayName("MessageKey[n] != MessageKey[n+1]")
    fun `O7-02 MessageKeys consecutivas son distintas`() {
        val r = newRatchet()
        val k0 = r.previewSend().messageKey
        r.commitSend()
        val k1 = r.previewSend().messageKey
        r.commitSend()
        val k2 = r.previewSend().messageKey
        assertFalse(k0.contentEquals(k1))
        assertFalse(k1.contentEquals(k2))
        assertFalse(k0.contentEquals(k2))
    }

    @Test
    @DisplayName("el mismo ChainKey produce el mismo resultado (determinismo)")
    fun `O7-03 el mismo ChainKey es determinista`() {
        val ck = initialChainKey(99)
        val a = SymmetricRatchet(ck, kdf)
        val b = SymmetricRatchet(ck, kdf)
        assertContentEquals(a.previewSend().messageKey, b.previewSend().messageKey)
        assertEquals(a.previewSend().messageNumber, b.previewSend().messageNumber)
    }

    @Test
    @DisplayName("MESSAGE y CHAIN usan dominios HKDF distintos")
    fun `O7-04 dominios separados entre MESSAGE y CHAIN`() {
        assertNotEquals(
            SymmetricRatchetSpec.INFO_MESSAGE, SymmetricRatchetSpec.INFO_CHAIN
        )
        // Y producen material criptograficamente distinto del mismo ikm.
        val ck = initialChainKey(1)
        val msg = kdf.hkdf(ByteArray(32), ck, SymmetricRatchetSpec.INFO_MESSAGE.toByteArray(), 32)
        val chain = kdf.hkdf(ByteArray(32), ck, SymmetricRatchetSpec.INFO_CHAIN.toByteArray(), 32)
        assertFalse(msg.contentEquals(chain))
    }

    @Test
    @DisplayName("la MessageKey no es la ChainKey sin transformar")
    fun `O7-05 MessageKey no es la ChainKey directa`() {
        val ck = initialChainKey(1)
        val r = SymmetricRatchet(ck, kdf)
        assertFalse(r.previewSend().messageKey.contentEquals(ck))
    }

    // ===================================================================
    // Envio: transaccionalidad
    // ===================================================================

    @Test
    @DisplayName("N avanza exactamente una vez por mensaje")
    fun `O7-06 N avanza una vez por mensaje`() {
        val r = newRatchet()
        assertEquals(0u, r.previewSend().messageNumber)
        r.commitSend()
        assertEquals(1u, r.previewSend().messageNumber)
        r.commitSend()
        assertEquals(2u, r.previewSend().messageNumber)
        assertEquals(2u, r.sentMessageCount)
    }

    @Test
    @DisplayName("previewSend NO consume estado")
    fun `O7-07 previewSend no consume estado`() {
        val r = newRatchet()
        r.previewSend()
        r.previewSend()
        r.previewSend()
        // Sin commit, N sigue en 0.
        assertEquals(0u, r.nextSendMessageNumber)
        assertEquals(0u, r.sentMessageCount)
    }

    @Test
    @DisplayName("previewSend repetido devuelve la MISMA clave (mismo estado)")
    fun `O7-08 previewSend es estable antes de commit`() {
        val r = newRatchet()
        val a = r.previewSend()
        val b = r.previewSend()
        assertContentEquals(a.messageKey, b.messageKey)
        assertEquals(a.messageNumber, b.messageNumber)
    }

    @Test
    @DisplayName("un fallo de procesamiento NO consume estado")
    fun `O7-09 un fallo no consume estado`() {
        val r = newRatchet()
        val step = r.previewSend()
        // Simula un fallo de cifrado: NO se llama a commitSend.
        val ex = runCatching { protector.protect(FrameType.MESSAGE, RatchetHeader(chainId(1), 0u, step.messageNumber), step.messageKey, ByteArray(0)) }
        assertTrue(ex.isSuccess) // el cifrado en si funciona
        // Sin commit, el estado no avanza.
        assertEquals(0u, r.sentMessageCount)
        // Y la clave sigue disponible para reintentar.
        assertContentEquals(step.messageKey, r.previewSend().messageKey)
    }

    @Test
    @DisplayName("la ChainKey no se expone en RatchetStep")
    fun `O7-10 ChainKey no se expone`() {
        val r = newRatchet()
        val step = r.previewSend()
        // RatchetStep solo lleva messageKey y messageNumber.
        val props = RatchetStep::class.java.declaredFields.map { it.name }
        assertFalse(props.contains("chainKey"), "RatchetStep no debe exponer chainKey: $props")
        assertFalse(props.any { it.contains("chain", ignoreCase = true) }, "$props")
    }

    // ===================================================================
    // Recepcion en orden
    // ===================================================================

    @Test
    @DisplayName("recepcion en orden: N=0,1,2 sucesivos")
    fun `O7-11 recepcion en orden`() {
        val r = newRatchet()
        for (n in 0u..2u) {
            val outcome = r.previewReceive(chainId(1), n)
            assertTrue(outcome is ReceiveOutcome.InOrder, "N=$n deberia ser InOrder")
            r.commitReceive()
        }
        assertEquals(3u, r.receivedMessageCount)
    }

    @Test
    @DisplayName("el emisor y el receptor derivan la misma MessageKey en orden")
    fun `O7-12 emisor y receptor coinciden`() {
        val sender = newRatchet()
        val receiver = newRatchet()   // mismo ChainKey inicial
        for (n in 0u..4u) {
            val send = sender.previewSend()
            assertEquals(n, send.messageNumber)
            sender.commitSend()

            val outcome = receiver.previewReceive(chainId(1), n)
            val inOrder = assertInstanceOf(ReceiveOutcome.InOrder::class.java, outcome)
            assertContentEquals(send.messageKey, inOrder.step.messageKey, "N=$n")
            receiver.commitReceive()
        }
    }

    // ===================================================================
    // Skipped keys
    // ===================================================================

    @Test
    @DisplayName("recibir 0 y luego saltar a 2 retiene N=1 como skipped")
    fun `O7-13 salto retiene la clave intermedia`() {
        val r = newRatchet()
        // Primero llega N=0 (en orden).
        assertInstanceOf(ReceiveOutcome.InOrder::class.java, r.previewReceive(chainId(1), 0u))
        r.commitReceive()
        assertEquals(0, r.skippedKeyCount())
        assertEquals(1u, r.receivedMessageCount)

        // Ahora salta a N=2: solo N=1 queda retenido.
        assertInstanceOf(ReceiveOutcome.InOrder::class.java, r.previewReceive(chainId(1), 2u))
        r.commitReceive()
        assertEquals(1, r.skippedKeyCount(), "solo N=1 debe quedar retenido")
        assertEquals(3u, r.receivedMessageCount)
    }

    @Test
    @DisplayName("un salto desde N=0 retiene todas las intermedias")
    fun `O7-13b un salto desde el inicio retiene N-1 claves`() {
        val r = newRatchet()
        // Receptor NUEVO en N=0 que recibe N=3: N=0,1,2 quedan retenidas.
        assertInstanceOf(ReceiveOutcome.InOrder::class.java, r.previewReceive(chainId(1), 3u))
        r.commitReceive()
        assertEquals(3, r.skippedKeyCount(), "N=0,1,2 retenidos")
        assertEquals(4u, r.receivedMessageCount)
    }

    @Test
    @DisplayName("la skipped key sirve el mensaje fuera de orden y es de un solo uso")
    fun `O7-14 skipped key de un solo uso`() {
        val r = newRatchet()
        r.previewReceive(chainId(1), 2u)
        r.commitReceive()
        // Saltando desde N=0 quedan retenidas N=0 y N=1.
        assertEquals(2, r.skippedKeyCount())

        // Ahora llega N=1: se sirve desde skipped y SE RETIRA.
        assertInstanceOf(ReceiveOutcome.FromSkipped::class.java, r.previewReceive(chainId(1), 1u))
        assertEquals(1, r.skippedKeyCount(), "la skipped key se retira al servirla")

        // Reintento: ya no existe -> rechazo, no doble entrega.
        val second = r.previewReceive(chainId(1), 1u)
        val rejected = assertInstanceOf(ReceiveOutcome.Rejected::class.java, second)
        assertEquals(RejectReason.REPLAY_OR_UNKNOWN, rejected.reason)
    }

    @Test
    @DisplayName("la skipped key corresponde a la MessageKey original del emisor")
    fun `O7-15 la skipped key corresponde a la del emisor`() {
        val sender = newRatchet()
        sender.previewSend().also { sender.commitSend() }  // N=0
        sender.previewSend().also { sender.commitSend() }  // N=1
        sender.previewSend().also { sender.commitSend() }  // N=2

        val receiver = newRatchet()
        // Llega N=2 primero: retiene N=0 y N=1 como skipped.
        assertInstanceOf(ReceiveOutcome.InOrder::class.java, receiver.previewReceive(chainId(1), 2u))
        receiver.commitReceive()
        assertEquals(2, receiver.skippedKeyCount())

        // N=0 y N=1 llegan despues, cada uno con la clave del emisor.
        // Re-derivamos las claves del emisor. OJO: con commit entre pasos,
        // porque sin commit previewSend devolveria siempre la clave de N=0.
        val sender2 = newRatchet()
        val step0 = sender2.previewSend()
        sender2.commitSend()
        val step1 = sender2.previewSend()

        val out0 = receiver.previewReceive(chainId(1), 0u)
        val skipped0 = assertInstanceOf(ReceiveOutcome.FromSkipped::class.java, out0)
        assertContentEquals(step0.messageKey, skipped0.step.messageKey, "N=0 desde skipped")
        assertEquals(0u, skipped0.step.messageNumber)

        val out1 = receiver.previewReceive(chainId(1), 1u)
        val skipped1 = assertInstanceOf(ReceiveOutcome.FromSkipped::class.java, out1)
        assertContentEquals(step1.messageKey, skipped1.step.messageKey, "N=1 desde skipped")
    }

    @Test
    @DisplayName("no hay entradas skipped duplicadas para (chainId, N)")
    fun `O7-16 no hay skipped duplicadas`() {
        val r = newRatchet()
        r.previewReceive(chainId(1), 5u)
        r.commitReceive()
        assertEquals(5, r.skippedKeyCount())  // N=0..4 retenidos
        // Reintentar el mismo salto no debe duplicar.
        r.previewReceive(chainId(1), 5u)
        r.commitReceive()
        assertTrue(r.skippedKeyCount() <= 5, "no debe crecer mas alla de las claves unicas")
    }

    @Test
    @DisplayName("N anterior al actual sin skipped -> rechazo")
    fun `O7-17 N antiguo sin skipped se rechaza`() {
        val r = newRatchet()
        r.previewReceive(chainId(1), 3u)
        r.commitReceive()
        // N=3 ya fue consumido y no hay skipped; N=1 fue retenido, N=0 tambien.
        // Pedir N=5 y luego N=1 debe servir skipped. Pedir algo anterior sin
        // entrada debe rechazarse. Forzamos drained de todas.
        for (n in 0u..3u) { r.previewReceive(chainId(1), n) }
        // Tras servir todas, ya no hay claves anteriores.
        val out = r.previewReceive(chainId(1), 1u)
        assertTrue(out is ReceiveOutcome.Rejected)
    }

    // ===================================================================
    // MAX_SKIP
    // ===================================================================

    @Test
    @DisplayName("MAX_SKIP es una constante de protocolo, no un parametro")
    fun `O7-18 MAX_SKIP no es parametro del constructor`() {
        // No existe parametro MAX_SKIP en el constructor.
        val ctor = SymmetricRatchet::class.java.constructors.first()
        val paramNames = ctor.parameterTypes.map { it.simpleName }
        assertFalse(paramNames.contains("maxSkip"), "no debe haber maxSkip como parametro: $paramNames")
    }

    @Test
    @DisplayName("salto mayor que MAX_SKIP se rechaza sin consumo de estado")
    fun `O7-19 salto mayor que MAX_SKIP se rechaza`() {
        val r = newRatchet()
        val tooFar = SymmetricRatchetSpec.MAX_SKIP.toUInt() + 1u
        val outcome = r.previewReceive(chainId(1), tooFar)
        assertTrue(outcome is ReceiveOutcome.Rejected)
        assertEquals(RejectReason.SKIP_LIMIT_EXCEEDED, (outcome as ReceiveOutcome.Rejected).reason)
        // Estado intacto.
        assertEquals(0u, r.receivedMessageCount)
        assertEquals(0, r.skippedKeyCount())
    }

    @Test
    @DisplayName("salto exactamente MAX_SKIP funciona (limite inclusivo)")
    fun `O7-20 salto exactamente MAX_SKIP permitido`() {
        val r = newRatchet()
        val atLimit = SymmetricRatchetSpec.MAX_SKIP.toUInt()
        val outcome = r.previewReceive(chainId(1), atLimit)
        assertTrue(outcome is ReceiveOutcome.InOrder, "MAX_SKIP debe permitirse")
        r.commitReceive()
        assertEquals(atLimit + 1u, r.receivedMessageCount)
        assertEquals(SymmetricRatchetSpec.MAX_SKIP, r.skippedKeyCount())
    }

    @Test
    @DisplayName("N con valor maximo se rechaza")
    fun `O7-21 UInt maximo se rechaza`() {
        val r = newRatchet()
        val outcome = r.previewReceive(chainId(1), UInt.MAX_VALUE)
        assertTrue(outcome is ReceiveOutcome.Rejected)
        assertEquals(RejectReason.SKIP_LIMIT_EXCEEDED, (outcome as ReceiveOutcome.Rejected).reason)
    }

    // ===================================================================
    // Integracion con SecureFrame
    // ===================================================================

    @Test
    @DisplayName("ratchet + SecureFrame: cifrar y descifrar extremo a extremo en orden")
    fun `O7-22 integracion extremo a extremo en orden`() {
        val sender = newRatchet()
        val receiver = newRatchet()
        val codec = BinarySecureFrameCodec

        for (n in 0u..3u) {
            val step = sender.previewSend()
            val frame = protector.protect(
                FrameType.MESSAGE,
                RatchetHeader(chainId(1), 0u, step.messageNumber),
                step.messageKey,
                "mensaje $n".toByteArray(),
            )
            sender.commitSend()

            val wire = codec.encode(frame)
            val decoded = codec.decode(wire)
            val outcome = receiver.previewReceive(chainId(1), decoded.ratchetHeader.messageNumber)
            val inOrder = assertInstanceOf(ReceiveOutcome.InOrder::class.java, outcome)
            val plaintext = protector.unprotect(decoded, inOrder.step.messageKey)
            receiver.commitReceive()
            assertContentEquals("mensaje $n".toByteArray(), plaintext)
        }
    }

    @Test
    @DisplayName("ratchet + SecureFrame: entrega fuera de orden usa skipped key")
    fun `O7-23 integracion fuera de orden`() {
        val sender = newRatchet()
        val receiver = newRatchet()
        val codec = BinarySecureFrameCodec
        val cid = chainId(1)

        // El emisor genera 3 mensajes.
        val frames = (0u..2u).map { n ->
            val step = sender.previewSend()
            val f = protector.protect(FrameType.MESSAGE, RatchetHeader(cid, 0u, step.messageNumber), step.messageKey, "msg$n".toByteArray())
            sender.commitSend()
            codec.encode(f)
        }

        // El receptor recibe en orden 0, luego 2, luego 1.
        val o0 = receiver.previewReceive(cid, 0u)
        val inOrder0 = assertInstanceOf(ReceiveOutcome.InOrder::class.java, o0)
        val p0 = protector.unprotect(codec.decode(frames[0]), inOrder0.step.messageKey)
        receiver.commitReceive()
        assertContentEquals("msg0".toByteArray(), p0)

        val o2 = receiver.previewReceive(cid, 2u)
        val inOrder2 = assertInstanceOf(ReceiveOutcome.InOrder::class.java, o2)
        val p2 = protector.unprotect(codec.decode(frames[2]), inOrder2.step.messageKey)
        receiver.commitReceive()
        assertContentEquals("msg2".toByteArray(), p2)
        assertEquals(1, receiver.skippedKeyCount())

        val o1 = receiver.previewReceive(cid, 1u)
        val skipped1 = assertInstanceOf(ReceiveOutcome.FromSkipped::class.java, o1)
        val p1 = protector.unprotect(codec.decode(frames[1]), skipped1.step.messageKey)
        assertContentEquals("msg1".toByteArray(), p1)
    }
}
