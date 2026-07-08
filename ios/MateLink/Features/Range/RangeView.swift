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

struct RangeView: View {
    @EnvironmentObject var state: AppState
    @State private var trips: [RangeTrip] = []
    @State private var loadError: String?

    var avgDiff: Double {
        guard !trips.isEmpty else { return 0 }
        return trips.map { abs($0.diff) }.reduce(0, +) / Double(trips.count)
    }

    var avgTemp: Double {
        guard !trips.isEmpty else { return 0 }
        return trips.map(\.temp).reduce(0, +) / Double(trips.count)
    }

    var accuracy: Double {
        let validTrips = trips.filter { $0.estimated > 0 }
        guard !validTrips.isEmpty else { return 100 }
        let accuracies = validTrips.map { trip -> Double in
            let error = abs(Double(trip.actual) - Double(trip.estimated)) / Double(trip.estimated)
            return max(0, (1 - error) * 100)
        }
        return accuracies.reduce(0, +) / Double(accuracies.count)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    // Header
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Range Accuracy").font(.title2).bold()
                        Text("Rated range vs actual distance per trip")
                            .font(.caption).foregroundColor(.secondary)
                    }.frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal)

                    if let loadError {
                        EmptyStateView("Range Data Unavailable", systemImage: "exclamationmark.triangle", message: loadError)
                            .padding(.top, 24)
                    }

                    // Summary Cards
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 10) {
                        MiniStatCard(title: "Avg Diff", value: "\(avgDiff.formatted(.number.precision(.fractionLength(1)))) km")
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
                                LineMark(x: .value("Date", t.date), y: .value("Rated (km)", t.estimated))
                                    .foregroundStyle(.blue).lineStyle(StrokeStyle(lineWidth: 2))
                                LineMark(x: .value("Date", t.date), y: .value("Actual (km)", t.actual))
                                    .foregroundStyle(.green).lineStyle(StrokeStyle(lineWidth: 2))
                            }
                            .chartForegroundStyleScale(["Est km": .blue, "Actual km": .green])
                            .frame(height: 280)
                            .padding(.top, 4)
                        }
                        .padding()
                        .background(.regularMaterial)
                        .clipShape(RoundedRectangle(cornerRadius: 16))
                        .padding(.horizontal)
                    } else {
                        EmptyStateView("No Trip Data",
                            systemImage: "chart.xyaxis.line",
                            message: "Complete at least one drive to see range comparison.")
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
        do {
            let rawDrives: [Drive]
            if state.isMockMode {
                rawDrives = await state.mock.getDrives(state.currentCarId)
            } else if let api = state.real {
                rawDrives = try await api.fetch("/api/v1/cars/\(state.currentCarId)/drives")
            } else {
                throw URLError(.notConnectedToInternet)
            }
            let drives = rawDrives.filter { $0.distanceKm > 5 }.suffix(30)
            trips = drives.map { d in
                let estimated = Int(d.startIdealRangeKm - d.endIdealRangeKm)  // rated range consumed
                let actual = Int(d.distanceKm)                                  // actual distance driven
                return RangeTrip(
                    date: String(d.startDate.prefix(10)),
                    estimated: estimated,
                    actual: actual,
                    diff: d.outsideTempAvg,
                    temp: d.outsideTempAvg
                )
            }
        } catch {
            trips = []
            loadError = "Unable to load real drive data: \(error.localizedDescription)"
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
