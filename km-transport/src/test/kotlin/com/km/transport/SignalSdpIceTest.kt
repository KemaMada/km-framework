package com.km.transport

import com.km.model.IdentityId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertIs

/**
 * S3 — `SIGNAL` con `sdp`/`candidate`: aditivo, con limites y compatible con un
 * decodificador anterior.
 *
 * ## Que se fija aqui
 *
 *  - El GOLDEN del frame: los campos y su nombre exacto. Un cambio de dialecto
 *    silencioso (Base64, `payload`, `description`) rompe el par contra un
 *    cliente de la misma version, y solo se ve con bytes comparados.
 *  - El cliente ANTIGUO: un decodificador que solo conoce `contact_exchange`
 *    ignora `sdp_offer` con `SIGNAL_UNKNOWN_KIND`, sin tumbar la conexion.
 *  - Los limites: 16 KiB de SDP y 1 KiB de candidato, rechazados al emitir y
 *    descartados al leer, cada uno con su codigo estable.
 */
class SignalSdpIceTest {

    private val local = IdentityId("11".repeat(32))
    private val remoto = IdentityId("22".repeat(32))
    private val uuid: UUID = UUID.fromString("00000000-0000-0000-0000-000000000042")

    private fun codec(known: Set<String> = RelayDataCodec.KNOWN_SIGNAL_KINDS) =
        RelayDataCodec(
            newUuid = { uuid },
            now = { 1_700_000_000_000L },
            knownSignalKinds = known,
        )

    private val sdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n"
    private val candidate = "candidate:1 1 udp 2122260223 192.0.2.10 54400 typ host generation 0"

    // ------------------------------------------------------------------
    // Golden
    // ------------------------------------------------------------------

    @Test
    @DisplayName("3.3-01 GOLDEN sdp_offer: campos exactos, SDP en claro")
    fun goldenOferta() {
        val frame = codec().encodeSignal(
            from = local,
            to = remoto,
            kind = RelayDataCodec.KIND_SDP_OFFER,
            sdp = sdp,
        )
        assertEquals(
            "{\"type\":\"SIGNAL\",\"messageId\":\"$uuid\",\"timestamp\":1700000000000," +
                "\"from\":\"${local.value}\",\"to\":\"${remoto.value}\"," +
                "\"kind\":\"sdp_offer\",\"sdp\":\"v=0\\r\\no=- 1 2 IN IP4 127.0.0.1\\r\\ns=-\\r\\nt=0 0\\r\\n\"}",
            frame,
        )
    }

    @Test
    @DisplayName("3.3-02 GOLDEN sdp_answer")
    fun goldenRespuesta() {
        val frame = codec().encodeSignal(local, remoto, RelayDataCodec.KIND_SDP_ANSWER, sdp = "v=0\\r\\ns=-\\r\\n")
        assertTrue(frame.contains("\"kind\":\"sdp_answer\""))
        assertTrue(frame.endsWith("\"sdp\":\"v=0\\\\r\\\\ns=-\\\\r\\\\n\"}"))
    }

    @Test
    @DisplayName("3.3-03 GOLDEN ice_candidate: campo candidate, no sdp")
    fun goldenCandidato() {
        val frame = codec().encodeSignal(local, remoto, RelayDataCodec.KIND_ICE_CANDIDATE, candidate = candidate)
        assertTrue(frame.contains("\"kind\":\"ice_candidate\""))
        assertTrue(frame.contains("\"candidate\":\"$candidate\""))
        assertTrue(!frame.contains("\"sdp\""), "un candidato no viaja en el campo sdp")
    }

    @Test
    @DisplayName("3.3-04 la firma ANTIGUA (contact_exchange) emite los mismos bytes que antes")
    fun firmaAntiguaIntacta() {
        val portador = byteArrayOf(1, 2, 3, 4)
        val esperado = "{\"type\":\"SIGNAL\",\"messageId\":\"$uuid\",\"timestamp\":1700000000000," +
            "\"from\":\"${local.value}\",\"to\":\"${remoto.value}\"," +
            "\"kind\":\"contact_exchange\",\"contactBundle\":\"AQIDBA==\"}"
        assertEquals(
            esperado,
            codec().encodeSignal(local, remoto, RelayDataCodec.KIND_CONTACT_EXCHANGE, portador),
            "los defaults no pueden cambiar el frame existente",
        )
    }

