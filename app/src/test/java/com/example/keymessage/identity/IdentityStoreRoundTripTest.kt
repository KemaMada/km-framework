package com.example.keymessage.identity

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 1.2 — Guardar y recuperar la identidad, y que un almacen roto PARE.
 *
 * ## QUE SE AFIRMA
 *
 * 1. Ida y vuelta: lo que se guarda y lo que se carga conserva `identityId` y la
 *    clave de firma. Lo que se guarda es la SEMILLA; la clave publica y el
 *    identificador se derivan de ella, y por eso el round-trip se compara con lo
 *    DERIVADO, no con lo leido.
 *  2. Un almacen corrupto LANZA [CorruptIdentityException]. El caso que se
 *    ataca es el mas peligroso: semilla y clave publica coherentes pero un
 *    `identityId` equivocado. Devolver el identificador guardado seria presentar
 *    una identidad que nadie tiene.
 *
 * ## POR QUE EL CORRUPTO NO ES UN VALOR CONSTANTE
 *
 * Un blob de ceros o de un patron repetido se detecta por cualquier comprobacion
 * de longitud. Aqui el blob corrupto tiene la longitud correcta y bytes
 * variables, de modo que solo lo detecta el CRUCE de las tres derivaciones.
 */
class IdentityStoreRoundTripTest {

    private val ed25519 = com.km.crypto.Ed25519Impl()
    private val factory = IdentityMaterialFactory(ed25519)

    private fun semilla(distintiva: Int): ByteArray =
        ByteArray(32) { ((it * 17 + distintiva * 13) % 253 + 11).toByte() }

    @Test
    fun `guardar y cargar conserva identidad y clave de firma`() {
        val store = InMemoryIdentityStore(ed25519)
        val original = factory.fromSeed(semilla(1))

        store.save(original)
        val cargado = assertNotNull(store.load(), "lo guardado se vuelve a leer")

        assertEquals(original.identityId.value, cargado.identityId.value, "misma identidad")
        assertContentEquals(original.keyPair.publicKey, cargado.keyPair.publicKey, "misma clave de firma")
        assertContentEquals(original.keyPair.privateKey, cargado.keyPair.privateKey, "misma semilla")
    }

    @Test
    fun `un almacen vacio devuelve null y no inventa identidad`() {
        val store = InMemoryIdentityStore(ed25519)

        assertNull(store.load(), "sin identidad guardada no hay identidad")
    }

    @Test
    fun `la segunda identidad SUSTITUYE a la primera`() {
        val store = InMemoryIdentityStore(ed25519)
        store.save(factory.fromSeed(semilla(1)))
        store.save(factory.fromSeed(semilla(2)))

        assertEquals(
            factory.fromSeed(semilla(2)).identityId.value,
            assertNotNull(store.load()).identityId.value,
            "guardar dos veces deja la segunda, no mezcla las dos",
        )
    }

    @Test
    fun `un identityId que no cuadra con la clave LANZA`() {
        val original = factory.fromSeed(semilla(1))
        val lineas = IdentityCodec.encode(original).split("\n").toMutableList()
        // Se conserva la longitud (64 hex) y la forma: solo cambia el VALOR.
        lineas[1] = original.identityId.value.reversed()

        val fallo = assertFailsWith<CorruptIdentityException> {
            IdentityCodec.decode(lineas.joinToString("\n"), ed25519)
        }
        assertTrue(
            fallo.message!!.contains("identityId"),
            "el fallo tiene que decir QUE no cuadra: ${fallo.message}",
        )
    }

    @Test
    fun `una clave publica que no deriva de la semilla LANZA`() {
        val original = factory.fromSeed(semilla(1))
        val otro = factory.fromSeed(semilla(2))
        val lineas = IdentityCodec.encode(original).split("\n").toMutableList()
        lineas[3] = java.util.Base64.getEncoder().encodeToString(otro.keyPair.publicKey)

        assertFailsWith<CorruptIdentityException> {
            IdentityCodec.decode(lineas.joinToString("\n"), ed25519)
        }
    }

    @Test
    fun `una semilla de tamano imposible LANZA`() {
        val original = factory.fromSeed(semilla(1))
        val lineas = IdentityCodec.encode(original).split("\n").toMutableList()
        lineas[2] = java.util.Base64.getEncoder().encodeToString(ByteArray(16) { 0x41 })

        val fallo = assertFailsWith<CorruptIdentityException> {
            IdentityCodec.decode(lineas.joinToString("\n"), ed25519)
        }
        assertTrue(fallo.message!!.contains("32"), "el fallo dice el tamano esperado: ${fallo.message}")
    }

    @Test
    fun `un texto que no es identidad LANZA`() {
        assertFailsWith<CorruptIdentityException> { IdentityCodec.decode("no soy una identidad", ed25519) }
        assertFailsWith<CorruptIdentityException> { IdentityCodec.decode("", ed25519) }
    }

    @Test
    fun `el almacen corrupto se detecta tambien a traves de load`() {
        val store = InMemoryIdentityStore(ed25519, initialText = "KM-IDENTITY-1\nbasura\nbasura\nbasura")

        assertFailsWith<CorruptIdentityException> { store.load() }
    }

    @Test
    fun `clear deja el almacen vacio`() {
        val store = InMemoryIdentityStore(ed25519)
        store.save(factory.fromSeed(semilla(1)))
        store.clear()

        assertNull(store.load(), "tras borrar no hay identidad")
    }

    @Test
    fun `una identidad generada aleatoria persiste entre arranques`() {
        val store = InMemoryIdentityStore(ed25519)
        val original = IdentityMaterialFactory(ed25519).generate()

        store.save(original)
        val cargado = assertNotNull(store.load(), "la identidad generada se recupera tras un rearranque")

        assertEquals(original.identityId.value, cargado.identityId.value, "misma identidad tras persistir")
        assertContentEquals(original.keyPair.publicKey, cargado.keyPair.publicKey, "misma clave de firma")
        assertContentEquals(original.keyPair.privateKey, cargado.keyPair.privateKey, "misma semilla")
    }

    @Test
    fun `control el codec serializa una forma legible`() {
        val original = factory.fromSeed(semilla(4))
        val texto = IdentityCodec.encode(original)
        val partes = texto.split("\n")

        assertEquals(4, partes.size, "version, identityId, semilla y clave publica")
        assertEquals("KM-IDENTITY-1", partes[0], "con version explicita: un almacen de otra version no se lee")
        assertEquals(original.identityId.value, partes[1], "el identificador va ahi, en claro, y es publico")
    }
}