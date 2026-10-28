package com.km.desktop.shell

import com.km.desktop.IdentityKeystore
import com.km.desktop.KeystoreException
import com.km.desktop.Session
import com.km.desktop.ui.PhraseValidator
import com.km.desktop.ui.UnlockField
import com.km.desktop.ui.UnlockMode
import com.km.desktop.ui.UnlockScreen
import com.km.desktop.ui.UnlockState
import com.km.desktop.ui.UnlockSubmission
import com.km.mnemonic.Mnemonic
import com.km.mnemonic.MnemonicException
import org.jline.terminal.Terminal
import org.jline.utils.NonBlockingReader
import java.nio.file.Path

/**
 * La pantalla de ARRANQUE/DESBLOQUEO: identidad, frase de paso y recuperacion.
 *
 * ## POR QUE ES UN BUCLE APARTE DEL SHELL
 *
 * Porque la identidad se abre ANTES de que exista red, y ese orden es una regla
 * de seguridad: un cliente que arrancara sin identidad y la inventara al vuelo
 * produciria un `identityId` distinto en cada ejecucion. El shell de pantallas
 * necesita una [Session] ya construida; esta puerta es la que la construye.
 *
 * ## TRES CAMINOS, UNA SOLA PUERTA
 *
 * - Sin almacen: [UnlockMode.CHOICE] ofrece crear o recuperar antes de pedir
 *   nada.
 * - Con almacen: se pide la frase de paso; `R` (con el buffer vacio) abre la
 *   restauracion, que sobrescribe y exige la confirmacion escrita.
 * - Recuperar escribe el almacen y ABRE LA SESION en el acto con la frase de
 *   paso nueva. No hay sesion viva todavia, asi que no hace falta reiniciar.
 *
 * ## EL TERMINAL SIGUE TENIENDO UN DUENO
 *
 * Usa el MISMO [ScreenWriter] que el shell y termina antes de que el shell
 * empiece: no hay dos escritores concurrentes, hay uno que cambia de fase.
 *
 * ## SIN PORCENTAJES
 *
 * Los pasos se declaran cuando TERMINAN (`+ CONFIG`, `+ IDENTIDAD`, `+ NUCLEO`).
 * Una barra que avanza sin saber cuanto falta es una promesa que el cliente no
 * puede cumplir.
 */
