package com.example.keymessage.ui.contact

import com.example.keymessage.contact.VerifiedContact
import com.example.keymessage.model.Contact
import com.example.keymessage.storage.room.entity.MessageEntity

/**
 * F2 — El ULTIMO mensaje de un par, ya resuelto para la fila.
 *
 * `peerId` es la RAIZ del OTRO extremo (la clave de ruteo de la conversacion),
 * NO quien escribio: la fila de contactos indexa por par.
 */
data class UltimoMensaje(
    /** La raiz del par (el "otro" extremo de la conversacion). */
    val peerId: String,
    /** El texto ya decodificado del ultimo mensaje. */
    val texto: String,
    /** Cuando se creo, en ms. */
    val timestamp: Long,
    /** Si el ultimo mensaje lo escribio el usuario local. */
    val esMio: Boolean,
)

/**
 * F2 — El resumen de un contacto: alias/huella y su ultimo mensaje.
 *
 * Se separa de [Contact] para no meter un tipo de la capa de storage en el
 * modelo de la UI: este es el resultado de la funcion PURA [mapearContactosOriginal].
 */
data class ResumenContacto(
    /** La raiz del contacto (clave de ruteo y de busqueda del ultimo mensaje). */
    val id: String,
    /** El alias/huella que se pinta en la PRIMERA linea. */
    val alias: String,
    /** El texto corto del ultimo mensaje, o `null` si no hay. */
    val snippet: String?,
    /** La hora/dia del ultimo mensaje, o `null` si no hay. */
    val hora: String?,
)

/** Cuantos caracteres de snippet se muestran como maximo en la fila. */
const val SNIPPET_MAX: Int = 80

/**
 * F2 — El snippet del ultimo mensaje, acotado a [SNIPPET_MAX].
 *
 * Colapsa los saltos de linea (una fila de lista no es un parrafo) y recorta
 * con elipsis cuando pasa del tope. NO inventa texto: un mensaje vacio devuelve
 * cadena vacia, que la UI trata como "sin mensaje".
 */
fun vistaPrevia(texto: String, max: Int = SNIPPET_MAX): String {
    val plano = texto.replace('\n', ' ').replace('\r', ' ').trim()
    if (plano.length <= max) return plano
    return plano.take(max) + "…"
}

/**
 * F2 — La hora del ultimo mensaje: `HH:mm` si es de HOY, `dd/MM` si es de antes.
 *
 * La decision vive en una funcion pura porque no hay `ComposeTestRule` en el
 * modulo: se prueba con un reloj inyectado. Un `timestamp` no positivo NO
 * produce una hora inventada: devuelve `""` y la UI no pinta la linea de hora.
 *
 * @param ahora el "ahora" en ms. Se inyecta para poder probar HOY vs ANTES.
 * @param zona la zona horaria, por defecto la del sistema.
 */
fun horaResumen(
    timestamp: Long,
    ahora: Long = System.currentTimeMillis(),
    zona: java.util.TimeZone = java.util.TimeZone.getDefault(),
): String {
    if (timestamp <= 0L) return ""
    val cal = java.util.Calendar.getInstance(zona).apply { timeInMillis = timestamp }
    val ahoraCal = java.util.Calendar.getInstance(zona).apply { timeInMillis = ahora }
    val mismoDia = cal.get(java.util.Calendar.YEAR) == ahoraCal.get(java.util.Calendar.YEAR) &&
        cal.get(java.util.Calendar.DAY_OF_YEAR) == ahoraCal.get(java.util.Calendar.DAY_OF_YEAR)
    return if (mismoDia) {
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val m = cal.get(java.util.Calendar.MINUTE)
        "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
    } else {
        val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val mes = cal.get(java.util.Calendar.MONTH) + 1
        "${d.toString().padStart(2, '0')}/${mes.toString().padStart(2, '0')}"
    }
}