    // ------------------------------------------------------------------
    // Ida y vuelta
    // ------------------------------------------------------------------

    @Test
    @DisplayName("3.3-05 el par nuevo recibe el SDP de la oferta")
    fun ofertaLlega() {
        val c = codec()
        val decoded = assertIs<DecodedSignal.Known>(
            c.decodeSignal(c.parseObject(c.encodeSignal(local, remoto, RelayDataCodec.KIND_SDP_OFFER, sdp = sdp))!!),
        )
        assertEquals(RelayDataCodec.KIND_SDP_OFFER, decoded.kind)
        assertEquals(sdp, decoded.text)
    }

    @Test
    @DisplayName("3.3-06 el par nuevo recibe el candidato ICE")
    fun candidatoLlega() {
        val c = codec()
        val decoded = assertIs<DecodedSignal.Known>(
            c.decodeSignal(
                c.parseObject(c.encodeSignal(local, remoto, RelayDataCodec.KIND_ICE_CANDIDATE, candidate = candidate))!!,
            ),
        )
        assertEquals(candidate, decoded.text)
    }

    // ------------------------------------------------------------------
    // Cliente antiguo
    // ------------------------------------------------------------------

    @Test
    @DisplayName("3.3-07 un decodificador ANTIGUO ignora sdp_offer con SIGNAL_UNKNOWN_KIND")
    fun clienteAntiguoIgnora() {
        val antiguo = codec(known = setOf(RelayDataCodec.KIND_CONTACT_EXCHANGE))
        val frame = codec().parseObject(
            codec().encodeSignal(local, remoto, RelayDataCodec.KIND_SDP_OFFER, sdp = sdp),
        )!!

        val result = antiguo.decodeSignal(frame)
        val unknown = assertIs<DecodedSignal.UnknownKind>(result)
        assertEquals(RelayDataCodec.KIND_SDP_OFFER, unknown.kind)
        assertEquals("SIGNAL_UNKNOWN_KIND", RelayDataCodec.CODE_SIGNAL_UNKNOWN_KIND)
    }

    @Test
    @DisplayName("3.3-08 tras ignorar el kind nuevo, la conexion sigue: la siguiente senal se entrega")
    fun conexionSigueViva() {
        val motor = JvmRelayTransport(
            identity = local,
            ed25519PublicKey = ByteArray(32),
            ed25519PrivateKey = ByteArray(32),
        )
        val recibidas = mutableListOf<DecodedSignal.Known>()
        motor.onSignal { if (it is DecodedSignal.Known) recibidas += it }
        val c = motor.relayCodec()

        // El par remoto emite hacia NOSOTROS: `to` es la identidad local del motor.
        motor.deliverSignal(c.parseObject(c.encodeSignal(remoto, local, "kind_de_otra_version", candidate = "x"))!!)
        motor.deliverSignal(
            c.parseObject(c.encodeSignal(remoto, local, RelayDataCodec.KIND_SDP_OFFER, sdp = sdp))!!,
        )

        assertEquals(1, recibidas.size, "la senal nueva llega DESPUES del kind desconocido")
        assertEquals(RelayDataCodec.KIND_SDP_OFFER, recibidas.single().kind)
    }

    // ------------------------------------------------------------------
    // Limites
    // ------------------------------------------------------------------

