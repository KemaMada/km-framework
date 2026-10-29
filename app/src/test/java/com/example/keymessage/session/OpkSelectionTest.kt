package com.example.keymessage.session

import com.km.identity.ContactBundle
import com.km.messaging.LocalInitiatorKeys
import com.km.messaging.SecureSessionRegistry
import com.km.model.IdentityId
import com.example.keymessage.support.TestWorld
import com.example.keymessage.support.countingManager
import com.example.keymessage.util.toHex
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * FIX — el indice OPK se DERIVA del bundle, y la sesion E2E por QR existe.
 *
 * ## LA CONTRADICCION QUE ESTE TEST FIJA
 *
 * Fix #2 dejo `QR_ONE_TIME_PREKEYS = 0` en `SelfBundleExporter`: el bundle QR no
 * lleva OPK. Fix #6 abrio la sesion E2E por UI, pero `AppContainer.abrirSesion`
 * llamaba a `SessionCoordinator.iniciar` sin `oneTimePrekeyIndex`, que por
 * defecto es `0`. Sobre un bundle con 0 OPK, `BootstrapPrekeys.fromVerified`
 * LANZA ("el bundle lleva 0 one-time prekeys y se pidio el indice 0; pasa null
 * para una sesion sin DH4"), asi que la sesion no se abria NUNCA y todo envio
 * salia `AUTH_FAILED` antes de transmitir.
 *
 * La decision correcta es de COMPOSICION: mirar el bundle y pedir `null` cuando
 * no lleva OPK, `0` cuando si los lleva. Eso es [OpkSelection], y aqui se prueba
 * que (a) elige bien y (b) el indice elegido REALMENTE abre la sesion en las dos
 * configuraciones.
 */
class OpkSelectionTest {

    private val coordinator = SessionCoordinator()
    private val alicePar = TestWorld.deviceWithRoot("alice")
    private val alice = alicePar.device
    private val aliceSession = IdentityId(alice.deviceId.toHex())

    /** Bundle de Bob SIN ningun OPK: el que produce el QR (`QR_ONE_TIME_PREKEYS = 0`). */
    private fun bundleSinOpk() =
        ContactBundle.verified(alicePar.root.bundleMapFor(listOf(alice), alice, emptyList()))

    /** Bundle de Bob CON un OPK emitido: la via que si puede usar DH4. */
    private fun bundleConOpk(): com.km.identity.VerifiedContactBundle {
        val opkId = TestWorld.BOB_ONE_TIME_PREKEY_ID
        alice.issueOneTimePreKey(opkId)
        return ContactBundle.verified(
            alicePar.root.bundleMapFor(listOf(alice), alice, listOf(opkId)),
        )
    }

    @Test
    fun `bundle con CERO OPK elige null para una sesion sin DH4`() {
        assertNull(
            OpkSelection.paraIniciar(bundleSinOpk()),
            "sin OPK en el bundle, el indice es null y no el 0 por defecto",
        )
    }

    @Test
    fun `bundle con OPK elige el indice 0`() {
        assertEquals(
            0,
            OpkSelection.paraIniciar(bundleConOpk()),
            "con OPK disponible, se usa el 0: es lo que mejora el secreto",
        )
    }

    @Test
    fun `con el indice derivado, un bundle SIN OPK SI abre sesion`() {
        // Sin este arreglo, `iniciar` con el default 0 LANZABA sobre este bundle.
        // Aqui se comprueba que el indice que elige `OpkSelection` atraviesa
        // `iniciar` y produce una sesion viva con su frame PREKEY.
        val bundle = bundleSinOpk()
        val (manager, _) = countingManager()
        val iniciada = coordinator.iniciar(
            localIdentity = aliceSession,
            peerContext = coordinator.contextoHacia(bundle),
            localKeys = LocalInitiatorKeys(alice.deviceId, alice.agreementKey),
            manager = manager,
            registry = SecureSessionRegistry(aliceSession),
            oneTimePrekeyIndex = OpkSelection.paraIniciar(bundle),
        )
        assertNull(iniciada.usedOneTimePreKeyId, "sin OPK, la sesion no consume ninguno")
        assertEquals(
            true,
            iniciada.prekeyFrame.isNotEmpty(),
            "el frame PREKEY se produce aunque el bundle no lleve OPK",
        )
    }

    @Test
    fun `con el indice derivado, un bundle CON OPK usa y consume el OPK`() {
        val bundle = bundleConOpk()
        val (manager, _) = countingManager()
        val iniciada = coordinator.iniciar(
            localIdentity = aliceSession,
            peerContext = coordinator.contextoHacia(bundle),
            localKeys = LocalInitiatorKeys(alice.deviceId, alice.agreementKey),
            manager = manager,
            registry = SecureSessionRegistry(aliceSession),
            oneTimePrekeyIndex = OpkSelection.paraIniciar(bundle),
        )
        assertEquals(
            TestWorld.BOB_ONE_TIME_PREKEY_ID,
            iniciada.usedOneTimePreKeyId,
            "con OPK disponible, la sesion SI lo consume: DH4 ocurre",
        )
    }
}
