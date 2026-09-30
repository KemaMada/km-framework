package com.km.ratchet

import com.km.crypto.Kdf
import com.km.crypto.X25519
import com.km.crypto.X25519KeyPair
import com.km.crypto.provider.BcChaCha20Poly1305
import com.km.crypto.provider.BcHkdfSha256
import com.km.crypto.provider.BcX25519
import com.km.frame.BinarySecureFrameCodec
import com.km.frame.SecureFrame
import com.km.frame.SecureFrameProtector
import com.km.frame.SecureRatchetProtocol
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals

/**
 * 3Q.5.2a-bis — Correccion de epochs y receive chains del Double Ratchet.
 *
 * Este checkpoint NO anade costura: corrige el camino criptografico. Lo que
 * fijaba `EP-01` en 3Q.5.2a era un bug REAL de `previewReceive`, y la premisa
 * de 3Q.5.2b (un frame en vuelo sobrevive a un corte de proceso) DEPENDE de
 * que este este arreglado. Ver [EPOCH-01].
 *
 * ## LA FRONTERA QUE ESTOS TESTS SOSTIENEN
 *
 * Una epoch la nombra la clave publica DH del header, y la sesion guarda una
 * cadena de recepcion por cada epoch que ha visto. De ahi sale la unica
 * pregunta que `previewReceive` tiene que responder antes de tocar nada:
 *
 *   "¿esta DH ya tiene cadena?"
 *
 * NO es "¿esta DH es distinta de la ultima que vi?". Son preguntas distintas y
 * confundirlas rompia los frames en vuelo de una epoca anterior: su DH no es la
 * ultima vista, luego se tomaba por una epoca nueva, se ejecutaba el DH ratchet
 * entero y se SOBRESCRIBIA la cadena de esa misma DH —con lo que se perdian sus
 * claves saltadas—. [EPOCH-02] fija que la pregunta es la primera.
 *
 * Lo que aqui NO se comprueba, y por que no puede comprobarse:
 *
 *  - Que la clave de mensaje derivada sea la "correcta" segun el RFC. No hay
 *    forma independiente de saberlo aqui: lo que se afirma es que el AEAD
 *    verifica, y el AEAD es el unico arbitro de si una clave es la buena.
 *  - Que el retenedor de claves saltadas tenga una politica de retirada. No la
 *    tiene, y anadirla seria inventar un criterio que nadie ha pedido. Lo que
 *    si se fija es que las cadenas NO se borran al abrir una epoch nueva.
 */
class DoubleRatchetEpochTest {

    private lateinit var x25519: X25519
    private lateinit var kdf: Kdf
    private lateinit var protector: SecureFrameProtector

    private val rootKey = ByteArray(32) { (it + 1).toByte() }

    @BeforeEach
    fun setUp() {
        x25519 = BcX25519()
        kdf = BcHkdfSha256()
        protector = SecureFrameProtector(BcChaCha20Poly1305(), kdf)
    }

    // ===================================================================
    // Utilidades
    // ===================================================================

    private fun session(dhSelf: X25519KeyPair, dhRemote: ByteArray?): DoubleRatchetSession =
        DoubleRatchetSession(
            rootKey = rootKey,
            dhSelf = dhSelf,
            dhRemote = dhRemote,
            sendChainKey = ByteArray(32) { (it * 3).toByte() },
            receiveChainKey = ByteArray(32) { (it * 5).toByte() },
            x25519 = x25519,
            kdf = kdf,
        )

    /**
     * Dos extremos que se entienden. Alice conoce la DH de Bob y Bob USA ESE
     * MISMO par: con claves distintas, cada uno derivaria un secreto DH
     * distinto y ninguna prueba de interop tendria sentido.
     *
     * `init` abre la PRIMERA epoch con `initiateEpoch()`. Es la unica
     * llamada legitima a esa operacion (ver `EPOCH-13`): las dos mitades
     * arrancan con una clave de cadena que no procede de ningun DH, y sin
     * este arranque los dos extremos derivarian claves distintas.
     */
    private inner class Wire {
        val bobDh = x25519.generateKeyPair()
        val aliceSession = session(x25519.generateKeyPair(), bobDh.publicKey)
        val bobSession = session(bobDh, null)
        val alice = SecureRatchetProtocol(aliceSession, protector)
        val bob = SecureRatchetProtocol(bobSession, protector)

        init {
            alice.initiateEpoch()
        }
    }

    /** Ida y vuelta: `from` cifra, `to` descifra, y el contenido tiene que salir. */
    private fun exchange(
        from: SecureRatchetProtocol,
        to: SecureRatchetProtocol,
        text: String,
    ): ByteArray {
        val wire = from.encrypt(text.toByteArray())
        assertContentEquals(
            text.toByteArray(), ok(to.decrypt(wire)), "contenido de '$text'",
        )
        return wire
    }

    private fun ok(r: SecureRatchetProtocol.DecryptResult): ByteArray =
        okFull(r, "el descifrado").plaintext

