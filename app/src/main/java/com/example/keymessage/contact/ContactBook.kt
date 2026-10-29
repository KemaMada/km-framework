package com.example.keymessage.contact

import com.km.model.IdentityId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * El registro de contactos VERIFICADOS de la app.
 *
 * ## QUE SE MUDO AL FRAMEWORK Y QUE SE QUEDA
 *
 * El TIPO de contacto ([VerifiedContact]) y la operacion de almacenar
 * reverificando ([com.km.identity.ContactRegistry]) son compartidos: el
 * escritorio necesitaba exactamente lo mismo y dos registros con dos politicas
 * se divergen. Lo que se queda en la app es el FLUJO (`StateFlow`): es lo que la
 * UI de Compose consume, y km-core no declara `kotlinx-coroutines`.
 *
 * ## POR QUE LA CLAVE ES LA RAIZ
 *
 * La raiz es la que firma el bundle y la que gobierna la sesion. Deduplicar por
 * dispositivo partiria una conversacion en dos cuando el contacto rota de
 * dispositivo.
 */
interface ContactBook : com.km.identity.ContactRegistry {

    /**
     * El estado PRESENTE del registro, como flujo HOT.
     *
     * Al suscribirse entrega INMEDIATAMENTE el estado que hay, y despues uno por
     * cada mutacion. Dos autores escriben (el auto-registro y el import manual) y
     * ninguno es la pantalla: con solo consultas, la lista seria una fotografia.
     */
    val contacts: StateFlow<List<VerifiedContact>>
}

/**
 * [ContactBook] en memoria sobre el registro COMPARTIDO.
 *
 * ## POR QUE DELEGA EN VEZ DE TENER SU PROPIO MAPA
 *
 * Porque la reverificacion al guardar y la deduplicacion por raiz ya estan
 * resueltas en [com.km.identity.InMemoryContactRegistry], y aqui repetirlas
 * seria la segunda copia que este change elimina. Cada mutacion se publica en el
 * flujo despues de que el registro la acepte: un rechazo NO emite, y el flujo se
 * queda con el valor anterior.
 *
 * ## SIN PERSISTENCIA
 *
 * El registro es estado de proceso: la app todavia no tiene un almacen de
 * contactos en disco (persist-contact-book sigue sin apply). Anadirlo es otra
 * implementacion de [ContactRegistry], no de esta lista.
 */
class InMemoryContactBook : ContactBook {

    private val registro = com.km.identity.InMemoryContactRegistry()

    private val _contacts = MutableStateFlow<List<VerifiedContact>>(emptyList())

    override val contacts: StateFlow<List<VerifiedContact>> = _contacts.asStateFlow()

    override fun all(): List<VerifiedContact> = registro.all()

    override fun find(identityRoot: IdentityId): VerifiedContact? = registro.find(identityRoot)

    override fun upsert(contact: VerifiedContact) {
        // El registro compartido reverifica y puede LANZAR: en ese caso el flujo
        // no se toca, que es lo que garantiza un `StateFlow` sin `update`.
        registro.upsert(contact)
        _contacts.update { registro.all() }
    }

    override fun remove(identityRoot: IdentityId) {
        registro.remove(identityRoot)
        _contacts.update { registro.all() }
    }
}
