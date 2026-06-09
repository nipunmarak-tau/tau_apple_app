package com.example.cameraaccess.viewmodel

import android.app.Application
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Rect
import android.location.Location
import android.os.IBinder
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.cameraaccess.controller.Camera2Controller
import com.example.cameraaccess.data.model.ApiCache
import com.example.cameraaccess.data.model.SiteBlockCreateRequest
import com.example.cameraaccess.data.model.SiteBlockResponse
import com.example.cameraaccess.data.repositories.RecordingRepository
import com.example.cameraaccess.processing.DetectionResult
import com.example.cameraaccess.service.RecordingService
import com.example.cameraaccess.ui.record.RecordingSegment
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoRecordingViewModel(
    initialController: Camera2Controller,
    private val context: Context,
    application: Application
) : AndroidViewModel(application) {

    private var _controller: Camera2Controller = initialController
    val controller: Camera2Controller get() = _controller

    val uiText = mutableStateOf("Idle")
    val recording = mutableStateOf(false)
    val previewRunning = mutableStateOf(false)
    val paused = mutableStateOf(false)

    private val _recordingDuration = MutableStateFlow(0L)
    val recordingDuration: StateFlow<Long> = _recordingDuration
    private var durationJob: Job? = null

    var lastVideoPath: String? = null
        private set

    private val _lastSegment = MutableStateFlow<RecordingSegment?>(null)
    val lastSegment: StateFlow<RecordingSegment?> = _lastSegment
    
    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location

    private val _detectionResult = MutableStateFlow<DetectionResult?>(null)
    val detectionResult: StateFlow<DetectionResult?> = _detectionResult
    private val _recordingReady = MutableStateFlow(false)
    val recordingReady: StateFlow<Boolean> = _recordingReady
    private val _gpsReadyForRecording = MutableStateFlow(false)
    val gpsReadyForRecording: StateFlow<Boolean> = _gpsReadyForRecording
    private var pipelineReady = false
    private var gpsConsecutiveCount = 0
    private var gpsGateActive = false
    private var gpsTimeoutJob: Job? = null

    // Smoothing state
    private var smoothedConfidence = 0f
    private var smoothedObjectLuma = 0f
    private var smoothedFrameLuma = 0f
    private val alpha = 0.15f // Smoothing factor

    private val repository = RecordingRepository(application)
    private val cache = ApiCache(application)
    private val gson = Gson()

    private var recordingService: RecordingService? = null
    private var isBound = false
    private var warmupJob: Job? = null
    private var lastPreviewVideoSize: Size? = null
    private var lastPreviewFpsRange: Range<Int>? = null
    private var lastPreviewDynamicRange: Long = Camera2Controller.DYNAMIC_RANGE_STANDARD

    private val TAG = "VideoRecordingViewModel"
    private val requiredGpsPointsForRecording = 10
    private val gpsAcquireTimeoutMs = 20_000L

    val selectedDynamicRange = MutableStateFlow(1L) // Default to STANDARD

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingService.LocalBinder
            recordingService = binder.getService()
            _controller = recordingService!!.cameraController
            isBound = true
            Log.d(TAG, "Service Connected")

            applyKiwiInferenceSetting(_controller)
            _controller.onDetectionUpdate = { result ->
                processAndSmoothResult(result)
            }

            viewModelScope.launch {
                recordingService?.currentLocation?.collect { loc ->
                    _location.value = loc
                    handleGpsGateLocationUpdate(loc)
                }
            }

            if (previewRunning.value && !recording.value) {
                pipelineReady = false
                recomputeRecordingReady()
                val warmedVideoSize = lastPreviewVideoSize
                if (warmedVideoSize != null) {
                    startGpsGateForPreview()
                    prepareRecordingPipeline(
                        videoSize = warmedVideoSize,
                        fpsRange = lastPreviewFpsRange,
                        dynamicRangeProfile = lastPreviewDynamicRange
                    )
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            isBound = false
            Log.d(TAG, "Service Disconnected")
        }
    }

    private fun processAndSmoothResult(newResult: DetectionResult) {
        // Smooth the Confidence Score
        smoothedConfidence = (alpha * newResult.confidenceScore) + ((1 - alpha) * smoothedConfidence)
        
        // Smooth the Object Luma (only if identified)
        if (newResult.objectLuma > 0) {
            if (smoothedObjectLuma == 0f) smoothedObjectLuma = newResult.objectLuma.toFloat()
            smoothedObjectLuma = (alpha * newResult.objectLuma) + ((1 - alpha) * smoothedObjectLuma)
        } else {
            smoothedObjectLuma = 0f
        }

        if (newResult.avgFrameLuma > 0) {
            if (smoothedFrameLuma == 0f) smoothedFrameLuma = newResult.avgFrameLuma.toFloat()
            smoothedFrameLuma = (alpha * newResult.avgFrameLuma) + ((1 - alpha) * smoothedFrameLuma)
        } else {
            smoothedFrameLuma = 0f
        }

        _detectionResult.value = newResult.copy(
            confidenceScore = smoothedConfidence,
            objectLuma = smoothedObjectLuma.toInt(),
            avgFrameLuma = smoothedFrameLuma.toInt(),
            frameWidth = newResult.frameWidth,
            frameHeight = newResult.frameHeight
        )
    }

    init {
        Intent(application, RecordingService::class.java).also { intent ->
            application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        applyKiwiInferenceSetting(_controller)
        _controller.onDetectionUpdate = { result ->
            processAndSmoothResult(result)
        }
    }

    /**
     * Fruit scan runs kiwi detection and can meter exposure on detected fruit; other scan types
     * meter the full frame only ([Camera2Controller.runKiwiInferenceEnabled] false).
     */
    fun useKiwiFruitMetering(): Boolean = isFruitScanTypeSelected()

    private fun isFruitScanTypeSelected(): Boolean {
        val submitted = getCachedSiteBlock() ?: return false
        val id = submitted.scanTypeId
        val fetchedJson = cache.getFetchedSiteBlock() ?: return false
        return try {
            val fetched = gson.fromJson(fetchedJson, SiteBlockResponse::class.java)
            val name = fetched.scan_type.firstOrNull { it.id == id }?.name?.trim()
            "Fruit".equals(name, ignoreCase = true)
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving scan type for kiwi metering", e)
            false
        }
    }

    private fun applyKiwiInferenceSetting(controller: Camera2Controller) {
        controller.runKiwiInferenceEnabled = isFruitScanTypeSelected()
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun startPreview(
        cameraId: String,
        previewSize: Size,
        videoSize: Size,
        fpsRange: Range<Int>?,
        iso: Int,
        exposureTimeNs: Long,
        dynamicRangeProfile: Long,
        onStatus: (String) -> Unit
    ) {
        val serviceIntent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START_PREVIEW
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service for preview", e)
        }

        controller.startPreview(
            cameraId = cameraId,
            previewSize = previewSize,
            fpsRange = fpsRange,
            iso = iso,
            exposureTimeNs = exposureTimeNs,
            dynamicRangeProfile = dynamicRangeProfile,
            onStatus = { msg ->
                uiText.value = msg
                onStatus(msg)
            }
        )
        previewRunning.value = true
        recording.value = false
        startGpsGateForPreview()
        lastPreviewVideoSize = videoSize
        lastPreviewFpsRange = fpsRange
        lastPreviewDynamicRange = dynamicRangeProfile
        prepareRecordingPipeline(videoSize, fpsRange, dynamicRangeProfile)
    }

    fun stopCamera() {
        controller.stop()
        previewRunning.value = false
        recording.value = false
        resetRecordingReadinessState()
        warmupJob?.cancel()
        lastPreviewVideoSize = null

        val serviceIntent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP_SERVICE
        }
        context.startService(serviceIntent)
    }

    fun startRecording(
        permissionLauncher: ActivityResultLauncher<Array<String>>,
        cameraId: String,
        previewSize: Size,
        videoSize: Size,
        fpsRange: Range<Int>?,
        iso: Int,
        exposureTimeNs: Long
    ) {
        if (!_recordingReady.value) {
            uiText.value = if (!_gpsReadyForRecording.value) {
                "Waiting for GPS..."
            } else {
                "Preparing camera pipeline. Please wait..."
            }
            return
        }
        val permissions = mutableListOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissions.any { !hasPermission(it) }) {
            uiText.value = "Missing permissions ❌"
            permissionLauncher.launch(permissions.toTypedArray())
            return
        }

        val serviceIntent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START_RECORDING
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            uiText.value = "Service error: ${e.message}"
            return
        }

        uiText.value = "Starting recording…"

        // Dual pipeline: 720p 10-bit YCBCR_P010 for green detection; encoder gets full HDR/10-bit.
        val analysisSize = controller.defaultAnalysisSize

        val scanTypeLabel = resolveScanTypeLabel()

        val videoPath = recordingService?.startRecording(
            cameraId = cameraId,
            previewSize = previewSize,
            videoSize = videoSize,
            fpsRange = fpsRange,
            iso = iso,
            exposureTimeNs = exposureTimeNs,
            dynamicRangeProfile = selectedDynamicRange.value,
            analysisSize = analysisSize,
            scanType = scanTypeLabel
        ) { msg -> uiText.value = msg } ?: controller.startRecording(
            cameraId, previewSize, videoSize, fpsRange, iso, exposureTimeNs,
            selectedDynamicRange.value, analysisSize
        ) { msg -> uiText.value = msg }

        lastVideoPath = videoPath
        recording.value = true
        paused.value = false
        previewRunning.value = true
        gpsGateActive = false
        gpsTimeoutJob?.cancel()
        startDurationTimer()
    }

    fun stopRecording() {
        val videoPath = recordingService?.stopRecording() ?: controller.stopRecording()
        val gpxPath = recordingService?.stopGpxLogging()

        recording.value = false
        paused.value = false
        resetRecordingReadinessState()
        stopDurationTimer()

        controller.stop()
        previewRunning.value = false

        val serviceIntent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP_SERVICE
        }
        context.startService(serviceIntent)

        if (videoPath != null) {
             val finalGpxPath = gpxPath ?: videoPath.replace(".mp4", ".gpx")

            _lastSegment.value = RecordingSegment(videoPath = videoPath, gpxPath = finalGpxPath)
            uiText.value = "Video + GPX Saved ✅"
            getCachedSiteBlock()?.let {
                viewModelScope.launch {
                    repository.saveRecording(videoPath, finalGpxPath, it)
                }
            }
        } else {
            uiText.value = "Stopped recording."
        }
    }

    private fun getCachedSiteBlock(): SiteBlockCreateRequest? {
        return try {
            cache.getSubmitSiteBlock()?.let {
                gson.fromJson(it, SiteBlockCreateRequest::class.java)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing cached site block", e)
            null
        }
    }

    /**
     * Returns a human-readable scan-type label (name from the fetched site-block catalogue,
     * falling back to the numeric id, or empty string if nothing is cached).
     */
    private fun resolveScanTypeLabel(): String {
        val submitted = getCachedSiteBlock() ?: return ""
        val id = submitted.scanTypeId
        val fetchedJson = cache.getFetchedSiteBlock() ?: return id.toString()
        return try {
            val fetched = gson.fromJson(fetchedJson, SiteBlockResponse::class.java)
            fetched.scan_type.firstOrNull { it.id == id }?.name ?: id.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing fetched site block for scan type", e)
            id.toString()
        }
    }

    private fun prepareRecordingPipeline(
        videoSize: Size,
        fpsRange: Range<Int>?,
        dynamicRangeProfile: Long
    ) {
        warmupJob?.cancel()
        pipelineReady = false
        recomputeRecordingReady()
        warmupJob = viewModelScope.launch {
            val scanTypeLabel = resolveScanTypeLabel()
            val artifactsReady = withContext(Dispatchers.IO) {
                recordingService?.prepareRecordingArtifacts(scanTypeLabel) != null
            }
            val detectionReady = withContext(Dispatchers.Default) { controller.warmUpDetection() }
            val recorderReady = withContext(Dispatchers.Default) {
                controller.preWarmRecorder(videoSize, fpsRange, dynamicRangeProfile)
            }
            pipelineReady = artifactsReady && detectionReady && recorderReady
            recomputeRecordingReady()
            if (!pipelineReady && previewRunning.value) {
                uiText.value = "Warm-up failed. Retry preview."
            }
        }
    }

    private fun startGpsGateForPreview() {
        gpsTimeoutJob?.cancel()
        gpsConsecutiveCount = 0
        gpsGateActive = true
        _gpsReadyForRecording.value = false
        recomputeRecordingReady()
        gpsTimeoutJob = viewModelScope.launch {
            delay(gpsAcquireTimeoutMs)
            if (previewRunning.value && !recording.value && !_gpsReadyForRecording.value) {
                stopCamera()
                uiText.value = "GPS data was not found."
            }
        }
    }

    private fun handleGpsGateLocationUpdate(location: Location?) {
        if (!gpsGateActive || recording.value) return
        if (location == null || !location.isValidCoordinate()) {
            gpsConsecutiveCount = 0
            return
        }
        gpsConsecutiveCount += 1
        if (gpsConsecutiveCount >= requiredGpsPointsForRecording) {
            _gpsReadyForRecording.value = true
            gpsGateActive = false
            gpsTimeoutJob?.cancel()
            recomputeRecordingReady()
        }
    }

    private fun recomputeRecordingReady() {
        _recordingReady.value = pipelineReady && _gpsReadyForRecording.value
    }

    private fun resetRecordingReadinessState() {
        pipelineReady = false
        _gpsReadyForRecording.value = false
        _recordingReady.value = false
        gpsConsecutiveCount = 0
        gpsGateActive = false
        gpsTimeoutJob?.cancel()
        gpsTimeoutJob = null
    }

    /**
     * Appends a per-frame metrics row to the CSV that accompanies the current recording.
     * No-op when no recording is in progress or the service isn't bound.
     */
    fun logMetrics(
        countPerFrame: Int,
        currentBrightness: Int,
        targetedBrightness: Int,
        adjustedBrightness: Int,
        currentIso: Int,
        adjustedIso: Int,
        currentShutterDenom: Int,
        adjustedShutterDenom: Int
    ) {
        if (!recording.value) return
        recordingService?.appendMetricsRow(
            countPerFrame,
            currentBrightness,
            targetedBrightness,
            adjustedBrightness,
            currentIso,
            adjustedIso,
            currentShutterDenom,
            adjustedShutterDenom
        )
    }

    fun togglePauseResume() {
        if (!recording.value) return
        if (!paused.value) {
            if (controller.pauseRecording()) {
                paused.value = true
                uiText.value = "Paused ⏸️"
                durationJob?.cancel()
            } else {
                uiText.value = "Pause not supported ❌"
            }
        } else {
            if (controller.resumeRecording()) {
                paused.value = false
                uiText.value = "Recording resumed ▶️"
                startDurationTimer(isResume = true)
            } else {
                uiText.value = "Resume failed ❌"
            }
        }
    }

    private fun startDurationTimer(isResume: Boolean = false) {
        if (!isResume) {
            _recordingDuration.value = 0L
        }
        durationJob?.cancel()
        durationJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                _recordingDuration.value += 1000
            }
        }
    }

    private fun stopDurationTimer() {
        durationJob?.cancel()
        durationJob = null
    }

    override fun onCleared() {
        super.onCleared()
        warmupJob?.cancel()
        gpsTimeoutJob?.cancel()
        try {
            if (isBound) {
                getApplication<Application>().unbindService(serviceConnection)
                isBound = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding service in onCleared", e)
        }
    }
}

private fun Location.isValidCoordinate(): Boolean {
    val lat = latitude
    val lon = longitude
    return lat in -90.0..90.0 && lon in -180.0..180.0
}
