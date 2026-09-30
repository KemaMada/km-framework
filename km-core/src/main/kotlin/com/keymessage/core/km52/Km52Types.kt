package com.keymessage.core.km52

import com.keymessage.core.messaging.FrameIdentity
import com.keymessage.core.messaging.PendingInboundEntry
import com.keymessage.core.model.MessageId
import com.keymessage.core.ratchet.DoubleRatchetSnapshot

/**
 * 3Q.5.2b-A — Tipos de la unidad de transmision `KM52`.
 *
 * ## v2 (3Q.5.3) — QUE CAMBIO Y POR QUE
 *
 * Tres cosas, y solo tres:
 *
 *  1. `unitVersion` pasa a **2**.
 *  2. Aparece el bloque **`PENDING_INBOUND`**: los frames entrantes que la sesion
 *     todavia no ha podido usar, en la MISMA unidad atomica que el estado
 *     criptografico. Antes vivian en un `ArrayDeque` en memoria que nadie
 *     vaciaba y del que nada salia del proceso.
 *  3. El bloque **outbound pasa a ser opcional** (cero o uno). Sigue siendo
 *     SINGULAR: no hay lista, ni contador, ni presupuesto propio. Su techo lo
 *     impone [Km52Spec.MAX_CIPHERTEXT_LENGTH], y por eso [Km52PendingLimits]
 *     —que es donde viven las cotas de 3Q.5.3— no declara ninguna cota de
 *     outbound.
 *
 * Lo que NO cambio, y es lo importante: `SecureFrame` v1 (44 B, header entero
 * como AAD), el ratchet, la version del REGISTRO (que es distinta de la version
 * de la UNIDAD), y la asimmetria de §6.9 —podar al escribir, rechazar entero al
 * leer— que ahora rige tambien L2.
 *
 * ## QUE ES ESTA UNIDAD
 *
 * Lo que 3Q.5.2b llama "unidad atomica": el estado del ratchet, el registro de
 * salida y el ciphertext original, escritos JUNTOS y con una unidad de
 * atomicidad propia. Este archivo NO decide cuando se escribe ni donde: solo
 * fija la FORMA y los nombres. La escritura, el write-ahead y el commit son del
 * checkpoint siguiente.
 *
 * ## DONDE ESTA LA FRONTERA, Y POR QUE ESTE PAQUETE NO ES EL DEL RATCHET
 *
 * El ratchet no puede saber nada de `messageId`, entrega, reintento ni
 * transporte (§1 de la spec, `SNAP-04` de 3Q.5.2a). Y al reves: el `SNAPSHOT`
 * de esta unidad no es un estado del ratchet, es su REPRESENTACION PERSISTIDA,
 * que lleva dos cosas que el ratchet no tiene:
 *
 *  - `lastUseOrdinal` por cadena de recepcion (§6.9.4), que solo existe para
 *    ordenar la retencion de claves saltadas;
 *  - las cotas de retencion (§6.9, §6.10), que son una politica de este
 *    almacen.
 *
 * Por eso el paquete es `com.keymessage.core.km52` y no `...ratchet`: asi
 * `SNAP-04` puede seguir auditando el directorio del ratchet con su lista de
 * prohibidos y encontrarlo limpio, sin tener que consultar de que el codec sabe
 * lo que sabe.
 *
 * ## LO QUE ESTE ARCHIVO NO HACE
 *
 * No valida. Un [OutboundRecord] con un estado de entrega imposible se puede
 * construir: la validacion vive en [Km52UnitCodec], en un solo sitio, y por eso
 * no puede discrepar de si misma.
 */

/**
 * Constantes del formato `KM52` v1.
 *
 * ## ENDIANNESS
 *
 * Los enteros PROPIOS de la unidad son little-endian (R-FMT-ENDIAN-01, ratificado
 * por el usuario). El material criptografico y los identificadores viajan como
 * bytes opacos en su orden canonico, porque no son enteros de la unidad: la
 * `frameIdentity` son los 40 bytes del AAD del SecureFrame tal y como viajan por
 * el cable, y el `messageId` es un UUID en su orden canonico (RFC 4122). La
 * divergencia con el wire big-endian es INTENCIONAL y "corregirla" seria un
 * cambio de version de formato, no un bugfix.
 */
