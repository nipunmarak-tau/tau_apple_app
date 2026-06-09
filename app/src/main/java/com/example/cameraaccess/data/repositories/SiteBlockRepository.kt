package com.example.cameraaccess.data.repositories

import android.content.Context
import android.util.Log
import com.example.cameraaccess.data.model.*
import com.example.cameraaccess.data.network.RetrofitClient
import com.google.gson.Gson
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException

class SiteBlockRepository(private val context: Context) {

    companion object {
        private const val TAG = "SiteBlockRepository"
    }

    private val cache = ApiCache(context)

    fun getCachedSiteBlock(): SiteBlockResponse? {
        val json = cache.getFetchedSiteBlock()
        Log.d(TAG, "Cached SiteBlock JSON: $json")

        return json?.let {
            try {
                Gson().fromJson(it, SiteBlockResponse::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing cached site block", e)
                null
            }
        }
    }

    fun createSiteBlock(
        body: SiteBlockCreateRequest,
        onResult: (Result<SiteBlockCreateResponse>) -> Unit
    ) {
        Log.d(TAG, "Create SiteBlock request body: ${Gson().toJson(body)}")

        RetrofitClient.api.createSiteBlock(body)
            .enqueue(object : Callback<SiteBlockCreateResponse> {

                override fun onResponse(
                    call: Call<SiteBlockCreateResponse>,
                    response: Response<SiteBlockCreateResponse>
                ) {
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Create failed: ${response.errorBody()?.string()}")
                        onResult(Result.failure(AppError.ServerError(response.code(), "Failed to create site block.")))
                        return
                    }

                    val responseBody = response.body()
                    if (responseBody == null) {
                        Log.e(TAG, "Response body is null")
                        onResult(Result.failure(AppError.UnexpectedError()))
                        return
                    }

                    Log.d(TAG, "Create SiteBlock success response: ${Gson().toJson(responseBody)}")
                    onResult(Result.success(responseBody))
                }

                override fun onFailure(call: Call<SiteBlockCreateResponse>, t: Throwable) {
                    Log.e(TAG, "API call failed", t)
                    val error = if (t is IOException) {
                        AppError.NetworkError(t)
                    } else {
                        AppError.UnexpectedError(t)
                    }
                    onResult(Result.failure(error))
                }
            })
    }

    fun getCachedSubmitSiteBlock(): SiteBlockCreateResponse? {
        val json = cache.getSubmitSiteBlock() ?: return null
        return try {
            Gson().fromJson(json, SiteBlockCreateResponse::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing cached submit site block", e)
            null
        }
    }
}
