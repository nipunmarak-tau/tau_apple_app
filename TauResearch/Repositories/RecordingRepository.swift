// RecordingRepository.swift
// Mirrors RecordingRepository.kt — save, list (Flow → AsyncStream via @Query), delete.

import Foundation
import SwiftData
import AVFoundation

@MainActor
struct RecordingRepository {

    let context: ModelContext

    // MARK: - Create

    func saveRecording(
        videoPath: String,
        gpxPath: String,
        siteBlock: SiteBlockCreateRequest
    ) async {
        let duration = await Self.videoDuration(at: videoPath)
        let url = URL(fileURLWithPath: videoPath)
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0

        if duration == 0 && size == 0 {
            AppHealthMonitor.shared.reportIssue(
                .init(
                    key: "recording_rejected_empty_video",
                    message: "Recording rejected due to empty/invalid video file",
                    severity: .critical,
                    area: "recording.storage",
                    attributes: ["video_path": String(videoPath.suffix(80))]
                )
            )
            return
        }

        let entity = RecordingEntity(
            videoPath: videoPath,
            gpxPath: gpxPath,
            blockId: siteBlock.blockId,
            scanTypeId: siteBlock.scanTypeId,
            rowWidth: siteBlock.rowWidth,
            rowHeight: siteBlock.rowHeight,
            bayLength: siteBlock.bayLength,
            addedMultiplier: siteBlock.addedMultiplier,
            duration: duration
        )
        context.insert(entity)
        do {
            try context.save()
            AppHealthMonitor.shared.recordMetric(
                name: "recording_saved",
                attributes: ["duration_s": String(format: "%.1f", duration)]
            )
        } catch {
            AppHealthMonitor.shared.captureException(area: "recording.database_save", error: error)
        }
    }

    // MARK: - Read

    func allRecordings() -> [RecordingEntity] {
        let descriptor = FetchDescriptor<RecordingEntity>(
            sortBy: [SortDescriptor(\.createdAt, order: .reverse)]
        )
        return (try? context.fetch(descriptor)) ?? []
    }

    func latestRecording() -> RecordingEntity? { allRecordings().first }

    func recording(id: UUID) -> RecordingEntity? {
        let descriptor = FetchDescriptor<RecordingEntity>(
            predicate: #Predicate { $0.id == id }
        )
        return try? context.fetch(descriptor).first
    }

    // MARK: - Delete after upload

    struct CleanupResult { let videoDeleted, gpxDeleted, csvDeleted: Bool }

    @discardableResult
    func cleanupAfterSuccessfulUpload(_ entity: RecordingEntity) -> CleanupResult {
        let videoDeleted = deleteFileIfExists(entity.videoPath)
        let gpxDeleted = deleteFileIfExists(entity.gpxPath)
        let csvPath = (entity.videoPath as NSString).deletingPathExtension + ".csv"
        let csvDeleted = deleteFileIfExists(csvPath)

        context.delete(entity)
        try? context.save()

        return CleanupResult(videoDeleted: videoDeleted, gpxDeleted: gpxDeleted, csvDeleted: csvDeleted)
    }

    private func deleteFileIfExists(_ path: String) -> Bool {
        let url = URL(fileURLWithPath: path)
        if !FileManager.default.fileExists(atPath: url.path) { return true }
        do {
            try FileManager.default.removeItem(at: url)
            return true
        } catch {
            AppHealthMonitor.shared.captureException(area: "recording.file_delete", error: error)
            return false
        }
    }

    // MARK: - Duration (AVAsset, with brief polling for slow finalization)

    static func videoDuration(at path: String) async -> TimeInterval {
        let asset = AVURLAsset(url: URL(fileURLWithPath: path))
        for attempt in 0..<6 {
            do {
                let duration = try await asset.load(.duration)
                let seconds = CMTimeGetSeconds(duration)
                if seconds.isFinite, seconds > 0 { return seconds }
            } catch {
                // ignore; retry
            }
            try? await Task.sleep(nanoseconds: 500_000_000)
            _ = attempt
        }
        AppHealthMonitor.shared.reportIssue(
            .init(
                key: "recording_duration_unavailable",
                message: "Failed to read duration after retries",
                severity: .high,
                area: "recording.metadata"
            )
        )
        return 0
    }
}