object Km52Spec {
    /** Identidad del formato: `KM52`. */
    val MAGIC: ByteArray = byteArrayOf(0x4B, 0x4D, 0x35, 0x32)

    /**
     * Version de la unidad. Distinto de la version del registro de salida.
     *
     * v2 (3Q.5.3) añade el bloque `PENDING_INBOUND` y el campo
     * [PENDING_INBOUND_LEN_OFFSET]. v1 es un layout DISTINTO y su byte-string se
     * rechaza entero: no hay lectura mixta, porque las dos versiones tienen
     * longitudes de bloque distintas y una unidad v1 pasada por el lector de v2
     * leeria el bloque equivocado.
     */
    const val UNIT_VERSION: UByte = 2u

    /** Cabecera de longitud FIJA. */
    const val HEADER_LENGTH: Int = 32

    /** SHA-256 sin clave sobre todo lo anterior. */
    const val CHECKSUM_LENGTH: Int = 32

    /** Una unidad no puede ser menor que cabecera + checksum. */
    const val MIN_UNIT_LENGTH: Int = HEADER_LENGTH + CHECKSUM_LENGTH

    const val MAGIC_OFFSET: Int = 0
    const val UNIT_VERSION_OFFSET: Int = 4
    const val FLAGS_OFFSET: Int = 5
    const val RESERVED16_OFFSET: Int = 6
    const val SNAPSHOT_LEN_OFFSET: Int = 8
    const val OUTBOUND_LEN_OFFSET: Int = 12
    const val CIPHERTEXT_LEN_OFFSET: Int = 16
    const val TOTAL_LEN_OFFSET: Int = 20

    /**
     * Longitud del bloque `PENDING_INBOUND`. Ocupa el primer medio del antiguo
     * `reserved2`.
     *
     * Es un campo NUEVO, y no un `reserved2` reusado a proposito: v1 tenia ocho
     * bytes a cero aqui y v2 tiene cuatro que describen un bloque. Un byte-string
     * v1 pasa a ser un byte-string v2 con una longitud distinta, y por eso la
     * version se comprueba ANTES que ninguna longitud.
     */
    const val PENDING_INBOUND_LEN_OFFSET: Int = 24

    /** Los cuatro bytes que quedan de `reserved2`. A cero, siempre. */
    const val RESERVED2_OFFSET: Int = 28

    /** Inicio del bloque SNAPSHOT. */
    const val SNAPSHOT_OFFSET: Int = HEADER_LENGTH

    /**
     * Partes fijas del bloque OUTBOUNDRECORD, antes del ciphertext.
     *
     * `recVersion(1) + deliveryState(1) + reserved(2) + messageId(16) +
     * frameIdentityLen(4) + frameIdentity(40) + createdOrdinal(8) = 72`.
     *
     * Los 8 ultimos bytes son `createdOrdinal` (§6.9.4). La spec §3.1 los llama
     * `reserved2` porque en esa tabla todavia no se habia decidido que lleva;
     * §6.9.4 y C-09 los concretan como el ordinal, y el ancho no cambia.
     *
     * ## LA ARITMETICA DE §3.1 CUENTA EL CIPHERTEXT DOS VECES, Y AQUI SE DICE
     * QUE SE HACE
     *
     * §3.1 escribe `totalLen == 32 + snapshotLen + outboundLen + ciphertextLen + 32`
     * y su propia cuenta deenol caso (404 984 B) sale de sumar las tres asi.
     * Pero `OUTBOUNDRECORD` (§3.1) TERMINA en `ciphertext(variable)`: el
     * ciphertext esta DENTRO del bloque, asi que `outboundLen` ya lo contiene y
     * la unidad tendria 65 535 bytes de mas que su propio `totalLen`.
     *
     * Se ha seguido la ESTRUCTURA de §3.1 —`UNIDAD = [Header 32][SNAPSHOT]
     * [OUTBOUND][Checksum 32]`, cuatro regiones y ninguna mas— y no la suma. Es
     * decir: `outboundLen` es el bloque entero, `ciphertextLen` es el tamano del
     * ultimo campo, y la comprobacion es
     * `ciphertextLen == outboundLen - 72`. El campo `ciphertextLen` no se
     * elimina: se queda, y se CONTRASTA, que es lo que evita que dos
     * describan el mismo campo con longitudes distintas.
     *
     * Ninguna cota depende de cual de las dos lecturas se elija: los contadores
     * son u32 y §3.1 declara que ninguno se desborda.
     */
    const val OUTBOUND_FIXED_LENGTH: Int = 72

