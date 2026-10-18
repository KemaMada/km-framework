package com.km.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 6.1 — El escritorio es un CLIENTE: no cifra, no genera claves, no tiene motor.
 *
 * ## QUE PINZA
 *
 * En el BYTECODE de produccion de `com.km.desktop`:
 *
 *  - cero `javax.crypto.Cipher` — la criptografia vive en km-core,
 *  - cero `java.security.KeyPairGenerator` — las claves las genera km-core,
 *  - cero clase `JvmRelayTransport` en este paquete — el motor vive en
 *    `:km-transport`.
 *
 * ## POR QUE EL BYTECODE Y NO EL CODIGO FUENTE
 *
 * Porque un grep falla por la REDACCION de un comentario: basta con escribir
 * "no uses javax.crypto.Cipher" para ponerlo rojo, y a la tercera vez nadie lo
 * mira y lo borra. Un invariante que se desactiva escribiendo un comentario no
 * es un invariante. El pool de constantes no tiene ese problema: un comentario no
 * llega al bytecode.
 *
 * ## POR QUE `MessageDigest` NO ESTA PROHIBIDO
 *
 * Porque el escritorio lo usa para una HUELLA que se muestra en pantalla —el
 * `sha256` de la identidad local y el resumen de `identityId`— y eso no es
 * criptografia de protocolo: no protege nada, solo resume. Si se prohibiera
 * `MessageDigest` habria que inventar una huella, y una huella inventada es peor
 * que un resumen honesto. Lo que no puede aparecer es `Cipher` ni
 * `KeyPairGenerator`, que si son capacidad criptografica.
 *
 * ## LOS CONTROLES, Y POR QUE NO SON OPCIONALES
 *
 * Sin el control POSITIVO, "no hay `Cipher`" seria indistinguible de "el escaner
 * no busca". Sin el NEGATIVO, seria indistinguible de "el escaner no mira el
 * paquete correcto". Los dos se compilan y se ejecutan ANTES de dar por buena
 * esta clase.
 */
class DesktopIsAThinClientTest {

    /**
     * Las clases de PRODUCCION que el build produjo para este paquete.
     *
     * Se toma el `codeSource` de `DesktopMainKt` y no el de esta clase de test:
     * escanear tambien los tests haria que la guardia se pusiera roja con el
     * defecto puesto.
     */
    private fun clasesDeEscritorio(): List<File> {
        val main = Class.forName("com.km.desktop.DesktopMainKt")
        val raiz = File(main.protectionDomain.codeSource.location.toURI())
        val paquete = File(raiz, "com/km/desktop")
        check(paquete.isDirectory) {
            "el build no produjo ${paquete.path}: la guardia mediria el disco, no el build"
        }
        return paquete.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.toList()
    }

    /** Busca un simbolo del pool de constantes en los bytes de una clase. */
    private fun contiene(clase: File, simbolo: String): Boolean = contieneEn(clase.readBytes(), simbolo)

    /** El escaner en si, sobre bytes sueltos: lo comparten clase y control. */
    private fun contieneEn(bytes: ByteArray, simbolo: String): Boolean {
        val aguja = simbolo.toByteArray(Charsets.ISO_8859_1)
        if (aguja.isEmpty() || aguja.size > bytes.size) return false
        outer@ for (i in 0..bytes.size - aguja.size) {
            for (j in aguja.indices) {
                if (bytes[i + j] != aguja[j]) continue@outer
            }
            return true
        }
        return false
    }

    /**
     * Simbolos que no pueden aparecer en NINGUNA clase del escritorio.
     *
     * Son capacidad criptografica de PROTOCOLO: generar claves, firmar,
     * autenticar o cifrar mensajes. Todo eso vive en km-core, y un escritorio
     * que lo hiciera seria una segunda implementacion del protocolo.
     */
    private val simbolosProhibidos = listOf(
        "java/security/KeyPairGenerator",
        "java/security/Signature",
        "javax/crypto/Mac",
        "javax/crypto/KeyGenerator",
    )

    /**
     * LA EXCEPCION, y es UNA y se NOMBRA.
     *
     * [IdentityKeystore] cifra el almacen de identidad en reposo con AES-GCM. No
     * es criptografia de protocolo: es la proteccion del material SECRETO del
     * usuario en disco, y km-core no tiene almacen que cifrar porque no guarda
     * claves. El checkpoint de "cifrado en reposo" esta cerrado y vive aqui.
     *
     * Se permite en ESA clase y solo en esa. Prohibirlo obligaria a dejar la
     * identidad del usuario en claro, que es peor que cualquier duplicacion de
     * protocolo. Lo que no se permite es que el permiso se extienda: la guardia
     * de abajo lo comprueba.
     */
    private val cifradoEnReposo = "IdentityKeystore.class"

