package com.flower.service

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class StreamServerStats(
    val isRunning: Boolean = false,
    val port: Int = 8080,
    val activeViewers: Int = 0,
    val fps: Float = 0f,
    val dataRateKbps: Float = 0f,
    val totalFrames: Long = 0,
    val width: Int = 0,
    val height: Int = 0
)

class StreamServer(
    private val preferredPort: Int = 8080,
    private val deviceName: String = "Android Device"
) {
    companion object {
        private const val TAG = "StreamServer"
        private const val BOUNDARY = "frame_boundary_screenshare"
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null

    private val activeClients = ConcurrentHashMap<String, Channel<ByteArray>>()
    private val viewerCounter = AtomicInteger(0)
    private val frameCounter = AtomicLong(0)
    private val byteCounter = AtomicLong(0)

    @Volatile
    private var latestJpegFrame: ByteArray? = null

    @Volatile
    private var currentWidth: Int = 0
    @Volatile
    private var currentHeight: Int = 0

    private val _stats = MutableStateFlow(StreamServerStats())
    val stats: StateFlow<StreamServerStats> = _stats.asStateFlow()

    private var statsJob: Job? = null
    private var actualPort: Int = preferredPort

    fun start(width: Int, height: Int): Int {
        stop()
        currentWidth = width
        currentHeight = height

        var port = preferredPort
        var bound = false
        var attempts = 0
        while (!bound && attempts < 10) {
            try {
                serverSocket = ServerSocket(port)
                bound = true
                actualPort = port
            } catch (e: Exception) {
                port++
                attempts++
            }
        }

        if (!bound) {
            Log.e(TAG, "Failed to bind StreamServer socket after 10 attempts")
            return -1
        }

        _stats.value = StreamServerStats(
            isRunning = true,
            port = actualPort,
            width = width,
            height = height
        )

        serverJob = scope.launch {
            try {
                while (isActive) {
                    val client = serverSocket?.accept() ?: break
                    launch(Dispatchers.IO) {
                        handleClient(client)
                    }
                }
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "Server loop exception", e)
            }
        }

        // Periodic stats calculation (every 1 second)
        statsJob = scope.launch {
            var lastFrames = 0L
            var lastBytes = 0L
            var lastTime = System.currentTimeMillis()

            while (isActive) {
                kotlinx.coroutines.delay(1000)
                val now = System.currentTimeMillis()
                val deltaSec = (now - lastTime) / 1000f
                if (deltaSec > 0f) {
                    val currentFrames = frameCounter.get()
                    val currentBytes = byteCounter.get()

                    val fps = (currentFrames - lastFrames) / deltaSec
                    val kbps = ((currentBytes - lastBytes) * 8f / 1024f) / deltaSec

                    lastFrames = currentFrames
                    lastBytes = currentBytes
                    lastTime = now

                    _stats.value = _stats.value.copy(
                        activeViewers = viewerCounter.get(),
                        fps = fps,
                        dataRateKbps = kbps,
                        totalFrames = currentFrames,
                        width = currentWidth,
                        height = currentHeight
                    )
                }
            }
        }

        return actualPort
    }

    fun onFrameAvailable(jpegBytes: ByteArray, width: Int, height: Int) {
        latestJpegFrame = jpegBytes
        currentWidth = width
        currentHeight = height
        frameCounter.incrementAndGet()

        if (activeClients.isEmpty()) return

        val header = ("--$BOUNDARY\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${jpegBytes.size}\r\n" +
                "X-Timestamp: ${System.currentTimeMillis()}\r\n\r\n").toByteArray(Charsets.UTF_8)
        val footer = "\r\n".toByteArray(Charsets.UTF_8)

        // Pre-allocate the full frame payload to avoid re-assembling it for every client
        val payload = ByteArray(header.size + jpegBytes.size + footer.size)
        System.arraycopy(header, 0, payload, 0, header.size)
        System.arraycopy(jpegBytes, 0, payload, header.size, jpegBytes.size)
        System.arraycopy(footer, 0, payload, header.size + jpegBytes.size, footer.size)
        
        byteCounter.addAndGet(payload.size.toLong())

        val iterator = activeClients.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val channel = entry.value
            // trySend on a CONFLATED channel always succeeds and non-blockingly replaces the old unread frame
            val success = channel.trySend(payload).isSuccess
            if (!success) {
                // If it fails for an unforeseen reason, log it or remove the client, but it shouldn't for CONFLATED.
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        val clientId = "${socket.inetAddress.hostAddress}:${socket.port}"
        try {
            val reader = socket.getInputStream().bufferedReader()
            val firstLine = reader.readLine() ?: return
            val parts = firstLine.split(" ")
            if (parts.size < 2) {
                socket.close()
                return
            }
            val path = parts[1]

            val out = BufferedOutputStream(socket.getOutputStream())

            when {
                path == "/stream" || path.startsWith("/stream?") -> {
                    // MJPEG stream
                    val header = ("HTTP/1.1 200 OK\r\n" +
                            "Connection: close\r\n" +
                            "Server: ScreenShareLAN/1.0\r\n" +
                            "Cache-Control: no-store, no-cache, must-revalidate, pre-check=0, post-check=0, max-age=0\r\n" +
                            "Pragma: no-cache\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n\r\n").toByteArray(Charsets.UTF_8)
                    out.write(header)
                    out.flush()

                    val channel = Channel<ByteArray>(Channel.CONFLATED)
                    activeClients[clientId] = channel
                    viewerCounter.incrementAndGet()

                    try {
                        // Send latest frame immediately if available
                        val snapshot = latestJpegFrame
                        if (snapshot != null) {
                            val frameHeader = ("--$BOUNDARY\r\n" +
                                    "Content-Type: image/jpeg\r\n" +
                                    "Content-Length: ${snapshot.size}\r\n" +
                                    "X-Timestamp: ${System.currentTimeMillis()}\r\n\r\n").toByteArray(Charsets.UTF_8)
                            out.write(frameHeader)
                            out.write(snapshot)
                            out.write("\r\n".toByteArray(Charsets.UTF_8))
                            out.flush()
                        }
                        
                        // Listen for new frames on the client's dedicated channel
                        while (coroutineContext.isActive) {
                            val framePayload = channel.receive()
                            out.write(framePayload)
                            out.flush()
                        }
                    } catch (e: Exception) {
                        // Socket closed or connection lost
                        Log.d(TAG, "Client $clientId disconnected")
                    } finally {
                        activeClients.remove(clientId)
                        viewerCounter.decrementAndGet()
                        try { socket.close() } catch (_: Exception) {}
                        channel.close()
                    }
                }

                path == "/snapshot" || path.startsWith("/snapshot.jpg") -> {
                    val snapshot = latestJpegFrame
                    if (snapshot != null) {
                        val header = ("HTTP/1.1 200 OK\r\n" +
                                "Content-Type: image/jpeg\r\n" +
                                "Content-Length: ${snapshot.size}\r\n" +
                                "Access-Control-Allow-Origin: *\r\n" +
                                "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
                        out.write(header)
                        out.write(snapshot)
                        out.flush()
                    } else {
                        val error = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.UTF_8)
                        out.write(error)
                        out.flush()
                    }
                    socket.close()
                }

                path == "/info" -> {
                    val json = "{\"name\":\"$deviceName\",\"width\":$currentWidth,\"height\":$currentHeight,\"viewers\":${viewerCounter.get()},\"fps\":${_stats.value.fps}}"
                    val bytes = json.toByteArray(Charsets.UTF_8)
                    val header = ("HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
                    out.write(header)
                    out.write(bytes)
                    out.flush()
                    socket.close()
                }

                else -> {
                    // Serve Web Viewer HTML
                    val html = getWebViewerHtml()
                    val bytes = html.toByteArray(Charsets.UTF_8)
                    val header = ("HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
                    out.write(header)
                    out.write(bytes)
                    out.flush()
                    socket.close()
                }
            }
        } catch (e: Exception) {
            activeClients.remove(clientId)
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun getWebViewerHtml(): String {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>$deviceName - Screen Share</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            background-color: #0B0F19;
            color: #F3F4F6;
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            min-height: 100vh;
            overflow: hidden;
        }
        header {
            position: fixed;
            top: 0;
            left: 0;
            right: 0;
            padding: 12px 20px;
            background: rgba(15, 23, 42, 0.85);
            backdrop-filter: blur(10px);
            display: flex;
            align-items: center;
            justify-content: space-between;
            z-index: 10;
            border-bottom: 1px solid rgba(255, 255, 255, 0.1);
        }
        .title-badge {
            display: flex;
            align-items: center;
            gap: 10px;
            font-weight: 600;
            font-size: 15px;
        }
        .live-dot {
            width: 10px;
            height: 10px;
            background: #EF4444;
            border-radius: 50%;
            box-shadow: 0 0 10px #EF4444;
            animation: pulse 1.5s infinite;
        }
        @keyframes pulse {
            0% { transform: scale(0.95); opacity: 0.8; }
            50% { transform: scale(1.15); opacity: 1; }
            100% { transform: scale(0.95); opacity: 0.8; }
        }
        .container {
            width: 100vw;
            height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 60px 10px 10px 10px;
        }
        img {
            max-width: 100%;
            max-height: calc(100vh - 80px);
            object-fit: contain;
            border-radius: 8px;
            box-shadow: 0 12px 30px rgba(0, 0, 0, 0.6);
        }
        .btn {
            background: #4F46E5;
            color: white;
            border: none;
            padding: 6px 14px;
            border-radius: 6px;
            cursor: pointer;
            font-weight: 500;
            font-size: 13px;
        }
        .btn:hover { background: #4338CA; }
    </style>
</head>
<body>
    <header>
        <div class="title-badge">
            <div class="live-dot"></div>
            <span>$deviceName (Screen Share LAN)</span>
        </div>
        <div>
            <button class="btn" onclick="toggleFullscreen()">Fullscreen</button>
        </div>
    </header>
    <div class="container">
        <img id="streamImg" src="/stream" alt="Live Screen Stream" />
    </div>
    <script>
        function toggleFullscreen() {
            if (!document.fullscreenElement) {
                document.documentElement.requestFullscreen();
            } else {
                if (document.exitFullscreen) document.exitFullscreen();
            }
        }
        const img = document.getElementById('streamImg');
        img.onerror = function() {
            setTimeout(() => { img.src = '/stream?' + new Date().getTime(); }, 1500);
        };
    </script>
</body>
</html>
        """.trimIndent()
    }

    fun stop() {
        statsJob?.cancel()
        statsJob = null
        serverJob?.cancel()
        serverJob = null

        for (client in activeClients.values) {
            try {
                client.close()
            } catch (_: Exception) {}
        }
        activeClients.clear()
        viewerCounter.set(0)

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        _stats.value = StreamServerStats(isRunning = false)
    }
}
