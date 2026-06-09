// RecordingView.swift
// Mirrors RecordingScreen.kt — camera preview + AE loop + overlays + start/stop/pause controls.
// The 1100-line Compose original packed AE math, GPS gating UI, HDR pickers, PiP support, and
// CSV preview together; the iOS version keeps the same surface but pushes most logic into the
// ViewModel and `CameraController` so the view is mostly layout + bindings.

import SwiftUI
import SwiftData
import AVFoundation
import CoreLocation

struct RecordingView: View {

    @Environment(AppRouter.self) private var router
    @Environment(\.modelContext) private var modelContext

    @State private var recordingVm: VideoRecordingViewModel?
    @State private var settingsVm: SettingsViewModel?
    @State private var previewSize: CGSize = .zero
    @State private var permissionStatus: AVAuthorizationStatus = .notDetermined
    @State private var aeLoopTask: Task<Void, Never>?
    @State private var currentEvLabel: String = "0.0"
    @State private var targetBrightness: Int = 181

    // Local AE state — kept here so the AE loop owns it (mirrors the Compose `LaunchedEffect`).
    @State private var currentIso: Int = 400
    @State private var currentShutterDenom: Int = 350

    private let preferredDenom: Int = 350
    private let maxDenom: Int = 2000
    private let minIso: Int = 100
    private let maxIso: Int = 3200

    var body: some View {
        Group {
            if let vm = recordingVm, let sVm = settingsVm {
                content(vm: vm, settings: sVm)
                    .task { await ensurePermissions() }
                    .onAppear { startPreviewIfNeeded(vm: vm, settings: sVm) }
                    .onDisappear { vm.stopCamera(); aeLoopTask?.cancel() }
            } else {
                ProgressView().task {
                    settingsVm = SettingsViewModel(context: modelContext)
                    recordingVm = VideoRecordingViewModel(context: modelContext)
                }
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button {
                    recordingVm?.stopCamera()
                    router.goBack()
                } label: { Image(systemName: "chevron.left") }
            }
        }
    }

    @ViewBuilder
    private func content(vm: VideoRecordingViewModel, settings: SettingsViewModel) -> some View {
        ZStack(alignment: .bottom) {
            Color.black.ignoresSafeArea()
            GeometryReader { proxy in
                ZStack {
                    CameraPreviewView(session: vm.controller.session)
                        .ignoresSafeArea()
                    DetectionOverlay(result: vm.detectionResult, viewSize: proxy.size)
                }
                .onAppear { previewSize = proxy.size }
                .onChange(of: proxy.size) { _, newSize in previewSize = newSize }
            }
            VStack {
                HStack(spacing: 8) {
                    Spacer()
                    statusChip
                    backendChip(vm: vm)
                }
                .padding(.top, 6)
                .padding(.horizontal, 12)

                Spacer()

                controlsBar(vm: vm, settings: settings)
            }
        }
    }

    private var statusChip: some View {
        Text(recordingVm?.uiText ?? "Idle")
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(Color.black.opacity(0.6), in: Capsule())
            .foregroundStyle(.white)
    }

    private func backendChip(vm: VideoRecordingViewModel) -> some View {
        let label: String = {
            guard vm.useKiwiFruitMetering() else { return "Off" }
            switch vm.detectionResult?.backend ?? .unknown {
            case .neuralEngine: return "ANE"
            case .gpu: return "GPU"
            case .cpu: return "CPU"
            case .unknown: return "Init…"
            }
        }()
        return Text(label)
            .font(.caption.weight(.bold))
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .background(Color.brandGreen, in: Capsule())
            .foregroundStyle(.white)
    }

    private func controlsBar(vm: VideoRecordingViewModel, settings: SettingsViewModel) -> some View {
        HStack(spacing: 24) {
            Button {
                vm.togglePauseResume()
            } label: {
                Image(systemName: vm.paused ? "play.circle.fill" : "pause.circle.fill")
                    .font(.system(size: 48))
            }
            .disabled(!vm.recording)
            .foregroundStyle(.white)

            Button {
                if vm.recording {
                    vm.stopRecording()
                } else if let device = activeDevice() {
                    vm.startRecording(device: device,
                                      fps: settings.fps,
                                      iso: currentIso,
                                      exposureTimeNs: Int64(1_000_000_000 / currentShutterDenom))
                }
            } label: {
                ZStack {
                    Circle().fill(vm.recording ? Color.red : Color.brandGreen).frame(width: 72, height: 72)
                    if vm.recording {
                        RoundedRectangle(cornerRadius: 6).fill(.white).frame(width: 24, height: 24)
                    } else {
                        Circle().fill(.white).frame(width: 28, height: 28)
                    }
                }
            }

            Button {
                router.navigate(to: .settings)
            } label: {
                Image(systemName: "gearshape.fill").font(.system(size: 38))
            }
            .foregroundStyle(.white)
        }
        .padding(.bottom, 24)
    }

    // MARK: - Permissions & preview

