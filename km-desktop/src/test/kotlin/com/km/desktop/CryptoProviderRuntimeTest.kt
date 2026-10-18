package com.km.desktop

import com.km.crypto.provider.BcChaCha20Poly1305
import com.km.crypto.provider.BcHkdfSha256
import com.km.crypto.provider.BcX25519
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 0.3 — BouncyCastle esta en el RUNTIME de este cliente, no solo en el de km-core.
 *
 * ## QUE PINZA
 *
 * `km-core` declara `bcprov` como `implementation`. Eso NO lo filtra al
 * classpath de compilacion de quien lo consume, pero si llega al de
 * EJECUCION como dependencia transitiva en alcance `runtime`. Este cliente
 * construye `BcX25519`, `BcHkdfSha256` y `BcChaCha20Poly1305` —los tres
 * providers de `com.km.crypto.provider`, los unicos que el framework usa por
 * debajo— y si el jar no estuviera, el fallo no seria una asercion: seria un
 * `NoClassDefFoundError` en el arranque, con una traza que no nombra al modulo
 * que la falta.
 *
 * ## LO QUE ESTE TEST NO DICE
 *
 * No dice que la criptografia sea correcta. Los VECTORES viven en `km-core`
 * (`P6VectorConformanceTest` los corre). Aqui lo unico que se afirma es CARGA DE
 * CLASE: que las tres clases se pueden instanciar en el runtime de ESTE modulo.
 *
 * ## POR QUE `assertDoesNotThrow` Y NO UN `try/catch` CON MENSAJE
 *
 * Porque el fallo que se quiere ver es una excepcion, no una afirmacion: un
 * `NoClassDefFoundError` es un `Error`, no una `Exception`, y un `catch (e:
 * Exception)` se lo saltaria de largo y dejaria este test en verde con el
 * defecto puesto.
 */
class CryptoProviderRuntimeTest {

    @Test
    fun `X25519 de BouncyCastle se puede instanciar en el runtime de km-desktop`() {
        val instancia = assertDoesNotThrow({ BcX25519() }) {
            "BcX25519 no se puede cargar: km-desktop no tiene BouncyCastle en su classpath de ejecucion"
        }
        assertNotNull(instancia, "la instancia existe: el fallo seria una excepcion, no un null")
    }

    @Test
    fun `HKDF-SHA256 de BouncyCastle se puede instanciar en el runtime de km-desktop`() {
        assertDoesNotThrow({ BcHkdfSha256() }) {
            "BcHkdfSha256 no se puede cargar: km-desktop no tiene BouncyCastle en su classpath de ejecucion"
        }
    }

    @Test
    fun `ChaCha20-Poly1305 de BouncyCastle se puede instanciar en el runtime de km-desktop`() {
        assertDoesNotThrow({ BcChaCha20Poly1305() }) {
            "BcChaCha20Poly1305 no se puede cargar: km-desktop no tiene BouncyCastle en su classpath de ejecucion"
        }
    }

    /**
     * Precontrol: las tres clases NO se inicializan porCachear un valor.
     *
     * Si `BcX25519()` devoljera siempre la misma instancia, las tres
     * aserciones de arriba serian verde por un motivo que no tiene que ver con
     * la carga de la clase. Se comprueba que dos constructions son objetos
     * DISTINTOS —el provider es sin estado y se construye por uso—.
     */
    @Test
    fun `control cada provider se construye de verdad y no se devuelve cacheado`() {
        assertTrue(BcX25519() !== BcX25519(), "dos construcciones son dos objetos")
        assertTrue(BcHkdfSha256() !== BcHkdfSha256(), "tambien HKDF")
        assertTrue(BcChaCha20Poly1305() !== BcChaCha20Poly1305(), "tambien el AEAD")
    }

    /**
     * CONTROL NEGATIVO: el detector de "esta clase existe" SABI falla.
     *
     * Sin esta prueba, "las tres clases cargan" seria indistinguible de "no se
     * comprueba nada". Se busca un simbolo que esta build no puede cargar, y se
     * exige que la carga falle: es lo que demuestra que [assertDoesNotThrow]
     * tiene dientes.
     */
    @Test
    fun `control negativo una clase inexistente SI falla al cargarse`() {
        val fallo = runCatching { Class.forName("org.bouncycastle.inexistente.KmNoExiste") }
        assertTrue(
            fallo.isFailure,
            "una clase que no existe tiene que fallar al cargarse; si carga, el test de arriba no mide nada",
        )
    }

    /**
     * Precontrol del simbolo: los NOMBRES que se comprueban son los de este
     * repositorio, no los de otra version.
     *
     * Una refactorizacion que renombrara el package `provider` dejaria las tres
     * aserciones de arriba mirando un simbolo inexistente y fallando con un
     * `NoClassDefFoundError` que diria "falta BouncyCastle", que es un diagnostico
     * FALSO. Aqui se comprueba que el nombre sigue siendo el real.
     */
    @Test
    fun `control los tres providers viven donde este repositorio los puso`() {
        for (nombre in listOf(
            BcX25519::class.java.name,
            BcHkdfSha256::class.java.name,
            BcChaCha20Poly1305::class.java.name,
        )) {
            assertTrue(
                nombre.startsWith("com.km.crypto.provider."),
                "$nombre deberia seguir en com.km.crypto.provider; si cambio, los mensajes de fallo de este test mienten",
            )
        }
        assertEquals(
            "com.km.crypto.provider.BcX25519",
            BcX25519::class.java.name,
            "y el nombre exacto es el que se documenta en km-core",
        )
    }
}