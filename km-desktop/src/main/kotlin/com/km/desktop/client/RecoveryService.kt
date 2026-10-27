package com.km.desktop.client

import com.km.desktop.DesktopIdentity
import com.km.desktop.IdentityKeystore
import com.km.desktop.ui.PhraseValidation
import com.km.mnemonic.Mnemonic
import com.km.mnemonic.MnemonicException
import java.nio.file.Path

/**
 * La FRASE DE RECUPERACION del escritorio: revelar, validar y restaurar.
 *
 * ## QUE RESTAURA, Y SE DICE EN PANTALLA
 *
 * Solo la IDENTIDAD. La semilla Ed25519 es la que deriva el `identityId`; el par
 * X25519 del almacen NO se deriva de la semilla y se regenera, y los contactos,
 * sesiones y mensajes viven en memoria y no se tocan. Prometer mas seria mentir
 * justo en el flujo donde el usuario ya ha perdido algo.
 *
 * ## LA VALIDACION ES LA DEL FRAMEWORK
 *
 * `Mnemonic.parse` normaliza (NFC, minusculas, espacios) y comprueba longitud,
 * wordlist y checksum. Aqui no se reimplementa ninguna de las tres: dos
 * validadores distintos aceptarian frases distintas.
 *
 * ## NUNCA REGISTRA LA FRASE
 *
 * Ni el texto, ni las palabras, ni la semilla. [Validation] solo lleva el
 * conteo y si el checksum cuadro, que es lo unico que la pantalla necesita.
 */
class RecoveryService(
    /** La identidad VIVA del proceso. */
    private val identity: () -> DesktopIdentity,
    private val keystorePath: Path,
    /**
     * La frase de paso del almacen, en memoria.
     *
     * Sin ella no se puede reescribir el contenedor: restaurar exige volver a
     * cifrar. Es una funcion porque el arranque puede no haberla retenido (modo
     * linea con `--reset`, por ejemplo) y entonces se responde `NO_PASSPHRASE`
     * en vez de escribir con una frase inventada.
     */
    private val passphrase: () -> CharArray?,
    /** Inyectable para el test del control negativo: no escribir sin confirmar. */
    private val writeFromSeed: (Path, CharArray, ByteArray) -> DesktopIdentity =
        { path, pass, seed -> IdentityKeystore.writeFromSeed(path, pass, seed) },
) {

    /** Las 24 palabras de la identidad. Solo se llama tras un reveal confirmado. */
    fun words(): List<String> {
        val frase = Mnemonic.fromEntropy(identity().ed25519PrivateKey)
        return (0 until frase.wordCount).map { frase.wordAt(it) }
    }

    /**
     * Valida forma y checksum SIN escribir nada.
     *
     * No lanza: un fallo de validacion es un estado de la pantalla, no un error
     * del proceso. El conteo se calcula incluso cuando `parse` falla, para que
     * la pantalla pueda decir "9/24" en vez de solo "invalida".
     */
    fun validate(input: String): Validation {
        val palabras = input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return try {
            Mnemonic.parse(input)
            Validation(Mnemonic.WORD_COUNT, checksumOk = true)
        } catch (e: MnemonicException) {
            Validation(palabras.size, checksumOk = false)
        }
    }

    /**
     * Restaura la identidad desde [input].
     *
     * Exige la confirmacion escrita OTRA VEZ aqui, aunque el reducer ya la
     * compruebe: la escritura del almacen es irreversible y no puede depender de
     * que la unica puerta sea una tecla bien cableada.
     */
    fun restore(input: String, confirmation: String): RestoreOutcome {
        if (confirmation.trim() != CONFIRMATION) {
            return RestoreOutcome.Rejected(CODE_CONFIRMATION_REQUIRED)
        }
        val entropy = try {
            Mnemonic.toEntropy(Mnemonic.parse(input))
        } catch (e: MnemonicException) {
            return RestoreOutcome.Rejected(CODE_PHRASE_INVALID)
        }
        val pass = passphrase() ?: return RestoreOutcome.Rejected(CODE_NO_PASSPHRASE)
        return try {
            val nueva = writeFromSeed(keystorePath, pass.copyOf(), entropy)
            RestoreOutcome.Restored(nueva.identityId.value)
        } catch (e: Exception) {
            RestoreOutcome.Rejected(CODE_WRITE_FAILED)
        }
    }

    /** Lo que la pantalla muestra de una validacion. */
    data class Validation(val wordCount: Int, val checksumOk: Boolean) {
        val ok: Boolean get() = wordCount == Mnemonic.WORD_COUNT && checksumOk

        fun toView(): PhraseValidation = PhraseValidation(wordCount, checksumOk)
    }

    sealed interface RestoreOutcome {
        data class Restored(val identityId: String) : RestoreOutcome

        data class Rejected(val code: String) : RestoreOutcome
    }

    companion object {
        /** La palabra que hay que escribir. La misma que pinta el reducer. */
        const val CONFIRMATION: String = "SOBRESCRIBIR"

        const val CODE_CONFIRMATION_REQUIRED: String = "CONFIRMATION_REQUIRED"
        const val CODE_PHRASE_INVALID: String = "PHRASE_INVALID"
        const val CODE_NO_PASSPHRASE: String = "NO_PASSPHRASE"
        const val CODE_WRITE_FAILED: String = "RESTORE_WRITE_FAILED"
    }
}
