package com.km.auth

/**
 * Dialecto del cable KM-WIRE-RELAY (§9.7, §17.1) — FUENTE UNICA DE VERDAD.
 *
 * Que es y que NO es
 * ------------------
 * El dialecto es el string que viaja en el campo `protocolVersion` de
 * `AUTH_REQUEST`, `AUTH_CHALLENGE` y `AUTH_OK`. Es SIEMPRE un string JSON,
 * NUNCA un numero (R34). NO es la `version` numerica de KM-0002 (hoy 3), que
 * es la version del propio RFC y viaja en un campo DISTINTO:
 * conflacionar los dos es el error que §9.7 documenta como divergencia.
 *
 * Antes de este tipo el literal `"2.0"` estaba repetido en el codec del
 * daemon (`JsonWireCodec.DIALECT_VERSION`) y en los dos clientes. Aqui pasa a
 * haber un solo sitio, y los tres consumidores lo referencian.
 *
 * Cada cliente implementa EXACTAMENTE UN dialecto (N7): declara el suyo y
 * falla cerrado ante cualquier otra confirmacion. No hay seleccion ambigua ni
 * downgrade silencioso.
 */
enum class Dialect(val wire: String) {
    /** Dialecto de transicion: `identityId` es la forma canonica y su alias se acepta. */
    V2_0("2.0"),

    /** Dialecto con el alias retirado: solo la forma canonica. */
    V2_1("2.1");

    companion object {
        /**
         * Conjunto de dialectos de la POLITICA DE TRANSICION aprobada por el
         * owner (N1): `{2.0, 2.1}`. El `"2.0"` se retira segun el criterio
         * verificable y el procedimiento de confirmacion de migracion
         * documentados en KM-WIRE-RELAY §17.
         *
         * Que un rele SOPORTE los dos dialectos NO significa que un mismo
         * handshake los MEZCLE: cada handshake usa uno y solo uno, el que el
         * cliente declaro. La distincion esta en KM-WIRE-RELAY §17.4.
         *
         * Un modulo NO debe ofrecer como efectivo un dialecto cuyas reglas de
         * campo no implemente todavia (asi el rele no confirma un dialecto que
         * no sabe servir).
         */
        val TRANSITIONAL: Set<Dialect> = setOf(V2_0, V2_1)

        /**
         * Convierte el string del cable a dialecto, o `null` si falta o es un
         * valor que no corresponde a ningun dialecto conocido.
         *
         * Un `protocolVersion` ausente y uno desconocido son fallos DISTINTOS:
         * el primero es estructural (MALFORMED_MESSAGE), el segundo es de
         * negociacion (UNSUPPORTED_PROTOCOL_VERSION). Por eso esta funcion no
         * decide el codigo: solo dice si el valor existe.
         */
        fun fromWireOrNull(wire: String?): Dialect? =
            if (wire == null) null else values().firstOrNull { it.wire == wire }
    }
}

/** Codigo de rechazo de la negociacion de dialecto, en el catalogo del cable. */
enum class DialectError(val wireCode: String) {
    /** Campo ausente o del tipo equivocado: fallo estructural (R15/R31). */
    MALFORMED_MESSAGE("MALFORMED_MESSAGE"),

    /** Valor desconocido, no implementado o inconsistente entre frames. */
    UNSUPPORTED_PROTOCOL_VERSION("UNSUPPORTED_PROTOCOL_VERSION"),
}

/** Resultado de comprobar el dialecto de un frame del handshake. */
sealed interface DialectCheck {
    /** El frame confirma el dialecto esperado; [dialect] es el efectivo. */
    data class Accepted(val dialect: Dialect) : DialectCheck

    /** El frame no confirma el dialecto esperado; se rechaza el handshake. */
    data class Rejected(val error: DialectError, val detail: String) : DialectCheck
}

