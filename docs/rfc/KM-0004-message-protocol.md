# KM-0004 — Message Protocol

| Metadata | Valor |
|----------|-------|
| **Estado** | Stable (Frozen) |
| **Versión** | 0.5 |
| **Última actualización** | 2026-07-25 |
| **Nota** | Congelado. Solo se corregirán bugs de especificación descubiertos durante la implementación. |
| **Autor** | KeyMessage Project |
| **Dependencias** | [KM-0000](./KM-0000-terminology.md), [KM-0001](./KM-0001-protocol-architecture-overview.md), [KM-0002](./KM-0002-authentication.md), [KM-0003](./KM-0003-relay-protocol.md) |

---

## 1. Purpose

KM-0004 define el modelo de mensajería de KeyMessage: la estructura, el ciclo de vida, la entrega, los acuses de recibo, la detección de duplicados y el reintento de **User Messages** entre **Peers**. Especifica qué significa "enviar un mensaje" en el protocolo, independientemente del **Transport** subyacente.

---

## 2. Scope

Este RFC cubre:

- La estructura conceptual y serializada de un **User Message**.
- El ciclo de vida de un mensaje desde su creación hasta su entrega.
- El protocolo de **ACK** (acuse de recibo) y retransmisión.
- La detección y manejo de mensajes duplicados.
- La cola offline del emisor.
- El modelo de adjuntos.
- Los códigos de error específicos de mensajería.

Este RFC **NO** cubre:

- La implementación concreta de almacenamiento local (SQLite, etc.).
- La interfaz de usuario.
- Grupos o canales multi-peer.
- Edición o borrado de mensajes.
- Historial, backups o sincronización multi-dispositivo.
- Lecturas o estados de visualización ("visto").
- La negociación de capacidades (véase KM-0002).

---

## 3. Definitions

| Término | Definición |
|---------|------------|
| **User Message** | Unidad fundamental de comunicación entre dos **Peers**. Contiene un payload cifrado y metadatos de enrutamiento y entrega. |
| **Message ID** | Identificador único e inmutable de un **User Message**. Generado por el emisor. **NUNCA** cambia. |
| **Conversation** | Secuencia ordenada de **User Messages** entre exactamente dos **Peers**. |
| **Delivery** | El proceso de transferir un **User Message** del emisor al receptor, incluyendo la verificación de recepción. |
| **Acknowledgement (ACK)** | Mensaje de protocolo con el que el receptor confirma que ha recibido y almacenado de forma persistente un **User Message**. |
| **Retry** | Reintento de envío de un **User Message** cuando el emisor no recibe un **ACK** dentro del tiempo esperado. |
| **Attachment** | Dato suplementario (imagen, archivo, audio) referenciado por un **User Message** mediante un **Content ID**. El contenido se transporta por un mecanismo separado. |
| **Payload** | Contenido cifrado de un **User Message**. Su formato interno es opaco para el protocolo de entrega. |
| **Delivery State** | Estado de un **User Message** en el emisor, según su ciclo de vida. |
| **Offline Queue** | Almacenamiento temporal en el emisor de **User Messages** cuyo **ACK** aún no se ha recibido, ya sea porque el receptor está desconectado o porque la retransmisión está en curso. |
| **Almacenamiento persistente** | Almacenamiento que sobrevive al cierre del proceso de la aplicación y al reinicio del dispositivo. Un mensaje en memoria RAM **NO** se considera almacenado de forma persistente. |

---

## 4. Goals

| # | Objetivo | Descripción |
|---|----------|-------------|
| G1 | **Cifrado extremo a extremo** | El contenido del **User Message** **DEBE** ser ininteligible para cualquier intermediario, incluido el **Relay**. |
| G2 | **Entrega idempotente** | Repetir un mensaje (por retransmisión) **NO DEBE** producir efectos secundarios en el receptor. |
| G3 | **Orden por conversación** | Los mensajes dentro de una misma **Conversation** **DEBEN** ser procesados en el orden de envío. |
| G4 | **Reintentos seguros** | El emisor **DEBE** poder reintentar la entrega sin riesgo de duplicación ni pérdida. |
| G5 | **ACK de persistencia** | El **ACK** confirma que el mensaje fue almacenado de forma persistente en el dispositivo del receptor, no meramente recibido en memoria. |
| G6 | **Soporte para adjuntos** | El modelo de mensajes **DEBE** permitir referenciar datos suplementarios (archivos, imágenes) sin acoplarse a un mecanismo de transferencia específico. |

