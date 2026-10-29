package com.example.keymessage.contact

import com.example.keymessage.support.TestWorld
import com.km.identity.RegistrationResult
import com.km.identity.RejectionCodes
import com.km.model.IdentityId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InboundContactRegistrarTest {
    private val parBob = TestWorld.deviceWithRoot("bob")
    private val parMallory = TestWorld.deviceWithRoot("mallory")
    private val bobRoot = parBob.root
    private val malloryRoot = parMallory.root
    private val bob = parBob.device

    private fun bundleDeBob(): String {
        bob.issueOneTimePreKey(TestWorld.BOB_ONE_TIME_PREKEY_ID)
        return bobRoot.bundleTextFor(listOf(bob), bob, listOf(TestWorld.BOB_ONE_TIME_PREKEY_ID))
    }

    @Test
    fun `un mensaje de un par desconocido lo registra automaticamente`() {
        val libro = InMemoryContactBook()
        val registrar = InboundContactRegistrar(libro) { IdentityId(bobRoot.identityId.value) }
        val contacto = registrar.registerIfUnknown(IdentityId(bobRoot.identityId.value), bundleDeBob())
        assertNotNull(contacto, "el par desconocido queda registrado tras el primer mensaje")
        assertEquals(1, libro.all().size, "y aparece en el libro de contactos")
        assertEquals(bobRoot.identityId.value, libro.all().single().identityRoot.value)
    }

    @Test
    fun `un par ya conocido no se duplica`() {
        val libro = InMemoryContactBook()
        val registrar = InboundContactRegistrar(libro) { IdentityId(bobRoot.identityId.value) }
        val bundle = bundleDeBob()
        registrar.registerIfUnknown(IdentityId(bobRoot.identityId.value), bundle)
        registrar.registerIfUnknown(IdentityId(bobRoot.identityId.value), bundle)
        assertEquals(1, libro.all().size, "registrar dos veces no duplica el contacto")
    }

    @Test
    fun `un bundle que no verifica NO registra al par`() {
        val libro = InMemoryContactBook()
        val registrar = InboundContactRegistrar(libro) { IdentityId(bobRoot.identityId.value) }
        val resultado = registrar.registerIfUnknown(IdentityId(bobRoot.identityId.value), "no-es-un-sobre")
        assertNull(resultado, "un bundle invalido no registra nada")
        assertEquals(0, libro.all().size, "y el libro queda vacio")
    }

    @Test
    fun `un bundle de otro par NO se registra bajo el emisor`() {
        val libro = InMemoryContactBook()
        val registrar = InboundContactRegistrar(libro) { IdentityId(bobRoot.identityId.value) }
        val resultado = registrar.registerIfUnknown(
            IdentityId(bobRoot.identityId.value),
            malloryRoot.bundleTextFor(listOf(parMallory.device), parMallory.device, emptyList()),
        )
        assertNull(resultado, "un bundle cuya raiz no coincide con el emisor se rechaza")
        assertEquals(0, libro.all().size)
    }

    @Test
    fun `una raiz que NO es la del emisor se rechaza con el codigo BUNDLE_ROOT_MISMATCH`() {
        // El codigo ESTABLE de R6. No es un "rechazo cualquiera": el bundle
        // VERIFICA, lo que no cuadra es DE QUIEN es. Eso es un intento de
        // suplantacion, y hay que poder distinguirlo de un bundle roto.
        val libro = InMemoryContactBook()
        val registrar = InboundContactRegistrar(libro) { IdentityId(bobRoot.identityId.value) }
        val resultado = registrar.register(
            IdentityId(bobRoot.identityId.value),
            malloryRoot.bundleTextFor(listOf(parMallory.device), parMallory.device, emptyList()),
        )

        assertTrue(resultado is RegistrationResult.Rejected, "no se registra: $resultado")
        assertEquals(
            RejectionCodes.BUNDLE_ROOT_MISMATCH,
            (resultado as RegistrationResult.Rejected).code,
        )
        assertEquals(0, libro.all().size, "y no queda ningun contacto")
    }

    @Test
    fun `un bundle valido del propio emisor se registra CON codigo de aceptacion`() {
        val libro = InMemoryContactBook()
        val registrar = InboundContactRegistrar(libro) { IdentityId(bobRoot.identityId.value) }
        val resultado = registrar.register(IdentityId(bobRoot.identityId.value), bundleDeBob())

        assertTrue(resultado is RegistrationResult.Accepted, "el bundle del emisor se registra: $resultado")
        assertEquals(bobRoot.identityId.value, resultado.contact!!.identityRoot.value)
        assertEquals(1, libro.all().size)
    }

    @Test
    fun `un bundle que no verifica se rechaza con el codigo de la EXCEPCION`() {
        // El codigo del rechazo sale de la EXCEPCION, no del TIPO de rechazo:
        // "no verifico" y "es de otro" son dos cosas distintas y se distinguen
        // por el codigo.
        val libro = InMemoryContactBook()
        val registrar = InboundContactRegistrar(libro) { IdentityId(bobRoot.identityId.value) }
        val resultado = registrar.register(IdentityId(bobRoot.identityId.value), "no-es-un-sobre")

        assertTrue(resultado is RegistrationResult.Rejected, "no se registra nada: $resultado")
        val codigo = (resultado as RegistrationResult.Rejected).code
        assertTrue(codigo.isNotEmpty(), "el rechazo tiene un codigo")
        assertTrue(
            codigo != RejectionCodes.BUNDLE_ROOT_MISMATCH,
            "y NO es el de la raiz distinta: son rechazos distintos",
        )
    }
}