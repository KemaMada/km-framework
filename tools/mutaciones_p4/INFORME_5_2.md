# Informe de mutaciones — p4-relay-backend (tarea 5.2/5.4)

Todas las corridas con: `-Dorg.gradle.java.home=/usr/lib/jvm/java-27-openjdk`,
`tools/mutprobe.py` (un solo patrón `--tests` por invocación, XML nuevo exigido,
`INVALIDA(sin XML)` distinto de "no detectada"), respaldo por `_comun_p2` en
`KM_P2_BACKUP_DIR=/tmp/opencode/p4-backups` (sin git). Cada mutación compila y
corre; el revert se valida por md5.

## Tabla

| Mut | Fichero mutado | Test que la detecta | Fallo esperado | Resultado | Revert md5 |
|---|---|---|---|---|---|
| M1-a | `km-transport/.../JvmRelayTransport.kt` (`sendData` no-op) | `RelayBackendFaceTest` | RB-01 rojo | **DETECTADA** (1/4) | OK |
| M1-b | `km-core/.../node/RealRelayTransport.kt` (`from` = local) | `RealRelayTransportTest` | R3-13 rojo | **DETECTADA** (1/13) | OK |
| M2-a | `km-core/.../node/PeerTransportManager.kt` (sin `cablearRecepcion`) | `ManagerReceiveWiringTest` | MW-01/05/06 rojo | **DETECTADA** (4/6) | OK |
| M2-b (=M6 orc) | `km-core/.../node/PeerTransportManager.kt` (`TransportEventCallbacks()` fresco) | `ManagerReceiveWiringTest` | MW-01/02 rojo | **DETECTADA** (4/6) | OK |
| M2-d | `km-transport/.../JvmRelayTransport.kt` (clasificación off) | `LegacyAndPeerChannelRoutingTest` | LR-02 rojo | **DETECTADA** (1/4) | OK |
| M3-a | `km-core/.../messaging/SecureMessagingSession.kt` (sin filtro `from`) | **`SecureSessionPeerIsolationTest`** | aislamiento roto | **DETECTADA** (2/4) | OK |
| M3-b | `km-transport/.../JvmRelayTransport.kt` (`from` constante) | **`RelaySocketSecureE2ETest`** | E2E-01 rojo | **DETECTADA** (2/2) | OK |
| M3-c | `km-transport/.../RelayPeerChannelDriver.kt` (sin `markConnected`) | `RelayPeerChannelDriverTest` | PC-01/02 rojo | **DETECTADA** (2/3) | OK |
| M4-a | `km-core/.../frame/SecureFrameCodec.kt` (AAD sin header) | `SecureFrameProtectorTest` | O6-04/05/06 rojo | **DETECTADA** (4/21) | OK |
| M4-b | `km-core/.../frame/SecureFrame.kt` (`HEADER_LENGTH` 44→43) | `SecureFrameCodecTest` | O5-18 rojo | **DETECTADA** (1/19) | OK |
| M5-a | `km-core/.../messaging/SecureInbound.kt` (entrega sin sesión) | `ManagerReceiveWiringTest` | MW-05 rojo | **DETECTADA** (1/6) | OK |
| M5-b | `km-core/.../messaging/SecureInbound.kt` (no-frame como frame) | `ManagerReceiveWiringTest` | MW-06 rojo | **DETECTADA** (1/6) | OK |
| M5-c | `km-core/.../messaging/SecureInbound.kt` (rechazo no contado) | `ManagerReceiveWiringTest` | MW-05/06 rojo | **DETECTADA** (2/6) | OK |
| M5-d | `km-daemon/.../server/MessageRouter.kt` (reapunta al emisor) | `RelayLegacyEchoTest` | LE-01 rojo | **DETECTADA** (1/1) | OK |
| M6 (spec) | `km-core/build.gradle.kts` (+`ktor-client-cio`) | `KmCoreNoWebRtcDependencyTest` | (predicho) guard rojo | **NO DETECTADA** (0/6) | OK |
| M7-a | `km-transport/.../JvmRelayTransport.kt` (reescrito al mover: ACK no entregado) | `RelayAckEndToEndTest` | suite roja | **DETECTADA** (2/3) | OK |
| M7-b | `km-webrtc/.../ManagedWebRtcPeer.kt` (bypass manual doble) | `SecureMessagingOverWebRtcTest` | doble entrega | **NO DETECTADA** (0/4) | OK |
| M8-a | `km-transport/.../RelayDataCodec.kt` (+`com.km.crypto`) | `RelayTransportCryptoSeparationTest` | guard R12 rojo | **DETECTADA** (2/8) | OK |
| M8-b | `km-transport/build.gradle.kts` (`project(":km-core")`) | (predicho) `Duplicate class` en consumidor | build rojo | **NO DETECTADA** (0/1) | OK |

