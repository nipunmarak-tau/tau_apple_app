package com.example.cameraaccess.data.model

data class SiteBlockResponse(
    val id: Int?,
    val name: String?,
    val company: String?,
    val crop_type: String?,
    val sites: List<SiteItem> = emptyList(),
    val scan_type: List<ScanTypeItem> = emptyList()
)

data class SiteItem(
    val site_id: Int,
    val address: String?,
    val total_blocks: Int?,
    val reject_percent: Double?,
    val blocks: List<BlockItem> = emptyList()
)

data class BlockItem(
    val block_id: Int,
    val block_name: String?,
    val total_rows: Int?
)

data class ScanTypeItem(
    val id: Int,
    val name: String?
)
