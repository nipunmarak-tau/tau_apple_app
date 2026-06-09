// LoginModels.swift
// Mirrors LoginRequest / LoginResponse / AppDeviceRequest.

import Foundation

struct LoginRequest: Encodable {
    let email: String
    let password: String
}

struct LoginResponse: Decodable {
    let access: String
    let refresh: String
}

struct AppDeviceRequest: Encodable {
    let email: String
    let device_type: String
    let device_model: String
}