    private func ensurePermissions() async {
        permissionStatus = AVCaptureDevice.authorizationStatus(for: .video)
        if permissionStatus == .notDetermined {
            let granted = await AVCaptureDevice.requestAccess(for: .video)
            permissionStatus = granted ? .authorized : .denied
        }
        _ = await AVCaptureDevice.requestAccess(for: .audio)
    }

    private func activeDevice() -> AVCaptureDevice? {
        let useMain = ApiCache.shared.getUseMainRearCameraForScan()
        return useMain ? CameraSelector.findRearMain() : CameraSelector.findRearUltraWide()
    }

    private func startPreviewIfNeeded(vm: VideoRecordingViewModel, settings: SettingsViewModel) {
        guard !vm.previewRunning, let device = activeDevice() else { return }
        vm.startPreview(device: device,
                        fps: settings.fps,
                        iso: currentIso,
                        exposureTimeNs: Int64(1_000_000_000 / currentShutterDenom)) { _ in }
        startAutoExposureLoop(vm: vm, settings: settings)
    }

    // MARK: - Auto-exposure loop (mirrors RecordingScreen's LaunchedEffect)

    private func startAutoExposureLoop(vm: VideoRecordingViewModel, settings: SettingsViewModel) {
        aeLoopTask?.cancel()
        aeLoopTask = Task.detached { [vm] in
            var lastAvg = -1
            var internalIso = 400.0
            var internalDenom = Double(preferredDenom)

            while !Task.isCancelled {
                let loopStart = Date()
                let isRunning = await MainActor.run { vm.previewRunning || vm.recording }
                if isRunning {
                    let detection = await MainActor.run { vm.detectionResult }
                    let fruit = await MainActor.run { vm.useKiwiFruitMetering() }
                    let fruitIdentified = fruit
                        && detection != nil
                        && (detection?.confidenceScore ?? 0) > 3
                        && (detection?.objectLuma ?? 0) > 0
                    let avgVal: Int = fruitIdentified
                        ? (detection?.objectLuma ?? 0)
                        : (detection?.avgFrameLuma ?? 0)

                    if avgVal > 0 {
                        let brightnessError = targetBrightness - avgVal
                        let inDeadband = abs(brightnessError) <= 5 && (10...245).contains(avgVal)
                        if !inDeadband {
                            let suddenJump = lastAvg != -1 && abs(avgVal - lastAvg) > 40
                            let k: Double
                            switch (abs(brightnessError), suddenJump) {
                            case (_, true): k = 1.0
                            case (let e, _) where e > 100: k = 0.25
                            case (let e, _) where e > 50: k = 0.15
                            default: k = 0.08
                            }
                            let currentPower = internalIso * (1.0 / internalDenom)
                            let nextPower = currentPower * pow(Double(targetBrightness) / Double(avgVal), k)
                            var goalDenom = Double(preferredDenom)
                            var goalIso = nextPower * goalDenom
                            if goalIso > Double(maxIso) {
                                goalIso = Double(maxIso); goalDenom = goalIso / nextPower
                            }
                            if goalIso < Double(minIso) {
                                goalIso = Double(minIso); goalDenom = goalIso / nextPower
                            }
                            goalDenom = min(Double(maxDenom), max(Double(preferredDenom), goalDenom))
                            goalIso = min(Double(maxIso), max(Double(minIso), goalIso))
                            internalIso = goalIso
                            internalDenom = goalDenom

                            let applyIso = Int(internalIso.rounded())
                            let applyDenom = Int(internalDenom.rounded())
                            let shutterNs: Int64 = Int64(1_000_000_000 / applyDenom)
                            await MainActor.run {
                                vm.controller.updateExposure(iso: applyIso, exposureTimeNs: shutterNs)
                                self.currentIso = applyIso
                                self.currentShutterDenom = applyDenom
                                settings.iso = applyIso
                                settings.exposureTimeNs = shutterNs
                                let newExp = Double(applyIso) * (1.0 / Double(applyDenom))
                                let ev = log2(newExp / 0.8)
                                self.currentEvLabel = ev > 0
                                    ? String(format: "+%.1f", ev)
                                    : String(format: "%.1f", ev)
                            }
                        }

                        if await MainActor.run(body: { vm.recording }) {
                            let countNow = detection?.boundingBoxes.count ?? 0
                            let target = targetBrightness
                            let adjusted = target - avgVal
                            await MainActor.run {
                                vm.logMetrics(
                                    countPerFrame: countNow,
                                    currentBrightness: avgVal,
                                    targetedBrightness: target,
                                    adjustedBrightness: adjusted,
                                    currentIso: self.currentIso,
                                    adjustedIso: Int(internalIso.rounded()),
                                    currentShutterDenom: self.currentShutterDenom,
                                    adjustedShutterDenom: Int(internalDenom.rounded())
                                )
                            }
                        }
                        lastAvg = avgVal
                    }
                }
                let elapsed = Date().timeIntervalSince(loopStart) * 1000
                let sleepMs = max(0, 33 - elapsed)
                if sleepMs > 0 {
                    try? await Task.sleep(nanoseconds: UInt64(sleepMs * 1_000_000))
                }
            }
        }
    }
}
