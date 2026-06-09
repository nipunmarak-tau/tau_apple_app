package com.example.cameraaccess.data.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

@Entity
data class CameraSettings(
    @Id var id: Long = 0,

    var previewWidth: Int = 1920,
    var previewHeight: Int = 1080,

    var videoWidth: Int = 1920,
    var videoHeight: Int = 1080,

    var iso: Int = 100,
    var fps: Int = 30,
    var exposureTimeNs: Long = 4_000_000L
)
