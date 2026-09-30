"""
ContactBundle, SignedPrekey y OneTimePrekey -- KM-ID-0001 secciones 13, 14
==========================================================================

El ContactBundle es el artefacto que viaja como `.kmc` o como QR. El
contenido criptografico subyacente es el MISMO en ambos casos: no existen
"protocolo QR" y "protocolo .kmc" separados.

DOS RESPONSABILIDADES DISTINTAS

    DeviceRoster  ->  QUE dispositivos estan autorizados
    ContactBundle ->  COMO abro sesion con subjectDevice

Por eso el bundle incluye el roster completo (necesario para bootstrap
autonomo) pero prekeys SOLO de subjectDevice. Nunca prekeys de los demas
dispositivos del roster.

BOOTSTRAP, NO ESTADO PERMANENTE

El bundle lleva un snapshot firmado del roster y prekeys vigentes, de modo
que es autonomo: permite iniciar una relacion sin ningun servicio. Pero es
un bootstrap, no una fuente de verdad. Los prekeys son de un solo uso y se
consumen; el roster envejece. La senalizacion despues aporta roster y
prekeys frescos.

La AUTHENTICIDAD la da la firma de la raiz, nunca el servidor de
senalizacion. Un servidor malicioso puede negarse a servir un bundle, pero
no puede falsificar uno.

INVARIANTE DE BINDING

El bundle no puede afirmar que pertenece al dispositivo X mientras contiene
las claves de Y. El verificador DEBE comprobar que:

    subjectSigningKey   ==  DeviceEntry(subjectDeviceId).signingKey
    subjectAgreementKey ==  DeviceEntry(subjectDeviceId).agreementKey
    deviceId(subjectSigningKey) == subjectDeviceId
    deviceId == deviceId de cada SignedPrekey y OneTimePrekey
"""

from __future__ import annotations

import constants as C
import ed25519
import roster as roster_mod
import schemas
from errors import VerificationError
from identity import device_id, identity_id
from kce import encode as kce_encode


# ---------------------------------------------------------------------------
# Prekeys
# ---------------------------------------------------------------------------


def build_signed_prekey(
    device_signing_public_key: bytes,
    public_key: bytes,
    *,
    key_id: int,
    device_id_bytes: bytes,
    created_at: int = C.SAMPLE_CREATED_AT,
    expires_at: int = C.SAMPLE_CREATED_AT + C.SAMPLE_SPK_VALIDITY_MS,
) -> dict:
    """Construye un SignedPrekey SIN firmar.

    La firma la emite la clave de FIRMA DEL DISPOSITIVO, no la raiz: la
    cadena de autoridad es
        IdentityRoot -> DeviceRoster -> DeviceSigningKey -> SignedPrekey
    """
    prekey = {
        "doc": C.DOC_SIGNED_PREKEY,
        "version": C.CURRENT_VERSION,
        "deviceId": device_id_bytes,
        "keyId": key_id,
        "publicKey": public_key,
        "createdAt": created_at,
        "expiresAt": expires_at,
    }
    schemas.validate(schemas.SIGNED_PREKEY, {**prekey, "signature": bytes(64)}, "SignedPrekey.")
    return prekey


def sign_signed_prekey(prekey: dict, device_signing_private_key: bytes) -> dict:
    encoded = kce_encode(schemas.SIGNED_PREKEY.signable(prekey))
    signed = dict(prekey)
    signed["signature"] = ed25519.sign(encoded, device_signing_private_key)
    return signed


def build_onetime_prekey(
    public_key: bytes,
    *,
    key_id: int,
    device_id_bytes: bytes,
) -> dict:
    """Construye una OneTimePrekey.

    SIN FIRMA PROPIA, deliberadamente: hereda la autenticidad de la firma
    de la raiz que envuelve el ContactBundle. Asi el verificador no tiene
    que verificar N firmas adicionales, y la garantia es "o el bundle
    entero es valido, o no sirve".
    """
    opk = {
        "doc": C.DOC_ONETIME_PREKEY,
        "version": C.CURRENT_VERSION,
        "deviceId": device_id_bytes,
        "keyId": key_id,
        "publicKey": public_key,
    }
    schemas.validate(schemas.ONETIME_PREKEY, opk, "OneTimePrekey.")
    return opk


