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
        let drives: [Drive] = await {
            if state.isMockMode {
                return await state.mock.getDrives(state.currentCarId)
            } else if let api = state.real {
                return (try? await api.fetch("/api/v1/cars/\(state.currentCarId)/drives")) ?? []
            }
            return []
        }().filter { $0.distanceKm > 1 }
        points = drives.map { d in
            let speed = max(0, Int((d.distanceKm / Double(max(d.durationMin, 1))) * 60.0))
            return EfficiencyPoint(speed: speed, efficiency: d.efficiency, temp: d.outsideTempAvg, date: String(d.startDate.prefix(10)))
        }

        let zoneDefs: [(String, Int, Int)] = [
            ("0-30", 0, 30), ("30-60", 30, 60), ("60-90", 60, 90),
            ("90-120", 90, 120), ("120+", 120, 300)
        ]
        zones = zoneDefs.map { label, minS, maxS in
            let pts = points.filter { $0.speed >= minS && $0.speed < maxS }
            let count = pts.count
            let avg = count > 0 ? pts.map(\.efficiency).reduce(0, +) / count : 0
            let best = pts.map(\.efficiency).min() ?? 0
            return SpeedZone(label: label, minSpeed: minS, maxSpeed: maxS, count: count, avgEff: avg, bestEff: best)
        }
    }
}
