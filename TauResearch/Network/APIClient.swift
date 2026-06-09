// APIClient.swift
// Mirrors RetrofitClient.kt + ApiService.kt — a single URLSession-based async/await client
// with a bearer-token interceptor.

import Foundation

enum APIMethod: String {
    case GET, POST, PUT, DELETE
}

final class APIClient {

    static let shared = APIClient()

    /// Same base URL as the Android app.
    let baseURL = URL(string: "https://kiwifruitiq.tau.co.nz/")!

    private let session: URLSession

    private init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 60 * 5
        self.session = URLSession(configuration: config)
    }

    // MARK: - Generic request

    /// Generic JSON request. Decodes the response body as `T`. Pass `Void.self` (via the
    /// `requestRaw` variant) for endpoints that return an empty/opaque body.
    func request<T: Decodable>(
        _ path: String,
        method: APIMethod,
        body: (any Encodable)? = nil,
        decode: T.Type = T.self
    ) async throws -> T {
        let data = try await requestRaw(path, method: method, body: body)
        do {
            return try JSONDecoder.tau.decode(T.self, from: data)
        } catch {
            AppHealthMonitor.shared.captureException(area: "network.decode", error: error)
            throw AppError.unexpected(underlying: error)
        }
    }

    /// Variant that returns the raw body — used for `getSiteBlock` which caches the JSON.
    func requestRaw(
        _ path: String,
        method: APIMethod,
        body: (any Encodable)? = nil
    ) async throws -> Data {
        var req = URLRequest(url: baseURL.appendingPathComponent(path))
        req.httpMethod = method.rawValue

        if let token = TokenManager.shared.accessToken, !token.isEmpty {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        if let body {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONEncoder.tau.encode(AnyEncodable(body))
        }

        do {
            let (data, response) = try await session.data(for: req)
            guard let http = response as? HTTPURLResponse else {
                throw AppError.unexpected(underlying: nil)
            }
            if http.statusCode == 401 {
                // Mirror the Android auth interceptor — clear token on 401.
                TokenManager.shared.clear()
                AppHealthMonitor.shared.reportIssue(
                    .init(
                        key: "auth_401_token_cleared",
                        message: "Received 401 from API and cleared auth token",
                        severity: .high,
                        area: "auth.network",
                        attributes: ["url": String(req.url?.absoluteString.prefix(140) ?? "")]
                    )
                )
                throw AppError.auth()
            }
            guard (200..<300).contains(http.statusCode) else {
                throw AppError.server(code: http.statusCode)
            }
            return data
        } catch let err as AppError {
            throw err
        } catch let err as URLError {
            throw AppError.network(underlying: err)
        } catch {
            throw AppError.unexpected(underlying: error)
        }
    }
}

// MARK: - Codable helpers

extension JSONDecoder {
    static var tau: JSONDecoder {
        let d = JSONDecoder()
        // Keep snake_case keys as-is — DTOs declare explicit CodingKeys.
        return d
    }
}

extension JSONEncoder {
    static var tau: JSONEncoder {
        let e = JSONEncoder()
        return e
    }
}

/// Type-erasing wrapper so the client can accept `any Encodable` without losing fidelity.
struct AnyEncodable: Encodable {
    private let _encode: (Encoder) throws -> Void
    init<T: Encodable>(_ wrapped: T) { _encode = wrapped.encode }
    func encode(to encoder: Encoder) throws { try _encode(encoder) }
}
