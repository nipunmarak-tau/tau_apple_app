// CreateSiteBlockViewModel.swift
// Mirrors CreateSiteBlockViewModel.kt + CreateSiteBlockUiState.

import Foundation
import Observation

struct CreateSiteBlockUiState {
    var data: SiteBlockResponse?
    var isLoading: Bool = false
    var error: String?
    var navigateNext: Bool = false

    var selectedSiteIndex: Int = -1
    var selectedBlockIndex: Int = -1
    var selectedScanTypeIndex: Int = -1

    var rowWidth: String = ""
    var rowHeight: String = ""
    var bayLength: String = ""

    var addLens: Bool = false
    var multiplier: String = ""
}

@Observable
@MainActor
final class CreateSiteBlockViewModel {

    private let repo = SiteBlockRepository()
    var state = CreateSiteBlockUiState()

    init() {
        if let cached = repo.getCachedSiteBlock() {
            state.data = cached
        } else {
            state.error = "No cached data. Go back and press New Scan again."
        }
    }

    // MARK: - Setters

    func setSelectedSiteIndex(_ i: Int) {
        state.selectedSiteIndex = i
        state.selectedBlockIndex = -1
    }
    func setSelectedBlockIndex(_ i: Int) { state.selectedBlockIndex = i }
    func setSelectedScanTypeIndex(_ i: Int) { state.selectedScanTypeIndex = i }
    func setRowWidth(_ v: String) { state.rowWidth = v }
    func setRowHeight(_ v: String) { state.rowHeight = v }
    func setBayLength(_ v: String) { state.bayLength = v }
    func setAddLens(_ v: Bool) {
        state.addLens = v
        if !v { state.multiplier = "" }
    }
    func setMultiplier(_ v: String) { state.multiplier = v }

    // MARK: - Submit

    func submit() {
        guard let data = state.data else {
            state.error = "Missing cached site data."
            return
        }
        let sites = data.sites
        let scanTypes = data.scan_type
        guard sites.indices.contains(state.selectedSiteIndex) else { return }
        let blocks = sites[state.selectedSiteIndex].blocks
        guard blocks.indices.contains(state.selectedBlockIndex),
              scanTypes.indices.contains(state.selectedScanTypeIndex) else { return }
        guard let rw = Double(state.rowWidth),
              let rh = Double(state.rowHeight),
              let bl = Double(state.bayLength) else { return }
        let multiplier = state.addLens ? (Double(state.multiplier) ?? 1.0) : 1.0

        let body = SiteBlockCreateRequest(
            blockId: blocks[state.selectedBlockIndex].block_id,
            scanTypeId: scanTypes[state.selectedScanTypeIndex].id,
            rowWidth: rw,
            rowHeight: rh,
            bayLength: bl,
            addedMultiplier: multiplier
        )

        state.isLoading = true
        state.error = nil
        do {
            let json = try String(data: JSONEncoder.tau.encode(body), encoding: .utf8) ?? ""
            ApiCache.shared.saveSubmitSiteBlock(json)
            ApiCache.shared.saveUseMainRearCameraForScan(state.addLens)
            state.isLoading = false
            state.navigateNext = true
        } catch {
            state.isLoading = false
            state.error = "Failed to save settings: \(error.localizedDescription)"
        }
    }

    func consumeNavigation() { state.navigateNext = false }
}
