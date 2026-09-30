package com.km.identity

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * KM Canonical Encoding (KCE)
 *
 * Perfil determinista de CBOR basado en RFC 8949 seccion 4.2.1 mas las
 * restricciones normativas propias de KM-ID-0001.
 *
 * KCE no es "usar un encoder CBOR": es una funcion de protocolo cuyo
 * resultado byte-a-byte forma parte de la semantica criptografica. Un
 * documento firmado que no cumpla KCE debe rechazarse aunque sea CBOR
 * valido.
 *
 * Este es un port DIRECTO de km-id-reference/kce.py. No se permite que las
 * dos implementaciones diverjan: los vectores congelados lo detectan.
 */
object Kce {

    // --- Limites de encoding (nivel KCE, no nivel de esquema) -------------

    // Los limites viven en KmIdConstants, NO aqui. Duplicarlos fue
    // precisamente lo que permitio que una transcritura errónea pasara
    // inadvertida: dos copias divergen sin que nada lascompare.
    const val MAX_DOCUMENT_BYTES = KmIdConstants.KCE_MAX_DOCUMENT_BYTES
    const val MAX_TEXT_BYTES = KmIdConstants.KCE_MAX_TEXT_BYTES
    const val MAX_MAP_ITEMS = KmIdConstants.KCE_MAX_MAP_ITEMS
    const val MAX_ARRAY_ITEMS = KmIdConstants.KCE_MAX_ARRAY_ITEMS
    const val MAX_DEPTH = KmIdConstants.KCE_MAX_DEPTH

    /**
     * Limite de anidamiento que el lector de version esta autorizado a
     * recorrer al saltar valores. Deliberadamente mas bajo que MAX_DEPTH: el
     * lector acotado no debe poder recorrer en profundidad.
     */
    const val FRAMER_MAX_DEPTH = KmIdConstants.KCE_FRAMER_MAX_DEPTH

    // --- Major types ------------------------------------------------------

    const val MT_UINT = 0
    const val MT_NINT = 1
    const val MT_BSTR = 2
    const val MT_TSTR = 3
    const val MT_ARRAY = 4
    const val MT_MAP = 5
    const val MT_TAG = 6
    const val MT_SIMPLE = 7

    const val INDEFINITE = 31

    /**
     * Para major type 7, los AI 25..27 son las tres anchuras de float de
     * RFC 8949. El AI 24 NO es un float: sigue siendo un valor simple de
     * 1 byte, igual que en los demas major types.
     */
    val FLOAT_AIS = setOf(25, 26, 27)
    val FLOAT_WIDTHS = mapOf(
        25 to "half precision",
        26 to "precision simple",
        27 to "precision doble",
    )

    // =======================================================================
    // Codificacion
    // =======================================================================

    /**
     * Codifica una cabecera CBOR con serializacion preferida (RFC 8949 4.1).
     */
    fun head(major: Int, arg: Long): ByteArray {
        if (arg < 0) {
            throw KceException("NEGATIVE_INTEGER", "los enteros negativos estan prohibidos por KCE")
        }
        return when {
            arg < 24 -> byteArrayOf(((major.toLong() shl 5) or arg).toByte())
            arg <= 0xFF -> byteArrayOf(((major.toLong() shl 5) or 24).toByte(), arg.toByte())
            arg <= 0xFFFF -> {
                val b = ByteBuffer.allocate(3)
                b.put(((major.toLong() shl 5) or 25).toByte())
                b.putShort(arg.toShort())
                b.array()
            }
            arg <= 0xFFFFFFFFL -> {
                val b = ByteBuffer.allocate(5)
                b.put(((major.toLong() shl 5) or 26).toByte())
                b.putInt(arg.toInt())
                b.array()
            }
            else -> {
                val b = ByteBuffer.allocate(9)
                b.put(((major.toLong() shl 5) or 27).toByte())
                b.putLong(arg)
                b.array()
            }
        }
    }

    /** Codifica un valor KM a bytes KCE. */
    fun encode(value: Any?): ByteArray {
        val out = encodeValue(value, 0)
        if (out.size > MAX_DOCUMENT_BYTES) {
            throw KceException("DOCUMENT_TOO_LARGE", "${out.size} > $MAX_DOCUMENT_BYTES")
        }
        return out
    }