def build_endpoint_hint(
    endpoint_type: int,
    value: str,
    *,
    observed_at: int = C.SAMPLE_CREATED_AT,
    relay_id: bytes | None = None,
) -> dict:
    """Construye una pista de endpoint. `relay_id` es obligatorio si es RELAY."""
    if endpoint_type == C.ENDPOINT_RELAY and relay_id is None:
        raise VerificationError("MISSING_FIELD", "relayId es obligatorio si type == RELAY")
    hint = {
        "type": endpoint_type,
        "value": value,
        "observedAt": observed_at,
    }
    if relay_id is not None:
        hint["relayId"] = relay_id
    schemas.validate(schemas.ENDPOINT_HINT, hint, "EndpointHint.")
    return hint


# ---------------------------------------------------------------------------
# ContactBundle
# ---------------------------------------------------------------------------


def build_bundle(
    identity_root_public_key: bytes,
    signed_roster: dict,
    subject_device_id: bytes,
    signed_prekey: dict,
    *,
    kind: int = C.KIND_CONTACT,
    one_time_prekeys: list[dict] | None = None,
    endpoints: list[dict] | None = None,
    capabilities: int = 0,
    created_at: int = C.SAMPLE_CREATED_AT,
    expires_at: int | None = None,
    link_secret: bytes | None = None,
) -> dict:
    """Construye un ContactBundle SIN firmar.

    `subject_device_id` DEBE existir en `signed_roster`; sus claves se toman
    del roster, no se aceptan por separado. Esto hace imposible por
    construccion el bundle que dice una cosa y contiene otra.
    """
    entry = roster_mod.find_device(signed_roster, subject_device_id)
    if entry is None:
        raise VerificationError("SUBJECT_NOT_IN_ROSTER", "subjectDeviceId no figura en el roster")
    if entry["status"] != C.STATUS_ACTIVE:
        raise VerificationError("SUBJECT_REVOKED", "subjectDeviceId esta revocado")

    one_time_prekeys = one_time_prekeys or []
    if len(one_time_prekeys) > C.MAX_ONE_TIME_PREKEYS:
        raise VerificationError("TOO_MANY_PREKEYS", f"maximo {C.MAX_ONE_TIME_PREKEYS}")
    endpoints = endpoints or []
    if len(endpoints) > C.MAX_ENDPOINTS:
        raise VerificationError("TOO_MANY_ENDPOINTS", f"maximo {C.MAX_ENDPOINTS}")

    if kind == C.KIND_DEVICE_LINK and link_secret is None:
        raise VerificationError("MISSING_FIELD", "linkSecret es obligatorio si kind == DEVICE_LINK")
    if kind == C.KIND_CONTACT and link_secret is not None:
        raise VerificationError("UNEXPECTED_FIELD", "linkSecret solo aplica a DEVICE_LINK")

    bundle = {
        "doc": C.DOC_CONTACT_BUNDLE,
        "version": C.CURRENT_VERSION,
        "kind": kind,
        "createdAt": created_at,
        "identityRoot": identity_root_public_key,
        "identityId": identity_id(identity_root_public_key),
        "roster": signed_roster,
        "subjectDeviceId": subject_device_id,
        "subjectSigningKey": entry["signingKey"],
        "subjectAgreementKey": entry["agreementKey"],
        "signedPrekey": signed_prekey,
        "oneTimePrekeys": one_time_prekeys,
        "endpoints": endpoints,
        "capabilities": capabilities,
    }
    if expires_at is not None:
        bundle["expiresAt"] = expires_at
    if link_secret is not None:
        bundle["linkSecret"] = link_secret

    schemas.validate(schemas.CONTACT_BUNDLE, {**bundle, "signature": bytes(64)}, "ContactBundle.")
    return bundle


def sign_bundle(bundle: dict, identity_root_private_key: bytes) -> dict:
    """Firma el bundle con la raiz de identidad."""
    encoded = kce_encode(schemas.CONTACT_BUNDLE.signable(bundle))
    signed = dict(bundle)
    signed["signature"] = ed25519.sign(encoded, identity_root_private_key)
    return signed


def signable_bytes(bundle: dict) -> bytes:
    return kce_encode(schemas.CONTACT_BUNDLE.signable(bundle))


def canonical_bytes(bundle: dict) -> bytes:
    return kce_encode(bundle)


