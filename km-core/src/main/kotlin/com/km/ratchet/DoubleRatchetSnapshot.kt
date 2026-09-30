package com.km.ratchet

import com.km.crypto.DerivedX25519KeyPair

/**
 * KM-0004 3Q.5.2a — Estado instantaneo del Double Ratchet.
 *
 * ## QUE ES
 *
 * Una foto del estado criptografico de una sesion, tomada en un instante, con
 * copia profunda. Con ella se puede reconstruir una sesion que continua
 * exactamente donde se dejo: mismo RootKey, mismo par DH, mismos contadores,
 * mismas cadenas de envio y recepcion con sus claves saltadas.
 *
 * NO es un formato de persistencia. No hay bytes, ni version, ni nada que se
 * escriba en un disco. Decidir como se serializa es trabajo del checkpoint de
 * persistencia atomica, que tiene que escribir tres cosas JUNTAS (este estado,
 * el ciphertext original y el registro de entrega) y por tanto necesita un
 * formato con una unidad de atomicidad propia. Fijar aqui ese formato seria
 * inventar una representacion que ningun consumidor exige todavia, y
 * congelarla sin uno que la Someta a prueba.
 *
 * ## LA FRONTERA QUE ESTE TIPO SOSTIENE
 *
 * El ratchet puede saber persistir su propio estado. NO puede saber nada mas.
 * Este archivo no declara ningun campo que no sea material criptografico del
 * ratchet, y no puede hacer otra cosa: un estado de ratchet que supiera si un
 * mensaje llego a la aplicacion o si su confirmacion salio por la red seria un
 * ratchet que toma decisiones de una capa superior, y esas decisiones no son
 * suyas. Un estado persistido que las mezclara tampoco serviria: al restaurarlo,
 * dos hechos de capas distintas se fundirian en uno solo, imposible de
 * desenredar despues.
 *
 * ## QUE SE GUARDA Y POR QUE
 *
 * | campo                   | por que hace falta                                        |
 * |-------------------------|-----------------------------------------------------------|
 * | `rootKey`               | sin el, KDF_RK derivaria otra rama y las claves noorian igual |
 * | `dhSelf`                | es la mitad DH que responde; sin el, el secreto DH se pierde |
 * | `dhRemote`              | sin el, el ratchet no sabe con quien hacer el DH ratchet     |
 * | `sendMessageNumber` (Ns)| el header lo anuncia; sin el, el receptor ve un N ya usado   |
 * | `previousChainLength` (PN)| permite al receptor saltar la cadena anterior               |
 * | `sendChain`             | cadena de envio y su indice dentro de ella                  |
 * | `receiveChains`         | una por epoca DH, con sus claves saltadas: historia de epochs|
 *
 * ## QUE NO SE GUARDA
 *
 * - `pending` y sus auxiliares. Ver [DoubleRatchetSession.snapshot].
 * - `x25519` y `kdf`. No son estado: son las primitivas. Se entregan a la
 *   hora de reconstruir, igual que al construir la sesion.
 * - Nada de las capas superiores. Por la frontera de arriba.
 */
class DoubleRatchetSnapshot internal constructor(
    rootKey: ByteArray,
    dhSelf: DerivedX25519KeyPair,
    dhRemote: ByteArray?,
    sendMessageNumber: UInt,
    previousChainLength: UInt,
    sendChain: SymmetricRatchetSnapshot,
    receiveChains: List<ReceiveChainSnapshot>,
) {
    // Copia profunda en el constructor: el estado de la sesion y el de la foto
    // no pueden llegar a compartir un solo array. Sin esto, escribir en uno
    // escribiria en el otro, y la foto dejaria de ser una foto.
    internal val rootKey: ByteArray = rootKey.copyOf()
    internal val dhSelf: DerivedX25519KeyPair = dhSelf
    internal val dhRemote: ByteArray? = dhRemote?.copyOf()
    internal val sendMessageNumber: UInt = sendMessageNumber
    internal val previousChainLength: UInt = previousChainLength
    internal val sendChain: SymmetricRatchetSnapshot = sendChain
    internal val receiveChains: List<ReceiveChainSnapshot> = receiveChains.map { it.copy() }

    init {
        require(rootKey.size == RootRatchetSpec.ROOT_KEY_LENGTH) {
            "rootKey debe tener ${RootRatchetSpec.ROOT_KEY_LENGTH} bytes, tiene ${this.rootKey.size}"
        }
        val remote = this.dhRemote
        require(remote == null || remote.size == DerivedX25519KeyPair.LENGTH) {
            "dhRemote debe tener ${DerivedX25519KeyPair.LENGTH} bytes, tiene ${remote?.size}"
        }
        // `ByteArray` compara por REFERENCIA: un `toSet()` sobre arrays daria
        // identificadores distintos para el mismo contenido y la comprobacion
        // no detectaria nada. `ChainIdentifier` compara por valor.
        val ids = this.receiveChains.map { ChainIdentifier(it.chainId) }
        require(ids.distinct().size == ids.size) {
            "hay dos cadenas de recepcion con la misma clave publica DH"
        }
        // `Ns` esta escrito DOS veces en el estado de la sesion: como campo de
        // `DoubleRatchetSession` y dentro de la cadena de envio. En una sesion
        // viva los dos valen lo mismo SIEMPRE, porque `commitSend` los fija
        // juntos. Se comprueba aqui por la misma razon que en
        // `DerivedX25519KeyPair`: una foto que se contradiga a si misma no
        // describe ningun estado que la sesion pueda tener, y restaurarla
        // produciria una sesion cuyo `Ns` no cuadra con el indice real de su
        // cadena de envio. Sin esta comprobacion, la mutacion "no serializar
        // `sendMessageNumber`" no la detecta NINGUN test: la cadena de envio ya
        // lleva el indice correcto y tapa el campo.
        require(sendMessageNumber == sendChain.sendMessageNumber) {
            "el indice de envio de la foto ($sendMessageNumber) no coincide con el de su " +
                "cadena de envio (${sendChain.sendMessageNumber})"
        }
    }
}

/**
 * Estado instantaneo de UNA cadena de recepcion (una epoca del ratchet DH).
 *
 * `chainId` es la clave publica DH que abrio la epoca. Se guarda entera, y no
 * solo su huella, porque es lo que el receptor mira para decidir si un frame
 * pertenece a una epoca conocida o abre una nueva.
 */
class ReceiveChainSnapshot internal constructor(
    chainId: ByteArray,
    ratchet: SymmetricRatchetSnapshot,
) {
    internal val chainId: ByteArray = chainId.copyOf()
    internal val ratchet: SymmetricRatchetSnapshot = ratchet

    /**
     * Copia profunda: el identificador es material de estado, no una etiqueta.
     *
     * Tambien se copia el ratchet. `SymmetricRatchetSnapshot` guarda sus
     * cadenas en `ByteArray`, y compartirlo entre dos entradas haria que
     * escribir en una escribiera en la otra: dos fotos tomadas del mismo
     * estado podrian acabar con claves distintas sin que nada lo indicase.
     */
    internal fun copy(): ReceiveChainSnapshot = ReceiveChainSnapshot(
        chainId = chainId,
        ratchet = SymmetricRatchetSnapshot(
            sendChainKey = ratchet.sendChainKey.copyOf(),
            sendMessageNumber = ratchet.sendMessageNumber,
            receiveChainKey = ratchet.receiveChainKey.copyOf(),
            receiveMessageNumber = ratchet.receiveMessageNumber,
            skipped = ratchet.skipped.mapValues { it.value.copyOf() },
        ),
    )
}
