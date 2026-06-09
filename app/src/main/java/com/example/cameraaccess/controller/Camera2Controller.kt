package com.example.cameraaccess.controller

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.media.ImageReader
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Rational
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.example.cameraaccess.monitoring.AppHealthMonitor
import com.example.cameraaccess.monitoring.AppIssue
import com.example.cameraaccess.monitoring.IssueSeverity
import com.example.cameraaccess.processing.DetectionResult
import com.example.cameraaccess.processing.KiwiDetectionProcessor
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Custom exceptions for Camera2Controller domain errors
 */
sealed class CameraControllerException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class CameraAccessException(message: String, cause: Throwable? = null) : CameraControllerException(message, cause)
    class RecordingException(message: String, cause: Throwable? = null) : CameraControllerException(message, cause)
    class ConfigurationException(message: String) : CameraControllerException(message)
}

class Camera2Controller private constructor(private val appContext: Context) {
    private val TAG = "CAM2"

    companion object {
        @Volatile
        private var INSTANCE: Camera2Controller? = null

        fun getInstance(context: Context): Camera2Controller {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Camera2Controller(context.applicationContext).also { INSTANCE = it }
            }
        }

        const val DYNAMIC_RANGE_STANDARD = 1L
        const val DYNAMIC_RANGE_SCENE_HDR = -1L

        /**
         * Legacy scene-based HDR path (CONTROL_SCENE_MODE_HDR). Not a DynamicRangeProfiles id.
         */
        fun isSceneHdr(profile: Long): Boolean = profile == DYNAMIC_RANGE_SCENE_HDR

