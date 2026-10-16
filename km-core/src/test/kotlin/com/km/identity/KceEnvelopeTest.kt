package com.km.identity

import com.km.auth.Base64Url
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Sobre de transporte base64url(KCE) y la entrada unica de verificacion.
 *
 * La mayoria de estos tests usan el vector CONGELADO G10/contact.cbor como
 * origen, no un bundle reconstruido: asi se prueba que el sobre transporta el
 * formato vigente sin alterarlo.
 */
class KceEnvelopeTest {

    private val contactBytes = KmIdFixtures.bytes("vectors/G10/contact.cbor")

    // ===================================================================
    // Sobre: ida y vuelta sobre el formato vigente
    // ===================================================================

    @Test
    fun `el sobre reconstruye los bytes canonicos del vector congelado`() {
        val texto = Base64Url.encode(contactBytes)
        val mapa = KceEnvelope.decodeMap(texto)
        assertContentEquals(
            contactBytes, Kce.encode(mapa),
            "el sobre no preserva los bytes canonicos de G10/contact.cbor",
        )
    }

    @Test
    fun `encode y decode son inversos byte a byte`() {
        val original = Kce.validate(contactBytes) as Map<String, Any?>
        assertContentEquals(contactBytes, Kce.encode(KceEnvelope.decodeMap(KceEnvelope.encode(original))))
    }

    @Test
    fun `el transporte no altera lo que se firma`() {
        // Si elviernes añadiese una transformacion al sobre, la firma dejaria
        // de verificar sobre los bytes decodificados. Este test lo detecta.
        val bundle = KceEnvelope.decodeMap(Base64Url.encode(contactBytes))
        ContactBundle.verifyBundle(bundle)
    }

    // ===================================================================
    // Entrada unica de verificacion
    // ===================================================================

    @Test
    fun `decodeAndVerify acepta un bundle valido transportado`() {
        val bundle = ContactBundle.decodeAndVerify(Base64Url.encode(contactBytes))
        // El mapa ya no es accesible: se comprueba sobre la superficie tipada
        // y, sobre todo, que los bytes canonicos del vector sobreviven.
        assertContentEquals(contactBytes, bundle.canonicalBytes())
        assertEquals(32, bundle.subjectDeviceId.size)
        assertEquals(32, bundle.subjectAgreementKey.size)
    }

    @Test
    fun `decodeAndVerify rechaza un bundle con la firma alterada en el sobre`() {
        val mapa = (Kce.validate(contactBytes) as Map<String, Any?>).toMutableMap()
        mapa["capabilities"] = 0b001L
        KmSchemas.validate(KmSchemas.CONTACT_BUNDLE, mapa, "ContactBundle.")
        val e = assertFailsWith<KmIdVerificationException> {
            ContactBundle.decodeAndVerify(Base64Url.encode(Kce.encode(mapa)))
        }
        assertEquals("INVALID_SIGNATURE", e.code)
    }

    // ===================================================================
    // Rechazo del formato heredado: NO hay fallback
    // ===================================================================

    @Test
    fun `el formato heredado nombre mas pubkey se rechaza`() {
        // `|` no pertenece al alfabeto base64url. El rechazo es inherente al
        // sobre: no hace falta una rama explicita para el formato antiguo.
        val e = assertFailsWith<Kce.KceException> {
            KceEnvelope.decodeMap("Alice|7f3a9c2b1d4e5f60718293a4b5c6d7e8")
        }
        assertEquals("ENVELOPE_MALFORMED", e.code)
    }

    @Test
    fun `un texto corto sin firma alguna se rechaza`() {
        assertFailsWith<Kce.KceException> {
            KceEnvelope.decodeMap("Alice")
        }
    }

    // ===================================================================
    // Formas malformadas
    // ===================================================================

    @Test
    fun `un sobre con padding se rechaza`() {
        val conPadding = Base64Url.encode(contactBytes) + "=="
        val e = assertFailsWith<Kce.KceException> { KceEnvelope.decodeMap(conPadding) }
        assertEquals("ENVELOPE_MALFORMED", e.code)
    }

    @Test
    fun `un sobre con base64 estandar y no url se rechaza`() {
        // El alfabeto base64url es mas estrecho: no hay Conversion Fallback.
        assertFailsWith<Kce.KceException> {
            KceEnvelope.decodeMap("ab+c/d/e")
        }
    }

    @Test
    fun `una codificacion KCE no canonica se rechaza`() {
        val bytes = KmIdFixtures.bytes("invalid/non-canonical-order.cbor")
        val e = assertFailsWith<Kce.KceException> {
            KceEnvelope.decodeMap(Base64Url.encode(bytes))
        }
        assertEquals("KCE_ROUNDTRIP_MISMATCH", e.code)
    }

    @Test
    fun `un sobre valido que no es un mapa se rechaza`() {
        val e = assertFailsWith<Kce.KceException> {
            KceEnvelope.decodeMap(Base64Url.encode(Kce.encode("solo una cadena")))
        }
        assertEquals("ENVELOPE_NOT_A_MAP", e.code)
    }

    // ===================================================================
    // Frontera framework / portador: el limite es del portador, no del modulo
    // ===================================================================

    @Test
    fun `el sobre no impone un tope mas alla del de Kce`() {
        // Documento grande y legal para Kce: 64 mapas de 32 enteros, del orden
        // de los kilobytes. El sobre lo transporta entero. Si el sobre
        // recortara, este test lo detectaria.
        val grande = (0 until KmIdConstants.KCE_MAX_MAP_ITEMS)
            .associate { i -> "lote-$i" to List(32) { it.toLong() } }
        val texto = KceEnvelope.encode(grande)
        assertEquals(grande, KceEnvelope.decodeMap(texto))
        assertEquals(true, texto.length > 4096)
    }

    @Test
    fun `el tope de documento vive en Kce, no en el sobre`() {
        // 64 KiB es el limite de documento de KCE. Es generico y no depende
        // del portador, que es lo correcto: aqui no debe existir ningun tope
        // derivado de como viaje despues.
        assertEquals(65536, Kce.MAX_DOCUMENT_BYTES)
        assertEquals(65536, KmIdConstants.KCE_MAX_DOCUMENT_BYTES)
    }

    @Test
    fun `el limite estructural lo impone el esquema, no el sobre`() {
        // MAX_ONE_TIME_PREKEYS acota la ESTRUCTURA y lo hace KmSchemas, que es
        // donde corresponde: el sobre solo mueve bytes.
        assertEquals(128, KmIdConstants.MAX_ONE_TIME_PREKEYS)
        assertEquals(64, KmIdConstants.MAX_DEVICES)

        val mapa = (Kce.validate(contactBytes) as Map<String, Any?>).toMutableMap()
        val opks = mapa["oneTimePrekeys"] as List<Map<String, Any?>>
        mapa["oneTimePrekeys"] = opks + List(200) { opks[0] }
        assertEquals(true, (mapa["oneTimePrekeys"] as List<*>).size > KmIdConstants.MAX_ONE_TIME_PREKEYS)

        val e = assertFailsWith<KmIdSchemaException> {
            KmSchemas.validate(KmSchemas.CONTACT_BUNDLE, mapa, "ContactBundle.")
        }
        assertEquals("ARRAY_TOO_LONG", e.code)
    }
}