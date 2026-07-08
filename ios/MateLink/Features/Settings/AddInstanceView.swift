import SwiftUI

struct AddInstanceView: View {
    @EnvironmentObject var state: AppState
    @State private var name: String = ""
    @State private var serverURL: String = ""
    @State private var apiToken: String = ""
    @State private var testing: Bool = false
    @State private var saving: Bool = false
    @State private var testResult: String?
    @State private var testSuccess: Bool = false
    @State private var saveError: String?
    @Environment(\.dismiss) var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Server") {
                    TextField("Instance Name", text: $name)
                    TextField("Server URL (e.g. http://192.168.1.100:3000)", text: $serverURL)
                        .textContentType(.URL)
                        .autocapitalization(.none)
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
            .navigationTitle("Add Instance")
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
                testResult = "Connected successfully"
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
            try await state.connect(url: serverURL, token: apiToken)
            saving = false
            dismiss()
        } catch {
            saveError = "Save failed: \(error.localizedDescription)"
            saving = false
        }
    }
}