/**
 * F2 — La linea de identidad de un contacto: su alias, o su huella corta.
 *
 * REGLA DE ORO: el alias aparece UNA SOLA VEZ. Si no hay alias, se recurre a un
 * ID CORTO (12 hex) de la raiz. NUNCA se devuelve la huella Y el id juntos, que
 * es la duplicacion que verify #368/#381 dejo como roce (fila con la huella
 * repetida).
 */
fun lineaIdentidad(contacto: VerifiedContact): String {
    val alias = contacto.displayName.trim()
    if (alias.isNotEmpty()) return alias
    val raiz = contacto.identityRoot.value
    return if (raiz.length > 12) raiz.take(12) else raiz
}

/**
 * F2 — La fila del libro de contactos al modelo de UI, con su ultimo mensaje.
 *
 * ## POR QUE ES UNA FUNCION PURA DE NIVEL SUPERIOR
 *
 * Porque el mapeo —que la clave es la raiz, que el alias no se duplica, que el
 * ultimo mensaje del par se resuelve por raiz— es la logica que importa, y se
 * prueba en JVM ([ResumenContactoTest]). El composable solo pinta.
 *
 * ## LA CLAVE DEL MAPA ES LA RAIZ DEL PAR
 *
 * [ultimos] viene indexado por `peerId`, que es la raiz del otro extremo. Como
 * cada contacto ES esa raiz, el `get` es directo. Un contacto sin entrada en el
 * mapa no tiene mensajes: se pinta "SIN MENSAJES", nunca una hora inventada.
 *
 * @param contactos el libro, en su orden de insercion.
 * @param ultimos el ultimo mensaje por raiz de par, como lo emite el repositorio.
 * @param ahora el "ahora" en ms, inyectado para probar HOY vs ANTES.
 */
fun mapearResumenes(
    contactos: List<VerifiedContact>,
    ultimos: Map<String, UltimoMensaje>,
    ahora: Long = System.currentTimeMillis(),
): List<ResumenContacto> = contactos.map { contacto ->
    val raiz = contacto.identityRoot.value
    val ultimo = ultimos[raiz]
    ResumenContacto(
        id = raiz,
        alias = lineaIdentidad(contacto),
        snippet = ultimo?.let { vistaPrevia(it.texto) }?.takeIf { it.isNotEmpty() },
        hora = ultimo?.let { horaResumen(it.timestamp, ahora) }?.takeIf { it.isNotEmpty() },
    )
}

/**
 * F2 — Proyecta una fila de `messages` al [UltimoMensaje] del par.
 *
 * El peer sale del OTRO extremo respecto a [local]: si el mensaje es mio, el par
 * es el destinatario; si no, el emisor. Esta funcion es TOTAL: recibe la fila ya
 * elegida por SQL (la de mayor timestamp/message_id por par).
 *
 * @param local la identidad LOCAL, para saber que extremo es el par.
 * @param decodificador como se saca el texto del payload. Es un parametro y no
 *   un `String(bytes)` fijo: el formato del payload es de la APLICACION.
 */
fun MessageEntity.aUltimoMensaje(
    local: String,
    decodificador: (ByteArray) -> String = { String(it) },
): UltimoMensaje {
    val esMio = from == local
    val peer = if (esMio) to else from
    val texto = runCatching { decodificador(payload) }.getOrElse { "<ilegible>" }
    return UltimoMensaje(peerId = peer, texto = texto, timestamp = timestamp, esMio = esMio)
}

/**
 * F2 — Mapea el libro de contactos al modelo de la lista (id raiz + nombre),
 * SIN el ultimo mensaje.
 *
 * Se conserva porque la CONVERSACION sigue derivando el contacto activo por el
 * id y porque el guard `MapearContactosTest` fija el contrato "id = raiz, name =
 * huella". La LISTA usa [mapearResumenes]; este mapeo es el del resto de la app.
 */
fun mapearContactosOriginal(contactos: List<VerifiedContact>): List<Contact> =
    contactos.map { Contact(id = it.identityRoot.value, name = it.fingerprint) }
