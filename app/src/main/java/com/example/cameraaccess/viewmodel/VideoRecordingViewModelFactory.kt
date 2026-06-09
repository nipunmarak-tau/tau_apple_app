package com.example.cameraaccess.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.cameraaccess.controller.Camera2Controller

class VideoRecordingViewModelFactory(
    private val controller: Camera2Controller,
    private val context: Context
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VideoRecordingViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            val app = context.applicationContext as android.app.Application
            return VideoRecordingViewModel(controller, context, app) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
