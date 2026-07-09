import SwiftUI

struct AddInstanceView: View {
    @EnvironmentObject var state: AppState
    let instance: TeslaMateInstance?
    @State private var name: String = ""
    @State private var serverURL: String = ""
    @State private var apiToken: String = ""
    @State private var testing: Bool = false
    @State private var saving: Bool = false
    @State private var testResult: String?
    @State private var testSuccess: Bool = false
    @State private var saveError: String?
    @Environment(\.dismiss) var dismiss

    init(instance: TeslaMateInstance? = nil) {
        self.instance = instance
        _name = State(initialValue: instance?.name ?? "")
        _serverURL = State(initialValue: instance?.serverURL ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("API") {
                    TextField("Instance Name", text: $name)
                    TextField("API Root URL (e.g. https://teslamate-api.example.com)", text: $serverURL)
                        .textContentType(.URL)
                        .autocapitalization(.none)
                    Text("Requires self-hosted TeslaMate + TeslaMateApi-compatible API. Enter the API root URL, not Grafana or TeslaMate Web UI. Do not add /api or /api/v1.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    SecureField("API Token (optional)", text: $apiToken)
                }

                Section {
                    Button(action: testConnection) {
                        HStack {
                            if testing {
                                ProgressView().padding(.trailing, 4)
                            }
                            Text(testing ? "Testing..." : "Test Connection")
                        }
                    }
                    .disabled(serverURL.isEmpty || testing)

                    if let result = testResult {
                        Label(result, systemImage: testSuccess ? "checkmark.circle.fill" : "xmark.circle.fill")
                            .foregroundColor(testSuccess ? .green : .red)
                    }
                }

                Section {
                    Button {
                        Task {
                            await saveAndConnect()
                        }
                    } label: {
                        HStack {
                            if saving {
                                ProgressView().padding(.trailing, 4)
                            }
                            Text(saving ? "Saving..." : "Save & Connect")
                        }
                    }
                    .disabled(serverURL.isEmpty || !testSuccess || saving)

                    if let saveError {
                        Label(saveError, systemImage: "xmark.circle.fill")
                            .foregroundColor(.red)
                    }
                }
            }
            .navigationTitle(instance == nil ? "Add Instance" : "Edit Instance")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    func testConnection() {
        testing = true
        testResult = nil
        saveError = nil
        Task {
            do {
                let api = TeslaMateAPI(baseURL: serverURL, token: apiToken.isEmpty ? nil : apiToken)
                try await api.checkStatus("/api/ping")
                do {
                    try await api.checkStatus("/api/readyz")
                } catch {
                    testResult = "Readiness check unavailable; continuing with vehicle check."
                }
                let resp: CarApiResponse = try await api.fetch("/api/v1/cars")
                let count = resp.data.cars.count
                guard count > 0 else {
                    testResult = "No vehicles returned by TeslaMate."
                    testSuccess = false
                    testing = false
                    return
                }
                testResult = "Connected successfully. Found \(count) vehicle\(count == 1 ? "" : "s")."
                testSuccess = true
            } catch {
                testResult = "Connection failed: \(error.localizedDescription)"
                testSuccess = false
            }
            testing = false
        }
    }

    func saveAndConnect() async {
        saving = true
        saveError = nil
        do {
            try await state.saveInstance(id: instance?.id, name: name, url: serverURL, token: apiToken)
            saving = false
            dismiss()
        } catch {
            saveError = "Save failed: \(error.localizedDescription)"
            saving = false
        }
    }
}
