package com.keymessage.core.km52

import com.keymessage.core.crypto.DerivedX25519KeyPair
import com.keymessage.core.crypto.Hash
import com.keymessage.core.crypto.HashImpl
import com.keymessage.core.crypto.Kdf
import com.keymessage.core.crypto.X25519
import com.keymessage.core.crypto.X25519KeyPair
import com.keymessage.core.crypto.provider.BcChaCha20Poly1305
import com.keymessage.core.crypto.provider.BcHkdfSha256
import com.keymessage.core.crypto.provider.BcX25519
import com.keymessage.core.messaging.FrameIdentity
import com.keymessage.core.messaging.PendingInboundEntry
import com.keymessage.core.model.MessageId
import com.keymessage.core.ratchet.ChainIdentifier
import com.keymessage.core.ratchet.DoubleRatchetSession
import com.keymessage.core.ratchet.DoubleRatchetSnapshot
import com.keymessage.core.ratchet.ReceiveChainSnapshot
import com.keymessage.core.ratchet.SymmetricRatchetSnapshot
import com.keymessage.core.sf.BinarySecureFrameCodec
import com.keymessage.core.sf.SecureFrameProtector
import com.keymessage.core.sf.SecureFrameSpec
import com.keymessage.core.sf.SecureRatchetProtocol
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 3Q.5.2b-A — El codec de la unidad `KM52`.
 *
 * Prefijo `KM52-`. Contrato congelado en la spec rev3 (§3.1 layout, §3.3
 * little-endian, §3.4 SHA-256, §6.9 cotas y reglas de lectura, §6.10 jerarquia).
 *
 * ## LAS DOS PRUEBAS QUE EL USUARIO EXIGIO, Y POR QUE SON LAS MAS FUERTES
 *
 * **1. `stateFingerprint()` NO prueba igualdad de snapshot.** La huella de
 * `DoubleRatchetSession.stateFingerprint()` se construye con `dhSelf.publicKey`
 * y NO con el escalar privado (`DoubleRatchetSession.kt:694`). Dos sesiones con
 * la misma huella pueden tener mitades privadas distintas y no poder hablar. La
 * prueba valida es el CIPHERTEXT byte a byte: `KM52-01b`.
 *
 * **2. Lectura sobredimensionada = RECHAZO TOTAL, SIN PODA.** `KM52-10` monta
 * una unidad estructuralmente valida que supera el presupuesto, exige
 * `RetentionBudgetExceeded`, y comprueba —con comportamiento criptografico, no
 * con una excepcion— que NO se podo nada: la clave retenida que una
 * implementacion que podara habria descartado SEGUIRA descifrando el frame que
 * la necesita. Una implementacion "lee, poda y restaura lo restante" deja ese
 * frame en `REPLAY_OR_UNKNOWN` y el test falla.
 */
/* ------------------------------------------------------------------ *
 * Utilidades de BYTES a nivel de archivo.
 *
 * Van fuera de la clase de test porque las clases anidadas que arman las
 * unidades (`B`, `Unidad`) NO ven los miembros de su clase externa, y su
 * error al no verlos es el mismo "unresolved reference" que el de un tipo
 * inexistente: dos fallos que se confunden.
 * ------------------------------------------------------------------ */

private enum class Endian { LITTLE, BIG }

private fun u32(v: Long, e: Endian): ByteArray {
    val out = ByteArray(4)
    if (e == Endian.LITTLE) {
        out[0] = (v and 0xFF).toByte()
        out[1] = ((v shr 8) and 0xFF).toByte()
        out[2] = ((v shr 16) and 0xFF).toByte()
        out[3] = ((v shr 24) and 0xFF).toByte()
    } else {
        out[0] = ((v shr 24) and 0xFF).toByte()
        out[1] = ((v shr 16) and 0xFF).toByte()
        out[2] = ((v shr 8) and 0xFF).toByte()
        out[3] = (v and 0xFF).toByte()
    }
    return out
}

private fun u64(v: ULong, e: Endian): ByteArray {
    val out = ByteArray(8)
    for (i in 0 until 8) {
        val shift = if (e == Endian.LITTLE) 8 * i else 8 * (7 - i)
        out[i] = ((v shr shift) and 0xFFuL).toByte()
    }
    return out
}

private val MENSAJE: MessageId = MessageId.from(UUID(0x0102030405060708L, 0x090a0b0c0d0e0f10L))

private fun CITA(): ByteArray = ByteArray(24) { (it * 5 + 1).toByte() }

class Km52CodecTest {

    private lateinit var x25519: X25519
    private lateinit var kdf: Kdf
    private lateinit var protector: SecureFrameProtector
    private lateinit var hash: Hash

    private val rootKey = ByteArray(32) { (it + 1).toByte() }

    @BeforeEach
    fun setUp() {
        x25519 = BcX25519()
        kdf = BcHkdfSha256()
        protector = SecureFrameProtector(BcChaCha20Poly1305(), kdf)
        hash = HashImpl()
    }

    private fun codec() = BinaryKm52UnitCodec(x25519, hash)

    // ===================================================================
    // GUION CRIPTOGRAFICO
    //
    // El mismo guion que `DoubleRatchetSnapshotTest` (3Q.5.2a), porque la
    // prueba criptografica de este checkpoint necesita una sesion REAL: un
    // snapshot sintetico no produce un ciphertext comparable con el de nadie.
    // ===================================================================

    private fun session(dhSelf: X25519KeyPair, dhRemote: ByteArray?): DoubleRatchetSession =
        DoubleRatchetSession(
            rootKey = rootKey,
            dhSelf = dhSelf,
            dhRemote = dhRemote,
            sendChainKey = ByteArray(32) { (it * 3).toByte() },
            receiveChainKey = ByteArray(32) { (it * 5).toByte() },
            x25519 = x25519,
            kdf = kdf,
        )

    private inner class Wire {
        val bobDh = x25519.generateKeyPair()
        val aliceSession = session(x25519.generateKeyPair(), bobDh.publicKey)
        val bobSession = session(bobDh, null)
        val alice = SecureRatchetProtocol(aliceSession, protector)
        val bob = SecureRatchetProtocol(bobSession, protector)

        init {
            aliceSession.initiateEpoch()
        }
    }

    private fun exchange(from: SecureRatchetProtocol, to: SecureRatchetProtocol, text: String): ByteArray {
        val wire = from.encrypt(text.toByteArray())
        val r = to.decrypt(wire)
        assertTrue(r is SecureRatchetProtocol.DecryptResult.Ok, "esperaba Ok en '$text', fue $r")
        return wire
    }

    private fun ok(r: SecureRatchetProtocol.DecryptResult): ByteArray {
        assertTrue(r is SecureRatchetProtocol.DecryptResult.Ok, "esperaba Ok, fue $r")
        return (r as SecureRatchetProtocol.DecryptResult.Ok).plaintext
    }

    /**
     * Deja a BOB con DOS cadenas de recepcion (`PN = 3`) y dos frames de la
     * epoca VIEJA sin entregar, que solo podran descifrarse desde claves
     * retenidas.
     *
     * @return los cinco frames de Alice, en el orden en que se emitieron.
     */
    private fun dosEpochs(w: Wire): List<ByteArray> {
        val a0 = exchange(w.alice, w.bob, "a0")
        val a1 = w.alice.encrypt("a1".toByteArray())   // N=1, epoca 1: RETENIDA
        val a2 = w.alice.encrypt("a2".toByteArray())   // N=2, epoca 1: RETENIDA
        exchange(w.bob, w.alice, "b0")                 // Alice rota: nueva DH
        exchange(w.bob, w.alice, "b1")
        exchange(w.bob, w.alice, "b2")
        val a3 = exchange(w.alice, w.bob, "a3")        // DH nueva: 2a cadena, PN = 3
        val a4 = w.alice.encrypt("a4".toByteArray())   // epoca 2, N = 1: PENDIENTE
        return listOf(a0, a1, a2, a3, a4)
    }

    private fun det(seed: Int) = X25519KeyPair(
        ByteArray(32) { ((it + seed) and 0xFF).toByte() },
        x25519.publicKey(ByteArray(32) { ((it + seed) and 0xFF).toByte() }),
    )

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Tabla de retencion completa para una foto, en orden canonico de `chainId`. */
    private fun retencionDe(foto: DoubleRatchetSnapshot): ReceiveChainRetention =
        ReceiveChainRetention(
            foto.receiveChains
                .mapIndexed { i, c -> ChainUseOrdinal(c.chainId, i.toULong()) }
                .sortedBy { hex(it.chainId) },
        )

    private fun registro(
        dh: ByteArray,
        pn: UInt = 0u,
        n: UInt = 0u,
        state: OutboundDeliveryState = OutboundDeliveryState.PENDIENTE,
        created: ULong = 7uL,
        ciphertext: ByteArray = CITA(),
    ) = OutboundRecord(
        recordVersion = 1u,
        deliveryState = state,
        messageId = MENSAJE,
        frameIdentity = FrameIdentity.of(dh, pn, n),
        createdOrdinal = created,
        ciphertext = ciphertext,
    )


    // ===================================================================
    // ENSAMBLADOR DE UNIDADES v2 (el otro lado del codec)
    //
    // Hace falta porque el codec NUNCA escribe una unidad invalida: si se le
    // pasa algo sobredimensionado lo poda, y si algo imposible lo rechaza. Para
    // probar el LADO DE LECTURA hace falta poder escribir a mano lo que el
    // codec no escribiria jamas.
    //
    // `KM52-00` demuestra que este ensamblador reproduce byte a byte lo que
    // produce `serialize`, asi que ninguna de las unidades artificiales de este
    // archivo se apoya en una layout inventada aqui.
    //
    // ## v2 (3Q.5.3): LO QUE CAMBIO EN ESTE ENSAMBLADOR
    //
    // Ahora hay CINCO regiones, no cuatro:
    //
    // ```
    // [HEADER 32][SNAPSHOT][OUTBOUND][PENDING_INBOUND][Checksum 32]
    // ```
    //
    // Y el header cambio de forma: los OCHO bytes de `reserved2` de v1 se
    // PARTEN en dos —cuatro que describen el bloque nuevo
    // ([Km52Spec.PENDING_INBOUND_LEN_OFFSET]) y cuatro que siguen reservados.
    // Un ensamblador de v1 aqui no escribiria el bloque, no sumaria su longitud
    // a `totalLen` y pondria el byte de version a 1: tres fallos distintos, y
    // solo el primero lo habria delatado.
    //
    // ## POR QUE `unitVersion` SE TOMA DE LA CONSTANTE DE PRODUCCION
    //
    // Bump de version no es un cambio de este archivo: es un cambio de
    // `Km52Spec.UNIT_VERSION`. Dejar el `1u` escrito a mano aqui fue
    // exactamente lo que produjo diez rojos de migracion. La version la fija
    // una sola vez, en produccion, y el ensamblador la sigue.
    //
    // Y eso NO debilita nada: los tests que fijan el LITERAL de la version —
    // `KM52-02` (que escribe 2 en el offset 4) y `KM52-05` (que exige que 2
    // sea la unica aceptada)— siguen siendo los que detectan un valor
    // equivocado. `KM52-00` no tiene por que detectarlo: tiene por que detectar
    // la DERIVA entre este ensamblador y el codec.
    // ===================================================================

    private class SendRec(
        val sendChainKey: ByteArray = ByteArray(32) { 0x11 },
        val sendMessageNumber: UInt = 0u,
        val receiveMessageNumber: UInt = 0u,
        val skipped: List<Triple<ByteArray, UInt, ByteArray>> = emptyList(),
        val skippedCountOverride: Int? = null,
    )