---

## 5. Non-goals

| # | No objetivo | Motivo |
|---|-------------|--------|
| N1 | **Historial persistente en el Relay** | El **Relay** **NO DEBE** almacenar **User Messages** (RI2, KM-0003). |
| N2 | **Backups** | La sincronización con servicios de backup está fuera del alcance del protocolo base. |
| N3 | **Grupos y canales** | El protocolo base soporta únicamente comunicación uno a uno. Los grupos se definirán en un RFC futuro. |
| N4 | **Edición de mensajes** | Modificar un mensaje ya enviado requiere semántica adicional que no pertenece a este RFC. |
| N5 | **Borrado remoto** | La eliminación de mensajes en el dispositivo del receptor es una decisión de implementación. |
| N6 | **Sincronización multi-dispositivo** | Múltiples dispositivos de una misma **Identity** se definen en KM-0002; la sincronización de mensajes entre ellos está fuera del alcance. |
| N7 | **ACK de visualización** | El estado "leído" o "visto" se definirá en un RFC independiente. KM-0004 sólo estandariza el ACK de persistencia. |
| N8 | **Compresión** | La compresión del payload es responsabilidad de la capa de aplicación, no del protocolo de mensajería. |

---

## 6. Message Invariants

Las siguientes propiedades **DEBEN** mantenerse en todas las versiones del protocolo:

| # | Invariante | Descripción |
|---|------------|-------------|
| MI1 | **Inmutabilidad** | Un **User Message** **NUNCA** cambia después de ser creado. Toda modificación del contenido o los metadatos genera un nuevo mensaje con un nuevo **Message ID**. |
| MI2 | **Exactamente un ACK** | Cada **User Message** genera exactamente un **ACK** lógico. Las retransmisiones del mismo mensaje **NO DEBEN** producir **ACKs** adicionales desde el punto de vista del emisor. |

---

## 7. Message Model

Un **User Message** es la unidad fundamental de comunicación en KeyMessage. Conceptualmente, un mensaje contiene:

- **Message ID**: Identificador único e inmutable.
- **Remitente**: **Identity ID** del emisor.
- **Destinatario**: **Identity ID** del receptor.
- **Timestamp de creación**: Tiempo Unix en milisegundos en que el emisor creó el mensaje.
- **Payload cifrado**: Contenido del mensaje, cifrado extremo a extremo.
- **Content IDs** (opcional): Lista de identificadores de adjuntos asociados.
- **Metadatos de entrega**: Información de control utilizada por el protocolo de ACK (no visible para la aplicación).

El formato serializado se define en §18. El receptor **DEBE** descifrar el `payload` antes de presentarlo al usuario.

La relación entre mensajes se define exclusivamente mediante la **Conversation**: el conjunto de todos los **User Messages** intercambiados entre un par de **Peers**, ordenados por su **Message ID** o por el orden de transmisión.

---

## 8. Message Lifecycle

Cada **User Message** en el emisor sigue los siguientes estados:

```
CREATED

  ↓

QUEUED

  ↓

SENDING ───────────┐
  ↓                │
  │                │
SENT ──────────┐   │
  ↓            │   │
  │            │   │
DELIVERED   FAILED │
  │            │   │
  ↓            ↓   ↓
READ (futuro) EXPIRED
```

