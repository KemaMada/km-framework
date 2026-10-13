package com.km.auth

/**
 * Transicion del alias legacy `identityId` en el `AUTH_OK` (task 1.3, R2/R3/N4).
 *
 * El campo canonico del iniciador es `initiatorIdentityId` (R1). Durante la
 * ventana de transicion el nombre legacy `identityId` se acepta como ALIAS,
 * pero **solo** en el dialecto `2.0`: en `2.1` el alias esta retirado y un
 * `AUTH_OK` que lo traiga SOLO en esa forma se rechaza con `UNKNOWN_PROTOCOL`
 * (R2, S15/P6-S).
 *
 * Si los dos campos conviven DEBEN ser iguales; una discrepancia es un mensaje
 * mal formado (`MALFORMED_MESSAGE`), nunca una eleccion silenciosa de uno de
 * los dos (R3, S14/P6-T). La igualdad se exige en AMBOS dialectos: convivir no
 * es "estar retirado".
 *
 * La regla es una funcion pura de (dialecto, campo canonico, campo legacy): no
 * decide criptografia, no verifica nada y no interpreta el transcript. Es la
 * parte de la transicion que el cabecera de `AuthJsonCodec` delega aqui para
 * que el nombre canonico y su alias se resuelvan en un solo sitio (R16).
 */
enum class AuthFieldError(val wireCode: String) {
    /** Campo ausente, vacio o del tipo equivocado: fallo estructural (R15/R31). */
    MALFORMED_MESSAGE("MALFORMED_MESSAGE"),

    /**
     * El alias legacy aparece en un dialecto que ya no lo admite (R2). Es el
     * nombre de auth equivalente a `UNSUPPORTED_PROTOCOL_VERSION` en el cable
     * (KM-0002 seccion 13): aqui la incompatibilidad es de FORMA de campo, no
     * de valor de dialecto.
     */
    UNKNOWN_PROTOCOL("UNKNOWN_PROTOCOL"),
}

/** Resultado de resolver el campo del iniciador de un `AUTH_OK`. */
sealed interface AuthOkInitiator {
    /** El campo resuelto: canonico si vino, alias si el dialecto lo admite. */
    data class Accepted(val identityId: String) : AuthOkInitiator

    /** El frame no es admisible: [error] dice por que. */
    data class Rejected(val error: AuthFieldError, val detail: String) : AuthOkInitiator
}

/** Resultado de parsear un `AUTH_OK` con las reglas del dialecto. */
sealed interface AuthOkFields {
    data class Accepted(val authOk: AuthOk) : AuthOkFields

    data class Rejected(val error: AuthFieldError, val detail: String) : AuthOkFields
}

/**
 * Regla de la transicion del alias `identityId` (R2/R3).
 *
 * [canonical] es el valor de `initiatorIdentityId` o `null` si el campo no
 * esta; [legacy] es el valor de `identityId` o `null` si no esta. La distincion
 * entre "ausente" (`null`) y "presente pero vacio" (`""`) importa: lo segundo
 * es un campo mal formado, no un alias valido.
 */
object AuthOkAliasRules {

    fun resolve(
        dialect: Dialect,
        canonical: String?,
        legacy: String?,
    ): AuthOkInitiator {
        canonical?.let {
            if (it.isEmpty()) return reject(AuthFieldError.MALFORMED_MESSAGE, "initiatorIdentityId vacio")
        }
        legacy?.let {
            if (it.isEmpty()) return reject(AuthFieldError.MALFORMED_MESSAGE, "identityId vacio")
        }

        if (canonical != null && legacy != null) {
            // R3: conviven -> DEBEN ser iguales. Vale en 2.0 y en 2.1.
            return if (canonical == legacy) {
                AuthOkInitiator.Accepted(canonical)
            } else {
                reject(
                    AuthFieldError.MALFORMED_MESSAGE,
                    "identityId != initiatorIdentityId: alias en conflicto",
                )
            }
        }

        canonical?.let { return AuthOkInitiator.Accepted(it) }

        legacy?.let {
            // R2: el alias se acepta SOLO en dialecto 2.0; en 2.1 esta retirado.
            return if (dialect == Dialect.V2_0) {
                AuthOkInitiator.Accepted(it)
            } else {
                reject(
                    AuthFieldError.UNKNOWN_PROTOCOL,
                    "alias 'identityId' retirado en dialecto '${dialect.wire}'; " +
                        "se esperaba 'initiatorIdentityId'",
                )
            }
        }

        // R15: el campo canonico es obligatorio; sin canonico ni alias no hay
        // iniciador que vincular.
        return reject(
            AuthFieldError.MALFORMED_MESSAGE,
            "falta 'initiatorIdentityId' (ni canonico ni alias 'identityId')",
        )
    }

    private fun reject(error: AuthFieldError, detail: String): AuthOkInitiator =
        AuthOkInitiator.Rejected(error, detail)
}
