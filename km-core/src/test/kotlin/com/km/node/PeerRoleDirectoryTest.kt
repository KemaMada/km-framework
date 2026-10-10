package com.km.node

import com.km.crypto.Ed25519Impl
import com.km.model.MessageId
import com.km.model.NodeAnnouncement
import com.km.model.NodeCapability
import com.km.model.NodeEndpoint
import com.km.model.NodeIdentity
import com.km.model.RelayInfo
import com.km.model.RelayLimits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A1.1 — [PeerRoleDirectory]: cache por `IdentityId` e indice invertido por
 * [NodeCapability].
 *
 * Lo que se demuestra:
 *  1. Un anuncio se cachea por identidad y se puede recuperar tal cual.
 *  2. El indice invertido responde "quien puede relevar" por capacidad.
 *  3. Un conjunto de capacidades VACIO es un estado valido, no un error.
 *  4. Re-anunciar QUITA la capacidad vieja del indice (no queda historia
 *     pegada).
 *  5. Sin reles conocidos el directorio lo dice, y eso es un estado soportado.
 *  6. Las semillas de bootstrap viven APARTE: no tienen identidad verificada.
 *
 * No se demuestra nada sobre firmas: el directorio NO verifica. Recibe
 * anuncios YA verificados por quien los trae; verificar aqui seria un segundo
 * sitio donde se decide la identidad de un peer.
 */
class PeerRoleDirectoryTest {

    private val ed25519 = Ed25519Impl()

    private val endpoint = NodeEndpoint("websocket", "wss://203.0.113.10:443/ws")

    private fun identity(name: String? = null): NodeIdentity =
        NodeIdentity.fromKeyPair(ed25519.generateKeyPair().publicKey, nodeName = name)

    private fun announcement(
        identity: NodeIdentity,
        caps: Set<NodeCapability>,
        endpoints: List<NodeEndpoint> = listOf(endpoint),
        limits: RelayLimits? = RelayLimits(),
    ) = NodeAnnouncement(
        messageId = MessageId(UUID.randomUUID()),
        timestamp = 1_000L,
        protocolVersion = "1.0",
        identity = identity,
        endpoints = endpoints,
        capabilities = caps,
        limits = limits,
    )

    // ===================================================================
    // Cache por identidad
    // ===================================================================

    @Test
    @DisplayName("A1.1-01 un anuncio se cachea por IdentityId y se recupera intacto")
    fun cacheaPorIdentidad() {
        val dir = PeerRoleDirectory { 42L }
        val id = identity("relay-a")
        val info = dir.record(
            announcement(id, setOf(NodeCapability.RELAY, NodeCapability.STORE_AND_FORWARD)),
            latencyMs = 7,
        )

        assertEquals(id.nodeId, info.identity.nodeId)
        assertEquals(endpoint, info.endpoint)
        assertEquals(setOf(NodeCapability.RELAY, NodeCapability.STORE_AND_FORWARD), info.capabilities)
        assertEquals(7L, info.latencyMs)
        assertEquals(42L, info.lastSeenMs)
        assertFalse(info.isTrusted, "un anuncio sin enlace explicito no se marca trusted")

        assertEquals(info, dir.get(id.nodeId))
        assertEquals(1, dir.size())
        assertNotNull(dir.get(id.nodeId))
    }

    @Test
    @DisplayName("A1.1-02 un anuncio sin endpoint no pierde sus capacidades")
    fun anuncioSinEndpointConservaCapacidades() {
        val dir = PeerRoleDirectory()
        val id = identity()
        val info = dir.record(announcement(id, setOf(NodeCapability.SIGNALING), endpoints = emptyList()))

        assertEquals(setOf(NodeCapability.SIGNALING), info.capabilities)
        assertEquals("", info.endpoint.address, "endpoint sintetico vacio, nunca null")
        assertTrue(id.nodeId in dir.peersWith(NodeCapability.SIGNALING))
    }

    // ===================================================================
    // Indice invertido por capacidad
    // ===================================================================

    @Test
    @DisplayName("A1.1-03 el indice invertido responde por cada capacidad")
    fun indicePorCapacidad() {
        val dir = PeerRoleDirectory()
        val relay = identity("relay")
        val signaler = identity("signaler")
        val client = identity("client")

        dir.record(announcement(relay, setOf(NodeCapability.RELAY, NodeCapability.STORE_AND_FORWARD)))
        dir.record(announcement(signaler, setOf(NodeCapability.SIGNALING)))
        dir.record(announcement(client, setOf(NodeCapability.CLIENT)))

        assertEquals(setOf(relay.nodeId), dir.peersWith(NodeCapability.RELAY))
        assertEquals(setOf(relay.nodeId), dir.peersWith(NodeCapability.STORE_AND_FORWARD))
        assertEquals(setOf(signaler.nodeId), dir.peersWith(NodeCapability.SIGNALING))
        assertEquals(setOf(client.nodeId), dir.peersWith(NodeCapability.CLIENT))
        assertEquals(3, dir.size())
    }