class UnlockGate(
    private val terminal: Terminal,
    private val writer: ScreenWriter,
    private val keystorePath: Path,
    private val bootstraps: List<String>,
    private val ascii: Boolean,
) {

    /**
     * La validacion REAL de la frase, con el framework.
     *
     * `ui/` no conoce la wordlist ni el checksum: aqui se inyecta la unica
     * implementacion (`Mnemonic.parse`), y `IdentityProvisioner` vuelve a
     * validar justo antes de escribir. Dos puertas, el mismo validador.
     */
    private val validator = PhraseValidator { texto ->
        try {
            Mnemonic.parse(texto)
            true
        } catch (fallo: MnemonicException) {
            false
        }
    }

    /**
     * Pinta, pide la frase y abre la sesion con [open].
     *
     * @return la sesion abierta, o `null` si el usuario salio (Ctrl-C / EOF).
     * @throws KeystoreException solo si [open] la lanza con un codigo que la
     *   pantalla no reconoce: los casos conocidos se muestran y se reintenta.
     */
    fun run(open: (CharArray) -> Result<Session>): Session? {
        var estado = UnlockState(
            keystorePath = keystorePath.toString(),
            creating = !IdentityKeystore.exists(keystorePath),
            bootstraps = bootstraps,
        )
        writer.enter()
        val reader = terminal.reader()
        val anterior = runCatching { terminal.enterRawMode() }.getOrNull()
        try {
            while (true) {
                writer.draw(UnlockScreen.render(estado, writer.size(), ascii))
                val code = reader.read(READ_TIMEOUT_MS)
                if (code == NonBlockingReader.EOF) return null
                if (code < 0) continue
                if (code == CTRL_C) return null

                when {
                    code == ENTER || code == LF -> {
                        when (val envio = UnlockScreen.submit(estado, validator)) {
                            is UnlockSubmission.Invalid -> estado = envio.state
                            is UnlockSubmission.Ready -> {
                                estado = estado.copy(
                                    busy = true,
                                    error = null,
                                    // La frase no se queda en el estado visible:
                                    // se borra en cuanto se entrega al almacen.
                                    passphrase = "",
                                    repeat = "",
                                    progress = listOf(
                                        "+ CONFIG  bootstrap: " + estado.bootstraps.size + " destino(s)",
                                    ),
                                )
                                writer.draw(UnlockScreen.render(estado, writer.size(), ascii))
                                val sesion = open(envio.passphrase.toCharArray())
                                val abierta = sesion.getOrNull()
                                if (abierta != null) {
                                    estado = estado.copy(
                                        progress = estado.progress + listOf(
                                            "+ IDENTIDAD  almacen abierto, " +
                                                abierta.identity.identityId.value.take(16) + "\u2026",
                                            "+ NUCLEO  registro y manager; sin texto plano",
                                        ),
                                    )
                                    writer.draw(UnlockScreen.render(estado, writer.size(), ascii))
                                    return abierta
                                }
                                estado = estado.copy(
                                    busy = false,
                                    error = codigoDe(sesion.exceptionOrNull()),
                                    passphrase = "",
                                    repeat = "",
                                    field = UnlockField.PASSPHRASE,
                                    progress = emptyList(),
                                )
                            }

                            is UnlockSubmission.Recover -> {
                                estado = estado.copy(busy = true, error = null)
                                writer.draw(UnlockScreen.render(estado, writer.size(), ascii))
                                val resultado = IdentityProvisioner.restore(
                                    path = keystorePath,
                                    phrase = envio.phrase,
                                    newPassphrase = envio.passphrase.toCharArray(),
                                    confirmation = envio.confirmation,
                                    // `creating` se fijo al abrir la puerta y no
                                    // cambia: si habia almacen, restaurar lo
                                    // sobrescribe y exige confirmacion.
                                    overwriting = estado.hasKeystore,
                                )
                                if (resultado is IdentityProvisioner.Outcome.Rejected) {
                                    estado = estado.copy(
                                        busy = false,
                                        error = resultado.code,
                                        field = UnlockScreen.fieldAfterError(estado, resultado.code),
                                    )
                                } else {
                                    val restaurada = (resultado as IdentityProvisioner.Outcome.Restored)
                                    estado = estado.copy(
                                        busy = false,
                                        // El secreto no sobrevive a la escritura.
                                        passphrase = "",
                                        repeat = "",
                                        recovery = "",
                                        confirmation = "",
                                        field = UnlockField.PASSPHRASE,
                                        progress = listOf(
                                            "+ FRASE  24/24 validada; identidad " +
                                                restaurada.identityId.take(16) + "\u2026",
                                            "+ ALMACEN  escrito con la frase de paso nueva; claves de dispositivo regeneradas",
                                            "+ CONFIG  bootstrap: " + estado.bootstraps.size + " destino(s)",
                                        ),
                                    )
                                    writer.draw(UnlockScreen.render(estado, writer.size(), ascii))
                                    // Se abre con la MISMA frase de paso recien
                                    // tecleada: no hay sesion viva ni reinicio.
                                    val sesion = open(envio.passphrase.toCharArray())
                                    val abierta = sesion.getOrNull()
                                    if (abierta != null) {
                                        estado = estado.copy(
                                            progress = estado.progress + listOf(
                                                "+ NUCLEO  registro y manager; sin texto plano",
                                            ),
                                        )
                                        writer.draw(UnlockScreen.render(estado, writer.size(), ascii))
                                        return abierta
                                    }
                                    // La escritura fue bien; lo que falla es la
                                    // apertura. Se deja el camino de desbloqueo
                                    // (el almacen ya existe) para reintentar.
                                    estado = estado.copy(
                                        creating = false,
                                        mode = UnlockMode.UNLOCK,
                                        field = UnlockField.PASSPHRASE,
                                        error = codigoDe(sesion.exceptionOrNull()),
                                        progress = emptyList(),
                                    )
                                }
                            }
                        }
                    }

                    code == ESCAPE -> estado = UnlockScreen.escape(estado)
                    code == TAB -> estado = UnlockScreen.cycleField(estado)
                    code == BACKSPACE || code == DELETE -> estado = UnlockScreen.backspace(estado)
                    else -> {
                        val stroke = KeyCodec.decode(code)
                        val text = stroke?.text
                        if (text != null) {
                            // Los atajos solo existen donde no hay texto que
                            // escribir (CHOICE) o con el buffer vacio (R).
                            val atajo = UnlockScreen.shortcut(estado, text.first())
                            estado = atajo ?: UnlockScreen.insert(estado, text)
                        }
                    }
                }
            }
        } finally {
            anterior?.let { runCatching { terminal.setAttributes(it) } }
        }
    }

    /**
     * Traduce el fallo del almacen a un CODIGO estable.
     *
     * La pantalla no pinta el mensaje de la excepcion: puede arrastrar una ruta
     * (util) o un detalle de implementacion (no). El codigo es el contrato.
     */
    private fun codigoDe(fallo: Throwable?): String = when (fallo) {
        is KeystoreException.WrongPassphrase -> UnlockScreen.Codes.WRONG
        is KeystoreException.Corrupt -> UnlockScreen.Codes.CORRUPT
        else -> UnlockScreen.Codes.OPEN_FAILED
    }

    companion object {
        private const val READ_TIMEOUT_MS: Long = 500L
        private const val CTRL_C: Int = 3
        private const val ENTER: Int = 13
        private const val LF: Int = 10
        private const val ESCAPE: Int = 27
        private const val TAB: Int = 9
        private const val BACKSPACE: Int = 8
        private const val DELETE: Int = 127
    }
}
