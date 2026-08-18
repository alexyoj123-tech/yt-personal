package io.github.alexyoj123.hapercontroler.core

/**
 * Lo que un dispositivo concreto sabe hacer. La UI pregunta SIEMPRE por
 * capacidades, nunca por marca: agregar una marca nueva = agregar un driver,
 * sin tocar una sola pantalla.
 */
enum class Capability {
    KEYS,
    TEXT,
    POINTER,
    APP_LAUNCH,
    DEEPLINK,
    APK_INSTALL,
    WAKE_ON_LAN,
}

/** Teclas logicas. Cada driver las traduce a su protocolo. */
enum class RemoteKey {
    POWER,
    HOME,
    MENU,
    BACK,
    UP,
    DOWN,
    LEFT,
    RIGHT,
    OK,
    VOLUME_UP,
    VOLUME_DOWN,
    MUTE,
    CHANNEL_UP,
    CHANNEL_DOWN,
    SOURCE,
    TOOLS,
    INFO,
    GUIDE,
    SEARCH,
    PLAY,
    PAUSE,
    PLAY_PAUSE,
    STOP,
    REWIND,
    FAST_FORWARD,
    NEXT,
    PREVIOUS,
    DIGIT_0,
    DIGIT_1,
    DIGIT_2,
    DIGIT_3,
    DIGIT_4,
    DIGIT_5,
    DIGIT_6,
    DIGIT_7,
    DIGIT_8,
    DIGIT_9,
}

/** Que protocolo habla el dispositivo. */
enum class DriverKind(val label: String) {
    SAMSUNG_TIZEN("Samsung (Tizen)"),
    ANDROID_TV_ADB("Android TV (ADB)"),
    ANDROID_TV_REMOTE("Android TV (remoto oficial)"),
    ROKU_ECP("Roku"),
    WEBOS("LG (webOS)"),
}

/** Un dispositivo descubierto o guardado. */
data class TvDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val kind: DriverKind,
    val macAddress: String? = null,
    val model: String? = null,
) {
    val displayName: String get() = if (model.isNullOrBlank()) name else "$name · $model"

    companion object {
        /**
         * Id estable. Se prefiere la MAC porque el DHCP cambia la IP; si no
         * hay MAC, la combinacion driver+host es lo mejor disponible.
         */
        fun idFor(kind: DriverKind, host: String, mac: String?): String =
            if (!mac.isNullOrBlank()) "${kind.name}@${mac.lowercase()}" else "${kind.name}@$host"
    }
}

/** Una app instalada en la TV. */
data class TvApp(
    val id: String,
    val name: String,
    val packageName: String? = null,
)

/** Estado de la conexion del driver activo. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState

    /**
     * El dispositivo pide una accion fisica del usuario antes de dejar
     * conectarse (aceptar un dialogo en pantalla, teclear un codigo,
     * habilitar depuracion por red...). [steps] son instrucciones concretas,
     * no un mensaje generico.
     */
    data class NeedsPairing(
        val title: String,
        val steps: List<String>,
        val requiresCode: Boolean = false,
    ) : ConnectionState

    data object Connected : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}

/** Resultado de una prueba de capacidad en la pantalla de diagnostico. */
data class CapabilityProbe(
    val capability: Capability,
    val label: String,
    val supported: Boolean,
    val detail: String,
    val latencyMs: Long? = null,
)
