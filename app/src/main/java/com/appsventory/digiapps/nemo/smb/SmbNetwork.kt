package com.appsventory.digiapps.nemo.smb

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Forces this process' plain sockets (jcifs-ng's SMB connections included) onto the Wi-Fi
 * network specifically.
 *
 * Why this is needed: on a phone with both Wi-Fi and mobile data active, Android's default
 * network is not always Wi-Fi - if the Wi-Fi network has no validated internet access (very
 * common for a home LAN whose router has no working WAN, or a Wi-Fi Android treats as
 * "no internet"), Android quietly prefers mobile data as the default route for ordinary
 * sockets. A request to a local IP like 192.168.x.x then goes out over cellular, which can
 * never reach it, and the connection just times out - exactly a "failed to connect ... after
 * 8000ms" error, even though the server is right there on the LAN and other apps that
 * explicitly bind to Wi-Fi work fine.
 */
object SmbNetwork {
    @Volatile
    private var bound = false

    fun ensureWifiBound(context: Context) {
        if (bound) return
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return

        // Already-connected Wi-Fi network, if there is one right now - no need to wait for a callback.
        val wifi = cm.allNetworks.firstOrNull { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        if (wifi != null) {
            bindTo(cm, wifi)
            return
        }

        // Not found yet (rare race right after Wi-Fi connects) - bind as soon as one shows up.
        runCatching {
            cm.registerNetworkCallback(
                android.net.NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        bindTo(cm, network)
                        cm.unregisterNetworkCallback(this)
                    }
                }
            )
        }
    }

    private fun bindTo(cm: ConnectivityManager, network: Network) {
        if (cm.bindProcessToNetwork(network)) bound = true
    }
}
