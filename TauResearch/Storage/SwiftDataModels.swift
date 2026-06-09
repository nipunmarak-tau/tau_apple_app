// SwiftDataModels.swift
// Mirrors RecordingEntity + CameraSettings ObjectBox entities, now as SwiftData @Models.

import Foundation
import SwiftData

@Model
final class RecordingEntity {
    @Attribute(.unique) var id: UUID
    var videoPath: String
    var gpxPath: String
    var createdAt: Date
    var blockId: Int
    var scanTypeId: Int
    var rowWidth: Double
    var rowHeight: Double
    var bayLength: Double
    var addedMultiplier: Double
    var duration: TimeInterval        // seconds (matches Android's "duration" ms but in seconds)

    init(
        videoPath: String,
        gpxPath: String,
        createdAt: Date = .now,
        blockId: Int,
        scanTypeId: Int,
        rowWidth: Double,
        rowHeight: Double,
        bayLength: Double,
        addedMultiplier: Double,
        duration: TimeInterval = 0
    ) {
        self.id = UUID()
        self.videoPath = videoPath
        self.gpxPath = gpxPath
        self.createdAt = createdAt
        self.blockId = blockId
        self.scanTypeId = scanTypeId
        self.rowWidth = rowWidth
        self.rowHeight = rowHeight
        self.bayLength = bayLength
        self.addedMultiplier = addedMultiplier
        self.duration = duration
    }
}

@Model
final class CameraSettingsEntity {
    var previewWidth: Int
    var previewHeight: Int
    var videoWidth: Int
    var videoHeight: Int
    var iso: Int
    var fps: Int
    /// Stored in nanoseconds to match Android values. Convert to seconds in Swift code paths.
    var exposureTimeNs: Int64

    init(
        previewWidth: Int = 1920,
        previewHeight: Int = 1080,
        videoWidth: Int = 1920,
        videoHeight: Int = 1080,
        iso: Int = 100,
        fps: Int = 30,
        exposureTimeNs: Int64 = 4_000_000
    ) {
        self.previewWidth = previewWidth
        self.previewHeight = previewHeight
        self.videoWidth = videoWidth
        self.videoHeight = videoHeight
        self.iso = iso
        self.fps = fps
        self.exposureTimeNs = exposureTimeNs
    }
}
