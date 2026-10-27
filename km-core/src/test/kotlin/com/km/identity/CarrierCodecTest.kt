package com.km.identity

import com.km.model.IdentityId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El portador compartido: los DOS `.km` REALES de Android y todos sus bordes.
 *
 * Los goldens son byte-exactos (469 y 470 B) y prueban DECODE/IMPORT solamente:
 * faltan sus claves privadas, asi que no se puede abrir sesion con ellos. El E2E
 * usa identidades generadas (ver DesktopCrossClientE2ETest).
 */
class CarrierCodecTest {

    private fun golden(nombre: String): String =
        javaClass.getResourceAsStream("/carriers/$nombre")!!.readBytes().toString(Charsets.ISO_8859_1)

    private fun exportPropio(opks: Int = 0): SelfBundleFactory.Exported {
        val signing = com.km.crypto.Ed25519Impl().generateKeyPair()
        val keys = LocalDeviceKeyFactory(signing.publicKey).generate(3)
        return SelfBundleFactory.export(signing.publicKey, signing.privateKey, keys, maxOneTimePreKeys = opks)
    }

    // ------------------------------------------------------------------
    // Goldens de Android: lo que hoy NO importaba en el escritorio
    // ------------------------------------------------------------------

    @Test
    fun `el golden de 469 B de Android se decodifica y verifica`() {
        val text = golden("s8plus-1e9a73f21347.km")
        assertEquals(469, CarrierCodec.bytes(text), "el portador real ocupa 469 bytes ISO-8859-1")

        val bundle = CarrierCodec.decode(text)
        val root = IdentityId(KmIds.identityId(bundle.identityRoot).hex())
        assertEquals("1e9a73f21347", root.value.take(12))
        assertTrue(bundle.subjectSigningKey.isNotEmpty())
        assertEquals(0, bundle.oneTimePrekeyCount(), "un QR de Android lleva 0 OPK")
        assertNull(bundle.initiatorOneTimePrekeyIndex)
    }

    @Test
    fun `el golden de 470 B de Android se decodifica y verifica`() {
        val text = golden("mediumphone-aeb864889b56.km")
        assertEquals(470, CarrierCodec.bytes(text))

        val bundle = CarrierCodec.decode(text)
        val root = IdentityId(KmIds.identityId(bundle.identityRoot).hex())
        assertEquals("aeb864889b56", root.value.take(12))
        assertEquals(0, bundle.oneTimePrekeyCount())
    }

    @Test
    fun `el golden sigue verificando con su raiz esperada y no con otra`() {
        val text = golden("s8plus-1e9a73f21347.km")
        val bundle = CarrierCodec.decode(text)
        assertNotNull(CarrierCodec.decode(text, bundle.identityRoot))

        val otra = exportPropio()
        val otraRaiz = CarrierCodec.decode(otra.text).identityRoot
        assertFailsWith<KmIdVerificationException> {
            CarrierCodec.decode(text, otraRaiz)
        }
    }

    // ------------------------------------------------------------------
    // Bytes y limites
    // ------------------------------------------------------------------

    @Test
    fun `encode produce ISO-8859-1 y bytes cuenta uno por caracter`() {
        val exportado = exportPropio()
        assertContentEquals(
            exportado.text.toByteArray(Charsets.ISO_8859_1),
            CarrierCodec.toBytes(exportado.text),
        )
        assertEquals(exportado.text.length, CarrierCodec.bytes(exportado.text))
    }

    @Test
    fun `un portador de mas de 16 KiB se rechaza ANTES de inflar`() {
        val grande = "x".repeat(CarrierCodec.MAX_CARRIER_BYTES + 1)
        val e = assertFailsWith<Kce.KceException> { CarrierCodec.decode(grande) }
        assertEquals(CarrierCodec.CODE_CARRIER_TOO_LARGE, e.code)
    }

    @Test
    fun `una bomba de descompresion se corta en 1 MiB`() {
        // Un stream zlib de ceros: comprime a poquisimos bytes y expande sin fin.
        val ceros = ByteArray(CarrierCodec.MAX_INFLATED_BYTES + 4096)
        val e = assertFailsWith<Kce.KceException> { CarrierCodec.decompress(CarrierCodec.encode(ceros)) }
        assertEquals(CarrierCodec.CODE_INFLATED_TOO_LARGE, e.code)
    }

