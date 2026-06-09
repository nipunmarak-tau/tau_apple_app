// CreateSiteBlockView.swift
// Mirrors CreateSiteBlockScreen.kt + CreateSiteBlockContent.kt.

import SwiftUI

struct CreateSiteBlockView: View {

    @Environment(AppRouter.self) private var router
    @State private var viewModel = CreateSiteBlockViewModel()

    var body: some View {
        Group {
            if viewModel.state.data == nil {
                errorPlaceholder
            } else {
                form
            }
        }
        .onChange(of: viewModel.state.navigateNext) { _, newValue in
            if newValue {
                viewModel.consumeNavigation()
                router.navigate(to: .record)
            }
        }
    }

    // MARK: - Pieces

    private var errorPlaceholder: some View {
        VStack {
            Text(viewModel.state.error ?? "Unknown error")
                .foregroundStyle(.red)
                .padding()
        }
        .navigationTitle("Error")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var form: some View {
        let data = viewModel.state.data!
        let sites = data.sites
        let scanTypes = data.scan_type
        let blocks = sites.indices.contains(viewModel.state.selectedSiteIndex)
            ? sites[viewModel.state.selectedSiteIndex].blocks
            : []

        return ScrollView {
            VStack(alignment: .leading, spacing: 16) {

                Text("Select site, block and scan type")
                    .font(.subheadline.weight(.semibold))

                dropdown(label: "Site",
                         options: sites.map { $0.address ?? "Site \($0.site_id)" },
                         selection: viewModel.state.selectedSiteIndex,
                         onSelect: viewModel.setSelectedSiteIndex)

                dropdown(label: "Block",
                         options: blocks.map { $0.block_name ?? "Block \($0.block_id)" },
                         selection: viewModel.state.selectedBlockIndex,
                         onSelect: viewModel.setSelectedBlockIndex)

                dropdown(label: "Scan type",
                         options: scanTypes.map { $0.name ?? "Scan \($0.id)" },
                         selection: viewModel.state.selectedScanTypeIndex,
                         onSelect: viewModel.setSelectedScanTypeIndex)

                Text("Row & bay measurements")
                    .font(.subheadline.weight(.semibold))
                    .padding(.top, 8)

                numericField(title: "Row width (m)",
                             text: Binding(get: { viewModel.state.rowWidth }, set: viewModel.setRowWidth))
                numericField(title: "Row height (m)",
                             text: Binding(get: { viewModel.state.rowHeight }, set: viewModel.setRowHeight))
                numericField(title: "Bay length (m)",
                             text: Binding(get: { viewModel.state.bayLength }, set: viewModel.setBayLength))

                Toggle("Add lens multiplier",
                       isOn: Binding(get: { viewModel.state.addLens }, set: viewModel.setAddLens))

                if let err = viewModel.state.error, !err.isEmpty {
                    Text(err).foregroundStyle(.red)
                }

                Button(action: { viewModel.submit() }) {
                    HStack {
                        if viewModel.state.isLoading {
                            ProgressView().controlSize(.small).tint(.white)
                            Text("Saving…")
                        } else {
                            Text("Next").bold()
                        }
                    }
                    .frame(maxWidth: .infinity).frame(height: 56)
                }
                .buttonStyle(.borderedProminent)
                .tint(Color.brandGreenAlt)
                .clipShape(RoundedRectangle(cornerRadius: 28))
                .disabled(viewModel.state.isLoading)
                .padding(.top, 8)
            }
            .padding(20)
        }
        .navigationTitle("Add Field Information")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button {
                    UIApplication.shared.sendAction(
                        #selector(UIResponder.resignFirstResponder),
                        to: nil, from: nil, for: nil
                    )
                } label: {
                    Image(systemName: "keyboard.chevron.compact.down")
                }
            }
        }
    }

    // MARK: - Helpers

    private func dropdown(
        label: String,
        options: [String],
        selection: Int,
        onSelect: @escaping (Int) -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            Menu {
                ForEach(Array(options.enumerated()), id: \.offset) { idx, item in
                    Button(item) { onSelect(idx) }
                }
            } label: {
                HStack {
                    Text(options.indices.contains(selection) ? options[selection] : "Select…")
                        .foregroundStyle(.primary)
                    Spacer()
                    Image(systemName: "chevron.down").foregroundStyle(.secondary)
                }
                .padding(14)
                .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 10))
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .stroke(Color.secondary.opacity(0.3), lineWidth: 1)
                )
            }
        }
    }

    private func numericField(title: String, text: Binding<String>) -> some View {
        TextField(title, text: Binding(
            get: { text.wrappedValue },
            set: { new in
                // Allow only digits and one optional decimal point.
                if new.isEmpty || new.range(of: #"^\d*\.?\d*$"#, options: .regularExpression) != nil {
                    text.wrappedValue = new
                }
            }
        ))
        .keyboardType(.decimalPad)
        .padding(14)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 10))
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(Color.secondary.opacity(0.3), lineWidth: 1)
        )
    }
}
