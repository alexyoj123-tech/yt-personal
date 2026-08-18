package io.github.alexyoj123.vallethremote.data

import android.content.Context
import io.github.alexyoj123.vallethremote.core.Capability
import io.github.alexyoj123.vallethremote.core.CapabilityProbe
import io.github.alexyoj123.vallethremote.core.ConnectionState
import io.github.alexyoj123.vallethremote.core.DiagLog
import io.github.alexyoj123.vallethremote.core.DriverKind
import io.github.alexyoj123.vallethremote.core.RemoteKey
import io.github.alexyoj123.vallethremote.core.TvApp
import io.github.alexyoj123.vallethremote.core.TvDevice
import io.github.alexyoj123.vallethremote.core.TvDriver
import io.github.alexyoj123.vallethremote.discovery.Discovery
import io.github.alexyoj123.vallethremote.driver.androidtv.AndroidTvAdbDriver
import io.github.alexyoj123.vallethremote.driver.roku.RokuEcpDriver
import io.github.alexyoj123.vallethremote.driver.samsung.SamsungTizenDriver
import io.github.alexyoj123.vallethremote.hid.BluetoothHidController
import io.github.alexyoj123.vallethremote.hid.ConsumerUsage
import io.github.alexyoj123.vallethremote.hid.HidKeyboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Como se manda el cursor en este momento. La UI lo muestra tal cual: si el
 * unico camino disponible es el D-pad, la app lo DICE en vez de fingir que
 * hay un cursor.
 */
enum class PointerMode {
    /** Raton Bluetooth real. Es el bueno. */
    BLUETOOTH_HID,

    /** Puntero nativo del protocolo de la TV (Samsung, LG). */
    DRIVER_NATIVE,

    /** Ultimo recurso: gestos traducidos a flechas. Se avisa en pantalla. */
    DPAD_FALLBACK,
}

/**
 * Orquesta descubrimiento, driver activo y Bluetooth HID.
 *
 * La cascada de respaldo del trackpad y del texto vive aca, en un solo lugar,
 * para que cada pantalla no tenga que reimplementarla.
 */
class RemoteRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    val store: DeviceStore,
    val hid: BluetoothHidController,
) {

    private val discovery = Discovery(context)

    private val _devices = MutableStateFlow<List<TvDevice>>(emptyList())
    val devices: StateFlow<List<TvDevice>> = _devices.asStateFlow()

    private val _activeDriver = MutableStateFlow<TvDriver?>(null)
    val activeDriver: StateFlow<TvDriver?> = _activeDriver.asStateFlow()

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _apps = MutableStateFlow<List<TvApp>>(emptyList())
    val apps: StateFlow<List<TvApp>> = _apps.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** Ultimo aviso honesto para la UI (por que algo cayo a un respaldo). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    val capabilities: Set<Capability>
        get() = _activeDriver.value?.capabilities.orEmpty()

    /**
     * Reconecta al ultimo dispositivo usado ANTES de que el dueno toque nada.
     * El remoto tiene que estar vivo cuando aparece la pantalla.
     */
    suspend fun bootstrap() {
        val known = store.devices.first()
        _devices.value = known
        val lastId = store.lastDeviceId.first() ?: return
        val device = known.firstOrNull { it.id == lastId } ?: return
        DiagLog.i("repo", "reconectando al último dispositivo usado")
        select(device)
    }

    suspend fun refreshDevices() {
        _scanning.value = true
        try {
            val found = discovery.discover()
            _devices.value = store.mergeDiscovered(found)
        } finally {
            _scanning.value = false
        }
    }

    suspend fun select(device: TvDevice): Result<Unit> {
        _activeDriver.value?.disconnect()
        _apps.value = emptyList()

        val driver = when (device.kind) {
            DriverKind.SAMSUNG_TIZEN -> SamsungTizenDriver(device, store)
            DriverKind.ANDROID_TV_ADB -> AndroidTvAdbDriver(context, device, scope)
            DriverKind.ROKU_ECP -> RokuEcpDriver(device)
            DriverKind.ANDROID_TV_REMOTE, DriverKind.WEBOS -> {
                val msg = "El driver de ${device.kind.label} todavía no está implementado (queda para la fase 3)."
                _connection.value = ConnectionState.Failed(msg)
                _notice.value = msg
                DiagLog.w("repo", "driver no implementado: ${device.kind}")
                return Result.failure(UnsupportedOperationException(msg))
            }
        }

        _activeDriver.value = driver
        _connection.value = ConnectionState.Connecting

        val result = driver.connect()
        _connection.value = driver.connectionState.value

        if (result.isSuccess) {
            (driver as? SamsungTizenDriver)?.persistTokenIfAny()
            store.rememberLast(device.id)
            runCatching { driver.listApps() }.getOrNull()?.getOrNull()?.let { _apps.value = it }
        }
        return result
    }

    fun disconnect() {
        _activeDriver.value?.disconnect()
        _activeDriver.value = null
        _connection.value = ConnectionState.Disconnected
        _apps.value = emptyList()
    }

    // ------------------------------------------------------------- teclas

    /**
     * Una tecla siempre intenta primero el driver de red. Si no hay driver
     * conectado pero si hay teclado Bluetooth, la manda por HID: eso hace que
     * el remoto siga vivo aunque la Wi-Fi este caida.
     */
    suspend fun key(key: RemoteKey): Result<Unit> {
        val driver = _activeDriver.value
        if (driver != null && driver.connectionState.value is ConnectionState.Connected) {
            val result = driver.sendKey(key)
            if (result.isSuccess) return result
            DiagLog.w("repo", "la tecla $key falló por red, se prueba HID")
        }
        return keyViaHid(key)
    }

    private suspend fun keyViaHid(key: RemoteKey): Result<Unit> {
        if (!hid.isConnected) {
            return Result.failure(IllegalStateException("Sin conexión con la TV y sin teclado Bluetooth"))
        }
        val consumer = when (key) {
            RemoteKey.VOLUME_UP -> ConsumerUsage.VOLUME_UP
            RemoteKey.VOLUME_DOWN -> ConsumerUsage.VOLUME_DOWN
            RemoteKey.MUTE -> ConsumerUsage.MUTE
            RemoteKey.PLAY_PAUSE, RemoteKey.PLAY, RemoteKey.PAUSE -> ConsumerUsage.PLAY_PAUSE
            RemoteKey.STOP -> ConsumerUsage.STOP
            RemoteKey.NEXT -> ConsumerUsage.NEXT
            RemoteKey.PREVIOUS -> ConsumerUsage.PREVIOUS
            RemoteKey.FAST_FORWARD -> ConsumerUsage.FAST_FORWARD
            RemoteKey.REWIND -> ConsumerUsage.REWIND
            RemoteKey.HOME -> ConsumerUsage.HOME
            RemoteKey.BACK -> ConsumerUsage.BACK
            RemoteKey.MENU -> ConsumerUsage.MENU
            else -> null
        }
        if (consumer != null) {
            hid.sendConsumer(consumer)
            return Result.success(Unit)
        }
        val usage = when (key) {
            RemoteKey.UP -> HidKeyboard.KEY_UP
            RemoteKey.DOWN -> HidKeyboard.KEY_DOWN
            RemoteKey.LEFT -> HidKeyboard.KEY_LEFT
            RemoteKey.RIGHT -> HidKeyboard.KEY_RIGHT
            RemoteKey.OK -> HidKeyboard.KEY_ENTER
            else -> return Result.failure(IllegalStateException("La tecla $key no tiene equivalente Bluetooth"))
        }
        hid.pressKey(usage)
        return Result.success(Unit)
    }

    // -------------------------------------------------------------- texto

    /**
     * Cascada de texto: primero el protocolo de la TV, y si el modelo lo tiene
     * capado (Samsung 2021+), el teclado Bluetooth. Nunca un aviso generico:
     * el motivo real queda en [notice] y en el log.
     */
    suspend fun text(value: String): Result<Unit> {
        val driver = _activeDriver.value
        if (driver != null && Capability.TEXT in driver.capabilities) {
            val result = driver.sendText(value)
            if (result.isSuccess) return result
            DiagLog.w("repo", "el texto por red falló: ${result.exceptionOrNull()?.message}")
        }
        if (hid.isConnected) {
            _notice.value = "Esta TV no acepta texto por red; se escribió con el teclado Bluetooth."
            return hid.typeText(value)
        }
        return Result.failure(
            IllegalStateException(
                "Esta TV no acepta texto por red. Conectá el teclado Bluetooth desde la pestaña Trackpad para poder escribir.",
            ),
        )
    }

    // ------------------------------------------------------------ puntero

    /** Cascada del §7: BT HID -> puntero del driver -> gestos a D-pad. */
    fun pointerMode(): PointerMode = when {
        hid.isConnected -> PointerMode.BLUETOOTH_HID
        _activeDriver.value?.capabilities?.contains(Capability.POINTER) == true -> PointerMode.DRIVER_NATIVE
        else -> PointerMode.DPAD_FALLBACK
    }

    suspend fun pointerMove(dx: Float, dy: Float) {
        when (pointerMode()) {
            PointerMode.BLUETOOTH_HID -> hid.queueMove(dx, dy)
            PointerMode.DRIVER_NATIVE -> _activeDriver.value?.pointerMove(dx, dy)
            PointerMode.DPAD_FALLBACK -> Unit // lo maneja la pantalla con gestos
        }
    }

    suspend fun pointerScroll(amount: Float) {
        when (pointerMode()) {
            PointerMode.BLUETOOTH_HID -> hid.queueScroll(amount)
            else -> Unit
        }
    }

    suspend fun pointerClick(rightClick: Boolean = false) {
        when (pointerMode()) {
            PointerMode.BLUETOOTH_HID -> hid.click(if (rightClick) 2 else 1)
            PointerMode.DRIVER_NATIVE -> _activeDriver.value?.pointerClick()
            PointerMode.DPAD_FALLBACK -> key(RemoteKey.OK)
        }
    }

    // --------------------------------------------------------------- apps

    suspend fun refreshApps() {
        val driver = _activeDriver.value ?: return
        driver.listApps().onSuccess { _apps.value = it }
    }

    suspend fun launch(app: TvApp, deepLink: String? = null): Result<Unit> =
        _activeDriver.value?.launchApp(app, deepLink)
            ?: Result.failure(IllegalStateException("Sin dispositivo conectado"))

    suspend fun search(query: String, app: TvApp?): Result<Unit> =
        _activeDriver.value?.search(query, app)
            ?: Result.failure(IllegalStateException("Sin dispositivo conectado"))

    /** Resuelve un alias de voz ("youtube") a una app real del dispositivo. */
    fun resolveApp(alias: String): TvApp? {
        val installed = _apps.value
        val needle = alias.lowercase()
        installed.firstOrNull { candidate ->
            val pkg = candidate.packageName?.lowercase().orEmpty()
            pkg.contains(needle) || candidate.name.lowercase().contains(needle)
        }?.let { return it }

        // La Samsung usa IDs numericos, no nombres de paquete.
        return SamsungTizenDriver.FALLBACK_APPS.firstOrNull { it.name.lowercase().contains(needle) }
            ?.takeIf { _activeDriver.value?.kind == DriverKind.SAMSUNG_TIZEN }
    }

    // ---------------------------------------------------------------- apk

    suspend fun installApk(file: File, onProgress: (Float) -> Unit): Result<Unit> {
        val driver = _activeDriver.value
            ?: return Result.failure(IllegalStateException("Sin dispositivo conectado"))
        if (Capability.APK_INSTALL !in driver.capabilities) {
            return Result.failure(
                UnsupportedOperationException(motivoSinInstalacion(driver.kind)),
            )
        }
        return driver.installApk(file, onProgress)
    }

    fun motivoSinInstalacion(kind: DriverKind): String = when (kind) {
        DriverKind.SAMSUNG_TIZEN ->
            "Las TVs Samsung usan Tizen, que no es Android: no ejecutan APKs. Solo aceptan paquetes .tpk firmados por Samsung. No hay forma de instalar apps de Android en esta TV."

        DriverKind.ROKU_ECP ->
            "Roku no permite instalar aplicaciones fuera de su tienda."

        DriverKind.ANDROID_TV_REMOTE ->
            "El protocolo del control oficial de Google TV no incluye instalación de apps. Para instalar APKs hay que habilitar «Depuración por red» y conectarse por ADB."

        DriverKind.WEBOS ->
            "LG webOS no ejecuta APKs de Android."

        DriverKind.ANDROID_TV_ADB -> "Instalación disponible."
    }

    fun clearNotice() {
        _notice.value = null
    }

    fun postNotice(message: String) {
        _notice.value = message
    }

    // -------------------------------------------------- prueba de capacidades

    /**
     * Corre cada funcion una por una y reporta que paso y que no en ESTE
     * modelo de TV. El resultado se pega en docs/CONTINUIDAD-project-i.md.
     */
    suspend fun runCapabilityProbe(): List<CapabilityProbe> {
        val driver = _activeDriver.value ?: return listOf(
            CapabilityProbe(Capability.KEYS, "Conexión", false, "No hay ningún dispositivo conectado"),
        )
        val out = mutableListOf<CapabilityProbe>()

        out += probe(Capability.KEYS, "Tecla (subir volumen)") { driver.sendKey(RemoteKey.VOLUME_UP) }
        out += probe(Capability.TEXT, "Texto por red") { driver.sendText("test") }
        out += probe(Capability.APP_LAUNCH, "Listar apps instaladas") { driver.listApps().map { } }

        out += if (Capability.POINTER in driver.capabilities) {
            probe(Capability.POINTER, "Puntero nativo del protocolo") {
                driver.pointerMove(5f, 0f)
                Result.success(Unit)
            }
        } else {
            CapabilityProbe(
                Capability.POINTER,
                "Puntero nativo del protocolo",
                false,
                "Este protocolo no tiene cursor. Para cursor real usá el trackpad por Bluetooth.",
            )
        }

        out += CapabilityProbe(
            Capability.APK_INSTALL,
            "Instalar APKs",
            Capability.APK_INSTALL in driver.capabilities,
            if (Capability.APK_INSTALL in driver.capabilities) {
                "Disponible por ADB. Si el operador bloqueó la instalación, el error exacto de pm install aparece al intentarlo."
            } else {
                motivoSinInstalacion(driver.kind)
            },
        )

        out += CapabilityProbe(
            Capability.WAKE_ON_LAN,
            "Encender por Wake-on-LAN",
            Capability.WAKE_ON_LAN in driver.capabilities && driver.device.macAddress != null,
            when {
                Capability.WAKE_ON_LAN !in driver.capabilities -> "Este protocolo no soporta Wake-on-LAN."
                driver.device.macAddress == null -> "Falta la MAC del dispositivo: encendelo una vez y volvé a buscar."
                else -> "MAC conocida, el magic packet se puede enviar."
            },
        )

        // El estado del HID es transversal a todos los drivers.
        out += CapabilityProbe(
            Capability.POINTER,
            "Trackpad Bluetooth HID",
            hid.isConnected,
            if (hid.isConnected) {
                "Conectado: cursor y teclado reales disponibles."
            } else {
                "Sin conectar. Emparejá el celular desde la TV (Ajustes → accesorios Bluetooth) y conectalo en la pestaña Trackpad."
            },
        )

        DiagLog.i("diag", "prueba de capacidades: ${out.count { it.supported }}/${out.size} OK")
        return out
    }

    private suspend fun probe(
        capability: Capability,
        label: String,
        block: suspend () -> Result<Unit>,
    ): CapabilityProbe {
        val driver = _activeDriver.value
        if (driver != null && capability !in driver.capabilities) {
            return CapabilityProbe(capability, label, false, "El driver no declara esta capacidad")
        }
        val start = System.nanoTime()
        val result = runCatching { block() }.getOrElse { Result.failure(it) }
        val ms = (System.nanoTime() - start) / 1_000_000
        return CapabilityProbe(
            capability = capability,
            label = label,
            supported = result.isSuccess,
            detail = result.exceptionOrNull()?.message ?: "OK",
            latencyMs = ms,
        )
    }
}
