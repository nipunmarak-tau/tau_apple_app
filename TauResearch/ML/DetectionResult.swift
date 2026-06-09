// DetectionResult.swift
// Mirrors DetectionResult.kt + TfliteDelegate.

import Foundation
import CoreGraphics

enum DetectionBackend: String {
    case unknown, neuralEngine, gpu, cpu
}

struct DetectionResult: Equatable {
    /// 0...100 — Android UI compares against 3f, 25f, etc.
    var confidenceScore: Float
    var status: String
    var boundingBoxes: [CGRect]
    var backend: DetectionBackend
    /// 0...255 luma over the detected subject area.
    var objectLuma: Int
    /// 0...255 luma averaged across the whole frame.
    var avgFrameLuma: Int
    var displayBoundingBox: CGRect?
    /// Source frame dimensions — used by overlay coord transforms.
    var frameWidth: CGFloat
    var frameHeight: CGFloat
}
