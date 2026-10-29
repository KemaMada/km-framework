package com.example.keymessage.data

import com.example.keymessage.storage.room.dao.MessageDao
import com.example.keymessage.ui.contact.UltimoMensaje
import com.example.keymessage.ui.contact.aUltimoMensaje
import com.km.model.IdentityId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * F2 — El ultimo mensaje por par, REACTIVO y en UNA consulta.
 *
 * ## POR QUE UN REPOSITORIO Y NO UNA CONSULTA EN LA PANTALLA
 *
 * Porque la fila de contactos necesita el ultimo mensaje de CADA par, y hacerlo
 * en la pantalla seria una consulta por fila (N+1). Aqui se hace UNA consulta
 * agregada ([MessageDao.observeUltimos]) y el resultado se indexa por raiz de
 * par: la pantalla solo hace `get`.
 *
 * ## POR QUE LA CLAVE ES LA RAIZ DEL PAR
 *
 * Porque la fila de contactos esta indexada por la raiz del contacto, que es
 * justo el "otro extremo" de la conversacion. La proyeccion del peer la hace
 * [aUltimoMensaje], que conoce la identidad LOCAL.
 *
 * ## SIN FILAS ES UN MAPA VACIO, NO UN ERROR
 *
 * Un almacen sin mensajes devuelve `emptyMap()`: "sin mensajes" es un resultado
 * valido y la UI lo pinta como "SIN MENSAJES", no como un fallo.
 */
class ConversacionResumenRepository(
    private val messageDao: MessageDao,
    private val localIdentity: IdentityId,
) {

    /**
     * El ultimo mensaje de cada par con el que hay conversacion.
     *
     * @return un flujo del mapa `raizDePar -> UltimoMensaje`.
     */
    fun observeResumenes(): Flow<Map<String, UltimoMensaje>> =
        messageDao.observeUltimos(localIdentity.value)
            .map { filas ->
                filas.map { it.aUltimoMensaje(localIdentity.value) }
                    .associateBy { it.peerId }
            }
}
