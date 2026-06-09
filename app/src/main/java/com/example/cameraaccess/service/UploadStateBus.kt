package com.example.cameraaccess.service

import com.example.cameraaccess.monitoring.AppHealthMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class UploadState(
    val currentRecordingId: Long? = null,
    val progress: Float = 0f,
    val stage: String = "",
    val error: String? = null,
    val isUploading: Boolean = false,
    val finishedRecordingId: Long? = null
)

object UploadStateBus {

    private val _state = MutableStateFlow(UploadState())
    val state = _state.asStateFlow()

    fun startUpload(id: Long) {
        AppHealthMonitor.recordMetric(
            "upload_state_start",
            mapOf("recording_id" to id.toString())
        )
        _state.update {
            it.copy(
                currentRecordingId = id,
                progress = 0f,
                stage = "Preparing...",
                error = null,
                isUploading = true,
                finishedRecordingId = null
            )
        }
    }

    fun setStage(stage: String) {
        AppHealthMonitor.recordMetric(
            "upload_stage_changed",
            mapOf("stage" to stage.take(50))
        )
        _state.update {
            it.copy(
                stage = stage,
                isUploading = true
            )
        }
    }

    fun updateProgress(progress: Float) {
        _state.update {
            it.copy(
                progress = progress,
                isUploading = true
            )
        }
    }

    fun finishSuccess(id: Long) {
        AppHealthMonitor.recordMetric(
            "upload_state_success",
            mapOf("recording_id" to id.toString())
        )
        _state.update {
            it.copy(
                currentRecordingId = null,
                progress = 1f,
                error = null,
                isUploading = false,
                finishedRecordingId = id
            )
        }
    }

    fun error(message: String) {
        AppHealthMonitor.recordMetric(
            "upload_state_error",
            mapOf("message" to message.take(80))
        )
        _state.update {
            it.copy(
                error = message,
                isUploading = false
            )
        }
    }

    fun reset() {
        _state.value = UploadState()
    }
}
