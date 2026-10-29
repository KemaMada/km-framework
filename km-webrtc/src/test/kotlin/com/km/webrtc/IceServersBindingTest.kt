package com.km.webrtc

import com.km.config.IceConfig
import com.km.model.IdentityId
import com.km.node.TransportErrorCategory
import com.km.node.TransportError
import com.km.node.TransportResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertIs

/**
 * S6 — ICE REAL en `RealWebRtcTransport` y errores "no listo" de categoria
 * TRANSPORT.
 *
 * ## Que se demuestra
 *
 *  - La lista de servidores aplicada es EXACTAMENTE la configurada: ni un
 *    servidor publico inventado, ni la lista vacia de antes.
 *  - Sin configuracion la PeerConnection queda host-only y lo DECLARA.
 *  - `initialize()` pendiente es `BACKEND_NOT_READY` (TRANSPORT), no
 *    `INVALID_STATE_TRANSITION` (STATE): un P2P sin inicializar debe permitir
 *    fallback, no detener la cadena.
 *  - El codigo de `src/main` no contiene URLs literales de STUN/TURN.
 */
class IceServersBindingTest {

    private val local = IdentityId("11".repeat(32))
    private val remote = IdentityId("22".repeat(32))

    @Test
    @DisplayName("6.4-01 STUN y TURN se traducen con sus URLs y credenciales exactas")
    fun listaExacta() {
        val config = IceConfig.of(
            stunUrls = listOf("stun:stun.example.org:3478", "stuns:stun.example.org:5349"),
            turnUrls = listOf("turn:turn.example.org:3478?transport=udp", "turns:turn.example.org:5349"),
            turnUsername = "usuario-turn",
            turnCredential = "credencial-turn",
        )

        val servers = rtcIceServersFor(config)

        assertEquals(2, servers.size, "un servidor para STUN y otro para TURN")
        assertEquals(config.stunUrls, servers[0].urls)
        assertEquals(null, servers[0].username, "STUN no lleva credencial de TURN")
        assertEquals(config.turnUrls, servers[1].urls)
        assertEquals("usuario-turn", servers[1].username)
        assertEquals("credencial-turn", servers[1].password)
    }

    @Test
    @DisplayName("6.4-02 solo STUN: un servidor y sin credenciales")
    fun soloStun() {
        val servers = rtcIceServersFor(IceConfig.of(stunUrls = listOf("stun:stun.example.org:3478")))
        assertEquals(1, servers.size)
        assertEquals(null, servers.single().username)
        assertEquals(null, servers.single().password)
    }

    @Test
    @DisplayName("6.4-03 lista vacia = host-only y CERO servidores (no contacta fuera)")
    fun listaVaciaHostOnly() {
        val transport = RealWebRtcTransport()

        assertTrue(transport.hostOnly, "sin ICE configurado el medio es host-only")
        assertTrue(transport.iceServersSnapshot().isEmpty(), "no puede haber STUN/TURN implicito")
        assertTrue(RealWebRtcTransport(IceConfig.hostOnly()).iceServersSnapshot().isEmpty())
    }

    @Test
    @DisplayName("6.4-04 el transporte expone la MISMA traduccion que el binding")
    fun transporteUsaLaTraduccion() {
        val config = IceConfig.of(
            stunUrls = listOf("stun:stun.example.org:3478"),
            turnUrls = listOf("turn:turn.example.org:3478"),
            turnUsername = "u",
            turnCredential = "c",
        )
        val transport = RealWebRtcTransport(config)
        assertFalse(transport.hostOnly)
        assertEquals(rtcIceServersFor(config).map { it.urls }, transport.iceServersSnapshot().map { it.urls })
        assertEquals(2, transport.iceServersSnapshot().size)
    }

    @Test
    @DisplayName("6.4-05 createBinding aplica los servidores configurados")
    fun bindingAplica() {
        val config = IceConfig.of(stunUrls = listOf("stun:stun.example.org:3478"))
        val transport = RealWebRtcTransport(config)
        assertTrue(transport.initialize().isSuccess)
        try {
            assertTrue(transport.onCreateBinding(local, remote).isSuccess)
            // La lista que se aplico AL BINDING es la configurada, no solo el
            // snapshot: un `emptyList()` fijo en `onCreateBinding` pondria esto
            // rojo aunque el snapshot siguiera correcto.
            assertEquals(
                listOf("stun:stun.example.org:3478"),
                transport.appliedIceServers().single().urls,
                "el binding debe aplicar los servidores configurados",
            )
        } finally {
            transport.shutdown()
            transport.disposeCallbacks()
        }
    }

    @Test
    @DisplayName("6.4-06 sin initialize, el error es BACKEND_NOT_READY (TRANSPORT), no STATE")
    fun noListoEsTransport() {
        val transport = RealWebRtcTransport()
        try {
            val result = transport.onCreateBinding(local, remote)
            val failure = assertIs<TransportResult.Failure>(result)
            assertEquals(TransportError.BACKEND_NOT_READY, failure.error)
            assertEquals(TransportErrorCategory.TRANSPORT, failure.error.category)
            assertTrue(failure.error.permitsFallback, "debe autorizar el relay")
        } finally {
            transport.disposeCallbacks()
        }
    }

    @Test
    @DisplayName("6.4-07 src/main no contiene URLs literales de STUN/TURN")
    fun sinUrlsLiteral() {
        val main = File(System.getProperty("user.dir"), "src/main/kotlin")
        assertTrue(main.isDirectory, "no existe $main")
        val offenders = main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex().mapNotNull { (i, line) ->
                    if (line.contains("\"stun:") || line.contains("\"turn:") || line.contains("\"stuns:") ||
                        line.contains("\"turns:")
                    ) "${file.name}:${i + 1}: ${line.trim()}" else null
                }
            }
            .toList()
        assertEquals(emptyList<String>(), offenders, "URLs ICE hardcodeadas:\n" + offenders.joinToString("\n"))
    }
}
