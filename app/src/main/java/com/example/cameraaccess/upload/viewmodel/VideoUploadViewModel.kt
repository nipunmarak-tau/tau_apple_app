package com.example.cameraaccess.upload.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cameraaccess.data.model.ApiCache
import com.example.cameraaccess.upload.model.UploadState
import com.example.cameraaccess.upload.repository.VideoUploadRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class VideoUploadViewModel(application: Application) : AndroidViewModel(application) {

    private val apiCache = ApiCache(application)
    private val repository: VideoUploadRepository = VideoUploadRepository(apiCache)

    private val _uploadState = MutableStateFlow(UploadState())
    val uploadState = _uploadState.asStateFlow()

    private var uploadJob: Job? = null

    fun startUpload(videoPath: String, gpxPath: String) {
        uploadJob?.cancel()

        uploadJob = viewModelScope.launch {
            try {
                _uploadState.value = UploadState(isUploading = true)

                val videoUrls = repository.getPresignedVideoUrls()
                val gpxUrl = repository.getPresignedGpxUrl()
                val csvUrl = repository.getPresignedCsvUrl()
                val csvPath = videoPath.substringBeforeLast('.') + ".csv"

                if (videoUrls.isEmpty() || gpxUrl.isNullOrEmpty() || csvUrl.isNullOrEmpty()) {
                    throw Exception("Missing presigned URLs")
                }

                val etags = repository.uploadVideoInChunks(
                    videoPath = videoPath,
                    presignedUrls = videoUrls,
                    onProgress = {
                        _uploadState.value =
                            _uploadState.value.copy(progress = it)
                    },
                    isCancelled = { !isActive }
                )

                repository.uploadGpxFile(
                    gpxPath = gpxPath,
                    presignedUrl = gpxUrl
                )

                repository.uploadCsvFile(
                    csvPath = csvPath,
                    presignedUrl = csvUrl
                )

                _uploadState.value = UploadState(
                    isUploading = false,
                    progress = 1f,
                    success = true
                )

            } catch (e: Exception) {
                _uploadState.value = UploadState(
                    isUploading = false,
                    error = e.message
                )
            }
        }
    }

    fun cancelUpload() {
        uploadJob?.cancel()
        _uploadState.value = UploadState(error = "Upload cancelled")
    }
}