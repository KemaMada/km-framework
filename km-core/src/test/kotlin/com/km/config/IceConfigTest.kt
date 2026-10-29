package com.km.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * S1 — `IceConfig`: carga por entorno, validacion y CERO credenciales en la
 * representacion.
 *
 * El test centinela es el que manda: un `toString` que compile y "se vea bien"
 * no prueba nada si filtra la credencial. Aqui se usa un valor imposible de
 * confundir y se afirma que no aparece ni en `toString` ni en `redactedUrls`.
 */
class IceConfigTest {

    private fun envOf(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { name -> map[name] }
    }

    private val sinEntorno: (String) -> String? = { null }

    private val centinela = "S3NT1N3LA-CREDENCIAL-TURN-0123456789"
    private val usuarioCentinela = "S3NT1N3LA-USUARIO-TURN"

    // ------------------------------------------------------------------
    // Carga y default
    // ------------------------------------------------------------------

    @Test
    @DisplayName("1.3-01 sin entorno la configuracion es host-only y vacia")
    fun sinEntornoEsHostOnly() {
        val config = IceConfig.fromEnv(sinEntorno)
        assertTrue(config.urls.isEmpty())
        assertFalse(config.isConfigured, "sin URLs no hay ICE configurado")
        assertTrue(config.isHostOnly, "sin URLs el medio es host-only")
        assertFalse(config.hasRelayServer)
        assertEquals(IceConfig.hostOnly(), config)
    }

    @Test
    @DisplayName("1.3-02 carga STUN y TURN del entorno, en orden y sin duplicados")
    fun cargaDelEntorno() {
        val config = IceConfig.fromEnv(
            envOf(
                IceConfig.ENV_STUN_URLS to " stun:stun.example.org:3478 , stuns:stun.example.org:5349 ,stun:stun.example.org:3478",
                IceConfig.ENV_TURN_URLS to "turn:turn.example.org:3478?transport=udp",
                IceConfig.ENV_TURN_USERNAME to usuarioCentinela,
                IceConfig.ENV_TURN_CREDENTIAL to centinela,
            ),
        )
        assertEquals(
            listOf("stun:stun.example.org:3478", "stuns:stun.example.org:5349"),
            config.stunUrls,
            "el orden se respeta y el duplicado desaparece",
        )
        assertEquals(listOf("turn:turn.example.org:3478?transport=udp"), config.turnUrls)
        assertEquals(3, config.urls.size)
        assertTrue(config.isConfigured)
        assertTrue(config.hasRelayServer)
        assertFalse(config.isHostOnly)
    }

    @Test
    @DisplayName("1.3-03 las listas vacias del entorno no cuentan como URLs")
    fun listasVacias() {
        val config = IceConfig.fromEnv(
            envOf(
                IceConfig.ENV_STUN_URLS to " , ,",
                IceConfig.ENV_TURN_URLS to "",
            ),
        )
        assertTrue(config.isHostOnly)
    }

    // ------------------------------------------------------------------
    // Validacion
    // ------------------------------------------------------------------

    @Test
    @DisplayName("1.3-04 http(s) se rechaza: seria TURN con credenciales en claro")
    fun rechazaHttp() {
        for (url in listOf("http://turn.example.org:3478", "https://turn.example.org:3478")) {
            assertThrows(IllegalArgumentException::class.java, {
                IceConfig.of(turnUrls = listOf(url), turnUsername = "u", turnCredential = "c")
            }, "debio rechazar $url")
        }
    }

    @Test
    @DisplayName("1.3-05 esquema desconocido o ausente se rechaza")
    fun rechazaEsquemas() {
        assertThrows(IllegalArgumentException::class.java) { IceConfig.of(stunUrls = listOf("ftp://x")) }
        assertThrows(IllegalArgumentException::class.java) { IceConfig.of(stunUrls = listOf("stun.example.org")) }
        assertThrows(IllegalArgumentException::class.java) { IceConfig.of(stunUrls = listOf("stun://")) }
    }

