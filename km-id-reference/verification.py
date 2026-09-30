"""
Procedimiento de verificacion -- KM-ID-0001 seccion 7
====================================================

Orden NORMATIVO. El orden importa y no es arbitrario:

    1. extraccion de version con lector acotado
    2. despacho de version
    3. validacion KCE + round-trip byte a byte
    4. validacion de esquema (mundo cerrado)
    5. invariantes semanticas
    6. verificacion criptografica
    7. comprobaciones de estado y frescura

POR QUE EL DESPACHO DE VERSION PRECEDE A KCE

Si se validara KCE primero, un documento de una version FUTURA que
introdujera una caracteristica hoy prohibida (por ejemplo, etiquetas
CBOR) se reportaria como "encoding invalido" en lugar de "version no
soportada", que es el mensaje que el operador necesita. Por eso KCE esta
VERSIONADO: la version seleccionada determina el perfil aplicable, no al
reves.

El paso 1 es un LECTOR DE FRAMING ACOTADO, deliberadamente restringido:
NO es un segundo parser CBOR general. Solo determina que existe un map de
nivel superior, localiza la clave `version` dentro de los limites
establecidos y se detiene. Si no puede hacerlo de forma inequivoca
devuelve INVALID_ENCODING en lugar de adivinar.

Códigos de resultado distinguished:

    UNSUPPORTED_VERSION  el documento es valido pero incompatible
    INVALID_ENCODING      el documento esta corrupto o mal formado
"""

from __future__ import annotations

import constants as C
import contact as contact_mod
import device_link as device_link_mod
import kce as kce_mod
import rotation as rotation_mod
import roster as roster_mod
import schemas
from errors import VerificationError
from kce import KceError

# Codigos de resultado de la extraccion de version
UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
INVALID_ENCODING = "INVALID_ENCODING"

# Documento -> (schema, verificador semantico+crypto)
_VERIFIERS = {
    "DeviceRoster": (
        schemas.DEVICE_ROSTER,
        lambda v, ctx: roster_mod.verify_roster(
            v,
            previous_roster=ctx.get("previous_roster"),
            expected_identity_root=ctx.get("expected_identity_root"),
        ),
    ),
    "IdentityRotation": (
        schemas.IDENTITY_ROTATION,
        lambda v, ctx: rotation_mod.verify_rotation(
            v,
            expected_old_root=ctx.get("expected_old_root"),
            expected_new_root=ctx.get("expected_new_root"),
        ),
    ),
    "ContactBundle": (
        schemas.CONTACT_BUNDLE,
        lambda v, ctx: contact_mod.verify_bundle(
            v, expected_identity_root=ctx.get("expected_identity_root")
        ),
    ),
    "DeviceLinkChallenge": (
        schemas.LINK_CHALLENGE,
        lambda v, ctx: device_link_mod.verify_challenge(
            v, ctx["presenting_device_signing_public_key"]
        ),
    ),
    "DeviceLinkResponse": (
        schemas.LINK_RESPONSE,
        lambda v, ctx: device_link_mod.verify_response(
            v, ctx["expected_nonce"], expected_identity_root=ctx.get("expected_identity_root")
        ),
    ),
}

SCHEMAS_BY_DOC = {
    C.DOC_ROSTER: "DeviceRoster",
    C.DOC_ROTATION: "IdentityRotation",
    C.DOC_CONTACT_BUNDLE: "ContactBundle",
    C.DOC_SIGNED_PREKEY: "SignedPrekey",
    C.DOC_ONETIME_PREKEY: "OneTimePrekey",
    C.DOC_LINK_CHALLENGE: "DeviceLinkChallenge",
    C.DOC_LINK_RESPONSE: "DeviceLinkResponse",
}


def identify(data: bytes) -> kce_mod.VersionResult:
    """Pasos 1-2. Lector acotado de version y despacho."""
    return kce_mod.peek_version(data, max_version=C.CURRENT_VERSION)


def verify_document(data: bytes, *, context: dict | None = None) -> dict:
    """Verifica un documento KM completo desde bytes crudos.

    `context` transporta el material que la verificacion semantica
    necesita y que no viaja en el documento:
      expected_identity_root, previous_roster, expected_old_root,
      expected_new_root, expected_nonce,
      presenting_device_signing_public_key
    """
    ctx = context or {}

    # 1-2. Extraccion acotada de version y despacho.
    #
    # El lector acotado puede concluir dos cosas muy distintas y NO deben
    # tratarse igual:
    #
    #   "la version es 7"                 -> conclusion DEFINITIVA
    #   "no puedo determinar la version"  -> conclusion INDEFINIDA
    #
    # Solo la primera justifica abortar antes de validar KCE. En el segundo
    # caso se sigue adelante: el lector acotado no es un parser CBOR general
    # y se niega a saltar etiquetas, flotantes o longitudes indefinidas, por
    # lo que abortar aqui ENMASCARARIA el diagnostico preciso (TAGS_FORBIDDEN,
    # FLOAT_FORBIDDEN, INDEFINITE_LENGTH) detras de un INVALID_ENCODING
    # generico. Si el documento resultara ser KCE valido y aun asi no
    # determinable, se reporta entonces si, tras el paso 4.
    result = identify(data)
    version_undetermined = result.reason is not None
    if result.reason == UNSUPPORTED_VERSION:
        raise VerificationError(UNSUPPORTED_VERSION, "el documento declara una version no soportada")

    # 3. KCE: prohibiciones semanticas + round-trip byte a byte.
    try:
        value = kce_mod.validate(data)
    except KceError as exc:
        raise VerificationError(exc.code, exc.message) from exc

    if version_undetermined:
        raise VerificationError(
            INVALID_ENCODING,
            f"documento KCE valido pero sin version determinable: {result.reason}",
        )

    # El tipo de documento se determina por su campo `doc`, leido de forma
    # segura: ya se ha validado que es un map KCE con claves de texto.
    doc_value = value.get("doc")
    schema_name = SCHEMAS_BY_DOC.get(doc_value)
    if schema_name is None:
        raise VerificationError("UNKNOWN_DOCUMENT", f"doc desconocido: {doc_value!r}")

    # 4. Esquema, mundo cerrado.
    schema, verifier = _VERIFIERS[schema_name]
    try:
        schemas.validate(schema, value, f"{schema_name}.")
    except schemas.SchemaError as exc:
        raise VerificationError(exc.code, exc.message) from exc

    # Estructuras anidadas dentro de un bundle.
    if schema_name == "ContactBundle":
        nested = value["roster"]
        try:
            schemas.validate(schemas.DEVICE_ROSTER, nested, "ContactBundle.roster.")
        except schemas.SchemaError as exc:
            raise VerificationError(exc.code, exc.message) from exc

    # 5-6. Invariantes semanticas y verificacion criptografica.
    verifier(value, ctx)

    # El paso 7 (estado y frescura) es responsabilidad del cliente que
    # mantiene `highestSequenceKnown` por identidad; no aplica a la
    # verificacion de un documento aislado.
    return value


def verify_roster_with_context(data: bytes, **ctx) -> dict:
    """Atajo tipado para el caso mas frecuente."""
    return verify_document(data, context=ctx)
