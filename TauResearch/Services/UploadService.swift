// UploadService.swift
// Mirrors service/UploadService.kt — but uses Swift Concurrency + a background URLSession.
// Maintains an in-memory queue of recording IDs and uploads them sequentially.

import Foundation

actor UploadService {

    static let shared = UploadService()

    private var queue: [UUID] = []
    private var isProcessing = false
    private let repository = VideoUploadRepository()

    private init() {}

    func enqueue(_ id: UUID, recording: RecordingEntity) {
        if !queue.contains(id) { queue.append(id) }
        Task { await processQueue(initialRecording: recording) }
    }

    private func processQueue(initialRecording: RecordingEntity) async {
        if isProcessing { return }
        isProcessing = true
        defer { isProcessing = false }

        while let id = queue.first {
            await uploadOne(recording: initialRecording.id == id ? initialRecording : initialRecording)
            queue.removeFirst()
        }
    }

    private func uploadOne(recording: RecordingEntity) async {
        let bus = UploadStateBus.shared
        await MainActor.run { bus.startUpload(id: recording.id) }

        do {
            let videoUrls = repository.presignedVideoUrls()
            guard let gpxUrl = repository.presignedGpxUrl(),
                  let csvUrl = repository.presignedCsvUrl(),
                  !videoUrls.isEmpty else {
                throw AppError.unexpected(underlying: nil)
            }

            await MainActor.run { bus.setStage("Uploading video…") }
            _ = try await repository.uploadVideoInChunks(
                videoPath: recording.videoPath,
                presignedUrls: videoUrls,
                progress: { p in
                    Task { @MainActor in bus.updateProgress(p) }
                },
                isCancelled: { false }
            )

            await MainActor.run { bus.setStage("Uploading GPX…") }
            try await repository.uploadGpxFile(path: recording.gpxPath, presignedUrl: gpxUrl)

            await MainActor.run { bus.setStage("Uploading CSV…") }
            let csvPath = (recording.videoPath as NSString).deletingPathExtension + ".csv"
            try await repository.uploadCsvFile(path: csvPath, presignedUrl: csvUrl)

            await MainActor.run { bus.finishSuccess(id: recording.id) }
        } catch {
            await MainActor.run {
                bus.error((error as? AppError)?.userMessage ?? error.localizedDescription)
            }
            AppHealthMonitor.shared.captureException(area: "upload.run", error: error)
        }
    }
}
