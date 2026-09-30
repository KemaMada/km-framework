package com.keymessage.core.messaging

import com.keymessage.core.crypto.provider.BcChaCha20Poly1305
import com.keymessage.core.crypto.provider.BcHkdfSha256
import com.keymessage.core.crypto.provider.BcX25519
import com.keymessage.core.model.MessageId
import com.keymessage.core.ratchet.RatchetSessionBootstrap
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
 * 3Q.5.1b — `DeliveryRecord`: `identidad de frame -> messageId`.
 *
 * Convierte en implementable el paso 2 de KM-0004 §9.4 (reenviar el ACK ante
 * un duplicado), que hasta ahora era imposible: el ratchet rechazaba el
 * frame repetido como `REPLAY_OR_UNKNOWN` sin decir sobre QUE mensaje era.
 *
 * Y lo hace SIN tocar el ratchet, usando la costura de 3Q.5.1a: `(DH, PN, N)`
 * son el AAD, asi que se leen del header sin descifrar nada.
 */
class DeliveryRecordTest {

    private val x25519 = BcX25519()
    private val kdf = BcHkdfSha256()
    private val protector = SecureFrameProtector(BcChaCha20Poly1305(), kdf)
    private val x3dh = X3dh(x25519, kdf)
    private val random = SecureRandom()

    private fun id(n: Int): MessageId =
        MessageId(UUID.fromString("018f3a5b-7c8d-4e9f-8a0b-1c2d3e4f5a%02x".format(n)))

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

    private fun wire(protocol: SecureRatchetProtocol, messageId: MessageId, payload: String): ByteArray =
        protocol.encrypt(MessageEnvelopeCodec.encode(MessageEnvelope.userMessage(messageId, payload.toByteArray())))

    // ===================================================================
    // FrameIdentity
    // ===================================================================

    @Test
    @DisplayName("DR-01 la identidad son 40 bytes: DH(32) || PN(4) || N(4)")
    fun `DR-01 40 bytes`() {
        assertEquals(40, FrameIdentity.LENGTH)
        val dh = ByteArray(32) { it.toByte() }
        val id = FrameIdentity.of(dh, 7u, 9u)
        assertEquals(40, id.bytes.size)
        assertContentEquals(dh, id.dhPublicKey)
        assertEquals(7u, id.previousChainLength)
        assertEquals(9u, id.messageNumber)
    }

    @Test
    @DisplayName("DR-02 la identidad se lee del wire SIN descifrar y sin el ratchet")
    fun `DR-02 legible del header`() {
        val p = Pair()
        val w = wire(p.initiator, id(1), "hola")

        // Solo hacen falta los primeros 44 bytes del frame.
        val id = FrameIdentity.fromWire(w)
        assertEquals(40, id.bytes.size)

        // Y funciona incluso sobre un frame cuyo CYTEXT este corrupto: la
        // identidad es una propiedad del header, no del contenido.
        val corrupto = w.copyOf()
        for (i in SecureFrameSpec.CIPHERTEXT_OFFSET until corrupto.size) corrupto[i] = 0x00
        assertEquals(id, FrameIdentity.fromWire(corrupto), "el header es legible aunque el contenido no")

        // Y no necesita ningun material criptografico: ni claves, ni AEAD, ni
        // el ratchet. Solo offsets.
        assertEquals(id, FrameIdentity.fromWire(w))
    }

