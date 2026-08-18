package io.github.alexyoj123.hapercontroler.deploy

import android.content.Context
import android.content.pm.PackageInfo
import io.github.alexyoj123.hapercontroler.core.ConnectionState
import io.github.alexyoj123.hapercontroler.core.DiagLog
import io.github.alexyoj123.hapercontroler.core.DriverKind
import io.github.alexyoj123.hapercontroler.core.TvDevice
import io.github.alexyoj123.hapercontroler.data.DeviceStore
import io.github.alexyoj123.hapercontroler.driver.androidtv.AndroidTvAdbDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * El actualizador que si es automatico.
 *
 * Obtainium baja la actualizacion sola pero **pide un toque para instalar**:
 * sin root, Shizuku o ser device-owner, Android no deja instalar en silencio a
 * una app normal. Este motor esquiva eso por otro lado: instala **por ADB**,
 * y como `pm install` corre entonces con el usuario `shell`, no le aplica la
 * restriccion de "apps desconocidas" que puso el operador.
 *
 * Reglas que respeta, en este orden:
 *  1. microG (`app.revanced.android.gms`) se instala SIEMPRE antes que
 *     cualquier build de YouTube o YouTube Music. Al reves, la app abre y
 *     falla en el login.
 *  2. Solo instala si el `versionCode` del APK descargado es mayor que el
 *     instalado. El del APK se lee sin instalarlo con
 *     `getPackageArchiveInfo`, asi que la comparacion es real y no por fecha.
 *  3. Ante `INSTALL_FAILED_UPDATE_INCOMPATIBLE` o `..._DUPLICATE_PERMISSION`
 *     deshabilita el original con `pm uninstall -k --user 0` y reintenta
 *     **una sola vez**. Si vuelve a fallar se detiene y deja el error exacto.
 *     Nunca un bucle de reintentos.
 */
