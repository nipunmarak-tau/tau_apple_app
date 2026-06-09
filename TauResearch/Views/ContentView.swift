// ContentView.swift
// Mirrors AppNavGraph.kt — NavigationStack rooted on the start destination.

import SwiftUI

struct ContentView: View {

    @Environment(AppRouter.self) private var router

    var body: some View {
        @Bindable var router = router
        NavigationStack(path: $router.path) {
            startView
                .navigationDestination(for: AppDestination.self) { destination in
                    view(for: destination)
                        .navigationBarBackButtonHidden(destination == .record || destination == .dashboard)
                }
        }
    }

    @ViewBuilder
    private var startView: some View {
        switch router.root {
        case .dashboard: DashboardView()
        case .login: LoginView()
        default: LoginView()
        }
    }

    @ViewBuilder
    private func view(for destination: AppDestination) -> some View {
        switch destination {
        case .login: LoginView()
        case .dashboard: DashboardView()
        case .createSiteBlock: CreateSiteBlockView()
        case .record: RecordingView()
        case .settings: SettingsView()
        }
    }
}

#Preview {
    ContentView().environment(AppRouter())
}
