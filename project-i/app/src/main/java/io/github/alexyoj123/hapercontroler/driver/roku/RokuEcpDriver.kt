package io.github.alexyoj123.hapercontroler.driver.roku

import io.github.alexyoj123.hapercontroler.core.BaseTvDriver
import io.github.alexyoj123.hapercontroler.core.Capability
import io.github.alexyoj123.hapercontroler.core.ConnectionState
import io.github.alexyoj123.hapercontroler.core.DiagLog
import io.github.alexyoj123.hapercontroler.core.DriverKind
import io.github.alexyoj123.hapercontroler.core.RemoteKey
import io.github.alexyoj123.hapercontroler.core.TvApp
import io.github.alexyoj123.hapercontroler.core.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Roku por ECP (External Control Protocol) sobre HTTP en el puerto 8060.
 * Es el protocolo mas simple y mas rapido de todos los que soporta la app:
 * no hay handshake, no hay token, no hay emparejamiento.
 */
class RokuEcpDriver(
    override val device: TvDevice,
) : BaseTvDriver() {

    override val kind = DriverKind.ROKU_ECP

    override val capabilities = setOf(
        Capability.KEYS,
        Capability.TEXT,
        Capability.APP_LAUNCH,
        Capability.DEEPLINK,
    )

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val base get() = "http://${device.host}:${device.port.takeIf { it > 0 } ?: 8060}"

    override suspend fun connect(): Result<Unit> {
        _connectionState.value = ConnectionState.Connecting
        val result = get("/query/device-info")
        _connectionState.value = if (result.isSuccess) {
            ConnectionState.Connected
        } else {
            ConnectionState.Failed(result.exceptionOrNull()?.message ?: "sin respuesta")
        }
        return result.map { }
    }

    override fun disconnect() {
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun sendKey(key: RemoteKey): Result<Unit> {
        val name = KEY_MAP[key] ?: return unsupported("Roku no tiene la tecla $key")
        return post("/keypress/$name").map { }
    }

    /** Roku escribe letra por letra con `Lit_<char>` URL-encodeado. */
    override suspend fun sendText(text: String): Result<Unit> {
        for (c in text) {
            val encoded = URLEncoder.encode(c.toString(), "UTF-8")
            post("/keypress/Lit_$encoded").onFailure { return Result.failure(it) }
        }
        return Result.success(Unit)
    }

    override suspend fun listApps(): Result<List<TvApp>> = get("/query/apps").map { xml ->
        APP_REGEX.findAll(xml).map { match ->
            TvApp(id = match.groupValues[1], name = match.groupValues[2].trim())
        }.toList()
    }

    override suspend fun launchApp(app: TvApp, deepLink: String?): Result<Unit> {
        val suffix = deepLink?.let { "?contentId=" + URLEncoder.encode(it, "UTF-8") } ?: ""
        return post("/launch/${app.id}$suffix").map { }
    }

    override suspend fun search(query: String, app: TvApp?): Result<Unit> {
        val keyword = URLEncoder.encode(query, "UTF-8")
        val provider = app?.id?.let { "&provider-id=$it" } ?: ""
        return post("/search/browse?keyword=$keyword&type=movie$provider").map { }
    }

    private suspend fun get(path: String): Result<String> = request("GET", path)

    private suspend fun post(path: String): Result<String> = request("POST", path)

    private suspend fun request(method: String, path: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("$base$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 2_000
                readTimeout = 2_000
                doOutput = method == "POST"
            }
            if (method == "POST") conn.outputStream.close()
            val code = conn.responseCode
            val body = runCatching {
                conn.inputStream.bufferedReader().use { it.readText() }
            }.getOrDefault("")
            conn.disconnect()
            if (code !in 200..299) {
                DiagLog.w("roku", "$method $path -> HTTP $code")
                throw IllegalStateException("Roku respondió HTTP $code")
            }
            body
        }
    }

    companion object {
        private val APP_REGEX = Regex("""<app id="([^"]+)"[^>]*>([^<]*)</app>""")

        val KEY_MAP: Map<RemoteKey, String> = mapOf(
            RemoteKey.POWER to "Power",
            RemoteKey.HOME to "Home",
            RemoteKey.BACK to "Back",
            RemoteKey.UP to "Up",
            RemoteKey.DOWN to "Down",
            RemoteKey.LEFT to "Left",
            RemoteKey.RIGHT to "Right",
            RemoteKey.OK to "Select",
            RemoteKey.VOLUME_UP to "VolumeUp",
            RemoteKey.VOLUME_DOWN to "VolumeDown",
            RemoteKey.MUTE to "VolumeMute",
            RemoteKey.CHANNEL_UP to "ChannelUp",
            RemoteKey.CHANNEL_DOWN to "ChannelDown",
            RemoteKey.SOURCE to "InputHDMI1",
            RemoteKey.INFO to "Info",
            RemoteKey.SEARCH to "Search",
            RemoteKey.PLAY to "Play",
            RemoteKey.PAUSE to "Play",
            RemoteKey.PLAY_PAUSE to "Play",
            RemoteKey.REWIND to "Rev",
            RemoteKey.FAST_FORWARD to "Fwd",
        )
    }
}