# ---------------------------------------------------------------------------
# Verificacion
# ---------------------------------------------------------------------------


def verify_bundle(
    bundle: dict,
    *,
    expected_identity_root: bytes | None = None,
) -> None:
    """Verifica un ContactBundle ya decodificado y con esquema validado.

    Orden de comprobacion:
      1. binding del subject contra la DeviceEntry del roster
      2. el roster embebido verifica (incluida su propia cadena y firma)
      3. binding de prekeys al subject
      4. firma de la raiz sobre el bundle
    """
    if expected_identity_root is not None and bundle["identityRoot"] != expected_identity_root:
        raise VerificationError("IDENTITY_MISMATCH", "identityRoot inesperada")

    if bundle["identityId"] != identity_id(bundle["identityRoot"]):
        raise VerificationError("IDENTITY_ID_MISMATCH", "identityId no coincide con su derivacion")

    # 1. El roster embebido debe verificar por si mismo en todo lo que SI es
    #    comprobable sin estado externo: firma, identidad, derivaciones de
    #    deviceId y revocacion. La CONTINUIDAD de la cadena no lo es: el
    #    bundle embebe un unico snapshot, asi que si su sequence > 1 el
    #    enlace al roster anterior no puede verificarse aqui. La autoridad
    #    del bundle es la firma de la raiz, no el encadenamiento.
    roster_mod.verify_roster(bundle["roster"], require_chain=False)

    if bundle["roster"]["identityRoot"] != bundle["identityRoot"]:
        raise VerificationError("IDENTITY_MISMATCH", "el roster embebido es de otra identidad")

    # 2. Binding del subject contra la DeviceEntry.
    entry = roster_mod.find_device(bundle["roster"], bundle["subjectDeviceId"])
    if entry is None:
        raise VerificationError("SUBJECT_NOT_IN_ROSTER", "subjectDeviceId no figura en el roster")
    if bundle["subjectSigningKey"] != entry["signingKey"]:
        raise VerificationError("SUBJECT_BINDING_MISMATCH", "subjectSigningKey no coincide con el roster")
    if bundle["subjectAgreementKey"] != entry["agreementKey"]:
        raise VerificationError(
            "SUBJECT_BINDING_MISMATCH", "subjectAgreementKey no coincide con el roster"
        )
    if device_id(bundle["subjectSigningKey"]) != bundle["subjectDeviceId"]:
        raise VerificationError("SUBJECT_BINDING_MISMATCH", "subjectDeviceId no deriva de su signingKey")

    # 3. Binding de prekeys al subject.
    spk = bundle["signedPrekey"]
    schemas.validate(schemas.SIGNED_PREKEY, spk, "ContactBundle.signedPrekey.")
    if spk["deviceId"] != bundle["subjectDeviceId"]:
        raise VerificationError("PREKEY_BINDING_MISMATCH", "signedPrekey.deviceId != subjectDeviceId")
    if not ed25519.verify(spk["signature"], signable_prekey_bytes(spk), entry["signingKey"]):
        raise VerificationError("INVALID_SIGNATURE", "la firma del signed prekey no verifica")

    for opk in bundle["oneTimePrekeys"]:
        schemas.validate(schemas.ONETIME_PREKEY, opk, "ContactBundle.oneTimePrekeys[].")
        if opk["deviceId"] != bundle["subjectDeviceId"]:
            raise VerificationError("PREKEY_BINDING_MISMATCH", "oneTimePrekey.deviceId != subjectDeviceId")

    for hint in bundle.get("endpoints", []):
        schemas.validate(schemas.ENDPOINT_HINT, hint, "ContactBundle.endpoints[].")
        if hint["type"] == C.ENDPOINT_RELAY and "relayId" not in hint:
            raise VerificationError("MISSING_FIELD", "relayId es obligatorio si type == RELAY")

    # 4. Autenticidad del bundle por la raiz.
    payload = schemas.CONTACT_BUNDLE.signable(bundle)
    if not ed25519.verify(bundle["signature"], kce_encode(payload), bundle["identityRoot"]):
        raise VerificationError("INVALID_SIGNATURE", "la firma de la raiz sobre el bundle no verifica")


def signable_prekey_bytes(prekey: dict) -> bytes:
    return kce_encode(schemas.SIGNED_PREKEY.signable(prekey))
