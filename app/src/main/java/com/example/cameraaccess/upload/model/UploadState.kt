package com.example.cameraaccess.upload.model

data class UploadState (
    val isUploading: Boolean = false,
    val progress: Float = 0f,
    val success: Boolean = false,
    val error: String? = null
)