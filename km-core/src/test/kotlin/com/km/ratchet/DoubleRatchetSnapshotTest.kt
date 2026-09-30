package com.km.ratchet

import com.km.crypto.Kdf
import com.km.crypto.X25519
import com.km.crypto.X25519KeyPair
import com.km.crypto.provider.BcChaCha20Poly1305
import com.km.crypto.provider.BcHkdfSha256
import com.km.crypto.provider.BcX25519
import com.km.frame.BinarySecureFrameCodec
import com.km.frame.SecureFrameProtector
import com.km.frame.SecureFrameSpec
import com.km.frame.SecureRatchetProtocol
import com.km.x3dh.BootstrapPrekeys
import com.km.x3dh.InitiatorKeyMaterial
import com.km.x3dh.ResponderKeyMaterial
import com.km.x3dh.X3dh
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertContentEquals

/**
 * 3Q.5.2a — Costura de estado del Double Ratchet: `snapshot()` / `restore()`.
 *
 * Que se comprueba, y por que con estos tests:
 *
 *  - No basta con comparar `stateFingerprint()`. La huella es un hash: una
 *    sesion que no pudiera operar bien puede coincidir con otra que si, y
 *    entonces el test pasa sin haber demostrado nada. La prueba que manda es
 *    el CIFRADO: la sesion restaurada tiene que producir exactamente los
 *    mismos bytes en el cable que habria producido la original.
 *  - `stateFingerprint()` no cubre el escalar DH privado, solo su mitad
 *    publica (asi esta construido hoy, en `DoubleRatchetSession`). Por eso las
 *    pruebas que afirman algo sobre la clave privada comprueban el
 *    SECRETO DERIVADO, no la huella.
 *  - La frontera se audita sobre el CODIGO FUENTE, no con reflexion: Kotlin
 *    manglea los nombres de metodo y una busqueda por nombre en un `Class`
 *    da falsos negativos. Ya ha pasado dos veces en este repositorio.
 *
 * ## UNA LIMITACION DEL RATCHET QUE ESTOS TESTS TIENEN QUE RESPETAR
 *
 * Al integrar la costura se descubrio algo que ya era cierto ANTES de este
 * checkpoint y que no se ha tocado: **`initiateEpoch()` solo es correcto si se
 * llama antes de que circule el primer mensaje**. Despues de un intercambio, el
 * extremo que acaba de recibir una DH nueva ha ejecutado DOS pasos de KDF_RK
 * (uno para la cadena de recepcion y otro para preparar su cadena de envio) y
 * queda UN paso por delante del otro. Si el otro entonces rota por su cuenta,
 * deriva su cadena de envio desde una raiz que el receptor ya no tiene, y el
 * AEAD falla. El guion de `EP-00` fija ese hecho.
 *
 * La razon por la que aparece aqui: la costura tiene que poder probarse sobre
 * las mismas secuencias que usa el resto del repositorio, y esas secuencias
 * hacen avanzar la epoca con el ratchet DH **reactivo** (que es lo que hace el
 * protocolo real: `RatchetSessionBootstrap` no llama nunca a `initiateEpoch()`).
 * Ver [GUION DE EPOCHAS] mas abajo.
 *
 * ## QUE CAMBIO DESPUES (3Q.5.2a-bis)
 *
 * El otro defecto que estos tests descubrían —que un frame de una epoch VIEJA
 * no descifraba— **si se ha corregido**, y por eso `EP-01` cambio de
 * expectativa: afirmaba el comportamiento roto. La correccion esta en
 * `DoubleRatchetSession.previewReceive` y su cobertura esta en
 * `DoubleRatchetEpochTest`. `EP-00` sigue en pie: `initiateEpoch()` se
 * restricting a `internal` y su comportamiento NO se cambio, porque arreglarlo
 * exigiria invertir el turno del DH ratchet.
 */
class DoubleRatchetSnapshotTest {

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
     * Dos sesiones que se entienden: Alice conoce la DH de Bob y Bob usa
     * ESE MISMO par. Con claves distintas, ambos derivarian secretos DH
     * distintos y ninguna prueba de interop Holds.
     */
    private inner class Wire {
        val bobDh = x25519.generateKeyPair()
        val aliceSession = session(x25519.generateKeyPair(), bobDh.publicKey)
        val bobSession = session(bobDh, null)
        val alice = SecureRatchetProtocol(aliceSession, protector)
        val bob = SecureRatchetProtocol(bobSession, protector)

        init {
            // La PRIMERA epoch se inicia siempre antes de enviar. Las dos
            // mitades arrancan con una clave de cadena que no procede del DH
            // (en el bootstrap real sale de X3DH), asi que sin este ratchet los
            // dos extremos derivarian claves distintas y no se entenderian.
            // Es el mismo arranque que usan `DoubleRatchetSessionTest` y
            // `SecureRatchetProtocolTest`.
            alice.initiateEpoch()
        }
    }

    private fun ok(r: SecureRatchetProtocol.DecryptResult): ByteArray {
        assertTrue(r is SecureRatchetProtocol.DecryptResult.Ok, "esperaba Ok, fue $r")
        return (r as SecureRatchetProtocol.DecryptResult.Ok).plaintext
    }

    private fun ok(r: DoubleRatchetSession.ReceiveResult): ByteArray {
        assertTrue(r is DoubleRatchetSession.ReceiveResult.Ready, "esperaba Ready, fue $r")
        return (r as DoubleRatchetSession.ReceiveResult.Ready).messageKey
    }

    /** Ida y vuelta: Alice cifra, Bob descifra, y se comprueba el texto. */
    private fun exchange(
        from: SecureRatchetProtocol,
        to: SecureRatchetProtocol,
        text: String,
    ): ByteArray {
        val wire = from.encrypt(text.toByteArray())
        val r = to.decrypt(wire)
        assertTrue(
            r is SecureRatchetProtocol.DecryptResult.Ok,
            "esperaba Ok en '$text', fue $r",
        )
        assertContentEquals(
            text.toByteArray(),
            (r as SecureRatchetProtocol.DecryptResult.Ok).plaintext,
            "contenido de '$text'",
        )
        return wire
    }

    private fun restore(s: DoubleRatchetSnapshot) = DoubleRatchetSession.restore(s, x25519, kdf)

    /** Par de claves determinista, para guionizar dos corridas iguales. */
    private fun det(seed: Int) = X25519KeyPair(
        ByteArray(32) { ((it + seed) and 0xFF).toByte() },
        x25519.publicKey(ByteArray(32) { ((it + seed) and 0xFF).toByte() }),
    )

    // ===================================================================
    // GUION DE EPOCHAS
    //
    // La epoca nueva la produce el extremo que RECIBE una DH nueva, no el que
    // la provoca por su cuenta. Estos dos ayudantes concentran las dos
    // secuencias que usan casi todos los tests de este archivo, para que la
    // razon de cada paso este escrita una sola vez.
    // ===================================================================

    /**
     * Deja a BOB con DOS cadenas de recepcion vivas (dos epocas DH), `PN = 3`
     * y un frame de la epoca VIEJA sin entregar.
     *
     * Secuencia: Alice envia dos, Bob responde tres (su `Ns` llega a 3), y al
     * siguiente mensaje de Alice —que ya lleva una DH nueva porque Alice roto
     * al recibir `b0`— Bob ejecuta el ratchet DH: `PN` pasa a ser 3 y nace su
     * segunda cadena de recepcion.
     *
     * @return los frames de Alice, en el orden en que se emitieron.
     */
    private fun dosEpochs(w: Wire): List<ByteArray> {
        val a0 = exchange(w.alice, w.bob, "a0")
        val a1 = w.alice.encrypt("a1".toByteArray())   // N=1, epoca 1: RETENIDA
        val a2 = w.alice.encrypt("a2".toByteArray())   // N=2, epoca 1: RETENIDA
        exchange(w.bob, w.alice, "b0")                // Alice rota: nueva DH
        exchange(w.bob, w.alice, "b1")                // Bob: Ns = 2
        exchange(w.bob, w.alice, "b2")                // Bob: Ns = 3
        val a3 = exchange(w.alice, w.bob, "a3")       // DH nueva: 2a cadena, PN = 3
        val a4 = w.alice.encrypt("a4".toByteArray())   // epoca 2, N = 1: PENDIENTE
        return listOf(a0, a1, a2, a3, a4)
    }

    /** Momento de la vida de una sesion de BOB en el que vale la pena parar. */
    private class Momento(val nombre: String, val foto: DoubleRatchetSnapshot)

    /** Recorre la sesion de BOB y va dejando una foto en cada punto distinto. */
    private fun momentosDeBob(w: Wire): List<Momento> {
        val momentos = mutableListOf<Momento>()
        momentos += Momento("recien creada", w.bobSession.snapshot())
        exchange(w.alice, w.bob, "a0")
        momentos += Momento("tras el primer DH ratchet", w.bobSession.snapshot())
        exchange(w.bob, w.alice, "b0")
        momentos += Momento("tras la respuesta de Bob", w.bobSession.snapshot())
        dosEpochs(w)
        momentos += Momento("tras dos epochs", w.bobSession.snapshot())
        return momentos
    }

