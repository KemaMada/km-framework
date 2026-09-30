package com.keymessage.core.sf

/**
 * Codec de SecureFrame v1: SOLO serializacion, NINGUNA criptografia.
 *
 * SEPARACION DE RESPONSABILIDADES:
 * - [SecureFrameCodec] traduce estructura ↔ bytes. No cifra, no descifra,
 *   no deriva claves, no conoce el AEAD.
 * - `SecureFrameProtector` (aun no implementado en 3O.1) hace la parte
 *   criptografica: deriva clave/nonce, construye el AAD y aplica el AEAD.
 *
 * El AAD se expone como [authenticatedHeader] para que el protector use
 * EXACTAMENTE los mismos bytes que el codec escribe. Esa coincidencia es
 * la base de la propiedad de autenticacion: no puede depender de una
 * re-serializacion accidental.
 */
interface SecureFrameCodec {
    fun encode(frame: SecureFrame): ByteArray
    fun decode(bytes: ByteArray): SecureFrame
}

/** Error de formato/validacion de un SecureFrame. */
open class SecureFrameFormatException(message: String) : RuntimeException(message)

/** Version de frame no soportada. Provoca RECHAZO TOTAL. */
class UnsupportedFrameVersion(val version: UByte) :
    SecureFrameFormatException("version de frame no soportada: $version")

/**
 * Codec binario de SecureFrame v1.
 *
 * CANONICALIDAD: existe una unica representacion wire por frame. El
 * layout es de longitud fija y `decode` exige que la longitud declarada
 * coincida EXACTAMENTE con los bytes disponibles, de modo que no admite
 * bytes sobrantes ni truncamiento.
 */
object BinarySecureFrameCodec : SecureFrameCodec {

    override fun encode(frame: SecureFrame): ByteArray {
        require(frame.version == SecureFrameSpec.VERSION) {
            "solo se puede codificar la version ${SecureFrameSpec.VERSION}, " +
                "recibida ${frame.version}"
        }
        val ciphertextLength = frame.ciphertext.size
        require(ciphertextLength <= SecureFrameSpec.MAX_CIPHERTEXT_LENGTH) {
            "ciphertext de $ciphertextLength bytes excede el maximo " +
                "${SecureFrameSpec.MAX_CIPHERTEXT_LENGTH} (campo length de 2 bytes)"
        }

        val out = ByteArray(SecureFrameSpec.CIPHERTEXT_OFFSET + ciphertextLength)

        out[SecureFrameSpec.VERSION_OFFSET] = frame.version.toByte()
        out[SecureFrameSpec.TYPE_OFFSET] = frame.type.code.toByte()
        writeUint16(out, SecureFrameSpec.LENGTH_OFFSET, ciphertextLength.toUInt())

        val header = frame.ratchetHeader
        System.arraycopy(
            header.dhPublicKey, 0,
            out, SecureFrameSpec.DH_PUBLIC_KEY_OFFSET,
            SecureFrameSpec.DH_PUBLIC_KEY_LENGTH,
        )
        writeUint32(out, SecureFrameSpec.PREVIOUS_CHAIN_LENGTH_OFFSET, header.previousChainLength)
        writeUint32(out, SecureFrameSpec.MESSAGE_NUMBER_OFFSET, header.messageNumber)

        System.arraycopy(
            frame.ciphertext, 0,
            out, SecureFrameSpec.CIPHERTEXT_OFFSET,
            ciphertextLength,
        )
        return out
    }

    override fun decode(bytes: ByteArray): SecureFrame {
        if (bytes.size < SecureFrameSpec.CIPHERTEXT_OFFSET) {
            throw SecureFrameFormatException(
                "frame truncado: ${bytes.size} bytes, se necesitan al menos " +
                    "${SecureFrameSpec.CIPHERTEXT_OFFSET} para el header"
            )
        }

        // Invariante 4: version desconocida -> rechazo total, sin continuar.
        val version = bytes[SecureFrameSpec.VERSION_OFFSET].toUByte()
        if (version != SecureFrameSpec.VERSION) {
            throw UnsupportedFrameVersion(version)
        }

        val typeCode = bytes[SecureFrameSpec.TYPE_OFFSET].toUByte()
        val type = FrameType.fromCode(typeCode)
            ?: throw SecureFrameFormatException("tipo de frame desconocido: $typeCode")

        val declaredLength = readUint16(bytes, SecureFrameSpec.LENGTH_OFFSET).toInt()
        if (declaredLength < SecureFrameSpec.MIN_CIPHERTEXT_LENGTH) {
            throw SecureFrameFormatException(
                "length declarado $declaredLength es menor que el minimo " +
                    "${SecureFrameSpec.MIN_CIPHERTEXT_LENGTH} (solo el tag)"
            )
        }

        // Canonicalidad: la longitud declarada debe coincidir EXACTAMENTE
        // con los bytes disponibles. Esto rechaza truncamiento y sobrantes.
        val expectedTotal = SecureFrameSpec.CIPHERTEXT_OFFSET + declaredLength
        if (bytes.size != expectedTotal) {
            throw SecureFrameFormatException(
                "longitud inconsistente: header declara $declaredLength bytes de " +
                    "ciphertext (total esperado $expectedTotal) pero el frame tiene ${bytes.size}"
            )
        }

        val dhPublicKey = bytes.copyOfRange(
            SecureFrameSpec.DH_PUBLIC_KEY_OFFSET,
            SecureFrameSpec.DH_PUBLIC_KEY_OFFSET + SecureFrameSpec.DH_PUBLIC_KEY_LENGTH,
        )
        val previousChainLength =
            readUint32(bytes, SecureFrameSpec.PREVIOUS_CHAIN_LENGTH_OFFSET)
        val messageNumber = readUint32(bytes, SecureFrameSpec.MESSAGE_NUMBER_OFFSET)
        val ciphertext = bytes.copyOfRange(
            SecureFrameSpec.CIPHERTEXT_OFFSET,
            expectedTotal,
        )

        return SecureFrame(
            version = version,
            type = type,
            ratchetHeader = RatchetHeader(dhPublicKey, previousChainLength, messageNumber),
            ciphertext = ciphertext,
        )
    }

