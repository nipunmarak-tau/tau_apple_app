package com.example.cameraaccess.upload.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.cameraaccess.upload.viewmodel.VideoUploadViewModel

@Composable
fun UploadSection(
    viewModel: VideoUploadViewModel,
    videoPath: String,
    gpxPath: String
) {
    val state by viewModel.uploadState.collectAsState()

    Column {
        Button(
            onClick = { viewModel.startUpload(videoPath, gpxPath) },
            enabled = !state.isUploading
        ) {
            Text("Upload Video")
        }

        if (state.isUploading) {
            LinearProgressIndicator(
                progress = state.progress,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { viewModel.cancelUpload() }) {
                Text("Cancel Upload")
            }
        }

        if (state.error != null) {
            state.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        if (state.success) {
            Text("Upload successful ✅")
        }
    }
}