    /** La cadena de recepcion de una foto cuya DH es [dh]. */
    private fun cadenaDe(foto: DoubleRatchetSnapshot, dh: ByteArray): ReceiveChainSnapshot =
        foto.receiveChains.firstOrNull { it.chainId.contentEquals(dh) }
            ?: fail("la foto no tiene cadena para esa DH: ${foto.receiveChains.size} cadenas")

    // ===================================================================
    // EP-00 / EP-01 — Defectos del ratchet
    //
    // Ninguno de los dos lo introdujo la costura. EP-01 se ha corregido en
    // 3Q.5.2a-bis y su test cambio de expectativa; EP-00 sigue abierto, con la
    // operacion restringida a `internal` y el comportamiento intacto. La
    // cobertura de epochs vive ahora en `DoubleRatchetEpochTest`.
    // ===================================================================

    @Test
    @DisplayName("EP-00 initiateEpoch() tras un intercambio rompe la sesion (limitacion preexistente)")
    fun `EP-00 iniciar epoca proactivemente no funciona`() {
        // Lo que el protocolo real hace, y lo que si funciona: el ratchet DH
        // REACTIVO. Alice rota al recibir la DH nueva de Bob, y su mensaje
        // siguiente entra en Bob sin problema.
        val w = Wire()
        exchange(w.alice, w.bob, "a0")
        exchange(w.bob, w.alice, "b0")
        assertContentEquals(
            "a1".toByteArray(), ok(w.bob.decrypt(w.alice.encrypt("a1".toByteArray()))),
            "el ratchet reactivo funciona",
        )
        assertContentEquals(
            "b1".toByteArray(), ok(w.alice.decrypt(w.bob.encrypt("b1".toByteArray()))),
            "y las dos cadenas de envio siguen vivas",
        )

        // Lo que NO funciona: rotar por cuenta propia cuando ya ha circular un
        // mensaje. Quien acaba de recibir una DH nueva ha hecho DOS pasos de
        // KDF_RK y queda uno por delante; el otro deriva su cadena de envio
        // desde una raiz que el receptor ya no tiene.
        w.alice.initiateEpoch()
        val roto = w.alice.encrypt("roto".toByteArray())
        val r = w.bob.decrypt(roto)
        assertTrue(
            r is SecureRatchetProtocol.DecryptResult.Unauthenticated,
            "lo que se documenta es exactamente lo que ocurre hoy: $r",
        )
        // Y el rechazo no consume estado: la sesion de Bob no se mueve. La de
        // Alice, en cambio, si: `initiateEpoch` le ha cambiado la raiz y la DH
        // propias, y no hay forma de volver atras. Por eso el ratchet reactivo
        // es el unico modo de abrir epoch que el protocolo real usa.
        val antes = w.bobSession.stateFingerprint()
        w.bob.decrypt(roto)
        assertContentEquals(antes, w.bobSession.stateFingerprint(), "el rechazo no muta el estado")
        assertFalse(
            antes.contentEquals(w.aliceSession.stateFingerprint()),
            "la que abre la epoch a proposito es la que se queda sin sincronizar",
        )
    }

    @Test
    @DisplayName("EP-01 un frame de una epoch VIEJA descifra (corregido en 3Q.5.2a-bis)")
    fun `EP-01 frame de una epoch anterior`() {
        // ATENCION: este test CAMBIO DE EXPECTATIVA en 3Q.5.2a-bis, y el motivo
        // hay que dejarlo escrito, porque lo que afirmaba antes era el BUG.
        //
        // AFIRMABA (antes): "un frame de una epoch VIEJA no descifra". Es decir,
        // fijaba como correcto un comportamiento roto.
        //
        // POR QUE ERA EL BUG: un frame que se emitio antes de que el otro
        // extremo rotara es un frame LEGITIMO que viaja mas lento que la
        // rotacion. `previewReceive` lo rechazaba por dos defectos encadenados
        // en la misma decision: (a) tomaba "epoch nueva" por "la DH del header
        // es distinta de `dhRemote`" —la ULTIMA vista—, sin mirar si esa DH ya
        // tenia cadena registrada; y (b) al abrir la "epoch" hacia
        // `receiveChains[DH] = SymmetricRatchet(ckRecv)`, SOBRESCRIBIA la
        // cadena existente de esa misma DH, con lo que se perdian sus claves
        // saltadas. El guion de abajo no llama a `snapshot()` ni a `restore()`
        // en ningun momento: la costura no era la causa, y ahora tampoco lo es.
        //
        // AFIRMA (ahora): el frame de la epoch vieja entra, y ademas el estado
        // de ratchet no se mueve al servirlo. La cobertura completa de este
        // checkpoint esta en `DoubleRatchetEpochTest` (prefijo `EPOCH-`).
        val w = Wire()
        val (_, a1, _, _, _) = dosEpochs(w)
        assertEquals(2, w.bobSession.receiveChainCount(), "el guion debe dejar dos cadenas")

        val r = w.bob.decrypt(a1)
        assertTrue(
            r is SecureRatchetProtocol.DecryptResult.Ok,
            "el frame de la epoch vieja entra: $r",
        )
        assertContentEquals(
            "a1".toByteArray(), (r as SecureRatchetProtocol.DecryptResult.Ok).plaintext,
        )
        // Y la foto se lleva las dos cadenas con sus claves saltadas (SNAP-09).
        assertEquals(2, w.bobSession.snapshot().receiveChains.size)
    }

    // ===================================================================
    // SNAPSHOT-01 — El snapshot contiene TODO el estado privado
    // ===================================================================

    @Test
    @DisplayName("SNAP-01 el snapshot contiene todo el estado privado de la sesion")
    fun `SNAP-01 el snapshot lo tiene todo`() {
        val w = Wire()
        dosEpochs(w)

        val original = w.bobSession
        val foto = original.snapshot()
        val b2 = restore(foto)

        // Raiz.
        assertContentEquals(original.currentRootKey(), b2.currentRootKey(), "raiz")
        // Par DH: la clave publica announce es la derivacion del escalar guardado.
        assertContentEquals(original.selfDhPublicKey(), b2.selfDhPublicKey(), "DH propia")
        assertContentEquals(
            x25519.publicKey(foto.dhSelf.privateKeyBytes()),
            b2.selfDhPublicKey(),
            "el escalar de la foto deriva la misma clave publica",
        )
        // DH remota.
        assertContentEquals(
            original.remoteDhPublicKey()!!, b2.remoteDhPublicKey()!!, "DH remota",
        )
        // Contadores: este guion deja PN = 3 y Ns = 0, no dos ceros de fabrica.
        assertEquals(3u, original.currentPreviousChainLength(), "el guion debe dejar PN = 3")
        assertEquals(0u, original.currentSendMessageNumber(), "el guion debe dejar Ns = 0")
        assertEquals(original.currentSendMessageNumber(), b2.currentSendMessageNumber(), "Ns")
        assertEquals(original.currentPreviousChainLength(), b2.currentPreviousChainLength(), "PN")
        // Historia de epochs.
        assertEquals(2, original.receiveChainCount(), "el guion debe dejar dos cadenas")
        assertEquals(original.receiveChainCount(), b2.receiveChainCount(), "cadenas vivas")
        // Y el estado entero.
        assertContentEquals(original.stateFingerprint(), b2.stateFingerprint(), "estado completo")

        // Un caso mas: la sesion recien creada, donde `dhRemote` es null. Un
        // `null` que se al perder por el camino haria fallar el ratchet entero
        // mas tarde y de forma opaca.
        val limpia = Wire()
        val fotoLimpia = limpia.bobSession.snapshot()
        assertNull(fotoLimpia.dhRemote, "una sesion sin epoch todavia no conoce DH remota")
        assertNull(restore(fotoLimpia).remoteDhPublicKey(), "y se restaura sin DH remota")
    }

    // ===================================================================
    // SNAPSHOT-02 — restore reproduce el estado del momento exacto
    // ===================================================================

    @Test
    @DisplayName("SNAP-02 restore reproduce el estado en cualquier punto de la vida de la sesion")
    fun `SNAP-02 restore en cualquier punto`() {
        val momentos = momentosDeBob(Wire())
        assertEquals(4, momentos.size, "el guion debe recorrer cuatro momentos")

        // Dos restauraciones de una misma foto son el mismo estado...
        for (m in momentos) {
            assertContentEquals(
                restore(m.foto).stateFingerprint(),
                restore(m.foto).stateFingerprint(),
                "dos restauraciones de '${m.nombre}' deben coincidir",
            )
        }
        // Cada momento tiene un estado DISTINTO: si coincidieran, la prueba
        // no distinguiria "restaurar bien" de "restaurar siempre lo mismo".
        val estados = momentos.map { restore(it.foto).stateFingerprint().toList() }
        assertEquals(
            estados.size, estados.distinct().size,
            "cada foto restaura un estado distinto: ${momentos.map { it.nombre }}",
        )
        // Y cada foto restaurada se parece a la sesion en ESE momento, no a
        // otro: la primera foto no puede contener las cadenas de la ultima.
        assertEquals(0, restore(momentos.first().foto).receiveChainCount(), "la primera foto no tiene cadenas")
        assertTrue(restore(momentos.last().foto).receiveChainCount() > 0, "la ultima si")
    }

