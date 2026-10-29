package com.km.transport.contact

import com.km.crypto.Ed25519Impl
import com.km.frame.FrameType
import com.km.frame.SecureFrameSpec
import com.km.identity.ContactIntake
import com.km.identity.InMemoryContactRegistry
import com.km.identity.KmIds
import com.km.identity.LocalDeviceKeyFactory
import com.km.identity.RejectionCodes
import com.km.identity.RegistrationResult
import com.km.identity.SelfBundleFactory
import com.km.model.IdentityId
import com.km.transport.SignalDelivery
import com.km.transport.SignalOutcome
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La secuencia un-solo-scan, probada SIN rele: las tres piezas son funciones.
 *
 * Lo que se afirma aqui es el ORDEN y los finales, que es donde se miente al
 * usuario si se afloja: PREKEY solo tras Delivered, un reenvio solo tras
 * re-auth, y los fallos terminales sin reintento.
 */
class ContactExchangeBootstrapTest {

    private fun portador(): ByteArray {
        val signing = Ed25519Impl().generateKeyPair()
        val keys = LocalDeviceKeyFactory(signing.publicKey).generate(3)
        return SelfBundleFactory.export(signing.publicKey, signing.privateKey, keys).text
            .toByteArray(Charsets.ISO_8859_1)
    }

    @Test
    fun `SIGNAL sale ANTES de abrir sesion y el PREKEY solo tras Delivered`() = runBlocking {
        val orden = mutableListOf<String>()
        var prekeys = 0
        val bootstrap = ContactExchangeBootstrap(
            exportarPortador = { portador() },
            enviarPortador = { orden += "SIGNAL"; SignalOutcome.Delivered(1) },
            abrirSesionYEntregar = { orden += "PREKEY"; prekeys++; Result.success(Unit) },
        )
        assertTrue(bootstrap.ejecutar().isSuccess)
        assertEquals(listOf("SIGNAL", "PREKEY"), orden)
        assertEquals(1, prekeys)
    }

    @Test
    fun `sin Delivered NO hay PREKEY y el codigo es estable`() = runBlocking {
        var prekeys = 0
        val bootstrap = ContactExchangeBootstrap(
            exportarPortador = { portador() },
            enviarPortador = { SignalOutcome.Failed(SignalDelivery.CODE_PEER_OFFLINE, 4, "par ausente") },
            abrirSesionYEntregar = { prekeys++; Result.success(Unit) },
        )
        val e = assertFailsWith<ContactExchangeException> { bootstrap.ejecutar() }
        assertEquals(SignalDelivery.CODE_PEER_OFFLINE, e.code)
        assertEquals(0, prekeys, "cero PREKEY sin Delivered")
    }

    @Test
    fun `REFUSED y FAILED son terminales y no se reintentan`() = runBlocking {
        for (code in listOf(SignalDelivery.CODE_REFUSED, SignalDelivery.CODE_FAILED)) {
            var envios = 0
            var esperas = 0
            val bootstrap = ContactExchangeBootstrap(
                exportarPortador = { portador() },
                enviarPortador = { envios++; SignalOutcome.Failed(code, 1) },
                abrirSesionYEntregar = { error("no debe abrir sesion") },
                esperarReauth = { esperas++; true },
            )
            val e = assertFailsWith<ContactExchangeException> { bootstrap.ejecutar() }
            assertEquals(code, e.code)
            assertEquals(1, envios)
            assertEquals(0, esperas, "no se espera re-auth para un rechazo terminal")
        }
    }

    @Test
    fun `NOT_CONNECTED espera re-auth y reenvia el MISMO bundle UNA vez`() = runBlocking {
        val bundle = portador()
        val enviados = mutableListOf<ByteArray>()
        var prekeys = 0
        val bootstrap = ContactExchangeBootstrap(
            exportarPortador = { bundle },
            enviarPortador = { b ->
                enviados += b
                if (enviados.size == 1) SignalOutcome.Failed(SignalDelivery.CODE_NOT_CONNECTED, 1)
                else SignalOutcome.Delivered(1)
            },
            abrirSesionYEntregar = { prekeys++; Result.success(Unit) },
            esperarReauth = { true },
        )
        assertTrue(bootstrap.ejecutar().isSuccess)
        assertEquals(2, enviados.size)
        assertTrue(enviados[0].contentEquals(enviados[1]), "el reenvio es el MISMO portador")
        assertEquals(1, prekeys, "un solo PREKEY")
    }

    @Test
    fun `si la re-auth no llega, NO hay segundo envio ni PREKEY`() = runBlocking {
        var envios = 0
        val bootstrap = ContactExchangeBootstrap(
            exportarPortador = { portador() },
            enviarPortador = { envios++; SignalOutcome.Failed(SignalDelivery.CODE_NOT_CONNECTED, 1) },
            abrirSesionYEntregar = { error("no debe abrir sesion") },
            esperarReauth = { false },
        )
        val e = assertFailsWith<ContactExchangeException> { bootstrap.ejecutar() }
        assertEquals(SignalDelivery.CODE_NOT_CONNECTED, e.code)
        assertEquals(1, envios)
    }

