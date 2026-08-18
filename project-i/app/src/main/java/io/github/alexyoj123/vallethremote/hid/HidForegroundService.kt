package io.github.alexyoj123.vallethremote.hid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import io.github.alexyoj123.vallethremote.MainActivity
import io.github.alexyoj123.vallethremote.core.DiagLog
import io.github.alexyoj123.vallethremote.R

/**
 * Mantiene vivo el periferico Bluetooth HID mientras el trackpad esta en uso.
 *
 * Sin esto, Android puede matar el proceso al apagar la pantalla y la TV
 * pierde el "raton" en mitad de una pelicula. No hace ningun trabajo propio:
 * solo existe para que el proceso no se vaya.
 */
class HidForegroundService : android.app.Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Trackpad activo")
            .setContentText("El celular está funcionando como mouse y teclado de la TV")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        // Un servicio de tipo connectedDevice exige BLUETOOTH_CONNECT concedido.
        // Si el dueno lo rechazo, startForeground lanza SecurityException y la
        // app se cae: se prefiere apagar el servicio en silencio. El trackpad
        // sigue funcionando mientras la app este en primer plano.
        val ok = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                },
            )
        }.isSuccess

        if (!ok) {
            DiagLog.w("hid", "no se pudo iniciar el servicio en primer plano (falta permiso de Bluetooth)")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Trackpad Bluetooth", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Aviso mientras el celular actúa como mouse/teclado de la TV"
                    setShowBadge(false)
                },
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "hid_trackpad"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, HidForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HidForegroundService::class.java))
        }
    }
}
