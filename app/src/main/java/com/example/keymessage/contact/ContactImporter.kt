package com.example.keymessage.contact

import com.example.keymessage.util.KMLog
import com.km.identity.CarrierCodec
import com.km.identity.RejectionCodes
import com.km.identity.VerifiedContactBundle
import com.km.identity.verifiedContactOf

/**
 * La entrada de un contacto en la app: DELEGACION al codec compartido.
 *
 * ## POR QUE ESTE FICHERO YA NO DESCOMPRIME NI VERIFICA
 *
 * Porque esas dos operaciones son un CONTRATO del portador y viven en
 * [CarrierCodec] desde G1. Tener una segunda copia aqui era la razon por la que
 * el escritorio podia divergir; tenerla ahora seria la misma divergencia al
 * reves. Este objeto conserva la API que la app y sus tests usan
 * ([verifiedBundle], [import]) y delega el ORDEN —descomprimir, validar KCE,
 * verificar— a un unico sitio.
 *
 * ## `VerifiedContact` ES EL TIPO COMPARTIDO
 *
 * El `data class` que vivia aqui se sustituye por el de km-core (ver el
 * `typealias` de [ContactBook]): app y escritorio no pueden tener dos formas de
 * describir un contacto verificado.
 */
object ContactImporter {

    /**
     * LA DECODIFICACION DEL PORTADOR: texto del QR/`.km` -> bundle VERIFICADO.
     *
     * @param expectedIdentityRoot raiz que se espera, o `null` para aceptar
     *   cualquiera.
     * @throws com.km.identity.Kce.KceException si el portador no se descomprime o
     *   el sobre no valida.
     * @throws com.km.identity.KmIdVerificationException si el bundle no verifica.
     */
    fun verifiedBundle(text: String, expectedIdentityRoot: ByteArray? = null): VerifiedContactBundle =
        CarrierCodec.decode(text, expectedIdentityRoot)

    /** El bundle verificado de un contacto YA importado, releido de su portador. */
    fun verifiedBundle(contacto: VerifiedContact): VerifiedContactBundle =
        verifiedBundle(contacto.bundleText)

    /**
     * Importa [text] —un `.km` o el contenido de un QR—.
     *
     * @throws IllegalArgumentException si el sobre esta vacio.
     * @throws com.km.identity.Kce.KceException si el portador no es un bundle comprimido.
     * @throws com.km.identity.KmIdVerificationException si no verifica.
     */
    fun import(text: String, expectedIdentityRoot: ByteArray? = null): VerifiedContact {
        if (text.isBlank()) {
            // Un sobre vacio tambien es un RECHAZO, y tiene que quedar escrito:
            // sin esta linea, pulsar IMPORTAR con el campo vacio no deja rastro
            // ni en logcat ni en la pantalla.
            KMLog.event("[CONTACT]", "rechazado codigo=${RejectionCodes.EMPTY_ENVELOPE}", KMLog.Level.WARN)
            throw IllegalArgumentException("el sobre del contacto esta vacio")
        }

        val bundle: VerifiedContactBundle = try {
            verifiedBundle(text, expectedIdentityRoot)
        } catch (e: Exception) {
            KMLog.event("[CONTACT]", "rechazado codigo=${RejectionCodes.of(e)}", KMLog.Level.WARN)
            throw e
        }

        val contacto = verifiedContactOf(bundle, text)
        KMLog.event(
            "[CONTACT]",
            "verificado root=${KMLog.truncar(contacto.identityRoot.value, 16)} " +
                "subject=${KMLog.truncar(contacto.subjectDeviceId, 16)}",
        )
        return contacto
    }
}

/**
 * El codigo ESTABLE de un rechazo de importacion, de cualquier tipo de fallo.
 *
 * Total por construccion: [RejectionCodes] mapea el sobre vacio, el malformado,
 * el incumplimiento de esquema, el fichero demasiado grande y el material local.
 * El codigo se deriva del TIPO de fallo, nunca del mensaje.
 */
fun rejectionCodeOf(failure: Throwable): String = when (failure) {
    // El codec del `.km` es de la app y conserva SU vocabulario en pantalla: sus
    // dos codigos propios se traducen aqui y el resto los mapea el framework.
    is ContactFileCodec.CodecException -> when (failure.code) {
        ContactFileCodec.CODE_OVERSIZED -> RejectionCodes.FILE_TOO_LARGE
        ContactFileCodec.CODE_EMPTY -> RejectionCodes.EMPTY_ENVELOPE
        else -> failure.code
    }
    else -> RejectionCodes.of(failure)
}
