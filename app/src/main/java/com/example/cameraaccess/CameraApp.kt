package com.example.cameraaccess

import android.app.Application
import com.example.cameraaccess.data.db.ObjectBox
import com.example.cameraaccess.data.network.RetrofitClient
import com.example.cameraaccess.monitoring.AppHealthMonitor

class CameraApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ObjectBox.init(this)
        RetrofitClient.init(this)
        AppHealthMonitor.init(this)
        AppHealthMonitor.recordDeviceHealthSnapshot()
    }
}