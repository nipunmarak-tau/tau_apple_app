package com.example.cameraaccess.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.cameraaccess.camera.CameraSelector
import com.example.cameraaccess.viewmodel.SettingsViewModel
import java.util.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onPreviewSelected: () -> Unit
) {
    val context = LocalContext.current
    val cm = remember { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    val ultraWideId = remember {
        CameraSelector.findRearUltraWideCameraId(cm) ?: cm.cameraIdList.firstOrNull { id ->
            val ch = cm.getCameraCharacteristics(id)
            ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }

    val characteristics = remember(ultraWideId) { ultraWideId?.let { cm.getCameraCharacteristics(it) } }
    val streamMap = remember(characteristics) { characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) }
    val isoRange = remember(characteristics) { characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) }
    val exposureRangeNs = remember(characteristics) { characteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) }

    val availablePreviewSizes: List<Size> = remember(streamMap) {
        streamMap?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
    }
    val availableVideoSizes: List<Size> = remember(streamMap) {
        streamMap?.getOutputSizes(android.media.MediaRecorder::class.java)?.toList().orEmpty()
    }

    // --- Automatic Resolution Selection ---
    LaunchedEffect(availableVideoSizes, availablePreviewSizes) {
        if (availableVideoSizes.isNotEmpty()) {
            viewModel.selectOptimalVideoSize(availableVideoSizes)
        }
        if (availablePreviewSizes.isNotEmpty()) {
            viewModel.selectDefaultPreviewSize(availablePreviewSizes)
        }
    }

    fun clampIso(v: Int): Int {
        val lo = isoRange?.lower ?: 100
        val hi = isoRange?.upper ?: 3200
        return min(hi, max(lo, v))
    }

    fun clampExposureNs(ns: Long): Long {
        val lo = exposureRangeNs?.lower ?: 1_000_000L
        val hi = exposureRangeNs?.upper ?: 100_000_000L
        return min(hi, max(lo, ns))
    }

    fun formatShutterFromNs(ns: Long): String {
        if (ns <= 0L) return "—"
        val t = ns / 1_000_000_000.0
        return if (t < 1.0) {
            val denom = (1.0 / t).roundToInt().coerceAtLeast(1)
            "1/$denom s"
        } else {
            val sec = String.format(Locale.US, "%.2f", t)
            "$sec s"
        }
    }
    
    val shutterOptionsNs: List<Long> = remember(exposureRangeNs) {
        val lo = exposureRangeNs?.lower ?: 1_000_000L
        val hi = exposureRangeNs?.upper ?: 100_000_000L
        val denominators = listOf(15, 30, 60, 120, 240, 250, 480, 500, 960, 1000, 2000, 4000, 8000)
        denominators
            .map { d -> (1_000_000_000L / d.toLong()) }
            .map { ns -> ns.coerceIn(lo, hi) }
            .distinct()
            .sorted()
    }

    LaunchedEffect(viewModel.exposureTimeNs.value, viewModel.targetEv100.value, isoRange) {
        viewModel.iso.value = clampIso(viewModel.isoForTargetEv100(viewModel.exposureTimeNs.value, viewModel.targetEv100.value))
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            
            Column {
                Text("Exposure Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                
                var textValue by remember { mutableStateOf(String.format(Locale.US, "%.2f", viewModel.targetEv100.value)) }
                var isError by remember { mutableStateOf(false) }

                LaunchedEffect(viewModel.targetEv100.value) {
                    val parsed = textValue.toDoubleOrNull()
                    if (parsed == null || kotlin.math.abs(parsed - viewModel.targetEv100.value) > 0.001) {
                        textValue = String.format(Locale.US, "%.2f", viewModel.targetEv100.value)
                        isError = false
                    }
                }

                OutlinedTextField(
                    value = textValue,
                    onValueChange = { str ->
                        textValue = str
                        val d = str.toDoubleOrNull()
                        if (d != null) {
                            if (d < -4.0 || d > 4.0) {
                                isError = true
                            } else {
                                isError = false
                                viewModel.setTargetEv100(d)
                            }
                        } else {
                            if (str.isNotEmpty() && str != "-") isError = true
                        }
                    },
                    label = { Text("Target EV100") },
                    isError = isError,
                    supportingText = {
                        if (isError) {
                            Text("Enter value between -4 and +4")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                Slider(
                    value = viewModel.targetEv100.value.toFloat(),
                    onValueChange = { viewModel.setTargetEv100(it.toDouble()) },
                    valueRange = -4f..4f,
                )

                Text("Set Shutter: ${formatShutterFromNs(viewModel.exposureTimeNs.value)}")
                Text("Auto ISO: ${viewModel.iso.value} (Range: ${isoRange?.lower ?: "N/A"} - ${isoRange?.upper ?: "N/A"})")
                val calculatedEv100 = viewModel.calculateEv100(viewModel.iso.value, viewModel.exposureTimeNs.value)
                Text("Calculated EV100: ${if (calculatedEv100.isFinite()) String.format(Locale.US, "%.2f", calculatedEv100) else "—"}")
            }


            Column {
                Text("Shutter Speed", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                SimpleDropdown(
                    items = shutterOptionsNs,
                    selected = viewModel.exposureTimeNs.value,
                    label = { ns -> ns?.let { formatShutterFromNs(it) } ?: "Select…" },
                    onSelect = { ns -> viewModel.setExposureTimeNs(clampExposureNs(ns)) }
                )
            }

            Column {
                Text("Video Resolution", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                // Display the auto-selected size. It is in a list for the dropdown to work.
                SimpleDropdown(
                    items = listOfNotNull(viewModel.selectedVideoSize.value),
                    selected = viewModel.selectedVideoSize.value,
                    label = { it?.let { "${it.width}×${it.height}" } ?: "Detecting…" },
                    onSelect = { /* No-op as it's auto-selected */ }
                )
            }

            Column {
                Text("Frame Rate (FPS)", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("${viewModel.fps.value} (Fixed)", style = MaterialTheme.typography.bodyLarge)
            }


            OutlinedButton(
                onClick = {
                    viewModel.saveSettings()
                    onPreviewSelected()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = viewModel.selectedPreviewSize.value != null && viewModel.selectedVideoSize.value != null
            ) {
                Text("Save Settings")
            }

            if (viewModel.saved.value) {
                Text(
                    text = "Settings saved ✅",
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun <T> SimpleDropdown(
    items: List<T>,
    selected: T?,
    label: (T?) -> String,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
        Text(label(selected))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        items.forEach { item ->
            DropdownMenuItem(
                text = { Text(label(item)) },
                onClick = {
                    expanded = false
                    onSelect(item)
                }
            )
        }
    }
}
