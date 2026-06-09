// UploadStateBus.swift
// Mirrors service/UploadStateBus.kt — global @Observable singleton driving the dashboard's
// upload progress UI.

import Foundation
import Observation

struct UploadServiceState: Equatable {
    var currentRecordingId: UUID?
    var progress: Float = 0
    var stage: String = ""
    var error: String?
    var isUploading: Bool = false
    var finishedRecordingId: UUID?
}

@Observable
final class UploadStateBus {

    static let shared = UploadStateBus()

    private(set) var state = UploadServiceState()

    private init() {}

    func startUpload(id: UUID) {
        AppHealthMonitor.shared.recordMetric(name: "upload_state_start",
                                             attributes: ["recording_id": id.uuidString])
        state = UploadServiceState(
            currentRecordingId: id,
            progress: 0,
            stage: "Preparing…",
            error: nil,
            isUploading: true,
            finishedRecordingId: nil
        )
    }

    func setStage(_ stage: String) {
        AppHealthMonitor.shared.recordMetric(name: "upload_stage_changed",
                                             attributes: ["stage": String(stage.prefix(50))])
        state.stage = stage
        state.isUploading = true
    }

    func updateProgress(_ progress: Float) {
        state.progress = progress
        state.isUploading = true
    }

    func finishSuccess(id: UUID) {
        AppHealthMonitor.shared.recordMetric(name: "upload_state_success",
                                             attributes: ["recording_id": id.uuidString])
        state = UploadServiceState(
            currentRecordingId: nil,
            progress: 1,
            stage: "Done",
            error: nil,
            isUploading: false,
            finishedRecordingId: id
        )
    }

    func error(_ message: String) {
        AppHealthMonitor.shared.recordMetric(name: "upload_state_error",
                                             attributes: ["message": String(message.prefix(80))])
        state.error = message
        state.isUploading = false
    }

    func reset() {
        state = UploadServiceState()
    }
}
