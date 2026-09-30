package com.km.unit

import com.km.crypto.DerivedX25519KeyPair
import com.km.crypto.Hash
import com.km.crypto.HashImpl
import com.km.crypto.Kdf
import com.km.crypto.X25519
import com.km.crypto.X25519KeyPair
import com.km.crypto.provider.BcChaCha20Poly1305
import com.km.crypto.provider.BcHkdfSha256
import com.km.crypto.provider.BcX25519
import com.km.messaging.FrameIdentity
import com.km.messaging.PendingInboundEntry
import com.km.messaging.PendingInbox
import com.km.model.MessageId
import com.km.ratchet.ChainIdentifier
import com.km.ratchet.DoubleRatchetSession
import com.km.ratchet.DoubleRatchetSnapshot
import com.km.ratchet.ReceiveChainSnapshot
import com.km.ratchet.SymmetricRatchetSnapshot
import com.km.frame.SecureFrameProtector
import com.km.frame.SecureRatchetProtocol
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/* ==================================================================== *
 * UTILIDADES DE BYTES A NIVEL DE ARCHIVO.
 *
 * Fuera de la clase de test: las clases anidadas que arman las unidades NO
 * ven los miembros de su clase externa, y su "unresolved reference" es
 * indistinguible del de un tipo inexistente.
 * ==================================================================== */

private enum class E2 { LITTLE, BIG }

private fun u32be(v: Long, e: E2): ByteArray {
    val out = ByteArray(4)
    if (e == E2.BIG) {
        out[0] = ((v shr 24) and 0xFF).toByte()
        out[1] = ((v shr 16) and 0xFF).toByte()
        out[2] = ((v shr 8) and 0xFF).toByte()
        out[3] = (v and 0xFF).toByte()
    } else {
        for (i in 0 until 4) out[i] = ((v shr (8 * i)) and 0xFF).toByte()
    }
    return out
}

private fun u64le(v: ULong, e: E2): ByteArray {
    val out = ByteArray(8)
    for (i in 0 until 8) {
        val shift = if (e == E2.LITTLE) 8 * i else 8 * (7 - i)
        out[i] = ((v shr shift) and 0xFFuL).toByte()
    }
    return out
}

private val MENSAJE_V2: MessageId = MessageId.from(UUID(0x0202020202020202L, 0x0303030303030303L))

