// RecordingService.swift
// Mirrors service/RecordingService.kt — a @MainActor coordinator that:
//   * subscribes to GPS updates via CLLocationManager
//   * prepares GPX + CSV log files
//   * delegates camera lifecycle to CameraController
//
// On iOS, "foreground service" semantics are replaced by:
//   * Background mode `location` keeps GPS updates flowing while screen is off
//   * Background mode `audio` keeps the AVCaptureSession alive
//   * `UIApplication.shared.isIdleTimerDisabled = true` while recording

import Foundation
import CoreLocation
import Observation
import UIKit
import AVFoundation

@MainActor
@Observable
final class RecordingService: NSObject {

    static let shared = RecordingService()

    // MARK: - Public state

    private(set) var currentLocation: CLLocation?
    private(set) var recordingActive: Bool = false

    let cameraController = CameraController.shared

    // MARK: - Private

    private let locationManager = CLLocationManager()
    private var gpxLogger: GPXLogger?
    private var csvLogger: CSVLogger?
    private var currentScanType: String = ""
    private var currentVideoPath: String?
    private var gpxPath: String?
    private var csvPath: String?

    override init() {
        super.init()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.activityType = .otherNavigation
        locationManager.pausesLocationUpdatesAutomatically = false
        locationManager.allowsBackgroundLocationUpdates = true
    }

    // MARK: - Public API (mirrors Service.startRecording / stopRecording etc.)

    /// Mirror of `prepareRecordingArtifacts(scanType:)`. Creates the GPX + CSV path stubs that
    /// will be paired with the recording video on `startRecording`.
    @discardableResult
    func prepareRecordingArtifacts(scanType: String) -> String? {
        currentScanType = scanType
        let basePath = CameraController.makeOutputURL(extension: "mp4")
        let baseNoExt = basePath.deletingPathExtension().path
        gpxPath = baseNoExt + ".gpx"
        csvPath = baseNoExt + ".csv"
        currentVideoPath = basePath.path
        gpxLogger = GPXLogger(path: gpxPath!, name: scanType)
        csvLogger = CSVLogger(path: csvPath!)
        return currentVideoPath
    }

    /// Mirror of the Kotlin `startRecording(...)`. Launches the camera and GPS streams.
    func startRecording(
        device: AVCaptureDevice,
        targetFps: Int,
        iso: Int,
        exposureTimeNs: Int64,
        analysisSize: CGSize,
        scanType: String,
        onStatus: @escaping (String) -> Void
    ) -> String? {
        if currentVideoPath == nil { _ = prepareRecordingArtifacts(scanType: scanType) }
        UIApplication.shared.isIdleTimerDisabled = true
        recordingActive = true
        locationManager.requestAlwaysAuthorization()
        locationManager.startUpdatingLocation()
        gpxLogger?.open()
        csvLogger?.open()
        let path = cameraController.startRecording(onStatus: onStatus)
        return path ?? currentVideoPath
    }

    func stopRecording() -> String? {
        recordingActive = false
        UIApplication.shared.isIdleTimerDisabled = false
        locationManager.stopUpdatingLocation()
        return cameraController.stopRecording() ?? currentVideoPath
    }

    func stopGpxLogging() -> String? {
        gpxLogger?.close()
        return gpxPath
    }

    func stopCsvLogging() -> String? {
        csvLogger?.close()
        return csvPath
    }

    /// Mirror of `appendMetricsRow`.
    func appendMetricsRow(
        countPerFrame: Int,
        currentBrightness: Int,
        targetedBrightness: Int,
        adjustedBrightness: Int,
        currentIso: Int,
        adjustedIso: Int,
        currentShutterDenom: Int,
        adjustedShutterDenom: Int
    ) {
        csvLogger?.appendRow(values: [
            String(Int(Date().timeIntervalSince1970 * 1000)),
            String(countPerFrame),
            String(currentBrightness),
            String(targetedBrightness),
            String(adjustedBrightness),
            String(currentIso),
            String(adjustedIso),
            String(currentShutterDenom),
            String(adjustedShutterDenom)
        ])
    }
}

extension RecordingService: CLLocationManagerDelegate {
    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        Task { @MainActor in
            self.currentLocation = loc
            self.gpxLogger?.appendPoint(latitude: loc.coordinate.latitude,
                                        longitude: loc.coordinate.longitude,
                                        elevation: loc.altitude,
                                        timestamp: loc.timestamp)
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        // No-op — request was made imperatively when starting the recording.
    }
}
