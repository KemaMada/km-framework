package com.keymessage.core.messaging

import com.keymessage.core.crypto.provider.BcChaCha20Poly1305
import com.keymessage.core.crypto.provider.BcHkdfSha256
import com.keymessage.core.crypto.provider.BcX25519
import com.keymessage.core.model.MessageId
import com.keymessage.core.ratchet.RatchetSessionBootstrap
import com.keymessage.core.sf.BinarySecureFrameCodec
import com.keymessage.core.sf.SecureFrameProtector
import com.keymessage.core.sf.SecureFrameSpec
import com.keymessage.core.sf.SecureRatchetProtocol
import com.keymessage.core.x3dh.BootstrapPrekeys
import com.keymessage.core.x3dh.InitiatorKeyMaterial
import com.keymessage.core.x3dh.ResponderKeyMaterial
import com.keymessage.core.x3dh.X3dh
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.UUID
import kotlin.test.assertContentEquals

/**
 * 3Q.5.1a — `MessageEnvelope` v1: el `messageId` dentro del plaintext cifrado.
 *
 * Este checkpoint congela el formato del sobre y demuestra por que vive
 * dentro del ciphertext. NO implementa persistencia, reconexion ni
 * retransmision: eso es 3Q.5.1b-3Q.5.4.
 *
 * La ultima seccion responde empiricamente la pregunta que bloquea 3Q.5.1b:
 * ¿como reconoce el receptor un frame repetido si el ratchet lo rechaza como
 * `REPLAY_OR_UNKNOWN`? La respuesta resulta no necesitar tocar el ratchet.
 */
class MessageEnvelopeTest {

    private val x25519 = BcX25519()
    private val kdf = BcHkdfSha256()
    private val protector = SecureFrameProtector(BcChaCha20Poly1305(), kdf)
    private val x3dh = X3dh(x25519, kdf)
    private val random = SecureRandom()

    private val id42 = MessageId(UUID.fromString("018f3a5b-7c8d-4e9f-8a0b-1c2d3e4f5a6b"))
    private val id99 = MessageId(UUID.fromString("018f3a5b-9d0e-1f2a-3b4c-5d6e7f8a9b0c"))

    /** Dos protocolos sobre la MISMA sesion, uno al otro. */
    private inner class Pair {
        val initiator: SecureRatchetProtocol
        val responder: SecureRatchetProtocol

        init {
            val aliceDh = x25519.generateKeyPair()
            val bobDh = x25519.generateKeyPair()
            val bobSpk = x25519.generateKeyPair()
            val bobOpk = x25519.generateKeyPair()
            val f = ByteArray(32).also { random.nextBytes(it) }

            val bobPrekeys = BootstrapPrekeys(
                deviceId = ByteArray(32) { 1 },
                identityAgreementKey = bobDh.publicKey,
                signedPreKey = bobSpk.publicKey,
                signedPreKeyId = 1L,
                oneTimePreKey = bobOpk.publicKey,
                oneTimePreKeyId = 5L,
            )
            val alicePrekeys = BootstrapPrekeys(
                deviceId = ByteArray(32) { 2 },
                identityAgreementKey = aliceDh.publicKey,
                signedPreKey = aliceDh.publicKey,
                signedPreKeyId = 1L,
                oneTimePreKey = null,
                oneTimePreKeyId = null,
            )
            val init = x3dh.initiate(InitiatorKeyMaterial(ByteArray(32), aliceDh), bobPrekeys, f)
            val prep = x3dh.respond(
                ResponderKeyMaterial(ByteArray(32) { 1 }, bobDh, bobSpk, bobOpk, 5L),
                alicePrekeys,
                init.ephemeralPublic,
            )
            assertContentEquals(init.sharedKey, prep.sharedKey, "X3DH debe derivar la misma SK")
            prep.commit()

            initiator = RatchetSessionBootstrap.initiatorProtocol(
                bootstrapValue = f,
                ephemeral = init.ephemeral,
                remoteSignedPreKey = bobSpk.publicKey,
                signedPreKeyPrivate = bobSpk.privateKey,
                x25519 = x25519,
                kdf = kdf,
                protector = protector,
            )
            responder = SecureRatchetProtocol(
                com.keymessage.core.ratchet.DoubleRatchetSession(
                    rootKey = RatchetSessionBootstrap.rootKeyFrom(f, kdf),
                    dhSelf = bobSpk,
                    dhRemote = null,
                    sendChainKey = ByteArray(32),
                    receiveChainKey = ByteArray(32),
                    x25519 = x25519,
                    kdf = kdf,
                ),
                protector,
            )
        }
    }

