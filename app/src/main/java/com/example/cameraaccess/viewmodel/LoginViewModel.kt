package com.example.cameraaccess.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.cameraaccess.data.model.AppError
import com.example.cameraaccess.data.repositories.AuthRepository
import com.example.cameraaccess.ui.login.LoginUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class LoginViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AuthRepository(app)

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState = _uiState.asStateFlow()

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _uiState.value = LoginUiState(error = "Please enter both email and password.")
            return
        }

        _uiState.value = LoginUiState(isLoading = true)
        repo.login(email.trim(), password.trim()) { result ->
            _uiState.value = if (result.isSuccess) {
                LoginUiState(success = true)
            } else {
                val exception = result.exceptionOrNull()
                val errorMsg = if (exception is AppError) {
                    exception.userMessage
                } else {
                    exception?.message ?: "An unexpected error occurred. Please try again."
                }
                LoginUiState(error = errorMsg)
            }
        }
    }
}