| Estado | Descripción |
|--------|-------------|
| **CREATED** | El mensaje fue construido y cifrado por la aplicación emisora. Aún no se ha intentado enviar. |
| **QUEUED** | El mensaje está en la **Offline Queue** esperando que el receptor esté disponible. |
| **SENDING** | El mensaje está siendo transmitido activamente al receptor a través del **Transport**. |
| **SENT** | El mensaje fue transmitido exitosamente al **Transport** subyacente. El emisor espera un **ACK**. |
| **DELIVERED** | El emisor recibió un **ACK** del receptor confirmando el almacenamiento persistente. |
| **FAILED** | El mensaje falló por un error inmediato (destino inválido, payload demasiado grande, versión incompatible, transporte caído). No se reintentará. |
| **EXPIRED** | El mensaje superó el tiempo máximo de reintento sin recibir **ACK**. Se descarta definitivamente. |

El emisor **DEBE** mantener el estado de cada mensaje. El receptor **PUEDE** mantener sólo el registro de **Message IDs** ya procesados para la detección de duplicados.

Un estado **NUNCA** retrocede. Una vez alcanzado **DELIVERED**, un mensaje no puede volver a **SENT** o **SENDING**. Una vez **EXPIRED** o **FAILED**, se descarta permanentemente.

La diferencia entre **FAILED** y **EXPIRED**:
- **FAILED**: error inmediato y no recuperable. El mensaje no pudo ser enviado para entrega (destino inválido, payload demasiado grande, versión incompatible, error de **Transport** antes de la transmisión). El emisor **NO DEBE** reintentar.
- **EXPIRED**: el mensaje sí salió del emisor (fue transmitido al **Transport**), pero nunca se recibió **ACK** dentro del tiempo definido por la implementación. El emisor abandonó la entrega tras agotar los reintentos.

---

## 9. Delivery Model

### 9.1 — Significado de los estados de entrega

| Estado | Significado para el emisor |
|--------|---------------------------|
| **SENT** | El mensaje fue entregado al **Transport** (Relay, red local, etc.). El emisor **NO** sabe aún si el receptor lo recibió. |
| **DELIVERED** | El receptor recibió el mensaje, lo descifró, lo almacenó de forma persistente en su dispositivo y envió un **ACK**. El mensaje no se perderá aunque la aplicación del receptor se cierre inmediatamente. |
| **FAILED** | El mensaje falló por un error inmediato y no se reintentará. |

### 9.2 — Quién genera el ACK

El **ACK** es generado exclusivamente por el **Peer** receptor tras:

1. Recibir el mensaje completo.
2. Realizar validación básica del formato (campos presentes, tipos correctos, tamaño dentro de límites).
3. Almacenar el mensaje de forma persistente en el dispositivo local.

El receptor **NO DEBE** esperar a descifrar ni procesar el `payload` para enviar el **ACK**. La presentación del mensaje al usuario **NO DEBE** formar parte de las condiciones para emitir un **ACK**. El flujo correcto es:

```
Receive packet
  ↓
Validación básica
  ↓
Persistir (almacenamiento durable)
  ↓
ACK ──→ (el emisor ya sabe que no se perderá)
  ↓
Descifrar
  ↓
Procesar
  ↓
Mostrar al usuario
```

El **ACK** significa exclusivamente "este mensaje está almacenado de forma persistente y no se perderá", no "la aplicación ya terminó de procesarlo".

El almacenamiento persistente **DEBE** completarse correctamente antes de generar el **ACK**. Si la operación de persistencia falla por cualquier motivo, el receptor **NO DEBE** enviar **ACK**. El emisor retransmitirá el mensaje cuando expire el temporizador de retransmisión, proporcionando al receptor una nueva oportunidad para almacenarlo de forma persistente.

### 9.3 — Pérdida del ACK

Si el **ACK** se pierde en la red, el emisor retransmitirá el mensaje (ver §11). El receptor **DEBE** detectar el duplicado por **Message ID** y reenviar el **ACK** sin procesar el `payload` nuevamente.

### 9.4 — Recepción duplicada

Si el receptor recibe un mensaje cuyo **Message ID** ya ha sido procesado y almacenado:

1. **DEBE** ignorar el `payload`.
2. **DEBE** reenviar el **ACK** correspondiente.

Este comportamiento hace que las retransmisiones sean idempotentes: repetir un mensaje no produce efectos secundarios.

---

## 10. Message Identifier

### 10.1 — Formato