    @Test
    @DisplayName("A1.1-04 re-anunciar retira las capacidades viejas del indice")
    fun reanunciarRetiraCapacidadesViejas() {
        val dir = PeerRoleDirectory()
        val id = identity("cambia-de-rol")

        dir.record(announcement(id, setOf(NodeCapability.RELAY, NodeCapability.SIGNALING)))
        assertEquals(setOf(id.nodeId), dir.peersWith(NodeCapability.RELAY))

        // El nodo deja de relevar y solo senaliza: el indice no puede seguir
        // contando la historia vieja.
        dir.record(announcement(id, setOf(NodeCapability.SIGNALING)))
        assertEquals(emptySet<NodeCapability>(), dir.peersWith(NodeCapability.RELAY))
        assertEquals(setOf(id.nodeId), dir.peersWith(NodeCapability.SIGNALING))
        assertEquals(1, dir.size(), "el peer se reemplaza, no se duplica")

        // Y al reves: vuelve a relevar.
        dir.record(announcement(id, setOf(NodeCapability.RELAY)))
        assertEquals(setOf(id.nodeId), dir.peersWith(NodeCapability.RELAY))
        assertEquals(emptySet<NodeCapability>(), dir.peersWith(NodeCapability.SIGNALING))
    }

    // ===================================================================
    // Conjunto vacio: valido, no error
    // ===================================================================

    @Test
    @DisplayName("A1.1-05 un conjunto de capacidades vacio es valido y no se indexa")
    fun conjuntoVacioEsValido() {
        val dir = PeerRoleDirectory()
        val id = identity("sin-caps")

        val info = dir.record(announcement(id, emptySet()))

        assertTrue(info.capabilities.isEmpty())
        assertEquals(1, dir.size(), "el peer se guarda como cualquier otro")
        assertNotNull(dir.get(id.nodeId))
        for (cap in NodeCapability.entries) {
            assertEquals(emptySet<NodeCapability>(), dir.peersWith(cap), "nada deberia indexarse por $cap")
        }
        assertTrue(dir.hasNoRelay(), "sin RELAY, no hay rele: estado soportado")
    }

    @Test
    @DisplayName("A1.1-06 un directorio vacio no tiene reles y no es un error")
    fun directorioVacioSinReles() {
        val dir = PeerRoleDirectory()
        assertTrue(dir.isEmpty())
        assertTrue(dir.hasNoRelay())
        assertEquals(emptyList<RelayInfo>(), dir.relays())
        assertEquals(emptyList<RelayInfo>(), dir.all())
    }

    // ===================================================================
    // Preferencia de reles
    // ===================================================================

    @Test
    @DisplayName("A1.1-07 relays prefiere menor latencia y deja la desconocida al final")
    fun relaysPrefiereMenorLatencia() {
        val dir = PeerRoleDirectory()
        val rapido = identity("rapido")
        val medio = identity("medio")
        val desconocido = identity("desconocido")

        dir.record(announcement(medio, setOf(NodeCapability.RELAY)), latencyMs = 50)
        dir.record(announcement(desconocido, setOf(NodeCapability.RELAY)), latencyMs = -1)
        dir.record(announcement(rapido, setOf(NodeCapability.RELAY)), latencyMs = 10)

        val orden = dir.relays().map { it.identity.nodeId }
        assertEquals(listOf(rapido.nodeId, medio.nodeId, desconocido.nodeId), orden)
    }

    @Test
    @DisplayName("A1.1-08 relays es estable entre llamadas con igual latencia")
    fun relaysEsEstable() {
        val dir = PeerRoleDirectory()
        repeat(5) { dir.record(announcement(identity("r$it"), setOf(NodeCapability.RELAY)), latencyMs = 1) }
        assertEquals(dir.relays().map { it.identity.nodeId }, dir.relays().map { it.identity.nodeId })
    }

    // ===================================================================
    // Baja de peers
    // ===================================================================

    @Test
    @DisplayName("A1.1-09 remove limpia cache e indice")
    fun removeLimpiaAmbosIndices() {
        val dir = PeerRoleDirectory()
        val id = identity()
        dir.record(announcement(id, setOf(NodeCapability.RELAY, NodeCapability.SIGNALING)))

        dir.remove(id.nodeId)

        assertNull(dir.get(id.nodeId))
        assertEquals(emptySet<NodeCapability>(), dir.peersWith(NodeCapability.RELAY))
        assertEquals(emptySet<NodeCapability>(), dir.peersWith(NodeCapability.SIGNALING))
        assertEquals(0, dir.size())
    }

    // ===================================================================
    // Semillas de bootstrap (A1.2): sin identidad verificada
    // ===================================================================

    @Test
    @DisplayName("A1.1-10 las semillas viven aparte y clasifican .onion como transporte onion")
    fun semillasDeBootstrap() {
        val dir = PeerRoleDirectory()
        dir.addSeeds(
            listOf(
                RelaySeed.fromAddress("100.101.102.103:8443"),
                RelaySeed.fromAddress("relay.example.onion:443"),
            ),
        )

        val seeds = dir.seeds()
        assertEquals(2, seeds.size)
        assertEquals(RelaySeed.TRANSPORT_WEBSOCKET, seeds[0].transport)
        assertEquals("100.101.102.103:8443", seeds[0].address)
        assertEquals(RelaySeed.TRANSPORT_ONION, seeds[1].transport)
        assertTrue(NodeCapability.RELAY in seeds[1].expectedCapabilities)
        assertTrue(dir.isEmpty(), "una semilla NO es un peer: no aparece en el cache por identidad")
    }

    @Test
    @DisplayName("A1.1-11 clear vacia peers y semillas")
    fun clearVaciaTodo() {
        val dir = PeerRoleDirectory()
        dir.record(announcement(identity(), setOf(NodeCapability.RELAY)))
        dir.addSeed(RelaySeed.fromAddress("10.0.0.1:1"))

        dir.clear()

        assertTrue(dir.isEmpty())
        assertEquals(emptyList<RelaySeed>(), dir.seeds())
    }
}
