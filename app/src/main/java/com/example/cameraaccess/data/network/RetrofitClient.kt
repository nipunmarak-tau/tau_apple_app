package com.example.cameraaccess.data.network

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.cameraaccess.monitoring.AppHealthMonitor
import com.example.cameraaccess.monitoring.AppIssue
import com.example.cameraaccess.monitoring.IssueSeverity
import com.example.cameraaccess.utils.TokenManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {

    private const val BASE_URL = "https://kiwifruitiq.tau.co.nz/"
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val authInterceptor = Interceptor { chain ->
        val context = appContext
        val requestBuilder = chain.request().newBuilder()

        if (context != null) {
            val token = TokenManager(context).getAccessToken()
            if (!token.isNullOrEmpty()) {
                requestBuilder.addHeader("Authorization", "Bearer $token")
            }
        }

        val request = requestBuilder.build()
        val response = chain.proceed(request)

        // Step 4: Handle 401 Unauthorized (Token Expired)
        if (response.code == 401) {
            Log.e("RetrofitClient", "Received 401 Unauthorized. Clearing token.")
            context?.let {
                TokenManager(it).clear()
                AppHealthMonitor.reportIssue(
                    AppIssue(
                        key = "auth_401_token_cleared",
                        message = "Received 401 from API and cleared auth token",
                        severity = IssueSeverity.HIGH,
                        area = "auth.network",
                        attributes = mapOf("url" to request.url.toString().take(140))
                    )
                )
                // Optionally: Navigate to login screen if it's a UI-driven request
                // This is a simplified global approach.
            }
        }

        response
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val api: ApiService by lazy { retrofit.create(ApiService::class.java) }
}