    /** Longitud de la identidad de frame: `dhPublicKey(32) + PN(4) + N(4)`. */
    const val FRAME_IDENTITY_LENGTH: Int = 40

    /**
     * Cabecera fija del bloque SNAPSHOT, hasta la cadena de envio.
     *
     * `rootKey(32) + dhSelfScalar(32) + dhRemotePresent(1) + dhRemote(32) +
     * Ns(4) + PN(4) = 105`.
     */
    const val SNAPSHOT_FIXED_LENGTH: Int = 105

    /**
     * Registro de la cadena de envio: `CKs(32) + Ns(4) + Nr(4) + count(2) = 42`.
     *
     * NO lleva `CKr`: la cadena de envio de una sesion NUNCA recibe
     * (`DoubleRatchetSession.commitReceive` solo llama a `commitReceive` sobre
     * `receiveChains`), asi que su mitad de recepcion es un reflejo de la de
     * envio y se deriva al leer. El codec comprueba la invariante antes de
     * escribir: si alguna vez dejara de cumplirse, serializarla perderia estado
     * en silencio.
     */
    const val SEND_CHAIN_LENGTH: Int = 42

    /**
     * Cabecera fija de una cadena de recepcion: 114 B.
     *
     * `chainId(32) + lastUseOrdinal(8) + CKs(32) + Ns(4) + CKr(32) + Nr(4) +
     * count(2)`.
     *
     * La spec §3.1 da 110 B, y la diferencia son 4: su lista
     * `chainId(32)+CKs(32)+Ns(4)+Nr(4)+CKr(32)+Nr(4)+count(2)` repite `Nr(4)`.
     * `SymmetricRatchetSnapshot` tiene UN `receiveMessageNumber`, asi que aquí
     * van 106 + los 8 del ordinal de §6.9.4. No cambia ninguna cota: el
     * presupuesto se mide en claves retenidas, no en cabeceras.
     */
    const val RECEIVE_CHAIN_LENGTH: Int = 114

    /** `chainId(32) + N(4) + messageKey(32)`. */
    const val SKIPPED_ENTRY_LENGTH: Int = 68

    /** Tope del ciphertext: el `length` del SecureFrame v1 son 2 bytes. */
    const val MAX_CIPHERTEXT_LENGTH: Int = 0xFFFF

    /**
     * Longitud del `count(4)` que abre el bloque `PENDING_INBOUND` de `v2`.
     *
     * El bloque tiene forma `count(4) || entrada*`, asi que un bloque con cero
     * entradas NO es de longitud cero: mide estos cuatro bytes. Es lo que permite
     * que una sesion sin frames entrantes pendientes se persista, y lo que hace
     * que una longitud de bloque de cero sea ambigua con "no hay bloque".
     */
    const val PENDING_INBOUND_COUNT_LENGTH: Int = 4

    /**
     * Cabecera FIJA de una entrada del bloque `PENDING_INBOUND` de `v2`.
     *
     * `frameIdentityLen(4) + frameIdentity(40) + wireLen(4) = 48`, y el wire-frame
     * va detras, hasta el final de la entrada.
     *
     * ## POR QUE 48 Y NO 44
     *
     * Es el ancho del SecureFrame v1 (44 B) MAS cuatro. No es una coincidencia:
     * la identidad son los 40 bytes que ocupan los offsets 4..43 del header, y
     * los cuatro que faltan son el `frameIdentityLen` que los precede. El
     * `wireLen` hace falta porque el wire-frame es de longitud VARIABLE y la
     * identidad no lo describe.
     */
    const val PENDING_ENTRY_FIXED_LENGTH: Int = 48
}

