// CSVLogger.swift
// Streaming CSV writer for per-frame exposure/detection metrics.

import Foundation

final class CSVLogger {

    private let path: String
    private var handle: FileHandle?

    private let header = [
        "timestamp_ms",
        "kiwi_count",
        "current_brightness",
        "target_brightness",
        "adjusted_brightness",
        "current_iso",
        "adjusted_iso",
        "current_shutter_denom",
        "adjusted_shutter_denom"
    ].joined(separator: ",") + "\n"

    init(path: String) { self.path = path }

    func open() {
        try? header.write(toFile: path, atomically: true, encoding: .utf8)
        handle = FileHandle(forWritingAtPath: path)
        try? handle?.seekToEnd()
    }

    func appendRow(values: [String]) {
        let line = values.joined(separator: ",") + "\n"
        if let data = line.data(using: .utf8) {
            try? handle?.write(contentsOf: data)
        }
    }

    func close() {
        try? handle?.close()
        handle = nil
    }
}
