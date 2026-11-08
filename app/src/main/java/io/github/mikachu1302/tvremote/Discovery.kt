package io.github.mikachu1302.tvremote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
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
