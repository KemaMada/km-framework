"""
Esquemas de KM-ID-0001
======================

Contiene:

  1. El CDDL que define el modelo de datos sintactico. Se incluira sin
     cambios en el apendice B del RFC.
  2. Validadores ejecutables de mundo cerrado.

NOTA SOBRE CDDL: CDDL (RFC 8610) es un lenguaje ABIERTO. No puede expresar
"un campo desconocido es un error", ni acotar la longitud de un array. La
politica de compatibilidad de KM es NORMATIVA e independiente de CDDL, y
se aplica en los validadores de este modulo. El CDDL no es la autoridad
para ella.

Este modulo no implementa criptografia ni verificacion de firmas; solo
forma de datos.
"""

from __future__ import annotations

import constants as C

# ---------------------------------------------------------------------------
# CDDL (RFC 8610) -- apendice B del RFC
# ---------------------------------------------------------------------------

CDDL = '''
; =====================================================================
; KM-ID-0001 -- Identity, Devices and Contact Bundles
; Modelo de datos sintactico.
;
; ADVERTENCIA: CDDL define el modelo sintactico UNICAMENTE. La politica de
; mundo cerrado de KM (un campo desconocido es un error de validacion) es
; normativa e independiente de CDDL, porque CDDL es un lenguaje abierto.
; Los limites de longitud de array tampoco son expresables en CDDL y se
; aplican como norma en el texto.
;
; Los tam.bstr/tstr son exactos. Los enumerados se documentan en
; comentario; CDDL no tiene forma nativa de restringirlos.
; =====================================================================

; ---------------------------------------------------------------------
; DeviceRoster: que dispositivos estan autorizados para una identidad
; ---------------------------------------------------------------------
km-device-roster = {
  "doc"           : "km.deviceRoster",
  "version"       : uint,
  "identityRoot"  : bstr .size 32,      ; Ed25519, clave de identidad de usuario
  "identityId"    : bstr .size 32,      ; SHA-256("KM-ID-IDENTITY" || identityRoot)
  "sequence"      : uint,               ; con alcance en identityRoot
  "previous"      : bstr .size 32,      ; genesis:  SHA-256("KM-ID-ROSTER-GENESIS" || identityRoot)
                                       ; posterior: SHA-256(KCE(roster previo completo))
  "createdAt"     : uint,               ; advisory; no participa en frescura
  "devices"       : [+ km-device-entry], ; longitud acotada a 64 por norma
  "signature"     : bstr .size 64       ; Ed25519(identityRoot, KCE(Signable))
}

km-device-entry = {
  "deviceId"      : bstr .size 32,      ; SHA-256("KM-ID-DEVICE" || signingKey)
  "signingKey"    : bstr .size 32,      ; Ed25519
  "agreementKey"  : bstr .size 32,      ; X25519, material opaco en esta capa
  "status"        : uint,               ; 1 = ACTIVE, 2 = REVOKED
  "addedAt"       : uint,               ; advisory
  ? "name"        : tstr .size 1..64,   ; opcional, NFC, etiqueta legible
  ? "revokedAt"   : uint                ; OBLIGATORIO si status == 2
}

; ---------------------------------------------------------------------
; IdentityRotation: transicion bilateral entre raices de identidad
; ---------------------------------------------------------------------
km-identity-rotation = {
  "doc"         : "km.identityRotation",
  "version"     : uint,
  "oldRoot"     : bstr .size 32,
  "newRoot"     : bstr .size 32,
  "sequence"    : uint,
  "newRootSign" : bstr .size 64,        ; Ed25519(newRoot, KCE(Signable))
  "oldRootSign" : bstr .size 64         ; Ed25519(oldRoot, KCE(Signable))
}

; ---------------------------------------------------------------------
; ContactBundle: bootstrap autonomo para un contacto o un device-link
; ---------------------------------------------------------------------
km-contact-bundle = {
  "doc"                 : "km.contactBundle",
  "version"             : uint,
  "kind"                : uint,           ; 1 = CONTACT, 2 = DEVICE_LINK
  "createdAt"           : uint,
  "identityRoot"        : bstr .size 32,
  "identityId"          : bstr .size 32,
  "roster"              : km-device-roster,
  "subjectDeviceId"     : bstr .size 32,
  "subjectSigningKey"   : bstr .size 32,  ; DEBE coincidir con la DeviceEntry
  "subjectAgreementKey" : bstr .size 32,  ; DEBE coincidir con la DeviceEntry
  "signedPrekey"        : km-signed-prekey,
  "oneTimePrekeys"      : [* km-onetime-prekey], ; acotado a 128 por norma
  "capabilities"        : uint,
  "signature"           : bstr .size 64,  ; Ed25519(identityRoot, KCE(Signable))
  ? "expiresAt"         : uint,
  ? "endpoints"         : [* km-endpoint-hint],  ; acotado a 16 por norma
  ? "linkSecret"        : bstr .size 32   ; OBLIGATORIO si kind == 2
}

; ---------------------------------------------------------------------
; Prekeys
; ---------------------------------------------------------------------
km-signed-prekey = {
  "doc"       : "km.signedPrekey",
  "version"   : uint,
  "deviceId"  : bstr .size 32,          ; DEBE ser subjectDeviceId del bundle
  "keyId"     : uint,
  "publicKey" : bstr .size 32,          ; X25519
  "createdAt" : uint,
  "expiresAt" : uint,
  "signature" : bstr .size 64           ; Ed25519(deviceSigningKey, KCE(Signable))
}

; La OPK NO lleva firma propia: hereda la autenticidad de la firma raiz
; del ContactBundle que la contiene.
km-onetime-prekey = {
  "doc"       : "km.oneTimePrekey",
  "version"   : uint,
  "deviceId"  : bstr .size 32,          ; DEBE ser subjectDeviceId del bundle
  "keyId"     : uint,
  "publicKey" : bstr .size 32           ; X25519
}

; ---------------------------------------------------------------------
; Endpoints: pistas, nunca presencia
; ---------------------------------------------------------------------
km-endpoint-hint = {
  "type"       : uint,                  ; 1 = ONION (estable), 2 = UDP, 3 = RELAY
  "value"      : tstr .size 1..256,
  "observedAt" : uint,                  ; advisory
  ? "relayId"  : bstr .size 32          ; OBLIGATORIO si type == 3
}

; ---------------------------------------------------------------------
; Device-link: bootstrap FUERA DE BANDA, no autenticacion
; ---------------------------------------------------------------------

; Canal fisico: movil -> PC. NO autoriza criptograficamente al PC.
km-device-link-challenge = {
  "doc"                : "km.deviceLinkChallenge",
  "version"            : uint,
  "identityRoot"       : bstr .size 32,
  "presentingDeviceId" : bstr .size 32,
  "nonce"              : bstr .size 32,  ; CSPRNG; anti-replay
  "expiresAt"          : uint,           ; advisory; plaza corta
  "linkSecret"         : bstr .size 32,  ; CSPRNG; liga este intercambio a ESTE QR
  "signature"          : bstr .size 64   ; Ed25519(presentingDeviceSigningKey, ...)
}

; Prueba de posesion: PC -> movil.
km-device-link-response = {
  "doc"          : "km.deviceLinkResponse",
  "version"      : uint,
  "identityRoot" : bstr .size 32,
  "deviceId"     : bstr .size 32,
  "signingKey"   : bstr .size 32,
  "agreementKey" : bstr .size 32,
  "nonce"        : bstr .size 32,        ; eco del challenge
  "signature"    : bstr .size 64,       ; Ed25519(nueva deviceSigningKey, ...)
  ? "name"       : tstr .size 1..64
}
'''


