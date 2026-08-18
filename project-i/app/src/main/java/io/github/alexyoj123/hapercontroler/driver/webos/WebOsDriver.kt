package io.github.alexyoj123.hapercontroler.driver.webos

import io.github.alexyoj123.hapercontroler.core.BaseTvDriver
import io.github.alexyoj123.hapercontroler.core.Capability
import io.github.alexyoj123.hapercontroler.core.ConnectionState
import io.github.alexyoj123.hapercontroler.core.DiagLog
import io.github.alexyoj123.hapercontroler.core.DriverKind
import io.github.alexyoj123.hapercontroler.core.Net
import io.github.alexyoj123.hapercontroler.core.RemoteKey
import io.github.alexyoj123.hapercontroler.core.TvApp
import io.github.alexyoj123.hapercontroler.core.TvDevice
import io.github.alexyoj123.hapercontroler.data.DeviceStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * LG webOS por SSAP (WebSocket 3000, o 3001 con TLS).
 *
 * El primer intento hace que la TV muestre un dialogo de permiso; al aceptar
 * devuelve un `client-key` que se guarda y evita volver a preguntar.
 *
 * El cursor es nativo de verdad: se pide un socket aparte con
 * `getPointerInputSocket` y por ahi van los movimientos, los clics y las
 * teclas del D-pad.
 */
