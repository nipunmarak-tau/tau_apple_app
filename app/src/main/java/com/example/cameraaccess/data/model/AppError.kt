package com.example.cameraaccess.data.model

sealed class AppError : Exception() {
    abstract val userMessage: String

    data class NetworkError(val originalCause: Throwable? = null) : AppError() {
        override val userMessage: String = "Connection problem. Please check your internet and try again."
    }

    data class AuthError(override val message: String = "Invalid email or password.") : AppError() {
        override val userMessage: String = message
    }

    data class ServerError(val code: Int, override val message: String? = null) : AppError() {
        override val userMessage: String = "Server error ($code). Please try again later."
    }

    data class ValidationError(override val message: String) : AppError() {
        override val userMessage: String = message
    }

    data class UnexpectedError(val originalCause: Throwable? = null) : AppError() {
        override val userMessage: String = "An unexpected error occurred. Please try again."
    }
}
