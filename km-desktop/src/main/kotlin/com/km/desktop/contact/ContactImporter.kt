package com.km.desktop.contact

import com.km.identity.CarrierCodec
import com.km.identity.RejectionCodes
import com.km.identity.VerifiedContact
import com.km.identity.VerifiedContactBundle

/**
 * La entrada de un contacto en el ESCRITORIO: delegacion pura al codec compartido.
 *
 * ## POR QUE ESTE FICHERO YA NO DECODIFICA NADA
 *
 * Hasta G1 importaba con `ContactBundle.decodeAndVerify`, que espera base64url
 * SIN comprimir. El portador real de Android es zlib + ISO-8859-1, asi que un
 * `.km` legitimo se rechazaba con `ENVELOPE_MALFORMED`. La regla vive ahora en
 * [CarrierCodec] —`decompress -> Kce.validate -> ContactBundle.verified`— y aqui
 * solo queda la politica del escritorio: que `import` devuelva el
 * [VerifiedContact] con su raiz, su `deviceId` y su clave de firma.
 *
 * El `ContactBook` y `InMemoryContactBook` que vivian aqui se sustituyen por los
 * tipos compartidos ([com.km.identity.ContactRegistry] y
 * [com.km.identity.InMemoryContactRegistry]), para que app y escritorio no
 * tengan dos registros con dos politicas.
 */
object ContactImporter {

    /**
     * El bundle VERIFICADO de un portador.
     *
     * @param expectedIdentityRoot raiz esperada, o `null` para aceptar la que
     *   firme el bundle.
     * @throws com.km.identity.Kce.KceException si el portador no se descomprime
     *   o el sobre no valida.
     * @throws com.km.identity.KmIdVerificationException si el bundle no verifica.
     */
    fun verifiedBundle(text: String, expectedIdentityRoot: ByteArray? = null): VerifiedContactBundle =
        CarrierCodec.decode(text, expectedIdentityRoot)

    /**
     * Importa [text] y devuelve el contacto.
     *
     * @throws IllegalArgumentException si el sobre esta vacio.
     * @throws com.km.identity.Kce.KceException si el portador no es un bundle comprimido.
     * @throws com.km.identity.KmIdVerificationException si no verifica.
     */
    fun import(text: String, expectedIdentityRoot: ByteArray? = null): VerifiedContact {
        require(text.isNotBlank()) { "el sobre del contacto esta vacio" }
        val bundle = CarrierCodec.decode(text, expectedIdentityRoot)
        return com.km.identity.verifiedContactOf(bundle, text)
    }

    /** El codigo ESTABLE de un rechazo, mapeado por el vocabulario compartido. */
    fun rejectionCode(failure: Throwable): String = RejectionCodes.of(failure)
}

/** El registro compartido, con el nombre historico del escritorio. */
typealias ContactBook = com.km.identity.ContactRegistry

/** El registro en memoria compartido, con el nombre historico del escritorio. */
typealias InMemoryContactBook = com.km.identity.InMemoryContactRegistry
