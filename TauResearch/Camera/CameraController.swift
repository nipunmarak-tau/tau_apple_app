// CameraController.swift
// Mirrors Camera2Controller.kt's contract — but built on AVFoundation
// (AVCaptureSession + AVCaptureMovieFileOutput / AVAssetWriter), which is the iOS-idiomatic
// way to get preview + recording + per-frame analysis in one pipeline.
//
// Surface kept stable so VideoRecordingViewModel can call into it the same way.

import Foundation
import AVFoundation
import UIKit
import CoreImage
import VideoToolbox

enum CameraControllerError: Error {
    case configuration(String)
    case accessDenied
    case recording(String)
}

@MainActor
final class CameraController: NSObject {

    static let shared = CameraController()

    // MARK: - Public state

    let session = AVCaptureSession()
    private(set) var recordingActive = false
    private(set) var paused = false

    /// Set by the view-model. Called whenever the ML/luma processor produces a fresh result.
    var onDetectionUpdate: ((DetectionResult) -> Void)?

    /// When true, the Kiwi (Core ML) inference is run on incoming frames. Mirrors
    /// `runKiwiInferenceEnabled` from the Android controller.
    var runKiwiInferenceEnabled: Bool = false

    /// Analysis pipeline always runs at this size for the detection processor.
    let defaultAnalysisSize = CGSize(width: 1280, height: 720)

    // MARK: - Private

    private let videoDataOutput = AVCaptureVideoDataOutput()
    private let audioDataOutput = AVCaptureAudioDataOutput()
    private var assetWriter: AVAssetWriter?
    private var videoWriterInput: AVAssetWriterInput?
    private var audioWriterInput: AVAssetWriterInput?
    private var pixelBufferAdaptor: AVAssetWriterInputPixelBufferAdaptor?
    private var sessionStarted = false
    private var currentVideoURL: URL?

    private let sessionQueue = DispatchQueue(label: "tau.camera.session")
    private let videoQueue = DispatchQueue(label: "tau.camera.video", qos: .userInitiated)
    private let audioQueue = DispatchQueue(label: "tau.camera.audio")

    private let detection = KiwiDetectionProcessor()
    private var lastDetection: DetectionResult?

    private var videoDevice: AVCaptureDevice?
    private var videoInput: AVCaptureDeviceInput?

    private var pendingISO: Float?
    private var pendingExposureSeconds: Double?

    // MARK: - Lifecycle

    func warmUpDetection() async -> Bool {
        await detection.warmUp()
    }

    /// Wires inputs (video + audio), the analysis output, and starts the preview session.
    func startPreview(
        device: AVCaptureDevice,
        targetFps: Int = 30,
        iso: Int,
        exposureTimeNs: Int64,
        onStatus: @escaping (String) -> Void
    ) async {
        do {
            try await configureSession(device: device, targetFps: targetFps)
            try applyExposure(iso: iso, exposureTimeNs: exposureTimeNs)
            sessionQueue.async { [session] in
                if !session.isRunning { session.startRunning() }
            }
            onStatus("Preview running")
        } catch {
            AppHealthMonitor.shared.captureException(area: "camera.preview", error: error)
            onStatus("Camera error: \(error.localizedDescription)")
        }
    }

    func stop() {
        sessionQueue.async { [session] in
            if session.isRunning { session.stopRunning() }
        }
        finalizeAssetWriter()
    }

    // MARK: - Recording