    // ===================================================================
    // SNAPSHOT-03 — La sesion restaurada produce lo mismo (CIFRADO REAL)
    // ===================================================================

    @Test
    @DisplayName("SNAP-03 la sesion restaurada produce el MISMO ciphertext, byte a byte")
    fun `SNAP-03 mismo ciphertext byte a byte`() {
        // Esta es la prueba central. No se compara una huella: se compara el
        // frame que sale por el cable. Si al restaurar se perdiera la raiz,
        // el par DH, un contador o una cadena, el texto cifrado saldria
        // distinto y el receptor no podria descifrarlo nunca.
        val w = Wire()
        val sessionA = w.aliceSession
        val payload = "contenido que no puede cambiar de bytes".toByteArray()

        val snapshot = sessionA.snapshot()
        val original = w.alice.encrypt(payload)          // A avanza

        val sessionB = restore(snapshot)
        val restaurado = SecureRatchetProtocol(sessionB, protector).encrypt(payload)

        assertContentEquals(original, restaurado, "el ciphertext debe ser el mismo, byte a byte")

        // Y no es una coincidencia de un solo mensaje: el segundo mensaje
        // tambien, con la cadena ya avanzada.
        val original2 = w.alice.encrypt(payload)
        val restaurado2 = SecureRatchetProtocol(sessionB, protector).encrypt(payload)
        assertContentEquals(original2, restaurado2, "tambien el siguiente")
    }

    @Test
    @DisplayName("SNAP-03b el mismo ciphertext sale de previewSend, antes de aplicar el frame")
    fun `SNAP-03b previewSend identico tras restaurar`() {
        // El mismo razonamiento un nivel mas abajo: la clave de mensaje, la DH
        // del header, PN y N tienen que salir identicos, porque de ellos sale
        // el ciphertext.
        val w = Wire()
        val foto = w.aliceSession.snapshot()
        val a = w.aliceSession.previewSend()
        val b = restore(foto).previewSend()

        assertContentEquals(a.dhPublicKey, b.dhPublicKey, "DH del header")
        assertEquals(a.previousChainLength, b.previousChainLength, "PN")
        assertEquals(a.messageNumber, b.messageNumber, "N")
        assertContentEquals(a.messageKey, b.messageKey, "clave de mensaje")
    }

    @Test
    @DisplayName("SNAP-03c el mismo ciphertext sale de una sesion CON historial")
    fun `SNAP-03c mismo ciphertext con cadenas y contadores ya avanzados`() {
        // SNAP-03 usa una sesion recien iniciada, donde casi todo esta a cero y
        // es facil que un estado perdido pase desapercibido. Aqui la foto se
        // toma con dos cadenas de recepcion, PN = 3, claves saltadas y un
        // historial de mensajes ya descifrados: si algo de eso no se guardara,
        // los bytes saldrian distintos aqui.
        val w = Wire()
        dosEpochs(w)
        val foto = w.bobSession.snapshot()

        val original = w.bob.encrypt("desde el medio".toByteArray())
        val restaurado = SecureRatchetProtocol(restore(foto), protector)
            .encrypt("desde el medio".toByteArray())

        assertContentEquals(original, restaurado, "con historia y contadores, el mismo frame")
    }

    // ===================================================================
    // SNAPSHOT-04 — El snapshot no conoce semantica de entrega
    // ===================================================================

    /** Tokens que el ratchet no puede conocer, ni como nombre ni como tipo. */
    private val prohibidos = listOf(
        "messageId", "ack", "delivery", "retransmit", "retry", "transport",
        "pendingMessage", "applicationDelivered", "delivered", "conversation", "peerId",
    )

    private fun archivosDeLaCostura(): List<File> {
        val dir = File("src/main/kotlin/com/km/ratchet")
        assertTrue(dir.exists(), "no se encuentra $dir desde ${File(".").absolutePath}")
        val delPaquete = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        // El paquete `crypto` entero NO se puede auditar aqui: `SignatureVerifier`
        // declara `verifyAck` desde antes de este checkpoint y no tiene nada que
        // ver con la costura. Se audita el archivo que este checkpoint anade.
        val nuevo = File("src/main/kotlin/com/km/crypto/DerivedX25519KeyPair.kt")
        assertTrue(nuevo.exists(), "falta ${nuevo.path}")
        return delPaquete + nuevo
    }

    @Test
    @DisplayName("SNAP-04 el ratchet no nombra ningun concepto de las capas superiores")
    fun `SNAP-04 frontera de nombres`() {
        val ofensas = mutableListOf<String>()
        for (archivo in archivosDeLaCostura()) {
            archivo.readLines().forEachIndexed { i, linea ->
                for (token in prohibidos) {
                    // Limite de palabra: `ack` no puede aparecer dentro de
                    // "package" o "stack", pero si como identificador.
                    if (Regex("(?i)\\b" + Regex.escape(token) + "\\b").containsMatchIn(linea)) {
                        ofensas += "${archivo.name}:${i + 1} contiene '$token' -> ${linea.trim()}"
                    }
                }
            }
        }
        assertTrue(
            ofensas.isEmpty(),
            "la costura del ratchet no puede hablar de las capas superiores:\n" +
                ofensas.joinToString("\n") { "  - $it" },
        )
    }

    @Test
    @DisplayName("SNAP-04b el snapshot declara exactamente los campos del ratchet, ni uno mas")
    fun `SNAP-04b los campos del snapshot son los esperados`() {
        // Un campo de mas no se veria en los tests de cripto, y es
        // precisamente por donde se colaria la semantica de entrega.
        val fuente = File("src/main/kotlin/com/km/ratchet/DoubleRatchetSnapshot.kt")
        val declarados = Regex("""internal val (\w+)""")
            .findAll(fuente.readText())
            .map { it.groupValues[1] }
            .toList()

        assertEquals(
            listOf(
                // DoubleRatchetSnapshot
                "rootKey", "dhSelf", "dhRemote", "sendMessageNumber",
                "previousChainLength", "sendChain", "receiveChains",
                // ReceiveChainSnapshot
                "chainId", "ratchet",
            ),
            declarados,
            "el estado del ratchet es exactamente material criptografico: sin campos extra",
        )
    }

    @Test
    @DisplayName("SNAP-04c la foto no guarda la semantica de las capas superiores")
    fun `SNAP-04c la foto no lleva semantica ajena`() {
        // Comprobacion de contenido y no solo de nombres: lo que la foto lleva
        // son claves y contadores. Ni una referencia, ni un id, ni un
        // indicador de lo que ya se ha hecho con el resultado.
        val w = Wire()
        dosEpochs(w)
        val foto = w.bobSession.snapshot()

        assertContentEquals(foto.rootKey, w.bobSession.currentRootKey(), "raiz")
        assertEquals(foto.sendMessageNumber, w.bobSession.currentSendMessageNumber())
        assertEquals(foto.previousChainLength, w.bobSession.currentPreviousChainLength())
        // Lo unico que se guarda son materiales de longitudes fijas.
        assertEquals(32, foto.rootKey.size)
        assertEquals(32, foto.dhSelf.privateKeyBytes().size)
        assertEquals(32, foto.dhSelf.publicKeyBytes().size)
        assertEquals(32, foto.dhRemote?.size)
        assertEquals(2, foto.receiveChains.size)
        for (cadena in foto.receiveChains) assertEquals(32, cadena.chainId.size)
    }

    // ===================================================================
    // SNAPSHOT-05 — No altera la semántica existente
    // ===================================================================