/**
 * Cotas del bloque `PENDING_INBOUND` de `KM52` v2 (3Q.5.3).
 *
 * ## POR QUE EXISTE UN OBJETO DISTINTO DEL DE LAS CLAVES
 *
 * Porque las dos cotas se miden en cosas distintas y se violan por motivos
 * distintos. [Km52RetentionLimits.MAX_TOTAL_RETAINED_BUDGET] mide material
 * CRIPTOGRAFICO retenido y su exceso se arregla podando una clave que el ratchet
 * todavia podria necesitar; [MAX_PENDING_INBOUND_BUDGET] mide mensajes ENTRANTES
 * sin procesar y su exceso se arregla tirando el mas antiguo, que ya nadie va a
 * recuperar. Confundirlas daria un error que no dice que hacer.
 */
object Km52PendingLimits {

    /**
     * L2 — techo del bloque `PENDING_INBOUND`: 256 KiB.
     *
     * ALCANZABLE por construccion: un frame de 65 579 B entra cuatro veces, y
     * mil frames de 256 B entran mil veces. Es una cota de POLITICA de este
     * almacen, no un invariante del formato.
     */
    const val MAX_PENDING_INBOUND_BUDGET: Int = 256 * 1024      // 262 144

    /**
     * L4 — **Defensive Unit Bound**: 1 MiB. NO es una cota de politica.
     *
     * La maxima unidad LEGAL de v2 es
     *
     * ```
     *   273 778  bloque SNAPSHOT (§3.1, 8 cadenas x 1000 retenidas)
     * +  65 607  bloque OUTBOUND (72 fijos + 65 535 de ciphertext)
     * + 262 144  bloque PENDING_INBOUND (L2 entero)
     * +      64  cabecera (32) + checksum (32)
     * = 601 593 B = 587 KiB
     * ```
     *
     * 601 593 < 1 048 576. **Ninguna unidad legal puede alcanzar este limite por
     * construccion**, y por eso activarlo no significa "hace falta mas sitio":
     * significa que se ha violado un invariante, que el byte-string esta
     * corrupto o que hay un defecto de implementacion. Por eso el rechazo es
     * COMPLETO y no una poda: no hay nada legitimo que recuperar de ahi.
     *
     * No existe NINGUN otro valor que pueda actuar como cota de politica: por
     * encima de 601 593 es inalcanzable, y por debajo rechazaria unidades
     * legales.
     */
    const val DEFENSIVE_UNIT_BOUND: Int = 1024 * 1024           // 1 048 576

    /** La maxima unidad legal, recalculada aqui para que el limite se pueda auditar. */
    const val MAX_LEGAL_UNIT: Int =
        Km52Spec.SNAPSHOT_FIXED_LENGTH +                      // 105
            Km52Spec.SEND_CHAIN_LENGTH +                       // 42
            Km52RetentionLimits.MAX_RECEIVE_CHAINS * (Km52Spec.RECEIVE_CHAIN_LENGTH + 2) +   // 8 * 116
            Km52RetentionLimits.MAX_RETAINED_PER_CHAIN *
            Km52RetentionLimits.MAX_RECEIVE_CHAINS * Km52Spec.SKIPPED_ENTRY_LENGTH +          // 8000 * 68
            Km52Spec.OUTBOUND_FIXED_LENGTH + Km52Spec.MAX_CIPHERTEXT_LENGTH +                // 65 607
            Km52PendingLimits.MAX_PENDING_INBOUND_BUDGET +    // 262 144
            Km52Spec.HEADER_LENGTH + Km52Spec.CHECKSUM_LENGTH  // 64
}

