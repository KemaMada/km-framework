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

### Added — envío y recepción E2E cifrados en el camino de aplicación (km-core)

El camino de envío de km-core puede salir ahora como `SecureFrame` en vez de
JSON en claro, y el camino de recepción sabe descifrarlo. Todo es **opt-in**:
sin inyección, el comportamiento es exactamente el de antes.

- `isSecureFrameWire` (`com.km.messaging`): discriminante D-1. Todo `SecureFrame`
  v1 empieza por su campo `version` (`0x01` en el byte 0) y ningún valor JSON
  válido puede empezar así, así que la clasificación no tiene colisión.
- `SecureOutbound` / `SecureOutboundImpl`: envuelve el `Message` en un
  `MessageEnvelope` y lo entrega a la `SecureMessagingSession` del destinatario.
  El `messageId` va **dentro** del sobre, que a su vez va dentro del plaintext
  cifrado: el header de 44 bytes está congelado y su AAD es el header completo.
- `SecureInbound`: entrega al `ReceiverImpl` los `Message` descifrados. **No
  descifra**: la sesión ya lo hizo en su propia suscripción, y un segundo paso
  por el ratchet sería un replay que perdería el mensaje. Se observa **por
  sesión**, no una vez por par: cuando el registro sustituye la sesión de un par
  por otra (un re-key), esa otra se observa también y la sustituida deja de
  hablar. Antes, los mensajes posteriores a un re-key se perdían en silencio.
- `SecureSessionRegistry.onInstall`: observa cada sesión que entra. Existe por
  una garantía de **orden** de suscripciones, no por comodidad.
- `SecureMessagingSession.sendTranscript`: reenvía bytes **ya cifrados** sin
  volver a cifrar, para que el reintento sea idempotente (`MAX_RESEND`).
- `SecureMessagingSession` filtra ahora `onData` por `remotePeerId`: con dos
  sesiones sobre el mismo `PeerTransportManager`, el frame de un par entraba en
  la sesión del otro.
- `KeyMessageCoreImpl` acepta `secureSessionRegistry`, `allowLegacyCleartextReceive`
  y `peerTransportManager` como parámetros opcionales al final, con valor por
  defecto `null`, `false` y `null`.
- `LegacyInboundObservation`: contadores y estado del camino legado de recepción,
  por API pública. Es lo que hace observable el rechazo en claro con el registro
  activo.

**Regla de decisión (R1), que hay que leer antes de usar nada de esto:**

- **Registro ausente → el mensaje sale EN CLARO** por el camino legado. Es
  compatibilidad, no cifrado, y así se declara aquí y en el KDoc. Ningún
  consumidor actual construye el registro.
- **Registro presente y sin sesión con el destinatario → RECHAZO observable**:
  estado `FAILED` con código de fallo, sin encolar y sin llamar a
  `Transport.send`. Nunca un reintento en claro como plan B.

**El espejo de esa regla en RECEPCIÓN, que es lo que faltaba:**

Con el registro presente, un `MESSAGE` **en claro** por el camino legado ya no
llega al handler de la aplicación. Antes sí llegaba, con la misma forma y el mismo
`from` que un mensaje E2E y sin ninguna marca de origen, así que un consumidor que
hubiera inyectado el registro creyendo estar cifrado no tenía forma de saberlo.

- **Registro ausente → el camino legado de recepción se conserva tal cual**, en
  claro y sin marcar. Es el camino de compatibilidad y no hay a quién engañar con
  el: nadie ha construido el registro todavía.
- **Registro presente y `allowLegacyCleartextReceive = false` (el valor por
  defecto) → el camino legado de recepción está DESACTIVADO.** El rechazo es
  observable por API pública en `KeyMessageCoreImpl.legacyInbound`
  (`rejectedCleartext`, `legacyReceiveEnabled`), igual que el rechazo del envío se
  ve en el estado del mensaje. No es un log.
- **Registro presente y `allowLegacyCleartextReceive = true` → el claro vuelve**,
  y **eso no es E2E**. Queda marcado en `legacyInbound.deliveredCleartext`, que
  es la forma de distinguirlo de un mensaje descifrado: un `Message` que suma ahí
  y **no** suma en `secureInbound.delivered` vino por el camino legado.

Lo que **no** cambia: el `ACK` sigue saliendo por `Transport.sendAck`, firmado y
en claro. Cerrar el `MESSAGE` en claro no cierra el canal `ACK`.

