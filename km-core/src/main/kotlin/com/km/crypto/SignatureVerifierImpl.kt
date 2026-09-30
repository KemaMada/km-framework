package com.km.crypto

class SignatureVerifierImpl(private val ed25519: Ed25519) : SignatureVerifier {
    override fun verifyMessage(signedMessage: SignedMessage): Boolean {
        return ed25519.verify(signedMessage.publicKey, signedMessage.data, signedMessage.signature)
    }

    override fun verifyAck(signedAck: SignedAck): Boolean {
        return ed25519.verify(signedAck.publicKey, signedAck.data, signedAck.signature)
    }
}