    @Test
    @DisplayName("3.3-09 SDP de exactamente 16 KiB se acepta; uno mas se RECHAZA al emitir")
    fun limiteSdp() {
        val exacto = "a".repeat(RelayDataCodec.MAX_SDP_BYTES)
        val frame = codec().encodeSignal(local, remoto, RelayDataCodec.KIND_SDP_OFFER, sdp = exacto)
        assertTrue(frame.contains(exacto))

        val exceso = "a".repeat(RelayDataCodec.MAX_SDP_BYTES + 1)
        val error = assertThrows(IllegalArgumentException::class.java) {
            codec().encodeSignal(local, remoto, RelayDataCodec.KIND_SDP_OFFER, sdp = exceso)
        }
        assertTrue(
            error.message.orEmpty().contains(RelayDataCodec.CODE_SIGNAL_SDP_TOO_LARGE),
            "el rechazo debe llevar codigo estable: ${error.message}",
        )
    }

    @Test
    @DisplayName("3.3-10 candidato de 1 KiB se acepta; uno mas se rechaza al emitir")
    fun limiteCandidato() {
        val exacto = "c".repeat(RelayDataCodec.MAX_CANDIDATE_BYTES)
        assertTrue(
            codec().encodeSignal(local, remoto, RelayDataCodec.KIND_ICE_CANDIDATE, candidate = exacto).contains(exacto),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            codec().encodeSignal(
                local,
                remoto,
                RelayDataCodec.KIND_ICE_CANDIDATE,
                candidate = "c".repeat(RelayDataCodec.MAX_CANDIDATE_BYTES + 1),
            )
        }
        assertTrue(error.message.orEmpty().contains(RelayDataCodec.CODE_SIGNAL_CANDIDATE_TOO_LARGE))
    }

    @Test
    @DisplayName("3.3-11 al LEER, un SDP sobredimensionado se descarta con su codigo")
    fun limiteAlLeer() {
        val c = codec()
        val enorme = "{\"type\":\"SIGNAL\",\"from\":\"${local.value}\",\"to\":\"${remoto.value}\"," +
            "\"kind\":\"sdp_offer\",\"sdp\":\"${"a".repeat(RelayDataCodec.MAX_SDP_BYTES + 1)}\"}"
        val result = c.decodeSignal(c.parseObject(enorme)!!)
        val invalid = assertIs<DecodedSignal.Invalid>(result)
        assertEquals(RelayDataCodec.CODE_SIGNAL_SDP_TOO_LARGE, invalid.code)
    }

    @Test
    @DisplayName("3.3-12 al LEER, un candidato sobredimensionado se descarta con su codigo")
    fun limiteCandidatoAlLeer() {
        val c = codec()
        val enorme = "{\"type\":\"SIGNAL\",\"from\":\"${local.value}\",\"to\":\"${remoto.value}\"," +
            "\"kind\":\"ice_candidate\",\"candidate\":\"${"c".repeat(RelayDataCodec.MAX_CANDIDATE_BYTES + 1)}\"}"
        val invalid = assertIs<DecodedSignal.Invalid>(c.decodeSignal(c.parseObject(enorme)!!))
        assertEquals(RelayDataCodec.CODE_SIGNAL_CANDIDATE_TOO_LARGE, invalid.code)
    }

    @Test
    @DisplayName("3.3-13 un sdp_offer sin sdp y un ice_candidate sin candidate se rechazan al emitir")
    fun camposObligatorios() {
        val sinSdp = assertThrows(IllegalArgumentException::class.java) {
            codec().encodeSignal(local, remoto, RelayDataCodec.KIND_SDP_OFFER)
        }
        assertTrue(sinSdp.message.orEmpty().contains(RelayDataCodec.CODE_SIGNAL_SDP_MISSING))

        val sinCandidato = assertThrows(IllegalArgumentException::class.java) {
            codec().encodeSignal(local, remoto, RelayDataCodec.KIND_ICE_CANDIDATE)
        }
        assertTrue(sinCandidato.message.orEmpty().contains(RelayDataCodec.CODE_SIGNAL_CANDIDATE_MISSING))
    }

    @Test
    @DisplayName("3.3-14 el sdp vacio es valido: una respuesta puede no llevar lineas")
    fun sdpVacio() {
        val frame = codec().encodeSignal(local, remoto, RelayDataCodec.KIND_SDP_ANSWER, sdp = "")
        assertTrue(frame.contains("\"sdp\":\"\""))
    }
}
