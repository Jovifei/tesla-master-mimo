import SwiftUI

struct OnboardingView: View {
    @EnvironmentObject var state: AppState
    @State private var url = ""
    @State private var token = ""
    @State private var loading = false
    @State private var error: String?
    @State private var currentStep: String?
    @State private var iconScale: CGFloat = 0.5
    @State private var iconOpacity: Double = 0

    private let steps = ["Pinging API...", "Checking API readiness...", "Fetching cars..."]

    var body: some View {
        VStack(spacing: 24) {
            Spacer()

            // App icon with spring entrance
            Image(systemName: "car.fill")
                .font(.system(size: 64))
                .foregroundStyle(.tint)
                .scaleEffect(iconScale)
                .opacity(iconOpacity)
                .onAppear {
                    withAnimation(MateAnimation.momentumSpring) {
                        iconScale = 1.0
                        iconOpacity = 1.0
                    }
                }

            Text("MateLink")
                .font(MateFont.inter(.bold, size: 34))

            Text("Requires self-hosted TeslaMate + TeslaMateApi-compatible API")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)

            Spacer().frame(height: 12)

            // Connection form
            VStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    TextField("https://teslamate-api.example.com", text: $url)
                        .textFieldStyle(.roundedBorder)
                        .keyboardType(.URL)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                    Text("Enter the API root URL, not Grafana or TeslaMate Web UI.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                SecureField("API Token (optional)", text: $token)
                    .textFieldStyle(.roundedBorder)
            }
            .padding(.horizontal, 40)

            // Connect button
            Button(action: { testConnection() }) {
                HStack {
                    if loading { ProgressView().tint(.white) }
                    Text("Test Connection")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .padding(.horizontal, 40)
            .disabled(loading || url.isEmpty)
            .animation(.easeInOut(duration: 0.2), value: loading)

            // Step indicator with animation
            if let step = currentStep {
                HStack(spacing: 8) {
                    ProgressView()
                        .scaleEffect(0.8)
                    Text(step)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }

            // Error message
            if let error {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(MateColors.error)
                    .padding(.horizontal, 40)
                    .multilineTextAlignment(.center)
                    .transition(.opacity)
            }

            Spacer()

            // Skip button
            Button("Skip — Use Mock Mode") {
                withAnimation(MateAnimation.defaultSpring) {
                    state.isMockMode = true
                    state.onboardingDone = true
                }
            }
            .font(.caption)
            .foregroundStyle(.secondary)

            Spacer()
        }
        .animation(MateAnimation.defaultSpring, value: currentStep)
        .animation(MateAnimation.defaultSpring, value: error)
    }

    func testConnection() {
        loading = true
        error = nil
        currentStep = steps[0]

        Task {
            do {
                let api = TeslaMateAPI(baseURL: url, token: token.isEmpty ? nil : token)

                currentStep = steps[0]
                try await api.checkStatus("/api/ping")

                currentStep = steps[1]
                try await api.checkStatus("/api/readyz")

                currentStep = steps[2]
                let resp: CarApiResponse = try await api.fetch("/api/v1/cars")
                guard !resp.data.cars.isEmpty else {
                    throw ApiError.serverError(0, "No cars found on this server")
                }

                try await state.connect(url: url, token: token)
                currentStep = nil
                loading = false

                // Haptic success
                UINotificationFeedbackGenerator().notificationOccurred(.success)
            } catch let e as ApiError {
                error = e.localizedDescription
                currentStep = nil
                loading = false
                UINotificationFeedbackGenerator().notificationOccurred(.error)
            } catch let err {
                error = err.localizedDescription
                currentStep = nil
                loading = false
                UINotificationFeedbackGenerator().notificationOccurred(.error)
            }
        }
    }
}
