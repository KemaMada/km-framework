package com.km.desktop

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 4.3 — CERO IPs incrustadas y CERO hosts literales en los URIs del escritorio.
 *
 * ## QUE PINZA
 *
 * Ninguna clase que el build produjo para `com.km.desktop` contiene una IP
 * literal ni un `ws://` / `wss://` / `stun:` / `turn:` con HOST LITERAL.
 *
 * ## POR QUE EL BYTECODE Y NO EL CODIGO FUENTE
 *
 * Porque un grep sobre el fuente falla por la REDACCION de un comentario: basta
 * con que alguien escriba "no uses ws://13.140.155.230 aqui" para que la
 * comprobacion se ponga roja, y a la tercera vez nadie la mira y la borra. Un
 * invariante que se puede desactivar escribiendo un comentario no es un
 * invariante.
 *
 * El pool de constantes no tiene ese problema: un comentario no llega al
 * bytecode, y una IP si. Y si el binario la tiene, es porque el binario la usa.
 *
 * ## POR QUE EL `wss://keymessage.xyz` DEL MOTOR NO CUENTA COMO DEFECTO
 *
 * Porque un NOMBRE de host es la configuracion por defecto que el operador puede
 * cambiar con `--bootstrap`. Una IP no se puede cambiar sin tocar el binario, y
 * un binario con la IP de un servidor metida dentro no se puede desplegar en otro
 * sitio.
 *
 * ## EL CONTROL NEGATIVO ES LO QUE LE DA DENTRO
 *
 * Sin el, "no hay IPs" seria indistinguible de "el detector no busca". El
 * control mira un buffer SINTETIZADO —construido aqui, en el test— y exige que
 * los dos detectores lo marquen. Un detector que nunca marca nada tambien daria
 * verde en la guardia principal.
 */
class NoHardcodedEndpointTest {

    /**
     * Las clases de PRODUCCION que el build produjo para este paquete.
     *
     * Se toma el `codeSource` de `DesktopMainKt` —una clase del MAIN— y no el de
     * esta clase de test, que resolveria al directorio de tests. La diferencia
     * no es academica: los tests de este repositorio usan `ws://127.0.0.1` y
     * `13.140.155.230` como MARCAS en sus propias cadenas, y escanearlos haria
     * que la guardia se pusiera roja con el defecto puesto.
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

    @Test
    fun `4-3 cero IPs literales en el escritorio`() {
        val culpables = clasesDeEscritorio()
            .flatMap { clase -> constantesDe(clase.readBytes()).map { clase.name to it } }
            .filter { (_, cadena) -> IPV4.containsMatchIn(cadena) }

        assertTrue(
            culpables.isEmpty(),
            "hay IPs literales en el escritorio, y eso hace el destino inmutable sin tocar el binario:\n" +
                culpables.joinToString("\n") { "  ${it.first}: ${it.second}" },
        )
    }

    @Test
    fun `4-3 cero URIs de red con HOST LITERAL en el escritorio`() {
        val culpables = clasesDeEscritorio()
            .flatMap { clase -> constantesDe(clase.readBytes()).map { clase.name to it } }
            .filter { (_, cadena) -> URI_CON_HOST.containsMatchIn(cadena) }

        assertTrue(
            culpables.isEmpty(),
            "hay ws/stun/turn con un host literal; el destino debe venir de --bootstrap o de KM_BOOTSTRAPS:\n" +
                culpables.joinToString("\n") { "  ${it.first}: ${it.second}" },
        )
    }

    /**
     * El default del MOTOR no esta DECLARADO en el escritorio, se LEE de ahi.
     *
     * Si el escritorio declarara su propio `DEFAULT_ENDPOINT`, un cambio de
     * destino en `:km-transport` dejaria al escritorio apuntando al viejo sin que
     * nadie lo notase. Que el valor aparezca como literal INLINADO en el
     * bytecode —que es lo que Kotlin hace con un `const val`— es inevitable y no
     * es el defecto: lo que se prohibe es la DECLARACION propia.
     */
    @Test
    fun `4-3 el escritorio no DECLARA su propio DEFAULT_ENDPOINT`() {
        val declaradas = clasesDeEscritorio().filter { clase ->
            constantesDe(clase.readBytes()).any { it == "DEFAULT_ENDPOINT" }
        }

        assertTrue(
            declaradas.isEmpty(),
            "ninguna clase del escritorio debe declarar su propio DEFAULT_ENDPOINT; se lee del motor: " +
                declaradas.joinToString { it.name },
        )
    }

    @Test
    fun `4-3 el default del motor es un NOMBRE de host, no una IP`() {
        val porDefecto = com.km.transport.JvmRelayTransport.DEFAULT_ENDPOINT

        assertFalse(
            IPV4.containsMatchIn(porDefecto),
            "el default del motor es un nombre de host, no una IP: $porDefecto",
        )
        assertTrue(
            Regex("""^(ws|wss)://[A-Za-z0-9][A-Za-z0-9.\-]*""").containsMatchIn(porDefecto),
            "y es un endpoint de red con NOMBRE, que es lo que el --bootstrap va a sustituir: $porDefecto",
        )
    }