# ---------------------------------------------------------------------------
# Validadores ejecutables
# ---------------------------------------------------------------------------


class SchemaError(Exception):
    def __init__(self, code: str, message: str = "") -> None:
        super().__init__(f"{code}: {message}" if message else code)
        self.code = code
        self.message = message


# Tipos admitidos por Field.kind:
#   "bstr32" | "bstr64" | "text" | "uint" | "array" | "map" | "doc"
class Field:
    __slots__ = ("key", "kind", "required", "enum", "size", "max_len", "require_if", "max_value")

    def __init__(
        self,
        key: str,
        kind: str,
        required: bool = True,
        *,
        enum: dict | None = None,
        size: int | None = None,
        max_len: int | None = None,
        require_if: tuple[str, object] | None = None,
        max_value: int = C.MAX_UINT64,
    ) -> None:
        self.key = key
        self.kind = kind
        self.required = required
        self.enum = enum
        self.size = size
        self.max_len = max_len
        self.require_if = require_if
        self.max_value = max_value


class Schema:
    def __init__(
        self,
        name: str,
        doc_value: str | None,
        fields: list[Field],
        signature_fields: list[str],
    ) -> None:
        self.name = name
        self.doc_value = doc_value
        self.fields = fields
        self.signature_fields = signature_fields
        self.by_key = {f.key: f for f in fields}
        self.allowed_keys = frozenset(self.by_key)

    def signable(self, value: dict) -> dict:
        """Payload firmable: la estructura menos TODOS sus campos de firma."""
        return {k: v for k, v in value.items() if k not in self.signature_fields}


