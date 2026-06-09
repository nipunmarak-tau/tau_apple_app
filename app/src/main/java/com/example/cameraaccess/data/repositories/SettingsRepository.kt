package com.example.cameraaccess.data.repositories

import android.content.Context
import com.example.cameraaccess.data.db.ObjectBox
import com.example.cameraaccess.data.entities.CameraSettings

class SettingsRepository(context: Context) {

    private val box = ObjectBox.store?.boxFor(CameraSettings::class.java)

    fun loadSettings(): CameraSettings {
        val b = box ?: return CameraSettings()
        return b.all.firstOrNull() ?: CameraSettings()
    }

    fun saveSettings(settings: CameraSettings) {
        val b = box ?: return
        val existing = b.all.firstOrNull()
        if (existing == null) {
            b.put(settings)
        } else {
            settings.id = existing.id
            b.put(settings)
        }
    }
}
