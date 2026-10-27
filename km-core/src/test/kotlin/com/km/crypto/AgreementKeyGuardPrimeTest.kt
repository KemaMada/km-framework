package com.km.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import com.km.crypto.provider.BcX25519
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.math.BigInteger
import java.util.zip.ZipFile

/**
 * El primo de Curve25519 del [AgreementKeyGuard], y la compatibilidad del
 * bytecode con Android API 28.
 *
 * ## QUE CIERRA ESTE ARCHIVO
 *
 * Un `NoSuchFieldError` en tiempo de ejecucion, en un dispositivo real, por un
 * campo estatico que existe en el JDK de compilacion pero NO en el runtime de
 * Android por debajo de API 33.
 *
 * `AgreementKeyGuard` calculaba el primo de la curva como
 * `BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))`. `BigInteger.TWO`
 * es un campo ESTATICO que se anadio en Java 9 y que **Android solo expone
 * desde API 33**. La app declara `minSdk = 28`, de modo que en un Galaxy S8+
 * (Android 9 / API 28) la inicializacion del `object` reventaba con:
 *
 *     java.lang.NoSuchFieldError: No static field TWO of type Ljava/math/BigInteger
 *
 * y la sesion E2E no se abria nunca: el envio terminaba en `AUTH_FAILED`.
 * El emulador (API 37) no lo reproduce, asi que el fallo solo aparecia en
 * hardware antiguo.
 *
 * ## POR QUE EL CAMBIO NO ES UN "PARCHE" SINO UNA RESTRICCION
 *
 * El arreglo es `BigInteger.valueOf(2).pow(255)...`: el MISMO numero escrito
 * con una API que existe desde API 1. Aqui se afirma, por tres vias
 * independientes:
 *
 *  1. **El valor**: el primo del guard es exactamente `2^255 - 19`, y se
 *     compara contra la expresion literal y contra la constante en
 *     hexadecimal. No contra "algo que se parece".
 *  2. **El comportamiento**: la lista de rechazo se sigue ante los mismos
 *     puntos. Si el primo hubiera cambiado, el guard rechazaria material
 *     distinto, y eso se veria aqui.
 *  3. **El BYTECODE**: el `.class` compilado no contiene ninguna referencia a
 *     un campo estatico de `java.math.BigInteger` que no existiera en API 1.
 *     Esta es la unica afirmacion que habla del ARTEFACTO que viaja en la APK:
 *     el fuente puede estar limpio y el `.class` guardar una referencia
 *     heredada de otra parte.
 *
 * ## POR QUE `ZERO` Y `ONE` SI VALEN
 *
 * `BigInteger.ZERO` y `BigInteger.ONE` existen desde Java 1.0 y Android los
 * expone desde API 1. `BigInteger.TEN` y `BigInteger.TWO` son los dos que
 * llegaron tarde (API 33 en Android). La lista de campos permitidos es
 * exactamente `{ZERO, ONE}`.
 *
 * ## CONTROLES
 *
 * [API28-05] es el control del DETECTOR, no del codigo: se construye un
 * `.class` sintetico que SI contiene la referencia prohibida y se afirma que
 * el detector la ve, y otro que no la contiene y se afirma que no la inventa.
 * Un detector que nunca dispara daria verde para siempre.
 */
class AgreementKeyGuardPrimeTest {

    // ===================================================================
    // 1. EL VALOR DEL PRIMO
    // ===================================================================

    /**
     * El primo del guard, leido del campo privado por reflexion.
     *
     * No se expone `P` a proposito: `P` es un detalle de la implementacion y
     * abrirlo al paquete `internal` para poder mirarlo desde un test seria
     * ampliar la superficie de la API del framework por un test. La
     * reflexion lee lo que hay sin cambiar lo que hay.
     */
    private fun primoDelGuard(): BigInteger {
        val campo = AgreementKeyGuard::class.java.getDeclaredField("P")
        campo.isAccessible = true
        return campo.get(null) as BigInteger
    }

