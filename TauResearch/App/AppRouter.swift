// AppRouter.swift
// Mirrors AppNavGraph.kt: a path-based router for the NavigationStack.

import Foundation
import SwiftUI

enum AppDestination: Hashable {
    case login
    case dashboard
    case createSiteBlock
    case record
    case settings
}

@Observable
final class AppRouter {

    /// Programmatic navigation stack. Mirrors `rememberNavController()`.
    var path: [AppDestination] = []

    /// Stored root destination — @Observable so ContentView re-renders on login/logout.
    var root: AppDestination = TokenManager.shared.accessToken != nil ? .dashboard : .login

    func navigate(to destination: AppDestination) {
        path.append(destination)
    }

    func goBack() {
        guard !path.isEmpty else { return }
        path.removeLast()
    }

    /// Replace the entire stack — used on login success / logout.
    func reset(to destination: AppDestination) {
        root = destination
        path = []
    }
}