    @Test
    fun `bytes truncados y basura detras se rechazan con ENVELOPE_MALFORMED`() {
        val text = exportPropio().text
        val truncado = CarrierCodec.fromBytes(CarrierCodec.toBytes(text).copyOf(text.length / 2))
        assertEquals(
            CarrierCodec.CODE_MALFORMED,
            assertFailsWith<Kce.KceException> { CarrierCodec.decode(truncado) }.code,
        )

        val conBasura = CarrierCodec.fromBytes(CarrierCodec.toBytes(text) + byteArrayOf(0x7f))
        assertEquals(
            CarrierCodec.CODE_MALFORMED,
            assertFailsWith<Kce.KceException> { CarrierCodec.decode(conBasura) }.code,
        )
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    fun `un portador con bytes fuera de rango NO cuelga el inflater`() {
        // Regresion: un portador cuyo texto se codifico con OTRA regla (UTF-8)
        // llega aqui como bytes que el Inflater no puede consumir. Si el bucle
        // no detecta el atasco, se queda pidiendo `inflate` para siempre —un
        // byte de entrada y el proceso al 100%.
        val exportado = exportPropio()
        val utf8 = String(exportado.text.toByteArray(Charsets.ISO_8859_1), Charsets.ISO_8859_1)
        val mangado = String(exportado.text.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        assertNotNull(CarrierCodec.decode(utf8), "el texto correcto sigue leyendose")
        assertFailsWith<Kce.KceException> { CarrierCodec.decode(mangado) }
    }

    @Test
    fun `un salto de linea de pegado se tolera sin trim`() {
        val text = exportPropio().text
        assertNotNull(CarrierCodec.decode(text + "\n"))
        assertEquals(text.length + 1, CarrierCodec.bytes(text + "\n"))
    }

    @Test
    fun `un byte alterado del bundle rompe la verificacion`() {
        val exportado = exportPropio(opks = 1)
        // Tamper DETERMINISTA: se decodifica el KCE, se cambia un one-time
        // prekey publicado y se vuelve a codificar. El documento sigue siendo un
        // KCE valido y lo que deja de cuadrar es la FIRMA.
        val mapa = Kce.decode(CarrierCodec.decompress(exportado.text)) as MutableMap<String, Any?>
        val opks = (mapa["oneTimePrekeys"] as List<*>).toMutableList()
        val opk = LinkedHashMap(opks[0] as Map<String, Any?>)
        val clave = (opk["publicKey"] as ByteArray).copyOf()
        clave[0] = (clave[0].toInt() xor 0x01).toByte()
        opk["publicKey"] = clave
        opks[0] = opk
        mapa["oneTimePrekeys"] = opks

        val fallo = assertFailsWith<KmIdVerificationException> {
            CarrierCodec.decode(CarrierCodec.encode(Kce.encode(mapa)))
        }
        assertTrue(fallo.code.isNotEmpty())
    }

    @Test
    fun `la exportacion propia con OPK expone el indice 0`() {
        val exportado = exportPropio(opks = 2)
        val bundle = CarrierCodec.decode(exportado.text)
        assertEquals(2, bundle.oneTimePrekeyCount())
        assertEquals(0, bundle.initiatorOneTimePrekeyIndex)
        assertEquals(exportado.text, exportado.text)
    }

    @Test
    fun `dos exportaciones del mismo material dan el MISMO texto`() {
        val signing = com.km.crypto.Ed25519Impl().generateKeyPair()
        val keys = LocalDeviceKeyFactory(signing.publicKey).generate(3)
        val a = SelfBundleFactory.export(signing.publicKey, signing.privateKey, keys)
        val b = SelfBundleFactory.export(signing.publicKey, signing.privateKey, keys)
        assertEquals(a.text, b.text)
        assertEquals(a.oneTimePreKeyCount, b.oneTimePreKeyCount)
        assertNotEquals(0, a.byteLength)
    }

    @Test
    fun `un material de otro dispositivo no puede exportar`() {
        val signing = com.km.crypto.Ed25519Impl().generateKeyPair()
        val otro = com.km.crypto.Ed25519Impl().generateKeyPair()
        val keys = LocalDeviceKeyFactory(otro.publicKey).generate(3)
        assertFailsWith<IllegalArgumentException> {
            SelfBundleFactory.export(signing.publicKey, signing.privateKey, keys)
        }
    }
}

/** El `.km`: mismos bytes, tope en lectura y rechazos con codigo. */
class CarrierFileCodecTest {

    private fun portador(): String {
        val signing = com.km.crypto.Ed25519Impl().generateKeyPair()
        val keys = LocalDeviceKeyFactory(signing.publicKey).generate(3)
        return SelfBundleFactory.export(signing.publicKey, signing.privateKey, keys).text
    }

    private class Contador(private val origen: InputStream) : InputStream() {
        var entregados = 0
            private set

        override fun read(): Int = origen.read().also { if (it >= 0) entregados++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            origen.read(b, off, len).also { if (it > 0) entregados += it }
    }

    @Test
    fun `escribir y releer devuelve los MISMOS bytes ISO-8859-1`() {
        val text = portador()
        val bytes = ByteArrayOutputStream().also { CarrierFileCodec.write(it, text) }.toByteArray()
        assertContentEquals(text.toByteArray(Charsets.ISO_8859_1), bytes)
        assertEquals(text, CarrierFileCodec.read(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `el tope de 64 KiB se aplica en lectura sin tragarse el resto`() {
        val enorme = ByteArray(CarrierFileCodec.MAX_BYTES * 4) { 0x41 }
        val contador = Contador(ByteArrayInputStream(enorme))
        val e = assertFailsWith<CarrierFileCodec.CarrierFileException> {
            CarrierFileCodec.read(contador)
        }
        assertEquals(CarrierFileCodec.CODE_TOO_LARGE, e.code)
        assertTrue(contador.entregados < enorme.size, "no se lee el cuerpo entero para rechazarlo")
    }

    @Test
    fun `un fichero vacio se rechaza como EMPTY_ENVELOPE`() {
        val e = assertFailsWith<CarrierFileCodec.CarrierFileException> {
            CarrierFileCodec.read(ByteArrayInputStream(ByteArray(0)))
        }
        assertEquals(CarrierFileCodec.CODE_EMPTY, e.code)
        assertEquals(RejectionCodes.EMPTY_ENVELOPE, RejectionCodes.of(e))
    }

    @Test
    fun `exactamente el tope se acepta`() {
        val justo = ByteArray(CarrierFileCodec.MAX_BYTES) { 0x41 }
        assertEquals(CarrierFileCodec.MAX_BYTES, CarrierFileCodec.readBytes(ByteArrayInputStream(justo)).size)
    }

    @Test
    fun `no cierra los flujos del llamador`() {
        var cerrado = false
        val out = object : OutputStream() {
            override fun write(b: Int) {}
            override fun close() { cerrado = true }
        }
        CarrierFileCodec.write(out, "abc")
        assertTrue(!cerrado)
    }
}


/** Hex de un `ByteArray`, local al test. */
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
