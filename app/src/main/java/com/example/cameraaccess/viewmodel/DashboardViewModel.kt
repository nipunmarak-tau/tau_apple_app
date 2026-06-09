package com.example.cameraaccess.viewmodel

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.cameraaccess.data.entities.RecordingEntity
import com.example.cameraaccess.data.model.ApiCache
import com.example.cameraaccess.data.model.AppError
import com.example.cameraaccess.data.repositories.DashboardRepository
import com.example.cameraaccess.data.repositories.RecordingRepository
import com.example.cameraaccess.service.UploadService
import com.example.cameraaccess.service.UploadStateBus
import com.example.cameraaccess.ui.dashboard.DashboardUiState
import com.example.cameraaccess.utils.NetworkConnectivityObserver
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = DashboardRepository(app)
    private val recordingRepository = RecordingRepository(app)
    val uiState = mutableStateOf(DashboardUiState())
    private val connectivityObserver = NetworkConnectivityObserver(app)

    init {
        // Initial load of device info
        viewModelScope.launch {
            val (type, model) = repo.getDeviceInfo()
            uiState.value = uiState.value.copy(
                deviceType = type,
                deviceModel = model
            )
        }

        // Reactive recordings list
        recordingRepository.getAllRecordingsFlow()
            .onEach { list ->
                uiState.value = uiState.value.copy(recordings = list)
            }
            .launchIn(viewModelScope)

        // Prefetch site block data so it's available for offline use
        repo.fetchSiteBlock { 
            // Data is cached in repository/cache layer automatically upon success.
        }

        connectivityObserver.observe()
            .onEach { isOnline ->
                uiState.value = uiState.value.copy(isOnline = isOnline)
            }
            .launchIn(viewModelScope)

        // ✅ React to upload lifecycle events
        UploadStateBus.state
            .onEach { state ->
                val currentState = uiState.value
                
                var newState = currentState.copy(
                    uploadingRecordingId = state.currentRecordingId,
                    uploadProgress = state.progress,
                    uploadStage = state.stage,
                    error = state.error
                )

                // Synchronize local queue with service state
                val idRemoving = state.currentRecordingId ?: state.finishedRecordingId
                if (idRemoving != null) {
                    newState = newState.copy(
                        uploadQueue = newState.uploadQueue.filter { it != idRemoving }
                    )
                }

                // On error, we stop the queue for safety
                if (state.error != null) {
                    newState = newState.copy(
                        uploadingRecordingId = null,
                        uploadQueue = emptyList()
                    )
                }

                uiState.value = newState
            }
            .launchIn(viewModelScope)
    }

    fun startNewScan() {
        val hasCachedData = ApiCache(getApplication()).getFetchedSiteBlock() != null

        if (!uiState.value.isOnline) {
            // Always navigate if offline, allowing user to proceed even if cache might be missing
            uiState.value = uiState.value.copy(navigateToMetadata = true)
            return
        }

        uiState.value = uiState.value.copy(isLoading = true)
        repo.fetchSiteBlock { result ->
            if (result.isSuccess) {
                uiState.value = uiState.value.copy(
                    isLoading = false,
                    navigateToMetadata = true
                )
            } else {
                // If fetch fails but we have cached data, allow navigation anyway
                if (hasCachedData) {
                    uiState.value = uiState.value.copy(
                        isLoading = false,
                        navigateToMetadata = true,
                        error = null
                    )
                } else {
                    val exception = result.exceptionOrNull()
                    val errorMsg = if (exception is AppError) {
                        exception.userMessage
                    } else {
                        exception?.message ?: "Failed to fetch scan configuration."
                    }
                    uiState.value = uiState.value.copy(
                        isLoading = false,
                        error = errorMsg
                    )
                }
            }
        }
    }

    fun toggleSelection(recordingId: Long) {
        val currentSelected = uiState.value.selectedIds
        val newSelected = if (currentSelected.contains(recordingId)) {
            currentSelected - recordingId
        } else {
            currentSelected + recordingId
        }
        uiState.value = uiState.value.copy(selectedIds = newSelected)
    }

    fun uploadSelected() {
        val selected = uiState.value.selectedIds
        if (selected.isEmpty()) return
        if (!uiState.value.isOnline) {
            uiState.value = uiState.value.copy(error = "You are offline. Please connect to the internet to upload.")
            return
        }

        val currentState = uiState.value
        val listToQueue = selected.toList().filter { id ->
            id != currentState.uploadingRecordingId && !currentState.uploadQueue.contains(id)
        }

        if (listToQueue.isEmpty()) {
            uiState.value = currentState.copy(selectedIds = emptySet())
            return
        }

        // Add to local queue immediately for UI responsiveness
        uiState.value = currentState.copy(
            selectedIds = emptySet(),
            uploadQueue = currentState.uploadQueue + listToQueue
        )

        // Send all to the service. The service's internal Channel will handle sequential execution.
        listToQueue.forEach { id ->
            startUploadService(id)
        }
    }

    fun uploadRecording(item: RecordingEntity) {
        if (!uiState.value.isOnline) {
            uiState.value = uiState.value.copy(error = "You are offline. Please connect to the internet to upload.")
            return
        }

        val currentState = uiState.value
        
        // Prevent adding same item multiple times
        if (currentState.uploadingRecordingId == item.id || currentState.uploadQueue.contains(item.id)) {
            return
        }

        uiState.value = currentState.copy(
            uploadQueue = currentState.uploadQueue + item.id
        )
        startUploadService(item.id)
    }

    private fun startUploadService(recordingId: Long) {
        val intent = Intent(getApplication(), UploadService::class.java).apply {
            putExtra(UploadService.EXTRA_RECORDING_ID, recordingId)
        }
        getApplication<Application>().startService(intent)
    }

    fun deleteRecording(item: RecordingEntity) {
        viewModelScope.launch {
            uiState.value = uiState.value.copy(isLoading = true, error = null)
            try {
                recordingRepository.cleanupAfterSuccessfulUpload(item)
                
                // If it was in queue, remove it
                val currentState = uiState.value
                val newQueue = currentState.uploadQueue.filter { it != item.id }
                
                uiState.value = currentState.copy(
                    isLoading = false,
                    uploadQueue = newQueue
                )
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(
                    isLoading = false,
                    error = "Failed to delete recording. Please try again."
                )
            }
        }
    }

    fun logout() {
        repo.logout()
        uiState.value = uiState.value.copy(loggedOut = true)
    }

    fun consumeNavigation() {
        uiState.value = uiState.value.copy(
            navigateToMetadata = false,
            loggedOut = false
        )
    }

    fun clearError() {
        uiState.value = uiState.value.copy(error = null)
    }
}
