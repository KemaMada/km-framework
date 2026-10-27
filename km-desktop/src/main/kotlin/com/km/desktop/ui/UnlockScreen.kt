package com.km.desktop.ui

/** Que campo de la pantalla de desbloqueo tiene el foco. */
enum class UnlockField { PASSPHRASE, REPEAT }

/**
 * El estado de la pantalla de ARRANQUE/DESBLOQUEO.
 *
 * ## POR QUE NO ESTA EN [ShellState]
 *
 * Porque ocurre ANTES de que exista una sesion: no hay contactos, ni enlace, ni
 * pantalla a la que volver. Meterlo en el reducer obligaria a que `Screen`
 * tuviera un valor que no se puede abandonar con `Esc`, y a que el keymap
 * tuviera que saber que en ese estado media tabla no existe. Es una maquina de
 * estados pequena y propia, con su propio test.
 *
 * ## LA FRASE DE PASO NO SE GUARDA COMO TEXTO EN NINGUNA PARTE VISIBLE
 *
 * [passphrase] vive en memoria el tiempo que dura el desbloqueo y se pinta
 * SIEMPRE enmascarada. El renderer no tiene acceso al valor: solo recibe la
 * longitud. Es la unica forma de que un test pueda afirmar que el texto no sale
 * en pantalla sin fiarse de la disciplina del llamador.
 */
data class UnlockState(
    val keystorePath: String,
    val creating: Boolean,
    val bootstraps: List<String>,
    val passphrase: String = "",
    val repeat: String = "",
    val field: UnlockField = UnlockField.PASSPHRASE,
    /** Codigo estable del ultimo fallo. Nunca un mensaje con la frase. */
    val error: String? = null,
    /** Hay una apertura en curso: se ignora el teclado. */
    val busy: Boolean = false,
    /** Lineas de bootstrap ya resueltas (`+ CONFIG`, `+ IDENTIDAD`...). */
    val progress: List<String> = emptyList(),
) {

    /** Lo que se pinta del campo: N asteriscos, nunca el texto. */
    val masked: String get() = "*".repeat(passphrase.length.coerceAtMost(MAX_MASK))

    val maskedRepeat: String get() = "*".repeat(repeat.length.coerceAtMost(MAX_MASK))

    companion object {
        /** Tope de asteriscos: una frase de 200 caracteres no debe romper la caja. */
        const val MAX_MASK: Int = 40
    }
}

/** Lo que devuelve `Enter` en la pantalla de desbloqueo. */
sealed interface UnlockSubmission {
    /** Falta algo: se queda en la pantalla con [state]. */
    data class Invalid(val state: UnlockState) : UnlockSubmission

    /** Hay frase: el llamador intenta abrir el almacen con ella. */
    data class Ready(val passphrase: String) : UnlockSubmission
}

/**
 * Transiciones puras de la pantalla de desbloqueo.
 *
 * No abre el almacen: eso es `open` del llamador. Aqui solo se decide si la
 * frase esta completa, si la repeticion coincide y que se pinta mientras.
 */
object UnlockScreen {

    fun insert(state: UnlockState, text: String): UnlockState {
        if (state.busy || text.isEmpty()) return state
        return if (state.field == UnlockField.PASSPHRASE) {
            state.copy(passphrase = state.passphrase + text, error = null)
        } else {
            state.copy(repeat = state.repeat + text, error = null)
        }
    }

    fun backspace(state: UnlockState): UnlockState {
        if (state.busy) return state
        return if (state.field == UnlockField.PASSPHRASE) {
            state.copy(passphrase = state.passphrase.dropLast(1), error = null)
        } else {
            state.copy(repeat = state.repeat.dropLast(1), error = null)
        }
    }

