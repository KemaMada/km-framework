package com.km.daemon.server

import com.km.auth.AuthResponse
import com.km.auth.Dialect
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
 * Lo que NO hace, por diseno: verificar firmas, construir transcripts, ni
 * decidir que una identidad es valida mas alla de su FORMA (64 hex, 32 bytes).
 * Eso es de km-core. Lo unico que NEGOCIA es el dialecto del cable (task 1.1):
 * acepta el declarado si este rele lo sirve, lo confirma en `AUTH_CHALLENGE` y
 * en `AUTH_OK`, y lo transporta a los codecs como `Dialect`, nunca como literal
 * ni como Int. Desde la task 1.3 sirve `{2.0, 2.1}`: la retirada del alias
 * `identityId` de `2.1` esta implementada en la lectura del `AUTH_OK`
 * (`AuthOkAliasRules`), de modo que la mitad del RELE —nunca emitir el alias y
 * confirmar coherentemente el dialecto— es una promesa que el rele cumple. La
 * mitad del CLIENTE no la ejerce todavia ningun consumidor de produccion: la
 * lectura dialectal del `AUTH_OK` no tiene llamador de produccion (llega con la
 * verificacion de `serverSignature`, 4.1/5.2) y los clientes entregados
 * implementan solo el dialecto `2.0`. "El rele sirve `2.1`" por tanto NO
 * equivale a "un cliente entregado aplica las reglas de campo de `2.1`".
 */
class AuthAdapter(
    private val relayServer: RelayServer,
    private val relayIdentityId: IdentityId,
    private val codec: JsonWireCodec,
    /**
     * Dialectos que este rele sabe SERVIR de verdad.
     *
     * La politica de transicion aprobada es `Dialect.TRANSITIONAL`
     * = `{2.0, 2.1}`. Desde la task 1.3 el rele sirve los DOS: `2.1` retira el
     * alias `identityId` del `AUTH_OK`, y esa regla de campo ya esta
     * implementada (`AuthOkAliasRules` / `AuthJsonCodec.parseAuthOkForDialect`),
     * asi que confirmar `2.1` ya no miente sobre el dialecto que ESTE rele
     * sirve. Esto es una propiedad de la mitad del rele: no implica que un
     * cliente entregado aplique las reglas de campo de `2.1` (solo implementa
     * `2.0` y la lectura dialectal no tiene consumidor de produccion todavia;
     * 4.1/5.2). La coherencia del dialecto a lo largo del handshake
     * (`AUTH_REQUEST` -> `AUTH_CHALLENGE` -> `AUTH_OK`) la garantiza este
     * adaptador, que deriva el dialecto EFECTIVO del `AUTH_REQUEST` y lo ecoa
     * identico en el challenge y en el OK.
     *
     * El parametro es inyectable para poder ejercitar la matriz de
     * compatibilidad (un rele que solo sirve `2.0`, o solo `2.1`) y para el gate
     * de retirada de `"2.0"` de KM-WIRE-RELAY seccion 17.4, que recorta el
     * conjunto efectivo sin cambiar codigo.
     *
     * El eco del dialecto en `AUTH_CHALLENGE` y `AUTH_OK` sale SIEMPRE de este
     * conjunto efectivo, nunca de un literal ni de un Int.
     */
    private val supportedDialects: Set<Dialect> = Dialect.TRANSITIONAL,
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
        // Negociacion de dialecto (task 1.1, §17.2). Se acepta EXACTAMENTE el
        // dialecto declarado si este rele lo sirve; ni upgrade ni downgrade
        // silencioso. Un dialecto desconocido o no servido es un rechazo
        // explicito con UNSUPPORTED_PROTOCOL_VERSION.
        val dialect = Dialect.fromWireOrNull(request.protocolVersion)
        if (dialect == null || dialect !in supportedDialects) {
            return AuthStep.Rejected(
                codec.authFail(
                    ErrorCode.UNSUPPORTED_PROTOCOL_VERSION,
                    "dialecto '${request.protocolVersion}' no soportado; este rele sirve " +
                        supportedDialects.map { it.wire }.sorted(),
                    // N6: eco diagnostico. NUNCA implica que el dialecto se acepto.
                    dialect = request.protocolVersion,
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
                // El dialecto EFECTIVO viaja en el challenge: el cliente lo
                // verifica ANTES de firmar.
                dialect = dialect.wire,
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

        // El dialecto EFECTIVO del handshake se re-deriva del propio
        // `AUTH_REQUEST` (misma regla que `begin`): el `AUTH_OK` confirma
        // exactamente el dialecto que el challenge confirmo. Si por lo que
        // fuera no fuese servible, el paso 2 no puede continuar.
        val dialect = Dialect.fromWireOrNull(request.protocolVersion)
            ?.takeIf { it in supportedDialects }
            ?: return AuthStep.Rejected(
                codec.authFail(
                    ErrorCode.INVALID_AUTH_FLOW,
                    "AUTH_RESPONSE sin negociacion de dialecto valida",
                    dialect = request.protocolVersion,
                )
            )

        // `protocolVersion` de AuthResponse es el dialecto del cable (fuente
        // unica `Dialect`, task 1.2 / P11 / R34). El `version` NUMERICO de
        // KM-0002 no participa: el dialecto efectivo ya se derivo arriba del
        // `AUTH_REQUEST` y es el que viaja en el `AUTH_OK`.
        val response = AuthResponse(
            identityId = request.identityId.value,
            publicKey = request.publicKey,
            signature = signature,
            protocolVersion = dialect,
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
                        dialect = dialect.wire,
                        // Material de verificacion del transcript de 120 B (R15):
                        // el cliente lo necesita para verificar `serverSignature`.
                        serverNonce = authOk.serverNonce,
                        responderPublicKey = authOk.responderPublicKey,
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
