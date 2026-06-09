// UploadSection.swift
// Mirrors upload/ui/UploadSection.kt — embedded "Upload video" panel.

import SwiftUI

struct UploadSection: View {
    @Bindable var viewModel: VideoUploadViewModel
    let videoPath: String
    let gpxPath: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button("Upload Video") {
                viewModel.startUpload(videoPath: videoPath, gpxPath: gpxPath)
            }
            .disabled(viewModel.state.isUploading)
            .buttonStyle(.borderedProminent)
            .tint(Color.brandGreen)

            if viewModel.state.isUploading {
                ProgressView(value: viewModel.state.progress)
                Button("Cancel Upload") { viewModel.cancelUpload() }
                    .buttonStyle(.bordered)
            }
            if let err = viewModel.state.error {
                Text(err).foregroundStyle(.red)
            }
            if viewModel.state.success {
                Label("Upload successful", systemImage: "checkmark.circle.fill")
                    .foregroundStyle(Color.brandGreen)
            }
        }
    }
}
