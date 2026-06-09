package com.example.cameraaccess.data.model

import android.content.Context

class ApiCache(context: Context) {
    private val prefs = context.getSharedPreferences("tau_cache", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SITE_BLOCK_FETCH = "site_block_fetch_json"
        private const val KEY_SITE_BLOCK_SUBMIT = "site_block_submit_json"
        /** When true, recording uses the main rear camera; when false, ultra-wide (default). Set from Create Site Block "Add lens multiplier". */
        private const val KEY_USE_MAIN_REAR_CAMERA = "use_main_rear_camera_for_scan"
    }
    /* ---------- FETCH SITE BLOCK ---------- */
    fun saveFetchedSiteBlock(json: String) {
        prefs.edit().putString(KEY_SITE_BLOCK_FETCH, json).apply()
    }

    fun getFetchedSiteBlock(): String? {
        return prefs.getString(KEY_SITE_BLOCK_FETCH, null)
    }

    /* ---------- SUBMIT SITE BLOCK (PREFETCH URL) ---------- */
    fun saveSubmitSiteBlock(json: String) {
        prefs.edit().putString(KEY_SITE_BLOCK_SUBMIT, json).apply()
    }

    fun getSubmitSiteBlock(): String? {
        return prefs.getString(KEY_SITE_BLOCK_SUBMIT, null)
    }

    fun saveUseMainRearCameraForScan(useMainRear: Boolean) {
        prefs.edit().putBoolean(KEY_USE_MAIN_REAR_CAMERA, useMainRear).apply()
    }

    fun getUseMainRearCameraForScan(): Boolean {
        return prefs.getBoolean(KEY_USE_MAIN_REAR_CAMERA, false)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}