/**
 * Cotas de retencion, ratificadas (§6.9.1, §6.10).
 *
 * ## POR QUE ESTAS TRES Y NO UNA
 *
 * `MAX_TOTAL_RETAINED_BUDGET` es la restriccion REAL (§6.10 R-JER-01): 200 KiB
 * de material retenido. Las otras dos son mas estrechas en su propio ambito y
 * por eso no se pueden deducir de la primera:
 *
 *  - `MAX_RETAINED_PER_CHAIN = 1000` esta DETERMINADA por
 *    [com.keymessage.core.ratchet.SymmetricRatchetSpec.MAX_SKIP], que ya es
 *    1000: el almacen no debe poder exceder lo que un salto deposita.
 *  - `MAX_RECEIVE_CHAINS = 8` es un SOFT-CAP de eviction, no un invariante de
 *    seguridad (§6.10 R-JER-03). Con el presupuesto global, 8x1000 = 8000 claves
 *    (544 000 B) NO es alcanzable (R-JER-05), asi que el numero de cadenas no
 *    es una frontera de seguridad: acota el trabajo de eviction.
 *
 * Y el presupuesto NO es solo almacenamiento (§6.8): una clave retenida es
 * capacidad criptografica de descifrado FUTURO. Retener mas material es
 * reluctantly mas superficie de compromiso, y por eso el techo es una decision
 * de exposicion.
 */
object Km52RetentionLimits {
    const val SKIPPED_ENTRY_LENGTH: Int = Km52Spec.SKIPPED_ENTRY_LENGTH
    const val MAX_TOTAL_RETAINED_BUDGET: Int = 200 * 1024      // 204 800
    const val MAX_RETAINED_PER_CHAIN: Int = 1000
    const val MAX_RECEIVE_CHAINS: Int = 8

    /**
     * Techo de claves retenidas bajo el modelo de coste de §6.1.
     *
     * `204800 / 68 = 3011`. Es un TECHO, no una promesa: nada obliga a que
     * existan 3011 claves simultaneamente, y de hecho 8x1000 no llega (§6.10
     * R-JER-05). La capacidad efectiva es `min(global, suma de per-chain)`.
     */
    const val MAX_TOTAL_RETAINED_KEYS: Int = MAX_TOTAL_RETAINED_BUDGET / SKIPPED_ENTRY_LENGTH
}

/**
 * Que cota se ha superado.
 *
 * No son tres excepciones distintas: el checkpoint pide UN error tipado
 * (§8.4-L) y el llamante necesita distinguir "el presupuesto global" de "el
 * tope de una cadena" para decidir si puede podar o no. Un solo tipo con el
 * motivo dentro dice las dos cosas sin multiplicar las ramas de `catch`.
 */
enum class RetentionRule {
    /** Suma de claves retenidas por encima del presupuesto global. */
    TOTAL_BYTES,

    /** Una cadena por encima de `MAX_RETAINED_PER_CHAIN`. */
    PER_CHAIN,

    /** Mas de `MAX_RECEIVE_CHAINS` cadenas de recepcion. */
    RECEIVE_CHAINS,
}

/**
 * Se supero L2: el bloque `PENDING_INBOUND` no cabe en su presupuesto.
 *
 * ## POR QUE ES UN TIPO PROPIO Y NO UN `OUTBOUND...` CUALQUIERA
 *
 * Por la misma razon que [RetentionBudgetExceeded] no hereda de
 * [Km52FormatException]: quien restaura tiene que poder distinguir "el estado
 * retenido es demasiado" de "el inbound pendiente es demasiado" sin leer el
 * mensaje, porque las dos respuestas son distintas —una se poda al escribir y
 * la otra se descarta por el frente; la otra se rechaza entera al leer— y
 * quien llama toma una decision.
 *
 * ## LA ASIMETRIA DE §6.9, REUTILIZADA TAL CUAL
 *
 *  - AL ESCRIBIR es un limite de DATOS: se descarta por el frente, que es
 *    [PendingInbox.purgarPorElFrente], y se escribe la unidad ya conforme.
 *  - AL LEER es un limite de VALIDACION: la unidad se RECHAZA entera.
 *
 * Podar al leer enmascararia corrupcion y elegiria por el usuario que mensajes
 * se pierden, que no es una decision del restaurador.
 */
class PendingInboundBudgetExceeded(
    val actual: Long,
    val limit: Long,
) : RuntimeException(
    "el bloque PENDING_INBOUND ocupa $actual bytes por encima del presupuesto $limit",
)

