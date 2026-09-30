package com.km.x3dh

import com.km.crypto.AgreementKeyGuard
import com.km.crypto.AllZeroSharedSecretException
import com.km.crypto.Kdf
import com.km.crypto.X25519
import com.km.crypto.X25519KeyPair
import com.km.crypto.provider.BcChaCha20Poly1305
import com.km.crypto.provider.BcHkdfSha256
import com.km.crypto.provider.BcX25519
import com.km.ratchet.DoubleRatchetSession
import com.km.ratchet.RatchetSessionBootstrap
import com.km.ratchet.RejectReason
import com.km.frame.SecureFrameProtector
import com.km.frame.SecureRatchetProtocol
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.security.SecureRandom
import kotlin.test.assertContentEquals

/**
 * Contencion de agreement keys remotas SEMANTICAMENTE INVALIDAS en el motor.
 *
 * ## QUE CIERRA ESTE ARCHIVO
 *
 * Es la misma clase de problema que `LowOrderDhRejectionTest` cerro en el
 * receptor, aplicada al lado del motor. En el receptor, `previewReceive`
 * capturaba [AllZeroSharedSecretException] y devolvia
 * `RejectReason.LOW_ORDER_DH_PUBLIC_KEY`. Del lado del motor seguia abierto:
 * `requireAgreementKey` (y su equivalente en el bootstrap) miraba SOLO la
 * longitud, y como el material remoto mide 32 bytes, una clave publica de orden
 * pequeño —32 bytes a cero— pasaba de largo y llegaba hasta `agree()`, que
 * abortaba lanzando.
 *
 * ## EL PRINCIPIO, EN UNA FRASE
 *
 * Material criptografico remoto del tamano correcto pero semanticamente
 * invalido no escapa como excepcion de proceso por un camino normal de
 * entrada hostil: se rechaza antes de hacer criptografia con el.
 *
 * ## LA MATRIZ QUE ESTOS TESTS CONSTRUYEN
 *
 * Cada ruta remota que acepta una clave publica de acuerdo tiene una celda
 * para material valido y otra para material de orden pequeno, y las dos
 * tienen que acabar sin excepcion de proceso:
 *
 * | Entrada                                  | DH valida | DH all-zero | Sin excepcion |
 * |------------------------------------------|-----------|-------------|---------------|
 * | Ruta 1 `respond(EK_A)`                   | AGREE-01  | AGREE-02    | AGREE-02      |
 * | Ruta 2 `respond(IK_A)`                   | AGREE-03  | AGREE-04    | AGREE-04      |
 * | Ruta 3 `initiate(IK_B,SPK_B,OPK_B)`      | AGREE-05  | AGREE-06    | AGREE-06      |
 * | Ruta 4 `initiatorProtocol(SPK_B)`        | AGREE-07  | AGREE-08    | AGREE-08      |
 *
 * Ruta 3 son TRES claves, no una: `IK_B`, `SPK_B` y `OPK_B` entran por el
 * mismo `ContactBundle` y por la misma comprobacion, y las tres se cubren en
 * la misma celda. Ruta 1 es la mas cercana al cable —`EK_A` viaja en el header
 * del `SecureFrame`— y ruta 2, 3 y 4 vienen del `ContactBundle` remoto. Las
 * cuatro son material que elige el otro extremo.
 *
 * ## LO QUE ESTOS TESTS AFIRMAN Y NO AFIRMAN
 *
 * Afirman que un rechazo es un rechazo tipado y que no cuesta material. No
 * afirman que el receptor distinga un ataque de un fallo de transporte, ni que
 * estas rutas sean alcanzables desde el cable hoy: `X3dh` todavia no tiene un
 * consumidor en `main` (ver `DhLowOrderAuditTest` antes de este checkpoint). Lo
 * que se fija es el contrato del motor para cuando ese consumidor exista.
 *
 * ## LO QUE NO SE UNIFICA, Y POR QUE
 *
 * «DH no utilizable» NO es un unico motivo, ni aqui ni en el receptor, y esa
 * duplicidad es INTENCIONAL y anterior a este checkpoint:
 *
 *  - longitud distinta de 32 bytes -> `IllegalArgumentException` en el motor,
 *    `REPLAY_OR_UNKNOWN` en el receptor;
 *  - 32 bytes pero secreto low-order -> [AllZeroSharedSecretException] en el
 *    motor, `LOW_ORDER_DH_PUBLIC_KEY` en el receptor.
 *
 * Se rechazan en momentos distintos: la longitud se decide mirando el
 * material, el orden pequeño solo se puede ver intentando el acuerdo. Unificar
 * cambiaria el comportamiento que un checkpoint anterior ya fijo, asi que este
 * archivo lo demuestra con las DOS ramas y no las fusiona. [AGREE-09] y
 * [AGREE-13] son los tests que sostienen esa frontera.
 */
class AgreementKeyContentionTest {

    private lateinit var real: X25519
    private lateinit var kdfReal: Kdf
    private lateinit var x: ContadorX25519
    private lateinit var k: ContadorKdf
    private lateinit var x3dh: X3dh
    private lateinit var protector: SecureFrameProtector

    private val random = SecureRandom()

    @BeforeEach
    fun setUp() {
        real = BcX25519()
        kdfReal = BcHkdfSha256()
        x = ContadorX25519(real)
        k = ContadorKdf(kdfReal)
        x3dh = X3dh(x, k)
        protector = SecureFrameProtector(BcChaCha20Poly1305(), k)
    }

    // ===================================================================
    // DOBLES DE PRUEBA (primero, porque todo lo que se afirma de las rutas
    // se afirma CONTRA un doble que cuenta, no contra el provider pelado)
    // ===================================================================

