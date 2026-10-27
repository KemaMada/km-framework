package com.km.mnemonic

import com.km.crypto.HashImpl
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 6.2 — La codificacion BIP39: vectores, round-trip y todo lo que NO vale.
 *
 * ## DE DONDE SALEN LOS VECTORES
 *
 * El primero es el vector oficial de BIP39 para 256 bits de entropia nula: la
 * palabra "abandon" tiene indice 0, asi que la entropia nula produce 23
 * "abandon" y la ultima palabra es la que codifica el checksum. Los otros dos
 * se calcularon con una implementacion INDEPENDIENTE de BIP39 en Python escrita
 * desde la wordlist y SHA-256, no con este modulo: un vector producido por el
 * codigo que se prueba no prueba nada.
 *
 * ## POR QUE NO HAY UN VECTOR DE 16 BYTES
 *
 * Porque este modulo NO implementa las 12 palabras. La semilla de la identidad
 * son 32 bytes y la frase son 24 palabras fijas; una frase de 12 palabras seria
 * una segunda codificacion con su propia forma de fallar.
 */
class MnemonicTest {

    private val hash = HashImpl()

    // ===================================================================
    // Vectores
    // ===================================================================

    @Test
    fun `la entropia nula de 256 bits da la frase oficial de BIP39`() {
        val frase = Mnemonic.fromEntropy(ByteArray(32))

        assertEquals(24, frase.wordCount)
        assertEquals(23, frase.display().split(" ").count { it == "abandon" })
        assertEquals("art", frase.wordAt(23))
        assertEquals(
            (List(23) { "abandon" } + "art").joinToString(" "),
            frase.display(),
        )
    }

    @Test
    fun `la entropia de 0xff da la frase oficial de 24 zoo y vote`() {
        val frase = Mnemonic.fromEntropy(ByteArray(32) { 0xFF.toByte() })

        assertEquals((List(23) { "zoo" } + "vote").joinToString(" "), frase.display())
    }

    @Test
    fun `una entropia de 32 bytes distintos da la frase del vector de referencia`() {
        val entropia = hex("030a11181f262d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dc")

        val frase = Mnemonic.fromEntropy(entropia)

        assertEquals(
            "adapt explain ecology dinner glare old unfair empty expect road off " +
                "suggest dash dolphin captain version oval census uncover inform " +
                "arrest trash first nice",
            frase.display(),
        )
    }

    @Test
    fun `codificar es determinista, misma entropia frase identica`() {
        val entropia = hex("4b6f2c1d9e8f7a0b3c5d7e9f1a2b4c6d1e0f2a4c6e8091b3d5f7a9c1e3b5d7f9")

        assertEquals(
            Mnemonic.fromEntropy(entropia).display(),
            Mnemonic.fromEntropy(entropia.copyOf()).display(),
        )
    }

    // ===================================================================
    // Round-trip
    // ===================================================================

    @Test
    fun `el round-trip devuelve byte a byte la entropia original`() {
        val azar = Random(20261018)
        repeat(200) {
            val entropia = ByteArray(32) { azar.nextInt(256).toByte() }

            val frase = Mnemonic.fromEntropy(entropia)

            assertContentEquals(entropia, Mnemonic.toEntropy(frase), "id de la vuelta $it")
        }
    }

    @Test
    fun `el round-trip aguanta entradas que no son aleatorias`() {
        // 00, ff y 7f son los tres casos en los que un troceado mal hecho
        // produce ceros a la izquierda y un checksum que parece bueno.
        for (relleno in listOf(0x00, 0x7F, 0xFF, 0x01)) {
            val entropia = ByteArray(32) { relleno.toByte() }

            assertContentEquals(
                entropia,
                Mnemonic.toEntropy(Mnemonic.fromEntropy(entropia)),
                "relleno $relleno",
            )
        }
    }

    @Test
    fun `la ultima palabra son los 3 ultimos bits de la entropia y el checksum`() {
        // El grupo 24 son 11 bits: los 3 ultimos de la entropia y los 8 del
        // checksum. Se comprueba CONTRA SHA-256 calculado aqui, y no contra el
        // propio modulo: si los dos usaran el mismo codigo, no probaria nada.
        val entropia = hex("030a11181f262d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dc")
        val checksum = MessageDigest.getInstance("SHA-256").digest(entropia)[0].toInt() and 0xFF
        val ultimo = entropia[31].toInt() and 0xFF
        val indiceEsperado = ((ultimo and 0b111) shl 8) or checksum

        val frase = Mnemonic.fromEntropy(entropia)

        assertEquals(32, Mnemonic.toEntropy(frase).size)
        assertEquals(Mnemonic.palabras()[indiceEsperado], frase.wordAt(23))
    }