    /** `Tab`/`Shift-Tab`: solo hay dos campos, y solo cuando se crea. */
    fun cycleField(state: UnlockState): UnlockState {
        if (state.busy || !state.creating) return state
        return state.copy(
            field = if (state.field == UnlockField.PASSPHRASE) UnlockField.REPEAT else UnlockField.PASSPHRASE,
            error = null,
        )
    }

    /**
     * `Enter`.
     *
     * Al CREAR se exige repetir la frase: una errata en la unica frase que
     * protege la identidad la deja irrecuperable.
     */
    fun submit(state: UnlockState): UnlockSubmission {
        if (state.busy) return UnlockSubmission.Invalid(state)
        if (state.passphrase.isEmpty()) {
            return UnlockSubmission.Invalid(state.copy(error = UnlockScreen.Codes.EMPTY))
        }
        if (state.creating && state.repeat != state.passphrase) {
            return UnlockSubmission.Invalid(state.copy(error = UnlockScreen.Codes.MISMATCH))
        }
        return UnlockSubmission.Ready(state.passphrase)
    }

    /** Codigos estables de la pantalla. */
    object Codes {
        const val EMPTY: String = "PASSPHRASE_EMPTY"
        const val MISMATCH: String = "PASSPHRASE_MISMATCH"
        const val WRONG: String = "PASSPHRASE_WRONG"
        const val CORRUPT: String = "KEYSTORE_CORRUPT"
        const val OPEN_FAILED: String = "KEYSTORE_OPEN_FAILED"
    }

    /**
     * Las lineas de la pantalla. Nunca incluye la frase.
     *
     * A diferencia del resto de pantallas, esta se pinta ANTES de que exista un
     * `ShellState`, asi que recibe el [Size] directamente.
     */
    fun render(state: UnlockState, viewport: Size, ascii: Boolean = false): List<RenderedLine> {
        val columns = viewport.columns.coerceAtLeast(Box.MIN_WIDTH)
        val rows = viewport.rows.coerceAtLeast(3)
        val cuerpo = ArrayList<String>()
        cuerpo += "almacen    " + state.keystorePath
        if (state.creating) {
            cuerpo += "identidad  NUEVA: la frase de paso la protege; no se puede recuperar"
        } else {
            cuerpo += "identidad  existente"
        }
        cuerpo += "bootstrap  " + if (state.bootstraps.isEmpty()) "sin destinos" else state.bootstraps.size.toString() + " destino(s)"
        state.bootstraps.take(3).forEachIndexed { i, destino ->
            cuerpo += "  [" + (i + 1) + "] " + destino
        }
        cuerpo += ""
        if (state.creating) {
            cuerpo += "frase de paso: " + state.masked + (if (state.field == UnlockField.PASSPHRASE) "\u2588" else "")
            cuerpo += "repita la frase: " + state.maskedRepeat + (if (state.field == UnlockField.REPEAT) "\u2588" else "")
        } else {
            cuerpo += "frase de paso: " + state.masked + "\u2588"
        }
        state.error?.let { cuerpo += "error: " + it }
        cuerpo += ""
        cuerpo += if (state.busy) "abriendo el almacen\u2026" else "[ENTER] desbloquear   [Ctrl-C] salir"
        if (state.progress.isNotEmpty()) {
            cuerpo += ""
            state.progress.forEach { cuerpo += it }
        }
        cuerpo += ""
        cuerpo += "sin porcentajes: los pasos se declaran cuando ocurren"

        val presupuesto = rows - 2
        val ajustado = if (cuerpo.size <= presupuesto) cuerpo else cuerpo.take(presupuesto)
        val salida = ArrayList<RenderedLine>(rows)
        salida += RenderedLine(Box.top("KeyMessage \u00b7 DESBLOQUEO", columns, ascii))
        ajustado.forEach { salida += RenderedLine(Box.line(it, columns, ascii)) }
        while (salida.size < rows - 1) salida.add(RenderedLine(Box.line("", columns, ascii)))
        salida += RenderedLine(Box.bottom(columns, ascii))
        return salida
    }
}
