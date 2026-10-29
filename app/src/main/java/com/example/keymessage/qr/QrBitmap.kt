package com.example.keymessage.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.common.BitMatrix

/**
 * 5.3 — El puente de UN solo sentido: matriz -> `Bitmap`.
 *
 * ## POR QUE ESTA CLASE ES TAN PEQUENA Y ESTA SEPARADA
 *
 * `android.graphics` no existe en un test de JVM. Todo lo que se puede probar
 * —tamano del QR, roundtrip del contenido, que el texto quepa— vive en [QrCodec],
 * que es Java puro. Aqui solo queda el pintado, que no tiene nada que decidir.
 *
 * ## POR QUE LOS MODULOS OSCUROS SON NEGROS Y NO "GRIS TERMINAL"
 *
 * El dato codificado es la diferencia entre blanco y negro. Un fondo que no sea
 * blanco puro o un modulo que no sea negro puro amplifica el contraste —ayuda—
 * pero cualquieruniformizacion por el tema rompe el margen. Los colores del QR no
 * seadaptan al tema, y por eso el QR se dibuja SIEMPRE sobre blanco.
 */
object QrBitmap {

    /**
     * Pinta [matrix] en un `Bitmap` de [matrix] pixeles.
     *
     * @param quietModules margen en pixeles que se anade alrededor. Cuatro
     *   pantallas de margen blanco es lo que la especificacion pide y lo que un
     *   lector de telefono necesita para encontrar los tiempos en el borde.
     */
    fun render(matrix: BitMatrix, quietModules: Int = 2): Bitmap {
        val lado = matrix.width + quietModules * 2
        val bitmap = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        for (y in 0 until lado) {
            for (x in 0 until lado) {
                val dentro = x >= quietModules && y >= quietModules &&
                    x < quietModules + matrix.width && y < quietModules + matrix.height
                val negro = dentro && matrix.get(x - quietModules, y - quietModules)
                bitmap.setPixel(x, y, if (negro) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    /** Como [render], pero devuelve `null` si la codificacion no cabe en un QR. */
    fun renderOrNull(text: String, sizePx: Int = QrCodec.DEFAULT_SIZE_PX, quietModules: Int = 2): Bitmap? =
        runCatching { render(QrCodec.encode(text, sizePx), quietModules) }.getOrNull()
}