package io.github.alexyoj123.vallethremote.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.alexyoj123.vallethremote.core.Capability
import io.github.alexyoj123.vallethremote.core.CapabilityProbe
import io.github.alexyoj123.vallethremote.core.DiagLog
import io.github.alexyoj123.vallethremote.core.RemoteKey
import io.github.alexyoj123.vallethremote.core.TvApp
import io.github.alexyoj123.vallethremote.core.TvDevice
import io.github.alexyoj123.vallethremote.data.DeviceStore
import io.github.alexyoj123.vallethremote.data.PointerMode
import io.github.alexyoj123.vallethremote.data.RemoteRepository
import io.github.alexyoj123.vallethremote.hid.BluetoothHidController
import io.github.alexyoj123.vallethremote.hid.HidForegroundService
import io.github.alexyoj123.vallethremote.voice.VoiceController
import io.github.alexyoj123.vallethremote.voice.VoiceIntent
import io.github.alexyoj123.vallethremote.voice.VoiceIntentParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Estado del envio de un APK a la TV. */
sealed interface ApkTransfer {
    data object Idle : ApkTransfer
    data class Working(val name: String, val progress: Float) : ApkTransfer
    data class Done(val name: String) : ApkTransfer
    data class Failed(val name: String, val reason: String) : ApkTransfer
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = DeviceStore(app)
    val hid = BluetoothHidController(app, viewModelScope)
    val repo = RemoteRepository(app, viewModelScope, store, hid)
    val voice = VoiceController(app)

    val devices = repo.devices
    val connection = repo.connection
    val apps = repo.apps
    val scanning = repo.scanning
    val notice = repo.notice
    val hidState = hid.state
    val pairedBluetooth = hid.pairedCandidates

    private val _probes = MutableStateFlow<List<CapabilityProbe>>(emptyList())
    val probes: StateFlow<List<CapabilityProbe>> = _probes.asStateFlow()

    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing.asStateFlow()

    private val _voiceStatus = MutableStateFlow<String?>(null)
    val voiceStatus: StateFlow<String?> = _voiceStatus.asStateFlow()

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _apkTransfer = MutableStateFlow<ApkTransfer>(ApkTransfer.Idle)
    val apkTransfer: StateFlow<ApkTransfer> = _apkTransfer.asStateFlow()

    private val _pendingApk = MutableStateFlow<Uri?>(null)
    val pendingApk: StateFlow<Uri?> = _pendingApk.asStateFlow()

    init {
        DiagLog.init(app)
        // Reconectar ANTES de que el dueno toque nada: el remoto tiene que
        // estar vivo cuando aparece la pantalla, no tres segundos despues.
        viewModelScope.launch { repo.bootstrap() }
    }

    val capabilities: Set<Capability> get() = repo.capabilities

    fun pointerMode(): PointerMode = repo.pointerMode()

    // ------------------------------------------------------- dispositivos

    fun scan() = viewModelScope.launch { repo.refreshDevices() }

    fun select(device: TvDevice) = viewModelScope.launch { repo.select(device) }

    fun forget(device: TvDevice) = viewModelScope.launch {
        store.forget(device.id)
        repo.refreshDevices()
    }

    fun wake() = viewModelScope.launch {
        val driver = repo.activeDriver.value ?: return@launch
        driver.wake().onFailure { repo.postNotice(it.message ?: "No se pudo encender") }
    }

    // ------------------------------------------------------------- teclas

    fun key(key: RemoteKey) = viewModelScope.launch {
        repo.key(key).onFailure { repo.postNotice(it.message ?: "La tecla no llegó") }
    }

    fun launch(app: TvApp) = viewModelScope.launch {
        repo.launch(app).onFailure { repo.postNotice(it.message ?: "No se pudo abrir la app") }
    }

    fun refreshApps() = viewModelScope.launch { repo.refreshApps() }

    fun sendText(text: String) = viewModelScope.launch {
        repo.text(text).onFailure { repo.postNotice(it.message ?: "No se pudo escribir") }
    }

    // ----------------------------------------------------------- trackpad

    fun startHid() {
        hid.start()
        HidForegroundService.start(getApplication())
    }

    fun stopHid() {
        hid.stop()
        HidForegroundService.stop(getApplication())
    }

    fun connectHid(address: String) {
        if (!hid.connectTo(address)) {
            repo.postNotice("No se pudo conectar el periférico Bluetooth. Emparejá el celular desde la TV primero.")
        }
    }

    fun refreshPairedBluetooth() = hid.refreshPaired()

    fun move(dx: Float, dy: Float) = viewModelScope.launch { repo.pointerMove(dx, dy) }

    fun scroll(amount: Float) = viewModelScope.launch { repo.pointerScroll(amount) }

