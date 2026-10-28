package com.km.desktop.ui

import com.km.desktop.client.RecoveryService

/**
 * Que camino de arranque se esta siguiendo.
 *
 * ## POR QUE HAY UNA ELECCION EXPLICITA
 *
 * La TUI pedia siempre una "frase de paso", y el usuario la confundia con la
 * "frase de recuperacion" de Android. Son cosas distintas:
 *
 * - **frase de paso**: cifra el almacen de ESTE equipo. No deriva la identidad.
 * - **frase de recuperacion**: 24 palabras BIP39 que derivan la IDENTIDAD; es la
 *   misma que muestra Android.
 *
 * [CHOICE] solo aparece cuando NO hay almacen: antes de pedir nada se elige
 * crear una identidad nueva o recuperar la que ya se tiene en otro dispositivo.
 */
enum class UnlockMode {
    /** Sin almacen: elegir [CREATE] o [RECOVER]. */
    CHOICE,

    /** Crear una identidad nueva protegida por una frase de paso nueva. */
    CREATE,

    /** Abrir un almacen existente con su frase de paso. */
    UNLOCK,

    /** Restaurar la identidad desde la frase de recuperacion de 24 palabras. */
    RECOVER,
}

/** Campo de la pantalla de desbloqueo que tiene el foco. */
enum class UnlockField {
    CHOICE,
    PHRASE,
    PASSPHRASE,
    REPEAT,
    CONFIRMATION,
}

/**
 * Validador de la frase de 24 palabras, INYECTADO.
 *
 * `ui/` no importa la wordlist ni el checksum: el modelo solo necesita saber si
 * la frase vale. La implementacion de produccion usa `Mnemonic.parse` de
 * km-core (lo hace `UnlockGate`); los tests inyectan uno de mentira y asi se
 * prueba la maquina de estados sin cargar 2048 palabras.
 *
 * El valor por defecto RECHAZA: si alguien olvida inyectar el validador, una
 * restauracion no puede colarse por accidente.
 */
fun interface PhraseValidator {
    fun isValid(text: String): Boolean

    companion object {
        /** Validador que rechaza todo: el defecto seguro. */
        val NONE: PhraseValidator = PhraseValidator { false }
    }
}

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
 * ## LOS SECRETOS NO SALEN DE AQUI
 *
 * [passphrase], [repeat], [recovery] y [confirmation] viven en memoria el tiempo
 * que dura el desbloqueo. El renderer NO los recibe: pinta [masked],
 * [recoveryWordCount] y la confirmacion, que no es secreta. [toString] esta
 * REDACTADO a proposito, porque un `data class` sin el volcaria la frase en
 * cualquier log o asercion de test.
 */
data class UnlockState(
    val keystorePath: String,
    /** `true` cuando NO existe almacen en este equipo. */
    val creating: Boolean,
    val bootstraps: List<String>,
    /** Camino elegido. Sin almacen arranca en [UnlockMode.CHOICE]. */
    val mode: UnlockMode = if (creating) UnlockMode.CHOICE else UnlockMode.UNLOCK,
    val passphrase: String = "",
    val repeat: String = "",
    /** Frase de recuperacion en crudo. NUNCA se pinta; solo su conteo. */
    val recovery: String = "",
    /** Confirmacion escrita para sobrescribir un almacen existente. */
    val confirmation: String = "",
    val field: UnlockField = if (creating) UnlockField.CHOICE else UnlockField.PASSPHRASE,
    /** Codigo estable del ultimo fallo. Nunca un mensaje con la frase. */
    val error: String? = null,
    /** Hay una apertura en curso: se ignora el teclado. */
    val busy: Boolean = false,
    /** Lineas de bootstrap ya resueltas (`+ CONFIG`, `+ IDENTIDAD`...). */
    val progress: List<String> = emptyList(),
) {

    /** Hay un almacen previo: recuperar lo sobrescribe y exige confirmacion. */
    val hasKeystore: Boolean get() = !creating

    /** Lo que se pinta del campo: N asteriscos, nunca el texto. */
    val masked: String get() = "*".repeat(passphrase.length.coerceAtMost(MAX_MASK))

    val maskedRepeat: String get() = "*".repeat(repeat.length.coerceAtMost(MAX_MASK))

    /** Cuantas palabras lleva la frase de recuperacion. Solo el contador se pinta. */
    val recoveryWordCount: Int
        get() = recovery.trim()
            .split(Regex("\\s+"))
            .count { it.isNotEmpty() }

    /**
     * Redactado NO por estetica: un `data class` imprime todos sus campos, y
     * estos incluyen la frase de paso y las 24 palabras.
     */
    override fun toString(): String =
        "UnlockState(keystorePath=$keystorePath, mode=$mode, field=$field, " +
            "passphrase=${passphrase.length} chars, repeat=${repeat.length} chars, " +
            "recovery=${recoveryWordCount}/24 palabras, confirmation=${confirmation.length} chars, " +
            "error=$error, busy=$busy)"

    companion object {
        /** Tope de asteriscos: una frase de 200 caracteres no debe romper la caja. */
        const val MAX_MASK: Int = 40
    }
}

