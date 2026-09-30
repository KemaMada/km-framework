package com.km.ratchet

import com.km.crypto.AllZeroSharedSecretException
import com.km.crypto.DerivedX25519KeyPair
import com.km.crypto.Kdf
import com.km.crypto.X25519
import com.km.crypto.X25519KeyPair

/**
 * KDF_RK: deriva del Root Key.
 *
 * <pre>
 *   newRootKey  = HKDF(salt = rootKey, ikm = dhOutput,
 *                       info = "KM-0004/ROOT-RATCHET/V1/ROOT",  len = 32)
 *   newChainKey = HKDF(salt = rootKey, ikm = dhOutput,
 *                       info = "KM-0004/ROOT-RATCHET/V1/CHAIN", len = 32)
 * </pre>
 *
 * Dominios separados: las dos salidas nunca tienen el mismo significado.
 * El RootKey actua como salt, el DH output como IKM.
 */
object RootRatchetSpec {
    const val INFO_ROOT: String = "KM-0004/ROOT-RATCHET/V1/ROOT"
    const val INFO_CHAIN: String = "KM-0004/ROOT-RATCHET/V1/CHAIN"

    const val ROOT_KEY_LENGTH: Int = 32
    const val CHAIN_KEY_LENGTH: Int = 32
}

/** Resultado de KDF_RK: la nueva raiz y la nueva cadena. */
data class RootDerivation(val newRootKey: ByteArray, val newChainKey: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RootDerivation) return false
        return newRootKey.contentEquals(other.newRootKey) && newChainKey.contentEquals(other.newChainKey)
    }

    override fun hashCode(): Int = 31 * newRootKey.contentHashCode() + newChainKey.contentHashCode()
}

/**
 * Estado completo de una sesion de DH Ratchet (3O.3).
 *
 * Corresponde al modelo formal: `DHs`, `DHr`, `RK`, `CKs`, `CKr`,
 * `Ns`, `Nr`, `PN` y las claves de mensaje saltadas.
 *
 * ATOMICIDAD: ninguna operacion muta el estado real hasta que el mensaje
 * se ha autenticado. [previewReceive] construye un estado CANDIDATO
 * completo (copia profunda) y devuelve la `messageKey`; [commitReceive]
 * lo adopta. [discardReceive] lo descarta.
 *
 * SEPARACION DE CLAVES (invariante 3O.0/3H):
 * `dhSelf` es un par X25519 EFIMERO del ratchet. NUNCA se reutiliza una
 * clave de firma Ed25519 para el ratchet.
 */
