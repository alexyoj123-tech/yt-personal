package io.github.alexyoj123.vallethremote.driver.samsung

import android.util.Base64
import io.github.alexyoj123.vallethremote.core.BaseTvDriver
import io.github.alexyoj123.vallethremote.core.Capability
import io.github.alexyoj123.vallethremote.core.ConnectionState
import io.github.alexyoj123.vallethremote.core.DiagLog
import io.github.alexyoj123.vallethremote.core.DriverKind
import io.github.alexyoj123.vallethremote.core.Net
import io.github.alexyoj123.vallethremote.core.RemoteKey
import io.github.alexyoj123.vallethremote.core.TvApp
import io.github.alexyoj123.vallethremote.core.TvDevice
import io.github.alexyoj123.vallethremote.data.DeviceStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Driver de TVs Samsung con Tizen (modelos 2016 en adelante).
 *
 * Canal de control:
 *   wss://<ip>:8002/api/v2/channels/samsung.remote.control?name=<b64>&token=<token>
 *
 * El certificado de la TV es autofirmado, asi que se usa un TrustManager
 * permisivo — pero SOLO en el OkHttpClient de este driver, nunca global.
 */
class SamsungTizenDriver(
    override val device: TvDevice,
    private val store: DeviceStore,
) : BaseTvDriver() {

    override val kind = DriverKind.SAMSUNG_TIZEN

    /**
     * POINTER y TEXT se declaran, pero Samsung los restringio en varios
     * modelos de 2021 en adelante. La pantalla de prueba de capacidades es la
     * que dice la verdad sobre ESTE modelo; si fallan, la app cae a Bluetooth
     * HID y lo dice en pantalla.
     */
    override val capabilities = setOf(
        Capability.KEYS,
        Capability.TEXT,
        Capability.POINTER,
        Capability.APP_LAUNCH,
        Capability.DEEPLINK,
        Capability.WAKE_ON_LAN,
    )

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val client: OkHttpClient by lazy { buildPermissiveClient() }
    private var socket: WebSocket? = null

    /** Se completa cuando llega `ms.channel.connect`. */
    private var handshake: CompletableDeferred<Result<Unit>>? = null

    /** Respuestas de `ed.installedApp.get`. */
    private var appsPending: CompletableDeferred<List<TvApp>>? = null

    private var lastError: String? = null

    // ------------------------------------------------------------ conexion

    override suspend fun connect(): Result<Unit> {
        val saved = store.secret(device.id)
        val first = openChannel(saved)
        if (first.isSuccess) return first

        // Un token vencido hace que la TV cierre el socket sin explicar nada.
        // Se borra y se reintenta el emparejamiento UNA sola vez.
        if (saved != null) {
            DiagLog.w("samsung", "el token guardado no sirvio, se reintenta el emparejamiento")
            store.clearSecret(device.id)
            return openChannel(null)
        }
        return first
    }

    private suspend fun openChannel(token: String?): Result<Unit> = withContext(Dispatchers.IO) {
        disconnect()
        _connectionState.value = ConnectionState.Connecting

        val nameB64 = Base64.encodeToString("VallEthRemote".toByteArray(), Base64.NO_WRAP)
        val url = buildString {
            append("wss://${device.host}:8002/api/v2/channels/samsung.remote.control")
            append("?name=$nameB64")
            if (!token.isNullOrBlank()) append("&token=$token")
        }

        val deferred = CompletableDeferred<Result<Unit>>()
        handshake = deferred
        lastError = null

        if (token.isNullOrBlank()) {
            _connectionState.value = ConnectionState.NeedsPairing(
                title = "Aceptá el permiso en la TV",
                steps = listOf(
                    "En la pantalla de la TV va a aparecer un aviso pidiendo permiso para «VallEthRemote».",
                    "Elegí «Permitir» con el control original.",
                    "El permiso se pide una sola vez: después queda guardado.",
                ),
            )
        }

        socket = client.newWebSocket(Request.Builder().url(url).build(), listener)

        val result = withTimeoutOrNull(35_000) { deferred.await() }
            ?: Result.failure(
                IllegalStateException(
                    lastError ?: "La TV no respondió al emparejamiento. ¿Aceptaste el aviso en pantalla?",
                ),
            )

        if (result.isSuccess) {
            _connectionState.value = ConnectionState.Connected
        } else {
            _connectionState.value = ConnectionState.Failed(
                result.exceptionOrNull()?.message ?: "No se pudo conectar",
            )
        }
        result
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            DiagLog.i("samsung", "websocket abierto con ${device.host}")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (json.optString("event")) {
                "ms.channel.connect" -> {
                    val newToken = json.optJSONObject("data")?.optString("token")
                    if (!newToken.isNullOrBlank() && newToken != "null") {
                        // Se guarda sin registrarlo jamas en el log.
                        pendingToken = newToken
                    }
                    DiagLog.i("samsung", "canal aceptado por la TV")
                    handshake?.complete(Result.success(Unit))
                }

                "ms.channel.unauthorized" -> {
                    lastError = "La TV rechazó el permiso. Volvé a intentar y elegí «Permitir»."
                    handshake?.complete(Result.failure(IllegalStateException(lastError)))
                }

                "ms.channel.timeOut" -> {
                    lastError = "Se agotó el tiempo del aviso en la TV."
                    handshake?.complete(Result.failure(IllegalStateException(lastError)))
                }

                "ed.installedApp.get" -> {
                    val list = parseApps(json)
                    DiagLog.i("samsung", "la TV reportó ${list.size} apps instaladas")
                    appsPending?.complete(list)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            lastError = t.message ?: "fallo de red"
            DiagLog.e("samsung", "websocket falló", t)
            handshake?.complete(Result.failure(t))
            appsPending?.complete(emptyList())
            _connectionState.value = ConnectionState.Failed(lastError ?: "fallo de red")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            DiagLog.w("samsung", "websocket cerrado ($code ${reason.ifBlank { "sin motivo" }})")
            handshake?.complete(
                Result.failure(IllegalStateException("La TV cerró la conexión sin aceptar el permiso")),
            )
            if (_connectionState.value is ConnectionState.Connected) {
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    /** Se persiste fuera del listener para no bloquear el hilo del socket. */
    private var pendingToken: String? = null

    /** Llamar despues de un connect() exitoso para persistir el token nuevo. */
    suspend fun persistTokenIfAny() {
        pendingToken?.let {
            store.putSecret(device.id, it)
            pendingToken = null
        }
    }

    override fun disconnect() {
        runCatching { socket?.close(1000, "bye") }
        socket = null
        if (_connectionState.value is ConnectionState.Connected) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    // ------------------------------------------------------------- comandos

    private fun send(payload: JSONObject): Result<Unit> {
        val ws = socket ?: return Result.failure(IllegalStateException("Sin conexión con la TV"))
        return if (ws.send(payload.toString())) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("La cola del WebSocket rechazó el comando"))
        }
    }

    override suspend fun sendKey(key: RemoteKey): Result<Unit> {
        val code = KEY_MAP[key] ?: return unsupported("La TV Samsung no tiene la tecla $key")
        val payload = JSONObject().apply {
            put("method", "ms.remote.control")
            put(
                "params",
                JSONObject().apply {
                    put("Cmd", "Click")
                    put("DataOfCmd", code)
                    put("Option", "false")
                    put("TypeOfRemote", "SendRemoteKey")
                },
            )
        }
        val start = System.nanoTime()
        val result = send(payload)
        DiagLog.d("samsung", "tecla $key -> $code", (System.nanoTime() - start) / 1_000_000)
        return result
    }

    override suspend fun sendText(text: String): Result<Unit> {
        val payload = JSONObject().apply {
            put("method", "ms.remote.control")
            put(
                "params",
                JSONObject().apply {
                    put("Cmd", Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP))
                    put("DataOfCmd", "base64")
                    put("TypeOfRemote", "SendInputString")
                },
            )
        }
        DiagLog.d("samsung", "SendInputString (${text.length} caracteres)")
        return send(payload)
    }

    /**
     * Puntero nativo de Tizen.
     *
     * OJO: el prompt de construccion documentaba `Cmd` con el objeto de
     * coordenadas adentro. La implementacion de referencia mas usada
     * (samsungtvws) manda `Cmd:"Move"` + `Position`. Se eligio esta segunda
     * por tener mas evidencia detras. Si en la TV del dueno no responde, la
     * pantalla de prueba de capacidades lo va a marcar y la app cae a
     * Bluetooth HID, que es el camino que si funciona siempre.
     */
    override suspend fun pointerMove(dx: Float, dy: Float) {
        val payload = JSONObject().apply {
            put("method", "ms.remote.control")
            put(
                "params",
                JSONObject().apply {
                    put("Cmd", "Move")
                    put(
                        "Position",
                        JSONObject().apply {
                            put("x", dx.toInt())
                            put("y", dy.toInt())
                            put("Time", System.currentTimeMillis().toString())
                        },
                    )
                    put("TypeOfRemote", "ProcessMouseDevice")
                },
            )
        }
        send(payload)
    }

    override suspend fun pointerClick() {
        val payload = JSONObject().apply {
            put("method", "ms.remote.control")
            put(
                "params",
                JSONObject().apply {
                    put("Cmd", "LeftClick")
                    put("TypeOfRemote", "ProcessMouseDevice")
                },
            )
        }
        send(payload)
    }

    override suspend fun listApps(): Result<List<TvApp>> {
        val cached = store.cachedApps(device.id)?.let { decodeApps(it) }
        val deferred = CompletableDeferred<List<TvApp>>()
        appsPending = deferred

        val request = JSONObject().apply {
            put("method", "ms.channel.emit")
            put(
                "params",
                JSONObject().apply {
                    put("event", "ed.installedApp.get")
                    put("to", "host")
                },
            )
        }
        val sent = send(request)
        if (sent.isFailure) {
            return if (cached != null) Result.success(cached) else Result.failure(sent.exceptionOrNull()!!)
        }

        val live = withTimeoutOrNull(4_000) { deferred.await() }
        appsPending = null

        return when {
            !live.isNullOrEmpty() -> {
                store.putCachedApps(device.id, encodeApps(live))
                Result.success(live)
            }

            cached != null -> {
                DiagLog.w("samsung", "la TV no listó apps; se usa la cache (${cached.size})")
                Result.success(cached)
            }

            else -> {
                DiagLog.w("samsung", "la TV no listó apps; se usan los IDs conocidos")
                Result.success(FALLBACK_APPS)
            }
        }
    }

    override suspend fun launchApp(app: TvApp, deepLink: String?): Result<Unit> {
        val payload = JSONObject().apply {
            put("method", "ms.channel.emit")
            put(
                "params",
                JSONObject().apply {
                    put("event", "ed.apps.launch")
                    put("to", "host")
                    put(
                        "data",
                        JSONObject().apply {
                            put("appId", app.id)
                            put("action_type", if (deepLink != null) "DEEP_LINK" else "NATIVE_LAUNCH")
                            deepLink?.let { put("metaTag", it) }
                        },
                    )
                },
            )
        }
        DiagLog.i("samsung", "abriendo ${app.name} (${app.id})")
        return send(payload)
    }

    /**
     * Samsung no expone busqueda por WebSocket: no hay un equivalente a los
     * intents de Android TV. Lo unico honesto es abrir la app y escribir.
     *
     * Se intenta con SendInputString; si el modelo lo bloquea (Samsung lo capo
     * en varios modelos de 2021 en adelante) el fallo sube y quien escribe es
     * el teclado Bluetooth HID, que lo decide [RemoteRepository].
     */
    override suspend fun search(query: String, app: TvApp?): Result<Unit> {
        app?.let {
            launchApp(it).onFailure { e -> return Result.failure(e) }
            // La app necesita terminar de abrir antes de aceptar texto.
            delay(3_000)
        }
        DiagLog.i("samsung", "escribiendo la búsqueda (${query.length} caracteres) en el campo activo")
        return sendText(query)
    }

    override suspend fun wake(): Result<Unit> {
        val mac = device.macAddress
            ?: return Result.failure(
                IllegalStateException("No conozco la MAC de esta TV todavía; encendela una vez y volvé a buscar dispositivos"),
            )
        Net.wakeOnLan(mac, device.host).onFailure { return Result.failure(it) }

        // La TV apagada no acepta WebSocket: se reintenta con backoff ~10 s.
        var wait = 700L
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            delay(wait)
            if (Net.probe(device.host, 8001, 700)) {
                DiagLog.i("samsung", "la TV respondió tras el Wake-on-LAN")
                return connect()
            }
            wait = (wait * 1.4).toLong().coerceAtMost(2_500)
        }
        return Result.failure(IllegalStateException("La TV no respondió 10 s después del Wake-on-LAN"))
    }

    // ------------------------------------------------------------ internos

    private fun parseApps(json: JSONObject): List<TvApp> {
        val data = json.optJSONObject("data")?.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (i in 0 until data.length()) {
                val o = data.optJSONObject(i) ?: continue
                val id = o.optString("appId").takeIf { it.isNotBlank() } ?: continue
                add(TvApp(id = id, name = o.optString("name").ifBlank { id }))
            }
        }
    }

    private fun encodeApps(list: List<TvApp>): String {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) }
        return arr.toString()
    }

    private fun decodeApps(raw: String): List<TvApp>? = runCatching {
        val arr = JSONArray(raw)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            TvApp(o.getString("id"), o.getString("name"))
        }
    }.getOrNull()

    private fun buildPermissiveClient(): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<javax.net.ssl.TrustManager>(trustAll), SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    companion object {
        /** IDs conocidos; solo se usan si `ed.installedApp.get` no responde. */
        val FALLBACK_APPS = listOf(
            TvApp("111299001912", "YouTube"),
            TvApp("3201907018807", "Netflix"),
            TvApp("3201512006785", "Prime Video"),
            TvApp("3201901017640", "Disney+"),
            TvApp("3201606009684", "Spotify"),
        )

        val KEY_MAP: Map<RemoteKey, String> = mapOf(
            RemoteKey.POWER to "KEY_POWER",
            RemoteKey.HOME to "KEY_HOME",
            RemoteKey.MENU to "KEY_MENU",
            RemoteKey.BACK to "KEY_RETURN",
            RemoteKey.UP to "KEY_UP",
            RemoteKey.DOWN to "KEY_DOWN",
            RemoteKey.LEFT to "KEY_LEFT",
            RemoteKey.RIGHT to "KEY_RIGHT",
            RemoteKey.OK to "KEY_ENTER",
            RemoteKey.VOLUME_UP to "KEY_VOLUP",
            RemoteKey.VOLUME_DOWN to "KEY_VOLDOWN",
            RemoteKey.MUTE to "KEY_MUTE",
            RemoteKey.CHANNEL_UP to "KEY_CHUP",
            RemoteKey.CHANNEL_DOWN to "KEY_CHDOWN",
            RemoteKey.SOURCE to "KEY_SOURCE",
            RemoteKey.TOOLS to "KEY_TOOLS",
            RemoteKey.INFO to "KEY_INFO",
            RemoteKey.GUIDE to "KEY_GUIDE",
            // SEARCH no se mapea: Tizen no tiene una tecla de busqueda comun a
            // todos los modelos, y mapearla a KEY_HOME haria que "buscar"
            // saque al usuario de la app. Mejor devolver "no soportado".
            RemoteKey.PLAY to "KEY_PLAY",
            RemoteKey.PAUSE to "KEY_PAUSE",
            RemoteKey.PLAY_PAUSE to "KEY_PLAY",
            RemoteKey.STOP to "KEY_STOP",
            RemoteKey.REWIND to "KEY_REWIND",
            RemoteKey.FAST_FORWARD to "KEY_FF",
            RemoteKey.DIGIT_0 to "KEY_0",
            RemoteKey.DIGIT_1 to "KEY_1",
            RemoteKey.DIGIT_2 to "KEY_2",
            RemoteKey.DIGIT_3 to "KEY_3",
            RemoteKey.DIGIT_4 to "KEY_4",
            RemoteKey.DIGIT_5 to "KEY_5",
            RemoteKey.DIGIT_6 to "KEY_6",
            RemoteKey.DIGIT_7 to "KEY_7",
            RemoteKey.DIGIT_8 to "KEY_8",
            RemoteKey.DIGIT_9 to "KEY_9",
        )
    }
}
