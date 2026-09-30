"""
DeviceRoster -- KM-ID-0001 seccion 10
====================================

El DeviceRoster responde a UNA pregunta:

    QUE DISPOSITIVOS ESTAN AUTORIZADOS PARA ESTA IDENTIDAD?

NO responde a "como abro sesion con este dispositivo": eso es material de
establecimiento de sesion y vive en el ContactBundle, para un unico
dispositivo (subjectDevice). La confusion entre ambas responsabilidades es
la fuente clasica de errores de identidad, y por eso la separacion es
normativa.

CADENA DE HASHES

  previous = SHA-256("KM-ID-ROSTER-GENESIS" || identityRoot)   si sequence == 1
  previous = SHA-256(KCE(roster previo COMPLETO))               si sequence > 1

El genesis se ancla a la identidad en lugar de usar un campo ausente: asi
el primer eslabon queda criptograficamente ligado a la identidad y no
admite un roster #1 alternativo para la misma clave.

El hash encadena el documento COMPLETO, incluida su firma, de modo que
tambien encadena la firma.

ALCANCE DE `sequence`

`sequence` tiene alcance en `identityRoot`. Una rotacion de identidad crea
un espacio de identidad NUEVO y su roster arranca en 1.

Esto solo es seguro porque la rotacion es bilateral: un atacante con la
raiz antigua robada puede firmar una rotacion, pero NO puede producir la
contrafirma de la raiz nueva legitima, de modo que la rotacion falsificada
se rechaza y el reinicio del contador no sirve para evadir el rastreo de
`highestSequenceKnown`.
"""

from __future__ import annotations

import hashlib

import constants as C
import ed25519
import schemas
from errors import VerificationError
from identity import device_id, identity_id
from kce import encode as kce_encode


# ---------------------------------------------------------------------------
# Construccion
# ---------------------------------------------------------------------------


def genesis_previous(identity_root_public_key: bytes) -> bytes:
    """Ancla del genesis de la cadena, ligada a la identidad."""
    digest = hashlib.sha256()
    digest.update(C.DS_ROSTER_GENESIS.encode("utf-8"))
    digest.update(identity_root_public_key)
    return digest.digest()


def chain_previous(previous_roster: dict) -> bytes:
    """Enlace al roster previo. Usa el documento completo, incluida la firma."""
    return hashlib.sha256(kce_encode(previous_roster)).digest()


def make_device_entry(
    signing_public_key: bytes,
    agreement_public_key: bytes,
    *,
    name: str | None = None,
    status: int = C.STATUS_ACTIVE,
    added_at: int = C.SAMPLE_CREATED_AT,
    revoked_at: int | None = None,
) -> dict:
    """Construye una DeviceEntry. `revoked_at` es obligatorio si se revoca."""
    if status == C.STATUS_REVOKED and revoked_at is None:
        raise VerificationError("MISSING_FIELD", "revokedAt es obligatorio si status == REVOKED")
    if status == C.STATUS_ACTIVE and revoked_at is not None:
        raise VerificationError("UNEXPECTED_FIELD", "revokedAt no aplica si status == ACTIVE")

    entry = {
        "deviceId": device_id(signing_public_key),
        "signingKey": signing_public_key,
        "agreementKey": agreement_public_key,
        "status": status,
        "addedAt": added_at,
    }
    if name is not None:
        entry["name"] = name
    if revoked_at is not None:
        entry["revokedAt"] = revoked_at
    schemas.validate(schemas.DEVICE_ENTRY, entry, "DeviceEntry.")
    return entry


def build_roster(
    identity_root_public_key: bytes,
    devices: list[dict],
    *,
    sequence: int = 1,
    previous: bytes | None = None,
    created_at: int = C.SAMPLE_CREATED_AT,
) -> dict:
    """Construye un DeviceRoster SIN firmar."""
    if not devices:
        raise VerificationError("EMPTY_ROSTER", "un roster debe contener al menos un dispositivo")
    if len(devices) > C.MAX_DEVICES:
        raise VerificationError("TOO_MANY_DEVICES", f"maximo {C.MAX_DEVICES}")

    if previous is None:
        if sequence != 1:
            raise VerificationError("MISSING_PREVIOUS", "sequence > 1 exige previous")
        previous = genesis_previous(identity_root_public_key)

    roster = {
        "doc": C.DOC_ROSTER,
        "version": C.CURRENT_VERSION,
        "identityRoot": identity_root_public_key,
        "identityId": identity_id(identity_root_public_key),
        "sequence": sequence,
        "previous": previous,
        "createdAt": created_at,
        "devices": devices,
    }
    schemas.validate(schemas.DEVICE_ROSTER, {**roster, "signature": bytes(64)}, "DeviceRoster.")
    return roster


def sign_roster(roster: dict, identity_root_private_key: bytes) -> dict:
    """Devuelve el roster con su firma, lista para canonicalizar."""
    payload = schemas.DEVICE_ROSTER.signable(roster)
    signature = ed25519.sign(kce_encode(payload), identity_root_private_key)
    signed = dict(roster)
    signed["signature"] = signature
    return signed


