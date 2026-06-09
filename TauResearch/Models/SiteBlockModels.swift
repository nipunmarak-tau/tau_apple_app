// SiteBlockModels.swift
// Mirrors SiteBlockModels.kt + SiteBlockCreateRequest/Response.

import Foundation

// MARK: - Fetched catalogue

struct SiteBlockResponse: Codable, Equatable {
    var id: Int?
    var name: String?
    var company: String?
    var crop_type: String?
    var sites: [SiteItem] = []
    var scan_type: [ScanTypeItem] = []
}

struct SiteItem: Codable, Equatable {
    let site_id: Int
    let address: String?
    let total_blocks: Int?
    let reject_percent: Double?
    var blocks: [BlockItem] = []
}

struct BlockItem: Codable, Equatable {
    let block_id: Int
    let block_name: String?
    let total_rows: Int?
}

struct ScanTypeItem: Codable, Equatable {
    let id: Int
    let name: String?
}

// MARK: - Create site block (POST body)

struct SiteBlockCreateRequest: Codable, Equatable {
    let blockId: Int
    let scanTypeId: Int
    let rowWidth: Double
    let rowHeight: Double
    let bayLength: Double
    let addedMultiplier: Double

    enum CodingKeys: String, CodingKey {
        case blockId = "block_id"
        case scanTypeId = "scan_type"
        case rowWidth = "row_width"
        case rowHeight = "row_height"
        case bayLength = "bay_length"
        case addedMultiplier = "added_multiplier"
    }
}

// MARK: - Create site block (response)

struct SiteBlockCreateResponse: Codable {
    var message: String?
    var siteBlockId: Int?
    var gpxPresignedUrl: String?
    var videoPresignedUrl: String?
    var csvPresignedUrl: String?

    var sse: String?
    var kmsKeyId: String?
    var gpxTagging: String?
    var videoTagging: String?
    var csvTagging: String?

    enum CodingKeys: String, CodingKey {
        case message
        case siteBlockId = "site_block_id"
        case gpxPresignedUrl = "gpx_presigned_url"
        case videoPresignedUrl = "video_presigned_url"
        case csvPresignedUrl = "csv_presigned_url"
        case sse = "x-amz-server-side-encryption"
        case kmsKeyId = "x-amz-server-side-encryption-aws-kms-key-id"
        case gpxTagging = "gpx_x-amz-tagging"
        case videoTagging = "video_x-amz-tagging"
        case csvTagging = "csv_x-amz-tagging"
    }
}
