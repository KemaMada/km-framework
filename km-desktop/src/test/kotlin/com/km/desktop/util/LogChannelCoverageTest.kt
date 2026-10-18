package com.km.desktop.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 5.2 — Los ocho canales se EMITEN desde el escritorio, y el sumidero recibe ese
 * token exacto.
 *
 * ## QUE AFIRMA
 *
 * 1. Cada uno de los ocho canales aparece en el BYTECODE que el build produjo
 *    para `com.km.desktop`, es decir: hay codigo que lo emite.
 * 2. Cada canal, cuando se emite, llega al sumidero con SU token.
 *
 * ## POR QUE EL BYTECODE Y NO EL CODIGO FUENTE
 *
 * Porque un canal puede estar DECLARADO en la lista y no emitido por nadie, y
 * eso es un canal muerto: la lista dice que se puede mirar y no hay nada que
 * mirar. Mirar el pool de constantes es mirar lo que el binario puede decir, no
 * lo que alguien quiso escribir.
 *
 * ## POR QUE `CHANNELS` NO BASTA
 *
 * Porque `CHANNELS` es una DECLARACION de intenciones. Si un canal se anade a la
 * lista y nadie lo emite, la etapa de observabilidad abriria el log, buscaria
 * `[X]` y no encontraria nada — que es indistinguible de "no paso nada".
 */
class LogChannelCoverageTest {

    /**
     * Las clases de PRODUCCION que el build produjo para este paquete.
     *
     * Se toma el `codeSource` de `DesktopMainKt` y no el de esta clase de test:
     * los tests usan nombres de canal propios en sus cadenas y escanearlos
     * haria que la guardia se pusiera roja con el defecto puesto.
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
    fun `5-2 los ocho canales se EMITEN desde el escritorio`() {
        val constantes = clasesDeEscritorio()
            .flatMap { clase -> ConstantPool(clase.readBytes()).utf8() }
            .toSet()

        val sinEmisor = DiagLog.CHANNELS.filterNot { canal -> constantes.any { it.contains(canal) } }

        assertTrue(
            sinEmisor.isEmpty(),
            "estos canales estan DECLARADOS pero nadie los emite: abrir el log y buscarlos daria nada.\n" +
                "  sin emisor: $sinEmisor\n" +
                "  emitidos:   ${DiagLog.CHANNELS.filter { c -> c !in sinEmisor }}",
        )
    }

    @Test
    fun `5-2 el sumidero recibe EXACTAMENTE el token de cada canal`() {
        val sumidero = MemoryDiagSink()
        val anterior = DiagLog.active()
        DiagLog.install(sumidero)
        DiagLog.clear()
        try {
            for (canal in DiagLog.CHANNELS) {
                DiagLog.event(canal, "prueba-$canal")
            }
            for (canal in DiagLog.CHANNELS) {
                val recibidas = sumidero.deCanal(canal)
                assertEquals(1, recibidas.size, "el canal $canal se emite y llega UNA vez")
                assertTrue(
                    recibidas.single().contains("prueba-$canal"),
                    "y el mensaje conserva el token: ${recibidas.single()}",
                )
            }
            assertEquals(
                DiagLog.CHANNELS.size,
                sumidero.lines().size,
                "y no hay ningun evento de mas: lo que se emite es lo que se declara",
            )
        } finally {
            DiagLog.clear()
            DiagLog.install(anterior)
        }
    }

    @Test
    fun `5-2 la lista de canales tiene OCHO y son los declarados`() {
        assertEquals(8, DiagLog.CHANNELS.size, "ocho canales, no mas ni menos")
        assertEquals(
            listOf("[BOOTSTRAP]", "[RELAY]", "[AUTH]", "[CONTACT]", "[SESSION]", "[SEND]", "[RECV]", "[INTEGRITY]"),
            DiagLog.CHANNELS,
            "y son exactamente los de la especificacion, en su orden",
        )
    }

    /**
     * CONTROL NEGATIVO: un canal RENOMBRADO se detecta.
     *
     * Es la mutacion que declara este test. Si el escaner de bytecode no
     * distinguiera `[AUTH]` de `[LOGIN]`, el renombrado pasaria y la etapa de
     * observabilidad buscaria un token que ya no existe.
     */
    @Test
    fun `control negativo un canal renombrado NO se detectaria como emitido`() {
        val constantes = clasesDeEscritorio()
            .flatMap { clase -> ConstantPool(clase.readBytes()).utf8() }
            .toSet()

        assertTrue(
            constantes.none { it.contains("[LOGIN]") },
            "control: `[LOGIN]` no existe en el escritorio, y `[AUTH]` si",
        )
        assertTrue(
            constantes.any { it.contains("[AUTH]") },
            "control positivo: `[AUTH]` SI esta, que es lo que un renombrado romperia",
        )
    }

