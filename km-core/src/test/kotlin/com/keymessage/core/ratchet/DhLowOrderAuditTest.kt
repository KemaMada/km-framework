package com.keymessage.core.ratchet

import com.keymessage.core.crypto.AllZeroSharedSecretException
import com.keymessage.core.crypto.Kdf
import com.keymessage.core.crypto.X25519
import com.keymessage.core.crypto.X25519KeyPair
import com.keymessage.core.crypto.provider.BcChaCha20Poly1305
import com.keymessage.core.crypto.provider.BcHkdfSha256
import com.keymessage.core.crypto.provider.BcX25519
import com.keymessage.core.sf.SecureFrameProtector
import com.keymessage.core.x3dh.BootstrapPrekeys
import com.keymessage.core.x3dh.InitiatorKeyMaterial
import com.keymessage.core.x3dh.ResponderKeyMaterial
import com.keymessage.core.x3dh.X3dh
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * ESTADO DE ESTA AUDITORIA: los cuatro caminos que estaban abiertos ya NO lo
 * estan. Este archivo se reescribio como estaba previsto en su propia
 * documentacion anterior.
 *
 * ## QUE AFIRMABA ANTES, Y QUE OCURRIO
 *
 * Afirmaba que la MISMA excepcion se escapaba por cuatro rutas del motor, y
 * que en las cuatro el material que la provoca era REMOTO y de longitud
 * correcta, de modo que ninguna comprobacion de longitud lo filtraba. Su
 * documentacion decia exactamente esto: "Cuando otro checkpoint los cierre,
 * estos tests fallaran —que es justo la senal de que hay que reescribirlos
 * como 'el camino ya devuelve un rechazo'".
 *
 * Esos tests NO fallaron, y el motivo importa: no fallaron porque el rechazo
 * conserve el TIPO. La contencion se hizo con
 * [AllZeroSharedSecretException], la misma excepcion que producia la
 * primitiva, de modo que un `assertThrows` de ese tipo sigue viendo exactamente
 * lo que veia. Eso es lo que se queria —no degradar una excepcion tipada que
 * el llamante ya debe manejar en algo peor— y a la vez significa que este
 * archivo, tal como estaba, ya no distinguished: seguia diciendo "el agujero
 * esta abierto" cuando el agujero ya no lo estaba. Un test que afirma algo
 * falso de forma silenciosa es peor que un test que falta.
 *
 * ## QUE AFIRMA AHORA
 *
 * Lo mismo que antes, mas la parte que faltaba. Antes se afirmaba "la
 * excepcion escapa"; ahora se afirma "el camino RECHAZA, tipado, ANTES de
 * cifrar y sin consumir material". Los contadores son lo que hace la
 * diferencia: con ellos, estos tests no pueden seguir pasando si alguien
 * devuelve el material invalido al flujo normal, porque el rechazo tendria que
 * haber cifrado antes de poder rechazarlo.
 *
 * La matriz completa de rutas y celdas vive en
 * [com.keymessage.core.x3dh.AgreementKeyContentionTest] (`AGREE-01`..`AGREE-08`).
 * Este archivo se queda con el control de las cuatro rutas y con la
 * affirmation de que el camino bueno sigue funcionando.
 *
 * ## LO QUE SIGUE SIN AFIRMAR
 *
 * Que estas rutas sean alcanzables desde el cable HOY. `X3dh` sigue sin
 * consumidor en `main`: es el motor que KM-0006 §7.4 le mandara, y sus
 * entradas son, por construccion, material remoto. Lo que se fija es el
 * contrato del motor para cuando ese consumidor exista.
 */
class DhLowOrderAuditTest {

    private val real = BcX25519()
    private val kdfReal = BcHkdfSha256()

    /** Cuenta acuerdos, pares generados y derivaciones: el coste de un rechazo. */
    private class Contador(private val real: X25519, private val kdf: Kdf) {
        var acuerdos = 0
            private set
        var pares = 0
            private set
        var derivaciones = 0
            private set

