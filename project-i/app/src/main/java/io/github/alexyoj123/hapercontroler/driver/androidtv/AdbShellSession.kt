package io.github.alexyoj123.hapercontroler.driver.androidtv

import dadb.AdbShellPacket
import dadb.AdbShellStream
import io.github.alexyoj123.hapercontroler.core.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * UN solo stream `shell` abierto durante toda la sesion, alimentado por stdin.
 *
 * Este es el punto critico de rendimiento del driver ADB: abrir un `shell`
 * nuevo por cada pulsacion cuesta 150-300 ms y se siente inaceptable en un
 * control remoto. Con el stream persistente una tecla es un `write` y ya.
 *
 * dadb abre el shell como `shell,v2,raw:` — sin PTY, asi que la terminal NO
 * hace eco de lo que se escribe y la salida que llega es solo la del comando.
 */
class AdbShellSession(
    private val stream: AdbShellStream,
    scope: CoroutineScope,
) {

    private val mutex = Mutex()
    private val chunks = Channel<String>(Channel.UNLIMITED)
    private val reader: Job = scope.launch(Dispatchers.IO) {
        try {
            while (isActive) {
                when (val packet = stream.read()) {
                    is AdbShellPacket.StdOut -> chunks.send(String(packet.payload))
                    is AdbShellPacket.StdError -> chunks.send(String(packet.payload))
                    is AdbShellPacket.Exit -> break
                }
            }
        } catch (t: Throwable) {
            DiagLog.w("adb", "el shell persistente se cerró: ${t.message}")
        } finally {
            chunks.close()
        }
    }

    /** Dispara y no espera respuesta. Es el camino de las teclas. */
    suspend fun fire(command: String): Result<Unit> = mutex.withLock {
        runCatching {
            withContext(Dispatchers.IO) {
                stream.write(command + "\n")
            }
        }
    }

    /** Ejecuta y devuelve la salida. Usa un centinela para saber donde corta. */
    suspend fun exec(command: String, timeoutMs: Long = 10_000): Result<String> = mutex.withLock {
        runCatching {
            // Se drena lo que haya quedado de un comando anterior.
            while (chunks.tryReceive().isSuccess) Unit

            withContext(Dispatchers.IO) {
                // El literal se parte en dos para que la linea del comando
                // nunca contenga el centinela completo, solo su salida.
                stream.write("$command\necho \"${SENTINEL_A}\"\"${SENTINEL_B}\"\n")
            }

            val sb = StringBuilder()
            val ok = withTimeoutOrNull(timeoutMs) {
                for (chunk in chunks) {
                    sb.append(chunk)
                    if (sb.contains(SENTINEL)) return@withTimeoutOrNull true
                }
                false
            }
            if (ok != true) {
                DiagLog.w("adb", "timeout esperando la salida de: ${command.take(60)}")
            }
            sb.toString().substringBefore(SENTINEL).trim()
        }
    }

    fun close() {
        reader.cancel()
        runCatching { stream.close() }
    }

    companion object {
        private const val SENTINEL_A = "__VALLETH"
        private const val SENTINEL_B = "_END__"
        const val SENTINEL = SENTINEL_A + SENTINEL_B
    }
}