/**
 * Se disparo L4: la unidad supera el **Defensive Unit Bound** de 1 MiB.
 *
 * ## POR QUE NO ES UNA COTA MAS Y POR QUE ES UN `Km52FormatException`
 *
 * Porque no se puede activar por una unidad legal: el maximo es 601 593 B. Su
 * aparicion dice que el byte-string esta corrupto o que hay un defecto de
 * implementacion, y las dos cosas son DATO INVALIDO, no "estado que no cabe".
 * De ahi que herede de [Km52FormatException] y no de
 * [PendingInboundBudgetExceeded]: quien restore solo necesita que la sesion
 * quede intacta, y en los dos casos lo que tiene delante es una unidad que no
 * cumple el contrato de la version.
 *
 * Su mensaje incluye la cuenta para que el diagnostico diga si el excesso viene
 * de un bloque o de todos.
 */
class Km52UnitTooLarge(
    val declared: Long,
    val limit: Long,
    val bloque: String,
) : Km52FormatException(
    "la unidad declara $declared bytes y el limite defensivo es $limit (bloque $bloque): " +
        "ninguna unidad legal puede llegar aqui, asi que esto es corrupcion o un defecto",
)

/**
 * Se supero una cota de retencion, al escribir o al leer.
 *
 * NO hereda de [Km52FormatException] a proposito: un byte-string corrupto y un
 * estado que excede un tope son fallos de naturaleza distinta, y quien restore
 * quiere poder distinguir "esta unidad no es valida" de "esta sesion retiene
 * demasiado" sin leer el mensaje.
 *
 * Al LEER no es negociable (§6.9.2): una unidad en disco por encima del
 * presupuesto es DATO INVALIDO y se rechaza entera. Podarla en silencio
 * enmascararia corrupcion y elegiria por el usuario que mensajes se pierden.
 */
class RetentionBudgetExceeded(
    val rule: RetentionRule,
    val actual: Long,
    val limit: Long,
) : RuntimeException(
    "cota de retencion superada ($rule): $actual por encima del tope $limit",
)

/** El byte-string no cumple el contrato de la version. */
open class Km52FormatException(message: String) : RuntimeException(message)

/** `unitVersion` distinta de [Km52Spec.UNIT_VERSION]. Provoca RECHAZO TOTAL. */
class UnsupportedUnitVersion(val version: UByte) :
    Km52FormatException("version de unidad no soportada: $version")

/** Los cuatro bytes de identidad no son `KM52`. */
class Km52BadMagic(val encontrado: String) :
    Km52FormatException("magic inesperado: '$encontrado', se esperaba 'KM52'")

/** Faltan bytes para la longitud que el propio header declara. */
class Km52Truncated(val declared: Long, val available: Int) :
    Km52FormatException("unidad truncada: el header declara $declared bytes, hay $available")

/** El SHA-256 de los ultimos 32 B no es el del cuerpo. */
class Km52ChecksumMismatch(val esperado: String, val obtenido: String) :
    Km52FormatException("checksum incorrecto: se esperaba $esperado y hay $obtenido")

/**
 * El material de la unidad no puede servir a ESTA sesion.
 *
 * Es distinto de un campo imposible: aqui los bytes son REGLAS, tienen la
 * longitud correcta y son coherentes entre si, pero describen una sesion
 * imposible. Aceptarlos seria fallar mas tarde y de forma opaca: un
 * `dhRemote` de orden pequeno aborta el ratchet en el primer `agree`, y una
 * clave retenida archivada bajo otra cadena no se vuelve a encontrar nunca.
 */
class ForeignSessionMaterial(message: String) : Km52FormatException(message)

/**
 * Estado del envio en el log de salida (§8.1-F, ratificado).
 *
 * 1 = PENDIENTE, 2 = IN_FLIGHT, 3 = DELIVERED, 4 = ACKED. El 0 queda reservado
 * para "ausente" y NO es un valor valido, igual que en [FrameType].
 */
enum class OutboundDeliveryState(val code: UByte) {
    /** Escrito y persistido, todavia sin salir por ningun transporte. */
    PENDIENTE(1u),

    /** Enviado, sin confirmacion. */
    IN_FLIGHT(2u),

    /** El otro extremo lo entrego a la aplicacion. */
    DELIVERED(3u),

    /** Confirmado con su ACK. */
    ACKED(4u),
    ;

