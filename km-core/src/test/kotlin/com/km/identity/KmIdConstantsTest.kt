package com.km.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Las constantes normativas de KM-ID-0001 contra el MANIFEST congelado.
 *
 * POR QUE ESTE TEST EXISTE
 *
 * Las constantes son invisibles para los vectores golden: G01..G03 solo
 * ejercitan separadores de dominio y hashes, asi que un enumerado mal
 * transcrito pasa todos los tests y solo falla meses despues, en
 * interoperabilidad, como un rechazo aparentemente inexplicable.
 *
 * Ese fallo ocurrio de verdad durante el port: STATUS_ACTIVE, KIND_CONTACT y
 * ENDPOINT_* se escribieron 0-based cuando la referencia usa 1-based, y
 * SAMPLE_SPK_VALIDITY_MS quedo en 7 dias en lugar de 30. Nada lo detecto
 * porque ningun vector construye un roster. A partir de aqui, cualquier
 * cambio de valor en KmIdConstants es un fallo de test immediate.
 */
class KmIdConstantsTest {

    private fun manifestConstants(): Map<String, Any?> {
        val node = KmIdFixtures.manifest()["constants"]
            ?: fail("el MANIFEST no expone el bloque 'constants'; regenera la referencia")
        val out = LinkedHashMap<String, Any?>()
        node.fieldNames().forEach { out[it] = if (node[it].isInt) node[it].asLong() else node[it].asText() }
        return out
    }

