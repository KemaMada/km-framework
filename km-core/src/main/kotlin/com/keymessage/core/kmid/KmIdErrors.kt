package com.keymessage.core.kmid

/**
 * Fallo de la verificacion semantica o criptografica de un documento.
 *
 * El [code] es estable y citable desde los fixtures y desde el RFC. El
 * mensaje es informacion para diagnostico y NO forma parte del contrato.
 */
class KmIdVerificationException(val code: String, val detail: String = "") :
    Exception(if (detail.isEmpty()) code else "$code: $detail")

/** Fallo de forma de datos. No implica juicio sobre la criptografia. */
class KmIdSchemaException(val code: String, val detail: String = "") :
    Exception(if (detail.isEmpty()) code else "$code: $detail")
