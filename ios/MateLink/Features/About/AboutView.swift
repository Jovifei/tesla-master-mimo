import SwiftUI

/// L1 "About" destination — Stitch/Android-aligned brand + tech + data-source shell.
/// Reached from MoreView. Keeps the existing minimalist card idiom (no data fetching).
struct AboutView: View {
    private var versionString: String {
        let info = Bundle.main.infoDictionary
        let short = (info?["CFBundleShortVersionString"] as? String) ?? "0.1.0"
        let build = (info?["CFBundleVersion"] as? String) ?? "1"
        return "v\(short) (\(build))"
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                BrandCard(versionString: versionString)

                InfoCard(
                    title: "Tech Stack",
                    icon: "cpu",
                    lines: [
                        "Android · Kotlin · Jetpack Compose · Hilt · Room",
                        "iOS · Swift · SwiftUI · Swift Charts",
                        "Web · TypeScript · React · Vite · Zustand"
                    ]
                )

                InfoCard(
                    title: "Data Source",
                    icon: "server.rack",
                    lines: [
                        "Requires self-hosted TeslaMate + TeslaMateApi-compatible API",
                        "API root URL only; not Grafana or TeslaMate Web UI",
                        "Do I need a server? Real data yes; Mock mode no.",
                        "Not affiliated with Tesla, Inc.",
                        "No telemetry collected by this app"
                    ]
                )

                InfoCard(
                    title: "Links",
                    icon: "link",
                    lines: [
                        "GitHub: TeslaMate community project",
                        "License: MIT"
                    ]
                )

                Text("MateLink is an open companion app for self-hosted TeslaMate users with a TeslaMateApi-compatible API. All data stays between your API endpoint and your device.")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .padding(.top, 8)
            }
            .padding(.horizontal)
            .padding(.vertical, 16)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle("About")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct BrandCard: View {
    let versionString: String

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: "car.fill")
                .font(.system(size: 56))
                .foregroundColor(.blue)
            Text("Tesla MateLink")
                .font(.title2)
                .fontWeight(.bold)
            Text("Your Tesla Data Companion")
                .font(.subheadline)
                .foregroundColor(.secondary)
            Text(versionString)
                .font(.caption)
                .foregroundColor(.secondary)
                .padding(.top, 4)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 24)
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

private struct InfoCard: View {
    let title: String
    let icon: String
    let lines: [String]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: icon)
                    .foregroundColor(.blue)
                Text(title)
                    .font(.headline)
            }
            ForEach(lines, id: \.self) { line in
                Text(line)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

#Preview {
    NavigationStack { AboutView() }
}
