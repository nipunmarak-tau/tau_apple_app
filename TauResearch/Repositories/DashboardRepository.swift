// DashboardRepository.swift
// Mirrors DashboardRepository.kt.

import Foundation

struct DashboardRepository {

    func getDeviceInfo() -> (type: String, model: String) {
        (DeviceManager.shared.deviceType ?? "-",
         DeviceManager.shared.deviceModel ?? "-")
    }

    func logout() {
        TokenManager.shared.clear()
        ApiCache.shared.clear()
        DeviceManager.shared.clear()
    }

    /// Fetches the site-block catalogue and caches the raw JSON for offline use.
    func fetchSiteBlock() async throws {
        let data = try await APIClient.shared.requestRaw("api/app/site-block", method: .GET)
        guard let json = String(data: data, encoding: .utf8) else {
            throw AppError.unexpected(underlying: nil)
        }
        ApiCache.shared.saveFetchedSiteBlock(json)
    }
}
