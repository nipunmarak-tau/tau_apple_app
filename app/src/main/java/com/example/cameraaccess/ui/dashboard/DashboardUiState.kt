package com.example.cameraaccess.ui.dashboard

import com.example.cameraaccess.data.entities.RecordingEntity

data class DashboardUiState(
    val isLoading: Boolean = false,
    val recordings: List<RecordingEntity> = emptyList(),
    val error: String? = null,
    val navigateToMetadata: Boolean = false,
    val loggedOut: Boolean = false,
    val deviceType: String = "",
    val deviceModel: String = "",
    val uploadingRecordingId: Long? = null,
    val uploadProgress: Float = 0f,
    val uploadStage: String = "",
    val isOnline: Boolean = true,
    val selectedIds: Set<Long> = emptySet(),
    val uploadQueue: List<Long> = emptyList()
)