    /**
     * Una sesion completa X3DH -> Double Ratchet -> SecureFrame, con
     * material determinista, para poder ejecutar el MISMO guion dos veces.
     */
    private inner class Guion(
        val f: ByteArray,
        // El efimero de X3DH se genera aleatorio dentro de `X3dh.initiate`,
        // asi que dos corridas tendrian DHs distintas. Para poder comparar dos
        // corridas, la sesion del emisor usa un efimero inyectado. El receptor
        // nunca ve su mitad privada: solo el efecto en el RootKey y en la
        // cadena inicial, que es lo que se compara.
        val ephemeral: X25519KeyPair = det(9),
    ) {
        val aliceDh = det(1)
        val bobDh = det(2)
        val bobSpk = det(3)
        val bobOpk = det(4)

        val initiator: SecureRatchetProtocol
        val responder: SecureRatchetProtocol
        val initiatorSession: DoubleRatchetSession
        val responderSession: DoubleRatchetSession

        init {
            val x3dh = X3dh(x25519, kdf)
            val bobPrekeys = BootstrapPrekeys(
                deviceId = ByteArray(32) { 1 },
                identityAgreementKey = bobDh.publicKey,
                signedPreKey = bobSpk.publicKey,
                signedPreKeyId = 1L,
                oneTimePreKey = bobOpk.publicKey,
                oneTimePreKeyId = 5L,
            )
            val alicePrekeys = BootstrapPrekeys(
                deviceId = ByteArray(32) { 2 },
                identityAgreementKey = aliceDh.publicKey,
                signedPreKey = aliceDh.publicKey,
                signedPreKeyId = 1L,
            )
            val init = x3dh.initiate(InitiatorKeyMaterial(ByteArray(32), aliceDh), bobPrekeys, f)
            val prep = x3dh.respond(
                ResponderKeyMaterial(ByteArray(32) { 1 }, bobDh, bobSpk, bobOpk, 5L),
                alicePrekeys,
                init.ephemeralPublic,
            )
            assertContentEquals(init.sharedKey, prep.sharedKey, "X3DH debe derivar la misma SK")
            prep.commit()

            // El bootstrap construye una sesion propia y no la expone. Se
            // comprueba que es la MISMA que la que se construye aqui, y a
            // partir de aqui se usa esta: dos sesiones con el mismo estado que
            // avanzan por separado no serian comparables.
            val protocoloDelBootstrap = RatchetSessionBootstrap.initiatorProtocol(
                bootstrapValue = f,
                ephemeral = ephemeral,
                remoteSignedPreKey = bobSpk.publicKey,
                signedPreKeyPrivate = bobSpk.privateKey,
                x25519 = x25519,
                kdf = kdf,
                protector = protector,
            )
            val dhOut = x25519.agree(ephemeral.privateKey, bobSpk.publicKey)
            val derivacion = KdfRk(x25519, kdf).kdfRk(
                RatchetSessionBootstrap.rootKeyFrom(f, kdf), dhOut,
            )
            initiatorSession = DoubleRatchetSession(
                rootKey = derivacion.newRootKey,
                dhSelf = ephemeral,
                dhRemote = bobSpk.publicKey,
                sendChainKey = derivacion.newChainKey,
                receiveChainKey = ByteArray(32),
                x25519 = x25519,
                kdf = kdf,
            )
            initiator = SecureRatchetProtocol(initiatorSession, protector)
            responderSession = DoubleRatchetSession(
                rootKey = RatchetSessionBootstrap.rootKeyFrom(f, kdf),
                dhSelf = bobSpk,
                dhRemote = null,
                sendChainKey = ByteArray(32),
                receiveChainKey = ByteArray(32),
                x25519 = x25519,
                kdf = kdf,
            )
            responder = SecureRatchetProtocol(responderSession, protector)
            assertContentEquals(
                protocoloDelBootstrap.stateFingerprint(),
                initiator.stateFingerprint(),
                "la sesion del bootstrap y esta son la misma",
            )
        }

        /**
         * Un mensaje pasando por la costura, en LOS DOS EXTREMOS.
         *
         * El emisor toma una foto, de ella se construye una sesion nueva (el
         * proceso que "murio" y vuelve), y esa sesion tiene que emitir
         * EXACTAMENTE el mismo frame que emite la sesion viva. Lo mismo en
         * recepcion: la sesion restaurada descifra lo mismo y queda igual.
         *
         * Asi la costura se atraviesa en cada mensaje de una sesion completa,
         * y no solo en un instante suelto.
         */
        fun mensajeConCostura(texto: String, desdeEmisor: Boolean): ByteArray {
            val emisor = if (desdeEmisor) initiatorSession else responderSession
            val receptor = if (desdeEmisor) responderSession else initiatorSession

            // 1. EMISOR: la foto y la sesion viva producen el mismo frame.
            val fotoEmisor = emisor.snapshot()
            val emisorRestaurado = restore(fotoEmisor)
            val porLaCostura = SecureRatchetProtocol(emisorRestaurado, protector)
                .encrypt(texto.toByteArray())
            val sinCostura = SecureRatchetProtocol(emisor, protector).encrypt(texto.toByteArray())
            assertContentEquals(
                porLaCostura, sinCostura,
                "el emisor restaurado tiene que emitir el mismo frame: '$texto'",
            )
            assertContentEquals(
                emisorRestaurado.stateFingerprint(), emisor.stateFingerprint(),
                "y terminar en el mismo estado: '$texto'",
            )

            // 2. RECEPTOR: idem al descifrar.
            val fotoReceptor = receptor.snapshot()
            val receptorRestaurado = restore(fotoReceptor)
            val textoRestaurado = ok(SecureRatchetProtocol(receptorRestaurado, protector).decrypt(sinCostura))
            val textoVivo = ok(SecureRatchetProtocol(receptor, protector).decrypt(sinCostura))
            assertContentEquals(texto.toByteArray(), textoRestaurado, "contenido restaurado de '$texto'")
            assertContentEquals(textoRestaurado, textoVivo, "el receptor restaurado y el vivo coinciden")
            assertContentEquals(
                receptorRestaurado.remoteDhPublicKey()!!, receptor.remoteDhPublicKey()!!,
                "la misma DH remota vista: '$texto'",
            )
            assertEquals(
                receptorRestaurado.receiveChainCount(), receptor.receiveChainCount(),
                "el mismo numero de cadenas: '$texto'",
            )
            return sinCostura
        }
    }

    @Test
    @DisplayName("SNAP-05 snapshot/restore es transparente en TODA una sesion X3DH completa")
    fun `SNAP-05 es transparente`() {
        val f = ByteArray(32) { (it * 7 + 3).toByte() }
        val g = Guion(f)

        // El guion es el de `SecureRatchetProtocolTest` DR-06: la epoca la
        // abre quien recibe la DH nueva, que es como avanza el protocolo real.
        // Cada mensaje se produce y se consume DOS veces —una por la sesion
        // viva y otra por una sesion restaurada desde su foto— y las dos
        // veces tienen que dar los mismos bytes.
        val textos = listOf(
            "m0" to true, "m1" to false, "m2" to true, "m3" to false, "m4" to true,
        )
        for ((texto, desdeEmisor) in textos) {
            g.mensajeConCostura(texto, desdeEmisor)
        }

        // Y la sesion sigue viva y sincronizada: un mensaje mas, por el camino
        // de siempre, entra sin costura.
        assertContentEquals(
            "m5".toByteArray(),
            ok(g.responder.decrypt(g.initiator.encrypt("m5".toByteArray()))),
            "la sesion sigue sirviendo tras cinco costuras",
        )
    }

    @Test
    @DisplayName("SNAP-05c la sesion NO es reproducible entre corridas: el DH de respuesta es aleatorio")
    fun `SNAP-05c no es determinista entre corridas`() {
        // Hecho que condiciona el checkpoint siguiente (3Q.5.2b, persistencia
        // atomica) y que conviene que quede escrito antes de que se intente
        // guardar un estado y "volver a generar el mensaje".
        //
        // Cuando un extremo recibe una DH nueva, `previewReceive` genera un par
        // DH NUEVO para su cadena de respuesta. Ese par es aleatorio, asi que
        // dos sesiones que parten del mismo estado y hacen la MISMA operacion
        // NO producen el mismo estado a la salida cuando esa operacion incluye
        // un ratchet DH.
        //
        // Consecuencia practica: para reintentar un mensaje hay que GUARDAR el
        // ciphertext original. Re-derivarlo es imposible. Es exactamente lo que
        //Ya dejo escrito `DeliverySemanticsAuditTest` A-5, y aqui se
        // demuestra en el otro extremo: la sesion viva cambia de bytes.
        val f = ByteArray(32) { (it * 7 + 3).toByte() }
        val una = Guion(f)
        val otra = Guion(f)
        // El establishment produce el mismo RootKey y la misma cadena inicial
        // en las dos: el material es identico.
        assertContentEquals(
            una.initiator.stateFingerprint(), otra.initiator.stateFingerprint(),
            "el establishment es determinista",
        )
        // Pero en cuanto entra un mensaje con DH nueva, el extremo que
        // responde acuña su DH y las dos sesiones se separan para siempre.
        val w1 = una.initiator.encrypt("x".toByteArray())
        val w2 = otra.initiator.encrypt("x".toByteArray())
        ok(una.responder.decrypt(w1))
        ok(otra.responder.decrypt(w2))
        assertFalse(
            una.responderSession.stateFingerprint()
                .contentEquals(otra.responderSession.stateFingerprint()),
            "las dos receptoras NO convergen: el DH de respuesta es aleatorio",
        )
    }

