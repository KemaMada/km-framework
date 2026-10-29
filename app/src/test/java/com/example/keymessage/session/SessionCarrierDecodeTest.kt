package com.example.keymessage.session

import com.km.identity.ContactBundle
import com.km.identity.Kce
import com.km.messaging.SecureSessionRegistry
import com.km.messaging.SendResult
import com.example.keymessage.contact.ContactImporter
import com.example.keymessage.contact.SelfBundleExporter
import com.example.keymessage.contact.VerifiedContact
import com.example.keymessage.identity.IdentityMaterial
import com.example.keymessage.identity.IdentityMaterialFactory
import com.example.keymessage.identity.LocalKeyFactory
import com.example.keymessage.identity.LocalKeyMaterial
import com.example.keymessage.support.countingManager
import com.example.keymessage.util.toHex
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * obs #308 — Abrir sesion con el portador GUARDADO de un QR, que esta COMPRIMIDO.
 *
 * ## EL BUG QUE ESTE TEST REPRODUCE
 *
 * `SelfBundleExporter.export` produce el `.kmc` con
 * [com.example.keymessage.contact.BundleCompression.compress]: el texto no es
 * base64url, son los bytes deflate leidos como ISO-8859-1. `ContactImporter.import`
 * lo descomprime y verifica, y **esa mitad funcionaba**. Lo que no funcionaba era
 * la OTRA mitad: el mismo texto comprimido se guardaba en
 * `VerifiedContact.bundleText`, y al abrir sesion se pasaba a
 * `ContactBundle.decodeAndVerify` SIN descomprimir. Base64Url rechazaba el primer
 * caracter fuera de su alfabeto (`U+00DA`) y la cadena entera colapsaba:
 *
 *   `ENVELOPE_MALFORMED: InvalidChar` -> sin sesion -> sin PREKEY ->
 *   `NO_SESSION` -> `AUTH_FAILED` en la pantalla.
 *
 * Los tests que existian (`PrekeyDeliveryTest`) construian el bundle con
 * `bundleMapFor` y verificaban en memoria: nunca pasaban por el PORTADOR, y por
 * eso no vieron la divergencia entre "lo que se exporta" y "lo que se decodifica
 * al abrir".
 *
 * ## POR QUE NO SE LLAMA A `AppContainer.abrirSesion` DIRECTAMENTE
 *
 * Porque el contenedor necesita un `Application` (Room, `Settings.Global`,
 * `EncryptedSharedPreferences`) y no existe en una JVM. Lo que se ejercita es el
 * punto UNICO por el que `abrirSesion` lee el portador
 * ([ContactImporter.verifiedBundle]) mas la misma composicion que hace despues
 * ([SessionCoordinator.iniciar] con [OpkSelection.paraIniciar]). El guard
 * estructural de `build/CarrierSingleDecodePointTest` cierra la mitad que un test
 * de JVM no puede ejecutar: que `AppContainer` no vuelva a decodificar el
 * portador por su cuenta.
 */
class SessionCarrierDecodeTest {

    /** Un remitente REAL: material generado, portador exportado como el QR. */
    private class Remitente(
        val identity: IdentityMaterial,
        val localKeys: LocalKeyMaterial,
        val exported: SelfBundleExporter.Exported,
    )

    /**
     * Material NUEVO en cada test: si el portador se fijara como constante, el
     * test diria "esta cadena concreta funciona" en vez de "un QR cualquiera se
     * puede releer".
     */
    private fun remitente(): Remitente {
        val identity = IdentityMaterialFactory().generate()
        val localKeys = LocalKeyFactory(identity.keyPair.publicKey).generate()
        val exported = SelfBundleExporter.export(
            identity = identity,
            localKeys = localKeys,
            maxOneTimePreKeys = SelfBundleExporter.QR_ONE_TIME_PREKEYS,
        )
        return Remitente(identity, localKeys, exported)
    }

