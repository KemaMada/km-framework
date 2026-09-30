package com.keymessage.core.ratchet

import com.keymessage.core.crypto.AllZeroSharedSecretException
import com.keymessage.core.crypto.Kdf
import com.keymessage.core.crypto.X25519
import com.keymessage.core.crypto.X25519KeyPair
import com.keymessage.core.crypto.provider.BcChaCha20Poly1305
import com.keymessage.core.crypto.provider.BcHkdfSha256
import com.keymessage.core.crypto.provider.BcX25519
import com.keymessage.core.messaging.SecureMessageReceiver
import com.keymessage.core.sf.BinarySecureFrameCodec
import com.keymessage.core.sf.SecureFrame
import com.keymessage.core.sf.SecureFrameProtector
import com.keymessage.core.sf.SecureRatchetProtocol
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.function.Executable
import kotlin.test.assertContentEquals

/**
 * Contencion de la DH publica de orden pequeno.
 *
 * ## QUE CIERRA ESTE ARCHIVO
 *
 * Un `SecureFrame` con una DH publica de 32 bytes que NO es una clave publica
 * utilizable —el caso canonico son 32 bytes a cero— pasa la comprobacion de
 * longitud, porque su longitud es exactamente la correcta. El acuerdo X25519
 * lo aborta con [AllZeroSharedSecretException], y esa excepcion se escapaba de
 * `previewReceive` hacia el llamante.
 *
 * El efecto era denegacion de servicio REMOTA y trivial: cualquiera que pueda
 * escribir un frame en el cable —y un frame lo fabrica cualquiera, no hace
 * falta ninguna clave— tumbaba el proceso receptor con 44 bytes de cabecera.
 * Un rechazo criptografico es una decision, y una decision no se expresa
 * abortando el proceso que la toma.
 *
 * ## EL PRINCIPIO, EN UNA FRASE
 *
 * Ninguna entrada remota invalida convierte un rechazo criptografico esperado
 * en una excepcion que escapa del camino normal de recepcion.
 *
 * ## LO QUE ESTOS TESTS NO AFIRMAN
 *
 * - No afirman que el receptor distinga un ataque de un fallo de transporte.
 *   Solo que un rechazo es un rechazo y que no tumba el proceso.
 * - No dicen nada de quien FABRICA el frame de orden pequeno. Aqui solo se
 *   demuestra que, cuando llega, se contiene.
 * - El estado del ratchet no se muta al rechazar. Es la misma invariante que
 *   sostiene el resto de los rechazos, y se comprueba con
 *   [DoubleRatchetSession.stateFingerprint].
 *
 * ## UNA DECISION DE DISENO QUE DEJA HUELLA
 *
 * Una DH de longitud invalida y una DH de orden pequeno son las dos material
 * que no puede ser clave, y aun asi reciben motivos DISTINTOS
 * (`REPLAY_OR_UNKNOWN` y `LOW_ORDER_DH_PUBLIC_KEY`). No es una incoherencia
 * sin ver: se rechazan en momentos distintos —la longitud se decide mirando
 * el material, el orden pequeno solo se puede ver intentando el acuerdo— y
 * unificar el motivo del rechazo por longitud cambiaria el comportamiento que
 * un checkpoint anterior ya fijo. [DHZERO-08] deja esa frontera escrita para
 * que no se lea por descuido.
 */
class LowOrderDhRejectionTest {

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
    // Material de la prueba
    // ===================================================================

    /**
     * Claves publicas X25519 de orden pequeno que `agree` aborta.
     *
     * La lista esta VERIFICADA EMPIRICAMENTE contra el provider del proyecto:
     * no es una lista copiada de la literatura, es la lista de valores que este
     * provider aborta con [AllZeroSharedSecretException], que es la condicion
     * que RFC 7748 §6.1 manda abortar. Se verifica en vez de copiar porque un
     * punto de orden pequeno que el provider aceptara en silencio seguiria
     * siendo material inservible, y un test que afirmara lo contrario sin
     * comprobarlo seria decoracion.
     */
    private val ordenPequeno = listOf(
        "todo cero" to ByteArray(32),
        "u=1" to "0100000000000000000000000000000000000000000000000000000000000000".hex(),
        "u=p (2^255-19)" to "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f".hex(),
        "u=p+1" to "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f".hex(),
        "u=p-1" to "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f".hex(),
        // Punto de orden pequeno publicado en la literatura, escrito tal cual
        // lo espera X25519 (32 bytes little-endian de la coordenada u).
        "punto publicado" to
            "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157".hex(),
    )