    @Test
    @DisplayName("SNAP-05b una sesion que nunca toma fotos se comporta igual")
    fun `SNAP-05b no altera la semantica existente`() {
        // `snapshot()`/`restore()` son PUROS ADITIVOS: la sesion completa
        // (X3DH -> Double Ratchet -> SecureFrame) sigue funcionando igual, con
        // el header de 44 bytes y el mismo AAD. Si algo se hubiera roto al
        // tocar el estado, se veria aqui.
        val w = Wire()
        for (i in 0..4) {
            assertContentEquals("a$i".toByteArray(), ok(w.bob.decrypt(w.alice.encrypt("a$i".toByteArray()))))
        }
        for (i in 0..4) {
            assertContentEquals("b$i".toByteArray(), ok(w.alice.decrypt(w.bob.encrypt("b$i".toByteArray()))))
        }
        // Y el wire sigue siendo el SecureFrame v1 CONGELADO: 44 bytes de
        // header (que son el AAD entero) + ciphertext + tag. Un campo mas
        // quedaria fuera de la autenticacion.
        val texto = "x".toByteArray()
        val wire = w.alice.encrypt(texto)
        assertEquals(44, SecureFrameSpec.HEADER_LENGTH, "el header v1 no crece")
        assertEquals(
            SecureFrameSpec.HEADER_LENGTH + texto.size + 16, wire.size,
            "header + ciphertext + tag, sin campos extra",
        )
        val frame = BinarySecureFrameCodec.decode(wire)
        assertEquals(32, frame.ratchetHeader.dhPublicKey.size)
        assertEquals(1, frame.version.toInt())
    }

    // ===================================================================
    // SNAP-06 / 07 — Raiz, clave DH privada, DH remota
    // ===================================================================

    @Test
    @DisplayName("SNAP-06 la clave DH privada sobrevive: el secreto derivado no cambia")
    fun `SNAP-06 sobrevive la clave DH privada`() {
        // La huella del estado NO incluye el escalar privado, solo su mitad
        // publica. Por eso la prueba del escalar no es "las huellas
        // coinciden" sino "el secreto DH derivado es el mismo": si la clave
        // privada se hubiera perdido o cambiado, el receptor derivaria otra
        // clave de cadena y el AEAD fallaria.
        val w = Wire()
        val wire = w.alice.encrypt("a0".toByteArray())   // DH nueva: dispara el ratchet en Bob
        val foto = w.bobSession.snapshot()               // Bob todavia no lo ha visto

        assertContentEquals("a0".toByteArray(), ok(w.bob.decrypt(wire)), "el original descifra")

        val b2 = restore(foto)
        assertContentEquals(
            x25519.publicKey(foto.dhSelf.privateKeyBytes()), b2.selfDhPublicKey(),
            "el escalar de la foto deriva la DH que se anuncia",
        )
        // El restaurado descifra el MISMO frame que el original, partiendo del
        // mismo estado. Eso es lo que demuestra que el escalar sobrevive: la
        // clave de recepcion de la epoca nueva sale de
        // `KDF_RK(rootKey, DH(dhSelf, DH_del_header))`, y `dhSelf` no se puede
        // reconstruir sin el escalar que la foto guarda.
        val r2 = SecureRatchetProtocol(b2, protector).decrypt(wire)
        assertContentEquals("a0".toByteArray(), ok(r2), "el restaurado descifra")
        assertContentEquals(
            w.bobSession.remoteDhPublicKey()!!, b2.remoteDhPublicKey()!!, "DH remota adoptada",
        )
        assertEquals(w.bobSession.receiveChainCount(), b2.receiveChainCount(), "cadenas vivas")
    }

    @Test
    @DisplayName("SNAP-06b la clave de recepcion sale del escalar privado: se deriva la MISMA")
    fun `SNAP-06b la clave derivada depende del escalar privado`() {
        // La prueba directa, y la que separa "guarde la publica" de "guarde el
        // escalar": la clave de recepcion de una epoca nueva sale de
        // `KDF_RK(rootKey, DH(dhSelf, DH_del_header))`. Si `dhSelf` fuese solo
        // su mitad publica —o se regenerase al restaurar— la clave de
        // recepcion seria OTRA, distinta, y el frame no descifraria.
        val w = Wire()
        dosEpochs(w)
        // Bob responde: Alice rota su DH y su siguiente frame abre una epoca
        // nueva para Bob, que es justo el caso en el que se usa `dhSelf`.
        exchange(w.bob, w.alice, "b3")
        val a4 = w.alice.encrypt("a4".toByteArray())
        val h = BinarySecureFrameCodec.decode(a4).ratchetHeader

        val foto = w.bobSession.snapshot()          // Bob aun NO ha visto a4
        val delOriginal = ok(w.bobSession.previewReceive(h.dhPublicKey, h.previousChainLength, h.messageNumber))
        w.bobSession.discardReceive()
        val delRestaurado = ok(restore(foto).previewReceive(h.dhPublicKey, h.previousChainLength, h.messageNumber))

        assertContentEquals(delOriginal, delRestaurado, "la clave sale del escalar de la foto")

        // Control negativo: con OTRO escalar y la MISMA raiz, la clave es otra.
        // Sin esto, la comprobacion de arriba pasaria tambien con una clave
        // regenerada al restaurar.
        val otro = DoubleRatchetSession(
            rootKey = foto.rootKey.copyOf(),
            dhSelf = x25519.generateKeyPair(),     // OTRO escalar
            dhRemote = foto.dhRemote?.copyOf(),
            sendChainKey = foto.sendChain.sendChainKey,
            receiveChainKey = ByteArray(32),
            x25519 = x25519,
            kdf = kdf,
        )
        val conOtro = ok(otro.previewReceive(h.dhPublicKey, h.previousChainLength, h.messageNumber))
        assertFalse(
            delOriginal.contentEquals(conOtro),
            "si cualquier escalar sirviera, la prueba no distinguiria nada",
        )
    }

    @Test
    @DisplayName("SNAP-07 la DH remota sobrevive: se puede seguir haciendo el ratchet DH")
    fun `SNAP-07 sobrevive la DH remota`() {
        val w = Wire()
        dosEpochs(w)

        val foto = w.bobSession.snapshot()
        val b2 = restore(foto)
        assertContentEquals(w.bobSession.remoteDhPublicKey()!!, b2.remoteDhPublicKey()!!)

        // Alice rota su DH al responder, asi que su siguiente frame abre una
        // epoca nueva para Bob: es el caso en el que la sesion necesita saber
        // con quien hace el DH ratchet.
        exchange(w.bob, w.alice, "b3")
        val a4 = w.alice.encrypt("a4".toByteArray())
        val h = BinarySecureFrameCodec.decode(a4).ratchetHeader
        assertFalse(
            h.dhPublicKey.contentEquals(foto.dhRemote!!),
            "el guion debe traer una DH nueva, si no no probaria nada",
        )

        val original = w.bob.decrypt(a4)
        assertTrue(original is SecureRatchetProtocol.DecryptResult.Ok, "el original descifra: $original")

        // El restaurado parte del mismo estado y ve el mismo frame nuevo.
        val b3 = restore(foto)
        val restaurado = SecureRatchetProtocol(b3, protector).decrypt(a4)
        assertTrue(restaurado is SecureRatchetProtocol.DecryptResult.Ok, "el restaurado descifra: $restaurado")
        assertContentEquals(
            "a4".toByteArray(), (restaurado as SecureRatchetProtocol.DecryptResult.Ok).plaintext,
        )
        // Y queda con lo mismo que la original: DH remota adoptada y una
        // cadena de recepcion mas. El resto del estado NO se compara: cada
        // recepcion con DH nueva acuña una DH de respuesta ALEATORIA, asi que
        // las dos sesiones divergen a proposito (ver SNAP-06).
        assertContentEquals(
            w.bobSession.remoteDhPublicKey()!!, b3.remoteDhPublicKey()!!, "DH remota adoptada",
        )
        assertEquals(w.bobSession.receiveChainCount(), b3.receiveChainCount(), "cadenas vivas")
        assertEquals(3, b3.receiveChainCount(), "el guion debe dejar tres cadenas")
    }

    // ===================================================================
    // SNAP-08 — Contadores
    // ===================================================================

    @Test
    @DisplayName("SNAP-08 los contadores Ns y PN sobreviven al frame")
    fun `SNAP-08 sobreviven los contadores`() {
        val w = Wire()
        dosEpochs(w)                                // Bob: PN = 3, Ns = 0

        val foto = w.bobSession.snapshot()
        val b2 = restore(foto)
        assertEquals(0u, b2.currentSendMessageNumber(), "Ns")
        assertEquals(3u, b2.currentPreviousChainLength(), "PN")

        // Lo observable: el header del siguiente frame lleva PN=3 y N=0.
        val wire = SecureRatchetProtocol(b2, protector).encrypt("hola".toByteArray())
        val header = BinarySecureFrameCodec.decode(wire).ratchetHeader
        assertEquals(3u, header.previousChainLength, "PN viaja en el header")
        assertEquals(0u, header.messageNumber, "N en la cadena nueva")

        // Y el MISMO frame que habria emitido la original. Para que la
        // comparacion sea determinista, la original vuelve al mismo estado:
        // `initiateEpoch()` genera una DH aleatoria, asi que dos rotaciones
        // seguidas jamas darian el mismo frame.
        w.bobSession.restore(foto)
        assertContentEquals(
            w.bob.encrypt("hola".toByteArray()), wire,
            "el frame tiene que salir identico",
        )
    }

    // ===================================================================
    // SNAP-09 / 10 — Historia de epochs y claves saltadas
    // ===================================================================

