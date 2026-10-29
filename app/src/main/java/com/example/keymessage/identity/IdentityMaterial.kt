package com.example.keymessage.identity

import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.crypto.KeyPair
import com.km.identity.KmIds
import com.km.mnemonic.Mnemonic
import com.km.mnemonic.MnemonicPhrase
import com.km.model.IdentityId
import com.example.keymessage.util.toHex

/**
 * 1.1 — El material de identidad de la app, y como se deriva su identificador.
 *
 * ## LO QUE ES Y LO QUE NO ES
 *
 * La app NO tiene criptografia propia: el par Ed25519 lo genera
 * `com.km.crypto.Ed25519Impl` (el mismo del framework) y el identificador lo
 * deriva `com.km.identity.KmIds`, que es KM-ID-0001 seccion 6. Lo unico que
 * hace esta clase es juntar las dos cosas y dar nombres decentes.
 *
 * ## POR QUE `identityId` Y NO `deviceId`
 *
 * `KmIds` deriva tres identificadores de tres dominios distintos, y ninguno es
 * alias de otro. El que se muestra y se enruta como "yo" en la app es
 * `identityId`, el mismo que `PeerContext.forContact` deriva de la RAIZ de un
 * bundle. Usar `deviceId` para lo mismo daria dos claves de ruteo distintas para
 * la misma conversacion.
 *
 * ## LA SEMILLA ES LA CLAVE PRIVADA
 *
 * `Ed25519.keyPairFromSeed` devuelve como `privateKey` la SEMILLA de 32 bytes,
 * no el escalar expandido (RFC 8032). Por eso [IdentityCodec] persiste la
 * semilla y la clave privada NUNCA se deriva de ella dos veces.
 */
data class IdentityMaterial(
    /** Identidad de USUARIO, derivada con `KmIds.identityId`. */
    val identityId: IdentityId,
    /** Par Ed25519 del framework. `privateKey` es la semilla de 32 bytes. */
    val keyPair: KeyPair,
) {
    override fun toString(): String = "IdentityMaterial($identityId)"
}

/**
 * Fabrica de material de identidad.
 *
 * Se instancia con un [Ed25519] inyectado para que un test pueda sembrar las
 * claves: dos semillas distintas tienen que dar dos identidades distintas, y
 * con claves aleatorias ese test solo podria comprobar que no se repiten.
 */
class IdentityMaterialFactory(
    private val ed25519: Ed25519 = Ed25519Impl(),
) {

    /** Par NUEVO, aleatorio.

     *  La entropia NO se toma de `Ed25519Impl.generateKeyPair()`: en Android
     *  `java.security.SecureRandom` (que usa esa implementacion) ha producido la
     *  MISMA semilla en dos dispositivos distintos, con lo que ambos nodos
     *  acababan con la misma identidad y el rele no podia distinguirlos. La
     *  semilla se lee directamente de `/dev/urandom` (fuente del kernel, la misma
     *  que alimenta a `SecureRandom` pero sin pasar por el proveedor Java que
     *  falla), y el par se deriva con el framework (`keyPairFromSeed`), que es
     *  la unica criptografia que se usa. Si `/dev/urandom` no esta disponible
     *  (entornos no-Unix), se cae al `SecureRandom` por defecto como ultimo
     *  recurso.
     */
    fun generate(): IdentityMaterial {
        val seed = ByteArray(32)
        var filled = false
        runCatching {
            java.io.FileInputStream("/dev/urandom").use { input ->
                var off = 0
                while (off < seed.size) {
                    val n = input.read(seed, off, seed.size - off)
                    if (n < 0) throw java.io.EOFException("fin prematuro de /dev/urandom")
                    off += n
                }
            }
            filled = true
        }
        if (!filled) {
            java.security.SecureRandom().nextBytes(seed)
        }
        val pair = ed25519.keyPairFromSeed(seed)
        return IdentityMaterial(identityIdOf(pair.publicKey), pair)
    }

    /** Par DERIVADO de una semilla de 32 bytes. Determinista por construccion. */
    fun fromSeed(seed: ByteArray): IdentityMaterial {
        val pair = ed25519.keyPairFromSeed(seed)
        return IdentityMaterial(identityIdOf(pair.publicKey), pair)
    }

    /**
     * 6.2 — [fromSeed] con la semilla DESTRUIDA al salir.
     *
     * ## POR QUE HACE FALTA Y POR QUE NO BASTA [fromSeed]
     *
     * La restauracion arranca en una frase, la frase se pliega a 32 bytes, y esos
     * 32 bytes son la clave privada de la identidad. [fromSeed] copia la semilla
     * dentro del `KeyPair` (`Ed25519Impl` devuelve `seed.copyOf()`), de modo que
     * el array de entrada se puede borrar sin tocar el material: si no se
     * borrara, quedaria una SEGUNDA copia de la clave en memoria, viva, al
     * alcance de cualquier volcado de heap, mucho despues de que la frase haya
     * salido de la pantalla.
     *
     * El borrado va en un `finally` a proposito: si `fromSeed` falla, la frase
     * es invalida y la clave derivada no existe, pero la tentativa sigue siendo
     * una clave que no hay que dejar en memoria.
     */
    fun fromSeedEfimera(seed: ByteArray): IdentityMaterial =
        try {
            fromSeed(seed)
        } finally {
            seed.fill(0)
        }

    /**
     * `identityId` de una clave publica de identidad.
     *
     * Expresion de KM-ID-0001 tal cual, sin recortar: un identificador
     * truncado seria OTRO identificador, y dos truncaciones distintas darian dos
     * claves de ruteo para la misma identidad.
     */
    fun identityIdOf(identityRootPublicKey: ByteArray): IdentityId =
        IdentityId(KmIds.identityId(identityRootPublicKey).toHex())
}

/**
 * 6.2 — La frase de 24 palabras de ESTA identidad.
 *
 * ## DE DONDE SALE Y DONDE ACABA
 *
 * De la semilla, que es la clave privada (ver la nota de [IdentityMaterial]), y
 * por eso la codificacion va al REVES de como la UI la usa: la frase se deriva
 * cuando el usuario la pide y no se guarda. Aqui no hay nada que limpiar porque
 * [Mnemonic.fromEntropy] no retiene la entrada: la copia en claro de la semilla
 * es la que ya tiene el [IdentityMaterial], y esa tiene que existir mientras viva
 * la identidad.
 *
 * ## POR QUE LA APP NO CODIFICA
 *
 * Porque `com.km.mnemonic` es el que sabe como se trocea y como es el checksum.
 * Una segunda codificacion en la app daria dos frases para la misma identidad en
 * cuanto una de las dos se tocara.
 */
fun fraseDeRecuperacion(material: IdentityMaterial): MnemonicPhrase =
    Mnemonic.fromEntropy(material.keyPair.privateKey)