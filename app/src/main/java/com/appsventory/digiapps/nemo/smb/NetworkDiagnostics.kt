package com.appsventory.digiapps.nemo.smb

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Live network snapshot + a raw TCP probe to one host:port, bypassing jcifs entirely.
 * Shown on-screen so a failing connection can be diagnosed on the actual device instead
 * of guessed at remotely - paste the text back when reporting a connection problem.
 */
object NetworkDiagnostics {

    suspend fun report(context: Context, host: String, port: Int): String = withContext(Dispatchers.IO) {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val sb = StringBuilder()

        if (cm == null) {
            sb.appendLine("ConnectivityManager unavailable")
        } else {
            val active = cm.activeNetwork
            val bound = cm.boundNetworkForProcess
            sb.appendLine("Active network : ${describe(cm, active)}")
            sb.appendLine("Bound to (app) : ${if (bound == null) "not bound" else describe(cm, bound)}")
            sb.appendLine()
            sb.appendLine("All networks:")
            cm.allNetworks.forEach { n -> sb.appendLine("  - ${describe(cm, n)}") }
        }

        sb.appendLine()
        sb.appendLine("TCP probe to $host:$port")
        probe(sb, host, port, 5000)

        // Compare against the router itself: if this also fails, the device is isolated
        // from the WHOLE local network (router setting), not just from this one server.
        val gateway = cm?.let { c -> c.getLinkProperties(c.activeNetwork)?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress }
        if (gateway != null && gateway != host) {
            sb.appendLine()
            sb.appendLine("TCP probe to router $gateway:80 (for comparison)")
            probe(sb, gateway, 80, 3000)
        }

        sb.toString()
    }

    private fun probe(sb: StringBuilder, host: String, port: Int, timeoutMs: Int) {
        val start = System.currentTimeMillis()
        runCatching {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        }.onSuccess {
            sb.appendLine("  -> connected in ${System.currentTimeMillis() - start} ms")
        }.onFailure { e ->
            sb.appendLine("  -> FAILED after ${System.currentTimeMillis() - start} ms")
            sb.appendLine("     ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun describe(cm: ConnectivityManager, network: android.net.Network?): String {
        if (network == null) return "none"
        val caps = cm.getNetworkCapabilities(network)
        val transports = buildList {
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("WIFI")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("CELLULAR")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ETHERNET")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
        }.ifEmpty { listOf("?") }
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val props = cm.getLinkProperties(network)
        val ips = props?.linkAddresses?.joinToString { it.address.hostAddress ?: "?" }.orEmpty()
        val dns = props?.dnsServers?.joinToString { it.hostAddress ?: "?" }.orEmpty()
        return "$transports validated=$validated internet=$internet ip=[$ips] dns=[$dns]"
    }
}
