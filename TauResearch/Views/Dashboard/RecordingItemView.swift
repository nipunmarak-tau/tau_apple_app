// RecordingItemView.swift
// Mirrors RecordingItem.kt.

import SwiftUI

struct RecordingItemView: View {

    let item: RecordingEntity
    let isOnline: Bool
    let isUploading: Bool
    let isInQueue: Bool
    let progress: Float
    let stage: String
    let isSelected: Bool

    var onPlay: () -> Void
    var onUpload: () -> Void
    var onDelete: () -> Void
    var onToggleSelection: () -> Void

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "dd MMM yyyy, HH:mm"
        return f
    }()

    private var title: String {
        let stem = (item.gpxPath as NSString).lastPathComponent
        return (stem as NSString).deletingPathExtension
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top, spacing: 8) {
                Toggle("", isOn: Binding(
                    get: { isSelected },
                    set: { _ in onToggleSelection() }
                ))
                .labelsHidden()
                .toggleStyle(CheckboxToggleStyle())
                .disabled(isUploading || isInQueue)

                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 8) {
                        Image(systemName: "video")
                            .foregroundStyle(Color.brandGreen)
                        Text(title)
                            .font(.headline)
                            .lineLimit(1)
                    }
                    HStack(spacing: 16) {
                        InfoChipView(icon: "clock",
                                     text: Self.dateFormatter.string(from: item.createdAt))
                        InfoChipView(icon: "timer",
                                     text: formatDuration(item.duration))
                    }
                }
                Spacer()
                Button(action: onPlay) {
                    Image(systemName: "play.fill")
                        .padding(8)
                        .background(Color.brandGreen.opacity(0.15), in: Circle())
                        .foregroundStyle(Color.brandGreen)
                }
                .disabled(isUploading)
            }
            .padding(8)

            if isUploading || isInQueue {
                VStack(spacing: 4) {
                    ProgressView(value: progress)
                        .progressViewStyle(.linear)
                    HStack {
                        Text(stage).font(.caption)
                        Spacer()
                        Text(String(format: "%.1f%%", progress * 100))
                            .font(.caption.weight(.bold))
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
            }

            Divider().padding(.top, 4)

            HStack(spacing: 8) {
                Spacer()
                Button {
                    onUpload()
                } label: {
                    Label(isOnline ? "Upload" : "No Internet", systemImage: "arrow.up")
                }
                .disabled(!isOnline || isUploading || isInQueue)

                Button(role: .destructive, action: onDelete) {
                    Label("Delete", systemImage: "trash")
                }
                .disabled(isUploading || isInQueue)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
        }
        .background(.background, in: RoundedRectangle(cornerRadius: 16))
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(Color.black.opacity(0.06), lineWidth: 1)
        )
        .shadow(color: .black.opacity(0.06), radius: 4, x: 0, y: 2)
    }
}

private struct CheckboxToggleStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Image(systemName: configuration.isOn ? "checkmark.square.fill" : "square")
            .foregroundStyle(configuration.isOn ? Color.brandGreen : .secondary)
            .font(.title2)
            .onTapGesture { configuration.isOn.toggle() }
    }
}

struct InfoChipView: View {
    let icon: String
    let text: String
    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: icon).font(.caption)
            Text(text).font(.caption)
        }
        .foregroundStyle(.secondary)
    }
}
