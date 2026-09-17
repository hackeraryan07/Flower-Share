package com.flower.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.flower.MainActivity
import com.flower.R
import com.flower.network.NetworkDiscovery
import com.flower.network.NetworkUtils
import com.flower.util.CrashReporter
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

        fun start(context: Context, resultCode: Int, data: Intent) {
            try {
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
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to start ScreenCaptureService", e)
                CrashReporter.recordError(context, "Failed to launch Screen Capture Service", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, ScreenCaptureService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to stop ScreenCaptureService", e)
                _isSharing.value = false
            }
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
    private var croppedBitmap: Bitmap? = null
    private var lastFrameTime = 0L
    private val minFrameIntervalMs = 33L // Cap at ~30 FPS for smooth LAN streaming without CPU saturation
    private val isProcessingFrame = java.util.concurrent.atomic.AtomicBoolean(false)

    private var mediaProjectionCallback: MediaProjection.Callback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            acquireLocks()
            // Immediately start foreground notification upon service creation to fulfill
            // Android 14+ / ForegroundServiceDidNotStartInTimeException contract unconditionally.
            startForegroundNotification()
        } catch (t: Throwable) {
            Log.e(TAG, "Error in onCreate", t)
            CrashReporter.recordError(this, "Service Initialization Error", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // Always ensure startForeground has been called as early as possible
            startForegroundNotification()

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
                        // Ensure background handler thread is active
                        if (handlerThread == null || handlerThread?.isAlive != true) {
                            handlerThread = HandlerThread("ScreenCaptureThread").apply { start() }
                            backgroundHandler = Handler(handlerThread!!.looper)
                        }
                        backgroundHandler?.post {
                            startCapture(resultCode, data)
                        }
                    } else {
                        Log.e(TAG, "Invalid resultCode or data for screen capture: resultCode=$resultCode, data=$data")
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(applicationContext, "Invalid screen capture permission data received", Toast.LENGTH_SHORT).show()
                        }
                        stopCapture()
                        stopSelf()
                    }
                }
                ACTION_STOP -> {
                    stopCapture()
                    stopSelf()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error in onStartCommand", t)
            CrashReporter.recordError(this, "Screen Share Service Command Error", t)
            stopCapture()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
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
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create notification channel", e)
            }
        }
    }

    private fun startForegroundNotification(): Boolean {
        return try {
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
                .setSmallIcon(R.drawable.ic_screen_share_notification)
                .setContentIntent(openPendingIntent)
                .addAction(R.drawable.ic_screen_share_notification, getString(R.string.stop_sharing), stopPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start foreground notification", t)
            CrashReporter.recordError(this, "Foreground Notification Error", t)
            false
        }
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
        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            if (mpManager == null) {
                val err = IllegalStateException("MediaProjectionManager not available on this device")
                CrashReporter.recordError(this, "MediaProjection Unavailable", err)
                stopSelf()
                return
            }

            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                val err = IllegalStateException("MediaProjection failed to obtain (permission was not granted or expired)")
                CrashReporter.recordError(this, "MediaProjection Failed", err)
                stopSelf()
                return
            }

            // Ensure background handler thread is active
            if (handlerThread == null || handlerThread?.isAlive != true) {
                handlerThread = HandlerThread("ScreenCaptureThread").apply { start() }
                backgroundHandler = Handler(handlerThread!!.looper)
            }
            val handler = backgroundHandler ?: Handler(Looper.getMainLooper())

            // CRITICAL (Android 14+ / API 34+): Register Callback BEFORE createVirtualDisplay()
            mediaProjectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection session terminated by user or system")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(applicationContext, "Screen sharing ended", Toast.LENGTH_SHORT).show()
                    }
                    stopCapture()
                    stopSelf()
                }
            }
            mediaProjection?.registerCallback(mediaProjectionCallback!!, handler)

            // Calculate display resolution safely
            val (screenWidth, screenHeight, screenDensity) = getDisplayDimensions()

            // Scale resolution for efficient local WiFi transmission (max 720p width or height)
            val maxDimension = 720
            val scale = if (screenWidth > screenHeight) {
                maxDimension.toFloat() / screenWidth.coerceAtLeast(1)
            } else {
                maxDimension.toFloat() / screenHeight.coerceAtLeast(1)
            }.coerceAtMost(1.0f)

            val captureWidth = (((screenWidth * scale).toInt() / 2) * 2).coerceAtLeast(320)
            val captureHeight = (((screenHeight * scale).toInt() / 2) * 2).coerceAtLeast(480)

            // Allocate 4 image buffers so that rapid display updates never exhaust the buffer queue
            imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 4)

            // Start embedded streaming server
            streamServer = StreamServer(preferredPort = 8080, deviceName = NetworkDiscovery.getDeviceName())
            val port = streamServer!!.start(captureWidth, captureHeight)

            val ip = NetworkUtils.getLocalIpAddress() ?: "127.0.0.1"
            _streamUrl.value = "http://$ip:$port"

            // Create Virtual Display
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

            if (virtualDisplay == null) {
                val err = IllegalStateException("Failed to create VirtualDisplay for screen capture")
                CrashReporter.recordError(this, "VirtualDisplay Creation Failed", err)
                stopCapture()
                stopSelf()
                return
            }

            imageReader?.setOnImageAvailableListener({ reader ->
                handleImageAvailable(reader, captureWidth, captureHeight)
            }, backgroundHandler)

            // Start local UDP auto-discovery beacon
            NetworkDiscovery.startBroadcasting(port, captureWidth, captureHeight)

            _isSharing.value = true

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, "Screen broadcast started on WiFi LAN", Toast.LENGTH_SHORT).show()
            }

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
        } catch (t: Throwable) {
            Log.e(TAG, "Fatal error starting screen capture", t)
            CrashReporter.recordError(this, "Failed to Start Screen Capture", t)
            stopCapture()
            stopSelf()
        }
    }

    private fun getDisplayDimensions(): Triple<Int, Int, Int> {
        return try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                val density = resources.configuration.densityDpi
                Triple(bounds.width(), bounds.height(), density)
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falling back to display metrics", e)
            val dm = resources.displayMetrics
            Triple(
                dm.widthPixels.coerceAtLeast(720),
                dm.heightPixels.coerceAtLeast(1280),
                dm.densityDpi.coerceAtLeast(DisplayMetrics.DENSITY_DEFAULT)
            )
        }
    }

    private fun handleImageAvailable(reader: ImageReader, width: Int, height: Int) {
        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) return

            // If a previous frame is still being encoded or we haven't reached min interval, skip
            val now = System.currentTimeMillis()
            if (now - lastFrameTime < minFrameIntervalMs || !isProcessingFrame.compareAndSet(false, true)) {
                // Drop frame to preserve responsiveness and target FPS
                return
            }
            lastFrameTime = now

            try {
                val planes = image.planes
                if (planes.isEmpty()) return

                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmapWidth = width + rowPadding / pixelStride
                if (reusableBitmap == null || reusableBitmap?.width != bitmapWidth || reusableBitmap?.height != height) {
                    reusableBitmap?.recycle()
                    reusableBitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
                }

                val bmp = reusableBitmap ?: return
                bmp.copyPixelsFromBuffer(buffer)

                val frameBitmap = if (rowPadding > 0) {
                    if (croppedBitmap == null || croppedBitmap?.width != width || croppedBitmap?.height != height) {
                        croppedBitmap?.recycle()
                        croppedBitmap = Bitmap.createBitmap(bmp, 0, 0, width, height)
                    } else {
                        // Re-draw onto croppedBitmap without allocation
                        val canvas = android.graphics.Canvas(croppedBitmap!!)
                        val srcRect = android.graphics.Rect(0, 0, width, height)
                        val dstRect = android.graphics.Rect(0, 0, width, height)
                        canvas.drawBitmap(bmp, srcRect, dstRect, null)
                    }
                    croppedBitmap ?: bmp
                } else {
                    bmp
                }

                jpegOutputStream.reset()
                frameBitmap.compress(Bitmap.CompressFormat.JPEG, 70, jpegOutputStream)
                val jpegBytes = jpegOutputStream.toByteArray()

                streamServer?.onFrameAvailable(jpegBytes, width, height)
            } finally {
                isProcessingFrame.set(false)
            }
        } catch (e: Exception) {
            // Normal during stream stop or resolution transition
            isProcessingFrame.set(false)
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
            if (mediaProjectionCallback != null && mediaProjection != null) {
                mediaProjection?.unregisterCallback(mediaProjectionCallback!!)
            }
        } catch (_: Exception) {}
        mediaProjectionCallback = null

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

        try {
            streamServer?.stop()
            streamServer = null
        } catch (_: Exception) {}

        try {
            handlerThread?.quitSafely()
            handlerThread = null
            backgroundHandler = null
        } catch (_: Exception) {}

        try {
            reusableBitmap?.recycle()
            reusableBitmap = null
            croppedBitmap?.recycle()
            croppedBitmap = null
        } catch (_: Exception) {}

        releaseLocks()

        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
            try {
                @Suppress("DEPRECATION")
                stopForeground(true)
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
