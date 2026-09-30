"""
Constantes de protocolo KM-ID-0001
=================================

Fuente unica de verdad para separacion de dominio, tamanos, enumeraciones
y limites de esquema.

REGLA NORMATIVA: las cadenas de separacion de dominio son constantes de
protocolo. NO DEBEN localizarse, traducirse, normalizarse ni modificarse
de ninguna forma. Una implementacion que use "km-id-identity" en vez de
"KM-ID-IDENTITY" produce una implementacion incompatible.
"""

from __future__ import annotations

# ---------------------------------------------------------------------------
# Version
# ---------------------------------------------------------------------------

CURRENT_VERSION = 1
"""Version de KCE y de todas las estructuras definidas en KM-ID-0001.

KCE esta versionado: la version seleccionada determina el perfil de
encoding aplicable. Por eso el despacho de version precede a la
validacion KCE en el procedimiento de verificacion.
"""

# ---------------------------------------------------------------------------
# Separacion de dominio
# ---------------------------------------------------------------------------

DS_IDENTITY = "KM-ID-IDENTITY"
DS_DEVICE = "KM-ID-DEVICE"
DS_NODE = "KM-NODE-NODE"
DS_ROSTER_GENESIS = "KM-ID-ROSTER-GENESIS"
DS_ROSTER = "KM-ID-ROSTER"
DS_ROSTER_CODE = "KM-ID-ROSTER-CODE"
DS_SAFETY_NUMBER = "KM-ID-SAFETY-NUMBER"
DS_ROTATION = "KM-ID-ROTATION"
DS_CONTACT_BUNDLE = "KM-CONTACT-BUNDLE"
DS_DEVICE_LINK = "KM-DEVICE-LINK"
DS_PREKEY_SIGNED = "KM-PREKEY-SIGNED"
DS_PREKEY_ONETIME = "KM-PREKEY-ONETIME"

ALL_DOMAIN_SEPARATION_STRINGS = (
    DS_IDENTITY,
    DS_DEVICE,
    DS_NODE,
    DS_ROSTER_GENESIS,
    DS_ROSTER,
    DS_ROSTER_CODE,
    DS_SAFETY_NUMBER,
    DS_ROTATION,
    DS_CONTACT_BUNDLE,
    DS_DEVICE_LINK,
    DS_PREKEY_SIGNED,
    DS_PREKEY_ONETIME,
)


# ---------------------------------------------------------------------------
# Cadenas `doc` (dentro del payload firmado)
# ---------------------------------------------------------------------------
# El campo `doc` viaja DENTRO del material firmado. Sin el, la firma de un
# DeviceRoster seria reutilizable como firma de un ContactBundle.

DOC_ROSTER = "km.deviceRoster"
DOC_ROTATION = "km.identityRotation"
DOC_CONTACT_BUNDLE = "km.contactBundle"
DOC_SIGNED_PREKEY = "km.signedPrekey"
DOC_ONETIME_PREKEY = "km.oneTimePrekey"
DOC_LINK_CHALLENGE = "km.deviceLinkChallenge"
DOC_LINK_RESPONSE = "km.deviceLinkResponse"

# ---------------------------------------------------------------------------
# Tamanos de clave
# ---------------------------------------------------------------------------

PUBKEY_BYTES = 32  # Ed25519 y X25519
SIG_BYTES = 64  # Ed25519
HASH_BYTES = 32  # SHA-256

# ---------------------------------------------------------------------------
# Enumeraciones
# ---------------------------------------------------------------------------

STATUS_ACTIVE = 1
STATUS_REVOKED = 2
DEVICE_STATUS_NAMES = {STATUS_ACTIVE: "ACTIVE", STATUS_REVOKED: "REVOKED"}

KIND_CONTACT = 1
KIND_DEVICE_LINK = 2
BUNDLE_KIND_NAMES = {KIND_CONTACT: "CONTACT", KIND_DEVICE_LINK: "DEVICE_LINK"}

ENDPOINT_ONION = 1
ENDPOINT_UDP = 2
ENDPOINT_RELAY = 3
ENDPOINT_TYPE_NAMES = {
    ENDPOINT_ONION: "ONION",
    ENDPOINT_UDP: "UDP",
    ENDPOINT_RELAY: "RELAY",
}

# ---------------------------------------------------------------------------
# Numeros de verificacion
# ---------------------------------------------------------------------------

SAFETY_NUMBER_DIGITS = 60
"""Numero de digitos decimales del numero de seguridad.

10^60 ~= 2^199.3157, por lo que hacen falta 25 bytes (200 bits) para cubrir
el espacio completo. Verificado aritmeticamente; el error de truncamiento
es despreciable y en cualquier caso irrelevante porque este valor es un
CODIGO DE COMPARACION, no un secreto ni una credencial.
"""

SAFETY_NUMBER_GROUPS = 12
SAFETY_NUMBER_GROUP_SIZE = 5
SAFETY_NUMBER_SOURCE_BYTES = 25
SAFETY_NUMBER_MODULUS = 10**SAFETY_NUMBER_DIGITS

# ---------------------------------------------------------------------------
# Limites de esquema
# ---------------------------------------------------------------------------

MAX_DEVICES = 64
MAX_ONE_TIME_PREKEYS = 128
MAX_ENDPOINTS = 16
MAX_NAME_BYTES = 64
MAX_ENDPOINT_VALUE_BYTES = 256
MAX_UINT64 = (1 << 64) - 1
MAX_CAPABILITIES_UINT = (1 << 32) - 1
"""`capabilities` es un bitfield de 32 bits, no un uint arbitrario."""

# Plazos de ejemplo usados por los vectores (advisory, no normativos).
SAMPLE_CREATED_AT = 1_760_000_000_000  # ms Unix
SAMPLE_LINK_EXPIRES_AT = SAMPLE_CREATED_AT + 60_000  # ~60 s
SAMPLE_SPK_VALIDITY_MS = 30 * 24 * 60 * 60 * 1000  # 30 dias

assert SAFETY_NUMBER_GROUPS * SAFETY_NUMBER_GROUP_SIZE == SAFETY_NUMBER_DIGITS
