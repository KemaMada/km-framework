package com.km.webrtc

import com.km.config.IceConfig
import com.km.model.IdentityId
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * ICE REAL contra servidores de verdad. **NO se ejecuta por defecto.**
 *
 * ## Por que gateado
 *
 * Porque la suite de CI no tiene TURN publico ni dos redes distintas, y un test
 * que dependa de traversal convierte una red lenta en un rojo que nadie puede
 * accionar. Se salta salvo que el operador lo pida:
 *
 *     KM_ICE_IT=1 \
 *     KM_ICE_STUN_URLS="stun:13.140.155.230:3479" \
 *     KM_ICE_TURN_URLS="turn:13.140.155.230:3479" \
 *     KM_ICE_TURN_USERNAME=... KM_ICE_TURN_CREDENTIAL=... \
 *     ./gradlew :km-webrtc:test --tests '*RealIceItTest*'
 *
 * ## Que demuestra
 *
 * Que `RealWebRtcTransport` aplica la configuracion RECIBIDA, inicializa el
 * nativo y recolecta candidatos reales. Si hay TURN, exige un candidato
 * `typ relay`: un `srflx` solo demuestra STUN, no relay.
 *
 * NO demuestra entrega de datos entre dos maquinas: eso es la prueba manual de
 * `docs/developer/p2p.md`.
 */
class RealIceItTest {

    private val local = IdentityId("51".repeat(32))
    private val remote = IdentityId("52".repeat(32))

    @Test
    @DisplayName("IT-01 ICE real: aplica la configuracion y recolecta candidatos del medio")
    fun iceReal() {
        assumeTrue(System.getenv("KM_ICE_IT") == "1", "KM_ICE_IT != 1: test de ICE real omitido")
        val ice = IceConfig.fromEnv()
        assumeTrue(ice.isConfigured, "sin KM_ICE_*: no hay servidores que probar")

        val transport = RealWebRtcTransport(ice)
        try {
            assertTrue(transport.initialize().isSuccess, "el nativo de webrtc no inicializo")
            assertTrue(transport.onCreateBinding(local, remote).isSuccess)
            assertTrue(transport.createDataChannel(local, remote, "km-it").isSuccess)
            assertTrue(transport.requestOffer(local, remote).isSuccess)

            assertTrue(
                transport.awaitIceGatheringComplete(local, remote, timeoutMs = 15_000),
                "ICE no completo en 15 s",
            )
            assertFalse(transport.appliedIceServers().isEmpty(), "no se aplico ningun servidor ICE")

            val candidatos = transport.drainPendingIce(local, remote)
            assertTrue(candidatos.isNotEmpty(), "sin candidatos: la configuracion no se uso")
            if (ice.hasRelayServer) {
                assertTrue(
                    candidatos.any { it.contains("typ relay") },
                    "hay TURN configurado y ningun candidato relay: $candidatos",
                )
            }
        } finally {
            runCatching { transport.shutdown() }
        }
    }
}