    @Test
    fun `un segundo NOT_CONNECTED es terminal (no hay tercer envio)`() = runBlocking {
        var envios = 0
        val bootstrap = ContactExchangeBootstrap(
            exportarPortador = { portador() },
            enviarPortador = { envios++; SignalOutcome.Failed(SignalDelivery.CODE_NOT_CONNECTED, 1) },
            abrirSesionYEntregar = { error("no debe abrir sesion") },
            esperarReauth = { true },
        )
        val e = assertFailsWith<ContactExchangeException> { bootstrap.ejecutar() }
        assertEquals(SignalDelivery.CODE_NOT_CONNECTED, e.code)
        assertEquals(2, envios, "un unico reenvio")
    }

    @Test
    fun `un portador que no se puede exportar falla sin tocar el rele`() = runBlocking {
        var envios = 0
        val bootstrap = ContactExchangeBootstrap(
            exportarPortador = { throw IllegalStateException("sin material") },
            enviarPortador = { envios++; SignalOutcome.Delivered(1) },
            abrirSesionYEntregar = { error("no debe abrir sesion") },
        )
        val e = assertFailsWith<ContactExchangeException> { bootstrap.ejecutar() }
        assertEquals(ContactExchangeBootstrap.CODE_EXPORT_FAILED, e.code)
        assertEquals(0, envios)
    }
}

/** La retencion acotada de PREKEY: TTL, tope y drenaje. */
class ContactExchangeCoordinatorTest {

    private val a = IdentityId("aa".repeat(32))
    private val b = IdentityId("bb".repeat(32))

    /** El mismo criterio de cabecera que km-core, local al test. */
    private fun esPrekeyDeTest(wire: ByteArray): Boolean {
        if (wire.size < 2) return false
        if (wire[com.km.frame.SecureFrameSpec.VERSION_OFFSET].toUByte() != com.km.frame.SecureFrameSpec.VERSION) return false
        return com.km.frame.FrameType.fromCode(wire[com.km.frame.SecureFrameSpec.TYPE_OFFSET].toUByte()) ==
            com.km.frame.FrameType.PREKEY
    }

    private fun prekeyFrame(): ByteArray {
        val wire = ByteArray(48)
        wire[SecureFrameSpec.VERSION_OFFSET] = SecureFrameSpec.VERSION.toByte()
        wire[SecureFrameSpec.TYPE_OFFSET] = FrameType.PREKEY.code.toByte()
        return wire
    }

    @Test
    fun `un PREKEY de par conocido se entrega en el acto`() {
        val coordinador = ContactExchangeCoordinator(conocido = { true }, esPrekey = ::esPrekeyDeTest)
        var entregado = false
        assertTrue(coordinador.onPrekey(a, prekeyFrame()) { _, _ -> entregado = true })
        assertTrue(entregado)
        assertEquals(0, coordinador.retenidos())
    }

    @Test
    fun `un PREKEY de par desconocido se retiene y se drena al registrarlo`() {
        var conocido = false
        val coordinador = ContactExchangeCoordinator(conocido = { conocido }, esPrekey = ::esPrekeyDeTest)
        assertFalse(coordinador.onPrekey(a, prekeyFrame()) { _, _ -> })
        assertEquals(1, coordinador.retenidos())

        conocido = true
        var drenado = 0
        assertEquals(1, coordinador.onContactoRegistrado(a) { _, wire -> drenado += wire.size })
        assertEquals(48, drenado)
        assertEquals(0, coordinador.retenidos())
    }

    @Test
    fun `lo caducado a los 60 s NO se entrega nunca`() {
        var ahora = 0L
        val coordinador = ContactExchangeCoordinator(
            conocido = { false },
            esPrekey = ::esPrekeyDeTest,
            now = { ahora },
        )
        coordinador.onPrekey(a, prekeyFrame()) { _, _ -> }
        ahora = ContactExchangeCoordinator.TTL_MS + 1
        assertEquals(0, coordinador.retenidos())
        assertEquals(0, coordinador.onContactoRegistrado(a) { _, _ -> error("no debe entregarse") })
    }

    @Test
    fun `el tope de 16 expulsa al mas antiguo`() {
        val coordinador = ContactExchangeCoordinator(conocido = { false }, esPrekey = ::esPrekeyDeTest)
        val ids = (1..17).map { IdentityId("%064x".format(it)) }
        ids.forEach { coordinador.onPrekey(it, prekeyFrame()) { _, _ -> } }
        assertEquals(ContactExchangeCoordinator.MAX_RETENIDOS, coordinador.retenidos())
        // El primero ya no esta: su drenaje devuelve 0.
        assertEquals(0, coordinador.onContactoRegistrado(ids.first()) { _, _ -> })
        assertEquals(1, coordinador.onContactoRegistrado(ids.last()) { _, _ -> })
    }

