package io.github.alexyoj123.vallethremote.discovery

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import io.github.alexyoj123.vallethremote.core.DiagLog
import io.github.alexyoj123.vallethremote.core.DriverKind
import io.github.alexyoj123.vallethremote.core.Net
import io.github.alexyoj123.vallethremote.core.TvDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Pista de un dispositivo: una IP y, si la hubo, un nombre amistoso.
 * mDNS y SSDP producen pistas; el sondeo de puertos las convierte en drivers.
 * Separarlo asi evita parsear XML de UPnP y da nombres reales
 * (p. ej. el Claro TV Box publica `fn=Claro TV Box 4k` por mDNS).
 */
private data class Hint(val host: String, val name: String?, val source: String)

/** Puertos que identifican cada protocolo. */
private val PORT_MAP = listOf(
    8001 to DriverKind.SAMSUNG_TIZEN,
    8060 to DriverKind.ROKU_ECP,
    3000 to DriverKind.WEBOS,
    5555 to DriverKind.ANDROID_TV_ADB,
    6466 to DriverKind.ANDROID_TV_REMOTE,
)

class Discovery(private val context: Context) {

    /**
     * Barrido completo. mDNS, SSDP y sondeo corren en paralelo con un techo
     * de [timeoutMs]; lo que no llego a tiempo simplemente no aparece.
     */
    suspend fun discover(timeoutMs: Long = 3_000): List<TvDevice> = coroutineScope {
        val hints = ConcurrentHashMap<String, Hint>()
        val multicastLock = acquireMulticastLock()

        try {
            withTimeoutOrNull(timeoutMs) {
                val jobs = listOf(
                    async { runCatching { mdns(hints, timeoutMs) } },
                    async { runCatching { ssdp(hints, timeoutMs) } },
                )
                jobs.awaitAll()
            }
        } finally {
            runCatching { multicastLock?.release() }
        }

        // El sondeo de puertos cubre lo que mDNS/SSDP no anunciaron: una TV
        // Samsung apagada no responde nada, y el ADB por red no se anuncia.
        val sweep = withTimeoutOrNull(timeoutMs + 1_500) { sweepSubnet() } ?: emptyList()
        for (host in sweep) hints.putIfAbsent(host, Hint(host, null, "sondeo"))

        DiagLog.i("discovery", "candidatos: ${hints.keys.sorted().joinToString()}")

        val devices = hints.values.map { hint ->
            async(Dispatchers.IO) { classify(hint) }
        }.awaitAll().flatten()

        devices.distinctBy { it.id }.sortedBy { it.name }
    }

    // ----------------------------------------------------------------- mDNS

