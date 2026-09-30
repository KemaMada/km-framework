# km-framework

**km-framework** es la implementación de referencia del protocolo **KeyMessage**:
la infraestructura criptográfica y de transporte P2P que otras aplicaciones
consumen para hablar entre sí de forma segura.

No es una aplicación de mensajería. No tiene interfaz de usuario, ni bandeja
de entrada, ni lista de contactos, ni notificaciones. Es la capa de la que se
sirve un cliente.

```
km-framework   =  protocolo + criptografía + transporte
un cliente     =  km-framework  +  interfaz de usuario  +  almacenamiento
```

Los dos módulos publicables son:

| Módulo | Qué es | Dependencias |
|--------|--------|--------------|
| `km-core` | Identidad, autenticación, negociación, X3DH, Double Ratchet, SecureFrame, persistencia. | Sin Android. Sin WebRTC. |
| `km-webrtc` | Transporte WebRTC: establecimiento de `PeerConnection`, señalización contra el relé, detección de plataforma nativa. | `km-core` + `webrtc-java` (JNI + libwebrtc nativo). |

---

## Qué NO es

- **No es un cliente de mensajería.** No envía ni muestra mensajes. `com.km.api.KeyMessageCore`
  es una fachada mínima; la interfaz de usuario la pone el consumidor.
- **No es un servidor de relé para producción.** `com.km.node.RelayServer` es una
  implementación en proceso usada por las pruebas y los tests de integración. No
  tiene autenticación de operador, ni limitación de tasa global, ni persistencia
  entre reinicios, ni métricas.