    @Test
    @DisplayName("SNAP-09 la historia de epochs sobrevive, con sus claves saltadas incluidas")
    fun `SNAP-09 sobrevive la historia de epochs`() {
        val w = Wire()
        val (_, a1, a2, _, a4) = dosEpochs(w)
        assertEquals(2, w.bobSession.receiveChainCount(), "el guion debe dejar dos cadenas")

        val foto = w.bobSession.snapshot()
        val b2 = restore(foto)
        assertEquals(2, b2.receiveChainCount(), "las dos cadenas siguen ahi")
        assertContentEquals(w.bobSession.stateFingerprint(), b2.stateFingerprint())

        // La cadena VIEJA no es solo una etiqueta: se lleva sus claves saltadas
        // y su indice de recepcion. Sin ellas, un frame de esa epoch no
        // tendria de donde sacar la clave.
        //
        // Y AHORA (3Q.5.2a-bis) no hace falta suponer nada mas: el frame de la
        // epoch vieja DESCIFRA tras restaurar. Antes de este checkpoint
        // `previewReceive` lo tomaba por una epoca nueva y sobrescribia la
        // cadena, asi que el estado intacto no bastaba para nada; hoy la foto
        // no solo conserva el estado, sino que el estado sabe usarlo. Es la
        // premisa de 3Q.5.2b: un frame en vuelo tiene que sobrevivir a un
        // corte de proceso, y solo sobrevive si se puede descifrar despues.
        val vieja = cadenaDe(foto, BinarySecureFrameCodec.decode(a1).ratchetHeader.dhPublicKey)
        assertEquals(2, vieja.ratchet.skipped.size, "N=1 y N=2 quedaron retenidas en la epoch vieja")
        assertEquals(4u, vieja.ratchet.receiveMessageNumber, "la cadena vieja apunta a N=4")
        val restaurada = cadenaDe(b2.snapshot(), vieja.chainId)
        assertEquals(vieja.ratchet.skipped.size, restaurada.ratchet.skipped.size)
        assertEquals(
            vieja.ratchet.skipped.keys.sortedBy { it.second },
            restaurada.ratchet.skipped.keys.sortedBy { it.second },
            "los pares (cadena, N) retenidos son los mismos",
        )
        for ((k, v) in vieja.ratchet.skipped) {
            assertContentEquals(
                v, restaurada.ratchet.skipped[k], "la clave retenida de N=${k.second}",
            )
        }

        // Y la epoch CORRIENTE sigue sirviendo con normalidad tras restaurar.
        val p2 = SecureRatchetProtocol(restore(foto), protector)
        assertContentEquals("a4".toByteArray(), ok(p2.decrypt(a4)), "la epoch vigente sigue viva")
    }

    @Test
    @DisplayName("SNAP-09b el frame de la epoch VIEJA descifra tras restaurar, no solo con la epoch nueva")
    fun `SNAP-09b la epoch vieja descifra tras restaurar`() {
        // SNAP-09 demuestra que el ESTADO de la epoch vieja sobrevive a la
        // foto. Este demuestra lo que faltaba: que ese estado SIRVE para algo.
        // Antes de 3Q.5.2a-bis era imposible afirmarlo —el propio `SNAP-09`
        // decia que "que ese frame llegue a descifrar hoy es OTRO asunto, ver
        // EP-01"—, porque al restaurar, `receive()` miraba solo la epoch
        // vigente y rechazaba un frame legitimo de una epoch anterior. Y si un
        // frame en vuelo no sobrevive a un corte de proceso, la costura no
        // cumple la premisa de 3Q.5.2b, por muy bien guardada que este.
        val w = Wire()
        val (_, a1, a2, _, a4) = dosEpochs(w)
        assertEquals(2, w.bobSession.receiveChainCount())

        val foto = w.bobSession.snapshot()
        val b2 = restore(foto)
        val p2 = SecureRatchetProtocol(b2, protector)

        // Los dos frames de la epoca vieja, que se emitieron antes de que Bob
        // abriera su segunda cadena y que nunca se le entregaron.
        val r1 = p2.decrypt(a1)
        assertTrue(r1 is SecureRatchetProtocol.DecryptResult.Ok, "a1 tras restaurar: $r1")
        assertTrue((r1 as SecureRatchetProtocol.DecryptResult.Ok).fromSkipped, "a1 venia de una clave retenida")
        assertContentEquals("a1".toByteArray(), r1.plaintext)
        val r2 = p2.decrypt(a2)
        assertTrue(r2 is SecureRatchetProtocol.DecryptResult.Ok, "a2 tras restaurar: $r2")
        assertContentEquals("a2".toByteArray(), (r2 as SecureRatchetProtocol.DecryptResult.Ok).plaintext)

        // Y la vigente tampoco se ha enterado: sigue admitiendo lo suyo, y las
        // dos cadenas siguen ahi — servir los viejos no movio la otra.
        assertContentEquals(
            "a4".toByteArray(), ok(p2.decrypt(a4)), "la epoch vigente sigue viva tras los viejos",
        )
        assertEquals(2, b2.receiveChainCount(), "las dos cadenas siguen tras servir los viejos")
    }

    @Test
    @DisplayName("SNAP-10 las claves saltadas sobreviven: el mensaje fuera de orden se descifra")
    fun `SNAP-10 sobreviven las claves saltadas`() {
        val w = Wire()
        exchange(w.alice, w.bob, "m0")        // N=0, en orden
        val m1 = w.alice.encrypt("m1".toByteArray())
        val m2 = w.alice.encrypt("m2".toByteArray())
        val m3 = w.alice.encrypt("m3".toByteArray())

        // Fuera de orden: al recibir N=3 la cadena avanza y RETIENE m1 y m2.
        // OJO con la semantica de `fromSkipped`: significa "la clave salio
        // de una clave retenida", no "llego tarde". m3 es la que abre el
        // hueco, asi que se sirve EN ORDEN; m1 y m2 son las que luego salen
        // de retenidas. Es lo que fija `SecureRatchetProtocolTest` DR-09.
        val r3 = w.bob.decrypt(m3)
        assertTrue(r3 is SecureRatchetProtocol.DecryptResult.Ok)
        assertFalse((r3 as SecureRatchetProtocol.DecryptResult.Ok).fromSkipped, "m3 abre el hueco: en orden")

        val foto = w.bobSession.snapshot()
        val p2 = SecureRatchetProtocol(restore(foto), protector)

        // Tras recibir N=3, la cadena esta en 4: m1 y m2 NO se pueden volver
        // a derivar. Solo se descifran si las claves saltadas viajaron.
        val r1 = p2.decrypt(m1)
        assertTrue(r1 is SecureRatchetProtocol.DecryptResult.Ok, "esperaba Ok, fue $r1")
        assertTrue((r1 as SecureRatchetProtocol.DecryptResult.Ok).fromSkipped, "m1 venia de una clave saltada")
        assertContentEquals("m1".toByteArray(), r1.plaintext)
        val r2 = p2.decrypt(m2)
        assertTrue(r2 is SecureRatchetProtocol.DecryptResult.Ok, "esperaba Ok, fue $r2")
        assertTrue((r2 as SecureRatchetProtocol.DecryptResult.Ok).fromSkipped, "m2 venia de una clave saltada")
        assertContentEquals("m2".toByteArray(), r2.plaintext)

        // Y las claves saltadas siguen siendo de UN SOLO USO tras restaurar.
        val repetido = p2.decrypt(m1)
        assertTrue(repetido is SecureRatchetProtocol.DecryptResult.Rejected, "una clave de un solo uso no se repite")
    }

    // ===================================================================
    // SNAP-11 — initiateEpoch() a medio camino
    // ===================================================================

    @Test
    @DisplayName("SNAP-11 la sesion restaurada justo despues de iniciar epoch sigue operativa")
    fun `SNAP-11 a mitad de una epoca`() {
        // "A medio camino" = la epoch ya esta abierta (raiz y cadena de envio
        // nuevas, `Ns = 0`) pero todavia no ha salido ningun mensaje por ella.
        // `Wire()` deja exactamente ese estado: su `init` llama a
        // `initiateEpoch()` y no envia nada.
        val w = Wire()
        assertEquals(0u, w.aliceSession.currentSendMessageNumber(), "cadena nueva en cero")

        val fotoAlice = w.aliceSession.snapshot()
        val fotoBob = w.bobSession.snapshot()      // Bob intacto: no ha visto nada

        val a1 = exchange(w.alice, w.bob, "a0")            // el original sigue
        // ...y la sesion restaurada de esa foto produce EXACTAMENTE el mismo
        // frame: misma DH en el header, mismo PN, mismo N, mismos bytes.
        val alice1 = restore(fotoAlice)
        val a2 = SecureRatchetProtocol(alice1, protector).encrypt("a0".toByteArray())
        assertContentEquals(a1, a2, "el mismo instante, el mismo frame")
        val h = BinarySecureFrameCodec.decode(a1).ratchetHeader
        assertContentEquals(w.aliceSession.selfDhPublicKey(), h.dhPublicKey, "la DH de la epoch abierta")
        assertEquals(0u, h.previousChainLength, "PN de una epoch recien abierta")
        assertEquals(0u, h.messageNumber, "N del primer mensaje de la cadena")

        // Y la conversacion sigue, con los dos extremos restaurados: Bob desde
        // su foto (anterior a a1) acepta a1, y Alice desde la foto de a1 emite
        // el siguiente con N = 1. El receptor no nota el corte.
        val bob1 = restore(fotoBob)
        assertContentEquals("a0".toByteArray(), ok(SecureRatchetProtocol(bob1, protector).decrypt(a1)))
        val alice2 = restore(alice1.snapshot())
        val bob2 = restore(bob1.snapshot())
        val a3 = SecureRatchetProtocol(alice2, protector).encrypt("a1".toByteArray())
        assertEquals(1u, BinarySecureFrameCodec.decode(a3).ratchetHeader.messageNumber, "N=1")
        assertContentEquals(
            "a1".toByteArray(),
            ok(SecureRatchetProtocol(restore(bob2.snapshot()), protector).decrypt(a3)),
        )
    }

