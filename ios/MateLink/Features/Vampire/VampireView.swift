import SwiftUI
import Charts

struct VampireDrain: Identifiable {
    let id = UUID()
    let date: String
    let kWh: Double
    let km: Int
    let temp: Double
}

struct VampireView: View {
    @EnvironmentObject var state: AppState
    @State private var drains: [VampireDrain] = []
    @State private var totalKWh: Double = 0
    @State private var totalKm: Int = 0
    @State private var loadError: String?

    private let batteryCapacity: Double = 75
    private let idealRange: Double = 520

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    // Header
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Vampire Drain").font(.title2).bold()
                        Text("Estimated battery loss during parking periods")
                            .font(.caption).foregroundColor(.secondary)
                    }.frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal)

                    if let loadError {
                        EmptyStateView("Drain Data Unavailable", systemImage: "exclamationmark.triangle", message: loadError)
                            .padding(.top, 24)
                    }

                    // Summary Cards
                    HStack(spacing: 12) {
                        StatCard(title: "Total Drain", value: "\(totalKWh.formatted(.number.precision(.fractionLength(1)))) kWh", subtitle: "", color: .red)
                        StatCard(title: "Range Loss", value: "\(totalKm) km", subtitle: "", color: .orange)
                        StatCard(title: "Events", value: "\(drains.count)", subtitle: "", color: .secondary)
                    }.padding(.horizontal)

                    // Trend Chart
                    if !drains.isEmpty {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Daily Drain Trend")
                                .font(.subheadline.weight(.medium)).foregroundColor(.secondary)
                            Chart(drains.suffix(30)) { d in
                                LineMark(
                                    x: .value("Date", d.date),
                                    y: .value("kWh", d.kWh)
                                )
                                .foregroundStyle(.red)
                                .lineStyle(StrokeStyle(lineWidth: 2))
                            }
                            .chartXAxis { AxisMarks(values: .stride(by: 7)) }
                            .frame(height: 250)
                        }
                        .padding()
                        .background(.regularMaterial)
                        .clipShape(RoundedRectangle(cornerRadius: 16))
                        .padding(.horizontal)
                    }

                    if drains.isEmpty {
                        EmptyStateView("No Drain Data",
                            systemImage: "bolt.slash",
                            message: "No parking periods with significant drain found.")
                            .padding(.top, 40)
                    }
                }
                .padding(.vertical)
            }
            .navigationBarTitleDisplayMode(.inline)
            .task { await loadData() }
        }
    }

    func loadData() async {
        loadError = nil
        if state.isMockMode {
            await loadFromDriveGaps()
            return
        }
        guard let api = state.real else {
            loadError = "No TeslaMate instance is configured."
            return
        }
        do {
            let windows = try await api.getStandbyWindows(carId: state.currentCarId)
            let cutoff = Calendar.current.date(byAdding: .day, value: -29, to: Date()) ?? Date()
            let qualified = windows.filter { window in
                guard window.isQualified else { return false }
                guard let start = HistoryDateFilter.parseISO(window.startDate) else { return false }
                return start >= cutoff
            }
            drains = qualified.map { window in
                let kWh: Double
                if window.hasPowerCoverage, let energy = window.energyKwh {
                    kWh = energy
                } else {
                    kWh = 0
                }
                let km = Int((kWh / 75.0) * 520.0)
                return VampireDrain(
                    date: String(window.startDate.prefix(10)),
                    kWh: kWh,
                    km: km,
                    temp: 0
                )
            }
            totalKWh = drains.reduce(0) { $0 + $1.kWh }
            totalKm = drains.reduce(0) { $0 + $1.km }
        } catch {
            await loadFromDriveGaps()
            if drains.isEmpty {
                loadError = "Standby API unavailable: \(error.localizedDescription)"
            }
        }
    }

    /// Fallback matching Android qualify rules when adapter standby is missing.
    private func loadFromDriveGaps() async {
        let drives: [Drive]
        if state.isMockMode {
            drives = await state.mock.getDrives(state.currentCarId)
        } else if let api = state.real {
            drives = (try? await api.getAllDrives(carId: state.currentCarId)) ?? []
        } else {
            drives = []
        }
        let sortedDrives = drives.sorted { $0.startDate < $1.startDate }
        var calculated: [VampireDrain] = []
        var totalKwh: Double = 0
        guard sortedDrives.count > 1 else {
            drains = []
            totalKWh = 0
            totalKm = 0
            return
        }
        for i in 1..<sortedDrives.count {
            let prev = sortedDrives[i - 1], cur = sortedDrives[i]
            guard let prevEnd = HistoryDateFilter.parseISO(prev.endDate),
                  let curStart = HistoryDateFilter.parseISO(cur.startDate) else { continue }
            let gapHours = curStart.timeIntervalSince(prevEnd) / 3600.0
            guard gapHours >= 2 else { continue }
            let battLoss = prev.endBatteryLevel - cur.startBatteryLevel
            guard battLoss > 0 else { continue }
            let kWh = Double(battLoss) / 100.0 * batteryCapacity
            let km = Int(Double(battLoss) / 100.0 * idealRange)
            totalKwh += kWh
            calculated.append(VampireDrain(date: String(prev.endDate.prefix(10)), kWh: kWh, km: km, temp: cur.outsideTempAvg))
        }
        drains = calculated
        totalKWh = totalKwh
        totalKm = Int(totalKwh * idealRange / batteryCapacity)
    }
}
