package io.github.alexyoj123.hapercontroler.driver.androidtv

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import io.github.alexyoj123.hapercontroler.core.BaseTvDriver
import io.github.alexyoj123.hapercontroler.core.Capability
import io.github.alexyoj123.hapercontroler.core.ConnectionState
import io.github.alexyoj123.hapercontroler.core.DiagLog
import io.github.alexyoj123.hapercontroler.core.DriverKind
import io.github.alexyoj123.hapercontroler.core.RemoteKey
import io.github.alexyoj123.hapercontroler.core.TvApp
import io.github.alexyoj123.hapercontroler.core.TvDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.buffer
import okio.source
import java.io.File

/**
 * Driver de Android TV por ADB sobre TCP (puerto 5555).
 *
 * Es el unico camino que da instalacion de APKs y texto real sin teclado
 * Bluetooth. A cambio exige que el dueno habilite «Depuración por red» en el
 * dispositivo — [PASOS_ADB] son las instrucciones exactas que la app muestra.
 *
 * NO declara POINTER a proposito: Android TV no tiene cursor de sistema, y
 * traducir deslizamientos a flechas del D-pad es justamente el defecto de las
 * apps que este proyecto reemplaza. Para cursor real esta Bluetooth HID.
 */
class AndroidTvAdbDriver(
    private val context: Context,
    override val device: TvDevice,
    private val scope: CoroutineScope,
) : BaseTvDriver() {

    override val kind = DriverKind.ANDROID_TV_ADB

    override val capabilities = setOf(
        Capability.KEYS,
        Capability.TEXT,
        Capability.APP_LAUNCH,
        Capability.DEEPLINK,
        Capability.APK_INSTALL,
    )

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var dadb: Dadb? = null
    private var shell: AdbShellSession? = null

    /** Cache de `packageName -> componente` resuelto con `cmd package`. */
    private val resolvedActivities = mutableMapOf<String, String>()

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        disconnect()
        _connectionState.value = ConnectionState.Connecting

        val keyPair = runCatching { adbKeyPair() }.getOrElse {
            val msg = "No se pudo generar la llave ADB: ${it.message}"
            _connectionState.value = ConnectionState.Failed(msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        val result = runCatching {
            val connection = Dadb.create(
                host = device.host,
                port = if (device.port == 5555) 5555 else device.port,
                keyPair = keyPair,
                connectTimeout = 4_000,
                socketTimeout = 0,
            )
            // Un shell persistente para toda la sesion.
            val session = AdbShellSession(connection.openShell(""), scope)
            dadb = connection
            session.exec("echo listo", timeoutMs = 5_000).getOrThrow()
            shell = session
            Unit
        }

        result.fold(
            onSuccess = {
                _connectionState.value = ConnectionState.Connected
                DiagLog.i("adb", "conectado a ${device.host}:${device.port}")
                Result.success(Unit)
            },
            onFailure = { t ->
                // La huella RSA sin aceptar y el ADB apagado dan errores
                // distintos pero igual de opacos; la app explica los pasos.
                _connectionState.value = ConnectionState.NeedsPairing(
                    title = "Habilitá la depuración por red en el dispositivo",
                    steps = PASOS_ADB,
                )
                DiagLog.e("adb", "no se pudo conectar a ${device.host}", t)
                Result.failure(t)
            },
        )
    }

    override fun disconnect() {
        shell?.close()
        shell = null
        runCatching { dadb?.close() }
        dadb = null
        resolvedActivities.clear()
        if (_connectionState.value is ConnectionState.Connected) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    // -------------------------------------------------------------- teclas

    override suspend fun sendKey(key: RemoteKey): Result<Unit> {
        val code = KEY_MAP[key] ?: return unsupported("Sin equivalente en Android TV para $key")
        val session = shell ?: return Result.failure(IllegalStateException("Sin conexión ADB"))
        val start = System.nanoTime()
        val result = session.fire("input keyevent $code")
        DiagLog.d("adb", "tecla $key -> keyevent $code", (System.nanoTime() - start) / 1_000_000)
        return result
    }

    override suspend fun sendText(text: String): Result<Unit> {
        val session = shell ?: return Result.failure(IllegalStateException("Sin conexión ADB"))
        DiagLog.d("adb", "input text (${text.length} caracteres)")
        return session.fire("input text ${quoteForShell(escapeForInputText(text))}")
    }

    // --------------------------------------------------------------- apps

    override suspend fun listApps(): Result<List<TvApp>> {
        val session = shell ?: return Result.failure(IllegalStateException("Sin conexión ADB"))
        return session.exec("pm list packages -3").map { raw ->
            raw.lineSequence()
                .mapNotNull { it.trim().removePrefix("package:").takeIf(String::isNotBlank) }
                .map { pkg -> TvApp(id = pkg, name = prettyPackageName(pkg), packageName = pkg) }
                .sortedBy { it.name }
                .toList()
        }
    }

    override suspend fun launchApp(app: TvApp, deepLink: String?): Result<Unit> {
        val session = shell ?: return Result.failure(IllegalStateException("Sin conexión ADB"))
        val pkg = app.packageName ?: app.id

        if (deepLink != null) {
            return session.fire(
                "am start -a android.intent.action.VIEW -d ${quoteForShell(deepLink)} $pkg",
            )
        }

        // Los nombres de actividad cambian entre versiones: se resuelven en
        // el dispositivo en vez de hardcodearlos.
        val component = resolvedActivities[pkg] ?: resolveLauncher(session, pkg)?.also {
            resolvedActivities[pkg] = it
        }

        return if (component != null) {
            DiagLog.i("adb", "abriendo $pkg vía $component")
            session.fire("am start -n $component")
        } else {
            DiagLog.w("adb", "no se resolvió actividad para $pkg, se usa monkey")
            session.fire("monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1")
        }
    }

    private suspend fun resolveLauncher(session: AdbShellSession, pkg: String): String? {
        for (category in listOf("android.intent.category.LEANBACK_LAUNCHER", "android.intent.category.LAUNCHER")) {
            val out = session.exec(
                "cmd package resolve-activity --brief -a android.intent.action.MAIN -c $category $pkg",
            ).getOrNull().orEmpty()
            val component = out.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.contains('/') && !it.startsWith("priority") && !it.contains(' ') }
            if (!component.isNullOrBlank() && component != "No activity found") return component
        }
        return null
    }

    /**
     * Busqueda. En Android TV es donde mejor funciona: hay intents concretos
     * y no hace falta escribir en el teclado de la TV.
     */
    override suspend fun search(query: String, app: TvApp?): Result<Unit> {
        val session = shell ?: return Result.failure(IllegalStateException("Sin conexión ADB"))
        val q = quoteForShell(query)
        val pkg = app?.packageName ?: app?.id

        return when {
            pkg == null -> session.fire("am start -a android.search.action.GLOBAL_SEARCH -e query $q")

            pkg.contains("netflix") -> session.fire(
                "am start -a android.intent.action.VIEW -d ${quoteForShell(netflixSearchUrl(query))} $pkg",
            )

            pkg.contains("youtube") -> session.fire(
                "am start -a android.intent.action.SEARCH -e query $q $pkg",
            )

            else -> session.fire("am start -a android.intent.action.SEARCH -e query $q $pkg")
        }
    }

    // -------------------------------------------------------- instalacion

    /**
     * Instalacion en streaming con progreso real. Si el operador bloqueo la
     * instalacion con una politica de device-owner, `pm` devuelve
     * `INSTALL_FAILED_USER_RESTRICTED` y eso es lo que ve el dueno en
     * pantalla — literal, no un "falló" generico.
     */
    override suspend fun installApk(file: File, onProgress: (Float) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            val connection = dadb ?: return@withContext Result.failure(
                IllegalStateException("Sin conexión ADB"),
            )
            runCatching {
                val total = file.length().coerceAtLeast(1)
                var sent = 0L
                val counting: Source = object : ForwardingSource(file.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        val n = super.read(sink, byteCount)
                        if (n > 0) {
                            sent += n
                            onProgress((sent.toFloat() / total).coerceIn(0f, 1f))
                        }
                        return n
                    }
                }
                DiagLog.i("adb", "instalando ${file.name} (${total / 1024} KB)")
                // dadb 1.2.9 no devuelve un resultado: lanza IOException con
                // la salida cruda de `pm` adentro (p. ej.
                // "Install failed: Failure [INSTALL_FAILED_USER_RESTRICTED...]").
                // Ese texto es exactamente el que ve el dueno en pantalla.
                try {
                    connection.install(counting.buffer(), total, "-r", "-g")
                } catch (e: Throwable) {
                    val reason = e.message?.trim().orEmpty().ifBlank { "pm install falló sin mensaje" }
                    DiagLog.e("adb", "pm install rechazó ${file.name}: $reason")
                    throw IllegalStateException(reason)
                }
                onProgress(1f)
                DiagLog.i("adb", "instalación correcta de ${file.name}")
            }
        }

    /**
     * `versionCode` del paquete instalado, o null si no esta.
     * Se usa para no reinstalar lo que ya esta al dia.
     */
    suspend fun installedVersionCode(packageName: String): Long? {
        val session = shell ?: return null
        val out = session.exec("dumpsys package $packageName | grep versionCode").getOrNull().orEmpty()
        // La linea tiene forma: "    versionCode=1234 minSdk=28 targetSdk=34"
        val match = Regex("""versionCode=(\d+)""").find(out) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    suspend fun installedVersionName(packageName: String): String? {
        val session = shell ?: return null
        val out = session.exec("dumpsys package $packageName | grep versionName").getOrNull().orEmpty()
        return Regex("""versionName=(\S+)""").find(out)?.groupValues?.get(1)
    }

    /**
     * Deshabilita un paquete de sistema para el usuario 0.
     *
     * No lo borra de verdad — sin root eso no se puede — pero libera el
     * conflicto de firma con el build propio, y es reversible con
     * `cmd package install-existing <paquete>`.
     */
    suspend fun uninstallForUser(packageName: String): Result<Unit> {
        val session = shell ?: return Result.failure(IllegalStateException("Sin conexión ADB"))
        val out = session.exec("pm uninstall -k --user 0 $packageName").getOrNull().orEmpty()
        DiagLog.w("adb", "pm uninstall -k --user 0 $packageName -> ${out.trim().take(120)}")
        return if (out.contains("Success", ignoreCase = true)) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(out.trim().ifBlank { "pm uninstall no respondió" }))
        }
    }

    /** Revierte [uninstallForUser]: vuelve a habilitar el paquete de fabrica. */
    suspend fun restoreSystemPackage(packageName: String): Result<Unit> {
        val session = shell ?: return Result.failure(IllegalStateException("Sin conexión ADB"))
        val out = session.exec("cmd package install-existing $packageName").getOrNull().orEmpty()
        return if (out.contains("installed", ignoreCase = true) || out.contains("Success", ignoreCase = true)) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(out.trim().ifBlank { "no se pudo restaurar" }))
        }
    }

    /** Verificacion posterior: el paquete quedo realmente instalado? */
    suspend fun isPackageInstalled(packageName: String): Boolean {
        val session = shell ?: return false
        val out = session.exec("pm list packages -3 $packageName").getOrNull().orEmpty()
        return out.lineSequence().any { it.trim() == "package:$packageName" }
    }

    /**
     * Diagnostico de politica del operador. El resultado se pega tal cual en
     * la pantalla de diagnostico: sirve para saber si el bloqueo de
     * instalacion es de device-owner o solo del toggle de apps desconocidas.
     */
    suspend fun devicePolicyReport(): String {
        val session = shell ?: return "sin conexión ADB"
        val dump = session.exec("dumpsys device_policy", timeoutMs = 12_000).getOrNull().orEmpty()
        if (dump.isBlank()) return "dumpsys device_policy no devolvió nada"
        val interesting = dump.lineSequence()
            .filter { line ->
                val l = line.lowercase()
                l.contains("owner") || l.contains("restriction") || l.contains("disallow")
            }
            .take(40)
            .joinToString("\n")
        return interesting.ifBlank { "sin device-owner ni restricciones declaradas" }
    }

    // ------------------------------------------------------------ helpers

    private fun adbKeyPair(): AdbKeyPair {
        val dir = File(context.filesDir, "adb").apply { mkdirs() }
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) {
            DiagLog.i("adb", "generando llave ADB propia de la app")
            AdbKeyPair.generate(priv, pub)
        }
        return AdbKeyPair.read(priv, pub)
    }

    private fun prettyPackageName(pkg: String): String =
        pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }

    private fun netflixSearchUrl(query: String): String =
        "https://www.netflix.com/search?q=" + query.replace(" ", "+")

    /**
     * `input text` interpreta `%s` como espacio; es la forma que funciona en
     * todas las versiones, incluso las viejas que rompen con espacios reales.
     */
    private fun escapeForInputText(text: String): String = text.replace(" ", "%s")

    private fun quoteForShell(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    companion object {
        val PASOS_ADB = listOf(
            "En el dispositivo: Ajustes → Preferencias del dispositivo → Acerca de.",
            "Tocá «Compilación» siete veces hasta que diga que ya sos programador.",
            "Volvé y entrá a Opciones de programador.",
            "Activá «Depuración por USB» y «Depuración por red» (o «Depuración inalámbrica»).",
            "Volvé a intentar acá: la TV va a mostrar un aviso con una huella RSA.",
            "Marcá «Permitir siempre desde este equipo» y aceptá.",
        )

        val KEY_MAP: Map<RemoteKey, Int> = mapOf(
            RemoteKey.POWER to 26,
            RemoteKey.HOME to 3,
            RemoteKey.MENU to 82,
            RemoteKey.BACK to 4,
            RemoteKey.UP to 19,
            RemoteKey.DOWN to 20,
            RemoteKey.LEFT to 21,
            RemoteKey.RIGHT to 22,
            RemoteKey.OK to 23,
            RemoteKey.VOLUME_UP to 24,
            RemoteKey.VOLUME_DOWN to 25,
            RemoteKey.MUTE to 164,
            RemoteKey.CHANNEL_UP to 166,
            RemoteKey.CHANNEL_DOWN to 167,
            RemoteKey.SOURCE to 178,
            RemoteKey.INFO to 165,
            RemoteKey.GUIDE to 172,
            RemoteKey.SEARCH to 84,
            RemoteKey.PLAY to 126,
            RemoteKey.PAUSE to 127,
            RemoteKey.PLAY_PAUSE to 85,
            RemoteKey.STOP to 86,
            RemoteKey.REWIND to 89,
            RemoteKey.FAST_FORWARD to 90,
            RemoteKey.NEXT to 87,
            RemoteKey.PREVIOUS to 88,
            RemoteKey.DIGIT_0 to 7,
            RemoteKey.DIGIT_1 to 8,
            RemoteKey.DIGIT_2 to 9,
            RemoteKey.DIGIT_3 to 10,
            RemoteKey.DIGIT_4 to 11,
            RemoteKey.DIGIT_5 to 12,
            RemoteKey.DIGIT_6 to 13,
            RemoteKey.DIGIT_7 to 14,
            RemoteKey.DIGIT_8 to 15,
            RemoteKey.DIGIT_9 to 16,
        )
    }
}
