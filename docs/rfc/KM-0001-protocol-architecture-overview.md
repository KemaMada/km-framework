# KM-0001 — Protocol Architecture Overview

| Campo | Valor |
|-------|-------|
| **RFC** | KM-0001 |
| **Título** | Protocol Architecture Overview |
| **Estado** | Draft |
| **Versión** | 0.1 |
| **Autor** | KeyMessage Project |
| **Última actualización** | 2026-07-24 |
| **Reemplaza** | — |
| **Reemplazado por** | — |

---

## 1. Propósito

Este documento define la arquitectura del protocolo KeyMessage: sus objetivos, principios, capas, actores y ciclo de vida.

Es el punto de partida para cualquier implementación del protocolo. Describe el alcance y los límites de la especificación, pero no define los detalles concretos de autenticación, transporte, mensajería o almacenamiento, que corresponden a RFC posteriores.

---

## 2. Alcance

Este documento cubre:

- Los objetivos del protocolo.
- Lo que el protocolo **no** define (Non-goals).
- Los principios arquitectónicos que guían todas las decisiones de diseño.
- Las capas del sistema.
- Las invariantes del protocolo.
- El modelo de comunicación de alto nivel.
- El ciclo de vida de una interacción entre **Peers**.
- Las políticas de compatibilidad, feature negotiation y extensibilidad.
- El modelo de seguridad y las consideraciones de seguridad transversales.

Este documento **NO** cubre:

- El algoritmo de autenticación (definido en KM-0002).
- El protocolo de relay (definido en KM-0003).
- La capa de mensajes y ACK (definida en KM-0004).
- Grupos (definidos en KM-0005).
- Voz (definida en KM-0006).
- Formato binario (definido en KM-0007).
- La DHT (definida en KM-DHT-0001).
- El modelo de identidad (definido en KM-IDENTITY-0001).

---

## 3. Definiciones

Este documento utiliza los términos definidos en **KM-0000** con el significado allí especificado.

