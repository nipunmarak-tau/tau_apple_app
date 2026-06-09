// SiteBlockRepository.swift
// Mirrors SiteBlockRepository.kt.

import Foundation
import os.log

struct SiteBlockRepository {

    private let log = Logger(subsystem: "com.tau.research", category: "siteblock")

    func getCachedSiteBlock() -> SiteBlockResponse? {
        guard let json = ApiCache.shared.getFetchedSiteBlock(),
              let data = json.data(using: .utf8) else { return nil }
        do {
            return try JSONDecoder.tau.decode(SiteBlockResponse.self, from: data)
        } catch {
            log.error("Error parsing cached site block: \(error.localizedDescription, privacy: .public)")
            return nil
        }
    }

    func createSiteBlock(body: SiteBlockCreateRequest) async throws -> SiteBlockCreateResponse {
        log.debug("Create SiteBlock request body: \(String(describing: body), privacy: .public)")
        return try await APIClient.shared.request(
            "api/app/site-block",
            method: .POST,
            body: body,
            decode: SiteBlockCreateResponse.self
        )
    }

    func getCachedSubmitSiteBlock() -> SiteBlockCreateResponse? {
        guard let json = ApiCache.shared.getSubmitSiteBlock(),
              let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder.tau.decode(SiteBlockCreateResponse.self, from: data)
    }
}
