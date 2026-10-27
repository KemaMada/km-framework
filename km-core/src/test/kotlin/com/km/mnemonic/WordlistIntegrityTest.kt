package com.km.mnemonic

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 6.2 — La wordlist es un DATO, y un dato se comprueba.
 *
 * ## POR QUE UN TEST DE INTEGRIDAD Y NO UN "USO"
 *
 * El fallo que hace dano aqui no es que una palabra este mal escrita: es que el
 * recurso no llegue al classpath, que le falte una linea o que se duplique una.
 * Los tres producen el mismo sintoma —una frase que parece valida y no lo es— y
 * ninguno se ve usando el modulo: `fromEntropy` y `parse` siguen funcionando
 * porque la lista que leen es la que hay.
 *
 * ## POR QUE UN SHA-256 FIJADO
 *
 * Porque el orden y el numero de entradas no cambian si alguien METE una
 * palabra nueva en medio, y cambiar el indice de una palabra cambia que frase
 * produce cada identidad existente. El hash es lo unico que detecta un cambio de
 * cualquier tipo sin tener que guardarse la lista entera en el test.
 *
 * El valor es el del `english.txt` oficial de BIP-39, que es la fuente de la que
 * se copio el fichero.
 */
class WordlistIntegrityTest {

    private fun recurso(): ByteArray =
        Mnemonic::class.java.getResourceAsStream(Mnemonic.RESOURCE)?.use { it.readBytes() }
            ?: error("el recurso ${Mnemonic.RESOURCE} no esta en el classpath")

    @Test
    fun `el recurso esta en el classpath del propio modulo`() {
        val bytes = recurso()

        assertTrue(bytes.isNotEmpty())
    }

    @Test
    fun `el recurso son los bytes oficiales de BIP-39`() {
        val digest = MessageDigest.getInstance("SHA-256").digest(recurso())

        assertEquals(
            "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda",
            digest.joinToString("") { "%02x".format(it) },
            "el english.txt cambio: si es a proposito, cambia tambien el hash de este test",
        )
    }

    @Test
    fun `la wordlist tiene 2048 entradas y ninguna repetida`() {
        val palabras = Mnemonic.palabras()

        assertEquals(2048, palabras.size)
        assertEquals(2048, palabras.toSet().size)
        assertEquals(Mnemonic.WORDLIST_SIZE, palabras.size)
    }

    @Test
    fun `la wordlist esta en orden alfabetico`() {
        val palabras = Mnemonic.palabras()

        assertContentEquals(palabras.sorted(), palabras)
    }

    @Test
    fun `las 2048 entradas son palabras ascii en minuscula`() {
        // Un indice debe caber en 11 bits Y tener una forma estable: una palabra
        // con acento o con mayuscula haria que la normalizacion del usuario
        // cambiase el indice, que es la clase de fallo que un hash no ve.
        val forma = Regex("[a-z]{3,8}")

        for ((i, palabra) in Mnemonic.palabras().withIndex()) {
            assertTrue(
                forma.matches(palabra),
                "la palabra ${i + 1} no tiene la forma esperada: ${palabra.length} caracteres",
            )
        }
    }

    @Test
    fun `el recurso no tiene lineas vacias ni espacios alrededor`() {
        val texto = String(recurso(), Charsets.UTF_8)

        assertTrue(texto.endsWith("\n"), "el fichero deberia terminar en salto de linea")
        assertTrue(!texto.contains("\r"), "el fichero deberia usar solo LF")
        for (linea in texto.split("\n").filter { it.isNotEmpty() }) {
            assertEquals(linea, linea.trim())
        }
        assertEquals(Mnemonic.WORDLIST_SIZE, texto.split("\n").filter { it.isNotEmpty() }.size)
    }

    @Test
    fun `los extremos de la wordlist son los de BIP-39`() {
        val palabras = Mnemonic.palabras()

        assertEquals("abandon", palabras.first())
        assertEquals("zoo", palabras.last())
        assertEquals("ability", palabras[1])
        assertEquals("zone", palabras[2047 - 1])
    }

    @Test
    fun `un recurso ausente da un MnemonicException legible, no un crash`() {
        // CONTROL NEGATIVO: esta ruta NO se puede ejercitar de otra forma. El
        // camino feliz se ejecuta contra el english.txt real de todos modos,
        // asi que sin esto "el recurso ausente falla bien" seria una afirmacion
        // sin ejecutar detras.
        val fallo = assertFailsWith<MnemonicException> {
            Mnemonic.cargarWordlistDe("/com/km/mnemonic/no-existe-esta.txt")
        }

        assertEquals(MnemonicMotivo.WORDLIST, fallo.motivo)
        assertTrue(fallo.message!!.contains("no-existe-esta.txt"))
        assertTrue(fallo.message!!.contains("classpath"))
    }

    @Test
    fun `la wordlist que el modulo usa sale del mismo lector`() {
        // El camino feliz por el MISMO metodo por el que falla el de arriba: si
        // `by lazy` usara otro lector, el control negativo no probaria nada.
        val cargada = Mnemonic.cargarWordlistDe(Mnemonic.RESOURCE)

        assertContentEquals(Mnemonic.palabras(), cargada)
        assertEquals(Mnemonic.WORDLIST_SIZE, cargada.size)
    }
}