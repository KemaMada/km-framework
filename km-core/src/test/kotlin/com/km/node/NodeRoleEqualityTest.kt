package com.km.node

import com.km.crypto.Ed25519Impl
import com.km.model.NodeAnnouncementJsonCodec
import com.km.model.NodeAnnouncementVerificationException
import com.km.model.NodeCapability
import com.km.model.NodeEndpoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A1.2 — Intercambio de capacidades dentro del `NodeAnnouncement` FIRMADO.
 *
 * Lo que se demuestra:
 *  1. El conjunto de capacidades da la vuelta por [NodeAnnouncementJsonCodec]
 *     sin perdida (round-trip).
 *  2. La firma cubre las capacidades: manipularlas invalida el anuncio.
 *  3. Un conjunto vacio es un anuncio valido.
 *  4. El directorio registra el conjunto YA verificado.
 *  5. Las semillas de bootstrap salen de configuracion (Tailscale / .onion).
 *  6. El flag `km.roles.capabilities` gobierna TODO el intercambio: apagado,
 *     registrar/anunciar es una no-operacion.
 *  7. **El relay NUNCA interpreta capacidades**: ni `km-daemon` ni
 *     [RelayServer] referencian el modelo de capacidades. El parseo vive en el
 *     cliente ([NodeAnnouncementJsonCodec] + [PeerRoleDirectory]); el relay solo
 *     reenvia bytes opacos.
 */
class NodeRoleEqualityTest {

    private val ed25519 = Ed25519Impl()

    private val endpoint = NodeEndpoint("websocket", "wss://203.0.113.7:443/ws")

    private fun runtime(
        directory: PeerRoleDirectory? = null,
        flag: Boolean = false,
        caps: Set<NodeCapability> = setOf(NodeCapability.SIGNALING),
    ): NodeRuntimeImpl {
        val kp = ed25519.generateKeyPair()
        return NodeRuntimeImpl.fromKeyPair(
            publicKey = kp.publicKey,
            privateKey = kp.privateKey,
            nodeName = "alice",
            capabilities = caps,
            roleDirectory = directory,
            rolesCapabilities = flag,
        )
    }

    // ===================================================================
    // Round-trip firmado
    // ===================================================================

    @Test
    @DisplayName("A1.2-01 las capacidades dan la vuelta firmadas por el codec")
    fun capacidadesDanLaVueltaFirmadas() {
        val rt = runtime()
        val caps = setOf(
            NodeCapability.CLIENT,
            NodeCapability.RELAY,
            NodeCapability.SIGNALING,
            NodeCapability.STORE_AND_FORWARD,
        )
        val original = rt.createNodeAnnouncement(endpoints = listOf(endpoint), nodeCapabilities = caps)

        val decoded = NodeAnnouncementJsonCodec.decode(NodeAnnouncementJsonCodec.encode(original))

        assertEquals(caps, decoded.capabilities)
        assertEquals(original.identity.nodeId, decoded.identity.nodeId)
        assertEquals(original.messageId, decoded.messageId)
        assertEquals(original.timestamp, decoded.timestamp)
    }

    @Test
    @DisplayName("A1.2-02 verifyNodeAnnouncement acepta el anuncio con capacidades")
    fun verificaAnuncioConCapacidades() {
        val rt = runtime()
        val caps = setOf(NodeCapability.RELAY, NodeCapability.STORE_AND_FORWARD)
        val signed = rt.createNodeAnnouncement(endpoints = listOf(endpoint), nodeCapabilities = caps)

        val verified = rt.verifyNodeAnnouncement(NodeAnnouncementJsonCodec.encode(signed))

        assertEquals(caps, verified.capabilities)
    }