El **Message ID** es un identificador único e inmutable. El formato concreto se define en §18 (Wire Protocol). El protocolo trata el **Message ID** como opaco: la única operación definida es la comparación lexicográfica para determinar el orden de presentación.

### 10.2 — Quién lo genera

El **Message ID** es generado exclusivamente por el **Peer** emisor en el momento de crear el mensaje.

### 10.3 — Inmutabilidad

El **Message ID** **NUNCA** cambia. Cada mensaje tiene exactamente un **Message ID** durante toda su vida. No se reasigna, no se reutiliza, no se modifica.

### 10.4 — Unicidad

El emisor **DEBE** garantizar que cada **Message ID** que genera es único en el espacio y en el tiempo. Dado que UUIDv7 incluye un componente temporal y aleatorio, una implementación conforme **PUEDE** confiar en la probabilidad de unicidad del estándar UUIDv7 sin verificación adicional contra mensajes históricos.

### 10.5 — Propósito

El **Message ID** sirve para:

- Identificar unívocamente un mensaje en la **Conversation**.
- Detectar duplicados en el receptor (ver §13).
- Asociar **ACKs** al mensaje original.
- Ordenar mensajes dentro de una **Conversation** (ver §12).

---

## 11. ACK Protocol

### 11.1 — Flujo básico

```
Emisor                             Receptor

  |                                   |
  |  MESSAGE (messageId, payload)     |
  |──────────────────────────────────>|
  |                                   |
  |  <procesa y almacena>             |
  |                                   |
  |  ACK (messageId, originalMessageId)|
  |<──────────────────────────────────|
  |                                   |
```

### 11.2 — Timeout de ACK

El emisor **DEBE** establecer un **ACK Timeout** tras enviar un mensaje. Si no recibe un **ACK** dentro de este período, **DEBE** iniciar una retransmisión.

| Parámetro | Valor recomendado |
|-----------|-------------------|
| **ACK Timeout inicial** | 5 s |
| **Multiplicador de backoff** | 2× por reintento |
| **ACK Timeout máximo** | 60 s |
| **Número máximo de reintentos** | 5 |

Los valores indicados son **RECOMENDADOS**. Una implementación **PUEDE** ajustarlos según las condiciones de red, pero **DEBE** implementar **exponential backoff** para evitar tormentas de retransmisión.

### 11.3 — Retransmisión

Al expirar el **ACK Timeout**, el emisor **DEBE** retransmitir el mensaje completo (mismo `messageId`, mismo `payload`). El receptor tratará la retransmisión como un duplicado (ver §9.4).

### 11.4 — ACK duplicado

El **ACK** es idempotente: puede recibirse cualquier número de veces.

- Si el emisor recibe un **ACK** para un mensaje en estado **SENT**, **DEBE** marcar el mensaje como **DELIVERED**.
- Si el emisor recibe un **ACK** para un mensaje ya en estado **DELIVERED**, **DEBE** ignorarlo silenciosamente.

### 11.5 — Origen del ACK

El receptor **DEBE** enviar exactamente un **ACK** por cada mensaje recibido y almacenado. Si recibe una retransmisión, **DEBE** reenviar el **ACK** (ver §9.4).

El mensaje **ACK** **DEBE** incluir el campo `signature` (ver §18.4) **siempre**, independientemente del **Transport** utilizado. La firma **DEBE** cubrir `from || to || originalMessageId || status || timestamp` usando la clave privada Ed25519 del receptor. Esto vincula la firma a la identidad del emisor del **ACK** y hace que el **ACK** sea intrínsecamente verificable sin depender de TLS, WSS ni de ningún otro mecanismo de seguridad del **Transport**. TLS sigue siendo necesario para la confidencialidad del canal, pero la validez del **ACK** no depende de él.

### 11.6 — ACK perdido

Si el emisor no recibe nunca el **ACK** (por pérdida total de conectividad o porque el mensaje expiró), el mensaje pasa al estado **EXPIRED** tras agotar los reintentos. El emisor **PUEDE** notificar a la aplicación que el mensaje no pudo ser entregado.

---

## 12. Ordering

