package com.example.keymessage.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.Images
import java.io.File
import java.io.FileOutputStream

object MediaStoreHelper {
    fun saveToCache(context: Context, bitmap: Bitmap): Uri {
        // Save to app's cache directory instead of MediaStore
        val cacheDir = File(context.cacheDir, "qr_shares")
        cacheDir.mkdirs()
        val file = File(cacheDir, "contact_qr_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return Uri.fromFile(file)
    }
}