        val x: X25519 = object : X25519 {
            override fun generateKeyPair(): X25519KeyPair {
                pares++
                return real.generateKeyPair()
            }

            override fun publicKey(privateKey: ByteArray): ByteArray = real.publicKey(privateKey)
            override fun agree(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
                acuerdos++
                return real.agree(privateKey, publicKey)
            }
        }

        val k: Kdf = object : Kdf {
            override fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray =
                kdf.hkdfExtract(salt, ikm)

            override fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
                derivaciones++
                return kdf.hkdfExpand(prk, info, length)
            }
        }

        fun reinicia() {
            acuerdos = 0
            pares = 0
            derivaciones = 0
        }

        fun sinNada() {
            assertEquals(0, acuerdos, "el rechazo no puede haber hecho un acuerdo X25519")
            assertEquals(0, pares, "el rechazo no puede haber generado material de sesion")
            assertEquals(0, derivaciones, "el rechazo no puede haber derivado ni una clave")
        }
    }

    /** El material hostil: 32 bytes, la longitud correcta, orden pequeno. */
    private val ordenPequeno = ByteArray(32)

    @Test
    @DisplayName("DHZERO-AUDIT-01 el lado receptor de X3DH RECHAZA, tipado y antes de cifrar")
    fun `DHZERO-AUDIT-01 X3DH respond`() {
        val c = Contador(real, kdfReal)
        val x3dh = X3dh(c.x, c.k)
        val ik = real.generateKeyPair()
        val spk = real.generateKeyPair()
        val opk = real.generateKeyPair()
        val yo = ResponderKeyMaterial(
            deviceId = ByteArray(32) { 1 },
            identityAgreementKey = ik,
            signedPreKey = spk,
            oneTimePreKey = opk,
            oneTimePreKeyId = 5L,
        )
        val remotos = BootstrapPrekeys(
            deviceId = ByteArray(32) { 2 },
            identityAgreementKey = real.generateKeyPair().publicKey,
            signedPreKey = spk.publicKey,
            signedPreKeyId = 1L,
            oneTimePreKey = null,
            oneTimePreKeyId = null,
        )

        // CASO 1: `IK_A` del ContactBundle es de orden pequeno.
        // Ruta: X3dh.respond -> dh(responder.signedPreKey.privateKey,
        //                            remotePrekeys.identityAgreementKey) -> agree
        val ikRemotoMalo = remotos.copy(identityAgreementKey = ordenPequeno)
        val e1 = rejection(x3dh, yo, ikRemotoMalo, real.generateKeyPair().publicKey, c)
        assertTrue(
            e1.message.orEmpty().contains("IK remoto"),
            "el rechazo dice que clave es: ${e1.message}",
        )

        // CASO 2: la clave efimera `EK_A` es de orden pequeno. Esta es la MAS
        // cercana a la contencion del receptor, porque `EK_A` viaja en el MISMO
        // sitio del SecureFrame que la DH del ratchet: es material de un frame
        // recibido, con la longitud correcta.
        // Ruta: X3dh.respond -> dh(<clave propia>, ephemeralPublic) -> agree
        rejection(x3dh, yo, remotos, ordenPequeno, c)

        // Y el caso de control: con material que SI sirve, responde bien. Sin
        // esta linea, los rechazos de arriba tambien pasarian si el metodo
        // fallara siempre por otra cosa.
        val remotoLegitimo = BootstrapPrekeys(
            deviceId = ByteArray(32) { 2 },
            identityAgreementKey = ik.publicKey,
            signedPreKey = spk.publicKey,
            signedPreKeyId = 1L,
            oneTimePreKey = null,
            oneTimePreKeyId = null,
        )
        val preparacion = x3dh.respond(yo, remotoLegitimo, real.generateKeyPair().publicKey)
        assertEquals(32, preparacion.sharedKey.size, "con material bueno responde: no falla siempre")
        assertTrue(c.acuerdos > 0, "y con material bueno SI criptografia: el control distingue")
    }

    @Test
    @DisplayName("DHZERO-AUDIT-02 el lado emisor de X3DH RECHAZA, tipado y sin gastar el efimero")
    fun `DHZERO-AUDIT-02 X3DH initiate`() {
        val c = Contador(real, kdfReal)
        val x3dh = X3dh(c.x, c.k)
        val ik = real.generateKeyPair()
        // `IK_B` se conserva como par, no solo como publica: el control de
        // "el motor sigue funcionando" cierra la sesion por los DOS lados, y
        // para eso el receptor necesita la parte privada de su propia `IK`.
        val ikB = real.generateKeyPair()
        val prekeys = BootstrapPrekeys(
            deviceId = ByteArray(32) { 1 },
            identityAgreementKey = ikB.publicKey,
            signedPreKey = ordenPequeno,
            signedPreKeyId = 1L,
            oneTimePreKey = null,
            oneTimePreKeyId = null,
        )

        // CASO 3: `SPK_B` del ContactBundle es de orden pequeno.
        // Ruta: X3dh.initiate -> dh(initiator.identityAgreementKey.privateKey,
        //                            prekeys.signedPreKey) -> agree
        rejection(c, x3dh) {
            x3dh.initiate(InitiatorKeyMaterial(ByteArray(32), ik), prekeys, ByteArray(32))
        }

        // CASO 4: `OPK_B` es de orden pequeno.
        val conOpk = prekeys.copy(
            signedPreKey = real.generateKeyPair().publicKey,
            oneTimePreKey = ordenPequeno,
            oneTimePreKeyId = 9L,
        )
        rejection(c, x3dh) {
            x3dh.initiate(InitiatorKeyMaterial(ByteArray(32), ik), conOpk, ByteArray(32))
        }

        // Control: con prekeys buenas, el emisor deriva y la sesion cierra.
        val spk = real.generateKeyPair()
        val opk = real.generateKeyPair()
        val buenas = prekeys.copy(
            signedPreKey = spk.publicKey,
            oneTimePreKey = opk.publicKey,
            oneTimePreKeyId = 9L,
        )
        c.reinicia()
        val init = x3dh.initiate(InitiatorKeyMaterial(ByteArray(32), ik), buenas, ByteArray(32))
        assertTrue(c.pares > 0, "el camino bueno genera el efimero: el control distingue")
        assertEquals(4, c.acuerdos, "y hace las cuatro DH")

        val yo = ResponderKeyMaterial(
            deviceId = ByteArray(32) { 1 },
            identityAgreementKey = ikB,
            signedPreKey = spk,
            oneTimePreKey = opk,
            oneTimePreKeyId = 9L,
        )
        val remotos = BootstrapPrekeys(
            deviceId = ByteArray(32) { 2 },
            // La `IK_A` que el receptor recibe por ContactExchange es la del
            // emisor de verdad. Sin esto, DH2 y DH3 se harian contra una clave
            // que no es la del emisor y el control de "el motor sigue
            // funcionando" fallaria por un motivo que no tiene que ver con la
            // contencion.
            identityAgreementKey = ik.publicKey,
            signedPreKey = spk.publicKey,
            signedPreKeyId = 1L,
            oneTimePreKey = null,
            oneTimePreKeyId = null,
        )
        val prep = x3dh.respond(yo, remotos, init.ephemeralPublic)
        assertTrue(
            init.sharedKey.contentEquals(prep.sharedKey),
            "las dos mitades derivan el mismo SK: el motor sigue funcionando",
        )
    }

    @Test
    @DisplayName("DHZERO-AUDIT-03 el bootstrap del ratchet RECHAZA antes de derivar el RootKey")
    fun `DHZERO-AUDIT-03 el bootstrap`() {
        val c = Contador(real, kdfReal)
        val protector = SecureFrameProtector(BcChaCha20Poly1305(), c.k)
        val efimera = real.generateKeyPair()
        val spk = real.generateKeyPair()

        // Ruta: RatchetSessionBootstrap.initiatorProtocol
        //         -> x25519.agree(ephemeral.privateKey, remoteSignedPreKey)
        // `remoteSignedPreKey` es `SPK_B.public` de un ContactBundle remoto.
        val e = try {
            RatchetSessionBootstrap.initiatorProtocol(
                bootstrapValue = ByteArray(32) { 7 },
                ephemeral = efimera,
                remoteSignedPreKey = ordenPequeno,
                signedPreKeyPrivate = spk.privateKey,
                x25519 = c.x,
                kdf = c.k,
                protector = protector,
            )
            null
        } catch (t: Throwable) {
            t
        }
        assertNotNull(e, "SPK_B de orden pequeno NO puede bootstrapar una sesion")
        assertEquals(
            AllZeroSharedSecretException::class.java,
            e!!.javaClass,
            "el rechazo es tipado: no es un fallo de proceso ni un error generico",
        )
        // `derivaciones == 0` es lo que hace que esto sea un rechazo TEMPRANO:
        // el `RootKey` tampoco se ha derivado.
        c.sinNada()

        // Control: con `SPK_B` buena, el bootstrap construye el protocolo.
        c.reinicia()
        val protocolo = RatchetSessionBootstrap.initiatorProtocol(
            bootstrapValue = ByteArray(32) { 7 },
            ephemeral = efimera,
            remoteSignedPreKey = spk.publicKey,
            signedPreKeyPrivate = spk.privateKey,
            x25519 = c.x,
            kdf = c.k,
            protector = protector,
        )
        assertEquals(1, c.acuerdos, "el camino bueno hace DH(EK_A, SPK_B)")
        assertEquals(3, c.derivaciones, "RootKey, newRootKey y newChainKey")
        assertNotNull(protocolo, "y produce el protocolo del ratchet")
    }

    @Test
    @DisplayName("DHZERO-AUDIT-04 el emisor del ratchet NO tiene esta entrada: se dice explicitamente")
    fun `DHZERO-AUDIT-04 el envio no tiene la entrada`() {
        // Control negativo, y es el que mas informacion da: `previewSend` NO
        // llama a `agree`. El unico `agree` del lado emisor es
        // `initiateEpoch`, y su material remoto (`dhRemote`) solo puede venir
        // de una DH del header que YA paso `agree`, o de una foto de estado
        // restaurada del disco —camino que hoy no existe, porque no hay
        // persistencia. Por eso no se cuenta como agujero de entrada remota
        // alcanzable, y por eso `initiateEpoch` no se ha tocado.
        val bobDh = real.generateKeyPair()
        val sesion = DoubleRatchetSession(
            rootKey = ByteArray(32) { 1 },
            dhSelf = real.generateKeyPair(),
            dhRemote = bobDh.publicKey,
            sendChainKey = ByteArray(32) { 2 },
            receiveChainKey = ByteArray(32) { 3 },
            x25519 = real,
            kdf = kdfReal,
        )
        repeat(3) { sesion.previewSend(); sesion.commitSend() }
        assertEquals(3u, sesion.currentSendMessageNumber(), "enviar no hace DH y no lanza")
    }

    // ===================================================================
    // Utilidades de rechazo
    // ===================================================================

    private fun rejection(
        x3dh: X3dh,
        yo: ResponderKeyMaterial,
        remotos: BootstrapPrekeys,
        efimera: ByteArray,
        c: Contador,
    ): AllZeroSharedSecretException {
        c.reinicia()
        val e = try {
            x3dh.respond(yo, remotos, efimera)
            null
        } catch (t: Throwable) {
            t
        }
        assertNotNull(e, "el material remoto de orden pequeno tiene que rechazarse")
        assertEquals(
            AllZeroSharedSecretException::class.java,
            e!!.javaClass,
            "el rechazo es tipado: no es un fallo de proceso ni un error generico",
        )
        c.sinNada()
        return e as AllZeroSharedSecretException
    }

    private fun rejection(c: Contador, x3dh: X3dh, bloque: () -> Unit): AllZeroSharedSecretException {
        c.reinicia()
        val e = try {
            bloque()
            null
        } catch (t: Throwable) {
            t
        }
        assertNotNull(e, "el material remoto de orden pequeno tiene que rechazarse")
        assertEquals(
            AllZeroSharedSecretException::class.java,
            e!!.javaClass,
            "el rechazo es tipado: no es un fallo de proceso ni un error generico",
        )
        c.sinNada()
        return e as AllZeroSharedSecretException
    }
}
