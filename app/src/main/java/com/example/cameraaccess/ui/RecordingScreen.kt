package com.example.cameraaccess.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.DynamicRangeProfiles
import android.location.LocationManager
import android.media.MediaRecorder
import android.os.Build
import android.provider.Settings
import android.util.Range
import android.util.Size
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.cameraaccess.camera.CameraSelector
import com.example.cameraaccess.data.model.ApiCache
import com.example.cameraaccess.controller.Camera2Controller
import com.example.cameraaccess.monitoring.AppHealthMonitor
import com.example.cameraaccess.monitoring.AppIssue
import com.example.cameraaccess.monitoring.IssueSeverity
import com.example.cameraaccess.processing.DetectionResult
import com.example.cameraaccess.processing.TfliteDelegate
import com.example.cameraaccess.ui.utils.formatShutterFromNs
import com.example.cameraaccess.viewmodel.SettingsViewModel
import com.example.cameraaccess.viewmodel.VideoRecordingViewModel
import com.example.cameraaccess.viewmodel.VideoRecordingViewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.*

@Composable
fun KeepScreenOn() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun Modifier.onHoldClick(
    scope: CoroutineScope,
    onHold: () -> Unit,
    enabled: Boolean = true,
    holdDurationMs: Long = 3000L
): Modifier = this.pointerInput(enabled, onHold, holdDurationMs) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val holdJob = scope.launch {
            delay(holdDurationMs)
            onHold()
        }
        waitForUpOrCancellation()
        holdJob.cancel()
    }
}

