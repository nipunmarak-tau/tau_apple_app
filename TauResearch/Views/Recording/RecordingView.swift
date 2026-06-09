// RecordingView.swift
// Mirrors RecordingScreen.kt — scrollable layout with the camera preview at the top, then
// status / HDR / brightness / controls cards stacked below. The view stays a thin shell:
// the AE loop owns ISO/shutter math, the ViewModel owns recording state + GPS gating, and
// `CameraController` owns the AVFoundation pipeline.

import SwiftUI
import SwiftData
import AVFoundation
import CoreLocation

struct RecordingView: View {

    @Environment(AppRouter.self) private var router
    @Environment(\.modelContext) private var modelContext
    @Environment(\.scenePhase) private var scenePhase

    @State private var recordingVm: VideoRecordingViewModel?
    @State private var settingsVm: SettingsViewModel?
    @State private var permissionStatus: AVAuthorizationStatus = .notDetermined
    @State private var aeLoopTask: Task<Void, Never>?
    @State private var currentEvLabel: String = "0.0"
    @State private var currentBrightness: Int = 0
    @State private var targetBrightness: Int = 181

    // Local AE state — kept here so the AE loop owns it (mirrors the Compose `LaunchedEffect`).
    @State private var currentIso: Int = 400
    @State private var currentShutterDenom: Int = 350

    // HDR selection mirrors `recordingVm.selectedDynamicRange`. iOS exposes HLG10 via
    // AVCaptureDevice; HDR10 / HDR10+ aren't separate AVCapture profiles, so HLG10 is the
    // only true 10-bit option here.
    @State private var selectedHdrProfile: Int = DynamicRangeProfile.standard.rawValue
    @State private var stabilizationEnabled: Bool = false

    // Hold-to-stop gesture state — tracks which button is currently being pressed.
    @State private var holdingStopPreview: Bool = false
    @State private var holdingStopRecording: Bool = false

    // GPS settings dialog
    @State private var showGpsDialog: Bool = false

    // CSV preview dialog
    @State private var csvDialogTitle: String = ""
    @State private var csvDialogText: String? = nil

    // Target-brightness picker
    @State private var showTargetBrightnessMenu: Bool = false

    private let brandColor = Color(red: 0x16 / 255.0, green: 0x4D / 255.0, blue: 0x3D / 255.0)
    private let preferredDenom: Int = 350
    private let maxDenom: Int = 2000
    private let minIso: Int = 100
    private let maxIso: Int = 3200

    // Target FPS — locked to 30 to mirror the Android Range(30, 30).
    private let targetFps: Int = 30