    @Test
    fun `cada constante Kotlin coincide con la referencia congelada`() {
        val ref = manifestConstants()

        // La fuente unica: los valores observados en KmIdConstants.
        val real: Map<String, Any?> = mapOf(
            "DS_IDENTITY" to KmIdConstants.DS_IDENTITY,
            "DS_DEVICE" to KmIdConstants.DS_DEVICE,
            "DS_NODE" to KmIdConstants.DS_NODE,
            "DS_ROSTER_GENESIS" to KmIdConstants.DS_ROSTER_GENESIS,
            "DS_ROSTER" to KmIdConstants.DS_ROSTER,
            "DS_ROSTER_CODE" to KmIdConstants.DS_ROSTER_CODE,
            "DS_SAFETY_NUMBER" to KmIdConstants.DS_SAFETY_NUMBER,
            "DS_ROTATION" to KmIdConstants.DS_ROTATION,
            "DS_CONTACT_BUNDLE" to KmIdConstants.DS_CONTACT_BUNDLE,
            "DS_DEVICE_LINK" to KmIdConstants.DS_DEVICE_LINK,
            "DS_PREKEY_SIGNED" to KmIdConstants.DS_PREKEY_SIGNED,
            "DS_PREKEY_ONETIME" to KmIdConstants.DS_PREKEY_ONETIME,
            "DOC_ROSTER" to KmIdConstants.DOC_ROSTER,
            "DOC_ROTATION" to KmIdConstants.DOC_ROTATION,
            "DOC_CONTACT_BUNDLE" to KmIdConstants.DOC_CONTACT_BUNDLE,
            "DOC_SIGNED_PREKEY" to KmIdConstants.DOC_SIGNED_PREKEY,
            "DOC_ONETIME_PREKEY" to KmIdConstants.DOC_ONETIME_PREKEY,
            "DOC_LINK_CHALLENGE" to KmIdConstants.DOC_LINK_CHALLENGE,
            "DOC_LINK_RESPONSE" to KmIdConstants.DOC_LINK_RESPONSE,
            "CURRENT_VERSION" to KmIdConstants.CURRENT_VERSION,
            "PUBKEY_BYTES" to KmIdConstants.PUBKEY_BYTES.toLong(),
            "SIG_BYTES" to KmIdConstants.SIG_BYTES.toLong(),
            "HASH_BYTES" to KmIdConstants.HASH_BYTES.toLong(),
            "MAX_DEVICES" to KmIdConstants.MAX_DEVICES.toLong(),
            "MAX_ONE_TIME_PREKEYS" to KmIdConstants.MAX_ONE_TIME_PREKEYS.toLong(),
            "MAX_ENDPOINTS" to KmIdConstants.MAX_ENDPOINTS.toLong(),
            "MAX_NAME_BYTES" to KmIdConstants.MAX_NAME_BYTES.toLong(),
            "MAX_ENDPOINT_VALUE_BYTES" to KmIdConstants.MAX_ENDPOINT_VALUE_BYTES.toLong(),
            "MAX_CAPABILITIES_UINT" to KmIdConstants.MAX_CAPABILITIES_UINT,
            "KCE_MAX_DOCUMENT_BYTES" to KmIdConstants.KCE_MAX_DOCUMENT_BYTES.toLong(),
            "KCE_MAX_TEXT_BYTES" to KmIdConstants.KCE_MAX_TEXT_BYTES.toLong(),
            "KCE_MAX_MAP_ITEMS" to KmIdConstants.KCE_MAX_MAP_ITEMS.toLong(),
            "KCE_MAX_ARRAY_ITEMS" to KmIdConstants.KCE_MAX_ARRAY_ITEMS.toLong(),
            "KCE_MAX_DEPTH" to KmIdConstants.KCE_MAX_DEPTH.toLong(),
            "KCE_FRAMER_MAX_DEPTH" to KmIdConstants.KCE_FRAMER_MAX_DEPTH.toLong(),
            "STATUS_ACTIVE" to KmIdConstants.STATUS_ACTIVE,
            "STATUS_REVOKED" to KmIdConstants.STATUS_REVOKED,
            "KIND_CONTACT" to KmIdConstants.KIND_CONTACT,
            "KIND_DEVICE_LINK" to KmIdConstants.KIND_DEVICE_LINK,
            "ENDPOINT_ONION" to KmIdConstants.ENDPOINT_ONION,
            "ENDPOINT_UDP" to KmIdConstants.ENDPOINT_UDP,
            "ENDPOINT_RELAY" to KmIdConstants.ENDPOINT_RELAY,
            "SAFETY_NUMBER_DIGITS" to KmIdConstants.SAFETY_NUMBER_DIGITS.toLong(),
            "SAFETY_NUMBER_GROUPS" to KmIdConstants.SAFETY_NUMBER_GROUPS.toLong(),
            "SAFETY_NUMBER_GROUP_SIZE" to KmIdConstants.SAFETY_NUMBER_GROUP_SIZE.toLong(),
            "SAFETY_NUMBER_SOURCE_BYTES" to KmIdConstants.SAFETY_NUMBER_SOURCE_BYTES.toLong(),
            "SAMPLE_CREATED_AT" to KmIdConstants.SAMPLE_CREATED_AT,
            "SAMPLE_LINK_EXPIRES_AT" to KmIdConstants.SAMPLE_LINK_EXPIRES_AT,
            "SAMPLE_SPK_VALIDITY_MS" to KmIdConstants.SAMPLE_SPK_VALIDITY_MS,
        )

        val diferencias = mutableListOf<String>()
        for ((clave, esperado) in real) {
            val obtenido = ref[clave] ?: run {
                diferencias += "$clave: no aparece en el MANIFEST"
                continue
            }
            if (obtenido.toString() != esperado.toString()) {
                diferencias += "$clave: referencia=$obtenido, kotlin=$esperado"
            }
        }
        assertTrue(
            diferencias.isEmpty(),
            "constantes divergentes del congelado:\n  " + diferencias.joinToString("\n  ")
        )
    }

    @Test
    fun `toda constante del MANIFEST tiene contraparte en Kotlin`() {
        val ref = manifestConstants()
        val noCubiertas = ref.keys - CONTRAPARTES_KOTLIN
        assertTrue(
            noCubiertas.isEmpty(),
            "el MANIFEST expone constantes que Kotlin no refleja: $noCubiertas. " +
                "Anadelas a KmIdConstants y a este test, o justifies la omision aqui."
        )
    }

