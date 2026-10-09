package com.km.desktop

import com.km.api.KeyMessageCoreImpl
import com.km.codec.JsonDecoder
import com.km.codec.JsonEncoder
import com.km.crypto.Ed25519Impl
import com.km.crypto.KeyPair
import com.km.identity.KmIds
import com.km.model.Ack
import com.km.model.AckStatus
import com.km.model.IdentityId
import com.km.model.MessageState
import com.km.protocol.ConnectionState
import com.km.protocol.Transport
import com.km.storage.DuplicateStore
import com.km.storage.InMemoryDuplicateStore
import com.km.storage.InMemoryMessageStore
import com.km.storage.InMemoryOfflineQueue
import com.km.storage.MessageStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Base64
import java.util.UUID

/**
 * El camino completo del escritorio: `PeerKeyDirectory` -> `KeyMessageCoreImpl`
 * -> `AckManagerImpl`, con el `RelayDataCodec` de por medio.
 *
 * QUE PRUEBA Y POR QUE ESTE TEST ESTA EN `km-desktop` Y NO EN `km-core`
 *
 * Que el `ACK` que llega por el cable se AUTENTICA y se PROCESA, y con eso el
 * emisor ve confirmarse su mensaje. `km-core` ya tiene su parte cubierta
 * (`AckCorrelationTest`); lo que aqui se comprueba es el cable entero: el codec
 * del dialecto del rele, y el resolver que construye `DesktopMain`.
 *
 * LOS `ACK` SON REALES, NO FABRICADOS
 *
 * `AckTranscript` es `internal` de `km-core` a proposito: el formato de lo que se
 * firma no se replica en un consumidor. Fabricar aqui un `ACK` "valido"
 * reconstruyendo la concatenacion a mano seria exactamente el duplicado que esa
 * visibilidad interna evita, y el test pasaria por un camino que el producto no
 * tiene. Por eso el `ACK` lo firma el `ReceiverImpl` de Bob de verdad, y este
 * test solo elige que clave tiene Alice, que bytes viajan y que se espera.
 *
 * SIN LA DIRECCION, NADA DE ESTO FUNCIONA
 *
 * `AckManagerImpl` con el resolver por defecto `{ null }` devuelve `success` sin
 * verificar y sin procesar. Es el fallo D3, y no se manifiesta como un error: el
 * `ACK` llega, no se queja nadie, y el emisor se queda esperando. Por eso esta
 * clase tiene el caso sin clave, y espera `SENT`.
 *
 * CADA CASO POSITIVO TIENE SU INESPERADO
 *
 * `confirma`, `con la clave de otro NO`, `con la firma alterada NO`, `sin clave
 * NO`. Un solo caso positivo no distinguiria "autentica" de "procesa todo".
 */
class DesktopAckAuthenticationTest {

    private val codec = RelayDataCodec()
    private val decoder = JsonDecoder()
    private val encoder = JsonEncoder()

    /** `identityId = SHA-256("KM-ID-IDENTITY" || clave)`, la derivacion de `km-core`. */
    private fun identityIdOf(publicKey: ByteArray): IdentityId =
        IdentityId(KmIds.identityId(publicKey).joinToString("") { "%02x".format(it) })

    // ------------------------------------------------------------------
    // Transporte de recuento: dos canales separados
    // ------------------------------------------------------------------

    private class RecordingTransport : Transport {
        val messages = mutableListOf<ByteArray>()
        val acks = mutableListOf<ByteArray>()
        var online = true

        private var messageHandler: ((ByteArray) -> Unit)? = null
        private var ackHandler: ((ByteArray) -> Unit)? = null

        override fun connect(): Result<Unit> {
            online = true
            return Result.success(Unit)
        }

        override fun disconnect() {
            online = false
        }

        override fun send(data: ByteArray, to: IdentityId): Result<Unit> {
            messages.add(data)
            return Result.success(Unit)
        }

        override fun sendAck(ackBytes: ByteArray): Result<Unit> {
            acks.add(ackBytes)
            return Result.success(Unit)
        }

        override fun setMessageHandler(handler: (ByteArray) -> Unit) {
            messageHandler = handler
        }

        override fun setAckHandler(handler: (ByteArray) -> Unit) {
            ackHandler = handler
        }

        override fun isOnline(): Boolean = online

        override fun connectionState(): ConnectionState =
            if (online) ConnectionState.ONLINE else ConnectionState.DISCONNECTED

        override fun onStateChange(handler: (ConnectionState) -> Unit) {}
        override fun onPeerOnline(handler: (IdentityId) -> Unit) {}
        override fun onPeerOffline(handler: (IdentityId) -> Unit) {}

        fun deliverMessage(bytes: ByteArray) = checkNotNull(messageHandler).invoke(bytes)
        fun deliverAck(bytes: ByteArray) = checkNotNull(ackHandler).invoke(bytes)
    }