    private fun String.hex(): ByteArray {
        val clean = filter { it != ' ' }
        require(clean.length % 2 == 0)
        return ByteArray(clean.length / 2) {
            ((Character.digit(clean[it * 2], 16) shl 4) or
                Character.digit(clean[it * 2 + 1], 16)).toByte()
        }
    }

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

    /** Alice y Bob con la MISMA sesion logica: Bob USA el par que Alice ve. */
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

    /**
     * Un frame de wire legitimo con la DH del header SUSTITUIDA.
     *
     * Esta es la forma de la entrada hostil real: los bytes llegan del cable y
     * cualquiera puede escribirlos. El AAD es el header entero, asi que el
     * AEAD detectara el cambio —pero eso da igual, y es justo lo que estos
     * tests comprueban: la contencion ocurre en el RATCHET, antes de que
     * exista clave con la que hacer nada.
     */
    private fun frameConDh(wire: ByteArray, dhPublicKey: ByteArray): ByteArray {
        val frame: SecureFrame = BinarySecureFrameCodec.decode(wire)
        val h = frame.ratchetHeader
        return BinarySecureFrameCodec.encode(
            frame.copy(ratchetHeader = h.copy(dhPublicKey = dhPublicKey)),
        )
    }

    private fun ok(r: SecureRatchetProtocol.DecryptResult): ByteArray {
        assertTrue(r is SecureRatchetProtocol.DecryptResult.Ok, "esperaba Ok, fue $r")
        return (r as SecureRatchetProtocol.DecryptResult.Ok).plaintext
    }

    private fun rejected(
        r: SecureRatchetProtocol.DecryptResult,
    ): SecureRatchetProtocol.DecryptResult.Rejected {
        assertTrue(r is SecureRatchetProtocol.DecryptResult.Rejected, "esperaba Rejected, fue $r")
        return r as SecureRatchetProtocol.DecryptResult.Rejected
    }

    private fun rejectedSesion(r: DoubleRatchetSession.ReceiveResult): RejectReason {
        assertTrue(r is DoubleRatchetSession.ReceiveResult.Rejected, "esperaba Rejected, fue $r")
        return (r as DoubleRatchetSession.ReceiveResult.Rejected).reason
    }

    // ===================================================================
    // DHZERO-01 — el caso minimo: 32 bytes a cero
    // ===================================================================

    @Test
    @DisplayName("DHZERO-01 una DH publica de 32 bytes a cero se RECHAZA, no revienta")
    fun `DHZERO-01 DH de todo cero se rechaza`() {
        val w = Wire()

        // A nivel de sesion.
        val motivo = rejectedSesion(w.bobSession.previewReceive(ByteArray(32), 0u, 0u))
        assertEquals(RejectReason.LOW_ORDER_DH_PUBLIC_KEY, motivo)

        // Y por el camino de verdad: el del receptor, con bytes de wire.
        val hostil = frameConDh(w.alice.encrypt("hola".toByteArray()), ByteArray(32))
        assertTrue(
            hostil.size >= 44,
            "el frame hostil es un SecureFrame bien formado: ${hostil.size} bytes",
        )
        val r = w.bob.decrypt(hostil)
        assertTrue(r is SecureRatchetProtocol.DecryptResult.Rejected, "esperaba Rejected, fue $r")

        // Nunca `Ok`: el texto plano no sale aunque el payload sea el de otro
        // mensaje legitimo.
        assertFalse(r is SecureRatchetProtocol.DecryptResult.Ok, "no puede entregarse nada: $r")
        assertFalse(r is SecureRatchetProtocol.DecryptResult.Unauthenticated, "no es un fallo de AEAD: $r")
    }

    // ===================================================================
    // DHZERO-02 — el motivo es ESPECIFICO, no generico
    // ===================================================================

