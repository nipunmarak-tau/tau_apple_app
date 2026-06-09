// VideoRecordingViewModel.swift
// Mirrors VideoRecordingViewModel.kt — wires CameraController + RecordingService + GPS gating
// + detection smoothing.

import Foundation
import AVFoundation
import CoreLocation
import SwiftData
import Observation

struct RecordingSegment: Equatable {
    let videoPath: String
    let gpxPath: String
    let createdAt: Date
}

@Observable
@MainActor
final class VideoRecordingViewModel {

    // MARK: - Public state mirroring the Compose VM

    var uiText: String = "Idle"
    var recording: Bool = false
    var previewRunning: Bool = false
    var paused: Bool = false
    var recordingDuration: TimeInterval = 0
    var detectionResult: DetectionResult?
    var lastSegment: RecordingSegment?
    var lastVideoPath: String?

    /// Mirrors `recordingVm.location` from the Android VM. Reads from
    /// `RecordingService.shared.currentLocation`; the service is `@Observable`, so
    /// SwiftUI re-renders whenever a new fix arrives.
    var location: CLLocation? { service.currentLocation }

    var recordingReady: Bool = false
    var gpsReadyForRecording: Bool = false
    var selectedDynamicRange: Int = 1  // 1 = standard, matches the Android constant

    // MARK: - Dependencies

    let controller: CameraController
    private let service = RecordingService.shared
    private let repo: RecordingRepository

    // MARK: - Internal state

    private var pipelineReady = false
    private var gpsConsecutiveCount = 0
    private var gpsGateActive = false
    private var durationTimer: Task<Void, Never>?
    private var gpsTimeoutTask: Task<Void, Never>?
    private let requiredGpsPointsForRecording = 10
    private let gpsAcquireTimeoutSeconds: TimeInterval = 20

    // Smoothing state — matches the Kotlin alpha = 0.15
    private let alpha: Float = 0.15
    private var smoothedConfidence: Float = 0
    private var smoothedObjectLuma: Float = 0
    private var smoothedFrameLuma: Float = 0

    init(context: ModelContext) {
        self.controller = CameraController.shared
        self.repo = RecordingRepository(context: context)
        wireDetectionHandler()
    }

    private func wireDetectionHandler() {
        controller.runKiwiInferenceEnabled = useKiwiFruitMetering()
        controller.onDetectionUpdate = { [weak self] result in
            self?.handleDetection(result)
        }
    }

    /// Fruit scan? Read the cached scan-type id and look up its name in the fetched catalogue.
    func useKiwiFruitMetering() -> Bool {
        guard let submitted = cachedSiteBlock(),
              let json = ApiCache.shared.getFetchedSiteBlock(),
              let data = json.data(using: .utf8),
              let fetched = try? JSONDecoder.tau.decode(SiteBlockResponse.self, from: data) else {
            return false
        }
        let name = fetched.scan_type.first(where: { $0.id == submitted.scanTypeId })?.name?.trimmingCharacters(in: .whitespaces)
        return name?.lowercased() == "fruit"
    }

    // MARK: - Preview / Recording

    func startPreview(device: AVCaptureDevice, fps: Int, iso: Int, exposureTimeNs: Int64, onStatus: @escaping (String) -> Void) {
        Task {
            // Kick the GPS stream BEFORE we start the gate. Previously the gate polled
            // `service.currentLocation` while `LocationManager.startUpdatingLocation`
            // was still idle, so the gate timed out and the Start-Recording button
            // never lit up.
            service.startLocationUpdates()

            await controller.startPreview(device: device,
                                           targetFps: fps,
                                           iso: iso,
                                           exposureTimeNs: exposureTimeNs) { msg in
                self.uiText = msg
                onStatus(msg)
            }
            previewRunning = true
            recording = false
            startGpsGateForPreview()
            await prepareRecordingPipeline()
        }
    }

