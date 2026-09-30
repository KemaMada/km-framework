# KM-0003 — Relay Protocol

| Campo | Valor |
|-------|-------|
| **RFC** | KM-0003 |
| **Título** | Relay Protocol |
| **Estado** | Draft (Frozen) |
| **Versión** | 0.2 |
| **Nota** | Congelado. Solo se corregirán bugs de especificación descubiertos durante la implementación. |
| **Autor** | KeyMessage Project |
| **Última actualización** | 2026-07-24 |
| **Reemplaza** | — |
| **Reemplazado por** | — |

---

## 1. Purpose

Este documento define el comportamiento del **Relay** dentro del protocolo KeyMessage.

El **Relay** es un componente de infraestructura cuya función se limita a:

- Descubrir qué **Peers** están disponibles en un momento dado.
- Autenticar y registrar conexiones de **Clients**.
- Transportar mensajes de señalización entre **Peers** para permitir el establecimiento de conexiones directas.
- Notificar cambios de presencia (**ONLINE** / **OFFLINE**).

Una vez que dos **Peers** establecen un canal directo (**DataChannel**), el **Relay** deja de participar en la comunicación.

El **Relay** **NO** almacena mensajes de usuario, **NO** cifra ni descifra contenido, y **NO** forma parte del plano de datos.

---

## 2. Scope

Este documento cubre:

- El modelo del **Relay**: qué es y qué no es.
- Los objetivos y no-objetivos del **Relay**.
- Los invariantes del **Relay**.
- El ciclo de vida de la conexión **Client** ↔ **Relay**.
- El registro y autenticación del **Client**.
- El descubrimiento de **Peers** conectados.
- Los mensajes de señalización.
- Las notificaciones de presencia.
- El reenvío de mensajes.
- La desconexión y limpieza de estado.
- Los modos de fallo.
- Las consideraciones de seguridad.
- El formato de serialización de los mensajes.

Este documento **NO** cubre:

- La autenticación entre **Peers** — definida en KM-0002.
- La capa de mensajes de usuario — definida en KM-0004.
- La implementación concreta del **Transport** (WebSocket, TCP, etc.).
- El protocolo TURN — definido en [RFC 5766](https://datatracker.ietf.org/doc/html/rfc5766).
- La mensajería grupal — definida en KM-0005.
- El cifrado extremo a extremo — definido en KM-IDENTITY-0001.
- El almacenamiento distribuido o DHT — fuera del alcance del protocolo base.

---

## 3. Definitions

Este documento utiliza los términos definidos en **KM-0000** con el significado allí especificado.

Además, se definen los siguientes términos específicos:

| Término | Definición |
|---------|------------|
| **Relay Connection** | Conexión de **Transport** entre un **Client** y un **Relay**. |
| **Peer Registration** | Proceso mediante el cual un **Client** declara su **Identity** ante el **Relay** y demuestra su control. |
| **Peer Presence** | Estado de disponibilidad de un **Peer** desde la perspectiva del **Relay**: **ONLINE** u **OFFLINE**. |
| **Signaling Message** | Mensaje intercambiado a través del **Relay** cuyo propósito es facilitar el establecimiento de una conexión directa entre **Peers**. |
| **Heartbeat** | Mensaje periódico de mantenimiento de conexión (**PING** / **PONG**). |
| **Session Timeout** | Período máximo sin actividad tras el cual el **Relay** considera a un **Peer** como **OFFLINE**. |

Los términos normativos **MUST**, **MUST NOT**, **SHOULD**, **SHOULD NOT** y **MAY** se interpretan según [RFC 2119](https://datatracker.ietf.org/doc/html/rfc2119).

---

## 4. Goals

| # | Objetivo | Descripción |
|---|----------|-------------|
| G1 | **Descubrimiento temporal** | El **Relay** permite que un **Peer** sepa qué otros **Peers** están disponibles en un momento dado. |
| G2 | **Señalización transparente** | El **Relay** reenvía mensajes de señalización entre **Peers** sin interpretar ni modificar su contenido. |
| G3 | **Registro autenticado** | Todo **Client** **DEBE** autenticarse ante el **Relay** antes de participar en cualquier intercambio. |
| G4 | **Notificación de presencia** | El **Relay** notifica a los **Peers** sobre cambios de estado (**ONLINE** / **OFFLINE**). |
| G5 | **Privacidad de grafo** | El **Relay** no almacena relaciones entre **Peers** y no retiene información más allá de la conexión activa. |

---

## 5. Non-goals

| # | Excluye | Motivo |
|---|---------|--------|
| N1 | Almacenamiento de **User Messages** | El **Relay** no es un servidor de mensajería. |
| N2 | Almacenamiento de **Attachments** | El **Relay** no gestiona contenido binario. |
| N3 | Enrutamiento de mensajes de usuario | Los **Messages** viajan por **DataChannel**, no por el **Relay**. |
| N4 | Autoridad de identidad | El **Relay** verifica que un `identityId` corresponde a una clave pública, pero no emite identidades. |
| N5 | Sistema de cuentas | No existen cuentas de usuario en el **Relay**. |
| N6 | Backup o sincronización | El **Relay** no conserva datos entre sesiones. |
| N7 | Moderación de contenido | El **Relay** no interpreta el contenido de los mensajes. |
| N8 | DHT o almacenamiento distribuido | El **Relay** no implementa resolución global de identidades ni persistencia distribuida. |
| N9 | Cifrado o descifrado | El **Relay** no participa en el cifrado extremo a extremo. |
| N10 | Descubrimiento histórico | El **Relay** solo conoce **Peers** conectados en este momento. |

---

## 6. Relay Model

### 6.1 — Qué es un Relay

Un **Relay** es un servicio de red que:

- Escucha conexiones entrantes de **Clients**.
- Autentica y registra a cada **Client** mediante su **Identity**.
- Mantiene un registro volátil de **Peers** activos.
- Reenvía mensajes de señalización entre **Peers**.
- Notifica cambios de presencia.
- Impone límites de tasa y timeouts.

### 6.2 — Qué NO es un Relay

Un **Relay** **NO** es un servidor de mensajería, un DHT, una autoridad de identidad, un sistema de cuentas, un backup, ni un servicio que interpreta el contenido de los mensajes de usuario.

### 6.3 — Principio fundamental

> El **Relay** facilita la conexión entre **Endpoints**, pero no forma parte de la comunicación de datos entre **Peers**.

```
Alice                    Relay                      Bob

  |                        |                         |
  | REGISTER               |                         |
  |----------------------->|                         |
  |                        |                         |
  |                        | REGISTER                |
  |                        |<------------------------|
  |                        |                         |
  | PEER_ONLINE            |                         |
  |<-----------------------|                         |
  |                        |                         |
  | SDP_OFFER              |                         |
  |----------------------->|                         |
  |                        | SDP_OFFER               |
  |                        |------------------------>|
  |                        |                         |
  |                        | SDP_ANSWER              |
  |                        |<------------------------|
  | SDP_ANSWER             |                         |
  |<-----------------------|                         |
  |                        |                         |
  | ICE_CANDIDATE          |                         |
  |<======================>|                         |
  |                        |                         |
  |<========== DataChannel ========================>|
```

Después del establecimiento del **DataChannel**:

```
Alice <=====================> Bob

Relay ya no participa.
```

---

## 7. Relay Invariants

Las siguientes propiedades **DEBEN** mantenerse en todas las versiones del protocolo:

| # | Invariante | Descripción |
|---|------------|-------------|
| RI1 | **No inspección de aplicación** | El **Relay** **NO DEBE** inspeccionar ni modificar mensajes de aplicación. |
| RI2 | **No almacenamiento de mensajes** | El **Relay** **NO DEBE** almacenar **User Messages**. |
| RI3 | **Solo señalización** | El **Relay** **DEBE** reenviar únicamente mensajes de señalización definidos por el protocolo. |
| RI4 | **Identidad única por conexión** | El **Relay** **DEBE** identificar cada conexión mediante una única **Identity** autenticada. |
| RI5 | **Sin asociación entre conexiones** | El **Relay** **NO DEBE** asumir que dos conexiones pertenecen al mismo dispositivo salvo que el protocolo lo indique explícitamente. |
| RI6 | **Estado efímero** | El **Relay** **PUEDE** descartar cualquier estado asociado a una conexión inmediatamente después de su cierre. |
| RI7 | **Sin grafo social** | El **Relay** **NO DEBE** almacenar relaciones entre **Peers**. |
| RI8 | **Autenticación previa** | El **Relay** **DEBE** rechazar cualquier mensaje de un **Client** no autenticado. |

---

## 8. Connection Lifecycle

### 8.1 — Estados

```
         ┌──────────────┐
         │ DISCONNECTED │
         └──────┬───────┘
                │ connect
                ▼
         ┌──────────────┐
         │  CONNECTING  │
         └──────┬───────┘
                │ transport established
                ▼
         ┌──────────────┐
         │  REGISTERED  │
         └──────┬───────┘
                │ registration confirmed
                ▼
         ┌──────────────┐
         │    ONLINE    │
         └──────┬───────┘
                │ disconnect / timeout
                ▼
         ┌──────────────┐
         │ DISCONNECTED │
         └──────────────┘
```

| Estado | Descripción |
|--------|-------------|
| **DISCONNECTED** | El **Client** no tiene conexión activa con el **Relay**. |
| **CONNECTING** | El **Client** está estableciendo la conexión de **Transport** con el **Relay**. |
| **REGISTERED** | El **Transport** está establecido. El **Client** ha completado la autenticación ([KM-0002]) y el **Relay** ha enviado **AUTH_OK**. |
| **ONLINE** | El registro ha sido confirmado. El **Client** puede enviar y recibir mensajes de señalización. |

### 8.2 — Transiciones

- **DISCONNECTED → CONNECTING**: El **Client** inicia una conexión de **Transport**.
- **CONNECTING → REGISTERED**: El **Transport** se ha establecido. El **Client** envía **AUTH_REQUEST** (KM-0002).
- **REGISTERED → ONLINE**: El **Relay** confirma la autenticación (**AUTH_OK**). El **Client** aparece como disponible.
- **REGISTERED → DISCONNECTED**: Autenticación rechazada o error de **Transport** durante el registro.
- **ONLINE → DISCONNECTED**: Desconexión explícita, timeout, o error de **Transport**.

> **Nota:** En implementaciones futuras, los estados CONNECTING y AUTHENTICATING **MAY** añadirse como sub-estados de REGISTERED para mayor granularidad en la notificación de presencia. La especificación actual agrupa ambos bajo REGISTERED por simplicidad.

### 8.3 — Session Timeout

El **Relay** **DEBE** imponer un **Session Timeout** para **Peers** en estado **ONLINE**. Si un **Peer** no envía ningún mensaje dentro de este período, el **Relay** **DEBE** pasarlo a **DISCONNECTED** y liberar todos los recursos asociados. Los valores indicados son **RECOMENDADOS** — una implementación **MAY** ajustarlos según sus requisitos operativos.

| Parámetro | Valor recomendado |
|-----------|-------------------|
| **Session Timeout** | 60 s sin mensaje |
| **Heartbeat interval** | 30 s |
| **Heartbeat response window** | 10 s |

### 8.4 — Heartbeat

El heartbeat permite al **Relay** detectar **Peers** desconectados y mantener el estado **ONLINE**.

**Cualquier mensaje del Client** (no solo **PONG**) renueva el timeout de actividad. El **Relay** no necesita enviar **PING** si hay actividad regular del **Peer**.

```
Relay → Client: PING
{
  "type": "PING",
  "messageId",
  "timestamp"
}
```

```
Client → Relay: PONG
{
  "type": "PONG",
  "messageId",
  "timestamp",
  "originalMessageId": "messageId del PING"
}
```

| Comportamiento | Regla |
|----------------|-------|
| El **Relay** envía **PING** | Cada 30 s de inactividad. |
| El **Client** responde **PONG** | Dentro de los 10 s siguientes. |
| Sin **PONG** en 10 s | El **Relay** **MAY** enviar un segundo **PING**. |
| Sin respuesta en 20 s | El **Relay** **DEBE** cerrar la conexión y emitir **PEER_OFFLINE**. |

---

## 9. Client Registration and Authentication

### 9.1 — Propósito

El registro permite que un **Client** declare su **Identity** ante el **Relay** y demuestre criptográficamente que posee la clave privada correspondiente. Para ello, **Client** y **Relay** **DEBEN** ejecutar el protocolo de autenticación mutua definido en [KM-0002], reutilizando sus tipos de mensaje, **Authentication Transcript** y **Server Authentication Transcript**. No se definen mensajes de autenticación específicos del **Relay**.

### 9.2 — Identidad del Relay

El **Relay** **DEBE** poseer un par de claves Ed25519 persistente. La clave pública del **Relay** **DEBE** ser distribuida a los **Clients** mediante un mecanismo fuera de banda (configuración incluida en el **Client**, DNS, QR en el panel de administración, etc.). El **identityId** del **Relay** se deriva deterministamente de su clave pública según KM-IDENTITY-0001.

### 9.3 — Flujo

| KM-0002 | Rol en Relay |
|---------|-------------|
| **Initiator** | **Client** |
| **Responder** | **Relay** |
| `identityId` (Initiator) | **identityId** del **Client** |
| `responderIdentityId` | **identityId** del **Relay** |

```
Client                              Relay

  |                                   |
  |  AUTH_REQUEST                     |
  |  (identityId, publicKey,          |
  |   responderIdentityId,            |
  |   capabilities)                   |
  |──────────────────────────────────>|
  |                                   |
  |  AUTH_CHALLENGE                   |
  |  (nonce)                          |
  |<──────────────────────────────────|
  |                                   |
  |  AUTH_RESPONSE                    |
  |  (signature)                      |
  |──────────────────────────────────>|
  |                                   |
  |  AUTH_OK / RELAY_ERROR            |
  |<──────────────────────────────────|
  |                                   |
```

### 9.4 — AUTH_REQUEST (Client → Relay)

El **Client** inicia la autenticación enviando su identidad, clave pública y capabilities, según el formato definido en [KM-0002].

```
{
  "type": "AUTH_REQUEST",
  "messageId",
  "timestamp",
  "protocolVersion",
  "identityId": "<identityId del Client>",
  "publicKey": "<clave pública del Client (Base64URL)>",
  "responderIdentityId": "<identityId del Relay>",
  "capabilities": ["<capabilities>"]
}
```

| Campo | Requisito | Descripción |
|-------|-----------|-------------|
| protocolVersion | MUST | Versión del protocolo [KM-0002] soportada. |
| identityId | MUST | **Identity ID** del **Client** (40 caracteres hex). |
| publicKey | MUST | Clave pública Ed25519 correspondiente al `identityId` (Base64URL). |
| responderIdentityId | MUST | **Identity ID** del **Relay**. El **Relay** **DEBE** verificar que coincide con su propia identidad. |
| capabilities | MAY | Lista de **Capabilities** soportadas (Feature Negotiation, [KM-0002 §X]). Son las mismas capabilities definidas en KM-0002; no existe un sistema de capabilities independiente para el **Relay**. |

### 9.5 — AUTH_CHALLENGE (Relay → Client)

El **Relay** genera un **nonce** y lo envía al **Client** según [KM-0002 §X].

```
{
  "type": "AUTH_CHALLENGE",
  "messageId",
  "timestamp",
  "nonce": "16 bytes codificados en Base64URL"
}
```

| Campo | Requisito | Descripción |
|-------|-----------|-------------|
| nonce | MUST | Valor aleatorio de 128 bits, codificado en Base64URL. **DEBE** tener entropía criptográfica. |

### 9.6 — AUTH_RESPONSE (Client → Relay)

El **Client** firma el **Authentication Transcript** definido en [KM-0002 §X] y envía la firma.

```
authentication_transcript = nonce || timestamp || responderIdentityId || identityId
signature = Ed25519.sign(clientPrivateKey, authentication_transcript)
```

```
{
  "type": "AUTH_RESPONSE",
  "messageId",
  "timestamp",
  "signature": "Ed25519 signature en Base64URL"
}
```

### 9.7 — Verificación

El **Relay** **DEBE** verificar:

1. Que el `identityId` recibido se corresponde con la `publicKey` (derivación determinista según KM-IDENTITY-0001).
2. Que el `responderIdentityId` coincide con la identidad del **Relay**.
3. Que la `signature` es válida contra la `publicKey` declarada, sobre el **Authentication Transcript**.
4. Que el `nonce` no ha sido utilizado previamente (protección contra replay).

Si cualquiera de estas verificaciones falla, el **Relay** **DEBE** rechazar el registro con código `AUTH_FAILED`.

### 9.8 — Confirmación (AUTH_OK)

El **Relay** confirma la autenticación exitosa mediante **AUTH_OK**, que incluye la **Server Authentication Transcript** firmada por el **Relay** ([KM-0002 §X]). Esto permite al **Client** verificar la identidad del **Relay** (autenticación mutua).

```
{
  "type": "AUTH_OK",
  "messageId",
  "timestamp",
  "protocolVersion",
  "identityId": "<identityId del Client>",
  "responderIdentityId": "<identityId del Relay>",
  "sessionId",
  "serverSignature": "<Ed25519 signature del Relay>",
  "capabilities": ["<capabilities acordadas>"]
}
```

A partir de este momento, el **Relay** considera al **Client** como **REGISTERED** y puede emitir **PEER_ONLINE** a otros **Peers**.

### 9.9 — Rechazo

Si la verificación falla o el **Relay** no puede autenticar al **Client**, responde con:

```
{
  "type": "RELAY_ERROR",
  "messageId",
  "timestamp",
  "errorCode": "AUTH_FAILED",
  "errorMessage": "Authentication failed — invalid identity or signature"
}
```

### 9.10 — Estado interno

El **Relay** mantiene exclusivamente por **Peer** registrado:

```
PeerEntry:
  identityId:    string (40 chars hex)
  connectionId:  string (32 bytes, aleatorio criptográfico)
  sessionId:     string (UUID v7)
  lastSeen:      uint64 (timestamp ms)
  publicKey:     string (Base64URL)
  capabilities:  string[]
  registeredAt:  uint64 (timestamp ms)
```

El **Relay** **NO DEBE** almacenar listas de contactos, historial de comunicaciones, ni metadatos adicionales.

El `connectionId` es un identificador interno del **Relay** que:

- **DEBE** ser generado criptográficamente (mínimo 32 bytes — 256 bits de entropía).
- **DEBE** ser impredecible.
- **DEBE** ser único entre todas las conexiones activas simultáneamente.
- **NUNCA** aparece en mensajes del protocolo wire.
- **DEBE** eliminarse al cerrar la conexión.
- **NO DEBE** reutilizarse tras la desconexión.

### 9.11 — Vida del registro

El registro vive mientras la conexión de **Transport** esté activa. Al desconectarse, el **Relay** **DEBE** eliminar la entrada asociada. El registro **NO** persiste entre reinicios del **Relay**.

### 9.12 — Múltiples conexiones

Si un **Client** completa exitosamente KM-0002 para una segunda conexión mientras la primera aún está activa para el mismo `identityId`, el **Relay** **DEBE** aplicar una de las siguientes políticas:

1. **Rechazar la nueva conexión** con código `SESSION_ALREADY_EXISTS`.
2. **Cerrar la conexión anterior** y aceptar la nueva (reasignación).

La política concreta es una decisión de implementación. El **Relay** **DEBE** documentar qué política aplica.

---

## 10. Peer Discovery

### 10.1 — Consulta directa

Un **Peer** registrado **MAY** consultar al **Relay** si otro **Peer** está **ONLINE**.

```
Client → Relay: PEER_QUERY
{
  "type": "PEER_QUERY",
  "messageId",
  "timestamp",
  "targetId": "identityId del Peer consultado"
}
```

```
Relay → Client: PEER_QUERY_RESULT
{
  "type": "PEER_QUERY_RESULT",
  "messageId",
  "timestamp",
  "targetId",
  "online": true | false
}
```

### 10.2 — Contenido mínimo

Las respuestas de presencia **DEBEN** contener únicamente el `identityId` y el estado booleano. No incluyen dirección IP, ubicación, información del dispositivo ni metadatos del **Peer**.

### 10.3 — Protecciones

Para prevenir enumeración masiva de identidades, el **Relay** **DEBE** implementar:

| Protección | Especificación |
|------------|----------------|
| **Autenticación** | Solo **Peers** registrados (KM-0002 autenticado) **MAY** realizar consultas. Consultas desde conexiones no autenticadas **DEBEN** ser rechazadas con `NOT_REGISTERED`. |
| **Respuesta uniforme** | La respuesta para un `targetId` inexistente **DEBE** ser idéntica en formato a la respuesta para un `targetId` existente pero offline: ambas devuelven `online: false`. Esto evita distinguir entre identidades válidas e inválidas. |
| **Rate limiting** | Consultas de presencia **DEBEN** limitarse a 30 / min por **Peer**. Superar este límite **DEBE** devolver `RATE_LIMITED`.

---

## 11. Signaling

### 11.1 — Principio de transparencia

El **Relay** únicamente interpreta los metadatos necesarios para el enrutamiento (`type`, `from`, `to`, `messageId`). El **Relay** **NO DEBE** interpretar, validar ni modificar el contenido del campo `payload`.

### 11.2 — Mensaje genérico

Para mantener independencia del protocolo de transporte subyacente (WebRTC, QUIC, TCP hole punching, libp2p), los mensajes de señalización utilizan un formato genérico con `payload` opaco.

### SDP_OFFER

```
Peer A → Relay → Peer B
{
  "type": "SDP_OFFER",
  "messageId",
  "timestamp",
  "from": "identityId del emisor",
  "to": "identityId del destinatario",
  "payload": "..."
}
```

### SDP_ANSWER

```
Peer B → Relay → Peer A
{
  "type": "SDP_ANSWER",
  "messageId",
  "timestamp",
  "from": "identityId del emisor",
  "to": "identityId del destinatario",
  "payload": "..."
}
```

### ICE_CANDIDATE

```
Peer A ↔ Relay ↔ Peer B
{
  "type": "ICE_CANDIDATE",
  "messageId",
  "timestamp",
  "from": "identityId del emisor",
  "to": "identityId del destinatario",
  "payload": "..."
}
```

### 11.3 — Independencia del protocolo de transporte

Si en el futuro se reemplaza WebRTC por otro mecanismo, los tipos `SDP_OFFER`, `SDP_ANSWER` e `ICE_CANDIDATE` pueden coexistir con nuevos tipos o ser reemplazados sin cambiar la semántica del **Relay**. El **Relay** transporta el campo `payload` sin inspeccionarlo.

### 11.4 — CONTACT_EXCHANGE

Transporta información de identidad de un **Client** a otro, típicamente después de un intercambio fuera de banda (código QR, enlace, etc.).

```
Peer A → Relay → Peer B
{
  "type": "CONTACT_EXCHANGE",
  "messageId",
  "timestamp",
  "from": "identityId del emisor",
  "to": "identityId del destinatario",
  "identityId": "identityId del remitente",
  "publicKey": "clave pública (Base64URL)"
}
```

El **Relay** **NO DEBE** validar el contenido semántico del **CONTACT_EXCHANGE**. La verificación de la información de identidad ocurre en KM-0002. El **Relay** **MAY** comprobar únicamente que el mensaje respeta el formato mínimo requerido (campos presentes, tipos correctos, tamaños dentro de límites).

El **Relay** **DEBE** reenviar (**forward verbatim**) el mensaje **CONTACT_EXCHANGE** sin modificar ninguno de sus campos, incluyendo `identityId` y `publicKey`. El **Relay** **NO DEBE** alterar, truncar ni reinterpretar el contenido.

---

## 12. Notifications

### 12.1 — PEER_ONLINE

El **Relay** **MAY** notificar a uno o más **Peers** cuando otro **Peer** se conecta.

```
Relay → Peer: PEER_ONLINE
{
  "type": "PEER_ONLINE",
  "messageId",
  "timestamp",
  "identityId": "identityId del Peer que se conectó"
}
```

### 12.2 — PEER_OFFLINE

El **Relay** **MAY** notificar a uno o más **Peers** cuando un **Peer** se desconecta.

```
Relay → Peer: PEER_OFFLINE
{
  "type": "PEER_OFFLINE",
  "messageId",
  "timestamp",
  "identityId": "identityId del Peer que se desconectó"
}
```

### 12.3 — Contenido mínimo

Las notificaciones **DEBEN** contener únicamente el `identityId`. No incluyen dirección IP, ubicación, ni metadatos del **Peer**.

---

## 13. Message Forwarding

### 13.1 — Enrutamiento por identityId

El **Relay** enruta los mensajes entrantes exclusivamente mediante el campo `to`. Si el **Peer** destinatario está **ONLINE**, el **Relay** **DEBE** reenviar el mensaje a su conexión activa.

### 13.1a — Orden de entrega

El **Relay** **DEBE** preservar el orden de los mensajes provenientes de un mismo emisor. Si el **Client A** envía `[M1, M2, M3]` en ese orden, el **Relay** **DEBE** entregarlos a **Client B** en el mismo orden. El **Relay** **NO DEBE** reordenar mensajes del mismo emisor.

Entre distintos emisores el **Relay** **NO** ofrece garantías de orden relativo.

### 13.2 — Destino no encontrado

Si el `to` no corresponde a ningún **Peer** **ONLINE**, el **Relay** **DEBE** responder con el código `PEER_NOT_FOUND`.

### 13.3 — Sin almacenamiento intermedio

El **Relay** **NO DEBE** almacenar mensajes en tránsito. Si el destinatario no está disponible, el mensaje **DEBE** ser descartado.

### 13.4 — Sin multidifusión

El **Relay** **NO DEBE** implementar multidifusión. Todo mensaje tiene exactamente un emisor (`from`) y un destinatario (`to`).

### 13.5 — Sin autoenvío

El **Relay** **DEBE** rechazar mensajes donde `from` == `to` con el código `SELF_DELIVERY`.

---

## 14. Disconnection

### 14.1 — Desconexión explícita

El **Client** **MAY** cerrar la conexión con el **Relay** en cualquier momento. El **Relay** **DEBE** limpiar todo el estado asociado y emitir **PEER_OFFLINE**.

### 14.2 — Desconexión por timeout

Si un **Peer** supera el **Session Timeout** sin actividad, el **Relay** **DEBE** cerrar la conexión y emitir **PEER_OFFLINE**.

### 14.3 — Desconexión por error

Si ocurre un error de **Transport**, el **Relay** **DEBE** cerrar la conexión y emitir **PEER_OFFLINE**.

### 14.4 — Limpieza de estado

Al desconectarse, el **Relay** **DEBE** eliminar toda la información asociada al **Peer**: entrada de presencia, estado de registro, y cualquier recurso interno. El **Relay** **MAY** descartar este estado inmediatamente después del cierre (RI6).

---

## 15. Failure Modes

| Código | Descripción | Causa probable |
|--------|-------------|----------------|
| `AUTH_FAILED` | La autenticación KM-0002 falló (identityId, publicKey o signature inválidos). | Error de registro, clave incorrecta, o firma inválida. |
| `PEER_NOT_FOUND` | El `to` no corresponde a ningún **Peer** **ONLINE**. | El destino no está conectado a este **Relay**. |
| `NOT_REGISTERED` | El remitente no ha completado la autenticación. | Mensaje enviado antes de **AUTH_OK**. |
| `RATE_LIMITED` | Se superó el límite de mensajes por minuto. | Abuso o error de implementación. |
| `CONNECTION_TIMEOUT` | La conexión superó el **Session Timeout**. | Inactividad prolongada. |
| `INVALID_SIGNAL` | El mensaje de señalización está mal formado. | Campos faltantes, tipo desconocido. |
| `MESSAGE_TOO_LARGE` | El mensaje excede el tamaño máximo permitido (64 KiB). | Payload excesivo. |
| `SELF_DELIVERY` | El emisor intentó enviarse un mensaje a sí mismo. | Error del **Client**. |
| `SESSION_ALREADY_EXISTS` | Ya existe una conexión activa para este `identityId`. | El **Client** intentó registrar una segunda conexión. |

---

## 16. Security Considerations

### 16.1 — Modelo de confianza

KM-0003 asume que el **Relay** es **no confiable**. El **Relay** **MAY** observar metadatos (quién envía señalización a quién), pero **NO DEBE** poder leer, modificar o bloquear **User Messages** (protegido por KM-0002 y KM-0004).

### 16.2 — Lo que el Relay protege

| Propiedad | Descripción |
|-----------|-------------|
| **Disponibilidad** | Límites de tasa y timeouts previenen abuso. |
| **Descubrimiento temporal** | Solo **Peers** actualmente conectados son visibles. |
| **Señalización** | El **Relay** reenvía sin modificar. |

### 16.3 — Lo que el Relay NO protege

| Propiedad | Motivo |
|-----------|--------|
| **Privacidad de metadatos** | El **Relay** ve quién envía señalización a quién. |
| **Anonimato** | La dirección IP del **Client** es visible para el **Relay**. |
| **Identidad** | La autenticación real entre **Peers** ocurre en KM-0002. |

### 16.4 — Conexión cifrada

El **Transport** entre **Client** y **Relay** **DEBE** estar cifrado (TLS / WSS) para proteger la señalización contra observación pasiva.

### 16.5 — Sin autoridad central

El **Relay** no es una autoridad de identidad. Un **Relay** comprometido no puede suplantar la identidad de un **Peer** sin romper KM-0002.

### 16.6 — Rate limiting

El **Relay** **DEBE** implementar los siguientes límites de tasa:

| Límite | Valor recomendado | Ámbito |
|--------|-------------------|--------|
| Mensajes de señalización | 60 / min | Por **Peer** |
| Intentos de registro | 5 / min | Por conexión |
| Contact Exchange | 10 / min | Por **Peer** |
| Tamaño máximo de mensaje | 64 KB (tamaño del mensaje serializado en bytes) | Por mensaje |

### 16.7 — Rotación de connectionId

El `connectionId` interno **DEBE** ser un valor aleatorio no predecible (ver §9.10).

### 16.8 — Metadata leakage

Aunque el **Relay** no puede leer el contenido de los mensajes de señalización ni de los **User Messages**, inevitablemente observa metadatos a nivel de **Transport** y de protocolo **Relay**:

| Metadato | Visible para el Relay | Implicación |
|----------|----------------------|-------------|
| **Quién se comunica con quién** | Sí — `from` y `to` en todo mensaje de señalización. | El **Relay** sabe qué **Peers** están intercambiando señalización. |
| **Cuándo se comunican** | Sí — timestamps de los mensajes. | El **Relay** puede inferir patrones de actividad. |
| **Frecuencia de comunicación** | Sí — conteo de mensajes por par. | El **Relay** puede inferir intensidad de uso. |
| **Quién está online** | Sí — lista de **Peers** registrados. | El **Relay** sabe qué identidades están activas. |
| **Dirección IP** | Sí — conexión **Transport** subyacente. | El **Relay** puede correlacionar IP con **identityId**. |
| **Contenido del payload** | No — cifrado extremo a extremo (KM-0004). | Protegido contra el **Relay**. |
| **Contenido de User Messages** | No — cifrado extremo a extremo (KM-0004). | Protegido contra el **Relay**. |

El modelo de seguridad de KM-0003 **asume** esta filtración de metadatos. Las contramedidas (como el uso de Tor, VPNs, o relays anónimos) están fuera del alcance de esta especificación.

---

## 17. Wire Protocol

### 17.1 — Serialization requirements

Los mensajes del protocolo **Relay** utilizan **JSON** como formato de serialización por defecto. Se aplican las siguientes reglas, consistentes con KM-0002:

| Regla | Especificación |
|-------|----------------|
| **Codificación Base64URL** | Según [RFC 4648 §5](https://datatracker.ietf.org/doc/html/rfc4648#section-5), **SIN** padding (`=`). |
| **Campos duplicados** | Una implementación **DEBE** rechazar cualquier mensaje con campos duplicados en JSON. |
| **Tamaño máximo** | Un mensaje completo serializado **NO DEBE** exceder los 64 KiB (65 536 bytes). |
| **payload** | El campo `payload` en mensajes de señalización es **opaco** para el **Relay**. Se codifica como Base64URL cuando contiene datos binarios, o como string JSON cuando contiene texto. El emisor y el receptor acuerdan el formato interno mediante **Feature Negotiation** (KM-0002). |

### 17.2 — Cabecera común

Todos los mensajes **DEBEN** incluir:

| Campo | Requisito | Descripción |
|-------|-----------|-------------|
| type | MUST | Tipo del mensaje. |
| messageId | MUST | Identificador único (UUIDv7 recomendado). |
| timestamp | MUST | Tiempo Unix en milisegundos del momento de envío. |

### 17.3 — AUTH_REQUEST (KM-0002)

El **Client** inicia la autenticación. Formato completo definido en [KM-0002 §X].

```
{
  "type": "AUTH_REQUEST",
  "messageId": "0190f5a2-3b4c-7d8e-9f01-23456789abcd",
  "timestamp": 1721827200000,
  "protocolVersion": "2.0",
  "identityId": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0",
  "publicKey": "MCowBQYDK2VwAyEA...",
  "responderIdentityId": "f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0",
  "capabilities": ["relay.v1", "encryption.v1"]
}
```

### 17.4 — AUTH_CHALLENGE (KM-0002)

El **Relay** desafía al **Client** con un nonce.

```
{
  "type": "AUTH_CHALLENGE",
  "messageId": "0190f5a2-4c5d-6e7f-8a9b-0123456789ab",
  "timestamp": 1721827200100,
  "nonce": "aB3dEfGhIjKlMnOpQrStUvWxYz123456"
}
```

### 17.5 — AUTH_RESPONSE (KM-0002)

El **Client** responde con la firma del **Authentication Transcript**.

```
{
  "type": "AUTH_RESPONSE",
  "messageId": "0190f5a2-5d6e-7f8a-9b01-234567890abc",
  "timestamp": 1721827200200,
  "signature": "MEQCIHl6k..."
}
```

### 17.6 — AUTH_OK (KM-0002)

El **Relay** confirma la autenticación exitosa. A partir de este momento el **Client** es considerado **REGISTERED**.

```
{
  "type": "AUTH_OK",
  "messageId": "0190f5a2-6e7f-8a9b-0123-45678901abcd",
  "timestamp": 1721827200300,
  "protocolVersion": "2.0",
  "identityId": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0",
  "responderIdentityId": "f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0",
  "sessionId": "0190f5a2-6e7f-8a9b-0123-45678901abcd",
  "serverSignature": "MEUCIQDM..."
}
```

### 17.7 — PEER_ONLINE

```
{
  "type": "PEER_ONLINE",
  "messageId": "0190f5a2-7f8a-9b01-2345-678901abcdef",
  "timestamp": 1721827205000,
  "identityId": "f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0"
}
```

### 17.8 — PEER_OFFLINE

```
{
  "type": "PEER_OFFLINE",
  "messageId": "0190f5a2-8f9a-0b12-3456-789012abcdef",
  "timestamp": 1721827208000,
  "identityId": "f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0"
}
```

### 17.9 — SDP_OFFER

```
{
  "type": "SDP_OFFER",
  "messageId": "0190f5a2-9a0b-1234-5678-9012345678ab",
  "timestamp": 1721827210000,
  "from": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0",
  "to": "f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0",
  "payload": "v=0... (opaco para el Relay)"
}
```

### 17.10 — SDP_ANSWER

```
{
  "type": "SDP_ANSWER",
  "messageId": "0190f5a2-abcd-1234-5678-9012345678cd",
  "timestamp": 1721827211000,
  "from": "f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0",
  "to": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0",
  "payload": "v=0... (opaco para el Relay)"
}
```

### 17.11 — ICE_CANDIDATE

```
{
  "type": "ICE_CANDIDATE",
  "messageId": "0190f5a2-bcde-1234-5678-9012345678ef",
  "timestamp": 1721827212000,
  "from": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0",
  "to": "f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0",
  "payload": "candidate:1 1 UDP 2122252543 192.168.1.1 54321 typ host"
}
```

### 17.12 — CONTACT_EXCHANGE

```
{
  "type": "CONTACT_EXCHANGE",
  "messageId": "0190f5a2-cdef-1234-5678-9012345678ab",
  "timestamp": 1721827213000,
  "from": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0",
  "to": "f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0",
  "identityId": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0",
  "publicKey": "MCowBQYDK2VwAyEA..."
}
```

### 17.13 — RELAY_ERROR

```
{
  "type": "RELAY_ERROR",
  "messageId": "0190f5a2-def0-1234-5678-9012345678bc",
  "timestamp": 1721827214000,
  "errorCode": "PEER_NOT_FOUND",
  "errorMessage": "Destination peer is not connected",
  "originalMessageId": "0190f5a2-9a0b-1234-5678-9012345678ab"
}
```

### 17.14 — PING

```
{
  "type": "PING",
  "messageId": "0190f5a2-ef01-2345-6789-0123456789ab",
  "timestamp": 1721827220000
}
```

### 17.15 — PONG

```
{
  "type": "PONG",
  "messageId": "0190f5a2-f012-3456-7890-123456789abc",
  "timestamp": 1721827220500,
  "originalMessageId": "0190f5a2-ef01-2345-6789-0123456789ab"
}
```

---

## 18. References

| Ref | Documento |
|-----|-----------|
| [KM-0000] | KM-0000 — Terminology |
| [KM-0001] | KM-0001 — Protocol Architecture Overview |
| [KM-0002] | KM-0002 — Authentication |
| [KM-0004] | KM-0004 — Message Protocol |
| [RFC 2119] | [Key words for use in RFCs](https://datatracker.ietf.org/doc/html/rfc2119) |
| [RFC 5766] | [TURN Specification](https://datatracker.ietf.org/doc/html/rfc5766) |

---

## 19. History

| Versión | Fecha | Cambios |
|---------|-------|---------|
| 0.1 | 2026-07-24 | Documento inicial — Relay Model, Goals, Non-goals, Relay Invariants (RI1–RI8), Connection Lifecycle, Client Registration and Authentication, Peer Discovery, Signaling, Notifications, Message Forwarding, Disconnection, Failure Modes, Security Considerations, Wire Protocol |
| 0.2 | 2026-07-25 | Revisión completa basada en feedback. Ver erratas abajo. |

### Erratas de la revisión 0.2

- **Registro mediante KM-0002**: El flujo de registro ya no usa mensajes `RELAY_REGISTER`/`RELAY_CHALLENGE`/`RELAY_RESPONSE`/`RELAY_REGISTERED`. En su lugar, **Client** y **Relay** ejecutan el protocolo de autenticación mutua definido en [KM-0002] (`AUTH_REQUEST`, `AUTH_CHALLENGE`, `AUTH_RESPONSE`, `AUTH_OK`). El **Relay** **DEBE** poseer su propio par de claves Ed25519 y **identityId**.
- **Failure Modes**: `INVALID_IDENTITY` reemplazado por `AUTH_FAILED` como código genérico de error de autenticación.
- **connectionId**: Especificación completa — 32 bytes mínimo, entropía criptográfica, único entre conexiones activas, no reutilizable tras desconexión.
- **Capabilities**: Clarificado que son las mismas **Capabilities** definidas en KM-0002 (Feature Negotiation). No existe un sistema independiente para el **Relay**.
- **Session Timeout**: Valores explícitamente marcados como RECOMENDADOS (SHOULD), no normativos.
- **Presence states**: Añadida nota sobre posibles sub-estados futuros (CONNECTING, AUTHENTICATING).
- **Orden de entrega**: Añadido §13.1a — el **Relay** **DEBE** preservar el orden de mensajes del mismo emisor.
- **PEER_QUERY protections**: Añadido §10.3 — solo **Peers** autenticados, respuesta uniforme para targets inexistentes vs offline, rate limiting 30/min.
- **CONTACT_EXCHANGE**: Añadido que el **Relay** **DEBE** reenviar (**forward verbatim**) sin modificar ningún campo.
- **Metadata Leakage**: Añadido §16.8 con tabla de metadatos visibles para el **Relay**.
- **Wire Protocol**: Mensajes de registro reemplazados por los tipos `AUTH_*` de KM-0002.
