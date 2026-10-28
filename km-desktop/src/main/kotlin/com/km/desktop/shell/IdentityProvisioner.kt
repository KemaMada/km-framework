package com.km.desktop.shell

import com.km.desktop.DesktopIdentity
import com.km.desktop.IdentityKeystore
import com.km.desktop.client.RecoveryService
import com.km.mnemonic.Mnemonic
import com.km.mnemonic.MnemonicException
import java.nio.file.Path

/**
 * Provisiona la identidad ANTES de que exista una sesion.
 *
 * ## POR QUE NO USA `RecoveryService`
 *
 * Porque `RecoveryService` vive dentro de una [com.km.desktop.Session] y toma la
 * frase de paso del almacen que YA esta abierto. En la puerta de arranque no hay
 * sesion todavia: la frase de paso es NUEVA y hay que escribirla antes de abrir.
 *
 * La validacion es la del framework (`Mnemonic.parse` / `toEntropy`), no una
 * copia de la wordlist. La confirmacion es la MISMA constante que usa la
 * restauracion dentro de la sesion ([RecoveryService.CONFIRMATION]): dos
 * literales distintos serian dos puertas que el usuario tendria que recordar.
 *
 * ## LO QUE NO HACE
 *
 * No abre la sesion ni toca el terminal. Devuelve un resultado y el llamador
 * decide; asi el "sin escribir" se puede probar sin un pty y el control negativo
 * (escribir antes de validar) tiene un sitio exacto donde mutar.
 */
object IdentityProvisioner {

    sealed interface Outcome {
        /** El almacen quedo escrito con la identidad derivada de la frase. */
        data class Restored(val identityId: String) : Outcome

        /** No se escribio nada. [code] es estable y no lleva secretos. */
        data class Rejected(val code: String) : Outcome
    }

    /**
     * Valida forma, wordlist y checksum SIN escribir nada.
     *
     * @return `null` si la frase vale; `PHRASE_INVALID` si no.
     */
    fun validate(phrase: String): String? = try {
        Mnemonic.parse(phrase)
        null
    } catch (fallo: MnemonicException) {
        RecoveryService.CODE_PHRASE_INVALID
    }

    /**
     * Restaura la identidad desde [phrase] y la protege con [newPassphrase].
     *
     * @param overwriting `true` si ya habia almacen: exige [confirmation]
     *   escrita. Sin almacen no hay nada que perder y no se pide.
     * @param writeFromSeed inyectable para el test del control negativo.
     *
     * El orden importa: la FRASE se valida primero. Una frase invalida responde
     * `PHRASE_INVALID` aunque falte la confirmacion, y no llama al escritor.
     */
    fun restore(
        path: Path,
        phrase: String,
        newPassphrase: CharArray,
        confirmation: String,
        overwriting: Boolean,
        writeFromSeed: (Path, CharArray, ByteArray) -> DesktopIdentity =
            { destino, pass, seed -> IdentityKeystore.writeFromSeed(destino, pass, seed) },
    ): Outcome {
        val entropy = try {
            Mnemonic.toEntropy(Mnemonic.parse(phrase))
        } catch (fallo: MnemonicException) {
            return Outcome.Rejected(RecoveryService.CODE_PHRASE_INVALID)
        }
        if (newPassphrase.isEmpty()) {
            entropy.fill(0)
            return Outcome.Rejected("PASSPHRASE_EMPTY")
        }
        if (overwriting && confirmation.trim() != RecoveryService.CONFIRMATION) {
            entropy.fill(0)
            return Outcome.Rejected(RecoveryService.CODE_CONFIRMATION_REQUIRED)
        }

        val pass = newPassphrase.copyOf()
        return try {
            val identity = writeFromSeed(path, pass, entropy)
            Outcome.Restored(identity.identityId.value)
        } catch (fallo: Exception) {
            Outcome.Rejected(RecoveryService.CODE_WRITE_FAILED)
        } finally {
            pass.fill('\u0000')
            entropy.fill(0)
        }
    }
}
