# KM-0000 — Terminology

| Campo | Valor |
|-------|-------|
| **RFC** | KM-0000 |
| **Título** | Terminology |
| **Estado** | Draft |
| **Versión** | 0.1 |
| **Autor** | KeyMessage Project |
| **Última actualización** | 2026-07-24 |

---

## 1. Propósito

Este documento define la terminología oficial utilizada por el protocolo KeyMessage.

Todos los RFC del proyecto **DEBEN** utilizar estos términos con el significado aquí especificado.

Los clientes, servidores y herramientas relacionadas **DEBERÍAN** utilizar la misma terminología para mantener consistencia.

---

## 2. Convenciones

Este documento utiliza las palabras normativas definidas en [RFC 2119](https://datatracker.ietf.org/doc/html/rfc2119):

| Término | Significado |
|---------|-------------|
| **MUST** / **DEBE** | Requisito absoluto |
| **MUST NOT** / **NO DEBE** | Prohibición absoluta |
| **SHOULD** / **DEBERÍA** | Recomendación, puede ignorarse con justificación |
| **SHOULD NOT** / **NO DEBERÍA** | Desaconsejado, puede hacerse con justificación |
| **MAY** / **PUEDE** | Opcional |

---

## 3. Términos

### Identity

Una identidad criptográfica permanente.

Una **Identity** representa a un usuario, independientemente del número de dispositivos utilizados.

Una Identity posee al menos:

- clave pública
- clave privada
- identityId

Una Identity **MAY** estar asociada a múltiples **Devices**.

### Identity ID

Identificador único de una **Identity**.

Identificador derivado determinísticamente de la clave pública según **KM-ID-0001 §6**:

```
identityId = SHA-256("KM-ID-IDENTITY" || publicKey)
```

representado como una cadena hexadecimal de 64 caracteres (32 bytes). Veáse **KM-ID-0001 §6** para la especificación completa.

El algoritmo anterior (`first160bits(SHA-256(publicKey))`, 40 hex) queda reemplazado. Las RFC KM-0002 v0.5 y KM-0005 v0.3 ya actualizaron sus referencias.

### Device

Una instancia física o virtual que ejecuta un cliente KeyMessage.

Ejemplos:

- Android
- Linux
- Windows
- macOS

Cada **Device** pertenece exactamente a una **Identity**.

Un **Device** **MAY** ser revocado sin afectar a la **Identity**.

### Device ID

Identificador único de un **Device**.

Permite distinguir múltiples dispositivos pertenecientes a la misma **Identity**.

### Client

Implementación del protocolo KeyMessage.

Ejemplos:

- Cliente Android
- Cliente Linux
- Cliente Web

### Peer

Instancia **Client** que ha completado correctamente el proceso de autenticación definido por el protocolo.

Todo **Peer** **DEBE** poseer una **Identity** válida.

### Relay

Servidor encargado exclusivamente de intercambiar mensajes de señalización.

El **Relay**:

- **NO** almacena mensajes de usuario.
- **NO** cifra ni descifra contenido.
- **NO** participa en el intercambio de claves de sesión.
- **NO** interpreta el contenido de los **DataChannels**.

### TURN Server

Servicio utilizado únicamente para facilitar conectividad cuando una conexión P2P directa no es posible.

### Transport

Canal físico o lógico mediante el cual viajan los mensajes del protocolo.

Ejemplos:

- WebSocket
- libp2p
- Bluetooth
- LAN Discovery

El protocolo KeyMessage es independiente del **Transport** utilizado.

### Protocol Message

Unidad lógica intercambiada entre **Clients**.

Ejemplos:

- AUTH
- OFFER
- ANSWER
- ICE
- ACK

Un **Protocol Message** **NO** depende del medio de **Transport** utilizado.

### Session

Relación temporal entre dos **Peers** autenticados.

Una **Session** comienza cuando ambos extremos completan correctamente el proceso de autenticación y termina cuando cualquiera de los extremos la cierra.

Una **Session** está asociada a una **Identity**, no a una **Connection**.

### Connection

Canal de comunicación concreto utilizado durante una **Session**.

Una **Session** **MAY** utilizar varias **Connections** durante su vida útil.

Una **Connection** puede reemplazarse sin invalidar la **Session**.

Ejemplo:

1. WiFi
2. datos móviles
3. WiFi

La **Session** continúa aunque cambie la **Connection**.

### DataChannel

Canal WebRTC utilizado para transportar datos entre dos **Peers**.

El **DataChannel** forma parte del **Transport** WebRTC.

### Attachment

Contenido binario compartido entre **Peers**.

Los **Attachments** **MAY** transportarse mediante múltiples mecanismos definidos por el protocolo.

Ejemplos:

- DataChannel
- DHT
- HTTP
- libp2p

Ejemplos de contenido:

- imágenes
- videos
- audio
- documentos

### User Message

Información enviada entre dos o más **Identities** con significado para el usuario final.

Ejemplos:

- texto
- imagen
- audio

Todo **User Message** posee al menos:

- messageId
- sender
- timestamp
- payload

### Message ID

Identificador único de un **User Message** o un **Protocol Message**.

El algoritmo recomendado es UUIDv7.

### ACK

Mensaje utilizado para confirmar el estado de un **User Message** o un **Protocol Message**.

Estados actualmente definidos:

```
queued
  ↓
sent
  ↓
received
  ↓
read
```

### Group

Conjunto de **Identities** que comparten un espacio común de comunicación.

La arquitectura de **Groups** está definida en **KM-0005**.

### Metadata

Información descriptiva de un **Group**.

Ejemplos:

- nombre
- descripción
- foto
- configuración

### Role

Permisos asignados a una **Identity** dentro de un **Group**.

Ejemplos:

- Owner
- Administrator
- Moderator
- Member

### DHT

Tabla Hash Distribuida utilizada para compartir metadatos descentralizados.

La implementación oficial está definida en **KM-DHT-0001**.

### Storage

Sistema responsable de almacenar información local.

Puede incluir:

- **Persistent Storage** (ej: SQLite)
- **Transient Cache** (ej: en memoria)
- **Distributed Storage** (ej: DHT)

### Capability

Característica opcional soportada por un **Peer**.

Las **Capabilities** representan soporte funcional, no permisos.

Ejemplos:

- ack
- ping
- groups
- voice
- relay

Las **Capabilities** se negocian durante el proceso de autenticación.

### Protocol Version

Versión del protocolo KeyMessage implementada por un **Peer**.

No debe confundirse con la versión de la aplicación.

### Application Version

Versión del software **Client**.

### Database Version

Versión del esquema de **Storage** local.

### Threat Actor

Entidad que intenta comprometer la seguridad del sistema.

Se clasifican en:

- **Passive Attacker**: observa el tráfico sin modificarlo.
- **Active Attacker**: intercepta, modifica o inyecta tráfico.

Ejemplos:

- ISP
- Relay comprometido
- Nodo malicioso
- Malware local
- Gobierno
- Atacante remoto

### Endpoint

Extremo lógico de una comunicación.

Normalmente un **Device** expone uno o más **Endpoints**.

### Nonce

Valor aleatorio utilizado una única vez para prevenir ataques de repetición.

### Replay Attack

Intento de reutilizar mensajes previamente válidos para producir una acción no autorizada.

### Payload

Contenido útil de un **User Message** o un **Protocol Message**.

Su formato depende del tipo de mensaje.

---

## 4. Abreviaturas

| Abreviatura | Significado |
|-------------|-------------|
| SDP | Session Description Protocol |
| ICE | Interactive Connectivity Establishment |
| STUN | Session Traversal Utilities for NAT |
| TURN | Traversal Using Relays around NAT |
| DHT | Distributed Hash Table |
| RTT | Round Trip Time |
| ACK | Acknowledgement |
| UUID | Universally Unique Identifier |
| ULID | Universally Unique Lexicographically Sortable Identifier |

---

## 5. Principio fundamental

El protocolo KeyMessage especifica el comportamiento esperado entre implementaciones, no una implementación concreta.

Ningún RFC **DEBE** depender de un lenguaje de programación, sistema operativo, biblioteca o framework específico.

Cualquier **Client** que cumpla con las especificaciones de los RFC correspondientes **DEBE** considerarse una implementación válida del protocolo.

La interoperabilidad entre implementaciones tiene prioridad sobre los detalles internos de implementación.

---

## 6. Historial

| Versión | Fecha | Cambios |
|---------|-------|---------|
| 0.1 | 2026-07-24 | Documento inicial — términos: Identity, Device, Client, Peer, Relay, TURN, Transport, Protocol Message, User Message, Session, Connection, DataChannel, Attachment, ACK, Group, Metadata, Role, DHT, Storage, Capability, Endpoint, Nonce, Payload, Threat Actor, Replay Attack |
