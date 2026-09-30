# Registro de skills y reglas del proyecto — km-framework

> **Creado:** 2026-09-30 · **Baseline:** 1187 tests, 0 fallos (km-core 1095 + km-webrtc 92)
> **Repositorio:** https://github.com/KemaMada/km-framework
> **Paquete raíz:** `com.km` (antes `com.keymessage.core`)

## Por qué existe este archivo

No había registro de proyecto, y eso ya produjo un fallo observable: en una misma
sesión, un subagente reportó `skill_resolution: injected` y otro
`fallback-path`. Sin registro, cada agente depende de que alguien recuerde el
historial entero. Las convenciones de este proyecto son específicas y **no se
deducen leyendo el código**: nacieron de errores concretos.

Uso: se resuelve **una vez** y en cada delegación se copia el bloque de reglas
compactas **literalmente** al prompt del subagente. Los subagentes **no** leen
este archivo.

---

## Reglas compactas

*Texto ya digerido, listo para inyectar. Esta es la sección que se consume.*

### Project Standards (auto-resueltas)

**Estado y ciclo**
- Baseline: **1187 tests, 0 fallos**. Cualquier cambio se compara contra eso. Si el
  número baja, algo se perdió: investigar antes de dar por bueno.
- Ciclo obligatorio: **spec → implementación → tests → mutación**. Una fase no está
  cerrada hasta que la mutación correspondiente la ha atacado.
- **Checkpoints cerrados, no se tocan** sin justificación explícita: 3Q.5.3,
  retransmisión idempotente (`MAX_RESEND`), cardinalidad de outbounds, HMAC,
  cifrado at-rest, semántica nueva de ACK, `SecureFrame` v1 (44 B, header completo
  = AAD), criptografía del ratchet.
- Engram es el almacén. Modo `engram`, **no se crea `openspec/`**. `mem_save` **sin
  `project`** (autodetección activa). Si devuelve `judgment_required`: iterar
  `candidates[]`, un `mem_judge` por entrada con **su** `judgment_id`.

**Ejecutar tests sin engañarse**
- `-Dorg.gradle.java.home=/usr/lib/jvm/java-27-openjdk` **obligatorio**;
  `gradle.properties` apunta a java-26 y no es el que se usa.
- **Borrar `*/build/test-results` antes de cada corrida relevante.** Un XML viejo
  muestra fallos anteriores, y si falló `compileTestKotlin` los resultados ni
  siquiera son de esta ejecución.
- **Un solo patrón `--tests` por invocación.** Nunca lista por comas.
- Comprobar que el XML **es nuevo** y que el **conteo coincide**. Registrar `exit`
  y número de tests.
- Objetivo inexistente → **`INVALIDA(sin XML)`**. No es "mutación no detectada": 
  es una corrida que no ocurrió. Confundirlas ya ha pasado.

**Controles negativos**
- **Un control negativo debe compilar y ejecutarse antes de ser evidencia.** Una
  violación con símbolo inexistente impide compilar, el test no corre y la prueba
  queda **falseada**. Ya ocurrió.
- Validar el detector contra un ejemplo muerto **antes** de confiar en él.
- Elegir datos no confusos: contenido **no constante** (una lectura parcial que
  conserva la longitud es indistinguible de un entero con relleno de ceros) y
  tamaños **distintos** entre la unidad anterior y la nueva.

**Renombrados**
- Un nombre de paquete aparece de **tres** formas: (1) **punto** — `package`,
  `import`, FQN en cadena; (2) **barras**, rutas dentro de cadenas, **invisibles a
  un grep de puntos** — `tools/mutaciones/_comun.py` localiza el medio **por
  ruta**; (3) **nombres de clase escritos a mano**, en `ProcessBuilder` o similar.
  Sin (2) la campaña de mutaciones muta un fichero inexistente y reporta "mutación
  no detectada" **en silencio**.

**Detección de defectos**
- **Estructural** (mira el código, demuestra que cambió) ≠ **conductual** (mide
  comportamiento). *Detectado* no es siempre *demostrado*. Caso registrado:
  `MEDIO-14` detecta `force(true)` en el código, **no** durabilidad ante pérdida de
  energía o caída de kernel, no observable desde un proceso ni con `SIGKILL`
  (el page cache sobrevive a la muerte del proceso).
- Test que toma el snapshot **después** de la operación que pretende congelar no
  prueba nada (`KM52V2-06`).
- **No afirmar causalidad que la evidencia no sostiene.** Precedente: se aceptó
  "10 fallos preexistentes" y eran del bump v1→v2, con firma de 4 bytes igual al
  `count` del bloque que faltaba.
- Leer el **mensaje completo** del fallo antes de cambiar una expectativa.
  Precedente: helper con precondición mal calculada (`inboundLenOverride`, 32
  bytes de sobra) contaminó una fase entera.

**Flaky**
- `km-webrtc` levanta `PeerConnection` reales de libwebrtc; no es suite unitaria.
- Inestabilidad conocida: `N10-02`, `N10-05`, `N12-16`, `N3-03`, `R13-04`.
- Un fallo puntual es **evidencia de temporización, no demostración de que no
  pueda ser regresión**. Documentar la evidencia concreta (`N10-02`: 1 fallo en 7
  observaciones) sin afirmar causalidad.

**Límites arquitectónicos**
- **`km-core` sin `dev.onvoid` ni dependencia de `km-webrtc`.** La dependencia va
  en un sentido solo.
- `km-core` es Kotlin/JVM puro, sin plugin de Android. No hay `group` ni
  `namespace` que mantener.
