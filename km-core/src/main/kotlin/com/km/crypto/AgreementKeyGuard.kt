package com.km.crypto

import java.math.BigInteger

/**
 * FRONTERA CRIPTOGRAFICA DEL MOTOR: una clave publica de acuerdo REMOTA tiene
 * que ser una clave, no solo tener el tamano de una clave.
 *
 * ## EL PROBLEMA QUE CIERRA
 *
 * Toda ruta de acuerdo del motor (X3DH y el bootstrap del Double Ratchet)
 * comprobaba que la clave publica remota midiera 32 bytes. Con 32 bytes, esa
 * comprobacion no dice NADA: una clave publica X25519 de orden pequeno —el caso
 * canonico son 32 bytes a cero— la atraviesa y llega hasta `X25519.agree`, que
 * aborta con [AllZeroSharedSecretException]. Ese material es REMOTO en las
 * cuatro rutas: `EK_A` viaja en el header del `SecureFrame`, e `IK`, `SPK` y
 * `OPK` viajan en el `ContactBundle` del otro dispositivo.
 *
 * ## POR QUE LA COMPROBACION VA ANTES Y NO DESPUES
 *
 * Por dos razones que son independientes y que las dos importan:
 *
 * 1. **Nada que consumir antes de decidir.** Si el rechazo ocurre despues, la
 *    ruta ya ha hecho trabajo con material remoto: en `X3dh.respond` ya ha
 *    derivado `K1` cuando se llega a `EK_A`; en `X3dh.initiate` ya ha gastado
 *    un par efimero generado para la sesion. Un rechazo no puede costar la
 *    creacion de material de sesion, y menos su derivacion.
 * 2. **El fallo es de ENTRADA, no de OPERACION.** Que 32 bytes no puedan
 *    producir un secreto es una propiedad del material, decidible sin
 *    criptografia. Descubrirlo haciendo criptografia y mirando el resultado es
 *    dejar que la decision se tome con el peor mecanismo posible.
 *
 * ## QUE TIPO DE RECHAZO ES
 *
 * [AllZeroSharedSecretException], el MISMO tipo que produce la primitiva, y no
 * uno nuevo. No se ha inventado un canal de `Result` que el motor no tiene ni
 * se ha degradado una excepcion tipada en algo peor: el llamante que ya
 * estaba obligado a manejar el abort por secreto cero sigue manejndolo igual,
 * solo que ahora se le entrega antes y con las manos limpias.
 *
 * ## LA LISTA ES CERRADA, Y SE CALCULO, NO SE COPIO
 *
 * La salida de X25519 es todo cero si y solo si el punto remoto pertenece al
 * subgrupo 2-primario de Curve25519, porque el escalar de RFC 7748 §5 es
 * multiplo de 8. Ese subgrupo tiene 8 puntos y, en coordenada u de Montgomery
 * (little-endian, 32 bytes), son exactamente los cinco valores de
 * [LOW_ORDER_U]. Los dos ultimos son de orden 8 y son los dos "puntos negros"
 * que aparecen en la lista canonica de las implementaciones de X25519.
 *
 * Las tres primeras filas se calculan a partir de `p = 2^255 - 19` con
 * [BigInteger] en vez de escribirse en hexadecimal a mano, porque un error de
 * un nibble en ese hexadecimal produce un valor que NO esta en la lista y que
 * el guard no filtraria: el fallo seria silencioso y con apariencia de
 * robustez. (`p` en 32 bytes big-endian termina en `ED`, no en `FD`.)
 *
 * ## LA LISTA SE COMPRA SOBRE LA CODIFICACION REAL, NO SOBRE EL CAMPO
 *
 * X25519 ignora el bit alto del ultimo byte y reduce el resultado modulo `p`
 * durante la aritmetica. Sin las dos operaciones de [normalize], un remoto
 * hostil solo tiene que poner `FF` donde el punto publicado lleva `7F` —o
 * sumar `p` a un valor— para salirse de la lista sin cambiar en absoluto el
 * material que la primitiva va a usar. Se normaliza ANTES de comparar, que es
 * comparar sobre lo que la primitiva realmente mira.
 *
 * ## LO QUE ESTA COMPROBACION NO ASEGURA
 *
 * Es una lista de rechazo, asi que por construccion no puede ser una prueba
 * de que un valor NO es de orden pequeno; solo de que los que lo son, lo son
 * temprano. La garantia incondicional de que ninguna ruta remota sale con una
 * excepcion de proceso no depende de esta lista: depende de que
 * (a) toda clave remota se comprueba aqui ANTES de derivar nada y antes de
 * generar material, y (b) si algo se escapara de la lista, el abort de
 * [X25519.agree] sigue siendo [AllZeroSharedSecretException], que es el mismo
 * tipo tipado y el mismo rechazo. Las dos capas no compiten: la primera evita
 * el trabajo, la segunda garantiza el contrato.
 */