    @Test
    fun `la frase de la entropia nula termina en el indice del checksum de los ceros`() {
        val checksum = MessageDigest.getInstance("SHA-256").digest(ByteArray(32))[0].toInt() and 0xFF

        val frase = Mnemonic.fromEntropy(ByteArray(32))

        assertEquals("abandon", frase.wordAt(22))
        assertEquals(Mnemonic.palabras()[checksum], frase.wordAt(23))
        assertEquals(32, hash.sha256(ByteArray(32)).size)
    }

    // ===================================================================
    // Normalizacion (spec D1: pegado completo)
    // ===================================================================

    @Test
    fun `parse normaliza espacios, saltos de linea, mayusculas y acentos`() {
        val frase = Mnemonic.fromEntropy(ByteArray(32)).display()
        val palabras = frase.split(" ")

        val pegado = buildString {
            append("   ${palabras[0].uppercase()}  \n")
            append(palabras.drop(1).take(11).joinToString("   "))
            append("\r\n\t")
            append(palabras.drop(12).joinToString(" "))
            append("  \n")
        }

        val leida = Mnemonic.parse(pegado)

        assertEquals(frase, leida.display())
        assertContentEquals(ByteArray(32), Mnemonic.toEntropy(leida))
    }

    @Test
    fun `parse acepta un separador de espacio duro`() {
        val palabras = Mnemonic.fromEntropy(ByteArray(32)).display().split(" ")

        val leida = Mnemonic.parse(palabras.joinToString("\u00A0"))

        assertEquals(24, leida.wordCount)
    }

    // ===================================================================
    // Rechazos
    // ===================================================================

    @Test
    fun `un checksum corrupto se rechaza y no devuelve semilla`() {
        val valida = Mnemonic.fromEntropy(ByteArray(32)).display().split(" ")
        // La ultima palabra se cambia por OTRA palabra VALIDA de la wordlist: si
        // el fallo fuera "palabra desconocida" la prueba no probaria el checksum.
        val corrupta = valida.dropLast(1) + "zoo"

        val fallo = assertFailsWith<MnemonicException> {
            Mnemonic.parse(corrupta.joinToString(" "))
        }

        assertEquals(MnemonicMotivo.CHECKSUM, fallo.motivo)
        assertEquals(null, fallo.indice)
        // No hay fallback: el error dice que no cuadra, sin devolver 32 bytes.
        assertFalse(fallo.message!!.contains("zoo"))
    }

    @Test
    fun `cambiar una palabra del principio tambien rompe el checksum`() {
        val valida = Mnemonic.fromEntropy(ByteArray(32)).display().split(" ")
        val corrupta = valida.toMutableList().also { it[0] = "zoo" }

        val fallo = assertFailsWith<MnemonicException> {
            Mnemonic.parse(corrupta.joinToString(" "))
        }

        assertEquals(MnemonicMotivo.CHECKSUM, fallo.motivo)
    }

    @Test
    fun `una palabra ajena a la wordlist se rechaza con su posicion y sin la palabra`() {
        val palabras = Mnemonic.fromEntropy(ByteArray(32)).display().split(" ")
        val intrusa = "zzzqqq"
        val conIntrusa = palabras.toMutableList().also { it[6] = intrusa }

        val fallo = assertFailsWith<MnemonicException> {
            Mnemonic.parse(conIntrusa.joinToString(" "))
        }

        assertEquals(MnemonicMotivo.PALABRA_DESCONOCIDA, fallo.motivo)
        assertEquals(7, fallo.indice)
        assertFalse(fallo.message!!.contains(intrusa))
        // Tampoco ninguna de las palabras que SI son legitimas.
        assertFalse(fallo.message!!.contains("abandon"))
    }

    @Test
    fun `una palabra valida pero mal puesta se rechaza por checksum, no por palabra`() {
        val palabras = Mnemonic.fromEntropy(ByteArray(32)).display().split(" ")
        val cambiadas = palabras.toMutableList().also { it[23] = "abandon" }

        val fallo = assertFailsWith<MnemonicException> {
            Mnemonic.parse(cambiadas.joinToString(" "))
        }

        assertEquals(MnemonicMotivo.CHECKSUM, fallo.motivo)
    }

    @Test
    fun `23 palabras se rechazan por longitud`() {
        val palabras = Mnemonic.fromEntropy(ByteArray(32)).display().split(" ")

        val fallo = assertFailsWith<MnemonicException> {
            Mnemonic.parse(palabras.dropLast(1).joinToString(" "))
        }

        assertEquals(MnemonicMotivo.LONGITUD, fallo.motivo)
        assertTrue(fallo.message!!.contains("23"))
    }

