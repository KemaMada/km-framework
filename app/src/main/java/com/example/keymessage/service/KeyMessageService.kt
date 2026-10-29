package com.example.keymessage.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.keymessage.KeyMessageApp
import com.example.keymessage.R
import com.example.keymessage.ui.MainActivity
import com.km.protocol.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano tipo WhatsApp: mantiene la conexion al relay viva
 * mientras la UI esta cerrada y recibe mensajes cifrados.
 *
 * ## QUE HACE
 *
 * 1. Crea el canal de notificacion y se promociona a foreground con la
 *    notificacion persistente de [ServiceContract].
 * 2. Arranca el [com.example.keymessage.AppContainer] (failover de endpoints) en
 *    un scope de IO.
 * 3. Observa el estado de conexion y actualiza la notificacion.
 * 4. Al recibir [ServiceContract.ACTION_STOP] (boton "Detener") se detiene.
 *
 * ## QUE NO HACE
 *
 * No reimplementa criptografia ni transporte: todo lo delega al AppContainer,
 * que es el mismo que usa la Activity. El servicio solo mantiene el proceso
 * vivo y la notificacion visible.
 */
class KeyMessageService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            ServiceContract.NOTIFICATION_ID,
            buildNotification(ConnectionState.CONNECTING, null),
        )
        scope.launch {
            val container = (application as KeyMessageApp).container
            container.arrancar()
            container.state.collectLatest { state ->
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(
                    ServiceContract.NOTIFICATION_ID,
                    buildNotification(state, runCatching { container.endpoints.preferred }.getOrNull()),
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ServiceContract.ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        (application as? KeyMessageApp)?.container?.parar()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ServiceContract.CHANNEL_ID,
                ServiceContract.TITLE,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = ServiceContract.text(ConnectionState.ONLINE)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(state: ConnectionState, detail: String?): Notification {
        val stopIntent = Intent(this, KeyMessageService::class.java).apply {
            action = ServiceContract.ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, ServiceContract.CHANNEL_ID)
            .setContentTitle(ServiceContract.TITLE)
            .setContentText(ServiceContract.text(state, detail))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopPending)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}