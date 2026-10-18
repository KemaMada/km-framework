package com.km.desktop

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * R-8.a — UN solo cliente de wire.
 *
 * ## QUE PINZA
 *
 * El motor del rele (cliente WebSocket + codec del dialecto KM-WIRE-RELAY) vive
 * en `com.km.transport`, y `km-desktop` lo consume POR ARTEFACTO. Hasta p4 habia
 * una copia byte a byte en `com.km.desktop`; dos clientes de wire son dos
 * protocolos, dos `MAX_RESEND` y dos vectores de ratchet, y la copia del
 * escritorio era la que usaba `DesktopMain`.
 *
 * Estas guardias NO son un grep sobre el codigo fuente. Un grep falla por la
 * redaccion de un comentario, no por el comportamiento; lo de aqui lee lo que el
 * BUILD produjo:
 *
 *  1. la EXISTENCIA de las clases en el CLASSPATH de ejecucion, y
 *  2. el POOL DE CONSTANTES del `.class` de `DesktopMainKt`.
 *
 * ## POR QUE UNA GUARDIA DE CLASES Y NO DE FICHERO
 *
 * El verify de p4 cerro R-8.a como FAILING por lectura de arbol. Una guardia de
 * fichero (`File(...).exists()`) tendria el mismo problema que el grep: miraria
 * el disco, no lo que el build resuelve. La clase es lo que el build resuelve.
 *
 * ## CONTROLES
 *
 * `control positivo el detector encuentra la clase de transporte` demuestra que
 * `existe()` no inventa ausencias. `control negativo el escaner de bytecode SI
 * encuentra un simbolo sintetizado` demuestra lo mismo de `contiene()`: sin el,
 * "no aparece en el pool" seria indistinguible de "el escaner no busca".
 */
class DesktopWireEngineReuseTest {

    /** `true` si la clase existe en el classpath de test de `km-desktop`. */
    private fun existe(paquete: String): Boolean = try {
        Class.forName(paquete)
        true
    } catch (e: ClassNotFoundException) {
        false
    }

    @Test
    fun `R-8a no hay un segundo cliente de wire en com-km-desktop`() {
        for (clase in listOf(
            "com.km.desktop.JvmRelayTransport",
            "com.km.desktop.RelayDataCodec",
        )) {
            assertFalse(
                existe(clase),
                "$clase no debe existir: R-8.a exige UN solo cliente de wire, en com.km.transport",
            )
        }
    }

    @Test
    fun `R-8a el punto de entrada del escritorio referencia al motor del modulo hermano`() {
        // NO es un grep sobre el codigo fuente, y NO es una tautologia.
        //
        // Lee los BYTECODE que el build produjo para `DesktopMainKt` —el
        // `mainClass` que declara `application`— y le pregunta al CONSTANT POOL
        // que clase del motor nombra. Antes de p4 ese pool decia
        // `com/km/desktop/JvmRelayTransport`; ahora solo puede decir
        // `com/km/transport/...`, porque la copia ya no existe.
        //
        // La version anterior de este test construia el motor importado y le
        // preguntaba su paquete: eso era verdade por como estaba escrito el
        // propio test, y habria seguido en verde con DesktopMain apuntando a la
        // copia. Aqui lo que se mide es lo que el escritorio COMPILA.
        val bytecode = bytecodeDe("DesktopMainKt")

        assertTrue(
            contiene("com/km/transport/JvmRelayTransport", bytecode),
            "DesktopMainKt debe referenciar el motor del modulo hermano",
        )
        assertFalse(
            contiene("com/km/desktop/JvmRelayTransport", bytecode),
            "DesktopMainKt no puede referenciar una copia local del motor",
        )
    }

    @Test
    fun `control positivo el detector encuentra la clase de transporte`() {
        // Si esto fallara, la guardia de arriba no estaria midiendo nada.
        assertTrue(
            existe("com.km.transport.JvmRelayTransport"),
            "el detector debe encontrar la clase que SI existe",
        )
    }

    @Test
    fun `control negativo el escaner de bytecode SI encuentra un simbolo sintetizado`() {
        // El otro control. Sin el, "no aparece en el pool de constantes" seria
        // indistinguible de "el escaner no busca".
        val sintetizado = "prefijoLlenura:com/km/desktop/JvmRelayTransport:sufijo".toByteArray(Charsets.ISO_8859_1)
        assertTrue(
            contiene("com/km/desktop/JvmRelayTransport", sintetizado),
            "el escaner debe encontrar el simbolo en un buffer sintetizado",
        )
        assertFalse(
            contiene("com/km/desktop/RelayDataCodec", sintetizado),
            "y no debe inventar simbolos que el buffer no tiene",
        )
    }

    /** Los bytes que el BUILD produjo para una clase de `com.km.desktop`. */
    private fun bytecodeDe(nombreSimple: String): ByteArray {
        val recurso = javaClass.getResourceAsStream("/com/km/desktop/$nombreSimple.class")
            ?: throw AssertionError(
                "el build no produjo com/km/desktop/$nombreSimple.class: la guardia mediria el disco, no el build",
            )
        return recurso.use { it.readBytes() }
    }

    /** Busca un simbolo del pool de constantes (un nombre interno) en los bytes. */
    private fun contiene(simbolo: String, bytes: ByteArray): Boolean {
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
}