    @Test
    @DisplayName("DHZERO-02 el motivo es el de DH invalida, no el generico de la cadena")
    fun `DHZERO-02 el motivo es especifico`() {
        val w = Wire()

        val porSesion = rejectedSesion(w.bobSession.previewReceive(ByteArray(32), 0u, 0u))
        assertEquals(RejectReason.LOW_ORDER_DH_PUBLIC_KEY, porSesion)

        // La asercion de arriba ya distingue: `assertEquals` contra una
        // constante distinta FALLA si vuelve el generico. Se dice ademas por que
        // la diferencia importa, y no solo por el nombre del motivo.
        assertNotEquals(
            RejectReason.REPLAY_OR_UNKNOWN,
            porSesion,
            "una DH de orden pequeno no es un replay: no hay ninguna cadena que replayear",
        )
        assertNotEquals(
            RejectReason.SKIP_LIMIT_EXCEEDED,
            porSesion,
            "tampoco es un salto excesivo: el salto ni se ha mirado",
        )

        // Y por el camino del protocolo, el motivo llega intacto.
        val hostil = frameConDh(w.alice.encrypt("hola".toByteArray()), ByteArray(32))
        assertEquals(
            RejectReason.LOW_ORDER_DH_PUBLIC_KEY,
            rejected(w.bob.decrypt(hostil)).reason,
            "el protocolo propaga el motivo sin rebajarlo a algo generico",
        )

        // El motivo existe de verdad en el modelo, y es distinguible del resto.
        assertTrue(
            RejectReason.LOW_ORDER_DH_PUBLIC_KEY in RejectReason.entries,
            "es una constante propia del enum, no un alias de otro valor",
        )
    }

    // ===================================================================
    // DHZERO-03 — NINGUNA EXCEPCION ESCAPA
    // ===================================================================

    @Test
    @DisplayName("DHZERO-03 ninguna excepcion escapa: ni de la sesion, ni del protocolo, ni del receptor")
    fun `DHZERO-03 no escapa ninguna excepcion`() {
        val w = Wire()

        // Si la excepcion saliera, ESTA asercion falla: es exactamente la
        // mutacion que este checkpoint cierra, y por eso la asercion existe en
        // vez de confiar en que "ya no hay try/catch".
        assertDoesNotThrow(
            Executable { w.bobSession.previewReceive(ByteArray(32), 0u, 0u) },
            "previewReceive no puede lanzar por material de orden pequeno",
        )

        val hostil = frameConDh(w.alice.encrypt("hola".toByteArray()), ByteArray(32))
        assertDoesNotThrow(
            Executable { w.bob.decrypt(hostil) },
            "decrypt no puede lanzar por material de orden pequeno",
        )

        // Y por la capa que de verdad CONSUME frames remotos. Que el resultado
        // siga siendo un rechazo es lo que distingue esta contencion de una
        // contencion mas arriba: si alguien atrapara la excepcion en el
        // receptor, tambien habria un `Rejected` aqui. La diferencia esta en
        // que el ratchet ya la cerro antes, y [DHZERO-02] lo comprueba.
        val receptor = SecureMessageReceiver(w.bob)
        assertDoesNotThrow(
            Executable {
                val r = receptor.receive(hostil)
                assertTrue(
                    r is SecureMessageReceiver.Result.Rejected,
                    "el receptor rechaza el frame, no lo procesa: $r",
                )
                assertEquals(
                    0,
                    receptor.recordCount(),
                    "y no registra nada de un frame rechazado",
                )
            },
            "la capa de entrega tampoco ve la excepcion",
        )
    }

    // ===================================================================
    // DHZERO-04 — EL ESTADO NO SE MUTA
    // ===================================================================