class DeployEngine(
    private val context: Context,
    private val store: DeviceStore,
    private val scope: CoroutineScope,
) {

    companion object {
        const val MICROG_PACKAGE = "app.revanced.android.gms"

        /** Prioridad de instalacion: mas bajo = antes. */
        private fun ordenDe(packageName: String): Int = when {
            packageName == MICROG_PACKAGE -> 0
            packageName.contains("youtube") || packageName.contains("music") -> 1
            else -> 2
        }
    }

    suspend fun run(config: DeployConfig): DeployReport {
        val entries = mutableListOf<DeployReport.Entry>()
        val ahora = System.currentTimeMillis()

        val deviceId = config.targetDeviceId
        if (deviceId == null) {
            return DeployReport(
                ahora,
                listOf(
                    DeployReport.Entry(
                        "Configuración", null, DeployReport.Outcome.OMITIDO,
                        "Falta elegir el aparato destino en la pantalla Despliegue.",
                    ),
                ),
            )
        }

        val device = store.devices.first().firstOrNull { it.id == deviceId }
        if (device == null || device.kind != DriverKind.ANDROID_TV_ADB) {
            return DeployReport(
                ahora,
                listOf(
                    DeployReport.Entry(
                        "Aparato destino", null, DeployReport.Outcome.ERROR,
                        "El aparato guardado ya no aparece como dispositivo con ADB. " +
                            "Volvé a buscar dispositivos y elegilo de nuevo.",
                    ),
                ),
            )
        }

        val driver = AndroidTvAdbDriver(context, device, scope)
        val conectado = driver.connect()
        if (conectado.isFailure) {
            driver.disconnect()
            return DeployReport(
                ahora,
                listOf(
                    DeployReport.Entry(
                        "Conexión ADB", null, DeployReport.Outcome.ERROR,
                        motivoSinAdb(device, conectado.exceptionOrNull()?.message),
                    ),
                ),
            )
        }

        try {
            val token = store.secret("github_token")
            val github = GithubReleases(token)
            val candidatos = mutableListOf<Candidato>()

            for (line in config.lines.filter { it.enabled }) {
                val assets = github.latestWithPrefix(line.repo, line.tagPrefix).getOrElse { error ->
                    entries += DeployReport.Entry(
                        line.label, null, DeployReport.Outcome.ERROR,
                        error.message ?: "no se pudo consultar GitHub",
                    )
                    continue
                }
                if (assets.isEmpty()) {
                    entries += DeployReport.Entry(
                        line.label, null, DeployReport.Outcome.OMITIDO,
                        "El último release con prefijo ${line.tagPrefix} no trae APKs.",
                    )
                    continue
                }

                // Atajo barato: si el tag no cambio desde el ultimo chequeo, no
                // se baja nada. Ahorra megas y tiempo en el caso normal.
                val visto = store.secret("deploy_tag_" + line.tagPrefix)
                val tag = assets.first().releaseTag
                if (visto == tag) {
                    entries += DeployReport.Entry(
                        line.label, null, DeployReport.Outcome.AL_DIA,
                        "Sin novedades desde $tag.",
                    )
                    continue
                }

                for (asset in assets) {
                    val destino = File(File(context.cacheDir, "deploy"), asset.assetName)
                    val bajado = github.download(asset, destino) { }.getOrElse { error ->
                        entries += DeployReport.Entry(
                            asset.assetName, null, DeployReport.Outcome.ERROR,
                            error.message ?: "no se pudo descargar",
                        )
                        null
                    } ?: continue

                    val info = archiveInfo(bajado)
                    if (info == null) {
                        entries += DeployReport.Entry(
                            asset.assetName, null, DeployReport.Outcome.ERROR,
                            "El archivo no parece un APK válido.",
                        )
                        bajado.delete()
                        continue
                    }
                    candidatos += Candidato(line, asset, bajado, info.packageName, versionCodeDe(info))
                }
                store.putSecret("deploy_tag_" + line.tagPrefix, tag)
            }

            // microG primero, siempre.
            candidatos.sortBy { ordenDe(it.packageName) }

            for (candidato in candidatos) {
                entries += instalar(driver, candidato)
                runCatching { candidato.file.delete() }
            }
        } finally {
            driver.disconnect()
        }

        val report = DeployReport(ahora, entries)
        DiagLog.i("deploy", "chequeo terminado: ${report.resumen()}")
        return report
    }

    private data class Candidato(
        val line: WatchedLine,
        val asset: ReleaseAsset,
        val file: File,
        val packageName: String,
        val versionCode: Long,
    )

    private suspend fun instalar(driver: AndroidTvAdbDriver, c: Candidato): DeployReport.Entry {
        val instalado = driver.installedVersionCode(c.packageName)
        if (instalado != null && instalado >= c.versionCode) {
            return DeployReport.Entry(
                c.asset.assetName, c.packageName, DeployReport.Outcome.AL_DIA,
                "Ya está la ${c.versionCode} instalada (o una más nueva).",
            )
        }

        DiagLog.i("deploy", "instalando ${c.packageName} v${c.versionCode} desde ${c.asset.releaseTag}")
        val primerIntento = driver.installApk(c.file) { }
        if (primerIntento.isSuccess) {
            return DeployReport.Entry(
                c.asset.assetName, c.packageName, DeployReport.Outcome.INSTALADO,
                "Instalada la versión ${c.versionCode} (${c.asset.releaseTag}).",
            )
        }

        val motivo = primerIntento.exceptionOrNull()?.message.orEmpty()
        val conflictoDeFirmaOPermisos = motivo.contains("UPDATE_INCOMPATIBLE", true) ||
            motivo.contains("DUPLICATE_PERMISSION", true)

        if (!conflictoDeFirmaOPermisos) {
            return DeployReport.Entry(
                c.asset.assetName, c.packageName, DeployReport.Outcome.ERROR,
                motivo.ifBlank { "pm install falló sin mensaje" },
            )
        }

        // El paquete de fabrica choca con este build. Se deshabilita para el
        // usuario (`-k --user 0` no borra el APK del sistema, es reversible con
        // `cmd package install-existing`) y se reintenta UNA vez.
        DiagLog.w("deploy", "conflicto con el paquete de fábrica, se deshabilita y se reintenta una vez")
        val quitado = driver.uninstallForUser(c.packageName)
        if (quitado.isFailure) {
            return DeployReport.Entry(
                c.asset.assetName, c.packageName, DeployReport.Outcome.ERROR,
                "Choca con el paquete de fábrica y no se pudo deshabilitar: " +
                    (quitado.exceptionOrNull()?.message ?: "sin detalle") +
                    " · error original: $motivo",
            )
        }

        val segundoIntento = driver.installApk(c.file) { }
        return if (segundoIntento.isSuccess) {
            DeployReport.Entry(
                c.asset.assetName, c.packageName, DeployReport.Outcome.INSTALADO,
                "Instalada la versión ${c.versionCode} tras deshabilitar el paquete de fábrica. " +
                    "Para revertir: cmd package install-existing ${c.packageName}",
            )
        } else {
            DeployReport.Entry(
                c.asset.assetName, c.packageName, DeployReport.Outcome.ERROR,
                "Falló también después de deshabilitar el original. No se reintenta más. " +
                    "Error: " + (segundoIntento.exceptionOrNull()?.message ?: "sin detalle"),
            )
        }
    }

    /** Lee package y versionCode del APK **sin instalarlo**. */
    private fun archiveInfo(file: File): PackageInfo? = runCatching {
        context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
    }.getOrNull()

    private fun versionCodeDe(info: PackageInfo): Long =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private fun motivoSinAdb(device: TvDevice, detalle: String?): String = buildString {
        append("No se pudo abrir ADB con ${device.name} (${device.host}:${device.port}). ")
        append("Para que el despliegue automático funcione hacen falta dos cosas: ")
        append("que el celular esté en la misma Wi-Fi que el aparato, y que el aparato ")
        append("tenga encendida la «Depuración por red». ")
        append("Ojo: en muchos Android TV esa opción se apaga sola al reiniciar el aparato. ")
        detalle?.let { append("Detalle: $it") }
    }
}

/** Estado de la conexion que el motor necesita, sin exponer el driver entero. */
internal fun ConnectionState.esConectado() = this is ConnectionState.Connected
