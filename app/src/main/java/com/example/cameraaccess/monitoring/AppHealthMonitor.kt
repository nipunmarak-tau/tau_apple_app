package com.example.cameraaccess.monitoring

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.core.content.ContextCompat

enum class IssueSeverity { LOW, MEDIUM, HIGH, CRITICAL }

data class AppIssue(
    val key: String,
    val message: String,
    val severity: IssueSeverity,
    val area: String,
    val attributes: Map<String, String> = emptyMap()
)

/**
 * Central monitoring utility for structured issue reporting and health metrics.
 * This keeps logging format consistent and sends telemetry to New Relic.
 */
object AppHealthMonitor {
    private const val TAG = "AppHealthMonitor"

    @Volatile
    private var initialized = false
    private var appContext: Context? = null

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            initialized = true
            recordMetric("monitoring_initialized", mapOf("sdk" to Build.VERSION.SDK_INT.toString()))
        }
    }

    fun reportIssue(issue: AppIssue) {
        val attrs = mutableMapOf<String, String>()
        attrs["issue_key"] = issue.key
        attrs["severity"] = issue.severity.name
        attrs["area"] = issue.area
        attrs.putAll(issue.attributes)

        logLine("ISSUE", issue.message, attrs)
        recordMetric("issue_reported", attrs + mapOf("message" to issue.message.take(120)))

        // Keep New Relic usage reflection-based to avoid API signature coupling.
        invokeNewRelic("recordHandledException", RuntimeException(issue.message))
    }

    fun captureException(area: String, throwable: Throwable, attributes: Map<String, String> = emptyMap()) {
        val attrs = attributes + mapOf(
            "area" to area,
            "exception" to throwable.javaClass.simpleName
        )
        logLine("EXCEPTION", throwable.message ?: "Unknown", attrs)
        recordMetric("exception_captured", attrs + mapOf("message" to (throwable.message ?: "unknown").take(120)))
        invokeNewRelic("recordHandledException", throwable)
    }

    fun recordMetric(name: String, attributes: Map<String, String> = emptyMap()) {
        val cleanName = name.replace(Regex("[^a-zA-Z0-9_]"), "_").lowercase()
        // Optional New Relic custom event via reflection if available.
        invokeNewRelic("recordCustomEvent", "AppMetric", mapOf("name" to cleanName) + attributes)
    }

    fun recordDeviceHealthSnapshot() {
        val context = appContext ?: return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val freeMb = availableStorageMb()
        val camGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val gpsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        recordMetric(
            "device_health_snapshot",
            mapOf(
                "sdk" to Build.VERSION.SDK_INT.toString(),
                "online" to online.toString(),
                "free_mb" to freeMb.toString(),
                "cam_perm" to camGranted.toString(),
                "mic_perm" to micGranted.toString(),
                "gps_perm" to gpsGranted.toString()
            )
        )
    }

    private fun availableStorageMb(): Long {
        return try {
            val dir = appContext?.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: appContext?.filesDir
                ?: return -1L
            val statFs = StatFs(dir.absolutePath)
            statFs.availableBytes / (1024L * 1024L)
        } catch (_: Exception) {
            -1L
        }
    }

    private fun logLine(type: String, message: String, attributes: Map<String, String>) {
        val attrs = if (attributes.isEmpty()) "" else " ${attributes.entries.joinToString(" ") { "${it.key}=${it.value}" }}"
        Log.i(TAG, "[$type] $message$attrs")
    }

    private fun invokeNewRelic(methodName: String, vararg args: Any?) {
        try {
            val clazz = Class.forName("com.newrelic.agent.android.NewRelic")
            val methods = clazz.methods.filter { it.name == methodName && it.parameterTypes.size == args.size }
            val method = methods.firstOrNull() ?: return
            method.invoke(null, *args)
        } catch (_: Throwable) {
            // Never fail app flow because of observability.
        }
    }
}
