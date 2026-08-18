package io.github.alexyoj123.vallethremote.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log en anillo, en memoria y en disco.
 *
 * Esto es lo que hace que la app se pueda arreglar dentro de un ano: cada
 * comando queda con driver, dispositivo, resultado y latencia. Lo que NUNCA
 * entra aqui son tokens ni el contenido de lo que se dicta por voz — solo su
 * longitud y la intencion detectada.
 */
object DiagLog {

    private const val MAX_BYTES = 500 * 1024L
    private const val MAX_IN_MEMORY = 400
    private const val TAG = "VallEthRemote"

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val timeMs: Long,
        val level: Level,
        val source: String,
        val message: String,
        val latencyMs: Long? = null,
    ) {
        fun format(): String {
            val ts = TS.format(Date(timeMs))
            val lat = latencyMs?.let { " (${it}ms)" } ?: ""
            return "$ts ${level.name.padEnd(5)} [$source] $message$lat"
        }
    }

    private val TS = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = Channel<Entry>(capacity = 256)
    private var logFile: File? = null

    fun init(context: Context) {
        if (logFile != null) return
        val dir = File(context.filesDir, "diag").apply { mkdirs() }
        logFile = File(dir, "valleth-remote.log")
        scope.launch {
            for (entry in pending) {
                appendToDisk(entry)
            }
        }
        i("app", "--- sesion iniciada ---")
    }

    fun d(source: String, message: String, latencyMs: Long? = null) =
        add(Level.DEBUG, source, message, latencyMs)

    fun i(source: String, message: String, latencyMs: Long? = null) =
        add(Level.INFO, source, message, latencyMs)

    fun w(source: String, message: String, latencyMs: Long? = null) =
        add(Level.WARN, source, message, latencyMs)

    fun e(source: String, message: String, throwable: Throwable? = null) =
        add(Level.ERROR, source, message + (throwable?.let { ": ${it.javaClass.simpleName}: ${it.message}" } ?: ""), null)

    private fun add(level: Level, source: String, message: String, latencyMs: Long?) {
        val entry = Entry(System.currentTimeMillis(), level, source, message, latencyMs)
        _entries.value = (_entries.value + entry).let {
            if (it.size > MAX_IN_MEMORY) it.subList(it.size - MAX_IN_MEMORY, it.size) else it
        }
        when (level) {
            Level.ERROR -> Log.e(TAG, entry.format())
            Level.WARN -> Log.w(TAG, entry.format())
            else -> Log.d(TAG, entry.format())
        }
        pending.trySend(entry)
    }

    private fun appendToDisk(entry: Entry) {
        val file = logFile ?: return
        try {
            file.appendText(entry.format() + "\n")
            if (file.length() > MAX_BYTES) rotate(file)
        } catch (t: Throwable) {
            Log.w(TAG, "no se pudo escribir el log: ${t.message}")
        }
    }

    /** Anillo simple: se conserva la mitad mas reciente y se descarta el resto. */
    private fun rotate(file: File) {
        try {
            val lines = file.readLines()
            val keep = lines.subList(lines.size / 2, lines.size)
            file.writeText(keep.joinToString("\n", postfix = "\n"))
        } catch (t: Throwable) {
            file.writeText("")
        }
    }

    /** Copia el log a la cache para compartirlo con un FileProvider. */
    fun exportTo(context: Context, header: String): File? {
        return try {
            val dir = File(context.cacheDir, "export").apply { mkdirs() }
            val out = File(dir, "valleth-remote-diagnostico.txt")
            val body = logFile?.takeIf { it.exists() }?.readText().orEmpty()
            val tail = _entries.value.joinToString("\n") { it.format() }
            out.writeText(header + "\n\n" + body + "\n--- en memoria ---\n" + tail + "\n")
            out
        } catch (t: Throwable) {
            e("diag", "fallo exportando el log", t)
            null
        }
    }

    fun clear() {
        _entries.value = emptyList()
        scope.launch { runCatching { logFile?.writeText("") } }
    }
}

/** Mide una operacion y la registra con su latencia real. */
suspend inline fun <T> timed(source: String, what: String, block: () -> T): T {
    val start = System.nanoTime()
    return try {
        val result = block()
        DiagLog.d(source, what, (System.nanoTime() - start) / 1_000_000)
        result
    } catch (t: Throwable) {
        DiagLog.e(source, "$what fallo", t)
        throw t
    }
}
