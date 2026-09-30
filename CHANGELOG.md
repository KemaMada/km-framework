# Changelog

Todas las novedades de km-framework.

El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y
el versionado sigue [SemVer](https://semver.org/lang/es/).

Este proyecto **está por debajo de 1.0 y no publica versiones todavía**. No hay
`maven-publish` aplicado en los módulos, así que no existe un artefacto del que
depender. Las entradas de abajo describen el estado del código, no de un
artefacto publicado.

## [No publicado]

Estado del árbol a **2026-09-30**. No hay releases; esto es lo que hay en
`main`.

### Añadido — estructura del repositorio

- Separación del cliente Android: `app/` (Compose) sale del repositorio y pasa a
  `km-android`. `/app/` está en `.gitignore` y fuera del índice de git.
  **km-framework** se queda con `km-core`, `km-webrtc`, `km-id-reference`,
  `docs/` y `tools/`.
- Documentación de publicación: `README.md`, `CONTRIBUTING.md`, `SECURITY.md`,
  `NOTICE`, `THIRD-PARTY-LICENSES/`.
- `LICENSE` (MIT, `Copyright (c) 2026 KemaMada`).

### Added — renombrado de paquetes (ROMPIENTE)

Los paquetes pasan de `com.keymessage.core.*` a `com.km.*`, con nombres más
cortos tomados del vocabulario del protocolo. El detalle completo está en el
[README](README.md#mapeo-de-renombrado-de-paquetes).

| Antes | Ahora |
|-------|-------|
| `com.keymessage.core.kmid` | `com.km.identity` |
| `com.keymessage.core.km7` | `com.km.negotiation` |
| `com.keymessage.core.sf` | `com.km.frame` |
| `com.keymessage.core.km52` | `com.km.unit` |
| `com.keymessage.core.*` (resto) | `com.km.*` |
| `com.keymessage.webrtc` | `com.km.webrtc` |

- **ROMPE COMPATIBILIDAD**: cambia la API pública completa. No hay shim, ni
  alias, ni `@Deprecated`. No hay compatibilidad que preservar porque no hay
  versión publicada. Los **nombres de tipo** no cambian: `Km7Negotiation`,
  `Km52Codec`, `KmIds` y `KmSchemas` conservan el identificador de
  especificación.

### Added — protocolo e implementación

- **SecureFrame v1** (`com.km.frame`): header fijo de 44 bytes, los 44 bytes
  íntegros como AAD, nonce derivado por HKDF y **no** transmitido. `length`
  acota el ciphertext a 65535 bytes; la fragmentación queda pendiente.
- **Double Ratchet** (`com.km.ratchet`): ratchet de raíz y de cadena, con
  dominios separados para `RootKey` y `ChainKey`. Establishment desde `F` sin
  mezclarlo con el bootstrap ni con la mensajería.
- **X3DH** (`com.km.x3dh`, KM-0006): `DH1..DH4`, `SK` y `K_prekey`. Ni `initiate`
  ni `respond` mutan estado; el consumo de la one-time prekey ocurre en el
  commit. Solo claves X25519 de acuerdo: no hay conversión Ed25519→X25519 en la
  API.
- **Unidad persistida KM52 v2** (`com.km.unit`): `unitVersion = 2`, y aparece el
  bloque `PENDING_INBOUND`. Los frames entrantes que la sesión todavía no ha
  podido usar se guardan **en la misma unidad atómica** que el estado
  criptográfico; antes vivían en una cola en memoria que nada vaciaba.
- **Medio físico con publicación atómica** (`com.km.transmit`): escribir un
  temporal → sincronizar → `rename` sobre el hueco → sincronizar el directorio.
  El invariante que gobierna la clase: *un fallo físico no puede convertirse en
  un nuevo estado criptográfico*. Se conserva la asimetría al escribir y al
  leer: podar al escribir, rechazar entero al leer.
- **Identidad KM-ID-0001** (`com.km.identity`): `identityId`, `deviceId` y
  `nodeId` con separación de dominio; `DeviceRoster` con cadena de hashes y
  revocación; `ContactBundle` con binding dispositivo↔identidad; Canonical
  Encoding (KCE) como perfil cerrado de CBOR.
- **Autenticación KM-0002** (`com.km.auth`): `AuthVerifier` con las reglas V1–V6
  —derivación de `identityId`, firma del transcript de 152 bytes, firma del
  transcript de servidor de 120 bytes, ventana de skew, guarda de replay de
  nonce— con `Clock` y `NonceReplayGuard` inyectados para mantener el verificador
  sin estado. `Base64URL` estricto, sin padding en el wire.
- **Negociación KM-0007** (`com.km.negotiation`): intersección por categoría,
  selección por preferencia **local**, determinista. Una capacidad desconocida se
  ignora, no se rechaza.
- **Transporte WebRTC** (`com.km.webrtc`): `ManagedWebRtcPeer`,
  `RealWebRtcTransport`, `RelaySignaling`, `WebRtcEstablishment` y
  `WebRtcPlatformDetector`, con selección del clasificador nativo a partir de
  `os.name` y `os.arch`.
- **km-id-reference**: implementación de referencia normativa de KM-ID-0001 en
  Python, con vectores congelados (`FROZEN.sha256`) y fixtures negativos que
  fallan por una sola razón cada uno.

### Added — andamiaje de pruebas

- `tools/mutprobe.py` y `tools/fallos.py`: arnés de medición de mutaciones y
  atribución de fallos por XML.
- `tools/mutaciones/m1..m6`: seis mutaciones que borran, cada una, una garantía
  distinta del medio físico (escritura directa en el hueco, `force` del canal,
  `ATOMIC_MOVE`, `copyTo`+`delete`, lectura parcial, verificación de integridad
  ignorada).

### Seguridad

- **1187 tests, 0 fallos** a 2026-09-30: 1095 en `km-core`, 92 en `km-webrtc`
  (esta última suite levanta `PeerConnection` reales de libwebrtc).
- Corrección del bug donde `identityId` se usaba como clave de verificación
  Ed25519: `identityId` y `deviceId` son identificadores y **nunca** material
  criptográfico. `SignedMessage` y `SignedAck` transportan la clave pública por
  separado, y `Ed25519Impl.verify()` rechaza claves de tamaño distinto de 32.
- `identityId` en el wire pasa de 40 a 64 hex, con la derivación de KM-ID-0001
  (`SHA-256("KM-ID-IDENTITY" || publicKey)`). El transcript de autenticación
  cambia de 104 a 152 bytes, lo que invalida las firmas anteriores. Es un límite
  de versión del protocolo.
- El `deviceId` opaco de KM-0002 queda retirado: ahora siempre es derivado
  (`SHA-256("KM-ID-DEVICE" || deviceSigningKey)`).

### Decisiones que conviene tener presentes

- **MIT no incluye concesión explícita de patentes.** Es una decisión
  consciente, no un descuido. La concesión de patentes que Apache-2.0 incorpora se
  añadió precisamente porque el proyecto que la inspiró (WebRTC) la necesitaba, y
  km-framework depende de él; aquí se ha preferido la simplicidad de la MIT.
  **Consecuencia:** km-framework no promete de forma explícita derechos de patente
  a quienes lo contribuya. Quien necesite esa protección debe declarar sus
  invenciones en el PR (ver [CONTRIBUTING.md](CONTRIBUTING.md)) y asumir el
  riesgo. Si esta decisión cambia, es un cambio de licencia y debe pasar por un
  proceso explícito, no por un commit.
- **Bouncy Castle está confinado** a `com.km.crypto.provider`. El resto de
  `km-core` depende solo de interfaces propias (`X25519`, `Kdf`, `Aead`,
  `Hash`), lo que permite cambiar de proveedor sin tocar el resto del framework.
- **Todas las dependencias se declaran con `implementation`, no con `api`.** El
  tipo de una dependencia de terceros no forma parte de la API pública de
  km-framework.
- **`km-core` no depende de Android ni de `dev.onvoid`**, y compila con toolchain
  JVM 11.

### Limitaciones conocidas

Declaradas en el [README](README.md) y en [SECURITY.md](SECURITY.md), y en el
propio código donde aplican:

- Sin grupos, sin DHT, sin voz, aunque tengan RFC.
- Sin fragmentación de `SecureFrame`: el ciphertext se acota a 65535 bytes.
- `com.km.storage` es solo en memoria. Lo durable es la unidad criptográfica.
- `RelayServer` es una implementación en proceso para pruebas, no un servicio
  desplegable.
- `com.km.api.KeyMessageCore` es una fachada mínima de 9 métodos.
- `com.km.model.Attachment` está declarado y no está referenciado por nada.
- Sin CI: no hay workflows, y `.github/` no existe. Los tests se ejecutan en
  local.
- Los RFC están en `Draft` o `Draft (Frozen)`. Solo KM-0002 y KM-0004 están
  `Stable`.

### Sin auditorías

Ninguna auditoría de seguridad externa o interna revisada por terceros. Ninguna
afirmación de cumplimiento. No está en producción ni lo usa nadie conocido.
En una implementación criptográfica, la ausencia de auditoría es el primer
dato que hay que dar, no una nota al pie.
