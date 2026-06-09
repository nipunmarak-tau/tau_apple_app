package com.example.cameraaccess.data.repositories

import android.content.Context
import com.example.cameraaccess.data.model.ApiCache
import com.example.cameraaccess.data.model.AppError
import com.example.cameraaccess.data.network.RetrofitClient
import com.example.cameraaccess.utils.DeviceManager
import com.example.cameraaccess.utils.TokenManager
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException

class DashboardRepository(private val context: Context) {

    fun getDeviceInfo(): Pair<String, String> {
        val dm = DeviceManager(context)
        return Pair(
            dm.getDeviceType() ?: "-",
            dm.getDeviceModel() ?: "-"
        )
    }

    fun logout() {
        TokenManager(context).clear()
        ApiCache(context).clear()
        DeviceManager(context).clear()
    }

    fun fetchSiteBlock(
        onResult: (Result<Unit>) -> Unit
    ) {
        RetrofitClient.api.getSiteBlock()
            .enqueue(object : Callback<ResponseBody> {

                override fun onResponse(
                    call: Call<ResponseBody>,
                    response: Response<ResponseBody>
                ) {
                    if (!response.isSuccessful) {
                        onResult(Result.failure(AppError.ServerError(response.code(), "Failed to fetch site data.")))
                        return
                    }

                    val body = response.body()
                    if (body == null) {
                        onResult(Result.failure(AppError.UnexpectedError()))
                        return
                    }

                    try {
                        ApiCache(context).saveFetchedSiteBlock(body.string())
                        onResult(Result.success(Unit))
                    } catch (e: Exception) {
                        onResult(Result.failure(AppError.UnexpectedError(e)))
                    }
                }

                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                    val error = if (t is IOException) {
                        AppError.NetworkError(t)
                    } else {
                        AppError.UnexpectedError(t)
                    }
                    onResult(Result.failure(error))
                }
            })
    }
}
