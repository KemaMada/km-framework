# KM-0006 — Session Bootstrap (X3DH)

| Campo | Valor |
|-------|-------|
| **RFC** | KM-0006 |
| **Título** | Session Bootstrap (X3DH) |
| **Estado** | Draft (Frozen) |
| **Versión** | 0.3 |
| **Nota** | Congelado. §13 resuelto. **Implementado** en `com.km.x3dh.X3dh` (motor X3DH v1, KM-0006 v0.3), con `initiate()`/`respond()` y tests en `X3dhTest` y `AgreementKeyContentionTest`. |
| **Autor** | KeyMessage Project |
| **Última actualización** | 2026-09-28 |
| **Depende de** | KM-0001 (identidad), KM-0002 (autenticación), KM-0004 (SecureFrame), KM-0005 (nodo) |
| **Reemplaza** | — |
| **Reemplazado por** | — |

---

## 1. Purpose

Este documento define cómo se establece el **secreto inicial** de una sesión
cifrada entre dos dispositivos, y cómo ese secreto se convierte en el estado
inicial del **Double Ratchet**.

Double Ratchet (KM-0004 §SecureRatchetProtocol) define la evolución del estado
*cryptográfico* una vez que existe un `RootKey` y un par DH. Este documento
define lo que ocurre **antes**: cómo se obtiene ese material de forma que
resista la asincronía de los mensajes y la ausencia del destinatario.

## 2. Why this document exists

El Double Ratchet está implementado y verificado, pero su inicialización es
una decisión criptográfica de primer orden. Es tentador improvisarla:

```
SK = X25519(algunaClave, otraClave)
```

Esa construcción es incorrecta en varios modos que importan, y este documento
existe para que ninguno de ellos ocurra por descuido.

## 3. Scope

**Define:**

- El papel de cada clave en el bootstrap.
- Las cuatro derivaciones DH y sus dominios de HKDF.
- La clave de bootstrap `F` y su transporte.
- La frontera explícita entre X3DH, el transporte del primer mensaje y el
  Double Ratchet.
- Los invariantes que la implementación debe hacer cumplir.

**NO define:**

- La codificación de `ContactBundle`, `SignedPrekey` o `OneTimePrekey`: ya
  están definidos en KM-0001.
- El wire format de `SecureFrame`: ya definido en KM-0004.
- Persistencia de estado (checkpointing) tras el bootstrap.
- Multi-sesión ni gestion de cambios de dispositivo.

## 4. Terminology

| Término | Significado |
|---------|-------------|
| **IK** | Identity Key: clave X25519 de acuerdo de una identidad o dispositivo. |
| **EK** | Ephemeral Key: par X25519 efímero del iniciador. |
| **SPK** | Signed PreKey: clave X25519 persistente, firmada por el dispositivo. |
| **OPK** | One-Time PreKey: clave X25519 de un solo uso. |
| **F** | Bootstrap value: 32 bytes aleatorios del iniciador. |
| **SK** | Shared Key: resultado del X3DH. |
| **K_prekey** | Clave que protege `F` en el primer `SecureFrame`. |
| **RootKey** | Raíz del Double Ratchet. |

## 5. Key roles

### 5.1 Separation invariant (NORMATIVE)

> **KM-0006: una clave Ed25519 NUNCA se convierte ni se reutiliza como clave
> X25519. La `signingKey` Ed25519 únicamente autentica artefactos; toda clave
> que entra en un DH es X25519 de acuerdo.**

Esta regla no es estética. Una clave de firma y una clave de acuerdo son dos
dominios criptográficos distintos:

```
compromiso de signingKey  ──▶  NO expone agreementKey privada
compromiso de agreementKey ──▶  NO habilita firma Ed25519
```

Mezclarlas crearía un único secreto cuya exposición compromete dos dominios.
KM-0007 ya define `SIGNATURE` y `KEY_AGREEMENT` como categorías separadas, y
este documento es su aplicación concreta en el bootstrap.