/**
 * Verificacion del dialecto en la DIRECCION DE RESPUESTA (rele -> cliente).
 *
 * Esto es lo que no existia (task 1.1): el cliente verifica el dialecto de los
 * frames que RECIBE, no solo lo declara. Es logica compartida por Android y
 * escritorio para que la regla viva en un solo sitio y no se pueda implementar
 * distinto en cada consumidor.
 *
 * Invariante que cierra el hueco A3: mientras estos metodos se invoquen antes
 * de firmar (`AUTH_CHALLENGE`) y antes de pasar a ONLINE (`AUTH_OK`), ningun
 * cliente puede quedar autenticado contra un dialecto que no implementa.
 *
 * El dialecto NO decide si se verifica `serverSignature`: eso lo decide la
 * posesion de un pin, no el dialecto (N5). Aqui solo se comprueba el campo de
 * negociacion.
 */
object DialectNegotiation {

    /**
     * Comprueba el `protocolVersion` de un `AUTH_CHALLENGE` contra el dialecto
     * que implementa el cliente.
     *
     * - ausente -> MALFORMED_MESSAGE (estructural).
     * - desconocido / no implementado -> UNSUPPORTED_PROTOCOL_VERSION.
     */
    fun checkChallenge(declared: Dialect, wire: String?): DialectCheck {
        val parsed = Dialect.fromWireOrNull(wire)
            ?: return if (wire == null) {
                DialectCheck.Rejected(
                    DialectError.MALFORMED_MESSAGE,
                    "AUTH_CHALLENGE sin protocolVersion: se descarta sin firmar",
                )
            } else {
                DialectCheck.Rejected(
                    DialectError.UNSUPPORTED_PROTOCOL_VERSION,
                    "AUTH_CHALLENGE con dialecto desconocido '$wire'",
                )
            }

        if (parsed != declared) {
            // No hay downgrade silencioso: el cliente NO adopta el dialecto
            // que confirma el rele solo porque lo entienda.
            return DialectCheck.Rejected(
                DialectError.UNSUPPORTED_PROTOCOL_VERSION,
                "AUTH_CHALLENGE confirma '${parsed.wire}' pero el cliente implementa '${declared.wire}'",
            )
        }
        return DialectCheck.Accepted(parsed)
    }

    /**
     * Comprueba el `protocolVersion` de un `AUTH_OK` contra el del challenge
     * del MISMO handshake y contra el dialecto implementado.
     *
     * - ausente -> MALFORMED_MESSAGE.
     * - desconocido -> UNSUPPORTED_PROTOCOL_VERSION.
     * - distinto del challenge -> UNSUPPORTED_PROTOCOL_VERSION (inconsistencia
     *   intra-handshake; NO es un upgrade de negociacion) (S21/P6-O).
     * - no implementado -> UNSUPPORTED_PROTOCOL_VERSION.
     */
    fun checkAuthOk(declared: Dialect, challengeDialect: Dialect, wire: String?): DialectCheck {
        val parsed = Dialect.fromWireOrNull(wire)
            ?: return if (wire == null) {
                DialectCheck.Rejected(
                    DialectError.MALFORMED_MESSAGE,
                    "AUTH_OK sin protocolVersion: se rechaza el AUTH_OK",
                )
            } else {
                DialectCheck.Rejected(
                    DialectError.UNSUPPORTED_PROTOCOL_VERSION,
                    "AUTH_OK con dialecto desconocido '$wire'",
                )
            }

        if (parsed != challengeDialect) {
            return DialectCheck.Rejected(
                DialectError.UNSUPPORTED_PROTOCOL_VERSION,
                "inconsistencia intra-handshake: AUTH_CHALLENGE '${challengeDialect.wire}' " +
                    "!= AUTH_OK '${parsed.wire}'",
            )
        }
        if (parsed != declared) {
            return DialectCheck.Rejected(
                DialectError.UNSUPPORTED_PROTOCOL_VERSION,
                "AUTH_OK confirma '${parsed.wire}' pero el cliente implementa '${declared.wire}'",
            )
        }
        return DialectCheck.Accepted(parsed)
    }
}
