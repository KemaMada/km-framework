package com.keymessage.core.kmid

/**
 * Constantes normativas de KM-ID-0001.
 *
 * PROVENIENCIA Y VERIFICACION
 *
 * Ninguna constante de aqui fue transcrita del RFC. Todas se verifican
 * contra el bloque `constants` del MANIFEST congelado por
 * `KmIdConstantsTest`. Ese test existe porque estas constantes son
 * invisibles para los vectores: G01..G03 solo ejercitan separadores de
 * dominio y hashes, de modo que un enum 0-based donde la referencia usa
 * 1-based pasaria todos los tests y solo fallaria meses despues, en
 * interoperabilidad, como un rechazo inexplicable.
 *
 * Ese error ya ocurrio una vez durante el port: STATUS_ACTIVE, KIND_CONTACT
 * y ENDPOINT_* se escribieron como 0,1,2 cuando la referencia usa 1,2,3.
 * No lo detecto ningun vector porque ninguno construye un roster.
 *
 * CONVENCION DE ENUMERADOS: 1-BASED
 *
 * Los valores empiezan en 1, no en 0. El 0 queda libre para "ausente" y
 * hace que un campo inicializado a 0 sea visible como valor invalido en
 * lugar de pasar por un estado valido.
 */
object KmIdConstants {

    // --- Separacion de dominio --------------------------------------------
    //
    // Los tres identificadores se derivan con prefijos DISTINTOS para que no
    // puedan colisionar entre si ni con ninguna otra construccion. Cambiar
    // cualquiera de estas cadenas cambia todos los identificadores y, por
    // tanto, invalida todos los vectores.

    const val DS_IDENTITY = "KM-ID-IDENTITY"
    const val DS_DEVICE = "KM-ID-DEVICE"
    const val DS_NODE = "KM-NODE-NODE"
    const val DS_ROSTER_GENESIS = "KM-ID-ROSTER-GENESIS"
    const val DS_ROSTER = "KM-ID-ROSTER"
    const val DS_ROSTER_CODE = "KM-ID-ROSTER-CODE"
    const val DS_SAFETY_NUMBER = "KM-ID-SAFETY-NUMBER"
    const val DS_ROTATION = "KM-ID-ROTATION"
    const val DS_CONTACT_BUNDLE = "KM-CONTACT-BUNDLE"
    const val DS_DEVICE_LINK = "KM-DEVICE-LINK"
    const val DS_PREKEY_SIGNED = "KM-PREKEY-SIGNED"
    const val DS_PREKEY_ONETIME = "KM-PREKEY-ONETIME"

    // --- Tipos de documento ------------------------------------------------

    const val DOC_ROSTER = "km.deviceRoster"
    const val DOC_ROTATION = "km.identityRotation"
    const val DOC_CONTACT_BUNDLE = "km.contactBundle"
    const val DOC_SIGNED_PREKEY = "km.signedPrekey"
    const val DOC_ONETIME_PREKEY = "km.oneTimePrekey"
    const val DOC_LINK_CHALLENGE = "km.deviceLinkChallenge"
    const val DOC_LINK_RESPONSE = "km.deviceLinkResponse"

    const val CURRENT_VERSION = 1L

    // --- Tamanos -----------------------------------------------------------

    const val PUBKEY_BYTES = 32
    const val SIG_BYTES = 64
    const val HASH_BYTES = 32
    const val SEED_BYTES = 32

    // --- Limites de esquema ------------------------------------------------

    const val MAX_DEVICES = 64
    const val MAX_ONE_TIME_PREKEYS = 128
    const val MAX_ENDPOINTS = 16
    const val MAX_NAME_BYTES = 64
    const val MAX_ENDPOINT_VALUE_BYTES = 256
    const val MAX_CAPABILITIES_UINT = 4294967295L

    // --- Limites de encoding (nivel KCE, replicados en Kce) ---------------

    const val KCE_MAX_DOCUMENT_BYTES = 65536
    const val KCE_MAX_TEXT_BYTES = 1024
    const val KCE_MAX_MAP_ITEMS = 64
    const val KCE_MAX_ARRAY_ITEMS = 256
    const val KCE_MAX_DEPTH = 8
    const val KCE_FRAMER_MAX_DEPTH = 4

    /**
     * Mayor valor representable por Kce. Es 2^64-1 en la referencia, que no
     * cabe en un Long, asi que Kce trabaja con los argumentos de cabecera
     * como Long y RECHAZA de forma explicita el rango 2^63..2^64-1 en vez
     * de dejar que se desbordara en silencio.
     */
    const val MAX_UINT64 = Long.MAX_VALUE

    // --- Numeros de verificacion -------------------------------------------

    const val SAFETY_NUMBER_DIGITS = 60
    const val SAFETY_NUMBER_GROUPS = 12
    const val SAFETY_NUMBER_GROUP_SIZE = 5
    const val SAFETY_NUMBER_SOURCE_BYTES = 25

    // --- Estados y tipos ---------------------------------------------------
    //
    // 1-BASED. El 0 esta reservado para "ausente" y es un valor invalido.

    const val STATUS_ACTIVE = 1L
    const val STATUS_REVOKED = 2L
    val DEVICE_STATUS_VALUES = setOf(STATUS_ACTIVE, STATUS_REVOKED)

    const val KIND_CONTACT = 1L
    const val KIND_DEVICE_LINK = 2L
    val BUNDLE_KIND_VALUES = setOf(KIND_CONTACT, KIND_DEVICE_LINK)

    const val ENDPOINT_ONION = 1L
    const val ENDPOINT_UDP = 2L
    const val ENDPOINT_RELAY = 3L
    val ENDPOINT_TYPE_VALUES = setOf(ENDPOINT_ONION, ENDPOINT_UDP, ENDPOINT_RELAY)

    // --- Muestras deterministas --------------------------------------------
    //
    // Corresponden a las semillas de km-id-reference/vectors/keys.json.
    // 1760000000000 ms = 2025-10-09T13:33:20Z

    const val SAMPLE_CREATED_AT = 1760000000000L
    const val SAMPLE_LINK_EXPIRES_AT = 1760000060000L
    const val SAMPLE_SPK_VALIDITY_MS = 2592000000L
}
