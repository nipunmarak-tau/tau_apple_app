package com.example.cameraaccess.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tau.research.R
import com.example.cameraaccess.data.entities.RecordingEntity
import com.example.cameraaccess.data.model.AppError
import com.example.cameraaccess.monitoring.AppHealthMonitor
import com.example.cameraaccess.monitoring.AppIssue
import com.example.cameraaccess.monitoring.IssueSeverity
import com.example.cameraaccess.data.network.CompletedPart
import com.example.cameraaccess.data.network.MultiVideoCompleteRequest
import com.example.cameraaccess.data.network.MultiVideoStartRequest
import com.example.cameraaccess.data.network.RetrofitClient
import com.example.cameraaccess.data.repositories.RecordingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.system.measureTimeMillis

class UploadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadQueue = Channel<Long>(Channel.UNLIMITED)
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var recordingRepository: RecordingRepository
    private lateinit var notificationManager: NotificationManager

    companion object {
        const val NOTIFICATION_ID = 1
        const val NOTIFICATION_CHANNEL_ID = "UploadChannel"
        const val EXTRA_RECORDING_ID = "recording_id"
        private const val UPLOAD_PART_SIZE = 16L * 1024L * 1024L

        private const val TAG = "UploadService"
        private const val GPX_TAG = "UPLOAD_GPX"
        private const val CSV_TAG = "UPLOAD_CSV"
    }

    override fun onCreate() {
        super.onCreate()
        recordingRepository = RecordingRepository(application)
        notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        Log.d(TAG, "Service created")

        // Start processing the queue
        serviceScope.launch {
            uploadQueue.receiveAsFlow().collect { recordingId ->
                processUpload(recordingId)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recordingId = intent?.getLongExtra(EXTRA_RECORDING_ID, -1) ?: -1
        if (recordingId != -1L) {
            Log.d(TAG, "Queueing upload for ID: $recordingId")
            uploadQueue.trySend(recordingId)
        }

        val notification = createNotification("Upload queue active", 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    private suspend fun processUpload(recordingId: Long) {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CameraAccess::UploadWakelock"
        ).apply { acquire(30 * 60 * 1000L) }

        try {
            UploadStateBus.startUpload(recordingId)
            AppHealthMonitor.recordMetric(
                "upload_started",
                mapOf("recording_id" to recordingId.toString())
            )
            
            updateNotification("Starting upload for #$recordingId...", 0)
            val item = recordingRepository.getRecordingById(recordingId)
                ?: throw AppError.ValidationError("Recording $recordingId not found in database.")

            UploadStateBus.setStage("Uploading Video...")
            uploadRecordingInternal(item)

            UploadStateBus.setStage("Cleaning up...")
            val cleanup = recordingRepository.cleanupAfterSuccessfulUpload(item)
            Log.d(
                TAG,
                "Cleanup completed videoDeleted=${cleanup.videoDeleted} gpxDeleted=${cleanup.gpxDeleted} csvDeleted=${cleanup.csvDeleted}"
            )

            UploadStateBus.finishSuccess(recordingId)
            Log.d(TAG, "Upload + cleanup finished successfully for #$recordingId.")
            AppHealthMonitor.recordMetric(
                "upload_completed",
                mapOf("recording_id" to recordingId.toString())
            )
        } catch (e: Exception) {
            if (e !is CancellationException) {
                val userMsg = if (e is AppError) e.userMessage else "Upload failed: ${e.message}"
                Log.e(TAG, "Upload failed for #$recordingId: $userMsg", e)
                UploadStateBus.error(userMsg)
                AppHealthMonitor.captureException(
                    area = "upload.pipeline",
                    throwable = e,
                    attributes = mapOf("recording_id" to recordingId.toString())
                )
            }
        } finally {
            wakeLock?.takeIf { it.isHeld }?.release()
        }
    }

    private suspend fun uploadRecordingInternal(item: RecordingEntity) {

        /* ---------------- VIDEO MULTIPART UPLOAD ---------------- */

        val videoFile = resolveToReadableFile(item.videoPath, true)
            ?: throw AppError.ValidationError("Video file not found on device.")

        val videoBytes = videoFile.length()
        val partSize = UPLOAD_PART_SIZE
        val totalParts = ((videoBytes + partSize - 1) / partSize).toInt()

        val response = withContext(Dispatchers.IO) {
            RetrofitClient.api.startMultiVideo(
                MultiVideoStartRequest(
                    block_id = item.blockId,
                    scan_type = item.scanTypeId,
                    row_width = item.rowWidth,
                    row_height = item.rowHeight,
                    bay_length = item.bayLength,
                    added_multiplier = item.addedMultiplier,
                    video_part = totalParts
                )
            ).execute()
        }

        if (!response.isSuccessful) {
            throw AppError.ServerError(response.code(), "Failed to initiate video upload.")
        }

        val startResp = response.body() ?: throw AppError.UnexpectedError()

        val okHttp = buildS3OkHttp()
        val completedParts = mutableListOf<CompletedPart>()

        for (partNumber in 1..totalParts) {
            val offset = (partNumber - 1) * partSize
            val len = minOf(partSize, videoBytes - offset)
            val url =
                startResp.video_parts.firstOrNull { it.part_number == partNumber }?.upload_part_url
                    ?: throw AppError.UnexpectedError()

            val body = FileRangeRequestBody(
                videoFile,
                "application/octet-stream".toMediaType(),
                offset,
                len
            )

            val req = Request.Builder().url(url).put(body).build()
            withContext(Dispatchers.IO) {
                okHttp.newCall(req).execute().use {
                    if (!it.isSuccessful) {
                        AppHealthMonitor.reportIssue(
                            AppIssue(
                                key = "upload_video_part_failed",
                                message = "Video part upload failed",
                                severity = IssueSeverity.HIGH,
                                area = "upload.video",
                                attributes = mapOf(
                                    "part_number" to partNumber.toString(),
                                    "status_code" to it.code.toString()
                                )
                            )
                        )
                        throw AppError.ServerError(it.code, "Failed to upload video part $partNumber.")
                    }
                    val etag = it.header("ETag") ?: throw AppError.UnexpectedError()
                    completedParts += CompletedPart(partNumber, etag)
                }
            }

            UploadStateBus.updateProgress(partNumber.toFloat() / totalParts)
            updateNotification("Uploading video part $partNumber/$totalParts", (partNumber * 100 / totalParts))
            AppHealthMonitor.recordMetric(
                "upload_video_part_success",
                mapOf(
                    "part_number" to partNumber.toString(),
                    "total_parts" to totalParts.toString()
                )
            )
        }

        val completeResponse = withContext(Dispatchers.IO) {
            RetrofitClient.api.completeMultiVideo(
                MultiVideoCompleteRequest(
                    startResp.site_video_id,
                    startResp.video_upload_id,
                    startResp.video_key,
                    completedParts
                )
            ).execute()
        }

        if (!completeResponse.isSuccessful) {
            AppHealthMonitor.reportIssue(
                AppIssue(
                    key = "upload_complete_api_failed",
                    message = "Video complete API failed",
                    severity = IssueSeverity.CRITICAL,
                    area = "upload.video",
                    attributes = mapOf("status_code" to completeResponse.code().toString())
                )
            )
            throw AppError.ServerError(completeResponse.code(), "Failed to finalize video upload.")
        }

        /* ---------------- GPX UPLOAD ---------------- */
        UploadStateBus.setStage("Uploading GPX...")

        val gpxFile = resolveToReadableFile(item.gpxPath, false)
            ?: throw AppError.ValidationError("GPX file not found on device.")

        val elapsed = measureTimeMillis {
            val gpxReq = Request.Builder()
                .url(startResp.gpx_presigned_url)
                .put(RequestBody.create("application/gpx+xml".toMediaType(), gpxFile))
                .addHeader("x-amz-tagging", startResp.gpx_x_amz_tagging)
                .addHeader(
                    "x-amz-server-side-encryption",
                    startResp.`x-amz-server-side-encryption`
                )
                .addHeader(
                    "x-amz-server-side-encryption-aws-kms-key-id",
                    startResp.`x-amz-server-side-encryption-aws-kms-key-id`
                )
                .build()

            withContext(Dispatchers.IO) {
                okHttp.newCall(gpxReq).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        AppHealthMonitor.reportIssue(
                            AppIssue(
                                key = "upload_gpx_failed",
                                message = "GPX upload failed",
                                severity = IssueSeverity.HIGH,
                                area = "upload.gpx",
                                attributes = mapOf("status_code" to resp.code.toString())
                            )
                        )
                        throw AppError.ServerError(resp.code, "Failed to upload GPX file.")
                    }
                }
            }
        }

        Log.d(GPX_TAG, "GPX upload finished in ${elapsed}ms")

        /* ---------------- CSV UPLOAD ---------------- */
        UploadStateBus.setStage("Uploading CSV...")

        val csvPathResolved = item.videoPath.substringBeforeLast('.') + ".csv"
        val csvFile = resolveToReadableFile(csvPathResolved, false)
            ?: throw AppError.ValidationError("CSV file not found on device.")

        val csvElapsed = measureTimeMillis {
            val csvReq = Request.Builder()
                .url(startResp.csv_presigned_url)
                .put(csvFile.asRequestBody("text/csv".toMediaType()))
                .addHeader("x-amz-tagging", startResp.csv_x_amz_tagging)
                .addHeader(
                    "x-amz-server-side-encryption",
                    startResp.`x-amz-server-side-encryption`
                )
                .addHeader(
                    "x-amz-server-side-encryption-aws-kms-key-id",
                    startResp.`x-amz-server-side-encryption-aws-kms-key-id`
                )
                .build()

            withContext(Dispatchers.IO) {
                okHttp.newCall(csvReq).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        AppHealthMonitor.reportIssue(
                            AppIssue(
                                key = "upload_csv_failed",
                                message = "CSV upload failed",
                                severity = IssueSeverity.HIGH,
                                area = "upload.csv",
                                attributes = mapOf("status_code" to resp.code.toString())
                            )
                        )
                        throw AppError.ServerError(resp.code, "Failed to upload CSV file.")
                    }
                }
            }
        }

        Log.d(CSV_TAG, "CSV upload finished in ${csvElapsed}ms")
    }

    private fun buildS3OkHttp(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    private fun createNotification(text: String, progress: Int) =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Uploading")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_logo)
            .setOngoing(true)
            .setProgress(100, progress, progress == 0)
            .build()

    private fun updateNotification(text: String, progress: Int) {
        val notification = createNotification(text, progress)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Uploads",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun resolveToReadableFile(pathOrUri: String, isVideo: Boolean): File? = withContext(Dispatchers.IO) {
        val f = File(pathOrUri)
        if (f.exists()) return@withContext f
        if (pathOrUri.startsWith("content://")) {
            val uri = Uri.parse(pathOrUri)
            val out = File(cacheDir, "tmp_${System.currentTimeMillis()}")
            return@withContext try {
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(out).use { input.copyTo(it) }
                }
                out
            } catch (e: IOException) {
                Log.e(TAG, "Error resolving URI to file", e)
                null
            }
        }
        return@withContext null
    }
}

/* ---------- Helpers ---------- */

private class FileRangeRequestBody(
    private val file: File,
    private val contentType: MediaType?,
    private val offset: Long,
    private val length: Long
) : RequestBody() {

    override fun contentType() = contentType
    override fun contentLength() = length

    override fun writeTo(sink: BufferedSink) {
        RandomAccessFile(file, "r").use {
            it.seek(offset)
            val buf = ByteArray(8192)
            var remaining = length
            while (remaining > 0) {
                val r = it.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (r == -1) break
                sink.write(buf, 0, r)
                remaining -= r
            }
        }
    }
}
