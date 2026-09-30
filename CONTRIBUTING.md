# Cómo contribuir a km-framework

Gracias por mirar esto. Lee el [README](README.md) primero: explica qué es y qué
no es el proyecto, y qué está implementado de verdad. Si vas a tocar
criptografía, lee también [SECURITY.md](SECURITY.md).

## Antes de nada: qué no se toca aquí

- **`app/`** (el cliente Android con Compose) **no pertenece a este
  repositorio**. Vive en `km-android`. `/app/` está en `.gitignore` y no está en
  el índice de git. No lo borres de tu copia local esperando que se suba.
- **Los RFC mandan sobre el código**, y el código manda sobre los RFC cuando el
  RFC es `Draft`. Si tu cambio altera el protocolo, actualiza `docs/rfc/` en el
  mismo commit.
- **No toques `km-id-reference/vectors/` ni `km-id-reference/invalid/` a mano.**
  Son la referencia normativa y están congelados por `FROZEN.sha256`. Se
  regeneran ejecutando `python3 generate.py`, y el cambio de esos ficheros tiene
  que ser un cambio de especificación, no un arreglo de un test.

## Requisitos

| Herramienta | Versión |
|-------------|---------|
| JDK | 11 (toolchain de compilación). 11 o superior para ejecutar Gradle. |
| Gradle | No lo instales: usa el wrapper `./gradlew` (9.5.0). |
| Python | 3.x, solo para `km-id-reference/` y `tools/`. |

`gradle.properties` fija `org.gradle.java.home` a una ruta concreta. Si tu
máquina no la tiene, cámbiala en tu copia local o bórrala; no es un requisito
del proyecto y no debería acabar en un PR.

## Compilar y testear

```sh
./gradlew build                    # compila y ejecuta todos los tests
./gradlew :km-core:test            # km-core   → 1095 tests
./gradlew :km-webrtc:test          # km-webrtc →   92 tests
./gradlew test                     # los dos módulos
```

Un solo test o una sola clase:

```sh
./gradlew :km-core:test --tests 'com.km.transmit.FileTransmitUnitStoreTest'
./gradlew :km-webrtc:test --tests 'com.km.webrtc.ManagedWebRtcPeerTest'
```

Ambos módulos usan JUnit 5 (`useJUnitPlatform()`).

### Sobre `km-webrtc`

`km-webrtc` **no** es una suite unitaria. Levanta `PeerConnection` reales de
libwebrtc, así que:

- Necesita el artefacto nativo con el clasificador de tu plataforma. El build lo
  detecta solo a partir de `os.name` y `os.arch` entre siete clasificadores
  soportados: `linux-x86_64`, `linux-aarch64`, `linux-aarch32`, `macos-x86_64`,
  `macos-aarch64`, `windows-x86_64`, `windows-aarch64`. Fuera de esa lista el
  build falla a propósito, con un mensaje que dice cuáles están soportados.
- Si construyes con JDK 21 o superior, el build añade
  `--enable-native-access=ALL-UNNAMED`. Con el toolchain 11 ese flag no existe y
  webrtc-java funciona sin él.
- Es lento y dependiente del entorno. Si un test de `km-webrtc` falla solo en tu
  máquina, dilo en el PR en vez de borrarlo.

#### Tests con inestabilidad conocida

Estos tests han fallado de forma intermitente. **No son estables por diseño, y
tampoco son prueba de "esto no puede ser una regresión"**: un fallo puntual es
evidencia de que hay temporización detrás, no una demostración de que jamás
pueda indicar un defecto real. Si uno falla, hay que investigarlo.

| Test | Qué mide |
|------|----------|
| `N10-02` | Datos de conexión cerrada no llegan a `downstream()` |
| `N10-05` | Mensaje de DataChannel obsoleto se descarta |
| `N12-16` | Cierre de un binding no afecta al otro peer |
| `N3-03` | Estado de conexión reportado por el transporte |
| `R13-04` | SDP offer atraviesa el relay y llega al answerer |

`N10-02` se añadió a esta lista el 2026-09-30, tras el renombrado de paquetes a
`com.km`. La evidencia que hay es esta: falló **una vez en siete observaciones**
(1 fallo en la primera corrida completa post-renombrado, 0 en tres corridas
completas posteriores y 0 en tres corridas aisladas). No es uno de los tests que
ya se sabían inestables, y el renombrado no tiene acoplamiento con el ciclo de
vida de ICE. La clasificación de "flaky" es la mejor lectura de esa evidencia, no
una conclusión lógica: no se ha probado que `N10-02` no pueda ser jamás una
regresión, y no debe tratarse como si se hubiera probado.

