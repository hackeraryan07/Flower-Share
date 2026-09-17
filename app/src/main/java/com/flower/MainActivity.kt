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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flower.service.ScreenCaptureService
import com.flower.ui.AppScreen
import com.flower.ui.ScreenShareViewModel
import com.flower.ui.components.CrashReportDialog
import com.flower.ui.screens.HomeScreen
import com.flower.ui.screens.SharingScreen
import com.flower.ui.screens.ViewerListScreen
import com.flower.ui.screens.ViewerStreamScreen
import com.flower.ui.theme.FlowerTheme
import com.flower.util.CrashReporter

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.init(application)
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

    val latestCrashReport by CrashReporter.latestReport.collectAsState()
    var showCrashDialog by remember { mutableStateOf(false) }

    // Automatically prompt dialog when a new error/crash is captured
    LaunchedEffect(latestCrashReport) {
        if (latestCrashReport != null) {
            showCrashDialog = true
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    // Media projection launcher with comprehensive crash guard
    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                ScreenCaptureService.start(context, result.resultCode, result.data!!)
                viewModel.navigateTo(AppScreen.SHARING)
            } else {
                Toast.makeText(context, "Screen capture permission was cancelled or not granted", Toast.LENGTH_SHORT).show()
            }
        } catch (t: Throwable) {
            CrashReporter.recordError(context, "Failed to Launch Screen Sharing", t)
        }
    }

    // Android 13+ Notification permission launcher
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        try {
            if (!isGranted) {
                Toast.makeText(context, "Notification permission recommended for background streaming status", Toast.LENGTH_LONG).show()
            }
            val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            if (mpManager != null) {
                projectionLauncher.launch(mpManager.createScreenCaptureIntent())
            } else {
                Toast.makeText(context, "MediaProjection service not available on this device", Toast.LENGTH_LONG).show()
            }
        } catch (t: Throwable) {
            CrashReporter.recordError(context, "Notification Permission Error", t)
        }
    }

    val startScreenShare = {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                if (mpManager != null) {
                    projectionLauncher.launch(mpManager.createScreenCaptureIntent())
                } else {
                    Toast.makeText(context, "MediaProjection service not available on this device", Toast.LENGTH_LONG).show()
                }
            }
        } catch (t: Throwable) {
            CrashReporter.recordError(context, "Screen Share Request Error", t)
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

    // Crash Report Modal
    if (showCrashDialog && latestCrashReport != null) {
        CrashReportDialog(
            report = latestCrashReport!!,
            onDismiss = { showCrashDialog = false }
        )
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
                    crashReport = latestCrashReport,
                    onViewCrashReport = { showCrashDialog = true },
                    onClearCrashReport = { CrashReporter.clearReport() },
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
