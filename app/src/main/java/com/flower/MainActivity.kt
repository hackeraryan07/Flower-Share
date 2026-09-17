package com.flower

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flower.service.ScreenCaptureService
import com.flower.ui.AppScreen
import com.flower.ui.ScreenShareViewModel
import com.flower.ui.screens.HomeScreen
import com.flower.ui.screens.SharingScreen
import com.flower.ui.screens.ViewerListScreen
import com.flower.ui.screens.ViewerStreamScreen
import com.flower.ui.theme.FlowerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            FlowerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ScreenShareApp(
                        onMinimize = { moveTaskToBack(true) }
                    )
                }
            }
        }
    }
}

@Composable
fun ScreenShareApp(
    viewModel: ScreenShareViewModel = viewModel(),
    onMinimize: () -> Unit
) {
    val currentScreen by viewModel.currentScreen.collectAsState()
    val networkState by viewModel.networkState.collectAsState()
    val discoveredHosts by viewModel.discoveredHosts.collectAsState()
    val isSharing by viewModel.isSharing.collectAsState()
    val shareStats by viewModel.shareStats.collectAsState()
    val streamUrl by viewModel.streamUrl.collectAsState()
    val viewerFrame by viewModel.viewerFrame.collectAsState()
    val viewerStats by viewModel.viewerStats.collectAsState()
    val activeHost by viewModel.activeHost.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current

    // Media projection launcher
    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureService.start(context, result.resultCode, result.data!!)
            viewModel.navigateTo(AppScreen.SHARING)
        } else {
            Toast.makeText(context, "Screen capture permission is required to share screen", Toast.LENGTH_SHORT).show()
        }
    }

    // Android 13+ Notification permission launcher
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mpManager != null) {
            projectionLauncher.launch(mpManager.createScreenCaptureIntent())
        }
    }

    val startScreenShare = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            if (mpManager != null) {
                projectionLauncher.launch(mpManager.createScreenCaptureIntent())
            }
        }
    }

    // Back button handling
    BackHandler(enabled = currentScreen != AppScreen.HOME) {
        when (currentScreen) {
            AppScreen.VIEWER_STREAM -> viewModel.disconnectViewer()
            AppScreen.VIEWER_LIST -> viewModel.navigateTo(AppScreen.HOME)
            AppScreen.SHARING -> viewModel.navigateTo(AppScreen.HOME)
            AppScreen.HOME -> Unit
        }
    }

    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen_transition"
    ) { screen ->
        when (screen) {
            AppScreen.HOME -> {
                HomeScreen(
                    networkState = networkState,
                    isSharing = isSharing,
                    discoveredCount = discoveredHosts.size,
                    onRefreshNetwork = { viewModel.refreshNetworkInfo() },
                    onRequestShare = { startScreenShare() },
                    onOpenSharingScreen = { viewModel.navigateTo(AppScreen.SHARING) },
                    onOpenViewer = { viewModel.navigateTo(AppScreen.VIEWER_LIST) }
                )
            }

            AppScreen.SHARING -> {
                SharingScreen(
                    stats = shareStats,
                    streamUrl = streamUrl,
                    onBack = { viewModel.navigateTo(AppScreen.HOME) },
                    onStopSharing = { viewModel.stopSharing(context) },
                    onMinimize = onMinimize
                )
            }

            AppScreen.VIEWER_LIST -> {
                ViewerListScreen(
                    hosts = discoveredHosts,
                    networkState = networkState,
                    onBack = { viewModel.navigateTo(AppScreen.HOME) },
                    onSelectHost = { host -> viewModel.connectToHost(host) },
                    onConnectManualIp = { ip, port -> viewModel.connectToManualIp(ip, port) },
                    onRefresh = {
                        viewModel.refreshNetworkInfo()
                        com.flower.network.NetworkDiscovery.startListening()
                    }
                )
            }

            AppScreen.VIEWER_STREAM -> {
                ViewerStreamScreen(
                    host = activeHost,
                    frame = viewerFrame,
                    stats = viewerStats,
                    onDisconnect = { viewModel.disconnectViewer() },
                    onTogglePause = { viewModel.togglePauseViewer() }
                )
            }
        }
    }
}
