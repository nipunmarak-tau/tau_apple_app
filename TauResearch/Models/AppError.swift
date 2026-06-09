// AppError.swift
// Mirrors AppError.kt — typed errors with a user-facing message.

import Foundation

enum AppError: LocalizedError {
    case network(underlying: Error?)
    case auth(message: String = "Invalid email or password.")
    case server(code: Int, message: String? = nil)
    case validation(message: String)
    case unexpected(underlying: Error? = nil)

    var userMessage: String {
        switch self {
        case .network:
            return "Connection problem. Please check your internet and try again."
        case .auth(let m):
            return m
        case .server(let code, _):
            return "Server error (\(code)). Please try again later."
        case .validation(let m):
            return m
        case .unexpected:
            return "An unexpected error occurred. Please try again."
        }
    }

    var errorDescription: String? { userMessage }
}
