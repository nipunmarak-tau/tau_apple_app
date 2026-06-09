package com.example.cameraaccess.data.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

@Entity
data class RecordingEntity(
    @Id var id: Long = 0,

    val videoPath: String,
    val gpxPath: String,
    val createdAt: Long,
    val blockId: Int,
    val scanTypeId: Int,
    val rowWidth: Double,
    val rowHeight: Double,
    val bayLength: Double,
    val addedMultiplier: Double,
    val duration: Long = 0
)