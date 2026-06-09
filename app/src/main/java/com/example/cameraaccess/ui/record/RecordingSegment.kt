package com.example.cameraaccess.ui.record

data class RecordingSegment(
    val videoPath: String,
    val gpxPath: String,
    val createdAt: Long = System.currentTimeMillis()
)