### Antes de abrir un PR

```sh
./gradlew build
```

Si `./gradlew build` pasa, el PR es discutible. Si falla, el PR se discusses
después. Este proyecto no tiene CI configurado: **nadie va a ejecutar los tests
por ti**. El estado del PR es exactamente lo que dice tu máquina.

## Estilo

- **Kotlin official style**, el que fija `kotlin.code.style=official` en
  `gradle.properties`.
- Indentación de 4 espacios. Fin de línea `\n`. Sin tabs.
- Nombres en inglés, comentarios en español. Es la convención que ya sigue todo
  `km-core`, y no es arbitraria: los comentarios explican el *porqué* en el
  idioma en el que se Pensaron las especificaciones (`docs/rfc/`), y los
  identificadores viajan a otras implementaciones.
- Comentarios que explican **por qué**, no qué. El código ya dice qué hace. Si un
  comentario reescribe la línea siguiente, bórralo.
- Los términos del protocolo se escriben como los define
  `docs/rfc/KM-0000-terminology.md`: `Identity`, `Device`, `Client`, `Peer`,
  `Relay`, `Session`, `Capability`, `Transport`, `Protocol Message`,
  `User Message`, `Storage`. No los traduzcas al escribir código, y no
  inventes sinónimos.
- Sin números mágicos en criptografía. Si un tamaño, un offset o un `info` de
  HKDF aparece en el código, es una constante con nombre en el objeto de
  especificación correspondiente (`SecureFrameSpec`, `Km52Spec`, `Km7Spec`…), y
  documentada con la sección del RFC de la que sale.

## Commits convencionales