class DoubleRatchetSession(
    rootKey: ByteArray,
    dhSelf: X25519KeyPair,
    dhRemote: ByteArray?,
    sendChainKey: ByteArray,
    receiveChainKey: ByteArray,
    private val x25519: X25519,
    private val kdf: Kdf,
) {
    // ------------------------------------------------------------------
    // Estado real
    // ------------------------------------------------------------------

    private var rootKey: ByteArray = rootKey.copyOf()
    private var dhSelf: X25519KeyPair = dhSelf
    private var dhRemote: ByteArray? = dhRemote?.copyOf()

    /** Indice de envio de la cadena actual (Ns). */
    private var sendMessageNumber: UInt = 0u

    /** Longitud de la cadena de envio anterior (PN). */
    private var previousChainLength: UInt = 0u

    /**
     * Ratchets de recepcion por epoca, indexados por la clave publica DH
     * que las origino. Las cadenas retiradas se conservan para servir
     * mensajes tardios desde sus skipped keys.
     *
     * ESTE MAPA ES LA RESPUESTA A "¿esta epoca ya existe?", y por eso
     * [previewReceive] consulta aqui ANTES de decidir que un frame abre una
     * epoca nueva. Su criterio de conservacion es additivo: una entrada se
     * crea cuando una DH autentica su primer frame, y se queda —con su indice
     * de recepcion y sus claves saltadas— mientras la sesion viva. No existe
     * politica de retirada: quien decide cuanto tiempo puede quedar un frame en
     * vuelo es la duracion de la sesion, no este codigo, ylimitarla aqui seria
     * inventar un criterio que ningun consumidor ha pedido.
     *
     * Consecuencia que hay que conocer: el numero de entradas crece una por
     * cada rotacion DH AUTENTICADA, y las claves saltadas de cada una se
     * consumen de una en una al servirlas. El total retenido no tiene tope
     * (el unico tope es [SymmetricRatchetSpec.MAX_SKIP], y es POR CADENA, para
     * el salto, no para el almacen). Es un coste asumido, no un olvido: sin
     * esta conservacion, los frames en vuelo de una epoca anterior no tendrian
     * de donde sacar la clave.
     */
    private val receiveChains = LinkedHashMap<ChainIdentifier, SymmetricRatchet>()

    private var sendChain: SymmetricRatchet =
        SymmetricRatchet(sendChainKey, kdf)

    // Estado candidato en curso (nunca aplicado hasta commit).
    private var pending: Candidate? = null

    private class Candidate(
        var rootKey: ByteArray,
        var dhSelf: X25519KeyPair,
        var dhRemote: ByteArray?,
        var sendMessageNumber: UInt,
        var previousChainLength: UInt,
        var sendChain: SymmetricRatchet,
        val receiveChains: MutableMap<ChainIdentifier, SymmetricRatchet>,
    )

    // ------------------------------------------------------------------
    // KDF_RK
    // ------------------------------------------------------------------

    /**
     * KDF_RK: avanza la raiz y produce una cadena nueva.
     *
     * Dominios separados para ROOT y CHAIN.
     */
    fun kdfRk(rootKey: ByteArray, dhOutput: ByteArray): RootDerivation {
        val newRoot = kdf.hkdf(
            salt = rootKey,
            ikm = dhOutput,
            info = RootRatchetSpec.INFO_ROOT.toByteArray(),
            length = RootRatchetSpec.ROOT_KEY_LENGTH,
        )
        val newChain = kdf.hkdf(
            salt = rootKey,
            ikm = dhOutput,
            info = RootRatchetSpec.INFO_CHAIN.toByteArray(),
            length = RootRatchetSpec.CHAIN_KEY_LENGTH,
        )
        return RootDerivation(newRoot, newChain)
    }

    /** Calcula el secreto DH entre la clave propia y una clave publica. */
    fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray =
        x25519.agree(privateKey, publicKey)

    /**
     * Un DH cuya clave publica VIENE DEL CABLE, o `null` si el acuerdo aborta.
     *
     * ## POR QUE UNA EXCEPCION SE CONVIERTE EN UN `null`
     *
     * `agree` lanza [AllZeroSharedSecretException] cuando el secreto
     * compartido sale de todo cero, que es lo que RFC 7748 6.1 obliga a hacer
     * con una clave publica de ORDEN PEQUENO. La longitud de esa clave es la
     * correcta (32 bytes, la que el wire format obliga), asi que la
     * comprobacion de longitud de [previewReceive] no la ve, y la excepcion se
     * escapaba hacia el llamante.
     *
     * El efecto era una denegacion de servicio remota trivial: cualquiera que
     * pueda escribir un frame en el cable tumba el proceso receptor con 44
     * bytes de cabecera y nada mas. Un rechazo criptografico es una DECISION,
     * y una decision no se expresa abortando el proceso que la toma. Por eso
     * el abort se convierte en `null` y el llamante lo traduce a un
     * [RejectReason]: la excepcion deja de ser la via por la que un rechazo se
     * comunica.
     *
     * ## POR QUE SOLO ESA EXCEPCION, Y NO `catch (e: Exception)`
     *
     * Porque se captura la excepcion UNICA que el contrato de [X25519]
     * declara como resultado esperado ante material remoto, y no una familia
     * entera. Un `catch (e: Exception)` aqui seria un tells-todo: volveria
     * "DH invalido" un fallo de programacion, una clave propia corrupta o un
     * provider mal implementado, y un rechazo con ese motivo afirma algo que
     * no se ha comprobado. Lo que si se puede afirmar con certeza ("este
     * material no produce clave") es exactamente lo que se afirma.
     *
     * La otra excepcion del contrato, `IllegalArgumentException` por
     * longitudes, no puede ocurrir aqui y por eso NO se captura: la clave
     * publica ya ha pasado la comprobacion de los 32 bytes mas abajo en
     * [previewReceive], y la privada sale de [X25519KeyPair], que la exige en
     * su constructor.
     */
    private fun dhRemoteOrNull(
        privateKey: ByteArray,
        headerDhPublicKey: ByteArray,
    ): ByteArray? =
        try {
            dh(privateKey, headerDhPublicKey)
        } catch (e: AllZeroSharedSecretException) {
            null
        }

    // ------------------------------------------------------------------
    // ENVIO
    // ------------------------------------------------------------------

    /** Datos necesarios para construir el header de un envio. */
    class SendPreview internal constructor(
        val dhPublicKey: ByteArray,
        val previousChainLength: UInt,
        val messageNumber: UInt,
        val messageKey: ByteArray,
    )

    /**
     * Inicia una NUEVA epoca de envio (ratchet DH del lado emisor).
     *
     * ## DONDE ESTA LA FRONTERA: solo en el establecimiento
     *
     * Esta operacion replica el PRIMER paso de `KDF_RK` que hara el receptor
     * al ver la DH nueva. Para que las claves coincidan, la raiz desde la que
     * se deriva tiene que ser la MISMA raiz que tiene el receptor. Eso solo es
     * cierto al abrir la sesion: las dos mitades arrancan de la misma `F` y,
     * hasta que circule el primer frame, nadie ha movido su raiz.
     *
     * En cuanto un extremo recibe una DH nueva, [previewReceive] ejecuta DOS
     * pasos de `KDF_RK` —uno para la cadena de recepcion y otro para preparar
     * su propia cadena de envio— y queda UN paso por delante del otro. Desde
     * ese momento las dos raices no vuelven a coincidir, y llamar aqui deriva la
     * cadena de envio desde una raiz que el receptor ya no tiene: el AEAD
     * falla y la sesion queda inservible para quien rotates, sin que su
     * receptor haya hecho nada malo. El guion `EPOCH-13` lo demuestra.
     *
     * Por eso `internal` y no `public`: el ratchet REAL es reactivo (quien ve
     * una DH nueva rota), [RatchetSessionBootstrap] no llama nunca a esta
     * operacion, y la unica llamada legitima es el arranque de los tests, que
     * necesita abrir la primera epoch porque las dos mitades arrancan con una
     * clave de cadena que no procede del DH.
     *
     * NO se anade un `require` que la vete fuera del establecimiento: el veto
     * borraria la unica forma de alcanzar los estados que `PN` describe
     * (`PN-01`, `PN-02`, `PN-03`, `DR-07`, `SNAP-11b`), que son estados REALES
     * del ratchet y que hoy solo se alcanzan por aqui.
     *
     * Consume estado directamente: todavia no hay frame que pueda fallar.
     */
    internal fun initiateEpoch() {
        val remote = dhRemote
            ?: throw IllegalStateException(
                "no se puede iniciar epoca sin conocer la clave publica DH remota"
            )
        val newDhSelf = x25519.generateKeyPair()
        val dhOut = dh(newDhSelf.privateKey, remote)
        val (newRoot, ckSend) = kdfRk(rootKey, dhOut)
        rootKey = newRoot
        dhSelf = newDhSelf
        previousChainLength = sendMessageNumber
        sendMessageNumber = 0u
        sendChain = SymmetricRatchet(ckSend, kdf)
        clearPending()
    }

    /**
     * Prepara un envio SIN consumir estado.
     *
     * Si es el primer envio, genera un nuevo par DH y ejecuta el
     * `DH ratchet` de inicio.
     */
    fun previewSend(): SendPreview {
        val cand = candidate()
        val step = cand.sendChain.previewSend()
        val preview = SendPreview(
            dhPublicKey = cand.dhSelf.publicKey,
            previousChainLength = cand.previousChainLength,
            messageNumber = step.messageNumber,
            messageKey = step.messageKey,
        )
        pending = Candidate(
            rootKey = cand.rootKey,
            dhSelf = cand.dhSelf,
            dhRemote = cand.dhRemote,
            sendMessageNumber = cand.sendMessageNumber,
            previousChainLength = cand.previousChainLength,
            sendChain = cand.sendChain,
            receiveChains = cand.receiveChains,
        )
        // Guardamos la clave que se consumira al confirmar.
        pendingSendChain = cand.sendChain.copyRatchet()
        return preview
    }

    private var pendingSendChain: SymmetricRatchet? = null

    /** Confirma el envio preparado por [previewSend]. */
    fun commitSend(): Boolean {
        val chain = pendingSendChain ?: return false
        chain.commitSend()
        sendChain = chain
        sendMessageNumber = chain.nextSendMessageNumber
        pendingSendChain = null
        pending = null
        return true
    }

    // ------------------------------------------------------------------
    // RECEPCION
    // ------------------------------------------------------------------

    /** Resultado de intentar obtener la clave de un mensaje entrante. */
    sealed class ReceiveResult {
        /** Clave disponible para descifrar. */
        data class Ready(
            val messageKey: ByteArray,
            val fromSkipped: Boolean,
        ) : ReceiveResult()

        /** El mensaje fue rechazado. No se consumio estado. */
        data class Rejected(val reason: RejectReason) : ReceiveResult()
    }

    /**
     * Prepara la recepcion SIN consumir estado.
     *
     * Si la clave publica DH del header NO tiene todavia cadena propia, ejecuta
     * el DH ratchet completo: salta las claves de la cadena anterior hasta
     * `PN`, genera un DH propio nuevo, deriva raiz y cadenas nuevas, y crea la
     * cadena de recepcion de esa epoca.
     *
     * Si la DH YA tiene cadena, el frame se sirve desde ella y NO se toca
     * nada mas: ni la raiz, ni el par DH propio, ni `PN`, ni `Ns`. Ver
     * [receiveChains] y el bloque marcado abajo.
     */
    fun previewReceive(
        headerDhPublicKey: ByteArray,
        previousChainLength: UInt,
        messageNumber: UInt,
    ): ReceiveResult {
        // La DH del header VIENE DEL CABLE: la elige quien envia. Si no puede
        // ser una clave publica X25519, no identifica NINGUNA epoca —ni nueva
        // ni vieja— y por tanto no hay nada que descifrar: es el unico dato de
        // entrada que se puede rechazar mirando solo el material, sin tocar el
        // estado. Se comprueba PRIMERO, antes de `candidate()`, para que el
        // rechazo ni siquiera abra una rama especulativa.
        //
        // Sin esta comprobacion, una DH de longitud distinta llegaria hasta
        // `x25519.agree` y su `require` saltaria hacia el llamante: una
        // entrada hostil convertida en excepcion.
        //
        // LO QUE ESTA COMPROBACION NO CUBRE, Y DONDE SE RESUELVE: una DH de
        // 32 bytes pero de orden pequeno (todo ceros) SI tiene aqui la
        // longitud correcta, asi que pasa de largo. Solo el propio acuerdo
        // puede reconocerla, y ocurre mas abajo, en `dhRemoteOrNull`, que
        // convierte el abort de `agree` en el rechazo
        // `LOW_ORDER_DH_PUBLIC_KEY`. Mismo problema, dos caminos de
        // deteccion: uno decide mirando solo el material y el otro intentando
        // el acuerdo.
        if (headerDhPublicKey.size != DerivedX25519KeyPair.LENGTH) {
            return ReceiveResult.Rejected(RejectReason.REPLAY_OR_UNKNOWN)
        }

        val cand = candidate()
        val chainId = ChainIdentifier(headerDhPublicKey)

        // 1. ¿Esta epoca ya esta ABIERTA?
        //
        // Lo que decide es `receiveChains`, y NO `dhRemote`.
        //
        // `dhRemote` recuerda cual fue la ULTIMA DH vista. No recuerda cuales
        // son las DH que tienen cadena. Son dos cosas distintas, y confundirlas
        // rompia los frames en vuelo de una epoca anterior: su DH no era
        // `dhRemote`, luego se tomaba por una epoca nueva, se ejecutaba el
        // DH ratchet entero —avanzando la raiz y rotando el par DH propio— y
        // se SOBRESCRIBIA la cadena de esa misma DH, perdiendole sus claves
        // saltadas. Dos sintomas, una sola causa: decidir "epoca nueva" sin
        // mirar si esa DH ya tiene cadena.
        //
        // Un frame de una epoca anterior es un frame LEGITIMO: es el que se
        // emitio antes de que el otro extremo rotara, y viaja mas lento que la
        // rotacion. Descartarlo no es ser estricto, es perder lo que el otro
        // extremo ya entrego.
        val epochYaAbierta = cand.receiveChains.containsKey(chainId)
        if (!epochYaAbierta) {
            // a) Saltar las claves de la cadena de recepcion anterior hasta PN.
            cand.dhRemote?.let { oldRemote ->
                val oldChain = cand.receiveChains[ChainIdentifier(oldRemote)]
                if (oldChain != null) {
                    val toSkip = previousChainLength
                    if (oldChain.nextReceiveMessageNumber < toSkip) {
                        val gap = toSkip - oldChain.nextReceiveMessageNumber
                        if (gap > SymmetricRatchetSpec.MAX_SKIP.toUInt()) {
                            return ReceiveResult.Rejected(RejectReason.SKIP_LIMIT_EXCEEDED)
                        }
                        // Avanzar hasta PN reteniendo las intermedias.
                        val out = oldChain.previewReceive(oldRemote, toSkip)
                        if (out is ReceiveOutcome.Rejected) {
                            return ReceiveResult.Rejected(out.reason)
                        }
                        oldChain.commitReceive()
                    }
                }
            }

            // b) Cadena de RECEPION: usa el DH propio ACTUAL.
            //    Es el mismo par que el emisor uso como `dhRemote`, por lo que
            //    ambos derivan el mismo secreto. Rotar aqui antes de tiempo
            //    haria que las claves de cadena no coincidieran.
            //
            //    `dhRemoteOrNull` y no `dh`: la clave publica es del cable y su
            //    longitud ya es correcta, asi que un abort por ORDEN PEQUENO
            //    solo puede salir del propio acuerdo. Sale como rechazo.
            val dhOutRecv = dhRemoteOrNull(cand.dhSelf.privateKey, headerDhPublicKey)
                ?: return ReceiveResult.Rejected(RejectReason.LOW_ORDER_DH_PUBLIC_KEY)
            val (rk1, ckRecv) = kdfRk(cand.rootKey, dhOutRecv)

            // c) Se rota el DH propio para la cadena de ENVIO de la respuesta.
            val replyDhSelf = x25519.generateKeyPair()
            // Mismo motivo, y no una excepcion distinta: es el MISMO material de
            // orden pequeno visto desde el otro extremo del acuerdo.
            val dhOutSend = dhRemoteOrNull(replyDhSelf.privateKey, headerDhPublicKey)
                ?: return ReceiveResult.Rejected(RejectReason.LOW_ORDER_DH_PUBLIC_KEY)
            val (rk2, ckSend) = kdfRk(rk1, dhOutSend)

            cand.rootKey = rk2
            cand.dhSelf = replyDhSelf
            cand.dhRemote = headerDhPublicKey.copyOf()
            cand.previousChainLength = cand.sendMessageNumber
            cand.sendMessageNumber = 0u
            cand.sendChain = SymmetricRatchet(ckSend, kdf)
            // Se ANADE, no se reemplaza. La comprobacion de arriba garantiza
            // que la clave no existe todavia, y por eso abrir una epoca no
            // puede pisar la cadena de una anterior: esa cadena —con su indice
            // de recepcion y sus claves saltadas— es lo unico que puede descifrar
            // un frame de la epoca que quedo en vuelo.
            cand.receiveChains[chainId] = SymmetricRatchet(ckRecv, kdf)
        }

        // 2. Servir la clave del mensaje: en orden o desde skipped.
        val recvChain = cand.receiveChains[chainId]
            ?: return ReceiveResult.Rejected(RejectReason.REPLAY_OR_UNKNOWN)
        val out = recvChain.previewReceive(headerDhPublicKey, messageNumber)
        if (out is ReceiveOutcome.Rejected) {
            return ReceiveResult.Rejected(out.reason)
        }
        val step = (out as? ReceiveOutcome.InOrder)?.step
            ?: (out as ReceiveOutcome.FromSkipped).step

        pending = cand
        pendingReceiveChainId = chainId
        pendingFromSkipped = out is ReceiveOutcome.FromSkipped
        pendingMessageKey = step.messageKey
        return ReceiveResult.Ready(step.messageKey, out is ReceiveOutcome.FromSkipped)
    }

    private var pendingReceiveChainId: ChainIdentifier? = null
    private var pendingFromSkipped: Boolean = false
    private var pendingMessageKey: ByteArray? = null

    /** Confirma la recepcion y adopta el estado candidato. */
    fun commitReceive(): Boolean {
        val cand = pending ?: return false
        val chainId = pendingReceiveChainId ?: return false
        cand.receiveChains[chainId]?.commitReceive()
        adopt(cand)
        clearPending()
        return true
    }

    /** Descarta la recepcion sin tocar el estado. */
    fun discardReceive() {
        clearPending()
    }

    /** Descarta un envio preparado sin avanzar la cadena de envio. */
    fun discardSend() {
        clearPending()
    }

    private fun clearPending() {
        pending = null
        pendingReceiveChainId = null
        pendingFromSkipped = false
        pendingMessageKey = null
        pendingSendChain = null
    }

    // ------------------------------------------------------------------
    // Estado candidato
    // ------------------------------------------------------------------

    /**
     * Copia profunda del estado actual, lista para modificar.
     *
     * Es la clave de la atomicidad: el DH ratchet opera sobre la copia y
     * solo se adopta con [adopt] tras un descifrado exitoso.
     */
    private fun candidate(): Candidate {
        pending?.let { return copyOf(it) }
        val current = Candidate(
            rootKey = rootKey.copyOf(),
            dhSelf = dhSelf,
            dhRemote = dhRemote?.copyOf(),
            sendMessageNumber = sendMessageNumber,
            previousChainLength = previousChainLength,
            sendChain = sendChain.copyRatchet(),
            receiveChains = LinkedHashMap(receiveChains.mapValues { it.value.copyRatchet() }),
        )
        pending = current
        return copyOf(current)
    }

    private fun copyOf(c: Candidate) = Candidate(
        rootKey = c.rootKey.copyOf(),
        dhSelf = c.dhSelf,
        dhRemote = c.dhRemote?.copyOf(),
        sendMessageNumber = c.sendMessageNumber,
        previousChainLength = c.previousChainLength,
        sendChain = c.sendChain.copyRatchet(),
        receiveChains = LinkedHashMap(c.receiveChains.mapValues { it.value.copyRatchet() }),
    )

    private fun adopt(c: Candidate) {
        rootKey = c.rootKey.copyOf()
        dhSelf = c.dhSelf
        dhRemote = c.dhRemote?.copyOf()
        sendMessageNumber = c.sendMessageNumber
        previousChainLength = c.previousChainLength
        sendChain = c.sendChain.copyRatchet()
        receiveChains.clear()
        c.receiveChains.forEach { (k, v) -> receiveChains[k] = v.copyRatchet() }
    }

    // ------------------------------------------------------------------
    // Estado instantaneo (3Q.5.2a)
    // ------------------------------------------------------------------

    /**
     * Toma una foto del estado COMPROMETIDO de la sesion (3Q.5.2a).
     *
     * ## Por que `pending` NO entra en la foto
     *
     * [pending] no es estado de la sesion: es una rama ESPECULATIVA que
     * [previewSend] y [previewReceive] abren y que solo se vuelve estado al
     * confirmar. Dos razones para dejarla fuera, y ninguna es de comodidad:
     *
     *  1. **Un candidato no ha sido autenticado.** El ratchet avanza con el
     *     ratchet DH antes de que el AEAD verifique el frame. Si la foto
     *     recogiera ese avance, un proceso que muriera entre el preview y el
     *     commit dejaria una sesion que ha rotado sin haber descifrado nada:
     *     habria saltado claves que el siguiente frame legitimo necesita.
     *     La foto refleja un estado en el que el ratchet todavia no ha
     *     combinado nada que no haya visto pasar por el AEAD.
     *
     *  2. **Una foto con dos estados es ambigua.** Guardar el comprometido y
     *     el candidato obligaria a que quien la restaure decidiera cual de
     *     los dos es "la sesion". Esa decision no es suya, y equivocarse en
     *     ella rompe la sesion de forma irreversible. Fuera el candidato, la
     *     foto tiene un unico significado: lo que la sesion puede hacer a
     *     partir de aqui.
     *
     * La consecuencia es deliberada y comprobable: tomar una foto despues de
     * un preview sin confirmar equivale a tomarla ANTES del preview. El
     * receptor de un frame no verificado vuelve, tras restaurar, al punto en
     * que estaba, que es el unico punto desde el que el frame legitimo
     * todavia se puede descifrar.
     *
     * Lo que si se guarda es el estado de las cadenas de recepcion de todas
     * las epocas, incluidas sus claves saltadas: eso si es estado ya
     * comprometido y sin el cual los mensajes que llegan fuera de orden
     * dejan de poder descifrarse.
     *
     * FRONTERA (3Q.5.2a): lo unico que esta foto sabe del mundo es material
     * criptografico. No hay ningun campo que describa un mensaje de aplicacion,
     * su confirmacion, su reintento ni por donde viaja. Esa informacion vive
     * en capas superiores, y solo ellas pueden llegar a ella.
     */
    fun snapshot(): DoubleRatchetSnapshot = DoubleRatchetSnapshot(
        rootKey = rootKey.copyOf(),
        dhSelf = DerivedX25519KeyPair.from(dhSelf, x25519),
        dhRemote = dhRemote?.copyOf(),
        sendMessageNumber = sendMessageNumber,
        previousChainLength = previousChainLength,
        sendChain = sendChain.snapshot(),
        receiveChains = receiveChains.map { (id, ratchet) ->
            ReceiveChainSnapshot(id.bytes.copyOf(), ratchet.snapshot())
        },
    )

    /**
     * Restaura la sesion completa desde una foto (3Q.5.2a).
     *
     * SOBREESCRIBE TODO. No fusiona: los contadores, la raiz, el par DH y
     * todas las cadenas de recepcion pasan a ser exactamente los de la foto, y
     * las que sobraban se descartan. Una restauracion que se limitara a
     * escribir encima de lo que ya habia produciria una sesion con estado de
     * dos historias, que descifra lo que le da la gana y se rompe en cuanto
     * las dos dejan de coincidir.
     *
     * Las primitivas (`x25519`, `kdf`) NO son estado: se conservan las que ya
     * tenia esta sesion. Para construir una sesion nueva a partir de una foto
     * existe [Companion.restore], que si las recibe.
     */
    fun restore(s: DoubleRatchetSnapshot) {
        rootKey = s.rootKey.copyOf()
        // La mitad publica sale de la foto YA derivada; `toKeyPair` copia, de
        // modo que la sesion y la foto no comparten ni un byte.
        dhSelf = s.dhSelf.toKeyPair()
        dhRemote = s.dhRemote?.copyOf()
        sendMessageNumber = s.sendMessageNumber
        previousChainLength = s.previousChainLength
        sendChain = ratchetFrom(s.sendChain)
        receiveChains.clear()
        s.receiveChains.forEach {
            receiveChains[ChainIdentifier(it.chainId.copyOf())] = ratchetFrom(it.ratchet)
        }
        // El candidato se descarta: la foto no lo contiene y una rama
        // especulativa de antes no puede sobrevivir a un corte de proceso.
        clearPending()
    }

    /**
     * Ratchet simetrico NUEVO con el estado de una foto.
     *
     * El constructor recibe una cadena vacia a proposito y lo que se copia es
     * el estado: es exactamente lo que hace `copyRatchet`, y evita que una
     * cadena de la sesion y la de la foto compartan un solo array.
     */
    private fun ratchetFrom(s: SymmetricRatchetSnapshot): SymmetricRatchet =
        SymmetricRatchet(ByteArray(0), kdf).also { it.restore(s) }

    companion object {
        /**
         * Construye una sesion nueva a partir de una foto (3Q.5.2a).
         *
         * @param x25519 primitiva de acuerdo. Debe ser la MISMA implementacion
         *   con la que se produjo el estado: `publicKey(sk)` es la operacion
         *   que convierte el escalar guardado en la clave que el ratchet
         *   anuncia, y pertenece a la primitiva, no a este paquete.
         * @param kdf primitiva de derivacion.
         */
        fun restore(
            s: DoubleRatchetSnapshot,
            x25519: X25519,
            kdf: Kdf,
        ): DoubleRatchetSession {
            // Semilla CENTINELA: todo ceros, deliberadamente distinta de
            // cualquier estado real. Su unico proposito es dar un objeto que
            // rellenar; `restore` la sobreescribe entera. No se generan claves
            // aqui a proposito: `restore` no debe poder crear material nuevo,
            // porque la unica clave DH que puede tener la sesion restaurada es
            // la de la foto.
            val seed = DoubleRatchetSession(
                rootKey = ByteArray(RootRatchetSpec.ROOT_KEY_LENGTH),
                dhSelf = X25519KeyPair(
                    ByteArray(DerivedX25519KeyPair.LENGTH),
                    ByteArray(DerivedX25519KeyPair.LENGTH),
                ),
                dhRemote = null,
                sendChainKey = ByteArray(SymmetricRatchetSpec.CHAIN_KEY_LENGTH),
                receiveChainKey = ByteArray(SymmetricRatchetSpec.CHAIN_KEY_LENGTH),
                x25519 = x25519,
                kdf = kdf,
            )
            seed.restore(s)
            return seed
        }
    }

    // ------------------------------------------------------------------
    // Observabilidad
    // ------------------------------------------------------------------

    /** Clave publica DH propia actual. */
    fun selfDhPublicKey(): ByteArray = dhSelf.publicKey.copyOf()

    /** Clave publica DH remota vista por ultima vez. */
    fun remoteDhPublicKey(): ByteArray? = dhRemote?.copyOf()

    /** Indice de envio actual (Ns). */
    fun currentSendMessageNumber(): UInt = sendMessageNumber

    /** Longitud de la cadena de envio anterior (PN). */
    fun currentPreviousChainLength(): UInt = previousChainLength

    /** Numero de cadenas de recepcion vivas (una por epoca DH). */
    fun receiveChainCount(): Int = receiveChains.size

    /** Raiz actual, para verificacion en tests. */
    internal fun currentRootKey(): ByteArray = rootKey.copyOf()

    /**
     * Huella criptografica de TODO el estado de la sesion.
     *
     * Cubre RK, DHs, DHr, Ns, PN y las cadenas de envio/recepcion con sus
     * claves saltadas. Permite demostrar en tests que un frame rechazado no
     * mutó nada del estado.
     */
    fun stateFingerprint(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun put(b: ByteArray) {
            out.write(b.size); out.write(b)
        }
        put(rootKey)
        put(dhSelf.publicKey)
        put(dhRemote ?: ByteArray(0))
        put(sendMessageNumber.toString().toByteArray())
        put(previousChainLength.toString().toByteArray())
        put(sendChain.stateFingerprint())
        // receiveChains se ordena por identificador para estabilidad.
        receiveChains.entries.sortedBy { it.key.toString() }.forEach { (k, v) ->
            put(k.bytes)
            put(v.stateFingerprint())
        }
        return java.security.MessageDigest.getInstance("SHA-256").digest(out.toByteArray())
    }
}