/** Lo que devuelve `Enter` en la pantalla de desbloqueo. */
sealed interface UnlockSubmission {
    /** Falta algo: se queda en la pantalla con [state]. */
    data class Invalid(val state: UnlockState) : UnlockSubmission

    /** Hay frase de paso: el llamador crea o abre el almacen con ella. */
    data class Ready(val passphrase: String) : UnlockSubmission

    /**
     * Restauracion completa: frase de 24 palabras + NUEVA frase de paso.
     *
     * El llamador valida otra vez, escribe el almacen y abre la sesion con
     * [passphrase] sin reiniciar. [toString] esta redactado.
     */
    data class Recover(
        val phrase: String,
        val passphrase: String,
        val confirmation: String,
    ) : UnlockSubmission {
        override fun toString(): String =
            "Recover(phrase=${phrase.length} chars, passphrase=${passphrase.length} chars, " +
                "confirmation=${confirmation.length} chars)"
    }
}

/**
 * Transiciones puras de la pantalla de desbloqueo.
 *
 * No abre ni escribe el almacen: eso es del llamador. Aqui solo se decide que
 * camino se eligio, que campo tiene el foco, si la frase vale y que se pinta.
 */
object UnlockScreen {

    fun insert(state: UnlockState, text: String): UnlockState {
        if (state.busy || text.isEmpty()) return state
        return when (state.field) {
            UnlockField.CHOICE -> state
            UnlockField.PHRASE -> state.copy(recovery = state.recovery + text, error = null)
            UnlockField.PASSPHRASE -> state.copy(passphrase = state.passphrase + text, error = null)
            UnlockField.REPEAT -> state.copy(repeat = state.repeat + text, error = null)
            UnlockField.CONFIRMATION -> state.copy(confirmation = state.confirmation + text, error = null)
        }
    }

    fun backspace(state: UnlockState): UnlockState {
        if (state.busy) return state
        return when (state.field) {
            UnlockField.CHOICE -> state
            UnlockField.PHRASE -> state.copy(recovery = state.recovery.dropLast(1), error = null)
            UnlockField.PASSPHRASE -> state.copy(passphrase = state.passphrase.dropLast(1), error = null)
            UnlockField.REPEAT -> state.copy(repeat = state.repeat.dropLast(1), error = null)
            UnlockField.CONFIRMATION -> state.copy(confirmation = state.confirmation.dropLast(1), error = null)
        }
    }

    /**
     * `Tab`/`Shift-Tab`: recorre SOLO los campos que existen en este camino.
     *
     * Al abrir un almacen existente no hay repeticion ni eleccion: el foco no se
     * mueve. Al recuperar sobre un almacen existente si aparece la confirmacion.
     */
    fun cycleField(state: UnlockState): UnlockState {
        if (state.busy) return state
        val campos = fieldsFor(state)
        if (campos.size <= 1) return state
        val actual = campos.indexOf(state.field).let { if (it < 0) 0 else it }
        return state.copy(field = campos[(actual + 1) % campos.size], error = null)
    }

    /** Los campos del modo actual, en orden. */
    fun fieldsFor(state: UnlockState): List<UnlockField> = when (state.mode) {
        UnlockMode.CHOICE -> listOf(UnlockField.CHOICE)
        UnlockMode.CREATE -> listOf(UnlockField.PASSPHRASE, UnlockField.REPEAT)
        UnlockMode.UNLOCK -> listOf(UnlockField.PASSPHRASE)
        UnlockMode.RECOVER -> buildList {
            add(UnlockField.PHRASE)
            add(UnlockField.PASSPHRASE)
            add(UnlockField.REPEAT)
            if (state.hasKeystore) add(UnlockField.CONFIRMATION)
        }
    }

