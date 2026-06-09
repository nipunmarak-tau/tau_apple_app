// AppHealthMonitor.swift
// Mirrors AppHealthMonitor.kt — structured issue + metric reporting.
// New Relic / Firebase integrations are stubbed: wire your SDK at the marked TODOs.

import Foundation
import os.log
import UIKit

enum IssueSeverity: String { case low, medium, high, critical }

struct AppIssue {
    let key: String
    let message: String
    let severity: IssueSeverity
    let area: String
    var attributes: [String: String] = [:]
}

final class AppHealthMonitor {

    static let shared = AppHealthMonitor()
    private let log = Logger(subsystem: "com.tau.research", category: "health")

    private init() {}

    func reportIssue(_ issue: AppIssue) {
        var attrs = issue.attributes
        attrs["issue_key"] = issue.key
        attrs["severity"] = issue.severity.rawValue
        attrs["area"] = issue.area
        log.error("ISSUE \(issue.message, privacy: .public) \(self.formatAttrs(attrs), privacy: .public)")
        recordMetric(name: "issue_reported", attributes: attrs.merging(["message": String(issue.message.prefix(120))]) { $1 })
        // TODO: forward to analytics SDK.
    }

    func captureException(area: String, error: Error, attributes: [String: String] = [:]) {
        var attrs = attributes
        attrs["area"] = area
        attrs["exception"] = String(describing: type(of: error))
        log.error("EXCEPTION \(error.localizedDescription, privacy: .public) \(self.formatAttrs(attrs), privacy: .public)")
        recordMetric(name: "exception_captured", attributes: attrs)
    }

    func recordMetric(name: String, attributes: [String: String] = [:]) {
        let cleanName = name
            .lowercased()
            .replacingOccurrences(of: #"[^a-z0-9_]"#, with: "_", options: .regularExpression)
        log.info("METRIC \(cleanName, privacy: .public) \(self.formatAttrs(attributes), privacy: .public)")
        // TODO: forward custom event to analytics SDK.
    }

    func recordDeviceHealthSnapshot() {
        let model = DeviceManager.currentDeviceModel
        recordMetric(
            name: "device_health_snapshot",
            attributes: [
                "ios": UIDevice.current.systemVersion,
                "model": String(model.prefix(80)),
                "free_mb": String(availableStorageMb())
            ]
        )
    }

    private func availableStorageMb() -> Int64 {
        do {
            let url = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
                ?? FileManager.default.temporaryDirectory
            let values = try url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            return (values.volumeAvailableCapacityForImportantUsage ?? 0) / (1024 * 1024)
        } catch {
            return -1
        }
    }

    private func formatAttrs(_ attrs: [String: String]) -> String {
        attrs.map { "\($0)=\($1)" }.sorted().joined(separator: " ")
    }
}
