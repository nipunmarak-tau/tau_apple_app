// VideoUploadViewModel.swift
// Mirrors upload/viewmodel/VideoUploadViewModel.kt — drives the standalone "Upload" panel.

import Foundation
import Observation

struct UploadState: Equatable {
    var isUploading: Bool = false
    var progress: Float = 0
    var success: Bool = false
    var error: String?
}

@Observable
@MainActor
final class VideoUploadViewModel {

    private let repo = VideoUploadRepository()
    var state = UploadState()

    private var uploadTask: Task<Void, Never>?

    func startUpload(videoPath: String, gpxPath: String) {
        uploadTask?.cancel()
        state = UploadState(isUploading: true)
        uploadTask = Task { [weak self] in
            guard let self else { return }
            do {
                let videoUrls = repo.presignedVideoUrls()
                guard let gpxUrl = repo.presignedGpxUrl(),
                      let csvUrl = repo.presignedCsvUrl(),
                      !videoUrls.isEmpty else {
                    throw AppError.unexpected(underlying: nil)
                }
                let csvPath = (videoPath as NSString).deletingPathExtension + ".csv"
                _ = try await repo.uploadVideoInChunks(
                    videoPath: videoPath,
                    presignedUrls: videoUrls,
                    progress: { p in
                        Task { @MainActor in self.state.progress = p }
                    },
                    isCancelled: { Task.isCancelled }
                )
                try await repo.uploadGpxFile(path: gpxPath, presignedUrl: gpxUrl)
                try await repo.uploadCsvFile(path: csvPath, presignedUrl: csvUrl)
                state = UploadState(isUploading: false, progress: 1, success: true)
            } catch {
                state = UploadState(isUploading: false, error: error.localizedDescription)
            }
        }
    }

    func cancelUpload() {
        uploadTask?.cancel()
        state = UploadState(error: "Upload cancelled")
    }
}
