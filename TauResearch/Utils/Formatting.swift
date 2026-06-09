// Formatting.swift
// Mirrors ui/utils/Formatting.kt.

import Foundation

func formatShutterFromNs(_ ns: Int64) -> String {
    guard ns > 0 else { return "—" }
    let t = Double(ns) / 1_000_000_000.0
    if t < 1.0 {
        let denom = max(1, Int((1.0 / t).rounded()))
        return "1/\(denom) s"
    } else {
        return String(format: "%.2f s", t)
    }
}

func formatDuration(_ seconds: TimeInterval) -> String {
    let total = Int(seconds)
    let minutes = total / 60
    let secs = total % 60
    return String(format: "%02d:%02d", minutes, secs)
}