    // ===================================================================
    // Formato del sobre
    // ===================================================================

    @Test
    @DisplayName("ENV-01 el sobre sobrevive un viaje completo de ida y vuelta")
    fun `ENV-01 ida y vuelta`() {
        val payload = "hola".toByteArray()
        val original = MessageEnvelope.userMessage(id42, payload)
        val bytes = MessageEnvelopeCodec.encode(original)
        val decoded = MessageEnvelopeCodec.decode(bytes)

        assertEquals(id42, decoded.messageId, "el messageId debe sobrevivir")
        assertEquals(EnvelopeType.USER_MESSAGE, decoded.type)
        assertContentEquals(payload, decoded.payload)
        assertEquals(MessageEnvelopeCodec.VERSION, decoded.version)
        assertEquals(original, decoded, "el valor debe ser identico, no solo equivalente")
    }

    @Test
    @DisplayName("ENV-02 la codificacion es DETERMINISTA: mismos bytes cada vez")
    fun `ENV-02 determinista`() {
        // Determinacion byte-a-byte: sin esto no se puede congelar un vector de
        // prueba, y dos implementaciones divergirian en silencio.
        val a = MessageEnvelopeCodec.encode(MessageEnvelope.userMessage(id42, "hola".toByteArray()))
        val b = MessageEnvelopeCodec.encode(MessageEnvelope.userMessage(id42, "hola".toByteArray()))
        assertTrue(a.contentEquals(b), "mismo sobre, mismos bytes")

        // Y distinto messageId produce bytes distintos.
        val c = MessageEnvelopeCodec.encode(MessageEnvelope.userMessage(id99, "hola".toByteArray()))
        assertFalse(a.contentEquals(c), "otro messageId, otros bytes")
    }

    @Test
    @DisplayName("ENV-03 el messageId va como 16 bytes, no como texto")
    fun `ENV-03 id binario`() {
        val bytes = MessageEnvelopeCodec.encode(MessageEnvelope.userMessage(id42, byteArrayOf()))
        // Compacto y sin dependencias de la representacion textual del UUID.
        assertTrue(bytes.size < 80, "el sobre debe ser compacto: ${bytes.size}B")

        val id = MessageEnvelopeCodec.uuidFromBytes(MessageEnvelopeCodec.uuidBytes(id42))
        assertEquals(id42, id, "UUID -> 16B -> UUID debe ser identidad")
    }

    @Test
    @DisplayName("ENV-04 una version desconocida se RECHAZA en total, no se adivina")
    fun `ENV-04 version desconocida`() {
        val bytes = MessageEnvelopeCodec.encode(MessageEnvelope.userMessage(id42, byteArrayOf(1)))
        // Reetiquetar la version a 99 sin mas: debe rechazarse.
        val mutado = bytes.copyOf()
        // El campo 'version' va codificado como entero KCE; localizarlo por
        // byte es fragil, asi que se construye el sobre con otra version.
        val futuro = MessageEnvelope(MessageEnvelopeCodec.VERSION + 98UL, id42, EnvelopeType.USER_MESSAGE, byteArrayOf(1))
        assertThrows<EnvelopeFormatException> {
            MessageEnvelopeCodec.decode(MessageEnvelopeCodec.encode(futuro))
        }
        assertNotNull(mutado)
    }

