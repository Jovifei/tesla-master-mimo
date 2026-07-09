import SwiftUI

struct OnboardingView: View {
    @EnvironmentObject var state: AppState
    @State private var url = ""; @State private var token = ""; @State private var loading = false; @State private var error: String?
    @State private var currentStep: String?

    private let steps = ["Pinging API...", "Checking API readiness...", "Fetching cars..."]

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            Image(systemName: "car.fill").font(.system(size: 60)).foregroundColor(.blue)
            Text("Tesla_MateLink").font(.largeTitle).bold()
            Text("Requires self-hosted TeslaMate + TeslaMateApi-compatible API").font(.subheadline).foregroundColor(.secondary)
            Spacer().frame(height: 20)

            VStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    TextField("https://teslamate-api.example.com", text: $url).textFieldStyle(.roundedBorder).keyboardType(.URL).autocapitalization(.none).disableAutocorrection(true)
                    Text("Enter the API root URL, not Grafana or TeslaMate Web UI. Do not add /api/v1.").font(.caption).foregroundColor(.secondary)
                }
                SecureField("API Token (optional)", text: $token).textFieldStyle(.roundedBorder)
                Text("Do I need a server? Real data yes; Mock mode no.")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }.padding(.horizontal, 40)

            Button(action: { testConnection() }) {
                HStack { if loading { ProgressView().tint(.white) }; Text("Test Connection") }.frame(maxWidth: .infinity)
            }.buttonStyle(.borderedProminent).padding(.horizontal, 40).disabled(loading || url.isEmpty)

            // Step indicator
            if let step = currentStep {
                HStack(spacing: 8) {
                    ProgressView().scaleEffect(0.8)
                    Text(step).font(.caption).foregroundColor(.secondary)
                }
            }

            if let error {
                Text(error).font(.caption).foregroundColor(.red).padding(.horizontal, 40).multilineTextAlignment(.center)
            }

            Spacer()
            Button("Skip — Use Mock Mode") {
                state.isMockMode = true
                state.onboardingDone = true
            }.font(.caption).foregroundColor(.secondary)
            Spacer()
        }
    }

    func testConnection() {
        loading = true; error = nil; currentStep = steps[0]
        Task {
            do {
                let api = TeslaMateAPI(baseURL: url, token: token.isEmpty ? nil : token)

                // Step 1: ping — server reachable?
                currentStep = steps[0]
                try await api.checkStatus("/api/ping")

                // Step 2: readyz — backend services healthy?
                currentStep = steps[1]
                try await api.checkStatus("/api/readyz")

                // Step 3: cars — token valid & data accessible
                currentStep = steps[2]
                let resp: CarApiResponse = try await api.fetch("/api/v1/cars")
                guard !resp.data.cars.isEmpty else {
                    throw ApiError.serverError(0, "No cars found on this server")
                }

                // Save and navigate
                try await state.connect(url: url, token: token)
                currentStep = nil; loading = false
            } catch let e as ApiError {
                error = e.localizedDescription; currentStep = nil; loading = false
            } catch let err {
                error = err.localizedDescription; currentStep = nil; loading = false
            }
        }
    }
}
