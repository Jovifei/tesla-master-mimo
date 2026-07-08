import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var state: AppState
    @State private var connectionMessage: String?
    @State private var isTestingConnection = false

    var body: some View {
        List {
            Section(L10n.string("connection")) {
                NavigationLink("Add Instance") {
                    AddInstanceView()
                }
                TextField(L10n.string("server_url"), text: $state.serverURL)
                SecureField(L10n.string("api_token"), text: $state.apiToken)
                Button {
                    Task {
                        isTestingConnection = true
                        defer { isTestingConnection = false }
                        do {
                            try await state.connect(url: state.serverURL, token: state.apiToken)
                            connectionMessage = "Connection successful."
                        } catch {
                            connectionMessage = error.localizedDescription
                        }
                    }
                } label: {
                    HStack {
                        if isTestingConnection {
                            ProgressView()
                        }
                        Text(L10n.string("test_connection"))
                    }
                }
                .disabled(state.serverURL.isEmpty || isTestingConnection)
            }
            Section(L10n.string("preferences")) {
                NavigationLink {
                    TariffConfigView()
                } label: {
                    Label("Tariff Config", systemImage: "clock.badge.checkmark")
                }
                Toggle("Dark Mode", isOn: $state.isDarkMode)
            }
            Section("Development") {
                Toggle(L10n.string("mock_mode"), isOn: $state.isMockMode)
            }
            Section {
                Text(L10n.string("version") + " 0.1.0-alpha")
                    .font(.caption)
                    .foregroundColor(.secondary)
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