    var body: some View {
        Group {
            if let vm = recordingVm, let sVm = settingsVm {
                content(vm: vm, settings: sVm)
                    .task {
                        await ensurePermissions()
                        UIApplication.shared.isIdleTimerDisabled = true
                    }
                    .onDisappear {
                        UIApplication.shared.isIdleTimerDisabled = false
                        aeLoopTask?.cancel()
                        if !vm.recording { vm.stopCamera() }
                    }
                    .onChange(of: scenePhase) { _, newPhase in
                        if newPhase == .background && !vm.recording {
                            vm.stopCamera()
                        }
                    }
            } else {
                ProgressView().task {
                    settingsVm = SettingsViewModel(context: modelContext)
                    recordingVm = VideoRecordingViewModel(context: modelContext)
                    startAutoExposureLoop()
                }
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(brandColor, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text("Start Scan")
                    .font(.headline.weight(.semibold))
                    .foregroundStyle(.white)
            }
            ToolbarItem(placement: .topBarLeading) {
                Button {
                    recordingVm?.stopCamera()
                    router.goBack()
                } label: {
                    Image(systemName: "chevron.left").foregroundStyle(.white)
                }
            }
        }
        .alert("Enable GPS", isPresented: $showGpsDialog) {
            Button("Open Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("GPS is required to record the track for your video. Please enable it in the device settings.")
        }
        .alert(csvDialogTitle, isPresented: Binding(
            get: { csvDialogText != nil },
            set: { if !$0 { csvDialogText = nil } }
        )) {
            Button("Close", role: .cancel) { csvDialogText = nil }
        } message: {
            Text(csvDialogText ?? "")
        }
    }

    // MARK: - Layout

    @ViewBuilder
    private func content(vm: VideoRecordingViewModel, settings: SettingsViewModel) -> some View {
        ScrollView {
            VStack(spacing: 16) {
                cameraPreviewBox(vm: vm)
                statusCard(vm: vm, settings: settings)
                hdrCard(vm: vm)
                brightnessCard()
                controlsCard(vm: vm, settings: settings)
                segmentCard(vm: vm)
            }
            .padding(16)
        }
        .background(Color(.systemGroupedBackground))
    }

    // MARK: - Camera preview box

    private func cameraPreviewBox(vm: VideoRecordingViewModel) -> some View {
        let screenHeight = UIScreen.main.bounds.height
        let previewHeight = screenHeight * 0.6 // a touch tighter than Android's 80% so the cards stay reachable

        return ZStack(alignment: .topLeading) {
            GeometryReader { proxy in
                ZStack {
                    CameraPreviewView(session: vm.controller.session)
                    DetectionOverlay(result: vm.detectionResult, viewSize: proxy.size)
                }
            }
            .background(Color.black)
            .clipShape(RoundedRectangle(cornerRadius: 12))

            if vm.previewRunning || vm.recording {
                previewOverlay(vm: vm)
                    .padding(8)
            }
        }
        .frame(height: previewHeight)
    }

    private func previewOverlay(vm: VideoRecordingViewModel) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            if vm.recording {
                Text(formatDuration(vm.recordingDuration))
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(.white)
            }
            if let loc = RecordingService.shared.currentLocation {
                Text(String(format: "%.6f, %.6f", loc.coordinate.latitude, loc.coordinate.longitude))
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.white)
            } else {
                Text("Waiting for GPS...")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.7))
            }
            if let result = vm.detectionResult {
                Text(result.status)
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(result.status == "Identified" ? .green : .white)
                if result.status == "Identified" {
                    Text("Object Brightness: \(result.objectLuma)")
                        .font(.footnote.weight(.bold))
                        .foregroundStyle(.yellow)
                }
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(Color.black.opacity(0.5), in: RoundedRectangle(cornerRadius: 8))
    }

    // MARK: - Status card

    private func statusCard(vm: VideoRecordingViewModel, settings: SettingsViewModel) -> some View {
        let gpsEnabled = isLocationAuthorized()
        let cameraLabel = ApiCache.shared.getUseMainRearCameraForScan() ? "Main" : "Ultra Wide"
        // Reflects what CameraController's session preset + AVAssetWriter actually deliver:
        // 4K UHD when the lens supports it, otherwise the AVCaptureSession.Preset.high default.
        let videoSize = activeDevice().map { device -> String in
            let supports4K = device.formats.contains { fmt in
                let dims = CMVideoFormatDescriptionGetDimensions(fmt.formatDescription)
                return dims.width >= 3840 && dims.height >= 2160
            }
            return supports4K ? "3840×2160" : "1920×1080"
        } ?? "3840×2160"
        let exposureNs: Int64 = currentShutterDenom > 0 ? Int64(1_000_000_000 / currentShutterDenom) : 0
        let backendLabel = tfliteDelegateLabel(vm: vm)

        return card {
            VStack(alignment: .leading, spacing: 8) {
                Text("Status: \(vm.uiText)")
                    .font(.body.weight(.semibold))
                HStack {
                    Text(gpsEnabled ? "GPS: On" : "GPS: Off")
                        .foregroundStyle(gpsEnabled ? .green : .red)
                    Spacer()
                    Text("TFLite: \(backendLabel)")
                        .foregroundStyle(tfliteDelegateColor(vm: vm))
                }
                Divider()
                HStack {
                    Spacer()
                    InfoChip(text: "Video: \(videoSize)")
                    Spacer()
                    InfoChip(text: "Camera: \(cameraLabel)")
                    Spacer()
                }
                HStack {
                    Spacer()
                    InfoChip(text: "ISO: \(currentIso)")
                    Spacer()
                    InfoChip(text: "FPS: \(targetFps)")
                    Spacer()
                    InfoChip(text: "Shutter: \(formatShutterFromNs(exposureNs))")
                    Spacer()
                }
            }
        }
    }

    private func tfliteDelegateLabel(vm: VideoRecordingViewModel) -> String {
        guard vm.useKiwiFruitMetering() else { return "Off" }
        switch vm.detectionResult?.backend ?? .unknown {
        case .neuralEngine: return "ANE"
        case .gpu: return "GPU"
        case .cpu: return "CPU"
        case .unknown: return "Initializing..."
        }
    }

    private func tfliteDelegateColor(vm: VideoRecordingViewModel) -> Color {
        guard vm.useKiwiFruitMetering() else { return .gray }
        switch vm.detectionResult?.backend ?? .unknown {
        case .neuralEngine: return Color(red: 0x15 / 255.0, green: 0x65 / 255.0, blue: 0xC0 / 255.0)
        case .gpu:          return Color(red: 0x2E / 255.0, green: 0x7D / 255.0, blue: 0x32 / 255.0)
        case .cpu:          return Color(red: 0x6D / 255.0, green: 0x4C / 255.0, blue: 0x41 / 255.0)
        case .unknown:      return Color(red: 0xF9 / 255.0, green: 0xA8 / 255.0, blue: 0x25 / 255.0)
        }
    }

    // MARK: - HDR card

    private func hdrCard(vm: VideoRecordingViewModel) -> some View {
        let supportedHdr = supportedHdrProfiles()
        let supportsStab = stabilizationSupported()
        let manualAeAllowed = true // iOS sensor AE stays available even for HLG10 capture
        // Chips render in HDR10+ → HDR10 → HLG10 order so the highest-priority option
        // appears first and matches the auto-selected default.
        let chipsToShow = DynamicRangeProfile.priorityOrder.filter {
            supportedHdr.contains($0.rawValue)
        }

        return Group {
            if !supportedHdr.isEmpty {
                card {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Dynamic Range")
                            .font(.subheadline.weight(.bold))
                        HStack(spacing: 8) {
                            ForEach(chipsToShow, id: \.rawValue) { profile in
                                HdrChip(
                                    label: profile.label,
                                    isSelected: selectedHdrProfile == profile.rawValue,
                                    action: {
                                        selectedHdrProfile = profile.rawValue
                                        vm.selectedDynamicRange = profile.rawValue
                                    }
                                )
                            }
                            Spacer()
                            Text("Stabilization").font(.caption)
                            Toggle("", isOn: $stabilizationEnabled)
                                .labelsHidden()
                                .disabled(!supportsStab)
                                .onChange(of: stabilizationEnabled) { _, enabled in
                                    vm.uiText = supportsStab
                                        ? (enabled ? "Stabilization ON" : "Stabilization OFF")
                                        : "Stabilization unsupported on this camera"
                                }
                        }
                        Text(manualAeAllowed
                            ? "Manual ISO/Shutter updates: Enabled (HDR stays 10-bit)"
                            : "Manual ISO/Shutter updates: Disabled (Scene HDR legacy)")
                            .font(.caption)
                    }
                }
                .onAppear {
                    // Auto-select the highest-priority HDR profile on first show. We
                    // only overwrite if the current selection is the SDR sentinel; if
                    // the user has already picked something, we respect that.
                    if selectedHdrProfile == DynamicRangeProfile.standard.rawValue,
                       let best = bestHdrProfile() {
                        selectedHdrProfile = best
                        vm.selectedDynamicRange = best
                    }
                }
            }
        }
    }

    // MARK: - Brightness stats card

    private func brightnessCard() -> some View {
        card {
            VStack(alignment: .leading, spacing: 8) {
                Text("Pixel Brightness Stats (0-255)")
                    .font(.subheadline.weight(.bold))
                HStack {
                    Spacer()
                    InfoChip(text: "Current: \(currentBrightness)")
                    Spacer()
                    InfoChip(text: "Target: \(targetBrightness)")
                    Spacer()
                }
                HStack {
                    Spacer()
                    Menu {
                        ForEach([64, 90, 128, 150, 181, 210, 230], id: \.self) { brightness in
                            Button("\(brightness)") {
                                targetBrightness = brightness
                            }
                        }
                    } label: {
                        HStack(spacing: 4) {
                            Text("Target Brightness: \(targetBrightness)")
                            Image(systemName: "chevron.down")
                        }
                        .font(.body)
                    }
                    Spacer()
                }
            }
        }
    }

    // MARK: - Controls card

    private func controlsCard(vm: VideoRecordingViewModel, settings: SettingsViewModel) -> some View {
        let hdrSupported = !supportedHdrProfiles().isEmpty
        let canStartPreview = hdrSupported && !vm.previewRunning && !vm.recording
        let canStopPreview = vm.previewRunning && !vm.recording
        let isRecording = vm.recording
        let canStartRecording = hdrSupported && !isRecording && vm.recordingReady
        let gpsEnabled = isLocationAuthorized()
        let waitingForGps = vm.previewRunning && !isRecording && gpsEnabled && !vm.gpsReadyForRecording

        return card {
            VStack(alignment: .leading, spacing: 10) {
                if !hdrSupported {
                    Text("This device does not support HDR")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.red)
                }
                HStack(spacing: 12) {
                    Button {
                        startPreview(vm: vm, settings: settings)
                    } label: {
                        Label("Start Preview", systemImage: "eye")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(brandColor)
                    .disabled(!canStartPreview)

                    Button {
                        // tap alone is a no-op; hold to stop
                    } label: {
                        Label("Stop", systemImage: "eye.slash")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(!canStopPreview)
                    .simultaneousGesture(
                        LongPressGesture(minimumDuration: 3.0).onEnded { _ in
                            guard canStopPreview else { return }
                            vm.stopCamera()
                            vm.uiText = "Stopped."
                        }
                    )
                }

                Divider()

                Button {
                    if !vm.previewRunning {
                        vm.uiText = "Start the Preview first"
                    } else if !isLocationAuthorized() {
                        showGpsDialog = true
                    } else if let device = activeDevice() {
                        vm.startRecording(device: device,
                                          fps: targetFps,
                                          iso: currentIso,
                                          exposureTimeNs: Int64(1_000_000_000 / max(1, currentShutterDenom)))
                    }
                } label: {
                    Label(waitingForGps ? "Waiting for GPS Point" : "Start Recording",
                          systemImage: "video.fill")
                        .frame(maxWidth: .infinity)
                        .frame(height: 48)
                }
                .buttonStyle(.borderedProminent)
                .tint(brandColor)
                .disabled(!canStartRecording)

                HStack(spacing: 12) {
                    Button {
                        // tap alone is a no-op; hold to stop
                    } label: {
                        Label("Stop Recording", systemImage: "stop.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(!isRecording)
                    .simultaneousGesture(
                        LongPressGesture(minimumDuration: 3.0).onEnded { _ in
                            guard isRecording else { return }
                            vm.stopRecording()
                        }
                    )
                }
            }
        }
    }

    // MARK: - Segment card

    private func segmentCard(vm: VideoRecordingViewModel) -> some View {
        Group {
            if let segment = vm.lastSegment {
                card {
                    VStack(spacing: 12) {
                        Text("Ready to Upload")
                            .font(.headline)
                        Button {
                            router.navigate(to: .dashboard)
                        } label: {
                            Label("Go to Dashboard", systemImage: "arrow.right")
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(brandColor)

                        Button {
                            presentCsvPreview(for: segment)
                        } label: {
                            Text("View CSV")
                        }
                        .buttonStyle(.bordered)
                    }
                    .frame(maxWidth: .infinity)
                }
                .transition(.opacity.combined(with: .scale))
            }
        }
        .animation(.easeInOut(duration: 0.2), value: vm.lastSegment)
    }

    // MARK: - Card chrome

    @ViewBuilder
    private func card<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        content()
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .shadow(color: .black.opacity(0.08), radius: 2, y: 1)
    }

    // MARK: - Permissions / camera selection

    private func ensurePermissions() async {
        permissionStatus = AVCaptureDevice.authorizationStatus(for: .video)
        if permissionStatus == .notDetermined {
            let granted = await AVCaptureDevice.requestAccess(for: .video)
            permissionStatus = granted ? .authorized : .denied
        }
        _ = await AVCaptureDevice.requestAccess(for: .audio)
        let locStatus = CLLocationManager().authorizationStatus
        if locStatus == .notDetermined {
            CLLocationManager().requestWhenInUseAuthorization()
        }
    }

    private func activeDevice() -> AVCaptureDevice? {
        let useMain = ApiCache.shared.getUseMainRearCameraForScan()
        return useMain ? CameraSelector.findRearMain() : CameraSelector.findRearUltraWide()
    }

    /// Mirrors Android's `locationManager.isProviderEnabled(GPS_PROVIDER)`. On iOS the closest
    /// signal is "the app is authorized to read location" — when authorization is denied or
    /// restricted, GPS effectively isn't available to us, so we route the user to settings.
    private func isLocationAuthorized() -> Bool {
        let status = CLLocationManager().authorizationStatus
        return status == .authorizedAlways || status == .authorizedWhenInUse
    }

    private func startPreview(vm: VideoRecordingViewModel, settings: SettingsViewModel) {
        guard !vm.previewRunning, let device = activeDevice() else { return }
        vm.uiText = "Starting preview…"
        vm.startPreview(device: device,
                        fps: targetFps,
                        iso: currentIso,
                        exposureTimeNs: Int64(1_000_000_000 / max(1, currentShutterDenom))) { _ in }
    }

    private func presentCsvPreview(for segment: RecordingSegment) {
        let csvPath = (segment.videoPath as NSString).deletingPathExtension + ".csv"
        let url = URL(fileURLWithPath: csvPath)
        csvDialogTitle = url.lastPathComponent
        guard FileManager.default.fileExists(atPath: csvPath),
              let raw = try? String(contentsOfFile: csvPath) else {
            csvDialogText = "CSV not found at:\n\(csvPath)"
            return
        }
        let lines = raw.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        let totalRows = max(0, lines.count - 1)
        let head = Array(lines.prefix(31))
        let tail = lines.count > 31 ? Array(lines.suffix(10)) : []
        let attrs = (try? FileManager.default.attributesOfItem(atPath: csvPath)) ?? [:]
        let size = (attrs[.size] as? NSNumber)?.intValue ?? 0
        var output = "Path: \(csvPath)\n"
        output += "Size: \(size) bytes\n"
        output += "Rows: \(totalRows)\n\n"
        output += head.joined(separator: "\n")
        if !tail.isEmpty {
            output += "\n…\n" + tail.joined(separator: "\n")
        }
        csvDialogText = output
    }

    // MARK: - HDR support detection

    /// Returns the set of `DynamicRangeProfile` raw values the rear-camera system can
    /// produce. We scan **every** rear camera (not just `activeDevice()`) because
    /// HLG_BT2020 is often exposed only on the main wide lens — checking just the
    /// ultra-wide misses HDR on Pro phones that genuinely support it.
    ///
    /// iOS specifics:
    ///   * **HLG10** — `Format.supportedColorSpaces` contains `.HLG_BT2020`. This is the
    ///     native HDR capture path on iPhone.
    ///   * **HDR10** — iOS's HEVC encoder can tag a 10-bit stream with the BT.2100 PQ
    ///     transfer function (`AVVideoTransferFunction_SMPTE_ST_2084_PQ`), giving a
    ///     valid HDR10 container. This is available whenever the device has any HDR-
    ///     capable format (`isVideoHDRSupported` OR HLG_BT2020).
    ///   * **HDR10+** — not currently exposed by AVFoundation; left out.
    private func supportedHdrProfiles() -> Set<Int> {
        let discovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: [
                .builtInWideAngleCamera,
                .builtInUltraWideCamera,
                .builtInTelephotoCamera,
                .builtInDualCamera,
                .builtInDualWideCamera,
                .builtInTripleCamera
            ],
            mediaType: .video,
            position: .back
        )

        var profiles: Set<Int> = []

        // HLG10 needs the BT.2020 HLG color space on at least one format.
        let supportsHlg = discovery.devices.contains { device in
            device.formats.contains { $0.supportedColorSpaces.contains(.HLG_BT2020) }
        }

        // HDR10 needs any HDR-capable format (legacy flag OR HLG10 color space). The
        // encoder writes BT.2100 PQ for HDR10 output.
        let supportsAnyHdr = discovery.devices.contains { device in
            device.formats.contains { $0.isVideoHDRSupported || $0.supportedColorSpaces.contains(.HLG_BT2020) }
        }

        if supportsHlg { profiles.insert(DynamicRangeProfile.hlg10.rawValue) }
        if supportsAnyHdr { profiles.insert(DynamicRangeProfile.hdr10.rawValue) }

        return profiles
    }

    /// Picks the best available HDR profile in HDR10+ → HDR10 → HLG10 order, returning
    /// `nil` when no HDR is supported. Used for the auto-select on first appearance.
    private func bestHdrProfile() -> Int? {
        let supported = supportedHdrProfiles()
        for profile in DynamicRangeProfile.priorityOrder where supported.contains(profile.rawValue) {
            return profile.rawValue
        }
        return nil
    }

    private func stabilizationSupported() -> Bool {
        guard let device = activeDevice() else { return false }
        return device.activeFormat.isVideoStabilizationModeSupported(.auto)
            || device.activeFormat.isVideoStabilizationModeSupported(.cinematic)
    }

    // MARK: - Auto-exposure loop (mirrors RecordingScreen's LaunchedEffect)

    private func startAutoExposureLoop() {
        aeLoopTask?.cancel()
        aeLoopTask = Task.detached {
            var lastAvg = -1
            var internalIso = 400.0
            var internalDenom = Double(preferredDenom)

            while !Task.isCancelled {
                let loopStart = Date()
                let vmRef: VideoRecordingViewModel? = await MainActor.run { recordingVm }
                guard let vm = vmRef else {
                    try? await Task.sleep(nanoseconds: 100_000_000)
                    continue
                }
                let settingsRef: SettingsViewModel? = await MainActor.run { settingsVm }
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

                    await MainActor.run { currentBrightness = avgVal }

                    if avgVal > 0 {
                        let target = await MainActor.run { targetBrightness }
                        let brightnessError = target - avgVal
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
                            let nextPower = currentPower * pow(Double(target) / Double(avgVal), k)
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
                            let shutterNs: Int64 = Int64(1_000_000_000 / max(1, applyDenom))

                            await MainActor.run {
                                let isoDelta = abs(applyIso - currentIso)
                                let denomDelta = abs(applyDenom - currentShutterDenom)
                                if isoDelta >= 5 || denomDelta >= 5 {
                                    vm.controller.updateExposure(iso: applyIso, exposureTimeNs: shutterNs)
                                    currentIso = applyIso
                                    currentShutterDenom = applyDenom
                                    settingsRef?.iso = applyIso
                                    settingsRef?.exposureTimeNs = shutterNs
                                    let newExp = Double(applyIso) * (1.0 / Double(applyDenom))
                                    let ev = log2(newExp / 0.8)
                                    currentEvLabel = ev > 0
                                        ? String(format: "+%.1f", ev)
                                        : String(format: "%.1f", ev)
                                }
                            }
                        }

                        if await MainActor.run(body: { vm.recording }) {
                            let countNow = detection?.boundingBoxes.count ?? 0
                            let adjusted = target - avgVal
                            await MainActor.run {
                                vm.logMetrics(
                                    countPerFrame: countNow,
                                    currentBrightness: avgVal,
                                    targetedBrightness: target,
                                    adjustedBrightness: adjusted,
                                    currentIso: currentIso,
                                    adjustedIso: Int(internalIso.rounded()),
                                    currentShutterDenom: currentShutterDenom,
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

// MARK: - Dynamic-range profile constants

/// Mirrors `android.hardware.camera2.params.DynamicRangeProfiles` raw values so the iOS
/// view-model and Android view-model can share the same `selectedDynamicRange` semantics.
enum DynamicRangeProfile: Int {
    case standard = 1
    case hlg10 = 2
    case hdr10 = 4
    case hdr10Plus = 8

    /// Short label shown in the HDR chip.
    var label: String {
        switch self {
        case .standard:  return "SDR"
        case .hlg10:     return "HLG10"
        case .hdr10:     return "HDR10"
        case .hdr10Plus: return "HDR10+"
        }
    }

    /// HDR priority order — HDR10+ first, then HDR10, then HLG10. Mirrors the Android
    /// VM's default-pick logic that prefers HDR10_PLUS over HDR10 over HLG10.
    static var priorityOrder: [DynamicRangeProfile] {
        [.hdr10Plus, .hdr10, .hlg10]
    }
}

// MARK: - Small reusable chips

struct InfoChip: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(.secondary)
    }
}

struct HdrChip: View {
    let label: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if isSelected {
                    Image(systemName: "checkmark").font(.caption.weight(.bold))
                }
                Text(label).font(.subheadline)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(
                Capsule().fill(isSelected
                    ? Color.brandGreen.opacity(0.15)
                    : Color(.tertiarySystemFill))
            )
            .overlay(
                Capsule().stroke(isSelected ? Color.brandGreen : Color.gray.opacity(0.4),
                                 lineWidth: 1)
            )
            .foregroundStyle(isSelected ? Color.brandGreen : Color.primary)
        }
        .buttonStyle(.plain)
    }
}