- **No está auditado.** No ha pasado ninguna revisión de seguridad externa,
  ni certificación, ni evaluación formal de los algoritmos. Ver
  [Estado del proyecto](#estado-del-proyecto).
- **No está en producción.** No está publicado en Maven Central ni en ningún
  registro, y no hay releases firmadas.
- **No es estable.** No hay versión `1.0`. La API y el formato de wire pueden
  cambiar sin aviso (ver [Renombrado de paquetes](#mapeo-de-renombrado-de-paquetes)).

---

## Mapeo de renombrado de paquetes

Los paquetes se renombraron de `com.keymessage.core.*` a `com.km.*`. El segmento
`core` desaparece y los nombres se acortan: los identificadores de especificación
(`kmid`, `km7`, `km52`, `sf`) ceden el sitio a palabras del vocabulario del
protocolo.

Este renombrado es un **cambio incompatible**. La API pública, los imports y los
nombres de clase que los referencian han cambiado. No hay versión publicada, así
que no hay compatibilidad que preservar: no hay shim, no hay `@Deprecated`, no
hay alias.

| Antes | Ahora | Qué contiene |
|-------|-------|--------------|
| `com.keymessage.core.kmid` | `com.km.identity` | `KmIds`, `DeviceRoster`, `ContactBundle`, `Kce`, `KmSchemas` — identidad, dispositivos, ContactBundle, Canonical Encoding. |
| `com.keymessage.core.km7` | `com.km.negotiation` | `Km7Negotiation`, `Km7Types`, `Km7Codec` — negociación de capacidades, serialización, compresión y suites (KM-0007). |
| `com.keymessage.core.sf` | `com.km.frame` | `SecureFrame`, `SecureFrameCodec`, `SecureFrameProtector` — contenedor cifrado del camino caliente (KM-0004). |
| `com.keymessage.core.km52` | `com.km.unit` | `Km52Types`, `Km52Codec` — formato de la unidad de transmisión persistida, versión 2. |
| `com.keymessage.core.crypto` | `com.km.crypto` | `Ed25519`, `X25519`, `Kdf`, `Aead`, `Hash`, `SignatureVerifier`, `AgreementKeyGuard`. |
| `com.keymessage.core.crypto.provider` | `com.km.crypto.provider` | `BcX25519`, `BcHkdfSha256`, `BcChaCha20Poly1305` — **único** punto donde entra Bouncy Castle. |
| `com.keymessage.core.ratchet` | `com.km.ratchet` | `DoubleRatchetSession`, `DoubleRatchetSnapshot`, `SymmetricRatchet`, `RatchetSessionBootstrap`. |
| `com.keymessage.core.x3dh` | `com.km.x3dh` | `X3dh`, `X3dhSpec` — bootstrap de sesión (KM-0006). |
| `com.keymessage.core.node` | `com.km.node` | `NodeRuntime`, `RelayService`, `RealRelayTransport`, `TransportChain`, `PeerContext`. |
| `com.keymessage.core.protocol` | `com.km.protocol` | `Sender`, `Receiver`, `AckManager`, `RetryManager`, `OrderingManager`, `Transport`. |
| `com.keymessage.core.messaging` | `com.km.messaging` | `SecureMessagingSession`, `SecureMessageReceiver`, `MessageEnvelope`, `PendingInbox`. |
| `com.keymessage.core.transmit` | `com.km.transmit` | `FileTransmitUnitStore`, `SecureTransmitJournal`, `TransmitLedger`, `ChainRetentionBook`. |
| `com.keymessage.core.storage` | `com.km.storage` | `MessageStore`, `OfflineQueue`, `RelayStore`, `DuplicateStore` y sus implementaciones en memoria. |
| `com.keymessage.core.codec` | `com.km.codec` | `JsonEncoder`, `JsonDecoder`, `JsonRelayControlCodec`, `Validator`. |
| `com.keymessage.core.model` | `com.km.model` | `Message`, `Ack`, `IdentityId`, `MessageId`, `NodeIdentity`, `NodeAnnouncement`, `StoredMessage`, … |
| `com.keymessage.core.api` | `com.km.api` | `KeyMessageCore`, `KeyMessageCoreImpl` — la fachada. |
| `com.keymessage.core.auth` | `com.km.auth` | `AuthVerifier`, `AuthSession`, `Transcripts`, `Base64Url`, `RelayAuthHandler`. |
| `com.keymessage.webrtc` | `com.km.webrtc` | `ManagedWebRtcPeer`, `RealWebRtcTransport`, `RelaySignaling`, `WebRtcEstablishment`, `WebRtcPlatformDetector`. |

**Lo que el renombrado NO tocó.** Los *nombres de tipo* siguen llevando el
identificador de especificación: `Km7Negotiation`, `Km52Codec`, `KmIds`,
`KmIdConstants`, `KmSchemas`. Solo cambiaron los paquetes. `km-id-reference/`
sigue llamándose así, y las rutas de especificación siguen siendo
`docs/rfc/KM-000N-*.md`. Si esperabas `com.km.negotiation.CapabilitySet` en lugar
de `com.km.negotiation.Km7Types`, todavía no está.

---

## Arquitectura

```
  ┌──────────────────────────────────────────────────────────────┐
  │                    Consumidores (externos)                   │
  │                                                              │
  │   km-desktop (TUI)      km-android (Compose)     tu cliente  │
  │   Linux + Windows       repo propio              propio     │
  │   NO EXISTE TODAVÍA ─┐   separado                 │         │
  └──────────────────────┼─────────────────────────────┼─────────┘
                         │                             │
                         ▼                             ▼
  ┌──────────────────────────────────────────────────────────────┐
  │                    km-webrtc   (com.km.webrtc)               │
  │   ManagedWebRtcPeer · RealWebRtcTransport                   │
  │   RelaySignaling · WebRtcEstablishment                       │
  │   └─ webrtc-java 0.19.0  →  libwebrtc nativo (classifier)   │
  ├──────────────────────────────────────────────────────────────┤
  │                    km-core    (com.km.*)                     │
  │                                                              │
  │  node       · relé, transporte, binding peer↔transporte     │
  │  protocol   · sender, receiver, ACK, retry, orden            │
  │  messaging  · sesión de mensajería segura, PendingInbox     │
  │  frame      · SecureFrame v1 (44 B, header íntegro como AAD) │
  │  ratchet    · Double Ratchet + establishment                 │
  │  x3dh       · bootstrap de sesión                            │
  │  identity   · identityId, deviceId, ContactBundle, KCE      │
  │  unit       · unidad persistida KM52 v2                      │
  │  transmit   · medio físico con publicación atómica           │
  │  auth       · KM-0002: verifier, transcripts, Base64URL     │
  │  crypto     · interfaces propias + provider Bouncy Castle   │
  │  negotiation· KM-0007: intersección de capacidades          │
  │  codec      · JSON / KCE                                     │
  │  storage    · MessageStore, OfflineQueue, DuplicateStore    │
  │  model      · Message, Ack, IdentityId, NodeIdentity…       │
  │  api        · KeyMessageCore (fachada)                       │
  ├──────────────────────────────────────────────────────────────┤
  │  km-id-reference/  ·  oráculo Python de KM-ID-0001          │
  │                      (normativo; vectores congelados)         │
  └──────────────────────────────────────────────────────────────┘
                         │
                         ▼
              red  ·  PeerConnection / relé
```

`km-core` no depende de `km-webrtc`: la dependencia va en el sentido contrario.
`km-core` no tiene dependencia alguna de Android ni de `dev.onvoid`, y compila
para un toolchain JVM 11.

---

## Estado real

Todo lo de esta tabla está comprobado leyendo el código de este repositorio a
**2026-09-30**. Lo que no está en la tabla de "Implementado" **no está
implementado**, por mucho que exista en `docs/rfc/`.

### Implementado y cubierto por tests

| Capacidad | Dónde | Notas |
|-----------|-------|-------|
| Identidad: `identityId`, `deviceId`, `nodeId` | `com.km.identity` | Derivación con separación de dominio (`KM-ID-IDENTITY`, `KM-ID-DEVICE`, `KM-NODE-NODE`). |
| `DeviceRoster`, cadena de hashes, revocación | `com.km.identity` | Validación de mundo cerrado contra `KmSchemas`. |
| `ContactBundle` y binding dispositivo↔identidad | `com.km.identity` | Un bundle que dice "móvil" y lleva la clave del PC se rechaza. |
| Canonical Encoding (KCE) | `com.km.identity.Kce` | Perfil de CBOR: rechaza flotantes, etiquetas, `null`, longitudes indefinidas, claves duplicadas y texto no NFC. |
| Autenticación KM-0002 | `com.km.auth` | `AuthVerifier` con V1–V6 (derivación de `identityId`, firma del transcript, ventana de skew, guarda de replay de nonce). Transcripts de 152 y 120 bytes. |
| Base64URL estricto (RFC 4648 §5, sin padding) | `com.km.auth.Base64Url` | El padding en el wire se rechaza. |
| Negociación de capacidades KM-0007 | `com.km.negotiation` | Intersección por categoría, selección por preferencia **local**, determinista. Capacidad desconocida se ignora, no se rechaza. |
| `SecureFrame` v1 | `com.km.frame` | Header fijo de 44 bytes, los 44 bytes íntegros son el AAD. `length` acota el ciphertext a 65535 bytes. El nonce **no viaja**: se deriva por HKDF. |
| Double Ratchet | `com.km.ratchet` | Ratchet de raíz + cadenas, con dominios separados para RootKey y ChainKey. |
| X3DH (KM-0006) | `com.km.x3dh` | `DH1..DH4`, `SK`, `K_prekey`. Transaccional: ni `initiate` ni `respond` mutan estado. Separación de claves: solo X25519 de acuerdo, nunca Ed25519. |
| Unidad persistida **KM52 v2** | `com.km.unit`, `com.km.transmit` | `unitVersion = 2`. Bloque `PENDING_INBOUND`: los frames entrantes que la sesión aún no pudo usar se guardan **en la misma unidad atómica** que el estado criptográfico. |
| Medio físico con publicación atómica | `com.km.transmit.FileTransmitUnitStore` | Escribir temporal → sincronizar → `rename` sobre el hueco → sincronizar directorio. Un corte nunca convierte una escritura a medias en una unidad aparentemente entera. |
| Recuperación de la unidad | `com.km.transmit.SecureTransmitJournal` | El medio persiste bytes, sin interpretarlos. Recuperación y verificación leen. |
| Transporte WebRTC | `com.km.webrtc` | Establecimiento de `PeerConnection`, señalización contra el relé, selección de clasificador nativo por plataforma. |
| Ciclo de vida de mensajes | `com.km.protocol`, `com.km.messaging` | ACK, reintento, orden, detección de duplicados, `OfflineQueue`. |
| Codecs JSON y KCE | `com.km.codec` | `Message`, `Ack` y control de relé. |

**Tests: 1187, 0 fallos** (1095 en `km-core`, 92 en `km-webrtc`) a 2026-09-30.
JUnit 5 vía `useJUnitPlatform()`.

### Declarado pero no cableado

| Cosa | Estado real |
|------|-------------|
| `com.km.api.KeyMessageCore` | Interfaz de 9 métodos (`start`, `stop`, `createMessage`, `sendMessage`, `getMessageState`, `getConversation`, tres `register*Handler`). Es una fachada mínima, no una API de aplicación. |
| `com.km.model.Attachment` | Clase de datos declarada. **No hay transporte de adjuntos**: no está referenciada por ningún otro fichero de `km-core/src/main`. |
| `com.km.node.RelayServer` | Servidor de relé en proceso, para pruebas e integración. No es un servicio desplegable. |
| `com.km.node.FakeTransportBackend` | Backend de transporte falso, para pruebas. |

### No implementado

Esto **no** existe en el código, aunque tenga RFC en `docs/rfc/`:

| Capacidad | Nota |
|-----------|------|
| Grupos / mensajería grupal | Sin una sola referencia a grupos en `km-core` ni `km-webrtc`. |
| DHT | Sin implementación. |
| Voz / llamadas | Sin implementación. |
| Fragmentación de `SecureFrame` | El propio código lo dice: cargas mayores de 65535 bytes "requieren fragmentacion (incremento posterior)". |
| Transports distintos de relé y WebRTC | `TransportBackend` es la extensión; solo hay relé real y un backend falso. |
| Persistencia durable de mensajes | `com.km.storage` solo tiene implementaciones **en memoria** (`InMemoryMessageStore`, `InMemoryOfflineQueue`, `InMemoryRelayStore`, `InMemoryDuplicateStore`). Lo que sí es durable es la unidad criptográfica, en `com.km.transmit`. |
| Publicación de artefactos | No hay plugin `maven-publish` aplicado, ni `group`/`version` declarados. Ver [Cómo consumirlo](#cómo-consumirlo). |
| CI | No hay workflows. No existe directorio `.github/`. Los tests se ejecutan en local. |

---

## Requisitos

| Requisito | Versión | Fuente |
|-----------|---------|--------|
| JDK para compilar | **11** | `jvmToolchain(11)` y `sourceCompatibility/targetCompatibility = 11` en ambos módulos. |
| JDK para ejecutar Gradle | 11 o superior | El build usa `foojay-resolver-convention`, que descarga el toolchain 11 si falta. |
| Gradle | **9.5.0** | `gradle/wrapper/gradle-wrapper.properties`. Se usa el wrapper: no hace falta instalarlo. |
| Python (opcional) | 3.x | Solo para `km-id-reference/` y el andamiaje de mutaciones de `tools/`. |

`gradle.properties` fija `org.gradle.java.home`. Si apunta a un JDK que no
existe en tu máquina, ajústalo o bórralo; no es un requisito del proyecto.

---

## Cómo consumirlo

> **Estado de publicación: pendiente.** En este commit los módulos **no**
> publican artefactos: no hay plugin `maven-publish` aplicado ni `group`/`version`
> declarados, así que **no existe ningún repositorio del que dependas**. Las
> coordenadas de abajo son el esquema previsto, y pasarán a ser válidas cuando se
> publique la primera versión. Si necesitas consumirlo hoy, construye desde el
> fuente (`includeBuild`) o usa un subtree.

El grupo previsto es `com.km`:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
```

```kotlin
// build.gradle.kts
dependencies {
    // Solo el protocolo: identidad, autenticación, X3DH, ratchet, SecureFrame,
    // persistencia. Sin Android, sin WebRTC, sin código nativo.
    implementation("com.km:km-core:<version>")

    // Añade el transporte WebRTC. Implica libwebrtc nativo: el artefacto con
    // clasificador se resuelve en tiempo de ejecución según la plataforma.
    implementation("com.km:km-webrtc:<version>")
}
```

Elegir solo `km-core` es una decisión real, no cosmética: es el único módulo que
no arrastra un `.so`/`.dll`/`.dylib` de ~25 MB. `km-core` por sí solo no puede
establecer una `PeerConnection`; necesita un `Transport` que tú inyectes.

Ambos módulos declaran sus dependencias con `implementation`, no con `api`. Es
decir: **el tipo de una dependencia de terceros no forma parte de la API pública
de km-framework**. Si expones un tipo de Jackson o de `webrtc-java` en tu propia
firma, Tus consumidores necesitarán esa dependencia por su cuenta.

---

## Construir y testear

```sh
./gradlew build                # compila y testea todo
./gradlew :km-core:test        # solo km-core  (1095 tests)
./gradlew :km-webrtc:test      # solo km-webrtc (92 tests, levanta PeerConnections nativas)
./gradlew :km-webrtc:test --tests 'com.km.webrtc.*Test'   # una clase
```

`km-webrtc` es una suite de integración real: levanta `PeerConnection` nativas de
libwebrtc. Necesita el artefacto con clasificador de tu plataforma
(`linux-x86_64`, `linux-aarch64`, `linux-aarch32`, `macos-x86_64`,
`macos-aarch64`, `windows-x86_64`, `windows-aarch64`), que el propio build
detecta a partir de `os.name` y `os.arch`.

Ver [CONTRIBUTING.md](CONTRIBUTING.md) para estilo, commits convencionales y el
andamiaje de mutaciones.

---

## Especificaciones

Las especificaciones del protocolo están en [`docs/`](docs/) y son la
autoridad normativa; el código las implementa.

| Ruta | Contenido |
|------|-----------|
| `docs/rfc/KM-0000-terminology.md` | **Terminología oficial.** Empieza por aquí: `Identity`, `Device`, `Peer`, `Relay`, `Session`, `Capability`… |
| `docs/rfc/KM-0001-protocol-architecture-overview.md` | Objetivos, no-objetivos, principios (P0–P7), capas, ciclo de vida, invariantes. |
| `docs/rfc/KM-0002-authentication.md` | Autenticación. *Stable.* |
| `docs/rfc/KM-0003-relay-protocol.md` | Relé. *Draft (Frozen).* |
| `docs/rfc/KM-0004-message-protocol.md` | SecureFrame, ciclo de vida, ACK, duplicados, orden, `OfflineQueue`. *Stable (Frozen).* |
| `docs/rfc/KM-0005-node-and-relay-protocol.md` | Nodo y relé. |
| `docs/rfc/KM-0006-session-bootstrap.md` | X3DH. |
| `docs/tests/KM-TEST-0001-conformance.md` | 29 casos de prueba de conformidad e integración, con matriz de cobertura. |
| `docs/architecture/` | Revisiones de arquitectura. |
| `docs/api/`, `docs/developer/` | Reservados. |

**Los RFC están en `Draft` o `Draft (Frozen)`.** Solo KM-0002 y KM-0004 están
`Stable`. Un RFC en `Draft` puede cambiar.

---

## km-id-reference

`km-id-reference/` es una implementación de referencia de **KM-ID-0001**
(identidad, dispositivos y `ContactBundle`) escrita en **Python**. Es
**normativa**: los vectores de `vectors/` e `invalid/` son la referencia contra la
que se comprueba el port a Kotlin.

Su razón de existir es demostrar que la especificación produce **bytes
deterministas y reproducibles**, de forma que sirva de oráculo para cualquier
implementación, sea en Kotlin, Rust o Go. Los vectores no están escritos a mano:
los calcula esta implementación, y `FROZEN.sha256` fija su contenido.

```sh
cd km-id-reference
python3 generate.py    # regenera vectors/, invalid/ y MANIFEST.json
python3 selfcheck.py   # verifica los fixtures sin escribir nada
```

Por alcance, esta implementación **no** contiene y no debe contener nunca
WebRTC, DHT, relé, sockets, base de datos, mensajería, sesiones, Double Ratchet
ni X25519.

---

## Estado del proyecto

Pre-1.0, en desarrollo activo, sin auditoría. En concreto:

- **No se ha hecho ninguna auditoría de seguridad.** Ni externa, ni interna
  revisada por terceros. No hay informe, ni threat model publicado, ni
  penetration test.
- **No hay afirmaciones de cumplimiento** (SOC 2, ISO 27001, GDPR, FIPS 140-3…).
- **No se usa en producción** por nadie conocido. No hay notificaciones de
  vulnerabilidades, no hay usuarios, no hay SLA.
- **No es *production-ready* ni *battle-tested*.** No uses km-framework con datos
  que no puedas perder. Las primitivas criptográficas se apoyan en Bouncy Castle
  y en Ed25519 de `net.i2p.crypto`, que son implementaciones usadas en otros
  sitios, pero **el diseño del protocolo y su implementación no han sido
  revisados por nadie**.

Lo que sí se puede afirmar: 1187 tests, código de referencia normativo, RFCs
escritos, y una campaña de mutaciones sobre la persistencia atómica.

---

## Licencia

[MIT](LICENSE) — `Copyright (c) 2026 KemaMada`.

**MIT no incluye concesión explícita de patentes.** Es una decisión consciente,
no un descuido: la concesión de patentes de Apache-2.0 se añadió precisamente
porque el proyecto que la inspiró (WebRTC) la necesitaba, y este framework
depende de él. Aquí se ha preferido la simplicidad de MIT. Quien necesite esa
protección debe declarar sus propias invenciones en las contribuciones y asumir
el riesgo correspondiente. Ver [CHANGELOG.md](CHANGELOG.md).

Las dependencias de terceros y sus licencias están en
[`THIRD-PARTY-LICENSES/`](THIRD-PARTY-LICENSES/) y en [NOTICE](NOTICE).

---

## Contribución

Lee [CONTRIBUTING.md](CONTRIBUTING.md). Resumen: `./gradlew build` antes de abrir
un PR, commits convencionales, estilo oficial de Kotlin.

## Seguridad

Lee [SECURITY.md](SECURITY.md). **No abras issues públicos para reportar un
fallo de seguridad.** Este proyecto implementa criptografía; un hallazgo
criptográfico se maneja con discreción, no en un hilo público.
