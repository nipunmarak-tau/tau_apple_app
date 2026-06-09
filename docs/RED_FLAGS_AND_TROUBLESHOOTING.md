# Red Flags, Challenges & Troubleshooting Guide

## Overview

This document identifies potential issues, errors, and challenges that clients may face when using the TAU Kiwi Fruit IQ app. It provides guidance on how to identify these issues early and implement fixes.

## Monitoring Tooling Implemented

The following monitoring tooling has now been implemented in the app codebase:

- `AppHealthMonitor` central utility with structured issue reporting, exception capture, and metric events.
- Firebase Analytics event emission for health snapshots, uploads, login outcomes, and issue counters.
- New Relic forwarding (reflection-based) for handled exceptions/custom metrics without hard API coupling.
- Device health snapshot on app startup (SDK, network status, free storage, key permissions).
- Structured instrumentation added to camera, upload, GPS, auth, token storage, and recording repository flows.

### New files

- `app/src/main/java/com/example/cameraaccess/monitoring/AppHealthMonitor.kt`

### Instrumented files

- `app/src/main/java/com/example/cameraaccess/CameraApp.kt`
- `app/src/main/java/com/example/cameraaccess/MainActivity.kt`
- `app/src/main/java/com/example/cameraaccess/data/network/RetrofitClient.kt`
- `app/src/main/java/com/example/cameraaccess/data/repositories/AuthRepository.kt`
- `app/src/main/java/com/example/cameraaccess/controller/Camera2Controller.kt`
- `app/src/main/java/com/example/cameraaccess/ui/RecordingScreen.kt`
- `app/src/main/java/com/example/cameraaccess/service/UploadService.kt`
- `app/src/main/java/com/example/cameraaccess/service/UploadStateBus.kt`
- `app/src/main/java/com/example/cameraaccess/service/RecordingService.kt`
- `app/src/main/java/com/example/cameraaccess/data/repositories/RecordingRepository.kt`
- `app/src/main/java/com/example/cameraaccess/utils/TokenManager.kt`

---

## Table of Contents