    @Test
    @DisplayName("A1.2-03 manipular las capacidades rompe la firma")
    fun manipularCapacidadesRompeFirma() {
        val rt = runtime()
        val signed = rt.createNodeAnnouncement(
            endpoints = listOf(endpoint),
            nodeCapabilities = setOf(NodeCapability.CLIENT),
        )
        val wire = String(NodeAnnouncementJsonCodec.encode(signed), Charsets.UTF_8)
        // El atacante se otorga RELAY sobre el anuncio firmado.
        val tampered = wire.replace("\"CLIENT\"", "\"RELAY\"").encodeToByteArray()
        assertFalse(String(tampered, Charsets.UTF_8).contains("\"CLIENT\""))

        val e = assertThrows(NodeAnnouncementVerificationException::class.java) {
            rt.verifyNodeAnnouncement(tampered)
        }
        assertEquals("INVALID_SIGNATURE", e.code)
    }

    @Test
    @DisplayName("A1.2-04 un conjunto de capacidades vacio da la vuelta como valido")
    fun conjuntoVacioDaLaVuelta() {
        val rt = runtime()
        val signed = rt.createNodeAnnouncement(endpoints = listOf(endpoint), nodeCapabilities = emptySet())

        val verified = rt.verifyNodeAnnouncement(NodeAnnouncementJsonCodec.encode(signed))

        assertTrue(verified.capabilities.isEmpty())
    }

    // ===================================================================
    // Registro tras verificar
    // ===================================================================

    @Test
    @DisplayName("A1.2-05 el directorio registra el conjunto ya verificado")
    fun directorioRegistraTrasVerificar() {
        val dir = PeerRoleDirectory()
        val rt = runtime(directory = dir, flag = true)
        val remote = runtime()
        val signed = remote.createNodeAnnouncement(
            endpoints = listOf(endpoint),
            nodeCapabilities = setOf(NodeCapability.RELAY, NodeCapability.SIGNALING),
        )

        val verified = rt.verifyNodeAnnouncement(NodeAnnouncementJsonCodec.encode(signed))
        val info = rt.registerPeerRoles(verified)

        assertNotNull(info)
        assertEquals(verified.identity.nodeId, info!!.identity.nodeId)
        assertEquals(setOf(NodeCapability.RELAY, NodeCapability.SIGNALING), dir.get(verified.identity.nodeId)?.capabilities)
        assertEquals(setOf(verified.identity.nodeId), dir.peersWith(NodeCapability.RELAY))
    }

    // ===================================================================
    // Semillas de bootstrap desde configuracion
    // ===================================================================

    @Test
    @DisplayName("A1.2-06 las semillas de config se clasifican Tailscale -> websocket, .onion -> onion")
    fun semillasDesdeConfig() {
        val dir = PeerRoleDirectory()
        val rt = runtime(directory = dir, flag = true)

        val n = rt.seedBootstrapRelays(
            listOf("100.101.102.103:8443", "relay.example.onion:443", "  ", ""),
        )

        assertEquals(2, n, "los vacios se descartan")
        val seeds = dir.seeds()
        assertEquals(RelaySeed.TRANSPORT_WEBSOCKET, seeds[0].transport)
        assertEquals(RelaySeed.TRANSPORT_ONION, seeds[1].transport)
        assertTrue(dir.isEmpty(), "una semilla no es un peer con identidad")
    }

    // ===================================================================
    // Flag OFF / ON
    // ===================================================================

    @Test
    @DisplayName("A1.2-07 flag OFF: registrar roles y sembrar reles no toca el directorio")
    fun flagApagadoEsNoOperacion() {
        val dir = PeerRoleDirectory()
        val rt = runtime(directory = dir, flag = false)
        val remote = runtime()
        val announcement = remote.createNodeAnnouncement(
            endpoints = listOf(endpoint),
            nodeCapabilities = setOf(NodeCapability.RELAY),
        )

        assertNull(rt.registerPeerRoles(announcement))
        assertEquals(0, rt.seedBootstrapRelays(listOf("100.64.0.1:8080")))

        assertTrue(dir.isEmpty(), "el cache sigue vacio con el flag apagado")
        assertTrue(dir.seeds().isEmpty(), "las semillas tampoco se registran")
        assertTrue(dir.hasNoRelay())
    }

