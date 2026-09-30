# km-id-reference

Implementación de referencia de **KM-ID-0001** (Identity, Devices and Contact
Bundles).

Su único propósito es demostrar que la especificación produce **bytes
deterministas y reproducibles**, y hacerlo de una forma que sirva de oráculo
para las implementaciones en Kotlin, Rust o Go.

## Alcance estricto

Esta implementación existe para una sola cosa: probar que la especificación
es implementable y no ambigua.

**No contiene, y no debe contener nunca:** WebRTC, DHT, relay, Tor, sockets de
red, base de datos, demonios, mensajería, sesiones, Double Ratchet, X25519.

El establecimiento de sesiones pertenece a **KM-SESSION-0001**. Las claves de
acuerdo X25519 son aquí material **opaco** de 32 bytes.

## Uso

```sh
python3 generate.py    # regenera vectors/ e invalid/ y el MANIFEST
python3 selfcheck.py   # verifica los fixtures sin generar nada
```

`selfcheck.py` no escribe nada: lee los fixtures del disco y comprueba que
producen exactamente lo que el `MANIFEST.json` declara. Sale con código 1 si
algo no cuadra.

## Estructura

| Fichero | Responsabilidad |
|---|---|
| `constants.py` | Separación de dominio, tamaños, enumeraciones, límites. Fuente única. |
| `ed25519.py` | Ed25519 autocontenido. Validado contra RFC 8032 §7.1. |
| `kce.py` | Canonical Encoding: orden, prohibiciones semánticas, lector acotado. |
| `schemas.py` | CDDL y validadores de mundo cerrado. |
| `identity.py` | Derivación de `identityId`, `deviceId`, `nodeId`. |
| `roster.py` | DeviceRoster, cadena de hashes, revocación. |
| `rotation.py` | IdentityRotation bilateral. |
| `contact.py` | ContactBundle, prekeys, invariantes de binding. |
| `device_link.py` | Challenge/response del enlace de dispositivos. |
| `fingerprints.py` | Número de seguridad de identidad y código de roster. |
| `verification.py` | Procedimiento de verificación de 7 pasos. |

## Hallazgos que cambiaron la especificación

Salieron de implementar, no de la teoria. Cada uno esta ahora fijo en el
código y cubierto por `selfcheck.py`.

### 1. KCE ordena por bytes codificados, no alfabéticamente

`kce.py` ordena las claves por sus **codificaciones deterministas**, y la
codificación de una clave `tstr` corta empieza por su **prefijo de longitud**.
El prefijo domina al contenido, así que en la práctica las claves se ordenan
**primero por longitud y después alfabéticamente**.

Con las claves de un `DeviceRoster` el orden real es:

```
doc (3)  devices (7)  version (7)  previous (8)  sequence (8)
createdAt (9)  signature (9)  identityId (10)  identityRoot (12)
```

Ordenar alfabéticamente el *texto* de las claves produce un documento
distinto e incompatible.

**Consecuencia práctica que ya mordió durante el desarrollo:** no se puede
alterar «el último byte» de un documento suponiendo que pertenece a la firma.
El último campo del mapa es el de la clave más larga, no el de `signature`.
Los fixtures localizan los bytes de la firma explícitamente (`flip_in_value`).

### 2. La continuidad de la cadena no es verificable dentro de un bundle

Un `ContactBundle` embebe **un solo snapshot** del roster. Si su `sequence` es
mayor que 1, el enlace al roster anterior **no se puede verificar** a partir
del propio documento.

La autoridad del bundle es exclusivamente la **firma de la raíz**. El RFC no
debe prometer más. `verify_roster` expone `require_chain` para dejar esto
explícito en vez de oculto tras una comprobación que fallaría siempre.

El ancla genesis de `sequence == 1` **sí** es comprobable sin estado externo, y
se comprueba siempre.

### 3. «No pude determinar la versión» no es «versión no soportada»

El lector acotado de versión puede concluir dos cosas muy distintas:

- «la versión es 7» → conclusión **definitiva**
- «no puedo determinar la versión» → conclusión **indefinida**

Solo la primera justifica abortar antes de validar KCE. En el segundo caso se
sigue adelante, porque el lector se niega a saltar etiquetas, flotantes y
longitudes indefinidas, y abortar ahí **enmascararía** el diagnóstico preciso
(`TAGS_FORBIDDEN`, `FLOAT_FORBIDDEN`, `INDEFINITE_LENGTH`) detrás de un
`INVALID_ENCODING` genérico.

El orden normativo se mantiene: `UNSUPPORTED_VERSION` siempre precede a
cualquier error de KCE cuando la versión es determinable.

## Los vectores no se escriben a mano

Se **calculan** ejecutando esta implementación. Si un valor fuera incorrecto,
el error queda en el código y no disfrazado de constante normativa en el RFC.

`vectors/keys.json` incluye las semillas deterministas
(`SHA-256("KM-ID-0001/" || label)`) para que otra implementación reproduzca
**exactamente** las mismas firmas.

## Qué prueba cada fixture

Cada fixture de `invalid/` falla por **una sola razón**, y `selfcheck.py`
comprueba que el código de error es el declarado — no simplemente que el
documento fue rechazado.

| Fixture | Motivo aislado |
|---|---|
| `duplicate-key.cbor` | clave de mapa repetida |
| `unknown-field.cbor` | campo que el esquema no define |
| `bad-signature.cbor` | solo la firma alterada |
| `wrong-device-binding.cbor` | el bundle dice móvil y lleva la clave del PC |
| `wrong-previous.cbor` | enlace mal calculado, **re-firmado** con la raíz correcta |
| `wrong-version.cbor` | bien formado y bien firmado, `version = 2` |
| `non-canonical-order.cbor` | orden de claves no canónico |
| `indefinite-length.cbor` | longitud indefinida |
| `float.cbor` | `createdAt` como float en lugar de uint |
| `non-nfc-text.cbor` | texto en forma de descomposición Unicode |
| `cbor-tag.cbor` | etiqueta CBOR |
| `null-field.cbor` | campo opcional presente pero nulo |
| `revoked-without-timestamp.cbor` | `REVOKED` sin `revokedAt` |

## Cross-validación de Ed25519

`ed25519.py` se contrastó contra los vectores oficiales de **RFC 8032 §7.1** y
contra la biblioteca `cryptography` en ambos sentidos, tanto en claves públicas
como en firmas. `selfcheck.py` repite esa comprobación si `cryptography` está
disponible.

## Advertencia sobre KCE y CBOR

KCE es un **perfil** de CBOR, no CBOR. Rechaza flotantes, etiquetas, `null`,
longitudes indefinidas, claves duplicadas y texto no NFC. La razón de fondo es
que una única representación canónica es lo que hace que dos implementaciones
firmen los mismos bytes.

CDDL (RFC 8610) define el modelo sintáctico pero es un lenguaje **abierto**: no
puede expresar «un campo desconocido es un error» ni acotar la longitud de un
array. La política de mundo cerrado es normativa e independiente de CDDL, y la
aplican los validadores de `schemas.py`.
