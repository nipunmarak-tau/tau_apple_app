package com.example.cameraaccess.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

object CameraSelector {
    fun findRearUltraWideCameraId(cm: CameraManager): String? {
        var bestId: String? = null
        var bestFocal = Float.MAX_VALUE

        for (id in cm.cameraIdList) {
            val ch = cm.getCameraCharacteristics(id)
            val facing = ch.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            val focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: continue
            val minF = focals.minOrNull() ?: continue

            if (minF < bestFocal) {
                bestFocal = minF
                bestId = id
            }
        }
        return bestId
    }

    /**
     * Rear "main" wide camera (primary ~24mm-equivalent), as opposed to the ultra-wide
     * lens selected by [findRearUltraWideCameraId]. Uses physical focal length hints:
     * when the widest rear lens is below ~3.4mm it is treated as ultra-wide and the
     * next rear camera is returned; otherwise the first rear camera is already the main wide.
     */
    fun findRearMainCameraId(cm: CameraManager): String? {
        val entries = cm.cameraIdList.mapNotNull { id ->
            val ch = cm.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
                return@mapNotNull null
            }
            val focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: return@mapNotNull null
            val minF = focals.minOrNull() ?: return@mapNotNull null
            id to minF
        }.sortedBy { it.second }

        if (entries.isEmpty()) return null
        if (entries.size == 1) return entries[0].first

        val minFocal = entries[0].second
        // Ultra-wide rear modules are typically well under ~3.4mm physical focal length.
        return if (minFocal < 3.4f) {
            entries[1].first
        } else {
            entries[0].first
        }
    }
}