    /**
     * Elige un camino y LIMPIA los secretos del anterior.
     *
     * Sin almacen se puede alternar [UnlockMode.CREATE] y [UnlockMode.RECOVER]
     * antes de enviar nada. Con almacen, [UnlockMode.RECOVER] esta disponible
     * para sobrescribir, y [UnlockMode.UNLOCK] para volver.
     */
    fun choose(state: UnlockState, mode: UnlockMode): UnlockState {
        if (state.busy) return state
        val permitido = if (state.hasKeystore) {
            mode == UnlockMode.UNLOCK || mode == UnlockMode.RECOVER
        } else {
            mode == UnlockMode.CREATE || mode == UnlockMode.RECOVER
        }
        if (!permitido) return state
        return state.copy(
            mode = mode,
            passphrase = "",
            repeat = "",
            recovery = "",
            confirmation = "",
            error = null,
            field = when (mode) {
                UnlockMode.CHOICE -> UnlockField.CHOICE
                UnlockMode.CREATE, UnlockMode.UNLOCK -> UnlockField.PASSPHRASE
                UnlockMode.RECOVER -> UnlockField.PHRASE
            },
        )
    }

    /**
     * `Esc`: vuelve al punto de partida SIN arrastrar secretos.
     *
     * Sin almacen vuelve a la eleccion (es la forma de cambiar de camino una vez
     * empezado un formulario); con almacen vuelve a pedir la frase de paso.
     */
    fun escape(state: UnlockState): UnlockState {
        if (state.busy) return state
        // `choose` no acepta CHOICE por diseno (no es un camino que se elija con
        // una tecla), asi que el retorno sin almacen se arma aqui.
        return if (state.hasKeystore) {
            state.copy(
                mode = UnlockMode.UNLOCK,
                field = UnlockField.PASSPHRASE,
                passphrase = "",
                repeat = "",
                recovery = "",
                confirmation = "",
                error = null,
            )
        } else {
            state.copy(
                mode = UnlockMode.CHOICE,
                field = UnlockField.CHOICE,
                passphrase = "",
                repeat = "",
                recovery = "",
                confirmation = "",
                error = null,
            )
        }
    }

    /**
     * Atajos de una sola tecla. Devuelve `null` si la tecla es TEXTO normal.
     *
     * - En [UnlockMode.CHOICE], `C`/`R` eligen camino.
     * - Con almacen y la frase de paso vacia, `R` mayuscula abre la
     *   restauracion. Se exige el buffer vacio para no robar una letra de una
     *   frase que empiece por R.
     * - Dentro de RECOVER no hay atajos de letra: todas las teclas son palabras
     *   BIP39 (hay palabras con `c` y con `r`), y `Esc` es el camino de vuelta.
     */
    fun shortcut(state: UnlockState, char: Char): UnlockState? = when {
        state.busy -> null
        state.mode == UnlockMode.CHOICE && (char == 'c' || char == 'C') -> choose(state, UnlockMode.CREATE)
        state.mode == UnlockMode.CHOICE && (char == 'r' || char == 'R') -> choose(state, UnlockMode.RECOVER)
        state.mode == UnlockMode.UNLOCK && state.passphrase.isEmpty() && char == 'R' ->
            choose(state, UnlockMode.RECOVER)
        else -> null
    }

