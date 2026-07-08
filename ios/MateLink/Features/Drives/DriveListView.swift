import SwiftUI

struct DriveListView: View {
    @EnvironmentObject var state: AppState
    @State private var drives: [Drive] = []; @State private var loading = true

    var body: some View {
        NavigationStack {
            Group {
                if loading { ProgressView("Loading...").padding() }
                else if drives.isEmpty { EmptyStateView("No Drives Yet", systemImage: "road.lanes", message: "Go for a drive!").padding() }
                else {
                    List {
                        ForEach(groupedKeys(), id: \.self) { label in
                            Section(label) {
                                ForEach(drivesForGroup(label)) { d in
                                    NavigationLink(destination: DriveDetailView(drive: d)) {
                                        HStack {
                                            Image(systemName: "car.fill").foregroundColor(.blue)
                                            VStack(alignment: .leading, spacing: 2) {
                                                Text("\(d.startAddress) → \(d.endAddress)").font(.subheadline).bold()
                                                Text("\(d.distanceKm, specifier: "%.1f") km · \(d.durationMin) min · \(d.efficiency) Wh/km").font(.caption).foregroundColor(.secondary)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }.listStyle(.plain)
                }
            }.navigationTitle("Drive History").refreshable { await load() }.task { await load() }
        }
    }

    func load() async {
        let carId = state.currentCarId
        if drives.isEmpty {
            loading = true
        }
        // Cache-first: show stale data immediately (F‑015)
        if let api = state.real, let cached = await api.getCachedDrives(carId: carId) {
            drives = cached
            loading = false
        }
        // Fetch fresh
        if state.isMockMode {
            drives = await state.mock.getDrives(carId)
        } else if let api = state.real {
            do {
                let fresh: [Drive] = try await api.fetch("/api/v1/cars/\(carId)/drives")
                drives = fresh; await api.cacheDrives(fresh, carId: carId)
            } catch { /* stale cache stays visible */ }
        }
        loading = false
    }
    func groupedKeys() -> [String] {
        let today = ISO8601DateFormatter().string(from: Date()).prefix(10)
        return Array(Set(drives.map { String($0.startDate.prefix(10)) == today ? "Today" : String($0.startDate.prefix(10)) })).sorted(by: >)
    }
    func drivesForGroup(_ label: String) -> [Drive] {
        let today = String(ISO8601DateFormatter().string(from: Date()).prefix(10))
        return drives.filter { d in let k = String(d.startDate.prefix(10)); return (label == "Today" && k == today) || label == k }
    }
}