    @Test
    @DisplayName("DHZERO-04 el estado del ratchet queda IDENTICO tras el rechazo")
    fun `DHZERO-04 el estado no se muta`() {
        val w = Wire()

        // Una sesion con historial, para que la huella cubra algo de verdad:
        // una cadena abierta y una clave ya consumida.
        assertContentEquals(
            "hola".toByteArray(),
            ok(w.bob.decrypt(w.alice.encrypt("hola".toByteArray()))),
            "un mensaje legitimo antes de nada",
        )

        val antes = w.bobSession.stateFingerprint()
        val antesProtocolo = w.bob.stateFingerprint()
        val cadenasAntes = w.bobSession.receiveChainCount()

        val hostil = frameConDh(w.alice.encrypt("no debe entrar".toByteArray()), ByteArray(32))
        rejected(w.bob.decrypt(hostil))

        assertContentEquals(
            antes,
            w.bobSession.stateFingerprint(),
            "la DH de orden pequeno NO puede avanzar el ratchet: no es una epoch",
        )
        assertContentEquals(
            antesProtocolo,
            w.bob.stateFingerprint(),
            "y el protocolo tampoco muta nada",
        )
        assertEquals(
            cadenasAntes,
            w.bobSession.receiveChainCount(),
            "rechazar no abre una cadena de recepcion nueva",
        )

        // Todas las DH de orden pequeno, no solo el cero total.
        for ((nombre, dh) in ordenPequeno) {
            rejectedSesion(w.bobSession.previewReceive(dh, 0u, 0u))
            assertContentEquals(
                antes,
                w.bobSession.stateFingerprint(),
                "el rechazo de '$nombre' no muta nada",
            )
        }
    }

    // ===================================================================
    // DHZERO-05 — LA SESION SIGUE VIVA DESPUES
    // ===================================================================

    @Test
    @DisplayName("DHZERO-05 tras el rechazo, un frame valido entra: la sesion no queda envenenada")
    fun `DHZERO-05 la sesion sigue viva`() {
        val w = Wire()

        assertContentEquals("m0".toByteArray(), ok(w.bob.decrypt(w.alice.encrypt("m0".toByteArray()))))

        // La intrusion va ENTRE dos mensajes legitimos: si envenenara la
        // sesion, el fallo se veria aqui y no como un rechazo.
        val hostil = frameConDh(w.alice.encrypt("esto ni entra".toByteArray()), ByteArray(32))
        rejected(w.bob.decrypt(hostil))

        assertContentEquals(
            "m1".toByteArray(),
            ok(w.bob.decrypt(w.alice.encrypt("m1".toByteArray()))),
            "el siguiente frame legitimo entra",
        )
        assertContentEquals(
            "m2".toByteArray(),
            ok(w.bob.decrypt(w.alice.encrypt("m2".toByteArray()))),
            "y el siguiente tambien: la cadena no se ha corrompido",
        )

        // Y en sentido contrario: el rechazo no ha tomado material de envio.
        val vuelta = w.bob.encrypt("respuesta".toByteArray())
        assertContentEquals(
            "respuesta".toByteArray(),
            ok(w.alice.decrypt(vuelta)),
            "la cadena de envio de Bob sigue alineada con la de Alice",
        )
    }

    // ===================================================================
    // DHZERO-06 — NO CONFUNDIR CON UNA EPOCH INEXISTENTE
    // ===================================================================

    @Test
    @DisplayName("DHZERO-06 una DH valida de epoch inexistente conserva SU rechazo, no este")
    fun `DHZERO-06 una epoch inexistente no se confunde con orden pequeno`() {
        val w = Wire()
        assertContentEquals("hola".toByteArray(), ok(w.bob.decrypt(w.alice.encrypt("hola".toByteArray()))))
        val antes = w.bobSession.stateFingerprint()

        // 32 bytes, clave publica REAL, pero de una epoch que esta sesion no
        // tiene. Es el caso que mas se le parece al de orden pequeno, y el mas
        // facil de colar en el mismo motivo: son cosas distintas.
        val ajena = x25519.generateKeyPair().publicKey
        assertEquals(32, ajena.size, "misma longitud que el caso que SI se rechaza: la longitud no las separa")

        // El ratchet SI puede derivar una clave con este material —de hecho la
        // deriva—, asi que en el ratchet no hay nada que rechazar. Lo que no
        // se puede es fingir que el material es inservible.
        val enSesion = w.bobSession.previewReceive(ajena, 0u, 1u)
        assertTrue(
            enSesion is DoubleRatchetSession.ReceiveResult.Ready,
            "una clave valida de epoch nueva SI produce clave: $enSesion",
        )
        w.bobSession.discardReceive()

        // En el protocolo, ese mismo frame cae donde cae siempre un frame que
        // el ratchet no puede descartar: en el AEAD.
        val hostil = frameConDh(w.alice.encrypt("ajeno".toByteArray()), ajena)
        val r = w.bob.decrypt(hostil)
        assertTrue(
            r is SecureRatchetProtocol.DecryptResult.Unauthenticated,
            "una epoch valida e inexistente cae en el AEAD, que es su rechazo propio: $r",
        )
        assertFalse(
            r is SecureRatchetProtocol.DecryptResult.Rejected,
            "y no en un `Rejected`: no hay material inservible que reprobar: $r",
        )

        assertContentEquals(
            antes,
            w.bobSession.stateFingerprint(),
            "una epoch nueva que no se autentica tampoco muta el estado",
        )

        // Y la sesion legitima sigue.
        assertContentEquals("m1".toByteArray(), ok(w.bob.decrypt(w.alice.encrypt("m1".toByteArray()))))
    }

