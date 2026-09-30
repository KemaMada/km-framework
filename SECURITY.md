# Política de seguridad

km-framework implementa criptografía. Un hallazgo de seguridad aquí no es un bug
más: puede ser una pérdida de confidencialidad, una firma falsificable o un
ratchet que no ratchetea. Trátalo como lo que es.

## Si encuentras un fallo de seguridad

**No abras un issue público.**

Un issue público es visible para todo el mundo en el momento en que lo abres, y
se archiva de forma permanente. Para un fallo criptográfico, la ventana entre
"descubierto" y "parcheado" es exactamente la ventana en la que alguien puede
aprovecharse. Publicar el detalle primero no es prudencia: es regalar el
exploit.

Tampoco abras un issue si **sospechas** que es un fallo, aunque no puedas
demostrarlo. Una sospecha fundada vale más que un issue cerrado como "no
reproducible".

### Cómo reportar

Abre un **issue privado** de tipo *Security advisory* en el repositorio:

<https://github.com/KemaMada/km-framework/security/advisories/new>

Si eso te da problemas, o prefieres no usar GitHub, pide contacto privado a
**KemaMada** por GitHub.

### Qué incluir

| Campo | Por qué lo pedimos |
|-------|--------------------|
| Qué falla, en una frase | Para triar. |
| **Pasos de reproducción** | Un caso mínimo que alguien pueda ejecutar sin preguntarte nada. |
| Versión o commit | En un repo pre-1.0, el commit es lo que importa. |
| Impacto: qué puede hacer un atacante | Confidencialidad, integridad, disponibilidad, y quién lo puede hacer. |
| Si es un ataque de red, el vector exacto | Si necesita estar presente, o puede hacerlo un pasivo. |
| Adjuntos: script, prueba de concepto, traza | Como prefieras. |

**Cifra el adjunto si lo mandas por correo.** No mandes un exploit funcional en
claro por un canal que no cifra.

### Qué esperar

- **Acuse de recibo**: pronto, pero no hay un plazo comprometido. No estamos en
  un proceso de disclosures formalizado, así que no hay ventanas de 90 días, ni
  coordinadores de seguridad, ni recompensa, ni SLA. Si necesitas una garantía de
  ese tipo, este repositorio no es el sitio adecuado todavía.
- **Triage**: revisamos, reproducimos, y confirmamos o rebotamos.
- **Corrección**: un commit, y una entrada en `CHANGELOG.md` que describa el
  problema **en términos de lo que pasó**, no de qué línea cambió.
- **Crédito**: en el aviso o en el changelog, como prefieras, incluido el
  anonimato.

Si el fallo es grave y la ventana de exposición es pequeña, no esperes al triage
para trabajar en un parche: mándalo igualmente, aunque esté sin revisar.

## Reportes que **no** son vulnerabilidades

Para no gastar el canal de disclosures en cosas que no lo son:

| Caso | Dónde va |
|------|----------|
| Un bug normal, sin implicación de seguridad | Issue público. |
| Un test que falla | Issue público, con el comando y la salida. |
| "Creo que X3DH es débil", sin una construcción concreta | Discussion pública. Este es un proyecto abierto, y la crítica pública al diseño es sana y bienvenida. |
| Un vector de ataque **teórico** que no llega a comprometer nada | Discussion pública. |
| Vulnerabilidad en Bouncy Castle, `net.i2p.crypto` o libwebrtc | Arriba, **al proyecto de origen**, no a este repositorio. |

Sobre esto último, para que conste: las primitivas de `km-core` vienen de
terceros — Bouncy Castle (X25519, HKDF-SHA256, ChaCha20-Poly1305) y
`net.i2p.crypto:eddsa` (Ed25519). Un fallo criptográfico en ellas es un fallo de
esos proyectos. Lo que sí es nuestro es **cómo las usamos**: qué claves entran en
HKDF, qué se autentica como AAD, qué ordena el ratchet, qué se persiste y qué se
rechaza.