# ---------------------------------------------------------------------------
# Canonicalizacion
# ---------------------------------------------------------------------------


def signable_bytes(roster: dict) -> bytes:
    """KCE del payload firmable: la estructura sin `signature`."""
    return kce_encode(schemas.DEVICE_ROSTER.signable(roster))


def canonical_bytes(roster: dict) -> bytes:
    """KCE del documento completo, incluida la firma."""
    return kce_encode(roster)


# ---------------------------------------------------------------------------
# Verificacion semantica y criptografica
# ---------------------------------------------------------------------------


def verify_roster(
    roster: dict,
    *,
    previous_roster: dict | None = None,
    expected_identity_root: bytes | None = None,
    require_chain: bool = True,
) -> None:
    """Verifica un DeviceRoster ya decodificado.

    Asume que KCE y el esquema ya fueron validados; comprueba las
    invariantes semanticas y la firma.

    `require_chain=False` desactiva la comprobacion de continuidad para el
    caso en que el roster va incrustado dentro de un ContactBundle. Un
    bundle embebe UN solo snapshot, de modo que si su `sequence` es > 1 el
    enlace al anterior NO es verificable a partir del propio documento. En
    ese contexto la autoridad es exclusivamente la FIRMA de la raiz, y el
    RFC no debe prometer mas. Cuando `sequence == 1` el ancla genesis si
    es comprobable sin estado externo, y se comprueba siempre.

    Lanza VerificationError con un codigo estable ante cualquier fallo.
    """
    if expected_identity_root is not None and roster["identityRoot"] != expected_identity_root:
        raise VerificationError("IDENTITY_MISMATCH", "identityRoot inesperada")

    # Invariante: identityId es DERIVADA de identityRoot, nunca declarada.
    if roster["identityId"] != identity_id(roster["identityRoot"]):
        raise VerificationError("IDENTITY_ID_MISMATCH", "identityId no coincide con su derivacion")

    if not roster["devices"]:
        raise VerificationError("EMPTY_ROSTER", "un roster debe contener al menos un dispositivo")
    if len(roster["devices"]) > C.MAX_DEVICES:
        raise VerificationError("TOO_MANY_DEVICES", f"maximo {C.MAX_DEVICES}")

    # Invariante: cada deviceId se deriva de SU clave de firma.
    seen: set[bytes] = set()
    for entry in roster["devices"]:
        schemas.validate(schemas.DEVICE_ENTRY, entry, "DeviceRoster.devices[].")
        if entry["deviceId"] != device_id(entry["signingKey"]):
            raise VerificationError("DEVICE_ID_MISMATCH", "deviceId no coincide con su derivacion")
        if entry["deviceId"] in seen:
            raise VerificationError("DUPLICATE_DEVICE", "deviceId repetido en el roster")
        seen.add(entry["deviceId"])

        is_revoked = entry["status"] == C.STATUS_REVOKED
        has_revoked_at = "revokedAt" in entry
        if is_revoked != has_revoked_at:
            raise VerificationError(
                "REVOCATION_FIELD_MISMATCH",
                "revokedAt debe estar presente si y solo si status == REVOKED",
            )

    # Continuidad de la cadena.
    if roster["sequence"] == 1:
        # El ancla genesis es comprobable sin estado externo: siempre.
        expected_previous = genesis_previous(roster["identityRoot"])
        if roster["previous"] != expected_previous:
            raise VerificationError("PREVIOUS_MISMATCH", "el ancla genesis no coincide")
    elif not require_chain:
        # Contexto de bundle: la continuidad no es verificable aqui. Solo se
        # comprueba que el enlace tenga tamano de hash, no que sea correcto.
        if len(roster["previous"]) != C.HASH_BYTES:
            raise VerificationError("PREVIOUS_MISMATCH", "previous debe tener 32 bytes")
    else:
        if previous_roster is None:
            raise VerificationError("MISSING_PREVIOUS", "se requiere el roster previo")
        if previous_roster["identityRoot"] != roster["identityRoot"]:
            raise VerificationError("IDENTITY_MISMATCH", "el roster previo es de otra identidad")
        expected_previous = chain_previous(previous_roster)
        if roster["previous"] != expected_previous:
            raise VerificationError("PREVIOUS_MISMATCH", "el enlace de la cadena no coincide")

    # Autenticidad.
    payload = schemas.DEVICE_ROSTER.signable(roster)
    if not ed25519.verify(roster["signature"], kce_encode(payload), roster["identityRoot"]):
        raise VerificationError("INVALID_SIGNATURE", "la firma de la raiz no verifica")


def find_device(roster: dict, target_device_id: bytes) -> dict | None:
    """Localiza una DeviceEntry por deviceId, o None."""
    for entry in roster["devices"]:
        if entry["deviceId"] == target_device_id:
            return entry
    return None
