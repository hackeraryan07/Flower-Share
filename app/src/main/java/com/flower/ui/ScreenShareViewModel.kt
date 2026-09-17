package com.flower.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flower.client.ReceiverStats
import com.flower.client.StreamReceiver
import com.flower.network.DiscoveredHost
import com.flower.network.NetworkDiscovery
import com.flower.network.NetworkUtils
import com.flower.service.ScreenCaptureService
import com.flower.service.StreamServerStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AppScreen {
    HOME,
    SHARING,
    VIEWER_LIST,
    VIEWER_STREAM
}

data class NetworkState(
    val ipAddress: String? = null,
    val wifiName: String = "Detecting network...",
    val isConnected: Boolean = false
)

class ScreenShareViewModel(application: Application) : AndroidViewModel(application) {

    private val _currentScreen = MutableStateFlow(AppScreen.HOME)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    private val _networkState = MutableStateFlow(NetworkState())
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    val discoveredHosts: StateFlow<List<DiscoveredHost>> = NetworkDiscovery.discoveredHosts
    val isSharing: StateFlow<Boolean> = ScreenCaptureService.isSharing
    val shareStats: StateFlow<StreamServerStats> = ScreenCaptureService.serverStats
    val streamUrl: StateFlow<String?> = ScreenCaptureService.streamUrl

    private val streamReceiver = StreamReceiver()
    val viewerFrame: StateFlow<Bitmap?> = streamReceiver.currentFrame
    val viewerStats: StateFlow<ReceiverStats> = streamReceiver.stats

    private val _activeHost = MutableStateFlow<DiscoveredHost?>(null)
    val activeHost: StateFlow<DiscoveredHost?> = _activeHost.asStateFlow()

    init {
        refreshNetworkInfo()

        // Sync screen with sharing status
        viewModelScope.launch {
            isSharing.collect { sharing ->
                if (sharing && _currentScreen.value == AppScreen.HOME) {
                    _currentScreen.value = AppScreen.SHARING
                } else if (!sharing && _currentScreen.value == AppScreen.SHARING) {
                    _currentScreen.value = AppScreen.HOME
                }
            }
        }
    }

    fun refreshNetworkInfo() {
        val context = getApplication<Application>()
        val ip = NetworkUtils.getLocalIpAddress()
        val connected = NetworkUtils.isWifiConnected(context) || ip != null
        val wifiName = NetworkUtils.getWifiName(context)
        _networkState.value = NetworkState(
            ipAddress = ip,
            wifiName = wifiName,
            isConnected = connected
        )
    }

    fun navigateTo(screen: AppScreen) {
        if (screen == AppScreen.VIEWER_LIST) {
            refreshNetworkInfo()
            NetworkDiscovery.startListening()
        } else if (_currentScreen.value == AppScreen.VIEWER_LIST && screen != AppScreen.VIEWER_STREAM) {
            NetworkDiscovery.stopListening()
        }
        _currentScreen.value = screen
    }

    fun connectToHost(host: DiscoveredHost) {
        _activeHost.value = host
        streamReceiver.start(host.streamUrl)
        _currentScreen.value = AppScreen.VIEWER_STREAM
    }

    fun connectToManualIp(ip: String, port: Int = 8080) {
        val trimmedIp = ip.trim()
        val host = DiscoveredHost(
            id = "$trimmedIp:$port",
            name = "Device ($trimmedIp)",
            ip = trimmedIp,
            port = port,
            width = 1080,
            height = 1920
        )
        connectToHost(host)
    }

    fun togglePauseViewer() {
        streamReceiver.togglePause()
    }

    fun disconnectViewer() {
        streamReceiver.stop()
        _activeHost.value = null
        _currentScreen.value = AppScreen.VIEWER_LIST
        NetworkDiscovery.startListening()
    }

    fun stopSharing(context: Context) {
        ScreenCaptureService.stop(context)
        _currentScreen.value = AppScreen.HOME
    }

    override fun onCleared() {
        super.onCleared()
        streamReceiver.stop()
        NetworkDiscovery.stopListening()
    }
}
