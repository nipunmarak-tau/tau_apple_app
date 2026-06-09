// MultiVideoModels.swift
// Mirrors MultiVideoModels.kt.

import Foundation

struct MultiVideoStartRequest: Encodable {
    let block_id: Int
    let scan_type: Int
    let row_width: Double
    let row_height: Double
    let bay_length: Double
    let added_multiplier: Double
    let video_part: Int
}

struct VideoPartUrl: Decodable {
    let part_number: Int
    let upload_part_url: String
}

struct MultiVideoStartResponse: Decodable {
    let site_video_id: Int64
    let gpx_presigned_url: String
    let gpx_x_amz_tagging: String
    let csv_presigned_url: String
    let csv_x_amz_tagging: String
    let video_key: String
    let video_upload_id: String
    let video_parts: [VideoPartUrl]
    let sse: String
    let kmsKeyId: String

    enum CodingKeys: String, CodingKey {
        case site_video_id, gpx_presigned_url, gpx_x_amz_tagging,
             csv_presigned_url, csv_x_amz_tagging, video_key,
             video_upload_id, video_parts
        case sse = "x-amz-server-side-encryption"
        case kmsKeyId = "x-amz-server-side-encryption-aws-kms-key-id"
    }
}

struct CompletedPart: Encodable {
    // Capitalised — server expects S3-style keys.
    let PartNumber: Int
    let ETag: String
}

struct MultiVideoCompleteRequest: Encodable {
    let site_video_id: Int64
    let upload_id: String
    let video_key: String
    let video_parts: [CompletedPart]
}
