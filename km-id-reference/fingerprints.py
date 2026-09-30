"""
Numeros de verificacion -- KM-ID-0001 seccion 16
===============================================

Se definen DOS representations distintas, deliberadamente separadas:

  identitySafetyNumber   representa la IDENTIDAD. Es estable mientras la
                         clave de identidad no rote, de modo que anadir un
                         dispositivo NO lo invalida.

  rosterVerificationCode representa el ESTADO ACTUAL DEL ROSTER. Cambia
                         cuando se anade, retira o revoca un dispositivo.

Esto permite expresar al usuario dos hechos distintos:

  "la identidad sigue siendo la misma, pero su conjunto de dispositivos
   cambio"

DERIVACION

  render60(b) =
      (int(b) mod 10^60) formateado a 60 digitos decimales con ceros a la
      izquierda, agrupado en 12 bloques de 5 separados por espacios.

  identitySafetyNumber   = render60(SHA-256("KM-ID-SAFETY-NUMBER" || identityId)[0:25])
  rosterVerificationCode = render60(SHA-256("KM-ID-ROSTER-CODE" || KCE(SignableRoster))[0:25])

25 bytes = 200 bits cubren los ~199.3157 bits que requiere 10^60.

PROPOSITO Y LIMITES

Un numero de seguridad es un CODIGO DE COMPARACION entre dos extremos que
se comunican por un canal autenticado. NO es un secreto, NO es una
credencial y NO autentica a una persona: no evita un hombre en el medio por
si solo, y no debe describirse como si lo hiciera. Su funcion es permitir
que dos partes detecten que se han intercambio identidades distintas.
"""

from __future__ import annotations

import hashlib

import constants as C
from kce import encode as kce_encode


def render60(digest: bytes) -> str:
    """Reduce un hash a 60 digitos decimales agrupados en 12 bloques de 5.

    El sesgo introducido por el modulo es ~6e-61 relativo y es
    deliberadamente aceptado: este valor se compara, no se adivina.
    """
    if len(digest) < C.SAFETY_NUMBER_SOURCE_BYTES:
        raise ValueError(
            f"se requieren al menos {C.SAFETY_NUMBER_SOURCE_BYTES} bytes, "
            f"se recibieron {len(digest)}"
        )
    value = int.from_bytes(digest[: C.SAFETY_NUMBER_SOURCE_BYTES], "big")
    value %= C.SAFETY_NUMBER_MODULUS
    digits = f"{value:0{C.SAFETY_NUMBER_DIGITS}d}"
    groups = [
        digits[i * C.SAFETY_NUMBER_GROUP_SIZE : (i + 1) * C.SAFETY_NUMBER_GROUP_SIZE]
        for i in range(C.SAFETY_NUMBER_GROUPS)
    ]
    return " ".join(groups)


def identity_safety_number(identity_id_bytes: bytes) -> str:
    """Numero de seguridad de identidad, estable mientras la raiz no rote."""
    digest = hashlib.sha256()
    digest.update(C.DS_SAFETY_NUMBER.encode("utf-8"))
    digest.update(identity_id_bytes)
    return render60(digest.digest())


def roster_verification_code(signable_roster: dict) -> str:
    """Codigo de verificacion del roster; cambia con cada revision del mismo."""
    digest = hashlib.sha256()
    digest.update(C.DS_ROSTER_CODE.encode("utf-8"))
    digest.update(kce_encode(signable_roster))
    return render60(digest.digest())