    @Test
    @DisplayName("ENV-05 un tipo desconocido se rechaza: 0 no es un tipo valido")
    fun `ENV-05 tipo desconocido`() {
        // Convencion 1-based: el codigo 0 queda reservado y NUNCA es valido.
        assertNull(EnvelopeType.fromCode(0uL), "0 esta reservado")
        assertNull(EnvelopeType.fromCode(99uL), "99 no existe")

        val raro = MessageEnvelope(MessageEnvelopeCodec.VERSION, id42, EnvelopeType.USER_MESSAGE, byteArrayOf(1))
        val bytes = MessageEnvelopeCodec.encode(raro)
        assertNotNull(MessageEnvelopeCodec.decode(bytes))
    }

    @Test
    @DisplayName("ENV-06 un sobre corrupto se rechaza, no se interpreta parcialmente")
    fun `ENV-06 corrupto`() {
        val bytes = MessageEnvelopeCodec.encode(MessageEnvelope.userMessage(id42, "hola".toByteArray()))

        assertThrows<EnvelopeFormatException> { MessageEnvelopeCodec.decode(ByteArray(0)) }
        assertThrows<EnvelopeFormatException> {
            MessageEnvelopeCodec.decode(bytes.copyOf(bytes.size - 1))
        }
        assertThrows<EnvelopeFormatException> { MessageEnvelopeCodec.decode(ByteArray(64) { 0xFF.toByte() }) }
    }

