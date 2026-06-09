// LoginView.swift
// Mirrors LoginScreen.kt.

import SwiftUI

struct LoginView: View {

    @Environment(AppRouter.self) private var router
    @State private var viewModel = LoginViewModel()
    @State private var email: String = ""
    @State private var password: String = ""
    @State private var showPassword: Bool = false
    @State private var toastMessage: String?

    private var isOnline: Bool { NetworkConnectivityObserver.shared.isOnline }

    var body: some View {
        ZStack {
            // Background image + dim overlay (mirrors `splash_background` + 45% black).
            Image("SplashBackground")
                .resizable()
                .scaledToFill()
                .ignoresSafeArea()
                .overlay(Color.black.opacity(0.45).ignoresSafeArea())

            VStack(spacing: 0) {
                Spacer().frame(height: 80)
                Image("AppLogo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 120, height: 120)
                    .padding(.bottom, 16)

                Text(isOnline ? "Welcome" : "No Internet")
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(isOnline ? Color.white : Color.brandError)
                    .padding(.bottom, 24)

                TextField("Email", text: $email)
                    .textFieldStyle(.tauOutlined)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.emailAddress)
                    .disabled(!isOnline || viewModel.state.isLoading)
                    .padding(.bottom, 12)

                HStack {
                    if showPassword {
                        TextField("Password", text: $password)
                    } else {
                        SecureField("Password", text: $password)
                    }
                    Button {
                        showPassword.toggle()
                    } label: {
                        Image(systemName: showPassword ? "eye.slash" : "eye")
                            .foregroundStyle(.white.opacity(0.85))
                    }
                }
                .textFieldStyle(.tauOutlined)
                .padding(.bottom, 32)
                .disabled(!isOnline || viewModel.state.isLoading)

                Button(action: {
                    guard !viewModel.state.isLoading else { return }
                    viewModel.login(email: email, password: password)
                }) {
                    Text(viewModel.state.isLoading ? "Logging in…" : "Login")
                        .frame(maxWidth: .infinity)
                        .frame(height: 52)
                }
                .buttonStyle(.borderedProminent)
                .tint(.brandGreen)
                .disabled(viewModel.state.isLoading || !isOnline)
                .padding(.bottom, 40)
                Spacer()
            }
            .padding(.horizontal, 24)
        }
        .onChange(of: viewModel.state.success) { _, newValue in
            if newValue {
                toastMessage = "Login successful"
                router.reset(to: .dashboard)
            }
        }
        .onChange(of: viewModel.state.error) { _, newValue in
            if let msg = newValue { toastMessage = msg }
        }
        .overlay(alignment: .bottom) {
            if let toast = toastMessage {
                Text(toast)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.black.opacity(0.8), in: Capsule())
                    .foregroundStyle(.white)
                    .padding(.bottom, 60)
                    .task(id: toast) {
                        try? await Task.sleep(nanoseconds: 2_500_000_000)
                        toastMessage = nil
                    }
            }
        }
    }
}

// MARK: - Outlined text field style matching the brand.

extension TextFieldStyle where Self == OutlinedTauTextFieldStyle {
    static var tauOutlined: Self { OutlinedTauTextFieldStyle() }
}

struct OutlinedTauTextFieldStyle: TextFieldStyle {
    func _body(configuration: TextField<Self._Label>) -> some View {
        configuration
            .foregroundStyle(.white)
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 16))
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .stroke(Color.white.opacity(0.55), lineWidth: 1)
            )
    }
}