### 12.1 — Orden por conversación

La capa de presentación **DEBERÍA** ordenar los mensajes por **Message ID**. Las políticas de ordenación alternativas son específicas de la implementación y están fuera del alcance de esta especificación.

El protocolo **NO** garantiza un orden causal entre mensajes: si el mensaje `M1` causa la creación de `M2`, el protocolo no asegura que `M1` llegue antes que `M2`. La implementación **DEBE** estar preparada para recibir y presentar mensajes fuera del orden causal.

Cuando se utilice UUIDv7 como **Message ID** (ver §18), el orden lexicográfico proporciona un orden temporal **aproximado** de creación en el emisor. El **Message ID** es opaco para el protocolo de ordenación: la única operación definida sobre el **Message ID** a nivel de protocolo es la comparación.

### 12.2 — Sin orden global

No existe orden global entre **Conversations** distintas. Los mensajes pertenecientes a diferentes pares de **Peers** no tienen relación de orden definida por el protocolo.

### 12.3 — Entregado vs procesado

El receptor **DEBE** almacenar todos los mensajes entrantes de forma persistente y enviar **ACKs** inmediatamente, independientemente del orden de llegada. La ordenación **NO DEBE** bloquear el pipeline de entrega.

Si el mensaje `M2` llega antes que `M1`:

1. El receptor **DEBE** almacenar `M2` de forma persistente.
2. El receptor **DEBE** enviar **ACK** por `M2` inmediatamente.
3. El receptor **DEBE** entregar `M2` a la capa de presentación sin esperar a `M1`.
4. Cuando `M1` llegue (o se declare perdido), la capa de presentación **DEBE** reordenar los mensajes según su **Message ID**.

La capa de presentación **DEBE** marcar visualmente los huecos (mensajes no recibidos) para que el usuario sepa que faltan mensajes. Una implementación **PUEDE** solicitar retransmisión de mensajes perdidos, pero el protocolo base **NO** exige un mecanismo de retransmisión selectiva.

Este enfoque evita el **Head-of-Line Blocking**: la pérdida de un mensaje no congela la conversación. Los mensajes se almacenan, confirman y entregan inmediatamente; el reordenamiento es responsabilidad exclusiva de la capa de presentación.

---

## 13. Duplicate Detection

### 13.1 — Regla fundamental

Si el receptor recibe un mensaje cuyo **Message ID** ya existe en su almacén de mensajes procesados:

1. **DEBE** ignorar el `payload` del mensaje duplicado.
2. **DEBE** reenviar el **ACK** correspondiente al emisor.
3. **NO DEBE** modificar el mensaje ya almacenado.

### 13.2 — Almacén de IDs procesados

El receptor **DEBE** mantener un registro de los **Message IDs** ya procesados. Este registro **PUEDE** ser el propio almacén de mensajes (si el mensaje está, ya fue procesado). Una implementación **MAY** mantener un índice separado de **Message IDs** para consulta eficiente.

### 13.3 — Retención del registro

El registro de **Message IDs** procesados **DEBE** conservarse al menos durante el tiempo máximo de retransmisión del emisor. Una vez superado ese tiempo, los **Message IDs** antiguos **PUEDEN** descartarse.

---

## 14. Offline Queue

### 14.1 — Propósito

La **Offline Queue** permite al emisor retener mensajes cuyo destinatario no está disponible en el momento del envío.

### 14.2 — Cuándo entra un mensaje

Un mensaje entra en la **Offline Queue** cuando:

- El emisor intenta enviar y el receptor no está **ONLINE** (según KM-0003).
- El emisor envía el mensaje, pero el **ACK Timeout** expira sin respuesta.

### 14.3 — Cuándo sale un mensaje

Un mensaje sale de la **Offline Queue** cuando:

- El emisor recibe un **ACK** del receptor (→ **DELIVERED**).
- El mensaje supera el tiempo máximo de vida en cola (→ **EXPIRED**).

### 14.4 — Tiempo de vida

