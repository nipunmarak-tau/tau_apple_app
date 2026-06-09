// ApiCache.swift
// Mirrors ApiCache.kt — UserDefaults-backed JSON cache for site-block fetch/submit responses.

import Foundation

final class ApiCache {

    static let shared = ApiCache()

    private let defaults = UserDefaults(suiteName: "tau_cache") ?? .standard

    private let keyFetch = "site_block_fetch_json"
    private let keySubmit = "site_block_submit_json"
    private let keyUseMainRearCamera = "use_main_rear_camera_for_scan"

    private init() {}

    // MARK: - Fetched catalogue

    func saveFetchedSiteBlock(_ json: String) {
        defaults.set(json, forKey: keyFetch)
    }

    func getFetchedSiteBlock() -> String? {
        defaults.string(forKey: keyFetch)
    }

    // MARK: - Submit response (presigned URLs)

    func saveSubmitSiteBlock(_ json: String) {
        defaults.set(json, forKey: keySubmit)
    }

    func getSubmitSiteBlock() -> String? {
        defaults.string(forKey: keySubmit)
    }

    // MARK: - Lens preference (was "add lens multiplier" switch)

    func saveUseMainRearCameraForScan(_ value: Bool) {
        defaults.set(value, forKey: keyUseMainRearCamera)
    }

    func getUseMainRearCameraForScan() -> Bool {
        defaults.bool(forKey: keyUseMainRearCamera)
    }

    func clear() {
        defaults.removePersistentDomain(forName: "tau_cache")
    }
}
