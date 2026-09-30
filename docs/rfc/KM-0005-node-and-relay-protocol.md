# KM-0005 — Node & Relay Protocol

| Campo | Valor |
|-------|-------|
| **RFC** | KM-0005 |
| **Título** | Node & Relay Protocol |
| **Estado** | Draft — Identidad actualizada |
| **Versión** | 0.3 |
| **Autor** | KeyMessage Project |
| **Última actualización** | 2026-09-27 |
| **Reemplaza** | Las referencias previas a "KM-0005 — Groups" (KM-0000, KM-0001, KM-0003) y "KM-0005 — Attachment Protocol" (KM-0004). Este número queda reasignado definitivamente a **Node & Relay Protocol**. |
| **Enmienda** | Relaja KM-0003 RI2 y KM-0004 §14.6 / §5 N1 para nodos con la capability `STORE_AND_FORWARD`. |

---

## 1. Purpose

Este documento define el modelo de **Nodes** de KeyMessage: la idea de que toda instancia del protocolo es un peer con una **Identity** propia, y que algunas de ellas pueden anunciar y ejercer **capabilities de infraestructura** (`RELAY`, `SIGNALING`, `STORE_AND_FORWARD`) además de las de **CLIENT**.

Introduce el **relay como rol**, no como componente separado: un PC de sobremesa, una VPS, una Raspberry Pi o un teléfono ejecutan **el mismo software base** y exponen roles distintos según sus capacidades de red.

Este RFC define:

- El concepto de **Node** y sus **NodeCapabilities**.
- El descubrimiento de nodos (**Node Discovery**).
- El anuncio y selección de relays (**Relay Advertisement / Selection**).
- La **autenticación mutua** entre **Client** y **Relay Node**.
- La conexión y el estado de la conexión a un **Relay Node**.
- El **forwarding** de mensajes y **ACKs** entre peers a través de uno o más **Relay Nodes**.
- El **Store-and-Forward**: almacenamiento temporal cifrado en el relay cuando el destinatario no está disponible.
- La **expiración**, límites, duplicados, y el modelo de confianza.

**(NOTA DE NUMERACIÓN):** KM-0005 fue mencionado en documentos previos como "Groups" (KM-0000, KM-0001, KM-0003) y como "Attachment Protocol" (KM-0004). Todos esos RFC **no están implementados para esos temas** y quedan reasignados a números futuros (véase §23). Este RFC tome el número definitivamente.

---

## 2. Scope

Este documento cubre:

- El modelo de **Node** y **NodeCapabilities**.
- El **Node Identity Model**.
- El descubrimiento de nodos y los **Node Announcements**.
- La **selección dinámica** de relays y la tolerancia a fallos.
- La autenticación de un **Client** contra un **Relay Node**.
- La conexión `Client ↔ Relay Node` y su ciclo de vida.
- El **Store-and-Forward** de mensajes cifrados.
- El reenvío de **MESSAGE** y **ACK** a través de relays.
- La expiración y los límites de almacenamiento.
- El manejo de duplicados y la semántica de entrega.
- El modelo de seguridad y de confianza.
- El formato wire de los nuevos mensajes.
- La evolución hacia **multi-hop** (como fase futura).

Este documento **NO** cubre:

- La autenticación entre **Peers** finales — definida en KM-0002.
- La capa de **User Messages** y ACK — definida en KM-0004.
- El cifrado extremo a extremo — definido en KM-0004/KM-IDENTITY-0001.
- El protocolo de transporte concreto (WebSocket, TCP, etc.).
- El **onion routing** / anonimato estilo Tor — fuera del alcance de esta versión (ver §22).
- Mecanismos de **reputación** global de relays — fase futura (ver §21.5).
- El descubrimiento descentralizado mediante DHT — definido en KM-DHT-0001.
- La mensajería grupal — reasignada a un RFC futuro.
- El protocolo de **adjuntos** — reasignado a un RFC futuro.

---

## 3. Definitions

Este documento utiliza los términos definidos en **KM-0000** con el significado allí especificado.

| Término | Definición |
|---------|------------|
| **Node** | Instancia del software KeyMessage con una **Identity** propia. Un **Node** **PUEDE** ejercer uno o más **NodeCapabilities**. Todo **Node** es, por definición, un **Peer** del protocolo. |
| **NodeCapability** | Rol funcional que un **Node** declara y ejecuta. Las capabilities definidas son `CLIENT`, `RELAY`, `SIGNALING`, `STORE_AND_FORWARD`. |
| **Client Node** | **Node** cuya capability principal es `CLIENT`: envía y recibe **User Messages** con otras identidades. |
| **Relay Node** | **Node** con capability `RELAY`: reenvía **MESSAGE** y **ACK** entre **Clients**. |
| **Store-and-Forward Node** | **Node** con capability `STORE_AND_FORWARD`: puede conservar temporalmente mensajes cifrados cuyo destinatario está **OFFLINE** y entregarlos cuando este reaparezca. |
| **Node Announcement** | Mensaje firmado con el que un **Node** declara su identidad, endpoint, capabilities y límites. |
| **Relay Connection** | Conexión de **Transport** entre un **Client Node** y un **Relay Node**. |
| **Relay Advertisement** | Subproducto del **Node Announcement**: la declaración pública de que un nodo está dispuesto a actuar como relay y bajo qué límites. |
| **Stored Message** | **User Message** cifrado retenido temporalmente por un **Relay Node** con `STORE_AND_FORWARD`. |
| **Storage Receipt (STORED)** | Confimación que envía un **Relay Node** al emisor de que un mensaje ha sido almacenado de forma durable. |
| **Relay Cache** | El conjunto de **Stored Messages** de un **Relay Node**. |
| **Relay Selection** | Proceso mediante el cual un **Client** escoge, entre los **Relay Nodes** conocidos, cuál utilizar de forma primaria y cuál como fallback. |

