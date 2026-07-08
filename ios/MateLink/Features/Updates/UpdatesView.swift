import SwiftUI

struct UpdatesView: View {
    @EnvironmentObject var state: AppState
    @State private var updates: [UpdateItem] = []
    @State private var loading = true
    @State private var endpointUnavailable = false

    var body: some View {
        NavigationStack {
            Group {
                if loading {
                    ProgressView("Loading...").padding()
                } else if endpointUnavailable {
                    UpdatesEmptyStateView(
                        title: "Updates Endpoint Unavailable",
                        systemImage: "desktopcomputer.trianglebadge.exclamationmark",
                        message: "Firmware history is not available from the current server connection."
                    )
                } else if updates.isEmpty {
                    UpdatesEmptyStateView(
                        title: "No Updates",
                        systemImage: "desktopcomputer",
                        message: "No firmware updates recorded"
                    )
                } else {
                    List {
                        Section("Version History") {
                            ForEach(updates) { item in
                                UpdatesRow(item: item, isLongest: item.id == longestRun?.id)
                            }
                        }
                    }.listStyle(.plain)
                }
            }
            .navigationTitle("Firmware Updates")
            .refreshable { await load() }
            .task { await load() }
        }
    }

    private var longestRun: UpdateItem? {
        updates.max { a, b in duration(a) < duration(b) }
    }

    private func duration(_ item: UpdateItem) -> TimeInterval {
        item.endDate.isoDate.timeIntervalSince(item.startDate.isoDate)
    }

    func load() async {
        loading = true
        endpointUnavailable = false
        if state.isMockMode {
            updates = await state.mock.getUpdates(state.currentCarId)
        } else if let api = state.real {
            do {
                let remote: [UpdateItem] = try await api.fetch("/api/v1/cars/\(state.currentCarId)/updates")
                updates = remote
            } catch {
                updates = []
                endpointUnavailable = true
            }
        } else {
            updates = []
            endpointUnavailable = true
        }
        loading = false
    }
}

private struct UpdatesEmptyStateView: View {
    let title: String
    let systemImage: String
    let message: String

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 36))
                .foregroundColor(.secondary)
            Text(title)
                .font(.headline)
            Text(message)
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(24)
    }
}

private struct UpdatesRow: View {
    let item: UpdateItem; let isLongest: Bool

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(item.version).font(.subheadline).bold()
                    if isLongest {
                        Text("Longest Run").font(.caption2).fontWeight(.semibold)
                            .foregroundColor(.white).padding(.horizontal, 6).padding(.vertical, 2)
                            .background(Color.orange).clipShape(Capsule())
                    }
                }
                Text("Installed \(item.startDate.prefix(10))").font(.caption).foregroundColor(.secondary)
                Text("Duration: \(durationMinutes(item)) min").font(.caption2).foregroundColor(.secondary)
            }
            Spacer()
        }.padding(.vertical, 4)
    }

    private func durationMinutes(_ item: UpdateItem) -> Int {
        let d = item.endDate.isoDate.timeIntervalSince(item.startDate.isoDate)
        return Int(d / 60)
    }
}

private extension String {
    var isoDate: Date {
        ISO8601DateFormatter().date(from: self) ?? Date()
    }
}
