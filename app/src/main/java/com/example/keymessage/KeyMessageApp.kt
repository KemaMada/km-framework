package com.example.keymessage

import android.app.Application
import android.content.Intent
import android.os.Build
import com.example.keymessage.service.KeyMessageService
import com.example.keymessage.util.KMLog

/**
 * 5.2 — El `Application` de la app.
 *
 * Monta el contenedor y arranca el servicio en primer plano tipo WhatsApp para
 * que la conexion al relay se mantenga con la UI cerrada.
 */
class KeyMessageApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val intent = Intent(this, KeyMessageService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onTerminate() {
        if (::container.isInitialized) container.parar()
        KMLog.clear()
        super.onTerminate()
    }
}