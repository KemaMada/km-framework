package com.example.keymessage.identity

import com.km.mnemonic.Mnemonic
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 6.4 — El circuito cerrado de la recuperacion: frase -> semilla -> la MISMA
 * identidad, byte a byte.
 *
 * ## LO QUE SE AFIRMA
 *
 * Que una identidad creada por `generate()` —con su semilla de 32 bytes leida de
 * `/dev/urandom`— se puede volver a construir DESDE SU FRASE y sale exactamente
 * igual: misma clave publica, misma clave privada y mismo `identityId`. No es que
 * "sea la misma identidad" en el sentido de que enrute igual: es que los bytes
 * son los mismos, que es la unica forma de que la conversacion que ya existia
 * siga existiendo.
 *
 * ## POR QUE LA SEMILLA DE ED25519 Y NO EL PBKDF2 DE BIP39
 *
 * Porque `Ed25519.keyPairFromSeed` devuelve la semilla COMO clave privada
 * (RFC 8032) y `IdentityCodec` persiste esa misma semilla: lo que se transporta
 * en la frase es exactamente lo que se guarda en disco. Un BIP39 con PBKDF2
 * tendria un `seed` de 64 bytes distinto de la semilla de la app, y entonces la
 * frase no reconstruiria la identidad, sino una clave que habria que volver a
 * convertir.
 *
 * ## LO QUE ESTA EN EL CLASSPATH DE LA APP
 *
 * El wordlist vive en el jar de `km-core` y la app lo consume POR ARTEFACTO
 * desde mavenLocal. Si el recurso no llegara transitivo, todo lo de aqui
 * fallaria con un error de recurso ausente: estos tests son tambien la prueba de
 * que la wordlist viaja.
 */
class RecoveryRestoreTest {

    private val factory = IdentityMaterialFactory()

    /** Semilla NO constante: bytes distintos y en ascension. */
    private fun semilla(distintiva: Int): ByteArray =
        ByteArray(32) { ((it * 17 + distintiva * 29) % 251 + 3).toByte() }

    @Test
    fun `restaurar desde la frase de generate reproduce la identidad byte a byte`() {
        val generada = factory.generate()
        val frase = fraseDeRecuperacion(generada)
        assertEquals(24, frase.wordCount)

        // El viaje de vuelta completo: texto -> frase -> bytes -> par.
        val leida = Mnemonic.parse(frase.display())
        val restaurada = factory.fromSeed(Mnemonic.toEntropy(leida))

        assertContentEquals(
            generada.keyPair.publicKey,
            restaurada.keyPair.publicKey,
            "la clave publica tiene que salir byte a byte igual",
        )
        assertContentEquals(
            generada.keyPair.privateKey,
            restaurada.keyPair.privateKey,
            "la clave privada ES la semilla: si difiere, la frase no es de esta identidad",
        )
        assertEquals(generada.identityId.value, restaurada.identityId.value)
    }

    @Test
    fun `el circuito cerrado con una semilla fija tambien vuelve al mismo punto`() {
        // El caso anterior depende de `/dev/urandom`, que no falla nunca pero no
        // es reproducible. Este se siembra, de modo que si el circuito se rompe
        // el fallo sale siempre igual.
        val original = factory.fromSeed(semilla(3))

        val frase = fraseDeRecuperacion(original)
        val restaurada = factory.fromSeed(Mnemonic.toEntropy(Mnemonic.parse(frase.display())))

        assertContentEquals(original.keyPair.publicKey, restaurada.keyPair.publicKey)
        assertEquals(original.identityId.value, restaurada.identityId.value)
    }

    @Test
    fun `distintas semillas dan frases distintas y el mismo texto no`() {
        val una = fraseDeRecuperacion(factory.fromSeed(semilla(1)))
        val otra = fraseDeRecuperacion(factory.fromSeed(semilla(2)))

        assertTrue(una.display() != otra.display(), "dos semillas distintas, dos frases")
        assertTrue(
            una.display().split(" ").toSet() != otra.display().split(" ").toSet(),
            "y no solo cambia el orden: las palabras tienen que ser otras",
        )
    }

    @Test
    fun `la frase pegada con saltos de linea restaura igual que la pegada en una linea`() {
        val original = factory.fromSeed(semilla(5))
        val palabras = fraseDeRecuperacion(original).display().split(" ")

        val pegada = palabras.joinToString("\n") { it.uppercase() } + "\n  "
        val restaurada = factory.fromSeed(Mnemonic.toEntropy(Mnemonic.parse(pegada)))

        assertEquals(original.identityId.value, restaurada.identityId.value)
    }

    @Test
    fun `la identidad restaurada se guarda y sobrevive a un store nuevo`() {
        // "Sobrevive a un reinicio de proceso": un `InMemoryIdentityStore` nuevo
        // construido con el TEXTO del anterior es el mismo almacen con otro
        // objeto delante, que es lo que pasa cuando el proceso vuelve a arrancar.
        val original = factory.fromSeed(semilla(7))
        val antes = InMemoryIdentityStore()
        antes.save(original)

        val despues = InMemoryIdentityStore(initialText = antes.text)
        val cargada = assertNotNull(despues.load())

        assertEquals(original.identityId.value, cargada.identityId.value)
        assertContentEquals(original.keyPair.publicKey, cargada.keyPair.publicKey)
        assertContentEquals(original.keyPair.privateKey, cargada.keyPair.privateKey)
    }

    @Test
    fun `restaurar encima de una identidad existente deja solo la restaurada`() {
        val primera = factory.fromSeed(semilla(11))
        val segunda = factory.fromSeed(semilla(12))
        val store = InMemoryIdentityStore()
        store.save(primera)

        // Esto es lo que hace `RecoveryFlow` por dentro: frase -> material ->
        // `save()`. El store se reemplaza entero, no se mezcla.
        store.save(factory.fromSeed(Mnemonic.toEntropy(Mnemonic.parse(fraseDeRecuperacion(segunda).display()))))

        val cargada = assertNotNull(store.load())
        assertEquals(segunda.identityId.value, cargada.identityId.value)
        assertTrue(cargada.identityId.value != primera.identityId.value, "no es la identidad anterior")
    }

    @Test
    fun `fromSeedEfimera destruye la semilla de entrada y deja el material intacto`() {
        val semillaEfimera = semilla(13)

        val material = factory.fromSeedEfimera(semillaEfimera)

        assertTrue(
            semillaEfimera.all { it == 0.toByte() },
            "la semilla de entrada tiene que quedar a cero: es la copia en claro de la clave",
        )
        assertEquals(32, material.keyPair.privateKey.size)
        assertTrue(
            material.keyPair.privateKey.any { it != 0.toByte() },
            "y el material derivado sigue siendo el bueno: `Ed25519` copia la entrada",
        )
    }

    @Test
    fun `el wordlist de BIP39 viaja en el jar de km-core y la app lo ve`() {
        // `Mnemonic.parse` sin anadir ningun recurso: si el english.txt no
        // llegara transitivo al artefacto publicado, esto falla con WORDLIST.
        // La frase es la del vector oficial de entropia nula: 23 "abandon" (que
        // son el indice 0) mas la palabra cuyo indice es el byte de checksum.
        val frase = Mnemonic.parse((List(23) { "abandon" } + "art").joinToString(" "))

        assertEquals(24, frase.wordCount)
        assertEquals("abandon", frase.wordAt(0))
        assertEquals("art", frase.wordAt(23))
        assertContentEquals(ByteArray(32), Mnemonic.toEntropy(frase))
    }
}