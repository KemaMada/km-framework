package com.km.identity

import com.km.auth.Base64Url

/**
 * Sobre de transporte para documentos KCE: base64url(Kce.encode(valor)).
 *
 * QUE ESTA Y QUE NO ESTA EN ESTE MODULO
 *
 *     Aqui vive el CONTENIDO: los bytes canonicos KCE de DeviceRoster,
 *     ContactBundle y los demas documentos, y como se vuelven texto
 *     transportable y como se recuperan.
 *
 *     Aqui NO vive el PORTADOR. Un QR, un fichero `.km`, una linea de texto o
 *     un socket son decisiones de la aplicacion. Este modulo no menciona QR,
 *     no conoce capacidades de imagen y no impone topes de tamano de
 *     transporte: el limite de un portador es del portador.
 *
 *     El reparto sale solo del tamano. Medido sobre bundles construidos por
 *     este codigo: 1130 B con un dispositivo y sin prekeys, y ~125 B por cada
 *     one-time prekey. El esquema admite hasta MAX_ONE_TIME_PREKEYS = 128, lo
 *     que produce documentos que no caben en ningun portador pequeno. El
 *     framework no resuelve eso recortando: expone el documento completo y
 *     deja que la aplicacion elija el portador que corresponda.
 *
 * POR QUE base64url Y NO BINARIO CRUDO
 *     Base64URL es el alfabeto que ya exige KM-0002 16.1 en el resto del
 *     wire, y sobrevive intacto al viaje por texto que hacen la mayoria de los
 *     portadores. El binario crudo no.
 *
 * POR QUE Kce.validate Y NO Kce.decode
 *     La firma se calcula sobre signableBytes, o sea sobre la re-codificacion
 *     canonica. Aceptar una entrada NO canonica admitiria varias cadenas de
 *     bytes para el mismo documento (maleabilidad). Kce.validate decodifica,
 *     re-codifica y exige igualdad byte a byte.
 *
 * FALLO DURO
 *     Sin padding, sin fallback, sin tolerancia: lo que no sea un documento
 *     KCE canonico se rechaza con codigo tipado y no se degrada.
 */
object KceEnvelope {

    /** Bytes canonicos KCE -> texto transportable. */
    fun encode(value: Any?): String = Base64Url.encode(Kce.encode(value))

    /**
     * Texto transportable -> valor KCE canonico.
     *
     * @throws Kce.KceException ENVELOPE_MALFORMED si el texto no es base64url
     *         estricto, KCE_ROUNDTRIP_MISMATCH si los bytes no son canonicos
     */
    fun decode(text: String): Any? {
        val raw = try {
            Base64Url.decode(text)
        } catch (e: Base64Url.DecodeError) {
            throw Kce.KceException("ENVELOPE_MALFORMED", "${e::class.simpleName}: ${e.message}")
        }
        return Kce.validate(raw)
    }

    /**
     * Como [decode], pero exige que el documento sea un mapa.
     *
     * Es la entrada de los verificadores de identidad, que toman
     * Map<String, Any?> y no Any?.
     */
    fun decodeMap(text: String): Map<String, Any?> {
        val value = decode(text)
        if (value !is Map<*, *>) {
            throw Kce.KceException(
                "ENVELOPE_NOT_A_MAP",
                "se esperaba un documento KCE de tipo map, vino ${value?.let { it::class.simpleName } ?: "null"}",
            )
        }
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }
}