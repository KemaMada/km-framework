package com.km.desktop.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * El resultado de intentar pintar un QR en el terminal.
 *
 * ## POR QUE DOS CASOS Y NO TRES
 *
 * [Fits] trae las lineas ya listas. [TooLarge] trae el MINIMO medido, que es lo
 * que la spec R7 pide declarar: "no cabe" sin decir cuanto hace falta deja al
 * usuario probando tamanos a ciegas. No hay un tercer caso "error": un portador
 * que la libreria no puede codificar se trata como [TooLarge] con el minimo de
 * referencia, porque el usuario puede seguir por texto o `.km`.
 */
sealed interface QrLayout {
    /** Las lineas del QR, cada una de `minColumns` columnas. */
    data class Fits(val lines: List<String>) : QrLayout

    /** No cabe: hacen falta al menos [minColumns] x [minRows] celdas. */
    data class TooLarge(val minColumns: Int, val minRows: Int) : QrLayout
}

/**
 * QR en terminal con `zxing-core`, y NADA de codificador propio.
 *
 * ## POR QUE ZXING Y NO UN ALGORITMO AQUI
 *
 * Un QR no es un dibujo: es Reed-Solomon, enmascarado, modo de codificacion y
 * version elegida por capacidad. Reimplementarlo seria una segunda
 * implementacion de un estandar que el movil ya lee con zxing, y un QR
 * "parecido" es un QR que no escanea. La libreria es la MISMA version que usa
 * la app (3.5.3, Apache-2.0), ya registrada en THIRD-PARTY-LICENSES.
 *
 * ## MEDIAS CELDAS
 *
 * Un terminal es mas alto que ancho: una celda de texto ocupa 1 columna x 2
 * filas de modulo. Por eso cada caracter pinta DOS modulos verticales con
 * `▀ ▄ █` y espacio. Asi un QR de N modulos ocupa N columnas y `ceil(N/2)`
 * filas, que es la geometria con la que se midio 77 + 8 = 85 columnas y 43
 * filas para el portador real de 469 B.
 *
 * ## EL PORTADOR SE CODIFICA EN ISO-8859-1
 *
 * Es la misma regla con la que se escribio: el texto del portador es una
 * secuencia de bytes ISO-8859-1, y codificarlo como UTF-8 cambiaria los bytes
 * altos y el QR leido no verificaria. La codificacion esta explicita en las
 * pistas de zxing; no se deja al default de la libreria.
 */
object QrRenderer {

    /** Margen de silencio en modulos: el minimo de la norma es 4. */
    const val MARGIN: Int = 4

    /** Minimo medido para el portador de referencia: 77 modulos + 8 de margen. */
    const val MIN_COLUMNS: Int = 85

    /** `ceil(85 / 2)`: dos modulos verticales por celda. */
    const val MIN_ROWS: Int = 43

    /** Caracteres de media celda. */
    private const val TOP: String = "\u2580"      // ▀
    private const val BOTTOM: String = "\u2584"   // ▄
    private const val FULL: String = "\u2588"     // █

    /**
     * Mide y, si cabe en [viewport], devuelve las lineas.
     *
     * No se recorta un QR para que "quepa": un QR truncado no escanea y fingir
     * que si es peor que decir el minimo.
     */
    fun layout(carrier: String, viewport: Size): QrLayout {
        if (carrier.isEmpty()) return QrLayout.TooLarge(MIN_COLUMNS, MIN_ROWS)
        val matrix = runCatching { matrixOf(carrier) }.getOrNull()
            ?: return QrLayout.TooLarge(MIN_COLUMNS, MIN_ROWS)
        val columns = matrix.width
        val rows = (matrix.height + 1) / 2
        if (viewport.columns < columns || viewport.rows < rows) {
            return QrLayout.TooLarge(columns, rows)
        }
        return QrLayout.Fits(render(matrix))
    }

    /**
     * La matriz de modulos del QR, con margen incluido.
     *
     * `width = 0` deja que zxing elija el MENOR numero de modulos que cabe: fijar
     * un tamano obligaria a escalar y gastaria mas pantalla de la necesaria.
     */
    fun matrixOf(carrier: String): BitMatrix {
        val pistas = mapOf(
            EncodeHintType.CHARACTER_SET to "ISO-8859-1",
            EncodeHintType.MARGIN to MARGIN,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        )
        return try {
            QRCodeWriter().encode(carrier, BarcodeFormat.QR_CODE, 0, 0, pistas)
        } catch (e: WriterException) {
            throw IllegalArgumentException("el portador no se puede codificar como QR", e)
        }
    }

    /** Las lineas de media celda de [matrix]. Cada una mide `matrix.width` columnas. */
    fun render(matrix: BitMatrix): List<String> {
        val salida = ArrayList<String>((matrix.height + 1) / 2)
        var y = 0
        while (y < matrix.height) {
            val sb = StringBuilder(matrix.width)
            for (x in 0 until matrix.width) {
                val arriba = matrix.get(x, y)
                val abajo = y + 1 < matrix.height && matrix.get(x, y + 1)
                sb.append(
                    when {
                        arriba && abajo -> FULL
                        arriba -> TOP
                        abajo -> BOTTOM
                        else -> " "
                    },
                )
            }
            salida += sb.toString()
            y += 2
        }
        return salida
    }
}
