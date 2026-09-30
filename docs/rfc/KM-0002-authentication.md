# KM-0002 — Authentication

| Campo | Valor |
|-------|-------|
| **RFC** | KM-0002 |
| **Título** | Authentication |
| **Estado** | Stable — Identidad actualizada |
| **Nota** | La identidad se actualizó para usar KM-ID-0001 como autoridad única. El `identityId` cambió de 40 a 64 caracteres hex. El `deviceId` dejó de ser opaco y pasó a ser criptográficamente derivado. La derivación anterior `first160bits(SHA-256(pubkey))` queda reemplazada. |
| **Versión** | 0.5 |
| **Autor** | KeyMessage Project |
| **Última actualización** | 2026-09-27 |
| **Reemplaza** | KM-0002 v0.4 |
| **Reemplazado por** | — |

---

## 1. Propósito

Este documento define el proceso de autenticación del protocolo KeyMessage.

La autenticación prueba exclusivamente la posesión de una **Identity** criptográfica. No prueba identidad legal, reputación, nombre, correo electrónico ni ningún otro atributo fuera del ámbito de las claves.

Una autenticación exitosa establece una **Session** entre dos **Peers** y negocia los parámetros de la misma mediante **Feature Negotiation**.

> **Nota:** Este RFC define el comportamiento semántico del proceso de autenticación. La representación en JSON, CBOR u otro formato de serialización es una decisión de codificación y no modifica el significado del protocolo.

---

## 2. Alcance

Este documento cubre:

- Los objetivos del proceso de autenticación.
- Lo que la autenticación **no** garantiza.
- El modelo de autenticación y sus invariantes.
- El flujo Challenge–Response.
- La negociación de características durante la autenticación.
- La máquina de estados del proceso.
- Los modos de fallo y sus códigos.
- Las consideraciones de seguridad específicas.
- El formato de serialización de los mensajes de autenticación.

Este documento **NO** cubre:

- La implementación del **Transport** (WebSocket, libp2p, etc.) — definido en RFCs de Transporte.
- El mecanismo de descubrimiento entre **Peers** — definido en RFCs de Transporte y KM-0003.
- La capa de mensajes posterior a la autenticación — definida en KM-0004.
- La generación, rotación o recuperación de claves — definido en KM-IDENTITY-0001.
- Los formatos de serialización alternativos — definidos en KM-0007.

---

## 3. Definiciones

Este documento utiliza los términos definidos en **KM-0000** con el significado allí especificado.