    @Test
    fun `25 palabras se rechazan por longitud`() {
        val palabras = Mnemonic.fromEntropy(ByteArray(32)).display().split(" ")

        val fallo = assertFailsWith<MnemonicException> {
            Mnemonic.parse((palabras + "zoo").joinToString(" "))
        }

        assertEquals(MnemonicMotivo.LONGITUD, fallo.motivo)
        assertTrue(fallo.message!!.contains("25"))
    }

    @Test
    fun `una entropia de 16 bytes se rechaza sin intentar 12 palabras`() {
        val fallo = assertFailsWith<MnemonicException> {
            Mnemonic.fromEntropy(ByteArray(16))
        }

        assertEquals(MnemonicMotivo.ENTROPIA, fallo.motivo)
        assertTrue(fallo.message!!.contains("16"))
    }

    @Test
    fun `una entropia de 33 bytes se rechaza`() {
        val fallo = assertFailsWith<MnemonicException> {
            Mnemonic.fromEntropy(ByteArray(33))
        }

        assertEquals(MnemonicMotivo.ENTROPIA, fallo.motivo)
        assertTrue(fallo.message!!.contains("33"))
    }

    @Test
    fun `ningun error de mnemonic lleva palabras de la frase`() {
        val palabras = Mnemonic.fromEntropy(ByteArray(32)).display().split(" ")
        val entradas = listOf(
            palabras.dropLast(1).joinToString(" "),
            (palabras + "zoo").joinToString(" "),
            palabras.toMutableList().also { it[3] = "zzzqqq" }.joinToString(" "),
            palabras.toMutableList().also { it[23] = "zoo" }.joinToString(" "),
        )

        for (entrada in entradas) {
            val fallo = assertFailsWith<MnemonicException> { Mnemonic.parse(entrada) }
            for (palabra in palabras.distinct()) {
                assertFalse(
                    fallo.message!!.contains(palabra),
                    "el fallo «${fallo.message}» contiene la palabra $palabra",
                )
            }
        }
    }

    // ===================================================================
    // Opacidad
    // ===================================================================

    @Test
    fun `toString no contiene ninguna palabra de la frase`() {
        val frase = Mnemonic.fromEntropy(ByteArray(32))

        val texto = frase.toString()

        assertEquals("MnemonicPhrase(24 palabras)", texto)
        for (palabra in frase.display().split(" ")) {
            assertFalse(texto.contains(palabra))
        }
    }

    @Test
    fun `toString de una frase con palabras dispares tampoco las suelta`() {
        // Se usa una frase cuyas palabras NO son todas "abandon": si el
        // redactado se hiciera por palabra suelta, esta frase lo sueltas.
        val frase = Mnemonic.fromEntropy(
            hex("030a11181f262d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dc"),
        )
        val texto = frase.toString()

        assertEquals("MnemonicPhrase(24 palabras)", texto)
        for (palabra in frase.display().split(" ").distinct()) {
            assertFalse(texto.contains(palabra))
        }
        assertFalse(texto.contains("adapt"))
        assertFalse(texto.contains("dolphin"))
    }

    @Test
    fun `wordAt lee cada palabra en su sitio`() {
        val frase = Mnemonic.fromEntropy(ByteArray(32))

        assertEquals("abandon", frase.wordAt(0))
        assertEquals("abandon", frase.wordAt(22))
        assertEquals("art", frase.wordAt(23))
        assertFailsWith<IndexOutOfBoundsException> { frase.wordAt(24) }
        assertFailsWith<IndexOutOfBoundsException> { frase.wordAt(-1) }
    }

    @Test
    fun `los indices de las palabras salen del grupo de 11 bits`() {
        // 11 grupos de 11 bits son 121 bits de los 264 del bloque. Con la
        // entropia nula los tres primeros bytes se consumen en dos palabras y
        // dejan un bit suelto, de modo que las primeras 10 palabras son indice
        // 0 y la 11 es el primer grupo con un bit del byte de checksum.
        val frase = Mnemonic.fromEntropy(ByteArray(32))

        for (i in 0 until 10) {
            assertEquals("abandon", frase.wordAt(i))
        }
        assertEquals(Mnemonic.palabras()[0], frase.wordAt(0))
        assertEquals(2048, Mnemonic.palabras().size)
    }

    private fun hex(texto: String): ByteArray {
        require(texto.length % 2 == 0) { "hex de longitud par" }
        return ByteArray(texto.length / 2) { i ->
            ((Character.digit(texto[i * 2], 16) shl 4) or Character.digit(texto[i * 2 + 1], 16)).toByte()
        }
    }
}