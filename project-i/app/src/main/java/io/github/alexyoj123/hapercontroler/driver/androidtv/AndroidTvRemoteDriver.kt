package io.github.alexyoj123.hapercontroler.driver.androidtv

import io.github.alexyoj123.hapercontroler.core.BaseTvDriver
import io.github.alexyoj123.hapercontroler.core.Capability
import io.github.alexyoj123.hapercontroler.core.ConnectionState
import io.github.alexyoj123.hapercontroler.core.DiagLog
import io.github.alexyoj123.hapercontroler.core.DriverKind
import io.github.alexyoj123.hapercontroler.core.RemoteKey
import io.github.alexyoj123.hapercontroler.core.TvApp
import io.github.alexyoj123.hapercontroler.core.TvDevice
import io.github.alexyoj123.hapercontroler.data.DeviceStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Android TV Remote v2 — el protocolo del control oficial de Google TV.
 *
 *   6467 = emparejar (la TV muestra un codigo de 6 simbolos hexadecimales)
 *   6466 = controlar
 *
 * Es el respaldo para cuando el dueno no quiere o no puede habilitar ADB. En
 * el Claro TV Box de la casa es, ademas, el UNICO puerto de control abierto
 * mientras la depuracion por red siga apagada.
 *
 * El esquema de mensajes esta tomado de la implementacion de referencia
 * `androidtvremote2` (la que usa Home Assistant); los numeros de campo y el
 * calculo del secreto no son inventados. Ver [pairingSecret] para el detalle
 * que mas facil se rompe.
 */
