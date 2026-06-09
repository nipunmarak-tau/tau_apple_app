package com.example.cameraaccess.data.network

import com.example.cameraaccess.data.model.LoginRequest
import com.example.cameraaccess.data.model.LoginResponse
import com.example.cameraaccess.data.model.AppDeviceRequest
import com.example.cameraaccess.data.model.SiteBlockCreateRequest
import com.example.cameraaccess.data.model.SiteBlockCreateResponse
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT

// -------- Multi-video models (kept here for simplicity) --------
data class MultiVideoStartRequest(
    val block_id: Int,
    val scan_type: Int,
    val row_width: Double,
    val row_height: Double,
    val bay_length: Double,
    val added_multiplier: Double,
    val video_part: Int
)

data class VideoPartUrl(
    val part_number: Int,
    val upload_part_url: String
)

data class MultiVideoStartResponse(
    val site_video_id: Long,
    val gpx_presigned_url: String,
    val gpx_x_amz_tagging: String,
    val csv_presigned_url: String,
    val csv_x_amz_tagging: String,
    val video_key: String,
    val video_upload_id: String,
    val video_parts: List<VideoPartUrl>,
    val `x-amz-server-side-encryption`: String,
    val `x-amz-server-side-encryption-aws-kms-key-id`: String
)

data class CompletedPart(
    val PartNumber: Int,
    val ETag: String
)

data class MultiVideoCompleteRequest(
    val site_video_id: Long,
    val upload_id: String,
    val video_key: String,
    val video_parts: List<CompletedPart>
)

// -------- API --------
interface ApiService {

    @POST("api/token")
    fun login(@Body request: LoginRequest): Call<LoginResponse>

    @POST("api/app/device")
    fun postDevice(@Body body: AppDeviceRequest): Call<ResponseBody>

    @GET("api/app/site-block")
    fun getSiteBlock(): Call<ResponseBody>

    @POST("api/app/site-block")
    fun createSiteBlock(@Body body: SiteBlockCreateRequest): Call<SiteBlockCreateResponse>

    @POST("api/app/multi-video")
    fun startMultiVideo(@Body body: MultiVideoStartRequest): Call<MultiVideoStartResponse>

    @PUT("api/app/multi-video")
    fun completeMultiVideo(@Body body: MultiVideoCompleteRequest): Call<ResponseBody>
}
