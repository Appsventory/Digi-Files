package com.appsventory.digiapps.nemo.util

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

data class SmbHost(val name: String, val address: String)

/**
 * Finds SMB servers on the local network via mDNS (_smb._tcp).
 * Detection only — browsing a share needs an SMB client library.
 */
class SmbDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val hosts = linkedMapOf<String, SmbHost>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var stopped = false
    private var onChange: ((List<SmbHost>) -> Unit)? = null

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) {
            main.post {
                pending.add(info)
                resolveNext()
            }
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            main.post {
                if (hosts.remove(info.serviceName) != null) publish()
            }
        }

        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
    }

    fun start(listener: (List<SmbHost>) -> Unit) {
        onChange = listener
        stopped = false
        runCatching { nsd?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
    }

    fun stop() {
        stopped = true
        onChange = null
        pending.clear()
        runCatching { nsd?.stopServiceDiscovery(discoveryListener) }
    }

    // NsdManager resolves one service at a time.
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || stopped) return
        val info = pending.removeFirstOrNull() ?: return
        resolving = true
        val started = runCatching {
            nsd?.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    main.post {
                        val address = resolved.host?.hostAddress
                        if (address != null && !stopped) {
                            hosts[resolved.serviceName] = SmbHost(resolved.serviceName, address)
                            publish()
                        }
                        resolving = false
                        resolveNext()
                    }
                }

                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        resolving = false
                        resolveNext()
                    }
                }
            })
        }.isSuccess
        if (!started) resolving = false
    }

    private fun publish() {
        onChange?.invoke(hosts.values.toList())
    }

    private companion object {
        const val SERVICE_TYPE = "_smb._tcp."
    }
}