    private class RecvRec(
        val chainId: ByteArray,
        val lastUseOrdinal: ULong = 0uL,
        val sendChainKey: ByteArray = ByteArray(32) { 0x22 },
        val sendMessageNumber: UInt = 0u,
        val receiveChainKey: ByteArray = ByteArray(32) { 0x33 },
        val receiveMessageNumber: UInt = 0u,
        val skipped: List<Triple<ByteArray, UInt, ByteArray>> = emptyList(),
        val skippedCountOverride: Int? = null,
        /** Escribe un `chainId` que no es el propio en la entrada saltada 0. */
        val foreignSkippedChainId: ByteArray? = null,
    )

    private class Snap(
        val rootKey: ByteArray = ByteArray(32) { 0x41 },
        val dhSelfScalar: ByteArray = ByteArray(32) { 0x51 },
        val dhRemotePresent: Int = 0,
        val dhRemote: ByteArray = ByteArray(32) { 0x61 },
        val ns: UInt = 0u,
        val pn: UInt = 0u,
        val sendChain: SendRec = SendRec(),
        val receiveChainCountOverride: Int? = null,
        val receiveChains: List<RecvRec> = emptyList(),
    )

    private class OutRec(
        val recordVersion: UByte = 1u,
        val deliveryState: UByte = 1u,
        val reserved16: UShort = 0u,
        val messageId: MessageId = MENSAJE,
        val frameIdentityLen: UInt = 40u,
        val frameIdentity: ByteArray = ByteArray(40) { 0x71 },
        val createdOrdinal: ULong = 0uL,
        val ciphertext: ByteArray = ByteArray(20) { 0x81.toByte() },
    )

    /**
     * Una entrada del bloque `PENDING_INBOUND` de `v2`.
     *
     * El `frameIdentity` va separado del wire-frame a proposito: el codec
     * CONTRASTA las dos copias y convierte "esta entrada dice de un frame que
     * su header no dice" en un rechazo tipado, y un ensamblador que no
     * permitiera separarlas no podria provar ese camino.
     */
    private class InbRec(
        val frameIdentity: ByteArray,
        val wireFrame: ByteArray,
        val frameIdentityLenOverride: UInt? = null,
        /** Escribe una identidad que NO es la del header del wire-frame. */
        val frameIdentityDistinta: Boolean = false,
    )

    private class Unidad(
        val snapshot: Snap,
        val outbound: OutRec,
        /** Las entradas del bloque `PENDING_INBOUND`, EN ORDEN DE LLEGADA. */
        val pendingInbound: List<InbRec> = emptyList(),
        val magic: ByteArray = "KM52".toByteArray(Charsets.US_ASCII),
        val unitVersion: UByte = Km52Spec.UNIT_VERSION,
        val flags: UByte = 0u,
        val reserved16: UShort = 0u,
        /**
         * Los cuatro bytes que QUEDAN reservados, los offsets 28..31.
         *
         * Eran ocho en `v1` porque los cuatro primeros eran relleno. En `v2`
         * esos cuatro describen el bloque `PENDING_INBOUND`, asi queReserved2
         * son cuatro y el ancho se mide aqui, no en el codigo de `bytes()`.
         */
        val reserved2: ByteArray = ByteArray(4),
        val endian: Endian = Endian.LITTLE,
        val snapshotLenOverride: Long? = null,
        val outboundLenOverride: Long? = null,
        val ciphertextLenOverride: Long? = null,
        val pendingInboundLenOverride: Long? = null,
        /** Anuncia un `count` que no es el de las entradas escritas. */
        val pendingCountOverride: Long? = null,
        val totalLenOverride: Long? = null,
        /** Sobrescribe el checksum; `null` = checksum correcto. */
        val checksumOverride: ByteArray? = null,
        val trailing: ByteArray = ByteArray(0),
    ) {
        /** Variante con un campo cambiado. No es `data class` porque sus campos
         *  son `ByteArray` y la igualdad por referencia seria una trampa. */
        @Suppress("LongParameterList")
        fun copy(
            snapshot: Snap = this.snapshot,
            outbound: OutRec = this.outbound,
            pendingInbound: List<InbRec> = this.pendingInbound,
            magic: ByteArray = this.magic,
            unitVersion: UByte = this.unitVersion,
            flags: UByte = this.flags,
            reserved16: UShort = this.reserved16,
            reserved2: ByteArray = this.reserved2,
            endian: Endian = this.endian,
            snapshotLenOverride: Long? = this.snapshotLenOverride,
            outboundLenOverride: Long? = this.outboundLenOverride,
            ciphertextLenOverride: Long? = this.ciphertextLenOverride,
            pendingInboundLenOverride: Long? = this.pendingInboundLenOverride,
            pendingCountOverride: Long? = this.pendingCountOverride,
            totalLenOverride: Long? = this.totalLenOverride,
            checksumOverride: ByteArray? = this.checksumOverride,
            trailing: ByteArray = this.trailing,
        ) = Unidad(
            snapshot, outbound, pendingInbound, magic, unitVersion, flags, reserved16, reserved2, endian,
            snapshotLenOverride, outboundLenOverride, ciphertextLenOverride, pendingInboundLenOverride,
            pendingCountOverride, totalLenOverride, checksumOverride, trailing,
        )
    }

    private class B {
        private val out = ByteArrayOutputStream()
        fun b(v: Int) { out.write(v and 0xFF) }
        fun b16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun b32(v: Long, e: Endian) { out.write(u32(v, e)) }
        fun b64(v: ULong, e: Endian) { out.write(u64(v, e)) }
        fun bytes(a: ByteArray) { out.write(a, 0, a.size) }
        fun build(): ByteArray = out.toByteArray()
    }

    private fun skipEntry(chainId: ByteArray, n: UInt, key: ByteArray, e: Endian): ByteArray =
        B().apply {
            bytes(chainId)
            b32(n.toLong(), e)
            bytes(key)
        }.build()

    private fun Snap.encode(e: Endian): ByteArray = B().apply {
        bytes(rootKey)
        bytes(dhSelfScalar)
        b(dhRemotePresent)
        if (dhRemotePresent == 1) bytes(dhRemote)
        b32(ns.toLong(), e)
        b32(pn.toLong(), e)
        // sendChain: sendChainKey(32) + Ns(4) + Nr(4) + count(2) + skipped*
        bytes(sendChain.sendChainKey)
        b32(sendChain.sendMessageNumber.toLong(), e)
        b32(sendChain.receiveMessageNumber.toLong(), e)
        b16(sendChain.skippedCountOverride ?: sendChain.skipped.size)
        sendChain.skipped.forEach { bytes(skipEntry(it.first, it.second, it.third, e)) }
        // receiveChainCount(2)
        b16(receiveChainCountOverride ?: receiveChains.size)
        receiveChains.forEach { r ->
            bytes(r.chainId)
            b64(r.lastUseOrdinal, e)
            bytes(r.sendChainKey)
            b32(r.sendMessageNumber.toLong(), e)
            bytes(r.receiveChainKey)
            b32(r.receiveMessageNumber.toLong(), e)
            b16(r.skippedCountOverride ?: r.skipped.size)
            r.skipped.forEachIndexed { i, s ->
                val id = if (i == 0) (r.foreignSkippedChainId ?: r.chainId) else r.chainId
                bytes(skipEntry(id, s.second, s.third, e))
            }
        }
    }.build()

    private fun OutRec.encode(e: Endian): ByteArray = B().apply {
        b(recordVersion.toInt())
        b(deliveryState.toInt())
        b16(reserved16.toInt())
        val msb = messageId.value.mostSignificantBits
        val lsb = messageId.value.leastSignificantBits
        for (i in 7 downTo 0) b(((msb shr (8 * i)) and 0xFF).toInt())
        for (i in 7 downTo 0) b(((lsb shr (8 * i)) and 0xFF).toInt())
        b32(frameIdentityLen.toLong(), e)
        bytes(frameIdentity)
        b64(createdOrdinal, e)
        bytes(ciphertext)
    }.build()

    /**
     * `frameIdentityLen(4) + frameIdentity(40) + wireLen(4) + wire`: los 48 fijos
     * de [Km52Spec.PENDING_ENTRY_FIXED_LENGTH] y el frame entero detras.
     */
    private fun InbRec.encode(e: Endian): ByteArray = B().apply {
        b32((frameIdentityLenOverride ?: 40u).toLong(), e)
        bytes(if (frameIdentityDistinta) ByteArray(40) { 0x77 } else frameIdentity)
        b32(wireFrame.size.toLong(), e)
        bytes(wireFrame)
    }.build()

    private fun Unidad.bytes(): ByteArray {
        val e = endian
        val snap = snapshot.encode(e)
        val outRec = outbound.encode(e)
        // El bloque `PENDING_INBOUND` NO desaparece cuando esta vacio: vale
        // `count = 0`, cuatro bytes. Un bloque de longitud cero seria ambiguo
        // con "no hay bloque", y en v2 son la misma cosa. Escribirlo siempre es
        // lo que hace `encodePendingInbound`, y por eso el ensamblador tiene que
        // hacerlo aunque `pendingInbound` este vacio.
        val inb = B().apply {
            b32(pendingCountOverride ?: pendingInbound.size.toLong(), e)
            pendingInbound.forEach { bytes(it.encode(e)) }
        }.build()
        val ct = outbound.ciphertext
        // El ciphertext va DENTRO del bloque OUTBOUNDRECORD: se cuenta una vez.
        val total = 32L + snap.size + outRec.size + inb.size + 32
        val header = B().apply {
            bytes(magic)
            b(unitVersion.toInt())
            b(flags.toInt())
            b16(reserved16.toInt())
            b32(snapshotLenOverride ?: snap.size.toLong(), e)
            b32(outboundLenOverride ?: outRec.size.toLong(), e)
            b32(ciphertextLenOverride ?: ct.size.toLong(), e)
            b32(totalLenOverride ?: total, e)
            // v2: los offsets 24..27 son la longitud del bloque nuevo, no
            // relleno. Los 28..31 siguen siendo los reservados que quedan.
            b32(pendingInboundLenOverride ?: inb.size.toLong(), e)
            bytes(reserved2)
        }.build()
        // CINCO regiones, en el orden del layout de v2. El bloque de entrada va
        // ENTRE el outbound y el checksum: es lo que hace que `totalLen` no
        // coincida con el de v1 ni con una unidad a la que falte el bloque.
        val cuerpo = header + snap + outRec + inb
        val completo = cuerpo + trailing
        // `total - 32`: el checksum se calcula sobre el CUERPO, y el propio
        // checksum son los ultimos 32 B. Pedir `total` bytes seria pedir 32 mas
        // de los que hay, y `copyOf` los rellenaria de ceros: un hash de unos
        // bytes que nadie escribio jamas.
        val checksum = checksumOverride ?: hash.sha256(completo.copyOf(total.toInt() - 32))
        return completo + checksum
    }

    private fun le32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun be32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    /** Bloques minimos validos: snapshot sin cadenas, OUTBOUND con 20 B de cita. */
    private fun snapBase(): Snap = Snap()
    private fun outBase(): OutRec = OutRec()

    /**
     * Un SecureFrame v1 REAL de 44 B, para las entradas de `PENDING_INBOUND`.
     *
     * Sale del guion criptografico y no de un array de relleno porque la
     * identidad se LEE del header: una entrada con un wire inventado pasaria el
     * contraste de identidades por casualidad o no pasaria nunca, y en los dos
     * casos el ensamblador estaria probando otra cosa.
     */
    private fun wireDeEntrada(w: Wire, texto: String): ByteArray = w.alice.encrypt(texto.toByteArray())

