"""
IdentityRotation -- KM-ID-0001 seccion 11
=========================================

La rotacion de identidad es BILATERAL: tanto la raiz antigua como la nueva
firman el mismo payload.

  oldRoot  --firma-->  IdentityRotation
  newRoot  --firma-->  IdentityRotation

AMBAS firmas cubren el MISMO `Signable`, que excluye a la vez `newRootSign`
y `oldRootSign`.

POR QUE BILATERAL Y NO UNIDIRECCIONAL

Si la raiz antigua se filtra, el atacante puede producir una rotacion
"hacia adelante" que parece legitima. Lo que NO puede es falsificar la
contrafirma de una raiz nueva que el usuario nunca genero. La firma bilateral
convierte ese ataque unilateral en algo VISIBLE en lugar de silencioso.

Esta es tambien la razon por la que el reinicio de `sequence` tras una
rotacion es seguro: el contador arranca en 1 para la identidad nueva, y ese
reinicio solo puede ser aceptado si existe un IdentityRotation valido.

Nota: KM-ID-0001 reserva el CONCEPTO de autoridad de recuperacion, pero el
mecanismo concreto de recuperacion NO se define aqui. Si la raiz antigua se
comprometera, la recuperacion no puede depender solo de la propia raiz.
"""

from __future__ import annotations

import constants as C
import ed25519
import schemas
from errors import VerificationError
from kce import encode as kce_encode


def build_rotation(
    old_root_public_key: bytes,
    new_root_public_key: bytes,
    sequence: int,
) -> dict:
    """Construye un IdentityRotation SIN firmar."""
    if old_root_public_key == new_root_public_key:
        raise VerificationError("DEGENERATE_ROTATION", "oldRoot y newRoot son iguales")

    rotation = {
        "doc": C.DOC_ROTATION,
        "version": C.CURRENT_VERSION,
        "oldRoot": old_root_public_key,
        "newRoot": new_root_public_key,
        "sequence": sequence,
    }
    schemas.validate(
        schemas.IDENTITY_ROTATION,
        {**rotation, "newRootSign": bytes(64), "oldRootSign": bytes(64)},
        "IdentityRotation.",
    )
    return rotation


def sign_rotation(
    rotation: dict,
    *,
    old_root_private_key: bytes | None = None,
    new_root_private_key: bytes | None = None,
) -> dict:
    """Firma el certificado con las claves privadas que correspondan.

    En el caso normal (rotacion legitima) se pasan ambas claves privadas.
    """
    payload = schemas.IDENTITY_ROTATION.signable(rotation)
    encoded = kce_encode(payload)

    signed = dict(rotation)
    if new_root_private_key is not None:
        signed["newRootSign"] = ed25519.sign(encoded, new_root_private_key)
    if old_root_private_key is not None:
        signed["oldRootSign"] = ed25519.sign(encoded, old_root_private_key)
    return signed


def signable_bytes(rotation: dict) -> bytes:
    return kce_encode(schemas.IDENTITY_ROTATION.signable(rotation))


def canonical_bytes(rotation: dict) -> bytes:
    return kce_encode(rotation)


def verify_rotation(
    rotation: dict,
    *,
    expected_old_root: bytes | None = None,
    expected_new_root: bytes | None = None,
) -> None:
    """Verifica un IdentityRotation ya decodificado.

    Exige AMBAS firmas. Un certificado con una sola firma debe rechazarse:
    es exactamente el caso que la firma bilateral existe para detectar.
    """
    if expected_old_root is not None and rotation["oldRoot"] != expected_old_root:
        raise VerificationError("IDENTITY_MISMATCH", "oldRoot inesperada")
    if expected_new_root is not None and rotation["newRoot"] != expected_new_root:
        raise VerificationError("IDENTITY_MISMATCH", "newRoot inesperada")

    if rotation["oldRoot"] == rotation["newRoot"]:
        raise VerificationError("DEGENERATE_ROTATION", "oldRoot y newRoot son iguales")

    payload = schemas.IDENTITY_ROTATION.signable(rotation)
    encoded = kce_encode(payload)

    if not ed25519.verify(rotation["oldRootSign"], encoded, rotation["oldRoot"]):
        raise VerificationError("INVALID_OLD_ROOT_SIGNATURE", "la firma de la raiz antigua no verifica")
    if not ed25519.verify(rotation["newRootSign"], encoded, rotation["newRoot"]):
        raise VerificationError("INVALID_NEW_ROOT_SIGNATURE", "la firma de la raiz nueva no verifica")