    @Test
    @DisplayName("DR-03 la identidad compara por CONTENIDO, no por referencia")
    fun `DR-03 igualdad por contenido`() {
        // Si comparara por referencia, el registro nunca encontraria un
        // duplicado y el fallo seria silencioso.
        val dh = ByteArray(32) { it.toByte() }
        val a = FrameIdentity.of(dh, 1u, 2u)
        val b = FrameIdentity.of(dh, 1u, 2u)
        val c = FrameIdentity.of(dh, 1u, 3u)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    @DisplayName("DR-04 un frame con DH, PN o N distintos es un frame distinto")
    fun `DR-04 identidades distintas`() {
        val dh = ByteArray(32) { it.toByte() }
        val otro = dh.copyOf().also { it[0] = 9 }
        assertNotEquals(FrameIdentity.of(dh, 1u, 1u), FrameIdentity.of(otro, 1u, 1u))
        assertNotEquals(FrameIdentity.of(dh, 1u, 1u), FrameIdentity.of(dh, 2u, 1u))
        assertNotEquals(FrameIdentity.of(dh, 1u, 1u), FrameIdentity.of(dh, 1u, 2u))
    }

    @Test
    @DisplayName("DR-05 un wire demasiado corto se rechaza sin lanzar excepcion rara")
    fun `DR-05 wire corto`() {
        assertThrows<IllegalArgumentException> { FrameIdentity.fromWire(ByteArray(10)) }
        assertThrows<IllegalArgumentException> { FrameIdentity.fromWire(ByteArray(0)) }
    }

    // ===================================================================
    // El registro
    // ===================================================================

    @Test
    @DisplayName("DR-06 el registro asocia identidad de frame con messageId")
    fun `DR-06 asocia`() {
        val t = DeliveryRecordTable()
        val f = FrameIdentity.of(ByteArray(32) { 1 }, 0u, 0u)
        assertNull(t.lookup(f))

        t.record(f, id(42))
        assertEquals(id(42), t.lookup(f), "frame -> mensaje, en esa direccion")
        assertEquals(1, t.size())
    }

    @Test
    @DisplayName("DR-07 la tabla es ACOTADA: descarta la menos usada")
    fun `DR-07 acotada`() {
        val t = DeliveryRecordTable(capacity = 3)
        val ids = (1..5).map { FrameIdentity.of(ByteArray(32) { it.toByte() }, 0u, it.toUInt()) }
        ids.forEach { t.record(it, id(42)) }

        assertEquals(3, t.size(), "no puede crecer sin limite")
        // Las tres MAS recientes sobreviven; las dos primeras se descartaron.
        assertNotNull(t.lookup(ids[4]), "la mas reciente sigue ahi")
        assertNotNull(t.lookup(ids[3]))
        assertNotNull(t.lookup(ids[2]))
        assertNull(t.lookup(ids[0]), "la mas antigua se descarta")
        assertNull(t.lookup(ids[1]))
    }

    @Test
    @DisplayName("DR-08 perder el registro DEGRADA con seguridad, no rompe nada")
    fun `DR-08 perder el registro degrada`() {
        // El registro no es la defensa criptografica: lo es el ratchet. Si el
        // registro se pierde, un duplicado llega al ratchet y lo rechaza, que
        // es la conducta anterior. Nunca se abre una ruta de texto plano.
        val p = Pair()
        val w = wire(p.initiator, id(42), "hola")
        val receptor = SecureMessageReceiver(p.responder, DeliveryRecordTable(capacity = 1))

        assertTrue(receptor.receive(w) is SecureMessageReceiver.Result.Fresh)
        // El registro expulsa la entrada al crecer.
        val segunda = wire(p.initiator, id(43), "otro")
        assertTrue(receptor.receive(segunda) is SecureMessageReceiver.Result.Fresh)

        // El duplicado del PRIMERO ya no esta en el registro: cae al ratchet,
        // que lo rechaza. No se entrega dos veces.
        val repetido = receptor.receive(w)
        assertTrue(
            repetido is SecureMessageReceiver.Result.Rejected,
            "sin registro, el ratchet sigue impidiendo la doble aplicacion: $repetido",
        )
    }

    // ===================================================================
    // La composicion: el receptor de mensajes
    // ===================================================================

    @Test
    @DisplayName("DR-09 un frame NUEVO se entrega y se registra")
    fun `DR-09 frame nuevo`() {
        val p = Pair()
        val receptor = SecureMessageReceiver(p.responder)
        val w = wire(p.initiator, id(42), "hola")

        val r = receptor.receive(w) as SecureMessageReceiver.Result.Fresh

        assertEquals(id(42), r.envelope.messageId)
        assertContentEquals("hola".toByteArray(), r.envelope.payload)
        assertEquals(1, receptor.recordCount(), "se registro tras la entrega")
        assertEquals(id(42), receptor.knownMessageId(w))
    }

    @Test
    @DisplayName("DR-10 un DUPLICADO devuelve el messageId ORIGINAL y NO toca el ratchet")
    fun `DR-10 duplicado no toca el ratchet`() {
        // LA PROPIEDAD CENTRAL. Si el duplicado avanzara el ratchet, la sesion
        // se desincronizaria en silencio.
        val p = Pair()
        val receptor = SecureMessageReceiver(p.responder)
        val w = wire(p.initiator, id(42), "hola")

        assertTrue(receptor.receive(w) is SecureMessageReceiver.Result.Fresh)
        val huellaTrasPrimero = p.responder.stateFingerprint()

        // Reenvio por otro medio: mismo frame, otro transporte.
        val repetido = receptor.receive(w)

        val dup = repetido as SecureMessageReceiver.Result.Duplicate
        assertEquals(id(42), dup.messageId, "§9.4: el messageId se conserva para reenviar el ACK")
        assertContentEquals(
            huellaTrasPrimero, p.responder.stateFingerprint(),
            "el duplicado NO debe mutar el estado del ratchet",
        )
    }

    @Test
    @DisplayName("DR-11 el duplicado NO se entrega a la aplicacion")
    fun `DR-11 no se entrega dos veces`() {
        val p = Pair()
        val receptor = SecureMessageReceiver(p.responder)
        val w = wire(p.initiator, id(42), "hola")

        val entregados = mutableListOf<SecureMessageReceiver.Result>()
        repeat(4) { entregados.add(receptor.receive(w)) }

        val frescos = entregados.count { it is SecureMessageReceiver.Result.Fresh }
        val duplicados = entregados.count { it is SecureMessageReceiver.Result.Duplicate }
        assertEquals(1, frescos, "cuatro entregas del mismo frame, un solo Fresh")
        assertEquals(3, duplicados, "las otras tres son duplicados recognized")
    }

    @Test
    @DisplayName("DR-12 lo que NO se registra: frame invalido, AEAD rota, sobre ilegible")
    fun `DR-12 no se registra lo invalido`() {
        val p = Pair()
        val receptor = SecureMessageReceiver(p.responder)
        val w = wire(p.initiator, id(42), "hola")

        // a) Ciphertext manipulado: la identidad SI se puede leer, pero el
        //    ratchet no lo aceptara y NO se registra nada.
        val rotoAEAD = w.copyOf()
        rotoAEAD[rotoAEAD.size - 1] = (rotoAEAD[rotoAEAD.size - 1] + 1).toByte()
        val r1 = receptor.receive(rotoAEAD)
        assertTrue(r1 is SecureMessageReceiver.Result.Rejected, "AEAD rota no se registra: $r1")
        assertEquals(0, receptor.recordCount(), "ni una entrada")

        // b) Basura sin header utilizable.
        val basura = ByteArray(64) { 0x7F }
        assertTrue(receptor.receive(basura) is SecureMessageReceiver.Result.Rejected)
        assertEquals(0, receptor.recordCount())

        // c) El bueno se registra normalmente: el estado no quedo envenenado.
        assertTrue(receptor.receive(w) is SecureMessageReceiver.Result.Fresh)
        assertEquals(1, receptor.recordCount())
    }

    @Test
    @DisplayName("DR-13 un mensaje nuevo con el mismo payload NO es duplicado")
    fun `DR-13 mensajes distintos`() {
        val p = Pair()
        val receptor = SecureMessageReceiver(p.responder)

        val a = wire(p.initiator, id(1), "hola")
        val b = wire(p.initiator, id(2), "hola")   // mismo texto, otro mensajeId

        assertTrue(receptor.receive(a) is SecureMessageReceiver.Result.Fresh)
        assertTrue(receptor.receive(b) is SecureMessageReceiver.Result.Fresh, "N distinto, mensaje distinto")
        assertEquals(2, receptor.recordCount())
    }

    @Test
    @DisplayName("DR-14 el ratchet sigue siendo la UNICA puerta al texto plano")
    fun `DR-14 el ratchet es la unica puerta`() {
        // La capa de entrega no abre una segunda ruta: un frame desconocido
        // SIEMPRE pasa por el ratchet, y si el ratchet dice que no, no hay
        // plaintext aunque la identidad sea "conocida".
        val p = Pair()
        val receptor = SecureMessageReceiver(p.responder)
        val w = wire(p.initiator, id(42), "hola")
        receptor.receive(w)

        // Se falsea el registro: se dice que esta identidad es conocida.
        val identidad = FrameIdentity.fromWire(w)
        // El frame REAL si esta registrado; uno con N distinto, no.
        val falso = FrameIdentity.of(identidad.dhPublicKey, identidad.previousChainLength, 99u)
        assertEquals(id(42), receptor.knownMessageId(w), "el frame real esta registrado")
        assertNull(
            SecureMessageReceiver(p.responder).knownMessageId(w),
            "un receptor SIN registro no reconoce nada: la tabla es por sesion",
        )

        // Y un frame cuya CABECERA miente no puede colarse: la identidad no
        // basta para abrir nada, el ratchet sigue verificando el contenido.
        // Aqui se reetiqueta N a 99, dejando el ciphertext intacto.
        val suplantado = w.copyOf().also {
            it[SecureFrameSpec.MESSAGE_NUMBER_OFFSET + 3] = 99
        }
        assertEquals(falso, FrameIdentity.fromWire(suplantado), "la identidad cambia al reetiquetar N")
        val r = receptor.receive(suplantado)
        assertTrue(
            r is SecureMessageReceiver.Result.Rejected,
            "manipular el header no abre una ruta: $r",
        )
    }

    @Test
    @DisplayName("DR-15 el registro NO guarda semantica de otras capas")
    fun `DR-15 solo frame y messageId`() {
        // `applicationDelivered`, `ackSent`, `timestamp`, `transport` y
        // `retryCount` pertenecen a otras capas. Su ausencia es intencional, no
        // accidental.
        //
        // La auditoria es del CODIGO FUENTE y no por reflexion: Kotlin manglea
        // los nombres de metodo (`lookup-oh8fPk4`), de modo que buscar por
        // nombre en un `Class` es fragil y daria un falso "ausente".
        val fuente = java.io.File("src/main/kotlin/com/keymessage/core/messaging/DeliveryRecord.kt")
        assertTrue(fuente.exists(), "debe existir DeliveryRecord.kt")
        val codigo = fuente.readLines()
            .filterNot { t -> val s = t.trimStart(); s.startsWith("*") || s.startsWith("//") || s.startsWith("/*") }

        // El unico estado del registro es el mapa identidad -> messageId.
        val campos = codigo
            .filter { it.trimStart().startsWith("private val ") }
            .map { it.trim().removePrefix("private val ").split(Regex("[\\s:=]")).first() }
        assertEquals(
            listOf("records"),
            campos,
            "el registro solo puede tener su mapa; nada mas -> $campos",
        )

        for (prohibido in listOf("ackSent", "delivered", "timestamp", "transport", "retry", "attempt", "status", "lastSeen")) {
            assertTrue(
                codigo.none { Regex("""(val|var)\s+$prohibido""").containsMatchIn(it) },
                "el registro no debe llevar estado de '$prohibido'",
            )
        }

        // Y la API publica es solo la asociacion y su control de tamano.
        assertTrue(codigo.any { it.contains("fun lookup(") }, "expone lookup")
        assertTrue(codigo.any { it.contains("fun record(") }, "expone record")
    }

    @Test
    @DisplayName("DR-16 capacidad no positiva es un error de configuracion")
    fun `DR-16 capacidad invalida`() {
        assertThrows<IllegalArgumentException> { DeliveryRecordTable(capacity = 0) }
        assertThrows<IllegalArgumentException> { DeliveryRecordTable(capacity = -1) }
    }
}