    private fun inbDe(wire: ByteArray) = InbRec(FrameIdentity.fromWire(wire).bytes, wire)

    // ===================================================================
    // KILOMETRO 0 — El ensamblador es de fiar
    // ===================================================================

    @Test
    @DisplayName("KM52-00 el ensamblador del test reproduce byte a byte lo que escribe el codec")
    fun `KM52-00 el ensamblador reproduce el codec`() {
        // Una sesion REAL con dos cadenas de recepcion: si el ensamblador y el
        // codec no coinciden byte a byte aqui, ninguna de las unidades
        // artificiales de este archivo apoyaria en el layout que dice el codec.
        val w = Wire()
        dosEpochs(w)
        val foto = w.bobSession.snapshot()
        assertEquals(2, foto.receiveChains.size, "el guion debe dejar dos cadenas que codificar")
        val orden = foto.receiveChains.map { ChainUseOrdinal(it.chainId, 4uL) }.sortedBy { hex(it.chainId) }
        val rec = registro(
            w.bobSession.selfDhPublicKey(), 3u, 5u, OutboundDeliveryState.IN_FLIGHT, 7uL,
        )
        val retencion = ReceiveChainRetention(orden)

        // El SNAPSHOT y el OUTBOUND a mano. Se construyen UNA vez y se reutilizan
        // en los dos casos de abajo: lo que se compara es el bloque de entrada y
        // el total, no dos guiones distintos.
        val snapAMano = Snap(
            rootKey = foto.rootKey,
            dhSelfScalar = foto.dhSelf.privateKeyBytes(),
            dhRemotePresent = 1,
            dhRemote = foto.dhRemote!!,
            ns = foto.sendMessageNumber,
            pn = foto.previousChainLength,
            sendChain = SendRec(
                sendChainKey = foto.sendChain.sendChainKey,
                sendMessageNumber = foto.sendChain.sendMessageNumber,
                receiveMessageNumber = foto.sendChain.receiveMessageNumber,
            ),
            // Orden CANONICO: el codec escribe las cadenas ordenadas por
            // `chainId`, y el ensamblador tiene que hacer lo mismo o la
            // comparacion estaria midiendo el orden de las dos listas.
            receiveChains = foto.receiveChains
                .sortedBy { hex(it.chainId) }
                .map { c ->
                    RecvRec(
                        chainId = c.chainId,
                        lastUseOrdinal = 4uL,
                        sendChainKey = c.ratchet.sendChainKey,
                        sendMessageNumber = c.ratchet.sendMessageNumber,
                        receiveChainKey = c.ratchet.receiveChainKey,
                        receiveMessageNumber = c.ratchet.receiveMessageNumber,
                        skipped = c.ratchet.skipped.entries
                            .sortedWith(compareBy({ hex(it.key.first.bytes) }, { it.key.second }))
                            .map { Triple(it.key.first.bytes, it.key.second, it.value) },
                    )
                },
        )
        val outAMano = OutRec(
            recordVersion = 1u,
            deliveryState = OutboundDeliveryState.IN_FLIGHT.code,
            reserved16 = 0u,
            messageId = MENSAJE,
            frameIdentityLen = 40u,
            frameIdentity = FrameIdentity.of(
                w.bobSession.selfDhPublicKey(), 3u, 5u,
            ).bytes,
            createdOrdinal = 7uL,
            ciphertext = CITA(),
        )

        // --- CASO A: la bandeja VACIA. El bloque nuevo vale `count = 0`. -----
        assertContentEquals(
            codec().serialize(Km52Unit(foto, retencion, rec)),
            Unidad(snapshot = snapAMano, outbound = outAMano).bytes(),
            "el ensamblador del test y el codec tienen que coincidir con la bandeja vacia",
        )

        // --- CASO B: la bandeja CON entradas. -------------------------------
        //
        // ## POR QUE HAY UN SEGUNDO CASO Y NO VALE EL DE ARRIBA
        //
        // El caso A mide cuatro bytes de bloque: los del `count`. Un
        // ensamblador v2 que codificara la cabecera de una entrada EN ORDEN
        // INVERSO, o que confundiera el `wireLen` con la longitud de la
        // identidad, o que no escribiera el wire detras de los 48 fijos, pasaria
        // el caso A intacto. El bloque nuevo no se demuestra con un caso que
        // solo mide su caso minimo: se demuestra con el bloque LLENO, y por eso
        // el meta-test de `v2` tiene dos casos donde el de `v1` tenia uno.
        //
        // Y no hace falta inventar las entradas: son frames de verdad del guion,
        // en el orden en que llegaron, que es el orden en que el codec los
        // escribe (INV-07).
        val entrantes = listOf(wireDeEntrada(w, "e0"), wireDeEntrada(w, "e1"), wireDeEntrada(w, "e2"))
        assertContentEquals(
            codec().serialize(
                Km52Unit(
                    foto, retencion, rec,
                    entrantes.map { PendingInboundEntry(FrameIdentity.fromWire(it), it) },
                ),
            ),
            Unidad(
                snapshot = snapAMano, outbound = outAMano,
                pendingInbound = entrantes.map { inbDe(it) },
            ).bytes(),
            "el bloque PENDING_INBOUND tiene que codificarse igual, entrada por entrada y en orden",
        )

        // --- Y LA VERSION DEL LAYOUT ES LO QUE SEPARA LOS DOS MUNDOS. --------
        //
        // El MISMO cuerpo de `v2`, con el byte de version puesto a 1, es un
        // byte-string que el lector de `v2` rechaza. No es decorativo: es lo que
        // demuestra que el ensamblador de arriba construye una unidad de `v2` de
        // verdad y no una unidad de `v1` con la etiqueta cambiada. Si el
        // ensamblador hubiera omitido el bloque de entrada, esta comprobacion
        // seguiria dando verde y `KM52-00` habria perdido su mordida.
        val etiquetadaComoV1 = Unidad(
            snapshot = snapAMano, outbound = outAMano,
            pendingInbound = entrantes.map { inbDe(it) },
            unitVersion = 1u,
        ).bytes()
        val rechazada = assertThrows<UnsupportedUnitVersion> { codec().deserialize(etiquetadaComoV1) }
        assertEquals(1u.toUByte(), rechazada.version, "el byte de version es el que separa las dos unidades")
    }

    // ===================================================================
    // KILOMETRO 1 — Ida y vuelta, probada con CIFRADO REAL
    // ===================================================================

    @Test
    @DisplayName("KM52-01 la unidad sobrevive a la ida y vuelta con TODO su contenido")
    fun `KM52-01 ida y vuelta`() {
        val w = Wire()
        dosEpochs(w)
        val foto = w.bobSession.snapshot()
        assertTrue(foto.receiveChains.isNotEmpty(), "el guion debe dejar cadenas que perder")

        val entrada = Km52Unit(
            snapshot = foto,
            retention = retencionDe(foto),
            outbound = registro(w.bobSession.selfDhPublicKey(), 3u, 5u, OutboundDeliveryState.IN_FLIGHT, 42uL),
        )
        val salida = codec().deserialize(codec().serialize(entrada))

        assertContentEquals(foto.rootKey, salida.snapshot.rootKey, "raiz")
        assertContentEquals(foto.dhSelf.privateKeyBytes(), salida.snapshot.dhSelf.privateKeyBytes(), "escalar DH")
        assertContentEquals(foto.dhRemote!!, salida.snapshot.dhRemote!!, "DH remota")
        assertEquals(foto.sendMessageNumber, salida.snapshot.sendMessageNumber, "Ns")
        assertEquals(foto.previousChainLength, salida.snapshot.previousChainLength, "PN")
        assertEquals(foto.receiveChains.size, salida.snapshot.receiveChains.size, "cadenas")
        // El codec escribe las cadenas en ORDEN CANONICO por `chainId` (INV-07:
        // los bytes no pueden depender del recorrido de un mapa), asi que la
        // comparacion se hace sobre ese orden y no sobre el de insercion.
        val esperadoOrden = foto.receiveChains.sortedBy { hex(it.chainId) }
        assertEquals(
            esperadoOrden.map { hex(it.chainId) },
            salida.snapshot.receiveChains.map { hex(it.chainId) },
            "las cadenas vuelven en orden canonico",
        )
        for (i in esperadoOrden.indices) {
            val a = esperadoOrden[i]
            val b = salida.snapshot.receiveChains[i]
            assertContentEquals(a.chainId, b.chainId, "chainId[$i]")
            assertEquals(
                entrada.retention.lastUseOrdinal(a.chainId),
                salida.retention.lastUseOrdinal(b.chainId),
                "lastUseOrdinal[$i]",
            )
            assertContentEquals(a.ratchet.sendChainKey, b.ratchet.sendChainKey, "CKs[$i]")
            assertContentEquals(a.ratchet.receiveChainKey, b.ratchet.receiveChainKey, "CKr[$i]")
            assertEquals(
                a.ratchet.skipped.keys.sortedBy { it.second },
                b.ratchet.skipped.keys.sortedBy { it.second },
                "claves retenidas[$i]",
            )
            for ((k, v) in a.ratchet.skipped) {
                assertContentEquals(v, b.ratchet.skipped[k], "clave retenida N=${k.second}")
            }
        }
        // `outbound` es OPCIONAL en v2, asi que se comprueba su presencia antes
        // de leerlo. La comprobacion NO es semantica nueva: es el `!!` de antes,
        // escrito para que el fallo diga QUE falta y no sea un NPE.
        val entradaOut = requireNotNull(entrada.outbound) { "la unidad escrita lleva outbound" }
        val salidaOut = assertNotNull(salida.outbound, "y la unidad leida tambien")
        assertEquals(entradaOut.deliveryState, salidaOut.deliveryState, "deliveryState")
        assertEquals(entradaOut.messageId, salidaOut.messageId, "messageId")
        assertEquals(entradaOut.frameIdentity, salidaOut.frameIdentity, "frameIdentity")
        assertEquals(entradaOut.createdOrdinal, salidaOut.createdOrdinal, "createdOrdinal")
        assertContentEquals(entradaOut.ciphertext, salidaOut.ciphertext, "ciphertext")
        assertEquals(
            emptyList<PendingInboundEntry>(), salida.pendingInbound,
            "una unidad v2 sin frames entrantes lleva la bandeja VACIA, no ausente",
        )
    }

    @Test
    @DisplayName("KM52-01b la sesion restaurada desde la unidad produce el MISMO ciphertext, byte a byte")
    fun `KM52-01b mismo ciphertext tras snapshot unidad bytes restore`() {
        // LA PRUEBA CRIPTOGRAFICA FUERTE. No se compara `stateFingerprint()`:
        // la huella de `DoubleRatchetSession` se construye con
        // `dhSelf.publicKey` y no cubre el escalar privado, asi que dos sesiones
        // con la misma huella pueden tener mitades privadas distintas y no poder
        // hablar. Lo que no miente es el cable.
        val w = Wire()
        dosEpochs(w)
        val foto = w.bobSession.snapshot()
        val carga = "contenido que no puede cambiar de bytes".toByteArray()

        // Dos mensajes seguidos, cada uno con su propia foto: es el guion de
        // `SNAP-03` de 3Q.5.2a. Tomar las dos fotos ANTES de enviar compararia
        // dos sesiones en el mismo instante y no probaria que la cadena avanza.
        fun sesionPorLaUnidad(fotoActual: DoubleRatchetSnapshot): DoubleRatchetSession {
            val bytes = codec().serialize(
                Km52Unit(fotoActual, retencionDe(fotoActual), registro(w.bobSession.selfDhPublicKey())),
            )
            return DoubleRatchetSession.restore(codec().deserialize(bytes).snapshot, x25519, kdf)
        }

        val original = w.bob.encrypt(carga)
        val restaurada = sesionPorLaUnidad(foto)
        assertContentEquals(
            original, SecureRatchetProtocol(restaurada, protector).encrypt(carga),
            "el frame tiene que salir IDENTICO",
        )
        // Y no es una coincidencia de un solo mensaje: el segundo tambien, con
        // la cadena de envio ya avanzada desde la foto POSTERIOR.
        val original2 = w.bob.encrypt(carga)
        assertContentEquals(
            original2, SecureRatchetProtocol(sesionPorLaUnidad(restaurada.snapshot()), protector).encrypt(carga),
            "y el siguiente sale igual con la cadena ya avanzada",
        )
    }