**Consecuencia:** no existe, y no existirá, conversión Ed25519 → X25519 en la
API de KM-0004/KM-0006.

### 5.2 Mapping to KeyMessage

```
IdentityRoot
 ├── signingKey    Ed25519   → firma roster, bundles, prekeys
 └── agreementKey  X25519    → IK

DeviceRoster
 └── Device
      ├── signingKey    Ed25519
      └── agreementKey  X25519   → IK del dispositivo
      ├── signedPreKey  X25519   → SPK
      └── oneTimePreKeys X25519  → OPK
```

El firmante del `SignedPrekey` es la `signingKey` Ed25519 **del dispositivo**,
no la raíz. La cadena de autoridad ya verificada es:

```
IdentityRoot → DeviceRoster → DeviceSigningKey → SignedPrekey
```

## 6. The four DH derivations

Alice inicia contra un `ContactBundle` de Bob ya verificado.

```
DH1 = X25519(IK_A.priv, SPK_B.pub)
DH2 = X25519(EK_A.priv, IK_B.pub)
DH3 = X25519(EK_A.priv, SPK_B.pub)
DH4 = X25519(EK_A.priv, OPK_B.pub)     // solo si existe un OPK disponible
```

Cada salida pasa por **su propio dominio** antes de formar el material de
sesión. No se concatena de forma opaca.

```
K1 = HKDF(salt = 0^32, ikm = DH1, info = "KM-0006/X3DH/V1/DH1", len = 32)
K2 = HKDF(salt = 0^32, ikm = DH2, info = "KM-0006/X3DH/V1/DH2", len = 32)
K3 = HKDF(salt = 0^32, ikm = DH3, info = "KM-0006/X3DH/V1/DH3", len = 32)
K4 = HKDF(salt = 0^32, ikm = DH4, info = "KM-0006/X3DH/V1/DH4", len = 32)   // si DH4 existe

SK = HKDF(salt = 0^32, ikm = K1 || K2 || K3 [|| K4],
          info = "KM-0006/X3DH/V1/SK", len = 32)
```

**Por qué dominios separados:** `DH1..DH4` salen de pares de claves distintos y
tienen propiedades distintas. Sin separación, una salida de HKDF podría
reinterpretarse bajo otro contexto.

## 7. The bootstrap value F

### 7.1 Purpose

`F` es 32 bytes aleatorios generados por el **iniciador**.

No es decorativa. Sin ella, dos sesiones de Alice contra Bob usando el mismo
`SPK_B` derivarían el mismo `SK`, y:

- Bob no podría distinguir dos conversaciones.
- Bob no podría detectar reutilización de un `OPK`.
- Un atacante con acceso al tráfico vería el mismo `SK` dos veces.

`F` es la mitad del secreto compartido, y la mitad que Alice conoce de antemano.

### 7.2 Transport

`F` viaja **cifrada dentro del primer `SecureFrame` de tipo `PREKEY`**, no en
`ContactBundle` ni en un mensaje de contacto previo.

```
K_prekey = HKDF(salt = 0^32, ikm = SK,
                info = "KM-0006/X3DH/V1/PREKEY", len = 32)

SecureFrame(
    type   = PREKEY,
    header = (dhPublicKey = EK_A.public, PN, N),   // entra en el AAD
    ciphertext = AEAD(K_prekey, nonce, F, AAD=header)
)
```

El `SecureFrame` ya autentica su header mediante el AAD, de modo que transportar
`F` ahí no crea un formato nuevo: reutiliza la infraestructura existente.

### 7.3 Requisito previo: el receptor necesita `IK_A`

> **El receptor DEBE disponer del `ContactBundle` del emisor antes de poder
> responder.** No es opcional.

El header del `SecureFrame` tiene longitud fija (44 bytes) y solo transporta
`EK_A`. Para calcular `DH1 = DH(IK_A, SPK_B)` el receptor necesita
`IK_A.public`, que **no** viaja en el frame.