    /**
     * REGRESION: el camino completo `export -> import -> abrir sesion`.
     *
     * Con la decodificacion cruda restaurada (`decodeAndVerify` sobre el texto
     * comprimido), la relectura falla con `ENVELOPE_MALFORMED` y este test cae en
     * la primera asercion.
     */
    @Test
    fun `el portador exportado se relee y abre sesion sin ENVELOPE_MALFORMED`() {
        val r = remitente()
        val contacto: VerifiedContact = ContactImporter.import(r.exported.text)

        // Este es el paso de `AppContainer.abrirSesion` ANTES de tocar el motor.
        // Se aisla el fallo para poder afirmar CUAL era, no solo que hubo uno.
        val relectura = runCatching { ContactImporter.verifiedBundle(contacto) }
        val fallo = relectura.exceptionOrNull()
        assertEquals(
            null,
            (fallo as? Kce.KceException)?.code,
            "el portador guardado NO se decodifica en crudo: ${fallo?.message}",
        )
        assertNull(fallo, "el portador de un QR exportado se puede releer: ${fallo?.message}")

        val bundle = relectura.getOrThrow()
        assertEquals(
            r.identity.identityId.value,
            contacto.identityRoot.value,
            "el bundle releido sigue siendo el del remitente",
        )
        assertEquals(
            contacto.subjectSigningKey,
            bundle.subjectSigningKey.toHex(),
            "y conserva la clave de firma con la que se autentican sus ACK",
        )

        // La composicion de `abrirSesion`, con las MISMAS piezas que la app usa.
        val coordinator = SessionCoordinator()
        val (manager, backend) = countingManager()
        val iniciada = coordinator.iniciar(
            localIdentity = r.identity.identityId,
            peerContext = coordinator.contextoHacia(bundle),
            localKeys = r.localKeys.initiatorKeys(),
            manager = manager,
            registry = SecureSessionRegistry(r.identity.identityId),
            oneTimePrekeyIndex = OpkSelection.paraIniciar(bundle),
        )

        assertNull(
            OpkSelection.paraIniciar(bundle),
            "el QR viaja con 0 OPK y la sesion se pide sin DH4: pedir el indice 0 lanzaria",
        )
        assertTrue(iniciada.prekeyFrame.isNotEmpty(), "hay un frame PREKEY que entregar")

        // Y la sesion es REAL: el frame sale por el canal de par, que es lo que
        // `abrirSesionYEntregar` hace justo despues.
        val sesion = iniciada.session
        manager.sendOffer(sesion.localIdentity, sesion.peerContext, "sdp-offer", sesion.remotePeerId).getOrThrow()
        manager.receiveAnswer(sesion.localIdentity, sesion.remotePeerId, "sdp-answer").getOrThrow()
        manager.markConnected(sesion.localIdentity, sesion.peerContext).getOrThrow()
        assertTrue(
            sesion.sendTranscript(iniciada.prekeyFrame) is SendResult.Ok,
            "el PREKEY de una sesion abierta desde el portador SALE",
        )
        assertTrue(backend.sent.isNotEmpty(), "y hay bytes en el cable, no solo un objeto en memoria")
    }

    /**
     * PRETESTO — el portador exportado NO es base64url.
     *
     * Sin esta afirmacion, "el test de regresion falla al revertir" podria ser
     * cierto por casualidad: aqui se fija que el atajo `decodeAndVerify(texto)`
     * es la decodificacion de OTRO formato, no una variante valida.
     */
    @Test
    fun `pretesto el portador exportado no es base64url`() {
        val r = remitente()

        val fallo = assertFailsWith<Kce.KceException> {
            ContactBundle.decodeAndVerify(r.exported.text)
        }
        assertEquals(
            "ENVELOPE_MALFORMED",
            fallo.code,
            "el portador esta comprimido: decodificarlo en crudo es el bug de obs #308",
        )
    }

    /**
     * El punto centralizado lee CUALQUIER portador, no uno de suerte: dos
     * remitentes distintos dan dos bundles distintos y los dos se releen.
     */
    @Test
    fun `dos portadores distintos se releen cada uno con su raiz`() {
        val uno = remitente()
        val otro = remitente()

        assertNotEquals(uno.exported.text, otro.exported.text, "los portadores son de verdad distintos")

        val contactoUno = ContactImporter.import(uno.exported.text)
        val contactoDos = ContactImporter.import(otro.exported.text)
        assertNotEquals(
            contactoUno.identityRoot,
            contactoDos.identityRoot,
            "y dan contactos distintos: el test no depende de una constante",
        )

        val bundleUno = ContactImporter.verifiedBundle(contactoUno)
        val bundleDos = ContactImporter.verifiedBundle(contactoDos)
        assertEquals(
            uno.identity.identityId.value,
            com.example.keymessage.support.TestWorld.identityOf(bundleUno.identityRoot).value,
            "el bundle releido del primero es el del primer remitente",
        )
        assertEquals(
            otro.identity.identityId.value,
            com.example.keymessage.support.TestWorld.identityOf(bundleDos.identityRoot).value,
            "y el del segundo, el del segundo",
        )
    }
}
