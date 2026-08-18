package io.github.alexyoj123.hapercontroler.deploy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.alexyoj123.hapercontroler.R
import io.github.alexyoj123.hapercontroler.core.DiagLog
import io.github.alexyoj123.hapercontroler.data.DeviceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Chequeo diario de actualizaciones y despliegue en el aparato.
 *
 * Corre con `WorkManager`: solo con Wi-Fi y sin bateria baja. Si el aparato no
 * esta accesible el trabajo NO reintenta en bucle — deja el motivo escrito y
 * espera al proximo dia. Un actualizador que se pelea con la red cada 15
 * minutos gasta bateria y no arregla nada.
 */
class DeployWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = DeviceStore(applicationContext)
        val config = DeployConfig.fromJson(store.deployConfigRaw())

        if (!config.enabled) {
            DiagLog.i("deploy", "el despliegue automático está apagado; no se hace nada")
            return Result.success()
        }

        DiagLog.i("deploy", "chequeo automático de actualizaciones")
        val engine = DeployEngine(applicationContext, store, CoroutineScope(SupervisorJob()))
        val report = runCatching { engine.run(config) }.getOrElse { t ->
            DiagLog.e("deploy", "el chequeo automático falló", t)
            DeployReport(
                System.currentTimeMillis(),
                listOf(
                    DeployReport.Entry(
                        "Chequeo", null, DeployReport.Outcome.ERROR,
                        t.message ?: "error inesperado",
                    ),
                ),
            )
        }

        store.putDeployReport(report)
        notify(report)
        // Siempre success: el reintento lo decide el calendario, no el worker.
        return Result.success()
    }

    private fun notify(report: DeployReport) {
        if (ContextCompat.checkSelfPermission(
                applicationContext,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }
        runCatching {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Despliegue", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Resultado del chequeo diario de actualizaciones"
                    },
                )
            }
            val detalle = report.entries.joinToString("\n") { "· ${it.label}: ${it.detail}" }
            val notification = android.app.Notification.Builder(applicationContext, CHANNEL_ID)
                .setContentTitle("HAPER CONTROLER · ${report.resumen()}")
                .setContentText(detalle.lineSequence().firstOrNull().orEmpty())
                .setStyle(android.app.Notification.BigTextStyle().bigText(detalle))
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "deploy"
        private const val NOTIFICATION_ID = 77
        const val UNIQUE_WORK = "haper_deploy_diario"
        const val ONE_SHOT_WORK = "haper_deploy_ahora"

        /** Programa (o reprograma) el chequeo diario a la hora configurada. */
        fun schedule(context: Context, config: DeployConfig) {
            val manager = WorkManager.getInstance(context)
            if (!config.enabled) {
                manager.cancelUniqueWork(UNIQUE_WORK)
                DiagLog.i("deploy", "chequeo diario cancelado")
                return
            }

            val request = PeriodicWorkRequestBuilder<DeployWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setInitialDelay(retrasoHasta(config.hour), TimeUnit.MINUTES)
                .build()

            manager.enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            DiagLog.i("deploy", "chequeo diario programado para las ${config.hour}:00")
        }

        /** Chequeo inmediato disparado a mano desde la pantalla. */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_WORK,
                androidx.work.ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DeployWorker>().build(),
            )
        }

        private fun retrasoHasta(hora: Int): Long {
            val ahora = Calendar.getInstance()
            val objetivo = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hora)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                if (before(ahora)) add(Calendar.DAY_OF_YEAR, 1)
            }
            return ((objetivo.timeInMillis - ahora.timeInMillis) / 60_000).coerceAtLeast(1)
        }
    }
}
