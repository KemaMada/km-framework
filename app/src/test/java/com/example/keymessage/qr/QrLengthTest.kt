package com.example.keymessage.qr

import com.example.keymessage.contact.SelfBundleExporter
import com.example.keymessage.identity.IdentityMaterialFactory
import com.example.keymessage.identity.LocalKeyFactory
import org.junit.Test
import kotlin.test.assertTrue

class QrLengthTest {
    @Test
    fun exportedBundleFitsInScannableQr() {
        val identity = IdentityMaterialFactory().generate()
        val localKeys = LocalKeyFactory(identity.keyPair.publicKey).generate(1)
        val exported = SelfBundleExporter.export(identity, localKeys)
        assertTrue(
            exported.text.length <= 500,
            "el QR tiene que ser escaneable por cualquier camara: ${exported.text.length} chars",
        )
    }
}