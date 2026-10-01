# km-daemon

El lado del **relé** del wire protocol. Aplicación headless (JVM) que habla el
**dialecto B** definido en [`docs/protocol/KM-WIRE-RELAY.md`](../docs/protocol/KM-WIRE-RELAY.md),
que es el mismo dialecto que habla `RelayClient` en el cliente Android.

Este módulo es el que el documento listaba como P1/P2/P3 en su §18.2 — «el
servidor WebSocket no existe», «`km daemon` no existe», «nada conecta
`RelayServer.deliverData` a un socket». Aquí ya existe un camino de datos
operativo.

## La regla que gobierna todo

> **El frame que entra es byte a byte el frame que sale.** (KM-WIRE-RELAY §6.2)

El daemon **transcodifica y nada más**:

```
SecureFrame → ByteArray → Base64 → JSON MESSAGE → WebSocket
            → daemon    → JSON MESSAGE → Base64 → ByteArray → WebSocket
```

No parsea el `SecureFrame`, no mira el `messageId`, no descifra, no valida
ACKs de aplicación, no re-cifra. La opacidad es una afirmación sobre el
código, y `JsonWireCodecMessageTest` la verifica byte a byte.

## Depende solo de km-core

```
km-daemon ──→ km-core
```

Una sola dirección. `km-daemon` **no** depende de:

- **`km-webrtc`** — el relé no participa en la negociación WebRTC: solo
  reenvía SDP/ICE. Arrastrarlo traería ~25 MB de libwebrtc nativo al VPS sin
  que nada lo use.
- **el cliente Android** — el dialecto es un contrato de cable, no una
  dependencia de código.

`km-core` tampoco conoce este módulo: la frontera arquitectónica se mantiene
en los dos sentidos.

## Arrancar

```bash
./gradlew :km-daemon:run
```

Con la configuración por defecto escucha en `0.0.0.0:8080`.

Para empaquetarlo:

```bash
./gradlew :km-daemon:installDist
./km-daemon/build/install/km-daemon/bin/km-daemon
```

`installDist` es la vía recomendada frente a un fat JAR: `Java-WebSocket` y
Jackson no tienen ficheros de servicio duplicables, y un uber-jar acaba
pidiendo firmas duplicadas si mañana se añade una.

## Configuración

Tres capas, de menor a mayor precedencia:

```
defaults  <  km-daemon.conf.json  <  variables de entorno KM_DAEMON_*
```

El fichero JSON es **opcional**. Si no está, el daemon arranca con los
defaults, que son los valores normativos del spec.

### `km-daemon.conf.json`

```json
{
  "listenHost": "0.0.0.0",
  "listenPort": 8080,
  "identityPath": "./km-daemon.identity",
  "relayEnabled": true,
  "signalingEnabled": true,
  "sessionExpiryMs": 300000,
  "maxConnections": 512
}
```

| Clave | Default | Qué hace |
|-------|---------|----------|
| `listenHost` | `0.0.0.0` | Interfaz de escucha |
| `listenPort` | `8080` | Puerto TCP |
| `identityPath` | `./km-daemon.identity` | Par Ed25519 del relé; se genera si no existe |
| `relayEnabled` | `true` | Camino de datos (`MESSAGE`) |
| `signalingEnabled` | `true` | Señalización SDP/ICE |
| `sessionExpiryMs` | `300000` | Vida de sesión; se pasa a `RelayServer`, no se reimplementa |
| `maxConnections` | `512` | Conexiones simultáneas (§15.1) |

### Variables de entorno

`KM_DAEMON_CONFIG` (ruta alternativa al JSON), `KM_DAEMON_LISTEN_HOST`,
`KM_DAEMON_LISTEN_PORT`, `KM_DAEMON_IDENTITY_PATH`, `KM_DAEMON_RELAY_ENABLED`,
`KM_DAEMON_SIGNALING_ENABLED`, `KM_DAEMON_SESSION_EXPIRY_MS`,
`KM_DAEMON_MAX_CONNECTIONS`.

Las banderas aceptan **solo** `true`/`false`. Un `1` o un `yes` son un error
de arranque, no un default: una bandera malinterpretada abre o cierra el
camino de datos sin que nadie lo haya pedido.

## Identidad

El daemon necesita una identidad **estable**. La `responderIdentityId` es la
que el cliente usa para construir el transcript de 152 B, así que un par
nuevo en cada arranque haría que todos los clientes rechazaran el challenge.

`identityPath` guarda un par Ed25519 generado en el primer arranque:

```
# km-daemon identity
identityId=<64 hex>
publicKey=<Base64URL sin padding>
privateKeySeed=<Base64URL sin padding, 32 B>
```

Texto plano, permisos `600`, formato legible a propósito: la protección es el
permiso del fichero, no su ofuscación. **No copies ni registres el contenido.**

Al arrancar, el daemon rehace la derivación
(`SHA-256("KM-ID-IDENTITY" || publicKey)`) y la compara con el `identityId`
guardado. Si no cuadran, se para: un `identityId` que no derive de la clave
pública emitiría un challenge que nadie podría validar.

## Qué está implementado, y qué no

Esta tabla es normativa. Todo lo de la columna «Implementado» es
verificable en el código.