    func startRecording(onStatus: @escaping (String) -> Void) -> String? {
        guard !recordingActive else { return currentVideoURL?.path }
        do {
            let url = Self.makeOutputURL(extension: "mp4")
            let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)

            // Default to 4K UHD (3840×2160) HEVC 10-bit. Pairing HEVC Main10 with the
            // device's HLG_BT2020 color space gives a true 10-bit pipeline equivalent
            // to the Android HLG10 / HDR10 capture. Bitrate is the same as the prior
            // H.264 setting — HEVC at 50 Mbps is comfortably above visually-lossless
            // for 4K30 footage.
            let videoSettings: [String: Any] = [
                AVVideoCodecKey: AVVideoCodecType.hevc,
                AVVideoWidthKey: 3840,
                AVVideoHeightKey: 2160,
                AVVideoCompressionPropertiesKey: [
                    AVVideoAverageBitRateKey: 50_000_000,
                    AVVideoExpectedSourceFrameRateKey: 30,
                    AVVideoProfileLevelKey: kVTProfileLevel_HEVC_Main10_AutoLevel as String
                ],
                // Tag the file with BT.2020 / HLG so players treat it as HDR rather than
                // SDR with a wide-gamut pixel format. Without this the encoder produces
                // 10-bit samples that some players display as washed-out SDR.
                AVVideoColorPropertiesKey: [
                    AVVideoColorPrimariesKey: AVVideoColorPrimaries_ITU_R_2020,
                    AVVideoTransferFunctionKey: AVVideoTransferFunction_ITU_R_2100_HLG,
                    AVVideoYCbCrMatrixKey: AVVideoYCbCrMatrix_ITU_R_2020
                ]
            ]
            let vInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
            vInput.expectsMediaDataInRealTime = true
            vInput.transform = CGAffineTransform(rotationAngle: .pi / 2)

            let audioSettings: [String: Any] = [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVNumberOfChannelsKey: 1,
                AVSampleRateKey: 44_100,
                AVEncoderBitRateKey: 64_000
            ]
            let aInput = AVAssetWriterInput(mediaType: .audio, outputSettings: audioSettings)
            aInput.expectsMediaDataInRealTime = true

            if writer.canAdd(vInput) { writer.add(vInput) }
            if writer.canAdd(aInput) { writer.add(aInput) }

            assetWriter = writer
            videoWriterInput = vInput
            audioWriterInput = aInput
            currentVideoURL = url
            sessionStarted = false

            recordingActive = true
            paused = false
            onStatus("Recording started")
            return url.path
        } catch {
            AppHealthMonitor.shared.captureException(area: "camera.record_start", error: error)
            onStatus("Recording error: \(error.localizedDescription)")
            return nil
        }
    }

    @discardableResult
    func pauseRecording() -> Bool {
        guard recordingActive, !paused else { return false }
        paused = true
        return true
    }

    @discardableResult
    func resumeRecording() -> Bool {
        guard recordingActive, paused else { return false }
        paused = false
        return true
    }

    func stopRecording() -> String? {
        guard recordingActive else { return currentVideoURL?.path }
        recordingActive = false
        paused = false
        let url = currentVideoURL
        finalizeAssetWriter()
        return url?.path
    }

    // MARK: - Exposure

    func updateExposure(iso: Int, exposureTimeNs: Int64) {
        try? applyExposure(iso: iso, exposureTimeNs: exposureTimeNs)
    }

    /// Mirror of Camera2's `updateExposureCompensation` — adjusts the AE bias in stops.
    func updateExposureCompensation(stops: Double) {
        guard let device = videoDevice else { return }
        do {
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }
            let target = Float(stops).clamped(to: device.minExposureTargetBias...device.maxExposureTargetBias)
            device.setExposureTargetBias(target, completionHandler: nil)
        } catch {
            AppHealthMonitor.shared.captureException(area: "camera.exposure_bias", error: error)
        }
    }

    func isStabilizationSupported() -> Bool {
        videoDevice?.activeFormat.isVideoStabilizationModeSupported(.cinematicExtended) ?? false
    }

    // MARK: - Helpers

    private func configureSession(device: AVCaptureDevice, targetFps: Int) async throws {
        session.beginConfiguration()
        defer { session.commitConfiguration() }

        for input in session.inputs { session.removeInput(input) }
        for output in session.outputs { session.removeOutput(output) }

        // Video input
        let input = try AVCaptureDeviceInput(device: device)
        if session.canAddInput(input) { session.addInput(input) }
        videoDevice = device
        videoInput = input

        // Audio
        if let mic = AVCaptureDevice.default(for: .audio),
           let micInput = try? AVCaptureDeviceInput(device: mic),
           session.canAddInput(micInput) {
            session.addInput(micInput)
        }

        // Pick the best 10-bit HLG format BEFORE adding outputs. Setting
        // `device.activeFormat` switches the session into `inputPriority` mode, so we
        // skip the `sessionPreset` call entirely.
        let picked10Bit = try select10BitHlgFormat(on: device, targetFps: targetFps)

        // Video data output (drives ML pipeline + writer). When we're capturing a 10-bit
        // HLG format we request the matching x420 (`420YpCbCr10BiPlanarVideoRange`) pixel
        // format so frames don't get downconverted to 8-bit before they reach the
        // encoder. We always include the 8-bit fallback for ML/detection paths.
        videoDataOutput.alwaysDiscardsLateVideoFrames = true
        if picked10Bit {
            videoDataOutput.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange
            ]
        } else {
            videoDataOutput.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
            ]
        }
        videoDataOutput.setSampleBufferDelegate(self, queue: videoQueue)
        if session.canAddOutput(videoDataOutput) { session.addOutput(videoDataOutput) }

        audioDataOutput.setSampleBufferDelegate(self, queue: audioQueue)
        if session.canAddOutput(audioDataOutput) { session.addOutput(audioDataOutput) }
    }

    /// Picks the highest-resolution 10-bit HLG-capable `AVCaptureDevice.Format` that can
    /// also hit `targetFps`, then locks the device into it (with HLG_BT2020 color space
    /// and the frame-rate range pinned at `targetFps`). Returns `true` when a 10-bit
    /// format was applied; `false` means we left the device's default `activeFormat`
    /// in place and the writer will receive 8-bit frames.
    @discardableResult
    private func select10BitHlgFormat(on device: AVCaptureDevice, targetFps: Int) throws -> Bool {
        let targetFpsD = Double(targetFps)
        let candidates = device.formats.filter { fmt in
            fmt.supportedColorSpaces.contains(.HLG_BT2020) &&
            fmt.videoSupportedFrameRateRanges.contains { range in
                range.minFrameRate <= targetFpsD && targetFpsD <= range.maxFrameRate
            }
        }

        // Prefer max pixels; tie-break by max sustained FPS so we pick the higher of
        // two equally-sized formats when one offers a wider FPS range.
        let best = candidates.max { a, b in
            let da = CMVideoFormatDescriptionGetDimensions(a.formatDescription)
            let db = CMVideoFormatDescriptionGetDimensions(b.formatDescription)
            let aPix = Int(da.width) * Int(da.height)
            let bPix = Int(db.width) * Int(db.height)
            if aPix != bPix { return aPix < bPix }
            let aMax = a.videoSupportedFrameRateRanges.map { $0.maxFrameRate }.max() ?? 0
            let bMax = b.videoSupportedFrameRateRanges.map { $0.maxFrameRate }.max() ?? 0
            return aMax < bMax
        }

        try device.lockForConfiguration()
        defer { device.unlockForConfiguration() }

        let desired = CMTimeMake(value: 1, timescale: Int32(targetFps))

        if let best {
            device.activeFormat = best
            device.activeColorSpace = .HLG_BT2020
            device.activeVideoMinFrameDuration = desired
            device.activeVideoMaxFrameDuration = desired
            return true
        }

        // No 10-bit option — keep the default activeFormat but still lock the FPS range
        // so the AE loop's manual shutter math stays predictable.
        if device.activeFormat.videoSupportedFrameRateRanges.contains(where: { range in
            range.minFrameRate <= targetFpsD && targetFpsD <= range.maxFrameRate
        }) {
            device.activeVideoMinFrameDuration = desired
            device.activeVideoMaxFrameDuration = desired
        }
        return false
    }

    /// Apply ISO + shutter (exposure-time in ns) to the back camera.
    private func applyExposure(iso: Int, exposureTimeNs: Int64) throws {
        guard let device = videoDevice else { return }
        try device.lockForConfiguration()
        defer { device.unlockForConfiguration() }

        let seconds = Double(exposureTimeNs) / 1_000_000_000.0
        let duration = CMTime(seconds: seconds, preferredTimescale: 1_000_000_000)

        let clampedIso = Float(iso).clamped(to: device.activeFormat.minISO...device.activeFormat.maxISO)
        if device.isExposureModeSupported(.custom) {
            device.setExposureModeCustom(duration: duration, iso: clampedIso, completionHandler: nil)
        }
    }

    private func finalizeAssetWriter() {
        guard let writer = assetWriter else { return }
        videoWriterInput?.markAsFinished()
        audioWriterInput?.markAsFinished()
        let group = DispatchGroup()
        group.enter()
        writer.finishWriting { group.leave() }
        _ = group.wait(timeout: .now() + 5)
        assetWriter = nil
        videoWriterInput = nil
        audioWriterInput = nil
    }

    static func makeOutputURL(extension ext: String) -> URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
            .appendingPathComponent("recordings", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let timestamp = Int(Date().timeIntervalSince1970 * 1000)
        return dir.appendingPathComponent("scan_\(timestamp).\(ext)")
    }
}

