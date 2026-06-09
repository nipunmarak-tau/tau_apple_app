package com.example.cameraaccess.data.model

import com.google.gson.annotations.SerializedName

data class SiteBlockCreateRequest(
    @SerializedName("block_id") val blockId: Int,
    @SerializedName("scan_type") val scanTypeId: Int,   // or rename param to scanType if you want
    @SerializedName("row_width") val rowWidth: Double,
    @SerializedName("row_height") val rowHeight: Double,
    @SerializedName("bay_length") val bayLength: Double,
    @SerializedName("added_multiplier") val addedMultiplier: Double
)