    /**
     * Dos nodos y sus transportes, con un parametro para la clave que Alice cree
     * tener de Bob.
     *
     * `resolver` sustituye al directorio a proposito: los casos negativos
     * necesitan darle a Alice una clave que NO es la de Bob, y el directorio no
     * se lo permite porque comprueba que la clave derive la identidad. Esa
     * compresion es la garantia de que un directorio bien llenado no puede
     * autenticar a un impostor; el caso negativo se hace por la otra via.
     */
    private inner class Fixture(
        resolver: ((IdentityId) -> ByteArray?)? = null,
        val ed25519: Ed25519Impl = Ed25519Impl(),
        val aliceKp: KeyPair = ed25519.generateKeyPair(),
        val bobKp: KeyPair = ed25519.generateKeyPair(),
    ) {
        val aliceId: IdentityId = identityIdOf(aliceKp.publicKey)
        val bobId: IdentityId = identityIdOf(bobKp.publicKey)

        val aliceWire = RecordingTransport()
        val bobWire = RecordingTransport()
        val aliceStore: MessageStore = InMemoryMessageStore()

        val alice = KeyMessageCoreImpl(
            localIdentity = aliceId,
            localPrivateKey = aliceKp.privateKey,
            transport = aliceWire,
            ed25519 = ed25519,
            messageStore = aliceStore,
            offlineQueue = InMemoryOfflineQueue(),
            duplicateStore = InMemoryDuplicateStore(),
            encoder = JsonEncoder(),
            decoder = JsonDecoder(),
            peerPublicKeyResolver = resolver ?: { null },
        )

        val bob = KeyMessageCoreImpl(
            localIdentity = bobId,
            localPrivateKey = bobKp.privateKey,
            transport = bobWire,
            ed25519 = ed25519,
            messageStore = InMemoryMessageStore(),
            duplicateStore = InMemoryDuplicateStore(),
            encoder = JsonEncoder(),
            decoder = JsonDecoder(),
        )

        /** Un directorio que SI conoce a Bob. */
        fun directoryKnowingBob(): PeerKeyDirectory = PeerKeyDirectory().also {
            assertTrue(it.register(bobId, bobKp.publicKey), "la clave de Bob deriva su identityId")
        }

        /**
         * El ciclo real: Alice envia, Bob recibe y firma, y su `ACK` sale por el
         * frame `ACK` del cable.
         *
         * Devuelve los bytes del `ACK` en el vocabulario de `km-core`, que es lo
         * que `ackHandler` recibe, y deja la carga de Alice en el sitio al que
         * hay que devolverla.
         */
        fun exchange(): Pair<ByteArray, com.km.model.MessageId> {
            val created = alice.createMessage(bobId, "hola".toByteArray()).getOrThrow()
            alice.sendMessage(created).getOrThrow()
            checkEquals(
                MessageState.SENT,
                alice.getMessageState(created.messageId).getOrThrow(),
                "el mensaje sale en SENT antes de que llegue ningun ACK",
            )

            // Alice -> frame `MESSAGE` -> Bob.
            val outbound = codec.parseObject(
                codec.encodeMessage(aliceId, bobId, aliceWire.messages.single())
            )!!
            bobWire.deliverMessage(codec.toCoreMessageBytes(codec.decodeMessage(outbound)!!)!!)

            // Bob -> frame `ACK` -> Alice, sin decodificarlo todavia.
            val ackWire = codec.parseObject(
                codec.encodeAck(decoder.decodeAck(bobWire.acks.single()).getOrThrow())
            )!!
            return codec.toCoreAckBytes(codec.decodeAck(ackWire)!!)!! to created.messageId
        }
    }

    private fun checkEquals(expected: MessageState, actual: MessageState, message: String) {
        assertEquals(expected, actual, message)
    }

    // ==================================================================
    // El camino feliz, y sus tres contrapuestos
    // ==================================================================

    @Test
    fun `el ACK con la clave correcta del peer AUTENTICA y confirma el mensaje`() {
        val keys = Fixture()
        val peers = keys.directoryKnowingBob()
        val fixture = Fixture(peers.resolver(), aliceKp = keys.aliceKp, bobKp = keys.bobKp)

        val (ackBytes, messageId) = fixture.exchange()
        fixture.aliceWire.deliverAck(ackBytes)

        assertEquals(
            MessageState.DELIVERED,
            fixture.alice.getMessageState(messageId).getOrThrow(),
            "el ACK autenticado tiene que confirmar el registro",
        )
    }