La criptografia no puede resolverlo: `F` se descifra con `K_prekey`, que se
deriva de `SK`, y `SK` depende de `DH1`, que depende de `IK_A`. Poner `IK_A`
en el plaintext del frame sería circular.

El material publico de Alice llega por el canal de descubrimiento ya existente:

```
RelaySignalMessage.ContactExchange   →  ContactBundle de Alice  →  IK_A
```

Esto respeta la separación de planos: el `ContactBundle` es material **público**
de bootstrap, y el relay transporta ese tipo de señalización. `F`, `SK` y el
plaintext de aplicación **nunca** pasan por el relay.

La verificación de ese bundle la realiza `ContactBundle.verifyBundle`, que ya
valida roster, binding del subject y binding de prekeys.

### 7.4 SK is not used directly as a key

`SK` no se usa como clave AEAD ni como `RootKey`. De `SK` se derivan, con
dominios distintos:

```
SK ──► K_prekey  (cifra F en el primer frame)
```

y el `RootKey` del ratchet se obtiene **de `F`**, no de `SK`. Esto mantiene tres
capas explícitamente separadas:

```
X3DH ──produce──► SK ──deriva──► K_prekey ──protege──► F ──inicializa──► RootKey
```

## 8. Bootstrap invariants (NORMATIVE)

1. `IK` es siempre una clave X25519. Nunca una Ed25519.
2. `DH1..DH4` usan exclusivamente X25519.
3. Cada `DHn` pasa por HKDF con su propio dominio antes de unirse.
4. `SK` se deriva con el dominio `/SK`.
5. `SK` no se usa directamente como clave AEAD.
6. `K_prekey` se deriva de `SK` con dominio `/PREKEY`.
7. `F` tiene exactamente 32 bytes.
8. `F` se genera aleatoriamente en el iniciador.
9. `F` nunca aparece en claro sobre el wire.
10. `F` nunca aparece en `ContactBundle`.
11. `F` nunca se deriva de `identityId`, `deviceId`, `SK`, `SPK` ni `OPK`.
12. `F` se consume una sola vez para inicializar el estado del ratchet.
13. Un `SecureFrame` de tipo `PREKEY` inválido **no** modifica el estado.
14. La recepción de `PREKEY` es transaccional, igual que el resto del ratchet.
15. Tras el bootstrap, ni `SK` ni `F` se reutilizan como `messageKey`.
16. El primer ratchet posterior genera material nuevo.

## 9. Initiator flow

```
0. Obtener el ContactBundle de Bob (descubrimiento, cache o ContactExchange)
   y verificarlo con `ContactBundle.verifyBundle`:
     - roster embebido verifica
     - subjectSigningKey / subjectAgreementKey coinciden con la DeviceEntry
     - deviceId deriva de su signingKey
     - SPK/OPK pertenecen a ese deviceId
2. Obtener IK_A (agreementKey), IK_B, SPK_B, OPK_B
3. Generar EK_A (efímera) y F (32 bytes aleatorios)
4. Calcular DH1..DH4 y derivar SK
5. Derivar K_prekey de SK
6. Construir SecureFrame(type=PREKEY) con DHs = EK_A.pub
7. Cifrar F con K_prekey
8. Enviar por el DataChannel (vía PeerTransportManager)
```

## 10. Responder flow

```
1. Recibir el ContactBundle del emisor (ContactExchange) y verificarlo con
   `ContactBundle.verifyBundle`. De el se obtiene IK_A.
   NOTA: este paso es OBLIGATORIO; sin IK_A no se puede calcular DH1.
2. Recibir SecureFrame de tipo PREKEY
3. Localizar su propio material (SPK/OPK privados del deviceId)
4. Calcular DH1..DH4 usando la EK_A recibida y SU material privado
   (X25519 es conmutativo, por lo que obtiene el mismo secreto que Alice)
5. Derivar SK
6. Derivar K_prekey de SK
7. Descifrar F
8. Inicializar el Double Ratchet con RootKey derivado de F
9. Solo tras descifrar con éxito → commit del estado
```

## 11. Relationship to the transport layers

