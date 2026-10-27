package com.km.identity

import com.km.crypto.Ed25519Impl
import com.km.crypto.provider.BcX25519
import com.km.model.IdentityId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `KM-LOCALKEYS-1`: se lee lo que se escribe, y el `deviceId` se comprueba al leer. */
class LocalDeviceKeysTest {

    private val signing = Ed25519Impl().generateKeyPair()

    private fun material(opks: Int = 3) =
        LocalDeviceKeyFactory(signing.publicKey, BcX25519()).generate(opks)

    @Test
    fun `codifica y decodifica sin perder un solo byte`() {
        val original = material(3)
        val texto = LocalDeviceKeyCodec.encode(original)
        assertTrue(texto.startsWith("KM-LOCALKEYS-1\n"))

        val leido = LocalDeviceKeyCodec.decode(texto, signing.publicKey)
        assertContentEquals(original.deviceId, leido.deviceId)
        assertContentEquals(original.agreementKey.publicKey, leido.agreementKey.publicKey)
        assertContentEquals(original.agreementKey.privateKey, leido.agreementKey.privateKey)
        assertContentEquals(original.signedPreKey.publicKey, leido.signedPreKey.publicKey)
        assertEquals(original.oneTimePreKeys.keys, leido.oneTimePreKeys.keys)
    }

    @Test
    fun `un deviceId de otra identidad se RECHAZA al leer`() {
        val otro = Ed25519Impl().generateKeyPair()
        val texto = LocalDeviceKeyCodec.encode(LocalDeviceKeyFactory(otro.publicKey).generate(3))
        val e = assertFailsWith<LocalDeviceKeyException> {
            LocalDeviceKeyCodec.decode(texto, signing.publicKey)
        }
        assertEquals(LocalDeviceKeyCodec.CODE_DEVICE_MISMATCH, e.code)
    }

    @Test
    fun `un formato desconocido o truncado se rechaza con codigo`() {
        assertEquals(
            LocalDeviceKeyCodec.CODE_CORRUPT,
            assertFailsWith<LocalDeviceKeyException> { LocalDeviceKeyCodec.decode("otra-cosa") }.code,
        )
        val lineas = LocalDeviceKeyCodec.encode(material(1)).split("\n")
        assertEquals(
            LocalDeviceKeyCodec.CODE_CORRUPT,
            assertFailsWith<LocalDeviceKeyException> {
                LocalDeviceKeyCodec.decode(lineas.take(2).joinToString("\n"))
            }.code,
        )
    }

    @Test
    fun `el OPK consumido desaparece Y PERSISTE en el almacen`() {
        val store = InMemoryLocalDeviceKeyStore(material(2))
        assertEquals(2, store.load()!!.oneTimePreKeys.size)

        val restante = store.consumeOneTimePreKey(0L)
        assertNotNull(restante)
        assertTrue(!restante.oneTimePreKeys.containsKey(0L))
        assertTrue(!store.load()!!.oneTimePreKeys.containsKey(0L), "el consumo se persiste")

        // Idempotente: volver a consumirlo no cambia nada ni borra otros.
        assertEquals(1, store.consumeOneTimePreKey(0L)!!.oneTimePreKeys.size)
        assertNull(store.consumeOneTimePreKey(99L)!!.oneTimePreKeys[99L])
    }

    @Test
    fun `responder sin OPK es null explicito, no un default`() {
        val keys = material(1)
        assertNull(keys.responderKeys(null).oneTimePreKey)
        assertNotNull(keys.responderKeys(0L).oneTimePreKey)
        // Un keyId que no esta NO se degrada en silencio a "sin DH4": cambiaria
        // el SK derivado. El framework lo rechaza y aqui se comprueba.
        assertFailsWith<IllegalArgumentException> { keys.responderKeys(7L) }
    }

    @Test
    fun `un dispositivo sin OPK no se genera`() {
        assertFailsWith<IllegalArgumentException> { material(0) }
    }

    @Test
    fun `el material viejo de la app se puede leer`() {
        // El formato es el mismo que escribia la app: version, deviceId, dos
        // pares y una linea opk: por cada OPK.
        val keys = material(1)
        val textoApp = LocalDeviceKeyCodec.encode(keys)
        val leido = LocalDeviceKeyCodec.decode(textoApp, signing.publicKey)
        assertEquals(1, leido.oneTimePreKeys.size)
    }
}

