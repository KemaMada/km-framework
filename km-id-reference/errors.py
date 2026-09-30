"""
Errores de KM-ID-0001
=====================

Codigos de error estables y citable desde los fixtures y desde el RFC.
"""

from __future__ import annotations


class VerificationError(Exception):
    """Fallo en la verificacion semantica o criptografica de un documento."""

    def __init__(self, code: str, message: str = "") -> None:
        super().__init__(f"{code}: {message}" if message else code)
        self.code = code
        self.message = message
