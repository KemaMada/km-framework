package com.km.mnemonic

import com.km.crypto.Hash
import com.km.crypto.HashImpl
import java.text.Normalizer
import java.util.Locale

/**
 * Por que una frase no es valida.
 *
 * El motivo viaja en la excepcion para que la UI pueda distinguir "te has
 * equivocado al teclear" de "esta frase no existe" sin leer el texto del error.
 * El texto, en cambio, SI se puede pintar: no lleva palabras.
 */
enum class MnemonicMotivo {
    /** La frase no tiene [Mnemonic.WORD_COUNT] palabras. */
    LONGITUD,

    /** Una posicion no pertenece a las 2048 palabras de BIP39. */
    PALABRA_DESCONOCIDA,

    /** Los 8 bits de checksum no cuadran con la entropia. */
    CHECKSUM,

    /** La entropia no mide [Mnemonic.ENTROPY_BYTES]. */
    ENTROPIA,

    /** La wordlist no esta en el classpath, o no tiene las 2048 palabras. */
    WORDLIST,
}

/**
 * 6.1 — Todo lo que puede fallar al hablar de una frase de recuperacion.
 *
 * ## POR QUE LLEVA MOTIVO E INDICE Y NO SOLO UN MENSAJE
 *
 * El mensaje es para el usuario y no puede llevar las palabras que introdujo
 * —acaba en un `Toast`, en un log de errores o en una captura de pantalla— y el
 * motivo y el indice son para el codigo: "la palabra 7 no existe" y "el checksum
 * no cuadra" son fallos que el usuario arregla de forma distinta, y un unico
 * string obligaria a decidir con `contains`.
 *
 * ## LA PALABRA NO VIAJA EN LA EXCEPCION
 *
 * Es una invariante, no una recomendacion. Un `MnemonicException` que incluyera
 * la frase o la palabra fallida volveria este modulo en el camino mas corto que
 * hay para filtrarla: cualquier `Log.e(tag, e.message)` la escribe.
 */
class MnemonicException(
    /** Que fallo. */
    val motivo: MnemonicMotivo,
    /** Posicion 1-based de la palabra problematica, si la hay. */
    val indice: Int? = null,
    /** Descripcion legible. NUNCA contiene palabras ni la frase. */
    message: String,
) : IllegalArgumentException(message)

/**
 * 6.1 — Una frase de 24 palabras, OPACA.
 *
 * ## POR QUE NO ES UN `data class`
 *
 * Un `data class` genera `toString()` con TODAS las propiedades: bastaria con
 * que un `MnemonicPhrase` acabara en un `Log.i(tag, "$obj")` —o en el mensaje de
 * una excepcion— para que la frase entera estuviera en el log. Redactar el
 * `toString()` no seria entonces una proteccion, sino una linea que alguien
 * borra por el camino. Aqui el `toString()` esta redactado EN SU DEFINICION y no
 * hay ninguna propiedad publica que lo deshaga.
 *
 * ## LO UNICO QUE SACA TEXTO
 *
 * [wordAt] (una palabra, para la rejilla `01..24`) y [display] (la frase entera,
 * para copiarla). No hay mas puertas, y las dos son explicitas.
 *
 * ## POR QUE EL CONSTRUCTOR ES `internal`
 *
 * Una frase solo puede existir pasando por [Mnemonic.fromEntropy] o
 * [Mnemonic.parse], y las dos validan. Con un constructor publico,
 * `MnemonicPhrase(cualquieraDeEstasPalabras)` fabricaria una frase que
 * [Mnemonic.toEntropy] no podria decodificar.
 */
class MnemonicPhrase internal constructor(private val words: List<String>) {

    /** Cuantas palabras tiene. */
    val wordCount: Int get() = words.size

    /**
     * La palabra [i], en base 0.
     *
     * @throws IndexOutOfBoundsException si [i] no es una posicion de la frase.
     */
    fun wordAt(i: Int): String = words[i]

    /** La frase como texto, separada por espacios. */
    fun display(): String = words.joinToString(" ")

    /**
     * REDACTADO a proposito: dice CUANTAS palabras hay, y ninguna mas.
     *
     * El `MnemonicPhrase(wordCount=24, words=[abandon, ...])` que daria un
     * `data class` es justo lo que este metodo impide que exista.
     */
    override fun toString(): String = "MnemonicPhrase($wordCount palabras)"
}