private fun hexb(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

/**
 * 3Q.5.3 — El codec de la unidad `KM52` v2.
 *
 * Prefijo `KM52V2-`. Contrato en la spec rev5 (obs #35): `unitVersion = 2`,
 * bloque `PENDING_INBOUND`, outbound singular y opcional, y exactamente dos
 * cotas de politica (L1 = 200 KiB de claves, L2 = 256 KiB de inbound) mas el
 * invariante defensivo L4 (1 MiB).
 *
 * ## LAS CUATRO PRUEBAS DE FRONTERA, Y POR QUE SON LAS FUERTES
 *
 * 1. **`KM52V2-06` — el estado se prueba con `encrypt()` byte a byte.** Nunca
 *    con `stateFingerprint()`: la huella de `DoubleRatchetSession` se construye
 *    con `dhSelf.publicKey` y no cubre el escalar privado.
 * 2. **`KM52V2-05` — leer sobredimensionado es RECHAZO TOTAL.** Una unidad en
 *    disco con el inbound por encima de L2 se rechaza entera; no se poda por el
 *    frente. Y se comprueba por COMPORTAMIENTO: los frames que estaban en la
 *    bandeja siguen descifrando.
 * 3. **`KM52V2-04` — el limite defensivo L4 es INALCANZABLE.** Se calcula el
 *    maximo legal y se comprueba que esta por debajo de 1 MiB. Es lo contrario
 *    de un test de "el limite salta": es la prueba de que el limite NO puede
 *    saltar, y por eso tiene que existir la comprobacion que lo haria saltar si
 *    el byte-string no cumpliera el contrato.
 * 4. **`KM52V2-09` — la frontera.** `ratchet/` no importa nada de la
 *    persistencia ni de la bandeja, y `PendingInboundEntry` no aparece dentro
 *    de `DoubleRatchetSnapshot`.
 */
class Km52V2CodecTest {

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
    // GUION CRIPTOGRAFICO (el mismo de Km52CodecTest: hacen falta sesiones REALES)
    // ===================================================================

    private fun sesion(dhSelf: X25519KeyPair, dhRemote: ByteArray?): DoubleRatchetSession =
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
        val aliceSession = sesion(x25519.generateKeyPair(), bobDh.publicKey)
        val bobSession = sesion(bobDh, null)
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

    /** Alice deja cuatro frames sin entregar: dos de la epoca vieja retenidos. */
    private fun dosEpochs(w: Wire): List<ByteArray> {
        val a0 = exchange(w.alice, w.bob, "a0")
        val a1 = w.alice.encrypt("a1".toByteArray())
        val a2 = w.alice.encrypt("a2".toByteArray())
        exchange(w.bob, w.alice, "b0")
        exchange(w.bob, w.alice, "b1")
        exchange(w.bob, w.alice, "b2")
        val a3 = exchange(w.alice, w.bob, "a3")
        val a4 = w.alice.encrypt("a4".toByteArray())
        return listOf(a0, a1, a2, a3, a4)
    }

    private fun det(seed: Int) = X25519KeyPair(
        ByteArray(32) { ((it + seed) and 0xFF).toByte() },
        x25519.publicKey(ByteArray(32) { ((it + seed) and 0xFF).toByte() }),
    )

    private fun hex(b: ByteArray) = hexb(b)

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
        ciphertext: ByteArray = ByteArray(24) { (it * 5 + 1).toByte() },
    ) = OutboundRecord(
        recordVersion = 1u,
        deliveryState = state,
        messageId = MENSAJE_V2,
        frameIdentity = FrameIdentity.of(dh, pn, n),
        createdOrdinal = created,
        ciphertext = ciphertext,
    )

    // ===================================================================
    // ENSAMBLADOR DE UNIDADES v2 — el "otro lado" del codec
    //
    // Hace falta porque el codec NUNCA escribe una unidad invalida: para probar
    // el LADO DE LECTURA hay que poder escribir a mano lo que el codec no
    // escribiria jamas.
    //
    // `KM52V2-00` demuestra que este ensamblador reproduce byte a byte lo que
    // produce `serialize`, de modo que ninguna unidad artificial de este
    // archivo se apoya en un layout inventado aqui.
    // ===================================================================

    private class InbRec(
        val identity: ByteArray,
        val wire: ByteArray,
        val identityLenOverride: UInt? = null,
        /** Escribe en la identidad una que NO es la del header del wire. */
        val identityDistinta: Boolean = false,
    ) {
        fun encode(): ByteArray = B().apply {
            b32((identityLenOverride ?: 40u).toLong(), E2.LITTLE)
            bytes(if (identityDistinta) ByteArray(40) { 0x77 } else identity)
            b32(wire.size.toLong(), E2.LITTLE)
            bytes(wire)
        }.build()
    }

    private inner class UnidadV2(
        val inbound: List<InbRec> = emptyList(),
        val countOverride: Long? = null,
        val inboundLenOverride: Long? = null,
        val outboundLenOverride: Long? = null,
        val checksumOverride: ByteArray? = null,
        val bodyOverride: ByteArray? = null,
        val unitVersion: UByte = Km52Spec.UNIT_VERSION,
    ) {
        fun bytes(snap: ByteArray, out: ByteArray, e: E2 = E2.LITTLE): ByteArray {
            val inb = B().apply {
                b32(countOverride ?: inbound.size.toLong(), e)
                inbound.forEach { bytes(it.encode()) }
            }.build()
            // `totalLen` tiene que DESCRIBIR lo que hay. Cuando se sobrescribe
            // una longitud de bloque, `totalLen` usa la sobrescrita: si usara la
            // real, el rechazo del lector seria por incoherencia del header y
            // no por la cota que el caso quiere provocar.
            val inboundEfectivo = inboundLenOverride ?: inb.size.toLong()
            val total = 32L + snap.size + out.size + inboundEfectivo + 32
            val header = B().apply {
                bytes(Km52Spec.MAGIC)
                b(unitVersion.toInt())
                b(0)
                b16(0)
                b32(snap.size.toLong(), e)
                b32(outboundLenOverride ?: out.size.toLong(), e)
                b32((out.size - 72L).coerceAtLeast(0), e)
                b32(total, e)
                b32(inboundEfectivo, e)
                b32(0, e)
            }.build()
            val cuerpo = bodyOverride ?: (header + snap + out + inb)
            // Con `inboundLenOverride` (o `outboundLenOverride`) el header anuncia
            // una longitud que NO es la del bloque realmente escrito, asi que el
            // cuerpo natural no mide lo que `totalLen` declara. Para que el
            // byte-string sea CANONICO en tamano —y el rechazo sea por la
            // incoherencia declarada y no por un truncamiento o una cola
            // accidentales— el cuerpo se ajusta a lo que el header dice:
            // relleno de ceros si le falta, recorte si sobra.
            //
            // El objetivo es `total - 32`, NO `total`: los 32 ultimos bytes de la
            // unidad son el checksum, que todavia no esta escrito. Rellenar
            // hasta `total` metia 32 ceros de mas entre el cuerpo y el checksum,
            // y el lector rechazaba TODO caso por "sobran 32 bytes" antes de
            // llegar a la cota que el caso queria provocar.
            val cuerpoEsperado = total.toInt() - Km52Spec.CHECKSUM_LENGTH
            val relleno = cuerpoEsperado - cuerpo.size
            val cuerpoRelleno = when {
                relleno < 0 -> cuerpo.copyOf(cuerpoEsperado)
                else -> cuerpo + ByteArray(relleno)
            }
            return cuerpoRelleno + (checksumOverride ?: hash.sha256(cuerpoRelleno))
        }
    }

    private class B {
        private val out = ByteArrayOutputStream()
        fun b(v: Int) { out.write(v and 0xFF) }
        fun b16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun b32(v: Long, e: E2) { out.write(u32be(v, e)) }
        fun b64(v: ULong, e: E2) { out.write(u64le(v, e)) }
        fun bytes(a: ByteArray) { out.write(a, 0, a.size) }
        fun build(): ByteArray = out.toByteArray()
    }

    /** Bloque SNAPSHOT minimo y valido: sin cadenas de recepcion. */
    private fun snapMinimo(): ByteArray = B().apply {
        bytes(ByteArray(32) { 0x41 })
        bytes(det(7).privateKey)
        b(0)                                   // dhRemote ausente
        b32(0, E2.LITTLE)                  // Ns
        b32(0, E2.LITTLE)                  // PN
        bytes(ByteArray(32) { 0x11 })          // CKs de la cadena de envio
        b32(0, E2.LITTLE)
        b32(0, E2.LITTLE)
        b16(0)                                 // sin retenidas
        b16(0)                                 // sin cadenas de recepcion
    }.build()

    /** Bloque OUTBOUND minimo: 72 fijos + 20 de ciphertext. */
    private fun outMinimo(): ByteArray = B().apply {
        b(1)                                   // recVersion
        b(1)                                   // deliveryState
        b16(0)
        val msb = MENSAJE_V2.value.mostSignificantBits
        val lsb = MENSAJE_V2.value.leastSignificantBits
        for (i in 7 downTo 0) b(((msb shr (8 * i)) and 0xFF).toInt())
        for (i in 7 downTo 0) b(((lsb shr (8 * i)) and 0xFF).toInt())
        b32(40, E2.LITTLE)
        bytes(ByteArray(40) { 0x71 })
        b64(7uL, E2.LITTLE)
        bytes(ByteArray(20) { 0x81.toByte() })
    }.build()

    private fun le32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun le32be(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    /** Frames de entrada reales, tomados de un guion con dos extremos. */
    private fun framesDeEntrada(w: Wire): List<ByteArray> = listOf(
        w.alice.encrypt("e0".toByteArray()),
        w.alice.encrypt("e1".toByteArray()),
        w.alice.encrypt("e2".toByteArray()),
    )

    private fun inbDe(wire: ByteArray) = InbRec(FrameIdentity.fromWire(wire).bytes, wire)

    // ===================================================================
    // KILOMETRO 0 — el ensamblador es de fiar
    // ===================================================================

    @Test
    @DisplayName("KM52V2-00 el ensamblador reproduce byte a byte lo que escribe el codec v2")
    fun `KM52V2-00 el ensamblador reproduce el codec`() {
        val w = Wire()
        dosEpochs(w)
        val frames = framesDeEntrada(w)
        val foto = w.bobSession.snapshot()
        val entrada = frames.map { PendingInboundEntry(FrameIdentity.fromWire(it), it) }
        val unidad = Km52Unit(
            snapshot = foto,
            retention = retencionDe(foto),
            outbound = registro(w.bobSession.selfDhPublicKey(), 3u, 5u, OutboundDeliveryState.IN_FLIGHT, 7uL),
            pendingInbound = entrada,
        )
        val porCodec = codec().serialize(unidad)

        // El mismo estado por la via del LADO DE ESCRITURA y por la del
        // ensamblador: si no coinciden byte a byte, ninguna de las unidades
        // artificiales de este archivo probaria nada.
        val salida = codec().deserialize(porCodec)
        val reensamblada = UnidadV2(
            inbound = frames.map { inbDe(it) },
        ).bytes(
            snap = snapshotCodificado(salida.snapshot, salida.retention),
            out = outboundCodificado(salida.outbound!!),
        )
        assertContentEquals(porCodec, reensamblada, "el ensamblador del test y el codec tienen que coincidir")
    }

    /** Re-codifica el SNAPSHOT que devuelve `deserialize`, para el ensamblador. */
    private fun snapshotCodificado(s: DoubleRatchetSnapshot, r: ReceiveChainRetention): ByteArray = B().apply {
        bytes(s.rootKey)
        bytes(s.dhSelf.privateKeyBytes())
        b(if (s.dhRemote != null) 1 else 0)
        s.dhRemote?.let { bytes(it) }
        b32(s.sendMessageNumber.toLong(), E2.LITTLE)
        b32(s.previousChainLength.toLong(), E2.LITTLE)
        bytes(s.sendChain.sendChainKey)
        b32(s.sendChain.sendMessageNumber.toLong(), E2.LITTLE)
        b32(s.sendChain.receiveMessageNumber.toLong(), E2.LITTLE)
        b16(0)
        val cadenas = s.receiveChains.sortedBy { hex(it.chainId) }
        b16(cadenas.size)
        for (c in cadenas) {
            bytes(c.chainId)
            b64(r.lastUseOrdinal(c.chainId)!!, E2.LITTLE)
            bytes(c.ratchet.sendChainKey)
            b32(c.ratchet.sendMessageNumber.toLong(), E2.LITTLE)
            bytes(c.ratchet.receiveChainKey)
            b32(c.ratchet.receiveMessageNumber.toLong(), E2.LITTLE)
            val claves = c.ratchet.skipped.entries.sortedWith(compareBy({ hex(it.key.first.bytes) }, { it.key.second }))
            b16(claves.size)
            for (e in claves) {
                bytes(e.key.first.bytes)
                b32(e.key.second.toLong(), E2.LITTLE)
                bytes(e.value)
            }
        }
    }.build()

    private fun outboundCodificado(r: OutboundRecord): ByteArray = B().apply {
        b(r.recordVersion.toInt())
        b(r.deliveryState.code.toInt())
        b16(0)
        val msb = r.messageId.value.mostSignificantBits
        val lsb = r.messageId.value.leastSignificantBits
        for (i in 7 downTo 0) b(((msb shr (8 * i)) and 0xFF).toInt())
        for (i in 7 downTo 0) b(((lsb shr (8 * i)) and 0xFF).toInt())
        b32(40, E2.LITTLE)
        bytes(r.frameIdentity.bytes)
        b64(r.createdOrdinal, E2.LITTLE)
        bytes(r.ciphertext)
    }.build()

    // ===================================================================
    // KILOMETRO 1 — Ida y vuelta CON la bandeja
    // ===================================================================

    @Test
    @DisplayName("KM52V2-01 la unidad v2 lleva el outbound y la bandeja, y los devuelve IGUALES")
    fun `KM52V2-01 ida y vuelta`() {
        val w = Wire()
        dosEpochs(w)
        val frames = framesDeEntrada(w)
        val foto = w.bobSession.snapshot()
        val entrada = frames.map { PendingInboundEntry(FrameIdentity.fromWire(it), it) }
        val rec = registro(w.bobSession.selfDhPublicKey(), 3u, 5u, OutboundDeliveryState.IN_FLIGHT, 42uL)

        val leida = codec().deserialize(
            codec().serialize(Km52Unit(foto, retencionDe(foto), rec, entrada)),
        )

        // El bloque nuevo.
        assertEquals(3, leida.pendingInbound.size, "las tres entradas vuelven")
        for (i in frames.indices) {
            assertContentEquals(frames[i], leida.pendingInbound[i].wireFrame, "el wire-frame [$i], byte a byte")
            assertEquals(
                FrameIdentity.fromWire(frames[i]), leida.pendingInbound[i].frameIdentity,
                "y su identidad, leida del header",
            )
        }
        // Y el resto de la unidad, que no puede haber cambiado.
        assertContentEquals(foto.rootKey, leida.snapshot.rootKey, "raiz")
        assertContentEquals(foto.dhSelf.privateKeyBytes(), leida.snapshot.dhSelf.privateKeyBytes(), "escalar DH")
        assertEquals(foto.receiveChains.size, leida.snapshot.receiveChains.size, "cadenas")
        assertEquals(rec.deliveryState, leida.outbound?.deliveryState, "deliveryState")
        assertEquals(rec.messageId, leida.outbound?.messageId, "messageId")
        assertEquals(rec.frameIdentity, leida.outbound?.frameIdentity, "frameIdentity")
        assertContentEquals(rec.ciphertext, leida.outbound?.ciphertext, "ciphertext")

        // INV-07 con la bandeja: el mismo estado produce SIEMPRE los mismos
        // bytes. Sin esto, un recorrido de mapa pasaria inadvertido.
        val c = codec()
        val u = Km52Unit(foto, retencionDe(foto), rec, entrada)
        assertContentEquals(c.serialize(u), c.serialize(u), "el mismo estado produce los mismos bytes")

        // Y el ORDEN es de llegada, no de identidad: se entra al reves y sale
        // al reves, no ordenado por nada.
        val reves = entrada.reversed()
        val leidaReves = codec().deserialize(
            codec().serialize(Km52Unit(foto, retencionDe(foto), rec, reves)),
        )
        assertEquals(
            reves.map { it.frameIdentity },
            leidaReves.pendingInbound.map { it.frameIdentity },
            "el orden de las entradas es el de ENTRADA, no uno derivado del contenido",
        )
    }

    // ===================================================================
    // KILOMETRO 2 — La version sube a 2 y v1 se rechaza ENTERA
    // ===================================================================

    @Test
    @DisplayName("KM52V2-02 la unidad declara version 2 y una v1 se rechaza sin leer el payload")
    fun `KM52V2-02 version dos`() {
        val w = Wire()
        val frames = framesDeEntrada(w)
        val foto = w.bobSession.snapshot()
        val bytes = codec().serialize(
            Km52Unit(foto, retencionDe(foto), registro(w.bobSession.selfDhPublicKey()), frames.map {
                PendingInboundEntry(FrameIdentity.fromWire(it), it)
            }),
        )
        assertEquals("KM52", String(bytes, 0, 4, Charsets.US_ASCII), "magic en el offset 0")
        assertEquals(2, bytes[4].toInt(), "unitVersion en el offset 4 es 2")

        // Y v1 (y cualquier otra) se rechaza ENTERA, ANTES de mirar longitudes.
        // Sin esto, una v1 pasada por el lector de v2 leeria el bloque PENDING
        // donde no lo hay.
        class Espia(private val real: X25519) : X25519 {
            var llamadas = 0
            override fun generateKeyPair(): X25519KeyPair = real.generateKeyPair()
            override fun publicKey(privateKey: ByteArray): ByteArray { llamadas++; return real.publicKey(privateKey) }
            override fun agree(privateKey: ByteArray, publicKey: ByteArray): ByteArray = real.agree(privateKey, publicKey)
        }
        val espia = Espia(x25519)
        val c = BinaryKm52UnitCodec(espia, hash)
        val v1 = UnidadV2(inbound = frames.map { inbDe(it) }, unitVersion = 1u)
            .bytes(snapMinimo(), outMinimo())
        val e = assertThrows<UnsupportedUnitVersion> { c.deserialize(v1) }
        assertEquals(1u, e.version, "el error dice que version vio")
        assertEquals(0, espia.llamadas, "una version desconocida corta ANTES de interpretar nada")
    }

    // ===================================================================
    // KILOMETRO 3 — Layout del header de v2
    // ===================================================================

    @Test
    @DisplayName("KM52V2-03 el header de v2 declara los cuatro bloques y el checksum los completa")
    fun `KM52V2-03 header de v2`() {
        val w = Wire()
        val frames = framesDeEntrada(w)
        val foto = w.bobSession.snapshot()
        val bytes = codec().serialize(
            Km52Unit(foto, retencionDe(foto), registro(w.bobSession.selfDhPublicKey()), frames.map {
                PendingInboundEntry(FrameIdentity.fromWire(it), it)
            }),
        )
        val snapshotLen = le32(bytes, Km52Spec.SNAPSHOT_LEN_OFFSET)
        val outboundLen = le32(bytes, Km52Spec.OUTBOUND_LEN_OFFSET)
        val ciphertextLen = le32(bytes, Km52Spec.CIPHERTEXT_LEN_OFFSET)
        val inboundLen = le32(bytes, Km52Spec.PENDING_INBOUND_LEN_OFFSET)
        val totalLen = le32(bytes, Km52Spec.TOTAL_LEN_OFFSET)

        assertEquals(24, Km52Spec.PENDING_INBOUND_LEN_OFFSET, "el campo nuevo ocupa los 4 primeros de reserved2")
        assertEquals(28, Km52Spec.RESERVED2_OFFSET, "y los ultimos 4 se quedan reservados")
        assertEquals(32, Km52Spec.HEADER_LENGTH, "el header no crece: v2 cabe en los 32 B de v1")
        assertEquals(
            32L + snapshotLen + outboundLen + inboundLen + 32L, totalLen,
            "los CUATRO bloques mas cabecera y checksum completan totalLen",
        )
        assertEquals(bytes.size.toLong(), totalLen, "totalLen es el tamano real")

        // `ciphertextLen` NO suma: es el ultimo campo del bloque OUTBOUND y ya
        // esta dentro de `outboundLen`.
        assertEquals(outboundLen - 72L, ciphertextLen, "OUTBOUND = 72 B fijos + ciphertext")
        // Y el bloque inbound se mide EN BYTES del bloque entero, count(4)
        // incluido: tres entradas de 44+len.
        assertEquals(
            4L + frames.sumOf { 48L + it.size },
            inboundLen,
            "el bloque inbound es count(4) mas 48 + wire por entrada",
        )
        // Y el cuerpo se consume EXACTAMENTE: no hay cola ni huecos.
        assertEquals(
            Km52Spec.HEADER_LENGTH + snapshotLen + outboundLen + inboundLen,
            (bytes.size - Km52Spec.CHECKSUM_LENGTH).toLong(),
            "la unidad es canonica: no sobran ni faltan bytes",
        )
    }

    // ===================================================================
    // KILOMETRO 4 — L4: el limite DEFENSIVO es inalcanzable por una unidad legal
    // ===================================================================

    @Test
    @DisplayName("KM52V2-04 L4 es un invariante defensivo: la unidad legal maxima esta por debajo")
    fun `KM52V2-04 L4 es inalcanzable`() {
        // LA AFIRMACION, calculada aqui y no importada: si el codigo y el test
        // compartieran la constante, un cambio de la unaMOVIERIA el criterio del
        // otro y el test pasaria por la razon equivocada.
        val maximoLegal = 273_778L +      // SNAPSHOT §3.1
            65_607L +                     // OUTBOUND = 72 + 65 535
            Km52PendingLimits.MAX_PENDING_INBOUND_BUDGET.toLong() +
            64L                          // cabecera 32 + checksum 32
        assertEquals(601_593L, maximoLegal, "la cuenta de la spec rev5")
        assertEquals(587, maximoLegal / 1024, "587 KiB, no 588: la correccion aritmetica de A3-bis")
        assertTrue(
            maximoLegal < Km52PendingLimits.DEFENSIVE_UNIT_BOUND,
            "601 593 < 1 048 576: por eso L4 NO puede dispararse con una unidad legal",
        )
        assertEquals(Km52Spec.HEADER_LENGTH, 32, "la cuenta de la spec usa cabecera de 32 B")
        assertEquals(Km52Spec.PENDING_ENTRY_FIXED_LENGTH, 48, "y la entrada de 48 = 4 + 40 + 4")

        // Y la comprobacion EXISTE: un byte-string que declara mas de 1 MiB se
        // rechaza con el error propio, y no con un error de formato cualquiera.
        val enorme = UnidadV2(inboundLenOverride = 1_100_000L).bytes(snapMinimo(), outMinimo())
        val e = assertThrows<Km52UnitTooLarge> { codec().deserialize(enorme) }
        assertEquals(Km52PendingLimits.DEFENSIVE_UNIT_BOUND.toLong(), e.limit, "el limite defensivo")
        assertTrue(e.message!!.contains("defensiv", ignoreCase = true), "el mensaje dice que es defensivo: $e")

        // Y al escribir tambien: una bandeja que no cabe en L2 se recorta ANTES,
        // de modo que la unidad que sale SIEMPRE esta por debajo de L4. Es la
        // unica forma de que el limite sea alcanzable desde el lado de la
        // escritura, y por eso existe.
        val w = Wire()
        val frames = framesDeEntrada(w)
        val foto = w.bobSession.snapshot()
        var muchas = emptyList<PendingInboundEntry>()
        repeat(40) {
            muchas = muchas + PendingInboundEntry(FrameIdentity.of(det(it + 1).publicKey, 0u, it.toUInt()), frames[0])
        }
        val bytes = codec().serialize(Km52Unit(foto, retencionDe(foto), null, muchas))
        assertTrue(
            bytes.size <= Km52PendingLimits.DEFENSIVE_UNIT_BOUND,
            "una bandeja por encima de L2 se recorta y la unidad sale por debajo de L4: ${bytes.size}",
        )
    }

    // ===================================================================
    // KILOMETRO 5 — L2 AL LEER: rechazo ENTERO, sin podar
    // ===================================================================

    @Test
    @DisplayName("KM52V2-05 un inbound por encima de L2 se rechaza ENTERO al leer y no se poda")
    fun `KM52V2-05 L2 rechazo total al leer`() {
        val w = Wire()
        val (_, a1, _, _, _) = dosEpochs(w)

        // Una entrada con 300 KiB de wire-frame: por encima de L2 (262 144), muy
        // por debajo de L4 (1 MiB). La unidad tiene que ser ESTRUCTURALMENTE
        // valida, asi que el wire-frame lleva un header REAL: si fuera un
        // bloque de ceros, el rechazo seria el contraste de identidad y no
        // la cota de L2, y el caso probaria otra cosa.
        val enormeWire = ByteArray(300 * 1024) { (it and 0xFF).toByte() }
        System.arraycopy(det(21).publicKey, 0, enormeWire, 4, 32)
        enormeWire[36] = 0; enormeWire[37] = 0; enormeWire[38] = 0; enormeWire[39] = 0
        enormeWire[40] = 0; enormeWire[41] = 0; enormeWire[42] = 0; enormeWire[43] = 7
        val identidad = FrameIdentity.fromWire(enormeWire).bytes
        val enorme = InbRec(identidad, enormeWire)
        val bytes = UnidadV2(inbound = listOf(enorme)).bytes(snapMinimo(), outMinimo())
        assertTrue(bytes.size < Km52PendingLimits.DEFENSIVE_UNIT_BOUND, "el guion tiene que estar por debajo de L4")
        assertTrue(
            le32(bytes, Km52Spec.PENDING_INBOUND_LEN_OFFSET) > Km52PendingLimits.MAX_PENDING_INBOUND_BUDGET,
            "y por encima de L2: si no, el rechazo seria por otro motivo",
        )

        // 1. RECHAZO TOTAL, con el error TIPADO de L2.
        val e = assertThrows<PendingInboundBudgetExceeded> { codec().deserialize(bytes) }
        assertEquals(Km52PendingLimits.MAX_PENDING_INBOUND_BUDGET.toLong(), e.limit, "el techo de L2")
        assertTrue(e.actual > e.limit, "y cuanto traia")

        // 2. DETERMINISMO: el mismo byte-string falla igual, siempre.
        repeat(3) {
            val otro = assertThrows<PendingInboundBudgetExceeded> { codec().deserialize(bytes) }
            assertEquals(e.actual, otro.actual, "intento $it")
        }

        // 3. LA SESION INTACTA, con COMPORTAMIENTO y no con `stateFingerprint()`:
        //    la huella no cubre el escalar DH privado. El frame retenido de la
        //    epoca vieja tiene que SEGUIR descifrando, y el siguiente frame
        //    tiene que salir byte a byte igual.
        val bob = w.bobSession
        val control = DoubleRatchetSession.restore(bob.snapshot(), x25519, kdf)
        val antes = SecureRatchetProtocol(control, protector).encrypt("ctrl".toByteArray())
        repeat(2) { assertThrows<PendingInboundBudgetExceeded> { codec().deserialize(bytes) } }
        assertContentEquals(
            antes, SecureRatchetProtocol(bob, protector).encrypt("ctrl".toByteArray()),
            "la sesion no se ha movido un byte",
        )
        val d = w.bob.decrypt(a1)
        assertTrue(d is SecureRatchetProtocol.DecryptResult.Ok, "el frame retenido entra: $d")
        assertTrue(
            (d as SecureRatchetProtocol.DecryptResult.Ok).fromSkipped,
            "y desde la clave retenida: leer no ha podado NADA",
        )

        // 4. Y POR ENDEBAJO DEL TECHO, LA MISMA FORMA ENTRA: no se esta midiendo
        //    "hay entradas", se esta midiendo "caben".
        val cabeWire = ByteArray(200 * 1024) { (it and 0xFF).toByte() }
        System.arraycopy(det(22).publicKey, 0, cabeWire, 4, 32)
        cabeWire[36] = 0; cabeWire[37] = 0; cabeWire[38] = 0; cabeWire[39] = 0
        cabeWire[40] = 0; cabeWire[41] = 0; cabeWire[42] = 0; cabeWire[43] = 9
        val leida = codec().deserialize(
            UnidadV2(inbound = listOf(InbRec(FrameIdentity.fromWire(cabeWire).bytes, cabeWire)))
                .bytes(snapMinimo(), outMinimo()),
        )
        assertEquals(1, leida.pendingInbound.size, "200 KiB entra")
        assertEquals(200 * 1024, leida.pendingInbound.single().wireFrame.size, "con todos sus bytes")
    }

    // ===================================================================
    // KILOMETRO 6 — El estado se mide con `encrypt()` byte a byte
    // ===================================================================

    @Test
    @DisplayName("KM52V2-06 la sesion restaurada desde una unidad v2 produce el MISMO ciphertext")
    fun `KM52V2-06 mismo ciphertext tras snapshot unidad v2 bytes restore`() {
        val w = Wire()
        dosEpochs(w)
        val frames = framesDeEntrada(w)
        val carga = "contenido que no puede cambiar de bytes".toByteArray()

        // EL ORDEN DE ESTAS DOS LINEAS ES LA PRUEBA.
//
// `w.bob` envuelve a `w.bobSession`: son el MISMO objeto. Si se cifrase antes
// de fotografiar, la foto contendria un indice de envio ya AVANZADO en una
// unidad, y la sesion restaurada de ella volveria a emitir el frame N+1 —no el
// N que se quiere comparar— aunque la unidadiese el estado perfecto. El fallo
// se/manifiesta en el offset 43, el ULTIMO byte del `messageNumber(4)` del
// header del SecureFrame (offsets 40..43): los bytes 0..42, que incluyen el
// AAD entero, coinciden, asi que el estado del ratchet no difiere en ningun otro
// sitio. Fotografiar ANTES de enviar es lo que hace la sesion restaurada igual
// a la que envio.
val foto = w.bobSession.snapshot()
val original = w.bob.encrypt(carga)
        val conBandeja = frames.map { PendingInboundEntry(FrameIdentity.fromWire(it), it) }

        fun sesionPorLaUnidad(inbox: List<PendingInboundEntry>): DoubleRatchetSession {
            val bytes = codec().serialize(Km52Unit(foto, retencionDe(foto), null, inbox))
            return DoubleRatchetSession.restore(codec().deserialize(bytes).snapshot, x25519, kdf)
        }

        assertContentEquals(
            original,
            SecureRatchetProtocol(sesionPorLaUnidad(conBandeja), protector).encrypt(carga),
            "el frame tiene que salir IDENTICO con la bandeja dentro de la unidad",
        )
        assertContentEquals(
            original,
            SecureRatchetProtocol(sesionPorLaUnidad(emptyList()), protector).encrypt(carga),
            "y tambien sin bandeja: la bandeja NO es estado del ratchet",
        )
    }

    // ===================================================================
    // KILOMETRO 7 — La bandeja: FIFO, deduplicacion y L2
    // ===================================================================

    @Test
    @DisplayName("KM52V2-07 la bandeja deduplica por FrameIdentity y acota por el frente en FIFO")
    fun `KM52V2-07 bandeja FIFO, dedup y L2`() {
        val w = Wire()
        val frames = framesDeEntrada(w)
        val identidades = frames.map { FrameIdentity.fromWire(it) }
        assertEquals(3, identidades.distinct().size, "el guion tiene que tener tres identidades distintas")

        // --- DEDUPLICACION ---
        val bandeja = PendingInbox()
        assertTrue(bandeja.encolar(frames[0]), "el primero entra")
        assertTrue(bandeja.encolar(frames[1]), "el segundo entra")
        assertFalse(bandeja.encolar(frames[0]), "el repetido se RECHAZA, no se sustituye")
        assertEquals(2, bandeja.tamano(), "el repetido no ocupa una segunda plaza")
        assertEquals(identidades.take(2), bandeja.entradas().map { it.frameIdentity }, "y los dos primeros siguen ahi")

        // Un frame DISTINTO con la MISMA identidad no puede existir: la identidad
        // es unica por frame. Por eso el rechazo por identidad y la deduplicacion
        // son la misma regla vista desde dos lados.
        val impostor = ByteArray(frames[0].size) { 0x5A }
        System.arraycopy(frames[0], 0, impostor, 0, 44)   // mismo header, otra carga
        assertFalse(bandeja.encolar(impostor), "misma identidad, bytes distintos: tambien se rechaza")
        assertContentEquals(frames[0], bandeja.entradas()[0].wireFrame, "y el ORIGINAL sigue intacto")

        // --- FIFO ---
        assertEquals(identidades.take(2), bandeja.entradas().map { it.frameIdentity }, "el orden es de llegada")
        val reves = PendingInbox()
        frames.reversed().forEach { reves.encolar(it) }
        assertEquals(
            identidades.reversed(), reves.entradas().map { it.frameIdentity },
            "entrar al reves sale al reves: el orden es de llegada, no de contenido",
        )

        // --- L2: se acota POR EL FRENTE ---
        //
        // Se fabrican frames REALES de `grande` bytes: se toma un frame del
        // guion, se le alarga el payload y se le PONTE una identidad distinta en
        // el header, porque `FrameIdentity.fromWire` lee el header y una carga
        // mayor sin tocarlo daria siempre la misma identidad.
        val grande = 60 * 1024
        fun frameLargo(semilla: Int): ByteArray = ByteArray(grande).also { b ->
            val base = frames[semilla % frames.size]
            base.copyInto(b, 0, 0, minOf(base.size, b.size))
            System.arraycopy(FrameIdentity.of(det(semilla + 1).publicKey, 0u, semilla.toUInt()).bytes, 0, b, 4, 40)
        }

        val enElLimite = PendingInbox()
        repeat(4) { enElLimite.encolar(frameLargo(it)) }
        assertEquals(4, enElLimite.tamano(), "cuatro frames de 60 KiB caben en 256 KiB")
        assertEquals(4 * grande, enElLimite.bytesOcupados(), "y ocupan lo que ocupan")

        val antes = enElLimite.entradas().map { it.frameIdentity }
        val quinto = frameLargo(4)
        enElLimite.encolar(quinto)

        assertTrue(
            enElLimite.bytesOcupados() <= PendingInbox.DEFAULT_BUDGET,
            "el presupuesto se respeta siempre: ${enElLimite.bytesOcupados()}",
        )
        assertTrue(
            enElLimite.tamano() < 5,
            "cinco frames de 60 KiB NO caben en 256 KiB: hay que tirar alguno",
        )
        // 4 x 60 KiB = 245 760 <= 262 144: los cuatro caben. El quinto no cabe
        // con ellos, asi que sale UNO —el primero— y quedan cuatro: los tres
        // ultimos de antes mas el recien llegado.
        assertEquals(
            antes.takeLast(3) + FrameIdentity.fromWire(quinto),
            enElLimite.entradas().map { it.frameIdentity },
            "y se ha tirado POR EL FRENTE: el mas antiguo, no el recien llegado",
        )
        assertEquals(
            FrameIdentity.fromWire(quinto), enElLimite.entradas().last().frameIdentity,
            "el recien llegado es el ULTIMO de la lista: lo que se tira es lo que mas tiempo lleva",
        )
        assertTrue(enElLimite.descartadas() >= 1uL, "y la cuenta de descartadas lo dice")

        // Y el recorte NO es "quedarse con los N primeros" de forma arbitraria:
        // al vaciar del todo y reencolar el mismo conjunto, el resultado es el
        // mismo. Si dependiera del recorrido de un mapa, estos dos recorrido
        // darian resultados distintos.
        val porTabla = LinkedHashMap<FrameIdentity, ByteArray>()
        for (i in 0 until 5) porTabla[FrameIdentity.fromWire(frameLargo(i))] = frameLargo(i)
        val reencolado = PendingInbox()
        for (f in porTabla.values) reencolado.encolar(f)
        assertEquals(
            enElLimite.entradas().map { it.frameIdentity },
            reencolado.entradas().map { it.frameIdentity },
            "el resultado depende del ORDEN DE LLEGADA, no de como este guardado el conjunto",
        )
    }

    // ===================================================================
    // KILOMETRO 8 — Campos imposibles del bloque nuevo
    // ===================================================================

    @Test
    @DisplayName("KM52V2-08 los campos imposibles del bloque PENDING_INBOUND se rechazan uno a uno")
    fun `KM52V2-08 campos imposibles`() {
        val w = Wire()
        val frames = framesDeEntrada(w)
        val sano = frames.map { inbDe(it) }

        fun base(
            inbound: List<InbRec> = sano,
            count: Long? = null,
            len: Long? = null,
        ): ByteArray = UnidadV2(inbound = inbound, countOverride = count, inboundLenOverride = len)
            .bytes(snapMinimo(), outMinimo())

        val rechazada = mutableListOf<String>()
        fun rechaza(nombre: String, bytes: ByteArray) {
            try {
                codec().deserialize(bytes)
                rechazada += nombre
            } catch (e: Km52FormatException) {
                assertTrue(e.message!!.isNotBlank(), "'$nombre' tiene que decir por que")
            }
        }

        // Dos entradas con la MISMA identidad.
        rechaza("identidades repetidas", base(listOf(inbDe(frames[0]), inbDe(frames[0]))))
        // Una identidad que no es la del header del wire.
        rechaza(
            "identidad distinta del header",
            base(listOf(InbRec(FrameIdentity.fromWire(frames[0]).bytes, frames[0], identityDistinta = true))),
        )
        // frameIdentityLen que no es 40.
        rechaza(
            "frameIdentityLen != 40",
            base(listOf(InbRec(FrameIdentity.fromWire(frames[0]).bytes, frames[0], identityLenOverride = 39u))),
        )
        // count que no cuadra con las entradas.
        rechaza("count mayor", base(count = sano.size.toLong() + 3))
        rechaza("count menor", base(count = 1))
        // inboundLen que no cuadra.
        rechaza("inboundLen corta", base(len = 8))
        rechaza("inboundLen larga", base(len = 4096))
        // Un wire-frame que no llega a tener header.
        rechaza(
            "wire-frame de 10 bytes",
            base(listOf(InbRec(FrameIdentity.of(det(1).publicKey, 0u, 0u).bytes, ByteArray(10)))),
        )
        // Y un wire-frame de 43 B: uno menos del header, que es donde
        // `FrameIdentity.fromWire` dejaria de poder leer la identidad.
        rechaza(
            "wire-frame de 43 bytes",
            base(listOf(InbRec(FrameIdentity.of(det(1).publicKey, 0u, 0u).bytes, ByteArray(43)))),
        )
        // Y el bloque tiene que acabar donde el header dice.
        rechaza("el bloque no cuadra con el header", base(len = 100))

        assertEquals(emptyList<String>(), rechazada, "ningun campo imposible puede pasar: $rechazada")

        // Y el bloque VACIO es legal: vale `count = 0` y ocupa 4 bytes. Una
        // sesion sin frames entrantes pendientes tiene que poder persistirse.
        val vacio = codec().deserialize(UnidadV2().bytes(snapMinimo(), outMinimo()))
        assertEquals(emptyList<PendingInboundEntry>(), vacio.pendingInbound, "sin entrada pendiente")
        assertNotNull(vacio.outbound, "y con su outbound")
    }

    // ===================================================================
    // KILOMETRO 9 — LA FRONTERA
    // ===================================================================

    @Test
    @DisplayName("KM52V2-09 la frontera: el ratchet no conoce la persistencia ni la bandeja")
    fun `KM52V2-09 frontera`() {
        val base = File("src/main/kotlin/com/km")
        assertTrue(base.exists(), "no se encuentra ${base.path} desde ${File(".").absolutePath}")

        // --- 1. `ratchet/` NO IMPORTA nada de la persistencia ni de la bandeja.
        val ratchet = base.resolve("ratchet")
        assertTrue(ratchet.exists(), "el paquete del ratchet tiene que existir")
        val prohibidosPaquete = listOf(
            "com.km.unit",
            "com.km.transmit",
            "com.km.messaging.PendingInbox",
            "PendingInboundEntry",
        )
        val ofensas = mutableListOf<String>()
        for (f in ratchet.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            f.readLines().forEachIndexed { i, linea ->
                for (p in prohibidosPaquete) {
                    if (linea.contains(p)) ofensas += "${f.name}:${i + 1} nombra '$p' -> ${linea.trim()}"
                }
            }
        }
        assertTrue(ofensas.isEmpty(), "el ratchet no puede conocer la persistencia:\n${ofensas.joinToString("\n")}")

        // --- 2. Y `PendingInboundEntry` NO entra en `DoubleRatchetSnapshot`.
        //     Se audita el CODIGO, no el doc: `DoubleRatchetSnapshot` solo
        //     declara material criptografico, y una entrada de bandeja es un
        //     mensaje que todavia no ha pasado por el ratchet.
        val foto = DoubleRatchetSnapshot(
            rootKey = ByteArray(32) { 1 },
            dhSelf = DerivedX25519KeyPair.derive(det(3).privateKey, x25519),
            dhRemote = null,
            sendMessageNumber = 0u,
            previousChainLength = 0u,
            sendChain = SymmetricRatchetSnapshot(
                sendChainKey = ByteArray(32) { 2 }, sendMessageNumber = 0u,
                receiveChainKey = ByteArray(32) { 2 }, receiveMessageNumber = 0u, skipped = emptyMap(),
            ),
            receiveChains = emptyList(),
        )
        val fuente = File("src/main/kotlin/com/km/ratchet/DoubleRatchetSnapshot.kt")
        assertTrue(fuente.exists(), "falta ${fuente.path}")
        val texto = fuente.readText()
        val cuerpo = texto.substringAfter("class DoubleRatchetSnapshot")
            .substringBefore("\n/**")
        assertFalse(
            cuerpo.contains("PendingInbound") || cuerpo.contains("messageId") || cuerpo.contains("wireFrame"),
            "DoubleRatchetSnapshot es material criptografico y nada mas",
        )

        // --- 3. Y el ratchet no habla de entrega, ni en CODIGO EJECUTABLE.
        //     `messageId` SI aparece en `OutboundRecord` y en
        //     `PendingInboundEntry`, que es donde vive de verdad; lo que no puede
        //     aparecer es LOGICA DE DECISION sobre el dentro de `ratchet/`.
        //     El limite de palabra evita "package" y "stack"; el filtro de
        //     comentarios evita que un KDoc que lonombre conta como codigo.
        //
        //     ## POR QUE "pending" NO ESTA EN LA LISTA
        //
        //     Porque el ratchet YA USA esa palabra, y para su propia staged
        //     state: `SymmetricRatchet.pendingReceive` es la recepcion PREPARADA
        //     que `commitReceive` aplica, y `DoubleRatchetSession.pending` es el
        //     `Candidate` de envio. No son entrega: son "aplicar esto si el
        //     paso tiene exito". Prohibir la palabra barra un nombre legitimo y
        //     preexistente de un ratchet que esta congelado y auditado campo a
        //     campo, y hace que un rojo ahi no signifique lo que parece.
        //
        //     Lo que la regla persigue —la bandeja— se persigue por SU NOMBRE:
        //     `PendingInbox` y `PendingInboundEntry` estan prohibidos aqui, y
        //     tambien en la lista de imports del punto 1.
        //
        //     ## POR QUE EL SALTO DE LINEA ES `return@forEachIndexed`
        //
        //     Antes era `continue` DENTRO de `forEachIndexed`, que en Kotlin es
        //     un `continue` NO LOCAL: abortaba el `for (f in ...)` de fuera. La
        //     primera linea de comentario de cada archivo cortaba el recorrido,
        //     y el resultado era que se escaneaban unas pocas lineas del
        //     primero y NADA de los demas. La comprobacion no podia fallar
        //     nunca — `offences = 0` con el ratchet entero delante —, que es
        //     peor que no comprobar: da verde a una frontera que nadie vigila.
        val decision = listOf(
            "messageId", "delivery", "retry", "retransmit", "transport", "inbox",
            "PendingInbox", "PendingInboundEntry",
        )
        val enCodigo = mutableListOf<String>()
        var lineasEscaneadas = 0
        for (f in ratchet.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            f.readLines().forEachIndexed { i, linea ->
                val t = linea.trimStart()
                if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) return@forEachIndexed
                lineasEscaneadas++
                for (token in decision) {
                    if (Regex("(?i)\\b" + Regex.escape(token) + "\\b").containsMatchIn(linea)) {
                        enCodigo += "${f.name}:${i + 1} contiene '$token' -> ${t}"
                    }
                }
            }
        }
        // Y el propio recorrido se audita: un frontier check que no recorre
        // las lineas que dice recorrer no esta comprobando nada, y su verde es
        // indistinguible del de uno que si las recorre. Este numero sale de
        // RECORRER el directorio de verdad, no de una constante.
        val lineasTotales = ratchet.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sumOf { it.readLines().size }
        assertTrue(
            lineasEscaneadas > lineasTotales / 2,
            "el frontier check tiene que recorrer el ratchet entero: escaneo " +
                "$lineasEscaneadas de $lineasTotales lineas",
        )
        assertTrue(enCodigo.isEmpty(), "el ratchet no puede decidir sobre entrega:\n${enCodigo.joinToString("\n")}")

        // --- 4. Y km-core no depende de km-webrtc ni de WebRTC.
        val build = File("build.gradle.kts").readText()
        assertFalse(build.contains("webrtc"), "km-core no puede depender de km-webrtc")
        assertFalse(build.contains("dev.onvoid"), "ni de una libreria de WebRTC (R3-12)")
    }

    // ===================================================================
    // KILOMETRO 10 — outbound singular y el tope estructural
    // ===================================================================

    @Test
    @DisplayName("KM52V2-10 el outbound es singular, opcional, y su unico tope es MAX_CIPHERTEXT_LENGTH")
    fun `KM52V2-10 outbound singular`() {
        val w = Wire()
        val foto = w.bobSession.snapshot()
        val c = codec()

        // --- CERO outbound: una sesion sin envio propio pendiente es legal. ---
        val sinEnvio = c.deserialize(c.serialize(Km52Unit(foto, retencionDe(foto), null)))
        assertNull(sinEnvio.outbound, "sin envio, la unidad lo dice")
        assertEquals(0L, le32(c.serialize(Km52Unit(foto, retencionDe(foto), null)), Km52Spec.OUTBOUND_LEN_OFFSET), "y el bloque mide cero")

        // --- UNO outbound: el caso normal. ---
        val conEnvio = c.deserialize(c.serialize(Km52Unit(foto, retencionDe(foto), registro(det(1).publicKey))))
        assertNotNull(conEnvio.outbound, "con envio, la unidad lo trae")

        // --- E-OUT-01 con el disparador de 3Q.5.3: se RECHAZA por exceder el
        //     maximo ESTRUCTURAL, y el registro NO se sustituye ni se poda. ---
        val enorme = registro(det(1).publicKey, ciphertext = ByteArray(65_536) { 0x7 })
        val e = assertThrows<Km52FormatException> { c.serialize(Km52Unit(foto, retencionDe(foto), enorme)) }
        assertTrue(
            e.message!!.contains("${Km52Spec.MAX_CIPHERTEXT_LENGTH}"),
            "el rechazo cita el maximo estructural del campo length: $e",
        )
        assertTrue(
            e.message!!.contains("rechaza entero", ignoreCase = true),
            "y dice que no se sustituye ni se poda: $e",
        )

        // El limite EXACTO entra: 65 535 es el `length` de dos bytes.
        val justo = registro(det(1).publicKey, ciphertext = ByteArray(65_535) { 0x7 })
        val leido = c.deserialize(c.serialize(Km52Unit(foto, retencionDe(foto), justo)))
        assertEquals(65_535, leido.outbound!!.ciphertext.size, "65 535 entra: es el maximo, no el maximo mas uno")

        // Y NO HAY COTA PROPIA del bloque outbound: ni de cantidad, ni de bytes.
        // La mutacion "MAX_OUTBOUND_RECORDS vuelve a existir" no tendria donde
        // esconderse, y esto lo demuestra mirando la superficie del paquete.
        val tipos = File("src/main/kotlin/com/km/unit/Km52Types.kt").readText()
        assertFalse(
            Regex("""MAX_OUTBOUND_(RECORDS|BUDGET)""").containsMatchIn(tipos),
            "3Q.5.3 elimino las dos cotas de outbound: el techo lo impone MAX_CIPHERTEXT_LENGTH",
        )
        assertFalse(
            Regex("""\boutbound: (List|Array|MutableList)<""").containsMatchIn(tipos),
            "el outbound es SINGULAR: no puede haber una coleccion",
        )
    }

    // ===================================================================
    // KILOMETRO 11 — Corrupcion y nada a medias
    // ===================================================================

    @Test
    @DisplayName("KM52V2-11 ninguna unidad v2 corrupta deja la sesion a medias")
    fun `KM52V2-11 nada a medias`() {
        val w = Wire()
        dosEpochs(w)
        val frames = framesDeEntrada(w)
        val bob = w.bobSession
        val foto = bob.snapshot()
        val sana = codec().serialize(
            Km52Unit(foto, retencionDe(foto), registro(bob.selfDhPublicKey()), frames.map {
                PendingInboundEntry(FrameIdentity.fromWire(it), it)
            }),
        )

        val variantes = listOf<Pair<String, ByteArray>>(
            "magic" to sana.copyOf().also { it[0] = 'X'.code.toByte() },
            "version" to sana.copyOf().also { it[4] = 9 },
            "flags" to sana.copyOf().also { it[5] = 1 },
            "reserved16" to sana.copyOf().also { it[7] = 1 },
            "snapshotLen" to sana.copyOf().also { it[8] = (it[8] + 1).toByte() },
            "outboundLen" to sana.copyOf().also { it[13] = (it[13] + 1).toByte() },
            "ciphertextLen" to sana.copyOf().also { it[16] = (it[16] + 1).toByte() },
            "totalLen" to sana.copyOf().also { it[21] = (it[21] + 1).toByte() },
            "inboundLen" to sana.copyOf().also { it[24] = (it[24] + 1).toByte() },
            "reserved2" to sana.copyOf().also { it[28] = 1 },
            "payload" to sana.copyOf().also { it[Km52Spec.HEADER_LENGTH + 40] = (it[Km52Spec.HEADER_LENGTH + 40] + 1).toByte() },
            "checksum" to sana.copyOf().also { it[sana.size - 1] = (it[sana.size - 1] + 1).toByte() },
            "truncada" to sana.copyOf(sana.size - 3),
            "sobrante" to sana + byteArrayOf(7),
        )
        assertEquals(14, variantes.size, "el guion tiene que cubrir catorce formas de corromper")

        for ((nombre, bytes) in variantes) {
            val control = DoubleRatchetSession.restore(foto, x25519, kdf)
            val referencia = SecureRatchetProtocol(control, protector).encrypt("ctrl".toByteArray())
            var fallo: Throwable? = null
            try {
                bob.restore(codec().deserialize(bytes).snapshot)
            } catch (e: Throwable) {
                fallo = e
            }
            assertNotNull(fallo, "'$nombre' tiene que rechazarse y no restaurar nada")
            assertTrue(
                fallo is Km52FormatException || fallo is RetentionBudgetExceeded || fallo is PendingInboundBudgetExceeded,
                "'$nombre' tiene que fallar con un error tipado del codec, fue $fallo",
            )
            assertEquals(2, bob.receiveChainCount(), "'$nombre' no puede haber tocado las cadenas")
            assertEquals(3u, bob.currentPreviousChainLength(), "'$nombre' no puede haber movido PN")
            assertContentEquals(
                referencia, SecureRatchetProtocol(bob, protector).encrypt("ctrl".toByteArray()),
                "'$nombre' no puede haber movido el estado",
            )
            bob.restore(foto)
        }
    }
}