    @Test
    @DisplayName("KM52-01c un escalar DH Danado cambia el frame: por eso la huella no basta")
    fun `KM52-01c el escalar DH cuenta`() {
        // CONTROL NEGATIVO de `KM52-01b`, y la razon de no usar la huella.
        //
        // Una implementacion que guardara la clave PUBLICA y regenerase el
        // escalar al leer produciria una sesion con la MISMA huella (la huella
        // es un hash de la publica) que NO puede hablar con nadie. Aqui se
        // comprueba el caso ejecutable y simetrico: una unidad cuyo escalar es
        // OTRO escalar valido, con el checksum recalculado, se acepta
        // estructuralmente pero produce un frame DISTINTO.
        val w = Wire()
        val foto = w.bobSession.snapshot()
        val carga = "x".toByteArray()
        val otroEscalar = det(99).privateKey

        val sana = Unidad(
            snapshot = Snap(
                dhSelfScalar = foto.dhSelf.privateKeyBytes(),
                ns = foto.sendMessageNumber,
                sendChain = SendRec(sendMessageNumber = foto.sendMessageNumber),
            ),
            outbound = outBase(),
        ).bytes()
        val danada = Unidad(
            snapshot = Snap(
                dhSelfScalar = otroEscalar,
                ns = foto.sendMessageNumber,
                sendChain = SendRec(sendMessageNumber = foto.sendMessageNumber),
            ),
            outbound = outBase(),
        ).bytes()
        assertFalse(sana.contentEquals(danada), "las dos unidades deben diferir en el escalar")

        val frameDe = { u: ByteArray ->
            val s = DoubleRatchetSession.restore(codec().deserialize(u).snapshot, x25519, kdf)
            SecureRatchetProtocol(s, protector).encrypt(carga)
        }
        assertFalse(
            frameDe(sana).contentEquals(frameDe(danada)),
            "el escalar privado separa dos sesiones con la misma publica: por eso la huella no basta",
        )
    }

    // ===================================================================
    // KILOMETRO 2-3 — Layout del header y little-endian
    // ===================================================================

    @Test
    @DisplayName("KM52-02 el header son 32 B fijos y los tres bloques mas el checksum completan totalLen")
    fun `KM52-02 header fijo de 32 bytes`() {
        val bytes = codec().serialize(unidadDePrueba())
        assertEquals("KM52", String(bytes, 0, 4, Charsets.US_ASCII), "magic en el offset 0")
        // El LITERAL, no [Km52Spec.UNIT_VERSION]: este test fija el valor en el
        // cable, y referenciar la constante de produccion lo volveria
        // tautologico — pasaria valga lo que valga la constante. El literal 2
        // es el contrato de `v2`; si el bump se revierte, este test se pone rojo.
        assertEquals(2, bytes[4].toInt(), "unitVersion en el offset 4")
        assertEquals(0, bytes[5].toInt(), "flags en el offset 5")
        assertEquals(0, bytes[6].toInt() or (bytes[7].toInt() shl 8), "reserved en el offset 6")
        val snapshotLen = le32(bytes, 8)
        val outboundLen = le32(bytes, 12)
        val ciphertextLen = le32(bytes, 16)
        val totalLen = le32(bytes, 20)
        val pendingInboundLen = le32(bytes, Km52Spec.PENDING_INBOUND_LEN_OFFSET)
        // `reserved2` son los offsets 28..31. Los 24..27 ya no son relleno:
        // describen el bloque `PENDING_INBOUND`, y por eso tienen que leerse
        // APARTE, o el test estaria afirmando que un campo con longitud esta a
        // cero — que en v2 es exactamente el caso de una unidad sin bandeja.
        assertEquals(
            4L, pendingInboundLen,
            "una unidad sin bandeja escribe count(4) en el offset 24, no un bloque de cero",
        )
        assertEquals(
            List(4) { 0 },
            bytes.copyOfRange(Km52Spec.RESERVED2_OFFSET, Km52Spec.HEADER_LENGTH).map { it.toInt() },
            "reserved2 a cero, y son cuatro bytes desde el offset 28",
        )
        // Y el bloque no solo mide lo que dice: esta donde dice. Los cuatro bytes
        // justo antes del checksum son su `count`, y estan a cero porque la
        // unidad de prueba no lleva bandeja.
        assertEquals(
            List(4) { 0 },
            bytes.copyOfRange(
                bytes.size - Km52Spec.CHECKSUM_LENGTH - Km52Spec.PENDING_INBOUND_COUNT_LENGTH,
                bytes.size - Km52Spec.CHECKSUM_LENGTH,
            ).map { it.toInt() },
            "el bloque de entrada va ENTRE el outbound y el checksum",
        )
        // `ciphertextLen` NO suma: es el ultimo campo del bloque OUTBOUND y ya
        // esta dentro de `outboundLen`. Ver la nota de la aritmetica de §3.1.
        assertEquals(
            32L + snapshotLen + outboundLen + pendingInboundLen + 32L, totalLen,
            "totalLen: en v2 son CINCO regiones, no cuatro",
        )
        assertEquals(
            ciphertextLen, outboundLen - Km52Spec.OUTBOUND_FIXED_LENGTH,
            "ciphertextLen es el ultimo campo del bloque OUTBOUND, no una region aparte",
        )
        assertEquals(bytes.size.toLong(), totalLen, "totalLen es el tamano real")
        assertTrue(totalLen >= 64, "una unidad no puede ser menor que cabecera + checksum")
        assertTrue(snapshotLen > 0 && outboundLen > 0, "los dos bloques tienen contenido")
        assertEquals(outboundLen - 72L, ciphertextLen, "OUTBOUND = 72 B fijos + ciphertext")
    }

    private fun unidadDePrueba(): Km52Unit {
        val remota = det(2).publicKey
        val foto = session(det(1), remota).snapshot()
        return Km52Unit(foto, ReceiveChainRetention(emptyList()), registro(remota, 0u, 0u))
    }

    @Test
    @DisplayName("KM52-03 la unidad es LITTLE-ENDIAN aunque el wire sea big-endian (R-FMT-ENDIAN-01)")
    fun `KM52-03 little endian`() {
        val w = Wire()
        dosEpochs(w)
        val foto = w.bobSession.snapshot()
        assertEquals(3u, foto.previousChainLength, "el guion debe dejar PN = 3")
        val bytes = codec().serialize(Km52Unit(foto, retencionDe(foto), registro(w.bobSession.selfDhPublicKey())))

        // PN vive en el offset 101 del bloque SNAPSHOT (32+32+1+32+4).
        val offsetPN = Km52Spec.HEADER_LENGTH + 101
        assertEquals(3L, le32(bytes, offsetPN), "PN se lee en little-endian")
        assertEquals(3L shl 24, be32(bytes, offsetPN), "y en big-endian seria OTRO numero: la divergencia es real")

        // Y una unidad escrita en big-endian NO se acepta: leer big-endian
        // seria el bug, no la compatibilidad.
        val enBe = Unidad(
            snapshot = Snap(ns = 3u, pn = 3u, sendChain = SendRec(sendMessageNumber = 3u)),
            outbound = outBase(),
            endian = Endian.BIG,
        ).bytes()
        val e = assertThrows<Km52FormatException> { codec().deserialize(enBe) }
        assertTrue(
            e.message!!.contains("longitud", ignoreCase = true) || e.message!!.contains("total", ignoreCase = true),
            "una unidad en big-endian tiene que rechazarse por longitudes incoherentes: $e",
        )
    }

    // ===================================================================
    // KILOMETRO 4 — totalLen EXACTO, y el truncamiento se ve antes del payload
    // ===================================================================

    @Test
    @DisplayName("KM52-04 el truncamiento se detecta ANTES de leer un solo byte de payload")
    fun `KM52-04 truncamiento antes del payload`() {
        val buena = codec().serialize(unidadDePrueba())
        assertTrue(buena.size > 64, "la unidad de prueba tiene contenido que truncar")

        // Un doble que CUENTA. La derivacion de la clave publica DH propia es
        // la primera operacion que el codec hace sobre el payload, asi que si
        // no se ha llamado, no se ha leido nada.
        val espia = EspiaX25519(x25519)
        val c = BinaryKm52UnitCodec(espia, hash)

        val cortada = buena.copyOf(buena.size - 1)
        val e = assertThrows<Km52Truncated> { c.deserialize(cortada) }
        assertEquals(buena.size.toLong(), e.declared, "el header declara el tamano real")
        assertEquals(cortada.size, e.available, "y estan disponibles menos")
        assertEquals(0, espia.publicKeyCalls, "no se puede haber leido el payload: no se ha derivado ninguna clave")

        // Tampoco una unidad a la que le falte la cabecera entera.
        assertThrows<Km52Truncated> { c.deserialize(ByteArray(31)) }
        assertEquals(0, espia.publicKeyCalls, "ni con la cabecera incompleta")

        // Canonicalidad: sobran bytes tambien se rechaza.
        assertThrows<Km52FormatException> { c.deserialize(buena + byteArrayOf(0)) }
        assertEquals(0, espia.publicKeyCalls, "los bytes sobrantes tambien se ven en el header")

        // Y un totalLen declarado que no cuadra con la suma de los bloques.
        val mentiroso = Unidad(
            snapshot = snapBase(), outbound = outBase(), totalLenOverride = 9999L,
        ).bytes()
        assertThrows<Km52FormatException> { c.deserialize(mentiroso) }
        assertEquals(0, espia.publicKeyCalls, "totalLen incoherente se ve sin tocar el payload")
    }

    private class EspiaX25519(private val real: X25519) : X25519 {
        var publicKeyCalls = 0
        var agreeCalls = 0
        override fun generateKeyPair(): X25519KeyPair = real.generateKeyPair()
        override fun publicKey(privateKey: ByteArray): ByteArray {
            publicKeyCalls++
            return real.publicKey(privateKey)
        }
        override fun agree(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
            agreeCalls++
            return real.agree(privateKey, publicKey)
        }
    }

    // ===================================================================
    // KILOMETRO 5-7 — Version, magic, checksum
    // ===================================================================