/**
 * 6.1 — La codificacion BIP39, en el framework.
 *
 * ## QUE ES Y QUE NO ES UN BIP39
 *
 * Es la parte de BIP39 que describe la FRASE: la wordlist de 2048 palabras, el
 * troceado en grupos de 11 bits y el checksum de los primeros 8 bits de
 * SHA-256(entropia). NO es la derivacion PBKDF2-HMAC-SHA512 de BIP39, que produce
 * un `seed` de 64 bytes: aqui lo que se transporta es la semilla de 32 bytes del
 * par Ed25519, que RFC 8032 ya define como la clave privada. Por eso la frase
 * son 24 palabras exactas y no 12, y por eso no hay PBKDF2: anadirlo seria una
 * segunda funcion de derivacion que nadie necesita.
 *
 * ## POR QUE VIVE AQUI Y NO EN LA APP
 *
 * La codificacion y su checksum son criptografia: si vivieran en un consumidor,
 * dos consumidores distintos podrian codificar de dos maneras y una frase
 * aceptada por uno seria rechazada por otro. El checksum se apoya en [Hash], la
 * primitiva que ya tiene el framework, y este modulo no anade ninguna
 * dependencia: ni de red, ni `dev.onvoid`, ni `km-webrtc`.
 *
 * ## ESTABILIDAD
 *
 * Misma entropia, misma frase. Sin aleatoriedad, sin estado y sin reloj.
 */
object Mnemonic {

    /** Bytes de entropia. 32 = 256 bits = 24 palabras de 11 bits. */
    const val ENTROPY_BYTES: Int = 32

    /** Palabras de una frase. 24 x 11 = 264 bits = 256 de entropia + 8 de checksum. */
    const val WORD_COUNT: Int = 24

    /** Entradas de la wordlist: 2^11, el numero de indices representables. */
    const val WORDLIST_SIZE: Int = 2048

    /** Bits por palabra. 11, porque la wordlist tiene 2048 = 2^11 entradas. */
    const val BITS_PER_WORD: Int = 11

    /** Bits de checksum: 8 = ENTROPY_BYTES * 8 / WORD_COUNT. */
    const val CHECKSUM_BITS: Int = 8

    /** Ubicacion del recurso dentro del jar de `km-core`. */
    const val RESOURCE: String = "/com/km/mnemonic/english.txt"

    /** SHA-256 del framework. La unica criptografia que usa este modulo. */
    private val hash: Hash = HashImpl()

    /**
     * La wordlist, leida del recurso del jar UNA vez.
     *
     * `com.km:km-core` se consume como dependencia, de modo que el recurso
     * llega TRANSITIVO a cualquier consumidor sin que tenga que anadir nada.
     */
    private val wordlist: List<String> by lazy { cargarWordlistDe(RESOURCE) }

    /** Indice de cada palabra. Se construye sobre [wordlist], sin orden propio. */
    private val wordIndex: Map<String, Int> by lazy {
        wordlist.withIndex().associate { (i, palabra) -> palabra to i }
    }

    /**
     * Codifica [entropy] como frase de [WORD_COUNT] palabras.
     *
     * ## EL BLOQUE QUE SE TROCEA
     *
     * No se trocea la entropia sola, sino la entropia SEGUIDA del primer byte de
     * su SHA-256: los 8 bits de checksum son el final de la frase, no una
     * palabra de mas. 32 + 1 bytes = 264 bits = 24 x 11, exacto.
     *
     * @throws MnemonicException con [MnemonicMotivo.ENTROPIA] si [entropy] no
     *   mide [ENTROPY_BYTES] bytes, y con [MnemonicMotivo.WORDLIST] si el
     *   recurso no esta en el classpath.
     */
    fun fromEntropy(entropy: ByteArray): MnemonicPhrase {
        if (entropy.size != ENTROPY_BYTES) {
            throw MnemonicException(
                motivo = MnemonicMotivo.ENTROPIA,
                message = "la entropia debe medir $ENTROPY_BYTES bytes, mide ${entropy.size}",
            )
        }
        val bloque = ByteArray(ENTROPY_BYTES + 1)
        System.arraycopy(entropy, 0, bloque, 0, ENTROPY_BYTES)
        bloque[ENTROPY_BYTES] = hash.sha256(entropy)[0]

        val indices = indicesFromBits(bitsOf(bloque))
        return MnemonicPhrase(indices.map { wordlist[it] })
    }

