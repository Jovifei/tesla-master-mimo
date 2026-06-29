import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var state: AppState

    var body: some View {
        List {
            Section("Connection") {
                TextField("Server URL", text: $state.serverURL)
                SecureField("API Token", text: $state.apiToken)
                Button("Test Connection") { Task { do { try await state.connect(url: state.serverURL, token: state.apiToken) } catch {} } }.disabled(state.serverURL.isEmpty)
            }
            Section("Preferences") {
                Toggle("Dark Mode", isOn: $state.isDarkMode)
            }
            Section("Development") {
                Toggle("Mock Mode", isOn: $state.isMockMode)
            }
            Section { Text("Version 0.1.0-alpha").font(.caption).foregroundColor(.secondary) }
        }.navigationTitle("Settings")
    }
}

struct AboutView: View {
    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "car.fill").font(.system(size: 60)).foregroundColor(.blue)
            Text("Tesla_MateLink").font(.title).bold()
            Text("Your Tesla Data Companion").font(.subheadline).foregroundColor(.secondary)
            Text("Not affiliated with Tesla, Inc.\nRequires self-hosted TeslaMate + TeslaMateApi.").font(.caption).multilineTextAlignment(.center).foregroundColor(.secondary)
            Text("Version 0.1.0-alpha · MIT License").font(.caption2).foregroundColor(.secondary)
        }.padding(40)
    }
}