La política de expiración de la cola es definida por la implementación. El protocolo únicamente requiere que el emisor comunique el estado **EXPIRED** a la aplicación cuando decida abandonar la entrega. Una implementación **PUEDE** utilizar cualquier criterio (tiempo fijo, número de reintentos, política del usuario) para determinar el momento de expiración.

### 14.5 — Reintento al reconectar

Cuando el **Relay** notifica que el receptor está **ONLINE** (PEER_ONLINE, KM-0003), el emisor **DEBE** intentar entregar todos los mensajes pendientes en la **Offline Queue** para ese **Peer**, en orden de **Message ID**.

### 14.6 — Sin almacenamiento en el Relay

La **Offline Queue** es exclusivamente del lado del emisor. El **Relay** **NO DEBE** almacenar mensajes en tránsito (KM-0003, RI2).

---

## 15. Attachments

### 15.1 — Modelo

Un **Attachment** es un dato suplementario (imagen, archivo, audio, video) referenciado por un **User Message**. El mensaje contiene una lista de **Attachment Descriptors** que describen cada adjunto mediante un **Content ID (CID)** y metadatos (tipo MIME, tamaño, nombre, cifrado). La especificación completa del **Attachment Descriptor** se define en [KM-0005].

### 15.2 — Content ID (CID)

El **CID** **DEBE** ser el hash SHA-256 del contenido del adjunto, codificado en Base64URL. El **CID** permite:

- Verificación de integridad: el receptor **DEBE** comprobar que `SHA-256(contenido) == cid`.
- Desduplicación: si dos mensajes referencian el mismo contenido, el receptor **PUEDE** almacenarlo una sola vez.

### 15.3 — Transporte de adjuntos

El mecanismo de transferencia del contenido del adjunto está fuera del alcance de este RFC. **PUEDE** ser inline (dentro del `payload`), P2P, o mediante un servidor externo. La negociación del método de transferencia ocurre durante la **Feature Negotiation** (KM-0002).

### 15.4 — Entrega de adjuntos

El mensaje se considera **DELIVERED** independientemente de si los adjuntos asociados han sido transferidos. La entrega de adjuntos es asíncrona y se definirá en un RFC futuro.

---

## 16. Failure Modes

| Código | Descripción | Causa probable |
|--------|-------------|----------------|
| Código | Descripción | Causa probable | Estado resultante |
|--------|-------------|----------------|-------------------|
| `ACK_TIMEOUT` | El emisor no recibió **ACK** dentro del tiempo de espera. | Receptor desconectado, ACK perdido, red lenta. | Retry → EXPIRED |
| `MESSAGE_EXPIRED` | El mensaje superó el tiempo máximo de reintento. | Receptor permanentemente offline. | EXPIRED |
| `INVALID_MESSAGE` | El mensaje entrante no cumple el formato requerido. | Campos faltantes, tipos incorrectos, firma inválida. | FAILED |
| `MESSAGE_TOO_LARGE` | El mensaje excede el tamaño máximo permitido (64 KiB para MESSAGE, 4 KiB para ACK). | Payload excesivo. | FAILED |
| `DUPLICATE_MESSAGE` | Mensaje recibido con **Message ID** ya procesado. | Retransmisión, error de red. (No es un error; el ACK se reenvía.) | — |
| `UNSUPPORTED_ATTACHMENT` | El adjunto referenciado usa un mecanismo no soportado. | El receptor no implementa el método de transferencia. | FAILED |

---

## 17. Security Considerations

### 17.1 — Replay de mensajes

El **Message ID** (UUIDv7, único por mensaje) sirve como protección natural contra replay. El receptor **DEBE** rechazar cualquier mensaje cuyo **Message ID** ya haya sido procesado.

### 17.2 — Duplicados maliciosos

Un atacante que intercepte un mensaje no puede inyectar duplicados porque el canal está cifrado extremo a extremo. Si pudiera retransmitir un mensaje cifrado, el receptor lo detectaría por **Message ID** duplicado y lo ignoraría.

### 17.3 — Flooding

El receptor **DEBE** implementar límites de tasa para mensajes entrantes:

| Límite | Valor recomendado | Ámbito |
|--------|-------------------|--------|
| Mensajes entrantes | 60 / min | Por **Peer** remitente |
| Tamaño máximo de payload | 64 KiB | Por mensaje |

### 17.4 — Tamaño máximo

El tamaño máximo de un mensaje **MESSAGE** serializado completo (incluyendo cabeceras y payload, medido como el número de bytes del JSON resultante codificado en UTF-8 sin espacios redundantes) **NO DEBE** exceder los 64 KiB (65 536 bytes). El tamaño del mensaje **ACK** **NO DEBE** exceder los 4 KiB (4096 bytes).

### 17.5 — Padding

Para prevenir ataques de análisis de tamaño, el emisor **MAY** añadir padding al payload antes de cifrar. El padding **DEBE** ser bytes aleatorios añadidos al final del contenido y **DEBEN** ser ignorados por el receptor tras el descifrado.

### 17.6 — Metadata leakage

El **Relay** puede observar que dos **Peers** intercambian mensajes (a través de los mensajes de señalización), pero **NO** puede leer el contenido ni los **ACKs** si estos viajan cifrados. Se recomienda que los **ACKs** también viajen cifrados o al menos firmados para evitar que un **Relay** comprometido pueda forjar confirmaciones de entrega.

---

## 18. Wire Protocol

### 18.1 — Serialization requirements

Los mensajes del protocolo **Message** utilizan **JSON** como formato de serialización. Se aplican las mismas reglas definidas en KM-0003 §17.1:

