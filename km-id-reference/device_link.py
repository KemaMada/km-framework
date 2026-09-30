"""
Device-linking -- KM-ID-0001 seccion 12
======================================

DISPOSITIVO, USUARIO E IDENTIDAD SON OBJETOS DISTINTOS

    IDENTIDAD
       |
       +-- Usuario
       |
       +-- Dispositivo movil   (Ed25519, X25519, prekeys)
       +-- PC                  (Ed25519, X25519, prekeys)
       +-- otro dispositivo    (Ed25519, X25519, prekeys)

La identidad del usuario FIRMA la pertenencia de los dispositivos. Un
dispositivo nunca es un usuario, y un nodo nunca es un usuario.

EL DEVICE-LINK ES UN BOOTSTRAP FUERA DE BANDA, NO AUTENTICACION

    Nivel 1 -- CANAL FISICO
        Movil -- QR / archivo --> PC
        Todavia NO hay autorizacion criptografica del PC.

    Nivel 2 -- PRUEBA DE POSESION
        El PC genera su par de claves y firma el nonce del challenge.
        Esto prueba que posee su clave, no que este autorizado.

    Nivel 3 -- AUTORIZACION DE IDENTIDAD
        El movil anade la nueva DeviceEntry al roster, firma el roster
        nuevo con identityRoot y se lo envia al PC.
        El PC verifica esa firma. AQUI, y no antes, queda autorizado.

FRASE NORMATIVA

    Device linking is an out-of-band bootstrap mechanism. The device-link
    exchange itself does not prove membership in the user's identity.
    Membership becomes cryptographically authenticated only after the
    receiving device obtains and successfully verifies a DeviceRoster
    signed by the user's IdentityRoot containing its DeviceEntry.

POR QUE HAY UN linkSecret

    Sin el, un tercero podria fabricar un QR con SU propia clave de
    dispositivo y pedir al usuario que lo escanee. El usuario creeria que
    esta vinculados a su propio PC y el atacante quedaria autorizado.

    El linkSecret liga este intercambio a ESTA instancia concreta de QR.
    No es, en ningun caso, un sustituto de la autorizacion criptografica:
    conocerlo no convierte a nadie en dispositivo autorizado. La
    autorizacion sigue viniendo de IdentityRoot -> DeviceRoster -> nuevo
    dispositivo.

    nonce   -> frescura y anti-replay
    linkSecret -> liga este intercambio a este QR
"""

from __future__ import annotations

import constants as C
import ed25519
import schemas
from errors import VerificationError
from identity import device_id
from kce import encode as kce_encode


def build_challenge(
    identity_root_public_key: bytes,
    presenting_device_id: bytes,
    nonce: bytes,
    link_secret: bytes,
    *,
    expires_at: int = C.SAMPLE_LINK_EXPIRES_AT,
) -> dict:
    """Construye un DeviceLinkChallenge SIN firmar (movil -> PC)."""
    for label, blob in (("nonce", nonce), ("linkSecret", link_secret)):
        if len(blob) != C.HASH_BYTES:
            raise VerificationError("FIELD_SIZE", f"{label} debe tener {C.HASH_BYTES} bytes")

    challenge = {
        "doc": C.DOC_LINK_CHALLENGE,
        "version": C.CURRENT_VERSION,
        "identityRoot": identity_root_public_key,
        "presentingDeviceId": presenting_device_id,
        "nonce": nonce,
        "expiresAt": expires_at,
        "linkSecret": link_secret,
    }
    schemas.validate(
        schemas.LINK_CHALLENGE, {**challenge, "signature": bytes(64)}, "DeviceLinkChallenge."
    )
    return challenge


def sign_challenge(challenge: dict, presenting_device_signing_private_key: bytes) -> dict:
    encoded = kce_encode(schemas.LINK_CHALLENGE.signable(challenge))
    signed = dict(challenge)
    signed["signature"] = ed25519.sign(encoded, presenting_device_signing_private_key)
    return signed


def build_response(
    identity_root_public_key: bytes,
    signing_public_key: bytes,
    agreement_public_key: bytes,
    nonce: bytes,
    *,
    name: str | None = None,
) -> dict:
    """Construye una DeviceLinkResponse SIN firmar (PC -> movil)."""
    if len(nonce) != C.HASH_BYTES:
        raise VerificationError("FIELD_SIZE", f"nonce debe tener {C.HASH_BYTES} bytes")

    response = {
        "doc": C.DOC_LINK_RESPONSE,
        "version": C.CURRENT_VERSION,
        "identityRoot": identity_root_public_key,
        "deviceId": device_id(signing_public_key),
        "signingKey": signing_public_key,
        "agreementKey": agreement_public_key,
        "nonce": nonce,
    }
    if name is not None:
        response["name"] = name
    schemas.validate(schemas.LINK_RESPONSE, {**response, "signature": bytes(64)}, "DeviceLinkResponse.")
    return response


def sign_response(response: dict, new_device_signing_private_key: bytes) -> dict:
    encoded = kce_encode(schemas.LINK_RESPONSE.signable(response))
    signed = dict(response)
    signed["signature"] = ed25519.sign(encoded, new_device_signing_private_key)
    return signed


def verify_challenge(
    challenge: dict,
    presenting_device_signing_public_key: bytes,
) -> None:
    """Verifica un challenge: prueba que el presentador posee su clave."""
    if device_id(presenting_device_signing_public_key) != challenge["presentingDeviceId"]:
        raise VerificationError(
            "PRESENTING_DEVICE_MISMATCH", "presentingDeviceId no deriva de la clave del presentador"
        )
    encoded = kce_encode(schemas.LINK_CHALLENGE.signable(challenge))
    if not ed25519.verify(challenge["signature"], encoded, presenting_device_signing_public_key):
        raise VerificationError("INVALID_SIGNATURE", "la firma del challenge no verifica")


def verify_response(
    response: dict,
    expected_nonce: bytes,
    *,
    expected_identity_root: bytes | None = None,
) -> None:
    """Verifica una respuesta: prueba de posesion y eco del nonce.

    NO prueba autorizacion. La autorizacion llega despues, en el roster
    firmado por la raiz.
    """
    if expected_identity_root is not None and response["identityRoot"] != expected_identity_root:
        raise VerificationError("IDENTITY_MISMATCH", "identityRoot inesperada")
    if response["nonce"] != expected_nonce:
        raise VerificationError("NONCE_MISMATCH", "el nonce no coincide con el del challenge")
    if response["deviceId"] != device_id(response["signingKey"]):
        raise VerificationError("DEVICE_ID_MISMATCH", "deviceId no deriva de su signingKey")

    encoded = kce_encode(schemas.LINK_RESPONSE.signable(response))
    if not ed25519.verify(response["signature"], encoded, response["signingKey"]):
        raise VerificationError("INVALID_SIGNATURE", "la firma de la respuesta no verifica")