Usamos [Conventional Commits](https://www.conventionalcommits.org/):

```
<tipo>(<ámbito>): <descripción en imperativo, < 72 caracteres>
```

Tipos permitidos:

| Tipo | Para qué |
|------|----------|
| `feat` | Funcionalidad nueva. |
| `fix` | Corrección de un fallo. |
| `docs` | Solo documentación. |
| `refactor` | Cambio de comportamiento que no debería notarse. |
| `test` | Solo tests. |
| `perf` | Rendimiento. |
| `build` | Gradle, toolchain, dependencias. |
| `ci` | Automatización. |
| `chore` | Lo que no encaja en los anteriores. |
| `revert` | Reverts. |

Ámbitos habituales: `core`, `webrtc`, `identity`, `auth`, `ratchet`, `frame`,
`unit`, `transmit`, `negotiation`, `crypto`, `node`, `protocol`, `docs`, `tools`.

Ejemplos:

```
feat(transmit): publica la unidad con rename en vez de copy+delete
fix(frame): rechaza SecureFrame con longitud declarada mayor que el buffer
docs(rfc): resuelve la ambiguedad de AAD en KM-0004 §7
refactor(core): renombra com.keymessage.core.* a com.km.*
```

Un commit, un cambio. Si necesitas "y" en el asunto, probablemente son dos
commits.

Los cambios que rompen la API o el formato de wire se marcan con `!` tras el
tipo y con un pie `BREAKING CHANGE:`:

```
refactor(core)!: renombra los paquetes a com.km.*

BREAKING CHANGE: todos los imports pasan de com.keymessage.core.* a com.km.*.
No hay shim ni alias; la API pública cambia por completo.
```

## `tools/`: el andamiaje de pruebas

`tools/` contiene Python, no Kotlin, y no está en la build de Gradle.

### Campaña de mutaciones

`tools/mutprobe.py` es un arnés de medición de mutaciones. Su trabajo es
comprobar que los tests **detectan** la ausencia de una garantía, no que la
garantía está.

```sh
# línea base: el test pasa sin mutación
tools/mutprobe.py --label baseline --pattern 'com.km.transmit.FileTransmitUnitStoreTest'

# con mutación: el test TIENE que fallar
tools/mutprobe.py --label mut1 --pattern 'com.km.transmit.FileTransmitUnitStoreTest' \
    --mutate tools/mutaciones/m1_escribir_en_el_hueco.py

# qué tests fallaron y por qué
python3 tools/fallos.py 'FileTransmitUnitStoreTest'
```

`tools/mutaciones/` contiene las mutaciones. Cada una borra deliberadamente una
garantía del medio físico:

| Mutación | Qué rompe |
|----------|-----------|
| `m1_escribir_en_el_hueco.py` | Escribe directamente en el hueco, sin temporal: un corte convierte una escritura a medias en una unidad aparentemente entera. |
| `m2_sin_force.py` | Quita el `force(true)` del **canal** de escritura y deja el del directorio: la sincronización del fichero desaparece aunque el token `force(true)` siga apareciendo en `sync()`. |
| `m3_sin_atomic_move.py` | Elimina `ATOMIC_MOVE` de las dos publicaciones. En Linux el `rename` sigue siendo un `rename`, así que no cambia el inodo: solo cambia la garantía declarada. Es la mutación que solo se ve leyendo el código. |
| `m4_copy_to_y_delete.py` | Sustituye el `rename` por `copyTo` + `delete`: abre una ventana en la que se pueden perder la unidad nueva **y** la anterior. |
| `m5_lectura_parcial.py` | Acepta una lectura parcial: una sola llamada a `read()` y se devuelve lo que haya cabido, sin comprobar que se leyó todo. |
| `m6_ignorar_integridad.py` | Ignora la verificación de integridad: un hueco corrupto pasa a contestar `null`, es decir, "el medio está vacío". Una afirmación sobre el estado hecha por un medio dañado. |

El arnés está escrito a propósito para ser paranoido, y conviene entender por
qué antes de tocarlo. La lección que lo motivó: **en este proyecto un arnés de
mutación ya afirmó haber ejecutado cosas que no había ejecutado**, porque se
aceptaba el XML de una corrida anterior como si fuera el de la actual. Por eso:

1. Borra el directorio de resultados antes de cada ejecución.
2. Admite **un** patrón `--tests` por ejecución y rechaza una lista con comas.
3. Exige un XML nuevo: la marca de tiempo tiene que ser posterior a un testigo
   escrito justo antes de lanzar Gradle, y el nombre del XML tiene que terminar en
   el nombre de la clase pedida.
4. Si no hay XML nuevo, el resultado es `INVALIDA(sin XML)`, que **no** es "mutación
   no detectada": es "no se midió nada", y se reporta como tal.
5. Registra el `exit` real de Gradle y el número de tests de la corrida.

`mutprobe.py` tiene `JAVA_HOME` y `RESULTS` hardcodeados. Si los mueves,
mueve también el arnés.

### `tools/`

No hay más cosas en `tools/`. No hay un helper de `gh`, ni scripts de release,
ni linters. Si necesitas alguno, escríbelo y ponlo aquí.

## `km-id-reference/`

Implementación de referencia normativa en Python. Ver su
[`README.md`](km-id-reference/README.md).

```sh
cd km-id-reference
python3 selfcheck.py   # verifica los fixtures sin escribir nada
```

Si tocas `km-core/src/main/kotlin/com/km/identity/`, sus tests verifican
byte a byte contra los vectores de aquí. Si un test de `km-core` falla porque
cambiaste la derivación, o arreglaste el código o tienes que regenerar vectores
**y** cambiar la especificación. Lo primero no es una opción si el test falla
por una razón criptográfica.

## Pull requests

- Un PR, un tema. Si son dos temas, son dos PRs.
- Explica **por qué**, no **qué**. El diff ya dice qué.
- Si tu PR cambia un RFC, di explícitamente qué versión pasa a qué y actualiza la
  tabla de historial del propio RFC.
- Si tu PR toca criptografía, prueba a escribir en el PR **cómo lo has
  attackeado**. Un PR criptográfico sin intento de romperlo no está acabado.
- Si no puedes completar algo, dilo. Un PR parcial y honesto vale más que uno
  completo y cuestionable.

## Licencia de las contribuciones

Al contribuir aceptas que tu trabajo se licencia bajo la [MIT](LICENSE) del
proyecto, con `Copyright (c) 2026 KemaMada`.

**Ten en cuenta que MIT no incluye concesión explícita de patentes.** Si tu
contribución incluye una invención patentable, decláralo en el PR para que
mantengamos registro de ello. Ver [SECURITY.md](SECURITY.md).