    @Test
    @DisplayName("KM52-05 una unitVersion distinta de 2 se rechaza sin leer el payload")
    fun `KM52-05 version desconocida`() {
        val espia = EspiaX25519(x25519)
        val c = BinaryKm52UnitCodec(espia, hash)
        // ## POR QUE `1` ENTRA AHORA Y NO ESTABA
        //
        // Antes la lista era 0, 2, 7, 255 y el `2` estaba porque la version
        // vigente era la 1. En v2 el papel se invierte: la version vigente es la
        // 2, y el `1` es el caso que de verdad importa, porque es la ANTERIOR.
        // Un byte-string de v1 tiene ocho bytes de `reserved2` donde v2 tiene
        // cuatro bytes de longitud de bloque: son cuatro regiones contra cinco,
        // y un lector de v2 que aceptase v1 leeria el bloque equivocado sin
        // ninguna clase de error — leeria otra cosa. Por eso v1 se rechaza
        // ENTERO y no se "interpreta como v2 sin el bloque".
        for (v in listOf(0u, 1u, 3u, 7u, 255u)) {
            val bytes = Unidad(
                snapshot = snapBase(), outbound = outBase(), unitVersion = v.toUByte(),
            ).bytes()
            val e = assertThrows<UnsupportedUnitVersion> { c.deserialize(bytes) }
            assertEquals(v.toUByte(), e.version, "el error dice que version vio")
        }
        assertEquals(0, espia.publicKeyCalls, "una version desconocida corta ANTES de interpretar nada")

        // ## Y POR QUE ESTA MITAD DEL TEST NO SE PUEDE QUITAR
        //
        // Lo de arriba, solo, no demuestra el contrato: un lector que
        // rechazase SIEMPRE pasaria la lista entera y el test saldria verde
        // mientras el codec fuese inservible. La propiedad real es de DOS
        // partes —"se acepta exactamente la 2"— y la parte que se acaba de
        // comprobar es la facil. La de que 2 SI se acepta y produce una unidad
        // entera es la que hacia falta, y la que el nombre del test afirma.
        //
        // Se usa un codec NUEVO y no la espia: la lectura de una unidad con
        // `dhRemote` ausente deriva la clave publica DH propia, que es
        // precisamente lo que la espia cuenta. Reutilizarla mixingaria el
        // "cero llamadas del rechazo" con la llamada legitima de la lectura.
        val original = unidadDePrueba()
        val buena = codec().serialize(original)
        assertEquals(2, buena[4].toInt(), "el layout vigente escribe 2 en el offset 4")
        val leida = codec().deserialize(buena)
        val envio = assertNotNull(leida.outbound, "una unidad v2 bien formada se lee ENTERA")
        assertEquals(
            original.outbound!!.ciphertext.size, envio.ciphertext.size,
            "con su contenido intacto: aceptar no es pasar por alto",
        )
        assertContentEquals(
            original.outbound!!.ciphertext, envio.ciphertext,
            "y el ciphertext es el MISMO, byte a byte",
        )
    }

    @Test
    @DisplayName("KM52-06 un magic distinto de KM52 no es una unidad")
    fun `KM52-06 magic equivocado`() {
        for (m in listOf("KM53", "km52", "XXXX", "KM5")) {
            val bytes = Unidad(
                snapshot = snapBase(), outbound = outBase(), magic = m.toByteArray(Charsets.US_ASCII),
            ).bytes()
            assertThrows<Km52BadMagic> { codec().deserialize(bytes) }
        }
        // Y una unidad en la que el primer byte esta bien pero el ultimo no.
        val bytes = codec().serialize(unidadDePrueba())
        val cambiado = bytes.copyOf()
        cambiado[3] = '3'.code.toByte()
        assertThrows<Km52BadMagic> { codec().deserialize(cambiado) }
    }

    @Test
    @DisplayName("KM52-07 el checksum SHA-256 cubre todo menos los ultimos 32 B")
    fun `KM52-07 checksum`() {
        val bytes = codec().serialize(unidadDePrueba())
        val esperado = hash.sha256(bytes.copyOf(bytes.size - 32))
        assertContentEquals(esperado, bytes.copyOfRange(bytes.size - 32, bytes.size), "checksum correcto al escribir")

        // Y que el checksum va SIN clave: una unidad montada aqui —sin ninguna
        // clave, sin ningun secreto, solo con SHA-256— valida contra el mismo
        // hash que el codec calcula. Si el formato usara HMAC, este paso no
        // podria existir.
        val aMano = Unidad(snapshot = snapBase(), outbound = outBase()).bytes()
        assertContentEquals(
            hash.sha256(aMano.copyOf(aMano.size - Km52Spec.CHECKSUM_LENGTH)),
            aMano.copyOfRange(aMano.size - Km52Spec.CHECKSUM_LENGTH, aMano.size),
            "el checksum de una unidad montada a mano es SHA-256 a secas",
        )
        assertEquals(
            20, assertNotNull(codec().deserialize(aMano).outbound, "esa unidad lleva su envio").ciphertext.size,
            "y esa unidad sin clave es valida",
        )

        // Un byte del payload cambiado lo invalida.
        val tocada = bytes.copyOf()
        val medio = Km52Spec.HEADER_LENGTH + 5
        tocada[medio] = (tocada[medio] + 1).toByte()
        assertThrows<Km52ChecksumMismatch> { codec().deserialize(tocada) }

        // Y un byte del HEADER tambien, pero con un error DISTINTO y por un
        // motivo que importa: la cabecera se valida ANTES que el cuerpo, asi que
        // un `totalLen` manipulado se rechaza por incoherencia y no porque el
        // hash no cuadre. Un `Km52ChecksumMismatch` aqui seria un SINTOMA: el
        // mismo fallo de integridad, contado dos veces.
        val tocada2 = bytes.copyOf()
        tocada2[20] = (tocada2[20] + 1).toByte()
        val e2 = assertThrows<Km52FormatException> { codec().deserialize(tocada2) }
        assertTrue(
            e2.message!!.contains("totalLen"),
            "la cabecera se rechaza por su propia incoherencia, no por el checksum: $e2",
        )
    }

    // ===================================================================
    // KILOMETRO 8 — Campos imposibles
    // ===================================================================

    @Test
    @DisplayName("KM52-08 los campos imposibles se rechazan uno a uno")
    fun `KM52-08 campos imposibles`() {
        val rechazada = mutableListOf<String>()
        fun rechaza(nombre: String, u: Unidad) {
            try {
                codec().deserialize(u.bytes())
                rechazada += nombre
            } catch (e: Km52FormatException) {
                assertTrue(e.message!!.isNotBlank(), "'$nombre' tiene que decir por que")
            }
        }
        val base = { Unidad(snapshot = snapBase(), outbound = outBase()) }

        rechaza("flags a distinto de cero", base().copy(flags = 1u))
        rechaza("reserved a distinto de cero", base().copy(reserved16 = 1u))
        // `reserved2` son los offsets 28..31: CUATRO bytes. Poner ocho a uno
        // llenaba en `v1` los 24..31; en `v2` los cuatro primeros son la
        // longitud del bloque de entrada, y ocho unos ahi se rechazarian por
        // incoherencia de `totalLen`, no por el reservado. Un caso que pasara
        // por el motivo equivocado es un caso que ya no prueba lo que dice, asi
        // que el ancho se mide donde el campo esta de verdad.
        rechaza("reserved2 a distinto de cero", base().copy(reserved2 = ByteArray(4) { 1 }))
        // Y el campo NUEVO, que es un vector de corrupcion que en v1 no existia:
        // una longitud de bloque que no se puede descomponer en `count(4)` mas
        // entradas de 48 mas un wire-frame. Siete bytes no son ni un `count` ni
        // un bloque con una entrada.
        rechaza(
            "pendingInboundLen indecomponible",
            base().copy(pendingInboundLenOverride = 7L),
        )
        // Y un `count` que no cuadra con las entradas escritas: el bloque mide lo
        // que se escribio, asi que el lector se queda sin entrada antes de
        // terminar. Sin este caso, un ensamblador que escribiera el `count`
        // equivocado —o un lector que no lo contrastara— no dejaria rastro.
        val entrante = wireDeEntrada(Wire(), "e0")
        rechaza(
            "count del bloque PENDING_INBOUND que no cuadra con las entradas",
            Unidad(
                snapshot = snapBase(), outbound = outBase(),
                pendingInbound = listOf(inbDe(entrante)),
                pendingCountOverride = 2L,
            ),
        )
        rechaza(
            "Ns distinto del de la cadena de envio",
            base().copy(snapshot = Snap(ns = 5u, sendChain = SendRec(sendMessageNumber = 4u))),
        )
        rechaza(
            "dos cadenas con el mismo chainId",
            base().copy(snapshot = Snap(receiveChains = listOf(RecvRec(ByteArray(32) { 1 }), RecvRec(ByteArray(32) { 1 })))),
        )
        rechaza(
            "dhRemotePresent que no es 0 ni 1",
            base().copy(snapshot = Snap(dhRemotePresent = 2)),
        )
        rechaza(
            "dhRemote declarado pero de 31 bytes",
            base().copy(snapshot = Snap(dhRemotePresent = 1, dhRemote = ByteArray(31))),
        )
        rechaza(
            "dhRemote ausente con la bandera puesta",
            base().copy(snapshot = Snap(dhRemotePresent = 1, dhRemote = ByteArray(32), receiveChains = emptyList())),
        )
        rechaza(
            "clave retenida con N >= Nr",
            base().copy(snapshot = Snap(receiveChains = listOf(
                RecvRec(
                    ByteArray(32) { 2 }, receiveMessageNumber = 1u,
                    skipped = listOf(Triple(ByteArray(32) { 2 }, 1u, ByteArray(32))),
                ),
            ))),
        )
        rechaza(
            "dos claves retenidas con el mismo (chainId, N)",
            base().copy(snapshot = Snap(receiveChains = listOf(
                RecvRec(
                    ByteArray(32) { 2 }, receiveMessageNumber = 5u,
                    skipped = listOf(
                        Triple(ByteArray(32) { 2 }, 0u, ByteArray(32) { 1 }),
                        Triple(ByteArray(32) { 2 }, 0u, ByteArray(32) { 2 }),
                    ),
                ),
            ))),
        )
        rechaza(
            "frameIdentityLen != 40",
            base().copy(outbound = OutRec(frameIdentityLen = 39u)),
        )
        rechaza("deliveryState 0", base().copy(outbound = OutRec(deliveryState = 0u)))
        rechaza("deliveryState 5", base().copy(outbound = OutRec(deliveryState = 5u)))
        rechaza("recVersion distinta de 1", base().copy(outbound = OutRec(recordVersion = 2u)))
        rechaza("reserved de OUTBOUND a distinto de cero", base().copy(outbound = OutRec(reserved16 = 2u)))
        rechaza("el bloque consume mas de lo que declara", base().copy(snapshotLenOverride = 40L))
        rechaza("el bloque consume menos de lo que declara", base().copy(snapshotLenOverride = 4000L))
        rechaza("outboundLen que no cuadra", base().copy(outboundLenOverride = 11L))
        rechaza("ciphertextLen que no cuadra", base().copy(ciphertextLenOverride = 11L))
        rechaza("totalLen que no cuadra", base().copy(totalLenOverride = 77L))

        assertEquals(emptyList<String>(), rechazada, "ningun campo imposible puede pasar: $rechazada")
    }

    // ===================================================================
    // KILOMETRO 9 — Material que no pertenece a la sesion
    // ===================================================================