## El estado de seguridad de este proyecto, sin adornos

Vale la pena decirlo claro, porque es un proyecto criptográfico:

- **No ha habido ninguna auditoría de seguridad.** Ni externa, ni interna
  revisada por terceros. No hay informe, no hay threat model publicado, no hay
  penetration test, no hay *red team*.
- **No hay afirmaciones de cumplimiento.** Nada de SOC 2, ISO 27001, FIPS 140-3,
  GDPR, ni "auditado".
- **No está en producción** y no lo usa nadie conocido. No hay superficie
  expuesta y por tanto no hay historial de CVEs. Eso no es una buena noticia: es
  la consecuencia de que no hay usuarios.
- **No hay proceso de disclosures formalizado.** No hay plazos comprometidos
  (el documento que estás leyendo es el primer intento), ni revisor de seguridad
  asignado, ni sello de seguridad de OpenSSF.
- **No es *production-ready*.** No lo pongas en el camino de nada que importe
  hasta que alguien con experiencia criptográfica lo revise de verdad.

Lo que sí hay, y es real:

- 1187 tests, 0 fallos (1095 en `km-core`, 92 en `km-webrtc`) a 2026-09-30.
- Vectores de prueba congelados, verificados byte a byte contra una implementación
  de referencia normativa (`km-id-reference/`), con fixtures negativos que
  fallan **por una sola razón** cada uno.
- Una campaña de mutaciones sobre la persistencia atómica, con la disciplina de
  no declarar "no detectada" lo que no se midió.
- Separación de dominio explícita en cada derivación, y el invariante de que
  `identityId` y `deviceId` **nunca** se usan como material criptográfico.

## Limitaciones conocidas, asumidas y documentadas en el código

| Limitación | Dónde está documentada |
|------------|------------------------|
| **`SecureFrame` no fragmenta.** `length` es un entero de 2 bytes, así que el ciphertext se acota a 65535 bytes. Cargas mayores requieren fragmentación, que no está implementada: hay que trocear antes, en la capa de aplicación. | `com.km.frame.SecureFrame`, invariante 5 |
| **`com.km.storage` es solo en memoria.** `InMemoryMessageStore`, `InMemoryOfflineQueue`, `InMemoryRelayStore`, `InMemoryDuplicateStore`. Si tu proceso muere, se pierden. Lo durable es la unidad criptográfica (`com.km.transmit`), no el almacén de mensajes. | `com.km.storage` |
| **`RelayServer` es de pruebas.** Sin autenticación de operador, sin rate limit global, sin persistencia entre reinicios, sin métricas. No lo despliegues. | `com.km.node.RelayServer` |
| **Sin ocultación de metadatos.** `SecureFrame` cifra el contenido, pero el transporte y los tamaños siguen siendo observables. El propio KM-0001 lo asume: el análisis de tráfico queda fuera del modelo de seguridad. | `docs/rfc/KM-0001`, §16 "No protege" |
| **El protocolo no aplica, documenta.** Si alguien despliega un relé que almacene o descifre mensajes, el protocolo no lo impide: lo prohíbe por especificación y no lo fuerza por mecanismo. | `docs/rfc/KM-0000`, §Relay |
| **Sin resistencia a DoS.** Declarado fuera del modelo de seguridad. | `docs/rfc/KM-0001`, §16 |

## Si una dependencia tiene un CVE

Las dependencias de `km-core` y `km-webrtc` se declaran explícitamente en los
ficheros `build.gradle.kts` y en `gradle/libs.versions.toml`, así que la
actualización hoy es manual: no hay `dependabot`, ni `renovate`, ni escaneo
automático. Si ves un aviso abierto en Bouncy Castle, `net.i2p.crypto` o
`webrtc-java`, mándalo por el canal de arriba. Se arregla rápido, porque es una
dependencia y no un error de diseño nuestro.

El detalle de qué dependencias son y con qué licencia está en
[`THIRD-PARTY-LICENSES/`](THIRD-PARTY-LICENSES/).
