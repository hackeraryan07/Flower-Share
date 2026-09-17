package com.flower.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.flower.MainActivity
import com.flower.R
import com.flower.network.NetworkDiscovery
import com.flower.network.NetworkUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        const val CHANNEL_ID = "screen_share_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA = "EXTRA_DATA"

        private val _isSharing = MutableStateFlow(false)
        val isSharing: StateFlow<Boolean> = _isSharing.asStateFlow()

        private val _serverStats = MutableStateFlow(StreamServerStats())
        val serverStats: StateFlow<StreamServerStats> = _serverStats.asStateFlow()

        private val _streamUrl = MutableStateFlow<String?>(null)
        val streamUrl: StateFlow<String?> = _streamUrl.asStateFlow()

        @Volatile
        var latestThumbnail: Bitmap? = null

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var streamServer: StreamServer? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val jpegOutputStream = ByteArrayOutputStream(128 * 1024)
    private var reusableBitmap: Bitmap? = null
    private var lastFrameTime = 0L
    private val minFrameIntervalMs = 30L // Cap at ~33 FPS to keep LAN bandwidth optimal and smooth

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }

                if (resultCode != -1 && data != null) {
                    startForegroundNotification()
                    startCapture(resultCode, data)
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.status_broadcasting))
            .setContentText("Streaming screen to local WiFi LAN")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openPendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, getString(R.string.stop_sharing), stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreenShare::WakeLock")
            wakeLock?.acquire()

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("ScreenShare::MulticastLock")
            multicastLock?.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire locks", e)
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null
            if (multicastLock?.isHeld == true) multicastLock?.release()
            multicastLock = null
        } catch (_: Exception) {}
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        mediaProjection = mpManager?.getMediaProjection(resultCode, data)
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection failed to obtain")
            stopSelf()
            return
        }

        // Get window metrics
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val screenDensity = metrics.densityDpi

        // Scale resolution for efficient local WiFi transmission (max 720p width or height)
        val maxDimension = 720
        val scale = if (screenWidth > screenHeight) {
            maxDimension.toFloat() / screenWidth.coerceAtLeast(1)
        } else {
            maxDimension.toFloat() / screenHeight.coerceAtLeast(1)
        }.coerceAtMost(1.0f)

        val captureWidth = ((screenWidth * scale).toInt() / 2) * 2
        val captureHeight = ((screenHeight * scale).toInt() / 2) * 2

        handlerThread = HandlerThread("ScreenCaptureThread").apply { start() }
        backgroundHandler = Handler(handlerThread!!.looper)

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)

        streamServer = StreamServer(preferredPort = 8080, deviceName = NetworkDiscovery.getDeviceName())
        val port = streamServer!!.start(captureWidth, captureHeight)

        val ip = NetworkUtils.getLocalIpAddress() ?: "127.0.0.1"
        _streamUrl.value = "http://$ip:$port"

        // Register virtual display
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenShareLAN",
            captureWidth,
            captureHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            backgroundHandler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            handleImageAvailable(reader, captureWidth, captureHeight)
        }, backgroundHandler)

        // Start local UDP auto-discovery beacon
        NetworkDiscovery.startBroadcasting(port, captureWidth, captureHeight)

        _isSharing.value = true

        // Forward stream server stats
        backgroundHandler?.post(object : Runnable {
            override fun run() {
                val stats = streamServer?.stats?.value
                if (stats != null) {
                    _serverStats.value = stats
                }
                if (_isSharing.value) {
                    backgroundHandler?.postDelayed(this, 1000)
                }
            }
        })
    }

    private fun handleImageAvailable(reader: ImageReader, width: Int, height: Int) {
        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) return

            val now = System.currentTimeMillis()
            if (now - lastFrameTime < minFrameIntervalMs) {
                // Drop frame to preserve target FPS and prevent CPU congestion
                return
            }
            lastFrameTime = now

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            // Bitmap width considering stride padding
            val bitmapWidth = width + rowPadding / pixelStride
            if (reusableBitmap == null || reusableBitmap?.width != bitmapWidth || reusableBitmap?.height != height) {
                reusableBitmap?.recycle()
                reusableBitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
            }

            val bmp = reusableBitmap ?: return
            bmp.copyPixelsFromBuffer(buffer)

            // Crop out padding if any
            val finalBitmap = if (rowPadding > 0) {
                Bitmap.createBitmap(bmp, 0, 0, width, height)
            } else {
                bmp
            }

            jpegOutputStream.reset()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 70, jpegOutputStream)
            val jpegBytes = jpegOutputStream.toByteArray()

            // Update latest local thumbnail for UI preview
            latestThumbnail = finalBitmap

            streamServer?.onFrameAvailable(jpegBytes, width, height)

            if (rowPadding > 0 && finalBitmap != bmp) {
                finalBitmap.recycle()
            }
        } catch (e: Exception) {
            // Buffer may be closed or in transition
        } finally {
            try {
                image?.close()
            } catch (_: Exception) {}
        }
    }

    private fun stopCapture() {
        _isSharing.value = false
        _streamUrl.value = null
        NetworkDiscovery.stopBroadcasting()

        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (_: Exception) {}

        try {
            imageReader?.close()
            imageReader = null
        } catch (_: Exception) {}

        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (_: Exception) {}

        streamServer?.stop()
        streamServer = null

        handlerThread?.quitSafely()
        handlerThread = null
        backgroundHandler = null

        reusableBitmap?.recycle()
        reusableBitmap = null
        latestThumbnail = null

        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
