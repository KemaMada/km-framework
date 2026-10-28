package com.km.desktop.ui

import com.km.desktop.ui.render.OverlayWindow
import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.model.MessageState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

/**
 * M5.3 — Toasts, spinner y Tick SIN reloj del sistema.
 *
 * El reducer recibe el instante como DATO (`Tick`) y el renderer solo compara
 * `nowMs` con `expiresAtMs`. Estos tests fijan los dos y afirman el
 * comportamiento: si alguien mete `System.currentTimeMillis` dentro del
 * reducer o del renderer, el test deja de ser determinista y falla.
 */
class ToastTickTest {

    private val ana = IdentityId("a".repeat(64))

    private fun estado(nowMs: Long = 1_000L) = ShellState(
        viewport = Size(80, 24),
        nowMs = nowMs,
        status = StatusLine(
            link = LinkState.ONLINE,
            endpoint = "wss://relay.example/km",
            localFingerprint = "f".repeat(16) + "\u2026",
            localIdentityId = "f".repeat(64),
            sessions = 1,
            ackKeys = 2,
        ),
        conversation = ConversationView(peer = ana, title = "ana", composer = "hola"),
    )

    private fun render(state: ShellState, nowMs: Long): String =
        ShellView.renderLines(state, false, true, RenderClock(ZoneOffset.UTC, nowMs))
            .joinToString("\n") { it.text }

    @Test
    fun `un envio deja un toast honesto con 4 segundos`() {
        val r = ShellReducer.reduce(
            estado(),
            ShellAction.EffectDone(EffectResult.Sent(MessageId.random(), MessageState.SENT, "hola", 0L)),
        )
        val toast = r.state.toast!!
        assertEquals(ToastVariant.INFO, toast.variant)
        assertEquals(1_000L + ShellReducer.INFO_TOAST_MS, toast.expiresAtMs)
        assertTrue(toast.title.contains("en cola local"), toast.title)
        assertTrue(toast.message!!.contains("no es entrega"), toast.message!!)

        assertTrue(render(r.state, 4_999L).contains("en cola local"), "vivo antes de caducar")
        assertTrue(!render(r.state, 5_000L).contains("en cola local"), "oculto al caducar")
    }

    @Test
    fun `un error dura el doble y se caduca con Tick`() {
        val r = ShellReducer.reduce(estado(2_000L), ShellAction.EffectDone(EffectResult.SendRejected("NO_SESSION")))
        val toast = r.state.toast!!
        assertEquals(ToastVariant.ERROR, toast.variant)
        assertEquals(2_000L + ShellReducer.ERROR_TOAST_MS, toast.expiresAtMs)

        val antes = ShellReducer.reduce(r.state, ShellAction.Tick(9_999L))
        assertNotNull(antes.state.toast, "aun vivo")
        assertEquals(9_999L, antes.state.nowMs)

        val despues = ShellReducer.reduce(antes.state, ShellAction.Tick(10_000L))
        assertNull(despues.state.toast, "caducado en el instante exacto")
        assertFalse(despues.state.needsTick)
    }

    @Test
    fun `needsTick solo pide reloj con un efecto en vuelo o un toast`() {
        val quieto = estado()
        assertTrue(!quieto.needsTick, "sin toast ni envio no hay temporizador")

        val conToast = quieto.copy(
            toast = Toast(ToastVariant.INFO, "copiado", expiresAtMs = 5_000L),
        )
        assertTrue(conToast.needsTick)

        val enviando = quieto.copy(
            conversation = quieto.conversation!!.copy(sending = true),
        )
        assertTrue(enviando.needsTick)
    }
}