    @Test
    @DisplayName("API28-01 el primo del guard es exactamente 2^255 - 19")
    fun `API28-01 el primo del guard`() {
        val p = primoDelGuard()

        // 1. Contra la EXPRESION LITERAL, escrita con API 1. Esta es la
        //    comparacion que importa: dice que el primo es el que dice ser.
        val esperado = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))
        assertEquals(
            esperado,
            p,
            "el primo del guard no es 2^255 - 19: el arreglo de API 28 no puede cambiar la matematica",
        )

        // 2. Contra la CONSTANTE en hexadecimal, que no se puede confundir con
        //    ninguna otra: 32 bytes big-endian que terminan en `ED`.
        //    Escribirlos a mano seria el error que el propio guard evita,
        //    asi que se comparan como TEXTO y se exige el hexadecimal
        //    exacto, no un decimal.
        assertEquals(
            "7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed",
            p.toString(16),
            "el primo en hexadecimal es el canonico de Curve25519 (2^255 - 19)",
        )

        // 3. Y en bytes: 32 bytes big-endian que terminan en `ED`, no en `FD`.
        //    Un nibble equivocado en ese hexadecimal produce un valor que NO
        //    esta en la lista de rechazo y el guard no filtraria nada.
        val bytes = p.toByteArray().let {
            if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it
        }
        assertEquals(32, bytes.size, "el primo ocupa 32 bytes")
        assertEquals(0xED.toByte(), bytes[31], "el primo en big-endian termina en ED, no en FD")
    }

    // ===================================================================
    // 2. EL COMPORTAMIENTO NO HA CAMBIADO
    // ===================================================================

    /** `v` en 32 bytes little-endian, que es como mira X25519. */
    private fun littleEndian32(value: BigInteger): ByteArray {
        val be = value.toByteArray()
        val big = if (be.size > 32) be.copyOfRange(be.size - 32, be.size) else be
        val out = ByteArray(32)
        System.arraycopy(big, 0, out, 32 - big.size, big.size)
        return out.reversedArray()
    }

    @Test
    @DisplayName("API28-02 la matematica no ha cambiado: el guard rechaza los mismos puntos")
    fun `API28-02 la matematica del guard no ha cambiado`() {
        val p = primoDelGuard()

        // El guard normaliza MODULO `p`. Por eso `p`, `p+1` y `p-1` se
        // normalizan a `0`, `1` y `p-1`: los tres son puntos de orden
        // pequeno y tienen que rechazarse.
        assertTrue(AgreementKeyGuard.isLowOrder(littleEndian32(p)), "p se reduce a 0: es de orden pequeno")
        assertTrue(
            AgreementKeyGuard.isLowOrder(littleEndian32(p.add(BigInteger.ONE))),
            "p+1 se reduce a 1: es de orden pequeno",
        )
        assertTrue(
            AgreementKeyGuard.isLowOrder(littleEndian32(p.subtract(BigInteger.ONE))),
            "p-1 es de orden pequeno",
        )

        // Y `p-2` y `p+2` NO lo son: si el primo se hubiera movido, estos dos
        // cambiarian de veredicto.
        assertFalse(
            AgreementKeyGuard.isLowOrder(littleEndian32(p.subtract(BigInteger.valueOf(2)))),
            "p-2 no se reduce a ningun punto de la lista",
        )
        assertFalse(
            AgreementKeyGuard.isLowOrder(littleEndian32(p.add(BigInteger.valueOf(2)))),
            "p+2 se reduce a 2, que no esta en la lista",
        )

        // Y una clave real no se toca: el riesgo de una lista de rechazo es
        // rechazar de mas, y eso romperia el bootstrap entero.
        repeat(32) {
            val real = BcX25519().generateKeyPair().publicKey
            assertFalse(AgreementKeyGuard.isLowOrder(real), "una clave real no es de orden pequeno")
        }
    }

    // ===================================================================
    // 3. EL BYTECODE
    // ===================================================================

    /**
     * Una referencia a un miembro de otra clase, tal y como queda en el
     * constant pool.
     */
    private data class Referencia(
        val tipo: String,
        val clase: String,
        val nombre: String,
        val descriptor: String,
    ) {
        override fun toString(): String = "$clase#$nombre$descriptor"
    }

    /**
     * Lee el constant pool de un `.class` y devuelve sus referencias a
     * CAMPOS y a METODOS de OTRAS clases.
     *
     * Se leen a mano en vez de usar reflexion porque una referencia a un campo
     * que no existe en el runtime no se puede resolver por reflexion: la
     * excepcion se produce al ENLAZAR, no al preguntar. El constant pool, en
     * cambio, es un fichero con indices, y se puede leer entero.
     */
    private fun referenciasDe(bytes: ByteArray): List<Referencia> {
        val d = DataInputStream(ByteArrayInputStream(bytes))
        require(d.readInt() == 0xCAFEBABE.toInt()) { "no es un fichero de clase" }
        d.readUnsignedShort() // minor
        d.readUnsignedShort() // major
        val count = d.readUnsignedShort()

        val utf8 = arrayOfNulls<String>(count)
        val clases = arrayOfNulls<Int>(count)
        val nameAndType = arrayOfNulls<Pair<Int, Int>>(count)
        val refs = arrayOfNulls<Triple<String, Int, Int>>(count) // (tipo, class_index, nat_index)

        var i = 1
        while (i < count) {
            when (val tag = d.readUnsignedByte()) {
                1 -> { val len = d.readUnsignedShort(); utf8[i] = String(d.readNBytes(len), Charsets.UTF_8) }
                3 -> d.readInt()
                4 -> d.readInt()
                5 -> { d.readLong(); i++ } // `long` ocupa DOS huecos del pool
                6 -> { d.readDouble(); i++ }
                7 -> clases[i] = d.readUnsignedShort()
                8 -> d.readUnsignedShort()
                9 -> refs[i] = Triple("campo", d.readUnsignedShort(), d.readUnsignedShort())
                10, 11 -> refs[i] = Triple("metodo", d.readUnsignedShort(), d.readUnsignedShort())
                12 -> nameAndType[i] = d.readUnsignedShort() to d.readUnsignedShort()
                15 -> { d.readUnsignedByte(); d.readUnsignedShort() }
                16 -> d.readUnsignedShort()
                17, 18 -> { d.readUnsignedShort(); d.readUnsignedShort() }
                19, 20 -> d.readUnsignedShort()
                else -> error("etiqueta de constant pool desconocida: $tag")
            }
            i++
        }

        val salida = mutableListOf<Referencia>()
        for (indice in 1 until count) {
            val ref = refs[indice] ?: continue
            val nombreClase = utf8[clases[ref.second] ?: continue] ?: continue
            val nat = nameAndType[ref.third] ?: continue
            val nombre = utf8[nat.first] ?: continue
            val descriptor = utf8[nat.second] ?: ""
            salida += Referencia(ref.first, nombreClase, nombre, descriptor)
        }
        return salida
    }

    /**
     * Los campos de `java.math.BigInteger` que existen en Android API 28.
     *
     * `ZERO` y `ONE` existen desde Java 1.0 y Android los expone desde API 1.
     *
     * `TEN` tambien, y esto NO es lo que dice el JDK: `BigInteger.TEN` llego
     * en Java 9, pero la implementacion de Android (`libcore`) lo tiene desde
     * API 1. No es una suposicion: una sonda ejecutada en el Galaxy S8+ real
     * (Android 9 / API 28) responde `BigInteger.TEN = 10` y
     * `BigInteger.TWO AUSENTE (NoSuchFieldException)`, mientras que en el
     * emulador (API 37) los dos existen. La tabla del propio SDK
     * (`api-versions.xml`) coincide: `TEN` sin atributo `since` (API 1) y
     * `TWO` con `since="33"`.
     *
     * `TWO` es el unico que no puede usarse, y es el que rompia la sesion E2E
     * en el S8+.
     */
    private val CAMPOS_DE_BIGINTEGER_EN_API_1 = setOf("ZERO", "ONE", "TEN")

    @Test
    @DisplayName("API28-03 el bytecode del guard no usa campos de BigInteger posteriores a API 1")
    fun `API28-03 el bytecode del guard`() {
        val recurso = AgreementKeyGuard::class.java.classLoader
            .getResourceAsStream("com/km/crypto/AgreementKeyGuard.class")
        require(recurso != null) { "no se encuentra el .class del guard en el classpath" }
        val bytes = recurso.use { it.readBytes() }

        val camposDeBigInteger = referenciasDe(bytes)
            .filter { it.tipo == "campo" && it.clase == "java/math/BigInteger" }
            .map { it.nombre }
            .toSet()

        assertTrue(
            camposDeBigInteger.none { it !in CAMPOS_DE_BIGINTEGER_EN_API_1 },
            "el bytecode del guard referencia campos de java.math.BigInteger que no existen en " +
                "API 1 (TWO es API 33 y la app declara minSdk = 28): $camposDeBigInteger",
        )
    }

    @Test
    @DisplayName("API28-04 ningun modulo de km-core usa campos de BigInteger posteriores a API 1")
    fun `API28-04 el barrido de km-core`() {
        val ofensas = mutableListOf<String>()
        forEachClassFileDeKmCore { clase, bytes ->
            val prohibidos = referenciasDe(bytes)
                .filter { it.tipo == "campo" && it.clase == "java/math/BigInteger" }
                .filter { it.nombre !in CAMPOS_DE_BIGINTEGER_EN_API_1 }
                .map { "$clase -> $it" }
            ofensas += prohibidos
        }
        assertTrue(
            ofensas.isEmpty(),
            "km-core referencia campos de java.math.BigInteger posteriores a API 1:\n" +
                ofensas.joinToString("\n") { "  - $it" },
        )
    }

    /**
     * Recorre todos los `.class` de km-core.
     *
     * El code source del test es el directorio de clases de `main`; si fuera un
     * JAR (publicacion), se recorre el JAR. Las dos formas se soportan porque
     * lo que se mira es el ARTEFACTO, y el artefacto es el mismo.
     */
    private fun forEachClassFileDeKmCore(accion: (String, ByteArray) -> Unit) {
        val localizacion = File(
            AgreementKeyGuard::class.java.protectionDomain.codeSource.location.toURI(),
        )
        if (localizacion.isDirectory) {
            localizacion.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .forEach { file ->
                    accion(
                        file.relativeTo(localizacion).path,
                        file.readBytes(),
                    )
                }
        } else {
            ZipFile(localizacion).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .forEach { entrada ->
                        accion(entrada.name, zip.getInputStream(entrada).use { it.readBytes() })
                    }
            }
        }
    }

    // ===================================================================
    // 4. CONTROL DEL DETECTOR
    // ===================================================================

    /**
     * Un `.class` sintetico cuyo constant pool referencia el campo indicado
     * de `java.math.BigInteger`.
     *
     * Se construye a mano para que el control no dependa de un compilador en
     * tiempo de test: el unico formato que hay que entender es el constant
     * pool, y el resto del fichero es un cascaron minimo.
     */
    private fun classFileQueReferencia(campo: String): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeInt(0xCAFEBABE.toInt())
        d.writeShort(0)   // minor
        d.writeShort(52)  // major
        d.writeShort(9)   // constant_pool_count
        d.writeByte(1); d.writeUTF("java/math/BigInteger")   // 1
        d.writeByte(1); d.writeUTF(campo)                    // 2
        d.writeByte(1); d.writeUTF("Ljava/math/BigInteger;") // 3
        d.writeByte(7); d.writeShort(1)                     // 4 Class -> 1
        d.writeByte(12); d.writeShort(2); d.writeShort(3)   // 5 NameAndType
        d.writeByte(9); d.writeShort(4); d.writeShort(5)    // 6 Fieldref
        d.writeByte(1); d.writeUTF("Sintetico")             // 7
        d.writeByte(7); d.writeShort(7)                     // 8 Class -> 7
        d.writeShort(0x0021) // access_flags
        d.writeShort(8)      // this_class
        d.writeShort(0)      // super_class
        d.writeShort(0)      // interfaces
        d.writeShort(0)      // fields
        d.writeShort(0)      // methods
        d.writeShort(0)      // attributes
        d.flush()
        return out.toByteArray()
    }

    @Test
    @DisplayName("API28-05 control: el detector ve el campo prohibido y no inventa")
    fun `API28-05 control del detector`() {
        // NEGATIVO: un `.class` que SI usa `BigInteger.TWO` tiene que ser
        // detectado. Si no lo fuera, los tests anteriores no probarian nada.
        val prohibido = referenciasDe(classFileQueReferencia("TWO"))
            .filter { it.tipo == "campo" && it.clase == "java/math/BigInteger" }
            .map { it.nombre }
        assertEquals(
            listOf("TWO"),
            prohibido,
            "el detector tiene que ver la referencia a BigInteger.TWO",  // API-LEVEL-FIXTURE
        )

        // POSITIVO: el mismo `.class` con un campo de API 1 no tiene que
        // disparar nada. Un detector que dispara siempre tampoco sirve.
        val permitido = referenciasDe(classFileQueReferencia("ONE"))
            .filter { it.tipo == "campo" && it.clase == "java/math/BigInteger" }
            .filter { it.nombre !in CAMPOS_DE_BIGINTEGER_EN_API_1 }
        assertTrue(
            permitido.isEmpty(),
            "el detector no puede inventar una prohibicion sobre un campo de API 1: $permitido",
        )
    }
}