    @Test
    @DisplayName("SNAP-11b el PN de una epoch iniciada despues de enviar sobrevive al frame")
    fun `SNAP-11b PN de una epoca a medio camino`() {
        val w = Wire()
        exchange(w.alice, w.bob, "a0")
        exchange(w.bob, w.alice, "b0")
        exchange(w.bob, w.alice, "b1")
        // Bob abre una epoch con PN = 2 y `Ns = 0`, y NO envia todavia. Es
        // el "a medio camino" con PN distinto de cero.
        w.bob.initiateEpoch()
        assertEquals(2u, w.bobSession.currentPreviousChainLength(), "PN = 2")
        assertEquals(0u, w.bobSession.currentSendMessageNumber(), "Ns = 0")

        val foto = w.bobSession.snapshot()
        val b2 = restore(foto)
        assertEquals(w.bobSession.currentPreviousChainLength(), b2.currentPreviousChainLength())

        // La comparacion es a nivel de `previewSend`, no de frame: `b2` y la
        // sesion original tendrian DH propia distinta si las dos rotaran (esa
        // DH es aleatoria), pero partiendo de la MISMA foto la operacion
        // completa tiene que salir identica. Header y clave de mensaje.
        val original = w.bobSession.previewSend()
        val restaurado = b2.previewSend()
        assertContentEquals(original.dhPublicKey, restaurado.dhPublicKey, "DH del header")
        assertEquals(original.previousChainLength, restaurado.previousChainLength, "PN")
        assertEquals(original.messageNumber, restaurado.messageNumber, "N")
        assertContentEquals(original.messageKey, restaurado.messageKey, "clave de mensaje")
        // Y lo que sale de ahi es un frame con PN = 2 y N = 0.
        w.bobSession.discardSend()
        b2.discardSend()
        val wire = SecureRatchetProtocol(b2, protector).encrypt("mitad".toByteArray())
        val header = BinarySecureFrameCodec.decode(wire).ratchetHeader
        assertEquals(2u, header.previousChainLength)
        assertEquals(0u, header.messageNumber)
    }

    // ===================================================================
    // SNAP-12 — El candidato NO es estado
    // ===================================================================

    @Test
    @DisplayName("SNAP-12 la foto excluye el candidato sin confirmar: es el estado comprometido")
    fun `SNAP-12 la foto excluye el candidato`() {
        val w = Wire()
        val antes = w.bobSession.stateFingerprint()
        assertEquals(0, w.bobSession.receiveChainCount(), "Bob aun no tiene cadenas")

        // Preview de una epoca nueva: el candidato YA ha ejecutado el ratchet
        // DH, pero nada se ha confirmado todavia.
        val preparado = w.bobSession.previewReceive(w.aliceSession.selfDhPublicKey(), 0u, 0u)
        ok(preparado)

        val foto = w.bobSession.snapshot()
        val b2 = restore(foto)

        assertContentEquals(
            antes, b2.stateFingerprint(),
            "la foto es el estado COMPROMETIDO, no el candidato sin verificar",
        )
        assertEquals(0, b2.receiveChainCount(), "el candidato no se restaura")
        assertFalse(b2.commitReceive(), "no hay nada que confirmar tras restaurar")
        w.bobSession.discardReceive()

        // Y el frame legitimo sigue descifrando: volver atras no ha roto nada.
        assertContentEquals(
            "a0".toByteArray(),
            ok(w.bob.decrypt(w.alice.encrypt("a0".toByteArray()))),
            "el mensaje de verdad sigue entrando",
        )
    }

    @Test
    @DisplayName("SNAP-12b un envio preparado a medias tampoco se restaura")
    fun `SNAP-12b el envio a medias tampoco`() {
        val w = Wire()
        w.aliceSession.previewSend()          // pendiente, sin confirmar
        val foto = w.aliceSession.snapshot()
        val a2 = restore(foto)
        assertFalse(a2.commitSend(), "no hay envio pendiente que confirmar")
        // Y produce exactamente el mismo frame que el original.
        w.aliceSession.discardSend()
        assertContentEquals(
            w.alice.encrypt("igual".toByteArray()),
            SecureRatchetProtocol(a2, protector).encrypt("igual".toByteArray()),
        )
    }

    @Test
    @DisplayName("SNAP-12c restore sobre una sesion con candidato en curso lo descarta")
    fun `SNAP-12c restore descarta el candidato`() {
        // El caso de la foto (`SNAP-12`) es "fotografiar con el candidato en
        // curso". Este es el otro: "RESTAURAR con el candidato en curso", que
        // es lo que pasaria si el proceso viviera lo suficiente para hacer un
        // `previewReceive` y luego alguien decidiera volver a un estado ya
        // comprometido. Si el candidato sobreviviera, un `commitReceive()`
        // posterior adoptaria una rama especulativa —de un ratchet DH que el
        // AEAD todavia no ha verificado— por encima del estado restaurado.
        val w = Wire()
        exchange(w.alice, w.bob, "a0")
        exchange(w.bob, w.alice, "b0")            // Alice rota: su DH siguiente es nueva
        ok(w.bobSession.previewReceive(w.aliceSession.selfDhPublicKey(), 0u, 0u))
        val comprometido = w.bobSession.stateFingerprint()

        val foto = w.bobSession.snapshot()       // el estado COMPROMETIDO
        w.bobSession.restore(foto)

        assertFalse(w.bobSession.commitReceive(), "el candidato no sobrevive a la restauracion")
        assertFalse(w.bobSession.commitSend(), "ni el de envio")
        assertContentEquals(
            comprometido, w.bobSession.stateFingerprint(),
            "el estado es exactamente el comprometido",
        )
        // Y el frame legitimo entra como si el preview no hubiera pasado.
        w.bobSession.discardReceive()
        assertContentEquals(
            "a1".toByteArray(), ok(w.bob.decrypt(w.alice.encrypt("a1".toByteArray()))),
            "el mensaje de verdad sigue entrando",
        )
    }

    // ===================================================================
    // SNAP-13 — restore SOBREESCRIBE todo
    // ===================================================================

    @Test
    @DisplayName("SNAP-13 restore sobreescribe todo el estado previo, no lo mezcla")
    fun `SNAP-13 restore no mezcla`() {
        val w = Wire()
        val fotoPristina = w.bobSession.snapshot()
        val huellaPristina = restore(fotoPristina).stateFingerprint()

        dosEpochs(w)                    // Bob queda avanzado: 2 cadenas, PN=3, raiz
        assertEquals(2, w.bobSession.receiveChainCount(), "el guion debe avanzar la sesion")
        assertFalse(
            huellaPristina.contentEquals(w.bobSession.stateFingerprint()),
            "si el estado avanzado fuera igual al pristino, la prueba no diria nada",
        )

        // Encima de todo eso se restaura una foto de un estado MUY distinto.
        w.bobSession.restore(fotoPristina)

        assertEquals(0, w.bobSession.receiveChainCount(), "las cadenas previas deben desaparecer")
        assertEquals(0u, w.bobSession.currentSendMessageNumber(), "Ns vuelve al de la foto")
        assertEquals(0u, w.bobSession.currentPreviousChainLength(), "PN vuelve al de la foto")
        assertContentEquals(
            huellaPristina, w.bobSession.stateFingerprint(),
            "el estado es EXACTAMENTE el de la foto, ni una mezcla de los dos",
        )
    }

    @Test
    @DisplayName("SNAP-13b el estado de la foto se aplica encima del estado que hubiera")
    fun `SNAP-13b el estado restaurado se aplica entero`() {
        // Direccion contraria: la foto AVANZADA encima de una sesion limpia
        // tiene que traerlo todo, incluidas las cadenas de recepcion.
        val avanz = Wire()
        dosEpochs(avanz)
        val foto = avanz.bobSession.snapshot()

        val limpio = Wire()
        val huellaLimpia = limpio.bobSession.stateFingerprint()
        limpio.bobSession.restore(foto)

        assertEquals(2, limpio.bobSession.receiveChainCount(), "las cadenas de la foto llegan")
        assertEquals(3u, limpio.bobSession.currentPreviousChainLength(), "PN de la foto llega")
        assertContentEquals(
            avanz.bobSession.stateFingerprint(), limpio.bobSession.stateFingerprint(),
            "y el estado es identico al de la foto, no al que ya tenia",
        )
        assertFalse(huellaLimpia.contentEquals(limpio.bobSession.stateFingerprint()))
    }

