import SwiftUI
import Charts

struct EfficiencyPoint: Identifiable {
    let id = UUID()
    let speed: Int
    let efficiency: Int
    let temp: Double
    let date: String
}

struct SpeedZone: Identifiable {
    let id = UUID()
    let label: String
    let minSpeed: Int
    let maxSpeed: Int
    var count: Int = 0
    var avgEff: Int = 0
    var bestEff: Int = 0
}

let tempLegend: [(label: String, color: Color)] = [
    ("<0°C", Color(hex: "3B82F6")),
    ("0-15°C", Color(hex: "10B981")),
    ("15-25°C", Color(hex: "F59E0B")),
    (">25°C", Color(hex: "EF4444"))
]

func tempColor(_ t: Double) -> Color {
    t < 0 ? Color(hex: "3B82F6") : t < 15 ? Color(hex: "10B981") : t < 25 ? Color(hex: "F59E0B") : Color(hex: "EF4444")
}

struct EfficiencyView: View {
    @EnvironmentObject var state: AppState
    @State private var points: [EfficiencyPoint] = []
    @State private var zones: [SpeedZone] = []
    @State private var selected: EfficiencyPoint?
    @State private var loadError: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    // Header
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Efficiency Curve").font(.title2).bold()
                        Text("Speed vs Efficiency — colored by outside temperature")
                            .font(.caption).foregroundColor(.secondary)
                    }.frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal)

                    if let loadError {
                        EmptyStateView("Efficiency Data Unavailable", systemImage: "exclamationmark.triangle", message: loadError)
                            .padding(.top, 24)
                    }

                    // Scatter Chart
                    VStack {
                        Chart(points) { pt in
                            PointMark(x: .value("Speed", pt.speed), y: .value("Efficiency", pt.efficiency))
                                .foregroundStyle(tempColor(pt.temp))
                                .opacity(0.6)
                                .symbolSize(24)
                        }
                        .chartXAxisLabel("km/h")
                        .chartYAxisLabel("Wh/km")
                        .frame(height: 300)
                    }
                    .padding()
                    .background(.regularMaterial)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .padding(.horizontal)

                    // Legend
                    HStack(spacing: 16) {
                        ForEach(tempLegend, id: \.label) { item in
                            HStack(spacing: 4) {
                                Circle().fill(item.color).frame(width: 10, height: 10)
                                Text(item.label).font(.caption2).foregroundColor(.secondary)
                            }
                        }
                    }

                    // Speed Zone Table
                    VStack(spacing: 0) {
                        HStack {
                            Text("Speed Zone").frame(maxWidth: .infinity, alignment: .leading)
                            Text("Drives").frame(width: 50)
                            Text("Avg Eff").frame(width: 70)
                            Text("Best").frame(width: 60)
                        }
                        .font(.system(size: 10, weight: .medium))
                        .foregroundColor(.secondary)
                        .padding(.horizontal).padding(.vertical, 8)

                        Divider()
                        ForEach(zones) { z in
                            HStack {
                                Text("\(z.label) km/h").font(.subheadline.weight(.medium))
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                Text("\(z.count)").font(.subheadline).foregroundColor(.secondary).frame(width: 50)
                                Text("\(z.avgEff) Wh/km").font(.subheadline).frame(width: 70)
                                Text("\(z.bestEff) Wh/km").font(.subheadline).foregroundColor(.secondary).frame(width: 60)
                            }
                            .padding(.horizontal).padding(.vertical, 10)
                            if z.id != zones.last?.id { Divider() }
                        }
                    }
                    .background(.regularMaterial)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .padding(.horizontal)

                    if let s = selected {
                        Text("Selected: \(s.speed) km/h \u{00B7} \(s.efficiency) Wh/km \u{00B7} \(s.date)")
                            .font(.caption).foregroundColor(.blue)
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
        let drives: [Drive]
        do {
            if state.isMockMode {
                drives = await state.mock.getDrives(state.currentCarId)
            } else if let api = state.real {
                drives = try await api.getAllDrives(carId: state.currentCarId)
            } else {
                throw URLError(.notConnectedToInternet)
            }
        } catch {
            points = []
            zones = []
            loadError = "Unable to load real drive data: \(error.localizedDescription)"
            return
        }
        let filteredDrives = drives.filter { $0.distanceKm > 1 }
        points = filteredDrives.map { d in
            let speed = max(0, Int(d.averageSpeedKmh.rounded()))
            return EfficiencyPoint(speed: speed, efficiency: Int(d.efficiency.rounded()), temp: d.outsideTempAvg, date: String(d.startDate.prefix(10)))
        }

        // Android: 20 km/h buckets from speedAvg
        zones = stride(from: 0, through: 120, by: 20).map { minS in
            let maxS = minS == 120 ? 300 : minS + 20
            let label = minS == 120 ? "120+" : "\(minS)-\(maxS)"
            let pts = points.filter { $0.speed >= minS && $0.speed < maxS }
            let count = pts.count
            let totalDistance = filteredDrives.filter {
                let s = Int($0.averageSpeedKmh.rounded())
                return s >= minS && s < maxS
            }
            let energy = totalDistance.reduce(0.0) { $0 + $1.consumptionKwh }
            let km = totalDistance.reduce(0.0) { $0 + $1.distanceKm }
            let avg = km > 0 ? Int((energy / km * 1000).rounded()) : 0
            let best = pts.map(\.efficiency).min() ?? 0
            return SpeedZone(label: label, minSpeed: minS, maxSpeed: maxS, count: count, avgEff: avg, bestEff: best)
        }
    }
}
