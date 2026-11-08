package io.github.mikachu1302.tvremote

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class Tv(val name: String, val host: String)

/**
 * Finds TVs and sticks on the local network: they advertise the remote service
 * over mDNS. Emits the growing list of resolved devices; collecting starts the
 * search, cancelling stops it.
 */
@Suppress("DEPRECATION") // resolveService is the only option below API 34's callback API; still works.
fun discoverTvs(context: Context): Flow<List<Tv>> = callbackFlow {
    val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    val found = linkedMapOf<String, Tv>()
    val queue = ArrayDeque<NsdServiceInfo>()
    var resolving = false

    // NSD callbacks arrive on different threads: queue and flag share one lock.
    fun resolveNext() {
        val info = synchronized(queue) {
            if (resolving) return
            (queue.removeFirstOrNull() ?: return).also { resolving = true }
        }
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onServiceResolved(service: NsdServiceInfo) {
                val host = service.host?.hostAddress
                if (host != null && !host.contains(':')) { // IPv4 only
                    found[service.serviceName] = Tv(service.serviceName, host)
                    trySend(found.values.toList())
                }
                synchronized(queue) { resolving = false }
                resolveNext()
            }

            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                synchronized(queue) { resolving = false }
                resolveNext()
            }
        })
    }

    val listener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(service: NsdServiceInfo) {
            synchronized(queue) { queue.addLast(service) }
            resolveNext()
        }
        override fun onServiceLost(service: NsdServiceInfo) {
            found.remove(service.serviceName)
            trySend(found.values.toList())
        }
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }
    nsd.discoverServices("_androidtvremote2._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
    awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
}

/** The renderer port and description path Hisense VIDAA TVs use; SSDP can miss them (the TV's Wi-Fi is slow to answer multicast). */
private const val VIDAA_PORT = 38400
private const val VIDAA_DESCRIPTION = "/MediaServer/rendererdevicedesc.xml"

private fun describe(host: String, port: Int, path: String): AudioTv? = runCatching {
    val answer = httpRequest(host, port, "GET $path HTTP/1.1\r\nHOST: $host:$port\r\nConnection: close\r\n\r\n")
    val (name, control) = Upnp.parseDescription(answer.body) ?: return@runCatching null
    AudioTv(name, host, port, control)
}.getOrNull()

/** The other addresses of the phone's own /24 network (a home network), without the phone itself. */
private fun subnetHosts(context: Context): List<String> {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val own = cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }
        ?: return emptyList()
    val b = own.address.map { it.toInt() and 255 }
    return (1..254).map { "${b[0]}.${b[1]}.${b[2]}.$it" }.filter { it != own.hostAddress }
}

/**
 * Finds devices with a UPnP RenderingControl (TVs, speakers): an SSDP search for about three seconds, plus
 * a look at the local network for the Hisense renderer port. Runs only when the user asks for it (the TV
 * volume settings).
 */
suspend fun discoverAudioTvs(context: Context): List<AudioTv> = withContext(Dispatchers.IO) {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val lock = wifi.createMulticastLock("tvremote-ssdp").apply { setReferenceCounted(false); acquire() }
    try {
        val locations = linkedSetOf<String>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 400
            val search = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
                "ST: ${Upnp.RENDERING}\r\n\r\n").toByteArray()
            val group = InetAddress.getByName("239.255.255.250")
            repeat(2) { socket.send(DatagramPacket(search, search.size, group, 1900)) }
            val end = SystemClock.elapsedRealtime() + 3_000
            val buffer = ByteArray(2_048)
            while (SystemClock.elapsedRealtime() < end) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    Upnp.location(String(packet.data, 0, packet.length))?.let { locations += it }
                } catch (e: SocketTimeoutException) {
                    // keep listening until the time is up
                }
            }
        }
        val viaSsdp = locations.mapNotNull { location ->
            runCatching {
                val uri = URI(location)
                val port = if (uri.port > 0) uri.port else 80
                describe(uri.host, port, (uri.rawPath ?: "/") + (uri.rawQuery?.let { "?$it" } ?: ""))
            }.getOrNull()
        }
        val known = viaSsdp.map { it.host }.toSet()
        val gate = Semaphore(64)
        val viaProbe = coroutineScope {
            subnetHosts(context).filter { it !in known }.map { host ->
                async {
                    gate.withPermit {
                        val open = runCatching { Socket().use { it.connect(InetSocketAddress(host, VIDAA_PORT), 400); true } }.getOrDefault(false)
                        if (open) describe(host, VIDAA_PORT, VIDAA_DESCRIPTION) else null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        (viaSsdp + viaProbe).distinctBy { it.host to it.port }
    } finally {
        lock.release()
    }
}