    @Test
    @DisplayName("KM52-09 se rechaza el material que no puede servir a ESTA sesion")
    fun `KM52-09 material ajeno`() {
        val e = assertThrows<ForeignSessionMaterial> {
            codec().deserialize(Unidad(
                snapshot = Snap(dhRemotePresent = 1, dhRemote = ByteArray(32)),   // de orden pequeno
                outbound = outBase(),
            ).bytes())
        }
        assertTrue(e.message!!.contains("orden pequeno"), "el rechazo tiene que decir POR QUE: $e")

        // Una DH remota de 32 bytes pero criptograficamente inutilizable tambien.
        val pMenos1 = ByteArray(32) { 0xFF.toByte() }
        pMenos1[0] = 0xEC.toByte()
        assertThrows<ForeignSessionMaterial> {
            codec().deserialize(Unidad(
                snapshot = Snap(dhRemotePresent = 1, dhRemote = pMenos1), outbound = outBase(),
            ).bytes())
        }

        // Una clave retenida archivada bajo una cadena que NO es la suya: el
        // ratchet solo la encontrara si el chainId coincide.
        val ajena = ByteArray(32) { 0x77 }
        assertThrows<ForeignSessionMaterial> {
            codec().deserialize(Unidad(
                snapshot = Snap(receiveChains = listOf(
                    RecvRec(
                        ByteArray(32) { 5 }, receiveMessageNumber = 9u,
                        skipped = listOf(Triple(ajena, 0u, ByteArray(32) { 1 })),
                        foreignSkippedChainId = ajena,
                    ),
                )),
                outbound = outBase(),
            ).bytes())
        }

        // Y una DH remota USABLE pasa: el guard es una lista de rechazo, no
        // una lista blanca.
        val buena = x25519.generateKeyPair().publicKey
        val leida = codec().deserialize(Unidad(
            snapshot = Snap(dhRemotePresent = 1, dhRemote = buena), outbound = outBase(),
        ).bytes())
        assertContentEquals(buena, leida.snapshot.dhRemote!!, "una DH remota utilizable se restaura")
    }

    // ===================================================================
    // KILOMETRO 10 — LA PRUEBA MAS FUERTE DEL CHECKPOINT
    //
    //   unidad estructuralmente valida
    //        -> supera el BUDGET (200 KiB = 204800 B)
    //        -> RetentionBudgetExceeded
    //        -> estado de la sesion EXACTAMENTE intacto
    //
    // Y la comprobacion anti-poda NO es "se lanzo la excepcion": es que la
    // clave retenida que una implementacion que podara habria descartado
    // SEGUIRA descifrando el frame que la necesita.
    // ===================================================================

    private fun chainIdDe(c: Int) = ByteArray(32) { ((it + c) and 0xFF).toByte() }

    /** Snapshot sintetico de 8 cadenas con [porCadena] claves retenidas cada una. */
    private fun snapshotSintetico(porCadena: Int): DoubleRatchetSnapshot = DoubleRatchetSnapshot(
        rootKey = ByteArray(32) { 0x41 },
        dhSelf = DerivedX25519KeyPair.derive(det(7).privateKey, x25519),
        dhRemote = null,
        sendMessageNumber = 0u,
        previousChainLength = 0u,
        sendChain = SymmetricRatchetSnapshot(
            sendChainKey = ByteArray(32) { 0x11 },
            sendMessageNumber = 0u,
            receiveChainKey = ByteArray(32) { 0x11 },
            receiveMessageNumber = 0u,
            skipped = emptyMap(),
        ),
        receiveChains = (0 until 8).map { c ->
            val id = chainIdDe(c)
            ReceiveChainSnapshot(
                id,
                SymmetricRatchetSnapshot(
                    sendChainKey = ByteArray(32) { (it + c).toByte() },
                    sendMessageNumber = 0u,
                    receiveChainKey = ByteArray(32) { (it + c + 40).toByte() },
                    receiveMessageNumber = porCadena.toUInt(),
                    skipped = (0 until porCadena).associate { n ->
                        Pair(ChainIdentifier(id), n.toUInt()) to ByteArray(32) { ((n + c) and 0xFF).toByte() }
                    },
                ),
            )
        },
    )

    private fun retencionDe8() = ReceiveChainRetention(
        (0 until 8).map { ChainUseOrdinal(chainIdDe(it), it.toULong()) },
    )

    @Test
    @DisplayName("KM52-10 una unidad sobredimensionada se rechaza ENTERA y no se poda nada")
    fun `KM52-10 sobredimensionada rechazo total sin poda`() {
        // 1. UNIDAD ESTRUCTURALMENTE VALIDA Y SOBREDIMENSIONADA.
        //    8 cadenas x 377 claves = 3016 x 68 B = 205 088 B > 204 800 B.
        //    377 <= 1000 y 8 <= 8: lo UNICO que se pasa es el presupuesto global.
        val porCadena = 377
        val retencion = retencionDe8()
        // Se arma A MANO, y no con `serialize`, por una razon que es el alma de
        // este test: el serializador NUNCA escribe una unidad sobredimensionada
        // (la poda), asi que el byte-string de este escenario no se puede
        // obtener del camino de escritura. Es exactamente el caso de §6.9.2 al
        // leer: una unidad en disco que no cumple el contrato.
        val bytes = Unidad(
            snapshot = Snap(
                dhSelfScalar = det(7).privateKey,
                receiveChains = (0 until 8).map { c ->
                    RecvRec(
                        chainId = chainIdDe(c),
                        lastUseOrdinal = c.toULong(),
                        receiveMessageNumber = porCadena.toUInt(),
                        skipped = (0 until porCadena).map { n ->
                            Triple(chainIdDe(c), n.toUInt(), ByteArray(32) { ((n + c) and 0xFF).toByte() })
                        },
                    )
                },
            ),
            outbound = outBase(),
        ).bytes()
        assertTrue(
            8 * porCadena * Km52RetentionLimits.SKIPPED_ENTRY_LENGTH > Km52RetentionLimits.MAX_TOTAL_RETAINED_BUDGET,
            "el guion tiene que pasar de verdad el presupuesto",
        )
        val espia = EspiaX25519(x25519)
        val c = BinaryKm52UnitCodec(espia, hash)

        // 2. LA SESION VIVA, ANTES DEL INTENTO. BOB con dos cadenas de recepcion
        //    y dos frames de la epoca VIEJA por descifrar desde retenidas.
        val w = Wire()
        val (_, a1, _, _, _) = dosEpochs(w)
        val bob = w.bobSession
        assertEquals(2, bob.receiveChainCount(), "el guion debe dejar dos cadenas")
        assertEquals(3u, bob.currentPreviousChainLength(), "y PN = 3")
        val retenidasAntes = bob.snapshot().receiveChains
            .map { it.ratchet.skipped.size }
        assertTrue(retenidasAntes.any { it > 0 }, "el guion debe dejar claves retenidas")

        // El victimario DETERMINISTA que una implementacion que podara
        // descartaria: la cadena con el `lastUseOrdinal` mas bajo y, dentro de
        // ella, la N mas baja (§6.9.1). Se deja escrito porque es la clave que
        // este test comprueba que NO se pierde.
        val victima = chainIdDe(0) to 0u
        val porOrdinal = (0 until 8).minWithOrNull(compareBy({ it.toULong() }, { hex(chainIdDe(it)) }))!!
        assertEquals(
            hex(chainIdDe(0)), hex(chainIdDe(porOrdinal)),
            "la victima es la cadena de ordinal 0: el guion lo deja inequivoco",
        )

        // 3. EL RECHAZO, CON LA RESTAURACION DENTRO DEL MISMO INTENTO: una
        //    implementacion que "lee, poda y restaura lo restante" LLEGA aqui.
        val e = assertThrows<RetentionBudgetExceeded> {
            bob.restore(c.deserialize(bytes).snapshot)
        }
        assertEquals(RetentionRule.TOTAL_BYTES, e.rule, "el motivo tiene que ser el presupuesto global")
        assertEquals(8L * porCadena * 68L, e.actual, "cuantas claves retenidas traia")
        assertEquals(Km52RetentionLimits.MAX_TOTAL_RETAINED_BUDGET.toLong(), e.limit, "el techo")

        // 4. LA SESION EXACTAMENTE INTACTA. Con COMPORTAMIENTO, no con una
        //    huella: el siguiente frame tiene que salir byte a byte igual.
        assertEquals(2, bob.receiveChainCount(), "ninguna cadena puede aparecer o desaparecer")
        assertEquals(3u, bob.currentPreviousChainLength(), "PN intacto")
        assertEquals(
            retenidasAntes,
            bob.snapshot().receiveChains.map { it.ratchet.skipped.size },
            "ninguna clave retenida puede haber desaparecido: ESO es no podar",
        )
        val control = SecureRatchetProtocol(
            DoubleRatchetSession.restore(bob.snapshot(), x25519, kdf), protector,
        )
        assertContentEquals(
            control.encrypt("ctrl".toByteArray()),
            SecureRatchetProtocol(bob, protector).encrypt("ctrl".toByteArray()),
            "la sesion no se ha movido un byte",
        )

        // 4b. LA PRUEBA ANTI-PODA. El frame de la epoca vieja se descifra desde
        //     una clave RETENIDA. Si el restaurador hubiera podado, esa clave
        //     no estaria y el frame caeria en REPLAY_OR_UNKNOWN; y la foto
        //     podada seria de OTRA sesion, con otras claves.
        val r = w.bob.decrypt(a1)
        assertTrue(r is SecureRatchetProtocol.DecryptResult.Ok, "el frame retenido tiene que seguir entrando: $r")
        assertTrue(
            (r as SecureRatchetProtocol.DecryptResult.Ok).fromSkipped,
            "y desde la clave retenida, no derivado de la cadena",
        )

        // 5. DETERMINISMO: el mismo byte-string falla igual, siempre.
        repeat(3) { i ->
            val otra = assertThrows<RetentionBudgetExceeded> { c.deserialize(bytes) }
            assertEquals(e.rule, otra.rule, "intento $i")
            assertEquals(e.actual, otra.actual, "intento $i")
        }

        // 6. Y NO ES "ESTA UNIDAD ESTA ROTA": la MISMA forma de unidad, un byte
        //    por debajo del techo, si se lee — y con TODAS sus claves.
        //    8 x 300 = 2400 x 68 = 163 200 B.
        val cabe = Unidad(
            snapshot = Snap(
                dhSelfScalar = det(7).privateKey,
                receiveChains = (0 until 8).map { c ->
                    RecvRec(
                        chainId = chainIdDe(c),
                        lastUseOrdinal = c.toULong(),
                        receiveMessageNumber = 300u,
                        skipped = (0 until 300).map { n ->
                            Triple(chainIdDe(c), n.toUInt(), ByteArray(32) { ((n + c) and 0xFF).toByte() })
                        },
                    )
                },
            ),
            outbound = outBase(),
        ).bytes()
        val leida = c.deserialize(cabe)
        assertEquals(8, leida.snapshot.receiveChains.size, "por debajo del presupuesto entran las ocho")
        assertEquals(
            2400, leida.snapshot.receiveChains.sumOf { it.ratchet.skipped.size },
            "y con TODAS sus claves: al leer no se ha podado ni una",
        )
        // Y la contraprueba de la cota: UNA clave mas y vuelve a rechazarse.
        val justo = Unidad(
            snapshot = Snap(
                dhSelfScalar = det(7).privateKey,
                receiveChains = (0 until 8).map { c ->
                    RecvRec(
                        chainId = chainIdDe(c),
                        lastUseOrdinal = c.toULong(),
                        receiveMessageNumber = 377u,
                        skipped = (0 until 377).map { n ->
                            Triple(chainIdDe(c), n.toUInt(), ByteArray(32) { ((n + c) and 0xFF).toByte() })
                        },
                    )
                },
            ),
            outbound = outBase(),
        ).bytes()
        val unoMas = assertThrows<RetentionBudgetExceeded> { c.deserialize(justo) }
        assertEquals(3016L * 68L, unoMas.actual, "3016 claves, 16 mas que el techo: también se rechaza")
    }

    // ===================================================================
    // KILOMETRO 11-12 — Las otras dos cotas
    // ===================================================================