// MARK: - Sample buffer delegates

extension CameraController: AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureAudioDataOutputSampleBufferDelegate {

    nonisolated func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        // Video → writer + detection.
        if output === videoDataOutput {
            handleVideo(sampleBuffer: sampleBuffer)
        } else if output === audioDataOutput {
            handleAudio(sampleBuffer: sampleBuffer)
        }
    }

    nonisolated private func handleVideo(sampleBuffer: CMSampleBuffer) {
        // Hop to the main actor once, then make all the property-touching decisions there.
        Task { @MainActor [weak self] in
            guard let self else { return }

            // Encoder
            if self.recordingActive, !self.paused, let writer = self.assetWriter {
                if !self.sessionStarted {
                    let startTime = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
                    writer.startWriting()
                    writer.startSession(atSourceTime: startTime)
                    self.sessionStarted = true
                }
                if let input = self.videoWriterInput, input.isReadyForMoreMediaData {
                    input.append(sampleBuffer)
                }
            }

            // Detection (Vision request)
            guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
            let shouldRunModel = self.runKiwiInferenceEnabled
            Task.detached(priority: .userInitiated) { [weak self] in
                guard let self else { return }
                let result: DetectionResult
                if shouldRunModel {
                    result = await self.detection.processImage(pixelBuffer: pixelBuffer)
                } else {
                    result = self.detection.lumaOnly(pixelBuffer: pixelBuffer)
                }
                await MainActor.run {
                    self.lastDetection = result
                    self.onDetectionUpdate?(result)
                }
            }
        }
    }

    nonisolated private func handleAudio(sampleBuffer: CMSampleBuffer) {
        Task { @MainActor in
            guard recordingActive, !paused, sessionStarted,
                  let input = audioWriterInput, input.isReadyForMoreMediaData else { return }
            input.append(sampleBuffer)
        }
    }
}

// MARK: - Small utilities

extension Float {
    func clamped(to range: ClosedRange<Float>) -> Float {
        min(max(self, range.lowerBound), range.upperBound)
    }
}

extension Double {
    func clamped(to range: ClosedRange<Double>) -> Double {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