        /**
         * True for API 33+ pipeline HDR (HDR10 / HLG10 / HDR10+ etc.).
         * Manual exposure (AE off + [SENSOR_SENSITIVITY]/[SENSOR_EXPOSURE_TIME]) is fully
         * supported for these profiles: the capture stays 10-bit HDR because the encoder
         * surface's [OutputConfiguration.setDynamicRangeProfile] retains the HDR tag — AE
         * mode is an orthogonal control. ISO/shutter are tuned dynamically by the UI loop.
         */
        fun isTenBitHdrProfile(profile: Long): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
            if (profile <= DYNAMIC_RANGE_STANDARD) return false
            if (profile == DYNAMIC_RANGE_SCENE_HDR) return false
            return true
        }
    }

    private var textureView: TextureView? = null
    fun setTextureView(tv: TextureView?) { 
        if (textureView !== tv) {
            try { textureViewSurface?.release() } catch (_: Exception) {}
            textureViewSurface = null
        }
        textureView = tv 
        Log.d(TAG, "TextureView set: $tv")
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var lastManualAeRejectLogMs: Long = 0L
    private var aeCompensationRange: Range<Int>? = null
    private var aeCompensationStep: Rational? = null
    private var lastAppliedAeCompensation: Int? = null
    private var lastAeCompensationUpdateMs: Long = 0L
    private var sensorActiveArray: Rect? = null
    private var maxAeRegions: Int = 0
    private var currentDynamicRangeProfile: Long = DYNAMIC_RANGE_STANDARD
    private var availableDynamicRangeProfiles: Set<Long> = emptySet()
    private var stabilizationEnabled: Boolean = false
    private var supportsVideoStabilization: Boolean = false
    private var supportsOpticalStabilization: Boolean = false
    private var lastCameraErrorRetryMs: Long = 0L
    private var cameraErrorRetryCount: Int = 0
    private val maxCameraErrorRetries = 1
    private val cameraErrorRetryCooldownMs = 1500L

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var analysisThread: HandlerThread? = null
    private var analysisHandler: Handler? = null

    private var mediaRecorder: android.media.MediaRecorder? = null
    private var currentVideoPath: String? = null
    private var isRecording: Boolean = false
    private var isPaused: Boolean = false
    private var recordingStartTime: Long = 0L

    val recordingActive: Boolean get() = isRecording

    // Persistent SurfaceTexture to keep camera alive in background
    private var persistentSurfaceTexture: SurfaceTexture? = null
    private var persistentPreviewSurface: Surface? = null
    private var textureViewSurface: Surface? = null

    // ImageReader for processing (preview / non-record)
    private var imageReader: ImageReader? = null

    /**
     * Dedicated 8-bit YUV pipeline at fixed resolution (e.g. 720p) used only during recording.
     * Encoder surface receives original HDR/10-bit; this stream is for green detection only.
     */
    private var analysisImageReader: ImageReader? = null
    private val detectionProcessor = KiwiDetectionProcessor(appContext)
    /** When false (non–Fruit scan), skips TFLite and only computes full-frame luma for AE. */
    @Volatile
    var runKiwiInferenceEnabled: Boolean = true
    var onDetectionUpdate: ((DetectionResult) -> Unit)? = null
    @Volatile
    var onRecordingFrameCaptured: ((Long) -> Unit)? = null
    private val isDetectionRunning = AtomicBoolean(false)
    private val isCameraOperational = AtomicBoolean(false)
    private var lastDetectionAtMs: Long = 0L
    private var lastInferenceAtMs: Long = 0L
    private var lastInferenceResult: DetectionResult? = null
    private var consecutiveAnalyzerErrors: Int = 0
    private var inferenceAutoDisabled: Boolean = false
    private var lastAnalyzerHealthLogMs: Long = 0L
    private val analyzerFramesReceived = AtomicLong(0L)
    private val analyzerFramesProcessed = AtomicLong(0L)
    private val analyzerFramesDroppedThrottle = AtomicLong(0L)
    private val analyzerFramesDroppedBusy = AtomicLong(0L)
    private val analyzerFramesDroppedInvalid = AtomicLong(0L)
    private val analyzerInferenceRuns = AtomicLong(0L)
    private val analyzerProcessingErrors = AtomicLong(0L)
    private val analyzerTotalProcessingNs = AtomicLong(0L)
    private val analyzerImageCloseErrors = AtomicLong(0L)
    private data class PreviewRestartConfig(
        val cameraId: String,
        val previewSize: Size,
        val fpsRange: Range<Int>?,
        val iso: Int,
        val exposureTimeNs: Long,
        val dynamicRangeProfile: Long,
        val onStatus: (String) -> Unit
    )
    private var lastPreviewRestartConfig: PreviewRestartConfig? = null
    private val recordCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            if (!isRecording) return
            val sensorTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            onRecordingFrameCaptured?.invoke(sensorTimestampNs)
        }
    }
    
    // Throttle luma sampling to 30 FPS for smooth exposure control
    private val lumaIntervalMs = 33L 
    // Throttle expensive CV inference to 15 FPS to save battery/heat
    private val inferenceIntervalMs = 66L
    @Volatile
    private var detectionWarmupCompleted: Boolean = false
    @Volatile
    private var recorderWarmupSignature: String? = null

    private fun startBg() {
        if (bgThread != null) return
        bgThread = HandlerThread("cam2-bg").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
        analysisThread = HandlerThread("cam2-analysis").also { it.start() }
        val handler = Handler(analysisThread!!.looper)
        analysisHandler = handler

        // Warm up the TFLite interpreter on the analysis thread while the user is still
        // on the preview screen. On first install the GPU driver compiles shaders here
        // (1–3 s) instead of mid-recording, so the video encoder is never starved.
        if (!detectionWarmupCompleted) {
            handler.post {
                try {
                    detectionProcessor.warmUp()
                    detectionWarmupCompleted = true
                } catch (e: Exception) {
                    Log.w(TAG, "Detection warm-up error (non-fatal)", e)
                }
            }
        }
    }

    fun warmUpDetection(): Boolean {
        return try {
            detectionProcessor.warmUp()
            detectionWarmupCompleted = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "Detection warm-up failed", e)
            false
        }
    }

    fun preWarmRecorder(
        videoSize: Size?,
        fpsRange: Range<Int>?,
        dynamicRangeProfile: Long
    ): Boolean {
        if (videoSize == null) return false
        val signature = "${videoSize.width}x${videoSize.height}:${fpsRange?.upper ?: 30}:$dynamicRangeProfile"
        if (recorderWarmupSignature == signature) return true

        val warmupFile = try {
            val outDir = File(
                appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                "CameraAccess"
            ).apply { mkdirs() }
            File(outDir, ".encoder_warmup.mp4")
        } catch (e: Exception) {
            Log.w(TAG, "Recorder warm-up path creation failed", e)
            return false
        }

        val recordDynamicProfile = if (isSceneHdr(dynamicRangeProfile)) {
            preferredDualPipelineHdrProfile() ?: dynamicRangeProfile
        } else {
            dynamicRangeProfile
        }

        val mr = android.media.MediaRecorder()
        return try {
            mr.setVideoSource(android.media.MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            mr.setOutputFile(warmupFile.absolutePath)

            val bitrate = when {
                isTenBitHdrProfile(recordDynamicProfile) && videoSize.width >= 1920 -> 25_000_000
                videoSize.width >= 1920 -> 15_000_000
                else -> 8_000_000
            }
            mr.setVideoEncodingBitRate(bitrate)
            mr.setVideoFrameRate((fpsRange?.upper ?: 30).coerceIn(24, 60))
            mr.setVideoSize(videoSize.width, videoSize.height)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                recordDynamicProfile > DYNAMIC_RANGE_STANDARD
            ) {
                mr.setVideoEncoder(android.media.MediaRecorder.VideoEncoder.HEVC)
                if (isTenBitHdrProfile(recordDynamicProfile) &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ) {
                    val requestedProfile = preferredHevcHdrProfile(recordDynamicProfile)
                    val (actualProfile, actualLevel) =
                        selectHevcProfileLevel(videoSize, requestedProfile)
                    mr.setVideoEncodingProfileLevel(actualProfile, actualLevel)
                }
            } else {
                mr.setVideoEncoder(android.media.MediaRecorder.VideoEncoder.H264)
            }

            mr.prepare()
            recorderWarmupSignature = signature
            true
        } catch (e: Exception) {
            Log.w(TAG, "Recorder warm-up failed", e)
            false
        } finally {
            try { mr.reset() } catch (_: Exception) {}
            try { mr.release() } catch (_: Exception) {}
            try {
                if (warmupFile.exists()) warmupFile.delete()
            } catch (_: Exception) {}
        }
    }

    private fun stopBg() {
        analysisThread?.let { thread ->
            thread.quitSafely()
            try {
                thread.join(1000)
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted while stopping analysis thread", e)
                Thread.currentThread().interrupt()
            }
        }
        analysisThread = null
        analysisHandler = null

        bgThread?.let { thread ->
            thread.quitSafely()
            try {
                thread.join(1000)
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted while stopping background thread", e)
                Thread.currentThread().interrupt()
            }
        }
        bgThread = null
        bgHandler = null
    }

    fun getSupportedDynamicRangeProfiles(cameraId: String): Set<Long> {
        val result = mutableSetOf<Long>()
        val cm = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val characteristics = cm.getCameraCharacteristics(cameraId)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val profiles = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                profiles?.supportedProfiles?.let { result.addAll(it) }
            }
            
            val sceneModes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
            if (sceneModes?.contains(CameraMetadata.CONTROL_SCENE_MODE_HDR) == true) {
                result.add(DYNAMIC_RANGE_SCENE_HDR)
            }
            
            Log.d(TAG, "Supported HDR profiles for $cameraId: $result")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting dynamic range profiles", e)
        }
        availableDynamicRangeProfiles = result
        return result
    }

    private fun preferredDualPipelineHdrProfile(): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return when {
            availableDynamicRangeProfiles.contains(DynamicRangeProfiles.HDR10_PLUS) -> DynamicRangeProfiles.HDR10_PLUS
            availableDynamicRangeProfiles.contains(DynamicRangeProfiles.HDR10) -> DynamicRangeProfiles.HDR10
            availableDynamicRangeProfiles.contains(DynamicRangeProfiles.HLG10) -> DynamicRangeProfiles.HLG10
            else -> null
        }
    }

    private fun getStablePreviewSurface(width: Int, height: Int): Surface {
        if (persistentSurfaceTexture == null) {
            persistentSurfaceTexture = SurfaceTexture(0).apply {
                detachFromGLContext()
            }
        }
        persistentSurfaceTexture!!.setDefaultBufferSize(width, height)
        if (persistentPreviewSurface == null) {
            persistentPreviewSurface = Surface(persistentSurfaceTexture)
        }
        return persistentPreviewSurface!!
    }

    private fun getTextureViewSurface(width: Int, height: Int): Surface? {
        val surfaceTexture = textureView?.surfaceTexture ?: return null
        surfaceTexture.setDefaultBufferSize(width, height)
        val existing = textureViewSurface
        return if (existing == null || !existing.isValid) {
            try {
                existing?.release()
            } catch (_: Exception) {}
            Surface(surfaceTexture).also { textureViewSurface = it }
        } else {
            existing
        }
    }

    fun startPreview(
        cameraId: String,
        previewSize: Size?,
        fpsRange: Range<Int>?,
        iso: Int,
        exposureTimeNs: Long,
        dynamicRangeProfile: Long = DYNAMIC_RANGE_STANDARD,
        onStatus: (String) -> Unit
    ) {
        currentDynamicRangeProfile = dynamicRangeProfile
        if (previewSize == null) {
            onStatus("No preview size available.")
            return
        }
        lastPreviewRestartConfig = PreviewRestartConfig(
            cameraId = cameraId,
            previewSize = previewSize,
            fpsRange = fpsRange,
            iso = iso,
            exposureTimeNs = exposureTimeNs,
            dynamicRangeProfile = dynamicRangeProfile,
            onStatus = onStatus
        )
        openCamera(cameraId, onStatus) {
            createPreviewSession(previewSize, fpsRange, iso, exposureTimeNs, dynamicRangeProfile, onStatus)
        }
    }

    fun updateExposure(iso: Int, exposureTimeNs: Long) {
        if (!isCameraOperational.get()) return
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        val handler = bgHandler ?: return
        val captureCallback = if (isRecording) recordCaptureCallback else null

        try {
            val aeMode = builder.get(CaptureRequest.CONTROL_AE_MODE)
            if (aeMode != null && aeMode != CaptureRequest.CONTROL_AE_MODE_OFF) {
                val now = System.currentTimeMillis()
                if (now - lastManualAeRejectLogMs > 2000L) {
                    lastManualAeRejectLogMs = now
                    Log.w(TAG, "Manual AE update rejected: CONTROL_AE_MODE=$aeMode (expected OFF).")
                }
                return
            }
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeNs)
            session.setRepeatingRequest(builder.build(), captureCallback, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update exposure", e)
        }
    }

    fun updateExposureCompensation(errorStops: Double) {
        if (!isCameraOperational.get()) return
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        val handler = bgHandler ?: return
        val captureCallback = if (isRecording) recordCaptureCallback else null
        val range = aeCompensationRange ?: return
        val step = aeCompensationStep ?: return

        if (step.numerator == 0) return
        val stepEv = step.toDouble()
        if (stepEv <= 0.0) return

        // Convert stop error to AE compensation steps, adding a +1 EV bias to brighten the image
        val evBias = 1.0
        val targetSteps = ((errorStops + evBias) / stepEv).toInt()
        val clamped = targetSteps.coerceIn(range.lower, range.upper)

        val now = System.currentTimeMillis()
        if (lastAppliedAeCompensation == clamped && now - lastAeCompensationUpdateMs < 500L) return
        if (now - lastAeCompensationUpdateMs < 120L) return

        try {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clamped)
            session.setRepeatingRequest(builder.build(), captureCallback, handler)
            lastAppliedAeCompensation = clamped
            lastAeCompensationUpdateMs = now
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update AE compensation", e)
        }
    }

    fun isStabilizationSupported(): Boolean {
        return supportsVideoStabilization || supportsOpticalStabilization
    }

    /** True when stabilization is requested and the camera reports support (video or optical). */
    fun isStabilizationActive(): Boolean {
        return stabilizationEnabled && isStabilizationSupported()
    }

    fun setStabilizationEnabled(enabled: Boolean): Boolean {
        stabilizationEnabled = enabled
        val session = captureSession
        val builder = previewRequestBuilder
        // If no active session yet (e.g. toggled before preview starts), keep the
        // requested state and apply it when the next request builder is created.
        if (session == null || builder == null) {
            return true
        }

        if (enabled && !isStabilizationSupported()) {
            return false
        }

        if (session != null && builder != null) {
            try {
                applyStabilizationControls(builder)
                session.setRepeatingRequest(builder.build(), null, bgHandler)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update stabilization state", e)
                return false
            }
        }
        return true
    }

    /** Default analyzer size: 720p pipeline for green detection while recording. */
    val defaultAnalysisSize: Size get() = Size(1280, 720)

    fun startRecording(
        cameraId: String,
        previewSize: Size?,
        videoSize: Size?,
        fpsRange: Range<Int>?,
        iso: Int,
        exposureTimeNs: Long,
        dynamicRangeProfile: Long = DYNAMIC_RANGE_STANDARD,
        analysisSize: Size? = null,
        outputPath: String? = null,
        onStatus: (String) -> Unit
    ): String? {
        currentDynamicRangeProfile = dynamicRangeProfile
        // Do not auto-restart on camera device error while recording.
        lastPreviewRestartConfig = null
        if (previewSize == null || videoSize == null) {
            onStatus("Missing preview/video size.")
            return null
        }

        val outFile = outputPath?.let { File(it) } ?: run {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outDir = File(
                appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                "CameraAccess"
            ).apply { mkdirs() }
            File(outDir, "track_$ts.mp4")
        }
        currentVideoPath = outFile.absolutePath

        openCamera(cameraId, onStatus) {
            try {
                createRecordSession(
                    previewSize = previewSize,
                    videoSize = videoSize,
                    fpsRange = fpsRange,
                    iso = iso,
                    exposureTimeNs = exposureTimeNs,
                    dynamicRangeProfile = dynamicRangeProfile,
                    analysisSize = analysisSize ?: defaultAnalysisSize,
                    outFile = outFile,
                    onStatus = onStatus
                )
            } catch (e: Exception) {
                Log.e(TAG, "Recording setup failed", e)
                onStatus("Recording setup failed: ${e.message}")
            }
        }

        return currentVideoPath
    }

    fun pauseRecording(): Boolean {
        if (!isRecording || isPaused) return false
        return try {
            mediaRecorder?.pause()
            isPaused = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause recording", e)
            false
        }
    }

    fun resumeRecording(): Boolean {
        if (!isRecording || !isPaused) return false
        return try {
            mediaRecorder?.resume()
            isPaused = false
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume recording", e)
            false
        }
    }

    fun stopRecording(): String? {
        if (!isRecording) return currentVideoPath

        // Ensure minimum recording duration (1 second) to prevent 0-byte files on Android 15
        val elapsed = System.currentTimeMillis() - recordingStartTime
        if (elapsed < 1000) {
            Log.d(TAG, "Recording too short ($elapsed ms), waiting...")
            try { Thread.sleep(1000 - elapsed) } catch (e: Exception) {}
        }

        isRecording = false
        isPaused = false

        val path = currentVideoPath

        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording failed", e)
            // If stop fails, the file might be corrupted/0 bytes.
        } finally {
            mediaRecorder = null
        }

        // Tear down analyzer pipeline; preview restart will recreate preview ImageReader if needed
        try {
            analysisImageReader?.setOnImageAvailableListener(null, null)
            analysisImageReader?.close()
        } catch (_: Exception) {}
        analysisImageReader = null

        return path
    }

    private fun openCamera(
        cameraId: String,
        onStatus: (String) -> Unit,
        afterOpen: () -> Unit
    ) {
        startBg()

        val cm = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onStatus("CAMERA permission missing.")
            return
        }

        if (cameraDevice != null) {
            afterOpen()
            return
        }

        onStatus("Opening cameraId=$cameraId …")
        try {
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    isCameraOperational.set(true)
                    try {
                        val characteristics = cm.getCameraCharacteristics(cameraId)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            availableDynamicRangeProfiles =
                                characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                                    ?.supportedProfiles
                                    ?.toSet()
                                    ?: emptySet()
                        }
                        aeCompensationRange =
                            characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                        aeCompensationStep =
                            characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                        sensorActiveArray =
                            characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                        maxAeRegions =
                            characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
                        val videoStabilizationModes =
                            characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                                ?: intArrayOf()
                        supportsVideoStabilization =
                            videoStabilizationModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                        val opticalStabilizationModes =
                            characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                                ?: intArrayOf()
                        supportsOpticalStabilization =
                            opticalStabilizationModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
                        Log.d(
                            TAG,
                            "AE caps range=$aeCompensationRange step=$aeCompensationStep " +
                                "maxAeRegions=$maxAeRegions activeArray=$sensorActiveArray " +
                                "videoStab=$supportsVideoStabilization opticalStab=$supportsOpticalStabilization"
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to read AE compensation capabilities", e)
                    }
                    onStatus("Camera opened.")
                    afterOpen()
                }

                override fun onDisconnected(device: CameraDevice) {
                    isCameraOperational.set(false)
                    onStatus("Camera disconnected.")
                    stop()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    isCameraOperational.set(false)
                    val msg = when (error) {
                        ERROR_CAMERA_IN_USE -> "Camera in use"
                        ERROR_MAX_CAMERAS_IN_USE -> "Too many cameras in use"
                        ERROR_CAMERA_DISABLED -> "Camera disabled (Error 3)"
                        ERROR_CAMERA_DEVICE -> "Camera device error"
                        ERROR_CAMERA_SERVICE -> "Camera service error"
                        else -> "Unknown camera error ($error)"
                    }
                    Log.e(TAG, "Camera error: $msg")
                    onStatus("Camera error: $msg")
                    AppHealthMonitor.reportIssue(
                        AppIssue(
                            key = "camera_open_error",
                            message = "Camera open error: $msg",
                            severity = IssueSeverity.CRITICAL,
                            area = "camera.open",
                            attributes = mapOf("error_code" to error.toString())
                        )
                    )
                    val recovered = if (error == ERROR_CAMERA_DEVICE) {
                        attemptCameraDeviceRecovery(msg)
                    } else {
                        false
                    }
                    if (!recovered) {
                        stop()
                    }
                }
            }, bgHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
            onStatus("Failed to open camera: ${e.message}")
            AppHealthMonitor.captureException("camera.open", e)
        }
    }

    /**
     * Build [OutputConfiguration] list. For 10-bit HDR record we apply the HDR profile to every
     * surface listed in [hdrSurfaces] (encoder + P010 analyzer reader) and keep STANDARD for the
     * TextureView preview surface (SurfaceTexture can't consume 10-bit directly).
     * If the device rejects mixed profiles, caller should retry with a single profile or without reader.
     */
    private fun outputConfigurationsForSurfaces(
        surfaces: List<Surface>,
        dynamicRangeProfile: Long,
        hdrSurfaces: Set<Surface> = emptySet()
    ): List<OutputConfiguration> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return surfaces.map { OutputConfiguration(it) }
        }
        if (!isTenBitHdrProfile(dynamicRangeProfile) || hdrSurfaces.isEmpty()) {
            if (dynamicRangeProfile > DYNAMIC_RANGE_STANDARD && !isSceneHdr(dynamicRangeProfile)) {
                return surfaces.map { s ->
                    OutputConfiguration(s).apply { this.dynamicRangeProfile = dynamicRangeProfile }
                }
            }
            return surfaces.map { OutputConfiguration(it).apply { this.dynamicRangeProfile = DYNAMIC_RANGE_STANDARD } }
        }
        return surfaces.map { s ->
            OutputConfiguration(s).apply {
                this.dynamicRangeProfile =
                    if (hdrSurfaces.contains(s)) dynamicRangeProfile else DYNAMIC_RANGE_STANDARD
            }
        }
    }

    private fun applyCaptureRequestForProfile(
        builder: CaptureRequest.Builder,
        dynamicRangeProfile: Long,
        iso: Int,
        exposureTimeNs: Long,
        fpsRange: Range<Int>?,
        afMode: Int
    ) {
        when {
            isSceneHdr(dynamicRangeProfile) -> {
                builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
                builder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_HDR)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeNs)
            }
            isTenBitHdrProfile(dynamicRangeProfile) -> {
                // Manual exposure for HDR: disable the HAL's auto-exposure and apply the
                // caller-supplied ISO/shutter. The HDR tonemap + 10-bit pipeline continue to
                // run because the OutputConfiguration keeps its HDR DynamicRangeProfile.
                builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeNs)
            }
            else -> {
                builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeNs)
            }
        }
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT)
        if (fpsRange != null) builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
        
        // Permanent exposure bias
        aeCompensationRange?.let { range ->
            aeCompensationStep?.let { step ->
                if (step.numerator != 0 && step.toDouble() > 0) {
                    val biasSteps = (1.0 / step.toDouble()).toInt()
                    builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, biasSteps.coerceIn(range.lower, range.upper))
                }
            }
        }

        // Hint the HAL to avoid mains-frequency banding. Documented as effective when
        // AE_MODE != OFF, but many OEM HALs honour it as a sensor-timing hint even in
        // manual AE — it's harmless to keep set unconditionally.
        builder.set(
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
        )
        applyStabilizationControls(builder)
        applyMeteringRegions(builder)
    }

    private fun applyMeteringRegions(builder: CaptureRequest.Builder) {
        if (maxAeRegions > 0) {
            sensorActiveArray?.let { activeArray ->
                val width = activeArray.width()
                val height = activeArray.height()
                // Meter on the bottom half where fruits are likely to be
                val x = activeArray.left + width / 4
                val y = activeArray.top + height / 2
                val w = width / 2
                val h = height / 2
                val aeRegion = android.hardware.camera2.params.MeteringRectangle(
                    x, y, w, h, android.hardware.camera2.params.MeteringRectangle.METERING_WEIGHT_MAX
                )
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(aeRegion))
            }
        }
    }

    private fun applyStabilizationControls(builder: CaptureRequest.Builder) {
        val enable = stabilizationEnabled && isStabilizationSupported()
        try {
            if (supportsVideoStabilization) {
                builder.set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    if (enable) {
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    } else {
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                    }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to set video stabilization mode", e)
        }

        try {
            if (supportsOpticalStabilization) {
                val opticalEnable = enable && !supportsVideoStabilization
                builder.set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    if (opticalEnable) {
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    } else {
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                    }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to set optical stabilization mode", e)
        }
    }

    private fun createPreviewSession(
        previewSize: Size,
        fpsRange: Range<Int>?,
        iso: Int,
        exposureTimeNs: Long,
        dynamicRangeProfile: Long,
        onStatus: (String) -> Unit
    ) {
        val device = cameraDevice ?: return
        resetAnalyzerMetrics()
        
        // ImageReader for processing (10-bit P010 when supported, so the preview session stays in
        // HDR for green detection; TextureView surface keeps STANDARD profile.)
        setupImageReader(previewSize)
        
        val stableSurface = getStablePreviewSurface(previewSize.width, previewSize.height)
        val surfaces = mutableListOf(stableSurface)
        
        getTextureViewSurface(previewSize.width, previewSize.height)?.let { surfaces.add(it) }
        
        imageReader?.surface?.let { surfaces.add(it) }

        try {
            previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                surfaces.forEach { addTarget(it) }
                // The capture request profile drives AE/ISO policy; 10-bit HDR uses HAL AE.
                // Scene HDR (legacy) still needs an SDR capture-request behaviour.
                val previewProfile =
                    if (isSceneHdr(dynamicRangeProfile)) {
                        DYNAMIC_RANGE_STANDARD
                    } else {
                        dynamicRangeProfile
                    }
                applyCaptureRequestForProfile(
                    this, previewProfile, iso, exposureTimeNs, fpsRange,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
            }

            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    isCameraOperational.set(true)
                    cameraErrorRetryCount = 0
                    try {
                        session.setRepeatingRequest(previewRequestBuilder!!.build(), null, bgHandler)
                        onStatus("Preview running")
                    } catch (e: Exception) {
                        isCameraOperational.set(false)
                        Log.e(TAG, "Failed to start preview repeating request", e)
                        onStatus("Preview failed: ${e.message}")
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    isCameraOperational.set(false)
                    AppHealthMonitor.reportIssue(
                        AppIssue(
                            key = "preview_session_config_failed",
                            message = "Preview session configuration failed",
                            severity = IssueSeverity.HIGH,
                            area = "camera.preview",
                            attributes = mapOf("dynamic_profile" to dynamicRangeProfile.toString())
                        )
                    )
                    onStatus("Preview session configure failed.")
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                dynamicRangeProfile > DYNAMIC_RANGE_STANDARD && !isTenBitHdrProfile(dynamicRangeProfile)
            ) {
                val configs = surfaces.map { s ->
                    OutputConfiguration(s).apply { this.dynamicRangeProfile = dynamicRangeProfile }
                }
                device.createCaptureSessionByOutputConfigurations(configs, callback, bgHandler)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                isTenBitHdrProfile(dynamicRangeProfile)
            ) {
                // Mixed session while HDR is selected: P010 ImageReader must carry the HDR
                // profile (10-bit format), TextureView/stable preview surfaces stay STANDARD.
                val readerSurface = imageReader?.surface
                val configs = surfaces.map { s ->
                    OutputConfiguration(s).apply {
                        this.dynamicRangeProfile =
                            if (s == readerSurface) dynamicRangeProfile else DYNAMIC_RANGE_STANDARD
                    }
                }
                device.createCaptureSessionByOutputConfigurations(configs, callback, bgHandler)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                isSceneHdr(dynamicRangeProfile)
            ) {
                val configs = surfaces.map { OutputConfiguration(it).apply { this.dynamicRangeProfile = DYNAMIC_RANGE_STANDARD } }
                device.createCaptureSessionByOutputConfigurations(configs, callback, bgHandler)
            } else {
                device.createCaptureSession(surfaces, callback, bgHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating preview session", e)
            onStatus("Preview session failed: ${e.message}")
            AppHealthMonitor.captureException(
                area = "camera.preview",
                throwable = e,
                attributes = mapOf("dynamic_profile" to dynamicRangeProfile.toString())
            )
        }
    }

    private fun codecProfileLevelFieldOrNull(name: String): Int? {
        return try {
            val field = MediaCodecInfo.CodecProfileLevel::class.java.getField(name)
            field.getInt(null)
        } catch (_: Exception) {
            null
        }
    }

    private fun preferredHevcHdrProfile(dynamicRangeProfile: Long): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
        }
        return when (dynamicRangeProfile) {
            DynamicRangeProfiles.HDR10_PLUS ->
                codecProfileLevelFieldOrNull("HEVCProfileMain10HDR10Plus")
                    ?: codecProfileLevelFieldOrNull("HEVCProfileMain10HDR10")
                    ?: MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
            DynamicRangeProfiles.HDR10 ->
                codecProfileLevelFieldOrNull("HEVCProfileMain10HDR10")
                    ?: MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
            DynamicRangeProfiles.HLG10 ->
                codecProfileLevelFieldOrNull("HEVCProfileMain10HLG")
                    ?: MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
            else -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun selectHevcProfileLevel(videoSize: Size, preferredProfile: Int): Pair<Int, Int> {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val preferredLevels = listOf(
            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62,
            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel61,
            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel6,
            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52,
            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51,
            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5,
            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41,
            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4
        )

        val fallbackBySize = when {
            videoSize.width >= 3840 || videoSize.height >= 2160 -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52
            videoSize.width >= 1920 || videoSize.height >= 1080 -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5
            else -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4
        }

        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder) continue
            val types = codecInfo.supportedTypes
            if (!types.contains(MediaFormat.MIMETYPE_VIDEO_HEVC)) continue
            val caps = try {
                codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            } catch (_: Exception) {
                continue
            }
            val preferredLevelsForProfile = caps.profileLevels
                .filter { it.profile == preferredProfile }
                .map { it.level }
                .toSet()
            if (preferredLevelsForProfile.isNotEmpty()) {
                for (level in preferredLevels) {
                    if (preferredLevelsForProfile.contains(level)) return preferredProfile to level
                }
                return preferredProfile to (preferredLevelsForProfile.maxOrNull() ?: fallbackBySize)
            }

            // Fallback to generic Main10 if HDR-signaling profile is not exposed.
            val main10Levels = caps.profileLevels
                .filter { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
                .map { it.level }
                .toSet()
            if (main10Levels.isEmpty()) continue
            for (level in preferredLevels) {
                if (main10Levels.contains(level)) {
                    return MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 to level
                }
            }
            return MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 to (main10Levels.maxOrNull()
                ?: fallbackBySize)
        }

        return preferredProfile to fallbackBySize
    }

    /**
     * Recording session: 3 surfaces only — TextureView (preview), MediaRecorder, analysis ImageReader.
     * No stable surface. Fallback: retry without analysis ImageReader if session configure fails (green detection off).
     */
    private fun createRecordSession(
        previewSize: Size,
        videoSize: Size,
        fpsRange: Range<Int>?,
        iso: Int,
        exposureTimeNs: Long,
        dynamicRangeProfile: Long,
        analysisSize: Size,
        outFile: File,
        onStatus: (String) -> Unit
    ) {
        val device = cameraDevice ?: return
        resetAnalyzerMetrics()
        val recordDynamicProfile = if (isSceneHdr(dynamicRangeProfile)) {
            preferredDualPipelineHdrProfile() ?: dynamicRangeProfile
        } else {
            dynamicRangeProfile
        }
        currentDynamicRangeProfile = recordDynamicProfile

        try { captureSession?.close() } catch (e: Exception) {}
        captureSession = null

        // Dual pipeline: (1) MediaRecorder surface = original HDR/10-bit for saved file.
        // (2) Analysis ImageReader = 10-bit YCBCR_P010 at analysisSize (e.g. 720p) for green detection only.
        try {
            imageReader?.close()
        } catch (_: Exception) {}
        imageReader = null
        setupAnalysisImageReader(analysisSize)

        val surfaces = mutableListOf<Surface>()

        getTextureViewSurface(previewSize.width, previewSize.height)?.let { surfaces.add(it) }

        val readerSurface = analysisImageReader?.surface
        readerSurface?.let { surfaces.add(it) }

        val mr = android.media.MediaRecorder()
        mediaRecorder = mr

        try {
            val micGranted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

            if (micGranted) mr.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            mr.setVideoSource(android.media.MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            mr.setOutputFile(outFile.absolutePath)

            // Bitrate management for Android 15 stability; 10-bit HDR benefits from higher bitrate
            val bitrate = when {
                isTenBitHdrProfile(recordDynamicProfile) && videoSize.width >= 1920 -> 25_000_000
                videoSize.width >= 1920 -> 15_000_000
                else -> 8_000_000
            }
            mr.setVideoEncodingBitRate(bitrate)
            mr.setVideoFrameRate((fpsRange?.upper ?: 30).coerceIn(24, 60))
            mr.setVideoSize(videoSize.width, videoSize.height)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && recordDynamicProfile > DYNAMIC_RANGE_STANDARD) {
                mr.setVideoEncoder(android.media.MediaRecorder.VideoEncoder.HEVC)
                if (isTenBitHdrProfile(recordDynamicProfile) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        val requestedProfile = preferredHevcHdrProfile(recordDynamicProfile)
                        val (actualProfile, actualLevel) = selectHevcProfileLevel(videoSize, requestedProfile)
                        mr.setVideoEncodingProfileLevel(
                            actualProfile,
                            actualLevel
                        )
                        Log.d(
                            TAG,
                            "Configured MediaRecorder HEVC profile=$actualProfile level=$actualLevel " +
                                "for HDR profile=$recordDynamicProfile"
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to force HEVC Main10 profile, encoder may fallback to SDR/8-bit", e)
                    }
                }
            } else {
                mr.setVideoEncoder(android.media.MediaRecorder.VideoEncoder.H264)
            }

            if (micGranted) {
                mr.setAudioEncodingBitRate(128_000)
                mr.setAudioSamplingRate(48_000)
                mr.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            }

            mr.prepare()
            val recordSurface = mr.surface
            surfaces.add(recordSurface)

            fun openSession(includeReader: Boolean): Boolean {
                val sessionSurfaces = if (includeReader && readerSurface != null) {
                    surfaces
                } else {
                    // Retry without ImageReader — green detection pauses but HDR record can still proceed
                    surfaces.filter { it != readerSurface }
                }
                if (sessionSurfaces.isEmpty()) return false

                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    sessionSurfaces.forEach { addTarget(it) }
                    applyCaptureRequestForProfile(
                        this, recordDynamicProfile, iso, exposureTimeNs, fpsRange,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    )
                }
                previewRequestBuilder = builder

                val callback = object : CameraCaptureSession.StateCallback() {
                    var failed = false
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (failed) return
                        captureSession = session
                        isCameraOperational.set(true)
                        cameraErrorRetryCount = 0
                        try {
                            session.setRepeatingRequest(builder.build(), recordCaptureCallback, bgHandler)
                            mr.start()
                            isRecording = true
                            isPaused = false
                            recordingStartTime = System.currentTimeMillis()
                            onStatus(
                            if (includeReader) "Recording… (HDR analyzer + HDR encoder)"
                            else "Recording… (green detection off)"
                        )
                        } catch (e: Exception) {
                            isCameraOperational.set(false)
                            Log.e(TAG, "Failed to start recording session", e)
                            onStatus("Failed to start recording: ${e.message}")
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        failed = true
                        isCameraOperational.set(false)
                        if (includeReader && isTenBitHdrProfile(recordDynamicProfile)) {
                            Log.w(TAG, "HDR session with ImageReader failed, retrying without reader")
                            AppHealthMonitor.reportIssue(
                                AppIssue(
                                    key = "record_session_reader_fallback",
                                    message = "HDR session failed with analyzer reader; retrying without reader",
                                    severity = IssueSeverity.HIGH,
                                    area = "camera.recording",
                                    attributes = mapOf("dynamic_profile" to recordDynamicProfile.toString())
                                )
                            )
                            try {
                                session.close()
                            } catch (_: Exception) {}
                            if (!openSession(includeReader = false)) {
                                onStatus("Record session configure failed.")
                            }
                        } else {
                            AppHealthMonitor.reportIssue(
                                AppIssue(
                                    key = "record_session_config_failed",
                                    message = "Record session configuration failed",
                                    severity = IssueSeverity.CRITICAL,
                                    area = "camera.recording",
                                    attributes = mapOf("dynamic_profile" to recordDynamicProfile.toString())
                                )
                            )
                            onStatus("Record session configure failed.")
                        }
                    }
                }

                return try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && recordDynamicProfile > DYNAMIC_RANGE_STANDARD) {
                        val hdrSurfaces: Set<Surface> = if (isTenBitHdrProfile(recordDynamicProfile)) {
                            // Encoder always HDR; P010 analyzer reader also requires the HDR profile.
                            mutableSetOf<Surface>(recordSurface).apply {
                                readerSurface?.let { if (includeReader) add(it) }
                            }
                        } else {
                            emptySet()
                        }
                        val configs = outputConfigurationsForSurfaces(sessionSurfaces, recordDynamicProfile, hdrSurfaces)
                        device.createCaptureSessionByOutputConfigurations(configs, callback, bgHandler)
                    } else {
                        device.createCaptureSession(sessionSurfaces, callback, bgHandler)
                    }
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "createCaptureSession failed", e)
                    AppHealthMonitor.captureException(
                        area = "camera.recording",
                        throwable = e,
                        attributes = mapOf("include_reader" to includeReader.toString())
                    )
                    if (includeReader && isTenBitHdrProfile(recordDynamicProfile)) {
                        openSession(includeReader = false)
                    } else {
                        onStatus("Recording setup failed: ${e.message}")
                    }
                    false
                }
            }

            if (!openSession(includeReader = true)) {
                onStatus("Recording setup failed.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording setup error", e)
            onStatus("Recording setup failed: ${e.message}")
            AppHealthMonitor.captureException(
                area = "camera.recording",
                throwable = e,
                attributes = mapOf("video_size" to "${videoSize.width}x${videoSize.height}")
            )
        }
    }

    /**
     * 10-bit YCBCR_P010 analyzer stream — matches the HDR pipeline so the entire capture
     * path stays HDR (HDR/HDR10/HDR10+/HLG10). The encoder receives its own dedicated HDR
     * surface; this reader is used only for green detection.
     *
     * SDR / YUV_420_888 is intentionally NOT supported. If the device cannot allocate P010,
     * the ImageReader allocation will throw and the UI layer must ensure this is never
     * called on non-HDR-capable devices (preview/record buttons are disabled there).
     */
    private fun setupAnalysisImageReader(size: Size) {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "YCBCR_P010 requires API 31+; HDR recording is unsupported on this device."
        }
        analysisImageReader?.setOnImageAvailableListener(null, null)
        analysisImageReader?.close()
        analysisImageReader = ImageReader.newInstance(
            size.width, size.height, ImageFormat.YCBCR_P010, 2
        ).apply {
            setOnImageAvailableListener({ reader ->
                analyzerFramesReceived.incrementAndGet()
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                processDetectionImage(image, "Analysis pipeline")
            }, analysisHandler)
        }
        Log.d(TAG, "Analysis pipeline (YCBCR_P010) size=${size.width}x${size.height}")
    }

    private fun setupImageReader(size: Size) {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "YCBCR_P010 requires API 31+; HDR recording is unsupported on this device."
        }
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = ImageReader.newInstance(
            size.width, size.height, ImageFormat.YCBCR_P010, 2
        ).apply {
            setOnImageAvailableListener({ reader ->
                analyzerFramesReceived.incrementAndGet()
                val image = reader.acquireLatestImage()
                if (image != null) {
                    processDetectionImage(image, "Preview pipeline")
                }
            }, analysisHandler)
        }
    }

    private fun processDetectionImage(image: android.media.Image, pipelineTag: String) {
        val now = System.currentTimeMillis()
        if (image.format != ImageFormat.YCBCR_P010 || image.planes.size < 3) {
            analyzerFramesDroppedInvalid.incrementAndGet()
            safeCloseImage(image)
            maybeLogAnalyzerHealth(now)
            return
        }
        // Always allow at least 30 FPS for luma sampling.
        if (now - lastDetectionAtMs < lumaIntervalMs) {
            analyzerFramesDroppedThrottle.incrementAndGet()
            safeCloseImage(image)
            maybeLogAnalyzerHealth(now)
            return
        }
        if (!isDetectionRunning.compareAndSet(false, true)) {
            analyzerFramesDroppedBusy.incrementAndGet()
            safeCloseImage(image)
            maybeLogAnalyzerHealth(now)
            return
        }

        val startNs = System.nanoTime()
        try {
            val result = if (runKiwiInferenceEnabled) {
                // If it's time for expensive AI inference (15 FPS)
                if (now - lastInferenceAtMs >= inferenceIntervalMs) {
                    analyzerInferenceRuns.incrementAndGet()
                    val newResult = detectionProcessor.processImage(image)
                    lastInferenceAtMs = now
                    lastInferenceResult = newResult
                    newResult
                } else {
                    // It's a "luma update only" frame (30 FPS).
                    // We reuse the last known bounding boxes to keep object metering fast.
                    detectionProcessor.updateLumaOnly(image, lastInferenceResult)
                }
            } else {
                // No kiwi detection enabled; just do full-frame luma at 30 FPS
                detectionProcessor.processImageFrameLumaOnly(image)
            }
            analyzerFramesProcessed.incrementAndGet()
            analyzerTotalProcessingNs.addAndGet(System.nanoTime() - startNs)
            consecutiveAnalyzerErrors = 0
            lastDetectionAtMs = now
            onDetectionUpdate?.invoke(result)
        } catch (e: Exception) {
            analyzerProcessingErrors.incrementAndGet()
            consecutiveAnalyzerErrors += 1
            if (runKiwiInferenceEnabled && consecutiveAnalyzerErrors >= 3 && !inferenceAutoDisabled) {
                inferenceAutoDisabled = true
                runKiwiInferenceEnabled = false
                AppHealthMonitor.reportIssue(
                    AppIssue(
                        key = "camera_analyzer_inference_disabled",
                        message = "Analyzer inference auto-disabled after repeated pipeline errors",
                        severity = IssueSeverity.HIGH,
                        area = "camera.preview",
                        attributes = mapOf("pipeline" to pipelineTag)
                    )
                )
                Log.w(TAG, "Analyzer inference disabled after repeated processing errors")
            }
            Log.e(TAG, "$pipelineTag processing error", e)
        } finally {
            isDetectionRunning.set(false)
            safeCloseImage(image)
            maybeLogAnalyzerHealth(now)
        }
    }

    private fun safeCloseImage(image: android.media.Image) {
        try {
            image.close()
        } catch (_: Exception) {
            analyzerImageCloseErrors.incrementAndGet()
        }
    }

    private fun resetAnalyzerMetrics() {
        analyzerFramesReceived.set(0L)
        analyzerFramesProcessed.set(0L)
        analyzerFramesDroppedThrottle.set(0L)
        analyzerFramesDroppedBusy.set(0L)
        analyzerFramesDroppedInvalid.set(0L)
        analyzerInferenceRuns.set(0L)
        analyzerProcessingErrors.set(0L)
        analyzerTotalProcessingNs.set(0L)
        analyzerImageCloseErrors.set(0L)
        consecutiveAnalyzerErrors = 0
        inferenceAutoDisabled = false
        lastAnalyzerHealthLogMs = 0L
    }

    private fun maybeLogAnalyzerHealth(nowMs: Long) {
        if (nowMs - lastAnalyzerHealthLogMs < 5000L) return
        lastAnalyzerHealthLogMs = nowMs

        val received = analyzerFramesReceived.get()
        val processed = analyzerFramesProcessed.get()
        val droppedThrottle = analyzerFramesDroppedThrottle.get()
        val droppedBusy = analyzerFramesDroppedBusy.get()
        val droppedInvalid = analyzerFramesDroppedInvalid.get()
        val inferenceRuns = analyzerInferenceRuns.get()
        val processingErrors = analyzerProcessingErrors.get()
        val closeErrors = analyzerImageCloseErrors.get()
        val avgProcessMs = if (processed > 0L) {
            (analyzerTotalProcessingNs.get() / processed) / 1_000_000.0
        } else {
            0.0
        }

        Log.d(
            TAG,
            String.format(
                Locale.US,
                "Analyzer health: recv=%d processed=%d dropThrottle=%d dropBusy=%d dropInvalid=%d infer=%d avgProc=%.2fms errors=%d closeErrors=%d",
                received,
                processed,
                droppedThrottle,
                droppedBusy,
                droppedInvalid,
                inferenceRuns,
                avgProcessMs,
                processingErrors,
                closeErrors
            )
        )
    }

    private fun attemptCameraDeviceRecovery(errorMessage: String): Boolean {
        if (isRecording) {
            Log.w(TAG, "Skipping auto-recovery while recording: $errorMessage")
            return false
        }
        val config = lastPreviewRestartConfig ?: return false
        val handler = bgHandler ?: return false
        val now = System.currentTimeMillis()

        if (cameraErrorRetryCount >= maxCameraErrorRetries) {
            Log.w(TAG, "Camera auto-recovery retry limit reached")
            return false
        }
        if (now - lastCameraErrorRetryMs < cameraErrorRetryCooldownMs) {
            Log.w(TAG, "Camera auto-recovery cooldown active")
            return false
        }

        cameraErrorRetryCount += 1
        lastCameraErrorRetryMs = now
        Log.w(TAG, "Attempting camera auto-recovery retry=$cameraErrorRetryCount")
        AppHealthMonitor.reportIssue(
            AppIssue(
                key = "camera_auto_recovery_attempt",
                message = "Attempting auto-recovery after camera device error",
                severity = IssueSeverity.HIGH,
                area = "camera.open",
                attributes = mapOf("retry_count" to cameraErrorRetryCount.toString())
            )
        )

        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        captureSession = null
        cameraDevice = null
        previewRequestBuilder = null
        isCameraOperational.set(false)

        handler.postDelayed({
            try {
                config.onStatus("Camera recovering...")
                openCamera(config.cameraId, config.onStatus) {
                    createPreviewSession(
                        previewSize = config.previewSize,
                        fpsRange = config.fpsRange,
                        iso = config.iso,
                        exposureTimeNs = config.exposureTimeNs,
                        dynamicRangeProfile = config.dynamicRangeProfile,
                        onStatus = config.onStatus
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Camera auto-recovery failed", t)
                val ex = if (t is Exception) t else Exception(t)
                AppHealthMonitor.captureException("camera.auto_recovery", ex)
                stop()
            }
        }, cameraErrorRetryCooldownMs)
        return true
    }

    fun stop() {
        Log.d(TAG, "Stopping camera controller (isRecording=$isRecording)")
        isCameraOperational.set(false)
        lastPreviewRestartConfig = null
        cameraErrorRetryCount = 0
        lastCameraErrorRetryMs = 0L
        try { if (isRecording) stopRecording() } catch (e: Exception) {}
        
        try { captureSession?.close() } catch (e: Exception) {}
        try { cameraDevice?.close() } catch (e: Exception) {}

        captureSession = null
        cameraDevice = null
        previewRequestBuilder = null
        isDetectionRunning.set(false)
        lastDetectionAtMs = 0L
        lastInferenceAtMs = 0L
        lastInferenceResult = null
        resetAnalyzerMetrics()
        lastAppliedAeCompensation = null
        lastAeCompensationUpdateMs = 0L
        currentDynamicRangeProfile = DYNAMIC_RANGE_STANDARD
        supportsVideoStabilization = false
        supportsOpticalStabilization = false
        recorderWarmupSignature = null

        try { mediaRecorder?.release() } catch (e: Exception) {}
        mediaRecorder = null
        isRecording = false
        isPaused = false

        persistentPreviewSurface?.release()
        persistentPreviewSurface = null
        try { textureViewSurface?.release() } catch (_: Exception) {}
        textureViewSurface = null
        persistentSurfaceTexture?.release()
        persistentSurfaceTexture = null
        
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        try {
            analysisImageReader?.setOnImageAvailableListener(null, null)
            analysisImageReader?.close()
        } catch (_: Exception) {}
        analysisImageReader = null

        stopBg()
    }
}