| Regla | Especificación |
|-------|----------------|
| **Codificación Base64URL** | Según [RFC 4648 §5](https://datatracker.ietf.org/doc/html/rfc4648#section-5), **SIN** padding (`=`). |
| **Campos duplicados** | Una implementación **DEBE** rechazar cualquier mensaje con campos duplicados en JSON. |
| **Tamaño máximo** | **MESSAGE**: 64 KiB (65 536 bytes) del JSON serializado en UTF-8. **ACK**: 4 KiB (4096 bytes). |
| **Payload** | El campo `payload` se codifica como Base64URL. |

### 18.2 — Cabecera común

Todos los mensajes **DEBEN** incluir los campos de cabecera común. Los mensajes enrutados a través de un **Relay** (MESSAGE, ACK) **DEBEN** incluir además `from` y `to`.

| Campo | Requisito | Descripción |
|-------|-----------|-------------|
| type | MUST | Tipo del mensaje. |
| messageId | MUST | Identificador único (UUIDv7 según [RFC 9562](https://datatracker.ietf.org/doc/html/rfc9562)). |
| timestamp | MUST | Tiempo Unix en milisegundos del momento de envío. |
| from | MUST (enrutado) | **Identity ID** del emisor. |
| to | MUST (enrutado) | **Identity ID** del receptor. |

### 18.3 — MESSAGE

```
{
  "type": "MESSAGE",
  "messageId": "018f3a5b-7c8d-4e9f-8a0b-1c2d3e4f5a6b",
  "timestamp": 1721827200000,
  "from": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0",
  "to": "f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0",
  "payload": "5PH7p8m... (cifrado, Base64URL)",
  "attachments": ["sha256-AAAA...", "sha256-BBBB..."],
  "expiresAt": 1722432000000
}
```

| Campo | Requisito | Descripción |
|-------|-----------|-------------|
| payload | MUST | Contenido del mensaje cifrado, codificado en Base64URL. |
| attachments | MAY | Lista de **Content IDs** (hashes SHA-256 en Base64URL). |
| expiresAt | MAY | Timestamp Unix en ms tras el cual el mensaje expira en la cola offline del emisor. |

### 18.4 — ACK

```
{
  "type": "ACK",
  "messageId": "018f3a5b-9d0e-1f2a-3b4c-5d6e7f8a9b0c",
  "timestamp": 1721827200500,
  "from": "f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0",
  "to": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0",
  "originalMessageId": "018f3a5b-7c8d-4e9f-8a0b-1c2d3e4f5a6b",
  "status": "DELIVERED",
  "signature": "MEUCIQD..."
}
```

| Campo | Requisito | Descripción |
|-------|-----------|-------------|
| originalMessageId | MUST | **Message ID** del mensaje being acknowledged. |
| status | MUST | Estado confirmado: `DELIVERED`. |
| signature | MUST | Firma Ed25519 sobre `from \|\| to \|\| originalMessageId \|\| status \|\| timestamp`, usando la clave privada del receptor. Previene la falsificación de **ACKs** por parte de intermediarios. |

---

## 19. Examples

### 19.1 — Flujo completo (entrega exitosa)

```
Peer A                              Peer B

  |                                   |
  |  MESSAGE                          |
  |  (id: M1, payload cifrado)        |
  |  ────────────────────────────────>|
  |                                   |
  |                   [descifra,      |
  |                    almacena,      |
  |                    genera ACK]     |
  |                                   |
  |  ACK                              |
  |  (originalMessageId: M1,          |
  |   status: DELIVERED)              |
  |  <────────────────────────────────|
  |                                   |
  |  [A marca M1 como DELIVERED]      |
  |                                   |
```

### 19.2 — Retransmisión por ACK perdido

```
Peer A                              Peer B

  |                                   |
  |  MESSAGE (M1)                     |
  |  ────────────────────────────────>|
  |                                   |
  |          [ACK perdido en la red]  |
  |                                   |
  |  [ACK Timeout expira]             |
  |                                   |
  |  MESSAGE (M1) [retransmisión]     |
  |  ────────────────────────────────>|
  |                                   |
  |          [detecta duplicado M1,   |
  |           reenvía ACK]            |
  |                                   |
  |  ACK (M1)                         |
  |  <────────────────────────────────|
  |                                   |
```

### 19.3 — Offline queue (receptor desconectado)

```
Peer A                           Relay                           Peer B

  |                                |                                |
  |  MESSAGE (M1 → B)              |                                |
  |  ─────────────────────────────>|                                |
  |                                |  [B no está ONLINE]           |
  |  RELAY_ERROR                   |                                |
  |  (PEER_NOT_FOUND)              |                                |
  |  <─────────────────────────────|                                |
  |                                |                                |
  |  [A guarda M1 en               |                                |
  |   Offline Queue]               |                                |
  |                                |                                |
  |                ... más tarde, B se conecta ...                  |
  |                                |                                |
  |                                |  PEER_ONLINE (B)               |
  |  <─────────────────────────────|                                |
  |                                |                                |
  |  [A recupera M1 de cola]       |                                |
  |                                |                                |
  |  MESSAGE (M1 → B)              |                                |
  |  ─────────────────────────────>|  ────────────────────────────>|
  |                                |                                |
  |                                |  ACK (M1)                      |
  |  <─────────────────────────────|  <────────────────────────────|
  |                                |                                |
```

---

## 20. Security Considerations (Additional)

Además de lo indicado en §17:

- **Integridad de ACKs**: Ver §11.5. Los mensajes **ACK** **DEBEN** viajar protegidos por el cifrado del **Transport** o firmados por el receptor.
- **Expiración**: Los mensajes con `expiresAt` en el pasado **DEBEN** ser descartados por el receptor sin procesar.

---

## 21. References

| Ref | Documento |
|-----|-----------|
| [KM-0000] | KM-0000 — Terminology |
| [KM-0001] | KM-0001 — Protocol Architecture Overview |
| [KM-0002] | KM-0002 — Authentication |
| [KM-0003] | KM-0003 — Relay Protocol |
| [KM-0005] | KM-0005 — Attachment Protocol |
| [RFC 2119] | [Key words for use in RFCs](https://datatracker.ietf.org/doc/html/rfc2119) |
| [RFC 9562] | [Universally Unique IDentifiers (UUID)](https://datatracker.ietf.org/doc/html/rfc9562) |

---

## 22. History

| Versión | Fecha | Cambios |
|---------|-------|---------|
| 0.1 | 2026-07-25 | Documento inicial — Message Model, Delivery Model, Message Lifecycle, ACK Protocol, Ordering, Duplicate Detection, Offline Queue, Attachments, Wire Protocol |
