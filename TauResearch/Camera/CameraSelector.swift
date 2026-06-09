// CameraSelector.swift
// Mirrors CameraSelector.kt — picks rear ultra-wide vs rear main on iOS via AVCaptureDevice.

import Foundation
import AVFoundation

enum CameraSelector {

    /// Best available rear ultra-wide. Falls back to the wide-angle if no ultra-wide exists.
    static func findRearUltraWide() -> AVCaptureDevice? {
        if let ultra = AVCaptureDevice.default(.builtInUltraWideCamera, for: .video, position: .back) {
            return ultra
        }
        return AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
    }

    /// Rear main "wide" camera. Falls back to whatever rear camera is available.
    static func findRearMain() -> AVCaptureDevice? {
        if let wide = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) {
            return wide
        }
        return findRearUltraWide()
    }
}