    @Test
    @DisplayName("KM52-11 mas de 1000 claves retenidas en una cadena se rechaza; 1000 entra")
    fun `KM52-11 cota por cadena`() {
        fun unidad(cadena: Int) = Unidad(
            snapshot = Snap(receiveChains = listOf(
                RecvRec(
                    chainIdDe(1), lastUseOrdinal = 1uL, receiveMessageNumber = cadena.toUInt(),
                    skipped = (0 until cadena).map {
                        Triple(chainIdDe(1), it.toUInt(), ByteArray(32) { it.toByte() })
                    },
                ),
            )),
            outbound = outBase(),
        )
        val mil = codec().deserialize(unidad(1000).bytes())
        assertEquals(1000, mil.snapshot.receiveChains.single().ratchet.skipped.size, "1000 es el tope y entra")

        val e = assertThrows<RetentionBudgetExceeded> { codec().deserialize(unidad(1001).bytes()) }
        assertEquals(RetentionRule.PER_CHAIN, e.rule, "el motivo es la cota por cadena")
        assertEquals(1001L, e.actual)
        assertEquals(Km52RetentionLimits.MAX_RETAINED_PER_CHAIN.toLong(), e.limit)
    }

    @Test
    @DisplayName("KM52-12 mas de 8 cadenas de recepcion se rechaza; 8 entra")
    fun `KM52-12 cota de cadenas`() {
        fun unidad(cadenas: Int) = Unidad(
            snapshot = Snap(receiveChains = (0 until cadenas).map {
                RecvRec(chainIdDe(it), lastUseOrdinal = it.toULong())
            }),
            outbound = outBase(),
        )
        assertEquals(8, codec().deserialize(unidad(8).bytes()).snapshot.receiveChains.size, "8 es el tope y entra")
        val e = assertThrows<RetentionBudgetExceeded> { codec().deserialize(unidad(9).bytes()) }
        assertEquals(RetentionRule.RECEIVE_CHAINS, e.rule, "el motivo es el numero de cadenas")
        assertEquals(9L, e.actual)
        assertEquals(Km52RetentionLimits.MAX_RECEIVE_CHAINS.toLong(), e.limit)
    }

    // ===================================================================
    // KILOMETRO 13 — Ningun estado parcialmente restaurado
    // ===================================================================

    @Test
    @DisplayName("KM52-13 ninguna unidad corrupta deja a medias la restauracion")
    fun `KM52-13 nada a medias`() {
        val w = Wire()
        dosEpochs(w)
        val bob = w.bobSession
        val foto = bob.snapshot()
        val sana = codec().serialize(Km52Unit(foto, retencionDe(foto), registro(bob.selfDhPublicKey())))

        val variantes = listOf<Pair<String, ByteArray>>(
            "magic" to sana.copyOf().also { it[0] = 'X'.code.toByte() },
            "version" to sana.copyOf().also { it[4] = 9 },
            "flags" to sana.copyOf().also { it[5] = 1 },
            "reserved" to sana.copyOf().also { it[7] = 1 },
            "snapshotLen" to sana.copyOf().also { it[8] = (it[8] + 1).toByte() },
            "outboundLen" to sana.copyOf().also { it[13] = (it[13] + 1).toByte() },
            "ciphertextLen" to sana.copyOf().also { it[16] = (it[16] + 1).toByte() },
            "totalLen" to sana.copyOf().also { it[21] = (it[21] + 1).toByte() },
            // `reserved2` son los offsets 28..31 en v2. El offset 24 —que en v1
            // era parte del reservado— es la longitud del bloque de entrada, y
            // por eso tiene su propio caso abajo. Tocar 24 aqui comprobaba el
            // relleno equivocado, que en v2 ya no existe.
            "reserved2" to sana.copyOf().also { it[28] = 1 },
            // El campo nuevo del header, manipulado. Es un vector de corrupcion
            // que en v1 no existia, asi que sin este caso la migracion dejaria
            // el header de v2 con un agujero: la cabecera validaria todo menos
            // el campo que el propio bump introdujo.
            "pendingInboundLen" to sana.copyOf().also { it[24] = (it[24] + 1).toByte() },
            "payload" to sana.copyOf().also { it[Km52Spec.HEADER_LENGTH + 40] = (it[Km52Spec.HEADER_LENGTH + 40] + 1).toByte() },
            "checksum" to sana.copyOf().also { it[sana.size - 1] = (it[sana.size - 1] + 1).toByte() },
            "truncada" to sana.copyOf(sana.size - 3),
            "sobrante" to sana + byteArrayOf(7),
        )
        assertEquals(14, variantes.size, "el guion tiene que cubrir catorce formas de corromper")

        for ((nombre, bytes) in variantes) {
            // Se mide el estado con COMPORTAMIENTO: la referencia sale de una
            // sesion construida desde la MISMA foto, y por eso es comparable
            // byte a byte (la sesion viva genera DH aleatorias; la restaurada,
            // no).
            fun frameDeReferencia(): ByteArray = SecureRatchetProtocol(
                DoubleRatchetSession.restore(foto, x25519, kdf), protector,
            ).encrypt("ctrl".toByteArray())

            var fallo: Throwable? = null
            try {
                bob.restore(codec().deserialize(bytes).snapshot)
            } catch (e: Throwable) {
                fallo = e
            }
            assertNotNull(fallo, "'$nombre' tiene que rechazarse y no restaurar nada")
            assertTrue(
                fallo is Km52FormatException || fallo is RetentionBudgetExceeded,
                "'$nombre' tiene que fallar con un error tipado del codec, fue $fallo",
            )
            assertEquals(2, bob.receiveChainCount(), "'$nombre' no puede haber tocado las cadenas")
            assertEquals(3u, bob.currentPreviousChainLength(), "'$nombre' no puede haber movido PN")
            assertContentEquals(
                frameDeReferencia(),
                SecureRatchetProtocol(bob, protector).encrypt("ctrl".toByteArray()),
                "'$nombre' no puede haber movido el estado: el frame tiene que salir igual",
            )
            // Y la sesion sigue admitiendo un frame legitimo.
            assertTrue(
                w.bob.decrypt(w.alice.encrypt("a9".toByteArray())) is SecureRatchetProtocol.DecryptResult.Ok,
                "'$nombre' no puede haber roto la sesion",
            )
            // La propia asercion consume la cadena de envio: se vuelve al punto
            // de partida para que la siguiente variante parta del mismo estado.
            bob.restore(foto)
        }
    }

    // ===================================================================
    // KILOMETRO 14-15 — Ordinales y pureza del portador
    // ===================================================================

    @Test
    @DisplayName("KM52-14 createdOrdinal y lastUseOrdinal viajan y vuelven")
    fun `KM52-14 ordinales`() {
        val w = Wire()
        dosEpochs(w)
        val foto = w.bobSession.snapshot()
        val cadena = foto.receiveChains.first().chainId

        for (created in listOf(0uL, 1uL, 1uL shl 40, ULong.MAX_VALUE)) {
            for (ordinal in listOf(0uL, 3uL, 1uL shl 63, ULong.MAX_VALUE)) {
                val u = Km52Unit(
                    snapshot = foto,
                    retention = ReceiveChainRetention(foto.receiveChains.map {
                        ChainUseOrdinal(it.chainId, if (it.chainId.contentEquals(cadena)) ordinal else 1uL)
                    }),
                    outbound = registro(w.bobSession.selfDhPublicKey(), created = created),
                )
                val leida = codec().deserialize(codec().serialize(u))
                assertEquals(
                    created, assertNotNull(leida.outbound, "el outbound vuelve con su ordinal").createdOrdinal,
                    "createdOrdinal $created",
                )
                assertEquals(ordinal, leida.retention.lastUseOrdinal(cadena), "lastUseOrdinal $ordinal")
            }
        }
    }

    @Test
    @DisplayName("KM52-15 la tabla de retencion tiene que cubrir exactamente las cadenas del snapshot")
    fun `KM52-15 tabla de retencion completa`() {
        val w = Wire()
        dosEpochs(w)
        val foto = w.bobSession.snapshot()
        val registros = foto.receiveChains.map { ChainUseOrdinal(it.chainId, 1uL) }
        val rec = registro(w.bobSession.selfDhPublicKey())

        // Falta una.
        val falta = assertThrows<Km52FormatException> {
            codec().serialize(Km52Unit(foto, ReceiveChainRetention(registros.drop(1)), rec))
        }
        assertTrue(
            falta.message!!.contains("retencion", ignoreCase = true) || falta.message!!.contains("ordinal", ignoreCase = true),
            "el rechazo tiene que senalar la tabla de retencion: $falta",
        )
        // Sobra una.
        assertThrows<Km52FormatException> {
            codec().serialize(
                Km52Unit(foto, ReceiveChainRetention(registros + ChainUseOrdinal(ByteArray(32) { 0xAB.toByte() }, 9uL)), rec),
            )
        }
        // Completa: entra.
        codec().serialize(Km52Unit(foto, ReceiveChainRetention(registros), rec))
    }

    @Test
    @DisplayName("KM52-16 OutboundRecord es un portador de datos puro, sin comportamiento")
    fun `KM52-16 outbound record puro`() {
        // El codec necesita poder serializarlo, asi que existe como estructura.
        // Lo que NO puede es tener reglas propias: si las tuviera, la
        // validacion viviria en dos sitios y podrian discrepar.
        val fuente = File("src/main/kotlin/com/keymessage/core/km52/Km52Types.kt")
        assertTrue(fuente.exists(), "falta ${fuente.path} desde ${File(".").absolutePath}")
        val cuerpo = fuente.readText()
            .substringAfter("class OutboundRecord(")
            .substringBefore("\n}")
        assertEquals(
            emptyList<String>(), Regex("""\bfun\s+\w+""").findAll(cuerpo).map { it.value }.toList(),
            "OutboundRecord no declara metodos: es un portador de datos",
        )
        assertEquals(0, Regex("""\brequire\s*\(""").findAll(cuerpo).count(), "tampoco valida por su cuenta")
    }

    // ===================================================================
    // KILOMETRO 17 — Poda al ESCRIBIR, determinista
    // ===================================================================

    private class Victima(val chainId: ByteArray, val n: UInt, val ordinal: ULong)

    @Test
    @DisplayName("KM52-17 al escribir se poda de forma DETERMINISTA hasta entrar en el presupuesto")
    fun `KM52-17 poda determinista al escribir`() {
        val porCadena = 377
        val foto = snapshotSintetico(porCadena)
        val retencion = retencionDe8()
        val entrada = Km52Unit(foto, retencion, registro(ByteArray(32) { 0x63 }))

        val c = codec()
        val uno = c.serialize(entrada)
        val dos = c.serialize(entrada)
        assertContentEquals(uno, dos, "INV-07: el mismo estado produce SIEMPRE los mismos bytes")

        val leida = c.deserialize(uno)
        assertEquals(
            3011, leida.snapshot.receiveChains.sumOf { it.ratchet.skipped.size },
            "3011 x 68 = 204 748 <= 204 800: el techo, ni una clave mas",
        )

        // El conjunto de victimas es EXACTAMENTE el que dice §6.9.1: primero
        // por cadena (lastUseOrdinal ASC), luego por N ASC, y el chainId como
        // desempate.
        val todas = foto.receiveChains.flatMap { cadena ->
            val ord = retencion.lastUseOrdinal(cadena.chainId)!!
            cadena.ratchet.skipped.keys.map { Victima(cadena.chainId, it.second, ord) }
        }
        val victimas = todas
            .sortedWith(compareBy({ it.ordinal }, { it.n }, { hex(it.chainId) }))
            .take(todas.size - 3011)
            .map { hex(it.chainId) to it.n }
            .toSet()
        val supervivientes = leida.snapshot.receiveChains.flatMap { c2 ->
            c2.ratchet.skipped.keys.map { hex(c2.chainId) to it.second }
        }.toSet()
        assertEquals(5, victimas.size, "3016 - 3011")
        assertEquals(
            todas.size - victimas.size, supervivientes.size,
            "las que se van son exactamente las victimas y solo ellas",
        )
        for (v in todas) {
            val id = hex(v.chainId)
            val enSalida = leida.snapshot.receiveChains.first { hex(it.chainId) == id }
            val antes = foto.receiveChains.first { hex(it.chainId) == id }
                .ratchet.skipped[ChainIdentifier(v.chainId) to v.n]!!
            val despues = enSalida.ratchet.skipped[ChainIdentifier(v.chainId) to v.n]
            if (victimas.contains(id to v.n)) {
                assertTrue(despues == null, "la victima se va: $id N=${v.n}")
            } else {
                assertContentEquals(antes, despues, "el superviviente intacto: $id N=${v.n}")
            }
        }
    }