    /**
     * Delega TODO en el provider real y cuenta las operaciones.
     *
     * No cambia el comportamiento en un solo bit: si el doble falseara el
     * resultado, los tests de la columna «DH valida» fallarian. Lo unico que
     * hace es dejar observar que un rechazo NO HA CRIPTOGRAFIADO y NO HA
     * GENERADO MATERIAL, que es la mitad de la invariante y la unica que no
     * se puede observar desde el valor devuelto.
     */
    private class ContadorX25519(private var real: X25519) : X25519 {
        var pares = 0
            private set
        var acuerdos = 0
            private set

        override fun generateKeyPair(): X25519KeyPair {
            pares++
            return real.generateKeyPair()
        }

        override fun publicKey(privateKey: ByteArray): ByteArray = real.publicKey(privateKey)

        override fun agree(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
            acuerdos++
            return real.agree(privateKey, publicKey)
        }

        fun reinicia() {
            pares = 0
            acuerdos = 0
        }
    }

    /** Igual que [ContadorX25519] pero para el KDF: cuenta material DERIVADO. */
    private class ContadorKdf(private val real: Kdf) : Kdf {
        var derivaciones = 0
            private set

        override fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray =
            real.hkdfExtract(salt, ikm)

        override fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
            derivaciones++
            return real.hkdfExpand(prk, info, length)
        }

