import SwiftUI

struct DriveListView: View {
    @EnvironmentObject var state: AppState
    @Environment(\.carPalette) private var palette
    @State private var allDrives: [Drive] = []
    @State private var loading = true
    @State private var loadError: String?
    @State private var dateFilter: HistoryDateFilter = .allTime
    @State private var distanceFilter: DriveDistanceFilter = .all

    private var drives: [Drive] {
        allDrives.filter { drive in
            !DriveFilterRules.isShortDrive(drive) && distanceFilter.matches(drive.distanceKm)
        }
    }

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading…")
                    .frame(maxWidth: .infinity, minHeight: 300)
            } else if drives.isEmpty {
                EmptyStateView("No Drives Yet",
                               systemImage: MateIcons.drives,
                               message: "Go for a drive!")
                    .padding(40)
            } else {
                listContent
            }
        }
        .navigationTitle("Drives")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Picker("Date", selection: $dateFilter) {
                        ForEach(HistoryDateFilter.allCases) { filter in
                            Text(filter.label).tag(filter)
                        }
                    }
                    Picker("Distance", selection: $distanceFilter) {
                        ForEach(DriveDistanceFilter.allCases) { filter in
                            Text(filter.label).tag(filter)
                        }
                    }
                } label: {
                    Image(systemName: "line.3.horizontal.decrease.circle")
                }
            }
        }
        .onChange(of: dateFilter) { _ in
            Task { await load() }
        }
        .refreshable { await load() }
        .task { await load() }
    }

    // MARK: - List Content

    private var listContent: some View {
        List {
            // Summary card
            Section {
                driveSummary
            }

            // Grouped drives
            ForEach(groupedKeys(), id: \.self) { label in
                Section(label) {
                    ForEach(drivesForGroup(label)) { drive in
                        NavigationLink(value: Route.driveDetail(carId: drive.carId, driveId: drive.id)) {
                            DriveRow(drive: drive, palette: palette)
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    // MARK: - Summary Card

    private var driveSummary: some View {
        HStack(spacing: 20) {
            SummaryMetric(title: "Total Distance",
                          value: String(format: "%.0f", totalDistance),
                          unit: "km",
                          color: palette.accent)
            SummaryMetric(title: "Total Time",
                          value: "\(totalMinutes / 60)h \(totalMinutes % 60)m",
                          unit: "",
                          color: palette.onSurfaceVariant)
            SummaryMetric(title: "Avg Efficiency",
                          value: averageEfficiency.map { String(format: "%.0f", $0) } ?? "—",
                          unit: "Wh/km",
                          color: palette.acColor)
        }
        .padding(.vertical, 4)
    }

    private var totalDistance: Double {
        drives.reduce(0) { $0 + $1.distanceKm }
    }
    private var totalMinutes: Int {
        drives.reduce(0) { $0 + $1.durationMin }
    }
    private var averageEfficiency: Double? {
        let valid = drives.filter { $0.efficiency > 0 }
        guard !valid.isEmpty else { return nil }
        return valid.reduce(0) { $0 + $1.efficiency } / Double(valid.count)
    }

    // MARK: - Data Loading

    func load() async {
        let carId = state.currentCarId
        if drives.isEmpty { loading = true }

        if let api = state.real, dateFilter == .allTime, let cached = await api.getCachedDrives(carId: carId) {
            allDrives = cached
            loading = false
        }

        let bounds = dateFilter.isoBounds()
        if state.isMockMode {
            allDrives = await state.mock.getDrives(carId)
        } else if let api = state.real {
            do {
                let fresh: [Drive] = try await api.getAllDrives(
                    carId: carId,
                    startDate: bounds?.start,
                    endDate: bounds?.end
                )
                allDrives = fresh
                if dateFilter == .allTime {
                    await api.cacheDrives(fresh, carId: carId)
                }
            } catch {
                loadError = error.localizedDescription
            }
        }
        loading = false
    }

    // MARK: - Grouping

    func groupedKeys() -> [String] {
        let today = String(ISO8601DateFormatter().string(from: Date()).prefix(10))
        return Array(Set(drives.map { d in
            let k = String(d.startDate.prefix(10))
            return k == today ? "Today" : k
        })).sorted(by: >)
    }

    func drivesForGroup(_ label: String) -> [Drive] {
        let today = String(ISO8601DateFormatter().string(from: Date()).prefix(10))
        return drives.filter { d in
            let k = String(d.startDate.prefix(10))
            return (label == "Today" && k == today) || label == k
        }
    }
}

// MARK: - Drive Row

private struct DriveRow: View {
    let drive: Drive
    let palette: CarColorPalette

    var body: some View {
        HStack(spacing: 12) {
            // Route icon
            Image(systemName: MateIcons.drives)
                .font(.title3)
                .foregroundStyle(palette.accent)
                .frame(width: 32)

            VStack(alignment: .leading, spacing: 3) {
                Text("\(drive.startAddress) → \(drive.endAddress)")
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)

                HStack(spacing: 8) {
                    Label("\(drive.distanceKm, specifier: "%.1f") km", systemImage: "ruler")
                    Label("\(drive.durationMin) min", systemImage: "clock")
                    Label("\(drive.efficiency, specifier: "%.0f") Wh/km", systemImage: "bolt")
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Summary Metric

private struct SummaryMetric: View {
    let title: String
    let value: String
    let unit: String
    let color: Color

    var body: some View {
        VStack(spacing: 4) {
            Text(title)
                .font(.caption2)
                .foregroundStyle(.secondary)
            HStack(alignment: .lastTextBaseline, spacing: 2) {
                Text(value)
                    .font(MateFont.mono(.bold, size: 18))
                    .foregroundStyle(color)
                if !unit.isEmpty {
                    Text(unit)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
            }
        }
        .frame(maxWidth: .infinity)
    }
}
