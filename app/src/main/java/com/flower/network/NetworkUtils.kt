package com.flower.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    /**
     * Finds the best active IPv4 address on local network interfaces (WiFi, Ethernet, Hotspot).
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // Prioritize wlan0, ap0, eth0, then others
            val sorted = interfaces.sortedByDescending { iface ->
                when {
                    iface.name.startsWith("wlan") -> 3
                    iface.name.startsWith("ap") -> 2
                    iface.name.startsWith("eth") -> 1
                    else -> 0
                }
            }

            for (networkInterface in sorted) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val addresses = Collections.list(networkInterface.inetAddresses)
                for (address in addresses) {
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val hostAddress = address.hostAddress ?: continue
                        if (!hostAddress.startsWith("127.")) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * Returns broadcast addresses for all active local interfaces.
     */
    fun getBroadcastAddresses(): List<InetAddress> {
        val broadcastAddresses = mutableListOf<InetAddress>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (networkInterface in interfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null && broadcast is Inet4Address) {
                        broadcastAddresses.add(broadcast)
                    }
                }
            }
            // Always include generic subnet broadcast as fallback
            broadcastAddresses.add(InetAddress.getByName("255.255.255.255"))
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                broadcastAddresses.add(InetAddress.getByName("255.255.255.255"))
            } catch (ignored: Exception) {}
        }
        return broadcastAddresses.distinct()
    }

    /**
     * Checks if WiFi or local network is currently connected.
     */
    fun isWifiConnected(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Returns the WiFi SSID if available, or a friendly network name.
     */
    fun getWifiName(context: Context): String {
        try {
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wifiManager?.connectionInfo
            val ssid = info?.ssid
            if (!ssid.isNullOrBlank() && ssid != "<unknown ssid>") {
                return ssid.replace("\"", "")
            }
        } catch (e: Exception) {
            // Ignore permission issues
        }
        val ip = getLocalIpAddress()
        return if (ip != null) "Local LAN ($ip)" else "Disconnected"
    }
}
