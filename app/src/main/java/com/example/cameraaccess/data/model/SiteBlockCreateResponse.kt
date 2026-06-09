package com.example.cameraaccess.data.model

import com.google.gson.annotations.SerializedName

data class SiteBlockCreateResponse(
    @SerializedName("message") val message: String? = null,
    @SerializedName("site_block_id") val siteBlockId: Int? = null,
    @SerializedName("gpx_presigned_url") val gpxPresignedUrl: String? = null,
    @SerializedName("video_presigned_url") val videoPresignedUrl: String? = null,
    @SerializedName("csv_presigned_url") val csvPresignedUrl: String? = null,

    // these keys contain hyphens -> map with SerializedName
    @SerializedName("x-amz-server-side-encryption") val sse: String? = null,
    @SerializedName("x-amz-server-side-encryption-aws-kms-key-id") val kmsKeyId: String? = null,
    @SerializedName("gpx_x-amz-tagging") val gpxTagging: String? = null,
    @SerializedName("video_x-amz-tagging") val videoTagging: String? = null,
    @SerializedName("csv_x-amz-tagging") val csvTagging: String? = null
)