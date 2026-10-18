package com.km.desktop

import com.km.desktop.support.SecureTestIdentities
import com.km.identity.VerifiedContactBundle
import com.km.model.IdentityId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 2.3 — El resolver sale del bundle VERIFICADO, no de un Base64 suelto.
 *
 * ## QUE AFIRMA
 *
 * Tras `registerFromVerifiedBundle`, la clave que devuelve `signingKeyOf` —la
 * que `KeyMessageCoreImpl` usa para autenticar los `ACK`— es EXACTAMENTE la
 * `subjectSigningKey` del bundle verificado.
 *
 * ## POR QUE ESTO NO ES UN DETALLE DE FORMATO
 *
 * Porque hay dos caminos para dar de alta un peer, y solo uno verifica:
 *
 *  - `peer <identityIdHex> <claveBase64>` escribe una clave suelta y exige que
 *    derive ese `identityId`. Es la identidad de IDENTIDAD.
 *  - `registerFromVerifiedBundle` guarda la clave de FIRMA DE DISPOSITIVO de un
 *    bundle que ya paso por el verificador, indexada por la RAIZ que lo firma.
 *
 * Mezclarlos haria que un contacto verificado y un Base64 pegado compartieran
 * el mismo hueco, y entonces "registrado" dejaria de significar nada.
 */
class PeerResolverFromBundleTest {

    private val par = SecureTestIdentities.deviceWithRoot("bob")

    @Test
    fun `la clave del resolver es la subjectSigningKey del bundle verificado`() {
        val directorio = PeerKeyDirectory()
        val bundle: VerifiedContactBundle = VerifiedContactBundleHolder.verificado(par)

        val raiz = directorio.registerFromVerifiedBundle(bundle)

        assertNotNull(raiz, "el bundle verifica y se registra")
        assertEquals(par.root.identityId, requireNotNull(raiz), "bajo la identidad de la RAIZ que lo firma")
        assertArrayEquals(
            bundle.subjectSigningKey,
            directorio.signingKeyOf(raiz),
            "la clave que sale del resolver es la del bundle, byte a byte",
        )
    }

    @Test
    fun `el resolver entrega la MISMA clave para la raiz del bundle`() {
        val directorio = PeerKeyDirectory()
        val bundle = VerifiedContactBundleHolder.verificado(par)
        val raiz = requireNotNull(directorio.registerFromVerifiedBundle(bundle))

        assertArrayEquals(
            directorio.signingKeyOf(raiz),
            directorio.resolver()(raiz),
            "el resolver que consume el core devuelve lo mismo que la busqueda directa",
        )
    }

    @Test
    fun `la clave del bundle NO se confunde con la clave de IDENTIDAD`() {
        val directorio = PeerKeyDirectory()
        val bundle = VerifiedContactBundleHolder.verificado(par)
        val raiz = requireNotNull(directorio.registerFromVerifiedBundle(bundle))

        // `subjectSigningKey` es la clave de FIRMA DEL DISPOSITIVO, y de ella
        // deriva un `deviceId`, no un `identityId`. Los dos dominios son
        // distintos y confundirlos daria una clave de ruteo que nadie mas
        // puede derivar.
        assertNotEquals(
            raiz.value,
            com.km.identity.KmIds.identityId(bundle.subjectSigningKey).joinToString("") { "%02x".format(it) },
            "la clave de firma del dispositivo NO deriva la identidad de la raiz",
        )
        assertEquals(
            par.device.deviceId.toList(),
            bundle.subjectDeviceId.toList(),
            "de ella deriva el deviceId, que es el otro dominio",
        )
    }

    /**
     * CONTROL NEGATIVO: el camino Base64 NO acepta la clave del dispositivo.
     *
     * Este es el contraste que demuestra que los dos caminos estan separados: si
     * `registerEncoded` aceptara la clave del dispositivo bajo la raiz, un
     * contacto verificado podria entrar por la puerta sin verificar y este
     * directorio no seria distinto de un mapa cualquiera.
     */
    @Test
    fun `control negativo el camino Base64 RECHAZA la clave del dispositivo`() {
        val directorio = PeerKeyDirectory()
        val bundle = VerifiedContactBundleHolder.verificado(par)
        val enBase64 = java.util.Base64.getEncoder().encodeToString(bundle.subjectSigningKey)

        val problema = directorio.registerEncoded(par.root.identityId.value, enBase64)

        assertNotNull(
            problema,
            "esa clave NO deriva ese identityId, y el directorio lo dice en vez de guardarla",
        )
        assertTrue(
            requireNotNull(problema).contains("deriva"),
            "y el motivo habla de la derivacion, que es lo que falla: $problema",
        )
        assertTrue(
            par.root.identityId !in directorio.known(),
            "y no se guardo nada: el directorio no quedo con una identidad sin clave coherente",
        )
    }

    /**
     * CONTROL NEGATIVO: un bundle con un byte alterado NO entra.
     *
     * `registerFromBundleText` es la puerta que usa la TUI, y debe ir por
     * `decodeAndVerify`. Sin esta prueba, "el resolver sale del bundle
     * verificado" seria verdad tambien con un importador que no verificara.
     */
    @Test
    fun `control negativo un sobre alterado NO entra por la puerta de texto`() {
        val directorio = PeerKeyDirectory()
        val texto = par.root.bundleTextFor(listOf(par.device), par.device, emptyList())
        val corte = texto.length / 2
        val alterado = texto.substring(0, corte) +
            (if (texto[corte] == 'A') 'B' else 'A') +
            texto.substring(corte + 1)

        val fallo = runCatching { directorio.registerFromBundleText(alterado) }.exceptionOrNull()

        assertTrue(
            fallo is com.km.identity.KmIdVerificationException,
            "un sobre con un byte alterado se RECHAZA con el verificador de km-core, no se guarda: $fallo",
        )
        assertTrue(directorio.known().isEmpty(), "y no queda registrado nada")
    }

    /**
     * CONTROL NEGATIVO: una identidad de DISPOSITIVO tampoco es una de RAIZ.
     *
     * El `remotePeerId` de una conversacion es la raiz, y el registro se indexa
     * por raiz. Si aceptara el `deviceId`, dos conversaciones distintas
     * aparecerian con la misma clave de ruteo.
     */
    @Test
    fun `control negativo registrar por deviceId NO crea una segunda entrada`() {
        val directorio = PeerKeyDirectory()
        val bundle = VerifiedContactBundleHolder.verificado(par)
        requireNotNull(directorio.registerFromVerifiedBundle(bundle))

        val porDevice = IdentityId(bundle.subjectDeviceId.joinToString("") { "%02x".format(it) })

        assertNull(
            directorio.signingKeyOf(porDevice),
            "no hay clave bajo el deviceId: la clave de ruteo es la RAIZ",
        )
        assertFalse(
            porDevice in directorio.known(),
            "y el directorio no reporta un deviceId entre los pares conocidos",
        )
    }

    /** Hex de un `ByteArray` en el test. */
    private fun ByteArray.toHexTest(): String = joinToString("") { "%02x".format(it) }
}

/** Atajo para obtener un `VerifiedContactBundle` en los tests. */
object VerifiedContactBundleHolder {
    fun verificado(par: SecureTestIdentities.DeviceWithRoot): VerifiedContactBundle =
        VerifiedContactBundle.verified(
            par.root.bundleMapFor(listOf(par.device), par.device, emptyList()),
        )
}