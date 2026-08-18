package io.github.alexyoj123.vallethremote.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.alexyoj123.vallethremote.core.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** Estado del periferico HID que expone el celular. */
sealed interface HidState {
    data object Unsupported : HidState
    data object PermissionMissing : HidState
    data object BluetoothOff : HidState
    data object Idle : HidState
    data object Registered : HidState
    data class Connected(val deviceName: String) : HidState
    data class Error(val reason: String) : HidState
}

/**
 * Registra el celular como periferico HID (raton + teclado + consumer) y le
 * manda reportes a la TV.
 *
 * Por que existe: es el unico camino que da cursor REAL en Android TV, y en
 * las Samsung donde `ProcessMouseDevice` esta capado. Ademas es el mas rapido
 * de todos (~15 ms) porque no pasa por el router.
 *
 * Emparejamiento del lado de la TV:
 *  - Android TV: Ajustes → Mandos y accesorios → Añadir accesorio.
 *  - Samsung Tizen: Ajustes → Administrador de dispositivos externos →
 *    Teclado/Mouse Bluetooth.
 */
class BluetoothHidController(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<HidState>(HidState.Idle)
    val state: StateFlow<HidState> = _state.asStateFlow()

    private val _pairedCandidates = MutableStateFlow<List<Pair<String, String>>>(emptyList())

    /** Dispositivos emparejados con el celular (nombre, MAC). */
    val pairedCandidates: StateFlow<List<Pair<String, String>>> = _pairedCandidates.asStateFlow()

    private val executor = Executors.newSingleThreadExecutor()
    private var proxy: BluetoothHidDevice? = null
    private var target: BluetoothDevice? = null
    private var registered = false

    // Coalescencia del trackpad -----------------------------------------
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var pendingWheel = 0f
    private var buttons = 0
    private var pump: Job? = null

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val connect = ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT)
        return connect == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (registered) return
        val adapter = adapter
        when {
            adapter == null -> {
                _state.value = HidState.Unsupported
                return
            }

            !hasPermissions() -> {
                _state.value = HidState.PermissionMissing
                return
            }

            !adapter.isEnabled -> {
                _state.value = HidState.BluetoothOff
                return
            }
        }

        val ok = adapter!!.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
        if (!ok) {
            _state.value = HidState.Unsupported
            DiagLog.w("hid", "este celular no expone el perfil HID_DEVICE")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        pump?.cancel()
        pump = null
        val p = proxy
        if (p != null && registered) {
            runCatching { p.unregisterApp() }
        }
        runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy) }
        proxy = null
        target = null
        registered = false
        _state.value = HidState.Idle
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, service: BluetoothProfile?) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val hid = service as? BluetoothHidDevice ?: return
            proxy = hid

            val sdp = BluetoothHidDeviceAppSdpSettings(
                "VallEthRemote",
                "Control remoto universal de TV",
                "VallEth",
                BluetoothHidDevice.SUBCLASS1_COMBO,
                HidDescriptor.BYTES,
            )
            val qos = BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                800, 9, 0,
                11_250, BluetoothHidDeviceAppQosSettings.MAX,
            )

            val ok = runCatching { hid.registerApp(sdp, null, qos, executor, callback) }.getOrDefault(false)
            if (!ok) {
                _state.value = HidState.Error("El sistema rechazó registrar el periférico HID")
                DiagLog.e("hid", "registerApp devolvió false")
            }
            refreshPaired()
        }

        override fun onServiceDisconnected(profile: Int) {
            proxy = null
            registered = false
            _state.value = HidState.Idle
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            registered = isRegistered
            DiagLog.i("hid", "periférico HID ${if (isRegistered) "registrado" else "dado de baja"}")
            if (isRegistered) {
                if (_state.value !is HidState.Connected) _state.value = HidState.Registered
                startPump()
            } else {
                _state.value = HidState.Idle
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    target = device
                    val name = runCatching { device?.name }.getOrNull() ?: "TV"
                    _state.value = HidState.Connected(name)
                    DiagLog.i("hid", "HID conectado a $name")
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (device == target) target = null
                    if (registered) _state.value = HidState.Registered
                    DiagLog.w("hid", "HID desconectado")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshPaired() {
        val bonded = runCatching { adapter?.bondedDevices.orEmpty() }.getOrDefault(emptySet())
        _pairedCandidates.value = bonded.mapNotNull { d ->
            val name = runCatching { d.name }.getOrNull() ?: return@mapNotNull null
            name to d.address
        }.sortedBy { it.first }
    }

    /** Conecta el periferico a una TV ya emparejada con el celular. */
    @SuppressLint("MissingPermission")
    fun connectTo(address: String): Boolean {
        val hid = proxy ?: return false
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return false
        target = device
        return runCatching { hid.connect(device) }.getOrDefault(false)
    }

    val isConnected: Boolean get() = _state.value is HidState.Connected

    // ------------------------------------------------------------ reportes

    /**
     * Bomba de reportes a ~66 Hz. Nunca se manda un reporte por cada
     * MotionEvent: eso inunda el canal HID y se siente peor que no tenerlo.
     */
    private fun startPump() {
        if (pump?.isActive == true) return
        pump = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(15)
                val dx: Int
                val dy: Int
                val wheel: Int
                synchronized(this@BluetoothHidController) {
                    dx = takeDelta { pendingDx }.also { pendingDx -= it }.roundToInt()
                    dy = takeDelta { pendingDy }.also { pendingDy -= it }.roundToInt()
                    wheel = takeDelta { pendingWheel }.also { pendingWheel -= it }.roundToInt()
                }
                if (dx != 0 || dy != 0 || wheel != 0) {
                    sendMouseReport(dx, dy, wheel)
                }
            }
        }
    }

    /** Recorta a +-127, el limite del reporte int8. */
    private inline fun takeDelta(value: () -> Float): Float {
        val v = value()
        if (abs(v) < 1f) return 0f
        return v.coerceIn(-127f, 127f).let { if (abs(it) < 1f) sign(it) else it }
    }

    /** Acumula el movimiento del dedo; la bomba lo emite. */
    fun queueMove(dx: Float, dy: Float) {
        synchronized(this) {
            pendingDx += dx
            pendingDy += dy
        }
    }

    fun queueScroll(amount: Float) {
        synchronized(this) { pendingWheel += amount }
    }

    @SuppressLint("MissingPermission")
    private fun sendMouseReport(dx: Int, dy: Int, wheel: Int) {
        val hid = proxy ?: return
        val device = target ?: return
        val report = byteArrayOf(
            buttons.toByte(),
            dx.coerceIn(-127, 127).toByte(),
            dy.coerceIn(-127, 127).toByte(),
            wheel.coerceIn(-127, 127).toByte(),
        )
        runCatching { hid.sendReport(device, HidDescriptor.REPORT_ID_MOUSE, report) }
    }

    /** Clic: 1 = izquierdo, 2 = derecho, 4 = medio. */
    suspend fun click(button: Int = 1) {
        buttons = button
        sendMouseReport(0, 0, 0)
        delay(40)
        buttons = 0
        sendMouseReport(0, 0, 0)
    }

    @SuppressLint("MissingPermission")
    private fun sendKeyboardReport(modifiers: Int, usage: Int) {
        val hid = proxy ?: return
        val device = target ?: return
        val report = ByteArray(8)
        report[0] = modifiers.toByte()
        report[2] = usage.toByte()
        runCatching { hid.sendReport(device, HidDescriptor.REPORT_ID_KEYBOARD, report) }
    }

    suspend fun pressKey(usage: Int, modifiers: Int = 0) {
        sendKeyboardReport(modifiers, usage)
        delay(12)
        sendKeyboardReport(0, 0)
        delay(8)
    }

    /** Escribe una frase completa como si fuera un teclado fisico. */
    suspend fun typeText(text: String): Result<Unit> {
        if (!isConnected) return Result.failure(IllegalStateException("El teclado Bluetooth no está conectado a la TV"))
        val normalized = HidKeyboard.normalize(text)
        DiagLog.i("hid", "escribiendo por HID (${normalized.length} caracteres)")
        for (c in normalized) {
            val (usage, shift) = HidKeyboard.usageFor(c) ?: continue
            pressKey(usage, if (shift) HidKeyboard.MOD_SHIFT else 0)
        }
        return Result.success(Unit)
    }

    @SuppressLint("MissingPermission")
    suspend fun sendConsumer(usage: Int) {
        val hid = proxy ?: return
        val device = target ?: return
        val down = byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0xFF).toByte())
        val up = byteArrayOf(0, 0)
        runCatching { hid.sendReport(device, HidDescriptor.REPORT_ID_CONSUMER, down) }
        delay(30)
        runCatching { hid.sendReport(device, HidDescriptor.REPORT_ID_CONSUMER, up) }
    }
}
