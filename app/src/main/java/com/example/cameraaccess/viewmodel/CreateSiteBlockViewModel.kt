package com.example.cameraaccess.viewmodel

import android.app.Application
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import com.example.cameraaccess.data.model.ApiCache
import com.example.cameraaccess.data.model.SiteBlockCreateRequest
import com.example.cameraaccess.data.repositories.SiteBlockRepository
import com.example.cameraaccess.ui.createsiteblock.CreateSiteBlockUiState
import com.google.gson.Gson

class CreateSiteBlockViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SiteBlockRepository(app)
    private val cache = ApiCache(app)

    val uiState = mutableStateOf(CreateSiteBlockUiState())

    init {
        val data = repo.getCachedSiteBlock()
        if (data == null) {
            uiState.value = uiState.value.copy(
                error = "No cached data. Go back and press New Scan again."
            )
        } else {
            uiState.value = uiState.value.copy(data = data)
        }
    }

    // -------------------------
    // Form setters (UI calls these)
    // -------------------------
    fun setSelectedSiteIndex(i: Int) {
        uiState.value = uiState.value.copy(
            selectedSiteIndex = i,
            selectedBlockIndex = -1 // reset block when site changes
        )
    }

    fun setSelectedBlockIndex(i: Int) {
        uiState.value = uiState.value.copy(selectedBlockIndex = i)
    }

    fun setSelectedScanTypeIndex(i: Int) {
        uiState.value = uiState.value.copy(selectedScanTypeIndex = i)
    }

    fun setRowWidth(v: String) {
        uiState.value = uiState.value.copy(rowWidth = v)
    }

    fun setRowHeight(v: String) {
        uiState.value = uiState.value.copy(rowHeight = v)
    }

    fun setBayLength(v: String) {
        uiState.value = uiState.value.copy(bayLength = v)
    }

    fun setAddLens(v: Boolean) {
        uiState.value = uiState.value.copy(
            addLens = v,
            multiplier = if (!v) "" else uiState.value.multiplier
        )
    }

    fun setMultiplier(v: String) {
        uiState.value = uiState.value.copy(multiplier = v)
    }

    // -------------------------
    // Submit (called by UI)
    // -------------------------
    fun submit() {
        val state = uiState.value
        val data = state.data ?: run {
            uiState.value = uiState.value.copy(error = "Missing cached site data.")
            return
        }

        val sites = data.sites
        val scanTypes = data.scan_type

        // Validate selections
        if (state.selectedSiteIndex !in sites.indices) return
        val blocks = sites[state.selectedSiteIndex].blocks
        if (state.selectedBlockIndex !in blocks.indices) return
        if (state.selectedScanTypeIndex !in scanTypes.indices) return

        // Validate numbers
        val rw = state.rowWidth.toDoubleOrNull() ?: return
        val rh = state.rowHeight.toDoubleOrNull() ?: return
        val bl = state.bayLength.toDoubleOrNull() ?: return
        val addedMultiplier =
            if (state.addLens) state.multiplier.toDoubleOrNull() ?: 1.0 else 1.0

        val body = SiteBlockCreateRequest(
            blockId = blocks[state.selectedBlockIndex].block_id,
            scanTypeId = scanTypes[state.selectedScanTypeIndex].id,
            rowWidth = rw,
            rowHeight = rh,
            bayLength = bl,
            addedMultiplier = addedMultiplier
        )
        
        uiState.value = uiState.value.copy(isLoading = true, error = null)

        try {
            // Convert the request object to a JSON string
            val json = Gson().toJson(body)
            Log.d("SiteBlockDebug", "submit() saving to cache: body=$json")

            // Save the JSON string to the local cache
            cache.saveSubmitSiteBlock(json)
            cache.saveUseMainRearCameraForScan(state.addLens)

            // Trigger navigation
            uiState.value = uiState.value.copy(
                isLoading = false,
                navigateNext = true
            )
        } catch (e: Exception) {
            Log.e("SiteBlockDebug", "Failed to save to cache", e)
            uiState.value = uiState.value.copy(
                isLoading = false,
                error = "Failed to save settings: ${e.message}"
            )
        }
    }

    fun consumeNavigation() {
        uiState.value = uiState.value.copy(navigateNext = false)
    }
}
