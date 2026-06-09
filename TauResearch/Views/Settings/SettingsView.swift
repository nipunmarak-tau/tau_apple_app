// SettingsView.swift
// Mirrors SettingsScreen.kt — target EV100, shutter selection, video resolution display.

import SwiftUI
import SwiftData
import AVFoundation

struct SettingsView: View {

    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var modelContext

    @State private var viewModel: SettingsViewModel?
    @State private var ev100Text: String = "0.00"
    @State private var ev100IsError: Bool = false

    var body: some View {
        Group {
            if let vm = viewModel {
                form(vm: vm)
            } else {
                ProgressView().task {
                    viewModel = SettingsViewModel(context: modelContext)
                    if let v = viewModel {
                        ev100Text = String(format: "%.2f", v.targetEv100)
                    }
                }
            }
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func form(vm: SettingsViewModel) -> some View {
        Form {
            Section("Exposure Settings") {
                TextField("Target EV100", text: $ev100Text)
                    .keyboardType(.numbersAndPunctuation)
                    .onChange(of: ev100Text) { _, newValue in
                        if let d = Double(newValue) {
                            if d < -4 || d > 4 { ev100IsError = true }
                            else { ev100IsError = false; vm.setTargetEv100(d) }
                        }
                    }
                if ev100IsError {
                    Text("Enter value between -4 and +4").foregroundStyle(.red).font(.caption)
                }
                Slider(value: Binding(
                    get: { vm.targetEv100 },
                    set: { vm.setTargetEv100($0) }
                ), in: -4...4)
                Text("Set Shutter: \(formatShutterFromNs(vm.exposureTimeNs))")
                Text("Auto ISO: \(vm.iso) (Range: \(vm.isoLimits.lowerBound) – \(vm.isoLimits.upperBound))")
                let calc = vm.calculateEv100(iso: vm.iso, exposureTimeNs: vm.exposureTimeNs)
                Text("Calculated EV100: \(calc.isFinite ? String(format: "%.2f", calc) : "—")")
            }

            Section("Shutter Speed") {
                let denominators: [Int] = [15, 30, 60, 120, 240, 250, 480, 500, 960, 1000, 2000, 4000, 8000]
                Picker("Shutter", selection: Binding(
                    get: { vm.exposureTimeNs },
                    set: { vm.setExposureTimeNs($0) }
                )) {
                    ForEach(denominators, id: \.self) { d in
                        let ns: Int64 = Int64(1_000_000_000) / Int64(d)
                        Text(formatShutterFromNs(ns)).tag(ns)
                    }
                }
            }

            Section("Video Resolution") {
                Text("\(vm.entity.videoWidth)×\(vm.entity.videoHeight)")
            }

            Section("Frame Rate (FPS)") {
                Text("\(vm.fps) (Fixed)")
            }

            Section {
                Button("Save Settings") {
                    vm.saveSettings()
                    dismiss()
                }
                if vm.saved {
                    Label("Settings saved", systemImage: "checkmark.circle.fill")
                        .foregroundStyle(Color.brandGreen)
                }
            }
        }
    }
}
