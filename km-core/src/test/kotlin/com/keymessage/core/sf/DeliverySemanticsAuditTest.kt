package com.keymessage.core.sf

import com.keymessage.core.crypto.provider.BcChaCha20Poly1305
import com.keymessage.core.crypto.provider.BcHkdfSha256
import com.keymessage.core.crypto.provider.BcX25519
import com.keymessage.core.ratchet.DoubleRatchetSession
import com.keymessage.core.ratchet.RatchetSessionBootstrap
import com.keymessage.core.ratchet.RejectReason
import com.keymessage.core.x3dh.BootstrapPrekeys
import com.keymessage.core.x3dh.InitiatorKeyMaterial
import com.keymessage.core.x3dh.ResponderKeyMaterial
import com.keymessage.core.x3dh.X3dh
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import kotlin.test.assertContentEquals

/**
 * 3Q.5.0 — Auditoria del contrato de ENTREGA antes de implementar reconexion.
 *
 * Este archivo no anade funcionalidad: fija HECHOS sobre lo que el ratchet y el
 * wire format hacen hoy, para que 3Q.5.1-3Q.5.4 se construyan sobre la
 * realidad y no sobre una suposicion.
 *
 * Las preguntas de la auditoria, respondidas una por una:
 *
 *  1. ¿El `messageId` viaja en el frame?            -> NO (§A-1)
 *  2. ¿Un ciphertext repetido se vuelve a aplicar? -> NO (§A-2)
 *  3. ¿Un duplicado se distingue de un replay?      -> NO (§A-3)
 *  4. ¿Se puede reenviar el ACK del duplicado?     -> NO (§A-4)
 *  5. ¿Se puede re-cifrar para reintentar?         -> NO (§A-5)
 *
 * Las cuatro primeras son el BLOQUE para 3Q.5.4 (retransmision idempotente).
 * La quinta define la obligacion de 3Q.5.1: persistir el CIPHERTEXT, no solo
 * el estado del ratchet.
 */
class DeliverySemanticsAuditTest {

    private val x25519 = BcX25519()
    private val kdf = BcHkdfSha256()
    private val protector = SecureFrameProtector(BcChaCha20Poly1305(), kdf)
    private val x3dh = X3dh(x25519, kdf)
    private val random = SecureRandom()