    companion object {
        fun fromCode(code: UByte): OutboundDeliveryState? = values().firstOrNull { it.code == code }
    }
}

/**
 * Registro de salida: un envio que se puede retransmitir tal cual.
 *
 * ## POR QUE ES UN PORTADOR DE DATOS PURO
 *
 * El codec necesita serializarlo, asi que tiene que existir como estructura.
 * Lo que NO puede es tener reglas propias: si las tuviera, la validacion
 * viviria en dos sitios —aqui y en el codec— y podrian discrepar. Toda
 * comprobacion de este registro vive en `Km52UnitCodec`.
 *
 * ## LO QUE NO LLEVA, Y POR QUE
 *
 * El payload de la aplicacion NO viaja aqui (§8.2-I): vive en el
 * `MessageStore`, que ya lo tiene. Y `resendCount` se RETIRO (§3.1, §8.3): un
 * contador de reintentos es semantica de entrega, y el tope de reenvios es de
 * 3Q.5.4. Los 8 bytes que ocupaba son ahora [createdOrdinal].
 */
class OutboundRecord(
    val recordVersion: UByte,
    val deliveryState: OutboundDeliveryState,
    val messageId: MessageId,
    val frameIdentity: FrameIdentity,
    val createdOrdinal: ULong,
    ciphertext: ByteArray,
) {
    /** Ciphertext ORIGINAL del SecureFrame, con su tag. Copia defensiva. */
    val ciphertext: ByteArray = ciphertext.copyOf()
}

/**
 * Cuando se uso por ultima vez una cadena de recepcion (§6.9.4).
 *
 * Contador MONOTONO de sesion, no tiempo: INV-07 prohibe depender del reloj, y
 * dos ejecuciones con el mismo estado tienen que elegir la MISMA victima. Se
 * incrementa al servir un frame de esa cadena.
 *
 * ## LA FRONTERA QUE ESTE CAMPO SOSTIENE
 *
 * Ordena la retencion de CLAVES SALTADAS, que es material que el ratchet ya
 * posee (`receiveChains`, `MAX_SKIP`). No sabe que es un mensaje, ni que se
 * entrego, ni por donde viaja, ni con quien. Su unico consumidor es la politica
 * de victimizacion, y por eso vive en la unidad y no en el estado del ratchet.
 */
class ChainUseOrdinal(
    chainId: ByteArray,
    val lastUseOrdinal: ULong,
) {
    /** Clave publica DH que abre la epoca. */
    val chainId: ByteArray = chainId.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChainUseOrdinal) return false
        return lastUseOrdinal == other.lastUseOrdinal && chainId.contentEquals(other.chainId)
    }

    override fun hashCode(): Int = 31 * chainId.contentHashCode() + lastUseOrdinal.hashCode()

    override fun toString(): String =
        "ChainUseOrdinal(${chainId.joinToString("") { "%02x".format(it) }}, $lastUseOrdinal)"
}

/**
 * Tabla de orden de retencion: una entrada por cadena de recepcion.
 *
 * ## POR QUE NO ESTA DENTRO DEL SNAPSHOT DEL RATCHET
 *
 * `DoubleRatchetSnapshot` esta congelado por 3Q.5.2a y auditado campo a campo
 * por `SNAP-04b`, que exige que sus campos sean EXACTAMENTE los del material
 * criptografico del ratchet. Un contador cuya unica funcion es ordenar una
 * politica de eviction no es material criptografico: meterlo dentro obligaria al
 * ratchet a saber de retencion, que es justo la frontera que §1 de la spec
 * prohibe. Aqui la sesion puede seguir siendo "solo material criptografico" y
 * el material retenido puede seguir ordenandose.
 *
 * ## COMPLETITUD
 *
 * Tiene que cubrir EXACTAMENTE las cadenas del snapshot: ni una de mas, ni una
 * de menos. Una cadena sin ordinal no tiene victima determinable, y un ordinal
 * huerfano apunta a una cadena que ya no existe. Las dos cosas son datos
 * invalidos, no valores por defecto.
 */