1. [Critical Red Flags](#1-critical-red-flags)
2. [Permission-Related Issues](#2-permission-related-issues)
3. [Device Compatibility Issues](#3-device-compatibility-issues)
4. [Camera & Recording Issues](#4-camera--recording-issues)
5. [GPS & Location Issues](#5-gps--location-issues)
6. [Network & Upload Issues](#6-network--upload-issues)
7. [Storage Issues](#7-storage-issues)
8. [Authentication Issues](#8-authentication-issues)
9. [Service & Background Processing Issues](#9-service--background-processing-issues)
10. [ML Model & Detection Issues](#10-ml-model--detection-issues)
11. [Database Issues](#11-database-issues)
12. [Memory & Performance Issues](#12-memory--performance-issues)
13. [How to Monitor & Debug](#13-how-to-monitor--debug)
14. [Quick Reference: Error Messages](#14-quick-reference-error-messages)

---

## 1. Critical Red Flags

These are the most severe issues that will completely block users:

### 1.1 Device Does Not Support HDR (App Unusable)

**What Happens:**
- App displays "This device does not support HDR"
- Start Preview and Start Recording buttons are disabled
- Users cannot proceed at all

**Root Cause:**
```kotlin
// From RecordingScreen.kt - Lines 550-556
val hasTenBitHdrProfile = supportedHdrProfiles.any { profile ->
    profile == DynamicRangeProfiles.HLG10 ||
    profile == DynamicRangeProfiles.HDR10 ||
    profile == DynamicRangeProfiles.HDR10_PLUS
}
val hdrSupported = supportsP010 && hasTenBitHdrProfile
```

**Devices at Risk:**
- Phones older than 2020
- Budget phones (most don't support HDR10/HLG10)
- Android versions below API 31 (Android 12)

**How to Identify:**
```kotlin
// Check in logs for:
Log.d(TAG, "Supported HDR profiles for $cameraId: $result")
// If result is empty or contains only DYNAMIC_RANGE_SCENE_HDR (-1L), device won't work
```

**Recommended Fix:**
- Add a compatibility check screen BEFORE users invest time
- Provide a clear list of supported devices
- Consider adding an SDR fallback mode for basic functionality

---

### 1.2 API Level Requirement Not Met

**What Happens:**
- App crashes or features fail silently
- `YCBCR_P010` format not available

**Root Cause:**
```kotlin
// Camera2Controller.kt - Lines 1019-1021
require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    "YCBCR_P010 requires API 31+; HDR recording is unsupported on this device."
}
```

**Required:** Android 12 (API 31) minimum

**How to Identify:**
- Check `Build.VERSION.SDK_INT` on app launch
- Log device info in crash reports

**Recommended Fix:**
```kotlin
// Add to MainActivity.onCreate()
if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
    showIncompatibleDeviceDialog()
}
```

---

### 1.3 Missing TensorFlow Lite Model

**What Happens:**
- Green/kiwi detection fails
- App may crash during recording

**Root Cause:**
```kotlin
// KiwiDetectionProcessor.kt - Lines 341-355
private fun loadModelFile(fileName: String): ByteBuffer {
    val afd: AssetFileDescriptor = context.assets.openFd(fileName)
    // If model-hdr-320.tflite is missing, throws IOException
}
```

**How to Identify:**
```
E/GreenDetection: Model inference failed
java.io.FileNotFoundException: model-hdr-320.tflite
```

**Recommended Fix:**
- Validate model file exists during build
- Add fallback detection mode without ML

---

## 2. Permission-Related Issues

### 2.1 Required Permissions

| Permission | Purpose | Consequence if Denied |
|------------|---------|----------------------|
| `CAMERA` | Video recording | App cannot function |
| `RECORD_AUDIO` | Audio in video | Video records without sound |
| `ACCESS_FINE_LOCATION` | GPS tracking | Cannot start recording |
| `POST_NOTIFICATIONS` (API 33+) | Foreground service | Service may be killed |
| `FOREGROUND_SERVICE_*` | Background recording | Service crashes |

### 2.2 How Issues Manifest

**Camera Permission Denied:**
```
"CAMERA permission missing ❌"
```

**Location Permission Denied:**
- Recording won't start
- "Waiting for GPS..." displayed indefinitely

**Audio Permission Denied:**
```kotlin
// RecordingService.kt - Lines 869-871
val micGranted = ContextCompat.checkSelfPermission(...) == PackageManager.PERMISSION_GRANTED
if (micGranted) mr.setAudioSource(...)
// Silently records without audio - user may not realize
```

### 2.3 Recommended Fixes

```kotlin
// Add explicit user feedback for audio permission
if (!micGranted) {
    showToast("Audio permission denied - video will have no sound")
}

// Pre-flight permission check before entering recording screen
fun validateAllPermissions(): PermissionResult {
    return PermissionResult(
        camera = checkPermission(CAMERA),
        audio = checkPermission(RECORD_AUDIO),
        location = checkPermission(ACCESS_FINE_LOCATION),
        notifications = checkPermission(POST_NOTIFICATIONS)
    )
}
```

---

## 3. Device Compatibility Issues

### 3.1 Camera Hardware Requirements

**Issue: Camera Feature Required**
```xml
<!-- AndroidManifest.xml - Lines 37-39 -->
<uses-feature
    android:name="android.hardware.camera"
    android:required="true" />
```
- Tablets without rear cameras won't see the app in Play Store
- External camera attachments not supported

### 3.2 Ultra-Wide vs Main Camera Selection

**Issue:**
```kotlin
// RecordingScreen.kt - Lines 481-490
val ultraWideId = CameraSelector.findRearUltraWideCameraId(cm)
val mainRearId = CameraSelector.findRearMainCameraId(cm)
val activeCameraId = if (useMainRearForScan) mainRearId else ultraWideId
```

**Red Flags:**
- Some devices have no ultra-wide camera
- Camera selection may pick wrong lens
- Settings change requires app restart

**How to Identify:**
```
cameraStatusLabel shows "Unknown" or "Custom"
```

### 3.3 Video Size Not Supported

**Issue:**
```kotlin
// RecordingScreen.kt - Lines 535-539
val forcedSize = Size(3840, 2160)  // 4K
val fallbackSize = Size(1920, 1080) // 1080p
val videoSize = if (supportedVideoSizes.contains(forcedSize)) forcedSize else fallbackSize
```

**Red Flag:** Some devices may not support even 1080p with HDR/P010

---

## 4. Camera & Recording Issues

### 4.1 Camera Access Errors

| Error Code | Message | Cause |
|------------|---------|-------|
| `ERROR_CAMERA_IN_USE` | "Camera in use" | Another app using camera |
| `ERROR_MAX_CAMERAS_IN_USE` | "Too many cameras in use" | Hardware limit reached |
| `ERROR_CAMERA_DISABLED` | "Camera disabled (Error 3)" | Device policy/parental controls |
| `ERROR_CAMERA_DEVICE` | "Camera device error" | Hardware failure |
| `ERROR_CAMERA_SERVICE` | "Camera service error" | System service crashed |

**How They Appear:**
```kotlin
// Camera2Controller.kt - Lines 485-497
override fun onError(device: CameraDevice, error: Int) {
    val msg = when (error) {
        ERROR_CAMERA_IN_USE -> "Camera in use"
        // ...
    }
    onStatus("Camera error: $msg")
}
```

### 4.2 Recording Failures

**Zero-Byte Video Files (Critical on Android 15):**
```kotlin
// Camera2Controller.kt - Lines 384-388
// Ensure minimum recording duration to prevent 0-byte files on Android 15
val elapsed = System.currentTimeMillis() - recordingStartTime
if (elapsed < 1000) {
    Thread.sleep(1000 - elapsed)
}
```

**MediaRecorder Stop Failure:**
```kotlin
// Camera2Controller.kt - Lines 395-406
try {
    mediaRecorder?.apply {
        stop()  // Can throw if recording too short
        reset()
        release()
    }
} catch (e: Exception) {
    Log.e(TAG, "stopRecording failed", e)
    // File might be corrupted/0 bytes
}
```

### 4.3 Session Configuration Failures

**HDR Session with ImageReader Fails:**
```kotlin
// Camera2Controller.kt - Lines 957-970
override fun onConfigureFailed(session: CameraCaptureSession) {
    if (includeReader && isTenBitHdrProfile(recordDynamicProfile)) {
        // Retry without ImageReader - green detection disabled
        Log.w(TAG, "HDR session with ImageReader failed, retrying without reader")
    }
}
```

**User Impact:** Recording works but kiwi detection is disabled

### 4.4 Recommended Monitoring

```kotlin
// Add comprehensive recording state tracking
sealed class RecordingState {
    object Idle : RecordingState()
    object Starting : RecordingState()
    data class Recording(val startTime: Long, val hasAudio: Boolean, val hasDetection: Boolean)
    data class Error(val code: Int, val message: String)
    object Stopping : RecordingState()
}
```

---

## 5. GPS & Location Issues

### 5.1 GPS Not Enabled

**What Happens:**
- Dialog shown: "GPS is required to record the track"
- Recording blocked until GPS enabled

**Code Location:**
```kotlin
// RecordingScreen.kt - Lines 945-950
if (!gpsEnabled) {
    showGpsDialog = true
} else {
    // Start recording
}
```

### 5.2 GPS Signal Acquisition Delay

**Issue:**
```kotlin
// RecordingScreen.kt - Lines 712-718
if (loc != null) {
    Text("%.6f, %.6f".format(loc.latitude, loc.longitude))
} else {
    Text("Waiting for GPS...", color = Color.White.copy(alpha = 0.7f))
}
```

**Red Flags:**
- Indoor usage = very slow/no GPS
- "Waiting for GPS..." persists for minutes
- First point in GPX track may be inaccurate

### 5.3 Location Service Crashes

```kotlin
// RecordingService.kt - Lines 417-425
try {
    fusedLocationClient.requestLocationUpdates(...)
} catch (e: SecurityException) {
    Log.e(TAG, "Failed to request location updates", e)
    // Location tracking silently fails!
}
```

### 5.4 Recommended Fixes

```kotlin
// Add GPS quality indicator
data class GpsQuality(
    val accuracy: Float,    // meters
    val satellites: Int,
    val isUsable: Boolean   // accuracy < 10m
)

// Warn users about poor GPS
if (gpsQuality.accuracy > 20f) {
    showWarning("GPS accuracy is low (${gpsQuality.accuracy}m). Move outdoors.")
}
```

---

## 6. Network & Upload Issues

### 6.1 Upload Failures

| Error | User Message | Technical Cause |
|-------|--------------|-----------------|
| `NetworkError` | "Connection problem. Please check your internet and try again." | No network / timeout |
| `ServerError(401)` | "Server error (401). Please try again later." | Token expired |
| `ServerError(5xx)` | "Server error (5xx). Please try again later." | Server down |
| `ValidationError` | Variable | Missing files/data |

### 6.2 Multipart Upload Failures

**Partial Upload Scenario:**
```kotlin
// UploadService.kt - Lines 169-196
for (partNumber in 1..totalParts) {
    // If any part fails, previous parts are orphaned on S3
    val req = Request.Builder().url(url).put(body).build()
    okHttp.newCall(req).execute().use {
        if (!it.isSuccessful) {
            throw AppError.ServerError(it.code, "Failed to upload video part $partNumber.")
        }
    }
}
```

**Red Flag:** No retry mechanism for individual parts

### 6.3 Missing Files During Upload

```kotlin
// UploadService.kt - Lines 139-141, 216-217, 249-251
val videoFile = resolveToReadableFile(item.videoPath, true)
    ?: throw AppError.ValidationError("Video file not found on device.")

val gpxFile = resolveToReadableFile(item.gpxPath, false)
    ?: throw AppError.ValidationError("GPX file not found on device.")

val csvFile = resolveToReadableFile(csvPathResolved, false)
    ?: throw AppError.ValidationError("CSV file not found on device.")
```

**Causes:**
- User manually deleted files
- Storage cleanup by system
- File path corruption

### 6.4 Timeout Configuration

```kotlin
// UploadService.kt - Lines 279-286
private fun buildS3OkHttp(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)  // Unlimited!
        .callTimeout(0, TimeUnit.SECONDS)   // Unlimited!
        .retryOnConnectionFailure(true)
        .build()
```

**Red Flag:** No overall timeout - upload could hang indefinitely on slow connections

### 6.5 Recommended Fixes

```kotlin
// Add upload retry logic
class UploadRetryPolicy(
    val maxRetries: Int = 3,
    val backoffMs: Long = 2000
) {
    suspend fun <T> execute(block: suspend () -> T): T {
        var lastException: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                delay(backoffMs * (attempt + 1))
            }
        }
        throw lastException!!
    }
}

// Add upload progress persistence
data class UploadCheckpoint(
    val recordingId: Long,
    val completedParts: List<Int>,
    val lastAttemptTime: Long
)
```

---

## 7. Storage Issues

### 7.1 Insufficient Storage Space

**Not Currently Checked!**

```kotlin
// Camera2Controller.kt - No storage check before recording
fun startRecording(...): String? {
    // Immediately creates file without checking space
    val outFile = File(outDir, "track_$ts.mp4")
}
```

**Recommended Fix:**
```kotlin
fun hasEnoughStorage(requiredMb: Long = 500): Boolean {
    val stat = StatFs(Environment.getExternalStorageDirectory().path)
    val availableMb = stat.availableBytes / (1024 * 1024)
    return availableMb >= requiredMb
}
```

### 7.2 External Storage Unavailable

```kotlin
// RecordingService.kt - Lines 213-214
val externalDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
    ?: throw IOException("External storage unavailable")
```

**Causes:**
- USB mass storage mode active
- Storage corruption
- Some custom ROMs

### 7.3 File Cleanup Issues

```kotlin
// RecordingRepository.kt - Lines 195-213
private suspend fun deleteFileIfExists(path: String): Boolean {
    try {
        val file = File(path)
        if (!file.exists()) return true
        val deleted = file.delete()
        if (!deleted) {
            Log.w(TAG, "Failed to delete file: $path")
        }
        return deleted
    } catch (e: SecurityException) {
        // File stuck on device, consuming storage
    }
}
```

**Red Flag:** Failed deletions accumulate over time

---

## 8. Authentication Issues

### 8.1 Token Expiration

**Silent Token Clear on 401:**
```kotlin
// RetrofitClient.kt - Lines 37-44
if (response.code == 401) {
    Log.e("RetrofitClient", "Received 401 Unauthorized. Clearing token.")
    context?.let {
        TokenManager(it).clear()
        // No automatic redirect to login!
    }
}
```

**User Experience:**
- Upload silently fails
- User must manually logout/login
- No "session expired" message

### 8.2 Token Storage Failures

```kotlin
// TokenManager.kt - Lines 22-41
private fun createPrefs(context: Context): SharedPreferences {
    return try {
        // EncryptedSharedPreferences
    } catch (e: Exception) {
        Log.e(TAG, "EncryptedSharedPreferences unavailable; using unencrypted fallback")
        // Falls back to unencrypted - security concern
    }
}
```

**Red Flags:**
- Devices with broken KeyStore
- Rooted devices
- Security audit failure

### 8.3 Recommended Fixes

```kotlin
// Add session management
class SessionManager {
    fun isSessionValid(): Boolean {
        val token = tokenManager.getAccessToken() ?: return false
        return !isTokenExpired(token)
    }
    
    fun handleUnauthorized() {
        tokenManager.clear()
        navigationManager.navigateToLogin()
        showToast("Session expired. Please login again.")
    }
}
```

---

## 9. Service & Background Processing Issues

### 9.1 Foreground Service Startup Failures

```kotlin
// VideoRecordingViewModel.kt - Lines 184-188
try {
    ContextCompat.startForegroundService(context, serviceIntent)
} catch (e: Exception) {
    Log.e(TAG, "Failed to start foreground service for preview", e)
    // Silent failure - user thinks it's working
}
```

**Android 14+ Restrictions:**
- Stricter foreground service requirements
- May fail if notification permission denied

### 9.2 Service Disconnection

```kotlin
// VideoRecordingViewModel.kt - Lines 99-103
override fun onServiceDisconnected(name: ComponentName?) {
    recordingService = null
    isBound = false
    // Recording state becomes inconsistent!
}
```

**Red Flag:** No recovery mechanism

### 9.3 Wake Lock Management

```kotlin
// UploadService.kt - Lines 100-103
wakeLock = powerManager.newWakeLock(
    PowerManager.PARTIAL_WAKE_LOCK,
    "CameraAccess::UploadWakelock"
).apply { acquire(30 * 60 * 1000L) }  // 30 minutes max
```

**Issues:**
- Large uploads may exceed 30 minutes
- Battery drain complaints
- Wake lock not released on crash

### 9.4 Recommended Fixes

```kotlin
// Add service health monitoring
class ServiceHealthMonitor {
    fun checkServiceHealth(): ServiceStatus {
        return when {
            !isServiceRunning() -> ServiceStatus.NOT_RUNNING
            !isServiceResponsive() -> ServiceStatus.UNRESPONSIVE
            else -> ServiceStatus.HEALTHY
        }
    }
}
```

---

## 10. ML Model & Detection Issues

### 10.1 Model Loading Failures

```kotlin
// KiwiDetectionProcessor.kt - Lines 35-38
private val interpreter: Interpreter by lazy {
    val options = Interpreter.Options().apply { setNumThreads(4) }
    Interpreter(loadModelFile("model-hdr-320.tflite"), options)
}
```

**Failure Modes:**
- Model file missing from APK
- Insufficient memory for model
- Thread allocation failure

### 10.2 Inference Performance Issues

```kotlin
// Camera2Controller.kt - Lines 134-135
private val detectionIntervalMs = 40L // ~25 FPS detection updates
```

**Red Flags:**
- Frame drops during detection
- UI lag on slower devices
- Battery drain from constant ML inference

### 10.3 Detection Accuracy Issues

**Threshold Configuration:**
```kotlin
// KiwiDetectionProcessor.kt - Lines 31-33
private val detectionThreshold = 0.25f   // Low threshold = more false positives
private val nmsIouThreshold = 0.50f
private val kiwiClassIndex = 1
```

**User Reports:**
- "Detecting non-kiwi objects"
- "Not detecting kiwis in certain lighting"
- "Detection boxes flickering"

### 10.4 Recommended Fixes

```kotlin
// Add detection quality metrics
data class DetectionMetrics(
    val avgConfidence: Float,
    val detectionRate: Float,  // detections per second
    val falsePositiveRate: Float,
    val processingTimeMs: Long
)

// Add adaptive threshold
fun calculateAdaptiveThreshold(brightness: Int): Float {
    return when {
        brightness < 50 -> 0.35f   // Low light: higher threshold
        brightness > 200 -> 0.20f  // Bright: can afford lower threshold
        else -> 0.25f
    }
}
```

---

## 11. Database Issues

### 11.1 ObjectBox Initialization Failures

```kotlin
// RecordingRepository.kt - Lines 21-22
private val box: Box<RecordingEntity>?
    get() = ObjectBox.store?.boxFor(RecordingEntity::class.java)
```

**If `ObjectBox.store` is null:**
- All database operations return empty/null
- Recordings not saved
- No error shown to user

### 11.2 Data Corruption

**Risk:** App crashes during write operations

```kotlin
// RecordingRepository.kt - Lines 59-64
try {
    box?.put(entity)
} catch (e: Exception) {
    Log.e(TAG, "Failed to save recording to database", e)
    // Recording lost!
}
```

### 11.3 Recommended Fixes

```kotlin
// Add database health check
fun validateDatabase(): DatabaseHealth {
    return try {
        val store = ObjectBox.store ?: return DatabaseHealth.NOT_INITIALIZED
        val testQuery = store.boxFor(RecordingEntity::class.java).count()
        DatabaseHealth.HEALTHY
    } catch (e: Exception) {
        DatabaseHealth.CORRUPTED
    }
}

// Add backup before critical operations
suspend fun backupDatabase() {
    val store = ObjectBox.store ?: return
    val backupFile = File(context.filesDir, "objectbox_backup.mdb")
    store.backupToFile(backupFile)
}
```

---

## 12. Memory & Performance Issues

### 12.1 Memory Leaks Risk Areas

**TextureView Reference:**
```kotlin
// RecordingScreen.kt - Line 189
var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
// Not cleared when navigating away
```

**Image Processing:**
```kotlin
// RecordingScreen.kt - Lines 284-299
val bitmap = withContext(Dispatchers.Main) {
    try { tv.getBitmap(100, 100) } catch (e: Exception) { null }
}
// bitmap.recycle() called but inside try block
```

### 12.2 High Memory Usage Scenarios

- 4K HDR recording: ~2GB RAM needed
- Multiple recordings in queue: Each loads metadata
- TensorFlow Lite model: ~50-100MB

### 12.3 CPU/Battery Issues

**Continuous AE Loop:**
```kotlin
// RecordingScreen.kt - Lines 220-448
LaunchedEffect(Unit) {
    withContext(Dispatchers.Default) {
        while (isActive) {
            // Complex calculations every 33ms
            val delayTime = max(0L, 33L - loopTime)
            delay(delayTime)
        }
    }
}
```

### 12.4 Recommended Fixes

```kotlin
// Add memory monitoring
fun getMemoryStatus(): MemoryStatus {
    val runtime = Runtime.getRuntime()
    val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    val maxMb = runtime.maxMemory() / (1024 * 1024)
    return MemoryStatus(usedMb, maxMb, usedMb.toFloat() / maxMb)
}

// Add low memory handling
override fun onTrimMemory(level: Int) {
    when (level) {
        TRIM_MEMORY_RUNNING_LOW -> pauseNonEssentialProcessing()
        TRIM_MEMORY_RUNNING_CRITICAL -> stopDetection()
    }
}
```

---

## 13. How to Monitor & Debug

### 13.1 Key Log Tags to Monitor

| Tag | What It Shows |
|-----|---------------|
| `CAM2` | Camera operations, session config |
| `RecordingService` | Service lifecycle, GPS, CSV/GPX |
| `UploadService` | Upload progress, errors |
| `GreenDetection` | ML inference results |
| `RecordingRepository` | Database operations |
| `RetrofitClient` | API calls, auth issues |
| `TokenManager` | Token storage issues |

### 13.2 Embrace & New Relic Integration

```kotlin
// MainActivity.kt - Lines 18-30
try {
    Embrace.start(this)
} catch (t: Throwable) {
    Log.e(TAG, "Embrace.start failed", t)
}

try {
    NewRelic.withApplicationToken("...").start(this.applicationContext)
} catch (t: Throwable) {
    Log.e(TAG, "New Relic start failed", t)
}
```

**Monitor for:**
- Crash rates by device/OS version
- ANR (Application Not Responding)
- Network failure rates
- Session duration anomalies

### 13.3 Recommended Monitoring Dashboard

```
Dashboard: TAU App Health
├── Critical Metrics
│   ├── Crash Rate (target: <0.1%)
│   ├── Upload Success Rate (target: >95%)
│   ├── Recording Complete Rate (target: >99%)
│   └── Session Token Failures
├── Device Compatibility
│   ├── HDR Support Rate
│   ├── P010 Support Rate
│   └── API Level Distribution
├── User Journey
│   ├── Login Success Rate
│   ├── Recording Start Success
│   ├── Upload Complete Rate
│   └── Average Upload Time
└── Technical Metrics
    ├── Memory Usage P95
    ├── Detection FPS Average
    └── GPS Fix Time Average
```

---

## 14. Quick Reference: Error Messages

| User Sees | Technical Cause | Action |
|-----------|-----------------|--------|
| "This device does not support HDR" | No P010/HLG10 support | Use compatible device |
| "CAMERA permission missing ❌" | Permission denied | Grant in settings |
| "Permissions denied ❌" | Camera+Mic denied | Grant in settings |
| "Camera in use" | Another app using camera | Close other apps |
| "Camera disabled (Error 3)" | Device policy | Check MDM settings |
| "Preview session configure failed" | Hardware incompatibility | Report device model |
| "Recording setup failed" | MediaRecorder error | Restart app |
| "GPS is required" | GPS disabled | Enable GPS |
| "Waiting for GPS..." | No GPS signal | Go outdoors |
| "Connection problem" | No internet | Check connection |
| "Server error (401)" | Token expired | Re-login |
| "Server error (5xx)" | Server down | Try later |
| "Video file not found" | File deleted | Re-record |
| "Upload failed" | Network/server issue | Retry |
| "Service error" | Foreground service failed | Restart app |

---

## Appendix: Testing Checklist

Before releasing to clients, verify:

- [ ] App works on target Android versions (12, 13, 14, 15)
- [ ] HDR recording works on flagship devices
- [ ] All permissions handled gracefully
- [ ] GPS tracking works indoors (with delay) and outdoors
- [ ] Upload works on WiFi and cellular
- [ ] Upload resumes after network interruption
- [ ] Large videos (>1GB) upload successfully
- [ ] App survives configuration changes (rotation, PiP)
- [ ] Background recording continues when app minimized
- [ ] Low storage warning appears appropriately
- [ ] Session expiry handled gracefully
- [ ] Crash reporting captures all exceptions

---

*Last Updated: May 2026*
*Document Version: 1.0*