    /**
     * Decodifica [phrase] a sus [ENTROPY_BYTES] bytes de entropia.
     *
     * ## POR QUE LA COMPARACION DEL CHECKSUM ESTA AQUI Y NO SOLO EN [parse]
     *
     * Porque [parse] delega en esta funcion y asi lo hace, pero el inverso tambien
     * es un camino publico: una frase obtenida con [fromEntropy] y leda de nuevo
     * tiene que pasar por aqui. Si el checksum se comprobara solo al parsear,
     * `toEntropy` seria un deserializador que acepta basura, y la mutacion que
     * quita la comparacion seria indetectable desde el otro lado.
     *
     * @throws MnemonicException con [MnemonicMotivo.CHECKSUM] si el checksum no
     *   cuadra, con [MnemonicMotivo.PALABRA_DESCONOCIDA] si alguna palabra no
     *   esta en la wordlist y con [MnemonicMotivo.LONGITUD] si la frase no tiene
     *   [WORD_COUNT] palabras. NUNCA devuelve una semilla parcial: o los 32 bytes,
     *   o una excepcion.
     */
    fun toEntropy(phrase: MnemonicPhrase): ByteArray {
        val bloque = bitsToBytes(indicesDe(phrase))
        val entropia = bloque.copyOf(ENTROPY_BYTES)
        val esperado = hash.sha256(entropia)[0]
        val codificado = bloque[ENTROPY_BYTES]
        if (esperado != codificado) {
            throw MnemonicException(
                motivo = MnemonicMotivo.CHECKSUM,
                message = "el checksum no cuadra: no es una frase valida",
            )
        }
        return entropia
    }

    /**
     * Normaliza, decodifica y VALIDA el texto pegado por el usuario.
     *
     * ## LA NORMALIZACION
     *
     * Split por blancos, NFC, minusculas y trim, en ese orden de ideas: lo que
     * llega del portapapeles de un movil es una frase con saltos de linea, a
     * veces con espacios multiples y a veces con las palabras capitalizadas. El
     * chequeo de checksum va AL FINAL, sobre los bytes ya normalizados, que es la
     * unica forma de que "Abandon ..." y "abandon ..." sean la misma frase.
     *
     * ## POR QUE VALIDA TAMBIEN EL CHECKSUM
     *
     * Porque una pantalla que "acepta" una frase y luego falla al escribir es el
     * peor sitio para descubrir que el checksum no cuadra: el usuario cree que
     * ha restaurado y no ha restaurado nada.
     *
     * @throws MnemonicException con [MnemonicMotivo.LONGITUD],
     *   [MnemonicMotivo.PALABRA_DESCONOCIDA] o [MnemonicMotivo.CHECKSUM].
     */
    fun parse(text: String): MnemonicPhrase {
        val palabras = normalizar(text)
        if (palabras.size != WORD_COUNT) {
            throw MnemonicException(
                motivo = MnemonicMotivo.LONGITUD,
                message = "una frase son $WORD_COUNT palabras; hay ${palabras.size}",
            )
        }
        // Las 24 se comprueban ANTES de construir la frase: una frase con una
        // palabra ajena y un checksum que no cuadra debe decir cual de los dos
        // es, y el orden "palabra" -> "checksum" es el que puede arreglar el
        // usuario.
        palabras.forEachIndexed { i, palabra ->
            if (!wordIndex.containsKey(palabra)) {
                throw MnemonicException(
                    motivo = MnemonicMotivo.PALABRA_DESCONOCIDA,
                    indice = i + 1,
                    message = "la palabra ${i + 1} no esta en la wordlist BIP39",
                )
            }
        }
        val frase = MnemonicPhrase(palabras)
        // El checksum se comprueba y la semilla se suprime: el resultado no
        // hace falta para parsear, y dejarla viva seria una segunda copia de la
        // clave en memoria sin usar.
        toEntropy(frase)
        return frase
    }

    /** La wordlist, en el mismo orden de indices que usa la codificacion. */
    internal fun palabras(): List<String> = wordlist

    // ------------------------------------------------------------------
    // Normalizacion
    // ------------------------------------------------------------------