    /**
     * Bytes que forman el AAD del frame dado.
     *
     * El protector DEBE usar estos mismos bytes como `associatedData`.
     */
    fun authenticatedHeader(frame: SecureFrame): ByteArray =
        authenticatedHeader(frame.type, frame.ratchetHeader, frame.ciphertext.size)

    /**
     * Bytes que forman el AAD a partir de los campos del header y de la
     * longitud FINAL del ciphertext.
     *
     * Existe separado porque `length` forma parte del AAD y debe contener
     * la longitud definitiva (plaintext + tag), que se conoce ANTES de
     * cifrar. Construir el AAD desde un frame provisional con un largo
     * placeholder firmaria una longitud incorrecta y el descifrado fallaria.
     */
    fun authenticatedHeader(
        type: FrameType,
        ratchetHeader: RatchetHeader,
        ciphertextLength: Int,
    ): ByteArray {
        val out = ByteArray(SecureFrameSpec.CIPHERTEXT_OFFSET)
        out[SecureFrameSpec.VERSION_OFFSET] = SecureFrameSpec.VERSION.toByte()
        out[SecureFrameSpec.TYPE_OFFSET] = type.code.toByte()
        writeUint16(out, SecureFrameSpec.LENGTH_OFFSET, ciphertextLength.toUInt())
        System.arraycopy(
            ratchetHeader.dhPublicKey, 0,
            out, SecureFrameSpec.DH_PUBLIC_KEY_OFFSET,
            SecureFrameSpec.DH_PUBLIC_KEY_LENGTH,
        )
        writeUint32(out, SecureFrameSpec.PREVIOUS_CHAIN_LENGTH_OFFSET, ratchetHeader.previousChainLength)
        writeUint32(out, SecureFrameSpec.MESSAGE_NUMBER_OFFSET, ratchetHeader.messageNumber)
        return out
    }

    // ------------------------------------------------------------------
    // Enteros unsigned big-endian
    // ------------------------------------------------------------------

    private fun writeUint16(out: ByteArray, offset: Int, value: UInt) {
        out[offset] = (value shr 8).toByte()
        out[offset + 1] = value.toByte()
    }

    private fun writeUint32(out: ByteArray, offset: Int, value: UInt) {
        out[offset] = (value shr 24).toByte()
        out[offset + 1] = (value shr 16).toByte()
        out[offset + 2] = (value shr 8).toByte()
        out[offset + 3] = value.toByte()
    }

    // Nota de precedencia: en Kotlin `shl` y `or` están en el MISMO nivel
    // de precedencia y son asociativos por la IZQUIERDA. Por eso cada
    // desplazamiento debe ir parentizado explicitamente; encadenarlos sin
    // parentesis produce `(a shl 24 or b) shl 16` en vez de
    // `(a shl 24) or (b shl 16)`.

    private fun readUint16(bytes: ByteArray, offset: Int): UInt {
        val hi = (bytes[offset].toInt() and 0xFF)
        val lo = (bytes[offset + 1].toInt() and 0xFF)
        return ((hi shl 8) or lo).toUInt()
    }

    private fun readUint32(bytes: ByteArray, offset: Int): UInt {
        val b0 = (bytes[offset].toLong() and 0xFF)
        val b1 = (bytes[offset + 1].toLong() and 0xFF)
        val b2 = (bytes[offset + 2].toLong() and 0xFF)
        val b3 = (bytes[offset + 3].toLong() and 0xFF)
        return (((b0 shl 24) or (b1 shl 16)) or (b2 shl 8) or b3).toUInt()
    }
}
