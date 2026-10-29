package com.example.keymessage.contact

import com.km.model.IdentityId
import com.km.transport.SignalDelivery
import com.km.transport.SignalOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F1 — re-escanear SIEMPRE re-anuncia y re-negocia; arrancar normal NO.
 *
 * ## QUE SE AFIRMA
 *
 * - Import con sesion existente: sale el bundle (`SIGNAL`) Y el PREKEY.
 *   Antes, `abrirSesionYEntregar` hacia `return` temprano por
 *   `sesionInstalada()` y no salia NINGUNO de los dos. El escritorio reiniciado
 *   descartaba el mensaje con el ratchet viejo y no habia ACK.
 * - Import sin sesion: se comporta como antes (bundle -> PREKEY).
 * - Arranque normal (abrir conversacion, pulsar contacto) con sesion: no emite
 *   nada. Re-negociar sin motivo haria divergir los ratchets.
 */
class EntregaDeParTest {

    private val peer = IdentityId("ab".repeat(32))

    private class Espia(var instalada: Boolean) {
        var bundles = 0
        var prekeys = 0
        val portador = byteArrayOf(1, 2, 3)
        fun entrega() = EntregaDePar(
            sesionInstalada = { instalada },
            exportarPortador = { portador },
            enviarPortador = { _, bytes ->
                assertEquals(portador.toList(), bytes.toList())
                bundles++
                SignalOutcome.Delivered(1)
            },
            abrirSesionYEntregar = {
                prekeys++
                Result.success(Unit)
            },
        )
    }

    @Test
    fun `import EXPLICITO con sesion existente emite bundle y PREKEY`() = runBlocking {
        val e = Espia(instalada = true)

        val resultado = e.entrega().ejecutar(peer, explicito = true)

        assertTrue(resultado.isSuccess, resultado.toString())
        assertEquals(1, e.bundles, "el bundle se publica aunque la sesion exista")
        assertEquals(1, e.prekeys, "y el PREKEY re-negocia")
    }

    @Test
    fun `import EXPLICITO sin sesion se comporta como siempre`() = runBlocking {
        val e = Espia(instalada = false)
        assertTrue(e.entrega().ejecutar(peer, explicito = true).isSuccess)
        assertEquals(1, e.bundles)
        assertEquals(1, e.prekeys)
    }

    @Test
    fun `arranque normal SIN sesion abre como siempre`() = runBlocking {
        val e = Espia(instalada = false)
        assertTrue(e.entrega().ejecutar(peer, explicito = false).isSuccess)
        assertEquals(1, e.bundles)
        assertEquals(1, e.prekeys)
    }

    @Test
    fun `arranque normal CON sesion NO emite nada`() = runBlocking {
        val e = Espia(instalada = true)
        assertTrue(e.entrega().ejecutar(peer, explicito = false).isSuccess)
        assertEquals(0, e.bundles, "no se spamea el SIGNAL en cada apertura")
        assertEquals(0, e.prekeys, "ni se reinicia X3DH sin motivo")
    }

    @Test
    fun `si el SIGNAL no llega NO hay PREKEY`() {
        var prekeys = 0
        val entrega = EntregaDePar(
            sesionInstalada = { true },
            exportarPortador = { byteArrayOf(1) },
            enviarPortador = { _, _ ->
                SignalOutcome.Failed(SignalDelivery.CODE_PEER_OFFLINE, 4, "par ausente")
            },
            abrirSesionYEntregar = {
                prekeys++
                Result.success(Unit)
            },
        )

        val fallo = kotlin.test.assertFailsWith<ContactExchangeException> {
            runBlocking { entrega.ejecutar(peer, explicito = true) }
        }
        assertEquals(SignalDelivery.CODE_PEER_OFFLINE, fallo.code)
        assertEquals(0, prekeys, "el orden SIGNAL -> PREKEY es la garantia")
    }
}
