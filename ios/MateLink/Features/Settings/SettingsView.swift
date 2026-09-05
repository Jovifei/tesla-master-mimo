import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var state: AppState
    @State private var connectionMessage: String?
    @State private var isTestingConnection = false
    @State private var testResult: String?

    var body: some View {
        List {
            // MARK: - Network
            Section(L10n.string("settings.network")) {
                if !state.instances.isEmpty {
                    ForEach(state.instances) { instance in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(instance.name).font(.headline)
                                Text(instance.serverURL)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                            Spacer()
                            if instance.id == state.activeInstanceID {
                                Label("Active", systemImage: "checkmark.circle.fill")
                                    .font(.caption)
                                    .foregroundStyle(MateColors.success)
                            } else {
                                Button("Switch") {
                                    Task {
                                        do {
                                            try await state.switchInstance(instance.id)
                                            connectionMessage = "Switched to \(instance.name)."
                                        } catch {
                                            connectionMessage = error.localizedDescription
                                        }
                                    }
                                }
                                .buttonStyle(.bordered)
                                .controlSize(.small)
                            }
                            NavigationLink {
                                AddInstanceView(instance: instance)
                            } label: {
                                Image(systemName: "pencil.circle")
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .onDelete { offsets in
                        offsets.map { state.instances[$0].id }.forEach(state.deleteInstance)
                    }
                }

                NavigationLink("Add Instance") {
                    AddInstanceView()
                }

                TextField(L10n.string("server_url"), text: $state.serverURL)
                    .textContentType(.URL)
                    .autocapitalization(.none)

                SecureField(L10n.string("api_token"), text: $state.apiToken)

                Button {
                    Task {
                        isTestingConnection = true
                        testResult = nil
                        defer { isTestingConnection = false }
                        do {
                            try await state.connect(url: state.serverURL, token: state.apiToken)
                            testResult = "✓ Connected successfully"
                        } catch {
                            testResult = "✗ \(error.localizedDescription)"
                        }
                    }
                } label: {
                    HStack {
                        if isTestingConnection { ProgressView().controlSize(.small) }
                        Text(L10n.string("test_connection"))
                    }
                }
                .disabled(state.serverURL.isEmpty || isTestingConnection)

                if let result = testResult {
                    Text(result)
                        .font(.caption)
                        .foregroundStyle(result.hasPrefix("✓") ? MateColors.success : MateColors.error)
                }
            }

            // MARK: - Data Status
            Section {
                NavigationLink(value: Route.dataReadiness(carId: state.currentCarId)) {
                    Label(L10n.string("data_readiness_title"), systemImage: "checkmark.seal")
                }
            }

            // MARK: - Display
            Section(L10n.string("settings.display")) {
                Toggle("Dark Mode", isOn: $state.isDarkMode)

                NavigationLink {
                    TariffConfigView()
                } label: {
                    Label("Tariff Config", systemImage: "clock.badge.checkmark")
                }
            }

            // MARK: - Instances
            Section(L10n.string("settings.instances")) {
                Text(L10n.string("settings.instances_desc"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text("Requires self-hosted TeslaMate + TeslaMateApi-compatible API.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            // MARK: - Development
            Section(L10n.string("settings.development")) {
                Toggle(L10n.string("mock_mode"), isOn: $state.isMockMode)
                Text("Mock mode uses built-in sample data — no server required.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            // MARK: - Map
            Section(L10n.string("settings.map")) {
                Text("AMap/Gaode Web Service Key is user-owned and must be applied for separately. Leave blank to use the built-in MapKit fallback.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            // MARK: - About
            Section {
                Text(L10n.string("version") + " 0.1.0-alpha")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle(L10n.string("settings.title"))
        .alert(L10n.string("connection"), isPresented: Binding(
            get: { connectionMessage != nil },
            set: { if !$0 { connectionMessage = nil } }
        )) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(connectionMessage ?? "")
        }
    }
}
