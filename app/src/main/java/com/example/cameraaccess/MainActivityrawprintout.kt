package com.example.cameraaccess

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivityrawprintout : ComponentActivity() {

    private val uiText = mutableStateOf("Starting...")

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                uiText.value = dumpCameraCapabilities()
            } else {
                uiText.value = "Camera permission denied."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val text = remember { uiText }
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Text(
                    text = text.value,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

        if (granted) {
            uiText.value = dumpCameraCapabilities()
        } else {
            uiText.value = "Requesting CAMERA permission..."
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun dumpCameraCapabilities(): String {
        val cm = getSystemService(CameraManager::class.java)

        val sb = StringBuilder()
        sb.appendLine("CameraAccess (Camera2 capability dump)")
        sb.appendLine("Permission granted ✅")
        sb.appendLine("Found ${cm.cameraIdList.size} cameras")
        sb.appendLine()

        for (cameraId in cm.cameraIdList) {
            val c = cm.getCameraCharacteristics(cameraId)

            sb.appendLine("===== cameraId=$cameraId =====")
            sb.appendLine("LENS_FACING=${c.get(CameraCharacteristics.LENS_FACING)}")
            sb.appendLine("SENSOR_ORIENTATION=${c.get(CameraCharacteristics.SENSOR_ORIENTATION)}")

            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            sb.appendLine("AVAILABLE_CAPABILITIES=${caps?.joinToString() ?: "null"}")
            sb.appendLine()

            // 1) Characteristics keys
            val charKeys = c.keys
            sb.appendLine("CHARACTERISTICS KEYS (count=${charKeys.size})")
            charKeys.forEach { key ->
                sb.appendLine("  • ${key.name}")
            }
            sb.appendLine()

            // 2) CaptureRequest keys (what you can set)
            val reqKeys = c.availableCaptureRequestKeys ?: emptyList()
            sb.appendLine("CAPTURE REQUEST KEYS (count=${reqKeys.size})")
            reqKeys.forEach { key ->
                sb.appendLine("  • ${key.name}")
            }
            sb.appendLine()

            // 3) CaptureResult keys (what you can read back)
            val resKeys = c.availableCaptureResultKeys ?: emptyList()
            sb.appendLine("CAPTURE RESULT KEYS (count=${resKeys.size})")
            resKeys.forEach { key ->
                sb.appendLine("  • ${key.name}")
            }
            sb.appendLine()

            // 4) Session keys (API 28+ only)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val sessKeys = c.availableSessionKeys ?: emptyList()
                sb.appendLine("SESSION KEYS (count=${sessKeys.size})")
                sessKeys.forEach { key ->
                    sb.appendLine("  • ${key.name}")
                }
            } else {
                sb.appendLine("SESSION KEYS (API 28+ only) — skipped on this device API level")
            }

            sb.appendLine()
            sb.appendLine("----------------------------------------")
            sb.appendLine()

            Log.d("CAM", "Dumped keys for cameraId=$cameraId (chars=${charKeys.size}, req=${reqKeys.size}, res=${resKeys.size})")
        }

        val out = sb.toString()
        Log.d("CAM", out)
        return out
    }
}