    func stopCamera() {
        let wasRecording = recording
        controller.stop()
        previewRunning = false
        recording = false
        // Don't cut the GPX off mid-track. If a recording was active when stopCamera
        // was called (e.g., scene backgrounded), leave the GPS stream running so the
        // recording's existing logger can finalize cleanly; otherwise stop the stream
        // to save battery.
        if !wasRecording {
            service.stopLocationUpdates()
        }
        resetRecordingReadinessState()
    }

    func startRecording(device: AVCaptureDevice, fps: Int, iso: Int, exposureTimeNs: Int64) {
        guard recordingReady else {
            uiText = !gpsReadyForRecording ? "Waiting for GPS…" : "Preparing camera pipeline. Please wait…"
            return
        }
        uiText = "Starting recording…"
        let scanType = resolveScanTypeLabel()
        let path = service.startRecording(
            device: device,
            targetFps: fps,
            iso: iso,
            exposureTimeNs: exposureTimeNs,
            analysisSize: controller.defaultAnalysisSize,
            scanType: scanType,
            onStatus: { msg in self.uiText = msg }
        )
        lastVideoPath = path
        recording = true
        paused = false
        previewRunning = true
        gpsGateActive = false
        gpsTimeoutTask?.cancel()
        startDurationTimer()
    }

    func stopRecording() {
        let videoPath = service.stopRecording()
        let gpxPath = service.stopGpxLogging()
        _ = service.stopCsvLogging()

        recording = false
        paused = false
        resetRecordingReadinessState()
        stopDurationTimer()
        controller.stop()
        previewRunning = false
        // Recording fully done — stop the GPS stream so it doesn't drain battery while
        // the user looks at the "Ready to Upload" card.
        service.stopLocationUpdates()

        if let videoPath {
            let gpx = gpxPath ?? (videoPath as NSString).deletingPathExtension + ".gpx"
            lastSegment = RecordingSegment(videoPath: videoPath, gpxPath: gpx, createdAt: .now)
            uiText = "Video + GPX Saved ✅"
            if let block = cachedSiteBlock() {
                Task { await repo.saveRecording(videoPath: videoPath, gpxPath: gpx, siteBlock: block) }
            }
        } else {
            uiText = "Stopped recording."
        }
    }

    func togglePauseResume() {
        guard recording else { return }
        if !paused {
            if controller.pauseRecording() {
                paused = true
                uiText = "Paused ⏸️"
                durationTimer?.cancel()
            } else {
                uiText = "Pause not supported ❌"
            }
        } else {
            if controller.resumeRecording() {
                paused = false
                uiText = "Recording resumed ▶️"
                startDurationTimer(isResume: true)
            } else {
                uiText = "Resume failed ❌"
            }
        }
    }

    func logMetrics(
        countPerFrame: Int,
        currentBrightness: Int,
        targetedBrightness: Int,
        adjustedBrightness: Int,
        currentIso: Int,
        adjustedIso: Int,
        currentShutterDenom: Int,
        adjustedShutterDenom: Int
    ) {
        guard recording else { return }
        service.appendMetricsRow(
            countPerFrame: countPerFrame,
            currentBrightness: currentBrightness,
            targetedBrightness: targetedBrightness,
            adjustedBrightness: adjustedBrightness,
            currentIso: currentIso,
            adjustedIso: adjustedIso,
            currentShutterDenom: currentShutterDenom,
            adjustedShutterDenom: adjustedShutterDenom
        )
    }

    // MARK: - Detection smoothing

    private func handleDetection(_ new: DetectionResult) {
        smoothedConfidence = alpha * new.confidenceScore + (1 - alpha) * smoothedConfidence
        if new.objectLuma > 0 {
            if smoothedObjectLuma == 0 { smoothedObjectLuma = Float(new.objectLuma) }
            smoothedObjectLuma = alpha * Float(new.objectLuma) + (1 - alpha) * smoothedObjectLuma
        } else {
            smoothedObjectLuma = 0
        }
        if new.avgFrameLuma > 0 {
            if smoothedFrameLuma == 0 { smoothedFrameLuma = Float(new.avgFrameLuma) }
            smoothedFrameLuma = alpha * Float(new.avgFrameLuma) + (1 - alpha) * smoothedFrameLuma
        } else {
            smoothedFrameLuma = 0
        }
        var smoothed = new
        smoothed.confidenceScore = smoothedConfidence
        smoothed.objectLuma = Int(smoothedObjectLuma)
        smoothed.avgFrameLuma = Int(smoothedFrameLuma)
        detectionResult = smoothed
    }

