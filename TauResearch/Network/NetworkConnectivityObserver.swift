// NetworkConnectivityObserver.swift
// Mirrors NetworkConnectivityObserver.kt — emits true/false as connectivity changes.

import Foundation
import Network
import Observation

@Observable
final class NetworkConnectivityObserver {

    static let shared = NetworkConnectivityObserver()

    private(set) var isOnline: Bool = true
    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "tau.network.monitor")

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            Task { @MainActor in
                self?.isOnline = online
            }
        }
        monitor.start(queue: queue)
    }

    deinit {
        monitor.cancel()
    }
}
