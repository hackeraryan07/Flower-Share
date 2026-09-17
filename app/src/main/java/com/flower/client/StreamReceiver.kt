package com.flower.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class ReceiverState {
    IDLE,
    CONNECTING,
    STREAMING,
    PAUSED,
    RECONNECTING,
    ERROR,
    DISCONNECTED
}

data class ReceiverStats(
    val state: ReceiverState = ReceiverState.IDLE,
    val fps: Float = 0f,
    val dataRateKbps: Float = 0f,
    val totalFrames: Long = 0,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val errorMessage: String? = null
)

class StreamReceiver {
    companion object {
        private const val TAG = "StreamReceiver"
        // JPEG SOI (Start Of Image) 0xFF, 0xD8 and EOI (End Of Image) 0xFF, 0xD9
        private const val SOI_1 = 0xFF.toByte()
        private const val SOI_2 = 0xD8.toByte()
        private const val EOI_1 = 0xFF.toByte()
        private const val EOI_2 = 0xD9.toByte()
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var streamJob: Job? = null
    private var statsJob: Job? = null

    private val _currentFrame = MutableStateFlow<Bitmap?>(null)
    val currentFrame: StateFlow<Bitmap?> = _currentFrame.asStateFlow()

    private val _stats = MutableStateFlow(ReceiverStats())
    val stats: StateFlow<ReceiverStats> = _stats.asStateFlow()

    private val isPaused = AtomicBoolean(false)
    private val frameCount = AtomicLong(0)
    private val byteCount = AtomicLong(0)

    fun start(streamUrl: String) {
        stop()
        _stats.value = ReceiverStats(state = ReceiverState.CONNECTING)

        statsJob = scope.launch {
            var lastFrames = 0L
            var lastBytes = 0L
            var lastTime = System.currentTimeMillis()

            while (isActive) {
                delay(1000)
                val now = System.currentTimeMillis()
                val deltaSec = (now - lastTime) / 1000f
                if (deltaSec > 0f) {
                    val currentFrames = frameCount.get()
                    val currentBytes = byteCount.get()

                    val fps = (currentFrames - lastFrames) / deltaSec
                    val kbps = ((currentBytes - lastBytes) * 8f / 1024f) / deltaSec

                    lastFrames = currentFrames
                    lastBytes = currentBytes
                    lastTime = now

                    if (_stats.value.state == ReceiverState.STREAMING) {
                        _stats.value = _stats.value.copy(
                            fps = fps,
                            dataRateKbps = kbps,
                            totalFrames = currentFrames
                        )
                    }
                }
            }
        }

        streamJob = scope.launch {
            var retryCount = 0
            while (isActive) {
                var connection: HttpURLConnection? = null
                var inputStream: BufferedInputStream? = null
                try {
                    val url = URL(streamUrl)
                    connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 4000
                        readTimeout = 8000
                        useCaches = false
                        setRequestProperty("Connection", "keep-alive")
                    }

                    connection.connect()
                    val responseCode = connection.responseCode
                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        throw Exception("HTTP error code: $responseCode")
                    }

                    inputStream = BufferedInputStream(connection.inputStream, 64 * 1024)
                    _stats.value = _stats.value.copy(state = ReceiverState.STREAMING, errorMessage = null)
                    retryCount = 0

                    readMjpegStream(inputStream)

                } catch (e: Exception) {
                    if (!isActive) break
                    Log.w(TAG, "Stream connection interrupted: ${e.message}")
                    retryCount++
                    if (retryCount <= 5) {
                        _stats.value = _stats.value.copy(
                            state = ReceiverState.RECONNECTING,
                            errorMessage = "Reconnecting to stream (attempt $retryCount)..."
                        )
                        delay(1500)
                    } else {
                        _stats.value = _stats.value.copy(
                            state = ReceiverState.ERROR,
                            errorMessage = "Lost connection to host: ${e.message}"
                        )
                        break
                    }
                } finally {
                    try {
                        inputStream?.close()
                    } catch (_: Exception) {}
                    try {
                        connection?.disconnect()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun readMjpegStream(inputStream: BufferedInputStream) {
        val buffer = ByteArray(16 * 1024)
        val frameBuffer = ByteArrayOutputStream(128 * 1024)
        var inFrame = false
        var prevByte: Byte = 0

        while (scope.isActive) {
            val bytesRead = inputStream.read(buffer)
            if (bytesRead == -1) break
            byteCount.addAndGet(bytesRead.toLong())

            var i = 0
            while (i < bytesRead) {
                val currentByte = buffer[i]

                if (!inFrame) {
                    // Look for JPEG SOI: 0xFF, 0xD8
                    if (prevByte == SOI_1 && currentByte == SOI_2) {
                        inFrame = true
                        frameBuffer.reset()
                        frameBuffer.write(SOI_1.toInt())
                        frameBuffer.write(SOI_2.toInt())
                    }
                } else {
                    frameBuffer.write(currentByte.toInt())
                    // Look for JPEG EOI: 0xFF, 0xD9
                    if (prevByte == EOI_1 && currentByte == EOI_2) {
                        inFrame = false
                        val jpegBytes = frameBuffer.toByteArray()

                        if (!isPaused.get()) {
                            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                            if (bitmap != null) {
                                val old = _currentFrame.value
                                _currentFrame.value = bitmap
                                old?.recycle()

                                frameCount.incrementAndGet()
                                _stats.value = _stats.value.copy(
                                    frameWidth = bitmap.width,
                                    frameHeight = bitmap.height
                                )
                            }
                        }
                    }
                }
                prevByte = currentByte
                i++
            }
        }
    }

    fun togglePause() {
        val newState = !isPaused.get()
        isPaused.set(newState)
        _stats.value = _stats.value.copy(
            state = if (newState) ReceiverState.PAUSED else ReceiverState.STREAMING
        )
    }

    fun stop() {
        statsJob?.cancel()
        statsJob = null
        streamJob?.cancel()
        streamJob = null

        isPaused.set(false)
        _currentFrame.value?.recycle()
        _currentFrame.value = null
        _stats.value = ReceiverStats(state = ReceiverState.DISCONNECTED)
    }
}
