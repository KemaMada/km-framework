package com.keymessage.core.kmid

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.InputStream
import java.security.MessageDigest

/**
 * Carga de los vectores congelados de km-id-reference.
 *
 * El punto de este objecto es que Kotlin NO reinterpreta la especificacion:
 * comprueba byte a byte que produce lo mismo que la referencia Python.
 */
object KmIdFixtures {

    private val mapper = ObjectMapper()

    fun stream(path: String): InputStream =
        KmIdFixtures::class.java.getResourceAsStream("/km-id/$path")
            ?: error("recurso ausente: /km-id/$path")

    fun bytes(path: String): ByteArray = stream(path).use { it.readBytes() }

    fun text(path: String): String = String(bytes(path), Charsets.UTF_8)

    fun manifest(): JsonNode = mapper.readTree(bytes("MANIFEST.json"))

    fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    fun hex(data: ByteArray): String = data.joinToString("") { "%02x".format(it) }

    fun unhex(s: String): ByteArray {
        val clean = s.filterNot { it.isWhitespace() }
        require(clean.length % 2 == 0) { "hex de longitud impar: $s" }
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Nombres de los vectores de Fixture, p. ej. ["G01", "G02", ...]. */
    fun vectorIds(): List<String> =
        manifest()["vectors"].map { it["id"].asText() }

    /**
     * Ejecuta [block] y devuelve el codigo de error de KCE, o null si no hubo
     * error. Lo usan los tests negativos para fijar el codigo EXACTO y no
     * solo "que se rechazo".
     */
    fun kceErrorCode(block: () -> Unit): String? =
        try {
            block()
            null
        } catch (e: Kce.KceException) {
            e.code
        }
}
