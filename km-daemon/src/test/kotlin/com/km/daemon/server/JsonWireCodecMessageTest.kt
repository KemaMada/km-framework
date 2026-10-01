package com.km.daemon.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.km.daemon.util.Base64Standard
import com.km.model.IdentityId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.security.SecureRandom

/**
 * La propiedad central del spec, y la unica que este modulo decide.
 *
 * KM-WIRE-RELAY §6.2: **el frame que entra es byte a byte el frame que
 * sale**. Es verificable sobre el codigo, no es una intencion: si la
 * transcodificacion tocara un solo bit, este test cae.
 *
 * Los bytes de los casos son ADVERSOS a proposito, no un `byteArrayOf(1,2,3)`:
 * cubren los 256 valores posibles y las tres longitudes de residuo modulo 3
 * (las que deciden cuanto padding lleva el Base64 de salida). Un test con tres
 * numeros pequenos pasaria con casi cualquier codificacion y no probaria
 * nada: en UTF-8, todos los bytes ASCII sobreviven a un `String(bytes)`, que
 * es exactamente el error que documented el cliente Android.
 *
 * El segundo caso cubre la otra mitad de "opaco" (§14.1): un Base64 invalido
 * se descarta ENTERO. No solo no se altera lo que llega: tampoco se inventa
 * lo que no llega.
 */
class JsonWireCodecMessageTest {

    private val mapper = ObjectMapper()
    private val codec = JsonWireCodec()
    private val from = IdentityId("a".repeat(64))
    private val to = IdentityId("b".repeat(64))

    @Test
    fun `la carga de MESSAGE sobrevive al viaje de ida y vuelta byte a byte`() {
        val random = SecureRandom()
        for (size in listOf(0, 1, 2, 3, 44, 255, 256, 4096, 65535)) {
            val original = ByteArray(size).also { random.nextBytes(it) }
            // Los 256 valores, para que ningun byte sobreviva por ser ASCII
            // imprimible y por tanto idempotente en UTF-8.
            if (size >= 256) for (i in 0 until 256) original[i] = i.toByte()

            // Salida: ByteArray -> Base64 estandar -> JSON MESSAGE.
            val wire = codec.message(from, to, original)
            val frame = codec.readObject(wire)
            assertNotNull(frame, "el frame emitido debe ser un objeto JSON (tamano $size)")
            assertEquals("MESSAGE", codec.typeOf(frame!!))
            assertEquals(Base64Standard.encode(original), frame.get("data").asText())

            // Entrada: el mismo JSON vuelve a entrar por el codec.
            val parsed = codec.parseMessage(frame)
            assertNotNull(parsed, "MESSAGE debe poder releerse (tamano $size)")
            assertEquals(from, parsed!!.from)
            assertEquals(to, parsed.to)
            assertEquals(original.size, parsed.data.size)
            assertArrayEquals(
                original,
                parsed.data,
                "la carga debe salir identica byte a byte (tamano $size, §6.2)",
            )
        }
    }

    @Test
    fun `un Base64 invalido se descarta entero, nunca parcialmente - seccion 14 punto 1`() {
        val invalidos = listOf(
            "AQ==A",  // caracteres fuera del alfabeto estandar
            "AQ D=",  // whitespace: NO se tolera en silencio
            "AQ-_",   // alfabeto URL-safe donde va el estandar
            "A",      // longitud imposible: len % 4 == 1
        )
        for (invalido in invalidos) {
            assertNull(
                codec.parseMessage(messageWithData(invalido)),
                "'$invalido' no es Base64 estandar valido: el frame se descarta entero",
            )
        }

        // El borde positivo que un decoder con las manos torpes tritura: un
        // unico byte, que es el que lleva el padding maximo ("/w==" para 0xFF).
        val unByte = byteArrayOf(0xFF.toByte())
        assertArrayEquals(unByte, codec.parseMessage(messageWithData("/w=="))?.data)
    }

    /** `MESSAGE` con un campo `data` arbitrario, sin pasar por el encoder. */
    private fun messageWithData(data: String): ObjectNode {
        val node = mapper.createObjectNode()
        node.put("type", "MESSAGE")
        node.put("from", from.value)
        node.put("to", to.value)
        node.put("data", data)
        return node
    }
}