class WebOsDriver(
    override val device: TvDevice,
    private val store: DeviceStore,
) : BaseTvDriver() {

    override val kind = DriverKind.WEBOS

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

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private var socket: WebSocket? = null
    private var pointerSocket: WebSocket? = null
    private val counter = AtomicInteger(1)

    /** Respuestas pendientes por id de mensaje. */
    private val pending = mutableMapOf<String, CompletableDeferred<JSONObject>>()
    private var registration: CompletableDeferred<Result<Unit>>? = null

    // ------------------------------------------------------------ conexion

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        disconnect()
        _connectionState.value = ConnectionState.Connecting

        val clientKey = store.secret(device.id)
        val deferred = CompletableDeferred<Result<Unit>>()
        registration = deferred

        val puerto = if (device.port == 3001) 3001 else 3000
        val esquema = if (puerto == 3001) "wss" else "ws"
        socket = client.newWebSocket(
            Request.Builder().url("$esquema://${device.host}:$puerto").build(),
            listener,
        )

        if (clientKey.isNullOrBlank()) {
            _connectionState.value = ConnectionState.NeedsPairing(
                title = "Aceptá el permiso en la TV",
                steps = listOf(
                    "La TV va a mostrar un aviso pidiendo permiso para «HAPER CONTROLER».",
                    "Elegí «Sí» con el control original.",
                    "Se pide una sola vez: después queda guardado.",
                ),
            )
        }

        sendRaw(
            JSONObject().apply {
                put("type", "register")
                put("id", "register_0")
                put(
                    "payload",
                    JSONObject().apply {
                        put("forcePairing", false)
                        put("pairingType", "PROMPT")
                        if (!clientKey.isNullOrBlank()) put("client-key", clientKey)
                        put(
                            "manifest",
                            JSONObject().apply {
                                put("manifestVersion", 1)
                                put("appVersion", "1.2.0")
                                put(
                                    "permissions",
                                    JSONArray(
                                        listOf(
                                            "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CONTROL_AUDIO",
                                            "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_POWER",
                                            "READ_INSTALLED_APPS", "CONTROL_INPUT_TV",
                                            "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD",
                                            "READ_CURRENT_CHANNEL", "CONTROL_DISPLAY",
                                        ),
                                    ),
                                )
                            },
                        )
                    },
                )
            },
        )

        val resultado = withTimeoutOrNull(40_000) { deferred.await() }
            ?: Result.failure(IllegalStateException("La TV no respondió al permiso. ¿Aceptaste el aviso en pantalla?"))

        if (resultado.isSuccess) {
            _connectionState.value = ConnectionState.Connected
            runCatching { openPointerSocket() }
        } else {
            _connectionState.value = ConnectionState.Failed(
                resultado.exceptionOrNull()?.message ?: "No se pudo conectar",
            )
        }
        resultado
    }

    override fun disconnect() {
        runCatching { pointerSocket?.close(1000, "bye") }
        pointerSocket = null
        runCatching { socket?.close(1000, "bye") }
        socket = null
        pending.clear()
        if (_connectionState.value is ConnectionState.Connected) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            DiagLog.i("webos", "websocket abierto con ${device.host}")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (json.optString("type")) {
                "registered" -> {
                    val key = json.optJSONObject("payload")?.optString("client-key")
                    if (!key.isNullOrBlank()) pendingClientKey = key
                    DiagLog.i("webos", "la TV aceptó el registro")
                    registration?.complete(Result.success(Unit))
                }

                "error" -> {
                    val motivo = json.optString("error").ifBlank { "la TV rechazó la petición" }
                    DiagLog.w("webos", "error de la TV: $motivo")
                    registration?.complete(Result.failure(IllegalStateException(motivo)))
                    json.optString("id").takeIf { it.isNotBlank() }?.let {
                        pending.remove(it)?.complete(json)
                    }
                }

                "response" -> {
                    json.optString("id").takeIf { it.isNotBlank() }?.let {
                        pending.remove(it)?.complete(json)
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            DiagLog.e("webos", "websocket falló", t)
            registration?.complete(Result.failure(t))
            _connectionState.value = ConnectionState.Failed(t.message ?: "fallo de red")
        }
    }

    /** Se persiste fuera del hilo del socket, igual que en el driver Samsung. */
    private var pendingClientKey: String? = null

    suspend fun persistClientKeyIfAny() {
        pendingClientKey?.let {
            store.putSecret(device.id, it)
            pendingClientKey = null
        }
    }

    // ------------------------------------------------------------ comandos

    private fun sendRaw(json: JSONObject): Boolean =
        socket?.send(json.toString()) ?: false

    private suspend fun request(uri: String, payload: JSONObject? = null): Result<JSONObject> {
        val ws = socket ?: return Result.failure(IllegalStateException("Sin conexión con la TV"))
        val id = "req_${counter.getAndIncrement()}"
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred

        val ok = ws.send(
            JSONObject().apply {
                put("type", "request")
                put("id", id)
                put("uri", uri)
                payload?.let { put("payload", it) }
            }.toString(),
        )
        if (!ok) {
            pending.remove(id)
            return Result.failure(IllegalStateException("La cola del WebSocket rechazó el comando"))
        }

        val respuesta = withTimeoutOrNull(5_000) { deferred.await() }
        pending.remove(id)
        return respuesta?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("La TV no respondió a $uri"))
    }

    override suspend fun sendKey(key: RemoteKey): Result<Unit> {
        // El volumen y el mute tienen endpoints SSAP propios; el resto va por
        // el socket de puntero, que es como manda las teclas el control real.
        when (key) {
            RemoteKey.VOLUME_UP -> return request("ssap://audio/volumeUp").map { }
            RemoteKey.VOLUME_DOWN -> return request("ssap://audio/volumeDown").map { }
            RemoteKey.MUTE -> return request(
                "ssap://audio/setMute",
                JSONObject().put("mute", true),
            ).map { }
            RemoteKey.POWER -> return request("ssap://system/turnOff").map { }
            else -> Unit
        }
        val boton = BOTONES[key] ?: return unsupported("webOS no tiene la tecla $key")
        val ptr = pointerSocket ?: return Result.failure(
            IllegalStateException("El canal de puntero de la TV no está abierto"),
        )
        val start = System.nanoTime()
        ptr.send("type:button\nname:$boton\n\n")
        DiagLog.d("webos", "tecla $key -> $boton", (System.nanoTime() - start) / 1_000_000)
        return Result.success(Unit)
    }

    override suspend fun sendText(text: String): Result<Unit> =
        request("ssap://com.webos.service.ime/insertText", JSONObject().put("text", text).put("replace", false)).map { }

    override suspend fun pointerMove(dx: Float, dy: Float) {
        pointerSocket?.send("type:move\ndx:${dx.toInt()}\ndy:${dy.toInt()}\ndown:0\n\n")
    }

    override suspend fun pointerClick() {
        pointerSocket?.send("type:click\n\n")
    }

    override suspend fun listApps(): Result<List<TvApp>> =
        request("ssap://com.webos.applicationManager/listLaunchPoints").map { json ->
            val puntos = json.optJSONObject("payload")?.optJSONArray("launchPoints")
            buildList {
                for (i in 0 until (puntos?.length() ?: 0)) {
                    val o = puntos?.optJSONObject(i) ?: continue
                    val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                    add(TvApp(id = id, name = o.optString("title").ifBlank { id }))
                }
            }
        }

    override suspend fun launchApp(app: TvApp, deepLink: String?): Result<Unit> {
        val payload = JSONObject().put("id", app.id)
        deepLink?.let { payload.put("contentId", it) }
        return request("ssap://system.launcher/launch", payload).map { }
    }

    override suspend fun search(query: String, app: TvApp?): Result<Unit> {
        app?.let {
            launchApp(it).onFailure { e -> return Result.failure(e) }
            kotlinx.coroutines.delay(2_500)
        }
        return sendText(query)
    }

    override suspend fun wake(): Result<Unit> {
        val mac = device.macAddress
            ?: return Result.failure(IllegalStateException("No conozco la MAC de esta TV todavía"))
        return Net.wakeOnLan(mac, device.host).map { }
    }

    /**
     * Cursor nativo: la TV entrega la ruta de un WebSocket aparte y por ahi
     * van `move`, `click` y `button`.
     */
    private suspend fun openPointerSocket() {
        val respuesta = request("ssap://com.webos.service.networkinput/getPointerInputSocket")
        val path = respuesta.getOrNull()?.optJSONObject("payload")?.optString("socketPath")
        if (path.isNullOrBlank()) {
            DiagLog.w("webos", "la TV no entregó el socket de puntero; el cursor nativo no estará disponible")
            return
        }
        pointerSocket = client.newWebSocket(
            Request.Builder().url(path).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    DiagLog.i("webos", "canal de puntero abierto")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    DiagLog.w("webos", "el canal de puntero falló: ${t.message}")
                    pointerSocket = null
                }
            },
        )
    }

    companion object {
        val BOTONES: Map<RemoteKey, String> = mapOf(
            RemoteKey.UP to "UP",
            RemoteKey.DOWN to "DOWN",
            RemoteKey.LEFT to "LEFT",
            RemoteKey.RIGHT to "RIGHT",
            RemoteKey.OK to "ENTER",
            RemoteKey.BACK to "BACK",
            RemoteKey.HOME to "HOME",
            RemoteKey.MENU to "MENU",
            RemoteKey.INFO to "INFO",
            RemoteKey.GUIDE to "GUIDE",
            RemoteKey.CHANNEL_UP to "CHANNELUP",
            RemoteKey.CHANNEL_DOWN to "CHANNELDOWN",
            RemoteKey.PLAY to "PLAY",
            RemoteKey.PAUSE to "PAUSE",
            RemoteKey.PLAY_PAUSE to "PLAY",
            RemoteKey.STOP to "STOP",
            RemoteKey.REWIND to "REWIND",
            RemoteKey.FAST_FORWARD to "FASTFORWARD",
            RemoteKey.DIGIT_0 to "0",
            RemoteKey.DIGIT_1 to "1",
            RemoteKey.DIGIT_2 to "2",
            RemoteKey.DIGIT_3 to "3",
            RemoteKey.DIGIT_4 to "4",
            RemoteKey.DIGIT_5 to "5",
            RemoteKey.DIGIT_6 to "6",
            RemoteKey.DIGIT_7 to "7",
            RemoteKey.DIGIT_8 to "8",
            RemoteKey.DIGIT_9 to "9",
        )
    }
}
