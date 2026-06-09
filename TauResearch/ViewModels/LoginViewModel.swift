// LoginViewModel.swift
// Mirrors LoginViewModel.kt — @Observable holding LoginUiState.

import Foundation
import Observation

struct LoginUiState: Equatable {
    var isLoading: Bool = false
    var error: String?
    var success: Bool = false
}

@Observable
@MainActor
final class LoginViewModel {
    private let repo = AuthRepository()
    var state = LoginUiState()

    func login(email: String, password: String) {
        let trimmedEmail = email.trimmingCharacters(in: .whitespaces)
        let trimmedPassword = password.trimmingCharacters(in: .whitespaces)

        guard !trimmedEmail.isEmpty, !trimmedPassword.isEmpty else {
            state = LoginUiState(error: "Please enter both email and password.")
            return
        }

        state = LoginUiState(isLoading: true)
        Task {
            do {
                try await repo.login(email: trimmedEmail, password: trimmedPassword)
                state = LoginUiState(success: true)
            } catch let err as AppError {
                state = LoginUiState(error: err.userMessage)
            } catch {
                state = LoginUiState(error: error.localizedDescription)
            }
        }
    }
}