    @Test
    @DisplayName("A1.2-08 flag ON: registrar roles y sembrar reles pueblan el directorio")
    fun flagEncendidoRegistra() {
        val dir = PeerRoleDirectory()
        val rt = runtime(directory = dir, flag = true)
        val remote = runtime()
        val announcement = remote.createNodeAnnouncement(
            endpoints = listOf(endpoint),
            nodeCapabilities = setOf(NodeCapability.RELAY, NodeCapability.STORE_AND_FORWARD),
        )

        val info = rt.registerPeerRoles(announcement)
        assertNotNull(info)
        assertEquals(setOf(NodeCapability.RELAY, NodeCapability.STORE_AND_FORWARD), info!!.capabilities)
        assertEquals(2, rt.seedBootstrapRelays(listOf("100.64.0.1:8080", "relay.onion:443")))
        assertEquals(2, dir.seeds().size)
    }

    @Test
    @DisplayName("A1.2-09 sin directorio, el flag encendido no inventa uno")
    fun flagEncendidoSinDirectorioNoHaceNada() {
        val rt = runtime(directory = null, flag = true)
        val remote = runtime()
        val announcement = remote.createNodeAnnouncement(nodeCapabilities = setOf(NodeCapability.RELAY))

        assertNull(rt.registerPeerRoles(announcement))
        assertEquals(0, rt.seedBootstrapRelays(listOf("100.64.0.1:8080")))
    }

    // ===================================================================
    // El relay nunca interpreta capacidades
    // ===================================================================

    @Test
    @DisplayName("A1.2-10 el relay (km-daemon + RelayServer) no conoce el modelo de capacidades")
    fun elRelayNoInterpretaCapacidades() {
        val root = repoRoot()
        // Tokens del MODELO de capacidades. Si el relay los nombra, esta
        // interpretando semantica de aplicacion que no le corresponde.
        val tokens = listOf("NodeCapability", "NodeAnnouncement", "PeerRoleDirectory", "RelaySeed")

        val offenders = mutableListOf<String>()

        val daemonMain = File(root, "km-daemon/src/main/kotlin")
        assertTrue(daemonMain.isDirectory, "no existe $daemonMain")
        daemonMain.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                val text = stripComments(f.readText())
                tokens.firstOrNull { text.contains(it) }?.let {
                    offenders += "${f.relativeTo(root)} referencia '$it'"
                }
            }

        val relayServer = File(root, "km-core/src/main/kotlin/com/km/node/RelayServer.kt")
        assertTrue(relayServer.isFile, "no existe $relayServer")
        val relayText = stripComments(relayServer.readText())
        tokens.firstOrNull { relayText.contains(it) }?.let {
            offenders += "km-core/.../RelayServer.kt referencia '$it'"
        }

        assertEquals(
            emptyList<String>(),
            offenders,
            "el relay debe ser un reenviador opaco; el parseo de capacidades vive en el cliente:\n" +
                offenders.joinToString("\n"),
        )
    }

    /**
     * Un comentario que nombra el modelo de capacidades NO es interpretarlo:
     * la guardia solo debe mirar CODIGO. Se descartan bloques y lineas de
     * comentario antes de buscar los tokens.
     */
    private fun stripComments(source: String): String {
        val sinBloque = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(source, " ")
        return sinBloque.lines().joinToString("\n") { linea ->
            val idx = linea.indexOf("//")
            if (idx >= 0) linea.substring(0, idx) else linea
        }
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "km-core").isDirectory && File(dir, "km-daemon").isDirectory) return dir
            dir = dir.parentFile
        }
        error("no se encontro la raiz del repo (con km-core/ y km-daemon/) desde user.dir")
    }
}