    @Test
    fun `los enumerados son 1-based y el 0 queda libre`() {
        // El 0 reservado para "ausente" es lo que hace visible un campo sin
        // inicializar como invalido, en lugar de colarse por un estado valido.
        for ((nombre, valor) in listOf(
            "STATUS_ACTIVE" to KmIdConstants.STATUS_ACTIVE,
            "STATUS_REVOKED" to KmIdConstants.STATUS_REVOKED,
            "KIND_CONTACT" to KmIdConstants.KIND_CONTACT,
            "KIND_DEVICE_LINK" to KmIdConstants.KIND_DEVICE_LINK,
            "ENDPOINT_ONION" to KmIdConstants.ENDPOINT_ONION,
            "ENDPOINT_UDP" to KmIdConstants.ENDPOINT_UDP,
            "ENDPOINT_RELAY" to KmIdConstants.ENDPOINT_RELAY,
        )) {
            assertTrue(valor > 0, "$nombre debe ser > 0; 0 esta reservado para ausente")
        }
    }

    @Test
    fun `los limites de KCE son los mismos en Kce y en KmIdConstants`() {
        // Una sola fuente: si divergen, el fallo aparece en el limite.
        assertEquals(KmIdConstants.KCE_MAX_DOCUMENT_BYTES, Kce.MAX_DOCUMENT_BYTES)
        assertEquals(KmIdConstants.KCE_MAX_TEXT_BYTES, Kce.MAX_TEXT_BYTES)
        assertEquals(KmIdConstants.KCE_MAX_MAP_ITEMS, Kce.MAX_MAP_ITEMS)
        assertEquals(KmIdConstants.KCE_MAX_ARRAY_ITEMS, Kce.MAX_ARRAY_ITEMS)
        assertEquals(KmIdConstants.KCE_MAX_DEPTH, Kce.MAX_DEPTH)
        assertEquals(KmIdConstants.KCE_FRAMER_MAX_DEPTH, Kce.FRAMER_MAX_DEPTH)
    }

    private companion object {
        val CONTRAPARTES_KOTLIN = setOf(
            "DS_IDENTITY", "DS_DEVICE", "DS_NODE", "DS_ROSTER_GENESIS", "DS_ROSTER",
            "DS_ROSTER_CODE", "DS_SAFETY_NUMBER", "DS_ROTATION", "DS_CONTACT_BUNDLE",
            "DS_DEVICE_LINK", "DS_PREKEY_SIGNED", "DS_PREKEY_ONETIME",
            "DOC_ROSTER", "DOC_ROTATION", "DOC_CONTACT_BUNDLE", "DOC_SIGNED_PREKEY",
            "DOC_ONETIME_PREKEY", "DOC_LINK_CHALLENGE", "DOC_LINK_RESPONSE",
            "CURRENT_VERSION", "PUBKEY_BYTES", "SIG_BYTES", "HASH_BYTES",
            "MAX_DEVICES", "MAX_ONE_TIME_PREKEYS", "MAX_ENDPOINTS", "MAX_NAME_BYTES",
            "MAX_ENDPOINT_VALUE_BYTES", "MAX_CAPABILITIES_UINT",
            "KCE_MAX_DOCUMENT_BYTES", "KCE_MAX_TEXT_BYTES", "KCE_MAX_MAP_ITEMS",
            "KCE_MAX_ARRAY_ITEMS", "KCE_MAX_DEPTH", "KCE_FRAMER_MAX_DEPTH",
            "STATUS_ACTIVE", "STATUS_REVOKED", "KIND_CONTACT", "KIND_DEVICE_LINK",
            "ENDPOINT_ONION", "ENDPOINT_UDP", "ENDPOINT_RELAY",
            "SAFETY_NUMBER_DIGITS", "SAFETY_NUMBER_GROUPS", "SAFETY_NUMBER_GROUP_SIZE",
            "SAFETY_NUMBER_SOURCE_BYTES",
            "SAMPLE_CREATED_AT", "SAMPLE_LINK_EXPIRES_AT", "SAMPLE_SPK_VALIDITY_MS",
        )
    }
}
