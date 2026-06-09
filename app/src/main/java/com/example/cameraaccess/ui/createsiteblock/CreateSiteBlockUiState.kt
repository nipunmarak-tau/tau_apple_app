package com.example.cameraaccess.ui.createsiteblock

import com.example.cameraaccess.data.model.SiteBlockResponse

data class CreateSiteBlockUiState(
    val data: SiteBlockResponse? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val navigateNext: Boolean = false,

    // ---- Form state (MOVED here so it is "saved" in the ViewModel) ----
    val selectedSiteIndex: Int = -1,
    val selectedBlockIndex: Int = -1,
    val selectedScanTypeIndex: Int = -1,

    val rowWidth: String = "",
    val rowHeight: String = "",
    val bayLength: String = "",

    val addLens: Boolean = false,
    val multiplier: String = ""
)