Los términos normativos **MUST**, **MUST NOT**, **SHOULD**, **SHOULD NOT** y **MAY** se interpretan según [RFC 2119](https://datatracker.ietf.org/doc/html/rfc2119).

---

## 4. Goals

El proceso de autenticación persigue los siguientes objetivos:

| # | Objetivo | Descripción |
|---|----------|-------------|
| A1 | **Prueba de posesión** | El **Peer** debe demostrar que posee la clave privada correspondiente a la **Identity** que declara. |
| A2 | **Protección contra replay** | Los mensajes de autenticación no deben poder ser reutilizados por un atacante. |
| A3 | **Feature Negotiation** | Durante la autenticación se negocian los parámetros de la **Session**. |
| A4 | **Identidad criptográfica** | El resultado de la autenticación es una **Identity** verificada, no un nombre ni un perfil. |
| A5 | **Independencia del descubrimiento** | La autenticación funciona igual independientemente del mecanismo de descubrimiento utilizado. |

---

## 5. Non-goals

La autenticación **NO** verifica:

| # | Excluye | Motivo |
|---|---------|--------|
| N1 | Nombre real del usuario | El protocolo no gestiona identidad legal. |
| N2 | Correo electrónico | No existe registro centralizado de usuarios. |
| N3 | Teléfono | No se vincula a ninguna línea telefónica. |
| N4 | Identidad legal | No se verifica documentación oficial. |
| N5 | Reputación o confianza | No existe sistema de reputación en el protocolo base. |
| N6 | Geolocalización | No se verifica ubicación física. |

---

## 6. Authentication Model

### 6.1 — Actores

Una autenticación involucra dos partes:

- **Initiator**: el **Endpoint** que solicita la autenticación.
- **Responder**: el **Endpoint** que recibe y verifica la solicitud.

Cada **Endpoint** **MAY** desempeñar temporalmente el rol de **Initiator** o **Responder** según el flujo del protocolo, independientemente de si actúa como **Peer**, **Relay** o **Discovery Service**.

### 6.2 — Simetría

La autenticación entre dos **Endpoints** **DEBE** ser mutua. Ambos extremos **DEBEN** probar su **Identity** antes de que la **Session** se considere establecida.

En el caso de autenticación contra un **Relay**, la simetría aplica únicamente si el **Relay** posee una **Identity** que deba ser verificada por el **Client**.

### 6.3 — sessionId

El **sessionId** es un identificador único de **Session** generado por el **Responder**. **DEBE** tener exactamente 160 bits de entropía, representados como 40 caracteres hexadecimales en minúsculas ASCII. El algoritmo de generación **DEBE** ser criptográficamente seguro.

### 6.4 — Formato de serialización

Este RFC define el comportamiento semántico del proceso de autenticación. La representación en JSON, CBOR u otro formato de serialización es una decisión de codificación y no modifica el significado del protocolo.

---

## 7. Authentication Invariants

Las siguientes propiedades **DEBEN** mantenerse en todas las versiones del protocolo:

| # | Invariante | Descripción |
|---|------------|-------------|
| AI1 | **Una Session, una autenticación** | Toda **Session** **DEBE** derivarse de una única autenticación exitosa. No es posible crear una **Session** sin autenticación. |
| AI2 | **Autenticación produce Identity** | Toda autenticación exitosa produce una **Identity** verificada. |
| AI3 | **Identity inmutable** | Una **Identity** autenticada nunca cambia durante la **Session**. |
| AI4 | **Protección contra replay** | Toda autenticación **DEBE** incluir protección contra **Replay Attacks**. |
| AI5 | **Feature Negotiation** | Toda autenticación **DEBE** negociar los parámetros de la **Session**. |
| AI6 | **Autenticación fallida no produce Session** | Una autenticación fallida **NO DEBE** crear una **Session**. |
| AI7 | **Timeout** | Toda autenticación **DEBE** tener un tiempo máximo de finalización. |
| AI8 | **Simetría** | En una autenticación entre dos **Peers**, ambos extremos **DEBEN** autenticarse mutuamente. |
| AI9 | **SessionId único** | Toda **Session** exitosa posee un **sessionId** único generado por el **Responder**. |

---

## 8. Authentication Flow

El flujo de alto nivel de una autenticación es el siguiente. La autenticación **DEBE** ser mutua: ambos extremos **DEBEN** probar su **Identity**. El diagrama muestra la fase unidireccional (Initiator → Responder); una vez completada, los roles se invierten o se ejecuta un segundo Challenge–Response simétrico antes de pasar a **SESSION_NEGOTIATION**.

```
Initiator                          Responder
    │                                  │
    │  ① Connect                       │
    │─────────────────────────────────>│
    │                                  │
    │  ② Challenge (nonce, timestamp,  │
    │     responderIdentityId)          │
    │<─────────────────────────────────│
    │                                  │
    │  ③ Response (identityId,         │
    │     publicKey, deviceId,          │
    │     signature, features)          │
    │─────────────────────────────────>│
    │                                  │
    │  ④ Verification                  │
    │                                  │
    │  ⑤ AuthResult (OK / FAIL)        │
    │<─────────────────────────────────│
    │                                  │
    │  ⑥ Reverse Challenge–Response    │
    │     (mismo esquema, roles        │
    │      invertidos)                 │
    │<════════════════════════════════>│
    │                                  │
    │  ⑦ Session Parameters            │
    │<════════════════════════════════>│
    │                                  │
```

> **Nota:** Los pasos ② a ⑤ autentican al **Initiator** frente al **Responder**. Para la autenticación mutua (paso ⑥), el mismo intercambio se repite con los roles invertidos: el **Responder** pasa a ser **Initiator** y viceversa. Una implementación **MAY** ejecutar ambos intercambios en paralelo o en secuencia, siempre que ambos extremos queden autenticados antes de pasar a **SESSION_NEGOTIATION**. El diseño concreto del intercambio paralelo está fuera del alcance de este RFC.

### ① Connect

El **Initiator** establece una **Connection** con el **Responder** a través del **Transport** seleccionado.

### ② Challenge

El **Responder** genera un **Challenge** que incluye:

- Un **Nonce** aleatorio de al menos 128 bits.
- Un **timestamp** de generación.
- Su **responderIdentityId**: la identidad del **Responder**, para que el **Initiator** sepa contra qué identidad está autenticando.

El **Challenge** se envía al **Initiator**.

### ③ Response

El **Initiator** construye una **Response** que incluye:

- Su **identityId** y clave pública.
- Su **deviceId** (ver §9.4).
- Una firma sobre el **Authentication Transcript**.
- Su **Protocol Version**.
- Sus **Capabilities** y parámetros de **Feature Negotiation**.

### ④ Verification

El **Responder** verifica:

1. Que el **identityId** se corresponda con la clave pública recibida, según el algoritmo definido en KM-IDENTITY-0001.
2. Que la firma sobre el **Authentication Transcript** sea válida.
3. Que el **timestamp** esté dentro de la ventana de tolerancia.
4. Que el **Nonce** no haya sido utilizado previamente.
5. Que los parámetros negociados sean compatibles.

### ⑤ AuthResult

El **Responder** envía el resultado al **Initiator**:

- **AUTH_OK**: autenticación exitosa, incluye **sessionId**.
- **AUTH_FAIL**: autenticación fallida, incluye código de error.

Los parámetros específicos del **Transport** (como credenciales **TURN**) se intercambian en mensajes posteriores a **AUTH_OK**, definidos en los RFC de Transporte correspondientes.

---

## 9. Authentication Transcript

Para garantizar que todas las implementaciones calculen la firma de forma idéntica, la firma se calcula sobre el **Authentication Transcript**.

> **Nota:** El `timestamp` que aparece en el transcript es exclusivamente el valor del campo `timestamp` **dentro** del payload de `AUTH_CHALLENGE` (§10.1). El `timestamp` de la cabecera común del `Protocol Message` **MUST NOT** participar en ningún cálculo criptográfico durante la autenticación.

### 9.1 — Definición del Transcript

El **Authentication Transcript** es la concatenación en bytes de los siguientes campos, en este orden:

| Orden | Campo | Tipo | Tamaño |
|-------|-------|------|--------|
| 1 | nonce | bytes (raw) | 16 bytes (128 bits) |
| 2 | timestamp | uint64 big-endian | 8 bytes |
| 3 | responderIdentityId | ASCII | 64 bytes |
| 4 | identityId | ASCII | 64 bytes |

### 9.2 — Cálculo

```
transcript = nonce_bytes || uint64_be(timestamp) || responderIdentityId_ascii || identityId_ascii
signature = Ed25519.sign(privateKey, transcript)
```

Donde:

- `nonce_bytes`: los 16 bytes del nonce, sin codificación Base64.
- `uint64_be(timestamp)` : timestamp Unix en milisegundos, como entero de 64 bits en big-endian. Este timestamp es el valor del campo `timestamp` dentro del **Challenge**, no el `timestamp` de la cabecera común del mensaje. Ambos **DEBEN** coincidir.
- `responderIdentityId_ascii` : los 64 caracteres hexadecimales del responderIdentityId, codificados en ASCII (64 bytes).
- `identityId_ascii` : los 64 caracteres hexadecimales del **identityId** del **Initiator**, según KM-ID-0001 §6, codificados en ASCII (64 bytes).

El transcript completo mide por tanto:

```
16 + 8 + 64 + 64 = 152 bytes
```

contra los 104 bytes de la versión 0.4. Esto se debe al cambio de 40 a 64 caracteres en ambos identityId (véase el historial).

### 9.3 — Verificador de compatibilidad

Dos implementaciones **DEBEN** producir exactamente el mismo **transcript** en bytes para los mismos valores de entrada. Si el transcript difiere, la firma será inválida.

### 9.4 — deviceId

El campo `deviceId` identifica unívocamente al dispositivo físico o lógico que el **Initiator** declara. **NO** es opaco: se deriva criptográficamente de la clave de firma Ed25519 del dispositivo, según KM-ID-0001 §6.

Si el **Initiator** tiene un solo dispositivo, el `deviceId` **DEBE** ser:

```
deviceId = SHA-256("KM-ID-DEVICE" || deviceSigningPublicKey)
```

representado como 64 caracteres hexadecimales.

| Restricción | Valor |
|-------------|-------|
| **Tamaño** | 32 bytes (64 caracteres hex) |
| **Derivación** | `SHA-256("KM-ID-DEVICE" || deviceSigningPublicKey)` |
| **Participación criptográfica** | No firma ni se incluye en el **Authentication Transcript** |
| **Participación en el roster** | El `deviceId` aparece en el DeviceRoster de la identidad, firmado por la identidad raíz (§KM-ID-0001 §10) |

Si el **Initiator** tiene múltiples dispositivos, el `deviceId` que declara en `AUTH_RESPONSE` **DEBE** coincidir con una entrada de su DeviceRoster. En ese caso, la verificación puede hacerse contra el roster firmado en el ContactBundle (véase KM-0006).

---

## 10. Challenge–Response

### 10.1 — Challenge

El **Challenge** **DEBE** contener:

| Campo | Requisito | Descripción |
|-------|-----------|-------------|
| nonce | MUST | Valor aleatorio de al menos 128 bits, codificado en Base64URL. |
| timestamp | MUST | Tiempo Unix en milisegundos (epoch) del momento de generación. Este timestamp es el único que se utiliza en el **Authentication Transcript** (§9). |
| version | MUST | Versión del protocolo soportada por el **Responder**. |
| responderIdentityId | MUST | IdentityId del **Responder** (64 caracteres hex, según KM-ID-0001). |

> **Nota:** El `responderIdentityId` incluido en el **Challenge** es una **identidad declarada** por el **Responder**. Su autenticidad **no queda demostrada** hasta que el **Responder** complete su parte de la autenticación mutua (firmando el **Server Authentication Transcript** en §10.4). Hasta entonces, el **Initiator** solo sabe que alguien afirma ser esa identidad.

### 10.2 — Response

La **Response** **DEBE** contener:

| Campo | Requisito | Descripción |
|-------|-----------|-------------|
| identityId | MUST | Identificador de la **Identity** del **Initiator** (64 caracteres hex, según KM-ID-0001 §6). |
| publicKey | MUST | Clave pública correspondiente al identityId (Base64URL). |
| signature | MUST | Firma Ed25519 sobre el **Authentication Transcript** (§9). |
| protocolVersion | MUST | Versión del protocolo implementada por el **Initiator**. |
| capabilities | SHOULD | Lista de **Capabilities** soportadas. |
| serialization | MAY | Formatos de serialización aceptados (definido en KM-0007). |
| compression | MAY | Algoritmos de compresión soportados (definido en KM-0007). |
| cryptoSuites | MAY | Suites criptográficas soportadas (definido en KM-0007). |
| extensions | MAY | Parámetros de extensión específicos. |

### 10.3 — AuthResult

| Campo | Requisito | Descripción |
|-------|-----------|-------------|
| type | MUST | `AUTH_OK` o `AUTH_FAIL`. |
| sessionId | MUST (OK) | Identificador único de la **Session** generado por el **Responder**. |
| serverNonce | MUST (OK) | Nuevo nonce del servidor (128 bits, codificado en Base64URL) para futuros mensajes de la **Session**. |
| signature | MUST (OK) | Firma Ed25519 del **Responder** sobre el **Server Authentication Transcript** (§10.4). |
| errorCode | MUST (FAIL) | Código de error según §13 (Failure Modes). |
| errorMessage | SHOULD (FAIL) | Mensaje legible del error. |

### 10.4 — Server Authentication Transcript

Para que el **Initiator** verifique la autenticidad del resultado, el **Responder** **DEBE** firmar el **Server Authentication Transcript**. Este transcript demuestra que el **Responder** acepta la **Session** y vincula el resultado a la identidad del **Initiator**.

#### 10.4.1 — Definición del Transcript

El **Server Authentication Transcript** es la concatenación en bytes de los siguientes campos, en este orden:

| Orden | Campo | Tipo | Tamaño |
|-------|-------|------|--------|
| 1 | sessionId | ASCII | 40 bytes (§6.3) |
| 2 | serverNonce | bytes (raw) | 16 bytes (128 bits) |
| 3 | initiatorIdentityId | ASCII | 64 bytes (§9.2) |

#### 10.4.2 — Cálculo

```
server_transcript = sessionId_ascii || serverNonce_bytes || initiatorIdentityId_ascii
ok_signature = Ed25519.sign(responderPrivateKey, server_transcript)
```

Donde:

- `sessionId_ascii`: el `sessionId` en ASCII (40 caracteres hex).
- `serverNonce_bytes`: los 16 bytes del `serverNonce`, decodificados de Base64URL.
- `initiatorIdentityId_ascii`: el **identityId** del **Initiator** en ASCII (64 caracteres hex, §9.2).

El **Server Authentication Transcript** mide por tanto:

```
40 + 16 + 64 = 120 bytes
```

contra los 96 bytes de la versión 0.4.

#### 10.4.3 — Verificación

El **Initiator** **DEBE** verificar:

1. Que `sessionId` no esté vacío y tenga formato válido.
2. Que la firma `ok_signature` sea válida contra la clave pública del **Responder** (obtenida del **Challenge** o de un medio externo).
3. Que `serverNonce` tenga el tamaño correcto (128 bits).

El `serverNonce` no es un mecanismo de protección contra replay de **AUTH_OK**. Su propósito es servir como semilla criptográfica para mensajes posteriores de la **Session**, según lo definido en RFCs posteriores. El **Initiator** **MUST** almacenar el `serverNonce` asociado a la **Session** para ese fin.

Si la verificación falla, el **Initiator** **DEBE** tratar el **AUTH_OK** como inválido y cerrar la **Connection** con el código `INVALID_AUTH_OK_SIGNATURE`.

---

## 11. Feature Negotiation

Durante la autenticación, los **Peers** **DEBEN** negociar los parámetros de la **Session**.

La negociación **DEBE** ocurrir en la **Response** y **MAY** continuar en mensajes posteriores antes de que la **Session** pase a **READY**.

### Parámetros negociables

| Parámetro | Comportamiento si no hay acuerdo |
|-----------|----------------------------------|
| **Protocol Version** | Se usa la versión menor común. Si no hay solapamiento, la autenticación **DEBE** fallar. |
| **Capabilities** | Cada **Peer** usa la intersección de las capacidades declaradas. |
| **Serialization** | Se usa el formato común de mayor prioridad. Si no hay formato común, la autenticación **DEBE** fallar. |
| **Compression** | Se usa el algoritmo común de mayor prioridad. La compresión es opcional. |
| **Crypto Suites** | Se usa el conjunto común de mayor prioridad. |

Los parámetros `serialization`, `compression` y `cryptoSuites` se definen en KM-0007. Este RFC define únicamente la semántica de su negociación.

### Reglas

1. Un **Peer** **DEBE** ignorar cualquier parámetro que no reconozca.
2. Un **Peer** **NO DEBE** asumir que otro **Peer** soporta una característica no negociada.
3. Si no es posible llegar a un acuerdo en un parámetro obligatorio, la autenticación **DEBE** fallar con el código `NEGOTIATION_FAILED`.
4. En las listas de parámetros negociables (`serialization`, `compression`, `cryptoSuites`), el primer elemento representa la prioridad más alta. El **Responder** **DEBE** seleccionar el primer elemento de la intersección de ambas listas según el orden del **Initiator**.

---

## 12. Authentication State Machine

```
         ┌──────────┐
         │ CONNECTED│
         └────┬─────┘
              │ challenge sent
              ▼
         ┌──────────┐
         │  WAIT_   │
         │ RESPONSE │
         └────┬─────┘
              │ response received
              ▼
         ┌──────────┐
         │ VERIFYING│
         └────┬─────┘
       ┌──────┴──────┐
       ▼              ▼
  ┌──────────┐  ┌──────────┐
  │AUTHENTIC.│  │  FAILED  │
  └────┬─────┘  └────┬─────┘
       │              │
       ▼              ▼
  ┌────────────┐ ┌──────────┐
  │  SESSION_  │ │  CLOSED  │
  │NEGOTIATION │ └──────────┘
  └─────┬──────┘
        │ negotiation complete
        ▼
  ┌──────────┐
  │  READY   │
  └──────────┘
```

| Estado | Descripción |
|--------|-------------|
| **CONNECTED** | **Connection** establecida a nivel **Transport**. |
| **WAIT_RESPONSE** | **Challenge** enviado, esperando **Response** del otro extremo. |
| **VERIFYING** | **Response** recibida, verificando firma, nonce y parámetros. |
| **AUTHENTICATED** | Autenticación exitosa del **Initiator**. **Session** creada con **sessionId**. Pendiente de autenticación recíproca (mutual auth). |
| **SESSION_NEGOTIATION** | Autenticación mutua completada. Intercambiando parámetros finales de la **Session** (ej. credenciales TURN, parámetros de transporte). |
| **FAILED** | Autenticación fallida. La **Connection** **DEBE** cerrarse. |
| **READY** | **Session** completamente establecida y lista para intercambiar **Messages**. |

### Transiciones de error

- Desde **CONNECTED**: error de **Transport** → **CLOSED**.
- Desde **WAIT_RESPONSE**: timeout → **FAILED** con código `TIMEOUT`.
- Desde **VERIFYING**: firma inválida, nonce repetido o negociación fallida → **FAILED**.
- Desde **FAILED**: **DEBE** cerrarse la **Connection**. No **MAY** reintentar sin una nueva **Connection**.

---

## 13. Failure Modes

| Código | Descripción | Causa probable |
|--------|-------------|----------------|
| `INVALID_SIGNATURE` | La firma no corresponde a la clave pública declarada. | Clave privada incorrecta o mensaje modificado. |
| `UNKNOWN_PROTOCOL` | La versión del protocolo no es compatible. | El **Client** usa una versión que el otro extremo no soporta. |
| `TIMEOUT` | La autenticación no se completó en el tiempo máximo. | Conexión lenta, ataque DoS, o **Client** malicioso. |
| `INVALID_TIMESTAMP` | El timestamp está fuera de la ventana de tolerancia. | Relojes desincronizados o **Replay Attack**. |
| `INVALID_NONCE` | El nonce fue reutilizado. | **Replay Attack** detectado. |
| `UNSUPPORTED_CAPABILITY` | Se requiere una **Capability** obligatoria no soportada. | Versiones incompatibles del protocolo. |
| `UNSUPPORTED_SERIALIZATION` | No hay formato de serialización común. | Definido en KM-0007. |
| `UNSUPPORTED_CRYPTO_SUITE` | No hay suite criptográfica común. | Definido en KM-0007. |
| `UNSUPPORTED_COMPRESSION` | No hay algoritmo de compresión común. | Definido en KM-0007. |
| `MALFORMED_MESSAGE` | El mensaje no pudo ser parseado. | JSON inválido, campos faltantes o tamaño excedido. |
| `INVALID_IDENTITY` | El identityId no corresponde a la clave pública. | Error de generación de identidad o ataque de suplantación. |
| `INVALID_PUBLIC_KEY` | La clave pública no es válida para el algoritmo esperado. | Formato incorrecto o clave corrupta. |
| `INVALID_CHALLENGE` | El challenge no es válido o ha expirado. | El **Initiator** respondió a un challenge antiguo. |
| `REPLAY_DETECTED` | Se detectó un intento de replay. | Nonce repetido o timestamp fuera de ventana. |
| `NEGOTIATION_FAILED` | No se pudo acordar un parámetro obligatorio. | No hay versión común, formato común o suite criptográfica común. |
| `RATE_LIMITED` | El **Peer** superó el límite de intentos de autenticación. | Ataque de fuerza bruta o error de configuración. |
| `SESSION_ALREADY_EXISTS` | Ya existe una **Session** activa para esta **Identity**. | Se intentó autenticar una **Identity** ya en sesión. |
| `INVALID_AUTH_OK_SIGNATURE` | La firma de **AUTH_OK** no es válida. | El **Responder** envió un **AUTH_OK** con firma incorrecta o el mensaje fue modificado. |
| `MESSAGE_TOO_LARGE` | El mensaje excede el tamaño máximo permitido (16 KiB). | Payload excesivo o ataque de consumo de memoria. |

---

## 14. Security Considerations

### 14.1 — Replay Attack

El uso de **Nonce** + **timestamp** previene **Replay Attacks**.

El **Responder** **DEBE** mantener un registro de **Nonces** utilizados durante la ventana de tolerancia y rechazar cualquier **Nonce** repetido.

### 14.2 — Man-in-the-Middle (MITM)

La autenticación basada en firma de clave pública previene ataques MITM siempre que el **Initiator** tenga la clave pública correcta del **Responder**.

El mecanismo de distribución inicial de claves públicas está fuera del alcance de este RFC (ver KM-IDENTITY-0001).

### 14.3 — Downgrade Attack

La **Protocol Version** se negocia en la **Response** y **NO** forma parte del **Authentication Transcript**. La protección contra ataques de downgrade se basa en que el **Responder** **DEBE** verificar que la versión declarada por el **Initiator** sea la máxima compatible entre ambos extremos. Si un atacante modifica la versión en tránsito, la negociación resultará en una versión distinta que el **Responder** **DEBE** detectar y rechazar.

Un atacante no puede forzar el uso de una versión inferior porque la versión aceptada es verificada por el **Responder** contra sus propias capacidades declaradas.

### 14.4 — Nonce Reuse

El **Nonce** **DEBE** tener al menos 128 bits de entropía generados criptográficamente.

El **Responder** **DEBE** rechazar cualquier **Response** con un **Nonce** que ya haya sido procesado dentro de la ventana de tolerancia.

### 14.5 — Clock Skew

El **Responder** **DEBE** rechazar autenticaciones cuya diferencia de reloj supere la ventana de tolerancia definida en §15.

### 14.6 — Brute Force

El **Responder** **DEBE** implementar rate limiting por **Identity** y por **Endpoint**.

### 14.7 — Resource Exhaustion

El **Responder** **DEBE** limitar el número de autenticaciones concurrentes para evitar ataques de denegación de servicio.

---

## 15. Time Requirements

Este RFC separa los requisitos normativos de los valores recomendados.

### 15.1 — Requisitos normativos (MUST)

| Requisito | Descripción |
|-----------|-------------|
| **Timeout de autenticación** | Una implementación **DEBE** imponer un tiempo máximo entre el envío del **Challenge** y la recepción de la **Response**. |
| **Ventana de tolerancia de reloj** | Una implementación **DEBE** rechazar autenticaciones con diferencia horaria superior a un límite configurable. |
| **Tamaño mínimo de Nonce** | El **Nonce** **DEBE** tener al menos 128 bits de entropía. |
| **Protección contra replay** | Una implementación **DEBE** detectar y rechazar **Nonces** repetidos dentro de la ventana de tolerancia. |

### 15.2 — Valores recomendados (SHOULD)

| Parámetro | Valor recomendado | Notas |
|-----------|-------------------|-------|
| **Timeout de autenticación** | 10 s | Desde Challenge hasta Response. |
| **Ventana de tolerancia de reloj** | ±30 s | Diferencia máxima aceptable. |
| **Intentos máximos de autenticación** | 5 / min | Por **Endpoint** o por **Identity**. |
| **Timeout de conexión sin autenticar** | 10 s | Desde que se establece la **Connection**. |

### 15.3 — Límites de tamaño

Los siguientes límites **SON NORMATIVOS**:

| Campo | Tamaño máximo | Descripción |
|-------|---------------|-------------|
| identityId | 64 bytes | Cadena hexadecimal (32 bytes raw). |
| deviceId | 64 bytes | Cadena hexadecimal del deviceId derivado del dispositivo (32 bytes raw). |
| publicKey | 2048 bytes | En formato serializado (Base64URL). |
| signature | 512 bytes | Incluye algoritmo y payload. |
| capabilities | 64 elementos | Número máximo de **Capabilities** por mensaje. |
| extension key | 64 bytes | Clave de extensión. |
| extension value | 256 bytes | Valor de extensión. |
| nonce (codificado) | 128 bytes | En codificación Base64URL. |

---

## 16. General Serialization Requirements

### 16.1 — Codificación Base64URL

Cuando este RFC especifica codificación en **Base64URL**, se refiere a la codificación definida en [RFC 4648 §5](https://datatracker.ietf.org/doc/html/rfc4648#section-5) (URL-safe Base64), **SIN** padding (`=`). Una implementación **DEBE** rechazar un valor Base64URL que contenga caracteres no válidos o padding.

### 16.2 — Campos duplicados en JSON

Una implementación **DEBE** rechazar cualquier mensaje que contenga campos duplicados en su representación JSON.

### 16.3 — Tamaño máximo de mensaje de autenticación

Un mensaje de autenticación completo serializado **NO DEBE** exceder los 16 KiB (16 384 bytes). Una implementación **DEBE** rechazar mensajes que superen este límite con el código `MESSAGE_TOO_LARGE`.

---

## 17. Wire Protocol

Esta sección define el formato de serialización de los mensajes de autenticación para la versión actual del protocolo.

El formato actual es **JSON**. Una implementación **MAY** soportar otros formatos de serialización, y negociarlos durante **Feature Negotiation**.

### 17.1 — Cabecera común de Protocol Message

Todos los mensajes de autenticación **DEBEN** incluir los siguientes campos de cabecera común:

| Campo | Requisito | Descripción |
|-------|-----------|-------------|
| type | MUST | Tipo del mensaje. |
| messageId | MUST | Identificador único del mensaje (UUIDv7 recomendado, **SHOULD** ser globalmente único incluso tras reinicios). No participa en ninguna firma criptográfica. El receptor **MUST NOT** utilizar `messageId` como mecanismo de autenticación ni de protección contra **Replay Attacks**. |
| timestamp | MUST | Tiempo Unix en milisegundos del momento de envío. **MUST NOT** participar en ningún cálculo criptográfico durante la autenticación. El único `timestamp` utilizado durante la autenticación es el campo `timestamp` contenido en el payload de `AUTH_CHALLENGE`, definido en §10.1. |
| protocolVersion | MUST | Versión del protocolo utilizada. |

### 17.2 — AUTH_CHALLENGE (Responder → Initiator)

```json
{
  "type": "AUTH_CHALLENGE",
  "messageId": "0190f5a2-3b4c-7d8e-9f01-23456789abcd",
  "timestamp": 1721827200000,
  "protocolVersion": "2.0",
  "nonce": "aB3dEfGhIjKlMnOpQrStUvWxYz123456",
  "version": "3.0",
  "responderIdentityId": "f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0a1b2c3d4e5f6a7b8c9d0e1f2"
}
```

### 17.3 — AUTH_RESPONSE (Initiator → Responder)

```json
{
  "type": "AUTH_RESPONSE",
  "messageId": "0190f5a2-4c5d-6e7f-8a9b-0123456789ab",
  "timestamp": 1721827200500,
  "protocolVersion": "2.0",
  "identityId": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2",
  "publicKey": "MCowBQYDK2VwAyEA...",
  "signature": "MEQCIHl6k...",
  "deviceId": "f1e2d3c4b5a6f7e8d9c0b1a2f3e4d5c6a7b8f9e0d1c2b3a4f5e6d7c8b9a0f1e2",
  "capabilities": ["ack", "ping", "relay"],
  "serialization": ["json", "cbor"],
  "compression": ["none", "zstd"],
  "extensions": {}
}
```

### 17.4 — AUTH_OK (Responder → Initiator)

```json
{
  "type": "AUTH_OK",
  "messageId": "0190f5a2-5d6e-7f8a-9b01-234567890abc",
  "timestamp": 1721827201000,
  "protocolVersion": "2.0",
  "identityId": "f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0a1b2c3d4e5f6a7b8c9d0e1f2",
  "sessionId": "b0a9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1",
  "serverNonce": "zYxWvUtSrQpOnMlKjIhGfEdCbA987654321",
  "signature": "MEUCIQD..."
}
```

> Los parámetros específicos del **Transport** (como credenciales **TURN**) se intercambian en un mensaje posterior `SESSION_PARAMETERS`, definido en los RFC de Transporte correspondientes.

### 17.5 — AUTH_FAIL (Responder → Initiator)

```json
{
  "type": "AUTH_FAIL",
  "messageId": "0190f5a2-6e7f-8a9b-0123-45678901abcd",
  "timestamp": 1721827201000,
  "protocolVersion": "2.0",
  "errorCode": "INVALID_SIGNATURE",
  "errorMessage": "Signature does not match public key"
}
```

---

## 18. Referencias

| Ref | Documento |
|-----|-----------|
| [KM-0000] | KM-0000 — Terminology |
| [KM-0001] | KM-0001 — Protocol Architecture Overview |
| [KM-0007] | KM-0007 — Binary Format and Serialization |
| [KM-IDENTITY-0001] | KM-IDENTITY-0001 — Identity Model |
| [KM-ID-0001] | KM-ID-0001 — Identity, Devices and Contact Bundles |
| [RFC 2119] | [Key words for use in RFCs](https://datatracker.ietf.org/doc/html/rfc2119) |
| [RFC 4648] | [Base64URL encoding](https://datatracker.ietf.org/doc/html/rfc4648#section-5) |

---

## 19. Historial

| Versión | Fecha | Cambios |
|---------|-------|---------|
| 0.5 | 2026-09-27 | **Actualización de identidad para KM-ID-0001**: identityId pasa de 40 a 64 caracteres hex (32 bytes SHA-256 con separador de dominio). deviceId deja de ser opaco y se deriva criptográficamente de la clave de firma del dispositivo (64 hex). Authentication Transcript crece de 104 a 152 bytes. Server Authentication Transcript crece de 96 a 120 bytes. ResponderIdentityId en Challenge actualizado. Ejemplos wire actualizados. Se añade referencia a KM-ID-0001. Derivación anterior `first160bits(SHA-256(pubkey))` queda reemplazada. Versión 0.4 queda reemplazada. |
| 0.4 | 2026-07-24 | Eliminado protocolVersion del Authentication Transcript. Añadida firma en AUTH_OK (Server Authentication Transcript). Formalizado Server Authentication Transcript con tabla y serverNonce como bytes raw (decodificado de Base64URL). Añadido MESSAGE_TOO_LARGE e INVALID_AUTH_OK_SIGNATURE. Añadidas reglas de serialización (Base64URL sin padding, campos duplicados MUST reject, tamaño máximo 16 KiB). Añadida regla de prioridad en listas de negociación. Clarificado que el receptor MUST NOT usar messageId como protección contra replay. Resuelta ambigüedad de timestamps (Transcript usa el timestamp del Challenge). Añadida nota: responderIdentityId es identidad declarada hasta autenticación mutua. |
| 0.3 | 2026-07-24 | Corregido AI1 (menos restrictivo). Añadido responderIdentityId a Challenge y al Transcript. Añadido deviceId (§9.4). Añadido protocolVersion al Transcript. Añadido SESSION_NEGOTIATION a la máquina de estados. Aclarado que messageId no participa en firmas. Añadido flujo de autenticación mutua. |
| 0.2 | 2026-07-24 | Desacoplado modelo de relay. Añadido Authentication Transcript (§9). Añadido sessionId. Eliminado TURN de AUTH_OK. Separados valores normativos de recomendados (§15). Añadidos códigos de error UNSUPPORTED_SERIALIZATION, UNSUPPORTED_CRYPTO_SUITE, UNSUPPORTED_COMPRESSION, INVALID_PUBLIC_KEY, INVALID_CHALLENGE, SESSION_ALREADY_EXISTS. Añadida cabecera común de Protocol Message. Añadidos límites de tamaño. |
| 0.1 | 2026-07-24 | Documento inicial — modelo de autenticación, invariantes, Challenge–Response, Feature Negotiation, máquina de estados, Failure Modes, Wire Protocol |