- `RelayServiceImpl` es capa aparte (KM-0004): no se toca desde aquí.
- La criptografía vive en el framework, **nunca en un consumidor**. `km-desktop`
  (TUI, por construir) consumirá `km-core`/`km-webrtc` y **no alojará lógica de
  protocolo**.
- Sin reflexión sobre Kotlin. `assertThrows` con `import
  org.junit.jupiter.api.assertThrows` (el de Kotlin).
- Test doubles primero. El estado criptográfico se prueba con `encrypt()` byte a
  byte, **nunca con `stateFingerprint()`**: no cubre el escalar DH privado.

**Publicación y honestidad**
- La documentación pública no afirma nada que el código no sostenga: sin
  `production-ready`, sin `battle-tested`, sin auditorías, sin benchmarks, sin
  usuarios en producción. Dependencias verificadas **en el artefacto**; licencia
  no declarada → se marca como tal, no se supone. No inventar titulares de
  copyright.
- Publicar es **verificar desde un clon limpio**: clonar, compilar y correr la
  suite. No basta `git push`. No reescribir historial remoto: fusionar con
  `--allow-unrelated-histories` y conservar el commit inicial.

---

## Tabla de skills del usuario

Escaneadas `~/.config/opencode/skills/` el 2026-09-30. No hay skills de proyecto
(`.claude/skills`, `.agent/skills`, `skills/` no existen).

| Skill | Disparador | ¿Aplica aquí? |
|---|---|---|
| `judgment-day` | "judgment day", "doble review", "que lo juzguen" | **Sí**, bajo demanda del usuario. Revisión adversarial doble. |
| `skill-creator` | crear una skill nueva, documentar patrones para IA | **Sí**, si se repite un patrón. Ejemplo: este registro. |
| `branch-pr` | abrir PR, preparar cambios para revisión | **No.** Flujo de "Agent Teams Lite", otro proyecto. |
| `issue-creation` | crear issue en GitHub | **No.** Flujo de "Agent Teams Lite", otro proyecto. |
| `go-testing` | tests en Go, `teatest`, cobertura | **No.** Es para `Gentleman.Dots` (Go). Este proyecto es **Kotlin/JVM**. |

Las tres marcadas "No" **no deben inyectarse** en delegaciones de este proyecto:
pertenecen a repositorios distintos y sus reglas no aplican a un build de Gradle
Kotlin. `go-testing` en particular trataría de aplicar patrones de Go a una suite
JVM, que es exactamente el error que hay que evitar.

Las skills `sdd-*` y `_shared` quedan fuera por definición: son flujo de trabajo
SDD, no convenciones de código.

---

## Estructura del proyecto

| Ruta | Qué es |
|---|---|
| `km-core/` | El framework. Kotlin/JVM puro, sin Android, sin `dev.onvoid`. 16 paquetes. |
| `km-webrtc/` | Transporte WebRTC: `ManagedWebRtcPeer`, `RealWebRtcTransport`, `RelaySignaling`, `WebRtcEstablishment`, `WebRtcPlatformDetector`. |
| `km-id-reference/` | Referencia **normativa** de identidad y autenticación, en **Python**. No es código de producción. |
| `docs/rfc/` | RFCs de protocolo (`KM-0001`…`KM-0007`…). Fuente de verdad del diseño. |
| `tools/` | Arnés de pruebas: `mutprobe.py`, `fallos.py`, `mutaciones/m1..m6`. |
| `THIRD-PARTY-LICENSES/` | Licencias de dependencias, verificadas en el artefacto. |
| `app/` | **No está en km-framework.** Va a `km-android`. 13 ficheros con imports `com.keymessage.core.*` que no compilarán al depender de la versión renombrada. |

Paquetes de `km-core` bajo `com.km`: `identity` (antes `kmid`), `auth`, `negotiation`
(antes `km7`, KM-0007), `ratchet`, `x3dh`, `frame` (antes `sf`, SecureFrame),
`unit` (antes `km52`, unidad persistida), `crypto`, `node`, `protocol`,
`messaging`, `transmit`, `storage`, `codec`, `model`, `api`, `integration`.

Los **nombres de tipo** conservan el identificador de especificación
(`Km7Negotiation`, `Km52Codec`, `KmIds`, `KmSchemas`): el renombrado fue de
paquetes, no de tipos.

## Dependencias de terceros

| Dependencia | Versión | Licencia |
|---|---|---|
| `bcprov-jdk18on` | 1.86 | MIT |
| `jackson-module-kotlin` | 2.15.2 | Apache-2.0 |
| `net.i2p.crypto:eddsa` | 0.3.0 | CC0-1.0 (sin titular declarado) |
| `webrtc-java` | 0.19.0 | Apache-2.0 (declarada en el POM padre) |
| libwebrtc nativo | 0.19.0 | BSD-3 + concesión de patentes de Google |
| `kotlin-stdlib` | 2.2.10 | **No declarada** |
| `junit-jupiter` | 5.10.0 | **No declarada** |

El proyecto es **MIT**, que no incluye concesión de patentes; la que hay entra por
`libwebrtc` nativo, que es BSD-3 con patentes de Google.

## Consumidores

`km-framework` es el **núcleo público estable del protocolo**. Encima:

- `km-android` — la app Compose existente, pendiente de extraer.
- `km-desktop` — TUI Linux/Windows, **no existe todavía**.
- futuros: `km-cli`, `km-ios`, y cualquier tercero.

Regla: son **consumidores del framework**, nunca fuentes alternativas de la lógica
de protocolo.