    // MARK: - GPS gate

    private func startGpsGateForPreview() {
        gpsTimeoutTask?.cancel()
        gpsConsecutiveCount = 0
        gpsGateActive = true
        gpsReadyForRecording = false
        recomputeRecordingReady()
        gpsTimeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(20 * 1_000_000_000))
            guard let self else { return }
            if previewRunning, !recording, !gpsReadyForRecording {
                stopCamera()
                uiText = "GPS data was not found."
            }
        }
        // Mirror Android: poll currentLocation each second.
        Task { [weak self] in
            while let self, self.gpsGateActive, !self.recording {
                self.handleGpsGate(location: self.service.currentLocation)
                try? await Task.sleep(nanoseconds: 500_000_000)
            }
        }
    }

    private func handleGpsGate(location: CLLocation?) {
        guard let location, location.isValidCoordinate else {
            gpsConsecutiveCount = 0
            return
        }
        gpsConsecutiveCount += 1
        if gpsConsecutiveCount >= requiredGpsPointsForRecording {
            gpsReadyForRecording = true
            gpsGateActive = false
            gpsTimeoutTask?.cancel()
            recomputeRecordingReady()
        }
    }

    private func recomputeRecordingReady() {
        recordingReady = pipelineReady && gpsReadyForRecording
    }

    private func resetRecordingReadinessState() {
        pipelineReady = false
        gpsReadyForRecording = false
        recordingReady = false
        gpsConsecutiveCount = 0
        gpsGateActive = false
        gpsTimeoutTask?.cancel()
        gpsTimeoutTask = nil
    }

    // MARK: - Pipeline prep

    private func prepareRecordingPipeline() async {
        pipelineReady = false
        recomputeRecordingReady()
        let scanType = resolveScanTypeLabel()
        let artifactsReady = service.prepareRecordingArtifacts(scanType: scanType) != nil
        let detectionReady = await controller.warmUpDetection()
        pipelineReady = artifactsReady && detectionReady
        recomputeRecordingReady()
        if !pipelineReady, previewRunning {
            uiText = "Warm-up failed. Retry preview."
        }
    }

    // MARK: - Duration

    private func startDurationTimer(isResume: Bool = false) {
        if !isResume { recordingDuration = 0 }
        durationTimer?.cancel()
        durationTimer = Task { [weak self] in
            while !(Task.isCancelled) {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard let self else { return }
                if Task.isCancelled { return }
                self.recordingDuration += 1
            }
        }
    }

    private func stopDurationTimer() {
        durationTimer?.cancel()
        durationTimer = nil
    }

    // MARK: - Cache helpers

    private func cachedSiteBlock() -> SiteBlockCreateRequest? {
        guard let json = ApiCache.shared.getSubmitSiteBlock(),
              let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder.tau.decode(SiteBlockCreateRequest.self, from: data)
    }

    private func resolveScanTypeLabel() -> String {
        guard let submitted = cachedSiteBlock() else { return "" }
        guard let json = ApiCache.shared.getFetchedSiteBlock(),
              let data = json.data(using: .utf8),
              let fetched = try? JSONDecoder.tau.decode(SiteBlockResponse.self, from: data) else {
            return String(submitted.scanTypeId)
        }
        return fetched.scan_type.first(where: { $0.id == submitted.scanTypeId })?.name ?? String(submitted.scanTypeId)
    }
}

private extension CLLocation {
    var isValidCoordinate: Bool {
        let lat = coordinate.latitude
        let lon = coordinate.longitude
        return (-90...90).contains(lat) && (-180...180).contains(lon)
    }
}
