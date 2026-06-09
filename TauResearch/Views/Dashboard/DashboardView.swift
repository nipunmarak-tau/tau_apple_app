// DashboardView.swift
// Mirrors DashboardScreen.kt — list of recordings, upload progress, FAB to start a scan.

import SwiftUI
import SwiftData
import AVKit

struct DashboardView: View {

    @Environment(AppRouter.self) private var router
    @Environment(\.modelContext) private var modelContext

    @Query(sort: \RecordingEntity.createdAt, order: .reverse)
    private var recordings: [RecordingEntity]

    @State private var viewModel: DashboardViewModel?
    @State private var videoToPlay: RecordingEntity?
    @State private var itemToDelete: RecordingEntity?
    @State private var showLogoutConfirm = false

    var body: some View {
        // Bind the @Observable VM (created on first appear so we can pass the ModelContext).
        Group {
            if let vm = viewModel {
                content(vm: vm)
            } else {
                ProgressView().task {
                    viewModel = DashboardViewModel(context: modelContext)
                }
            }
        }
    }

    @ViewBuilder
    private func content(vm: DashboardViewModel) -> some View {
        let state = vm.state
        ZStack {
            Color(.systemGroupedBackground).ignoresSafeArea()
            if state.isLoading, recordings.isEmpty {
                ProgressView()
            } else if recordings.isEmpty {
                Text("No recordings yet. Tap 'New Scan' to begin.")
                    .padding()
                    .multilineTextAlignment(.center)
            } else {
                List {
                    ForEach(recordings) { rec in
                        RecordingItemView(
                            item: rec,
                            isOnline: state.isOnline,
                            isUploading: state.uploadingRecordingId == rec.id,
                            isInQueue: state.uploadQueue.contains(rec.id),
                            progress: state.uploadingRecordingId == rec.id ? state.uploadProgress : 0,
                            stage: state.uploadingRecordingId == rec.id ? state.uploadStage : (state.uploadQueue.contains(rec.id) ? "In Queue" : ""),
                            isSelected: state.selectedIds.contains(rec.id),
                            onPlay: { videoToPlay = rec },
                            onUpload: { vm.uploadRecording(rec) },
                            onDelete: { itemToDelete = rec },
                            onToggleSelection: { vm.toggleSelection(rec.id) }
                        )
                        .listRowSeparator(.hidden)
                        .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
                        .listRowBackground(Color.clear)
                    }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle(state.selectedIds.isEmpty ? "Dashboard" : "\(state.selectedIds.count) Selected")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Color.brandGreen, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Image("AppLogo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 28, height: 28)
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                if !state.selectedIds.isEmpty {
                    Button {
                        vm.uploadSelected(in: recordings)
                    } label: {
                        Image(systemName: "square.and.arrow.up")
                    }
                }
                Button {
                    showLogoutConfirm = true
                } label: {
                    Image(systemName: "rectangle.portrait.and.arrow.right")
                }
            }
        }
        .overlay(alignment: .bottomTrailing) {
            VStack(alignment: .trailing, spacing: 16) {
                if !state.uploadQueue.isEmpty || state.uploadingRecordingId != nil {
                    let count = state.uploadQueue.count + (state.uploadingRecordingId != nil ? 1 : 0)
                    Text("\(count) in queue")
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(Color.brandGreen, in: Capsule())
                        .foregroundStyle(.white)
                }
                Button {
                    vm.startNewScan()
                } label: {
                    Label("New Scan", systemImage: "plus")
                        .font(.headline)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 14)
                        .background(.thinMaterial, in: Capsule())
                }
            }
            .padding(20)
        }
        .alert("Logout",
               isPresented: $showLogoutConfirm) {
            Button("Logout", role: .destructive) { vm.logout() }
            Button("Cancel", role: .cancel) { }
        } message: { Text("Are you sure you want to log out?") }
        .alert("Confirm Deletion",
               isPresented: Binding(
                get: { itemToDelete != nil },
                set: { if !$0 { itemToDelete = nil } })) {
            Button("Yes", role: .destructive) {
                if let r = itemToDelete { vm.deleteRecording(r) }
                itemToDelete = nil
            }
            Button("No", role: .cancel) { itemToDelete = nil }
        } message: { Text("Are you sure you want to delete this recording? This action cannot be undone.") }
        .sheet(item: $videoToPlay) { rec in
            VideoPlayerSheet(url: URL(fileURLWithPath: rec.videoPath))
        }
        .onChange(of: state.navigateToMetadata) { _, newValue in
            if newValue {
                vm.consumeNavigation()
                router.navigate(to: .createSiteBlock)
            }
        }
        .onChange(of: state.loggedOut) { _, newValue in
            if newValue {
                vm.consumeNavigation()
                router.reset(to: .login)
            }
        }
        .onChange(of: UploadStateBus.shared.state) { _, newValue in
            vm.syncFromBus(newValue)
        }
        .alert(state.error ?? "",
               isPresented: Binding(
                get: { state.error != nil },
                set: { if !$0 { vm.clearError() } })) {
            Button("OK", role: .cancel) { vm.clearError() }
        }
    }
}

// MARK: - Video player

private struct VideoPlayerSheet: View {
    let url: URL
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VideoPlayer(player: AVPlayer(url: url))
                .ignoresSafeArea()
            VStack {
                HStack {
                    Spacer()
                    Button { dismiss() } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.largeTitle)
                            .foregroundStyle(.white.opacity(0.85))
                            .padding()
                    }
                }
                Spacer()
            }
        }
    }
}