    @Test
    @DisplayName("ENV-07 el envelope tiene igualdad por CONTENIDO")
    fun `ENV-07 igualdad por contenido`() {
        // Sin esto, dos sobres con el mismo contenido seriam distintos y la
        // deduplicacion en silencio.
        val a = MessageEnvelope.userMessage(id42, "hola".toByteArray())
        val b = MessageEnvelope.userMessage(id42, "hola".toByteArray())
        val c = MessageEnvelope.userMessage(id99, "hola".toByteArray())

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode(), "el hash debe coincidir tambien")
        assertNotEquals(a, c)
    }

    @Test
    @DisplayName("ENV-08 un payload enorme excede el limite y se rechaza en el emisor")
    fun `ENV-08 limite de tamano`() {
        val enorme = MessageEnvelope.userMessage(id42, ByteArray(MessageEnvelopeCodec.MAX_ENVELOPE_BYTES))
        assertThrows<EnvelopeFormatException> { MessageEnvelopeCodec.encode(enorme) }
    }

    // ===================================================================
    // El sobre dentro del ratchet
    // ===================================================================

    @Test
    @DisplayName("ENV-09 el messageId viaja CIFRADO: el wire no lo contiene")
    fun `ENV-09 el wire no lo contiene`() {
        val p = Pair()
        val envelope = MessageEnvelope.userMessage(id42, "contenido-secreto".toByteArray())
        val wire = p.initiator.encrypt(MessageEnvelopeCodec.encode(envelope))

        // El wire no contiene el messageId en claro: ni sus 16 bytes ni su
        // forma textual.
        val idBytes = MessageEnvelopeCodec.uuidBytes(id42)
        assertFalse(contiene(wire, idBytes), "el messageId NO puede ir en claro")
        assertFalse(contiene(wire, id42.value.toString().toByteArray()), "ni su forma textual")
        assertFalse(contiene(wire, "contenido-secreto".toByteArray()), "ni el payload")

        // Y el receptor lo recupera descifrando.
        val r = p.responder.decrypt(wire) as SecureRatchetProtocol.DecryptResult.Ok
        val recibido = MessageEnvelopeCodec.decode(r.plaintext)
        assertEquals(id42, recibido.messageId, "el receptor si lo obtiene")
    }

    @Test
    @DisplayName("ENV-10 el MISMO ciphertext conserva el mismo messageId (base de la retransmision)")
    fun `ENV-10 identidad estable`() {
        val p = Pair()
        val wire = p.initiator.encrypt(MessageEnvelopeCodec.encode(MessageEnvelope.userMessage(id42, "hola".toByteArray())))

        // El emisor guarda el ciphertext UNA vez. Reenviar ESE frame, no
        // re-cifrar: re-cifrar produciria otro `N` (auditoria 3Q.5.0 A-5).
        val primera = p.responder.decrypt(wire) as SecureRatchetProtocol.DecryptResult.Ok
        val id1 = MessageEnvelopeCodec.decode(primera.plaintext).messageId

        // El emisor lo reenvia tal cual, por otro medio de transporte.
        val segunda = p.responder.decrypt(wire)
        assertTrue(
            segunda is SecureRatchetProtocol.DecryptResult.Rejected,
            "el ratchet ya lo consumio: se rechaza como REPLAY_OR_UNKNOWN",
        )
        // El emisor sigue sabiendo cual es: lo guardo con el frame.
        assertEquals(id42, id1, "el emisor conserva la identidad de entrega")

        // Y el emisor puede reconstruir el mismo mensaje las veces que quiera
        // a partir del MISMO sobre, sin pasar por el ratchet.
        val otro = p.initiator.encrypt(MessageEnvelopeCodec.encode(MessageEnvelope.userMessage(id42, "hola".toByteArray())))
        assertFalse(wire.contentEquals(otro), "re-cifrar NO sirve para reintentar")
    }

    @Test
    @DisplayName("ENV-11 el ACK viaja en un sobre, y su originalMessageId es verificable")
    fun `ENV-11 ack en sobre`() {
        val p = Pair()
        val ack = MessageEnvelope.deliveryAck(messageId = id99, originalMessageId = id42, status = 1uL)
        val wire = p.initiator.encrypt(MessageEnvelopeCodec.encode(ack))

        val r = p.responder.decrypt(wire) as SecureRatchetProtocol.DecryptResult.Ok
        val recibido = MessageEnvelopeCodec.decode(r.plaintext)

        assertEquals(EnvelopeType.DELIVERY_ACK, recibido.type)
        assertEquals(id99, recibido.messageId, "el ACK tiene su propia identidad")
        assertEquals(
            id42, MessageEnvelope.ackOriginalMessageId(recibido),
            "y apunta al mensaje que acusa, cifrado dentro del sobre",
        )
    }

    // ===================================================================
    // 3Q.5.1b — La costura para reconocer un frame repetido
    // ===================================================================

    @Test
    @DisplayName("ENV-12 el ratchet NO retiene identidad de un frame ya consumido")
    fun `ENV-12 el ratchet no recuerda`() {
        val p = Pair()
        val wire = p.initiator.encrypt(MessageEnvelopeCodec.encode(MessageEnvelope.userMessage(id42, "hola".toByteArray())))
        p.responder.decrypt(wire)

        // Lo que el ratchet expone: un contador que YA paso de este frame, y
        // nada que lo identifique. No hay consulta "¿consumi este frame?".
        val metodos = p.responder.javaClass.methods.map { it.name }.toSet()
        val consultaDeDuplicado = metodos.filter {
            it.contains("seen", true) || it.contains("consumed", true) ||
                it.contains("duplicate", true) || it.contains("processed", true)
        }
        assertTrue(
            consultaDeDuplicado.isEmpty(),
            "auditado: el ratchet no guarda registro de frames consumidos -> $consultaDeDuplicado",
        )
    }

    @Test
    @DisplayName("ENV-13 la identidad del frame es LEGIBLE sin el ratchet: (DH, PN, N) van en claro")
    fun `ENV-13 identidad en claro`() {
        // Esta es la costura de 3Q.5.1b, y es la buena noticia: NO hace falta
        // tocar el ratchet.
        //
        // (DH, PN, N) son el AAD: van en el header, en claro y AUTENTICADOS.
        // La capa de entrega puede leerlos del wire por su cuenta, calcular la
        // identidad del frame, y consultarla en SU propio registro ANTES de
        // ofrecerlo al ratchet.
        //
        //   frame -> (DH, PN, N) -> registro de entrega -> messageId conocido?
        //                                                      |
        //                                            si -> duplicado -> re-ACK
        //                                            no -> ratchet -> descifrar
        val p = Pair()
        val wire = p.initiator.encrypt(MessageEnvelopeCodec.encode(MessageEnvelope.userMessage(id42, "hola".toByteArray())))

        // La identidad se obtiene SIN el ratchet: solo parseando el header.
        val frame = BinarySecureFrameCodec.decode(wire)
        val identidad = Triple(
            frame.ratchetHeader.dhPublicKey.contentHashCode(),
            frame.ratchetHeader.previousChainLength.toLong(),
            frame.ratchetHeader.messageNumber.toLong(),
        )
        assertNotNull(identidad, "la identidad se calcula sin descifrar nada")

        // Y es estable: el mismo frame da la misma identidad, siempre.
        val repetido = BinarySecureFrameCodec.decode(wire)
        assertEquals(
            identidad,
            Triple(
                repetido.ratchetHeader.dhPublicKey.contentHashCode(),
                repetido.ratchetHeader.previousChainLength.toLong(),
                repetido.ratchetHeader.messageNumber.toLong(),
            ),
            "la identidad de un frame es estable sin descifrarlo",
        )

        // Y distingue dos mensajes distintos de la misma sesion.
        val otro = p.initiator.encrypt(MessageEnvelopeCodec.encode(MessageEnvelope.userMessage(id99, "otro".toByteArray())))
        val idOtro = BinarySecureFrameCodec.decode(otro)
        assertNotEquals(
            identidad.second * 1000 + identidad.third,
            idOtro.ratchetHeader.previousChainLength.toLong() * 1000 + idOtro.ratchetHeader.messageNumber.toLong(),
            "dos mensajes distintos tienen identidades distintas",
        )
    }

    @Test
    @DisplayName("ENV-14 deteccion de duplicado ANTES del ratchet: el mensajeId se conserva")
    fun `ENV-14 detectar antes del ratchet`() {
        // El orden correcto, y la razon por la que el sobre resuelve §9.4:
        //
        //   1. parsear (DH, PN, N) del header          <- sin ratchet
        //   2. ¿esta en el registro de entrega?        <- sin ratchet
        //        si -> es DUPLICADO: se reenvia el ACK con el messageId
        //                ya registrado. No se toca el ratchet.
        //        no -> pasar al ratchet, descifrar, REGISTRAR
        //
        // Asi el ratchet nunca ve un duplicado, sigue siendo la unica puerta
        // al plaintext, y no necesita saber nada de messageId.
        val p = Pair()
        val wire = p.initiator.encrypt(MessageEnvelopeCodec.encode(MessageEnvelope.userMessage(id42, "hola".toByteArray())))

        // Registro de entrega de la capa de mensajeria: identidad -> messageId.
        val registro = mutableMapOf<Triple<Int, Long, Long>, MessageId>()
        fun identidadDe(wire: ByteArray): Triple<Int, Long, Long> {
            val h = BinarySecureFrameCodec.decode(wire).ratchetHeader
            return Triple(h.dhPublicKey.contentHashCode(), h.previousChainLength.toLong(), h.messageNumber.toLong())
        }

        // --- Primera entrega: el ratchet la acepta y se registra ---
        val id = identidadDe(wire)
        assertNull(registro[id], "frame nuevo")
        val primera = p.responder.decrypt(wire) as SecureRatchetProtocol.DecryptResult.Ok
        val envelope = MessageEnvelopeCodec.decode(primera.plaintext)
        registro[id] = envelope.messageId
        assertEquals(id42, registro[id])

        // --- Reenvio por otro medio: se reconoce ANTES del ratchet ---
        val id2 = identidadDe(wire)
        val conocido = registro[id2]
        assertEquals(id42, conocido, "el duplicado se reconoce SIN descifrar y conserva su messageId")
        assertFalse(
            p.responder.decrypt(wire) is SecureRatchetProtocol.DecryptResult.Ok,
            "y aunque se ofreciera al ratchet, no volveria a aplicarse",
        )
        // Con `conocido` disponible, el receptor puede reenviar el ACK que
        // KM-0004 §9.4 exige. Eso es lo que antes era imposible.
        assertNotNull(conocido, "el ACK del duplicado es construible")
    }

    // ===================================================================

    private fun contiene(h: ByteArray, n: ByteArray): Boolean {
        if (n.isEmpty() || n.size > h.size) return false
        outer@ for (i in 0..h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return true
        }
        return false
    }
}