def _check_value(field: Field, value, where: str) -> None:
    if field.kind == "bstr32":
        if not isinstance(value, bytes) or len(value) != C.PUBKEY_BYTES:
            raise SchemaError("FIELD_SIZE", f"{where} debe ser bstr .size 32")
    elif field.kind == "bstr64":
        if not isinstance(value, bytes) or len(value) != C.SIG_BYTES:
            raise SchemaError("FIELD_SIZE", f"{where} debe ser bstr .size 64")
    elif field.kind == "text":
        if not isinstance(value, str) or value == "":
            raise SchemaError("FIELD_TYPE", f"{where} debe ser tstr no vacio")
        if field.size is not None and len(value.encode("utf-8")) > field.size:
            raise SchemaError("FIELD_SIZE", f"{where} excede {field.size} bytes")
    elif field.kind == "uint":
        if not isinstance(value, int) or isinstance(value, bool):
            raise SchemaError("FIELD_TYPE", f"{where} debe ser uint")
        if value < 0 or value > field.max_value:
            raise SchemaError("FIELD_TYPE", f"{where} fuera de rango uint")
    elif field.kind == "array":
        if not isinstance(value, list):
            raise SchemaError("FIELD_TYPE", f"{where} debe ser array")
        if field.max_len is not None and len(value) > field.max_len:
            raise SchemaError("ARRAY_TOO_LONG", f"{where} excede {field.max_len} elementos")
    elif field.kind == "map":
        if not isinstance(value, dict):
            raise SchemaError("FIELD_TYPE", f"{where} debe ser map")
    elif field.kind == "doc":
        if value != field.enum:
            raise SchemaError("FIELD_TYPE", f"{where} debe ser {field.enum!r}")
    else:  # pragma: no cover - error de programacion
        raise SchemaError("FIELD_TYPE", f"{where} tipo interno desconocido {field.kind}")

    if field.enum is not None and field.kind == "uint" and value not in field.enum:
        raise SchemaError("ENUM_VALUE", f"{where} debe ser uno de {sorted(field.enum)}")


def validate(schema: Schema, value: dict, path: str = "") -> None:
    """Valida una estructura contra su esquema, aplicando mundo cerrado.

    Orden: mapa -> `doc` -> campos desconocidos -> campos requeridos ->
    tipo y tamano -> dependencias (require_if).

    Los campos desconocidos se rechazan ANTES de comprobar los requeridos,
    para que el error que se reporta sea el primario del documento.
    """
    if not isinstance(value, dict):
        raise SchemaError("FIELD_TYPE", f"{path or schema.name} debe ser map")

    if schema.doc_value is not None and value.get("doc") != schema.doc_value:
        raise SchemaError("FIELD_TYPE", f"{path}doc debe ser {schema.doc_value!r}")

    for key in value:
        if key not in schema.allowed_keys:
            raise SchemaError("UNKNOWN_FIELD", f"{path}{key} no esta definido en {schema.name}")

    for field in schema.fields:
        if field.key not in value:
            if field.required and field.require_if is None:
                raise SchemaError("MISSING_FIELD", f"{path}{field.key} es obligatorio")
            continue
        _check_value(field, value[field.key], f"{path}.")

    for field in schema.fields:
        if field.require_if is None:
            continue
        other_key, expected = field.require_if
        if value.get(other_key) == expected and field.key not in value:
            raise SchemaError(
                "MISSING_FIELD",
                f"{path}{field.key} es obligatorio si {other_key} == {expected}",
            )


# ---------------------------------------------------------------------------
# Definicion de esquemas
# ---------------------------------------------------------------------------

DEVICE_ENTRY = Schema(
    "DeviceEntry",
    None,
    [
        Field("deviceId", "bstr32"),
        Field("signingKey", "bstr32"),
        Field("agreementKey", "bstr32"),
        Field("status", "uint", enum=C.DEVICE_STATUS_NAMES),
        Field("addedAt", "uint"),
        Field("name", "text", required=False, size=C.MAX_NAME_BYTES),
        Field("revokedAt", "uint", required=False),
    ],
    signature_fields=[],
)

DEVICE_ROSTER = Schema(
    "DeviceRoster",
    C.DOC_ROSTER,
    [
        Field("doc", "doc", enum=C.DOC_ROSTER),
        Field("version", "uint"),
        Field("identityRoot", "bstr32"),
        Field("identityId", "bstr32"),
        Field("sequence", "uint"),
        Field("previous", "bstr32"),
        Field("createdAt", "uint"),
        Field("devices", "array", max_len=C.MAX_DEVICES),
        Field("signature", "bstr64"),
    ],
    signature_fields=["signature"],
)

