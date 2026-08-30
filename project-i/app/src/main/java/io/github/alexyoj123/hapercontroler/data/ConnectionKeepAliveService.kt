package io.github.alexyoj123.hapercontroler.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import io.github.alexyoj123.hapercontroler.MainActivity
import io.github.alexyoj123.hapercontroler.R
import io.github.alexyoj123.hapercontroler.core.DiagLog

/**
 * Mantiene viva la conexion con la TV mientras la app esta abierta.
 *
 * Android restringe la red de las apps en segundo plano (Doze / App Standby)
 * poco despues de que la pantalla se apaga o la app deja de estar visible —
 * es la razon tipica por la que los controles remotos "se desconectan solos"
 * apenas la pantalla se apaga. Un servicio en primer plano exime al proceso
 * de esa restriccion mientras dure la conexion, sin sacar a la app de
 * primer plano de verdad ni pedir permisos nuevos.
 *
 * No hace ningun trabajo propio — el socket lo mantiene el driver activo en
 * [RemoteRepository]. Esto solo evita que el sistema lo mate.
 */
class ConnectionKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()

        val nombre = intent?.getStringExtra(EXTRA_NOMBRE) ?: "la TV"
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Conectado a $nombre")
            .setContentText("El control sigue activo aunque se apague la pantalla")
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        val ok = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
        }.isSuccess

        if (!ok) {
            DiagLog.w("keepalive", "no se pudo iniciar el servicio en primer plano")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Conexión con la TV", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Aviso mientras el control sigue conectado con la pantalla apagada"
                    setShowBadge(false)
                },
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "connection_keepalive"
        private const val NOTIFICATION_ID = 43
        private const val EXTRA_NOMBRE = "nombre"

        fun start(context: Context, nombreDispositivo: String) {
            val intent = Intent(context, ConnectionKeepAliveService::class.java)
                .putExtra(EXTRA_NOMBRE, nombreDispositivo)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionKeepAliveService::class.java))
        }
    }
}
