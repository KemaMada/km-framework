package com.km.invariants

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S10 — EL BASELINE CRIPTOGRAFICO DEL CAMBIO P2P.
 *
 * Estos MD5 se tomaron ANTES de tocar nada (evidencia
 * `.atl/evidence/p2p-baseline/checkpoints.md5`). Un cambio de medio no puede
 * mover un byte de la criptografia, del wire de MESSAGE/ACK ni de los defaults
 * de flags: si alguno de estos ficheros cambia, este test se pone rojo y obliga
 * a una decision explicita.
 *
 * ## Por que un tripwire y no un golden de frames
 *
 * Porque los frames dependen de claves aleatorias: un golden de bytes seria un
 * test que se reescribe solo. El fichero FUENTE es determinista y es donde vive
 * el invariante. Los frames se prueban en `MediumCryptoInvariantTest`.
 *
 * Actualizar este fichero es un acto consciente: si un cambio de criptografia es
 * legitimo, se actualiza junto con su propuesta y su checkpoint, nunca para
 * "poner verde" un cambio de transporte.
 */
class P2pBaselineCheckpointsTest {

    private data class Checkpoint(val path: String, val md5: String)

    /**
     * Copia literal de `.atl/evidence/p2p-baseline/checkpoints.md5` para los
     * ficheros que este cambio NO puede tocar.
     *
     * `TransportChain.kt` NO esta: este cambio le anadio el accessor `last`, y
     * esa adicion es parte del diseno (S2).
     */
    private val checkpoints = listOf(
        Checkpoint("src/main/kotlin/com/km/frame/SecureFrameCodec.kt", "454bd0cc4b8b7e8eb900aaf0a8471b03"),
        Checkpoint("src/main/kotlin/com/km/frame/SecureRatchetProtocol.kt", "8531496c1d39094cada62e45250bb5ca"),
        Checkpoint("src/main/kotlin/com/km/identity/Kce.kt", "8bc0005e978541ab6bb7880b4306e35c"),
        Checkpoint("src/main/kotlin/com/km/identity/ContactBundle.kt", "ad5a90422f5a5966b83db8c2752309c3"),
        Checkpoint("src/main/kotlin/com/km/protocol/Transport.kt", "c4ffde3c4a726b48e319468ee0e6d013"),
        Checkpoint("src/main/kotlin/com/km/node/TransportBackend.kt", "f4395e1cd7544c04a978c8aa73ca9038"),
        Checkpoint("src/main/kotlin/com/km/node/PeerTransportManager.kt", "70f3e262b2faa3bf0e2555f6f29b5338"),
        Checkpoint("src/main/kotlin/com/km/config/KmFlags.kt", "387c7012c65ef57f55236f0ad1d71160"),
    )

    private fun md5(path: Path): String =
        MessageDigest.getInstance("MD5").digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `el cambio P2P no movio un byte de la criptografia ni del wire`() {
        checkpoints.forEach { cp ->
            val path = Path.of(cp.path)
            assertTrue(Files.isRegularFile(path), "no existe ${cp.path} (se ejecuta desde km-core)")
            assertEquals(
                cp.md5,
                md5(path),
                "${cp.path} cambio respecto al baseline de S0. Un cambio de MEDIO no puede " +
                    "tocar criptografia, wire ni defaults de flags; si es intencionado, actualiza " +
                    "el checkpoint junto con su propuesta.",
            )
        }
    }
}