    /**
     * `Enter`.
     *
     * Al CREAR se exige repetir la frase: una errata en la unica frase que
     * protege la identidad la deja irrecuperable. Al RECUPERAR se valida la
     * frase ANTES que nada: una frase invalida no puede llegar ni a pedir la
     * confirmacion, y menos a escribir.
     */
    fun submit(
        state: UnlockState,
        validator: PhraseValidator = PhraseValidator.NONE,
        confirmationWord: String = RecoveryService.CONFIRMATION,
    ): UnlockSubmission {
        if (state.busy) return UnlockSubmission.Invalid(state)
        return when (state.mode) {
            UnlockMode.CHOICE -> UnlockSubmission.Invalid(
                state.copy(error = UnlockScreen.Codes.CHOICE_REQUIRED),
            )

            UnlockMode.UNLOCK, UnlockMode.CREATE -> {
                if (state.passphrase.isEmpty()) {
                    return UnlockSubmission.Invalid(
                        state.copy(field = UnlockField.PASSPHRASE, error = UnlockScreen.Codes.EMPTY),
                    )
                }
                if (state.mode == UnlockMode.CREATE && state.repeat != state.passphrase) {
                    return UnlockSubmission.Invalid(
                        state.copy(field = UnlockField.REPEAT, error = UnlockScreen.Codes.MISMATCH),
                    )
                }
                UnlockSubmission.Ready(state.passphrase)
            }

            UnlockMode.RECOVER -> {
                if (state.recovery.isBlank()) {
                    return UnlockSubmission.Invalid(
                        state.copy(field = UnlockField.PHRASE, error = UnlockScreen.Codes.PHRASE_EMPTY),
                    )
                }
                if (!validator.isValid(state.recovery)) {
                    return UnlockSubmission.Invalid(
                        state.copy(field = UnlockField.PHRASE, error = UnlockScreen.Codes.PHRASE_INVALID),
                    )
                }
                if (state.passphrase.isEmpty()) {
                    return UnlockSubmission.Invalid(
                        state.copy(field = UnlockField.PASSPHRASE, error = UnlockScreen.Codes.EMPTY),
                    )
                }
                if (state.repeat != state.passphrase) {
                    return UnlockSubmission.Invalid(
                        state.copy(field = UnlockField.REPEAT, error = UnlockScreen.Codes.MISMATCH),
                    )
                }
                if (state.hasKeystore && state.confirmation.trim() != confirmationWord) {
                    return UnlockSubmission.Invalid(
                        state.copy(
                            field = UnlockField.CONFIRMATION,
                            error = UnlockScreen.Codes.CONFIRMATION_REQUIRED,
                        ),
                    )
                }
                UnlockSubmission.Recover(state.recovery, state.passphrase, state.confirmation)
            }
        }
    }

    /** Campo que debe recuperar el foco cuando el llamador devuelve un codigo. */
    fun fieldAfterError(state: UnlockState, code: String): UnlockField = when (code) {
        UnlockScreen.Codes.PHRASE_INVALID, UnlockScreen.Codes.PHRASE_EMPTY -> UnlockField.PHRASE
        UnlockScreen.Codes.EMPTY -> UnlockField.PASSPHRASE
        UnlockScreen.Codes.MISMATCH -> UnlockField.REPEAT
        UnlockScreen.Codes.CONFIRMATION_REQUIRED -> UnlockField.CONFIRMATION
        else -> if (state.mode == UnlockMode.CHOICE) UnlockField.CHOICE else UnlockField.PASSPHRASE
    }

    /** Codigos estables de la pantalla. */
    object Codes {
        const val EMPTY: String = "PASSPHRASE_EMPTY"
        const val MISMATCH: String = "PASSPHRASE_MISMATCH"
        const val WRONG: String = "PASSPHRASE_WRONG"
        const val CORRUPT: String = "KEYSTORE_CORRUPT"
        const val OPEN_FAILED: String = "KEYSTORE_OPEN_FAILED"
        const val CHOICE_REQUIRED: String = "CHOICE_REQUIRED"
        const val PHRASE_EMPTY: String = "PHRASE_EMPTY"
        const val PHRASE_INVALID: String = "PHRASE_INVALID"
        const val CONFIRMATION_REQUIRED: String = "CONFIRMATION_REQUIRED"
    }