    private fun encodeValue(value: Any?, depth: Int): ByteArray {
        if (depth > MAX_DEPTH) throw KceException("MAX_DEPTH_EXCEEDED", "profundidad $depth")

        when (value) {
            // Boolean ANTES que los numeros: en Kotlin Boolean no es Number,
            // pero el orden explicito evita que alguien anada un Int y lo
            // codifique como entero.
            is Boolean -> return byteArrayOf(
                ((MT_SIMPLE.toLong() shl 5) or (if (value) 20 else 21)).toByte()
            )

            is Int -> return encodeNumber(value.toLong())
            is Long -> return encodeNumber(value)
            is Short -> return encodeNumber(value.toLong())
            is Byte -> return encodeNumber(value.toLong())

            is ByteArray -> {
                if (value.size > MAX_TEXT_BYTES) {
                    throw KceException("TEXT_TOO_LONG", "${value.size} > $MAX_TEXT_BYTES")
                }
                return head(MT_BSTR, value.size.toLong()) + value
            }

            is String -> {
                val raw = encodeUtf8Strict(value)
                if (raw.size > MAX_TEXT_BYTES) {
                    throw KceException("TEXT_TOO_LONG", "${raw.size} > $MAX_TEXT_BYTES")
                }
                if (java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC) != value) {
                    throw KceException("TEXT_NOT_NFC", "el texto debe estar en NFC antes de codificar")
                }
                return head(MT_TSTR, raw.size.toLong()) + raw
            }

            is List<*> -> {
                if (value.size > MAX_ARRAY_ITEMS) {
                    throw KceException("ARRAY_TOO_LONG", "${value.size} > $MAX_ARRAY_ITEMS")
                }
                var out = head(MT_ARRAY, value.size.toLong())
                for (v in value) out += encodeValue(v, depth + 1)
                return out
            }

            is Map<*, *> -> return encodeMap(value, depth)

            // null, Double, Float y cualquier otra cosa se rechazan aqui. Un
            // Double que llegase al encoder produciria bytes que ninguna otra
            // implementacion podria reproducir, y en una ruta de firma eso es
            // corrupcion silenciosa.
            else -> throw KceException("UNSUPPORTED_TYPE", typeName(value))
        }
    }

    private fun encodeNumber(v: Long) = head(MT_UINT, v)

    /**
     * Pares (clave_codificada, valor) de un mapa, en el orden canonico KCE.
     *
     * ORDEN POR BYTES CODIFICADOS, NO ALFABETICO. Esta es la propiedad que
     * mas errores produce al implementar KCE.
     *
     * RFC 8949 4.2.1 manda ordenar por el orden lexicografico bytewise de las
     * CODIFICACIONES DETERMINISTAS de las claves, y la codificacion de una
     * clave tstr corta empieza por su PREFIXIO DE LONGITUD. Por tanto el
     * prefijo domina al contenido y, en la practica, las claves se ordenan
     * primero por LONGITUD y despues alfabeticamente entre las de igual
     * longitud.
     *
     * Con las claves de un DeviceRoster el orden real es:
     *
     *     doc (3)  devices (7)  version (7)  previous (8)  sequence (8)
     *     createdAt (9)  signature (9)  identityId (10)  identityRoot (12)
     *
     * Ordenar alfabeticamente el texto de las claves produce un documento
     * DISTINTO e incompatible.
     *
     * Consecuencia practica: no se puede alterar el ultimo byte de un
     * documento suponiendo que pertenece a la firma; el ultimo campo del
     * mapa es el de la clave mas larga.
     *
     * La comparacion de ByteArray en Kotlin es por REFERENCIA, no por
     * contenido. Ordenar con `sortedBy { it.first }` seria un bug silencioso
     * que colapsaria todas las claves al mismo valor. Por eso el comparador
     * es lexicografico explicito.
     */
    fun sortedPairs(mapping: Map<*, *>): List<Pair<ByteArray, Any?>> {
        if (mapping.size > MAX_MAP_ITEMS) {
            throw KceException("MAP_TOO_LONG", "${mapping.size} > $MAX_MAP_ITEMS")
        }
        val entries = ArrayList<Pair<ByteArray, Any?>>(mapping.size)
        for (e in mapping) {
            val key = e.key
            if (key !is String) throw KceException("MAP_KEY_NOT_TEXT", typeName(key))
            val raw = encodeUtf8Strict(key)
            if (raw.size > MAX_TEXT_BYTES) {
                throw KceException("TEXT_TOO_LONG", "clave de ${raw.size} bytes")
            }
            if (java.text.Normalizer.normalize(key, java.text.Normalizer.Form.NFC) != key) {
                throw KceException("TEXT_NOT_NFC", "la clave debe estar en NFC")
            }
            entries.add(Pair(head(MT_TSTR, raw.size.toLong()) + raw, e.value))
        }
        // Orden lexicografico bytewise EXPLICITO. Ver la nota sobre equals.
        entries.sortWith { a, b -> compareBytes(a.first, b.first) }
        return entries
    }

    /** Comparacion bytewise con signo, equivalente al orden lexicografico de RFC 8949. */
    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a[i].toInt() and 0xFF
            val y = b[i].toInt() and 0xFF
            if (x != y) return x - y
        }
        return a.size - b.size
    }

    private fun encodeMap(mapping: Map<*, *>, depth: Int): ByteArray {
        val entries = sortedPairs(mapping)
        var out = head(MT_MAP, entries.size.toLong())
        for ((kb, v) in entries) out += kb + encodeValue(v, depth + 1)
        return out
    }

    // =======================================================================
    // Decodificacion
    // =======================================================================

    /** Error de KCE con codigo estable, citable desde los fixtures. */
    class KceException(val code: String, val detail: String = "") :
        Exception(if (detail.isEmpty()) code else "$code: $detail")

    private class Reader(val data: ByteArray) {
        var pos = 0
        val n: Int get() = data.size

        fun take(count: Int): ByteArray {
            if (count < 0 || pos + count > n) throw KceException("TRUNCATED_INPUT")
            val chunk = data.copyOfRange(pos, pos + count)
            pos += count
            return chunk
        }

        fun readHead(): Head {
            if (pos >= n) throw KceException("TRUNCATED_INPUT")
            val initial = data[pos].toInt() and 0xFF
            pos += 1
            val major = initial shr 5
            val ai = initial and 0x1F
            if (ai < 24) return Head(major, ai.toLong(), ai)
            if (ai == 28 || ai == 29 || ai == 30) {
                throw KceException("RESERVED_ADDITIONAL_INFO", ai.toString())
            }
            if (ai == INDEFINITE) {
                throw KceException("INDEFINITE_LENGTH", "las longitudes indefinidas estan prohibidas")
            }
            // El ancho del argumento NO depende del major type: ai 24 siempre
            // son 1 byte, 25 -> 2, 26 -> 4, 27 -> 8. Para major 7 los AI
            // 25..27 son las anchuras de float de RFC 8949; el AI 24 sigue
            // siendo un valor simple de 1 byte.
            val width = when (ai) {
                24 -> 1
                25 -> 2
                26 -> 4
                else -> 8
            }
            val raw = take(width)
            if (ai == 27) {
                // El rango 2^63..2^64-1 es un uint64 legitimo pero no cabe
                // en un Long. Se rechaza de forma EXPLICITA en lugar de
                // dejar que se desbordara en silencio. Ningun campo de
                // KM-ID-0001 alcanza ese rango.
                if (raw[0].toInt() and 0x80 != 0) {
                    throw KceException("UINT_OUT_OF_RANGE", "uint64 por encima de 2^63-1")
                }
                return Head(major, ByteBuffer.wrap(raw).long, ai)
            }
            var v = 0L
            for (b in raw) v = (v shl 8) or (b.toLong() and 0xFF)
            return Head(major, v, ai)
        }

        /** Salta un valor completo con limite de profundidad. */
        fun skip(depth: Int = 0) {
            if (depth > MAX_DEPTH) throw KceException("MAX_DEPTH_EXCEEDED")
            val h = readHead()
            val major = h.major
            val arg = h.arg
            when (major) {
                MT_UINT -> return
                MT_NINT -> throw KceException("NEGATIVE_INTEGER")
                MT_BSTR, MT_TSTR -> {
                    if (arg > MAX_TEXT_BYTES) {
                        throw KceException("TEXT_TOO_LONG", "$arg > $MAX_TEXT_BYTES")
                    }
                    take(arg.toInt())
                    return
                }
                MT_ARRAY -> repeat(arg.toInt()) { skip(depth + 1) }
                MT_MAP -> repeat(arg.toInt()) {
                    skip(depth + 1)
                    skip(depth + 1)
                }
                MT_SIMPLE -> {
                    if (h.ai in FLOAT_AIS) {
                        throw KceException("FLOAT_FORBIDDEN", FLOAT_WIDTHS[h.ai]!!)
                    }
                    throw KceException("FORBIDDEN_MAJOR_TYPE", major.toString())
                }
                else -> throw KceException("FORBIDDEN_MAJOR_TYPE", major.toString())
            }
        }

        fun readValue(depth: Int = 0): Any? {
            if (depth > MAX_DEPTH) throw KceException("MAX_DEPTH_EXCEEDED")
            val h = readHead()
            val major = h.major
            val arg = h.arg
            val ai = h.ai

            when (major) {
                MT_UINT -> {
                    if (arg > KmIdConstants.MAX_UINT64) throw KceException("UINT_OUT_OF_RANGE")
                    return arg
                }
                MT_NINT -> throw KceException("NEGATIVE_INTEGER")
                MT_BSTR -> {
                    if (arg > MAX_TEXT_BYTES) {
                        throw KceException("TEXT_TOO_LONG", "$arg > $MAX_TEXT_BYTES")
                    }
                    return take(arg.toInt())
                }
                MT_TSTR -> {
                    if (arg > MAX_TEXT_BYTES) {
                        throw KceException("TEXT_TOO_LONG", "$arg > $MAX_TEXT_BYTES")
                    }
                    return decodeUtf8Strict(take(arg.toInt()))
                }
                MT_ARRAY -> {
                    if (arg > MAX_ARRAY_ITEMS) {
                        throw KceException("ARRAY_TOO_LONG", "$arg > $MAX_ARRAY_ITEMS")
                    }
                    val out = ArrayList<Any?>(arg.toInt())
                    repeat(arg.toInt()) { out.add(readValue(depth + 1)) }
                    return out
                }
                MT_MAP -> {
                    if (arg > MAX_MAP_ITEMS) {
                        throw KceException("MAP_TOO_LONG", "$arg > $MAX_MAP_ITEMS")
                    }
                    // LinkedHashMap: preserva el orden de lectura del
                    // documento, que NO es el orden canonico de emision.
                    val result = LinkedHashMap<String, Any?>(maxOf(8, arg.toInt()))
                    repeat(arg.toInt()) {
                        val key = readValue(depth + 1)
                        if (key !is String) throw KceException("MAP_KEY_NOT_TEXT", typeName(key))
                        if (result.containsKey(key)) throw KceException("DUPLICATE_MAP_KEY", key)
                        result[key] = readValue(depth + 1)
                    }
                    return result
                }
                // major 6 (tag) y 7 (simple/float) nunca son validos en KCE.
                MT_TAG -> throw KceException("TAGS_FORBIDDEN", "las etiquetas CBOR estan prohibidas")
                MT_SIMPLE -> {
                    // La discriminacion es por AI, NUNCA por arg: tras consumir
                    // el argumento adicional, arg es el payload, no la anchura.
                    if (ai == 20) return false
                    if (ai == 21) return true
                    if (ai == 22) throw KceException("NULL_FORBIDDEN", "null no esta permitido en KCE")
                    if (ai == 23) throw KceException("UNDEFINED_FORBIDDEN", "undefined no esta permitido en KCE")
                    if (ai in FLOAT_AIS) {
                        throw KceException("FLOAT_FORBIDDEN", FLOAT_WIDTHS[ai]!!)
                    }
                    throw KceException("SIMPLE_VALUE_FORBIDDEN", "ai=$ai")
                }
                else -> throw KceException("FORBIDDEN_MAJOR_TYPE", major.toString())
            }
        }
    }

    /** Decodifica bytes KCE. NO comprueba que la entrada sea canonica. */
    fun decode(data: ByteArray): Any? = Reader(data).readValue()

    /**
     * Decodifica y ADEMAS comprueba que la entrada sea la forma canonica
     * unica, re-codificando y comparando byte a byte.
     *
     * Comparar bytes y no comparar estructuras es lo que detecta un mapa
     * cuyas claves estan en el orden correcto por casualidad pero no por
     * regla, o una cabecera no minimal.
     */
    fun validate(data: ByteArray): Any? {
        val value = decode(data)
        val again = encode(value)
        if (!again.contentEquals(data)) throw KceException("KCE_ROUNDTRIP_MISMATCH")
        return value
    }

    // =======================================================================
    // Lector acotado de version
    // =======================================================================

    sealed class VersionResult {
        data class Ok(val version: Long) : VersionResult()
        data class Unsupported(val version: Long) : VersionResult()
        data class Indeterminate(val reason: String) : VersionResult()
    }

    /**
     * Cabecera CBOR leida.
     *
     * [ai] se conserva porque para `major == 7` es el UNICO dato que permite
     * distinguir un float de 25/26/27 bits de un valor simple ordinario: tras
     * consumir el argumento adicional, [arg] contiene el PAYLOAD del float, no
     * su anchura.
     *
     * Sin [ai], un float de doble precision (0xFB + 8 bytes) se reportaria
     * como SIMPLE_VALUE_FORBIDDEN en lugar de FLOAT_FORBIDDEN, y los chequeos
     * de false/true/null serian coincidencias afortunadas en vez de
     * decisiones. Sigue rechazandose en ambos casos, pero el diagnostico
     * equivocado acaba enterrando bugs reales.
     */
    data class Head(val major: Int, val arg: Long, val ai: Int)

    /**
     * Extrae el campo `version` de nivel superior sin parsear el documento.
     *
     * Lector de framing deliberadamente restringido: NO es un segundo parser
     * CBOR general. Solo distingue un map de nivel superior con longitudes
     * definitivas, localiza claves de texto y salta valores dentro de
     * FRAMER_MAX_DEPTH.
     *
     * Este lector NO decide por si solo cuando el documento es invalido. Es
     * UNDETERMINATE, no INVALID: el lector se niega a saltar etiquetas,
     * flotantes y longitudes indefinidas, y decidir aqui enmascararia el
     * diagnostico preciso detras de un error generico. Quien llama debe
     * seguir con la validacion KCE completa cuando el resultado sea
     * Indeterminate.
     */
    /**
     * Clasifica una version leida con certeza.
     *
     * La COMPARACION con [maxVersion] ocurre aqui y no en el llamante, como en
     * la referencia. Si se devolviera siempre `Ok(version)`, un documento de
     * una generacion futura pasaria el despacho de version y se reportaria
     * despues como "la firma no verifica": un diagnostico que senala el
     * sintoma en lugar de la causa, y que ademas abre la puerta a
     * reinterpretar campos de otra version con las reglas de la actual.
     *
     * `Indeterminate` NO se confunde con `Unsupported`: un documento ilegible
     * no es un documento de otra version.
     */
    private fun versionResult(version: Long, maxVersion: Long): VersionResult =
        if (version > maxVersion) VersionResult.Unsupported(version)
        else VersionResult.Ok(version)

    fun peekVersion(data: ByteArray, maxVersion: Long): VersionResult {
        if (data.size > MAX_DOCUMENT_BYTES) {
            return VersionResult.Indeterminate("DOCUMENT_TOO_LARGE")
        }
        val reader = Reader(data)
        return try {
            val (major, count, _) = reader.readHead()
            if (major != MT_MAP) return VersionResult.Indeterminate("INVALID_ENCODING")
            if (count > MAX_MAP_ITEMS) return VersionResult.Indeterminate("INVALID_ENCODING")

            var found: Long? = null
            repeat(count.toInt()) {
                val (kmajor, klen, _) = reader.readHead()
                if (kmajor != MT_TSTR || klen > MAX_TEXT_BYTES) {
                    return VersionResult.Indeterminate("INVALID_ENCODING")
                }
                val key = try {
                    decodeUtf8Strict(reader.take(klen.toInt()))
                } catch (e: KceException) {
                    return VersionResult.Indeterminate("INVALID_ENCODING")
                }

                if (key == "version") {
                    val (vmajor, vlen, _) = reader.readHead()
                    if (vmajor != MT_UINT) return VersionResult.Indeterminate("INVALID_ENCODING")
                    found = vlen
                } else {
                    try {
                        reader.skip(0)
                    } catch (e: KceException) {
                        return VersionResult.Indeterminate(e.code)
                    }
                }
                if (found != null) return versionResult(found, maxVersion)
            }
            if (found != null) versionResult(found, maxVersion)
            else VersionResult.Indeterminate("INVALID_ENCODING")
        } catch (e: KceException) {
            VersionResult.Indeterminate(e.code)
        }
    }

    // =======================================================================
    // Utilidades
    // =======================================================================

    /**
     * UTF-8 ESTRICTO.
     *
     * `String(bytes, Charsets.UTF_8)` de la JVM NO lanza excepcion: sustituye
     * silenciosamente por U+FFFD. Eso convertiria una entrada malformada en
     * un documento con un texto distinto, y en una ruta de firma es
     * corrupcion silenciosa. El decodificador debe REPORTAR.
     */
    private fun decodeUtf8Strict(raw: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(raw)).toString()
        } catch (e: Exception) {
            throw KceException("INVALID_UTF8", "secuencia UTF-8 invalida")
        }
    }

    /** UTF-8 ESTRICTO al codificar: un surrogate suelto debe fallar, no volverse '?'. */
    private fun encodeUtf8Strict(s: String): ByteArray {
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buf: java.nio.ByteBuffer = try {
            encoder.encode(CharBuffer.wrap(s))
        } catch (e: Exception) {
            throw KceException("INVALID_UTF8", "el texto no es codificable en UTF-8")
        }
        val out = ByteArray(buf.remaining())
        buf.get(out)
        return out
    }

    private fun typeName(v: Any?): String =
        if (v == null) "null" else v.javaClass.simpleName
}
