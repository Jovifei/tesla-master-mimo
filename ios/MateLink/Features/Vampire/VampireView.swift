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
                        ContentUnavailableView("No Drain Data",
                            systemImage: "bolt.slash",
                            description: Text("No parking periods with significant drain found."))
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
        let drives: [Drive] = await {
            if state.isMockMode {
                return await state.mock.getDrives(state.currentCarId)
            } else if let api = state.real {
                return (try? await api.fetch("/api/v1/cars/\(state.currentCarId)/drives")) ?? []
            }
            return []
        }().sorted { $0.startDate < $1.startDate }
        guard drives.count > 1 else { return }

        var calculated: [VampireDrain] = []
        var totalKwh: Double = 0

        for i in 1..<drives.count {
            let prev = drives[i - 1], cur = drives[i]

            let fmt = ISO8601DateFormatter()
            fmt.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            let prevEnd = fmt.date(from: prev.endDate) ?? Date()
            let curStart = fmt.date(from: cur.startDate) ?? Date()
            let gapHours = curStart.timeIntervalSince(prevEnd) / 3600.0

            guard gapHours > 1 && gapHours < 48 else { continue }

            let battLoss = prev.endBatteryLevel - cur.startBatteryLevel
            guard battLoss > 0 else { continue }

            let kWh = Double(battLoss) / 100.0 * batteryCapacity
            let km = Int(Double(battLoss) / 100.0 * idealRange)
            totalKwh += kWh

            let date = String(prev.endDate.prefix(10))
            calculated.append(VampireDrain(date: date, kWh: kWh, km: km, temp: cur.outsideTempAvg))
        }

        drains = calculated
        totalKWh = totalKwh
        totalKm = Int(totalKwh * idealRange / batteryCapacity)
    }
}