    /**
     * Los separadores que aparecen al PEGAR una frase.
     *
     * `\s` de Java no cubre el espacio duro U+00A0 —que es justo el que pone un
     * teclado movil al partir lineas— ni el BOM U+FEFF que algunos portapapeles
     * pegan. Sin ellos, una frase bien escrita se rechazaba por un caracter
     * invisible.
     */
    private val separadores = Regex("[\\s\\u00A0\\uFEFF]+")

    /** Split por blancos, NFC, minusculas y trim. */
    private fun normalizar(text: String): List<String> =
        Normalizer.normalize(text, Normalizer.Form.NFC)
            .lowercase(Locale.ROOT)
            .split(separadores)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    // ------------------------------------------------------------------
    // Bits
    // ------------------------------------------------------------------

    /** Un array de bits, MSB primero: el bit 0 es el bit mas alto del byte 0. */
    private fun bitsOf(bytes: ByteArray): IntArray =
        IntArray(bytes.size * 8) { i ->
            (bytes[i ushr 3].toInt() shr (7 - (i and 7))) and 1
        }

    /** La posicion del primer bit de la palabra [word] dentro del bloque. */
    private fun offsetOf(word: Int): Int = word * BITS_PER_WORD

    /** Los [WORD_COUNT] indices de palabra del bloque de bits. */
    private fun indicesFromBits(bits: IntArray): IntArray =
        IntArray(WORD_COUNT) { word -> bitsAt(bits, offsetOf(word)) }

    /** Los [BITS_PER_WORD] bits que empiezan en [from], como un indice. */
    private fun bitsAt(bits: IntArray, from: Int): Int {
        var value = 0
        for (k in 0 until BITS_PER_WORD) {
            value = (value shl 1) or bits[from + k]
        }
        return value
    }

    /** El bloque de bits de vuelta a bytes: 264 bits -> 33 bytes. */
    private fun bitsToBytes(indices: IntArray): ByteArray {
        val bits = IntArray(indices.size * BITS_PER_WORD)
        for ((word, indice) in indices.withIndex()) {
            for (k in 0 until BITS_PER_WORD) {
                bits[offsetOf(word) + k] = (indice shr (BITS_PER_WORD - 1 - k)) and 1
            }
        }
        val bytes = ByteArray(bits.size / 8)
        for (i in bits.indices) {
            bytes[i ushr 3] = ((bytes[i ushr 3].toInt() shl 1) or bits[i]).toByte()
        }
        return bytes
    }

    // ------------------------------------------------------------------
    // Puentes con la frase
    // ------------------------------------------------------------------

    /** Los indices de las palabras de [phrase], o el primer fallo que aparezca. */
    private fun indicesDe(phrase: MnemonicPhrase): IntArray {
        if (phrase.wordCount != WORD_COUNT) {
            throw MnemonicException(
                motivo = MnemonicMotivo.LONGITUD,
                message = "una frase son $WORD_COUNT palabras; hay ${phrase.wordCount}",
            )
        }
        return IntArray(WORD_COUNT) { i ->
            wordIndex[phrase.wordAt(i)]
                ?: throw MnemonicException(
                    motivo = MnemonicMotivo.PALABRA_DESCONOCIDA,
                    indice = i + 1,
                    message = "la palabra ${i + 1} no esta en la wordlist BIP39",
                )
        }
    }

    /**
     * Lee la wordlist de [recurso].
     *
     * Un recurso ausente es un error LEGIBLE y no un `NullPointerException` a
     * mitad de un guardado de identidad: el usuario tiene que ver que al APK le
     * falta un fichero, no una excepcion de Java que no dice nada.
     *
     * `internal` y con el recurso como parametro porque es la UNICA forma de
     * ejercitar el fallo: el camino feliz no se puede simular sin desinstalar el
     * APK, y un control negativo que no se ejecuta no es evidencia de nada.
     */
    internal fun cargarWordlistDe(recurso: String): List<String> {
        val stream = Mnemonic::class.java.getResourceAsStream(recurso)
            ?: throw MnemonicException(
                motivo = MnemonicMotivo.WORDLIST,
                message = "no se encontro la wordlist BIP39 en el classpath: $recurso",
            )
        val palabras = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (palabras.size != WORDLIST_SIZE) {
            throw MnemonicException(
                motivo = MnemonicMotivo.WORDLIST,
                message = "la wordlist de $recurso deberia tener $WORDLIST_SIZE palabras, " +
                    "tiene ${palabras.size}",
            )
        }
        return palabras
    }
}