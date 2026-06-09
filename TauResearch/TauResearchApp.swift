// TauResearchApp.swift
// Application entry point — mirrors CameraApp.kt + MainActivity.kt.

import SwiftUI
import SwiftData

@main
struct TauResearchApp: App {

    @State private var router = AppRouter()

    init() {
        AppHealthMonitor.shared.recordMetric(name: "app_launch")
        AppHealthMonitor.shared.recordDeviceHealthSnapshot()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(router)
                .preferredColorScheme(.light)
        }
        .modelContainer(for: [RecordingEntity.self, CameraSettingsEntity.self])
    }
}
