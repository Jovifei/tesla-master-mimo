import SwiftUI

struct CountriesVisitedView: View {
    @EnvironmentObject var state: AppState
    @State private var rows: [CountryVisit] = []
    @State private var loading = true
    @State private var loadError: String?

    var body: some View {
        Group {
            if loading {
                ProgressView().frame(maxWidth: .infinity, minHeight: 300)
            } else if let loadError {
                EmptyStateView("Countries Unavailable", systemImage: "exclamationmark.triangle", message: loadError)
            } else if rows.isEmpty {
                EmptyStateView("No Countries Yet", systemImage: MateIcons.countries, message: "Drive destinations will appear after trips with addresses.")
            } else {
                List(rows) { row in
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(row.name).font(.headline)
                            Text("\(row.driveCount) drives · \(String(format: "%.0f", row.distanceKm)) km")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                    }
                }
                .listStyle(.insetGrouped)
            }
        }
        .navigationTitle("Countries Visited")
        .task { await load() }
    }

    private func load() async {
        loading = true
        do {
            let drives = try await state.loadAllDrives()
            var map: [String: (count: Int, km: Double)] = [:]
            for drive in drives {
                let name = Self.country(from: drive.endAddress)
                guard !name.isEmpty else { continue }
                var entry = map[name] ?? (0, 0)
                entry.count += 1
                entry.km += drive.distanceKm
                map[name] = entry
            }
            rows = map.map { CountryVisit(name: $0.key, driveCount: $0.value.count, distanceKm: $0.value.km) }
                .sorted { $0.driveCount > $1.driveCount }
        } catch {
            loadError = error.localizedDescription
        }
        loading = false
    }

    /// Last comma-separated token — Android uses geocoded country; API lists only have addresses.
    static func country(from address: String) -> String {
        let parts = address.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
        return parts.last ?? ""
    }
}

private struct CountryVisit: Identifiable {
    var id: String { name }
    let name: String
    let driveCount: Int
    let distanceKm: Double
}