    /**
     * Las lineas de la pantalla. Nunca incluye la frase ni la passphrase.
     *
     * A diferencia del resto de pantallas, esta se pinta ANTES de que exista un
     * `ShellState`, asi que recibe el [Size] directamente.
     */
    fun render(state: UnlockState, viewport: Size, ascii: Boolean = false): List<RenderedLine> {
        val columns = viewport.columns.coerceAtLeast(Box.MIN_WIDTH)
        val rows = viewport.rows.coerceAtLeast(3)
        val cuerpo = ArrayList<String>()
        cuerpo += "almacen    " + state.keystorePath
        cuerpo += "identidad  " + when (state.mode) {
            UnlockMode.CHOICE -> "no hay almacen en este equipo"
            UnlockMode.CREATE -> "NUEVA: la frase de paso la protege; no se puede recuperar"
            UnlockMode.UNLOCK -> "existente"
            UnlockMode.RECOVER -> "se RESTAURA desde la frase de recuperacion"
        }
        cuerpo += "bootstrap  " + if (state.bootstraps.isEmpty()) "sin destinos" else state.bootstraps.size.toString() + " destino(s)"
        state.bootstraps.take(3).forEachIndexed { i, destino ->
            cuerpo += "  [" + (i + 1) + "] " + destino
        }
        cuerpo += ""

        when (state.mode) {
            UnlockMode.CHOICE -> {
                cuerpo += "  [C] crear una identidad nueva en este equipo"
                cuerpo += "  [R] recuperar desde la frase de recuperacion (24 palabras)"
                cuerpo += ""
                cuerpo += "frase de paso        cifra el almacen de ESTE equipo"
                cuerpo += "frase de recuperacion deriva la IDENTIDAD (la misma que Android)"
                cuerpo += "recuperar restaura SOLO la identidad: ni contactos ni mensajes"
                cuerpo += "y regenera las claves de dispositivo: el peer vera un device nuevo"
                cuerpo += ""
                cuerpo += "[C] crear   [R] recuperar   [Ctrl-C] salir"
            }

            UnlockMode.CREATE -> {
                cuerpo += fraseDePaso(state, "frase de paso: ")
                cuerpo += fraseDePaso(state, "repita la frase: ", repetir = true)
                cuerpo += ""
                cuerpo += "la frase de paso NO es la frase de recuperacion: solo cifra este equipo"
                cuerpo += "[TAB] campo   [ENTER] crear   [ESC] cambiar de camino   [Ctrl-C] salir"
            }

            UnlockMode.UNLOCK -> {
                cuerpo += fraseDePaso(state, "frase de paso: ")
                if (state.passphrase.isEmpty()) {
                    cuerpo += ""
                    cuerpo += "[R] restaurar desde la frase de recuperacion (sobrescribe)"
                }
                cuerpo += ""
                cuerpo += "[ENTER] desbloquear   [Ctrl-C] salir"
            }

            UnlockMode.RECOVER -> {
                cuerpo += "frase de recuperacion: " + state.recoveryWordCount + "/24" +
                    if (state.field == UnlockField.PHRASE) "\u2588" else ""
                cuerpo += fraseDePaso(state, "nueva frase de paso: ")
                cuerpo += fraseDePaso(state, "repita la frase: ", repetir = true)
                if (state.hasKeystore) {
                    cuerpo += "confirmacion: " + state.confirmation +
                        (if (state.field == UnlockField.CONFIRMATION) "\u2588" else "")
                }
                cuerpo += ""
                cuerpo += "restaura la IDENTIDAD; no contactos ni mensajes; regenera claves de dispositivo"
                cuerpo += if (state.hasKeystore) {
                    "[TAB] campo   [ENTER] restaurar   [ESC] volver   [Ctrl-C] salir"
                } else {
                    "[TAB] campo   [ENTER] restaurar   [ESC] cambiar de camino   [Ctrl-C] salir"
                }
            }
        }
        state.error?.let {
            cuerpo += ""
            cuerpo += "error: " + it
        }
        cuerpo += ""
        cuerpo += if (state.busy) "abriendo el almacen\u2026" else "sin porcentajes: los pasos se declaran cuando ocurren"
        if (state.progress.isNotEmpty()) {
            cuerpo += ""
            state.progress.forEach { cuerpo += it }
        }

        val presupuesto = rows - 2
        val ajustado = if (cuerpo.size <= presupuesto) cuerpo else cuerpo.take(presupuesto)
        val salida = ArrayList<RenderedLine>(rows)
        salida += RenderedLine(Box.top("KeyMessage \u00b7 DESBLOQUEO", columns, ascii))
        ajustado.forEach { salida += RenderedLine(Box.line(it, columns, ascii)) }
        while (salida.size < rows - 1) salida.add(RenderedLine(Box.line("", columns, ascii)))
        salida += RenderedLine(Box.bottom(columns, ascii))
        return salida
    }

    /** Campo enmascarado con el cursor al final si tiene el foco. */
    private fun fraseDePaso(
        state: UnlockState,
        etiqueta: String,
        repetir: Boolean = false,
    ): String {
        val activo = state.field == if (repetir) UnlockField.REPEAT else UnlockField.PASSPHRASE
        val mascara = if (repetir) state.maskedRepeat else state.masked
        return etiqueta + mascara + if (activo) "\u2588" else ""
    }
}
