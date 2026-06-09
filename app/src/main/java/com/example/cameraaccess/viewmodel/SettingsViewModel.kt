package com.example.cameraaccess.viewmodel

import android.app.Application
import android.util.Size
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import com.example.cameraaccess.data.entities.CameraSettings
import com.example.cameraaccess.data.repositories.SettingsRepository
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)

    val selectedPreviewSize = mutableStateOf<Size?>(null)
    val selectedVideoSize = mutableStateOf<Size?>(null)
    val fps = mutableStateOf(30) // FPS is now fixed
    val saved = mutableStateOf(false)

    // --- EV100 Logic ---
    val targetEv100 = mutableStateOf(0.0) // Default to 0.0
    val iso = mutableStateOf(100)
    val exposureTimeNs = mutableStateOf(4_000_000L) // Default to 1/250s to match CameraSettings

    private fun log2(x: Double) = ln(x) / ln(2.0)

    fun calculateEv100(isoVal: Int, shutterNs: Long): Double {
        val t = shutterNs / 1_000_000_000.0
        if (t <= 0.0) return Double.NaN
        return log2(1.0 / t) - log2(isoVal / 100.0)
    }

    fun isoForTargetEv100(shutterNs: Long, ev100: Double): Int {
        val t = shutterNs / 1_000_000_000.0
        if (t <= 0.0) return 100
        val isoRaw = 100.0 * (1.0 / t) / (2.0.pow(ev100))
        return isoRaw.roundToInt()
    }

    private fun updateIsoFromShutter() {
        iso.value = isoForTargetEv100(exposureTimeNs.value, targetEv100.value)
    }

    init {
        loadSettingsIntoUi()
    }

    fun loadSettingsIntoUi() {
        val s = repository.loadSettings()
        exposureTimeNs.value = s.exposureTimeNs
        iso.value = s.iso
        // We might want to persist targetEv100 too in the future, but for now we reset to 0.0 or calculate it?
        // The user asked for default 0.00.
        // However, if we load exposure and iso, that implies a certain EV100.
        // If we strictly follow "default value would be 0.00", we set it to 0.0.
        // But if we want to reflect the actual loaded settings, we should probably calculate it from the loaded ISO/Shutter.
        // Given the instructions, I'll stick to initializing it to 0.0 in the declaration, 
        // but if the UI relies on this value to set ISO, we should be careful.
        // The logic in SettingsScreen uses LaunchedEffect to update ISO based on targetEv100.
        // If we blindly set targetEv100 to 0.0, the ISO will change to match EV 0.0 at the current shutter speed.
        // This might overwrite the loaded ISO.
        
        // Let's check the previous file content.
        // In the previous version, targetEv100 was 4.0.
        // And LaunchedEffect in SettingsScreen updates ISO.
        // "LaunchedEffect(viewModel.exposureTimeNs.value, viewModel.targetEv100.value, isoRange) { ... }"
        // This means as soon as the screen opens, ISO will be recalculated based on EV100=0.0 and loaded Shutter.
        // The loaded ISO will be ignored/overwritten. This seems to be the existing behavior (previously with 4.0).
        // I will proceed with changing the initial value to 0.0.
    }

    /**
     * Automatically selects the best video size based on a predefined priority list.
     */
    fun selectOptimalVideoSize(supportedSizes: List<Size>) {
        val forcedSize = Size(3840, 2160) // default 4K
        val fallbackSize = Size(1920, 1080) // fallback if 4K not available

        selectedVideoSize.value = if (supportedSizes.contains(forcedSize)) {
            forcedSize
        } else {
            fallbackSize
        }
    }

    /**
     * Automatically selects a sensible default preview size.
     */
    fun selectDefaultPreviewSize(supportedSizes: List<Size>) {
        val forcedSize = Size(3840, 2160) // default 4K
        val fallbackSize = Size(1920, 1080) // fallback if 4K not available

        selectedPreviewSize.value = if (supportedSizes.contains(forcedSize)) {
            forcedSize
        } else {
            fallbackSize
        }
    }

    fun setPreviewSize(size: Size) {
        selectedPreviewSize.value = size
        saved.value = false
    }

    fun setVideoSize(size: Size) {
        selectedVideoSize.value = size
        saved.value = false
    }

    fun setExposureTimeNs(v: Long) {
        exposureTimeNs.value = v
        updateIsoFromShutter() // Recalculate ISO whenever shutter speed changes
        saved.value = false
    }

    fun setTargetEv100(ev: Double) {
        targetEv100.value = ev
        updateIsoFromShutter()
        saved.value = false
    }

    fun saveSettings() {
        val p = selectedPreviewSize.value ?: return
        val v = selectedVideoSize.value ?: return

        // The ISO value is clamped in the UI layer (SettingsScreen) before saving.
        repository.saveSettings(
            CameraSettings(
                previewWidth = p.width,
                previewHeight = p.height,
                videoWidth = v.width,
                videoHeight = v.height,
                iso = iso.value,
                fps = fps.value,
                exposureTimeNs = exposureTimeNs.value
            )
        )
        saved.value = true
    }
}