Los términos normativos **MUST**, **MUST NOT**, **SHOULD**, **SHOULD NOT** y **MAY** se interpretan según [RFC 2119](https://datatracker.ietf.org/doc/html/rfc2119).

---

## 4. Goals

El protocolo KeyMessage persigue los siguientes objetivos:

| # | Objetivo | Descripción |
|---|----------|-------------|
| G1 | **Comunicación P2P cifrada** | Dos **Peers** deben poder intercambiar **Messages** sin que ningún intermediario pueda leer su contenido. |
| G2 | **Descubrimiento mínimo** | El protocolo debe proporcionar un mecanismo para que dos **Peers** se encuentren y negocien una conexión directa. |
| G3 | **Independencia del transporte** | El protocolo debe funcionar sobre cualquier **Transport** que garantice entrega bidireccional de bytes. |
| G4 | **Offline first** | Los **Clients** deben poder encolar **Messages** localmente y entregarlos cuando se restablezca la conectividad. |
| G5 | **Identidad criptográfica** | Cada **Identity** se define exclusivamente por su par de claves. No existe un sistema de cuentas centralizado. |
| G6 | **Feature Negotiation** | **Protocol Version**, **Capabilities**, formatos y algoritmos se negocian dinámicamente entre **Peers** sin romper compatibilidad. |
| G7 | **Interoperabilidad** | Cualquier implementación que cumpla los RFC correspondientes debe poder comunicarse con cualquier otra. |

---

## 5. Non-goals

El protocolo KeyMessage explícitamente **NO** define:

| # | Excluye | Motivo |
|---|---------|--------|
| N1 | La implementación del **Transport** (WebSocket, libp2p, Bluetooth, etc.) | El protocolo es independiente del transporte. Cada **Transport** se especifica por separado. |
| N2 | La implementación del cifrado interno (algoritmos, modos, derivación de claves) | Pertenece a KM-IDENTITY-0001 y a la capa de **Crypto**. |
| N3 | La interfaz de usuario | El protocolo especifica comportamiento entre **Clients**, no experiencia de usuario. |
| N4 | El sistema operativo o plataforma | Cualquier sistema que pueda implementar el protocolo es un **Client** válido. |
| N5 | El almacenamiento local (**Persistent Storage**, **Transient Cache**) | Cada **Client** gestiona su propio **Storage**. |
| N6 | El formato de los **Attachments** | El protocolo transporta referencias a **Attachments**, no define códecs. |
| N7 | La mensajería grupal (definida en KM-0005) | Se especifica como una extensión opcional. |

---

## 6. Out of Scope

El protocolo KeyMessage no aborda los siguientes temas, aunque estén relacionados funcionalmente:

| # | Fuera de alcance | Motivo |
|---|------------------|--------|
| O1 | Interfaz gráfica de usuario | El protocolo especifica comportamiento entre **Clients**, no experiencia visual. |
| O2 | Políticas de moderación | Cada **Client** o comunidad define sus propias políticas. |
| O3 | Almacenamiento de **Attachments** | El protocolo transporta referencias, no gestiona repositorios. |
| O4 | Descubrimiento mediante código QR | Es uno de múltiples mecanismos posibles, no parte de la especificación. |
| O5 | Sincronización multidispositivo | Definido en KM-IDENTITY-0001 como una extensión del modelo de identidad. |
| O6 | Push notifications | Dependen del sistema operativo y están fuera del alcance del protocolo. |
| O7 | Cifrado de extremo a extremo en grupos | La mensajería grupal (KM-0005) definirá su propio modelo de cifrado. |

---

## 7. Architectural Principles

Toda decisión de diseño en el protocolo KeyMessage **DEBE** respetar los siguientes principios:

### P0 — Protocol Before Implementation

Ninguna decisión de implementación **PUEDE** modificar el comportamiento definido por la especificación.

Primero se define el protocolo; luego las implementaciones se adaptan a él.

### P1 — Transport Agnostic (Independencia del Transporte)

El protocolo **DEBE** funcionar sobre cualquier **Transport** que proporcione entrega bidireccional de bytes ordenada o no ordenada.

Un **Client** **MAY** implementar múltiples **Transports** simultáneamente.

### P2 — End-to-End Encryption (Cifrado Extremo a Extremo)

El contenido de los **User Messages** **DEBE** ser cifrado por el emisor y descifrado por el destinatario.

Ningún intermediario (**Relay**, **TURN Server**, ISP) **DEBE** poder descifrar el contenido.

### P3 — Decentralization First (Descentralización Prioritaria)

El protocolo **DEBE** minimizar la dependencia de infraestructura centralizada.

El **Relay** existe únicamente como mecanismo de descubrimiento y señalización.

Un mecanismo de descubrimiento **MAY** requerir un **Relay**, pero la autenticación forma parte del protocolo, independientemente del mecanismo de descubrimiento utilizado.

La función del **Relay** **DEBE** limitarse a:

- Intercambiar señalización.
- Emitir credenciales temporales para **TURN**.

El **Relay** **NO DEBE** almacenar **User Messages**.

### P4 — Offline First (Prioridad Offline)

Un **Client** **DEBE** poder funcionar sin conexión permanente a la red.

Los **Messages** **DEBEN** encolarse localmente y entregarse cuando se restablezca la conectividad.

### P5 — Interoperability (Interoperabilidad)

Cualquier implementación conforme a los RFC **DEBE** poder comunicarse con cualquier otra implementación conforme.

Los detalles internos de implementación **NO DEBEN** afectar al comportamiento observable del protocolo.

### P6 — Backward Compatibility (Compatibilidad hacia atrás)

Una implementación **NO DEBE** romper la comunicación con implementaciones anteriores dentro de la misma versión **major** del protocolo.

Los cambios incompatibles **DEBEN** reservarse para una nueva versión **major**.

### P7 — Feature Negotiation (Negociación de Capacidades)

Las características opcionales (**Protocol Version**, **Capabilities**, formatos, algoritmos) **DEBEN** negociarse durante la autenticación.

Un **Peer** **NO DEBE** asumir que otro **Peer** soporta una **Capability** o parámetro sin haberlo negociado explícitamente.

---

## 8. Protocol Layers

El protocolo KeyMessage define una única capa dentro del modelo de comunicación:

```
┌──────────────────────────────────┐
│          Application             │
│  (interfaz de usuario, lógica)   │
├──────────────────────────────────┤
│     KeyMessage Protocol          │  ← Alcance de este RFC
│  (autenticación, mensajes, ACK)  │
├──────────────────────────────────┤
│           Transport              │
│  (WebSocket, libp2p, Bluetooth)  │
├──────────────────────────────────┤
│            Network               │
│  (TCP/IP, UDP, LoRa, etc.)       │
└──────────────────────────────────┘
```

El protocolo KeyMessage **SOLO** especifica el comportamiento de la capa **KeyMessage Protocol**.

Las capas inferior (**Transport**, **Network**) y superior (**Application**) están fuera del alcance de la especificación.

Un **Client** intercambia **Protocol Messages** a través de un **Transport**. El **Transport** serializa, entrega y deserializa estos mensajes sin interpretar su contenido.

---

## 9. High-level Communication Model

El modelo de comunicación entre dos **Peers** sigue este flujo general:

```
 Identity (Alice)               Identity (Bob)
       │                              │
       │        ① Discover            │
       │        ② Authenticate        │
       │        ③ Session             │
       │        ④ Exchange            │
       │        ⑤ Terminate           │
       │                              │
```

### ① Discover

Alice necesita conocer la dirección de Bob para iniciar la comunicación.

El descubrimiento puede ocurrir a través de:

- Un **Relay** compartido (broadcast de presencia).
- Una DHT (consulta descentralizada).
- Un mecanismo local (LAN Discovery, código QR, etc.).

El método de descubrimiento está fuera del alcance del protocolo, pero el resultado **DEBE** ser uno o más **Endpoints** compatibles con algún **Transport** soportado por el **Client**.

### ② Authenticate

Alice y Bob intercambian **Protocol Messages** de autenticación.

El proceso de autenticación **DEBE** probar que cada **Peer** posee la **Identity** que declara.

El proceso exacto está definido en **KM-0002**.

### ③ Session

Una vez autenticados, Alice y Bob establecen una **Session**.

Una **Session** es una relación lógica que persiste aunque cambie la **Connection** subyacente.

### ④ Exchange

Durante la **Session**, ambos **Peers** intercambian **User Messages** y **Protocol Messages** de control (ACK, PING, etc.).

El intercambio **DEBE** ocurrir sobre un canal cifrado extremo a extremo.

### ⑤ Terminate

Cualquiera de los dos **Peers** **MAY** cerrar la **Session** en cualquier momento.

El cierre **DEBE** notificarse al otro extremo cuando sea posible.

---

## 10. Protocol Lifecycle

Una implementación conforme **DEBE** manejar los siguientes estados:

```
         ┌──────────┐
         │  IDLE    │
         └────┬─────┘
              │ connect
              ▼
         ┌──────────┐
         │ DISCOVER │
         └────┬─────┘
              │ peer found
              ▼
         ┌──────────┐
         │  AUTH    │  ← KM-0002
         └────┬─────┘
              │ authenticated
              ▼
         ┌──────────┐
         │  READY   │  ← KM-0003, KM-0004
         ├──────────┤
         │ exchange │
         └────┬─────┘
              │ terminate / error
              ▼
         ┌──────────┐
         │  CLOSED  │
         └──────────┘
```

| Estado | Descripción |
|--------|-------------|
| **IDLE** | El **Client** existe pero no ha iniciado ninguna comunicación. |
| **DISCOVER** | El **Client** busca la dirección de otro **Peer**. |
| **AUTH** | El **Client** está autenticándose con el **Peer** remoto. |
| **READY** | La **Session** está establecida. Los **Messages** pueden intercambiarse. |
| **CLOSED** | La **Session** ha terminado. |

> **Nota:** Este diagrama es una abstracción de alto nivel. Los estados internos del protocolo entre **AUTH** y **READY** (p. ej. NEGOTIATING, ESTABLISHED) se definen en RFC posteriores, en particular KM-0002 y KM-0003.

Transiciones adicionales:

- Desde cualquier estado **MAY** ocurrir un error que lleve a **CLOSED**.
- Desde **CLOSED** **MAY** volver a **DISCOVER** para reconectar.

---

## 11. Protocol Invariants

Las siguientes propiedades **DEBEN** mantenerse en todas las versiones del protocolo:

| # | Invariante | Descripción |
|---|------------|-------------|
| I1 | **Identity inmutable en Session** | Una **Identity** nunca cambia durante una **Session**. |
| I2 | **Message ID único** | Un **Message ID** es único dentro del espacio de la **Identity** emisora. |
| I3 | **Protocol Message tipado** | Todo **Protocol Message** posee un tipo explícito. |
| I4 | **Session binaria** | Toda **Session** pertenece exactamente a dos **Identities**. |
| I5 | **Capability explícita** | Un **Peer** nunca puede asumir una **Capability** no negociada. |
| I6 | **Relay ciego** | Un **Relay** nunca interpreta el contenido de los **User Messages**. |
| I7 | **Autenticación previa** | No puede intercambiarse ningún **User Message** antes de completar la autenticación. |

---

## 12. Compatibility

### Versiones

El protocolo utiliza **versionado semántico** para la especificación:

- **Major**: cambios incompatibles.
- **Minor**: añadidos compatibles hacia atrás.
- **Patch**: correcciones y aclaraciones.

### Política

1. Un **Client** **DEBE** declarar su **Protocol Version** durante la autenticación.
2. Dos **Clients** con la misma versión **major** **DEBEN** poder comunicarse.
3. Un **Client** con versión **major** superior **DEBE** intentar negociar compatibilidad con uno de versión inferior.
4. Si no es posible la negociación, el **Client** **DEBE** rechazar la conexión con un código de error explícito.

### Capa de compatibilidad

Una implementación **MAY** incluir una capa de compatibilidad que permita la comunicación entre distintas versiones **major**, pero esta capa está fuera del alcance de la especificación.

---

## 13. Feature Negotiation

Durante la autenticación, los **Peers** negocian los parámetros de la **Session** mediante **Feature Negotiation**.

Este proceso incluye:

| Parámetro | Descripción |
|-----------|-------------|
| **Protocol Version** | Versión del protocolo que cada **Peer** implementa. |
| **Capabilities** | Funcionalidades opcionales soportadas (ACK, PING, GROUPS, VOICE, RELAY, etc.). |
| **Formatos soportados** | Serialización aceptada (JSON, CBOR, etc.). |
| **Compresión** | Algoritmos de compresión soportados. |
| **Algoritmos criptográficos** | Cifrado y firma disponibles. |

Un **Peer** **DEBE** ignorar cualquier parámetro que no reconozca.

Los parámetros no negociables (como el algoritmo de derivación de **Identity ID**) se definen en los RFC correspondientes y **NO** se negocian.

---

## 14. Extensibility

El protocolo se extiende mediante los siguientes mecanismos:

### 14.1 — Nuevas Capabilities

Nuevas **Capabilities** se añaden sin cambiar la versión **major** del protocolo.

Un **Peer** **DEBE** ignorar **Capabilities** que no reconozca.

### 14.2 — Nuevos tipos de Protocol Message

Una extensión **MAY** definir nuevos tipos de **Protocol Message**.

Los tipos no reconocidos **DEBEN** ser ignorados o rechazados con un código de error.

### 14.3 — Nuevos Transports

Cualquier medio que proporcione entrega bidireccional de bytes **MAY** utilizarse como **Transport**.

La especificación de un **Transport** concreto está fuera del alcance del protocolo base.

### 14.4 — Nuevos algoritmos criptográficos

Una extensión **MAY** definir nuevos algoritmos para autenticación, cifrado o firma.

La negociación de algoritmos ocurre durante **Feature Negotiation**.

---

## 15. Future Evolution

El protocolo está diseñado para admitir futuras extensiones mediante:

- Nuevas **Capabilities**.
- Nuevos **Transports**.
- Nuevos tipos de **Protocol Message**.
- Nuevos algoritmos criptográficos.
- Nuevos mecanismos de descubrimiento.

Todo ello sin romper la compatibilidad con implementaciones existentes dentro de la misma versión **major** del protocolo.

---

## 16. Security Model

El protocolo KeyMessage protege las siguientes propiedades:

### Protege

| Propiedad | Descripción |
|-----------|-------------|
| **Confidencialidad** | El contenido de los **User Messages** solo es accesible para el emisor y el destinatario. |
| **Autenticación** | Cada **Peer** demuestra criptográficamente su **Identity**. |
| **Integridad** | Los **Protocol Messages** no pueden ser modificados en tránsito sin detección. |
| **No repudio** | Un **Peer** no puede negar haber enviado un mensaje firmado. |

### No protege

| Propiedad | Motivo |
|-----------|--------|
| **Dispositivo comprometido** | Un atacante con acceso físico al **Device** puede leer claves locales. |
| **Malware local** | El protocolo no puede protegerse contra software malicioso en el mismo sistema. |
| **Capturas de pantalla** | El contenido mostrado al usuario puede ser capturado por el sistema operativo. |
| **Análisis de tráfico** | Un **Passive Attacker** puede observar metadatos (quién se comunica con quién, cuándo, volúmenes). |
| **Disponibilidad** | El protocolo no garantiza resistencia a DoS. |

---

## 17. Security Considerations

### 17.1 — Autenticación obligatoria

Todo **Peer** **DEBE** autenticarse antes de establecer una **Session**.

Las conexiones no autenticadas **DEBEN** ser rechazadas.

### 17.2 — Protección contra replay

Todo mensaje de autenticación **DEBE** incluir un **Nonce** o **timestamp** para prevenir **Replay Attacks**.

### 17.3 — Integridad

Todo **Protocol Message** **DEBE** poder ser verificado por el receptor para detectar modificaciones en tránsito.

### 17.4 — Confidencialidad

Los **User Messages** **DEBEN** ser cifrados extremo a extremo.

El **Relay**, el **TURN Server** y cualquier intermediario **NO DEBEN** tener acceso al contenido en claro.

### 17.5 — Separación entre Identity y Transport

La **Identity** de un **Peer** **NO DEBE** estar ligada a su **Endpoint** de **Transport**.

Un **Peer** **MAY** cambiar de **Endpoint** sin cambiar de **Identity**.

### 17.6 — Timeout de autenticación

Un **Client** que no complete la autenticación dentro de un plazo definido (ver KM-0002) **DEBE** ser desconectado.

---

## 18. Referencias

| Ref | Documento |
|-----|-----------|
| [KM-0000] | KM-0000 — Terminology |
| [KM-0002] | KM-0002 — Authentication |
| [KM-0003] | KM-0003 — Relay Protocol |
| [KM-0004] | KM-0004 — Message Layer |
| [KM-0005] | KM-0005 — Groups |
| [KM-0006] | KM-0006 — Voice |
| [KM-0007] | KM-0007 — Binary Format |
| [KM-DHT-0001] | KM-DHT-0001 — Kademlia DHT |
| [KM-IDENTITY-0001] | KM-IDENTITY-0001 — Identity Model |
| [RFC 2119] | [Key words for use in RFCs](https://datatracker.ietf.org/doc/html/rfc2119) |

---

## 19. Historial

| Versión | Fecha | Cambios |
|---------|-------|---------|
| 0.1 | 2026-07-24 | Documento inicial — arquitectura (P0-P7), capas, modelo de comunicación, ciclo de vida, invariantes, feature negotiation, compatibilidad, extensibilidad, modelo de seguridad |
