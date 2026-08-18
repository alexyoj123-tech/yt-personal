package io.github.alexyoj123.vallethremote.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

object Net {

    /** IPv4 local no-loopback de la interfaz activa (Wi-Fi o Ethernet). */
    fun localIpv4(): Inet4Address? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses.asSequence() }
            .mapNotNull { it.address as? Inet4Address }
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
    }.getOrNull()

    /** Prefijo /24 de la red actual, p. ej. "192.168.1." */
    fun subnetPrefix24(): String? {
        val ip = localIpv4()?.hostAddress ?: return null
        return ip.substringBeforeLast('.') + "."
    }

    /** Direccion de broadcast de la interfaz activa; cae a 255.255.255.255. */
    fun broadcastAddress(): InetAddress = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses.asSequence() }
            .mapNotNull { it.broadcast }
            .firstOrNull()
    }.getOrNull() ?: InetAddress.getByName("255.255.255.255")

    /** Conexion TCP con timeout; true si el puerto acepta. */
    suspend fun probe(host: String, port: Int, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Magic packet Wake-on-LAN. Se manda por broadcast a los puertos 9 y 7
     * (distintas TVs escuchan en uno u otro) y tambien directo a la IP por si
     * el ARP del router todavia recuerda al dispositivo.
     */
    suspend fun wakeOnLan(mac: String, host: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = macToBytes(mac)
            val payload = ByteArray(6 + 16 * 6)
            for (i in 0 until 6) payload[i] = 0xFF.toByte()
            for (rep in 0 until 16) {
                System.arraycopy(bytes, 0, payload, 6 + rep * 6, 6)
            }
            val targets = buildList {
                add(broadcastAddress())
                add(InetAddress.getByName("255.255.255.255"))
                host?.let { runCatching { add(InetAddress.getByName(it)) } }
            }
            DatagramSocket().use { socket ->
                socket.broadcast = true
                for (target in targets) {
                    for (port in intArrayOf(9, 7)) {
                        runCatching {
                            socket.send(DatagramPacket(payload, payload.size, target, port))
                        }
                    }
                }
            }
            DiagLog.i("wol", "magic packet enviado a $mac")
        }
    }

    fun macToBytes(mac: String): ByteArray {
        val clean = mac.replace("-", ":").replace(".", ":").trim()
        val parts = clean.split(":")
        require(parts.size == 6) { "MAC invalida: $mac" }
        return ByteArray(6) { parts[it].toInt(16).toByte() }
    }
}
