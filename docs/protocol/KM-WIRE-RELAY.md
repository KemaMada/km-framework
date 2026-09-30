# KM-WIRE-RELAY — Relay Transport Wire Contract

| Campo | Valor |
|-------|-------|
| **Documento** | KM-WIRE-RELAY |
| **Título** | Relay Transport Wire Contract |
| **Estado** | Draft (especificado) |
| **Versión** | 0.1 |
| **Naturaleza** | Especificación de **transporte**. No es un RFC de dominio. |
| **Autor** | KeyMessage Project |
| **Última actualización** | 2026-09-30 |
| **Depende de** | KM-0002 (autenticación), KM-0004 (`SecureFrame`) |
| **Relacionado con** | KM-0003, KM-0005 (dominio), [RFC 6455](https://datatracker.ietf.org/doc/html/rfc6455), [RFC 4648](https://datatracker.ietf.org/doc/html/rfc4648) |
| **Implementado por** | `RelayClient` (cliente Android, OkHttp WebSocket) |
| **Implementado para** | `km daemon` — **no existe todavía** |

---

## 1. Purpose

Este documento fija el **contrato de transporte** del relé: qué bytes viajan por el
WebSocket, qué significa cada uno y qué está obligado a hacer quien los mueve.

El alcance es deliberadamente estrecho. El *qué* significa un mensaje de usuario,
una identidad o un `SecureFrame` pertenece a los RFC de dominio (`docs/rfc/`). Aquí
solo se define el **dialecto**: JSON sobre WebSocket, UTF-8, con una familia cerrada
de tipos de mensaje.

## 2. Why this document exists

KM-0003 describe el **modelo** del relé: qué hace y qué no hace. KM-0004 describe el
contenido cifrado. Ninguno de los dos fija el **formato de los frames WebSocket**, y ese
formato es hoy la cosa menos definida del sistema: existe un cliente que habla un
dialecto y **no existe nadie del otro lado**.

Este documento existe para que el futuro `km daemon` no tenga que *adivinar* el contrato.
La regla es simple: lo que el cliente ya envía y ya sabe leer queda aquí escrito como
norma; lo que el cliente **no** hace queda aquí escrito como *previsto*, nunca como
norma.

## 3. Naming: por qué `KM-WIRE-RELAY` y no `KM-00xx`

Los RFC de dominio (`docs/rfc/`) usan el espacio `KM-00xx` y están numerados. Este
documento **no** es un RFC de dominio: no introduce semántica de identidades, mensajes
ni sesiones. Describiría mal un `KM-0007`.

El prefijo `KM-WIRE-` marca la categoría: **contrato de cable**. El sufijo lo identifica
dentro de la categoría. Así, `docs/rfc/KM-0003-relay-protocol.md` y
`docs/protocol/KM-WIRE-RELAY.md` pueden convivir sin colisión de nombres, y una lectura
del índice deja claro que uno describe comportamiento y el otro describe bytes.

## 4. Scope

**Define:**

- El framing WebSocket: RFC 6455, frames de texto, UTF-8.
- La envoltura JSON y el campo `type` como discriminante.
- Los tipos de mensaje existentes, sus campos, su dirección y su semántica.
- El flujo de autenticación paso a paso, con los tamaños de los transcripts.
- La frontera de opacidad del relé y la regla de Base64.
- Los límites de tamaño y el comportamiento al excederlos.
- Los errores y sus códigos.

**NO define:**

- El contenido de `SecureFrame` — definido en KM-0004.
- La semántica de identidad y autenticación — definida en KM-0002.
- El modelo del relé, presencia o señalización como comportamiento — definido en KM-0003.
- El protocolo de nodo, anuncios y descubrimiento — definido en KM-0005.
- Cómo se despliega, escala u opera el relé.

## 5. Terminology

| Término | Significado |
|---------|-------------|
| **Dialecto** | El vocabulario concreto de campos y tipos que un endpoint habla. El *lenguaje* es KM-0003/KM-0004; el dialecto es este documento. |
| **Opaco** | Que el relé mueve bytes sin interpretarlos. «Opaco» es una afirmación sobre el código del relé, no una promesa sobre el contenido. |
| **Transcodificación** | Único trabajo del relé: representational, sin semántica. Base64 → `ByteArray` → Base64. |
| **Frame de transporte** | Un mensaje WebSocket de texto con un objeto JSON de nivel superior. |
| **`SecureFrame`** | Contenedor cifrado de KM-0004, 44 bytes de cabecera + ciphertext. Para el relé es una `ByteArray` opaca. |
| **Transcripción (transcript)** | Los bytes exactos que se firman. No es JSON; es una concatenación binaria de tamaño fijo. |

Los términos normativos MUST / MUST NOT / SHOULD / SHOULD NOT / MAY se interpretan según
[RFC 2119](https://datatracker.ietf.org/doc/html/rfc2119).

---

## 6. The Central Rule

> **Base64 pertenece al TRANSPORTE, no a `SecureFrame`.**

El `SecureFrame` de KM-0004 es una estructura binaria de tamaño fijo con una cabecera de
44 bytes. Base64 no forma parte de ese formato. Base64 es **la representación de este
dialecto**, y nada más.

El camino completo es:

```
SecureFrame → ByteArray → Base64 → JSON MESSAGE → WebSocket
             → daemon → JSON MESSAGE → Base64 decode → ByteArray original
```

### 6.1 — Consecuencia normativa (NORMATIVE)

> **KM-WIRE-RELAY: el `daemon` MUST NOT parsear el `SecureFrame`, MUST NOT inspeccionar
> `messageId`, MUST NOT descifrar, MUST NOT validar ACKs de aplicación, MUST NOT
> reconstruir ni re-cifrar, y MUST NOT modificar ningún byte de la carga útil. Su único
> trabajo es transcodificar.**

### 6.2 — Propiedad verificable

> **El frame que entra es byte a byte el frame que sale.**

Ésta es la prueba fuerte de que el relé es opaco. No es una intención ni un objetivo de
diseño: es una propiedad que se puede comprobar sobre el código. `RelayServer.deliverData`
recibe un `RelayDataEnvelope`, comprueba `envelope.payload.size`, comprueba
`envelope.from`, busca el handler del destinatario y lo invoca
(`km-core/.../node/RelayServer.kt:320-352`). El payload se entrega **sin tocar**. Quien
lo recibe obtiene exactamente los mismos bytes.

Si alguna vez un cambio en el relé rompe esta propiedad, el relé ha dejado de ser un
relé y se ha convertido en un participant en el protocolo de mensajes.

### 6.3 — Por qué Base64 en JSON y no frames binarios

Se eligió **Base64 estándar dentro del campo JSON**, no frames WebSocket binarios. Motivo:
mantener el contrato actual del cliente y reducir el cambio de protocolo al mínimo. El
cliente ya está escrito sobre `org.json` y OkHttp text frames.

Coste aceptado: ~33 % de expansión de tamaño, y JSON + Base64 es redundante. Se acepta
explícitamente a cambio de no romper el cliente existente.

### 6.4 — Evolución futura (documentada, no cerrada)

Internamente se trabaja con `ByteArray`; Base64 es solo la representación de este
dialecto. Cuando esto sea cierto —es decir, cuando exista un `daemon` que obeyezca §6.1—
más adelante podrá añadirse un **canal WebSocket binario** como alternativa, sin tocar
`km-core`, ni `RelayServer`, ni la lógica criptográfica.

Esto queda **documentado y deliberadamente sin cerrar**. No es una commitment de
implementación. El requisito para que sea viable es que §6.1 se cumpla hoy.

---

## 7. Framing

| Propiedad | Valor | Estado |
|-----------|-------|--------|
| Transporte | WebSocket, RFC 6455 | **Implementado** (`RelayClient.kt:136`) |
| Tipo de frame | Texto (opcode `0x1`) | **Implementado** (`RelayClient.kt:318-320`) |
| Codificación | UTF-8 | **Implementado** |
| Formato de carga | Objeto JSON de nivel superior | **Implementado** (`RelayClient.kt:150`) |
| Discriminante | Campo `type`, string | **Implementado** (`RelayClient.kt:151-166`) |
| Binario nativo | — | **Previsto**, no implementado (§6.4) |

### 7.1 — Reglas de parseo (NORMATIVE)

1. Cada frame de texto MUST contener un único objeto JSON.
2. `type` MUST estar presente y ser un string.
3. Campos desconocidos MUST ser **ignorados** (extensions MAY). Esto sigue la política
   ya establecida en `AuthJsonCodec` (`km-core/.../auth/AuthJsonCodec.kt:19-22`).
4. Un frame que no cumpla 1 o 2 MUST ser descartado. **No** MUST Mata la conexión.

`RelayClient` cumple 4 de forma total: el `type` desconocido cae en `else -> {}` y el
`try/catch` exterior absorbe cualquier excepción de parseo
(`RelayClient.kt:148-169`). El comportamiento resultante es silencio.

---

## 8. Referencia rápida de tipos de mensaje

| `type` | Dirección | Estado | Propósito |
|--------|-----------|--------|-----------|
| `AUTH_REQUEST` | Cliente → Relé | **Implementado** (`RelayClient.kt:207`) | Abre la autenticación KM-0002 |
| `AUTH_CHALLENGE` | Relé → Cliente | **Implementado** (`RelayClient.kt:154`) | Nonce + timestamp del relé |
| `AUTH_RESPONSE` | Cliente → Relé | **Implementado** (`RelayClient.kt:229`) | Firma Ed25519 sobre transcript de 152 B |
| `AUTH_OK` | Relé → Cliente | **Implementado** (`RelayClient.kt:155`) | Sesión establecida, `sessionId` |
| `AUTH_FAIL` | Relé → Cliente | **Implementado** (`RelayClient.kt:156`) | Autenticación rechazada |
| `PEER_ONLINE` | Relé → Cliente | **Implementado** (`RelayClient.kt:157`) | Presencia: un peer se conectó |
| `PEER_OFFLINE` | Relé → Cliente | **Implementado** (`RelayClient.kt:158`) | Presencia: un peer se desconectó |
| `PING` | Relé → Cliente | **Implementado** (`RelayClient.kt:159`) | Heartbeat de aplicación |
| `PONG` | Cliente → Relé | **Implementado** (`RelayClient.kt:264`) | Respuesta al heartbeat |
| `MESSAGE` | Ambos | **Implementado** (`RelayClient.kt:163`) | Carga opaca: `data` = Base64(`SecureFrame`) |
| `ACK` | Ambos | **Implementado** (`RelayClient.kt:164`) | Control de **aplicación** (§12) |
| `STORED` | Relé → Cliente | **Implementado** (`RelayClient.kt:161`) | Store-and-forward: mensaje aceptado y guardado |
| `RELAY_EXPIRED` | Relé → Cliente | **Implementado** (`RelayClient.kt:162`) | El mensaje almacenado expiró |
| `RELAY_ERROR` | Relé → Cliente | **Implementado** (`RelayClient.kt:160`) | Error con `errorCode` |
| `AUTH_SESSION_TIMEOUT` | Relé → Cliente | **No implementado** | Cierre por `sessionExpiryMs` |
| `PEER_LIST` | Relé → Cliente | **No implementado** | Lista de peers conectados |
| `PEER_SUBSCRIBE` | Cliente → Relé | **No implementado** | Suscripción a presencia |
| `STATS_REQUEST` / `STATS_RESPONSE` | — | **No implementado** | Telemetría del relé |

Los tipos marcados **No implementado** no aparecen en `RelayClient`. El servidor no
existe, así que ninguno de ellos tiene emisor real (§17).

---

## 9. Autenticación

### 9.1 — Flujo paso a paso

```
Cliente                                        Relé
  │
  │  1. AUTH_REQUEST ─────────────────────────►│   identidad + publicKey + versión
  │
  │  2. AUTH_CHALLENGE ◄───────────────────────│   nonce (16 B) + timestamp
  │
  │     [cliente verifica timestamp dentro de ventana]
  │     [cliente firma transcript de 152 bytes]
  │
  │  3. AUTH_RESPONSE ────────────────────────►│   signature (Base64URL)
  │
  │     [relé verifica transcript con publicKey]
  │
  │  4. AUTH_OK ◄─────────────────────────────│   sessionId + serverSignature
  │
  │     [cliente verifica transcript de 120 bytes]   ← PREVISTO, §9.5
  │
  │  ══ sesión activa ══
```

### 9.2 — `AUTH_REQUEST`

**Implementado.** `RelayClient.kt:205-219`.

| Campo | Tipo | Obligatorio | Notas |
|-------|------|-------------|-------|
| `type` | string | Sí | `"AUTH_REQUEST"` |
| `messageId` | string (UUID) | Sí | `RelayClient.kt:208` |
| `timestamp` | int64 (ms) | Sí | `RelayClient.kt:209` |
| `protocolVersion` | string | Sí | `"2.0"` — `RelayClient.kt:210` |
| `identityId` | string | Sí | Hex del cliente |
| `publicKey` | string (Base64URL) | Sí | Clave Ed25519 — `RelayClient.kt:212` |
| `responderIdentityId` | string | Sí | Identidad del relé |
| `capabilities` | object | Sí | Hoy: `{"serialization":["json"]}` |

### 9.3 — `AUTH_CHALLENGE`

**Recibido por el cliente** (`RelayClient.kt:221-235`). El emisor no existe (§17).

| Campo | Tipo | Obligatorio | Notas |
|-------|------|-------------|-------|
| `type` | string | Sí | `"AUTH_CHALLENGE"` |
| `messageId` | string | Sí | |
| `timestamp` | int64 (ms) | Sí | Entra en el transcript firmado |
| `nonce` | string (Base64URL) | Sí | 16 bytes, `nonceSize` |
| `responderIdentityId` | string | Previsto | `AuthJsonCodec.challengeWire` lo emite; `RelayClient` no lo lee |

### 9.4 — `AUTH_RESPONSE`

**Implementado.** `RelayClient.kt:228-234`.

| Campo | Tipo | Obligatorio | Notas |
|-------|------|-------------|-------|
| `type` | string | Sí | `"AUTH_RESPONSE"` |
| `messageId` | string (UUID) | Sí | |
| `timestamp` | int64 (ms) | Sí | |
| `signature` | string (Base64URL) | Sí | Ed25519 sobre el transcript de 152 B |

`identityId` y `publicKey` **no** se reenvían aquí: viajan en `AUTH_REQUEST` y el transcript
los vincula por posición fija.

### 9.5 — Los dos transcripts

Ambos tamaños son **constantes**, no aproximaciones (`km-core/.../auth/Transcripts.kt:28-29`).

**Authentication Transcript — 152 bytes** (`Transcripts.kt:37-58`, `AuthVerifier.kt:73-81`)

| Offset | Tamaño | Campo |
|--------|--------|-------|
| 0 | 16 | `nonce` |
| 16 | 8 | `uint64_be(timestamp)` |
| 24 | 64 | `responderIdentityId` (ASCII hex) |
| 88 | 64 | `identityId` (ASCII hex) |
| | **152** | |

**Server Authentication Transcript — 120 bytes** (`Transcripts.kt:60-76`, `AuthVerifier.kt:106-113`)

| Offset | Tamaño | Campo |
|--------|--------|-------|
| 0 | 40 | `sessionId` (ASCII hex) |
| 40 | 16 | `serverNonce` |
| 56 | 64 | `initiatorIdentityId` (ASCII hex) |
| | **120** | |

### 9.6 — `AUTH_OK` y `AUTH_FAIL`

**Implementado** en recepción (`RelayClient.kt:237-249`). Emisor: no existe.

| `AUTH_OK` campo | Tipo | Obligatorio | Notas |
|-------|------|-------------|-------|
| `type` | string | Sí | `"AUTH_OK"` |
| `sessionId` | string (40 hex) | Sí | `RelayClient.kt:238` |
| `serverSignature` | string (Base64URL) | Sí (según KM-0002) | Firma sobre el transcript de 120 B |

| `AUTH_FAIL` campo | Tipo | Obligatorio | Notas |
|-------|------|-------------|-------|
| `type` | string | Sí | `"AUTH_FAIL"` |
| `errorCode` | string | Sí | Leído en `RelayClient.kt:245` |
| `errorMessage` | string | Previsto | Leído en `RelayClient.kt:246` |

> **Nota de honestidad (normativa por proyecto, honesta por estado):**
> `RelayClient.handleAuthOk`
> **no verifica** `serverSignature`. Pasa a `ONLINE` en cuanto lee `sessionId`
> (`RelayClient.kt:237-242`). La verificación del transcript de 120 bytes existe en km-core
> (`AuthVerifier.verifyAuthOk`) y está probada, pero **el cliente Android no la invoca**.
> Esto está marcado como pendiente, no como norma cumplida.

### 9.7 — Divergencia de tipos en `protocolVersion`

> **Honestidad:** el cliente envía `protocolVersion` como **string** `"2.0"`
> (`RelayClient.kt:210`), pero `AuthJsonCodec` lo parsea como **int**
> (`AuthJsonCodec.kt:65`, `intField`). Son dialectos incompatibles hoy. La norma de este
> documento asume el **string `"2.0"`** porque es lo que el cliente emite, y marca la
> reconciliación como pendiente (§18).

---

## 10. `MESSAGE`

**Implementado** en recepción (`RelayClient.kt:163`). Envío: `RelayClient.send` reenvía
bytes opacos sin interpretarlos (`RelayClient.kt:79-85`).

| Campo | Tipo | Obligatorio | Notas |
|-------|------|-------------|-------|
| `type` | string | Sí | `"MESSAGE"` |
| `from` | string (hex) | Sí | Identidad del emisor. **Verificada** por anti-spoofing (§11) |
| `to` | string (hex) | Sí | Identidad del destinatario |
| `data` | string (Base64 estándar) | Sí | `SecureFrame` codificado (§6) |
| `messageId` | string | Previsto | El relé **no** lo lee |
| `timestamp` | int64 (ms) | Previsto | El relé **no** lo lee |

### 10.1 — Sobre el nombre del campo

> **Honestidad:** `RelayClient` no nombra ningún campo de carga: reenvía el `ByteArray`
> entero tal cual (`RelayClient.kt:84`). Ni `RelayClient` ni `km-core` producen hoy un
> `MESSAGE` con campo `data`. El nombre canónico aquí es **`data`** por decisión de
> diseño (§6.3), y su codificación en ambos extremos está **pendiente** (§18).

El campo equivalente que sí existe hoy es `payload` en `JsonEncoder`
(`km-core/.../codec/JsonEncoder.kt:15`) — pero pertenece a la capa de mensajes de
km-core, no a este dialecto de relé.

---

## 11. `MESSAGE` vs `ACK`

> **Insight — El relé mueve bytes y no conoce semántica de `ACK`.**

Este punto se ha malinterpretado antes, así que se enuncia sin ambigüedad:

- **`MESSAGE`**: carga opaca. El relé no la interpreta. La aplica una capa de aplicación
  que sí la entiende.
- **`ACK`**: **control de aplicación**. El relé **NO lo entiende**.

Un `ACK` **no** es un `MESSAGE` con una respuesta. Es un mensaje de una capa distinta que
el relé transporta con la misma indiferencia con la que transporta cualquier otra cosa.

### 11.1 — Por qué esta distinción importa

Si el relé entendiera `ACK`, tendría que mantener estado de pendientes, correlacionar
`originalMessageId`, decidir qué hacer con destinatarios que ya se fueron, y conocer el
ciclo de vida de la sesión de aplicación. Todo eso es semántica de aplicación y ninguno
pertenece a un relé opaco.

El cliente Android **sí** distingue los dos: despacha `MESSAGE` y `ACK` por separado
(`RelayClient.kt:163-164`), y `KeyMessageCoreImpl` los entrega a¡ capas distintas —
`receiver.onMessageBytesReceived` frente a `ackManager.onAckReceived`
(`km-core/.../api/KeyMessageCoreImpl.kt:46-52`). Esa separación es correcta y el relé
debe preservarla, no aplanarla.

### 11.2 — Campos de `ACK`

**Implementado** en `JsonEncoder.encodeAck` (`JsonEncoder.kt:20-29`).

| Campo | Tipo | Obligatorio |
|-------|------|------------|
| `type` | string | Sí (`"ACK"`) |
| `from` | string | Sí |
| `to` | string | Sí |
| `originalMessageId` | string (UUID) | Sí |
| `status` | string (enum `AckStatus`) | Sí |
| `timestamp` | int64 (ms) | Sí |
| `signature` | string (Base64 estándar) | Sí |

---

## 12. Presencia y heartbeat

### 12.1 — `PEER_ONLINE` / `PEER_OFFLINE`

**Implementados** (`RelayClient.kt:251-259`).

| Campo | Tipo | Obligatorio | Notas |
|-------|------|-------------|-------|
| `type` | string | Sí | |
| `identityId` | string (hex) | Sí | El peer afectado |

El relé ya tiene el modelo de presencia internamente (`RelayServer` mantiene
`onlineHandlers` / `offlineHandlers`, `km-core/.../node/RelayServer.kt:110-111`), pero
**no serializa ninguno de esos eventos a JSON**. La conversión presencia → `PEER_ONLINE`
está pendiente (§18).

### 12.2 — `PING` / `PONG`

**Implementado.** El cliente responde a `PING` con `PONG` (`RelayClient.kt:261-270`).

| `PING` campo | Tipo | Obligatorio |
|-------|------|------------|
| `type` | string | Sí |
| `messageId` | string | Sí |
| `timestamp` | int64 (ms) | Sí |

| `PONG` campo | Tipo | Obligatorio |
|-------|------|------------|
| `type` | string | Sí |
| `messageId` | string (UUID) | Sí |
| `timestamp` | int64 (ms) | Sí |
| `originalMessageId` | string | Sí — correlaciona con el `PING` recibido |

### 12.3 — Dos niveles de ping (distinción importante)

| Nivel | Mecanismo | Quién lo genera |
|-------|-----------|-----------------|
| **RFC 6455** | Frame de control PING de WebSocket | OkHttp, `pingInterval(15s)` — `RelayClient.kt:33` |
| **Aplicación** | `PING` / `PONG` JSON | El relé (previsto); el cliente responde |

Son **distintos** y ambos pueden existir a la vez. El primero lo emite la librería de
transporte sin intervención del código de aplicación; el segundo viaja dentro del
diagrama JSON de este documento. Un daemon MUST NOT confundir ambos niveles.

---

## 13. Store-and-forward

### 13.1 — `STORED`

**Codificación implementada**, emisión no. `JsonRelayControlCodec.encodeStored`
(`km-core/.../codec/JsonRelayControlCodec.kt:12-22`); decodificación y dispatch por el
cliente en `RelayClient.kt:161, 275-278`.

| Campo | Tipo | Obligatorio |
|-------|------|------------|
| `type` | string | Sí (`"STORED"`) |
| `messageId` | string (UUID) | Sí |
| `timestamp` | int64 (ms) | Sí |
| `originalMessageId` | string (UUID) | Sí |
| `to` | string (hex) | Sí |
| `expiresAt` | int64 (ms) | Sí |
| `relayNodeId` | string | Sí |

### 13.2 — `RELAY_EXPIRED`

**Codificación implementada**, emisión no. `JsonRelayControlCodec.encodeRelayExpired`
(`JsonRelayControlCodec.kt:36-45`); cliente en `RelayClient.kt:162, 280-283`.

| Campo | Tipo | Obligatorio |
|-------|------|------------|
| `type` | string | Sí (`"RELAY_EXPIRED"`) |
| `messageId` | string (UUID) | Sí |
| `timestamp` | int64 (ms) | Sí |
| `originalMessageId` | string (UUID) | Sí |
| `to` | string (hex) | Sí |
| `reason` | string | Sí |

### 13.3 — Estado real del store-and-forward

> **Honestidad:** el store-and-forward **no está implementado**. Existe
> `InMemoryRelayStore` (`km-core/.../storage/InMemoryRelayStore.kt`), que es
> **solo en memoria** y con límites por defecto `Int.MAX_VALUE` / `Long.MAX_VALUE`
> (líneas 7-8) — es decir, sin límites efectivos. No hay persistencia, y no hay ningún
> componente que convierta un `STORED` en un `RELAY_EXPIRED` por paso del tiempo.

Los dos codecs están listos. La lógica que los dispararía, no.

---

## 14. Base64 inválido

### 14.1 — Regla (NORMATIVE)

> El daemon **MUST NOT adivinar** y **MUST NOT decodificar parcialmente**.

Si el campo `data` no es Base64 estándar válido, el daemon:

1. MUST NOT entregar carga parcial al destinatario.
2. MUST NOT intentar "reparar" la entrada (recortar caracteres inválidos, ignorar
   padding, asumir que falta un carácter).
3. MUST NOT reenviar los bytes tal cual hoping que el otro extremo lo entienda.
4. MUST descartar el frame completo de forma atómica.
5. MAY emitir `RELAY_ERROR` con `MESSAGE_TOO_LARGE` o el código de error de parseo que
   corresponda.

### 14.2 — Por qué "parcial" es peor que "fallar"

Un decode parcial devuelve bytes que *parecen* válidos. Si el relé los reenvía, el
destinatario recibe un `SecureFrame` truncado. La verificación AEAD de KM-0004 lo
rechazará, lo cual es correcto — pero el fallo se manifiesta como "mensaje corrupto" en
la capa de aplicación, muy lejos de la causa real, que fue un campo de transporte mal
formado.

Fallback atómico: **o se entrega el frame entero, o no se entrega nada.**

### 14.3 — Tolerancia a variaciones (previsto)

| Variación | ¿Aceptada? | Nota |
|-----------|------------|------|
| Padding `=` ausente | Previsto | El cliente auth ya usa Base64URL sin padding (`RelayClient.kt:306, 310`) |
| Caracteres fuera del alfabeto | **No** | Base64 estándar: `A-Za-z0-9+/` y `=` |
| whitespace / saltos de línea | **No** | El decoder MUST NOT tolerarlos en silencio |
| Longitud `len % 4 == 1` | **No** | Longitud imposible en Base64 |

---

## 15. Límites

### 15.1 — Valores

`RelayLimits` (`km-core/.../model/RelayLimits.kt:11-15`):

| Límite | Valor | Bytes |
|--------|-------|-------|
| `maxMessageSize` | 65 536 | 64 KiB |
| `maxStoredMessages` | 10 000 | — |
| `maxStorageBytes` | 1 073 741 824 | 1 GiB |
| `maxMessageAgeMs` | 604 800 000 | 7 días |
| `maxConnections` | 512 | — |

`RelayServer` (`km-core/.../node/RelayServer.kt:93-101`):

| Parámetro | Valor |
|-----------|-------|
| `sessionExpiryMs` | 300 000 |
| `timestampWindowMs` | 300 000 |
| `nonceSize` | 16 |
| `maxDataPayloadBytes` | 65 535 |

### 15.2 — La diferencia de 1 byte

`RelayLimits.maxMessageSize` es 65 536; `RelayServer.maxDataPayloadBytes` es 65 535.

No es una discrepancia que resolver aquí: `maxDataPayloadBytes` acota el **payload
binario** (`SecureFrame`), cuyo campo `length` es `uint16` y por tanto tope 65 535
(`SecureFrame.kt:38-39, 51`). `maxMessageSize` acota el **mensaje**. Son dos cosas, con
dos unidades distintas. El comentario en `RelayServer.kt:97-100` lo dice: «lo que cabe es
lo que un `SecureFrame` puede transportar, no más».

### 15.3 — Exceso (NORMATIVE)

| Límite excedido | Comportamiento |
|-----------------|----------------|
| `maxDataPayloadBytes` | Rechazo **antes** de rutear. Ya implementado en `RelayServer.kt:331-336`. |
| `maxMessageSize` | `RELAY_ERROR` con `MESSAGE_TOO_LARGE`. |
| `maxConnections` | Rechazo de la conexión entrante. |
| `maxStoredMessages` / `maxStorageBytes` | `RELAY_ERROR` con `STORAGE_FULL`. |
| `maxMessageAgeMs` | `RELAY_EXPIRED` (§13.2). |

El rechazo MUST ocurrir **antes** del enrutado, nunca después. `RelayServer` ya lo hace
así en el camino de datos: el chequeo de tamaño (línea 331) precede al lookup del
destinatario (línea 344).

---

## 16. Errores

### 16.1 — `RELAY_ERROR`

**Codificación implementada** (`JsonRelayControlCodec.encodeError`,
`JsonRelayControlCodec.kt:58-67`). **Recepción del cliente es un no-op**: la función
`handleRelayError` tiene el cuerpo vacío (`RelayClient.kt:272-273`). El tipo se reconoce
y se descarta.

| Campo | Tipo | Obligatorio |
|-------|------|------------|
| `type` | string | Sí (`"RELAY_ERROR"`) |
| `messageId` | string (UUID) | Sí |
| `timestamp` | int64 (ms) | Sí |
| `errorCode` | string (enum) | Sí |
| `errorMessage` | string | Opcional |
| `originalMessageId` | string (UUID) | Opcional |

### 16.2 — `RelayErrorCode`

Los nueve valores existentes (`km-core/.../model/RelayErrorNotice.kt:3-13`):

| Código | Significado |
|--------|-------------|
| `RELAY_UNREACHABLE` | No se puede alcanzar el relé |
| `RELAY_AUTH_FAILED` | Fallo de autenticación |
| `STORAGE_FULL` | Almacenamiento saturado |
| `TTL_EXPIRED` | TTL vencido |
| `MESSAGE_TOO_LARGE` | Excede el máximo |
| `RELAY_NOT_AUTHORIZED` | No autorizado |
| `PEER_NOT_FOUND` | Destinatario inexistente |
| `RELAY_HANDOFF_FAILED` | Fallo el traspaso |
| `RATE_LIMITED` | Rate limit excedido |

### 16.3 — `RelayServerError` (distinto, interno)

`RelayServer` usa un enum **diferente** y más fino
(`km-core/.../node/RelayServer.kt:17-28`), con nueve valores: `SESSION_NOT_FOUND`,
`SESSION_CLOSED`, `SESSION_EXPIRED`, `PEER_NOT_ONLINE`, `SPOOFING_DETECTED`,
`AUTH_FAILED`, `INVALID_MESSAGE`, `DUPLICATE_SESSION`, `INTERNAL_ERROR`.

> **Honestidad:** la correspondencia entre `RelayServerError` (interno) y
> `RelayErrorCode` (wire) **no está definida**. El mapeo es pendiente (§18). Ningún
> código de este documento debe asumirse como emitted por el relé hasta que ese mapeo
> exista.

---

## 17. Versionado

### 17.1 — Qué es `protocolVersion`

`"2.0"`, string, enviado por el cliente en `AUTH_REQUEST` (`RelayClient.kt:210`).

Es un **nivel de dialecto**, no una versión de KM-0002 y no una versión del modelo de
relé. KM-0002 tiene su propio campo `version` numérico dentro del challenge
(`AuthJsonCodec.kt:35`), y `RelayServer.createChallenge` lo pone a `3`
(`RelayServer.kt:132`). Son campos distintos con valores distintos. Que `"2.0"` sea un
string y `version` sea un int 3 es otra expresión de la divergencia de §9.7.

### 17.2 — Cómo se negocia

**No hay negociación implementada.** El modelo actual es *send-and-hope*:

1. El cliente anuncia `"2.0"`.
2. El relé (cuando exista) decide qué hacer con esa cadena.
3. No hay campo de versión en `AUTH_OK` que confirme el acuerdo.

**Previsto** para el daemon:

1. El relé MUST leer `protocolVersion` de `AUTH_REQUEST`.
2. Si la versión no es soportada, MUST responder `AUTH_FAIL` con un código explícito de
   versión no soportada.
3. Si es soportada, MUST confirmar la versión efectiva en `AUTH_OK` para que el cliente
   sepa con qué dialecto está hablando.

El punto 3 es importante: hoy el cliente asume que su dialectsólo existe. Sin
confirmación, un cliente antiguo y un relé nuevo pueden hablar y no entenderse.

### 17.3 — Compatibilidad

Un campo `type` desconocido MUST ser ignorado (§7.1). Esto da compatibilidad hacia
atrás limitada pero real: un relé nuevo que envie tipos que el cliente no conoce no rompe
al cliente viejo, que simplemente los descarta.

---

## 18. Estado: implementado vs previsto

Esta sección es normativa para la honestidad del documento. Cualquier cambio a un ítem
de la columna «Implementado» requiere actualizar esta tabla.

### 18.1 — Implementado (verificable en código)

| # | Componente | Ubicación |
|---|-----------|-----------|
| I1 | Cliente WebSocket sobre OkHttp, texto UTF-8 | `RelayClient.kt:32-34, 134-137, 313-333` |
| I2 | Dispatch por `type` de 11 tipos | `RelayClient.kt:153-166` |
| I3 | `AUTH_REQUEST` con `protocolVersion: "2.0"` | `RelayClient.kt:205-219` |
| I4 | Cálculo del transcript de 152 B en el cliente | `RelayClient.kt:292-307` |
| I5 | Transcripts de 152 B y 120 B con tamaño constante | `Transcripts.kt:28-29, 37-76` |
| I6 | `AuthVerifier` con 6 capas de verificación | `AuthVerifier.kt:61-115` |
| I7 | Vectores dorados de auth byte-a-byte | `GoldenAuthVectorsTest.kt` |
| I8 | `AuthJsonCodec` para challenge / response / authOk | `AuthJsonCodec.kt:31-90` |
| I9 | `STORED` y `RELAY_EXPIRED` codificados | `JsonRelayControlCodec.kt:12-56` |
| I10 | `RELAY_ERROR` codificado | `JsonRelayControlCodec.kt:58-78` |
| I11 | Separación `peerHandlers` / `dataHandlers` | `RelayServer.kt:107-108` |
| I12 | Anti-spoofing: `from` = peer autenticado | `RelayServer.kt:252-256, 337-343` |
| I13 | Límite de payload de datos 65 535 B, chequeado pre-ruta | `RelayServer.kt:331-336` |
| I14 | Límites `sessionExpiryMs`, `timestampWindowMs`, `nonceSize` | `RelayServer.kt:93-95` |
| I15 | `RelayLimits` con los 5 valores | `RelayLimits.kt:3-16` |
| I16 | `MESSAGE` y `ACK` despachados por separado | `RelayClient.kt:163-164` |
| I17 | Reconexión con backoff exponencial, tope 30 s | `RelayClient.kt:183-193` |
| I18 | `PING` → `PONG` con `originalMessageId` | `RelayClient.kt:261-270` |

### 18.2 — Previsto o ausente (NO es norma cumplida)

| # | Ítem | Nota |
|---|------|------|
| P1 | **El servidor WebSocket** | **No existe.** No hay ningún servidor en el repo. Ningún módulo lo declara (`settings.gradle.kts:26-27` solo incluye `:km-core` y `:km-webrtc`). |
| P2 | `km daemon` | No existe. |
| P3 | Transporte de datos por el relé | `RelayServer.deliverData` existe como lógica, pero **nada lo conecta a un socket**. No hay camino de datos operativo. |
| P4 | Store-and-forward persistente | Solo `InMemoryRelayStore`, en memoria, sin límites efectivos. |
| P5 | `MESSAGE` con campo `data` | El cliente reenvía bytes opacos; no nombra el campo (§10.1). |
| P6 | Verificación de `serverSignature` en `AUTH_OK` | El cliente pasa a `ONLINE` sin verificar (§9.6). |
| P7 | Negociación de `protocolVersion` | Sin confirmar en `AUTH_OK` (§17.2). |
| P8 | Emisión de `PEER_ONLINE` / `PEER_OFFLINE` | El cliente los lee; el relé no los serializa (§12.1). |
| P9 | Tratamiento de `RELAY_ERROR` en el cliente | Cuerpo vacío (`RelayClient.kt:272-273`). |
| P10 | Mapeo `RelayServerError` → `RelayErrorCode` | Indefinido (§16.3). |
| P11 | Reconciliación `protocolVersion` string vs int | §9.7. |
| P12 | Canal WebSocket binario | §6.4. |
| P13 | `AUTH_SESSION_TIMEOUT` | El cliente no lo maneja; `sessionExpiryMs` existe solo como parámetro interno. |

### 18.3 — La única especificación del lado servidor

> **Honestidad:** la única descripción del comportamiento servidor que existe es un
> **mock escrito a mano** dentro de un test: `RelayClientIntegrationTest.kt:126-267`.
> Levanta un `ServerSocket` crudo, hace el handshake WebSocket a mano, y despacha con
> `text.contains("\"type\":\"AUTH_REQUEST\"")` — matching por substring, no por parseo.
> Sus tres tests están marcados `@Ignore` con la nota «Requires debugging of in-process
> WebSocket mock relay» (líneas 24, 53, 94).

**Esto no es una norma.** Es un andamiaje de test. Cítalo como lo que es: la única
referencia existente de cómo sería un servidor, no una especificación. Lo que este
documento establece como norma es §1–§17.

Nótese además que el mock usa el campo `payload`, no `data`
(`RelayClientIntegrationTest.kt:266`), lo que confirma la divergencia de §10.1.

---

## 19. Ejemplos de JSON real

> **Honestidad:** estos ejemplos son **ilustrativos del dialecto normativo** de §1–§17.
> Los valores concretos (`sessionId`, `messageId`, firmas) son ficticios porque no hay
> servidor contra el que validarlos. Los **nombres de campo y tipos sí son normativa**.

### 19.1 — Apertura de sesión

```json
{
  "type": "AUTH_REQUEST",
  "messageId": "3f2a7c10-4b8d-4e91-a2c6-7d5e9f013b84",
  "timestamp": 1790000000000,
  "protocolVersion": "2.0",
  "identityId": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f40516273849a5b6c",
  "publicKey": "3p8Qm2vN7kLxR0dYhW5sZfTjA9bC1eG4uIoP6nMxKw",
  "responderIdentityId": "f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d9c8b7a6958473625140f3e2d",
  "capabilities": { "serialization": ["json"] }
}
```

### 19.2 — Challenge

```json
{
  "type": "AUTH_CHALLENGE",
  "messageId": "b7e91c48-2f6a-4d03-9a15-8e4c7b20d6f1",
  "timestamp": 1790000001042,
  "nonce": "kQ3vNp7xRz2mW8tYc5bHjL0nVd",
  "responderIdentityId": "f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d9c8b7a6958473625140f3e2d"
}
```

El `nonce` son 16 bytes en Base64URL sin padding. Entra en el transcript de 152 B.

### 19.3 — Response

```json
{
  "type": "AUTH_RESPONSE",
  "messageId": "c1d2e3f4-a5b6-4788-99aa-bbccddeeff00",
  "timestamp": 1790000001088,
  "signature": "8mKq2vXhR7pLdN3wYtZ9bJfHcE1aS4uG6iO0pQ8rV2w"
}
```

La firma cubre los 152 bytes del transcript, **no** este JSON. Reserializar el JSON con
las claves reordenadas no invalida nada — por diseño (`AuthJsonCodec.kt:10-17`).

### 19.4 — Confirmación

```json
{
  "type": "AUTH_OK",
  "messageId": "d4e5f607-1a2b-4c3d-8e4f-5061728394a5",
  "timestamp": 1790000001150,
  "protocolVersion": "2.0",
  "identityId": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f40516273849a5b6c",
  "responderIdentityId": "f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d9c8b7a6958473625140f3e2d",
  "sessionId": "7f3a9b2c1d4e5f60718293a4b5c6d7e8f90a1b2c3",
  "serverSignature": "B2nQ4wE6rT8yU0iO2pA4sD6fG8hJ0kL2mN4pQ6rS8u"
}
```

`sessionId` son 40 hex (= 20 bytes), requisito de `AuthVerifier.isValidHex40`
(`AuthVerifier.kt:120-121`). Entra en el transcript de 120 B.

### 19.5 — Mensaje de ida (el `SecureFrame` viaja opaco)

```json
{
  "type": "MESSAGE",
  "messageId": "e6f70819-2b3c-4d5e-9f60-1728394a5b6c",
  "timestamp": 1790000005000,
  "from": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f40516273849a5b6c",
  "to": "0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9",
  "data": "AQBkBwcAAAAAAAAAAQEBAaIqJ4gLUEwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMAABAgQIBRAAAABwYAAABlAABlAAFdwAAZABgAADwAAAAGAAgABhAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAAAA="
}
```

Descomposición de `data`: son los bytes del `SecureFrame` en Base64 estándar. Los
primeros 44 bytes son la cabecera (offset 0 = `0x01`, la versión). El resto es
ciphertext. **El daemon no lee ninguno de esos bytes.**

### 19.6 — El mismo mensaje, ida y vuelta

El `daemon` recibe §19.5, decodea `data`, y reenvía al destinatario:

```json
{
  "type": "MESSAGE",
  "messageId": "f708192a-3c4d-5e6f-a071-8293a4b5c6d7",
  "timestamp": 1790000005002,
  "from": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f40516273849a5b6c",
  "to": "0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9",
  "data": "AQBkBwcAAAAAAAAAAQEBAaIqJ4gLUEwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMAABAgQIBRAAAABwYAAABlAABlAAFdwAAZABgAADwAAAAGAAgABhAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAGAAAAAAA="
}
```

`data` es **idéntico**. Solo cambian `messageId` y `timestamp`, que son metadatos del
envelope, no de la carga.

> Esa es la demostración de §6.2: **el frame que entra es byte a byte el frame que
> sale.**

### 19.7 — Presencia y heartbeat

```json
{ "type": "PEER_ONLINE",  "identityId": "0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9" }
{ "type": "PEER_OFFLINE", "identityId": "0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9" }
```

```json
{ "type": "PING", "messageId": "192a3b4c-5d6e-4f70-8192-93a4b5c6d7e8", "timestamp": 1790000030000 }
```

```json
{ "type": "PONG", "messageId": "2a3b4c5d-6e7f-4081-92a3-b4c5d6e7f809", "timestamp": 1790000030011, "originalMessageId": "192a3b4c-5d6e-4f70-8192-93a4b5c6d7e8" }
```

### 19.8 — Store-and-forward

```json
{
  "type": "STORED",
  "messageId": "3b4c5d6e-7f80-4192-a3b4-c5d6e7f8091a",
  "timestamp": 1790000100000,
  "originalMessageId": "e6f70819-2b3c-4d5e-9f60-1728394a5b6c",
  "to": "0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9",
  "expiresAt": 1790606800000,
  "relayNodeId": "f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d9c8b7a6958473625140f3e2d"
}
```

```json
{
  "type": "RELAY_EXPIRED",
  "messageId": "4c5d6e7f-8091-42a3-b4c5-d6e7f8091a2b",
  "timestamp": 1790606801000,
  "originalMessageId": "e6f70819-2b3c-4d5e-9f60-1728394a5b6c",
  "to": "0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9",
  "reason": "TTL_EXPIRED"
}
```

### 19.9 — Errores

```json
{
  "type": "RELAY_ERROR",
  "messageId": "5d6e7f80-91a2-43b4-c5d6-e7f8091a2b3c",
  "timestamp": 1790000200000,
  "errorCode": "MESSAGE_TOO_LARGE",
  "errorMessage": "payload de 70000B excede el maximo de 65535B",
  "originalMessageId": "e6f70819-2b3c-4d5e-9f60-1728394a5b6c"
}
```

```json
{ "type": "AUTH_FAIL", "messageId": "6e7f8091-a2b3-44c5-d6e7-f8091a2b3c4d", "errorCode": "AUTH_FAILED", "errorMessage": "transcript mismatch" }
```

---

## 20. Checklist para el futuro `km daemon`

Cada ítem es verificable contra el código. Los primeros son **bloqueantes**: sin ellos
no hay transporte.

- [ ] Lee frames de texto UTF-8 y parsea JSON de nivel superior.
- [ ] Despacha por `type`; campos desconocidos se ignoran sin romper la conexión.
- [ ] Implementa el flujo `AUTH_REQUEST` → `AUTH_CHALLENGE` → `AUTH_RESPONSE` → `AUTH_OK`.
- [ ] Construye el transcript de 152 B con `TranscriptBuilder`; no lo reimplementa.
- [ ] Verifica la firma con `AuthVerifier`, no con lógica propia.
- [ ] Confirma `protocolVersion` en `AUTH_OK` (§17.2, P7).
- [ ] **Decodifica `data` de forma atómica; jamás parcialmente** (§14).
- [ ] **Entrega `RelayDataEnvelope.payload` sin tocar un byte** (§6.1, §6.2).
- [ ] Aplica anti-spoofing: `envelope.from` = peer autenticado (`RelayServer.kt:337-343`).
- [ ] Aplica el límite de 65 535 B **antes** de rutear (`RelayServer.kt:331-336`).
- [ ] Delega en `RelayServer`; no reimplementa presencia ni store-and-forward.
- [ ] **No implementa semántica de `ACK`** (§11).

---

## 21. Referencias

| Documento | Relación |
|-----------|----------|
| [RFC 6455](https://datatracker.ietf.org/doc/html/rfc6455) | WebSocket |
| [RFC 4648](https://datatracker.ietf.org/doc/html/rfc4648) | Base64 (§4 estándar, §5 URL-safe) |
| [RFC 2119](https://datatracker.ietf.org/doc/html/rfc2119) | Términos normativos |
| KM-0002 | Autenticación, transcripts, `AuthVerifier` |
| KM-0003 | Modelo del relé, presencia, señalización |
| KM-0004 | `SecureFrame` — contenido de `data` |
| KM-0005 | Protocolo de nodo |