// GPXLogger.swift
// Streaming GPX writer used during recording. Mirrors the GPX output of RecordingService.kt.

import Foundation

final class GPXLogger {

    private let path: String
    private let name: String
    private var handle: FileHandle?
    private let dateFormatter: ISO8601DateFormatter

    init(path: String, name: String) {
        self.path = path
        self.name = name
        self.dateFormatter = ISO8601DateFormatter()
        self.dateFormatter.formatOptions = [.withInternetDateTime]
    }

    func open() {
        let header = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="Tau Research iOS" xmlns="http://www.topografix.com/GPX/1/1">
          <trk>
            <name>\(name)</name>
            <trkseg>
        """
        try? header.write(toFile: path, atomically: true, encoding: .utf8)
        handle = FileHandle(forWritingAtPath: path)
        try? handle?.seekToEnd()
    }

    func appendPoint(latitude: Double, longitude: Double, elevation: Double, timestamp: Date) {
        let line = """
              <trkpt lat="\(latitude)" lon="\(longitude)">
                <ele>\(elevation)</ele>
                <time>\(dateFormatter.string(from: timestamp))</time>
              </trkpt>

        """
        if let data = line.data(using: .utf8) {
            try? handle?.write(contentsOf: data)
        }
    }

    func close() {
        let footer = """
            </trkseg>
          </trk>
        </gpx>
        """
        if let data = footer.data(using: .utf8) { try? handle?.write(contentsOf: data) }
        try? handle?.close()
        handle = nil
    }
}
