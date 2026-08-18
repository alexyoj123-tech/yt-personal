package io.github.alexyoj123.hapercontroler.core

import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Contrato unico de todos los protocolos de TV.
 *
 * Regla de oro del proyecto: la UI consulta [capabilities], nunca la marca.
 * Si una capacidad no esta en el set, el boton correspondiente se oculta o
 * se explica — nunca se muestra un boton que parece funcionar y no hace nada.
 */
interface TvDriver {
    val kind: DriverKind
    val device: TvDevice
    val capabilities: Set<Capability>
    val connectionState: StateFlow<ConnectionState>

    suspend fun connect(): Result<Unit>

    /** Solo para drivers con [ConnectionState.NeedsPairing] y `requiresCode`. */
    suspend fun submitPairingCode(code: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Este driver no usa codigo"))

    suspend fun sendKey(key: RemoteKey): Result<Unit>

    suspend fun sendText(text: String): Result<Unit>

    suspend fun pointerMove(dx: Float, dy: Float)

    suspend fun pointerClick()

    suspend fun listApps(): Result<List<TvApp>>

    suspend fun launchApp(app: TvApp, deepLink: String? = null): Result<Unit>

    /** Busqueda dentro de una app concreta, o global si [app] es null. */
    suspend fun search(query: String, app: TvApp?): Result<Unit>

    suspend fun installApk(file: File, onProgress: (Float) -> Unit): Result<Unit>

    /** Enciende el dispositivo si el driver sabe hacerlo (Wake-on-LAN, etc.). */
    suspend fun wake(): Result<Unit> =
        Result.failure(UnsupportedOperationException("Este dispositivo no se puede encender desde la app"))

    fun disconnect()
}

/** Implementacion base: todo "no soportado" salvo lo que el driver sobrescriba. */
abstract class BaseTvDriver : TvDriver {

    protected fun unsupported(what: String): Result<Unit> =
        Result.failure(UnsupportedOperationException(what))

    override suspend fun sendText(text: String): Result<Unit> =
        unsupported("Este dispositivo no acepta texto por red")

    override suspend fun pointerMove(dx: Float, dy: Float) = Unit

    override suspend fun pointerClick() = Unit

    override suspend fun listApps(): Result<List<TvApp>> = Result.success(emptyList())

    override suspend fun launchApp(app: TvApp, deepLink: String?): Result<Unit> =
        unsupported("Este dispositivo no permite abrir apps por red")

    override suspend fun search(query: String, app: TvApp?): Result<Unit> =
        unsupported("Este dispositivo no soporta busqueda remota")

    override suspend fun installApk(file: File, onProgress: (Float) -> Unit): Result<Unit> =
        unsupported("Este dispositivo no puede instalar APKs")
}
