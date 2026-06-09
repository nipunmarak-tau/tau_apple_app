// DashboardViewModel.swift
// Mirrors DashboardViewModel.kt — listens to the upload bus, drives navigation/state.

import Foundation
import SwiftData
import Observation

struct DashboardUiState: Equatable {
    var isLoading: Bool = false
    var error: String?
    var navigateToMetadata: Bool = false
    var loggedOut: Bool = false
    var deviceType: String = ""
    var deviceModel: String = ""
    var uploadingRecordingId: UUID?
    var uploadProgress: Float = 0
    var uploadStage: String = ""
    var isOnline: Bool = true
    var selectedIds: Set<UUID> = []
    var uploadQueue: [UUID] = []
}

@Observable
@MainActor
final class DashboardViewModel {

    private let dashboardRepo = DashboardRepository()
    private let recordingRepo: RecordingRepository
    var state = DashboardUiState()

    init(context: ModelContext) {
        recordingRepo = RecordingRepository(context: context)
        let (type, model) = dashboardRepo.getDeviceInfo()
        state.deviceType = type
        state.deviceModel = model
        observeNetwork()
        observeUploadBus()
        Task { try? await dashboardRepo.fetchSiteBlock() }
    }

    private func observeNetwork() {
        state.isOnline = NetworkConnectivityObserver.shared.isOnline
        // SwiftUI views can additionally watch NetworkConnectivityObserver.shared.isOnline.
    }

    private func observeUploadBus() {
        // The dashboard view reads `UploadStateBus.shared.state` reactively — but we also
        // mirror the value into our UiState so dashboard-specific transitions can react.
        let s = UploadStateBus.shared.state
        state.uploadingRecordingId = s.currentRecordingId
        state.uploadProgress = s.progress
        state.uploadStage = s.stage
        state.error = s.error
    }

    /// Called from SwiftUI's `.onChange` to keep dashboard state in sync with the bus.
    func syncFromBus(_ s: UploadServiceState) {
        let removeId = s.currentRecordingId ?? s.finishedRecordingId
        state.uploadingRecordingId = s.currentRecordingId
        state.uploadProgress = s.progress
        state.uploadStage = s.stage
        state.error = s.error
        if let id = removeId {
            state.uploadQueue.removeAll { $0 == id }
        }
        if s.error != nil {
            state.uploadingRecordingId = nil
            state.uploadQueue.removeAll()
        }
    }

    // MARK: - Actions

    func startNewScan() {
        if !state.isOnline {
            state.navigateToMetadata = true
            return
        }
        let hasCached = ApiCache.shared.getFetchedSiteBlock() != nil
        state.isLoading = true
        Task {
            do {
                try await dashboardRepo.fetchSiteBlock()
                state.isLoading = false
                state.navigateToMetadata = true
            } catch {
                state.isLoading = false
                if hasCached {
                    state.navigateToMetadata = true
                } else if let err = error as? AppError {
                    state.error = err.userMessage
                } else {
                    state.error = error.localizedDescription
                }
            }
        }
    }

    func toggleSelection(_ id: UUID) {
        if state.selectedIds.contains(id) {
            state.selectedIds.remove(id)
        } else {
            state.selectedIds.insert(id)
        }
    }

    func uploadSelected(in recordings: [RecordingEntity]) {
        guard !state.selectedIds.isEmpty else { return }
        guard state.isOnline else {
            state.error = "You are offline. Please connect to the internet to upload."
            return
        }
        let pending = recordings.filter { state.selectedIds.contains($0.id) }
        let alreadyUploadingOrQueued: Set<UUID> = Set(
            ([state.uploadingRecordingId].compactMap { $0 }) + state.uploadQueue
        )
        let toEnqueue = pending.filter { !alreadyUploadingOrQueued.contains($0.id) }
        if toEnqueue.isEmpty { state.selectedIds.removeAll(); return }
        state.selectedIds.removeAll()
        state.uploadQueue.append(contentsOf: toEnqueue.map(\.id))
        for r in toEnqueue {
            Task { await UploadService.shared.enqueue(r.id, recording: r) }
        }
    }

    func uploadRecording(_ recording: RecordingEntity) {
        guard state.isOnline else {
            state.error = "You are offline. Please connect to the internet to upload."
            return
        }
        if state.uploadingRecordingId == recording.id || state.uploadQueue.contains(recording.id) { return }
        state.uploadQueue.append(recording.id)
        Task { await UploadService.shared.enqueue(recording.id, recording: recording) }
    }

    func deleteRecording(_ recording: RecordingEntity) {
        state.isLoading = true
        state.error = nil
        let result = recordingRepo.cleanupAfterSuccessfulUpload(recording)
        state.uploadQueue.removeAll { $0 == recording.id }
        state.isLoading = false
        if !(result.videoDeleted || result.gpxDeleted || result.csvDeleted) {
            state.error = "Failed to delete recording. Please try again."
        }
    }

    func logout() {
        dashboardRepo.logout()
        state.loggedOut = true
    }

    func consumeNavigation() {
        state.navigateToMetadata = false
        state.loggedOut = false
    }

    func clearError() { state.error = nil }
}
