package com.example.cameraaccess.utils

import android.content.Context

class DeviceManager(context: Context) {
    private val prefs = context.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)

    fun save(deviceType: String, deviceModel: String) {
        prefs.edit()
            .putString("device_type", deviceType)
            .putString("device_model", deviceModel)
            .apply()
    }

    fun getDeviceType(): String? = prefs.getString("device_type", null)
    fun getDeviceModel(): String? = prefs.getString("device_model", null)

    fun clear() {
        prefs.edit().clear().apply()
    }
}