class AndroidTvRemoteDriver(
    override val device: TvDevice,
    private val store: DeviceStore,
    private val scope: CoroutineScope,
) : BaseTvDriver() {

    override val kind = DriverKind.ANDROID_TV_REMOTE

    /**
     * TEXT no se declara a proposito. El protocolo si tiene inyeccion de IME
     * (`RemoteImeBatchEdit`), pero solo funciona cuando la TV ya avisó que hay
     * un campo de texto enfocado, y no hay forma de garantizarlo desde acá.
     * Declararlo como capacidad dejaria un boton que a veces no hace nada, que
     * es justo lo que este proyecto no hace. Para escribir: ADB o el teclado
     * Bluetooth HID.
     */
    override val capabilities = setOf(
        Capability.KEYS,
        Capability.APP_LAUNCH,
        Capability.DEEPLINK,
    )

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var controlSocket: SSLSocket? = null
    private var controlOut: OutputStream? = null
    private var readerJob: Job? = null

    // Estado del emparejamiento, vivo solo mientras dura el dialogo del codigo.
    private var pairingSocket: SSLSocket? = null
    private var pairingOut: OutputStream? = null
    private var pairingJob: Job? = null
    private var pairingServerCert: X509Certificate? = null
    private var pairingStep: CompletableDeferred<Int>? = null

    private val pairedKey get() = "atv_paired"

    // ------------------------------------------------------------ conexion

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        disconnect()
        _connectionState.value = ConnectionState.Connecting

        runCatching { AtvIdentity.ensure(device.id) }.onFailure {
            val msg = "No se pudo crear la identidad TLS: ${it.message}"
            _connectionState.value = ConnectionState.Failed(msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        val yaEmparejado = store.secret(device.id + "_" + pairedKey) == "1"
        if (!yaEmparejado) {
            DiagLog.i("atv", "sin emparejar todavía, se abre el diálogo del código")
            return@withContext startPairing()
        }

        val result = openControl()
        if (result.isFailure) {
            // Si la TV nos olvido (reset de fabrica, "olvidar dispositivos"),
            // el 6466 rechaza el certificado. Se vuelve a emparejar.
            DiagLog.w("atv", "el control rechazó la identidad guardada, se reintenta el emparejamiento")
            store.clearSecret(device.id + "_" + pairedKey)
            return@withContext startPairing()
        }
        result
    }

    override fun disconnect() {
        readerJob?.cancel()
        readerJob = null
        runCatching { controlSocket?.close() }
        controlSocket = null
        controlOut = null
        closePairing()
        if (_connectionState.value is ConnectionState.Connected) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    // ---------------------------------------------------------- emparejar

    private suspend fun startPairing(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val socket = openTls(6467)
            pairingSocket = socket
            pairingOut = socket.outputStream
            pairingServerCert = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw IllegalStateException("La TV no presentó certificado")

            val step = CompletableDeferred<Int>()
            pairingStep = step
            pairingJob = scope.launch(Dispatchers.IO) { readPairing(socket.inputStream) }

            // 1. PairingRequest
            sendPairing(
                Proto.encode {
                    varintAlways(1, 2) // protocol_version
                    varintAlways(2, STATUS_OK.toLong())
                    message(10) {
                        string(1, "com.google.android.videos") // service_name
                        string(2, "HAPER CONTROLER") // client_name
                    }
                },
            )

            // Se espera el ack, la opcion y la configuracion; recien ahi la TV
            // pinta el codigo en pantalla.
            val alcanzado = withTimeoutOrNull(12_000) { step.await() }
                ?: throw IllegalStateException("La TV no respondió al inicio del emparejamiento")
            if (alcanzado != PASO_CODIGO_EN_PANTALLA) {
                throw IllegalStateException("El emparejamiento se cortó antes de mostrar el código")
            }

            _connectionState.value = ConnectionState.NeedsPairing(
                title = "Escribí el código que aparece en la TV",
                steps = listOf(
                    "Mirá la pantalla de la TV: tiene que haber un código de 6 caracteres.",
                    "Escribilo acá abajo tal cual, sin espacios.",
                    "Si no aparece nada, apagá y prendé la TV y volvé a intentar.",
                ),
                requiresCode = true,
            )
            Unit
        }.onFailure {
            val msg = it.message ?: "No se pudo iniciar el emparejamiento"
            DiagLog.e("atv", "fallo iniciando el emparejamiento", it)
            _connectionState.value = ConnectionState.Failed(msg)
            closePairing()
        }
    }

    override suspend fun submitPairingCode(code: String): Result<Unit> = withContext(Dispatchers.IO) {
        val limpio = code.trim().replace(" ", "").uppercase()
        if (limpio.length < 4 || !limpio.all { it.isDigit() || it in 'A'..'F' }) {
            return@withContext Result.failure(
                IllegalArgumentException("El código son 6 caracteres hexadecimales (0-9 y A-F)"),
            )
        }
        val clientCert = AtvIdentity.certificate(device.id)
            ?: return@withContext Result.failure(IllegalStateException("Falta la identidad TLS"))
        val serverCert = pairingServerCert
            ?: return@withContext Result.failure(IllegalStateException("El emparejamiento ya se cerró; volvé a intentar"))

        val secreto = runCatching { pairingSecret(limpio, clientCert, serverCert) }
            .getOrElse { return@withContext Result.failure(it) }

        val step = CompletableDeferred<Int>()
        pairingStep = step

        runCatching {
            sendPairing(
                Proto.encode {
                    varintAlways(1, 2)
                    varintAlways(2, STATUS_OK.toLong())
                    message(40) { bytes(1, secreto) } // pairing_secret
                },
            )
            val resultado = withTimeoutOrNull(12_000) { step.await() }
                ?: throw IllegalStateException("La TV no confirmó el código")
            if (resultado != PASO_SECRETO_ACEPTADO) {
                throw IllegalStateException("La TV rechazó el código. Revisalo y probá de nuevo.")
            }
        }.onFailure {
            DiagLog.e("atv", "el secreto de emparejamiento fue rechazado", it)
            _connectionState.value = ConnectionState.Failed(it.message ?: "Código rechazado")
            return@withContext Result.failure(it)
        }

        closePairing()
        store.putSecret(device.id + "_" + pairedKey, "1")
        DiagLog.i("atv", "emparejamiento completado")
        openControl()
    }

    /**
     * Secreto de emparejamiento.
     *
     *   alpha = SHA-256( mod_cliente ‖ exp_cliente ‖ mod_servidor ‖ exp_servidor ‖ nonce )
     *
     * donde `nonce` son los ultimos 4 caracteres hexadecimales del codigo (2
     * bytes) y los dos primeros caracteres son un checksum: tienen que dar
     * igual al primer byte de `alpha`. Verificarlo acá permite decirle al
     * dueno "ese codigo no es" al instante, en vez de esperar el rechazo de la
     * TV.
     *
     * Los enteros van en big-endian minimo, SIN el byte de signo de
     * `BigInteger.toByteArray()` — ver [AtvIdentity.publicNumbers].
     */
    internal fun pairingSecret(
        code: String,
        clientCert: X509Certificate,
        serverCert: X509Certificate,
    ): ByteArray {
        val (modCliente, expCliente) = AtvIdentity.publicNumbers(clientCert)
            ?: throw IllegalStateException("El certificado propio no es RSA")
        val (modServidor, expServidor) = AtvIdentity.publicNumbers(serverCert)
            ?: throw IllegalStateException("El certificado de la TV no es RSA")

        val nonce = hexToBytes(code.substring(2))
        val checksum = hexToBytes(code.substring(0, 2)).first()

        val md = MessageDigest.getInstance("SHA-256")
        md.update(modCliente)
        md.update(expCliente)
        md.update(modServidor)
        md.update(expServidor)
        md.update(nonce)
        val alpha = md.digest()

        if (alpha[0] != checksum) {
            throw IllegalArgumentException("Ese código no coincide con el que muestra la TV. Miralo de nuevo.")
        }
        return alpha
    }

    private fun readPairing(input: InputStream) {
        readFrames(input) { payload ->
            val msg = Proto.decode(payload)
            val status = msg.varint(2)?.toInt() ?: STATUS_UNKNOWN
            if (status != STATUS_OK) {
                DiagLog.w("atv", "emparejamiento: la TV devolvió status $status")
                pairingStep?.complete(PASO_ERROR)
                return@readFrames
            }
            when {
                // PairingRequestAck -> mandamos las opciones
                msg.has(11) -> sendPairing(
                    Proto.encode {
                        varintAlways(1, 2)
                        varintAlways(2, STATUS_OK.toLong())
                        message(20) { // pairing_option
                            message(1) { // input_encodings
                                varintAlways(1, ENCODING_HEXADECIMAL.toLong())
                                varintAlways(2, 6)
                            }
                            varintAlways(3, ROLE_INPUT.toLong()) // preferred_role
                        }
                    },
                )

                // PairingOption -> mandamos la configuracion
                msg.has(20) -> sendPairing(
                    Proto.encode {
                        varintAlways(1, 2)
                        varintAlways(2, STATUS_OK.toLong())
                        message(30) { // pairing_configuration
                            message(1) { // encoding
                                varintAlways(1, ENCODING_HEXADECIMAL.toLong())
                                varintAlways(2, 6)
                            }
                            varintAlways(2, ROLE_INPUT.toLong()) // client_role
                        }
                    },
                )

                // PairingConfigurationAck -> la TV ya esta mostrando el codigo
                msg.has(31) -> pairingStep?.complete(PASO_CODIGO_EN_PANTALLA)

                // PairingSecretAck -> emparejados
                msg.has(41) -> pairingStep?.complete(PASO_SECRETO_ACEPTADO)
            }
        }
    }

    private fun sendPairing(payload: ByteArray) {
        val out = pairingOut ?: throw IllegalStateException("El emparejamiento ya se cerró")
        writeFrame(out, payload)
    }

    private fun closePairing() {
        pairingJob?.cancel()
        pairingJob = null
        runCatching { pairingSocket?.close() }
        pairingSocket = null
        pairingOut = null
        pairingStep = null
    }

    // ----------------------------------------------------------- controlar

    private suspend fun openControl(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val socket = openTls(6466)
            controlSocket = socket
            controlOut = socket.outputStream
            readerJob = scope.launch(Dispatchers.IO) { readControl(socket.inputStream) }

            val listo = withTimeoutOrNull(8_000) {
                while (isActive && _connectionState.value !is ConnectionState.Connected) {
                    kotlinx.coroutines.delay(60)
                }
                true
            }
            if (listo != true) throw IllegalStateException("La TV no completó el saludo del control")
            DiagLog.i("atv", "control conectado con ${device.host}")
            Unit
        }.onFailure {
            DiagLog.e("atv", "no se pudo abrir el canal de control", it)
            _connectionState.value = ConnectionState.Failed(it.message ?: "sin control")
        }
    }

    private fun readControl(input: InputStream) {
        readFrames(input) { payload ->
            val msg = Proto.decode(payload)
            when {
                // RemoteConfigure -> devolvemos nuestra info de dispositivo
                msg.has(1) -> {
                    sendControl(
                        Proto.encode {
                            message(1) {
                                varintAlways(1, 622)
                                message(2) {
                                    string(1, "HAPER CONTROLER") // model
                                    string(2, "haper") // vendor
                                    varintAlways(3, 1)
                                    string(4, "1")
                                    string(5, "io.github.alexyoj123.hapercontroler")
                                    string(6, "1.2.0")
                                }
                            }
                        },
                    )
                }

                // RemoteSetActive -> confirmamos
                msg.has(2) -> {
                    sendControl(Proto.encode { message(2) { varintAlways(1, 622) } })
                    _connectionState.value = ConnectionState.Connected
                }

                // RemotePingRequest -> pong con el mismo val1, o el canal muere
                msg.has(8) -> {
                    val val1 = msg.message(8)?.varint(1) ?: 0
                    sendControl(Proto.encode { message(9) { varintAlways(1, val1) } })
                }

                // RemoteStart -> canal listo
                msg.has(40) -> _connectionState.value = ConnectionState.Connected

                // RemoteError
                msg.has(3) -> DiagLog.w("atv", "la TV reportó un error en el canal de control")
            }
        }
    }

    private fun sendControl(payload: ByteArray) {
        val out = controlOut ?: return
        runCatching { writeFrame(out, payload) }
            .onFailure { DiagLog.w("atv", "no se pudo escribir en el canal: ${it.message}") }
    }

    // ------------------------------------------------------------ comandos

    override suspend fun sendKey(key: RemoteKey): Result<Unit> {
        val code = AndroidTvAdbDriver.KEY_MAP[key]
            ?: return unsupported("Sin equivalente en Android TV para $key")
        if (controlOut == null) return Result.failure(IllegalStateException("Sin conexión con la TV"))
        val start = System.nanoTime()
        // RemoteKeyInject { key_code = 1, direction = 2 }; SHORT = 3
        sendControl(
            Proto.encode {
                message(10) {
                    varintAlways(1, code.toLong())
                    varintAlways(2, DIRECTION_SHORT.toLong())
                }
            },
        )
        DiagLog.d("atv", "tecla $key -> keycode $code", (System.nanoTime() - start) / 1_000_000)
        return Result.success(Unit)
    }

    override suspend fun sendText(text: String): Result<Unit> = unsupported(
        "El control oficial de Google TV no permite escribir texto de forma confiable. " +
            "Para escribir usá ADB o el teclado Bluetooth desde la pestaña Trackpad.",
    )

    override suspend fun listApps(): Result<List<TvApp>> = Result.success(APPS_CONOCIDAS)

    /**
     * `RemoteAppLinkLaunchRequest` abre un enlace, no un paquete: hay que
     * darle una URL. Por eso solo se ofrecen las apps cuyo enlace conocemos —
     * inventar un `market://` abriria la tienda, no la app, y eso seria
     * justamente un boton que finge funcionar.
     */
    override suspend fun launchApp(app: TvApp, deepLink: String?): Result<Unit> {
        if (controlOut == null) return Result.failure(IllegalStateException("Sin conexión con la TV"))
        val link = deepLink ?: APP_LINKS[app.id] ?: APP_LINKS[app.packageName]
            ?: return unsupported(
                "No conozco el enlace de «${app.name}». Con ADB habilitado se puede abrir cualquier app por paquete.",
            )
        DiagLog.i("atv", "abriendo ${app.name} por enlace")
        sendControl(Proto.encode { message(90) { string(1, link) } })
        return Result.success(Unit)
    }

    override suspend fun search(query: String, app: TvApp?): Result<Unit> {
        val encoded = query.replace(" ", "+")
        val link = when {
            app?.id?.contains("netflix") == true -> "https://www.netflix.com/search?q=$encoded"
            app?.id?.contains("youtube") == true || app == null ->
                "https://www.youtube.com/results?search_query=$encoded"
            else -> return unsupported("No sé cómo buscar dentro de «${app.name}» sin ADB")
        }
        if (controlOut == null) return Result.failure(IllegalStateException("Sin conexión con la TV"))
        sendControl(Proto.encode { message(90) { string(1, link) } })
        return Result.success(Unit)
    }

    // ------------------------------------------------------------ plomeria

    /**
     * Los mensajes van con la longitud por delante como varint. Casi todos
     * caben en un byte, que es por lo que varias implementaciones simples usan
     * un solo byte y les funciona igual; acá se hace bien por si alguno crece.
     */
    private fun writeFrame(out: OutputStream, payload: ByteArray) {
        synchronized(out) {
            out.write(Proto.varintBytes(payload.size.toLong()))
            out.write(payload)
            out.flush()
        }
    }

    private inline fun readFrames(input: InputStream, onMessage: (ByteArray) -> Unit) {
        val buffer = ArrayList<Byte>(512)
        val chunk = ByteArray(4096)
        try {
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                for (i in 0 until read) buffer.add(chunk[i])

                // Se drena todo mensaje completo que ya haya en el buffer.
                while (true) {
                    val data = buffer.toByteArray()
                    val (len, offset) = Proto.readVarint(data, 0)
                    if (offset < 0) break
                    val total = offset + len.toInt()
                    if (len < 0 || total > data.size) break
                    val payload = data.copyOfRange(offset, total)
                    repeat(total) { buffer.removeAt(0) }
                    runCatching { onMessage(payload) }
                        .onFailure { DiagLog.w("atv", "mensaje ilegible: ${it.message}") }
                }
            }
        } catch (t: Throwable) {
            DiagLog.w("atv", "el socket se cerró: ${t.message}")
        }
    }

    /**
     * TLS mutuo con el certificado del Keystore. La TV usa un certificado
     * autofirmado, asi que el TrustManager es permisivo — pero solo en ESTE
     * SSLContext, nunca global.
     */
    private fun openTls(port: Int): SSLSocket {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(AtvIdentity.keyManagers(), arrayOf<TrustManager>(trustAll), SecureRandom())

        val raw = Socket()
        raw.connect(InetSocketAddress(device.host, port), 5_000)
        raw.soTimeout = 0
        val factory: SSLSocketFactory = ctx.socketFactory
        val ssl = factory.createSocket(raw, device.host, port, true) as SSLSocket
        ssl.startHandshake()
        return ssl
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "El código tiene que tener un número par de caracteres" }
        return ByteArray(hex.length / 2) {
            ((Character.digit(hex[it * 2], 16) shl 4) or Character.digit(hex[it * 2 + 1], 16)).toByte()
        }
    }

    companion object {
        private const val STATUS_UNKNOWN = 0
        private const val STATUS_OK = 200

        private const val ROLE_INPUT = 1
        private const val ENCODING_HEXADECIMAL = 3
        private const val DIRECTION_SHORT = 3

        private const val PASO_CODIGO_EN_PANTALLA = 1
        private const val PASO_SECRETO_ACEPTADO = 2
        private const val PASO_ERROR = -1

        /** Enlaces que el protocolo sabe abrir. Solo lo verificable. */
        val APP_LINKS: Map<String, String> = mapOf(
            "youtube" to "https://www.youtube.com",
            "com.google.android.youtube.tv" to "https://www.youtube.com",
            "netflix" to "https://www.netflix.com",
            "com.netflix.ninja" to "https://www.netflix.com",
            "prime" to "https://app.primevideo.com",
            "disney" to "https://www.disneyplus.com",
            "spotify" to "https://open.spotify.com",
        )

        val APPS_CONOCIDAS = listOf(
            TvApp("youtube", "YouTube"),
            TvApp("netflix", "Netflix"),
            TvApp("prime", "Prime Video"),
            TvApp("disney", "Disney+"),
            TvApp("spotify", "Spotify"),
        )
    }
}