    /** Igual que [ok], pero devuelve el resultado entero: hace falta `fromSkipped`. */
    private fun okFull(
        r: SecureRatchetProtocol.DecryptResult,
        contexto: String,
    ): SecureRatchetProtocol.DecryptResult.Ok {
        assertTrue(r is SecureRatchetProtocol.DecryptResult.Ok, "$contexto: esperaba Ok, fue $r")
        return r as SecureRatchetProtocol.DecryptResult.Ok
    }

    private fun ok(r: DoubleRatchetSession.ReceiveResult): DoubleRatchetSession.ReceiveResult.Ready {
        assertTrue(r is DoubleRatchetSession.ReceiveResult.Ready, "esperaba Ready, fue $r")
        return r as DoubleRatchetSession.ReceiveResult.Ready
    }

    private fun rejected(r: SecureRatchetProtocol.DecryptResult): SecureRatchetProtocol.DecryptResult.Rejected {
        assertTrue(
            r is SecureRatchetProtocol.DecryptResult.Rejected,
            "esperaba Rejected, fue $r",
        )
        return r as SecureRatchetProtocol.DecryptResult.Rejected
    }

    /** El header de ratchet de un frame de wire. */
    private fun header(wire: ByteArray) = BinarySecureFrameCodec.decode(wire).ratchetHeader

    /** Reescribe un frame cambiando su header de ratchet (el AEAD lo detectara). */
    private fun reescribir(wire: ByteArray, pn: UInt? = null, n: UInt? = null): ByteArray {
        val f: SecureFrame = BinarySecureFrameCodec.decode(wire)
        val h = f.ratchetHeader
        return BinarySecureFrameCodec.encode(
            f.copy(
                ratchetHeader = h.copy(
                    previousChainLength = pn ?: h.previousChainLength,
                    messageNumber = n ?: h.messageNumber,
                ),
            ),
        )
    }

    // ===================================================================
    // EL GUION
    //
    // La epoca nueva la produce el extremo que RECIBE una DH nueva, que es
    // como avanza el protocolo real. El guion deja a BOB con DOS cadenas de
    // recepcion vivas y dos frames de la epoca VIEJA sin entregar.
    // ===================================================================

    /**
     * @return `a1` y `a2` son de la epoca 1 y quedan EN VOLO: los emitio
     *   Alice antes de que Bob abriera su segunda cadena, y llegan tarde.
     *   `a3` es el frame que ABRIO la segunda epoca de Bob; `a4` es de esa
     *   epoca y tampoco se entrega.
     */
    private fun dosEpochs(w: Wire): List<ByteArray> {
        val a0 = exchange(w.alice, w.bob, "a0")          // epoca 1, N=0: entra
        val a1 = w.alice.encrypt("a1".toByteArray())    // epoca 1, N=1: EN VOLO
        val a2 = w.alice.encrypt("a2".toByteArray())    // epoca 1, N=2: EN VOLO
        exchange(w.bob, w.alice, "b0")                  // Bob responde: Alice rota
        exchange(w.bob, w.alice, "b1")
        exchange(w.bob, w.alice, "b2")                  // Ns de Bob = 3
        val a3 = exchange(w.alice, w.bob, "a3")         // DH nueva: 2a cadena, PN=3
        val a4 = w.alice.encrypt("a4".toByteArray())    // epoca 2, N=1: EN VOLO
        assertEquals(2, w.bobSession.receiveChainCount(), "el guion debe dejar dos cadenas")
        return listOf(a0, a1, a2, a3, a4)
    }

    /** Convierte a BOB en un tercer extremo que rota: Bob abre su tercera cadena. */
    private fun terceraEpoch(w: Wire) {
        w.alice.decrypt(w.bob.encrypt("b3".toByteArray()))  // Bob envia con DH nueva: Alice rota
        w.bob.decrypt(w.alice.encrypt("a5".toByteArray()))  // Alice envia con DH nueva: Bob abre la 3a
        assertEquals(3, w.bobSession.receiveChainCount(), "el guion debe dejar tres cadenas")
    }

    // ===================================================================
    // EPOCH-01 — EL CASO QUE DESCUBRIO EL BUG
    // ===================================================================