    // ===================================================================
    // SNAP-14 — Copia profunda
    // ===================================================================

    @Test
    @DisplayName("SNAP-14 la foto y la sesion no comparten memoria")
    fun `SNAP-14 sin memoria compartida`() {
        val w = Wire()
        dosEpochs(w)
        val foto = w.bobSession.snapshot()
        val huellaOriginal = w.bobSession.stateFingerprint()

        val b2 = restore(foto)
        val b3 = restore(foto)

        // Dos restauraciones del mismo estado son el mismo estado...
        assertContentEquals(b2.stateFingerprint(), b3.stateFingerprint(), "mismo estado de partida")

        // ...y avanzar una no mueve a la otra. `initiateEpoch()` es la via mas
        // corta para mover el estado de BOB sin que ningun otro extremo se
        // entere: cambia raiz, par DH, PN, Ns y cadena de envio de golpe.
        b2.initiateEpoch()
        assertFalse(
            huellaOriginal.contentEquals(b2.stateFingerprint()), "avanzar cambia su estado",
        )
        assertContentEquals(huellaOriginal, b3.stateFingerprint(), "y no toca a la otra restauracion")
        assertContentEquals(huellaOriginal, w.bobSession.stateFingerprint(), "ni a la sesion original")

        // La foto sigue restaurando el estado que captures, despues de que
        // las sesiones Restore se hayan movido: no hay arrays compartidos.
        assertContentEquals(huellaOriginal, restore(foto).stateFingerprint(), "la foto no se movio")

        // Y al reves: tocar la foto no cambia la sesion ya restaurada.
        val foto2 = w.bobSession.snapshot()
        val b4 = restore(foto2)
        val huella4 = b4.stateFingerprint()
        foto2.rootKey[0] = (foto2.rootKey[0] + 1).toByte()
        foto2.receiveChains.forEach { it.chainId[0] = (it.chainId[0] + 1).toByte() }
        assertContentEquals(huella4, b4.stateFingerprint(), "la sesion ya no depende de la foto")

        // DONDE se hace la copia importa, y esta comprobacion la fija en el
        // sitio correcto: en el CONSTRUCTOR de la foto, no en el codigo que la
        // construye. Se pasa uns arrays propios, se los destruye, y la foto
        // sigue intacta. Asi, aunque `snapshot()` dejara de copiar, el tipo
        // seguiria garantizando que la foto y la sesion no comparten un byte.
        val root = ByteArray(32) { 7 }
        val remoto = ByteArray(32) { 9 }
        val propia = DoubleRatchetSnapshot(
            rootKey = root,
            dhSelf = foto.dhSelf,
            dhRemote = remoto,
            sendMessageNumber = foto.sendMessageNumber,
            previousChainLength = foto.previousChainLength,
            sendChain = foto.sendChain,
            receiveChains = foto.receiveChains,
        )
        root.fill(0)
        remoto.fill(0)
        assertContentEquals(ByteArray(32) { 7 }, propia.rootKey, "la foto copio la raiz")
        assertContentEquals(ByteArray(32) { 9 }, propia.dhRemote, "la foto copio la DH remota")
    }

    // ===================================================================
    // SNAP-15 — Validacion de una foto mal formada
    // ===================================================================

    @Test
    @DisplayName("SNAP-15 una foto mal formada se rechaza al construirla")
    fun `SNAP-15 valida la foto`() {
        val w = Wire()
        dosEpochs(w)
        val buena = w.bobSession.snapshot()
        assertTrue(buena.receiveChains.isNotEmpty(), "el guion debe dejar cadenas que repetir")
        fun buenaComo(
            rootKey: ByteArray = buena.rootKey,
            dhRemote: ByteArray? = buena.dhRemote,
            receiveChains: List<ReceiveChainSnapshot> = buena.receiveChains,
        ) = DoubleRatchetSnapshot(
            rootKey = rootKey,
            dhSelf = buena.dhSelf,
            dhRemote = dhRemote,
            sendMessageNumber = buena.sendMessageNumber,
            previousChainLength = buena.previousChainLength,
            sendChain = buena.sendChain,
            receiveChains = receiveChains,
        )

        // Longitudes de clave.
        assertThrows<IllegalArgumentException> { buenaComo(rootKey = ByteArray(31)) }
        assertThrows<IllegalArgumentException> { buenaComo(dhRemote = ByteArray(31)) }
        // Dos cadenas con la MISMA clave publica DH.
        val repetida = ReceiveChainSnapshot(buena.receiveChains.first().chainId, buena.sendChain)
        assertThrows<IllegalArgumentException> {
            buenaComo(receiveChains = listOf(repetida, repetida))
        }
    }

    // ===================================================================
    // SNAP-17 — Ns esta escrito dos veces: la foto no puede contradecirse
    // ===================================================================

    @Test
    @DisplayName("SNAP-17 la foto no puede llevar un Ns distinto del de su cadena de envio")
    fun `SNAP-17 Ns no esta duplicado`() {
        // `Ns` vive en DOS sitios del estado: como campo de la sesion y dentro
        // de la cadena de envio. `commitSend` los fija juntos, asi que en una
        // sesion viva nunca discrepan. Una foto en la que discrepan no
        // describe ningun estado alcanzable, y restaurarla daria una sesion
        // cuyo contador no cuadra con el indice real de su cadena.
        //
        // Esto se comprueba porque sin esta guarda la mutacion "no serializar
        // `sendMessageNumber`" NO la detecta ningun test: la cadena de envio ya
        // lleva el indice correcto y tapa el campo. Es el unico hueco de
        // cobertura que la mutacion encontro, y se cierra aqui.
        val w = Wire()
        dosEpochs(w)
        repeat(3) { i -> exchange(w.bob, w.alice, "b$i") }
        val foto = w.bobSession.snapshot()

        assertEquals(
            foto.sendMessageNumber, foto.sendChain.sendMessageNumber,
            "las dos copias de Ns coinciden en una foto real",
        )
        assertEquals(w.bobSession.currentSendMessageNumber(), foto.sendMessageNumber)

        // Y una foto que se contradiga se rechaza, en vez de dejar pasar una
        // sesion imposible.
        assertThrows<IllegalArgumentException> {
            DoubleRatchetSnapshot(
                rootKey = foto.rootKey,
                dhSelf = foto.dhSelf,
                dhRemote = foto.dhRemote,
                sendMessageNumber = foto.sendMessageNumber + 1u,
                previousChainLength = foto.previousChainLength,
                sendChain = foto.sendChain,
                receiveChains = foto.receiveChains,
            )
        }
    }

    // ===================================================================
    // SNAP-15b — Incoherencia de la clave DH
    // ===================================================================

    @Test
    @DisplayName("SNAP-15b una foto con una clave DH incoherente se rechaza al capturarla")
    fun `SNAP-15b clave DH incoherente`() {
        // no se puede fotografiar: el estado announcing una clave publica que
        // su escalar no produce es un estado que nadie podria descifrar, y lo
        // unico razonable es enterarse al MIRAR, no al descifrar.
        val incoherente = X25519KeyPair(
            x25519.generateKeyPair().privateKey,
            x25519.generateKeyPair().publicKey,
        )
        val s = session(incoherente, null)
        assertThrows<IllegalArgumentException> { s.snapshot() }
        // Con un par coherente no hay problema.
        session(x25519.generateKeyPair(), null).snapshot()
    }

    // ===================================================================
    // SNAP-16 — Tomar una foto no es hacer nada
    // ===================================================================

    @Test
    @DisplayName("SNAP-16 tomar una foto no muta la sesion y es estable")
    fun `SNAP-16 tomar una foto no muta`() {
        val w = Wire()
        dosEpochs(w)
        val antes = w.bobSession.stateFingerprint()
        val f1 = w.bobSession.snapshot()
        val f2 = w.bobSession.snapshot()
        val f3 = w.bobSession.snapshot()

        assertContentEquals(antes, w.bobSession.stateFingerprint(), "fotografiar no mueve el estado")
        // Tres fotos del mismo instante describen el mismo estado.
        assertContentEquals(
            restore(f1).stateFingerprint(), restore(f2).stateFingerprint(), "fotos estables",
        )
        assertContentEquals(
            restore(f2).stateFingerprint(), restore(f3).stateFingerprint(), "fotos estables",
        )
        // Y la sesion sigue pudiendo enviar exactamente igual.
        val original = w.bob.encrypt("sigo aqui".toByteArray())
        w.bobSession.restore(f1)
        val restaurada = SecureRatchetProtocol(w.bobSession, protector).encrypt("sigo aqui".toByteArray())
        assertContentEquals(original, restaurada, "fotografiar no cambia lo que se emite")
    }
}