    @Test
    @DisplayName("1.3-06 un esquema TURN no vale como STUN y al reves")
    fun separaStunDeTurn() {
        assertThrows(IllegalArgumentException::class.java) {
            IceConfig.of(stunUrls = listOf("turn:turn.example.org:3478"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            IceConfig.of(turnUrls = listOf("stun:stun.example.org:3478"))
        }
    }

    @Test
    @DisplayName("1.3-07 usuario y credencial van JUNTOS o no van")
    fun credencialesEmparejadas() {
        assertThrows(IllegalArgumentException::class.java) {
            IceConfig.of(turnUrls = listOf("turn:turn.example.org:3478"), turnUsername = "u")
        }
        assertThrows(IllegalArgumentException::class.java) {
            IceConfig.of(turnUrls = listOf("turn:turn.example.org:3478"), turnCredential = "c")
        }
    }

    // ------------------------------------------------------------------
    // Centinela: nada de credenciales en la representacion
    // ------------------------------------------------------------------

    @Test
    @DisplayName("1.3-08 CENTINELA: el toString no contiene usuario ni credencial de TURN")
    fun centinelaToString() {
        val config = IceConfig.of(
            stunUrls = listOf("stun:stun.example.org:3478"),
            turnUrls = listOf("turn:turn.example.org:3478"),
            turnUsername = usuarioCentinela,
            turnCredential = centinela,
        )
        val text = config.toString()
        assertFalse(text.contains(centinela), "FUGA: la credencial aparece en toString: $text")
        assertFalse(text.contains(usuarioCentinela), "FUGA: el usuario aparece en toString: $text")
        assertTrue(text.contains("turn.example.org"), "el toString debe seguir siendo diagnostico")
        assertTrue(text.contains("<redacted>"))
    }

    @Test
    @DisplayName("1.3-09 CENTINELA: redactedUrls elimina userinfo embebido en la URL")
    fun centinelaUserinfo() {
        val config = IceConfig.of(
            turnUrls = listOf("turns:$usuarioCentinela:$centinela@turn.example.org:5349?transport=tcp"),
            turnUsername = usuarioCentinela,
            turnCredential = centinela,
        )
        assertEquals(listOf("turns:turn.example.org:5349"), config.redactedUrls())
        val joined = config.redactedUrls().joinToString(" ")
        assertFalse(joined.contains(centinela))
        assertFalse(joined.contains(usuarioCentinela))
    }

    @Test
    @DisplayName("1.3-10 la credencial real sigue disponible para construir el medio")
    fun credencialDisponible() {
        val config = IceConfig.of(
            turnUrls = listOf("turn:turn.example.org:3478"),
            turnUsername = "usuario",
            turnCredential = "secreto",
        )
        assertEquals("usuario", config.turnUsername)
        assertEquals("secreto", config.turnCredential)
    }

    @Test
    @DisplayName("1.3-11 TURN sin credenciales NO cuenta como relay utilizable")
    fun turnSinCredencialesNoEsRelay() {
        val config = IceConfig.of(turnUrls = listOf("turn:turn.example.org:3478"))
        assertTrue(config.isConfigured, "la URL existe")
        assertFalse(
            config.hasRelayServer,
            "sin credenciales no hay allocation posible: no es un relay utilizable",
        )
    }

    @Test
    @DisplayName("1.3-12 REST: username efimero y credencial HMAC-SHA1 con vector conocido")
    fun credencialEfimeraRest() {
        val config = IceConfig.fromEnv(
            envOf(
                IceConfig.ENV_TURN_URLS to "turn:turn.example.org:3478",
                IceConfig.ENV_TURN_USERNAME to "kmdesktop",
                IceConfig.ENV_TURN_SECRET to "secreto",
                IceConfig.ENV_TURN_TTL_SECONDS to "60",
            ),
        ) { 1_699_999_940_000L }
        // 1699999940 + 60 = 1700000000, el vector fijado con openssl.
        assertEquals("1700000000:kmdesktop", config.turnUsername)
        assertEquals("RmXmFj5dggM6lTTy20MsdXFmY3U=", config.turnCredential)
        assertTrue(config.hasRelayServer, "con secreto hay credencial: el relay es utilizable")
    }

    @Test
    @DisplayName("1.3-13 CENTINELA: el secreto REST no aparece en la representacion")
    fun centinelaSecretoRest() {
        val secreto = "centinela-rest-9f3a"
        val config = IceConfig.of(
            turnUrls = listOf("turn:turn.example.org:3478"),
            turnUsername = "kmdesktop",
            turnSecret = secreto,
        )
        assertFalse(config.toString().contains(secreto), "FUGA: el secreto REST aparece en toString")
        assertFalse(
            config.turnCredential!!.contains(secreto),
            "la credencial derivada no puede contener el secreto",
        )
        assertFalse(config.equals(IceConfig.hostOnly()))
    }

    @Test
    @DisplayName("1.3-14 secreto REST y credencial estatica son excluyentes")
    fun secretoYCredencialEstaticaNoCoexisten() {
        assertThrows(IllegalArgumentException::class.java) {
            IceConfig.of(
                turnUrls = listOf("turn:turn.example.org:3478"),
                turnUsername = "kmdesktop",
                turnCredential = "estatica",
                turnSecret = "secreto",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            IceConfig.of(turnUrls = listOf("turn:turn.example.org:3478"), turnSecret = "secreto")
        }
    }
}