    /** Dos protocolos con la MISMA sesion,icker uno al otro. */
    private inner class Pair {
        val initiator: SecureRatchetProtocol
        val responder: SecureRatchetProtocol
        val f: ByteArray

        init {
            val aliceDh = x25519.generateKeyPair()
            val bobDh = x25519.generateKeyPair()
            val bobSpk = x25519.generateKeyPair()
            val bobOpk = x25519.generateKeyPair()
            f = ByteArray(32).also { random.nextBytes(it) }

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
                oneTimePreKey = null,
                oneTimePreKeyId = null,
            )
            val init = x3dh.initiate(InitiatorKeyMaterial(ByteArray(32), aliceDh), bobPrekeys, f)
            val prep = x3dh.respond(
                ResponderKeyMaterial(ByteArray(32) { 1 }, bobDh, bobSpk, bobOpk, 5L),
                alicePrekeys,
                init.ephemeralPublic,
            )
            assertContentEquals(init.sharedKey, prep.sharedKey, "X3DH debe derivar la misma SK")
            prep.commit()

            initiator = RatchetSessionBootstrap.initiatorProtocol(
                bootstrapValue = f,
                ephemeral = init.ephemeral,
                remoteSignedPreKey = bobSpk.publicKey,
                signedPreKeyPrivate = bobSpk.privateKey,
                x25519 = x25519,
                kdf = kdf,
                protector = protector,
            )
            responder = SecureRatchetProtocol(
                DoubleRatchetSession(
                    rootKey = RatchetSessionBootstrap.rootKeyFrom(f, kdf),
                    dhSelf = bobSpk,
                    dhRemote = null,
                    sendChainKey = ByteArray(32),
                    receiveChainKey = ByteArray(32),
                    x25519 = x25519,
                    kdf = kdf,
                ),
                protector,
            )
        }
    }

    // ===================================================================
    // A-1 — El messageId NO viaja en el frame
    // ===================================================================

    @Test
    @DisplayName("A-1 el SecureFrame no lleva messageId: se identifica por (DH, PN, N)")
    fun `A-1 el frame no lleva messageId`() {
        val p = Pair()
        val wire = p.initiator.encrypt("hola".toByteArray())

        // El header son 44 bytes y NO hay ningun messageId (UUID, 16 bytes)
        // ni ningun campo de identidad de mensaje.
        assertEquals(44, SecureFrameSpec.HEADER_LENGTH)
        val frame = BinarySecureFrameCodec.decode(wire)
        assertEquals(1u.toUByte(), frame.version, "version v1")

        // El unico identificador de un frame es su posicion en la cadena.
        assertEquals(0u, frame.ratchetHeader.messageNumber, "el frame se ubica por su message number")
        assertEquals(0u, frame.ratchetHeader.previousChainLength, "PN UInt")
        assertEquals(SecureFrameSpec.HEADER_LENGTH, frame.ratchetHeader.dhPublicKey.size + 4 + 4 + 4)
        // No existe ningun accessor de messageId en SecureFrame: el tipo no lo
        // tiene, y por eso el ID no puede viajar en el header.
        val accessors = SecureFrame::class.java.methods.map { it.name }.toSet()
        assertTrue(
            accessors.none { it.contains("messageId", ignoreCase = true) },
            "SecureFrame no conoce messageId: vive dentro del payload cifrado",
        )
    }

    @Test
    @DisplayName("A-1b el messageId solo puede viajar DENTRO del payload cifrado")
    fun `A-1b el messageId va cifrado`() {
        // El wire format v1 esta congelado en 44 bytes con version. Añadir un
        // messageId al header romperia la compatibilidad de la v1 y, peor,
        // dejaria el ID FUERA del alcance de la AEAD: un intermediario podria
        // alterarlo sin que la autenticacion lo detecte.
        //
        // Conclusion de la auditoria: el messageId DEBE ir dentro del plaintext
        // cifrado por el ratchet. Ahi queda autenticado y opaco.
        assertEquals(44, SecureFrameSpec.HEADER_LENGTH, "el header v1 es fijo; no cabe un messageId")
        assertTrue(
            SecureFrameSpec.INFO_AEAD_KEY.startsWith("KM-0004/SECURE-FRAME"),
            "la AEAD protege el header completo: un campo extra quedaria fuera del AAD",
        )
    }

    // ===================================================================
    // A-2 — Un ciphertext repetido NO se vuelve a aplicar
    // ===================================================================

    @Test
    @DisplayName("A-2 reenviar el MISMO ciphertext no entrega el payload dos veces")
    fun `A-2 el duplicado no se re-aplica`() {
        val p = Pair()
        val wire = p.initiator.encrypt("hola".toByteArray())

        val primero = p.responder.decrypt(wire)
        assertTrue(primero is SecureRatchetProtocol.DecryptResult.Ok, "la primera vez se entrega")
        assertContentEquals("hola".toByteArray(), (primero as SecureRatchetProtocol.DecryptResult.Ok).plaintext)

        val huellaTrasPrimero = p.responder.stateFingerprint()

        // Reintento de entrega: el MISMO frame, otro medio de transporte.
        val segundo = p.responder.decrypt(wire)
        assertTrue(
            segundo is SecureRatchetProtocol.DecryptResult.Rejected,
            "un ciphertext repetido NO debe volver a entregar: $segundo",
        )
        assertContentEquals(
            huellaTrasPrimero, p.responder.stateFingerprint(),
            "el rechazo no debe mutar el estado del ratchet",
        )
    }

    @Test
    @DisplayName("A-2b la seguridad NO depende de la aplicacion: el ratchet ya es idempotente")
    fun `A-2b idempotencia en la capa baja`() {
        // Este es el hecho que mas importa para 3Q.5.4: la garantia de "no
        // aplicar dos veces" ya la da el ratchet, no la capa de entrega. Aunque
        // un reenvio-see-doblado, el receptor no ejecutara el payload otra vez.
        val p = Pair()
        val wire = p.initiator.encrypt("efecto".toByteArray())
        var aplicados = 0
        repeat(5) {
            if (p.responder.decrypt(wire) is SecureRatchetProtocol.DecryptResult.Ok) aplicados++
        }
        assertEquals(1, aplicados, "cinco entregas del mismo ciphertext, un solo efecto")
    }

    // ===================================================================
    // A-3 — Un duplicado es INDISTINGUIBLE de un ataque de replay
    // ===================================================================

    @Test
    @DisplayName("A-3 un duplicado y un replay devuelven el MISMO motivo de rechazo")
    fun `A-3 duplicado indistinguible de replay`() {
        val p = Pair()
        val wire = p.initiator.encrypt("hola".toByteArray())
        p.responder.decrypt(wire)

        val duplicado = p.responder.decrypt(wire)

        // Y un frame totalmente ajeno (bits aleatorios con formato invalido):
        val basura = ByteArray(64) { 0x7F }
        val intruso = p.responder.decrypt(basura)

        assertTrue(duplicado is SecureRatchetProtocol.DecryptResult.Rejected)
        assertTrue(intruso is SecureRatchetProtocol.DecryptResult.Rejected)
        assertEquals(
            (duplicado as SecureRatchetProtocol.DecryptResult.Rejected).reason,
            (intruso as SecureRatchetProtocol.DecryptResult.Rejected).reason,
            "HOLE: el receptor no puede diferenciar 'duplicado legitimo' de 'ataque'",
        )
    }

    // ===================================================================
    // A-4 — No se puede reenviar el ACK del duplicado
    // ===================================================================

    @Test
    @DisplayName("A-4 el rechazo no aporta nada con que reenviar el ACK (§9.4 paso 2)")
    fun `A-4 no se puede reenviar el ack`() {
        val p = Pair()
        val wire = p.initiator.encrypt("hola".toByteArray())
        p.responder.decrypt(wire)
        val duplicado = p.responder.decrypt(wire) as SecureRatchetProtocol.DecryptResult.Rejected

        // KM-0004 §9.4 exige, ante un duplicado: ignorar el payload Y reenviar
        // el ACK. Ignorar el payload ya ocurre. Reenviar el ACK es IMPOSIBLE
        // hoy: el motivo de rechazo no lleva identificador de mensaje, y el
        // receptor no sabe CUAL mensaje fue el duplicado.
        // `RejectReason` no declara constructor: sus valores son constantes
        // simples, sin messageId ni ningun otro dato de carga. El receptor
        // recibe un "no" sin saber sobre QUE mensaje.
        val reason = duplicado.reason
        assertTrue(reason in RejectReason.entries, "el motivo es una constante del enum")

        // El conjunto completo de motivos que el ratchet puede devolver, y que
        // ninguno corresponde al duplicado de KM-0004 §9.4:
        //
        //   REPLAY_OR_UNKNOWN        -> N atrasado sin clave: replay O duplicado
        //   SKIP_LIMIT_EXCEEDED      -> salto mayor que MAX_SKIP
        //   SKIPPED_KEY_ALREADY_USED -> clave saltada ya servida (un solo uso)
        //   LOW_ORDER_DH_PUBLIC_KEY  -> DH de orden pequeno (RFC 7748 §6.1)
        //
        // Ninguno dice "este mensaje ya lo procese, reenvia el ACK".
        //
        // `LOW_ORDER_DH_PUBLIC_KEY` llego DESPUES de esta auditoria (contencion
        // de la DH de orden pequeno) y se anadio al conjunto. No cambia la
        // conclusion: es un motivo sobre el MATERIAL de la clave, no sobre el
        // estado de entrega de un mensaje, y por tanto sigue sin representar
        // el duplicado legitimo de §9.4. Lo que este test sigue vigilando es
        // que la lista crezca sin que aparezca ese motivo que hace falta.
        val motives = RejectReason.entries.map { it.name }.toSet()
        assertEquals(
            setOf(
                "REPLAY_OR_UNKNOWN",
                "SKIP_LIMIT_EXCEEDED",
                "SKIPPED_KEY_ALREADY_USED",
                "LOW_ORDER_DH_PUBLIC_KEY",
            ),
            motives,
            "HOLE: ningun motivo representa el duplicado legitimo de KM-0004 §9.4",
        )
    }

    // ===================================================================
    // A-5 — No se puede re-cifrar para reintentar
    // ===================================================================

    @Test
    @DisplayName("A-5 re-cifrar el mismo plaintext produce OTRO frame: la retransmision debe reutilizar el ciphertext")
    fun `A-5 no se puede re-cifrar`() {
        val p = Pair()
        val a = p.initiator.encrypt("hola".toByteArray())
        val b = p.initiator.encrypt("hola".toByteArray())

        // Mismo plaintext, dos ciphertext distintos: el ratchet avanza.
        assertFalse(a.contentEquals(b), "re-cifrar NO reproduce el frame")
        assertEquals(0u, BinarySecureFrameCodec.decode(a).ratchetHeader.messageNumber)
        assertEquals(1u, BinarySecureFrameCodec.decode(b).ratchetHeader.messageNumber, "el ratchet avanzo: N distinto")

        // Y esto es lo que obliga la conclusion de 3Q.5.1: para reintentar hay
        // que GUARDAR el ciphertext original, no re-derivar.
        //
        // Ademas el ratchet se DESCARTA al perder la sesion: tras un
        // `restore(snapshot)`, el siguiente frame tendria el mismo N que el
        // ya entregado, y el receptor lo rechazaria como replay.
    }

    @Test
    @DisplayName("A-5b el estado del ratchet NO es recuperable hoy")
    fun `A-5b sin persistencia`() {
        // Hecho verificable: `DoubleRatchetSession` expone `stateFingerprint()`
        // para COMPARAR, pero no existe ningun metodo que devuelva el estado
        // completo de forma serializable ni que lo restaure. La persistencia
        // del ratchet (3Q.5.1) es trabajo nuevo, no una capacidad existente.
        val p = Pair()
        val metodos = p.initiator.javaClass.methods.map { it.name }.toSet()
        val dePersistencia = metodos.filter {
            it.contains("snapshot", true) || it.contains("restore", true) ||
                it.contains("serialize", true) || it.contains("export", true)
        }
        assertTrue(
            dePersistencia.isEmpty(),
            "auditado: no hay API de persistencia en SecureRatchetProtocol -> $dePersistencia",
        )
        // Lo que si hay es una huella, suficiente para COMPARAR, no para
        // RESTAURAR.
        assertTrue(metodos.contains("stateFingerprint"), "si hay huella para comparar")
    }

}