    @Test
    fun `el ACK con la clave de OTRO peer NO confirma el mensaje`() {
        val keys = Fixture()
        val impostor = keys.ed25519.generateKeyPair()
        // Alice resuelve la identidad de Bob a una clave que no es de Bob.
        val fixture = Fixture({ bobId -> impostor.publicKey }, aliceKp = keys.aliceKp, bobKp = keys.bobKp)

        val (ackBytes, messageId) = fixture.exchange()
        fixture.aliceWire.deliverAck(ackBytes)

        assertEquals(
            MessageState.SENT,
            fixture.alice.getMessageState(messageId).getOrThrow(),
            "una clave que no verifica tiene que dejar el mensaje sin confirmar",
        )
    }

    @Test
    fun `el ACK con la firma alterada NO confirma el mensaje`() {
        val keys = Fixture()
        val peers = keys.directoryKnowingBob()
        val fixture = Fixture(peers.resolver(), aliceKp = keys.aliceKp, bobKp = keys.bobKp)

        val (ackBytes, messageId) = fixture.exchange()
        // La clave es la correcta; lo que cambia es la firma. Un `ACK` de
        // `DELIVERED` reetiquetado como `FAILED` es el caso que de verdad
        // importa: el emisor creeria entregado lo que no lo esta.
        val tampered = decoder.decodeAck(ackBytes).getOrThrow()
            .copy(status = AckStatus.FAILED, signature = ByteArray(64) { 7 })
        fixture.aliceWire.deliverAck(encoder.encodeAck(tampered).getOrThrow())

        assertEquals(
            MessageState.SENT,
            fixture.alice.getMessageState(messageId).getOrThrow(),
            "un ACK que no verifica tiene que descartarse entero",
        )
    }

    @Test
    fun `sin claves registradas el ACK llega y NO se procesa, en silencio`() {
        // El comportamiento documentado de D3: sin resolver, `AckManagerImpl`
        // devuelve `success` y no hace nada. Se fija aqui para que anadir un
        // cliente no lo lea como una garantia de seguridad.
        val fixture = Fixture()

        val (ackBytes, messageId) = fixture.exchange()
        fixture.aliceWire.deliverAck(ackBytes)

        assertEquals(MessageState.SENT, fixture.alice.getMessageState(messageId).getOrThrow())
    }

    // ==================================================================
    // El ciclo completo, y la no amplificacion
    // ==================================================================

    @Test
    fun `el ida y vuelta por el codec correlaciona y no amplifica`() {
        val keys = Fixture()
        val peers = keys.directoryKnowingBob()
        val fixture = Fixture(peers.resolver(), aliceKp = keys.aliceKp, bobKp = keys.bobKp)

        val created = fixture.alice.createMessage(fixture.bobId, "hola".toByteArray()).getOrThrow()
        fixture.alice.sendMessage(created).getOrThrow()

        // R1.1: el `MESSAGE` saliente lleva el identificador de la carga.
        val outbound = codec.parseObject(
            codec.encodeMessage(fixture.aliceId, fixture.bobId, fixture.aliceWire.messages.single())
        )!!
        assertEquals(
            created.messageId.value.toString(), outbound.string("messageId"),
            "R1.1: el envelope lleva el messageId que creo createMessage",
        )

        // R1.2: la carga conserva ese identificador al entrar en el receptor.
        val inbound = codec.decodeMessage(outbound)!!
        val arrivedAtBob = decoder.decodeMessage(codec.toCoreMessageBytes(inbound)!!).getOrThrow()
        assertEquals(created.messageId, arrivedAtBob.messageId, "R1.2: el id sobrevive al salto")
        fixture.bobWire.deliverMessage(codec.toCoreMessageBytes(inbound)!!)

        // R1.3: el `ACK` va por su frame y cita el mensaje que confirma.
        val ackWire = codec.parseObject(
            codec.encodeAck(decoder.decodeAck(fixture.bobWire.acks.single()).getOrThrow())
        )!!
        assertEquals("ACK", codec.typeOf(ackWire))
        assertEquals(
            created.messageId.value.toString(), ackWire.string("originalMessageId"),
            "R1.3: el ACK cita M",
        )
        fixture.aliceWire.deliverAck(codec.toCoreAckBytes(codec.decodeAck(ackWire)!!)!!)

        assertEquals(
            MessageState.DELIVERED,
            fixture.alice.getMessageState(created.messageId).getOrThrow(),
            "el emisor correlaciona M",
        )
        assertTrue(
            fixture.bobWire.messages.isEmpty(),
            "cero `MESSAGE` del receptor: confirmar no genera otro mensaje",
        )
    }

    // ==================================================================
    // PeerKeyDirectory: la coherencia se recalcula
    // ==================================================================