IDENTITY_ROTATION = Schema(
    "IdentityRotation",
    C.DOC_ROTATION,
    [
        Field("doc", "doc", enum=C.DOC_ROTATION),
        Field("version", "uint"),
        Field("oldRoot", "bstr32"),
        Field("newRoot", "bstr32"),
        Field("sequence", "uint"),
        Field("newRootSign", "bstr64"),
        Field("oldRootSign", "bstr64"),
    ],
    signature_fields=["newRootSign", "oldRootSign"],
)

SIGNED_PREKEY = Schema(
    "SignedPrekey",
    C.DOC_SIGNED_PREKEY,
    [
        Field("doc", "doc", enum=C.DOC_SIGNED_PREKEY),
        Field("version", "uint"),
        Field("deviceId", "bstr32"),
        Field("keyId", "uint"),
        Field("publicKey", "bstr32"),
        Field("createdAt", "uint"),
        Field("expiresAt", "uint"),
        Field("signature", "bstr64"),
    ],
    signature_fields=["signature"],
)

ONETIME_PREKEY = Schema(
    "OneTimePrekey",
    C.DOC_ONETIME_PREKEY,
    [
        Field("doc", "doc", enum=C.DOC_ONETIME_PREKEY),
        Field("version", "uint"),
        Field("deviceId", "bstr32"),
        Field("keyId", "uint"),
        Field("publicKey", "bstr32"),
    ],
    signature_fields=[],
)

ENDPOINT_HINT = Schema(
    "EndpointHint",
    None,
    [
        Field("type", "uint", enum=C.ENDPOINT_TYPE_NAMES),
        Field("value", "text", size=C.MAX_ENDPOINT_VALUE_BYTES),
        Field("observedAt", "uint"),
        Field("relayId", "bstr32", required=False),
    ],
    signature_fields=[],
)

CONTACT_BUNDLE = Schema(
    "ContactBundle",
    C.DOC_CONTACT_BUNDLE,
    [
        Field("doc", "doc", enum=C.DOC_CONTACT_BUNDLE),
        Field("version", "uint"),
        Field("kind", "uint", enum=C.BUNDLE_KIND_NAMES),
        Field("createdAt", "uint"),
        Field("identityRoot", "bstr32"),
        Field("identityId", "bstr32"),
        Field("roster", "map"),
        Field("subjectDeviceId", "bstr32"),
        Field("subjectSigningKey", "bstr32"),
        Field("subjectAgreementKey", "bstr32"),
        Field("signedPrekey", "map"),
        Field("oneTimePrekeys", "array", max_len=C.MAX_ONE_TIME_PREKEYS),
        Field("capabilities", "uint", max_value=C.MAX_CAPABILITIES_UINT),
        Field("signature", "bstr64"),
        Field("expiresAt", "uint", required=False),
        Field("endpoints", "array", required=False, max_len=C.MAX_ENDPOINTS),
        Field(
            "linkSecret",
            "bstr32",
            required=False,
            require_if=("kind", C.KIND_DEVICE_LINK),
        ),
    ],
    signature_fields=["signature"],
)

LINK_CHALLENGE = Schema(
    "DeviceLinkChallenge",
    C.DOC_LINK_CHALLENGE,
    [
        Field("doc", "doc", enum=C.DOC_LINK_CHALLENGE),
        Field("version", "uint"),
        Field("identityRoot", "bstr32"),
        Field("presentingDeviceId", "bstr32"),
        Field("nonce", "bstr32"),
        Field("expiresAt", "uint"),
        Field("linkSecret", "bstr32"),
        Field("signature", "bstr64"),
    ],
    signature_fields=["signature"],
)

LINK_RESPONSE = Schema(
    "DeviceLinkResponse",
    C.DOC_LINK_RESPONSE,
    [
        Field("doc", "doc", enum=C.DOC_LINK_RESPONSE),
        Field("version", "uint"),
        Field("identityRoot", "bstr32"),
        Field("deviceId", "bstr32"),
        Field("signingKey", "bstr32"),
        Field("agreementKey", "bstr32"),
        Field("nonce", "bstr32"),
        Field("signature", "bstr64"),
        Field("name", "text", required=False, size=C.MAX_NAME_BYTES),
    ],
    signature_fields=["signature"],
)

ALL_SCHEMAS = {
    "DeviceRoster": DEVICE_ROSTER,
    "DeviceEntry": DEVICE_ENTRY,
    "IdentityRotation": IDENTITY_ROTATION,
    "ContactBundle": CONTACT_BUNDLE,
    "SignedPrekey": SIGNED_PREKEY,
    "OneTimePrekey": ONETIME_PREKEY,
    "EndpointHint": ENDPOINT_HINT,
    "DeviceLinkChallenge": LINK_CHALLENGE,
    "DeviceLinkResponse": LINK_RESPONSE,
}