@Composable
fun rememberGpsStatusState(): State<Boolean> {
    val context = LocalContext.current
    val locationManager = remember { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    val gpsStatus = remember { mutableStateOf(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) }

    DisposableEffect(context) {
        val broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                    gpsStatus.value = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                }
            }
        }
        context.registerReceiver(broadcastReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
        onDispose {
            context.unregisterReceiver(broadcastReceiver)
        }
    }
    return gpsStatus
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(navController: NavController) {
    KeepScreenOn()

    val context = LocalContext.current
    val brandColor = Color(0xFF164D3D)
    val configuration = LocalConfiguration.current

    // PiP Mode Detection
    val activity = context.findActivity() as? ComponentActivity
    var isInPipMode by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }

    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { info ->
            isInPipMode = info.isInPictureInPictureMode
        }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose {
            activity?.removeOnPictureInPictureModeChangedListener(listener)
        }
    }

    val settingsVm: SettingsViewModel = viewModel()
    LaunchedEffect(Unit) { settingsVm.loadSettingsIntoUi() }

    val controller = remember { Camera2Controller.getInstance(context.applicationContext) }
    val recordingVm: VideoRecordingViewModel = viewModel(
        factory = VideoRecordingViewModelFactory(controller, context)
    )

    val segment by recordingVm.lastSegment.collectAsState()
    val gpsEnabled by rememberGpsStatusState()
    var showGpsDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    BackHandler(enabled = recordingVm.previewRunning.value || recordingVm.recording.value) {
        if (recordingVm.recording.value) {
            recordingVm.stopRecording()
            recordingVm.uiText.value = "Recording stopped. Press back again to leave."
        } else if (recordingVm.previewRunning.value) {
            recordingVm.stopCamera()
            recordingVm.uiText.value = "Preview stopped. Press back again to leave."
        }
    }

    // TEMP: in-app CSV preview for verifying per-frame metrics logging.
    var csvDialogTitle by remember { mutableStateOf("") }
    var csvDialogText by remember { mutableStateOf<String?>(null) }

    // Brightness Stats State
    var currentBrightness by remember { mutableIntStateOf(0) }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    var currentEvLabel by remember { mutableStateOf("0.0") }
    
    // Target brightness (0-255) used directly for AE error calculation
    var targetBrightness by remember { mutableIntStateOf(181) }

    // Fixed shutter floor/default: 1/350 s.
    val preferredDenom = 350

    // Auto Exposure State (UI)
    // Start shutter at the mains-flicker-safe denominator so indoor preview doesn't band.
    val currentIsoState = remember { mutableIntStateOf(400) }
    val currentShutterDenomState = remember { mutableIntStateOf(preferredDenom) }

    // Detection State
    val detectionResult by recordingVm.detectionResult.collectAsState()
    val recordingReady by recordingVm.recordingReady.collectAsState()
    val gpsReadyForRecording by recordingVm.gpsReadyForRecording.collectAsState()
    // Fruit scan: kiwi model + object luma for AE; other scans: full-frame brightness only.
    val fruitScanForObjectAe = remember { recordingVm.useKiwiFruitMetering() }
    val delegateLabel = when {
        !fruitScanForObjectAe -> "Off"
        detectionResult == null || detectionResult?.tfliteDelegate == TfliteDelegate.UNKNOWN -> "Initializing..."
        detectionResult?.tfliteDelegate == TfliteDelegate.GPU -> "GPU"
        detectionResult?.tfliteDelegate == TfliteDelegate.NNAPI -> "NNAPI"
        detectionResult?.tfliteDelegate == TfliteDelegate.CPU -> "CPU"
        else -> "Unknown"
    }
    val delegateColor = when {
        !fruitScanForObjectAe -> Color.Gray
        detectionResult?.tfliteDelegate == TfliteDelegate.GPU -> Color(0xFF2E7D32)
        detectionResult?.tfliteDelegate == TfliteDelegate.NNAPI -> Color(0xFF1565C0)
        detectionResult?.tfliteDelegate == TfliteDelegate.CPU -> Color(0xFF6D4C41)
        else -> Color(0xFFF9A825)
    }

    // Constants for AE Logic
    // Slowest shutter allowed = 1/350 s. In dim scenes it must raise ISO (up to maxIso).
    val minDenom = preferredDenom
    val maxDenom = 2000             // Hard ceiling; only reached in very bright scenes
    val minIso = 100
    val maxIso = 3200

    // Auto Exposure Logic Loop
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            // Local variables for the algorithm to ensure atomic updates in the loop
            var internalIso = 400.0
            var internalShutterDenom = preferredDenom.toDouble()

            // Apply initial defaults
            val initialShutterNs = 1_000_000_000L / internalShutterDenom.roundToInt()
            controller.updateExposure(internalIso.roundToInt(), initialShutterNs)
            withContext(Dispatchers.Main) {
                settingsVm.iso.value = internalIso.roundToInt()
                settingsVm.exposureTimeNs.value = initialShutterNs
                currentIsoState.intValue = internalIso.roundToInt()
                currentShutterDenomState.intValue = internalShutterDenom.roundToInt()
                currentEvLabel = "0.0"
            }

            var lastAvgVal = -1

            while (isActive) {
                val loopStart = System.currentTimeMillis()
                
                val isRunning = withContext(Dispatchers.Main) {
                    recordingVm.previewRunning.value || recordingVm.recording.value
                }
                
                val selectedHdr = withContext(Dispatchers.Main) { recordingVm.selectedDynamicRange.value }
                // Manual sensor-AE is now enabled for 10-bit HDR as well. The HDR pipeline
                // stays 10-bit because the OutputConfiguration retains its HDR
                // DynamicRangeProfile — AE mode is orthogonal.
                // Only the legacy Scene HDR scene-mode still needs the HAL's AE.
                val runManualAe = !Camera2Controller.isSceneHdr(selectedHdr)

                // Always sample brightness and update Pixel Brightness Stats when preview is running (including HDR10)
                if (isRunning) {
                    val tv = textureViewRef
                    if (tv != null && tv.isAvailable) {
                        
                        // Brightness source selection (Fruit scan only uses kiwi object luma when identified).
                        val detection = recordingVm.detectionResult.value
                        val fruitIdentified = fruitScanForObjectAe &&
                            detection != null &&
                            detection.confidenceScore > 3f &&
                            detection.objectLuma > 0

                        var avgVal = 0

                        if (fruitIdentified) {
                            avgVal = detection!!.objectLuma
                        } else {
                            // Prefer YUV analyzer mean luma (same path as fruit)—TextureView.getBitmap
                            // is often null on HDR/preview surfaces, which left avgVal at 0 and
                            // disabled AE entirely.
                            val fromAnalysis = detection?.avgFrameLuma ?: 0
                            avgVal = if (fromAnalysis > 0) {
                                fromAnalysis
                            } else {
                                var bmpAvg = 0
                                val bitmap = withContext(Dispatchers.Main) {
                                    try { tv.getBitmap(100, 100) } catch (e: Exception) { null }
                                }
                                if (bitmap != null) {
                                    val w = bitmap.width
                                    val h = bitmap.height
                                    val pixels = IntArray(w * h)
                                    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                                    var sum = 0L
                                    for (px in pixels) {
                                        val r = (px shr 16) and 0xFF
                                        val g = (px shr 8) and 0xFF
                                        val b = px and 0xFF
                                        val luma = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                                        sum += luma
                                    }
                                    bmpAvg = if (pixels.isNotEmpty()) (sum / pixels.size).toInt() else 0
                                    bitmap.recycle()
                                }
                                bmpAvg
                            }
                        }
                        
                        withContext(Dispatchers.Main) {
                            currentBrightness = avgVal
                        }
                        
                        val startIso = currentIsoState.intValue
                        val startDenom = currentShutterDenomState.intValue

                        // --- Auto Exposure Algorithm (Direct Brightness) ---
                        if (runManualAe && avgVal > 0) {
                            val brightnessError = targetBrightness - avgVal

                            // Hold if within a small luma deadband (e.g. +/- 5 units) and not saturated
                            val inDeadband = kotlin.math.abs(brightnessError) <= 5
                            if (inDeadband && avgVal in 10..245) {
                                // Do nothing
                            } else {
                                // Detect sudden jump in lighting (irregular behaviour)
                                val isSuddenJump = lastAvgVal != -1 && kotlin.math.abs(avgVal - lastAvgVal) > 40
                                
                                // Dynamic gain: jump instantly if sudden lighting change, otherwise slow slewing
                                val k = when {
                                    isSuddenJump -> 1.0 // Skip/jump immediately to target
                                    kotlin.math.abs(brightnessError) > 100 -> 0.25
                                    kotlin.math.abs(brightnessError) > 50 -> 0.15
                                    else -> 0.08
                                }

                                val currentExposurePower = internalIso * (1.0 / internalShutterDenom)
                                // Direct multiplier approach: new = old * (target / current)^k
                                val nextExposurePower = currentExposurePower * (targetBrightness.toDouble() / avgVal).pow(k)

                                // --- Strategy: ISO First (with mains-flicker-safe shutter) ---
                                // Keep the shutter pinned at the configured floor/default
                                // (1/350 s). ISO moves first.
                                // Shutter only leaves the safe value when ISO saturates.
                                var goalDenom = preferredDenom.toDouble()
                                var goalIso = nextExposurePower * goalDenom

                                // 1. Too dark for max ISO at the safe shutter → lengthen
                                //    shutter below the safe denominator.
                                if (goalIso > maxIso) {
                                    goalIso = maxIso.toDouble()
                                    goalDenom = goalIso / nextExposurePower
                                }

                                // 2. Too bright for min ISO at the safe shutter → shorten
                                //    shutter above the safe denominator.
                                if (goalIso < minIso) {
                                    goalIso = minIso.toDouble()
                                    goalDenom = goalIso / nextExposurePower
                                }

                                // Hard clamps
                                goalDenom = goalDenom.coerceIn(minDenom.toDouble(), maxDenom.toDouble())
                                goalIso = goalIso.coerceIn(minIso.toDouble(), maxIso.toDouble())

                                internalIso = goalIso
                                internalShutterDenom = goalDenom

                                val applyIso = internalIso.roundToInt()
                                val applyDenom = internalShutterDenom.roundToInt()

                                val isoDelta = kotlin.math.abs(applyIso - currentIsoState.intValue)
                                val denomDelta = kotlin.math.abs(applyDenom - currentShutterDenomState.intValue)

                                // Apply if change is meaningful
                                if (isoDelta >= 5 || denomDelta >= 5) {
                                    val shutterNs = 1_000_000_000L / applyDenom
                                    controller.updateExposure(applyIso, shutterNs)

                                    // Sync to UI
                                    withContext(Dispatchers.Main) {
                                        currentIsoState.intValue = applyIso
                                        currentShutterDenomState.intValue = applyDenom

                                        settingsVm.iso.value = applyIso
                                        settingsVm.exposureTimeNs.value = shutterNs

                                        // Calculate EV relative to base (ISO 400, 1/500)
                                        val newExp = applyIso * (1.0 / applyDenom)
                                        val evValue = ln(newExp / 0.8) / ln(2.0)
                                        currentEvLabel = if (evValue > 0) "+%.1f".format(Locale.US, evValue) else "%.1f".format(Locale.US, evValue)
                                    }
                                }
                            }
                        } else {
                            // In 10-bit HDR modes we keep manual ISO/shutter off, but still steer
                            // exposure using AE compensation so kiwi/object brightness can influence metering.
                            if (avgVal > 0) {
                                val brightnessError = targetBrightness - avgVal
                                // Only update if outside the deadband
                                if (kotlin.math.abs(brightnessError) > 5) {
                                    val stopsToMove = ln(targetBrightness.toDouble() / avgVal.toDouble()) / ln(2.0)
                                    controller.updateExposureCompensation(stopsToMove)
                                }
                            }
                        }

                        // Per-frame CSV metrics (only while recording). Written from the service.
                        if (recordingVm.recording.value) {
                            val targetBrightnessNow = targetBrightness
                            val countNow = detection?.boundingBoxes?.size ?: 0
                            val adjustedBrightness = targetBrightnessNow - avgVal
                            
                            val adjIso = if (runManualAe) internalIso.roundToInt() else startIso
                            val adjDenom = if (runManualAe) internalShutterDenom.roundToInt() else startDenom
                            
                            recordingVm.logMetrics(
                                countPerFrame = countNow,
                                currentBrightness = avgVal,
                                targetedBrightness = targetBrightnessNow,
                                adjustedBrightness = adjustedBrightness,
                                currentIso = startIso,
                                adjustedIso = adjIso,
                                currentShutterDenom = startDenom,
                                adjustedShutterDenom = adjDenom
                            )
                        }
                        
                        lastAvgVal = avgVal
                    }
                }
                
                val loopTime = System.currentTimeMillis() - loopStart
                // Target ~30 iterations per second
                val delayTime = max(0L, 33L - loopTime) 
                delay(delayTime)
            }
        }
    }


    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resultsMap: Map<String, Boolean> ->
        val camOk = resultsMap[Manifest.permission.CAMERA] == true
        val micOk = resultsMap[Manifest.permission.RECORD_AUDIO] == true
        recordingVm.uiText.value = when {
            camOk && micOk -> "Permissions granted ✅"
            camOk -> "Camera granted (Mic denied) 🔇"
            else -> "Permissions denied ❌"
        }
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }) {
            recordingVm.uiText.value = "Requesting permissions…"
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            recordingVm.uiText.value = "Permissions granted ✅"
        }
    }

    val cm = remember { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    val useMainRearForScan = remember {
        ApiCache(context.applicationContext).getUseMainRearCameraForScan()
    }
    val ultraWideId = remember {
        CameraSelector.findRearUltraWideCameraId(cm) ?: cm.cameraIdList.firstOrNull { id ->
            val ch = cm.getCameraCharacteristics(id)
            ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }
    val mainRearId = remember {
        CameraSelector.findRearMainCameraId(cm) ?: ultraWideId
    }
    val activeCameraId = if (useMainRearForScan) mainRearId else ultraWideId
    val cameraStatusLabel = remember(activeCameraId, mainRearId, ultraWideId) {
        when (activeCameraId) {
            null -> "Unknown"
            mainRearId -> "Main"
            ultraWideId -> "Ultra Wide"
            else -> "Custom"
        }
    }

    val characteristics = remember(activeCameraId) { activeCameraId?.let { cm.getCameraCharacteristics(it) } }
    
    val supportedPreviewSizes = remember(characteristics) {
        characteristics?.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
    }

    val supportedVideoSizes = remember(characteristics) {
        characteristics?.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )?.getOutputSizes(MediaRecorder::class.java)?.toList() ?: emptyList()
    }
    
    val supportsP010 = remember(characteristics) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            characteristics?.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )?.outputFormats?.contains(android.graphics.ImageFormat.YCBCR_P010) == true
        } else {
            false
        }
    }
    val supportsVideoStabilization = remember(characteristics) {
        val videoModes =
            characteristics?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                ?: intArrayOf()
        val opticalModes =
            characteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?: intArrayOf()
        videoModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) ||
            opticalModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
    }
    var stabilizationEnabled by remember { mutableStateOf(false) }

    val forcedSize = Size(3840, 2160)
    val fallbackSize = Size(1920, 1080)

    val previewSize = if (supportedPreviewSizes.contains(forcedSize)) forcedSize else fallbackSize
    val videoSize = if (supportedVideoSizes.contains(forcedSize)) forcedSize else fallbackSize

    // HDR Support Detection
    val supportedHdrProfiles = remember(activeCameraId) {
        activeCameraId?.let { controller.getSupportedDynamicRangeProfiles(it) } ?: emptySet()
    }
    val selectedHdrProfile by recordingVm.selectedDynamicRange.collectAsState()

    // True 10-bit HDR (HDR/HDR10/HDR10+/HLG10) is the ONLY supported mode. We require both
    // YCBCR_P010 output and at least one 10-bit DynamicRangeProfile — legacy Scene HDR and
    // SDR fallbacks are intentionally rejected.
    val hasTenBitHdrProfile = supportedHdrProfiles.any { profile ->
        profile == DynamicRangeProfiles.HLG10 ||
            profile == DynamicRangeProfiles.HDR10 ||
            profile == DynamicRangeProfiles.HDR10_PLUS
    }
    val hdrSupported = supportsP010 && hasTenBitHdrProfile

    LaunchedEffect(hdrSupported, supportsP010, hasTenBitHdrProfile) {
        if (!hdrSupported) {
            AppHealthMonitor.reportIssue(
                AppIssue(
                    key = "device_hdr_unsupported",
                    message = "Device is incompatible: HDR capture requirements not met",
                    severity = IssueSeverity.CRITICAL,
                    area = "device.compatibility",
                    attributes = mapOf(
                        "supports_p010" to supportsP010.toString(),
                        "has_hdr_profile" to hasTenBitHdrProfile.toString()
                    )
                )
            )
        }
    }

    // Default selection — only offered when the device truly supports HDR.
    LaunchedEffect(supportedHdrProfiles, supportsP010) {
        if (hdrSupported && selectedHdrProfile == Camera2Controller.DYNAMIC_RANGE_STANDARD) {
            val defaultHdr = when {
                supportedHdrProfiles.contains(DynamicRangeProfiles.HDR10_PLUS) -> DynamicRangeProfiles.HDR10_PLUS
                supportedHdrProfiles.contains(DynamicRangeProfiles.HDR10) -> DynamicRangeProfiles.HDR10
                supportedHdrProfiles.contains(DynamicRangeProfiles.HLG10) -> DynamicRangeProfiles.HLG10
                else -> Camera2Controller.DYNAMIC_RANGE_STANDARD
            }
            if (defaultHdr != Camera2Controller.DYNAMIC_RANGE_STANDARD) {
                recordingVm.selectedDynamicRange.value = defaultHdr
            }
        }
    }

    // We use values from our local state
    val isoValue = currentIsoState.intValue
    val exposureNs = if (currentShutterDenomState.intValue > 0) 1_000_000_000L / currentShutterDenomState.intValue else 0L
    // FPS is locked to 30
    val fpsValue = 30
    
    val screenHeight = configuration.screenHeightDp.dp
    val previewHeight = screenHeight * 0.8f

    if (showGpsDialog) {
        AlertDialog(
            onDismissRequest = { showGpsDialog = false },
            title = { Text("Enable GPS") },
            text = { Text("GPS is required to record the track for your video. Please enable it in the device settings.") },
            confirmButton = {
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    showGpsDialog = false
                }) { Text("Open Settings") }
            },
            dismissButton = {
                TextButton(onClick = { showGpsDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            if (!isInPipMode) {
                TopAppBar(
                    title = {
                        Text(
                            "Start Scan",
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = brandColor)
                )
            }
        }
    ) { paddingValues ->
        val scrollState = rememberScrollState()

        var colMod = Modifier.fillMaxSize()
        if (!isInPipMode) {
            colMod = colMod.padding(paddingValues)
        }
        colMod = colMod.verticalScroll(scrollState)
        if (!isInPipMode) {
            colMod = colMod.padding(16.dp)
        }

        Column(
            modifier = colMod,
            verticalArrangement = if (isInPipMode) Arrangement.Center else Arrangement.spacedBy(16.dp)
        ) {
            val boxModifier = if (isInPipMode) {
                Modifier.fillMaxWidth().height(configuration.screenHeightDp.dp)
            } else {
                Modifier
                    .fillMaxWidth()
                    .height(previewHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
            }

            Box(modifier = boxModifier) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            textureViewRef = this
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    recordingVm.controller.setTextureView(this@apply)
                                    // Refresh preview if it was running in background
                                    if (recordingVm.previewRunning.value && !recordingVm.recording.value) {
                                        activeCameraId?.let { id ->
                                            recordingVm.startPreview(
                                                cameraId = id,
                                                previewSize = previewSize,
                                                videoSize = videoSize,
                                                fpsRange = Range(30, 30),
                                                iso = currentIsoState.intValue,
                                                exposureTimeNs = 1_000_000_000L / currentShutterDenomState.intValue,
                                                dynamicRangeProfile = selectedHdrProfile
                                            ) { /* quiet refresh */ }
                                        }
                                    }
                                }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    recordingVm.controller.setTextureView(null)
                                    // Do NOT stop camera if recording or if we want to keep preview in background
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Detection Overlay with corrected orientation mapping
                DetectionOverlay(
                    detectionResult = detectionResult,
                    sensorSize = previewSize,
                    modifier = Modifier.fillMaxSize()
                )

                val duration by recordingVm.recordingDuration.collectAsState()
                val currentLocation by recordingVm.location.collectAsState()
                val isRecording = recordingVm.recording.value
                val isPreviewRunning = recordingVm.previewRunning.value

                if (isRecording || isPreviewRunning) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        if (isRecording) {
                            val minutes = TimeUnit.MILLISECONDS.toMinutes(duration)
                            val seconds = TimeUnit.MILLISECONDS.toSeconds(duration) % 60
                            Text(
                                text = String.format(Locale.US, "%02d:%02d", minutes, seconds),
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        val loc = currentLocation
                        if (loc != null) {
                             Text(
                                text = "%.6f, %.6f".format(loc.latitude, loc.longitude),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        } else {
                            Text(
                                text = "Waiting for GPS...",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                        }

                        detectionResult?.let { res ->
                            Text(
                                text = res.status,
                                color = if (res.status == "Identified") Color.Green else Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (res.status == "Identified") {
                                Text(
                                    text = "Object Brightness: ${res.objectLuma}",
                                    color = Color.Yellow,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            if (!isInPipMode) {
                Card(elevation = CardDefaults.cardElevation(2.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Status: ${recordingVm.uiText.value}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (gpsEnabled) "GPS: On" else "GPS: Off",
                                color = if (gpsEnabled) Color.Green else Color.Red
                            )
                            Text(
                                text = "TFLite: $delegateLabel",
                                color = delegateColor
                            )
                        }
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
//                            InfoChip("Preview: ${previewSize.width}×${previewSize.height}")
                            InfoChip("Video: ${videoSize.width}×${videoSize.height}")
                            InfoChip("P010: ${if (supportsP010) "Yes" else "No"}")
                            InfoChip("Camera: $cameraStatusLabel")
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            InfoChip("ISO: $isoValue")
                            InfoChip("FPS: $fpsValue")
                            InfoChip("Shutter: ${formatShutterFromNs(exposureNs)}")
                        }
//                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
//                            InfoChip("EV: $currentEvLabel")
//                            InfoChip("P010: ${if (supportsP010) "Yes" else "No"}")
//                        }
//                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
//                            InfoChip("Stabilization: ${if (stabilizationEnabled) "On" else "Off"}")
//                            InfoChip("Stab Support: ${if (supportsVideoStabilization) "Yes" else "No"}")
//                        }
                    }
                }

                // HDR Selection
                if (supportedHdrProfiles.isNotEmpty()) {
                    Card(elevation = CardDefaults.cardElevation(2.dp)) {
                        Column(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Dynamic Range", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // SDR option disabled — the camera always shoots in HDR.
                                // HdrChip(
                                //     label = "SDR",
                                //     isSelected = selectedHdrProfile == Camera2Controller.DYNAMIC_RANGE_STANDARD,
                                //     onClick = { recordingVm.selectedDynamicRange.value = Camera2Controller.DYNAMIC_RANGE_STANDARD }
                                // )

                                // Modern 10-bit HDR Profiles
                                if (supportedHdrProfiles.contains(DynamicRangeProfiles.HLG10)) {
                                    HdrChip(
                                        label = "HDR",
                                        isSelected = selectedHdrProfile == DynamicRangeProfiles.HLG10,
                                        onClick = { recordingVm.selectedDynamicRange.value = DynamicRangeProfiles.HLG10 }
                                    )
                                }
//                                if (supportedHdrProfiles.contains(DynamicRangeProfiles.HDR10)) {
//                                    HdrChip(
//                                        label = "HDR10",
//                                        isSelected = selectedHdrProfile == DynamicRangeProfiles.HDR10,
//                                        onClick = { recordingVm.selectedDynamicRange.value = DynamicRangeProfiles.HDR10 }
//                                    )
//                                }
//                                if (supportedHdrProfiles.contains(DynamicRangeProfiles.HDR10_PLUS)) {
//                                    HdrChip(
//                                        label = "HDR10+",
//                                        isSelected = selectedHdrProfile == DynamicRangeProfiles.HDR10_PLUS,
//                                        onClick = { recordingVm.selectedDynamicRange.value = DynamicRangeProfiles.HDR10_PLUS }
//                                    )
//                                }
                                // Legacy scene HDR disabled — it is often not a 10-bit container
                                // HDR and forces HAL AE, which we don't want for manual exposure.
                                // if (supportedHdrProfiles.contains(Camera2Controller.DYNAMIC_RANGE_SCENE_HDR)) {
                                //     HdrChip(
                                //         label = "HDR (Scene)",
                                //         isSelected = selectedHdrProfile == Camera2Controller.DYNAMIC_RANGE_SCENE_HDR,
                                //         onClick = { recordingVm.selectedDynamicRange.value = Camera2Controller.DYNAMIC_RANGE_SCENE_HDR }
                                //     )
                                // }
                                Spacer(modifier = Modifier.weight(1f))
                                Text("Stabilization", style = MaterialTheme.typography.bodySmall)
                                Switch(
                                    checked = stabilizationEnabled,
                                    onCheckedChange = { enabled ->
                                        stabilizationEnabled = enabled
                                        val applied = recordingVm.controller.setStabilizationEnabled(enabled)
                                        recordingVm.uiText.value = when {
                                            !supportsVideoStabilization -> "Stabilization unsupported on this camera"
                                            applied && enabled -> "Stabilization ON"
                                            applied && !enabled -> "Stabilization OFF"
                                            else -> "Failed to apply stabilization"
                                        }
                                    },
                                    enabled = supportsVideoStabilization
                                )
                            }
                            val manualAeAllowed = !Camera2Controller.isSceneHdr(selectedHdrProfile)
                            Text(
                                if (manualAeAllowed) "Manual ISO/Shutter updates: Enabled (HDR stays 10-bit)"
                                else "Manual ISO/Shutter updates: Disabled (Scene HDR legacy)",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // --- Realtime Brightness Stats ---
                Card(elevation = CardDefaults.cardElevation(2.dp)) {
                    Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Pixel Brightness Stats (0-255)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            InfoChip("Current: $currentBrightness")
                            InfoChip("Target: $targetBrightness")
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                            var expanded by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { expanded = true }) {
                                    Text("Target Brightness: $targetBrightness")
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    listOf(64, 90, 128, 150, 181, 210, 230).forEach { brightness ->
                                        DropdownMenuItem(
                                            text = { Text("$brightness") },
                                            onClick = {
                                                targetBrightness = brightness
                                                expanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Card(elevation = CardDefaults.cardElevation(2.dp)) {
                    Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!hdrSupported) {
                            Text(
                                "This device does not support HDR",
                                color = Color.Red,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                        recordingVm.uiText.value = "CAMERA permission missing ❌"
                                        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                                    } else {
                                        recordingVm.uiText.value = "Starting preview…"
                                        val startIso = currentIsoState.intValue
                                        val startDenom = currentShutterDenomState.intValue
                                        recordingVm.controller.setStabilizationEnabled(stabilizationEnabled)
                                        activeCameraId?.let { id ->
                                            recordingVm.startPreview(
                                                cameraId = id,
                                                previewSize = previewSize,
                                                videoSize = videoSize,
                                                fpsRange = Range(30, 30),
                                                iso = startIso,
                                                exposureTimeNs = 1_000_000_000L / startDenom,
                                                dynamicRangeProfile = selectedHdrProfile
                                            ) { msg -> recordingVm.uiText.value = msg }
                                        }
                                    }
                                },
                                enabled = hdrSupported && !recordingVm.previewRunning.value && !recordingVm.recording.value,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = brandColor)
                            ) {
                                Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text("Start Preview")
                            }
                            val canStopPreview = recordingVm.previewRunning.value && !recordingVm.recording.value
                            OutlinedButton(
                                onClick = { /* Hold to stop */ },
                                enabled = canStopPreview,
                                modifier = Modifier
                                    .weight(1f)
                                    .onHoldClick(
                                        scope = scope,
                                        enabled = canStopPreview,
                                        onHold = {
                                            recordingVm.stopCamera()
                                            recordingVm.uiText.value = "Stopped."
                                        }
                                    )
                            ) {
                                Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text("Stop")
                            }
                        }

                        HorizontalDivider()

                        val isRecording = recordingVm.recording.value
                        val canStartRecording = hdrSupported && !isRecording && recordingReady
                        val waitingForGpsPoint =
                            recordingVm.previewRunning.value &&
                                !isRecording &&
                                gpsEnabled &&
                                !gpsReadyForRecording
                        Button(
                            onClick = {
                                if (!recordingVm.previewRunning.value) {
                                    recordingVm.uiText.value = "Start the Preview first"
                                } else if (!gpsEnabled) {
                                    showGpsDialog = true
                                } else {
                                    val startIso = currentIsoState.intValue
                                    val startDenom = currentShutterDenomState.intValue
                                    recordingVm.controller.setStabilizationEnabled(stabilizationEnabled)
                                    activeCameraId?.let { id ->
                                        recordingVm.startRecording(
                                            permissionLauncher = permissionLauncher,
                                            cameraId = id,
                                            previewSize = previewSize,
                                            videoSize = videoSize,
                                            fpsRange = Range(30, 30),
                                            iso = startIso,
                                            exposureTimeNs = 1_000_000_000L / startDenom
                                        )
                                    }
                                }
                            },
                            enabled = canStartRecording,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = brandColor)
                        ) {
                            Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(if (waitingForGpsPoint) "Waiting for GPS Point" else "Start Recording")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { /* Hold to stop recording */ },
                                enabled = isRecording,
                                modifier = Modifier
                                    .weight(1f)
                                    .onHoldClick(
                                        scope = scope,
                                        enabled = isRecording,
                                        onHold = { recordingVm.stopRecording() }
                                    )
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text("Stop Recording")
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = segment != null) {
                    Card(elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Ready to Upload", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { navController.navigate("dashboard") },
                                colors = ButtonDefaults.buttonColors(containerColor = brandColor)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text("Go to Dashboard")
                            }
                            Spacer(Modifier.height(8.dp))
                            // TEMP: debug preview for per-frame CSV metrics.
                            OutlinedButton(
                                onClick = {
                                    val videoPath = segment?.videoPath
                                    if (videoPath == null) {
                                        csvDialogTitle = "CSV"
                                        csvDialogText = "No recording available."
                                        return@OutlinedButton
                                    }
                                    val csvFile = java.io.File(
                                        videoPath.substringBeforeLast('.') + ".csv"
                                    )
                                    csvDialogTitle = csvFile.name
                                    csvDialogText = if (!csvFile.exists()) {
                                        "CSV not found at:\n${csvFile.absolutePath}"
                                    } else {
                                        val lines = csvFile.readLines()
                                        val totalRows = (lines.size - 1).coerceAtLeast(0)
                                        val head = lines.take(1) + lines.drop(1).take(30)
                                        val tail = if (lines.size > 31) lines.takeLast(10) else emptyList()
                                        buildString {
                                            append("Path: ${csvFile.absolutePath}\n")
                                            append("Size: ${csvFile.length()} bytes\n")
                                            append("Rows: $totalRows\n\n")
                                            append(head.joinToString("\n"))
                                            if (tail.isNotEmpty()) {
                                                append("\n…\n")
                                                append(tail.joinToString("\n"))
                                            }
                                        }
                                    }
                                }
                            ) {
                                Text("View CSV")
                            }
                        }
                    }
                }

                if (csvDialogText != null) {
                    AlertDialog(
                        onDismissRequest = { csvDialogText = null },
                        title = { Text(csvDialogTitle) },
                        text = {
                            Box(Modifier.heightIn(max = 420.dp)) {
                                Text(
                                    csvDialogText ?: "",
                                    fontSize = 11.sp,
                                    modifier = Modifier.verticalScroll(rememberScrollState())
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { csvDialogText = null }) { Text("Close") }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DetectionOverlay(
    detectionResult: DetectionResult?,
    sensorSize: Size,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val result = detectionResult ?: return@Canvas

        // Map from analyzer frame (e.g. 720p YUV) or sensor/preview size when frame size not set
        val sw = (if (result.frameWidth > 0) result.frameWidth else sensorSize.width).toFloat()
        val sh = (if (result.frameHeight > 0) result.frameHeight else sensorSize.height).toFloat()

        // Red border outline around detected green regions (analyzer pipeline coords → view)
        for (box in result.boundingBoxes) {
            val uw = size.width
            val uh = size.height

            val ux1 = (1f - box.top.toFloat() / sh) * uw
            val uy1 = (box.left.toFloat() / sw) * uh
            val ux2 = (1f - box.bottom.toFloat() / sh) * uw
            val uy2 = (box.right.toFloat() / sw) * uh

            val rectLeft = min(ux1, ux2)
            val rectTop = min(uy1, uy2)
            val rectWidth = abs(ux2 - ux1)
            val rectHeight = abs(uy2 - uy1)

            // Red border outline only (no fill) — encoder pipeline unchanged
            drawRect(
                color = Color.Red,
                topLeft = Offset(rectLeft, rectTop),
                size = androidx.compose.ui.geometry.Size(rectWidth, rectHeight),
                style = Stroke(width = 3.dp.toPx())
            )
        }
    }
}

@Composable
fun InfoChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun HdrChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (isSelected) {
            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else null
    )
}