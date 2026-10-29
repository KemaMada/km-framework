package com.example.keymessage.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.common.BitMatrix
import com.example.keymessage.util.KMLog

/**
 * 5.3 — El PORTADOR QR, y solo el portador.
 *
 * ## QUE HACE Y QUE NO
 *
 * Codifica un texto a una [com.google.zxing.common.BitMatrix] y la vuelve a leer
 * de una imagen en luminancia. NO sabe que es un `ContactBundle`, no lo valida y
 * no decide quien es el contacto: eso es [com.example.keymessage.contact.ContactImporter].
 *
 * La separacion es la que hace que esto se pueda PROBAR en un test de JVM:
 * `zxing-core` es Java puro —sin `android.graphics`, sin `Bitmap`—, de modo que
 * el mismo codigo que dibuja el QR de la pantalla es el que el test ejercita
 * codificando y decodificando, sin emulador y sin camara.
 *
 * ## POR QUE ESTO NO ES "UN SEGUNDO FORMATO DE BUNDLE"
 *
 * El contenido del QR es el MISMO `.kmc` base64url(KCE) que se pega a mano. Un
 * `ContactBundle` no sabe que puede viajar en una imagen y el framework no
 * menciona QR: `KceEnvelope` deja el portador en manos de la aplicacion. Un
 * segundo formato seria material criptografico que el otro extremo tendria que
 * saber distinguir, y por tanto material que podria llegar sin verificar.
 */
object QrCodec {

    /** Lado del QR en pixeles. Cuadrado: los QR son cuadrados. */
    const val DEFAULT_SIZE_PX: Int = 1024

    /**
     * Margen en MODULOS alrededor del codigo.
     *
     * La especificacion pide cuatro. Menos de dos y un lector de telefono deja de
     * encontrar el patron de los tiempos en el borde, que es el fallo clasico de
     * "mi QR no se lee" y no tiene nada que ver con los datos.
     */
    const val QUIET_ZONE: Int = 4

    /**
     * Codifica [text] a una matriz de [sizePx] x [sizePx].
     *
     * @throws IllegalArgumentException si el texto no cabe en un QR con los
     *   parametros dados. Falla de verdad, con el numero de version y de
     *   capacidad, en vez de recortar en silencio: un QR recortado es un QR que
     *   el otro extremo no puede verificar.
     */
    fun encode(text: String, sizePx: Int = DEFAULT_SIZE_PX): BitMatrix =
        encode(text, sizePx, QUIET_ZONE)

    /** Como [encode], con el margen en modulos a eleccion. */
    fun encode(
        text: String,
        sizePx: Int,
        quietZone: Int,
    ): BitMatrix {
        require(text.isNotEmpty()) { "un QR vacio no se escanea: no hay nada que codificar" }
        require(sizePx > 0) { "sizePx tiene que ser positivo: $sizePx" }
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.CHARACTER_SET to Charsets.ISO_8859_1.name(),
            EncodeHintType.MARGIN to quietZone,
            // Correccion baja: el documento se comparte en pantalla a pantalla, y
            // cada punto de correccion son modulos que el mismo documento paga en
            // tamano. Un bundle de 1500 B en version baja cabe donde en version
            // alta ya no cabe en una pantalla de movil.
            EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L, // L
        )
        return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    }

    /**
     * Lee un QR de una imagen en luminancia.
     *
     * @param luminance un byte por pixel, en orden de filas. Lo produce el
     *   analizador de la camara; aqui solo se exige el array.
     * @return el texto del QR, o `null` si en esa imagen no hay ninguno legible.
     *   "No hay QR" y "el QR no se puede leer" son el mismo resultado para quien
     *   escanea: no se inventa una diferencia que el llamante no puede observar.
     */
    fun decodeLuminance(
        luminance: ByteArray,
        width: Int,
        height: Int,
    ): String? {
        if (width <= 0 || height <= 0 || luminance.size != width * height) return null
        val fuente = PlanarYUVLuminanceSource(
            luminance,
            width,
            height,
            0,
            0,
            width,
            height,
            false,
        )
        return decodeSource(BinaryBitmap(HybridBinarizer(fuente)))
    }

    /**
     * Lee un QR de una matriz de bits ya binarizada.
     *
     * Es la puerta que usa un test: `decodeLuminance` necesita una camara, esta
     * necesita solo la matriz, y las dos comparten el lector.
     */
    fun decodeMatrix(matrix: BitMatrix): String? =
        decodeSource(BinaryBitmap(HybridBinarizer(MatrixLuminanceSource(matrix))))

    /** Lectura comun: [MultiFormatReader] con las pistas de QR. */
    private fun decodeSource(imagen: BinaryBitmap): String? = runCatching {
        val lector = MultiFormatReader()
        lector.setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to Charsets.ISO_8859_1.name(),
            ),
        )
        lector.decode(imagen).text
    }.getOrElse { failure ->
        // `NotFoundException` es "aqui no hay un QR legible", que es una ausencia
        // y no un fallo. Cualquier otra excepcion tambien se degrada a ausencia:
        // el llamante —una pantalla de escaneo— no puede hacer nada con la traza.
        if (failure !is NotFoundException) {
            KMLog.event(
                "[CONTACT]",
                "el fotograma no se pudo decodificar: ${failure::class.java.simpleName}",
                KMLog.Level.DEBUG,
            )
        }
        null
    }
}

/**
 * Luminancia de una `BitMatrix`: 0xff para los modulos claros, 0x00 para los
 * oscuros. Es lo que espera `HybridBinarizer`, y evita que un test tenga que
 * fabricar un `android.graphics.Bitmap`.
 */
private class MatrixLuminanceSource(
    private val matrix: BitMatrix,
) : LuminanceSource(matrix.width, matrix.height) {

    private val pixeles: ByteArray = ByteArray(matrix.width * matrix.height) { i ->
        if (matrix[i % matrix.width, i / matrix.width]) 0x00.toByte() else 0xFF.toByte()
    }

    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        val destino = row ?: ByteArray(width)
        val offset = y * width
        System.arraycopy(pixeles, offset, destino, 0, width)
        return destino
    }

    override fun getMatrix(): ByteArray = pixeles
}