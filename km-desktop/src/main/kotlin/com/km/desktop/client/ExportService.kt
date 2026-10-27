package com.km.desktop.client

import com.km.identity.CarrierFileCodec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Escrituras de FICHERO de la TUI: el `.km` propio y el export de logs.
 *
 * ## LOS DOS SON PUBLICOS, Y SE TRATAN DISTINTO
 *
 * - El `.km` es el MISMO documento que el QR: material publico. Se escribe con
 *   [CarrierFileCodec], ISO-8859-1 exacto y sin normalizar, para que un `.km`
 *   del escritorio importe en Android y al reves.
 * - El log es diagnostico redactado. Se escribe en UTF-8 porque es texto para
 *   leer, y solo con lineas de `DiagLog` (la decision de QUE lineas vive en
 *   [DiagnosticsService]).
 *
 * ## NADA DE SOBRESCRITURAS SILENCIOSAS DEL LOG
 *
 * El export lleva marca de tiempo en el nombre: dos exportaciones seguidas no se
 * pisan, y una traza que el usuario quiere conservar no desaparece porque pulso
 * `X` dos veces.
 */
class ExportService(
    /** Directorio base: el del almacen de identidad. */
    private val baseDir: Path,
    private val clock: () -> LocalDateTime = { LocalDateTime.now() },
) {

    private val stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** Ruta del `.km` propio: junto al almacen, con nombre estable. */
    fun ownCarrierPath(): Path = baseDir.resolve(OWN_CARRIER_FILE)

    /**
     * Guarda [carrier] como `.km` en [path] (o en [ownCarrierPath]).
     *
     * @throws java.io.IOException si no se puede escribir.
     */
    fun saveCarrier(carrier: String, path: Path? = null): Path {
        val destino = path ?: ownCarrierPath()
        destino.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.newOutputStream(
            destino,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { CarrierFileCodec.write(it, carrier) }
        return destino
    }

    /** Escribe [text] (solo lineas de `DiagLog`) y devuelve la ruta. */
    fun saveLogs(text: String, filter: String? = null): Path {
        val sufijo = if (filter == null) "todos" else filter.removeSurrounding("[").removeSuffix("]").lowercase()
        val destino = baseDir.resolve("km-logs-" + clock().format(stamp) + "-" + sufijo + ".log")
        Files.createDirectories(destino.toAbsolutePath().parent)
        Files.writeString(
            destino,
            text,
            Charsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        return destino
    }

    companion object {
        /** Nombre del `.km` propio. El mismo que usaba el modo lineas. */
        const val OWN_CARRIER_FILE: String = "own.km"
    }
}
