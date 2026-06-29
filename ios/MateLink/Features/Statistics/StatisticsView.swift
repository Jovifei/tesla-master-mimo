import SwiftUI
import Charts

// MARK: - Aggregation Models

struct MonthSummary: Identifiable {
    let id = UUID()
    let year: Int
    let month: Int
    let label: String
    let totalKm: Double
    let totalKwh: Double
    let driveCount: Int
    let avgEfficiency: Int
}

struct DaySummary: Identifiable {
    let id = UUID()
    let date: String
    let label: String
    let totalKm: Double
    let driveCount: Int
}

// MARK: - Navigation Target

enum StatsNavTarget: Hashable {
    case month(year: Int, month: Int)
    case day(date: String)
}

// MARK: - Statistics Year View (Root)

struct StatisticsView: View {
    @EnvironmentObject var state: AppState
    @State private var drives: [Drive] = []
    @State private var loading = true

    private let calendar = Calendar.current

    private static let isoFormatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    var body: some View {
        NavigationStack {
            Group {
                if loading {
                    ProgressView("Loading statistics...")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if drives.isEmpty {
                    ContentUnavailableView(
                        "No Drives Yet",
                        systemImage: "chart.bar.fill",
                        description: Text("Your driving statistics will appear here.")
                    )
                } else {
                    ScrollView {
                        VStack(spacing: 16) {
                            summaryCards
                            monthlyChartSection
                            monthGrid
                        }
                        .padding()
                    }
                }
            }
            .navigationTitle("Statistics")
            .background(Color(.systemGroupedBackground))
            .task { await load() }
            .navigationDestination(for: StatsNavTarget.self) { target in
                switch target {
                case .month(let y, let m):
                    MonthDetailView(drives: drives, year: y, month: m)
                case .day(let d):
                    DayDetailView(drives: drives, date: d)
                }
            }
        }
    }

    // MARK: - Summary Cards

    private var summaryCards: some View {
        let months = (1...12).map { aggregateMonth(year: currentYear, month: $0) }
        let totalKm   = months.reduce(0) { $0 + $1.totalKm }
        let totalKwh  = months.reduce(0) { $0 + $1.totalKwh }
        let totalDrives = months.reduce(0) { $0 + $1.driveCount }

        return HStack(spacing: 12) {
            SummaryCardView(icon: "road.lanes",
                            value: "\(Int(totalKm)) km",
                            title: "Total Distance",
                            color: .blue)
            SummaryCardView(icon: "bolt.fill",
                            value: String(format: "%.1f kWh", totalKwh),
                            title: "Total Energy",
                            color: .green)
            SummaryCardView(icon: "car.fill",
                            value: "\(totalDrives)",
                            title: "Total Drives",
                            color: .orange)
        }
    }

    // MARK: - Monthly Bar Chart

    private var monthlyChartSection: some View {
        let months = (1...12).map { aggregateMonth(year: currentYear, month: $0) }

        return VStack(alignment: .leading, spacing: 12) {
            Text("Monthly Distance")
                .font(.subheadline.weight(.semibold))
                .foregroundColor(.secondary)

            Chart(months) { m in
                BarMark(
                    x: .value("Month", m.label),
                    y: .value("Distance", m.totalKm)
                )
                .foregroundStyle(Color.blue.gradient)
                .cornerRadius(4)
            }
            .frame(height: 200)
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 12)) { _ in
                    AxisValueLabel().font(.system(size: 9))
                }
            }
            .chartYAxis {
                AxisMarks { value in
                    AxisGridLine()
                    AxisValueLabel().font(.system(size: 9))
                }
            }
        }
        .padding()
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    // MARK: - Month Grid

    private var monthGrid: some View {
        LazyVGrid(
            columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 3),
            spacing: 12
        ) {
            ForEach(1...12, id: \.self) { month in
                let summary = aggregateMonth(year: currentYear, month: month)
                NavigationLink(value: StatsNavTarget.month(year: summary.year, month: summary.month)) {
                    MonthCardView(summary: summary)
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - Data Helpers

    private var currentYear: Int {
        calendar.component(.year, from: Date())
    }

    private func parseDate(_ iso: String) -> Date? {
        if let d = Self.isoFormatter.date(from: iso) { return d }
        let f2 = ISO8601DateFormatter()
        f2.formatOptions = [.withInternetDateTime]
        return f2.date(from: iso)
    }

    private func dateComponents(_ iso: String) -> (year: Int, month: Int)? {
        guard let d = parseDate(iso) else { return nil }
        return (
            calendar.component(.year, from: d),
            calendar.component(.month, from: d)
        )
    }

    private func aggregateMonth(year: Int, month: Int) -> MonthSummary {
        let monthDrives = drives.filter { d in
            guard let (y, m) = dateComponents(d.startDate) else { return false }
            return y == year && m == month
        }
        let totalKm   = monthDrives.reduce(0) { $0 + $1.distanceKm }
        let totalKwh  = monthDrives.reduce(0) { $0 + $1.consumptionKwh }
        let avgEff    = monthDrives.isEmpty
            ? 0
            : Int(monthDrives.reduce(0) { $0 + $1.efficiency } / monthDrives.count)
        let monthName = DateFormatter().shortMonthSymbols[month - 1]
        return MonthSummary(
            year: year,
            month: month,
            label: monthName,
            totalKm: totalKm,
            totalKwh: totalKwh,
            driveCount: monthDrives.count,
            avgEfficiency: avgEff
        )
    }

    private func load() async {
        loading = true
        if state.isMockMode {
            drives = await state.mock.getDrives(state.currentCarId)
        } else if let api = state.real {
            drives = (try? await api.fetch("/api/v1/cars/\(state.currentCarId)/drives")) ?? []
        }
        loading = false
    }
}

// MARK: - Summary Card

struct SummaryCardView: View {
    let icon: String
    let value: String
    let title: String
    let color: Color

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundColor(color)

            Text(value)
                .font(.title3.weight(.bold))
                .minimumScaleFactor(0.6)
                .lineLimit(1)

            Text(title)
                .font(.caption2)
                .foregroundColor(.secondary)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 16)
        .padding(.horizontal, 4)
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

// MARK: - Month Card

struct MonthCardView: View {
    let summary: MonthSummary

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(summary.label)
                .font(.caption.weight(.semibold))
                .foregroundColor(.secondary)

            Text("\(Int(summary.totalKm)) km")
                .font(.headline.weight(.bold))
                .foregroundColor(.primary)

            Text("\(summary.driveCount) drives \u{00B7} \(summary.avgEfficiency) Wh/km")
                .font(.caption2)
                .foregroundColor(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

// MARK: - Month Detail View (2nd level)

struct MonthDetailView: View {
    let drives: [Drive]
    let year: Int
    let month: Int

    @State private var daySummaries: [DaySummary] = []

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MMM d"
        return f
    }()

    private static let isoFormatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    var body: some View {
        List {
            monthHeaderSection

            Section("Days") {
                if daySummaries.isEmpty {
                    Text("No drives this month")
                        .foregroundColor(.secondary)
                        .font(.subheadline)
                }
                ForEach(daySummaries) { day in
                    NavigationLink(value: StatsNavTarget.day(date: day.date)) {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(day.label)
                                    .font(.subheadline.weight(.semibold))
                                Text("\(day.driveCount) drive\(day.driveCount == 1 ? "" : "s")")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                            Text("\(Int(day.totalKm)) km")
                                .font(.subheadline.weight(.bold))
                                .foregroundColor(.blue)
                        }
                        .padding(.vertical, 2)
                    }
                }
            }
        }
        .navigationTitle(monthLabel)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { buildDaySummaries() }
    }

    private var monthLabel: String {
        "\(DateFormatter().monthSymbols[month - 1]) \(year)"
    }

    private var monthHeaderSection: some View {
        let totalKm     = daySummaries.reduce(0) { $0 + $1.totalKm }
        let driveCount  = daySummaries.reduce(0) { $0 + $1.driveCount }
        let totalKwh    = drivesForThisMonth.reduce(0) { $0 + $1.consumptionKwh }
        let avgEff      = drivesForThisMonth.isEmpty
            ? 0
            : Int(drivesForThisMonth.reduce(0) { $0 + $1.efficiency } / drivesForThisMonth.count)

        return Section {
            HStack(spacing: 24) {
                statItem(value: "\(Int(totalKm))", unit: "km")
                Divider().frame(height: 32)
                statItem(value: String(format: "%.1f", totalKwh), unit: "kWh")
                Divider().frame(height: 32)
                statItem(value: "\(driveCount)", unit: "drives")
                Divider().frame(height: 32)
                statItem(value: "\(avgEff)", unit: "Wh/km")
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 4)
        }
    }

    private func statItem(value: String, unit: String) -> some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.headline.weight(.bold))
            Text(unit)
                .font(.caption2)
                .foregroundColor(.secondary)
        }
    }

    private var drivesForThisMonth: [Drive] {
        let prefix = "\(year)-\(String(format: "%02d", month))"
        return drives.filter { $0.startDate.hasPrefix(prefix) }
    }

    private func buildDaySummaries() {
        let monthDrives = drivesForThisMonth

        var dayMap: [String: [Drive]] = [:]
        for d in monthDrives {
            let dateKey = String(d.startDate.prefix(10))
            dayMap[dateKey, default: []].append(d)
        }

        daySummaries = dayMap.keys.sorted(by: >).map { dateKey in
            let dayDrives = dayMap[dateKey]!
            let totalKm   = dayDrives.reduce(0) { $0 + $1.distanceKm }

            var label = dateKey
            let dateStr = dateKey + "T12:00:00Z"
            if let d = Self.isoFormatter.date(from: dateStr) {
                label = Self.dateFormatter.string(from: d)
            }

            return DaySummary(
                date: dateKey,
                label: label,
                totalKm: totalKm,
                driveCount: dayDrives.count
            )
        }
    }
}