        fun reinicia() {
            derivaciones = 0
        }
    }

    // ===================================================================
    // Material de la prueba
    // ===================================================================

    private inner class Dispositivo(private val ik: X25519KeyPair) {
        val deviceId: ByteArray = ByteArray(32) { it.toByte() }
        val spk = real.generateKeyPair()
        var opk: X25519KeyPair? = null

        fun prekeys(opkId: Long? = null) = BootstrapPrekeys(
            deviceId = deviceId,
            identityAgreementKey = ik.publicKey,
            signedPreKey = spk.publicKey,
            signedPreKeyId = 1L,
            oneTimePreKey = opk?.publicKey,
            oneTimePreKeyId = opkId,
        )

        fun responder(opkId: Long? = null) = ResponderKeyMaterial(
            deviceId = deviceId,
            identityAgreementKey = ik,
            signedPreKey = spk,
            oneTimePreKey = opk,
            oneTimePreKeyId = opkId,
        )

        fun initiator() = InitiatorKeyMaterial(deviceId, ik)
    }

    private fun nuevo() = Dispositivo(real.generateKeyPair())
    private fun f() = ByteArray(32).also { random.nextBytes(it) }

    /** La entrada hostil canonica: 32 bytes, la longitud correcta, orden pequeno. */
    private val todoCero = ByteArray(32)

    /**
     * El conjunto CERRADO de claves publicas de orden pequeno.
     *
     * No es una lista de literatura: son los cinco puntos del subgrupo
     * 2-primario de Curve25519 en coordenada u, mas los alias NO CANONICOS que
     * se obtienen sumando `p` —el remoto hostil solo tiene que cambiar un bit
     * para escribir el mismo punto de otra forma—. Los valores de `p`, `p+1` y
     * `p-1` se calculan en [AGREE-11] y se comprueban aqui contra el
     * provider, no se escriben a mano.
     */
    private fun ordenPequeno(): List<Pair<String, ByteArray>> {
        val p = java.math.BigInteger.TWO.pow(255).subtract(java.math.BigInteger.valueOf(19))
        fun le(v: java.math.BigInteger): ByteArray {
            val be = v.toByteArray()
            val big = if (be.size > 32) be.copyOfRange(be.size - 32, be.size) else be
            // Relleno a la izquierda: `BigInteger.ZERO.toByteArray()` es de UN
            // byte, y sin esto el helper devolveria un array de 1 y el
            // "bit 255" de abajo reventaria con IndexOutOfBounds.
            val out = ByteArray(32)
            System.arraycopy(big, 0, out, 32 - big.size, big.size)
            return out.reversedArray()
        }
        val base = listOf(
            "u=0 (todo cero)" to java.math.BigInteger.ZERO,
            "u=1" to java.math.BigInteger.ONE,
            "u=p-1" to p.subtract(java.math.BigInteger.ONE),
            "u=p (alias no canonico de 0)" to p,
            "u=p+1 (alias no canonico de 1)" to p.add(java.math.BigInteger.ONE),
        )
        return base.map { (n, v) ->
            val enc = le(v)
            listOf(
                n to enc,
                // El mismo valor con el bit alto del ultimo byte a 1: X25519 lo
                // ignora, asi que para la primitiva es EL MISMO punto. Si el
                // guard no normalizara, este pasaria y el comentario del guard
                // seria mentira.
                "$n con bit 255" to enc.copyOf().also { it[31] = (it[31].toInt() or 0x80).toByte() },
            )
        }.flatten()
    }

    // ===================================================================
    // Aserciones compartidas
    // ===================================================================

    /**
     * El rechazo tiene que ser EXACTAMENTE [AllZeroSharedSecretException].
     *
     * `assertThrows<T>` ya falla si sale otro tipo, pero eso deja pasar dos
     * cosas que aqui no valen: una SUBCLASE (el motivo se habria degradado a
     * otra cosa) y dejar que la excepcion no llegue a salir y que el test
     * pasara por otra via. Se captura a mano y se compara la clase exacta.
     */
    private fun rechazoTipado(que: String, bloque: () -> Unit): AllZeroSharedSecretException {
        val capturado: Throwable? = try {
            bloque()
            null
        } catch (t: Throwable) {
            t
        }
        assertNotNull(
            capturado,
            "$que: el material remoto invalido TIENE que rechazarse, y no con excepcion",
        )
        assertEquals(
            AllZeroSharedSecretException::class.java,
            capturado!!.javaClass,
            "$que: el rechazo debe ser exactamente AllZeroSharedSecretException, fue $capturado",
        )
        assertTrue(
            capturado.message.orEmpty().contains("orden pequeno"),
            "$que: el motivo debe decir que es material de orden pequeno: ${capturado.message}",
        )
        return capturado as AllZeroSharedSecretException
    }

    /**
     * Un rechazo NO PUEDE costar criptografia, derivaciones ni generacion de
     * material. Esta es la asercion que distingue «rechazo temprano» de
     * «se.crypto y luego me di cuenta»: con la mutacion de quitar la
     * validacion de la frontera, los tres contadores se mueven.
     */
    private fun sinConsumir(que: String, acuerdos: Int = 0, pares: Int = 0, derivaciones: Int = 0) {
        assertEquals(acuerdos, x.acuerdos, "$que: NO puede haberse hecho ningun acuerdo X25519")
        assertEquals(pares, x.pares, "$que: NO puede haberse generado ningun par de claves")
        assertEquals(
            derivaciones,
            k.derivaciones,
            "$que: NO puede haberse derivado ni una clave (ni SK, ni K_prekey, ni RootKey)",
        )
    }

    /** Copia profunda de todo el material remoto y privado que entra por la ruta. */
    private fun huella(vararg bloques: ByteArray?): ByteArray =
        bloques.filterNotNull().fold(ByteArray(0)) { acc, b -> acc + b }

    private fun huella(b: BootstrapPrekeys) = huella(
        b.deviceId, b.identityAgreementKey, b.signedPreKey,
        b.oneTimePreKey, b.signedPreKeyId.toString().toByteArray(),
    )

    private fun huella(r: ResponderKeyMaterial) = huella(
        r.deviceId, r.identityAgreementKey.privateKey, r.identityAgreementKey.publicKey,
        r.signedPreKey.privateKey, r.signedPreKey.publicKey,
        r.oneTimePreKey?.privateKey, r.oneTimePreKey?.publicKey,
    )

    private fun huella(i: InitiatorKeyMaterial) = huella(
        i.deviceId, i.identityAgreementKey.privateKey, i.identityAgreementKey.publicKey,
    )

    // ===================================================================
    // RUTA 1 — `X3dh.respond` con `EK_A`, la clave que viaja en el frame
    // ===================================================================

    @Test
    @DisplayName("AGREE-01 RUTA 1: respond con EK_A valida ACEPTA y deriva el mismo SK")
    fun `AGREE-01 ruta 1 DH valida`() {
        val alice = nuevo()
        val bob = nuevo()
        val valorF = f()

        // Se prepara la sesion con material bueno, para tener un SK con el que
        // comparar. Esto pasa por el MISMO codigo que la columna hostil: si el
        // guard rechazara de mas, este test caeria antes de llegar.
        val init = x3dh.initiate(alice.initiator(), bob.prekeys(), valorF)
        x.reinicia()
        k.reinicia()

        val prep = x3dh.respond(bob.responder(), alice.prekeys(), init.ephemeralPublic)

        assertContentEquals(init.sharedKey, prep.sharedKey, "SK: el receptor recalcula el del emisor")
        assertContentEquals(init.preKeyKey, prep.preKeyKey, "K_prekey con su propio dominio")
        assertEquals(32, prep.sharedKey.size)

        // Y no es decoracion: se ha hecho el trabajo completo de la ruta.
        assertEquals(3, x.acuerdos, "DH1, DH2 y DH3 contra una EK_A utilizable")
        assertEquals(0, x.pares, "respond no genera material: no es el emisor")
        assertEquals(5, k.derivaciones, "K1, K2, K3, SK y K_prekey")
    }

    @Test
    @DisplayName("AGREE-02 RUTA 1: respond con EK_A de todo cero RECHAZA antes de criptografiar")
    fun `AGREE-02 ruta 1 DH all-zero`() {
        val alice = nuevo()
        val bob = nuevo()
        val remotos = alice.prekeys()
        val responder = bob.responder()

        val huellaRemotos = huella(remotos)
        val huellaResponder = huella(responder)
        x.reinicia()
        k.reinicia()

        // La columna «sin excepcion»: lo que sale es un rechazo TIPADO, no un
        // fallo de proceso y no un valor.
        rechazoTipado("EK_A de todo cero") {
            x3dh.respond(responder, remotos, todoCero)
        }

        sinConsumir("EK_A de todo cero")

        // Estado intacto, byte a byte, del material que entro.
        assertContentEquals(
            huellaRemotos, huella(remotos),
            "el ContactBundle remoto no se toca al rechazar",
        )
        assertContentEquals(
            huellaResponder, huella(responder),
            "las claves propias del receptor no se tocan al rechazar",
        )

        // Y la ruta sigue viva: la sesion legitima entra justo despues.
        val init = x3dh.initiate(alice.initiator(), bob.prekeys(), f())
        val prep = x3dh.respond(bob.responder(), alice.prekeys(), init.ephemeralPublic)
        assertContentEquals(init.sharedKey, prep.sharedKey, "el rechazo no envenena el motor")

        // Las otras DH de orden pequeno de la misma ruta, no solo el cero total.
        for ((nombre, clave) in ordenPequeno()) {
            x.reinicia()
            k.reinicia()
            rechazoTipado("EK_A $nombre") { x3dh.respond(responder, remotos, clave) }
            sinConsumir("EK_A $nombre")
        }
    }

    // ===================================================================
    // RUTA 2 — `X3dh.respond` con `IK_A` del ContactBundle del emisor
    // ===================================================================

    @Test
    @DisplayName("AGREE-03 RUTA 2: respond con IK_A valida ACEPTA y deriva el mismo SK")
    fun `AGREE-03 ruta 2 DH valida`() {
        val alice = nuevo()
        val bob = nuevo()
        val init = x3dh.initiate(alice.initiator(), bob.prekeys(), f())
        x.reinicia()
        k.reinicia()

        // `IK_A` aqui es la clave publica REAL de Alice, la que el receptor
        // recibe por ContactExchange y verifica (KM-0006 §7.3).
        val prep = x3dh.respond(bob.responder(), alice.prekeys(), init.ephemeralPublic)

        assertContentEquals(init.sharedKey, prep.sharedKey, "SK recalculado con IK_A utilizable")
        assertEquals(3, x.acuerdos, "DH1 usa IK_A y DH2/DH3 usan EK_A: tres acuerdos")
        assertEquals(5, k.derivaciones)
    }

    @Test
    @DisplayName("AGREE-04 RUTA 2: respond con IK_A de todo cero RECHAZA antes de criptografiar")
    fun `AGREE-04 ruta 2 DH all-zero`() {
        val alice = nuevo()
        val bob = nuevo()
        val ekLegitima = real.generateKeyPair().publicKey

        // `EK_A` es BUENA a proposito: el rechazo tiene que venir de `IK_A`, no
        // de la otra entrada. Si el guard de `EK_A` se disparara primero, este
        // test no probaria lo que dice que prueba.
        val remotos = alice.prekeys().copy(identityAgreementKey = todoCero)
        val huellaRemotos = huella(remotos)
        val huellaResponder = huella(bob.responder())
        x.reinicia()
        k.reinicia()

        val e = rechazoTipado("IK_A de todo cero") {
            x3dh.respond(bob.responder(), remotos, ekLegitima)
        }
        assertTrue(
            e.message!!.contains("IK remoto"),
            "el motivo dice que clave es: ${e.message}",
        )
        sinConsumir("IK_A de todo cero")
        assertContentEquals(huellaRemotos, huella(remotos), "el material remoto no se toca")
        assertContentEquals(huellaResponder, huella(bob.responder()), "las claves propias tampoco")

        for ((nombre, clave) in ordenPequeno()) {
            val malas = alice.prekeys().copy(identityAgreementKey = clave)
            x.reinicia()
            k.reinicia()
            rechazoTipado("IK_A $nombre") { x3dh.respond(bob.responder(), malas, ekLegitima) }
            sinConsumir("IK_A $nombre")
        }

        // Y con `IK_A` buena, la ruta funciona: no se rechazaba de mas.
        val init = x3dh.initiate(alice.initiator(), bob.prekeys(), f())
        val prep = x3dh.respond(bob.responder(), alice.prekeys(), init.ephemeralPublic)
        assertContentEquals(init.sharedKey, prep.sharedKey)
    }

    // ===================================================================
    // RUTA 3 — `X3dh.initiate`: IK_B, SPK_B y OPK_B del ContactBundle remoto
    // ===================================================================

    @Test
    @DisplayName("AGREE-05 RUTA 3: initiate con prekeys validas ACEPTA y cierra el bootstrap")
    fun `AGREE-05 ruta 3 DH valida`() {
        val alice = nuevo()
        val bob = nuevo()
        bob.opk = real.generateKeyPair()
        x.reinicia()
        k.reinicia()

        val init = x3dh.initiate(alice.initiator(), bob.prekeys(opkId = 7L), f())
        x.reinicia()
        k.reinicia()

        // La columna «DH valida» tiene que significar «funciona», no «no lanza».
        val prep = x3dh.respond(bob.responder(opkId = 7L), alice.prekeys(), init.ephemeralPublic)
        assertContentEquals(init.sharedKey, prep.sharedKey, "SK coincide con las cuatro DH")
        assertEquals(7L, init.usedOneTimePreKeyId, "el OPK se anota, no se consume aqui")

        assertEquals(4, x.acuerdos, "DH1, DH2, DH3 y DH4")
        assertEquals(0, x.pares, "las claves de la sesion ya estaban: no se genera nada")
        assertEquals(6, k.derivaciones, "K1..K4, SK y K_prekey")
    }

    @Test
    @DisplayName("AGREE-06 RUTA 3: initiate con IK_B, SPK_B u OPK_B de todo cero RECHAZA sin gastar el efimero")
    fun `AGREE-06 ruta 3 DH all-zero`() {
        val alice = nuevo()
        val bob = nuevo()
        bob.opk = real.generateKeyPair()
        val buenas = bob.prekeys(opkId = 7L)
        val material = alice.initiator()
        val huellaMaterial = huella(material)
        val huellaPrekeys = huella(buenas)

        // Las TRES claves remotas, una por una. Una sola comprobacion las cubre
        // a las tres, asi que si esa comprobacion desaparece, caen las tres:
        // por eso el mismo test las recorre.
        val rutas = listOf(
            "IK_B" to buenas.copy(identityAgreementKey = todoCero),
            "SPK_B" to buenas.copy(signedPreKey = todoCero),
            "OPK_B" to buenas.copy(oneTimePreKey = todoCero, oneTimePreKeyId = 7L),
        )
        for ((nombre, prekeys) in rutas) {
            x.reinicia()
            k.reinicia()
            val e = rechazoTipado("$nombre de todo cero") {
                x3dh.initiate(material, prekeys, f())
            }
            assertTrue(
                e.message!!.contains("IK remoto") || e.message!!.contains("SPK") ||
                    e.message!!.contains("OPK"),
                "el motivo nombra el papel rechazado: ${e.message}",
            )
            // Aqui esta la asercion que hace que esto sea un rechazo TEMPRANO
            // y no un fallo: el par efimero NO se genera. `pares == 0` es la
            // prueba de que no se ha quemado material de sesion.
            sinConsumir("$nombre de todo cero")
        }

        // Estado intacto del material propio y del remoto bueno.
        assertContentEquals(huellaMaterial, huella(material), "el material del emisor no se toca")
        assertContentEquals(huellaPrekeys, huella(buenas), "el ContactBundle no se toca")

        // Y el resto de DH de orden pequeno, con las tres rutas.
        for ((nombre, clave) in ordenPequeno()) {
            for ((papel, prekeys) in listOf(
                "IK_B" to buenas.copy(identityAgreementKey = clave),
                "SPK_B" to buenas.copy(signedPreKey = clave),
                "OPK_B" to buenas.copy(oneTimePreKey = clave, oneTimePreKeyId = 7L),
            )) {
                x.reinicia()
                k.reinicia()
                rechazoTipado("$papel = $nombre") { x3dh.initiate(material, prekeys, f()) }
                sinConsumir("$papel = $nombre")
            }
        }

        // Con prekeys buenas, la ruta funciona: el guard no rechaza de mas.
        val init = x3dh.initiate(material, buenas, f())
        val prep = x3dh.respond(bob.responder(opkId = 7L), alice.prekeys(), init.ephemeralPublic)
        assertContentEquals(init.sharedKey, prep.sharedKey)
    }

    // ===================================================================
    // RUTA 4 — `RatchetSessionBootstrap.initiatorProtocol` con `SPK_B`
    // ===================================================================

    /**
     * El bootstrap del EMISOR completo: Alice deriva su RootKey y su cadena de
     * envio contra `SPK_B`; Bob deriva lo mismo desde su `SPK_B` privada, con
     * el mismo `RootKey` derivado de `F`.
     *
     * No se reimplementa el protocolo: se usa `DoubleRatchetSession` con las
     * claves que el bootstrap deja preparadas, que es lo que hara el
     * consumidor de KM-0006 §7.4.
     */
    private fun bootstrapCompleto(
        alice: Dispositivo,
        bob: Dispositivo,
    ): Pair<SecureRatchetProtocol, SecureRatchetProtocol> {
        val valorF = f()
        val init = x3dh.initiate(alice.initiator(), bob.prekeys(), valorF)
        // Simetria de X3DH: el receptor recalcula el mismo SK con la publica
        // del emisor. Sin esto, `SPK_B` de Bob no estaria verificado por nadie.
        val prep = x3dh.respond(bob.responder(), alice.prekeys(), init.ephemeralPublic)
        assertContentEquals(init.sharedKey, prep.sharedKey, "el motor X3DH cierra antes del ratchet")

        val rootKey = RatchetSessionBootstrap.rootKeyFrom(valorF, k)
        val aliceProtocolo = RatchetSessionBootstrap.initiatorProtocol(
            bootstrapValue = valorF,
            ephemeral = init.ephemeral,
            remoteSignedPreKey = bob.spk.publicKey,
            signedPreKeyPrivate = bob.spk.privateKey,
            x25519 = x,
            kdf = k,
            protector = protector,
        )
        // El receptor: su `dhSelf` es el par que el emisor vio como `SPK_B`, y
        // la primera cadena la produce el propio ratchet al abrir la epoch.
        val bobSession = DoubleRatchetSession(
            rootKey = rootKey,
            dhSelf = bob.spk,
            dhRemote = init.ephemeralPublic,
            sendChainKey = ByteArray(32) { 0x11 },
            receiveChainKey = ByteArray(32) { 0x22 },
            x25519 = x,
            kdf = k,
        )
        return aliceProtocolo to SecureRatchetProtocol(bobSession, protector)
    }

    @Test
    @DisplayName("AGREE-07 RUTA 4: initiatorProtocol con SPK_B valida ACEPTA y la sesion funciona")
    fun `AGREE-07 ruta 4 DH valida`() {
        val alice = nuevo()
        val bob = nuevo()
        val valorF = f()
        val init = x3dh.initiate(alice.initiator(), bob.prekeys(), valorF)
        val prep = x3dh.respond(bob.responder(), alice.prekeys(), init.ephemeralPublic)
        assertContentEquals(init.sharedKey, prep.sharedKey, "el motor X3DH cierra antes del ratchet")

        x.reinicia()
        k.reinicia()

        val aliceProtocolo = RatchetSessionBootstrap.initiatorProtocol(
            bootstrapValue = valorF,
            ephemeral = init.ephemeral,
            remoteSignedPreKey = bob.spk.publicKey,
            signedPreKeyPrivate = bob.spk.privateKey,
            x25519 = x,
            kdf = k,
            protector = protector,
        )
        // Con material BUENO, el bootstrap hace su trabajo: un acuerdo, tres
        // derivaciones y ni un par generado. El contraste con [AGREE-08], que
        // exige CERO de las tres cosas, es lo que hace que el rechazo sea un
        // rechazo y no una excepcionexpensive.
        assertEquals(1, x.acuerdos, "DH(EK_A, SPK_B) y solo ese")
        assertEquals(3, k.derivaciones, "RootKey, newRootKey y newChainKey")
        assertEquals(0, x.pares, "el bootstrap no genera material de sesion")

        // Y la columna «DH valida» tiene que ser una sesion que FUNCIONA, no
        // una que devuelve un objeto: un protocolo que no intercambia un solo
        // mensaje no demuestra nada.
        val bobSession = DoubleRatchetSession(
            rootKey = RatchetSessionBootstrap.rootKeyFrom(valorF, k),
            dhSelf = bob.spk,
            dhRemote = init.ephemeralPublic,
            sendChainKey = ByteArray(32) { 0x11 },
            receiveChainKey = ByteArray(32) { 0x22 },
            x25519 = x,
            kdf = k,
        )
        val r = SecureRatchetProtocol(bobSession, protector)
            .decrypt(aliceProtocolo.encrypt("hola".toByteArray()))
        assertTrue(
            r is SecureRatchetProtocol.DecryptResult.Ok,
            "una SPK_B utilizable produce una sesion que descifra: fue $r",
        )
        assertContentEquals(
            "hola".toByteArray(),
            (r as SecureRatchetProtocol.DecryptResult.Ok).plaintext,
        )
    }

    @Test
    @DisplayName("AGREE-08 RUTA 4: initiatorProtocol con SPK_B de todo cero RECHAZA sin derivar el RootKey")
    fun `AGREE-08 ruta 4 DH all-zero`() {
        val alice = nuevo()
        val bob = nuevo()
        val efimera = real.generateKeyPair()
        val valorF = f()
        val huellaF = valorF.copyOf()
        val huellaEphemeral = huella(efimera.privateKey, efimera.publicKey)
        val huellaSpk = huella(bob.spk.privateKey, bob.spk.publicKey)

        x.reinicia()
        k.reinicia()

        val e = rechazoTipado("SPK_B de todo cero") {
            RatchetSessionBootstrap.initiatorProtocol(
                bootstrapValue = valorF,
                ephemeral = efimera,
                remoteSignedPreKey = todoCero,
                signedPreKeyPrivate = bob.spk.privateKey,
                x25519 = x,
                kdf = k,
                protector = protector,
            )
        }
        assertTrue(e.message!!.contains("SPK_B"), "el motivo nombra la clave: ${e.message}")
        // `derivaciones == 0` es la asercion fuerte de esta ruta: significa que
        // el `RootKey` tampoco se ha derivado. El rechazo va ANTES de
        // `rootKeyFrom`, no despues.
        sinConsumir("SPK_B de todo cero")

        assertContentEquals(huellaF, valorF, "F no se consume al rechazar")
        assertContentEquals(
            huellaEphemeral, huella(efimera.privateKey, efimera.publicKey),
            "el material efimero no se toca",
        )
        assertContentEquals(
            huellaSpk, huella(bob.spk.privateKey, bob.spk.publicKey),
            "las claves propias no se tocan",
        )

        for ((nombre, clave) in ordenPequeno()) {
            x.reinicia()
            k.reinicia()
            rechazoTipado("SPK_B $nombre") {
                RatchetSessionBootstrap.initiatorProtocol(
                    bootstrapValue = f(),
                    ephemeral = efimera,
                    remoteSignedPreKey = clave,
                    signedPreKeyPrivate = bob.spk.privateKey,
                    x25519 = x,
                    kdf = k,
                    protector = protector,
                )
            }
            sinConsumir("SPK_B $nombre")
        }

        // Con `SPK_B` buena, la sesion existe: no se rechazaba de mas.
        // Con `SPK_B` buena, la sesion existe y funciona: el guard no rechaza
        // de mas, y el rechazo anterior no ha envenenado nada.
        val (a, b) = bootstrapCompleto(nuevo(), nuevo())
        val r = b.decrypt(a.encrypt("sigue viva".toByteArray()))
        assertTrue(
            r is SecureRatchetProtocol.DecryptResult.Ok,
            "tras el rechazo, una SPK_B buena produce una sesion que descifra: $r",
        )
    }

    // ===================================================================
    // LA NO-UNIFICACION: los dos rechazos siguen siendo DOS
    // ===================================================================

    @Test
    @DisplayName("AGREE-09 longitud invalida y secreto low-order conservan MOTIVOS DISTINTOS")
    fun `AGREE-09 los dos rechazos no se unifican`() {
        val alice = nuevo()
        val bob = nuevo()
        val remotos = alice.prekeys()
        val responder = bob.responder()
        val ek = real.generateKeyPair().publicKey
        val prekeys = bob.prekeys(opkId = 7L)
        val material = alice.initiator()
        val efimera = real.generateKeyPair()

        // (a) LO QUE NO ES UNA CLAVE: longitud distinta de 32. Motivo
        // estructural, `IllegalArgumentException`, el de siempre.
        //
        // Comprobar SOLO la clase de la excepcion NO alcanza, y el mutation
        // testing lo demostro: quitando la comprobacion de longitud del
        // guardian, la PRIMitiva lanza `IllegalArgumentException` igualmente
        // (su propio `require` exige 32 bytes) y el test pasaba. La diferencia
        // entre el rechazo de la frontera y el de la primitiva esta en DOS
        // cosas mas: que no se haya llegado a cifrar, y que el motivo diga que
        // clave es. Sin las dos, este test es cobertura aparente.
        for (larga in listOf(0, 1, 16, 31, 33, 64)) {
            val bytes = ByteArray(larga) { 0x41 }
            rechazoEstructural("EK_A de $larga bytes", "clave efimera recibida") {
                x3dh.respond(responder, remotos, bytes)
            }
            rechazoEstructural("IK_A de $larga bytes", "IK remoto") {
                x3dh.respond(responder, remotos.copy(identityAgreementKey = bytes), ek)
            }
            rechazoEstructural("IK_B de $larga bytes", "IK remoto") {
                x3dh.initiate(material, prekeys.copy(identityAgreementKey = bytes), f())
            }
            rechazoEstructural("SPK_B de $larga bytes", "SPK") {
                x3dh.initiate(material, prekeys.copy(signedPreKey = bytes), f())
            }
            rechazoEstructural("OPK_B de $larga bytes", "OPK") {
                x3dh.initiate(material, prekeys.copy(oneTimePreKey = bytes), f())
            }
            rechazoEstructural("SPK_B de $larga bytes en el bootstrap", "SPK_B") {
                RatchetSessionBootstrap.initiatorProtocol(
                    f(), efimera, bytes, bob.spk.privateKey, x, k, protector,
                )
            }
        }

        // (b) LO QUE ES UNA CLAVE Y NO SIRVE: 32 bytes, secreto low-order. El
        // motivo es criptografico, y NO se le ha extendido el del anterior.
        val criptografico = listOf<() -> Unit>(
            { x3dh.respond(responder, remotos, todoCero) },
            { x3dh.respond(responder, remotos.copy(identityAgreementKey = todoCero), ek) },
            { x3dh.initiate(material, prekeys.copy(identityAgreementKey = todoCero), f()) },
            { x3dh.initiate(material, prekeys.copy(signedPreKey = todoCero), f()) },
            { x3dh.initiate(material, prekeys.copy(oneTimePreKey = todoCero), f()) },
            {
                RatchetSessionBootstrap.initiatorProtocol(
                    f(), efimera, todoCero, bob.spk.privateKey, x, k, protector,
                )
            },
        )
        for (bloque in criptografico) {
            assertEquals(
                AllZeroSharedSecretException::class.java,
                claseDe(bloque),
                "32 bytes de orden pequeño: motivo criptografico, no estructural",
            )
        }

        // (c) Y el punto del checkpoint: la no-unificacion no es un descuido
        // de este motor, es una decision que ya esta escrita en el receptor.
        // Los dos `RejectReason` siguen siendo DOS constantes distintas, con
        // el comportamiento que un checkpoint anterior fijo.
        val sesion = DoubleRatchetSession(
            rootKey = ByteArray(32) { 3 },
            dhSelf = real.generateKeyPair(),
            dhRemote = null,
            sendChainKey = ByteArray(32) { 4 },
            receiveChainKey = ByteArray(32) { 5 },
            x25519 = real,
            kdf = kdfReal,
        )
        val porLongitud = (sesion.previewReceive(ByteArray(31) { 0x41 }, 0u, 0u)
            as DoubleRatchetSession.ReceiveResult.Rejected).reason
        val porOrden = (sesion.previewReceive(todoCero, 0u, 0u)
            as DoubleRatchetSession.ReceiveResult.Rejected).reason
        assertEquals(RejectReason.REPLAY_OR_UNKNOWN, porLongitud, "31 bytes: motivo historico")
        assertEquals(RejectReason.LOW_ORDER_DH_PUBLIC_KEY, porOrden, "32 ceros: motivo criptografico")
        assertNotEquals(porLongitud, porOrden, "NO son el mismo motivo: la distincion es intencional")
    }

    @Test
    @DisplayName("AGREE-10 EPOCH-05 y REPLAY_OR_UNKNOWN intactos: la contencion no los toco")
    fun `AGREE-10 EPOCH-05 intacto`() {
        // Se audita el CODIGO FUENTE, no por reflexion: la reflexion sobre
        // Kotlin manglea los nombres de metodo y no distinguiria un cambio real
        // de uno aparente (regla dura del repositorio).
        val raiz = File("src/main/kotlin/com/km")
        assertTrue(raiz.exists(), "no se encuentra el arbol de fuentes desde ${File(".").absolutePath}")

        val spec = File(raiz, "ratchet/SymmetricRatchetSpec.kt")
        val textoSpec = spec.readText()
        assertTrue(
            textoSpec.contains("REPLAY_OR_UNKNOWN"),
            "REPLAY_OR_UNKNOWN debe seguir existiendo: no se renombra para unificar",
        )
        assertTrue(
            textoSpec.contains("LOW_ORDER_DH_PUBLIC_KEY"),
            "el motivo criptografico sigue siendo el suyo",
        )
        // Y la documentacion de la no-unificacion, ahora escrita en los dos
        // lados: el receptor explica por que son dos motivos y el motor
        // declara que reutiliza el tipo de la primitiva en vez de crear otro.
        assertTrue(
            textoSpec.contains("NO CUBRE TAMBIEN LA LONGITUD INVALIDA"),
            "la frontera entre los dos motivos tiene que quedar escrita en el receptor",
        )
        assertTrue(
            File(raiz, "crypto/AgreementKeyGuard.kt").readText()
                .contains("MISMO tipo que produce la primitiva"),
            "el motor declara que reutiliza el tipo de la primitiva y no uno nuevo",
        )

        // Y el motor NO ha incorporado un canal de `Result` paralelo.
        for (archivo in listOf("x3dh/X3dh.kt", "ratchet/RatchetSessionBootstrap.kt")) {
            val texto = File(raiz, archivo).readLines()
                .filterNot { val t = it.trimStart(); t.startsWith("*") || t.startsWith("//") }
                .joinToString("\n")

            assertFalse(
                texto.contains("RejectReason"),
                "$archivo no debe inventar un canal de resultado paralelo: el motor no tiene ninguno",
            )
        }

        // Comportamiento, no solo texto: EPOCH-05 sigue rechazando por
        // longitud con su motivo, con la sesion intacta.
        val bobDh = real.generateKeyPair()
        val sesion = DoubleRatchetSession(
            rootKey = ByteArray(32) { 9 },
            dhSelf = real.generateKeyPair(),
            dhRemote = bobDh.publicKey,
            sendChainKey = ByteArray(32) { 1 },
            receiveChainKey = ByteArray(32) { 2 },
            x25519 = real,
            kdf = kdfReal,
        )
        val antes = sesion.stateFingerprint()
        for (larga in listOf(0, 1, 31, 33, 64)) {
            val r = sesion.previewReceive(ByteArray(larga) { 0x41 }, 0u, 0u)
            assertEquals(
                RejectReason.REPLAY_OR_UNKNOWN,
                (r as DoubleRatchetSession.ReceiveResult.Rejected).reason,
                "EPOCH-05: una DH de $larga bytes conserva su motivo",
            )
        }
        assertContentEquals(antes, sesion.stateFingerprint(), "y no muta la sesion")
    }

    // ===================================================================
    // EL GUARDIAN, AFIRMADO POR SEPARADO
    // ===================================================================

    @Test
    @DisplayName("AGREE-11 el guardian compara sobre la codificacion real, y la lista esta cerrada")
    fun `AGREE-11 el criterio del guardian`() {
        // 1. Los cinco valores de la lista son, de verdad, material que aborta
        //    el acuerdo. Sin esto, la lista seria decoracion: el guard podria
        //    contener valores que la primitiva aceptaria en silencio.
        for ((nombre, clave) in ordenPequeno()) {
            assertEquals(32, clave.size, "'$nombre' tiene la longitud correcta")
            assertTrue(AgreementKeyGuard.isLowOrder(clave), "'$nombre' es de orden pequeño")
            assertThrows<AllZeroSharedSecretException>("'$nombre' debe abortar el acuerdo") {
                real.agree(real.generateKeyPair().privateKey, clave)
            }
        }

        // 2. Una clave publica REAL no se toca. El riesgo de una lista de
        //    rechazo es rechazar de mas, y eso romperia el bootstrap entero.
        repeat(64) {
            val buena = real.generateKeyPair().publicKey
            assertFalse(AgreementKeyGuard.isLowOrder(buena), "una clave real no es de orden pequeño")
        }
        // Y con el bit alto a 1, que es la forma canonica de varias claves.
        repeat(16) {
            val buena = real.generateKeyPair().publicKey
            val conBitAlto = buena.copyOf().also { it[31] = (it[31].toInt() or 0x80).toByte() }
            assertFalse(
                AgreementKeyGuard.isLowOrder(conBitAlto),
                "una clave real con el bit alto puesto sigue siendo una clave real",
            )
        }

        // 3. `p` se calcula, no se copia. El hexadecimal de `p = 2^255 - 19` en
        //    32 bytes big-endian termina en `ED`; escribir `FD` produce un valor
        //    que NO esta en la lista y el guard no filtraria nada.
        val p = java.math.BigInteger.TWO.pow(255).subtract(java.math.BigInteger.valueOf(19))
        val pBytes = p.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        assertEquals(32, pBytes.size, "p ocupa 32 bytes")
        assertEquals(0xED.toByte(), pBytes[31], "p en big-endian termina en ED, no en FD")

        // 4. La longitud no es lo mismo que el valor: una clave de 31 bytes no
        //    es «de orden pequeño», es que no llega a ser clave. Por eso el
        //    guardian NO lanza aqui y quien lo decide es la comprobacion de
        //    longitud, con su propio motivo.
        assertFalse(
            AgreementKeyGuard.isLowOrder(ByteArray(31)),
            "31 bytes no es una DH de orden pequeño: es material que no llega a ser clave",
        )
    }

    @Test
    @DisplayName("AGREE-12 el rechazo no deja sesion a medias: repetir la ruta da el mismo resultado")
    fun `AGREE-12 no hay estado parcial`() {
        val alice = nuevo()
        val bob = nuevo()
        val remotos = alice.prekeys()
        val responder = bob.responder()

        // Con material bueno, dos llamadas seguidas dan el mismo SK: el motor
        // es puro y no lleva cuenta.
        val init = x3dh.initiate(alice.initiator(), bob.prekeys(), f())
        val a = x3dh.respond(responder, remotos, init.ephemeralPublic)
        val b = x3dh.respond(responder, remotos, init.ephemeralPublic)
        assertContentEquals(a.sharedKey, b.sharedKey, "el motor es puro")
        assertContentEquals(a.preKeyKey, b.preKeyKey, "y tambien en K_prekey")

        // Un rechazo en medio no cambia eso.
        rechazoTipado("intermedio") { x3dh.respond(responder, remotos, todoCero) }
        val c = x3dh.respond(responder, remotos, init.ephemeralPublic)
        assertContentEquals(a.sharedKey, c.sharedKey, "rechazar no ha movido nada")

        // Y el `OPK` sigue disponible: un rechazo no lo consume. `respond` no
        // consume OPK ni siquiera en el camino bueno; se comprueba que el
        // receptor puede confirmar DESPUES de haber recibido material hostil.
        val bob2 = nuevo()
        bob2.opk = real.generateKeyPair()
        val i2 = x3dh.initiate(alice.initiator(), bob2.prekeys(opkId = 42L), f())
        rechazoTipado("intermedio con OPK") {
            x3dh.respond(bob2.responder(opkId = 42L), remotos, todoCero)
        }
        val prep = x3dh.respond(bob2.responder(opkId = 42L), remotos, i2.ephemeralPublic)
        assertEquals(42L, prep.usedOneTimePreKeyId, "el OPK sigue disponible tras el rechazo")
        val sesion = prep.commit()
        assertEquals(42L, sesion.consumedOneTimePreKeyId, "y se consume al confirmar, no antes")
    }

    /**
     * El rechazo ESTRUCTURAL: longitud distinta de la que exige el papel.
     *
     * Se afirma el tipo, el mensaje Y que no se ha llegado a cifrar. Los tres
     * hacen falta: el `require` de la propia primitiva lanza el mismo tipo con
     * otro mensaje DESPUES de haber hecho trabajo, y un test que solo mirase
     * el tipo no distinguiria una contencion de una excepcion de proceso.
     */
    private fun rechazoEstructural(que: String, etiqueta: String, bloque: () -> Unit) {
        x.reinicia()
        k.reinicia()
        val e = try {
            bloque()
            null
        } catch (t: Throwable) {
            t
        }
        assertNotNull(e, "$que: el material de longitud invalida tiene que rechazarse")
        assertEquals(
            IllegalArgumentException::class.java,
            e!!.javaClass,
            "$que: el rechazo estructural conserva su tipo historico",
        )
        assertTrue(
            e.message.orEmpty().contains(etiqueta) && e.message.orEmpty().contains("32"),
            "$que: el motivo viene de la frontera y nombra el papel ('$etiqueta'): ${e.message}",
        )
        sinConsumir("$que")
    }

    /** Ejecuta y devuelve la CLASE EXACTA de lo que sale, incluido lo que no sale. */
    private fun claseDe(bloque: () -> Unit): Class<*>? = try {
        bloque()
        null
    } catch (t: Throwable) {
        t.javaClass
    }
}
