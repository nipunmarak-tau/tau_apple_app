// SettingsRepository.swift
// Mirrors SettingsRepository.kt — load/save a single CameraSettings row in SwiftData.

import Foundation
import SwiftData

@MainActor
struct SettingsRepository {

    let context: ModelContext

    func loadSettings() -> CameraSettingsEntity {
        let descriptor = FetchDescriptor<CameraSettingsEntity>()
        if let existing = try? context.fetch(descriptor).first {
            return existing
        }
        let fresh = CameraSettingsEntity()
        context.insert(fresh)
        try? context.save()
        return fresh
    }

    func saveSettings(_ settings: CameraSettingsEntity) {
        // SwiftData tracks @Model mutations; just persist.
        try? context.save()
    }
}
