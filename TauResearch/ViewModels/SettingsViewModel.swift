// SettingsViewModel.swift
// Mirrors SettingsViewModel.kt — EV100 math, ISO clamping, shutter selection.

import Foundation
import SwiftData
import Observation

@Observable
@MainActor
final class SettingsViewModel {

    private let repo: SettingsRepository
    var entity: CameraSettingsEntity

    var saved: Bool = false
    var targetEv100: Double = 0.0
    var isoLimits: ClosedRange<Int> = 100...3200
    var exposureLimitsNs: ClosedRange<Int64> = 1_000_000...100_000_000

    init(context: ModelContext) {
        self.repo = SettingsRepository(context: context)
        self.entity = repo.loadSettings()
    }

    var iso: Int {
        get { entity.iso }
        set { entity.iso = clampIso(newValue); saved = false }
    }

    var exposureTimeNs: Int64 {
        get { entity.exposureTimeNs }
        set {
            entity.exposureTimeNs = clampExposureNs(newValue)
            entity.iso = clampIso(isoForTargetEv100(exposureTimeNs: entity.exposureTimeNs, ev100: targetEv100))
            saved = false
        }
    }

    var fps: Int { entity.fps }

    // MARK: - EV math

    func calculateEv100(iso: Int, exposureTimeNs: Int64) -> Double {
        let t = Double(exposureTimeNs) / 1_000_000_000.0
        guard t > 0 else { return .nan }
        return log2(1.0 / t) - log2(Double(iso) / 100.0)
    }

    func isoForTargetEv100(exposureTimeNs: Int64, ev100: Double) -> Int {
        let t = Double(exposureTimeNs) / 1_000_000_000.0
        guard t > 0 else { return 100 }
        let raw = 100.0 * (1.0 / t) / pow(2.0, ev100)
        return Int(raw.rounded())
    }

    func setTargetEv100(_ ev: Double) {
        targetEv100 = ev
        iso = clampIso(isoForTargetEv100(exposureTimeNs: entity.exposureTimeNs, ev100: ev))
    }

    func setExposureTimeNs(_ v: Int64) { exposureTimeNs = v }

    // MARK: - Resolution helpers

    /// Mirror of `selectOptimalVideoSize` — prefer 4K, fall back to 1080p.
    func selectOptimalVideoSize(supported: [(Int, Int)]) {
        if let four = supported.first(where: { $0 == (3840, 2160) }) {
            entity.videoWidth = four.0; entity.videoHeight = four.1
        } else {
            entity.videoWidth = 1920; entity.videoHeight = 1080
        }
    }

    func selectDefaultPreviewSize(supported: [(Int, Int)]) {
        if let four = supported.first(where: { $0 == (3840, 2160) }) {
            entity.previewWidth = four.0; entity.previewHeight = four.1
        } else {
            entity.previewWidth = 1920; entity.previewHeight = 1080
        }
    }

    func saveSettings() {
        repo.saveSettings(entity)
        saved = true
    }

    // MARK: - Clamping

    func clampIso(_ v: Int) -> Int {
        min(isoLimits.upperBound, max(isoLimits.lowerBound, v))
    }
    func clampExposureNs(_ v: Int64) -> Int64 {
        min(exposureLimitsNs.upperBound, max(exposureLimitsNs.lowerBound, v))
    }
}
