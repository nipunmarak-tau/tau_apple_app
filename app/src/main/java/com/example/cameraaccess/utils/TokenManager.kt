package com.example.cameraaccess.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.cameraaccess.monitoring.AppHealthMonitor
import com.example.cameraaccess.monitoring.AppIssue
import com.example.cameraaccess.monitoring.IssueSeverity

class TokenManager(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences = createPrefs(appContext)

    private val plainLegacyPrefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_PLAIN_LEGACY, Context.MODE_PRIVATE)

    init {
        migrateFromPlainLegacy()
    }

    private fun createPrefs(context: Context): SharedPreferences {
        return try {
            createEncryptedPrefs(context)
        } catch (firstError: Exception) {
            // Some OEM keystore states can be healed by recreating the encrypted prefs file once.
            Log.w(TAG, "EncryptedSharedPreferences init failed, retrying after reset", firstError)
            try {
                context.deleteSharedPreferences(PREFS_ENCRYPTED_NAME)
                createEncryptedPrefs(context)
            } catch (secondError: Exception) {
                reportEncryptionFallback(secondError)
                context.getSharedPreferences(PREFS_FALLBACK, Context.MODE_PRIVATE)
            }
        }
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_ENCRYPTED_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun reportEncryptionFallback(error: Exception) {
        Log.e(
            TAG,
            "EncryptedSharedPreferences unavailable; using unencrypted fallback (tokens still app-private)",
            error
        )
        AppHealthMonitor.reportIssue(
            AppIssue(
                key = "token_storage_encryption_unavailable",
                message = "EncryptedSharedPreferences unavailable, using fallback",
                severity = IssueSeverity.HIGH,
                area = "auth.storage",
                attributes = mapOf("exception" to error.javaClass.simpleName)
            )
        )
    }

    private fun migrateFromPlainLegacy() {
        val oldAccessToken = plainLegacyPrefs.getString(KEY_ACCESS, null)
        val oldRefreshToken = plainLegacyPrefs.getString(KEY_REFRESH, null)

        if (oldAccessToken != null || oldRefreshToken != null) {
            Log.d(TAG, "Migrating tokens from legacy plain prefs")
            prefs.edit().apply {
                oldAccessToken?.let { putString(KEY_ACCESS, it) }
                oldRefreshToken?.let { putString(KEY_REFRESH, it) }
                apply()
            }
            plainLegacyPrefs.edit().remove(KEY_ACCESS).remove(KEY_REFRESH).apply()
        }
    }

    fun saveAccessToken(token: String) {
        prefs.edit().putString(KEY_ACCESS, token).apply()
    }

    fun saveRefreshToken(token: String) {
        prefs.edit().putString(KEY_REFRESH, token).apply()
    }

    fun getAccessToken(): String? {
        return prefs.getString(KEY_ACCESS, null)
    }

    fun clear() {
        prefs.edit().clear().apply()
        plainLegacyPrefs.edit().clear().apply()
        appContext.getSharedPreferences(PREFS_FALLBACK, Context.MODE_PRIVATE).edit().clear().apply()
    }

    companion object {
        private const val TAG = "TokenManager"
        private const val PREFS_ENCRYPTED_NAME = "auth_prefs_encrypted"
        private const val PREFS_PLAIN_LEGACY = "auth_prefs"
        private const val PREFS_FALLBACK = "auth_prefs_fallback"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
    }
}