    @Test
    fun `un frame que NO es PREKEY no se retiene`() {
        val coordinador = ContactExchangeCoordinator(conocido = { false }, esPrekey = ::esPrekeyDeTest)
        assertFalse(coordinador.onPrekey(a, ByteArray(48)) { _, _ -> })
        assertEquals(0, coordinador.retenidos())
    }

    @Test
    fun `un frame de par desconocido que se descarta se CUENTA con codigo estable`() {
        val lineas = mutableListOf<String>()
        val coordinador = ContactExchangeCoordinator(
            conocido = { false },
            esPrekey = ::esPrekeyDeTest,
            logger = { _, mensaje -> lineas += mensaje },
        )
        val frame = ByteArray(77) { 0x5a }

        assertFalse(coordinador.onPrekey(a, frame) { _, _ -> })

        assertEquals(1, coordinador.unknownDropped(), "un frame descartado, contado")
        assertEquals(1, lineas.size, lineas.toString())
        assertTrue(lineas.single().contains(ContactExchangeCoordinator.CODE_UNKNOWN_PEER_FRAME), lineas.toString())
        assertTrue(lineas.single().contains("bytes=77"), lineas.toString())
        // Sin payload: el coordinador NUNCA recibe el contenido, pero el test
        // exige que tampoco lo reemita en la traza.
        assertFalse(lineas.single().contains("5a5a5a"), "la traza no lleva el cuerpo: ${lineas.single()}")
    }

    @Test
    fun `un PREKEY retenido NO cuenta como descarte`() {
        val coordinador = ContactExchangeCoordinator(conocido = { false }, esPrekey = ::esPrekeyDeTest)
        coordinador.onPrekey(a, prekeyFrame()) { _, _ -> }
        assertEquals(1, coordinador.retenidos())
        assertEquals(0, coordinador.unknownDropped(), "retener es el camino bueno, no un descarte")
    }

    @Test
    fun `un frame por encima del tope no se retiene`() {
        val coordinador = ContactExchangeCoordinator(conocido = { false }, esPrekey = ::esPrekeyDeTest)
        val grande = prekeyFrame() + ByteArray(ContactExchangeCoordinator.MAX_FRAME_BYTES)
        assertFalse(coordinador.onPrekey(a, grande) { _, _ -> })
        assertEquals(0, coordinador.retenidos())
    }

    @Test
    fun `el mismo emisor reemplaza su PREKEY, no acumula`() {
        val coordinador = ContactExchangeCoordinator(conocido = { false }, esPrekey = ::esPrekeyDeTest)
        coordinador.onPrekey(a, prekeyFrame()) { _, _ -> }
        coordinador.onPrekey(a, prekeyFrame()) { _, _ -> }
        assertEquals(1, coordinador.retenidos())
        assertEquals(0, coordinador.onContactoRegistrado(b) { _, _ -> })
    }
}

/** El registro entrante: root != from no registra, y un rechazo no lanza. */
class InboundContactRegistrarTest {

    private val firmante = Ed25519Impl().generateKeyPair()
    private val root = IdentityId(KmIds.identityId(firmante.publicKey).joinToString("") { "%02x".format(it) })

    private fun portador(): String {
        val keys = LocalDeviceKeyFactory(firmante.publicKey).generate(3)
        return SelfBundleFactory.export(firmante.publicKey, firmante.privateKey, keys).text
    }

    private fun registrar(registry: InMemoryContactRegistry = InMemoryContactRegistry()) =
        registry to InboundContactRegistrar(registry, ContactIntake(registry) { root })

    @Test
    fun `un SIGNAL de la raiz correcta registra UNA vez`() {
        val (registry, registrar) = registrar()
        val resultado = registrar.register(root, portador())
        assertTrue(resultado is RegistrationResult.Accepted)
        assertTrue((resultado as RegistrationResult.Accepted).isNew)
        assertEquals(1, registry.all().size)

        val segunda = registrar.register(root, portador())
        assertFalse((segunda as RegistrationResult.Accepted).isNew)
        assertEquals(1, registry.all().size)
    }

    @Test
    fun `root distinta del from NO registra y devuelve el codigo estable`() {
        val (registry, registrar) = registrar()
        val ajeno = IdentityId("cd".repeat(32))
        val resultado = registrar.register(ajeno, portador())
        assertEquals(RegistrationResult.Rejected(RejectionCodes.BUNDLE_ROOT_MISMATCH), resultado)
        assertEquals(0, registry.all().size)
        assertNull(registrar.registerIfUnknown(ajeno, portador()))
    }

    @Test
    fun `un bundle corrupto es un rechazo, no una excepcion`() {
        val (registry, registrar) = registrar()
        val resultado = registrar.register(root, "basura")
        assertTrue(resultado is RegistrationResult.Rejected)
        assertNotNull((resultado as RegistrationResult.Rejected).code)
        assertEquals(0, registry.all().size)
    }
}
