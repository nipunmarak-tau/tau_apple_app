// VideoUploadRepository.swift
// Mirrors upload/repository/VideoUploadRepository.kt — chunked PUTs to presigned S3 URLs.

import Foundation
import os.log

struct VideoUploadRepository {

    private let log = Logger(subsystem: "com.tau.research", category: "upload")
    private let session: URLSession = .shared

    // 500 MB — matches Android implementation.
    static let chunkSize: Int64 = 500 * 1024 * 1024

    // MARK: - Video (chunked PUT)

    func uploadVideoInChunks(
        videoPath: String,
        presignedUrls: [String],
        progress: @escaping (Float) -> Void,
        isCancelled: () -> Bool
    ) async throws -> [String] {
        let fileURL = URL(fileURLWithPath: videoPath)
        let handle = try FileHandle(forReadingFrom: fileURL)
        defer { try? handle.close() }

        let fileSize = Int64(
            (try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        )
        let totalParts = Int((Double(fileSize) / Double(Self.chunkSize)).rounded(.up))
        var etags: [String] = []
        var uploadedBytes: Int64 = 0

        for partIndex in 0..<totalParts {
            if isCancelled() { break }
            let offset = Int64(partIndex) * Self.chunkSize
            let remaining = fileSize - offset
            let thisChunk = min(Self.chunkSize, remaining)

            try handle.seek(toOffset: UInt64(offset))
            let buffer = try handle.read(upToCount: Int(thisChunk)) ?? Data()
            guard partIndex < presignedUrls.count,
                  let url = URL(string: presignedUrls[partIndex]) else {
                throw AppError.unexpected(underlying: nil)
            }

            log.debug("Uploading chunk \(partIndex + 1)/\(totalParts, privacy: .public)")

            var req = URLRequest(url: url)
            req.httpMethod = "PUT"
            req.setValue("video/mp4", forHTTPHeaderField: "Content-Type")

            let (_, response) = try await session.upload(for: req, from: buffer)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                throw AppError.server(code: code, message: "Chunk \(partIndex + 1) failed")
            }
            let etag = http.value(forHTTPHeaderField: "ETag") ?? ""
            etags.append(etag)

            uploadedBytes += thisChunk
            progress(Float(uploadedBytes) / Float(fileSize))
        }
        return etags
    }

    // MARK: - GPX / CSV single PUT

    func uploadGpxFile(path: String, presignedUrl: String) async throws {
        try await uploadSingleFile(path: path, presignedUrl: presignedUrl, contentType: "application/gpx+xml")
    }

    func uploadCsvFile(path: String, presignedUrl: String) async throws {
        try await uploadSingleFile(path: path, presignedUrl: presignedUrl, contentType: "text/csv")
    }

    private func uploadSingleFile(path: String, presignedUrl: String, contentType: String) async throws {
        guard let url = URL(string: presignedUrl) else { throw AppError.unexpected(underlying: nil) }
        let fileURL = URL(fileURLWithPath: path)
        let data = try Data(contentsOf: fileURL)
        var req = URLRequest(url: url)
        req.httpMethod = "PUT"
        req.setValue(contentType, forHTTPHeaderField: "Content-Type")
        let (_, response) = try await session.upload(for: req, from: data)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw AppError.server(code: http.statusCode, message: "\(contentType) upload failed")
        }
    }

    // MARK: - Cached presigned URL accessors

    private func cachedSubmitResponse() -> SiteBlockCreateResponse? {
        guard let json = ApiCache.shared.getSubmitSiteBlock(),
              let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder.tau.decode(SiteBlockCreateResponse.self, from: data)
    }

    func presignedVideoUrls() -> [String] {
        guard let resp = cachedSubmitResponse(), let url = resp.videoPresignedUrl else { return [] }
        return [url]
    }

    func presignedGpxUrl() -> String? { cachedSubmitResponse()?.gpxPresignedUrl }
    func presignedCsvUrl() -> String? { cachedSubmitResponse()?.csvPresignedUrl }
}
