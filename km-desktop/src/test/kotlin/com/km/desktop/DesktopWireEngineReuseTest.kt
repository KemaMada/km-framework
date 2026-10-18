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

    /**
     * 1.2 — NINGUNA clase del escritorio monta un `PeerTransportManager`, y
     * NINGUNA nombra una copia local del motor.
     *
     * La p5 compone la pila en `RelayStackFactory`, que llama a
     * `RelayClientStack.compose`. Ese `compose` RECHAZA un manager cuyo backend
     * no sea el motor, de modo que la divergencia entre "el motor por el que
     * salen los bytes" y "el motor que sostiene la conexion" ya no puede ocurrir
     * por la puerta de composicion. Estas guardias cierran la otra: que el
     * escritorio no tenga un segundo sitio de montaje al lado del motor.
     *
     * No es un grep sobre el codigo fuente: lee el BYTECODE que el build produjo
     * para este paquete. Un grep falla por la redaccion de un comentario; esto
     * falla si el escritorio CONSTRUYE el manager o nombra una copia.
     */
    @Test
    fun `1-2 ninguna clase del escritorio CONSTRUYE un PeerTransportManager`() {
        // La composicion ocurre en UN sitio declarado: `RelayStackFactory`, cuyo
        // `managerFactory` por defecto construye el manager. Ese fichero es la
        // excepcion y solo el.
        //
        // EL SENA_ QUE SE BUSCA NO ES EL NOMBRE DEL TIPO. `DesktopMainKt` lo
        // menciona legitimamente: le PASA `stack.manager` a `CoreFactory.create`,
        // y eso deja el nombre en el pool de constantes como PARAMETRO de una
        // firma. Un guardia que buscara el nombre marcaria a proposito el sitio
        // correcto y dejaria de medir nada.
        //
        // Lo que distingue CONSTRUIR de reenviar es el ARGUMENTO: el
        // constructor de `PeerTransportManager` pide un `TransportBackend`, y un
        // manager no se puede montar sin nombrarlo. Por eso el simbolo que se
        // busca es `com/km/node/TransportBackend`, y el UNICO fichero que puede
        // contenerlo es el que compone la pila.
        for (clase in CLASES_DEL_ESCRITORIO) {
            if (clase.startsWith("net/RelayStackFactory")) continue
            assertFalse(
                contiene("com/km/node/TransportBackend", bytecodeDe(clase)),
                "$clase nombra TransportBackend, luego puede CONSTRUIR un PeerTransportManager: " +
                    "la composicion vive solo en RelayStackFactory",
            )
        }
    }

    @Test
    fun `1-2 ninguna clase del escritorio nombra una copia local del motor`() {
        for (clase in CLASES_DEL_ESCRITORIO) {
            val bytecode = bytecodeDe(clase)
            for (prohibido in listOf(
                "com/km/desktop/JvmRelayTransport",
                "com/km/desktop/RelayDataCodec",
                "com/km/desktop/RelayControlCodec",
            )) {
                assertFalse(
                    contiene(prohibido, bytecode),
                    "$clase nombra $prohibido: el motor vive en com.km.transport y aqui no hay copia",
                )
            }
        }
    }

    /**
     * Y donde se monta, se monta SOBRE el motor del modulo hermano.
     *
     * Las dos guardias anteriores pasarian igual si `RelayStackFactory`
     * compusiera con otra cosa. Esta comprueba que el camino existe: la pila se
     * compone con `RelayClientStack` de `:km-transport`, sobre el motor del
     * modulo hermano.
     */
    @Test
    fun `1-2 la pila se compone sobre el motor del modulo hermano`() {
        val bytecode = bytecodeDe("net/RelayStackFactory")
        assertTrue(
            contiene("com/km/transport/RelayClientStack", bytecode),
            "RelayStackFactory debe componer con RelayClientStack de :km-transport",
        )
        assertTrue(
            contiene("com/km/transport/JvmRelayTransport", bytecode),
            "y sobre el motor del modulo hermano, no sobre una copia local",
        )
    }

    /**
     * Control positivo de 1.2: el detector de "monta un PeerTransportManager" SI
     * encuentra el simbolo.
     *
     * Sin este, "ninguna clase lo monta" seria indistinguible de "el escaner no
     * busca". Se mira el propio `RelayStackFactory` —que SI lo monta, y es el
     * unico que debe— y un buffer sintetizado con el mismo nombre interno.
     */
    @Test
    fun `1-2 control positivo el escaner SI ve un PeerTransportManager`() {
        val sintetizado = (
            "java/lang/Object <init> (Lcom/km/node/TransportBackend;)V" +
                "com/km/node/PeerTransportManager com/km/node/TransportBackend"
            ).toByteArray(Charsets.ISO_8859_1)
        assertTrue(
            contiene("com/km/node/PeerTransportManager", sintetizado),
            "el escaner debe ver el simbolo en un buffer sintetizado",
        )
        assertTrue(
            contiene("com/km/node/TransportBackend", sintetizado),
            "y el del argumento del constructor, que es el que se busca de verdad",
        )
        assertTrue(
            contiene("com/km/node/TransportBackend", bytecodeDe("net/RelayStackFactory")),
            "el UNICO sitio autorizado tiene el argumento del constructor: " +
                "sin el, la composicion no tendria de donde",
        )
    }

    /**
     * Las clases de `com.km.desktop` que el build produjo y estas guardias miran.
     *
     * Se nombran de forma EXPLICITA y no se recorre el directorio: una lista que
     * se leyera sola dejaria pasar —en verde— cualquier clase nueva que nadie
     * anadio, que es como una guardia se vuelve decorativa.
     */
    private companion object {
        val CLASES_DEL_ESCRITORIO: List<String> = listOf(
            "DesktopMainKt",
            "PeerKeyDirectory",
            "IdentityKeystore",
            "Ansi",
            "core/CoreFactory",
            "net/RelayStackFactory",
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