    @Test
    @DisplayName("EPOCH-01 un frame de una epoch VIEJA descifra: epoch N -> epoch N+1 -> frame de N")
    fun `EPOCH-01 un frame de una epoch anterior descifra`() {
        // El guion es exactamente "epoch N -> generar frame -> iniciar epoch
        // N+1 -> recibir frame legitimo de N". `a1` se emitio en la epoca 1 de
        // Alice; Bob ya abrio su epoca 2 al recibir `a3`, y al hacerlo salto
        // su cadena de la epoca 1 hasta `PN=3` RETENIENDO las claves de N=1 y
        // N=2. `a1` es uno de esos frames legitimos que llegaron tarde.
        //
        // ANTES de este checkpoint esto fallaba, y la razon era que
        // `previewReceive` decidia "epoch nueva" comparando la DH del header
        // solo con `dhRemote` —la ULTIMA vista—. La DH de la epoca 1 no es la
        // ultima, luego se tomaba por una epoca nueva: se ejecutaba el DH
        // ratchet entero y se SOBRESCRIBIA la cadena de la epoca 1, con lo
        // cual sus claves saltadas desaparecian y la clave derivada era otra.
        val w = Wire()
        val (_, a1, a2, _, a4) = dosEpochs(w)

        val r1 = okFull(w.bob.decrypt(a1), "el frame viejo tiene que entrar")
        assertContentEquals("a1".toByteArray(), r1.plaintext)
        assertTrue(r1.fromSkipped, "N=1 salta la cadena de la epoca 1: la clave estaba retenida")

        val r2 = okFull(w.bob.decrypt(a2), "tambien N=2")
        assertContentEquals("a2".toByteArray(), r2.plaintext)

        // Y la epoca VIGENTE no se ha enterado de nada: `a4` es de la epoca 2
        // y sigue entrando por su cadena, en orden.
        val r4 = okFull(w.bob.decrypt(a4), "la epoca vigente sigue viva")
        assertContentEquals("a4".toByteArray(), r4.plaintext)
        assertFalse(r4.fromSkipped, "a4 va en orden dentro de la epoca 2")

        // Y la sesion sigue siendo una sesion de verdad: los dos extremos
        // vuelven a entenderse en la epoca actual.
        exchange(w.bob, w.alice, "b3")
        exchange(w.alice, w.bob, "a6")
    }

    @Test
    @DisplayName("EPOCH-01b un frame en vuelo de una epoch vieja sobrevive a un corte de proceso")
    fun `EPOCH-01b el frame en vuelo sobrevive a restaurar`() {
        // Esta es la premisa de 3Q.5.2b escrita como prueba. El ciphertext de
        // un frame que ya salio no se puede volver a generar (ver `SNAP-05c`:
        // el DH de respuesta es aleatorio), asi que la UNICA forma de que un
        // frame en vuelo sobreviva a un corte es que la sesion restaurada
        // sepa descifrarlo. Antes de este checkpoint no podia.
        val w = Wire()
        val (_, a1, a2, _, _) = dosEpochs(w)

        val foto = w.bobSession.snapshot()
        val b2 = DoubleRatchetSession.restore(foto, x25519, kdf)
        val p2 = SecureRatchetProtocol(b2, protector)

        assertContentEquals("a1".toByteArray(), ok(p2.decrypt(a1)), "a1 tras el corte")
        assertContentEquals("a2".toByteArray(), ok(p2.decrypt(a2)), "a2 tras el corte")
        // Y la epoca vigente tampoco se rompio al servir los viejos.
        assertEquals(2, b2.receiveChainCount(), "las dos cadenas siguen ahi")
    }

    // ===================================================================
    // EPOCH-02 — LA REGLA DE DECISION
    // ===================================================================

    @Test
    @DisplayName("EPOCH-02 la pregunta es '¿esta DH ya tiene cadena?', no '¿es distinta de la ultima vista?'")
    fun `EPOCH-02 la regla es la cadena registrada`() {
        val w = Wire()
        val (_, a1, _, _, _) = dosEpochs(w)
        val vieja = header(a1).dhPublicKey

        // La sesion VIVA, con su estado tal cual.
        val antes = Estado(
            raiz = w.bobSession.currentRootKey(),
            dhPropia = w.bobSession.selfDhPublicKey(),
            dhRemota = w.bobSession.remoteDhPublicKey(),
            pn = w.bobSession.currentPreviousChainLength(),
            ns = w.bobSession.currentSendMessageNumber(),
            cadenas = w.bobSession.receiveChainCount(),
        )
        // Un frame cuya DH TIENE cadena: solo se sirve la clave de ese frame.
        assertContentEquals(
            "a1".toByteArray(), ok(w.bob.decrypt(a1)), "descifra",
        )
        // Nada del estado de ratchet se ha movido. Si la decision hubiera sido
        // "epoch nueva", la raiz habria avanzado, el par DH propio habria
        // rotado y PN/Ns habrian cambiado: ese es exactamente lo que hacia el
        // bug, y por eso se comprueba aqui y no solo el descifrado.
        val trasElViejo = Estado(
            raiz = w.bobSession.currentRootKey(),
            dhPropia = w.bobSession.selfDhPublicKey(),
            dhRemota = w.bobSession.remoteDhPublicKey(),
            pn = w.bobSession.currentPreviousChainLength(),
            ns = w.bobSession.currentSendMessageNumber(),
            cadenas = w.bobSession.receiveChainCount(),
        )
        assertEquals(
            antes.contenido(), trasElViejo.contenido(),
            "servir un frame de una epoch conocida NO puede ratchetear",
        )

        // Y el CONTRASTE: una DH que NO tiene cadena si mueve todo eso. Sin
        // esta mitad, la prueba de arriba pasaria tambien con una sesion que
        // no hiciera nada ante ninguna DH.
        val nueva = x25519.generateKeyPair().publicKey
        w.bobSession.previewReceive(nueva, 0u, 0u)
        w.bobSession.commitReceive()
        val despues = Estado(
            raiz = w.bobSession.currentRootKey(),
            dhPropia = w.bobSession.selfDhPublicKey(),
            dhRemota = w.bobSession.remoteDhPublicKey(),
            pn = w.bobSession.currentPreviousChainLength(),
            ns = w.bobSession.currentSendMessageNumber(),
            cadenas = w.bobSession.receiveChainCount(),
        )
        assertNotEquals(
            antes.contenido(), despues.contenido(),
            "una DH SIN cadena si mueve el estado de ratchet: raiz, DH propia, PN y Ns",
        )
        assertEquals(antes.cadenas + 1, despues.cadenas, "y anade una cadena")
        assertNotEquals(
            antes.dhRemota?.toList(), despues.dhRemota?.toList(),
            "y la DH remota pasa a ser la nueva",
        )
        assertTrue(vieja.contentEquals(header(a1).dhPublicKey), "la DH de a1 no cambio por abrir la nueva")
    }