| # | Componente | Dónde |
|---|-----------|-------|
| I1 | Servidor WebSocket (RFC 6455) sobre Java-WebSocket | `server/RelayWebSocketServer.kt` |
| I2 | Dispatch por `type`, campos desconocidos ignorados sin matar la conexión (§7.1) | `server/MessageRouter.kt` |
| I3 | `AUTH_REQUEST` → `AUTH_CHALLENGE` (nonce 16 B, transcript 152 B) | `server/AuthAdapter.kt` |
| I4 | `AUTH_RESPONSE` → `AUTH_OK` / `AUTH_FAIL`, verificado por `AuthVerifier` | `server/AuthAdapter.kt` |
| I5 | `AUTH_OK` firma el transcript de 120 B vía `RelayServer` | `server/AuthAdapter.kt` |
| I6 | `protocolVersion` confirmado en `AUTH_OK` (§17.2.3) | `server/JsonWireCodec.kt` |
| I7 | `MESSAGE` Base64 estándar → `ByteArray` → `RelayServer.deliverData` → Base64 | `server/MessageRouter.kt` |
| I8 | Base64 inválido se descarta entero, nunca parcialmente (§14.1) | `util/Base64Standard.kt` |
| I9 | Anti-spoofing delegado: `envelope.from` = peer autenticado | `RelayServer` (km-core) |
| I10 | Límite de 65 535 B aplicado antes de rutear (§15.3) | `RelayServer` (km-core) |
| I11 | `PEER_ONLINE` / `PEER_OFFLINE` serializados (§12.1) | `server/MessageRouter.kt` |
| I12 | `PING` / `PONG` de aplicación cada 30 s (§12.2) | `server/RelayWebSocketServer.kt` |
| I13 | `RELAY_ERROR` con `errorCode` (§16.1), codificado por `JsonRelayControlCodec` | `server/MessageRouter.kt` |
| I14 | `maxConnections` con cierre `1013 Try Again Later` | `server/RelayWebSocketServer.kt` |
| I15 | Frame binario rechazado con `1003 Unsupported Data` (§7) | `server/RelayWebSocketServer.kt` |

### Previsto, NO implementado

| # | Ítem | Nota |
|---|------|------|
| P1 | **Store-and-forward** | No implementado (§13.3). `MessageRouter.sendStored` y `sendRelayExpired` son la costura de serialización, lista y probada, pero **nada las dispara**: no hay politica de expiracion. `RELAY_ERROR` sí se emite, en los caminos de §I13. |
| P2 | **`serverNonce` en `AUTH_OK`** | §9.6 no lo lista, así que no se emite. Consecuencia honesta: `serverSignature` viaja sin material verificable para el cliente. No se resuelve aquí; es P6 del spec. |
| P3 | **`AUTH` server-side de `RelayClient`** | `RelayClient.handleAuthOk` no verifica `serverSignature`. El daemon lo firma correctamente igual (§I5); el cliente es el que no lo mira. |
| P4 | **Canal WebSocket binario** | §6.4, deliberadamente sin cerrar. Hoy se rechaza con `1003`. |
| P5 | **Emisión de `ACK` por el relé** | §11: `ACK` es control de aplicación. El daemon registra el `ACK` entrante y lo descarta. No lo reenvía. |
| P6 | **`SIGNAL` como tipo de §8** | El dialecto normativo **no define** cómo viajan SDP/ICE. `JsonWireCodec` lo implementa como extensión explícita con `kind` ∈ {`sdp_offer`, `sdp_answer`, `ice_candidate`, `contact_exchange`}, no como un tipo inventado del catálogo. |
| P7 | **Reconciliación `protocolVersion` string vs int** | §9.7. El cliente manda `"2.0"` (string); `AuthResponse.protocolVersion` es `Int`. `AuthAdapter` traduce sin judgement, porque ni `AuthVerifier` ni `RelayServer` miran ese campo. |
| P8 | **`AUTH_SESSION_TIMEOUT`** | El cliente no lo maneja (§18.2 P13). |

## Verificación

```bash
./gradlew :km-daemon:test
```

`JsonWireCodecMessageTest` cubre la propiedad central del spec:

1. **§6.2** — la carga de `MESSAGE` sobrevive al viaje de ida y vuelta
   **byte a byte**, con los 256 valores posibles de byte y las tres
   longitudes de residuo que deciden el padding de Base64.
2. **§14.1** — un Base64 inválido (alfabeto equivocado, whitespace,
   longitud imposible) se descarta **entero**. No hay carga parcial.

Este módulo no lleva una suite de tests amplia a propósito: es el MVP del
daemon, y su cobertura no es el frente. El número de tests de `km-core` y
`km-webrtc` no baja.

## Licencia

MIT, como el resto de km-framework.

Depende de **Java-WebSocket 1.5.6**, que es **MIT**, no LGPL. Se verificó en
dos sitios independientes: el bloque `<licenses>` de su POM en Maven Central
y el fichero `LICENSE` del tag `v1.5.6` del repositorio upstream. La
licencia real del artefacto, no la que se le supone al proyecto.

Detalle en [`THIRD-PARTY-LICENSES/README.md`](../THIRD-PARTY-LICENSES/README.md).
