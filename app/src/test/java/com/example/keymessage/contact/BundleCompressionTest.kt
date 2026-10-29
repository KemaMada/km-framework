package com.example.keymessage.contact

import com.example.keymessage.support.TestWorld
import com.km.identity.Kce
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * R4 — la tolerancia a bytes colgantes de [BundleCompression.decompress] es
 * load-bearing para RECHAZAR corrupcion no-whitespace tras el fin del stream.
 * `Inflater` acepta por su cuenta bytes colgantes arbitrarios, asi que solo el
 * bucle de validacion distingue "pegado sucio" (whitespace) de corrupcion.
 *
 * Los datos NO son constantes: el portador sale de un bundle real firmado.
 */
class BundleCompressionTest {

    private fun bundleReal(): String {
        val par = TestWorld.deviceWithRoot("r4")
        val dev = par.device
        val root = par.root
        dev.issueOneTimePreKey(TestWorld.BOB_ONE_TIME_PREKEY_ID)
        return root.bundleTextFor(
            devices = listOf(dev),
            subject = dev,
            oneTimePreKeyIds = listOf(TestWorld.BOB_ONE_TIME_PREKEY_ID),
        )
    }

    @Test
    fun `byte no-whitespace colgante tras el stream se rechaza`() {
        val portador = bundleReal()
        // 0x41 = 'A', no-whitespace, colgado DESPUES del fin del stream zlib.
        val conColgante = portador + "A"
        val fallo = assertFailsWith<Exception> { BundleCompression.decompress(conColgante) }
        assertTrue(
            fallo is Kce.KceException,
            "el rechazo tiene que venir del decodificador, no de otra capa: ${fallo::class}",
        )
        assertTrue(
            (fallo as Kce.KceException).code == "ENVELOPE_MALFORMED",
            "y el codigo es ENVELOPE_MALFORMED: ${fallo.code}",
        )
    }

    @Test
    fun `byte corrupto dentro del stream se rechaza (control negativo)`() {
        val portador = bundleReal()
        val bytes = portador.toByteArray(Charsets.ISO_8859_1)
        // Alterar un byte en el MEDIO del portador: esta dentro del stream zlib,
        // no es colgante. El checksum Adler-32 (o el inflate) lo detecta.
        val corrupto = bytes.copyOf().apply {
            this[size / 2] = (this[size / 2].toInt() xor 0xFF).toByte()
        }
        val textoCorrupto = String(corrupto, Charsets.ISO_8859_1)
        val fallo = assertFailsWith<Exception> { BundleCompression.decompress(textoCorrupto) }
        assertTrue(
            fallo is Kce.KceException,
            "corrupcion interna tambien se rechaza: ${fallo::class}",
        )
    }

    @Test
    fun `tolerancia a whitespace colgante se mantiene`() {
        val portador = bundleReal()
        // Control positivo: el whitespace colgante sigue aceptandose (no romper R2).
        val conNl = portador + "\n"
        val kce = BundleCompression.decompress(conNl)
        assertTrue(kce.isNotEmpty(), "el payload descomprimido no esta vacio")
    }

    // ==================================================================
    // R9 — los DOS limites del portador
    // ==================================================================

    @Test
    fun `un portador mas grande de 16 KiB se rechaza ANTES de descomprimir`() {
        // El limite se comprueba sobre el portador COMPRIMIDO, que es lo que
        // viaja: un bundle son ~500-1500 bytes, asi que 16 KiB es holgado y
        // cualquier cosa mayor es un intento, no un bundle.
        val enorme = "A".repeat(BundleCompression.MAX_CARRIER_BYTES + 1)
        val fallo = assertFailsWith<Exception> { BundleCompression.decompress(enorme) }

        assertTrue(fallo is Kce.KceException, "el rechazo viene del decodificador: ${fallo::class}")
        assertEquals(
            BundleCompression.CODE_CARRIER_TOO_LARGE,
            (fallo as Kce.KceException).code,
            "y el codigo es el de tamano, no ENVELOPE_MALFORMED: son cosas distintas",
        )
    }

    @Test
    fun `un portador que descomprime a mas de 1 MiB se rechaza`() {
        // La bomba de DEFLACION: un portador de un kilo que se descomprime a un
        // gigabyte. Sin este limite, un par desconocido puede hacer crecer la
        // memoria de la app con un frame que cabe de sobra en el cable.
        val bomba = BundleCompression.compress(ByteArray(4 * 1024 * 1024))
        assertTrue(
            BundleCompression.carrierBytes(bomba) < BundleCompression.MAX_CARRIER_BYTES,
            "la bomba tiene que caber en el portador para que el limite que se prueba sea el de la SALIDA",
        )

        val fallo = assertFailsWith<Exception> { BundleCompression.decompress(bomba) }
        assertTrue(fallo is Kce.KceException, "el rechazo viene del decodificador: ${fallo::class}")
        assertEquals(
            BundleCompression.CODE_INFLATED_TOO_LARGE,
            (fallo as Kce.KceException).code,
            "y el codigo es el de la salida descomprimida",
        )
    }

    @Test
    fun `un portador de tamano normal se descomprime sin problema`() {
        // Control positivo de los dos limites: un bundle de verdad pasa.
        val portador = bundleReal()
        assertTrue(BundleCompression.decompress(portador).isNotEmpty())
    }
}