    /**
     * CONTROL NEGATIVO: los detectores SI marcan un ejemplo sintetizado.
     *
     * El buffer lo CONSTRUYE este test, no el build. Si el detector no lo ve, la
     * guardia principal no mide nada y su verde no vale nada.
     */
    @Test
    fun `control negativo el detector SI marca una muestra sintetizada`() {
        val texto = "ws://13.140.155.230:3001/ws"

        assertTrue(
            IPV4.containsMatchIn(texto),
            "el detector de IPs debe ver la muestra: si no la ve, la guardia principal no mide nada",
        )
        assertTrue(
            URI_CON_HOST.containsMatchIn(texto),
            "y el de URIs con host tambien",
        )
        val enElPool = constantesDe(sintetizaClase(texto))
        assertTrue(
            enElPool.any { it == texto },
            "y el LECTOR DE CONSTANTES debe devolverla: $enElPool",
        )
    }

    /**
     * CONTROL NEGATIVO del LECTOR: no inventa constantes donde no hay.
     *
     * Sin esto, "no hay IPs en el pool de constantes" seria verdad tambien con un
     * lector que devolviera siempre lista vacia.
     */
    @Test
    fun `control negativo el lector de constantes no inventa entradas`() {
        val conUna = constantesDe(sintetizaClase("wss://198.51.100.7:9000/x"))
        assertEquals(listOf("wss://198.51.100.7:9000/x"), conUna, "una entrada, esa")

        val sinUtf8 = constantesDe(sintetizaClaseConInteger())
        assertTrue(
            sinUtf8.none { IPV4.containsMatchIn(it) },
            "un pool sin entradas UTF8 no puede contener IPs: $sinUtf8",
        )
    }

    /** CONTROL POSITIVO del UMBRAL: el rango IP completo se ve. */
    @Test
    fun `control positivo el detector ve el rango IP completo`() {
        for (ip in listOf("0.0.0.0", "127.0.0.1", "10.0.0.1", "192.168.1.1", "255.255.255.255")) {
            assertTrue(
                IPV4.containsMatchIn("host=$ip"),
                "el detector debe ver $ip: si no, el rango que cubre es menor del que se cree",
            )
        }
    }

    /** CONTROL POSITIVO de los esquemas: los cinco se detectan, no solo `ws://`. */
    @Test
    fun `control positivo el detector cubre los seis esquemas`() {
        for (esquema in listOf("ws", "wss", "stun", "stuns", "turn", "turns")) {
            assertTrue(
                URI_CON_HOST.containsMatchIn("$esquema://13.140.155.230:3001/x"),
                "el detector debe ver el esquema $esquema",
            )
        }
    }

    /**
     * CONTROL NEGATIVO: un NOMBRE de host NO es un host literal.
     *
     * Es el otro lado del mismo detector. Si marcara los nombres, la guardia
     * prohibiria el default del motor —que tiene que existir— y acabaria
     * borrandose ella sola.
     */
    @Test
    fun `control negativo un NOMBRE de host no se marca`() {
        for (nombre in listOf("keymessage.xyz", "relay.ejemplo.invalid", "localhost")) {
            assertFalse(
                URI_CON_HOST.containsMatchIn("wss://$nombre/km"),
                "$nombre es un nombre configurable, no un host literal: marcarlo haria imposible el default",
            )
        }
        assertFalse(
            URI_CON_HOST.containsMatchIn("https://keymessage.xyz/km"),
            "y `https` no es un esquema de este cliente",
        )
    }

    /**
     * Una clase minima y VALIDA de Java, con una constante UTF8 que contiene la
     * muestra sintetizada.
     *
     * Se construye byte a byte porque el test tiene que poder sembrar lo que
     * quiera en un pool de constantes: leer el pool de una clase REAL dejaria el
     * control condicionado a lo que el build decidiera generar.
     */
    private fun sintetizaClase(texto: String): ByteArray {
        val utf8 = texto.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()
        fun u1(v: Int) = out.write(v and 0xFF)
        fun u2(v: Int) {
            u1(v shr 8)
            u1(v)
        }
        u4(out, 0xCAFEBABE.toInt()) // magic
        u2(0) // minor
        u2(61) // major (Java 17)
        u2(3) // constant_pool_count: #1 = Utf8, #2 = Class
        u1(1) // tag Utf8
        u2(utf8.size)
        out.write(utf8)
        u1(7) // tag Class
        u2(1) // name_index -> #1
        u2(0x0021) // access_flags: public super
        u2(0) // this_class -> index 0 (deja fuera de rango a proposito: el lector
        // solo recorre el pool, que es lo que se esta probando)
        u2(0) // super_class
        u2(0) // interfaces_count
        u2(0) // fields_count
        u2(0) // methods_count
        u2(0) // attributes_count
        return out.toByteArray()
    }

