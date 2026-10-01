package com.km.daemon.server

import com.km.auth.AuthResponse
import com.km.model.IdentityId
import com.km.node.RelayServer
import com.km.node.RelayServerError
import com.km.node.RelayServerResult

/**
 * Puente entre el dialecto B y la autenticacion KM-0002 que ya vive en
 * km-core.
 *
 * Regla que gobierna este fichero: aqui NO hay criptografia. El challenge lo
 * genera `RelayServer.createChallenge`, la firma la verifica
 * `RelayServer.authenticate` a traves de `AuthVerifier` sobre el transcript
 * canonico de 152 B, y la `AUTH_OK` la firma `RelayServer` sobre el
 * transcript de 120 B. Este adaptador solo hace dos cosas que el core no
 * puede hacer por si solo:
 *
 * 1. Recordar el `publicKey` del `AUTH_REQUEST`, porque §9.4 dice que
 *    `AUTH_RESPONSE` NO lo reenvia: viaja una vez y el transcript lo vincula
 *    por posicion fija. Sin esa memoria no hay forma de reconstruir la
 *    `AuthResponse` que `RelayServer.authenticate` espera.
 * 2. Traducir el resultado a los tipos de frame del dialecto.
 *
 * Lo que NO hace, por diseno: verificar firmas, construir transcripts,
 * negociar nada, ni decidir que una identidad es valida mas alla de su FORMA
 * (64 hex, 32 bytes). Eso es de km-core.
 */
class AuthAdapter(
    private val relayServer: RelayServer,
    private val relayIdentityId: IdentityId,
    private val codec: JsonWireCodec,
) {

    /** Codigos de `AUTH_FAIL`. El cliente los lee como string, sin enum. */
    object ErrorCode {
        /** Firma o identidad invalida. Es lo que devuelve `RelayServer.AUTH_FAILED`. */
        const val AUTH_FAILED: String = "AUTH_FAILED"

        /** `protocolVersion` que este rele no habla (§17.2.2). */
        const val UNSUPPORTED_PROTOCOL_VERSION: String = "UNSUPPORTED_PROTOCOL_VERSION"

        /** El `responderIdentityId` del cliente no es el de este rele. */
        const val RELAY_IDENTITY_MISMATCH: String = "RELAY_IDENTITY_MISMATCH"

        /** `AUTH_RESPONSE` sin challenge previo, o con firma que no cuadra. */
        const val INVALID_AUTH_FLOW: String = "INVALID_AUTH_FLOW"
    }

    /**
     * Paso 1: `AUTH_REQUEST` -> `AUTH_CHALLENGE`.
     *
     * Devuelve el frame a enviar, o el motivo del rechazo en texto. No lanza:
     * un `AUTH_REQUEST` mal formado es un frame descartable, no una excepcion
     * que tumbe el hilo de la conexion.
     */
    fun begin(request: AuthRequestFields): AuthStep {
        if (request.protocolVersion != JsonWireCodec.DIALECT_VERSION) {
            return AuthStep.Rejected(
                codec.authFail(
                    ErrorCode.UNSUPPORTED_PROTOCOL_VERSION,
                    "dialecto '${request.protocolVersion}' no soportado; este rele habla " +
                        "'${JsonWireCodec.DIALECT_VERSION}'",
                )
            )
        }

        val declaredResponder = request.responderIdentityId
        if (declaredResponder != null && !declaredResponder.equals(relayIdentityId.value, ignoreCase = true)) {
            return AuthStep.Rejected(
                codec.authFail(
                    ErrorCode.RELAY_IDENTITY_MISMATCH,
                    "responderIdentityId no corresponde a este rele",
                )
            )
        }

        // El challenge lo genera el core, con su nonce de 16 B y su timestamp.
        val challenge = relayServer.createChallenge(request.identityId)
        return AuthStep.Challenge(
            wire = codec.authChallenge(
                nonce = challenge.nonce,
                timestamp = challenge.timestampMillis,
                responderIdentityId = challenge.responderIdentityId,
            )
        )
    }

    /**
     * Paso 2: `AUTH_RESPONSE` -> `AUTH_OK` o `AUTH_FAIL`.
     *
     * @param request los campos del `AUTH_REQUEST` que abrio este challenge.
     *   `null` si llego una respuesta sin peticion previa.
     * @param signature la firma tal cual vino, en bytes.
     */
    fun complete(request: AuthRequestFields?, signature: ByteArray): AuthStep {
        if (request == null) {
            return AuthStep.Rejected(
                codec.authFail(ErrorCode.INVALID_AUTH_FLOW, "AUTH_RESPONSE sin AUTH_REQUEST previo")
            )
        }

        // `protocolVersion` de AuthResponse es un Int y el cliente manda el
        // string "2.0" (§9.7, P11). Ni `AuthVerifier` ni `RelayServer` lo
        // miran, asi que aqui se traduce sin judgement: la version SI se ha
        // comprobado arriba, sobre el string. La reconciliacion de los dos
        // espacios es P11, no una decision de este modulo.
        val response = AuthResponse(
            identityId = request.identityId.value,
            publicKey = request.publicKey,
            signature = signature,
            protocolVersion = DIALECT_VERSION_NUMBER,
        )

        return when (val result = relayServer.authenticate(request.identityId, response)) {
            is RelayServerResult.Success -> {
                val (session, authOk) = result.value
                AuthStep.Accepted(
                    wire = codec.authOk(
                        sessionId = session.sessionId,
                        serverSignature = authOk.signature,
                        initiatorIdentityId = request.identityId.value,
                        responderIdentityId = relayIdentityId.value,
                    ),
                    sessionId = session.sessionId,
                )
            }

            is RelayServerResult.Failure -> AuthStep.Rejected(
                codec.authFail(mapError(result.error), result.message)
            )
        }
    }

    /**
     * `RelayServerError` -> codigo de `AUTH_FAIL`.
     *
     * En ESTA ruta el enum tiene un unico valor posible: `authenticate` falla
     * unicamente con `AUTH_FAILED` (§16.3). No se inventa una tabla de
     * conversion mas amplia que la que el core produce, porque una tabla que
     * dice "esto nunca ocurre" es documentacion disfrazada de logica.
     */
    private fun mapError(error: RelayServerError): String = when (error) {
        RelayServerError.AUTH_FAILED -> ErrorCode.AUTH_FAILED
        // inalcanzable hoy; si `authenticate` empieza a devolver otra cosa,
        // este `when` no compila y obliga a decidir el codigo a proposito.
        else -> ErrorCode.INVALID_AUTH_FLOW
    }

    private companion object {
        /** "2.0" -> 2. Solo para rellenar el Int de `AuthResponse`. */
        const val DIALECT_VERSION_NUMBER: Int = 2
    }
}

/**
 * Resultado de un paso del handshake.
 *
 * [Challenge] y [Accepted] son exito; [Rejected] lleva ya el `AUTH_FAIL`
 * serializado, porque el texto del fallo lo compone quien tiene el contexto.
 */
sealed class AuthStep {
    data class Challenge(val wire: String) : AuthStep()

    data class Accepted(val wire: String, val sessionId: String) : AuthStep()

    data class Rejected(val wire: String) : AuthStep()
}
