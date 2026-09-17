package com.flower.network

import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

data class DiscoveredHost(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int,
    val width: Int,
    val height: Int,
    val lastSeenMs: Long = System.currentTimeMillis()
) {
    val streamUrl: String get() = "http://$ip:$port/stream"
    val infoUrl: String get() = "http://$ip:$port"
}

object NetworkDiscovery {
    private const val TAG = "NetworkDiscovery"
    const val DISCOVERY_PORT = 49202
    private const val PREFIX_HOST = "SCREEN_SHARE_HOST"
    private const val PREFIX_DISCOVER = "SCREEN_SHARE_DISCOVER"

    private val scope = CoroutineScope(Dispatchers.IO)
    private var broadcasterJob: Job? = null
    private var listenerJob: Job? = null

    private val _discoveredHosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val discoveredHosts: StateFlow<List<DiscoveredHost>> = _discoveredHosts.asStateFlow()

    fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer)) model else "$manufacturer $model"
    }

    /**
     * Starts broadcasting availability as a screen sharing host on the local LAN.
     */
    fun startBroadcasting(port: Int, width: Int, height: Int) {
        stopBroadcasting()
        val deviceName = getDeviceName().replace("|", " ")

        broadcasterJob = scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(DISCOVERY_PORT).apply {
                    broadcast = true
                    soTimeout = 800
                }
            } catch (e: Exception) {
                // If port is already bound, fallback to random socket for sending
                try {
                    socket = DatagramSocket().apply { broadcast = true }
                } catch (ex: Exception) {
                    Log.e(TAG, "Failed to create discovery broadcast socket", ex)
                    return@launch
                }
            }

            val buf = ByteArray(1024)
            val receivePacket = DatagramPacket(buf, buf.size)

            // Launch periodic beacon sender
            val beaconSender = launch {
                while (isActive) {
                    val localIp = NetworkUtils.getLocalIpAddress()
                    if (localIp != null) {
                        val message = "$PREFIX_HOST|$deviceName|$localIp|$port|$width|$height|${System.currentTimeMillis()}"
                        val bytes = message.toByteArray(Charsets.UTF_8)
                        val broadcasts = NetworkUtils.getBroadcastAddresses()
                        for (broadcast in broadcasts) {
                            try {
                                val packet = DatagramPacket(bytes, bytes.size, broadcast, DISCOVERY_PORT)
                                socket?.send(packet)
                            } catch (e: Exception) {
                                // Ignore send errors for specific interface
                            }
                        }
                    }
                    delay(800)
                }
            }

            // Listen for direct discovery queries
            try {
                while (isActive) {
                    try {
                        socket?.receive(receivePacket)
                        val text = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8).trim()
                        if (text.startsWith(PREFIX_DISCOVER)) {
                            val localIp = NetworkUtils.getLocalIpAddress()
                            if (localIp != null) {
                                val reply = "$PREFIX_HOST|$deviceName|$localIp|$port|$width|$height|${System.currentTimeMillis()}"
                                val replyBytes = reply.toByteArray(Charsets.UTF_8)
                                val replyPacket = DatagramPacket(
                                    replyBytes,
                                    replyBytes.size,
                                    receivePacket.address,
                                    receivePacket.port
                                )
                                socket?.send(replyPacket)
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // Expected timeout
                    } catch (e: Exception) {
                        if (!isActive) break
                    }
                }
            } finally {
                beaconSender.cancel()
                socket?.close()
            }
        }
    }

    /**
     * Stops broadcasting host presence.
     */
    fun stopBroadcasting() {
        broadcasterJob?.cancel()
        broadcasterJob = null
    }

    /**
     * Starts listening for hosts and actively sends discovery queries on the LAN.
     */
    fun startListening() {
        stopListening()
        _discoveredHosts.value = emptyList()

        listenerJob = scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 1000
                    bind(java.net.InetSocketAddress(DISCOVERY_PORT))
                }
            } catch (e: Exception) {
                try {
                    socket = DatagramSocket().apply {
                        broadcast = true
                        soTimeout = 1000
                    }
                } catch (ex: Exception) {
                    Log.e(TAG, "Failed to bind discovery listening socket", ex)
                    return@launch
                }
            }

            // Periodic discovery query broadcaster
            val discoverSender = launch {
                while (isActive) {
                    try {
                        val query = "$PREFIX_DISCOVER|${System.currentTimeMillis()}"
                        val bytes = query.toByteArray(Charsets.UTF_8)
                        val broadcasts = NetworkUtils.getBroadcastAddresses()
                        for (broadcast in broadcasts) {
                            try {
                                val packet = DatagramPacket(bytes, bytes.size, broadcast, DISCOVERY_PORT)
                                socket?.send(packet)
                            } catch (_: Exception) {}
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }
                    delay(1200)
                }
            }

            // Periodic cleanup of stale hosts (> 3.5s silent)
            val cleanupJob = launch {
                while (isActive) {
                    delay(1500)
                    val now = System.currentTimeMillis()
                    _discoveredHosts.value = _discoveredHosts.value.filter { now - it.lastSeenMs < 3500 }
                }
            }

            val buf = ByteArray(2048)
            val packet = DatagramPacket(buf, buf.size)

            try {
                val myIp = NetworkUtils.getLocalIpAddress()
                while (isActive) {
                    try {
                        socket?.receive(packet)
                        val message = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                        if (message.startsWith(PREFIX_HOST)) {
                            val parts = message.split("|")
                            if (parts.size >= 6) {
                                val name = parts[1]
                                val ip = parts[2]
                                val port = parts[3].toIntOrNull() ?: 8080
                                val width = parts[4].toIntOrNull() ?: 1080
                                val height = parts[5].toIntOrNull() ?: 1920

                                // Don't show our own screen if we happen to receive our own broadcast
                                if (ip != myIp) {
                                    val host = DiscoveredHost(
                                        id = "$ip:$port",
                                        name = name,
                                        ip = ip,
                                        port = port,
                                        width = width,
                                        height = height,
                                        lastSeenMs = System.currentTimeMillis()
                                    )
                                    val currentList = _discoveredHosts.value.toMutableList()
                                    val index = currentList.indexOfFirst { it.id == host.id }
                                    if (index >= 0) {
                                        currentList[index] = host
                                    } else {
                                        currentList.add(host)
                                    }
                                    _discoveredHosts.value = currentList
                                }
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // Timeout allows checking isActive
                    } catch (e: Exception) {
                        if (!isActive) break
                    }
                }
            } finally {
                discoverSender.cancel()
                cleanupJob.cancel()
                socket?.close()
            }
        }
    }

    /**
     * Stops listening for hosts.
     */
    fun stopListening() {
        listenerJob?.cancel()
        listenerJob = null
    }
}