## Correcciones al plan (predicción de detector equivocada)

1. **M3-a**: la spec predecia MW-05. Medido: `ManagerReceiveWiringTest` sale
   **VERDE** (0 fallos). Motivo: `MW-05` cuenta `unknownPeerFrames` en
   `SecureInbound.classifyOnly` (observador GLOBAL), que corre igual sin el
   filtro de la sesion; y el intento de descifrar el frame ajeno no produce
   entrega, luego `delivered` sigue 0. El detector REAL es
   **`SecureSessionPeerIsolationTest`** (2 fallos). M3-a queda DETECTADA, pero
   por otro test.
2. **M3-b**: la spec predecia LR-02/R3-13. Medido: `LegacyAndPeerChannelRoutingTest`
   sale **VERDE** (0 fallos): LR-02 comprueba que el byte va al compositor de pares
   pero NO afirma la identidad del `from`. El detector REAL es
   **`RelaySocketSecureE2ETest`** (2/2 rojo): al recibir `from == identidad local`,
   el filtro de la sesion descarta el frame y `delivered == 0`, que E2E-01 si pinza.

## Mutaciones NO DETECTADAS: hallazgos declarados, no fallos del arnes

- **M6(spec)** — `KmCoreNoWebRtcDependencyTest` NO ve una dependencia de red en
  km-core. Es correcto y estaba previsto: ese guard solo cubre `km-webrtc`. R-6
  ("km-core sin red") NO tiene guardia; su evidencia es declarada
  (`git diff km-core/build.gradle.kts = 0`). El guard NO es falso: cubre lo que su
  KDoc dice. Lo que se declara es que **no** cubre red.
- **M7-b** — `SecureMessagingOverWebRtcTest` NO detecta el bypass manual doble.
  El segundo despacho por `previo.onDataReceived` reentra en la sesion y solo
  incrementa `rejectedFrames` (replay); ningun test de km-webrtc afirma ese
  contador ni un conteo de entregas ≥ 2. **Hueco de cobertura declarado**: la
  doble entrega es real y hoy no es observable por test en km-webrtc.
- **M8-b** — `Duplicate class` NO es reproducible en este repositorio. Se
  midieron DOS formas: (a) `api(project(":km-core"))` en `km-transport`, que deja
  `:km-transport:test` verde (km-desktop consume el ARTEFACTO, no el proyecto); y
  (b) `implementation(project(":km-core"))` en `km-desktop` conservando el
  artefacto de km-transport, que da `:km-desktop:compileKotlin` **BUILD
  SUCCESSFUL** porque Gradle SUSTITUYE el modulo externo `com.km:km-core` por el
  proyecto homonimo (module substitution) y las dos formas colapsan en una. La
  afirmacion del KDoc describe el riesgo de un consumidor FUERA del composite
  build, no medible desde dentro. La eleccion del artefacto sigue siendo la
  correcta; lo que se corrige es que su consecuencia observable aqui NO es un
  `Duplicate class`.

## Suite completa (tarea 5.1)

| Módulo | Baseline (esta sesión) | Final | Fallos | Exit |
|---|---|---|---|---|
| km-core | 1388 | **1388** | 0 | 0 |
| km-transport | 70 | **70** | 0 | 0 |
| km-desktop | 43 | **43** | 0 | 0 |
| km-daemon | 49 | **49** | 0 | 0 |
| km-webrtc | 92 | **92** | 0 | 0 |

Guardias km-core re-confirmadas verdes: `ChainCryptoSeparationArchTest` (9/0) y
`KmCoreNoWebRtcDependencyTest` (6/0). Guardia km-transport `RelayTransportCryptoSeparationTest`
(8/0). Ninguna mutación deja residuo: `--verify` LIMPIO en los 18 ficheros
respaldados.