class ReceiveChainRetention(entries: List<ChainUseOrdinal>) {
    /** Las entradas, en el orden en que las dio el llamante. */
    val entries: List<ChainUseOrdinal> = entries.map { ChainUseOrdinal(it.chainId, it.lastUseOrdinal) }

    init {
        // Comparacion POR CONTENIDO y no por hash: con ocho entradas la
        // comparacion por pares es gratis, y un `contentHashCode` podria dar
        // dos entradas iguales como distintas y dejar pasar la que esta
        // prohibida. Una colision de hash aqui seria un falso NEGATIVO, que es
        // justo la clase de fallo que la tabla tiene que cerrar.
        for (i in this.entries.indices) {
            for (j in i + 1 until this.entries.size) {
                require(!this.entries[i].chainId.contentEquals(this.entries[j].chainId)) {
                    "hay dos entradas de retencion para la misma cadena de recepcion"
                }
            }
        }
    }

    /** Ordinal de la cadena, o `null` si la tabla no la cubre. */
    fun lastUseOrdinal(chainId: ByteArray): ULong? {
        for (e in entries) if (e.chainId.contentEquals(chainId)) return e.lastUseOrdinal
        return null
    }

    val size: Int get() = entries.size
}

/**
 * Una unidad `KM52` completa: los cuatro bloques de `v2`, ya validados.
 *
 * ```
 * [HEADER 32][SNAPSHOT][OUTBOUND][PENDING_INBOUND][Checksum 32]
 * ```
 *
 * No se construye a medias: [Km52UnitCodec.deserialize] solo devuelve una
 * unidad cuando los cuatro bloques han validado, y [serialize] solo escribe una
 * cuando la que le dan cumple las cotas. Un fallo en cualquiera de los dos
 * deja el estado de la sesion exactamente como estaba.
 *
 * ## POR QUE EL OUTBOUND ES OPCIONAL Y EL INBOUND UNA LISTA
 *
 * El outbound es **singular** porque 3Q.5.3 NO decide que envio se sacrifica:
 * la cardinalidad de los envios pendientes es de 3Q.5.4. Que sea opcional y no
 * obligatorio es una consecuencia, no una decision: una sesion puede tener
 * frames entrantes sin usar y ningun envio propio pendiente, y una unidad que
 * obligara a fabricar un `OutboundRecord` para representarla estaria mintiendo
 * sobre el envio.
 *
 * El inbound es una **coleccion** porque no hay decision que tomar: son frames
 * que LLEGARON, y llegado esta, cargado esta. Que quepan todos en L2 es una
 * cuestion de presupuesto, y de eso se encarga la bandeja, no de la unidad.
 */
class Km52Unit(
    /** Estado del ratchet, en su forma persistida. */
    val snapshot: DoubleRatchetSnapshot,
    /** Orden de retencion de las cadenas de recepcion del snapshot. */
    val retention: ReceiveChainRetention,
    /** El envio que esta unidad hace retransmisible, o `null` si no hay ninguno. */
    val outbound: OutboundRecord?,
    /**
     * Los frames entrantes sin usar, EN ORDEN DE LLEGADA.
     *
     * El orden no es decorativo: es FIFO y es lo que la sesion reproduce al
     * consumirlos. Un recorrido de mapa no seria comparable entre dos
     * ejecuciones del mismo estado (INV-07).
     */
    val pendingInbound: List<PendingInboundEntry> = emptyList(),
) {
    init {
        // Dos entradas con la MISMA `FrameIdentity` describen un estado que no
        // puede existir: la identidad es unica por frame dentro de la sesion, y
        // la bandeja ya deduplica al encolar. Se comprueba aqui, y no solo en el
        // codec, porque [Km52Unit] se puede construir a mano en una prueba y
        // una prueba que monta una unidad invalida y espera que el codec la
        // rechace tiene que poder montarla.
        for (i in pendingInbound.indices) {
            for (j in i + 1 until pendingInbound.size) {
                require(pendingInbound[i].frameIdentity != pendingInbound[j].frameIdentity) {
                    "hay dos entradas PENDING_INBOUND con la misma FrameIdentity " +
                        "${pendingInbound[i].frameIdentity}"
                }
            }
        }
    }
}
