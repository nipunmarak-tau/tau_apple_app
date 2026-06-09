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
//
// GPS lifecycle:
//   * `startLocationUpdates()` is called as soon as the preview begins so the GPS gate
//     in `VideoRecordingViewModel` actually has data to count. The Android version
//     starts the LocationManager when the preview starts; the previous iOS build only
//     started it inside `startRecording`, which was a chicken-and-egg — the gate
//     polled `currentLocation` but updates weren't running, so it never enabled the
//     Start-Recording button.
//   * Authorization is requested through this service's *persistent* `locationManager`.
//     The previous build called `requestWhenInUseAuthorization()` on a transient
//     `CLLocationManager()` that was deallocated before the system prompt resolved,
//     which silently dropped the prompt on some devices.

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
    /// Whether `startLocationUpdates()` has been called and not yet balanced by a stop.
    /// Used to make repeat calls idempotent.
    private(set) var locationUpdatesActive: Bool = false

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
        // `allowsBackgroundLocationUpdates` is deferred to `startLocationUpdates()`
        // because it can only be set to true after the app holds at least
        // `WhenInUse` authorization. Setting it eagerly in init is a no-op on first
        // launch (NotDetermined) but the OS may log a warning.
    }

    // MARK: - Location lifecycle

    /// Begin streaming locations into `currentLocation`. Safe to call repeatedly.
    /// Requests `WhenInUse` authorization on first call if needed; the auth-change
    /// delegate restarts updates once the user grants permission.
    func startLocationUpdates() {
        let status = locationManager.authorizationStatus
        switch status {
        case .notDetermined:
            // Kick the prompt — `locationManagerDidChangeAuthorization` will reach back
            // here once the user responds.
            locationManager.requestWhenInUseAuthorization()
        case .denied, .restricted:
            // Nothing we can do without user action; the view shows the GPS-disabled
            // dialog when the user tries to record.
            return
        case .authorizedWhenInUse, .authorizedAlways:
            break
        @unknown default:
            break
        }

        // Background updates require the `location` background mode (Info.plist OK) and
        // at least WhenInUse auth. Always upgrades cleanly to "true" later.
        locationManager.allowsBackgroundLocationUpdates =
            (status == .authorizedAlways || status == .authorizedWhenInUse)

        if !locationUpdatesActive {
            locationManager.startUpdatingLocation()
            locationUpdatesActive = true
        }
    }

    func stopLocationUpdates() {
        guard locationUpdatesActive else { return }
        locationManager.stopUpdatingLocation()
        locationUpdatesActive = false
        locationManager.allowsBackgroundLocationUpdates = false
    }

    /// Ask for the stronger `Always` authorization. Called when the user actually
    /// starts a recording so the GPS stream survives the screen turning off.
    func requestAlwaysAuthorizationIfNeeded() {
        let status = locationManager.authorizationStatus
        if status == .authorizedWhenInUse {
            locationManager.requestAlwaysAuthorization()
        }
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

    /// Mirror of the Kotlin `startRecording(...)`. Launches the camera and ensures the
    /// GPS stream is running so the GPX file fills up with track points.
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

        // Upgrade to Always so we keep getting points if the screen locks mid-scan,
        // and make sure updates are running. `startLocationUpdates` is idempotent.
        requestAlwaysAuthorizationIfNeeded()
        startLocationUpdates()

        gpxLogger?.open()
        csvLogger?.open()
        let path = cameraController.startRecording(onStatus: onStatus)
        return path ?? currentVideoPath
    }

    func stopRecording() -> String? {
        recordingActive = false
        UIApplication.shared.isIdleTimerDisabled = false
        // We deliberately keep `locationManager` updates running here; the VM decides
        // whether to stop them when the camera fully stops. That mirrors the Android
        // pattern where GPS continues during the brief "stopped but still on the
        // screen" window so we keep `currentLocation` fresh for the overlay.
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
        // Filter out obviously stale or invalid fixes before they pollute the gate
        // counter or GPX file. iOS sometimes delivers cached fixes from previous
        // sessions on first start with `horizontalAccuracy < 0`.
        guard loc.horizontalAccuracy >= 0,
              loc.horizontalAccuracy <= 100, // 100 m cap mirrors the Android filter
              abs(loc.timestamp.timeIntervalSinceNow) < 30 else {
            return
        }
        Task { @MainActor in
            self.currentLocation = loc
            self.gpxLogger?.appendPoint(latitude: loc.coordinate.latitude,
                                        longitude: loc.coordinate.longitude,
                                        elevation: loc.altitude,
                                        timestamp: loc.timestamp)
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Surface to the health monitor so a failure mode (e.g., kCLErrorDenied) shows
        // up in diagnostics. We don't toggle UI state here — the VM's gate timeout
        // handles the "no fix in 20 s" path.
        Task { @MainActor in
            AppHealthMonitor.shared.captureException(area: "gps.updates", error: error)
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            switch status {
            case .authorizedAlways, .authorizedWhenInUse:
                // The user just granted permission (or we escalated to Always) — kick
                // updates if the VM has asked for them but the prompt was previously
                // outstanding.
                self.startLocationUpdates()
            case .denied, .restricted:
                self.stopLocationUpdates()
            default:
                break
            }
        }
    }
}