// MARK: - Day Detail View (3rd level)

struct DayDetailView: View {
    let drives: [Drive]
    let date: String

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "EEEE, MMM d, yyyy"
        return f
    }()

    private static let isoFormatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    private var dayDrives: [Drive] {
        drives.filter { $0.startDate.hasPrefix(date) }
    }

    var body: some View {
        let totalKm  = dayDrives.reduce(0) { $0 + $1.distanceKm }
        let totalKwh = dayDrives.reduce(0) { $0 + $1.consumptionKwh }

        return List {
            Section {
                HStack(spacing: 24) {
                    statItem(value: "\(Int(totalKm))", unit: "km")
                    Divider().frame(height: 32)
                    statItem(value: String(format: "%.1f", totalKwh), unit: "kWh")
                    Divider().frame(height: 32)
                    statItem(value: "\(dayDrives.count)", unit: "drives")
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 4)
            }

            Section("Drives") {
                ForEach(dayDrives) { drive in
                    NavigationLink(destination: DriveDetailView(drive: drive)) {
                        DriveRowView(drive: drive)
                    }
                }
            }
        }
        .navigationTitle(formattedDate)
        .navigationBarTitleDisplayMode(.inline)
    }

    private var formattedDate: String {
        let dateStr = date + "T12:00:00Z"
        if let d = Self.isoFormatter.date(from: dateStr) {
            return Self.dateFormatter.string(from: d)
        }
        return date
    }

    private func statItem(value: String, unit: String) -> some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.headline.weight(.bold))
            Text(unit)
                .font(.caption2)
                .foregroundColor(.secondary)
        }
    }
}

// MARK: - Drive Row View

struct DriveRowView: View {
    let drive: Drive

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "car.fill")
                .foregroundColor(.blue)
                .font(.title3)
                .frame(width: 28)

            VStack(alignment: .leading, spacing: 2) {
                Text(drive.startAddress)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                Text(drive.endAddress)
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 2) {
                Text("\(drive.distanceKm, specifier: "%.1f") km")
                    .font(.subheadline.weight(.bold))
                    .foregroundColor(.blue)
                Text("\(drive.durationMin) min")
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Preview

#Preview("Statistics") {
    let state = AppState()
    return StatisticsView()
        .environmentObject(state)
}

#Preview("Month Detail") {
    let state = AppState()
    let drives = state.mock.getDrives(1)
    return NavigationStack {
        MonthDetailView(drives: drives, year: 2026, month: 6)
    }
    .environmentObject(state)
}

#Preview("Day Detail") {
    let state = AppState()
    let drives = state.mock.getDrives(1)
    return NavigationStack {
        DayDetailView(drives: drives, date: String(drives.first?.startDate.prefix(10) ?? "2026-06-01"))
    }
    .environmentObject(state)
}
