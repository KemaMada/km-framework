"""
Identificadores derivados de clave -- KM-ID-0001 seccion 6
==========================================================

Los tres identificadores de KM se derivan de claves publicas con
separacion de dominio, de modo que no pueden colisionar entre si ni con
ninguna otra construccion:

    identityId = SHA-256("KM-ID-IDENTITY" || identityRootPublicKey)
    deviceId   = SHA-256("KM-ID-DEVICE"   || deviceSigningPublicKey)
    nodeId     = SHA-256("KM-NODE-NODE"  || nodeIdentityPublicKey)

INVARIANTE: identidad, dispositivo y nodo son objetos DISTINTOS. Un
identificador no es un alias de otro.

La clave de acuerdo X25519 NO participa en la derivacion de deviceId:
deviceId se ancla a la clave de FIRMA, que es la que autoriza al
dispositivo.
"""

from __future__ import annotations

import hashlib

import constants as C


def _derive(domain: str, public_key: bytes) -> bytes:
    if len(public_key) != C.PUBKEY_BYTES:
        raise ValueError(
            f"la clave publica debe tener {C.PUBKEY_BYTES} bytes, "
            f"se recibieron {len(public_key)}"
        )
    digest = hashlib.sha256()
    # El dominio se concatena como bytes UTF-8, sin ningun separador ni
    # prefijo de longitud: el prefijo de dominio hace la separacion.
    digest.update(domain.encode("utf-8"))
    digest.update(public_key)
    return digest.digest()


def identity_id(identity_root_public_key: bytes) -> bytes:
    """Deriva el identityId de una clave de identidad de usuario (Ed25519)."""
    return _derive(C.DS_IDENTITY, identity_root_public_key)


def device_id(device_signing_public_key: bytes) -> bytes:
    """Deriva el deviceId de una clave de firma de dispositivo (Ed25519)."""
    return _derive(C.DS_DEVICE, device_signing_public_key)


def node_id(node_identity_public_key: bytes) -> bytes:
    """Deriva el nodeId de una clave de identidad de nodo (Ed25519)."""
    return _derive(C.DS_NODE, node_identity_public_key)