    @Test
    @DisplayName("KM52-18 al escribir, una entrada fuera de las cotas se RECHAZA, no se poda")
    fun `KM52-18 no se escribe una unidad fuera de las cotas`() {
        // 9 cadenas. El tope de 8 no se puede arreglar podando claves: PODAR es
        // descartar material retenido, y descartar una CADENA entera es perder
        // estado no-retenido, que §6.9.1 prohibe ("NO se pierde: todo el
        // estado"). Asi que la unica salida es el rechazo.
        val nueve = snapshotSintetico(1)
        val fotoNueve = DoubleRatchetSnapshot(
            rootKey = nueve.rootKey,
            dhSelf = DerivedX25519KeyPair.derive(det(7).privateKey, x25519),
            dhRemote = null,
            sendMessageNumber = 0u,
            previousChainLength = 0u,
            sendChain = nueve.sendChain,
            receiveChains = nueve.receiveChains + ReceiveChainSnapshot(
                chainIdDe(0xEE),
                SymmetricRatchetSnapshot(
                    sendChainKey = ByteArray(32) { 9 },
                    sendMessageNumber = 0u,
                    receiveChainKey = ByteArray(32) { 9 },
                    receiveMessageNumber = 1u,
                    skipped = mapOf(Pair(ChainIdentifier(chainIdDe(0xEE)), 0u) to ByteArray(32) { 3 }),
                ),
            ),
        )
        val e = assertThrows<RetentionBudgetExceeded> {
            codec().serialize(
                Km52Unit(
                    fotoNueve,
                    ReceiveChainRetention(
                        fotoNueve.receiveChains
                            .mapIndexed { i, c -> ChainUseOrdinal(c.chainId, i.toULong()) }
                            .sortedBy { hex(it.chainId) },
                    ),
                    registro(ByteArray(32) { 0x63 }),
                ),
            )
        }
        assertEquals(RetentionRule.RECEIVE_CHAINS, e.rule, "no se escribe una unidad fuera de las cotas")
        assertEquals(9L, e.actual)
    }

    @Test
    @DisplayName("KM52-19 el codec comprueba que la cadena de envio NO ha recibido nunca")
    fun `KM52-19 mitad de recepcion de la cadena de envio`() {
        // La cadena de envio de la sesion NUNCA recibe: `commitReceive` solo se
        // llama sobre `receiveChains`. Su mitad de recepcion es, por tanto, un
        // reflejo de la de envio, y el layout la DERIVA en vez de guardarla
        // (42 B en vez de 80 B). Si alguna vez dejara de serlo, escribir la
        // unidad perderia estado en silencio: por eso se comprueba ANTES.
        val idCadena = ByteArray(32) { 0x42 }
        fun conMitadDeRecepcion(
            nr: UInt,
            retenidas: Map<Pair<ChainIdentifier, UInt>, ByteArray>,
        ) = DoubleRatchetSnapshot(
            rootKey = ByteArray(32) { 0x41 },
            dhSelf = DerivedX25519KeyPair.derive(det(7).privateKey, x25519),
            dhRemote = null,
            sendMessageNumber = 0u,
            previousChainLength = 0u,
            sendChain = SymmetricRatchetSnapshot(
                sendChainKey = ByteArray(32) { 0x11 },
                sendMessageNumber = 0u,
                receiveChainKey = ByteArray(32) { 0x11 },
                receiveMessageNumber = nr,
                skipped = retenidas,
            ),
            receiveChains = emptyList(),
        )

        // Una cadena de envio con indice de recepcion: imposible, porque
        // `commitReceive` solo se llama sobre `receiveChains`.
        val e = assertThrows<Km52FormatException> {
            codec().serialize(
                Km52Unit(conMitadDeRecepcion(4u, emptyMap()), ReceiveChainRetention(emptyList()), registro(ByteArray(32) { 1 })),
            )
        }
        assertTrue(
            e.message!!.contains("cadena de envio", ignoreCase = true),
            "el rechazo tiene que senalar la cadena de envio: $e",
        )
        // Y una con claves retenidas: material que el ratchet no podria volver
        // a encontrar y que la ordenacion de victimizacion no sabe ordenar.
        val e2 = assertThrows<Km52FormatException> {
            codec().serialize(
                Km52Unit(
                    conMitadDeRecepcion(0u, mapOf(Pair(ChainIdentifier(idCadena), 0u) to ByteArray(32))),
                    ReceiveChainRetention(emptyList()),
                    registro(ByteArray(32) { 1 }),
                ),
            )
        }
        assertTrue(
            e2.message!!.contains("cadena de envio", ignoreCase = true),
            "el rechazo tiene que senalar la cadena de envio: $e2",
        )
        // Y una sesion REAL cumple lo que si es invariante: la cadena de envio
        // no ha recibido nunca. Lo que NO se comprueba es que su
        // `receiveChainKey` coincida con la de envio, y el motivo es que en una
        // sesion viva dejan de coincidir en cuanto envia: `commitSend` avanza
        // `sendChainKey` y deja la otra atras.
        val w = Wire()
        dosEpochs(w)
        val foto = w.bobSession.snapshot()
        assertEquals(0u, foto.sendChain.receiveMessageNumber, "una cadena de envio nunca ha recibido")
        assertTrue(foto.sendChain.skipped.isEmpty(), "y no retiene claves")
        // Y aun asi se serializa: la sesion de este guion ya ha enviado, asi que
        // sus dos mitades de envio NO coinciden y el formato tiene que soportarlo.
        exchange(w.bob, w.alice, "b9")
        val fotoEnviada = w.bobSession.snapshot()
        assertFalse(
            fotoEnviada.sendChain.sendChainKey.contentEquals(fotoEnviada.sendChain.receiveChainKey),
            "tras enviar, las dos mitades de la cadena de envio difieren: por eso no se comprueban",
        )
        codec().serialize(Km52Unit(fotoEnviada, retencionDe(fotoEnviada), registro(w.bobSession.selfDhPublicKey())))
    }

    @Test
    @DisplayName("KM52-19b perder el receiveChainKey de la cadena de envio es INERTE: los frames son los mismos")
    fun `KM52-19b la mitad de recepcion de la cadena de envio no se usa`() {
        // La justificacion de que §3.1 pueda NO guardar el `receiveChainKey` de
        // la cadena de envio, probada por COMPORTAMIENTO y no por argumento.
        //
        // Dos sesiones que solo se diferencian en ese valor tienen que emitir
        // el MISMO frame byte a byte. Si salieran distinto, la perdida del
        // layout seria real y habria que guardar el campo.
        val w = Wire()
        dosEpochs(w)
        val foto = w.bobSession.snapshot()
        val carga = "mismo frame".toByteArray()

        val conLaMitad = foto.sendChain.receiveChainKey
        val alterada = DoubleRatchetSnapshot(
            rootKey = foto.rootKey.copyOf(),
            dhSelf = foto.dhSelf,
            dhRemote = foto.dhRemote?.copyOf(),
            sendMessageNumber = foto.sendMessageNumber,
            previousChainLength = foto.previousChainLength,
            sendChain = SymmetricRatchetSnapshot(
                sendChainKey = foto.sendChain.sendChainKey.copyOf(),
                sendMessageNumber = foto.sendChain.sendMessageNumber,
                receiveChainKey = ByteArray(32) { (it * 7 + 3).toByte() },   // OTRA, y distinta
                receiveMessageNumber = 0u,
                skipped = emptyMap(),
            ),
            receiveChains = foto.receiveChains,
        )
        assertFalse(
            conLaMitad.contentEquals(alterada.sendChain.receiveChainKey),
            "el guion tiene que cambiar de verdad ese valor",
        )
        val a = SecureRatchetProtocol(
            DoubleRatchetSession.restore(foto, x25519, kdf), protector,
        ).encrypt(carga)
        val b = SecureRatchetProtocol(
            DoubleRatchetSession.restore(alterada, x25519, kdf), protector,
        ).encrypt(carga)
        assertContentEquals(a, b, "el receiveChainKey de la cadena de envio no participa en nada")
    }

    @Test
    @DisplayName("KM52-20 el frame de la epoch VIEJA sigue descifrando tras pasar por la unidad")
    fun `KM52-20 la epoch vieja sobrevive al codec`() {
        val w = Wire()
        val (_, a1, a2, _, _) = dosEpochs(w)
        val foto = w.bobSession.snapshot()
        assertEquals(2, foto.receiveChains.size, "el guion debe dejar dos cadenas")

        val bytes = codec().serialize(Km52Unit(foto, retencionDe(foto), registro(w.bobSession.selfDhPublicKey())))
        val b2 = DoubleRatchetSession.restore(codec().deserialize(bytes).snapshot, x25519, kdf)
        val p2 = SecureRatchetProtocol(b2, protector)
        val r1 = p2.decrypt(a1)
        assertTrue(r1 is SecureRatchetProtocol.DecryptResult.Ok, "la epoch vieja descifra tras el codec: $r1")
        assertTrue((r1 as SecureRatchetProtocol.DecryptResult.Ok).fromSkipped, "desde la clave retenida")
        assertContentEquals("a1".toByteArray(), r1.plaintext)
        assertContentEquals("a2".toByteArray(), ok(p2.decrypt(a2)))
        assertEquals(2, b2.receiveChainCount(), "las dos cadenas siguen")
    }

    @Test
    @DisplayName("KM52-21 SecureFrame v1 no se toca y km-core no depende de km-webrtc")
    fun `KM52-21 fronteras`() {
        // 1. SecureFrame v1 CONGELADO: 44 bytes de header, AAD entero.
        val w = Wire()
        val wire = w.alice.encrypt("x".toByteArray())
        assertEquals(44, SecureFrameSpec.HEADER_LENGTH, "el header v1 no crece")
        assertEquals(44 + 1 + SecureFrameSpec.TAG_LENGTH, wire.size, "header + ciphertext + tag, sin campos extra")
        val frame = BinarySecureFrameCodec.decode(wire)
        assertEquals(1, frame.version.toInt())
        assertEquals(32, frame.ratchetHeader.dhPublicKey.size)

        // 2. El codec no depende de la libreria criptografica.
        val fuente = File("src/main/kotlin/com/keymessage/core/km52/Km52Codec.kt")
        assertTrue(fuente.exists(), "falta ${fuente.path}")
        assertFalse(fuente.readText().contains("org.bouncycastle"), "el codec no puede importar bouncycastle")

        // 3. km-core no declara ninguna dependencia de km-webrtc, por ninguna via.
        val build = File("build.gradle.kts")
        assertTrue(build.exists(), "no se encuentra build.gradle.kts desde ${File(".").absolutePath}")
        assertFalse(build.readText().contains("km-webrtc"), "km-core no puede depender de km-webrtc")
    }
}
