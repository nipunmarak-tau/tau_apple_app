package com.example.cameraaccess.upload.repository

import android.util.Log
import com.example.cameraaccess.data.model.ApiCache
import com.example.cameraaccess.data.model.SiteBlockCreateResponse
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.ceil
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class VideoUploadRepository(
    private val apiCache: ApiCache
) {
    private val okHttpClient: OkHttpClient = OkHttpClient()

    suspend fun uploadVideoInChunks(
        videoPath: String,
        presignedUrls: List<String>,
        chunkSize: Long = 500L * 1024 * 1024, // 500MB
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): List<String> {

        val file = File(videoPath)
        val fileSize = file.length()
        val totalParts = ceil(fileSize / chunkSize.toDouble()).toInt()

        val raf = RandomAccessFile(file, "r")
        val eTags = mutableListOf<String>()

        var uploadedBytes = 0L

        for (partIndex in 0 until totalParts) {
            coroutineContext.ensureActive()
            if (isCancelled()) break

            val offset = partIndex * chunkSize
            val remaining = fileSize - offset
            val currentChunkSize = minOf(chunkSize, remaining)

            val buffer = ByteArray(currentChunkSize.toInt())
            raf.seek(offset)
            raf.readFully(buffer)

            val requestBody = buffer.toRequestBody("video/mp4".toMediaType())

            val url = presignedUrls[partIndex]
            Log.d("UPLOAD", "Uploading chunk ${partIndex + 1}/$totalParts to $url")

            val request = Request.Builder()
                .url(url)
                .put(requestBody)
                .build()

            val response = okHttpClient.newCall(request).execute()

            val responseBody = response.body?.string()
            Log.d(
                "UPLOAD",
                "Chunk ${partIndex + 1} response: code=${response.code}, headers=${response.headers}"
            )

            if (!response.isSuccessful) {
                Log.e(
                    "UPLOAD",
                    "Chunk ${partIndex + 1} FAILED: code=${response.code}, body=$responseBody"
                )
                throw Exception(
                    "Chunk ${partIndex + 1} failed: HTTP ${response.code}, body=$responseBody"
                )
            }

            val etag = response.header("ETag") ?: ""
            eTags.add(etag)

            uploadedBytes += currentChunkSize
            onProgress(uploadedBytes.toFloat() / fileSize.toFloat())
        }

        raf.close()
        return eTags
    }

    fun uploadCsvFile(
        csvPath: String,
        presignedUrl: String
    ) {
        val file = File(csvPath)
        val body = file.asRequestBody("text/csv".toMediaType())

        val request = Request.Builder()
            .url(presignedUrl)
            .put(body)
            .build()

        val response = okHttpClient.newCall(request).execute()
        val responseBody = response.body?.string()

        Log.d(
            "UPLOAD",
            "CSV upload response: code=${response.code}, headers=${response.headers}"
        )

        if (!response.isSuccessful) {
            Log.e(
                "UPLOAD",
                "CSV upload FAILED: code=${response.code}, body=$responseBody"
            )
            throw Exception("CSV upload failed: HTTP ${response.code}, body=$responseBody")
        }
    }

    fun uploadGpxFile(
        gpxPath: String,
        presignedUrl: String
    ) {
        val file = File(gpxPath)
        val body = file.asRequestBody("application/gpx+xml".toMediaType())

        val request = Request.Builder()
            .url(presignedUrl)
            .put(body)
            .build()

        val response = okHttpClient.newCall(request).execute()
        val responseBody = response.body?.string()

        Log.d(
            "UPLOAD",
            "GPX upload response: code=${response.code}, headers=${response.headers}"
        )

        if (!response.isSuccessful) {
            Log.e(
                "UPLOAD",
                "GPX upload FAILED: code=${response.code}, body=$responseBody"
            )
            throw Exception("GPX upload failed: HTTP ${response.code}, body=$responseBody")
        }
    }

    fun getPresignedVideoUrls(): List<String> {
        val submitResponseJson = apiCache.getSubmitSiteBlock() ?: return emptyList()
        val submitResponse =
            Gson().fromJson(submitResponseJson, SiteBlockCreateResponse::class.java)

        return submitResponse.videoPresignedUrl?.let { listOf(it) } ?: emptyList()
    }

    fun getPresignedGpxUrl(): String? {
        val submitResponseJson = apiCache.getSubmitSiteBlock() ?: return null
        val submitResponse =
            Gson().fromJson(submitResponseJson, SiteBlockCreateResponse::class.java)

        return submitResponse.gpxPresignedUrl
    }

    fun getPresignedCsvUrl(): String? {
        val submitResponseJson = apiCache.getSubmitSiteBlock() ?: return null
        val submitResponse =
            Gson().fromJson(submitResponseJson, SiteBlockCreateResponse::class.java)

        return submitResponse.csvPresignedUrl
    }
}