    /** Simbolo que solo puede aparecer en [cifradoEnReposo]. */
    private val cipher = "javax/crypto/Cipher"

    @Test
    fun `6-1 cero criptografia de PROTOCOLO en el escritorio`() {
        val culpables = clasesDeEscritorio().filter { clase ->
            simbolosProhibidos.any { contiene(clase, it) }
        }.map { clase ->
            "${clase.name} -> ${simbolosProhibidos.filter { contiene(clase, it) }}"
        }

        assertTrue(
            culpables.isEmpty(),
            "el escritorio no genera claves, no firma y no autentica: eso es km-core.\n" +
                culpables.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun `6-1 el Cipher del almacen SOLO puede estar en IdentityKeystore`() {
        val conCipher = clasesDeEscritorio().filter { contiene(it, cipher) }

        assertEquals(
            listOf(cifradoEnReposo),
            conCipher.map { it.name },
            "`Cipher` solo puede usarse para el cifrado EN REPOSO del almacen de identidad " +
                "($cifradoEnReposo). En cualquier otra clase seria criptografia de protocolo en el cliente",
        )
    }

    @Test
    fun `6-1 cero motor de rele propio en este paquete`() {
        val motorLocal = clasesDeEscritorio()
            .map { it.name }
            .filter { it.endsWith("JvmRelayTransport.class") || it.endsWith("RelayDataCodec.class") }

        assertTrue(
            motorLocal.isEmpty(),
            "el motor del rele vive en com.km.transport y aqui no hay copia: $motorLocal",
        )
    }

    @Test
    fun `6-1 el escritorio SI puede resumir con MessageDigest`() {
        // El otro lado de la regla. El escritorio muestra una huella de su
        // identidad, y `MessageDigest` no protege nada: solo resume. Prohibirlo
        // obligaria a inventar una huella, que es peor.
        val conDigest = clasesDeEscritorio().filter { contiene(it, "java/security/MessageDigest") }

        assertTrue(
            conDigest.isNotEmpty(),
            "el escritorio usa MessageDigest para la huella que muestra: no es criptografia de protocolo " +
                "y la regla lo permite a proposito",
        )
    }

    /**
     * CONTROL POSITIVO: el escaner de bytecode SI ve cada simbolo prohibido.
     *
     * Sin esto, "cero criptografia propia" seria verde con un escaner que nunca
     * encuentra nada, que es indistinguible de un escritorio limpio.
     */
    @Test
    fun `control positivo el escaner SI ve cada simbolo prohibido`() {
        for (simbolo in simbolosProhibidos + cipher) {
            val sintetizado = "prefijo:$simbolo:sufijo".toByteArray(Charsets.ISO_8859_1)
            assertTrue(
                contieneEn(sintetizado, simbolo),
                "el escaner debe ver $simbolo en un buffer sintetizado: si no, la guardia no mide nada",
            )
        }
    }

    /**
     * CONTROL NEGATIVO: el escaner NO inventa simbolos que el buffer no tiene.
     *
     * El otro control: sin el, "no hay `Cipher`" seria verdad para un escaner que
     * dice que SI a todo.
     */
    @Test
    fun `control negativo el escaner NO inventa simbolos`() {
        val sintetizado = "prefijo:com/km/transport/JvmRelayTransport:sufijo".toByteArray(Charsets.ISO_8859_1)

        assertTrue(
            contieneEn(sintetizado, "com/km/transport/JvmRelayTransport"),
            "control positivo: el simbolo que SI esta, se ve",
        )
        for (simbolo in simbolosProhibidos + cipher) {
            assertTrue(
                !contieneEn(sintetizado, simbolo),
                "y $simbolo NO esta en el buffer: el escaner no puede inventarlo",
            )
        }
    }

    /** El paquete correcto: el escaner mira `com/km/desktop`, no el de arriba. */
    @Test
    fun `control negativo el escaner mira el paquete de PRODUCCION`() {
        val clases = clasesDeEscritorio()

        assertTrue(
            clases.any { it.name.endsWith("DesktopMainKt.class") },
            "el paquete escaneado contiene el punto de entrada: si no, se estaria midiendo otra cosa",
        )
        assertTrue(
            clases.none { it.name.endsWith("DesktopIsAThinClientTest.class") },
            "y NO contiene las clases de test: los tests usan marcas que aqui serian falsos positivos",
        )
    }
}