internal object AgreementKeyGuard {

    /** `p = 2^255 - 19`. Calculado, no copiado. */
    private val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))

    /**
     * Orden pequeno (RFC 7748 §6.1).
     *
     * Los dos ultimos son los unicos puntos de orden 8 de la curva y coinciden
     * con la lista canonica que usan las implementaciones de X25519; el segundo
     * es el "punto publicado" que ya aparecia en los tests del receptor.
     */
    private val LOW_ORDER_U: List<BigInteger> = listOf(
        // Orden 1: el todo cero, que es el caso canonico de la entrada hostil.
        BigInteger.ZERO,
        // Orden 4.
        BigInteger.ONE,
        // Orden 4.
        P.subtract(BigInteger.ONE),
        // Orden 8.
        BigInteger("325606250916557431795983626356110631294008115727848805560023387167927233504"),
        // Orden 8. Little-endian: 5f9c95bc...1157
        BigInteger("39382357235489614581723060781553021112529911719440698176882885853963445705823"),
    )

    private val LOW_ORDER_32: List<ByteArray> = LOW_ORDER_U.map { littleEndian32(it) }

    /**
     * Comprueba que una clave publica de acuerdo REMOTA sea utilizable.
     *
     * @param key material remoto.
     * @param label nombre del papel, solo para el diagnostico.
     * @param keyLength longitud que exige el llamante (32 en X25519).
     * @throws IllegalArgumentException si la longitud no es la correcta. Este
     *   es el rechazo ESTRUCTURAL y conserva su tipo historico.
     * @throws AllZeroSharedSecretException si el material es criptograficamente
     *   invalido pese a tener la longitud correcta.
     */
    fun requireUsableAgreementKey(key: ByteArray, label: String, keyLength: Int) {
        require(key.size == keyLength) {
            "$label debe ser una clave X25519 de $keyLength bytes, " +
                "tiene ${key.size}"
        }
        if (isLowOrder(key)) {
            throw AllZeroSharedSecretException(
                "clave publica de acuerdo de orden pequeno ($label): " +
                    "el secreto compartido seria todo cero (RFC 7748 6.1)",
            )
        }
    }

    /**
     * `true` si la clave publica no puede producir un secreto compartido.
     *
     * Compara sobre la CODIFICACION NORMALIZADA (bit alto del ultimo byte a
     * cero y reduccion modulo `p`), que es exactamente lo que X25519 decodifica.
     * Publico para que los tests puedan afirmar el criterio sin reimplementarlo.
     */
    fun isLowOrder(publicKey: ByteArray): Boolean {
        if (publicKey.size != 32) return false
        val u = normalize(publicKey)
        return LOW_ORDER_32.any { it.contentEquals(u) }
    }

    /**
     * `u` como lo mira de verdad la primitiva: 32 bytes little-endian con el
     * bit 255 a cero, reducido modulo `p`.
     *
     * El `reversedArray` NO es cosmetico: `BigInteger(signum, magnitude)` lee
     * la magnitud en ORDEN GRANDE, y la entrada de X25519 esta en orden
     * PEQUENO. Sin invertir, `BigInteger(1, be)` leeria `u` al reves, y la
     * comparacion contra la lista saldria `false` para todos los puntos
     * salvo el todo cero —que es simetrico y por eso funcionaba—, de modo que
     * el guard solo contendria el caso mas obvio y dejaria pasar el resto sin
     * avisar. Un fallo asi es peor que no tener guard: parece que hay uno.
     */
    private fun normalize(publicKey: ByteArray): ByteArray {
        val le = publicKey.copyOf()
        le[31] = (le[31].toInt() and 0x7F).toByte()
        return littleEndian32(BigInteger(1, le.reversedArray()).mod(P))
    }

    /**
     * `u` en 32 bytes little-endian.
     *
     * Rellena a la IZQUIERDA con ceros antes de invertir. Sin ese relleno,
     * `BigInteger.ZERO.toByteArray()` son UN byte y la comparacion acabaria
     * entre arrays de longitudes distintas: `contentEquals` daria `false` para
     * el todo cero y el punto de orden pequeno mas canonico se escaparia. Es
     * un fallo silencioso con apariencia de robustez, que es exactamente la
     * clase de fallo que este archivo existe para evitar.
     */
    private fun littleEndian32(value: BigInteger): ByteArray {
        val be = value.toByteArray()
        val big = if (be.size > 32) be.copyOfRange(be.size - 32, be.size) else be
        val out = ByteArray(32)
        System.arraycopy(big, 0, out, 32 - big.size, big.size)
        return out.reversedArray()
    }

}
