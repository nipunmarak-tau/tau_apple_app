// DeviceManager.swift
// Mirrors DeviceManager.kt — small wrapper around UserDefaults for device identity.

import Foundation
import UIKit

final class DeviceManager {

    static let shared = DeviceManager()
    private let defaults = UserDefaults(suiteName: "device_prefs") ?? .standard

    private init() {}

    func save(deviceType: String, deviceModel: String) {
        defaults.set(deviceType, forKey: "device_type")
        defaults.set(deviceModel, forKey: "device_model")
    }

    var deviceType: String? { defaults.string(forKey: "device_type") }
    var deviceModel: String? { defaults.string(forKey: "device_model") }

    func clear() {
        defaults.removeObject(forKey: "device_type")
        defaults.removeObject(forKey: "device_model")
    }

    /// "iOS" — used by the login flow when registering a device.
    static var currentDeviceType: String { "iOS" }

    /// Best-effort hardware model name, e.g. "iPhone15,2".
    static var currentDeviceModel: String {
        var sys = utsname()
        uname(&sys)
        let mirror = Mirror(reflecting: sys.machine)
        let machine = mirror.children.reduce(into: "") { result, child in
            guard let value = child.value as? Int8, value != 0 else { return }
            result.append(Character(UnicodeScalar(UInt8(value))))
        }
        return machine.isEmpty ? UIDevice.current.model : machine
    }
}