    fun click(right: Boolean = false) = viewModelScope.launch { repo.pointerClick(right) }

    // ---------------------------------------------------------------- voz

    fun startListening() {
        if (_listening.value) return
        _listening.value = true
        _voiceStatus.value = "Escuchando…"
        voice.listenOnce(
            onResult = { text ->
                _listening.value = false
                handleVoice(text)
            },
            onError = { message ->
                _listening.value = false
                _voiceStatus.value = message
            },
        )
    }

    private fun handleVoice(text: String) = viewModelScope.launch {
        when (val intent = VoiceIntentParser.parse(text)) {
            is VoiceIntent.Key -> {
                _voiceStatus.value = "→ ${intent.key.name.lowercase().replace('_', ' ')}"
                DiagLog.i("voz", "intención: tecla ${intent.key}")
                repo.key(intent.key)
            }

            is VoiceIntent.OpenApp -> {
                val app = repo.resolveApp(intent.appAlias)
                if (app == null) {
                    _voiceStatus.value = "No encontré «${intent.appAlias}» instalada en este dispositivo."
                    DiagLog.w("voz", "app no resuelta: ${intent.appAlias}")
                } else {
                    _voiceStatus.value = "Abriendo ${app.name}…"
                    DiagLog.i("voz", "intención: abrir ${intent.appAlias}")
                    repo.launch(app).onFailure { _voiceStatus.value = it.message }
                }
            }

            is VoiceIntent.SearchInApp -> {
                val app = intent.appAlias?.let { repo.resolveApp(it) }
                _voiceStatus.value = if (app != null) "Buscando en ${app.name}…" else "Buscando…"
                DiagLog.i("voz", "intención: buscar (${intent.query.length} caracteres) en ${intent.appAlias ?: "búsqueda global"}")
                repo.search(intent.query, app).onFailure {
                    _voiceStatus.value = it.message ?: "No se pudo buscar"
                }
            }

            is VoiceIntent.Unknown -> {
                _voiceStatus.value = "No entendí la orden."
                DiagLog.w("voz", "intención desconocida")
            }
        }
    }

    fun clearVoiceStatus() {
        _voiceStatus.value = null
    }

    // ---------------------------------------------------------------- apk

    fun offerApk(uri: Uri?) {
        _pendingApk.value = uri
    }

    fun sendApk(uri: Uri) = viewModelScope.launch {
        val context = getApplication<Application>()
        val name = displayName(context, uri)
        _apkTransfer.value = ApkTransfer.Working(name, 0f)

        val copied = withContext(Dispatchers.IO) {
            runCatching {
                val cache = File(context.cacheDir, "apk").apply { mkdirs() }
                val target = File(cache, name.ifBlank { "app.apk" })
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "No se pudo leer el archivo seleccionado" }
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target
            }
        }

        val file = copied.getOrElse {
            _apkTransfer.value = ApkTransfer.Failed(name, it.message ?: "No se pudo leer el APK")
            return@launch
        }

        val result = repo.installApk(file) { progress ->
            _apkTransfer.value = ApkTransfer.Working(name, progress)
        }

        _apkTransfer.value = result.fold(
            onSuccess = { ApkTransfer.Done(name) },
            onFailure = { ApkTransfer.Failed(name, it.message ?: "pm install falló sin mensaje") },
        )
        runCatching { file.delete() }
        _pendingApk.value = null
    }

    fun clearApkTransfer() {
        _apkTransfer.value = ApkTransfer.Idle
    }

    private fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: uri.lastPathSegment
    }.getOrNull() ?: "app.apk"

    // -------------------------------------------------------- diagnostico

    fun runProbe() = viewModelScope.launch {
        _probing.value = true
        try {
            _probes.value = repo.runCapabilityProbe()
        } finally {
            _probing.value = false
        }
    }

    fun exportLog(): File? {
        val driver = repo.activeDriver.value
        val header = buildString {
            appendLine("VallEthRemote — diagnóstico")
            appendLine("dispositivo activo: ${driver?.device?.displayName ?: "ninguno"}")
            appendLine("driver: ${driver?.kind?.label ?: "-"}")
            appendLine("host: ${driver?.device?.host ?: "-"}:${driver?.device?.port ?: "-"}")
            appendLine("capacidades: ${driver?.capabilities?.joinToString() ?: "-"}")
            appendLine("modo de puntero: ${repo.pointerMode()}")
            appendLine("bluetooth HID: ${hid.state.value}")
        }
        return DiagLog.exportTo(getApplication(), header)
    }

    fun clearNotice() = repo.clearNotice()

    override fun onCleared() {
        super.onCleared()
        repo.disconnect()
        hid.stop()
    }
}
