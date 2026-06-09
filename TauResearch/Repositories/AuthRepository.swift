// AuthRepository.swift
// Mirrors AuthRepository.kt — login + device registration via APIClient.

import Foundation

struct AuthRepository {

    func login(email: String, password: String) async throws {
        let request = LoginRequest(email: email, password: password)
        do {
            let response: LoginResponse = try await APIClient.shared.request(
                "api/token",
                method: .POST,
                body: request,
                decode: LoginResponse.self
            )
            TokenManager.shared.save(accessToken: response.access)
            TokenManager.shared.save(refreshToken: response.refresh)

            let deviceType = DeviceManager.currentDeviceType
            let deviceModel = DeviceManager.currentDeviceModel
            DeviceManager.shared.save(deviceType: deviceType, deviceModel: deviceModel)

            // Fire-and-forget device registration; mirror the Android version.
            Task {
                let body = AppDeviceRequest(
                    email: email,
                    device_type: deviceType,
                    device_model: deviceModel
                )
                _ = try? await APIClient.shared.requestRaw("api/app/device", method: .POST, body: body)
            }

            AppHealthMonitor.shared.recordMetric(
                name: "login_success",
                attributes: ["device_model": String(deviceModel.prefix(80))]
            )
        } catch let err as AppError {
            switch err {
            case .auth:
                AppHealthMonitor.shared.reportIssue(
                    .init(key: "login_failed_http",
                          message: "Login failed (auth)",
                          severity: .medium,
                          area: "auth.login")
                )
            case .server(let code, _):
                AppHealthMonitor.shared.reportIssue(
                    .init(key: "login_failed_http",
                          message: "Login failed with HTTP \(code)",
                          severity: .high,
                          area: "auth.login",
                          attributes: ["http_code": String(code)])
                )
            default:
                break
            }
            throw err
        }
    }
}