Los términos normativos **MUST**, **MUST NOT**, **SHOULD**, **SHOULD NOT** y **MAY** se interpretan según **BCP 14** ([RFC 2119](https://datatracker.ietf.org/doc/html/rfc2119) + [RFC 8174](https://datatracker.ietf.org/doc/html/rfc8174)): solo tienen fuerza normativa cuando aparecen en mayúsculas.

---

## 4. Goals

| # | Objetivo | Descripción |
|---|----------|-------------|
| G1 | **Un solo software, múltiples roles** | El mismo binario debe poder ejecutarse como **CLIENT**, **RELAY**, **SIGNALING** y/o **STORE_AND_FORWARD** en función de su configuración y capacidades de red. |
| G2 | **Relay como peer** | Un **Relay Node** **DEBE** ser un nodo del protocolo con **Identity** propia, no un componente externo con un protocolo distinto. |
| G3 | **Store-and-Forward cifrado** | La **Offline Queue** de un mensaje **PUEDE** vivir en uno o más **Relay Nodes** como **ciphertext** sin que el relay pueda leer el contenido. |
| G4 | **Disponibilidad mejorada** | El emisor **PODRÁ** apagarse después de entregar su mensaje a un relay estable; el mensaje no se pierde si el destinatario aparece más tarde. La durabilidad se garantiza por la **copia local persistente** (NI6) más la custodia del relay. |
| G5 | **Tolerancia a fallos de relay** | Si el relay primario desaparece, el **Client** **DEBE** poder conmutar a otro relay conocido sin perder los mensajes pendientes. |
| G6 | **No persistencia permanente** | El relay **NO** se convierte en una base de datos permanente: todos los mensajes se eliminan tras la entrega confirmada o expiran. |
| G7 | **Cero confianza necesaria en el relay** | El relay **NUNCA** debe tener acceso al plaintext; la entrega confirmada se prueba mediante la **ACK signature** de KM-0004. |

---

## 5. Non-goals

| # | Excluye | Motivo |
|---|---------|--------|
| N1 | **Almacenamiento permanente** | El relay no guarda historial indefinidamente. Solo **Stored Messages** con TTL y borrado tras entrega. |
| N2 | **Onion routing / anonimato Tor** | No se mezcla con el objetivo de este RFC. Se trata como una capa de privacidad futura e independiente (§22). |
| N3 | **Sistema de reputación global** | La selección de relays bajo confianza se basa en criterios locales (configuración, announcents firmados). La reputación es fase futura (§21.5). |
| N4 | **Blockchain** | Cualquier token, consenso o blockchain está explícitamente fuera del alcance. |
| N5 | **DHT descentralizada** | El anuncio de relays mediante DHT se define en KM-DHT-0001. Este RFC define un mecanismo por configuración y propagación directa. |
| N6 | **Multi-hop obligatorio** | El formato wire lo permite, pero la entrega de referencia es `Client → Relay → Client`. **Multi-hop** es una fase posterior (§19). |
| N7 | **Autenticación entre peers**. | Sigue perteneciendo a KM-0002. |
| N8 | **Enrutamiento de adjuntos** | Los adjuntos se transportan por un mecanismo separado (RFC futuro). |
| N9 | **Almacenamiento de mensajes no cifrados** | Un relay **NO DEBE** almacenar jamás un `payload` que pueda descifrar. |

---

## 6. Node Model

### 6.1 — Qué es un Node

Un **Node** es cualquier instancia del software KeyMessage con una **Identity** propia (par de claves Ed25519, `identityId` derivado según **KM-ID-0001 §6**: `SHA-256("KM-ID-IDENTITY" || publicKey)`). Todo **Node** es susceptible de:

- Mantener **conversaciones** como **CLIENT**.
- Conectarse a otros **Relay Nodes** y a otros **Clients**.
- **Anunciar** su presencia y capabilities.
- Servir **Relay Connection** a otros **Clients** si lo configura su operador.

No hay un concepto de "server" separado del protocolo: **todo servidor es un Node**.

### 6.2 — NodeCapabilities

Cada **Node** declara un subconjunto (posiblemente vacío) de las siguientes **NodeCapabilities**:

| Capability | Descripción | Ejemplo de uso |
|------------|-------------|----------------|
| `CLIENT` | El nodo participa en conversaciones: envía y recibe **User Messages**. | Teléfono, app de sobremesa, CLI interactivo. |
| `RELAY` | El nodo reenvía **MESSAGE** y **ACK** entre otros **Clients**. No los almacena. | PC de sobremesa, VPS. |
| `SIGNALING` | El nodo mediuma **señalización** (SDP_OFFER, SDP_ANSWER, ICE_CANDIDATE) para que dos peers establezcan un **DataChannel** directo. | Cualquier nodo con conexión estable. |
| `STORE_AND_FORWARD` | El nodo retiene temporalmente **Stored Messages** cifrados y los entrega cuando el destinatario reaparece. Implica `RELAY`. | VPS, PC de sobremesa de alta disponibilidad. |

Reglas:

- Un **Node** **DEBE** declarar `CLIENT` si ofrece conversaciones a su operador; un **Node** que solo sirve infraestructura (`RELAY`/`SIGNALING`/`STORE_AND_FORWARD`) **NO** **DEBE** declarar `CLIENT`.
- `STORE_AND_FORWARD` **REQUIERE** declarar `RELAY`.
- `SIGNALING` es independiente de `RELAY`.
- Las **NodeCapabilities** **NO** son **Capabilities** de sesión (KM-0002). Las primeras describen el rol de un nodo en la red; las segundas se negocian por par durante la autenticación. Un nodo **MAY** exponer ambas pero son conceptos distinguidos.

### 6.3 — Identidad del Node

Todo **Node** tiene:

- un `identityId` (64 chars hex, 32 bytes) derivado de su clave pública según **KM-ID-0001 §6**;
- un par de claves Ed25519;
- opcionalmente, un nombre legible publicado en el **Node Announcement** (p. ej. `freddy-pc`).

El `nodeId` de un Node **ES** su `identityId`. No existe una derivación separada para `nodeId`: el Node se identifica mediante la identidad que posee.

El `identityId` del relay es exactamente el campo `responderIdentityId` que KM-0002 y KM-0003 esperan para autenticación mutua. **Este RFC reafirma** que un relay es un **Identity** más: el mismo flujo `AUTH_REQUEST / AUTH_CHALLENGE / AUTH_RESPONSE / AUTH_OK` de KM-0002 aplica tal cual.

### 6.4 — Decisión arquitectónica pendiente: `CLIENT` como rol, no como capability

Este RFC modela `CLIENT` como una **NodeCapability** más, igual que los demás. Esto es implementable y es el modelo de esta versión. Sin embargo, existe una alternativa conceptualmente más limpia para la arquitectura a largo plazo:

```
NodeRole:                         NodeCapabilities (infraestructura):
    CLIENT                             RELAY
    INFRASTRUCTURE (p. ej. VPS)        SIGNALING
                                       STORE_AND_FORWARD

Android:       Role = CLIENT, Capabilities = {}
Desktop:       Role = CLIENT, Capabilities = { RELAY, SIGNALING, STORE_AND_FORWARD }
Headless VPS:  Role = INFRASTRUCTURE, Capabilities = { RELAY, SIGNALING, STORE_AND_FORWARD }
```

Razones: `CLIENT` describe el **modo de participación del usuario** en la red, mientras que las otras tres describen **servicios prestados a terceros**. Mezclarlos en un mismo enum obliga a que un `Node` sin rol `CLIENT` (como una VPS) entienda `CLIENT` como algo que es y no ejerce.

**Estado:** pendiente de decisión antes de congelar KM-0005. Esta versión mantiene el modelo plano `NodeCapabilities { CLIENT, RELAY, SIGNALING, STORE_AND_FORWARD }` para minimizar el cambio de la fase 1 de implementación; la bifurcación `NodeRole`/`NodeCapabilities` es compatible hacia atrás vía versionado del `NODE_ANNOUNCEMENT`.

---

## 7. Node Invariants

| # | Invariante | Descripción |
|---|------------|-------------|
| NI1 | **Toda instancia es un Node** | No existe software de servidor que no sea un **Node** del protocolo. |
| NI2 | **Nodo autenticado** | Toda comunicación entre dos **Nodes** **DEBE** autenticarse mediante KM-0002. |
| NI3 | **Relay ciego al contenido** | Ningún relay **DEBE** poder descifrar el `payload` de los mensajes que reenvía o almacena. |
| NI4 | **Borrado tras entrega** | Un **Stored Message** **DEBE** eliminarse cuando su ACK de recepción se reenvía al emisor o cuando expira su TTL. |
| NI5 | **Llaves del emisor, no del relay** | El relay jamás posee las claves de sesión de los **Clients**. |
| NI6 | **Copia local durable del emisor** | El emisor **DEBE** conservar una copia persistente de cada mensaje hasta recibir el **ACK firmado** del destinatario. El `STORED` **NUNCA** autoriza a eliminar la copia local. |
| NI7 | **Identidad derivada de clave** | El `identityId` de un relay **DEBE** derivarse determinísticamente de su clave pública según KM-ID-0001 §6 (`SHA-256("KM-ID-IDENTITY" || publicKey)`). |

---

## 8. Message Flow Overview

### 8.1 — Entrega inmediata (destinatario ONLINE)

```
D1 ──MESSAGE──> R1 ──MESSAGE──> D2
                 │
                 │ ACK
D1 <──ACK────────┘ <─────────── D2
```

### 8.2 — Store-and-Forward (destinatario OFFLINE)

```
D1 ──MESSAGE──> R1
                 │ D2 OFFLINE
                 ▼
              [STORE]  (TTL, límites)
                 │ D2 vuelve
                 ▼
R1 ──MESSAGE──> D2
                 │ ACK
R1 <──ACK────────┘
                 ▼
              [DELETE]
                 │ forward ACK
D1 <──ACK────────┘
```

### 8.3 — Fallo del relay primario

```
D1 ──X── R1 (caído)
D1 ───── R2  (seleccionado por failover)
```

### 8.4 — Multi-hop (fase futura)

```
D1 ──> R1 ──> R2 ──> D2
```

Definido como evolución futura en §19 — no requerido en esta versión.

---

## 9. Node Announcement

### 9.1 — Propósito

Un **Node Announcement** es un mensaje firmado que un nodo publica o envía para:

- dar a conocer su identidad (`nodeId`, `publicKey`);
- declarar su endpoint de **Transport**;
- declarar sus **NodeCapabilities**;
- declarar los **Relay Limits** que el emisor está dispuesto a imponer (si es relay);
- autenticarse criptográficamente.

### 9.2 — Formato (wire)

```
{
  "type": "NODE_ANNOUNCEMENT",
  "messageId": "<UUIDv7>",
  "timestamp": 1721827200000,
  "protocolVersion": "2.0",
  "nodeId": "<identityId del nodo (64 hex, 32 bytes)>",
  "publicKey": "<clave pública Ed25519 (Base64URL)>",
  "nodeName": "<nombre legible optativo>",
  "endpoints": [
    { "transport": "websocket", "address": "wss://203.0.113.10:443/ws" },
    { "transport": "tcp", "address": "203.0.113.10:9090" }
  ],
  "capabilities": ["CLIENT", "RELAY", "SIGNALING", "STORE_AND_FORWARD"],
  "limits": {
    "maxMessageSize": 65536,
    "maxStoredMessages": 10000,
    "maxStorageBytes": 1073741824,
    "maxMessageAgeMs": 604800000,
    "maxConnections": 512
  },
  "signature": "<Ed25519 sobre el cuerpo completo (excluyendo signature)>"
}
```

### 9.3 — Campo por campo

| Campo | Requisito | Descripción |
|-------|-----------|-------------|
| nodeId | MUST | `identityId` del nodo. |
| publicKey | MUST | Clave pública que corresponde a `nodeId`. |
| endpoints | MUST (≥1) | Uno o más endpoints de transporte del nodo. |
| capabilities | MUST | Lista no vacía de **NodeCapabilities**. |
| limits | SHOULD | Solo si el nodo puede actuar como relay (`RELAY`/`STORE_AND_FORWARD`). Límites que impondrá a sus clientes. |
| nodeName | MAY | Nombre legible del nodo (p. ej. `freddy-pc`). |
| signature | MUST | Firma Ed25519 del cuerpo serializado **sin** el campo `signature`. |

### 9.4 — Verificación

Quien recibe un **Node Announcement** **DEBE** verificar:

1. Que `identityId == SHA-256("KM-ID-IDENTITY" || publicKey)`, según KM-ID-0001 §6.
2. Que la firma es válida contra `publicKey`.
3. Que `timestamp` no está en el futuro ni excede una ventana de validez (> 300 s).

Un anuncio no verificado **DEBE** descartarse.

### 9.5 — Propagación

Los **Node Announcements** se distribuyen por uno o más de:

- **Configuración**: endpoints de relays de arranque (bootstrap) incluidos en el cliente.
- **Contact directo**: el anuncio viaja como respuesta a un `NODE_QUERY` (ver §10).
- **Reenvío por relays**: un relay `MAY` reenviar anuncios de otros relays conocidos entre sus clientes, sujeto a límites de tasa.
- (Futuro) **DHT** (KM-DHT-0001).

Un **Client** **DEBE** limitar el número de anuncios que acepta por fuente (defensa contra flooding) y **DEBE** aplicar los límites de tasa definidos en §16.

---

## 10. Node Discovery

### 10.1 — NODE_QUERY

Un **Client** puede consultar a un nodo conocido qué relays conoce.

```
Client → Node: NODE_QUERY
{
  "type": "NODE_QUERY",
  "messageId": "<UUIDv7>",
  "timestamp": 1721827200000,
  "query": "relays"            // fijo en esta versión
}
```

### 10.2 — NODE_QUERY_RESULT

```
Node → Client: NODE_QUERY_RESULT
{
  "type": "NODE_QUERY_RESULT",
  "messageId": "<UUIDv7>",
  "timestamp": 1721827200000,
  "nodes": [ <Node Announcement>... ]
}
```

El nodo **DEBE** devolver únicamente anuncios firmados (`Node Announcement`) que él haya verificado, y **DEBE** aplicar límite de tasa (ver §16).

### 10.3 — Bootstrap

Un **Client** **DEBE** iniciar con al menos un endpoint de bootstrap configurado (su relay primario o la VPS del operador). A partir de ahí descubre el resto mediante §9.5 y §10.

---

## 11. Relay Advertisement

### 11.1 — Qué es

El **Relay Advertisement** es el **Node Announcement** de un nodo que declara `RELAY` y/o `STORE_AND_FORWARD`, incluidos sus `limits`. No es un tipo de mensaje distinto: es el mismo `NODE_ANNOUNCEMENT` interpretado para este propósito.

### 11.2 — Capacidad efectiva

Un nodo que anuncia `STORE_AND_FORWARD` **DEBE** cumplir los `limits` que publica cuando actúa como relay. Un emisor puede **elegir destino** de sus mensajes según estos límites (por ejemplo, rehusar almacenamiento en un relay con `maxMessageAgeMs` demasiado corto para su mensaje con `expiresAt`).

---

## 12. Relay Connection

### 12.1 — Estados

La conexión `Client ↔ Relay Node` sigue el mismo ciclle de vida de KM-0003 §8.1:

```
DISCONNECTED → CONNECTING → AUTHENTICATING → REGISTERED → ONLINE → DISCONNECTED
```

Esta versión **MAY** usar los estados `CONNECTING` y `AUTHENTICATING` (misma nota de granularidad de KM-0003).

### 12.2 — Autenticación

La autenticación entre **Client** y **Relay Node** **DEBE** ejecutar el flujo de KM-0002 tal cual, con:

| KM-0002 | Valor |
|---------|-------|
| Initiator | **Client** |
| Responder | **Relay Node** |
| `responderIdentityId` | `identityId` del relay |
| Capabilities negociadas | Las de sesión (KM-0002), distintas de las NodeCapabilities. |

Además, el **Client** **DEBE** verificar la identidad del relay (autenticación mutua) antes de enviarle cualquier mensaje de aplicación.

### 12.3 — Capacidades de sesión vs NodeCapabilities

Durante la autenticación KM-0002 el relay puede anunciar `capabilities` de sesión (p. ej. `km.relay.v1`, `km.signal.v1`, `km.store.v1`). Son ortogonales a las `NodeCapabilities` del **Node Announcement**: las primeras se negocian por par; las segundas describen el rol global del nodo.

Un **Client** solo **DEBE** usar `STORE_AND_FORWARD` de un relay si tanto la **NodeCapability** (`NODE_ANNOUNCEMENT`) como la **Capability de sesión** (`AUTH_OK`) lo declararon.

### 12.4 — Múltiples conexiones simultáneas

Un **Client** **MAY** mantener simultáneamente:

- una conexión a su **Relay primario**;
- una o más conexiones secundarias a otros relays (failover, o redundancia de almacenamiento);
- conexiones **DataChannel** directas a otros **Clients** (KM-0003).

Protocolo de transporte objetivo:

```
D1
 ├─ PeerConnection → D2        (WebRTC DataChannel, directo)
 ├─ RelayConnection → R1       (primario)
 └─ RelayConnection → R2       (secundario, failover)
```

---

## 13. Relay Selection & Failover

### 13.1 — Conjunto de relays conocidos

Un **Client** **DEBE** mantener una lista local de **Relay Nodes** conocidos con:

| Campo | Descripción |
|-------|-------------|
| nodeId | Identidad del relay. |
| endpoints | Endpoints de transporte. |
| capabilities | NodeCapabilities declaradas. |
| limits | Relay Limits publicados. |
| metrics | Latencia, disponibilidad, intentos de conexión (observadas localmente, no publicadas). |
| trustFlags | Marca local (`trusted`, `default`, `fallback`) definida por el operador. |

### 13.2 — Selección primaria

Al conectar, el **Client** **DEBE** escoger un relay primario aplicando, en orden:

1. Relays marcados como `trusted`/`default` por configuración.
2. Entre los equivalentes, el de menor latencia observada.
3. Preferir `STORE_AND_FORWARD` para mensajes que esperan destinatario offline.

### 13.3 — Failover

Si la conexión al relay primario falla (timeout, error de transporte, `RELAY_ERROR`), el **Client** **DEBE**:

1. Reintentar según el backoff de KM-0003 (exponential).
2. Tras un número configurable de intentos fallidos, conmutar a otro relay de la lista.
3. Marcarla localmente con la métrica de fallo para favorecer al resto.

### 13.4 — Mensajes pendientes durante failover

Los mensajes pendientes son **re-enviados** al nuevo relay si:

- el emisor mantiene copia local (NI6 opción A), o
- el nuevo relay los solicitó (ver §14.7 **Relay Handoff**).

**El emisor NO DEBE** dar un mensaje por perdido por el fallo del relay: si conserva la copia local, la reenvía; si no, depende del relay original o del mecanismo de handoff.

---

## 14. Store-and-Forward

### 14.1 — Principio

El **Store-and-Forward** permite que la **Offline Queue** de un mensaje exista **fuera del dispositivo del emisor**: como **ciphertext** en uno o más **Relay Nodes**. El emisor puede apagarse después de que el relay confirme el almacenamiento, sin perder el mensaje si el destinatario vuelve.

### 14.2 — Recepción en el relay

Cuando un **Relay Node** con `STORE_AND_FORWARD` recibe un `MESSAGE`:

1. Verifica autenticación y formato (KM-0004).
2. Comprueba el destinatario:

   - **DESTINATARIO ONLINE**: reenvía según §13 de KM-0003 (entrega inmediata). No almacena.
   - **DESTINATARIO OFFLINE**: pasa al paso 3.

3. Almacena el `MESSAGE` (intacto, cifrado) en su **Relay Cache**, indexado por `messageId`, con `storedAt` y `expiresAt`.
4. Envía `STORED` al emisor (ver §14.4).

### 14.3 — Qué almacena exactamente

El relay almacena **el mensaje wire completo recibido** (`MESSAGE` JSON), **sin** reemplazar el `from` ni el `to` originales. El relay **NO** descifra, **NO** vuelve a cifrar, **NO** verifica el contenido del `payload`.

### 14.4 — Storage Receipt (STORED)

```
Relay → Emisor: STORED
{
  "type": "STORED",
  "messageId": "<UUIDv7>",
  "timestamp": 1721827200500,
  "originalMessageId": "<messageId del MESSAGE almacenado>",
  "to": "<identityId del destinatario>",
  "expiresAt": 1722432000000,
  "relayNodeId": "<identityId del relay>"
}
```

Significado para el emisor: *el relay ha persistido el mensaje y asume el rol de retransmisor / custodio cuando el destinatario esté disponible, hasta `expiresAt`.*

**`STORED` NO significa entrega ni autorización para olvidar.** Los niveles de confirmación son dos:

```
SENDING
   │
   │  relay confirma custodia
   ▼
STORED ───────────► (no se elimina copia local)
   │
   │  destinatario confirma con ACK firmado
   ▼
QUEUED / DELIVERED  ►  se eliminan copia local y almacenada
```

- Tras `STORED`, el emisor marca el mensaje como **pendiente con custodia externa** (estado `STORED` de la capa de aplicación, dentro del ciclo de vida de KM-0004 como sub-estado de `QUEUED`/`SENT`).
- **NO** se elimina la copia local del emisor (NI6).
- El mensaje **NO** se marca `DELIVERED`: eso solo lo produce el **ACK firmado** por el destinatario (KM-0004 §11.5).

### 14.4a — Flujo completo con copia local durable

```
D1                  R1                    D2

 │──── MESSAGE ─────>│                    │
 │<──── STORED ──────│                    │
 │ [mantiene copia]  │                    │
 │                   │                    │
 X aplicación        │                    │
   cerrada           │                    │
                     │   D2 se conecta    │
                     │<───────────────────│
                     │──── MESSAGE ──────>│
                     │<──── ACK (firmado)─│
 │<──── ACK ─────────│                    │
 │ [elimina copia]   │ [el relay elimina  │
 │                   │  su copia]         │
```

Escenario de fallo del relay:

```
D1 ─── MESSAGE ───> R1
                      │
                      │ STORED (D1 conserva copia)
                      │
                      X R1 muere / pierde datos
                      
D1 detecta ausencia de entrega (TIME),
 reintenta vía R2 con su copia local ──> R2 ──> D2
```

La copia local persiste en almacenamiento del emisor, por lo que el cierre de la aplicación **no** la destruye; lo único que desaparece al cerrar D1 es la capacidad de reintento activo, no el mensaje.

### 14.5 — Entrega al reaparecer

Cuando el destinatario se conecta al relay:

1. El relay+ **DEBE** consultar su **Relay Cache** por `to == identityId(destinatario)`.
2. Entregar cada **Stored Message** por orden de `messageId` (relacionado con KM-0003 §13.1a).
3. Esperar el ACK del destinatario.

### 14.6 — Borrado

El relay **DEBE** eliminar un **Stored Message** cuando:

- **ACK observado**: el relay ve el `ACK` del destinatario (`originalMessageId` coincidente) y lo reenvía al emisor (§15). Tras reenviarlo, **DEBE** borrar la copia.
- **Dúplicado entregado**: si el destinatario confirma con ACK un mensaje que el relay aún tenía en cache (por ejemplo porque el emisor lo reenvió), el relay **DEBE** eliminar cualquier copia duplicada.
- **TTL caducado**: ver §18.

El relay **NUNCA** borra un mensaje antes de haberlo reenviado al destinatario o recibido su ACK, **salvo por TTL**.

### 14.7 — Relay Handoff (transferencia entre relays)

Si el emisor decide cambiar de relay, o si un relay debe descargar su cache a otro:

- El **Client** **MAY** solicitar explícitamente la transferencia: `RELAY_HANDOFF` con los `messageId` de interés al nuevo relay; el nuevo relay los solicita al antiguo con `RELAY_PULL`; el antiguo los reenvía como `MESSAGE` (sin modificar `from`/`to`) y los borra tras confirmar con el nuevo.
- La seguridad es idéntica: los mensajes viajan como ciphertext y solo el destinatario puede leerlos.

```
D1 ──> R1 (almacena M)
D1 decide mover la custodia:
D1 ──> R2: RELAY_HANDOFF {messageIds:[M]}
R2 ──> R1: RELAY_PULL {messageIds:[M]}
R1 ──> R2: MESSAGE (M, ciphertext)
R1 ──> R1-cache: borra M
```

### 14.8 — Copia local del emisor (requisito de entrega durable)

Un **Client** **DEBE** conservar una copia persistente de cada mensaje hasta recibir el **ACK firmado** del destinatario (NI6). Esta copia:

- proporciona tolerancia a la pérdida del relay (fallo, borrado, `TTL_EXPIRED`);
- permite reintentar la entrega vía otro relay cuando el elegido falla;
- convierte el almacenamiento del relay en un **mecanismo de disponibilidad adicional**, no en el único lugar donde existe el mensaje.

El emisor **DEBE** mantener la correspondencia `messageId ↔ relayNodeId ↔ originalMessageId` para poder recomponer los reintentos entre distintos relays.

La copia local es persistencia de la capa de aplicación; el wire protocol no la modela. La política de expiración de la copia local sigue a KM-0004 (§14.4): si el mensaje supera su tiempo máximo de vida sin ACK, el emisor lo marca `EXPIRED` y **PUEDE** eliminar la copia.

---

## 15. ACK Forwarding

### 15.1 — Flujo

```
D2 ──ACK──> R1 ──ACK──> D1
```

1. El destinatario envía el `ACK` de KM-0004 (firmado con su clave privada).
2. El relay **DEBE** reenviarlo al emisor **verboatim** (sin modificar `from`, `to`, `signature`, ni `originalMessageId`).
3. El relay, si tiene el mensaje en cache, **DEBE** borrarlo tras el reenvío (sección 14.6).

### 15.2 — El relay no genera ACK

El relay **NUNCA** genera un `ACK` en nombre de un destinatario: el `ACK` solo puede producir el peer receptor con su clave privada (KM-0004 §11.5). El `STORED` es un acuse del **relay** sobre **almacenamiento**, no un ACK de **recepción de usuario**.

### 15.3 — ACK en bandeja de mensaje

Como el `ACK` incluye la firma del receptor, un relay **NO PUEDE** falsificar una entrega exitosa. Esto es requisito de seguridad: ver §21.

---

## 16. Relay Limits

Un **Relay Node** **DEBE** imponer los límites que publica en su **Node Announcement**. Valores recomendados:

| Parámetro | Valor recomendado | Obligatorio |
|-----------|-------------------|-------------|
| `maxMessageSize` | 64 KiB (65 536 bytes) | MUST |
| `maxStoredMessages` | 10 000 por relay | SHOULD |
| `maxStorageBytes` | 1 GiB por relay | SHOULD |
| `maxMessageAgeMs` | 7 días (604 800 000 ms) | MUST |
| `maxConnections` | 512 conexiones simultáneas | SHOULD |

Reglas:

- Cuando el relay alcanza `maxStoredMessages` o `maxStorageBytes`, **DEBE** rechazar nuevos almacenamientos con `RELAY_ERROR: STORAGE_FULL` (ver §20). Puede además expulsar mensajes con TTL más corto siempre que respete §14.6.
- `maxMessageSize` **NO** puede superar el límite de KM-0004 (§17.4).

El **Client** puede consultar los límites de un relay antes de decidir usarlo como store (relación con §11.2).

---

## 17. Expiration

### 17.1 — TTL en el relay

Cada **Stored Message** tiene un `expiresAt`. Un relay **DEBE** calcularlo como:

```
expiresAt = min(sender.expiresAt (si existe), now + maxMessageAgeMs)
```

- Si el mensaje incluye `expiresAt` (KM-0004 §18.3) más corto que `maxMessageAgeMs`, el relay **DEBE** respetar el del emisor.

### 17.2 — RELAY_EXPIRED

Cuando un **Stored Message** expira **DEBE**:

- eliminarse de la cache;
- enviarse (best-effort) `RELAY_EXPIRED` al emisor:

```
{
  "type": "RELAY_EXPIRED",
  "messageId": "<UUIDv7>",
  "timestamp": 1722432000000,
  "originalMessageId": "<messageId del mensaje expirado>",
  "to": "<identityId del destinatario>",
  "reason": "TTL_EXPIRED"
}
```

El emisor que mantiene copia local puede entonces reintentar vía otro relay, o marcar el mensaje `EXPIRED` según KM-0004.

### 17.3 — Mensajes entrantes con `expiresAt` pasado

El relay **DEBE** descartar sin almacenar ningún `MESSAGE` cuyo `expiresAt` ya haya pasado (coherente con KM-0004 §20).

---

## 18. Duplicate Handling

### 18.1 — Del lado del relay

El relay **DEBE** deduplicar por `originalMessageId` en su **Relay Cache**:

- Si recibe un `MESSAGE` cuyo `messageId` ya tiene almacenado, **DEBE** ignorar la duplicación y **MAY** reenviar `STORED` (ya que el emisor pudo perder el anterior).
- Si recibe un `MESSAGE` duplicado **tras haberlo borrado** (por ACK), reenvía al destinatario si está ONLINE; si está OFFLINE, puede volver a almacenarlo (el emisor lo reintenta). La deduplicación del lado del **receptor** (KM-0004 §13) garantiza idempotencia.

### 18.2 — Del lado del destinatario

El destinatario aplica KM-0004 §13: detecta duplicado por `messageId`, reenvía el `ACK`, no re-procesa el payload.

### 18.3 — Consecuencia

Multiples copias del mismo mensaje (en el emisor, en el relay, reenviadas) convergen a **exactamente un significado** gracias a la deduplicación por `messageId` en cada capa.

---

## 19. Multi-hop (evolución futura)

Esta versión define la entrega `Client → Relay → Client`. Se contempla explícitamente la evolución:

```
D1 ──> R1 ──> R2 ──> D2
```

Requisitos que deberá cumplir esa fase (no implementados aquí):

- selección de ruta (`address discovery`);
- reenvío sin modificar `from`/`to` (ya soportado por el transporte);
- confiabilidad idéntica a la de un salto (ACK viaja de vuelta por la misma ruta o por la más corta);
- las **NodeCapabilities** no cambian: cada nodo del camino ejecuta el mismo protocolo.

El **onion routing** (privacidad) es una fase posterior **independiente**, sin relación de requisitos con el multi-hop de resiliencia.

---

## 20. Failure Modes

| Código | Descripción | Causa probable |
|--------|-------------|----------------|
| `RELAY_UNREACHABLE` | No se pudo conectar al relay. | Endpoint caído, NAT, DNS. |
| `RELAY_AUTH_FAILED` | La autenticación KM-0002 contra el relay falló. | Clave inválida, relay desconocido. |
| `STORAGE_FULL` | El relay alcanzó `maxStoredMessages`/`maxStorageBytes`. | Saturación del relay. |
| `TTL_EXPIRED` | El mensaje excedió `maxMessageAgeMs`/`expiresAt`. | Destinatario nunca apareció. |
| `MESSAGE_TOO_LARGE` | El mensaje excede `maxMessageSize`. | Reutiliza definición KM-0004/0003. |
| `RELAY_NOT_AUTHORIZED` | El cliente intentó usar `STORE_AND_FORWARD` sin capability acordada. | Mala negociación. |
| `PEER_NOT_FOUND` | Destinatario no online (y el relay no tiene `STORE_AND_FORWARD`). | Def. KM-0003 §13.2. |
| `RELAY_HANDOFF_FAILED` | Falló la transferencia de custodia entre relays. | Relay original caído. |
| `RATE_LIMITED` | Exceso de mensajes/consultas por minuto. | Ver §16. |

---

## 21. Security Model

### 21.1 — Confidencialidad

El `payload` de los **User Messages** viaja cifrado extremo a extremo (KM-0004 / KM-IDENTITY-0001). El relay **NO DEBE** poder descifrarlo jamás, ni en tránsito ni en reposo.

### 21.2 — Integridad de la entrega

El relay **NO PUEDE** falsificar un ACK: el `ACK` está firmado con la clave privada del receptor (KM-0004 §11.5). Un relay **DEBE** reenviar el `ACK` verbatim. Ni siquiera un relay comprometido puede probar una entrega inexistente.

### 21.3 — Storage at rest

Un relay con `STORE_AND_FORWARD` **DEBE** cifrar sus **Stored Messages** en reposo (protección del *storage*) y **DEBE** borrarlos de forma definitiva al cumplir §14.6. El cifrado en reposo es un mecanismo interno del relay (no protocolo), pero **MUST** aplicarse.

### 21.4 — Separación identidad vs reputación

| Dimensión | Origen | Naturaleza |
|-----------|--------|------------|
| **Identidad** | Claves Ed25519, `signature` | Criptográfica, verificable localmente. |
| **Reputación / capacidades observadas** | Latencia, uptime, entregas | Estadística, no verificable sin historial. |

En esta versión, la **selección** de relays se basa en identidad + config + anuncios firmados (`trustFlags`). El **sistema de reputación** (uptime, bandwidth, success rate) es una **fase futura** y **NO** debe implementarse como blockchain.

### 21.5 — Ataques y contramedidas

| Ataque | Contramedida |
|--------|--------------|
| Relay malicioso declara `STORE_AND_FORWARD` | El cliente solo usa relays `trusted`/configurados; los anuncios se verifican (firma + derivación de identity). |
| Sybil (1000 relays) | La confianza es por configuración/verificación, no por volumen; límites de tasa; (futuro) reputación. |
| Relay borra mensajes almacenados (disponibilidad) | El emisor puede conservar copia local (NI6) y detectar vía TIME (`STORED` sin `ACK`). |
| Relay devuelve `STORED` pero descarta el mensaje | El emisor reintenta localmente hasta ACK; `RELAY_EXPIRED` avisa. |
| Replay de `MESSAGE` | Deduplicación por `messageId` (KM-0004 §13). |
| Denegación de servicio al relay | Límites de tasa y `maxConnections`. |

### 21.6 — Metadatos visibles al relay

El relay observa `from`, `to`, `timestamp`, tamaño. Esto es inherente y aceptado (mismo modelo que KM-0003 §16.8). El **onion routing** para ocultar metadatos queda fuera de esta versión (§22).

---

## 22. Relación con Tor / Onion Routing

Este RFC **no** implementa Tor ni anonimato:

| Modelo | Qué es | En KM-0005 |
|--------|--------|-----------|
| Store-and-forward | Delivery con almacenamiento temporal en un nodo estable | **Esta versión** |
| Multi-hop relay | `D1→R1→R2→D2` para resiliencia | Fase futura (§19) |
| Onion routing | Capas de cifrado para ocultar emisor/receptor | **Fuera de alcance** |

Los objetivos aquí son **disponibilidad + NAT traversal + resilience de entrega**. El anonimato, si se desea, será una capa independiente posterior.

---

## 23. Wire Protocol

### 23.1 — Serialization

Mismas reglas que KM-0003 §17.1 y KM-0004 §18.1:

- JSON sin espacios redundantes;
- Base64URL sin padding;
- rechazo de campos duplicados;
- tamaños máximos por mensaje.

### 23.2 — Tipos nuevos

| Tipo | Dirección | Sección |
|------|-----------|---------|
| `NODE_ANNOUNCEMENT` | Node → peers | §9 |
| `NODE_QUERY` | Client → Node | §10.1 |
| `NODE_QUERY_RESULT` | Node → Client | §10.2 |
| `STORED` | Relay → Emisor | §14.4 |
| `RELAY_EXPIRED` | Relay → Emisor | §17.2 |
| `RELAY_HANDOFF` | Client → Relay nuevo | §14.7 |
| `RELAY_PULL` | Relay nuevo → Relay anterior | §14.7 |
| `RELAY_ERROR` | Relay → Client | §20 |

Reutilizados sin cambios: `AUTH_*` (KM-0002/0003), `PEER_ONLINE/OFFLINE` (KM-0003), `SDP_*`, `ICE_CANDIDATE` (KM-0003), `PING/PONG` (KM-0003), `MESSAGE`/`ACK` (KM-0004).

### 23.3 — Ejemplos completos

**NODE_ANNOUNCEMENT (VPS relay):**

```
{
  "type": "NODE_ANNOUNCEMENT",
  "messageId": "0192f5a2-3b4c-7d8e-9f01-23456789abcd",
  "timestamp": 1721827200000,
  "protocolVersion": "2.0",
  "nodeId": "f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0a1b2c3d4e5f6a7b8c9d0e1f2",
  "publicKey": "MCowBQYDK2VwAyEA...",
  "nodeName": "vps-helsinki",
  "endpoints": [
    { "transport": "websocket", "address": "wss://203.0.113.10:443/ws" }
  ],
  "capabilities": ["RELAY", "SIGNALING", "STORE_AND_FORWARD"],
  "limits": {
    "maxMessageSize": 65536,
    "maxStoredMessages": 10000,
    "maxStorageBytes": 1073741824,
    "maxMessageAgeMs": 604800000,
    "maxConnections": 512
  },
  "signature": "MEUCIQDW..."
}
```

**STORED:**

```
{
  "type": "STORED",
  "messageId": "0192f5a2-4c5d-6e7f-8a9b-0123456789ab",
  "timestamp": 1721827200500,
  "originalMessageId": "0192f5a0-1234-5678-9abc-def012345678",
  "to": "f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0a1b2c3d4e5f6a7b8c9d0e1f2",
  "expiresAt": 1722432000000,
  "relayNodeId": "f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0a1b2c3d4e5f6a7b8c9d0e1f2"
}
```

**RELAY_ERROR:**

```
{
  "type": "RELAY_ERROR",
  "messageId": "0192f5a2-5d6e-7f8a-9b01-234567890abc",
  "timestamp": 1721827201000,
  "errorCode": "STORAGE_FULL",
  "errorMessage": "Relay cache at capacity",
  "originalMessageId": "0192f5a0-1234-5678-9abc-def012345678"
}
```

---

## 24. Enmiendas a documentos congelados

Este RFC **enmienda formalmente** las siguientes cláusulas, **solo** para nodos con capability `STORE_AND_FORWARD` y solo cuando el cliente haya aceptado explícitamente (capability de sesión + estos `limits`):

| Documento | Cláusula | Efecto de KM-0005 |
|-----------|----------|-------------------|
| KM-0003 RI2 | "El Relay NO DEBE almacenar User Messages" | **Relajada**: un relay puede almacenar **ciphertext** temporalmente si declara `STORE_AND_FORWARD`, respectando §14 y §16-17. |
| KM-0003 §13.3 | "El Relay NO DEBE almacenar mensajes en tránsito" | **Relajada** de igual forma para `STORE_AND_FORWARD`. |
| KM-0004 §5 N1 | "Historial persistente en el Relay — NO" | **Precisada**: el almacenamiento temporal cifrado no es persistencia permanente; se mantiene el espíritu (no historial). |
| KM-0004 §14.6 | "La Offline Queue es exclusivamente del lado del emisor" | **Ampliada**: la cola puede existir en relays con `STORE_AND_FORWARD` como custodia temporal. |
| KM-0001 P3 | "El Relay NO DEBE almacenar User Messages" | **Precisada**: aplica a relays sin capability; con capability, solo ciphertext temporal con borrado. |

Los relays **sin** `STORE_AND_FORWARD` siguen obligados por las cláusulas originales sin cambios.

---

## 25. Reasignación de números

| Número anterior | Asignación previa (obsoleta) | Nuevo destino |
|-----------------|------------------------------|---------------|
| KM-0005 | Groups (KM-0000/0001/0003), Attachment Protocol (KM-0004) | **Node & Relay Protocol** (este RFC) |
| KM-0006 | Voice | Libre (futura asignación) |
| KM-0007 | Binary Format | Libre (futura asignación) |

Los RFC referencian a "KM-0005" en cuatro documentos. Esa referencia **DEBE** leerse como: *"KM-0005 — Node & Relay Protocol"*. La asignación definitiva de los números de Groups, Voice, Binary Format y Attachments se detallará en la próxima revisión de KM-0001 (que es Draft y no está congelado).

---

## 26. References

| Ref | Documento |
|-----|-----------|
| [KM-0000] | KM-0000 — Terminology |
| [KM-0001] | KM-0001 — Protocol Architecture Overview |
| [KM-0002] | KM-0002 — Authentication |
| [KM-0003] | KM-0003 — Relay Protocol |
| [KM-0004] | KM-0004 — Message Protocol |
| [KM-IDENTITY-0001] | KM-IDENTITY-0001 — Identity Model |
| [KM-ID-0001] | KM-ID-0001 — Identity, Devices and Contact Bundles |
| [RFC 2119] | [Key words for use in RFCs](https://datatracker.ietf.org/doc/html/rfc2119) |
| [RFC 8174] | [Ambiguity of Uppercase vs Lowercase in RFC 2119 Key Words (BCP 14)](https://datatracker.ietf.org/doc/html/rfc8174) |

---

## 27. History

| Versión | Fecha | Cambios |
|---------|-------|---------|
| **0.3** | **2026-09-27** | **Actualización de identidad para KM-ID-0001**: identityId pasa de 40 a 64 caracteres hex (32 bytes). nodeId == identityId (no existe derivación separada para nodeId). La verificación de NODE_ANNOUNCEMENT cambia de `first160bits(SHA-256(pubkey))` a `SHA-256("KM-ID-IDENTITY" \|\| pubkey)` según KM-ID-0001 §6. NI7 actualizado. Ejemplos wire actualizados con identityId de 64 hex. |
| 0.1 | 2026-08-10 | Documento inicial — Node Model & NodeCapabilities, Node Announcement, Node Discovery, Relay Selection/Failover, Relay Connection, Store-and-Forward, ACK Forwarding, Relay Limits, Expiration, Duplicate Handling, Multi-hop (futuro), Security Model, Wire Protocol, enmiendas a KM-0003/KM-0004/KM-0001, reasignación del número KM-0005 |
| 0.2 | 2026-08-10 | Feedback de revisión: (1) copia local del emisor elevada a requisito (NI6) con semántica de confirmación de dos niveles `STORED → DELIVERED`; (2) `STORED` ya no autoriza borrar la copia local; (3) flujo completo y escenario de fallo de relay en §14.4a; (4) decisión pendiente `NodeRole` vs `NodeCapabilities` (§6.4); (5) boilerplate normativo a BCP 14 (RFC 2119 + RFC 8174) |