package com.km.auth

/**
 * Tipos wire de la autenticacion KM-0002, seccion 10.
 *
 * Los campos binarios (nonce, publicKey, signature) se transportan en
 * Base64URL y en memoria como ByteArray crudo (sin re-codificar para firmar).
 */
data class AuthChallenge(
    val nonce: ByteArray,           // 16 bytes
    val timestampMillis: Long,      // uint64 epoch ms
    /**
     * Version del propio RFC KM-0002 (hoy `3`), NO el dialecto del cable.
     * Es un campo DISTINTO de `protocolVersion` y nunca debe consumirse como
     * dialecto (§9.7, R34).
     */
    val version: Int,               // version soportada por el Responder (KM-0002)
    val responderIdentityId: String, // 64 hex ascii
)

data class AuthResponse(
    val identityId: String,         // 64 hex ascii
    val publicKey: ByteArray,       // 32 bytes Ed25519
    val signature: ByteArray,       // 64 bytes
    /**
     * Dialecto del cable, de la fuente unica [Dialect] (task 1.2 / P11 / R34).
     * Antes era un `Int` sintetizado y ambiguo; el `version` NUMERICO de KM-0002
     * vive en [AuthChallenge.version], no aqui. La serializacion de cable emite
     * `Dialect.wire` (un string), nunca un numero.
     */
    val protocolVersion: Dialect,
)

/**
 * Resultado de autenticacion del Responder (KM-0002 seccion 10). Los campos de
 * identidad son los canonicos del cable KM-WIRE-RELAY §9.6.
 */
data class AuthOk(
    val sessionId: String,          // 40 hex ascii
    val serverNonce: ByteArray,     // 16 bytes
    val responderPublicKey: ByteArray, // 32 bytes Ed25519 del Responder
    /** Se serializa como la clave de cable `serverSignature` (64 bytes). */
    val signature: ByteArray,
    /**
     * `identityId` del INICIADOR (64 hex). Campo canonico `initiatorIdentityId`
     * del `AUTH_OK` (R1/R15). Por defecto vacio para los llamantes que solo
     * ejercitan la verificacion; [AuthJsonCodec.authOkWire] exige la forma
     * canonica al serializar (lo llena el emisor del cable).
     */
    val initiatorIdentityId: String = "",
    /** `identityId` del RESPONDER/rele (64 hex). Campo canonico `responderIdentityId`. */
    val responderIdentityId: String = "",
)