/** La puerta de entrada: manual acepta la raiz, inbound exige `root == from`. */
class ContactRegistryTest {

    private val signing = Ed25519Impl().generateKeyPair()
    private val local = IdentityId(KmIds.identityId(signing.publicKey).hex())

    private fun portador(): String {
        val keys = LocalDeviceKeyFactory(signing.publicKey).generate(3)
        return SelfBundleFactory.export(signing.publicKey, signing.privateKey, keys).text
    }

    private fun intake(registry: ContactRegistry = InMemoryContactRegistry(), identidad: IdentityId? = local) =
        registry to ContactIntake(registry) { identidad }

    @Test
    fun `import manual registra bajo la raiz del bundle`() {
        val (registry, intake) = intake()
        val resultado = intake.manual(portador())
        val accepted = resultado as RegistrationResult.Accepted
        assertTrue(accepted.isNew)
        assertEquals(local, accepted.contact.identityRoot)
        assertEquals(1, registry.all().size)
    }

    @Test
    fun `importar dos veces el mismo bundle no duplica`() {
        val (registry, intake) = intake()
        intake.manual(portador())
        val segunda = intake.manual(portador()) as RegistrationResult.Accepted
        assertTrue(!segunda.isNew)
        assertEquals(1, registry.all().size)
    }

    @Test
    fun `inbound exige que la raiz SEA el from y no registra nada si no`() {
        val (registry, intake) = intake()
        val ajeno = IdentityId("ab".repeat(32))
        val e = assertFailsWith<ContactImportException> { intake.inbound(ajeno, portador()) }
        assertEquals(RejectionCodes.BUNDLE_ROOT_MISMATCH, e.code)
        assertEquals(0, registry.all().size)

        val ok = intake.inbound(local, portador()) as RegistrationResult.Accepted
        assertEquals(local, ok.contact.identityRoot)
    }

    @Test
    fun `sin identidad local el fallo es estable y NO hay escritura`() {
        val (registry, intake) = intake(identidad = null)
        val e = assertFailsWith<ContactImportException> { intake.manual(portador()) }
        assertEquals(RejectionCodes.MISSING_LOCAL_IDENTITY, e.code)
        assertEquals(0, registry.all().size)

        val eInbound = assertFailsWith<ContactImportException> { intake.inbound(local, portador()) }
        assertEquals(RejectionCodes.MISSING_LOCAL_IDENTITY, eInbound.code)
        assertEquals(0, registry.all().size)
    }

    @Test
    fun `un sobre vacio y uno corrupto tienen codigo estable`() {
        val (_, intake) = intake()
        assertEquals(
            RejectionCodes.EMPTY_ENVELOPE,
            assertFailsWith<ContactImportException> { intake.manual("  ") }.code,
        )
        assertEquals(
            CarrierCodec.CODE_MALFORMED,
            assertFailsWith<ContactImportException> { intake.manual("no-es-un-portador") }.code,
        )
    }

    @Test
    fun `upsert reverifica y rechaza un texto manipulado`() {
        val (registry, intake) = intake()
        val contacto = (intake.manual(portador()) as RegistrationResult.Accepted).contact
        val roto = contacto.copy(bundleText = contacto.bundleText.dropLast(4) + "XXXX")
        assertFailsWith<Exception> { registry.upsert(roto) }
        assertEquals(1, registry.all().size)
    }

    @Test
    fun `rejection codes es TOTAL sobre los tipos conocidos`() {
        assertEquals(RejectionCodes.EMPTY_ENVELOPE, RejectionCodes.of(IllegalArgumentException("x")))
        assertEquals(RejectionCodes.IMPORT_FAILED, RejectionCodes.of(RuntimeException("x")))
        assertEquals(
            RejectionCodes.FILE_TOO_LARGE,
            RejectionCodes.of(CarrierFileCodec.CarrierFileException(CarrierFileCodec.CODE_TOO_LARGE, "x")),
        )
        assertEquals(
            CarrierCodec.CODE_INFLATED_TOO_LARGE,
            RejectionCodes.of(Kce.KceException(CarrierCodec.CODE_INFLATED_TOO_LARGE, "x")),
        )
    }
}


/** Hex de un `ByteArray`, local al test. */
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
