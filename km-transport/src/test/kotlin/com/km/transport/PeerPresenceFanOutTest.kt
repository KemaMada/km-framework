package com.km.transport

import com.km.model.IdentityId
import com.km.transport.SocketSecureWorld.IdentityRoot
import com.km.transport.SocketSecureWorld.awaitOrFail
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList

/**
 * R6 — un suscriptor de `onPeerPresence` que lanza NO silencia a los demas.
 *
 * ## EL DEFECTO
 *
 * `JvmRelayTransport.onPeerPresence` recorria `peerOnlineHandlers` /
 * `peerOfflineHandlers` con un `forEach` desnudo. Una excepcion de un handler
 * cortaba el recorrido, de modo que los suscriptores POSTERIORES no recibian la
 * presencia, mientras `onFrame` capturaba por frame y la sesion seguia viva: el
 * sintoma era "a este consumidor no le llega el par" sin un solo error propio.
 *
 * El consumidor afectado puede ser el `RelayPeerChannelDriver` de otro par, o
 * la publicacion de presencia de la UI. Por eso el aislamiento es por handler y
 * el fallo se registra como WARN.
 *
 * ## POR QUE CON UN RELE DE VERDAD
 *
 * `onPeerPresence` es privado y solo lo alimenta un frame `PEER_ONLINE` /
 * `PEER_OFFLINE` autenticado. Un doble del motor probaria al doble; aqui Bob se
 * conecta y se desconecta, y el rele emite la presencia de verdad.
 */
class PeerPresenceFanOutTest {

    private val trace = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private var relay: LocalRelay? = null
    private val open = CopyOnWriteArrayList<JvmRelayTransport>()

    @AfterEach
    fun tearDown() {
        open.forEach { runCatching { it.close() } }
        open.clear()
        relay?.shutdown()
        relay = null
    }

    private fun node(root: IdentityRoot): JvmRelayTransport {
        val engine = JvmRelayTransport(
            identity = root.identityId,
            ed25519PublicKey = root.keyPair.publicKey,
            ed25519PrivateKey = root.keyPair.privateKey,
            endpoint = requireNotNull(relay).endpoint(),
            relayIdentity = requireNotNull(relay).relayIdentityId,
            ed25519 = SocketSecureWorld.ed25519,
            handshakeTimeoutMs = 20_000,
            log = { message -> trace.add("${root.identityId.value.take(6)}: $message") },
        )
        open.add(engine)
        return engine
    }

    @Test
    @Timeout(180)
    fun `un suscriptor que lanza no impide que el siguiente reciba la presencia - R6`() {
        relay = LocalRelay.start("fanout")

        val aliceRoot = IdentityRoot()
        val alice = node(aliceRoot)
        val bobRoot = IdentityRoot()
        val bob = node(bobRoot)

        val online = CopyOnWriteArrayList<IdentityId>()
        val offline = CopyOnWriteArrayList<IdentityId>()
        var onlineRotoInvocado = false
        var offlineRotoInvocado = false

        // PRIMERO el que lanza: es el orden que reproduce el defecto, porque el
        // `forEach` desnudo abandonaba en el primer throw.
        alice.onPeerOnline {
            onlineRotoInvocado = true
            error("suscriptor ONLINE roto a proposito")
        }
        alice.onPeerOnline { online.add(it) }
        alice.onPeerOffline {
            offlineRotoInvocado = true
            error("suscriptor OFFLINE roto a proposito")
        }
        alice.onPeerOffline { offline.add(it) }

        // El frame-loop NO reporta estas excepciones al consumidor: si el
        // aislamiento no existiera, la sesion seguiria viva y el segundo
        // suscriptor se quedaria mudo. Eso es exactamente lo que se mide.
        alice.connect().getOrThrow()
        awaitOrFail("Alice debe quedar ONLINE", trace = { trace.toString() }) { alice.isOnline() }

        bob.connect().getOrThrow()
        awaitOrFail("Alice debe quedar ONLINE", trace = { trace.toString() }) { bob.isOnline() }

        awaitOrFail("el segundo suscriptor debe recibir PEER_ONLINE", trace = { trace.toString() }) {
            online.contains(bobRoot.identityId)
        }
        assertTrue(onlineRotoInvocado, "el primero SI se invoca: el aislamiento no es 'saltarse el roto'")
        assertTrue(alice.isOnline(), "la sesion sigue viva tras la excepcion de un suscriptor")
        assertTrue(
            alice.isPeerPresent(bobRoot.identityId),
            "la presencia se registra ANTES del fan-out: el hecho no depende de los handlers",
        )

        bob.close()

        awaitOrFail("el segundo suscriptor debe recibir PEER_OFFLINE", trace = { trace.toString() }) {
            offline.contains(bobRoot.identityId)
        }
        assertTrue(offlineRotoInvocado, "el primero de OFFLINE tambien se invoca y tambien lanza")
        assertTrue(alice.isOnline(), "una desconexion rota de un consumidor no tumba la sesion")
        assertFalse(alice.isPeerPresent(bobRoot.identityId), "el motor olvido al par aunque un handler lanzara")
        assertEquals(
            listOf(bobRoot.identityId),
            online.toList(),
            "el segundo suscriptor recibe UN evento por presencia, no una rafaga: $online",
        )
    }
}