    /** Igual que [sintetizaClase], pero sin ninguna entrada UTF8. */
    private fun sintetizaClaseConInteger(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u1(v: Int) = out.write(v and 0xFF)
        fun u2(v: Int) {
            u1(v shr 8)
            u1(v)
        }
        u4(out, 0xCAFEBABE.toInt())
        u2(0)
        u2(61)
        u2(2) // solo #1 = Integer
        u1(3) // tag Integer
        u4(out, 42)
        u2(0x0021)
        u2(0)
        u2(0)
        u2(0)
        u2(0)
        u2(0)
        u2(0)
        return out.toByteArray()
    }

    /** Escribe un entero de 4 bytes en big-endian, como el formato de clase. */
    private fun u4(out: java.io.ByteArrayOutputStream, v: Int) {
        out.write((v shr 24) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    /**
     * Las entradas `CONSTANT_Utf8` del pool de constantes de un `.class`.
     *
     * Se recorre el pool EN TABLA, no buscando el byte de marca: una busqueda de
     * byte daria falsos positivos dentro de los payloads de otros tipos de
     * entrada, y un lector que inventa entradas haria verde una guardia que no
     * mira nada.
     */
    private fun constantesDe(bytes: ByteArray): List<String> {
        val cursor = Cursor(bytes)
        return try {
            cursor.skip(4) // magic
            cursor.skip(2) // minor
            cursor.skip(2) // major
            val cuenta = cursor.u2()
            val utf8 = mutableListOf<String>()
            var i = 1
            while (i < cuenta) {
                when (val tag = cursor.u1()) {
                    1 -> utf8 += cursor.texto(cursor.u2())
                    3, 4 -> cursor.skip(4)
                    5, 6 -> {
                        cursor.skip(8)
                        i++ // los largos ocupan dos huecos del pool
                    }
                    7, 8, 16, 19, 20 -> cursor.skip(2)
                    15 -> cursor.skip(3)
                    9, 10, 11, 12, 17, 18 -> cursor.skip(4)
                    else -> throw IllegalStateException("tag de constante desconocido: $tag en $i")
                }
                i++
            }
            utf8
        } catch (e: IndexOutOfBoundsException) {
            // Un `.class` truncado o con un pool que este test no entiende NO es
            // una ausencia de IPs: es una excepcion, y se propaga. Se atrapa
            // solo para que el mensaje diga de donde viene.
            throw IllegalStateException("no se pudo leer el pool de constantes", e)
        }
    }

    /** Cursor sobre los bytes de un `.class`, en ISO-8859-1 (1 byte = 1 char). */
    private class Cursor(private val bytes: ByteArray) {
        private var i = 0

        fun u1(): Int = (bytes[i++].toInt() and 0xFF)

        fun u2(): Int = (u1() shl 8) or u1()

        fun skip(n: Int) {
            i += n
            if (i > bytes.size) throw IndexOutOfBoundsException("se pidio mas de lo que hay")
        }

        /** Los bytes modificados se leen como ISO-8859-1, que es 1:1. */
        fun texto(n: Int): String {
            if (i + n > bytes.size) throw IndexOutOfBoundsException("cadena truncada")
            return String(bytes, i, n, Charsets.ISO_8859_1).also { i += n }
        }
    }

    private companion object {
        /** Una IP literal v4: cuatro octetos separados por puntos. */
        val IPV4 = Regex("""(?<![\d.])\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(?![\d.])""")

        /**
         * Un esquema de red cuyo HOST es un LITERAL DE IP.
         *
         * ## POR QUE UN NOMBRE DE HOST NO CUENTA
         *
         * "Host literal" en el sentido de esta guardia es lo que RFC 3986 llama
         * IP-literal: una direccion. Un NOMBRE —`keymessage.xyz`— es lo
         * contrario de una IP incrustada: es el default LEGIBLE, el que el
         * operador sustituye con `--bootstrap` o con `KM_BOOTSTRAPS`, y el que
         * este repositorio escribe a proposito para que el cliente funcione sin
         * configurar nada.
         *
         * Por eso el patron exige un OCTETO tras el `://`. Y cubre tambien el
         * literal IPv6 entre corchetes, que es la otra forma de host literal.
         */
        val URI_CON_HOST = Regex(
            """(ws|wss|stun|stuns|turn|turns)://(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}|\[[0-9A-Fa-f:]+])""",
            RegexOption.IGNORE_CASE,
        )
    }
}

/** `assertEquals` con el nombre del caso, para no repetir el import por test. */
private fun assertEquals(esperado: Any?, real: Any?, mensaje: String) =
    org.junit.jupiter.api.Assertions.assertEquals(esperado, real, mensaje)