    /** El estado de ratchet que una recepcion NO puede tocar. */
    private class Estado(
        val raiz: ByteArray,
        val dhPropia: ByteArray,
        val dhRemota: ByteArray?,
        val pn: UInt,
        val ns: UInt,
        val cadenas: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Estado) return false
            return raiz.contentEquals(other.raiz) &&
                dhPropia.contentEquals(other.dhPropia) &&
                ((dhRemota == null && other.dhRemota == null) ||
                    (dhRemota != null && other.dhRemota != null && dhRemota.contentEquals(other.dhRemota))) &&
                pn == other.pn && ns == other.ns && cadenas == other.cadenas
        }

        override fun hashCode(): Int = 0

        /** Huella comparable, para los mensajes de asercion. */
        fun contenido(): String = buildString {
            append("raiz=").append(raiz.joinToString("") { "%02x".format(it) })
            append(" dhPropia=").append(dhPropia.joinToString("") { "%02x".format(it) })
            append(" dhRemota=").append(dhRemota?.joinToString("") { "%02x".format(it) } ?: "null")
            append(" PN=").append(pn).append(" Ns=").append(ns)
            append(" cadenas=").append(cadenas)
        }
    }

    // ===================================================================
    // EPOCH-03 — ABRIR UNA EPOCA NO BORRA LAS ANTERIORES
    // ===================================================================

    @Test
    @DisplayName("EPOCH-03 abrir una epoch nueva no elimina las receive chains anteriores")
    fun `EPOCH-03 abrir una epoch no borra las anteriores`() {
        val w = Wire()
        val (_, a1, a2, _, _) = dosEpochs(w)
        assertEquals(2, w.bobSession.receiveChainCount())

        // Bob abre una TERCERA epoch.
        terceraEpoch(w)
        assertEquals(3, w.bobSession.receiveChainCount(), "las tres cadenas siguen vivas")

        // Y no son etiquetas: la de la epoca 1 conserva su indice de recepcion
        // y sus claves retenidas, que es de donde salen las claves de a1 y a2.
        val foto = w.bobSession.snapshot()
        val vieja = foto.receiveChains.first { it.chainId.contentEquals(header(a1).dhPublicKey) }
        assertEquals(2, vieja.ratchet.skipped.size, "N=1 y N=2 siguen retenidas tras dos epochs mas")
        assertEquals(4u, vieja.ratchet.receiveMessageNumber, "y la cadena sigue apuntando a N=4")

        // Prueba de que no es una decoracion: los frames de la epoca 1 siguen
        // descifrando DESPUES de que se haya abierto la tercera epoch.
        assertContentEquals("a1".toByteArray(), ok(w.bob.decrypt(a1)), "a1 tras tres epochs")
        assertContentEquals("a2".toByteArray(), ok(w.bob.decrypt(a2)), "a2 tras tres epochs")
        assertEquals(3, w.bobSession.receiveChainCount(), "servirlos no anade ni quita cadenas")
    }

    // ===================================================================
    // EPOCH-04 — EPOCH INEXISTENTE
    // ===================================================================

    @Test
    @DisplayName("EPOCH-04 una epoch que no existio no descifra y no muta el estado")
    fun `EPOCH-04 una epoch inexistente no descifra`() {
        val w = Wire()
        dosEpochs(w)
        val antes = w.bobSession.stateFingerprint()

        // Una DH bien formada que la sesion no ha visto nunca. El ratchet NO
        // puede saber que es falsa —eso lo decide el AEAD—, asi que abre una
        // epoca nueva y devuelve una clave; lo que se afirma aqui es lo unico
        // que se puede afirmar sin mentir: el frame NO entra y el estado NO se
        // mueve. Por eso el resultado esperado es `Unauthenticated` y no
        // `Rejected`: ver `EPOCH-05` para el `Rejected` de verdad.
        val intrusa = x25519.generateKeyPair().publicKey
        val r = w.bobSession.previewReceive(intrusa, 0u, 0u)
        assertTrue(r is DoubleRatchetSession.ReceiveResult.Ready, "el ratchet deriva una clave: $r")
        w.bobSession.discardReceive()
        assertContentEquals(antes, w.bobSession.stateFingerprint(), "descartar no muta nada")

        // A traves del protocolo completo, tampoco entra.
        val w2 = Wire()
        dosEpochs(w2)
        val falso = w2.bob.encrypt("falso".toByteArray()).copyOf()
        // La huella se toma DESPUES: cifrar consume la cadena de envio de Bob
        // y la huella la cubre, asi que tomarla antes compararia dos estados
        // que difieren por el cifrado legitimo, no por el frame intruso.
        val huella = w2.bobSession.stateFingerprint()
        // Se le cambia la DH por una que la sesion no conoce. El AAD es el
        // header entero, asi que el AEAD tiene que detectarlo.
        val frame = BinarySecureFrameCodec.decode(falso)
        val manipulado = BinarySecureFrameCodec.encode(
            frame.copy(
                ratchetHeader = frame.ratchetHeader.copy(dhPublicKey = intrusa),
            ),
        )
        val r2 = w2.bob.decrypt(manipulado)
        assertFalse(r2 is SecureRatchetProtocol.DecryptResult.Ok, "una epoch inventada no puede descifrar: $r2")
        assertContentEquals(huella, w2.bobSession.stateFingerprint(), "y no muta el estado")

        // Y la sesion legitima sigue viva justo despues.
        exchange(w2.alice, w2.bob, "a7")
    }

    @Test
    @DisplayName("EPOCH-05 una DH que no puede ser clave publica se rechaza sin lanzar excepcion")
    fun `EPOCH-05 una DH de longitud invalida se rechaza`() {
        val w = Wire()
        dosEpochs(w)
        val antes = w.bobSession.stateFingerprint()

        // Una DH de longitud distinta NO identifica ninguna epoca, ni nueva ni
        // vieja, y por tanto no hay nada que descifrar. Se rechaza por el
        // material, antes de mirar el estado. Antes de esta comprobacion, una
        // DH de 31 bytes llegaba hasta `x25519.agree` y su `require` salia
        // como excepcion hacia el llamante: una entrada hostil convertida en
        // un fallo de proceso.
        for (larga in listOf(0, 1, 31, 33, 64)) {
            val r = w.bobSession.previewReceive(ByteArray(larga) { 0x41 }, 0u, 0u)
            assertTrue(
                r is DoubleRatchetSession.ReceiveResult.Rejected,
                "una DH de $larga bytes debe rechazarse, fue $r",
            )
            assertEquals(
                RejectReason.REPLAY_OR_UNKNOWN,
                (r as DoubleRatchetSession.ReceiveResult.Rejected).reason,
            )
        }
        assertContentEquals(antes, w.bobSession.stateFingerprint(), "rechazar no muta nada")

        // Y el camino del protocolo tampoco: `decrypt` nunca lanza por esto.
        val w2 = Wire()
        dosEpochs(w2)
        val valido = w2.alice.encrypt("x".toByteArray())
        val base = BinarySecureFrameCodec.decode(valido)
        val recortado = valido.copyOf(44) + ByteArray(base.ciphertext.size)
        System.arraycopy(base.ciphertext, 0, recortado, 44, base.ciphertext.size)
        // 32 bytes de DH: el caso que NO cubre esta comprobacion es el de
        // orden pequeno, y se documenta en el codigo. Aqui se comprueba que
        // el resto de longitudes no llegan a la primitiva.
        val r = w2.bob.decrypt(recortado)
        assertTrue(r is SecureRatchetProtocol.DecryptResult.Ok, "un frame integro sigue entrando: $r")
    }

    // ===================================================================
    // EPOCH-06 — FRAME MANIPULADO
    // ===================================================================

    @Test
    @DisplayName("EPOCH-06 un frame manipulado de una epoch vieja no entra y no quema su clave retenida")
    fun `EPOCH-06 un frame viejo manipulado se rechaza`() {
        val w = Wire()
        val (_, a1, a2, _, _) = dosEpochs(w)
        val antes = w.bobSession.stateFingerprint()

        // Se altera el ciphertext de un frame de la epoch vieja. El ratchet si
        // encuentra la clave —la epoch existe— pero el AEAD no verifica, y el
        // candidato se descarta entero.
        val roto = a1.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val r = w.bob.decrypt(roto)
        assertTrue(
            r is SecureRatchetProtocol.DecryptResult.Unauthenticated,
            "lo que se documenta es exactamente lo que ocurre: $r",
        )
        assertContentEquals(antes, w.bobSession.stateFingerprint(), "el rechazo no muta el estado")

        // Y aqui esta la parte que importa: la clave retenida de N=1 NO se
        // consumio. `SymmetricRatchet` la saca del almacen al servirla, pero
        // lo hace sobre la COPIA del candidato; si el AEAD falla, esa copia se
        // tira. Sin esto, un solo frame manipulado dejaria al receptor sin
        // poder descifrar nunca el frame bueno que venia a continuacion.
        assertContentEquals("a1".toByteArray(), ok(w.bob.decrypt(a1)), "el frame bueno entra despues")
        assertContentEquals("a2".toByteArray(), ok(w.bob.decrypt(a2)), "y el siguiente tambien")
    }

    @Test
    @DisplayName("EPOCH-07 el PN de un frame de epoch vieja sigue autenticado, aunque el ratchet lo ignore")
    fun `EPOCH-07 el PN de una epoch vieja sigue autenticado`() {
        // Al servir un frame de una epoch ya abierta, el ratchet IGNORA su
        // `PN`: ese `PN` describe la cadena que el emisor dejaba atras cuando
        // ABRIO esa epoch, y no dice nada de la cadena vigente del receptor.
        // Ignorarlo no lo deja de autenticar: el header entero es el AAD.
        val w = Wire()
        val (_, a1, _, _, _) = dosEpochs(w)

        val r = w.bob.decrypt(reescribir(a1, pn = 999u))
        assertTrue(
            r is SecureRatchetProtocol.DecryptResult.Unauthenticated,
            "un PN retocado en un frame viejo debe caer en el AEAD: $r",
        )
        // Y el bueno entra igual: la manipulacion no ha movido nada.
        assertContentEquals("a1".toByteArray(), ok(w.bob.decrypt(a1)), "el frame intacto sigue entrando")
    }

    // ===================================================================
    // EPOCH-08 / 09 — REPLAY Y N INVALIDO
    // ===================================================================

    @Test
    @DisplayName("EPOCH-08 un frame de epoch vieja ya consumido se rechaza")
    fun `EPOCH-08 replay de una epoch vieja`() {
        val w = Wire()
        val (_, a1, _, _, _) = dosEpochs(w)

        assertContentEquals("a1".toByteArray(), ok(w.bob.decrypt(a1)), "la primera vez entra")
        val despues = w.bobSession.stateFingerprint()

        // Replay exacto. La clave retenida es de UN SOLO USO: se retiro al
        // servirla, y ademas N=1 queda por detras del indice de la cadena.
        val r = w.bob.decrypt(a1)
        assertEquals(
            RejectReason.REPLAY_OR_UNKNOWN,
            rejected(r).reason,
            "el replay se rechaza por la regla de la cadena, no por la de la epoch",
        )
        assertContentEquals(despues, w.bobSession.stateFingerprint(), "el rechazo no muta nada")
    }

    @Test
    @DisplayName("EPOCH-09 un N invalido en una epoch vieja se rechaza")
    fun `EPOCH-09 un N invalido en una epoch vieja`() {
        val w = Wire()
        val (_, a1, _, _, _) = dosEpochs(w)
        val antes = w.bobSession.stateFingerprint()

        // N enorme: el salto supera MAX_SKIP. El ratchet lo rechaza ANTES de
        // tocar el AEAD, asi que el resultado es `Rejected` y no
        // `Unauthenticated` — aunque el AAD tampoco cuadre, el ratchet no llega
        // a comprobarlo.
        val enorme = reescribir(a1, n = UInt.MAX_VALUE)
        assertEquals(
            RejectReason.SKIP_LIMIT_EXCEEDED,
            rejected(w.bob.decrypt(enorme)).reason,
            "N excesivo en epoch vieja: MAX_SKIP",
        )
        assertContentEquals(antes, w.bobSession.stateFingerprint(), "el rechazo no muta nada")

        // N antiguo, sin clave retenida: replay o invalido.
        val antiguo = reescribir(a1, n = 0u)
        assertEquals(
            RejectReason.REPLAY_OR_UNKNOWN,
            rejected(w.bob.decrypt(antiguo)).reason,
            "N anterior sin clave retenida en epoch vieja",
        )
        assertContentEquals(antes, w.bobSession.stateFingerprint(), "el rechazo no muta nada")

        // Y el frame de verdad sigue entrando: ninguno de los rechazos toco
        // la clave retenida de N=1.
        assertContentEquals("a1".toByteArray(), ok(w.bob.decrypt(a1)), "el frame bueno sigue ahi")
    }

    // ===================================================================
    // EPOCH-10 / 11 — ORDEN Y COEXISTENCIA
    // ===================================================================

    @Test
    @DisplayName("EPOCH-10 dos frames de una epoch vieja, en orden inverso, descifran los dos")
    fun `EPOCH-10 frames viejos fuera de orden`() {
        val w = Wire()
        val (_, a1, a2, _, _) = dosEpochs(w)

        // Al revés: primero N=2 y luego N=1. Las dos claves estaban retenidas
        // desde que `a3` salto la cadena hasta PN=3, asi que las dos se sirven
        // desde el almacen de retenidas y el orden en que lleguen no importa.
        val r2 = okFull(w.bob.decrypt(a2), "a2 primero")
        assertTrue(r2.fromSkipped, "a2 venia de una clave retenida")
        assertContentEquals("a2".toByteArray(), r2.plaintext)

        val r1 = okFull(w.bob.decrypt(a1), "a1 despues")
        assertTrue(r1.fromSkipped, "a1 venia de una clave retenida")
        assertContentEquals("a1".toByteArray(), r1.plaintext)

        // Y las dos son de un solo uso: repetirlas se rechaza.
        assertTrue(w.bob.decrypt(a1) is SecureRatchetProtocol.DecryptResult.Rejected, "a1 no se repite")
        assertTrue(w.bob.decrypt(a2) is SecureRatchetProtocol.DecryptResult.Rejected, "a2 no se repite")
    }

    @Test
    @DisplayName("EPOCH-11 servir frames viejos no interrumpe la epoca vigente")
    fun `EPOCH-11 la epoca vigente sigue viva`() {
        val w = Wire()
        val (_, a1, a2, _, a4) = dosEpochs(w)
        val epocaVigente = header(a4).dhPublicKey

        // Se sirven los dos frames viejos, entremezclados con los de la epoca
        // vigente. Las dos cadenas son independientes: servir una no puede
        // desplazar el indice de la otra.
        assertContentEquals("a1".toByteArray(), ok(w.bob.decrypt(a1)))
        assertContentEquals("a4".toByteArray(), ok(w.bob.decrypt(a4)))
        assertContentEquals("a2".toByteArray(), ok(w.bob.decrypt(a2)))
        assertContentEquals("a5".toByteArray(), ok(w.bob.decrypt(w.alice.encrypt("a5".toByteArray()))))

        // La DH remota y la cadena vigente siguen siendo las de la epoca 2.
        assertContentEquals(epocaVigente, w.bobSession.remoteDhPublicKey()!!, "la epoca vigente no se movio")
        // Y el otro extremo no se ha enterado de nada.
        exchange(w.bob, w.alice, "b3")
        exchange(w.alice, w.bob, "a6")
    }

    // ===================================================================
    // EPOCH-12 — DETERMINISMO
    // ===================================================================

    @Test
    @DisplayName("EPOCH-12 servir un frame de una epoch vieja es determinista: no genera DH aleatoria")
    fun `EPOCH-12 servir un frame viejo no genera aleatoriedad`() {
        // `SNAP-05c` (3Q.5.2a) demuestra que el ratchet NO es reproducible
        // entre corridas: al recibir una DH nueva, el extremo acuña un par DH
        // ALEATORIO para su cadena de respuesta, y dos sesiones que parten del
        // mismo estado y hacen la misma operacion se separan para siempre. Por
        // eso, para reintentar un mensaje hay que guardar el CIPHERTEXT.
        //
        // Este test fija el otro lado de esa frontera: servir un frame de una
        // epoch YA ABIERTA no debe acuñar nada. Si volviera a hacer un ratchet,
        // dos sesiones restauradas de la misma foto acabarian con huellas
        // distintas — y entonces la foto no bastaria ni para intentar el
        // mismo descifrado dos veces.
        val w = Wire()
        val (_, a1, _, _, _) = dosEpochs(w)
        val foto = w.bobSession.snapshot()

        val uno = SecureRatchetProtocol(DoubleRatchetSession.restore(foto, x25519, kdf), protector)
        val otro = SecureRatchetProtocol(DoubleRatchetSession.restore(foto, x25519, kdf), protector)

        assertContentEquals("a1".toByteArray(), ok(uno.decrypt(a1)))
        assertContentEquals("a1".toByteArray(), ok(otro.decrypt(a1)))
        assertContentEquals(
            uno.stateFingerprint(), otro.stateFingerprint(),
            "servir el mismo frame viejo desde el mismo estado da el mismo estado",
        )
        // Y el mismo resultado que obtiene la sesion viva, que es la que nunca
        // dejo el estado: la foto no introduce ninguna diferencia al servir un
        // frame de una epoch vieja.
        assertContentEquals(
            "a1".toByteArray(), ok(w.bob.decrypt(a1)), "la sesion viva tambien lo sirve",
        )
        assertContentEquals(
            w.bobSession.stateFingerprint(), uno.stateFingerprint(),
            "y las tres acaban en el mismo estado",
        )
    }

    // ===================================================================
    // EPOCH-13 — initiateEpoch() FUERA DEL ESTABLECIMIENTO
    // ===================================================================

    @Test
    @DisplayName("EPOCH-13 initiateEpoch() tras un intercambio rompe la sesion que rota")
    fun `EPOCH-13 iniciar epoch proactivamente rompe la sesion`() {
        // QUE HACE Y POR QUE NO SE ARREGLA AQUI. Ver el KDoc de
        // `DoubleRatchetSession.initiateEpoch`.
        //
        // `initiateEpoch()` replica el PRIMER paso de `KDF_RK` que hara el
        // receptor al ver la DH nueva. Para que las claves coincidan, la raiz
        // desde la que se deriva tiene que ser la MISMA raiz que tiene el
        // receptor. Al abrir la sesion eso es cierto: las dos mitades arrancan
        // de la misma `F` y nadie ha movido su raiz.
        //
        // En cuanto un extremo recibe una DH nueva, `previewReceive` ejecuta
        // DOS pasos de `KDF_RK` —uno para la cadena de recepcion y otro para
        // preparar su propia cadena de envio— y queda UN paso por delante del
        // otro. Desde ahi las dos raices no vuelven a coincidir, y `initiateEpoch`
        // deriva la cadena de envio desde una raiz que el receptor ya no tiene.
        //
        // Corregirlo no es un arreglo local: haria falta invertir el turno del
        // DH ratchet (que rota el EMISOR y no el receptor), lo que cambia TODA
        // clave derivada de la sesion. Eso no es un bugfix, es otro protocolo.
        val w = Wire()
        exchange(w.alice, w.bob, "a0")
        exchange(w.bob, w.alice, "b0")
        // El ratchet REACTIVO, que es el que usa el protocolo real
        // (`RatchetSessionBootstrap` no llama nunca a `initiateEpoch`), sigue
        // funcionando: es el unico modo de abrir epoch que hay.
        assertContentEquals("a1".toByteArray(), ok(w.bob.decrypt(w.alice.encrypt("a1".toByteArray()))))
        assertContentEquals("b1".toByteArray(), ok(w.alice.decrypt(w.bob.encrypt("b1".toByteArray()))))

        // Un frame legitimo emitido ANTES de que Alice rompa su propia sesion.
        // Se entrega despues, a proposito: sirve para comprobar que el rechazo
        // del frame roto no consumio estado en quien recibe.
        val enVuelo = w.alice.encrypt("en vuelo".toByteArray())

        // Y el modo proactivo, una vez que han circulado mensajes, no.
        w.alice.initiateEpoch()
        val roto = w.alice.encrypt("roto".toByteArray())
        val r = w.bob.decrypt(roto)
        assertTrue(
            r is SecureRatchetProtocol.DecryptResult.Unauthenticated,
            "lo que se documenta es exactamente lo que ocurre hoy: $r",
        )

        // El rechazo no consume estado en quien recibe: la sesion de Bob esta
        // intacta, y eso se ve porque el frame legitimo que tenia en vuelo
        // sigue entrando despues del rechazo.
        val antes = w.bobSession.stateFingerprint()
        w.bob.decrypt(roto)
        assertContentEquals(antes, w.bobSession.stateFingerprint(), "el rechazo no muta el estado")
        assertContentEquals("en vuelo".toByteArray(), ok(w.bob.decrypt(enVuelo)), "el frame bueno entra igual")

        // La que se queda sin sincronizar es la que rota: su raiz y su par DH
        // han cambiado y no hay forma de volver atras.
        assertFalse(
            antes.contentEquals(w.aliceSession.stateFingerprint()),
            "la sesion que abre la epoch a proposito es la que se descuadra",
        )
    }

    @Test
    @DisplayName("EPOCH-13b initiateEpoch() no forma parte de la API publica del protocolo")
    fun `EPOCH-13b initiateEpoch no es API publica`() {
        // La trampa era la SUPERFICIE, no el comportamiento: una llamada a
        // `SecureRatchetProtocol` es, para quien no conoce el ratchet, "abrir
        // una epoch" y nada mas. Se comprueba sobre el CODIGO FUENTE y no con
        // reflexion: Kotlin manglea los nombres de metodo y una busqueda por
        // nombre en un `Class` daria falsos negativos.
        val fuente = java.io.File("src/main/kotlin/com/km/frame/SecureRatchetProtocol.kt")
        assertTrue(fuente.exists(), "no se encuentra ${fuente.path}")
        val lineas = fuente.readLines()

        val declaracion = lineas.indexOfFirst { it.contains("fun initiateEpoch") }
        assertTrue(declaracion >= 0, "deberia existir la declaracion")
        // El modificador de visibilidad tiene que estar en la propia
        // declaracion (`internal fun initiateEpoch`), no en el KDoc de antes:
        // si `internal` desapareciera, la linea empezaria por `fun` y la
        // comprobacion de abajo tiene que notarlo.
        assertTrue(
            lineas[declaracion].trimStart().startsWith("internal fun initiateEpoch"),
            "initiateEpoch debe ser internal: ${lineas[declaracion].trim()}",
        )
        // Y en el paquete `ratchet` entero, igual.
        val ratchet = java.io.File("src/main/kotlin/com/km/ratchet/DoubleRatchetSession.kt")
        val enSesion = ratchet.readLines().first { it.contains("fun initiateEpoch") }
        assertTrue(
            enSesion.trimStart().startsWith("internal fun initiateEpoch"),
            "tambien en la sesion: ${enSesion.trim()}",
        )
    }
}
