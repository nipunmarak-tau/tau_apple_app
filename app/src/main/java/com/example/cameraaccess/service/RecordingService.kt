package com.example.cameraaccess.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssMeasurementsEvent
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.core.app.NotificationCompat
import com.example.cameraaccess.MainActivity
import com.example.cameraaccess.controller.Camera2Controller
import com.example.cameraaccess.monitoring.AppHealthMonitor
import com.example.cameraaccess.monitoring.AppIssue
import com.example.cameraaccess.monitoring.IssueSeverity
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class RecordingService : Service(), SensorEventListener {

    private val binder = LocalBinder()
    lateinit var cameraController: Camera2Controller
        private set

    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    
    private var currentHeading: Float = 0f
    private var hasSensorHeading = false

    private var gnssMeasurementsCallback: GnssMeasurementsEvent.Callback? = null
    
    private var gpxWriter: BufferedWriter? = null
    private var gpxFile: File? = null
    private val gpxLock = Any()
    private var gpxPointsSinceFlush: Int = 0
    private val gpxFlushEveryPoints = 10
    @Volatile
    private var gpxHandlerThread: HandlerThread? = null
    @Volatile
    private var gpxHandler: Handler? = null

    private var csvWriter: BufferedWriter? = null
    private var csvFile: File? = null
    private var csvFrameId: Long = 0L
    private var csvStartSensorTimestampNs: Long? = null
    private var csvScanType: String = ""
    private var csvRowsSinceFlush: Int = 0
    private val csvFlushEveryRows = 30
    private val csvLock = Any()
    private var latestCsvMetrics = CsvMetrics()
    private var preparedVideoPath: String? = null
    private var preparedScanType: String = ""
    @Volatile
    private var csvHandlerThread: HandlerThread? = null
    @Volatile
    private var csvHandler: Handler? = null

    private var currentTemperature: Float = 0f

    private data class CsvMetrics(
        val countPerFrame: Int = 0,
        val currentBrightness: Int = 0,
        val targetedBrightness: Int = 0,
        val adjustedBrightness: Int = 0,
        val currentIso: Int = 0,
        val adjustedIso: Int = 0,
        val currentShutterDenom: Int = 0,
        val adjustedShutterDenom: Int = 0
    )

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                currentTemperature = tempTenths / 10.0f
            }
        }
    }

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val TAG = "RecordingService"

    override fun onCreate() {
        super.onCreate()
        ensureCsvWriterThread()
        cameraController = Camera2Controller.getInstance(applicationContext)
        cameraController.onRecordingFrameCaptured = { sensorTimestampNs ->
            val handler = csvHandler ?: ensureCsvWriterThread()
            handler?.post { appendCaptureMetricsRow(sensorTimestampNs) }
                ?: appendCaptureMetricsRow(sensorTimestampNs)
        }
        
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)
    }

    @Synchronized
    private fun ensureCsvWriterThread(): Handler? {
        val existingThread = csvHandlerThread
        val existingHandler = csvHandler
        if (existingThread != null && existingThread.isAlive && existingHandler != null) {
            return existingHandler
        }
        val thread = HandlerThread("csv-writer").also { it.start() }
        val handler = Handler(thread.looper)
        csvHandlerThread = thread
        csvHandler = handler
        return handler
    }

    @Synchronized
    private fun stopCsvWriterThread() {
        val thread = csvHandlerThread
        csvHandler = null
        csvHandlerThread = null
        if (thread != null) {
            thread.quitSafely()
            try {
                thread.join(500)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    @Synchronized
    private fun ensureGpxWriterThread(): Handler? {
        val existingThread = gpxHandlerThread
        val existingHandler = gpxHandler
        if (existingThread != null && existingThread.isAlive && existingHandler != null) {
            return existingHandler
        }
        val thread = HandlerThread("gpx-writer").also { it.start() }
        val handler = Handler(thread.looper)
        gpxHandlerThread = thread
        gpxHandler = handler
        return handler
    }

    @Synchronized
    private fun stopGpxWriterThread() {
        val thread = gpxHandlerThread
        gpxHandler = null
        gpxHandlerThread = null
        if (thread != null) {
            thread.quitSafely()
            try {
                thread.join(500)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> {
                startForegroundService("Recording in progress...")
                startLocationUpdates()
            }
            ACTION_START_PREVIEW -> {
                startForegroundService("Camera preview active")
                startLocationUpdates()
            }
            ACTION_STOP_SERVICE -> {
                stopGpxLogging()
                stopCsvLogging()
                preparedVideoPath = null
                preparedScanType = ""
                cameraController.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundService(contentText: String) {
        val channelId = "recording_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Recording Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Camera Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun startRecording(
        cameraId: String,
        previewSize: Size?,
        videoSize: Size?,
        fpsRange: Range<Int>?,
        iso: Int,
        exposureTimeNs: Long,
        dynamicRangeProfile: Long,
        analysisSize: Size? = null,
        scanType: String = "",
        onStatus: (String) -> Unit
    ): String? {
        val reservedPath = preparedVideoPath ?: prepareRecordingArtifacts(scanType)
        val videoPath = cameraController.startRecording(
            cameraId, previewSize, videoSize, fpsRange, iso, exposureTimeNs,
            dynamicRangeProfile, analysisSize, reservedPath, onStatus
        )
        if (videoPath != null) {
            if (gpxWriter == null) startGpxLogging(videoPath)
            if (csvWriter == null) startCsvLogging(videoPath, scanType)
            preparedVideoPath = null
            preparedScanType = ""
        }
        return videoPath
    }

    fun prepareRecordingArtifacts(scanType: String): String? {
        if (preparedVideoPath != null && preparedScanType == scanType) {
            return preparedVideoPath
        }
        val path = createPreparedVideoPath() ?: return null
        preparedVideoPath = path
        preparedScanType = scanType
        startGpxLogging(path)
        startCsvLogging(path, scanType)
        return path
    }

    private fun createPreparedVideoPath(): String? {
        return try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outDir = File(
                getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                "CameraAccess"
            ).apply { mkdirs() }
            File(outDir, "track_$ts.mp4").absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create prepared video path", e)
            null
        }
    }

    fun stopRecording(): String? {
        val videoPath = cameraController.stopRecording()
        stopGpxLogging()
        stopCsvLogging()
        preparedVideoPath = null
        preparedScanType = ""
        return videoPath
    }

    private fun startGpxLogging(videoPath: String?) {
        stopGpxWriterThread()
        synchronized(gpxLock) {
            gpxWriter?.let {
                try { it.close() } catch (_: Exception) {}
            }
            gpxWriter = null
            gpxFile = null
            gpxPointsSinceFlush = 0
        }

        try {
            val videoFile = videoPath?.let { File(it) }
            val externalDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: throw IOException("External storage unavailable")

            val dir = (videoFile?.parentFile
                ?: File(externalDir, "CameraAccess")
                    ).apply { mkdirs() }

            val baseName = videoFile?.nameWithoutExtension
                ?: SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()).let { "track_$it" }

            val file = File(dir, "$baseName.gpx")
            val writer = BufferedWriter(FileWriter(file))
            synchronized(gpxLock) {
                gpxFile = file
                gpxWriter = writer
                gpxPointsSinceFlush = 0
            }
            
            writer.apply {
                write("""<?xml version="1.0" encoding="UTF-8"?><gpx version="1.1" creator="CameraAccessApp" xmlns="http://www.topografix.com/GPX/1/1"><trk><name>Video Track</name><trkseg>""")
                newLine()
                flush()
            }
            ensureGpxWriterThread()
            startLocationUpdates()
            Log.d(TAG, "GPX logging started: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GPX logging", e)
            AppHealthMonitor.captureException("recording.gpx_start", e)
            gpxWriter = null
            gpxFile = null
        }
    }

    private fun startCsvLogging(videoPath: String?, scanType: String = "") {
        synchronized(csvLock) {
            csvWriter?.let {
                try { it.close() } catch (_: Exception) {}
            }
        }
        try {
            val videoFile = videoPath?.let { File(it) }
            val externalDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: throw IOException("External storage unavailable")

            val dir = (videoFile?.parentFile
                ?: File(externalDir, "CameraAccess")
                    ).apply { mkdirs() }

            val baseName = videoFile?.nameWithoutExtension
                ?: SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()).let { "track_$it" }

            val file = File(dir, "$baseName.csv")
            val writer = BufferedWriter(FileWriter(file))
            synchronized(csvLock) {
                csvFile = file
                csvWriter = writer
                csvFrameId = 0L
                csvStartSensorTimestampNs = null
                csvScanType = scanType
                csvRowsSinceFlush = 0
                latestCsvMetrics = CsvMetrics()
            }

            writer.apply {
                write("frameID,timeStamp,countPerFrame,currentBrightness,targetedBrightness,adjustedBrightness,currentISO,adjustedISO,currentShutter,adjustedShutter,scanType,stabilizationStatus,temperature")
                newLine()
                flush()
            }
            Log.d(TAG, "CSV logging started: ${file.absolutePath} scanType=$scanType")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start CSV logging", e)
            AppHealthMonitor.captureException("recording.csv_start", e)
            synchronized(csvLock) {
                csvWriter = null
                csvFile = null
                csvStartSensorTimestampNs = null
                csvRowsSinceFlush = 0
            }
        }
    }

    fun appendMetricsRow(
        countPerFrame: Int,
        currentBrightness: Int,
        targetedBrightness: Int,
        adjustedBrightness: Int,
        currentIso: Int,
        adjustedIso: Int,
        currentShutterDenom: Int,
        adjustedShutterDenom: Int
    ) {
        synchronized(csvLock) {
            latestCsvMetrics = CsvMetrics(
                countPerFrame = countPerFrame,
                currentBrightness = currentBrightness,
                targetedBrightness = targetedBrightness,
                adjustedBrightness = adjustedBrightness,
                currentIso = currentIso,
                adjustedIso = adjustedIso,
                currentShutterDenom = currentShutterDenom,
                adjustedShutterDenom = adjustedShutterDenom
            )
        }
    }

    private fun appendCaptureMetricsRow(sensorTimestampNs: Long) {
        try {
            synchronized(csvLock) {
                val writer = csvWriter ?: return
                val startTs = csvStartSensorTimestampNs ?: sensorTimestampNs.also {
                    csvStartSensorTimestampNs = it
                }
                val deltaNs = sensorTimestampNs - startTs
                val elapsedSec = if (deltaNs > 0L) deltaNs / 1_000_000_000.0 else 0.0
                val metrics = latestCsvMetrics
                val shutterSec =
                    if (metrics.currentShutterDenom > 0) 1.0 / metrics.currentShutterDenom else 0.0
                val adjustedShutterSec =
                    if (metrics.adjustedShutterDenom > 0) 1.0 / metrics.adjustedShutterDenom else 0.0
                val stabilizationStatus =
                    if (cameraController.isStabilizationActive()) "on" else "off"

                val row = String.format(
                    Locale.US,
                    "%d,%.3f,%d,%d,%d,%d,%d,%d,%.6f,%.6f,%s,%s,%.1f",
                    ++csvFrameId,
                    elapsedSec,
                    metrics.countPerFrame,
                    metrics.currentBrightness,
                    metrics.targetedBrightness,
                    metrics.adjustedBrightness,
                    metrics.currentIso,
                    metrics.adjustedIso,
                    shutterSec,
                    adjustedShutterSec,
                    csvScanType.csvEscape(),
                    stabilizationStatus,
                    currentTemperature
                )
                writer.write(row)
                writer.newLine()
                csvRowsSinceFlush += 1
                if (csvRowsSinceFlush >= csvFlushEveryRows) {
                    writer.flush()
                    csvRowsSinceFlush = 0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing CSV row from capture callback", e)
        }
    }

    fun stopCsvLogging(): String? {
        val path: String?
        val writer: BufferedWriter?
        synchronized(csvLock) {
            path = csvFile?.absolutePath
            writer = csvWriter
            csvWriter = null
            csvFile = null
            csvScanType = ""
            csvStartSensorTimestampNs = null
            csvRowsSinceFlush = 0
            latestCsvMetrics = CsvMetrics()
        }

        if (writer != null) {
            try {
                writer.use { it.flush() }
                Log.d(TAG, "CSV logging stopped: $path")
            } catch (e: Exception) {
                Log.e(TAG, "Error while closing CSV writer", e)
            }
        }
        return path
    }

    fun stopGpxLogging(): String? {
        stopGpxWriterThread()
        val path: String?
        val writer: BufferedWriter?
        synchronized(gpxLock) {
            path = gpxFile?.absolutePath
            writer = gpxWriter
            gpxWriter = null
            gpxFile = null
            gpxPointsSinceFlush = 0
        }

        if (writer != null) {
            try {
                writer.use { w ->
                    try {
                        w.write("</trkseg></trk></gpx>")
                        w.flush()
                    } catch (e: IOException) {
                        Log.e(TAG, "Error writing GPX footer", e)
                    }
                }
                Log.d(TAG, "GPX logging stopped: $path")
            } catch (e: Exception) {
                Log.e(TAG, "Error while closing GPX writer", e)
            }
        }
        return path
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        if (locationCallback != null) return // Already updating

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            gnssMeasurementsCallback = object : GnssMeasurementsEvent.Callback() {
                override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent) {
                    super.onGnssMeasurementsReceived(eventArgs)
                }
            }
            try {
                locationManager.registerGnssMeasurementsCallback(gnssMeasurementsCallback!!, null)
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to register GNSS measurements callback", e)
            AppHealthMonitor.reportIssue(
                AppIssue(
                    key = "gps_gnss_registration_failed",
                    message = "GNSS measurements callback registration failed",
                    severity = IssueSeverity.MEDIUM,
                    area = "gps.permissions"
                )
            )
            }
        }

        accelerometer?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        magnetometer?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        // Get last known location for immediate UI update
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    if (_currentLocation.value == null) {
                        _currentLocation.value = it
                        writeGpxPoint(it)
                    }
                }
            }
        } catch (e: SecurityException) {
             Log.e(TAG, "Failed to get last location", e)
             AppHealthMonitor.reportIssue(
                 AppIssue(
                     key = "gps_last_location_failed",
                     message = "Failed to fetch last known location",
                     severity = IssueSeverity.MEDIUM,
                     area = "gps.location"
                 )
             )
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    _currentLocation.value = location
                    if (hasSensorHeading) {
                        location.bearing = currentHeading
                    }
                    writeGpxPoint(location)
                }
            }

            override fun onLocationAvailability(locationAvailability: LocationAvailability) {
                if (!locationAvailability.isLocationAvailable) {
                    Log.w(TAG, "Location temporarily unavailable; GPX points may be sparse")
                }
            }
        }
        
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                null
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to request location updates", e)
            AppHealthMonitor.reportIssue(
                AppIssue(
                    key = "gps_location_updates_failed",
                    message = "Failed to request location updates",
                    severity = IssueSeverity.HIGH,
                    area = "gps.location"
                )
            )
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssMeasurementsCallback != null) {
            locationManager.unregisterGnssMeasurementsCallback(gnssMeasurementsCallback!!)
            gnssMeasurementsCallback = null
        }
        
        sensorManager.unregisterListener(this)
        hasSensorHeading = false
        _currentLocation.value = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.size)
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.size)
        }

        updateOrientationAngles()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    private fun updateOrientationAngles() {
        val success = SensorManager.getRotationMatrix(
            rotationMatrix,
            null,
            accelerometerReading,
            magnetometerReading
        )
        
        if (success) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            var azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            if (azimuth < 0) {
                azimuth += 360f
            }
            currentHeading = azimuth
            hasSensorHeading = true
        }
    }

    private fun writeGpxPoint(location: Location) {
        val point = GpxPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = location.altitude,
            timeMs = location.time,
            bearing = if (location.hasBearing()) location.bearing else null
        )
        val handler = gpxHandler ?: ensureGpxWriterThread()
        if (handler != null) {
            handler.post { appendGpxPoint(point) }
        } else {
            appendGpxPoint(point)
        }
    }

    private fun appendGpxPoint(point: GpxPoint) {
        try {
            synchronized(gpxLock) {
                val writer = gpxWriter ?: return
                val time = formatGpxTimestamp(point.timeMs)
                val lat = String.format(Locale.US, "%.6f", point.latitude)
                val lon = String.format(Locale.US, "%.6f", point.longitude)
                val ele = String.format(Locale.US, "%.1f", point.altitude)
                val bearing = point.bearing?.let {
                    String.format(Locale.US, "<course>%.1f</course>", it)
                } ?: ""
                writer.write("""<trkpt lat="$lat" lon="$lon"><ele>$ele</ele><time>$time</time>$bearing</trkpt>""")
                writer.newLine()
                gpxPointsSinceFlush += 1
                if (gpxPointsSinceFlush >= gpxFlushEveryPoints) {
                    writer.flush()
                    gpxPointsSinceFlush = 0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing GPX point", e)
        }
    }

    private fun formatGpxTimestamp(timeMs: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(timeMs))
    }

    override fun onDestroy() {
        unregisterReceiver(batteryReceiver)
        stopLocationUpdates()
        stopGpxLogging()
        stopCsvLogging()
        cameraController.onRecordingFrameCaptured = null
        stopCsvWriterThread()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START_RECORDING = "ACTION_START_RECORDING"
        const val ACTION_START_PREVIEW = "ACTION_START_PREVIEW"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
    }

    private data class GpxPoint(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val timeMs: Long,
        val bearing: Float?
    )
}

private fun String.csvEscape(): String =
    if (any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + replace("\"", "\"\"") + "\""
    } else this