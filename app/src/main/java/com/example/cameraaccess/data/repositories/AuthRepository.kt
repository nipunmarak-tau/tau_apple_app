package com.example.cameraaccess.data.repositories

import android.content.Context
import android.os.Build
import com.example.cameraaccess.data.model.AppDeviceRequest
import com.example.cameraaccess.data.model.AppError
import com.example.cameraaccess.data.model.LoginRequest
import com.example.cameraaccess.data.model.LoginResponse
import com.example.cameraaccess.data.network.RetrofitClient
import com.example.cameraaccess.monitoring.AppHealthMonitor
import com.example.cameraaccess.monitoring.AppIssue
import com.example.cameraaccess.monitoring.IssueSeverity
import com.example.cameraaccess.utils.DeviceManager
import com.example.cameraaccess.utils.TokenManager
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException

class AuthRepository(private val context: Context) {

    fun login(
        email: String,
        password: String,
        onResult: (Result<Unit>) -> Unit
    ) {
        val request = LoginRequest(email, password)

        RetrofitClient.api.login(request).enqueue(
            object : Callback<LoginResponse> {

                override fun onResponse(
                    call: Call<LoginResponse>,
                    response: Response<LoginResponse>
                ) {
                    if (!response.isSuccessful) {
                        AppHealthMonitor.reportIssue(
                            AppIssue(
                                key = "login_failed_http",
                                message = "Login failed with HTTP ${response.code()}",
                                severity = if (response.code() == 401) IssueSeverity.MEDIUM else IssueSeverity.HIGH,
                                area = "auth.login",
                                attributes = mapOf("http_code" to response.code().toString())
                            )
                        )
                        val error = when (response.code()) {
                            401 -> AppError.AuthError("Invalid email or password. Please try again.")
                            else -> AppError.ServerError(response.code())
                        }
                        onResult(Result.failure(error))
                        return
                    }

                    val body = response.body()
                    if (body == null) {
                        AppHealthMonitor.reportIssue(
                            AppIssue(
                                key = "login_empty_body",
                                message = "Login succeeded but response body was null",
                                severity = IssueSeverity.HIGH,
                                area = "auth.login"
                            )
                        )
                        onResult(Result.failure(AppError.UnexpectedError()))
                        return
                    }

                    TokenManager(context).saveAccessToken(body.access)
                    TokenManager(context).saveRefreshToken(body.refresh)

                    val deviceType = "Android"
                    val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"

                    DeviceManager(context).save(deviceType, deviceModel)

                    val deviceReq = AppDeviceRequest(
                        email = email,
                        device_type = deviceType,
                        device_model = deviceModel
                    )

                    RetrofitClient.api.postDevice(deviceReq).enqueue(object :
                        Callback<okhttp3.ResponseBody> {
                        override fun onResponse(
                            call: Call<okhttp3.ResponseBody>,
                            response: Response<okhttp3.ResponseBody>
                        ) {}

                        override fun onFailure(
                            call: Call<okhttp3.ResponseBody>,
                            t: Throwable
                        ) {}
                    })

                    onResult(Result.success(Unit))
                    AppHealthMonitor.recordMetric(
                        "login_success",
                        mapOf("device_model" to deviceModel.take(80))
                    )
                }

                override fun onFailure(
                    call: Call<LoginResponse>,
                    t: Throwable
                ) {
                    val error = if (t is IOException) {
                        AppError.NetworkError(t)
                    } else {
                        AppError.UnexpectedError(t)
                    }
                    AppHealthMonitor.captureException(
                        area = "auth.login",
                        throwable = t,
                        attributes = mapOf("network" to (t is IOException).toString())
                    )
                    onResult(Result.failure(error))
                }
            }
        )
    }
}