    // ===================================================================
    // DHZERO-07 — OTRAS DH DE ORDEN PEQUENO
    // ===================================================================

    @Test
    @DisplayName("DHZERO-07 otras DH de orden pequeno distintas del cero total se rechazan igual")
    fun `DHZERO-07 otras DH de orden pequeno`() {
        val w = Wire()
        val antes = w.bobSession.stateFingerprint()

        for ((nombre, dh) in ordenPequeno) {
            assertEquals(
                32,
                dh.size,
                "'$nombre' tiene la longitud correcta: es el caso que la comprobacion de longitud no ve",
            )

            // 1. El ratchet la rechaza con el motivo especifico.
            assertEquals(
                RejectReason.LOW_ORDER_DH_PUBLIC_KEY,
                rejectedSesion(w.bobSession.previewReceive(dh, 0u, 0u)),
                "'$nombre' a nivel de sesion",
            )

            // 2. Y por el cable tampoco escapa nada, ni cambia el motivo.
            val hostil = frameConDh(w.alice.encrypt("x".toByteArray()), dh)
            assertEquals(
                RejectReason.LOW_ORDER_DH_PUBLIC_KEY,
                rejected(w.bob.decrypt(hostil)).reason,
                "'$nombre' por el camino del protocolo",
            )
            assertContentEquals(
                antes,
                w.bobSession.stateFingerprint(),
                "'$nombre' no muta el estado",
            )

            // 3. El caso de referencia: es la PRIMITIVA la que aborta, y por eso
            // el motivo puede decir "orden pequeno" con certeza. Sin esto, el
            // test solo probaria que el codigo nuevo devuelve lo que el codigo
            // nuevo devuelve.
            assertThrows<AllZeroSharedSecretException>(
                "'$nombre' debe abortar el acuerdo segun RFC 7748 6.1",
            ) {
                x25519.agree(x25519.generateKeyPair().privateKey, dh)
            }
        }
    }

    // ===================================================================
    // DHZERO-08 — LA FRONTERA CON EL RECHAZO POR LONGITUD
    // ===================================================================

    @Test
    @DisplayName("DHZERO-08 una DH de longitud invalida conserva su motivo: el nuevo no se ha extendido")
    fun `DHZERO-08 la longitud conserva su motivo`() {
        val w = Wire()
        val antes = w.bobSession.stateFingerprint()

        // Estas NO son de orden pequeno: son material que ni siquiera tiene
        // forma de clave. Se rechazan mirando el material, antes de abrir
        // ninguna rama, y con el motivo que ya tenian.
        for (larga in listOf(0, 1, 16, 31, 33, 64)) {
            assertEquals(
                RejectReason.REPLAY_OR_UNKNOWN,
                rejectedSesion(w.bobSession.previewReceive(ByteArray(larga) { 0x41 }, 0u, 0u)),
                "una DH de $larga bytes",
            )
        }

        // Y el motivo nuevo NO se ha extendido a estos casos: eso seria
        // afirmar "de orden pequeno" de algo que solo es corto.
        assertNotEquals(
            RejectReason.LOW_ORDER_DH_PUBLIC_KEY,
            rejectedSesion(w.bobSession.previewReceive(ByteArray(31) { 0x41 }, 0u, 0u)),
            "31 bytes no es una DH de orden pequeno: es una DH que no llega a ser clave",
        )
        assertContentEquals(
            antes,
            w.bobSession.stateFingerprint(),
            "ninguno de esos rechazos muta el estado",
        )
    }
}
