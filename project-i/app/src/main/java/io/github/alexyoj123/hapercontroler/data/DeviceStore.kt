package io.github.alexyoj123.hapercontroler.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.alexyoj123.hapercontroler.core.DiagLog
import io.github.alexyoj123.hapercontroler.core.DriverKind
import io.github.alexyoj123.hapercontroler.core.TvDevice
import io.github.alexyoj123.hapercontroler.deploy.DeployReport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "valleth_remote")

/**
 * Cache de dispositivos + secretos de emparejamiento.
 *
 * Los tokens viven aqui y NUNCA se escriben al log de diagnostico. Tampoco
 * salen del dispositivo: el backup de Android esta desactivado a proposito
 * (ver res/xml/backup_rules.xml).
 */
class DeviceStore(private val context: Context) {

    private object Keys {
        val DEVICES = stringPreferencesKey("devices")
        val LAST_DEVICE = stringPreferencesKey("last_device")
        fun secret(deviceId: String) = stringPreferencesKey("secret_$deviceId")
        fun apps(deviceId: String) = stringPreferencesKey("apps_$deviceId")
        val FAVORITE_APKS = stringPreferencesKey("favorite_apks")
        val DEPLOY_CONFIG = stringPreferencesKey("deploy_config")
        val DEPLOY_REPORT = stringPreferencesKey("deploy_report")
    }

    val devices = context.dataStore.data.map { prefs ->
        decodeDevices(prefs[Keys.DEVICES])
    }

    val lastDeviceId = context.dataStore.data.map { it[Keys.LAST_DEVICE] }

    suspend fun saveDevices(list: List<TvDevice>) {
        val json = JSONArray()
        for (d in list) {
            json.put(
                JSONObject().apply {
                    put("id", d.id)
                    put("name", d.name)
                    put("host", d.host)
                    put("port", d.port)
                    put("kind", d.kind.name)
                    d.macAddress?.let { put("mac", it) }
                    d.model?.let { put("model", it) }
                },
            )
        }
        context.dataStore.edit { it[Keys.DEVICES] = json.toString() }
    }

    /** Fusiona lo recien descubierto con lo ya conocido, sin perder MACs. */
    suspend fun mergeDiscovered(found: List<TvDevice>): List<TvDevice> {
        val known = devices.first().associateBy { it.id }.toMutableMap()
        for (d in found) {
            val prev = known[d.id]
            known[d.id] = d.copy(
                macAddress = d.macAddress ?: prev?.macAddress,
                model = d.model ?: prev?.model,
            )
        }
        val merged = known.values.toList()
        saveDevices(merged)
        return merged
    }

    suspend fun rememberLast(deviceId: String) {
        context.dataStore.edit { it[Keys.LAST_DEVICE] = deviceId }
    }

    suspend fun forget(deviceId: String) {
        val remaining = devices.first().filterNot { it.id == deviceId }
        saveDevices(remaining)
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.secret(deviceId))
            prefs.remove(Keys.apps(deviceId))
            if (prefs[Keys.LAST_DEVICE] == deviceId) prefs.remove(Keys.LAST_DEVICE)
        }
        DiagLog.i("store", "dispositivo olvidado (id oculto por privacidad)")
    }

    // -------------------------------------------------------- secretos

    suspend fun secret(deviceId: String): String? =
        context.dataStore.data.first()[Keys.secret(deviceId)]

    suspend fun putSecret(deviceId: String, value: String) {
        context.dataStore.edit { it[Keys.secret(deviceId)] = value }
        DiagLog.i("store", "secreto de emparejamiento guardado (valor no registrado)")
    }

    suspend fun clearSecret(deviceId: String) {
        context.dataStore.edit { it.remove(Keys.secret(deviceId)) }
        DiagLog.w("store", "secreto de emparejamiento borrado, se reintentara el pareo")
    }

    // ------------------------------------------------------ apps cache

    suspend fun cachedApps(deviceId: String): String? =
        context.dataStore.data.first()[Keys.apps(deviceId)]

    suspend fun putCachedApps(deviceId: String, json: String) {
        context.dataStore.edit { it[Keys.apps(deviceId)] = json }
    }

    // -------------------------------------------------- APKs favoritos

    suspend fun favoriteApks(): List<String> =
        context.dataStore.data.first()[Keys.FAVORITE_APKS]
            ?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?.let { arr -> List(arr.length()) { arr.getString(it) } }
            ?: emptyList()

    suspend fun toggleFavoriteApk(uri: String) {
        val current = favoriteApks().toMutableList()
        if (!current.remove(uri)) current.add(uri)
        val arr = JSONArray()
        current.forEach { arr.put(it) }
        context.dataStore.edit { it[Keys.FAVORITE_APKS] = arr.toString() }
    }

    // ------------------------------------------------------- despliegue

    suspend fun deployConfigRaw(): String? =
        context.dataStore.data.first()[Keys.DEPLOY_CONFIG]

    val deployConfigFlow = context.dataStore.data.map { it[Keys.DEPLOY_CONFIG] }

    suspend fun putDeployConfig(json: String) {
        context.dataStore.edit { it[Keys.DEPLOY_CONFIG] = json }
    }

    val deployReportFlow = context.dataStore.data.map { it[Keys.DEPLOY_REPORT] }

    /** Se guarda como texto plano legible: es lo que se lee en la pantalla. */
    suspend fun putDeployReport(report: DeployReport) {
        val json = JSONObject().apply {
            put("checkedAtMs", report.checkedAtMs)
            put("resumen", report.resumen())
            put(
                "entries",
                JSONArray().apply {
                    report.entries.forEach { e ->
                        put(
                            JSONObject().apply {
                                put("label", e.label)
                                e.packageName?.let { put("packageName", it) }
                                put("outcome", e.outcome.name)
                                put("detail", e.detail)
                            },
                        )
                    }
                },
            )
        }
        context.dataStore.edit { it[Keys.DEPLOY_REPORT] = json.toString() }
    }

    private fun decodeDevices(raw: String?): List<TvDevice> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                TvDevice(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    host = o.getString("host"),
                    port = o.getInt("port"),
                    kind = DriverKind.valueOf(o.getString("kind")),
                    macAddress = o.optString("mac").takeIf { it.isNotBlank() },
                    model = o.optString("model").takeIf { it.isNotBlank() },
                )
            }
        }.getOrElse {
            DiagLog.e("store", "cache de dispositivos corrupta, se descarta", it)
            emptyList()
        }
    }
}
