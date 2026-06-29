import SwiftUI
import Charts

struct RangeTrip: Identifiable {
    let id = UUID()
    let date: String
    let estimated: Int
    let actual: Int
    let diff: Double
    let temp: Double
}

struct RangePageView: View {
    @EnvironmentObject var state: AppState
    @State private var trips: [RangeTrip] = []

    var avgDiff: Double {
        guard !trips.isEmpty else { return 0 }
        return trips.map { abs($0.diff) }.reduce(0, +) / Double(trips.count)
    }

    var avgTemp: Double {
        guard !trips.isEmpty else { return 0 }
        return trips.map(\.temp).reduce(0, +) / Double(trips.count)
    }

    var accuracy: Double {
        let totalDiff = trips.map { abs($0.diff) }.reduce(0, +)
        let totalRange = trips.map { abs(Double($0.estimated - $0.actual)) }.reduce(0, +)
        guard totalRange > 0 else { return 100 }
        return (1 - totalDiff / totalRange) * 100
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    // Header
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Projected Range").font(.title2).bold()
                        Text("Estimated vs actual battery consumption per trip")
                            .font(.caption).foregroundColor(.secondary)
                    }.frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal)

                    // Summary Cards
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 10) {
                        MiniStatCard(title: "Avg Diff", value: "\(avgDiff.formatted(.number.precision(.fractionLength(1))))%")
                        MiniStatCard(title: "Trips", value: "\(trips.count)")
                        MiniStatCard(title: "Avg Temp", value: "\(Int(avgTemp))\u{00B0}C")
                        MiniStatCard(title: "Accuracy", value: "\(Int(accuracy))%")
                    }.padding(.horizontal)

                    // Line Chart
                    if !trips.isEmpty {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Est vs Actual per Trip")
                                .font(.subheadline.weight(.medium)).foregroundColor(.secondary)
                            Chart(trips) { t in
                                LineMark(x: .value("Date", t.date), y: .value("Battery %", t.estimated))
                                    .foregroundStyle(.blue).lineStyle(StrokeStyle(lineWidth: 2))
                                LineMark(x: .value("Date", t.date), y: .value("Battery %", t.actual))
                                    .foregroundStyle(.green).lineStyle(StrokeStyle(lineWidth: 2))
                            }
                            .chartForegroundStyleScale(["Est %": .blue, "Actual %": .green])
                            .frame(height: 280)
                            .padding(.top, 4)
                        }
                        .padding()
                        .background(.regularMaterial)
                        .clipShape(RoundedRectangle(cornerRadius: 16))
                        .padding(.horizontal)
                    } else {
                        ContentUnavailableView("No Trip Data",
                            systemImage: "chart.xyaxis.line",
                            description: Text("Complete at least one drive to see range comparison."))
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
        }().filter { $0.distanceKm > 5 }.suffix(30)
        trips = drives.map { d in
            let diff = Double(d.startBatteryLevel - d.endBatteryLevel)
            return RangeTrip(
                date: String(d.startDate.prefix(10)),
                estimated: d.startBatteryLevel,
                actual: d.endBatteryLevel,
                diff: diff,
                temp: d.outsideTempAvg
            )
        }
    }
}

struct MiniStatCard: View {
    let title: String; let value: String
    var body: some View {
        VStack(spacing: 4) {
            Text(title).font(.system(size: 10)).foregroundColor(.secondary)
            Text(value).font(.subheadline.weight(.bold))
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}
