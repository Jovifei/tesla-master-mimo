import SwiftUI

struct UpdatesView: View {
    @EnvironmentObject var state: AppState
    @State private var updates: [UpdateItem] = []
    @State private var loading = true

    var body: some View {
        NavigationStack {
            Group {
                if loading {
                    ProgressView("Loading...").padding()
                } else if updates.isEmpty {
                    ContentUnavailableView("No Updates", systemImage: "desktopcomputer", description: Text("No firmware updates recorded"))
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
        updates = state.isMockMode ? await state.mock.getUpdates(state.currentCarId) : []
        loading = false
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
