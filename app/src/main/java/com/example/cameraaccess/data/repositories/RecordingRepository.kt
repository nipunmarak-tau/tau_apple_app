package com.example.cameraaccess.data.repositories

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import com.example.cameraaccess.data.db.ObjectBox
import com.example.cameraaccess.data.entities.RecordingEntity
import com.example.cameraaccess.data.entities.RecordingEntity_
import com.example.cameraaccess.monitoring.AppHealthMonitor
import com.example.cameraaccess.monitoring.AppIssue
import com.example.cameraaccess.monitoring.IssueSeverity
import com.example.cameraaccess.data.model.SiteBlockCreateRequest
import io.objectbox.Box
import io.objectbox.kotlin.flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.io.File

class RecordingRepository(context: Context) {

    private val box: Box<RecordingEntity>?
        get() = ObjectBox.store?.boxFor(RecordingEntity::class.java)

    companion object {
        private const val TAG = "RecordingRepository"
    }

    /* ---------------- CREATE ---------------- */

    suspend fun saveRecording(
        videoPath: String,
        gpxPath: String,
        siteBlock: SiteBlockCreateRequest
    ) = withContext(Dispatchers.IO) {
        val duration = getVideoDuration(videoPath)

        // If duration is 0 and file size is 0, the recording failed.
        // We shouldn't save a 0-byte file to the database.
        val file = File(videoPath)
        if (duration == 0L && (!file.exists() || file.length() == 0L)) {
            Log.e(TAG, "Rejecting recording save: Video file is empty or invalid. Path: $videoPath")
            AppHealthMonitor.reportIssue(
                AppIssue(
                    key = "recording_rejected_empty_video",
                    message = "Recording rejected due to empty/invalid video file",
                    severity = IssueSeverity.CRITICAL,
                    area = "recording.storage",
                    attributes = mapOf("video_path" to videoPath.takeLast(80))
                )
            )
            return@withContext
        }

        val entity = RecordingEntity(
            videoPath = videoPath,
            gpxPath = gpxPath,
            createdAt = System.currentTimeMillis(),

            blockId = siteBlock.blockId,
            scanTypeId = siteBlock.scanTypeId,
            rowWidth = siteBlock.rowWidth,
            rowHeight = siteBlock.rowHeight,
            bayLength = siteBlock.bayLength,
            addedMultiplier = siteBlock.addedMultiplier,
            duration = duration
        )

        try {
            box?.put(entity)
            Log.d(TAG, "Recording saved successfully: $videoPath (Duration: $duration ms)")
            AppHealthMonitor.recordMetric(
                "recording_saved",
                mapOf("duration_ms" to duration.toString())
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save recording to database", e)
            AppHealthMonitor.captureException("recording.database_save", e)
        }
    }

    private suspend fun getVideoDuration(videoPath: String): Long = withContext(Dispatchers.IO) {
        val file = File(videoPath)
        
        // Poll for up to 3 seconds (6 attempts x 500ms) to handle slow file finalization on Android 15
        repeat(6) { attempt ->
            val duration = tryGetDuration(videoPath)
            if (duration > 0) {
                if (attempt > 0) Log.d(TAG, "Duration found on attempt ${attempt + 1}")
                return@withContext duration
            }
            
            val exists = file.exists()
            val size = if (exists) file.length() else -1
            
            if (exists && size > 0) {
                Log.w(TAG, "File exists ($size bytes) but duration is 0 (attempt ${attempt + 1}). Retrying...")
            } else {
                Log.w(TAG, "File not ready or size 0 (attempt ${attempt + 1}). Retrying...")
            }
            delay(500)
        }
        
        Log.e(TAG, "Failed to retrieve duration for $videoPath after 6 retries. Size: ${if (file.exists()) file.length() else "Missing"}")
        AppHealthMonitor.reportIssue(
            AppIssue(
                key = "recording_duration_unavailable",
                message = "Failed to read duration after retries",
                severity = IssueSeverity.HIGH,
                area = "recording.metadata",
                attributes = mapOf("file_size" to (if (file.exists()) file.length().toString() else "missing"))
            )
        )
        return@withContext 0L
    }

    private fun tryGetDuration(filePath: String): Long {
        val file = File(filePath)

        // 1. Check if file exists and has data
        if (!file.exists() || file.length() == 0L) {
            return 0L
        }

        // 2. Use a FileDescriptor (Safest for Android 15)
        val retriever = MediaMetadataRetriever()
        return try {
            file.inputStream().use { inputStream ->
                retriever.setDataSource(inputStream.fd)
                val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                time?.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            // Only log if it's not a standard 'file still being written' issue
            if (file.length() > 1024) {
                 Log.w(TAG, "tryGetDuration failed for $filePath (${file.length()} bytes): ${e.message}")
            }
            0L
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {}
        }
    }

    /* ---------------- READ ---------------- */

    suspend fun getAllRecordings(): List<RecordingEntity> = withContext(Dispatchers.IO) {
        try {
            box?.all ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch all recordings", e)
            emptyList()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun getAllRecordingsFlow(): Flow<List<RecordingEntity>> {
        return box?.query()?.build()?.flow() ?: flowOf(emptyList())
    }

    suspend fun getLatestRecording(): RecordingEntity? = withContext(Dispatchers.IO) {
        try {
            box?.query()
                ?.orderDesc(RecordingEntity_.createdAt)
                ?.build()
                ?.findFirst()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch latest recording", e)
            null
        }
    }

    suspend fun getRecordingById(id: Long): RecordingEntity? = withContext(Dispatchers.IO) {
        try {
            box?.get(id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch recording by ID: $id", e)
            null
        }
    }

    /* ---------------- DELETE (UPLOAD SUCCESS PATH) ---------------- */

    suspend fun cleanupAfterSuccessfulUpload(entity: RecordingEntity): CleanupResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting cleanup for recording id=${entity.id}")

        val videoDeleted = deleteFileIfExists(entity.videoPath)
        val gpxDeleted = deleteFileIfExists(entity.gpxPath)
        val csvResolved = entity.videoPath.substringBeforeLast('.') + ".csv"
        val csvDeleted = deleteFileIfExists(csvResolved)

        deleteRecording(entity)

        Log.d(
            TAG,
            "Cleanup finished id=${entity.id} videoDeleted=$videoDeleted gpxDeleted=$gpxDeleted csvDeleted=$csvDeleted"
        )

        CleanupResult(
            videoDeleted = videoDeleted,
            gpxDeleted = gpxDeleted,
            csvDeleted = csvDeleted
        )
    }

    private suspend fun deleteRecording(entity: RecordingEntity) = withContext(Dispatchers.IO) {
        try {
            if (entity.id != 0L) {
                box?.remove(entity.id)
            } else {
                box?.remove(entity)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete recording from database", e)
        }
    }

    private suspend fun deleteFileIfExists(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (!file.exists()) {
                true // already gone = OK
            } else {
                val deleted = file.delete()
                if (!deleted) {
                    Log.w(TAG, "Failed to delete file: $path")
                }
                deleted
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied while deleting file: $path", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error deleting file: $path", e)
            false
        }
    }

    /* ---------------- RESULT ---------------- */

    data class CleanupResult(
        val videoDeleted: Boolean,
        val gpxDeleted: Boolean,
        val csvDeleted: Boolean
    )
}