    /**
     * CONTROL NEGATIVO: el LECTOR del pool no devuelve siempre lista vacia.
     *
     * Sin esto, "los ocho canales se emiten" seria verde con un lector roto, que
     * es exactamente el falso verde que esta clase existe para evitar.
     */
    @Test
    fun `control negativo el lector del pool SI lee constantes`() {
        // El buffer tiene que ser una CLASE valida: el lector recorre el pool en
        // tabla, y un byte cualquiera no es un pool. Se construye una aqui.
        val conTexto = ConstantPool(claseMinimaCon("relleno: [AUTH] sesion instalada:fin")).utf8()

        assertTrue(
            conTexto.any { it.contains("[AUTH]") },
            "el lector debe ver la constante del buffer sintetizado: $conTexto",
        )
        assertTrue(
            ConstantPool(claseMinimaCon("")).utf8().none { it.contains("[AUTH]") },
            "y no debe inventar el token donde el buffer no lo tiene",
        )
    }

    /**
     * Una clase minima y VALIDA de Java con UNA constante UTF8.
     *
     * Se construye byte a byte porque el control tiene que poder sembrar lo que
     * quiera en un pool: leer el pool de una clase REAL dejaria el control
     * condicionado a lo que el build decidiera generar.
     */
    private fun claseMinimaCon(texto: String): ByteArray {
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
        u2(0) // this_class (fuera de rango a proposito: solo se lee el pool)
        u2(0) // super_class
        u2(0) // interfaces_count
        u2(0) // fields_count
        u2(0) // methods_count
        u2(0) // attributes_count
        return out.toByteArray()
    }

    private fun u4(out: java.io.ByteArrayOutputStream, v: Int) {
        out.write((v shr 24) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }
}

/**
 * Lector MINIMO del pool de constantes de un `.class`.
 *
 * Se recorre el pool EN TABLA, no buscando el byte de marca: una busqueda de
 * byte daria falsos positivos dentro de los payloads de otros tipos de entrada.
 * Un lector que inventa entradas haria verde una guardia que no mira nada, que
 * es el fallo que el control negativo de arriba vigila.
 */
internal class ConstantPool(private val bytes: ByteArray) {

    fun utf8(): List<String> {
        var i = 0
        fun u1(): Int = (bytes[i++].toInt() and 0xFF)
        fun u2(): Int = (u1() shl 8) or u1()
        fun skip(n: Int) {
            i += n
            if (i > bytes.size) throw IndexOutOfBoundsException("se pidio mas de lo que hay")
        }
        i = 0
        skip(4) // magic
        skip(2) // minor
        skip(2) // major
        val cuenta = u2()
        val salida = mutableListOf<String>()
        var n = 1
        while (n < cuenta) {
            when (val tag = u1()) {
                1 -> {
                    val largo = u2()
                    if (i + largo > bytes.size) throw IndexOutOfBoundsException("cadena truncada")
                    salida += String(bytes, i, largo, Charsets.ISO_8859_1)
                    i += largo
                }
                3, 4 -> skip(4)
                5, 6 -> {
                    skip(8)
                    n++ // los largos ocupan dos huecos del pool
                }
                7, 8, 16, 19, 20 -> skip(2)
                15 -> skip(3)
                9, 10, 11, 12, 17, 18 -> skip(4)
                else -> throw IllegalStateException("tag de constante desconocido: $tag en $n")
            }
            n++
        }
        return salida
    }
}