Este documento no altera la separación de planos ya congelada:

| Capa | Transporta |
|------|-----------|
| `RelayServer` | Señalización únicamente. Nunca `F`, `SK` ni plaintext. |
| `PeerTransportManager` | Bytes opacos. No sabe qué son. |
| `SecureFrame` | Ciphertext + tag. |
| `SecureRatchetProtocol` | Único punto que conoce plaintext y ratchets. |
| Aplicación | Plaintext. |

`F` viaja por el DataChannel, igual que el resto del tráfico de la sesión. El
relay no participa.

## 12. Architectural tests (REQUIRED)

La implementación debe incluir pruebas de arquitectura, no solo de comportamiento:

```
X3DH-IK-01: IK must be an X25519 public key.
X3DH-IK-02: An Ed25519 signing key cannot be supplied as IK.
X3DH-IK-03: No Ed25519 → X25519 conversion exists in the KM-0006 API.
X3DH-IK-04: Compromise of signingKey does not expose agreementKey.
X3DH-IK-05: Compromise of agreementKey does not enable Ed25519 signing.
X3DH-DOM-01: DH1..DH4 and SK use distinct HKDF domains.
X3DH-DOM-02: SK is never used directly as an AEAD key.
X3DH-F-01:  F is 32 bytes and never appears in cleartext on the wire.
X3DH-F-02:  F does not appear in ContactBundle.
X3DH-AT-01: An invalid PREKEY frame does not mutate ratchet state.
```

## 13. Scope of IK: device-scoped (FROZEN)

> **DECISIÓN (congelada): `IK` es el `agreementKey` X25519 del DISPOSITIVO
> concreto, no el de la identidad.**

### 13.1 Definición

```
IK_A = Alice.device.agreementKey      (X25519, del dispositivo emisor)
IK_B = Bob.device.agreementKey        (X25519, del dispositivo receptor)
SPK_B   = Bob.device.signedPreKey     (per-device, firmado)
OPK_B   = Bob.device.oneTimePreKey    (per-device)
```

Una sesión X3DH pertenece inequívocamente a un par de dispositivos:

```
Identity A / Device A  ↔  Identity B / Device B
```

### 13.2 Justificación

El `ContactBundle` es per-device y contiene `subjectAgreementKey`,
`signedPrekey` y `oneTimePrekeys` **del mismo dispositivo**. Además,
`deviceId` se deriva de la `signingKey` de ese dispositivo.

Usar el `agreementKey` del mismo dispositivo mantiene toda la sesión —IK, SPK,
OPK— ligada a un único `deviceId`, y hace que la verificación de binding ya
existente en `ContactBundle.verifyBundle` sea suficiente: si el bundle verifica,
el material de X3DH pertenece al dispositivo que dice pertenecer.

La alternativa *identity-scoped* compartiría una sola clave de acuerdo entre
todos los dispositivos de una identidad, lo que debilita la propiedad de que una
sesión pertenece a un dispositivo concreto y complica la revocación: revocar un
dispositivo no bastaría para invalidar el material de acuerdo.

### 13.3 Consecuencia normativa

> Una sesión X3DH iniciada contra el `deviceId` D solo puede ser respondida por
> el dispositivo D. La clave de acuerdo de la identidad **no** participa en
> ninguna operación DH.

## 14. Change log

| Versión | Fecha | Cambio |
|---------|-------|--------|
| 0.1 | 2026-09-28 | Especificación inicial del bootstrap. Sin implementación. |
| 0.2 | 2026-09-28 | §13 congelado: `IK` device-scoped. Sin cambios en el resto. |
| 0.3 | 2026-09-28 | §7.3: requisito de `IK_A` del receptor vía ContactExchange. §7.2 era irrealizable sin esto. |
| 0.3 | 2026-09-30 | Sin cambios en la especificación. Se corrige la cabecera, que decía «Aún sin implementación»: el motor X3DH v1 ya existe en `com.km.x3dh.X3dh` con sus tests. La especificación no cambió, la nota estaba desfasada. |