**Lo que este cambio NO cubre**, y sigue igual que antes:

- **km-desktop es TEST-ONLY para este camino.** No tiene origen de
  `VerifiedContactBundle`, luego no puede establecer sesión y su envío sigue
  saliendo en claro. No se ha escrito ningún test que fije ese comportamiento
  como correcto.
- **El `ACK` sigue firmado y EN CLARO** por `Transport.sendAck`. Cifrarlo exige
  `EnvelopeType.DELIVERY_ACK` más `SecureMessageReceiver`, y es otro slice.
- **El payload sigue persistido en claro** en el `MessageStore`. El cifrado
  at-rest es otra fase: que el cable vaya cifrado no dice nada del disco.
- **`MessageEnvelope` v1 no lleva `timestamp`.** El `Message` entrante se
  reconstruye con `0`, y eso se declara en vez de inventar una hora.
- **`SecureMessagingSession.send` sigue exigiendo
  `PeerContext.authSession == AUTHENTICATED`**, y no se ha relajado. Es un
  checkpoint de superficie con la criptografía del ratchet detrás, y el hueco que
  deja para un contacto E2E en producción está pendiente del rediseño de
  app/desktop.
- **La disponibilidad mezcla dos nociones.** `SenderImpl` sigue preguntando al
  `Transport` legado (`isOnline()`), mientras que la rama cifrada decide por el
  estado del binding de `PeerTransportManager`. Un destino offline **se encola**
  y se vacia por la rama **cifrada**, o **no se vacia**: si el binding no está
  `CONNECTED` el envío se rechaza de forma observable y el mensaje sigue en la
  cola. **No** se drena por el camino legado, porque esa rama solo existe sin
  registro. La brecha es de **disponibilidad** —lo que dispara el vaciado es la
  presencia que declara el `Transport` legado, no el estado del binding—, no de
  confidencialidad. Declarado, no arreglado.
- **El drenado de `onPeerOnline` con el registro presente y sin sesión está
  pinado, y no solo declarado.** Es la misma fuga que se cerró en el reintento
  (C-1), pero por la otra rama: el vaciado de la cola vuelve a pasar por `send`,
  y `send` rechaza por `NO_SESSION`. Lo que ahora está probado es que ese
  rechazo **no se convierte en un plan B**: con la cola llena, el par de vuelta
  en línea y el registro presente pero sin sesión con ese par, no sale nada por
  `Transport.send` —los dos observadores del transporte falso, `sendCount == 0` y
  `sent` vacío— y el mensaje **sigue en la cola** con su `messageId` intacto. Sin
  ese test la afirmación eraStructural: un drenado en claro del fallback
  `if (send falla) transport.send(json)` era invisible para toda la suite, porque
  la rama feliz del vaciado nunca llega al fallo.

### Added — andamiaje de pruebas

- `tools/mutprobe.py` y `tools/fallos.py`: arnés de medición de mutaciones y
  atribución de fallos por XML.
- `tools/mutaciones/m1..m6`: seis mutaciones que borran, cada una, una garantía
  distinta del medio físico (escritura directa en el hueco, `force` del canal,
  `ATOMIC_MOVE`, `copyTo`+`delete`, lectura parcial, verificación de integridad
  ignorada).

### Seguridad

- **1363 tests, 0 fallos** en `km-core` tras el cierre de C-1, C-2 y W-A del
  slice 4 (antes de estos arreglos: 1355; +7 nuevos: 4 de recepción heredada,
  1 de sesión de reemplazo, 1 de `ACK` del camino cifrado, 1 del drenado de la
  cola sin sesión). Baseline del slice: 1331.
  Mutaciones aplicadas y revertidas: las 9 del slice más 6 de estos arreglos
  (fuga en claro aditiva en el reintento, discriminante de recepción invertido,
  opt-in apagado, observación solo de la primera sesión por par, `ACK` sin
  correlacionar, y **plan B en claro al drenar la cola sin sesión**), **todas
  válidas**: cada una compiló y falló en la aserción esperada.
  La del drenado es de las que importan por lo que **no** detectaba: con ella
  puesta y el test nuevo desactivado, la suite seguía en 1362/0 y `exit 0`.
  Activado el test, 1363 con 1 fallo y en la aserción nueva y solo en ella.
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