    @Test
    fun `el directorio rechaza una clave que no deriva el identityId indicado`(@TempDir dir: Path) {
        val kp = Ed25519Impl().generateKeyPair()
        val directory = PeerKeyDirectory(dir.resolve("peers.json"))
        val real = identityIdOf(kp.publicKey)
        val otro = identityIdOf(Ed25519Impl().generateKeyPair().publicKey)

        assertFalse(
            directory.register(otro, kp.publicKey),
            "la clave de A no puede registrarse como si fuera la de B",
        )
        assertNull(directory.signingKeyOf(otro))

        assertTrue(directory.register(real, kp.publicKey), "la coherencia si se cumple: se guarda")
        assertEquals(kp.publicKey.toList(), directory.signingKeyOf(real)?.toList())
        assertNull(directory.signingKeyOf(otro), "una identidad no registrada no tiene clave")
    }

    @Test
    fun `el directorio guarda y relee, y lo incoherente se salta`(@TempDir dir: Path) {
        val ed25519 = Ed25519Impl()
        val goodKp = ed25519.generateKeyPair()
        val goodId = identityIdOf(goodKp.publicKey)
        val file = dir.resolve("peers.json")

        val saved = PeerKeyDirectory(file)
        assertTrue(saved.register(goodId, goodKp.publicKey))
        assertTrue(saved.save())

        val loaded = PeerKeyDirectory(file).load()
        assertEquals(0, loaded.skipped, "un directorio recien escrito no tiene nada que saltar")
        assertEquals(goodKp.publicKey.toList(), loaded.signingKeyOf(goodId)?.toList())

        // Ahora se corrompe a mano: una identidad con la clave de otro, y una
        // entrada que no es ni Base64.
        val corruptId = identityIdOf(ed25519.generateKeyPair().publicKey)
        java.nio.file.Files.writeString(
            file,
            """
            {"format":"km-desktop-peers","version":1,"peers":[
              {"identityId":"${goodId.value}","publicKey":"${Base64.getEncoder().encodeToString(goodKp.publicKey)}"},
              {"identityId":"$corruptId","publicKey":"${Base64.getEncoder().encodeToString(ed25519.generateKeyPair().publicKey)}"},
              {"identityId":"basura","publicKey":"no-es-base64!!"}
            ]}
            """.trimIndent(),
        )

        val afterCorruption = PeerKeyDirectory(file).load()
        assertEquals(2, afterCorruption.skipped, "los dos registros ilegibles se cuentan y se saltan")
        assertEquals(goodKp.publicKey.toList(), afterCorruption.signingKeyOf(goodId)?.toList())
        assertNull(afterCorruption.signingKeyOf(corruptId))
    }

    @Test
    fun `el directorio no acepta un ContactBundle cuya clave no derive la identidad`(@TempDir dir: Path) {
        val ed25519 = Ed25519Impl()
        val kp = ed25519.generateKeyPair()
        val identity = identityIdOf(kp.publicKey)
        val directory = PeerKeyDirectory(dir.resolve("peers.json"))

        // El `subjectSigningKey` de un ContactBundle es una clave de FIRMA DE
        // DISPOSITIVO dentro de un roster, y su `deviceId` deriva de otro
        // dominio. Usarla como si fuera la clave de identidad seria autenticar
        // ACKs de otra identidad sin que nadie se entere.
        val rosterKey = ed25519.generateKeyPair().publicKey
        assertFalse(
            directory.registerFromContactBundle(mapOf("subjectSigningKey" to rosterKey), identity),
            "un bundle cuya clave no deriva la identidad se rechaza",
        )
        assertNull(directory.signingKeyOf(identity), "y no queda nada guardado")

        // El camino coherente si se guarda.
        assertTrue(directory.registerFromContactBundle(mapOf("subjectSigningKey" to kp.publicKey), identity))
        assertEquals(kp.publicKey.toList(), directory.signingKeyOf(identity)?.toList())
    }

    @Test
    fun `registerEncoded distingue lo ilegible de lo incoherente`(@TempDir dir: Path) {
        val ed25519 = Ed25519Impl()
        val kp = ed25519.generateKeyPair()
        val id = identityIdOf(kp.publicKey)
        val directory = PeerKeyDirectory(dir.resolve("peers.json"))
        val b64 = Base64.getEncoder()

        assertNotNull(directory.registerEncoded(id.value, "no-es-base64!!"), "Base64 invalido")
        assertNotNull(directory.registerEncoded(id.value, b64.encodeToString(ByteArray(8))), "clave corta")
        assertNotNull(directory.registerEncoded("corto", b64.encodeToString(kp.publicKey)), "identityId corto")
        assertNotNull(
            directory.registerEncoded(id.value, b64.encodeToString(ed25519.generateKeyPair().publicKey)),
            "la clave no deriva ese identityId",
        )
        assertNull(
            directory.registerEncoded(id.value, b64.encodeToString(kp.publicKey)),
            "el par coherente no produce error",
        )
        assertEquals(kp.publicKey.toList(), directory.signingKeyOf(id)?.toList())
    }

}
