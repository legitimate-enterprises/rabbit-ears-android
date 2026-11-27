package enterprises.legitimate.rabbitears

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import enterprises.legitimate.rabbitears.kit.ServerEndpoint

data class FoundServer(val name: String, val endpoint: ServerEndpoint)

/**
 * Browses for servers advertising `_rabbit-ears._tcp` and reports each one it resolves, on the main
 * thread. Resolves run one at a time because older NsdManager rejects concurrent ones.
 */
class Discovery(context: Context, private val onFound: (FoundServer) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var running = false

    private val browse =
        object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { enqueue(info) }
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit

            override fun onDiscoveryStarted(type: String) = Unit

            override fun onDiscoveryStopped(type: String) = Unit

            override fun onStartDiscoveryFailed(type: String, code: Int) {
                running = false
            }

            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
        }

    fun start() {
        if (running) return
        running =
            runCatching { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, browse) }.isSuccess
    }

    fun stop() {
        if (running) runCatching { nsd.stopServiceDiscovery(browse) }
        running = false
        queue.clear()
    }

    private fun enqueue(info: NsdServiceInfo) {
        queue.addLast(info)
        next()
    }

    @Suppress("DEPRECATION") // resolveService and host are the calls every supported release has.
    private fun next() {
        if (resolving || !running) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    // A scoped IPv6 link-local address cannot go in a URL; the IPv4 answer will.
                    val host = resolved.host?.hostAddress?.takeIf { '%' !in it }
                    main.post {
                        resolving = false
                        if (host != null && running)
                            onFound(
                                FoundServer(
                                    resolved.serviceName,
                                    ServerEndpoint.of(host, resolved.port),
                                ),
                            )
                        next()
                    }
                }

                override fun onResolveFailed(failed: NsdServiceInfo, code: Int) {
                    main.post {
                        resolving = false
                        next()
                    }
                }
            },
        )
    }

    private companion object {
        const val TYPE = "_rabbit-ears._tcp"
    }
}