    @SuppressLint("MissingPermission")
    private fun acquireMulticastLock(): WifiManager.MulticastLock? = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.createMulticastLock("valleth-remote-discovery").apply {
            setReferenceCounted(true)
            acquire()
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private suspend fun mdns(hints: ConcurrentHashMap<String, Hint>, timeoutMs: Long) = coroutineScope {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return@coroutineScope
        val types = listOf("_androidtvremote2._tcp.", "_googlecast._tcp.", "_airplay._tcp.")
        val listeners = mutableListOf<Pair<String, NsdManager.DiscoveryListener>>()

        for (type in types) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    DiagLog.w("mdns", "no se pudo iniciar $serviceType (err $errorCode)")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    // resolveService solo tolera una resolucion a la vez en
                    // varias versiones de Android; se serializa con el canal
                    // de la corrutina y se ignoran los errores de "busy".
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            val friendly = friendlyNameOf(info) ?: info.serviceName
                            hints.compute(host) { _, existing ->
                                if (existing?.name != null) existing else Hint(host, friendly, "mDNS")
                            }
                            DiagLog.d("mdns", "$friendly -> $host (${info.serviceType})")
                        }
                    })
                }
            }
            listeners += type to listener
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
        }

        try {
            delay(timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } finally {
            for ((_, listener) in listeners) runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    /** El TXT de Cast trae `fn=` (friendly name) y `md=` (modelo). */
    private fun friendlyNameOf(info: NsdServiceInfo): String? = runCatching {
        val attrs = info.attributes ?: return@runCatching null
        val fn = attrs["fn"]?.toString(Charsets.UTF_8)
        val md = attrs["md"]?.toString(Charsets.UTF_8)
        fn ?: md
    }.getOrNull()

    // ----------------------------------------------------------------- SSDP

    private suspend fun ssdp(hints: ConcurrentHashMap<String, Hint>, timeoutMs: Long) = withContext(Dispatchers.IO) {
        val targets = listOf(
            "urn:dial-multiscreen-org:service:dial:1",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
        )
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 900
                val group = InetAddress.getByName("239.255.255.250")
                for (st in targets) {
                    val msg = buildString {
                        append("M-SEARCH * HTTP/1.1\r\n")
                        append("HOST: 239.255.255.250:1900\r\n")
                        append("MAN: \"ssdp:discover\"\r\n")
                        append("MX: 2\r\n")
                        append("ST: $st\r\n\r\n")
                    }.toByteArray()
                    repeat(2) {
                        runCatching { socket.send(DatagramPacket(msg, msg.size, group, 1900)) }
                    }
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: Throwable) {
                        continue
                    }
                    val host = packet.address?.hostAddress ?: continue
                    hints.putIfAbsent(host, Hint(host, null, "SSDP"))
                    DiagLog.d("ssdp", "respuesta de $host")
                }
            }
        }
    }

    // ------------------------------------------------------- sondeo de red

    /** Sondea la /24 buscando cualquiera de los puertos conocidos. */
    private suspend fun sweepSubnet(): List<String> = withContext(Dispatchers.IO) {
        val prefix = Net.subnetPrefix24() ?: return@withContext emptyList()
        val gate = Semaphore(96)
        val found = ConcurrentHashMap.newKeySet<String>()
        coroutineScope {
            for (last in 1..254) {
                val host = "$prefix$last"
                for ((port, _) in PORT_MAP) {
                    launch {
                        gate.withPermit {
                            if (tcpOpen(host, port, 350)) found.add(host)
                        }
                    }
                }
            }
        }
        found.toList()
    }

    private fun tcpOpen(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    } catch (_: Throwable) {
        false
    }

    // ------------------------------------------------------- clasificacion

    /**
     * Una IP puede exponer varios protocolos a la vez (el Claro TV Box abre
     * 6466/6467 y, si el dueno habilita depuracion por red, tambien 5555).
     * Se devuelven todos: el repositorio elige cual usar por prioridad.
     */
    private suspend fun classify(hint: Hint): List<TvDevice> {
        val out = mutableListOf<TvDevice>()
        for ((port, kind) in PORT_MAP) {
            if (!tcpOpen(hint.host, port, 400)) continue
            val device = when (kind) {
                DriverKind.SAMSUNG_TIZEN -> samsungInfo(hint.host)
                else -> TvDevice(
                    id = TvDevice.idFor(kind, hint.host, null),
                    name = hint.name ?: defaultName(kind),
                    host = hint.host,
                    port = port,
                    kind = kind,
                )
            }
            if (device != null) out += device
        }
        if (out.isNotEmpty()) {
            DiagLog.i("discovery", "${hint.host} (${hint.source}) -> ${out.joinToString { it.kind.name }}")
        }
        return out
    }

    private fun defaultName(kind: DriverKind) = when (kind) {
        DriverKind.SAMSUNG_TIZEN -> "TV Samsung"
        DriverKind.ANDROID_TV_ADB, DriverKind.ANDROID_TV_REMOTE -> "Dispositivo Android TV"
        DriverKind.ROKU_ECP -> "Roku"
        DriverKind.WEBOS -> "TV LG"
    }

    /** `GET http://<ip>:8001/api/v2/` devuelve modelo, nombre y MAC. */
    private suspend fun samsungInfo(host: String): TvDevice? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("http://$host:8001/api/v2/").openConnection() as HttpURLConnection).apply {
                connectTimeout = 1_200
                readTimeout = 1_200
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val json = JSONObject(body)
            val deviceJson = json.optJSONObject("device")
            val name = deviceJson?.optString("name")?.takeIf { it.isNotBlank() }
                ?: json.optString("name").takeIf { it.isNotBlank() }
                ?: "TV Samsung"
            val model = deviceJson?.optString("modelName")?.takeIf { it.isNotBlank() }
            val mac = deviceJson?.optString("wifiMac")?.takeIf { it.isNotBlank() && it != "null" }
            TvDevice(
                id = TvDevice.idFor(DriverKind.SAMSUNG_TIZEN, host, mac),
                name = name,
                host = host,
                port = 8002,
                kind = DriverKind.SAMSUNG_TIZEN,
                macAddress = mac,
                model = model,
            )
        }.getOrElse {
            DiagLog.w("discovery", "8001 abierto en $host pero /api/v2/ no respondio: ${it.message}")
            TvDevice(
                id = TvDevice.idFor(DriverKind.SAMSUNG_TIZEN, host, null),
                name = "TV Samsung",
                host = host,
                port = 8002,
                kind = DriverKind.SAMSUNG_TIZEN,
            )
        }
    }
}
