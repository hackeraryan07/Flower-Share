package com.flower.util

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CrashReport(
    val timestamp: String,
    val title: String,
    val message: String,
    val stackTrace: String,
    val deviceInfo: String
) {
    fun toFormattedLog(): String {
        return buildString {
            appendLine("=== CRASH / ERROR REPORT ===")
            appendLine("Timestamp: $timestamp")
            appendLine("Device: $deviceInfo")
            appendLine("Error: $title")
            appendLine("Message: $message")
            appendLine("--- STACK TRACE ---")
            appendLine(stackTrace)
            appendLine("===========================")
        }
    }
}

object CrashReporter {
    private const val TAG = "CrashReporter"
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _latestReport = MutableStateFlow<CrashReport?>(null)
    val latestReport: StateFlow<CrashReport?> = _latestReport.asStateFlow()

    private var defaultUncaughtHandler: Thread.UncaughtExceptionHandler? = null
    private var isInitialized = false

    fun init(application: Application) {
        if (isInitialized) return
        isInitialized = true

        defaultUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            recordError(
                context = application,
                title = "Uncaught Exception in ${thread.name}",
                throwable = throwable,
                showToast = true
            )
            // Keep app alive by not passing to OS default killer if on background thread,
            // or let user view crash log
        }
    }

    fun recordError(
        context: Context?,
        title: String,
        throwable: Throwable,
        showToast: Boolean = true
    ) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val timestamp = sdf.format(Date())

        val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})"

        val report = CrashReport(
            timestamp = timestamp,
            title = title,
            message = throwable.localizedMessage ?: throwable.javaClass.simpleName,
            stackTrace = stackTrace,
            deviceInfo = deviceInfo
        )

        Log.e(TAG, "Recording error: $title - ${report.message}\n$stackTrace")
        _latestReport.value = report

        if (showToast && context != null) {
            val toastMsg = "$title: ${report.message}"
            mainHandler.post {
                try {
                    Toast.makeText(context.applicationContext, toastMsg, Toast.LENGTH_LONG).show()
                } catch (_: Exception) {}
            }
        }
    }

    fun clearReport() {
        _latestReport.value = null
    }

    fun copyToClipboard(context: Context): Boolean {
        val report = _latestReport.value ?: return false
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Crash Log", report.toFormattedLog())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Crash log copied to clipboard!", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to copy: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
