import SwiftUI
import Charts

struct BatteryHealthView: View {
    @EnvironmentObject var state: AppState; @State private var data: BatteryHealth?; @State private var loading = true; @State private var loadError: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                if loading { ProgressView("Loading...").padding(40) }
                else if let loadError {
                    EmptyStateView("Battery Health Unavailable", systemImage: "exclamationmark.triangle", message: loadError)
                        .padding(40)
                }
                else if let d = data {
                    VStack(spacing: 20) {
                        // Health gauge
                        VStack {
                            ZStack {
                                Circle().stroke(Color.gray.opacity(0.2), lineWidth: 10).frame(width: 120, height: 120)
                                Circle().trim(from: 0, to: (100-(d.capacityDegradationPercent ?? 0))/100).stroke(Color.blue, style: StrokeStyle(lineWidth: 10, lineCap: .round)).rotationEffect(.degrees(-90)).frame(width: 120, height: 120)
                                VStack { Text("\(Int(100-(d.capacityDegradationPercent ?? 0)))%").font(.title).bold(); Text("Health").font(.caption2).foregroundColor(.secondary) }
                            }
                            Text(healthLabel(d.capacityDegradationPercent ?? 0)).font(.headline).foregroundColor(healthColor(d.capacityDegradationPercent ?? 0))
                            Text("\(d.mileageKm.formatted()) km driven").font(.caption).foregroundColor(.secondary)
                        }.padding().background(.regularMaterial).clipShape(RoundedRectangle(cornerRadius: 16))

                        // Comparison
                        HStack {
                            VStack(alignment: .leading) { Text("Original").font(.caption).foregroundColor(.secondary); Text("\(String(format:"%.1f", d.originalCapacityKwh ?? 0)) kWh").font(.title3).bold() }
                            Spacer()
                            VStack(alignment: .trailing) { Text("Current").font(.caption).foregroundColor(.secondary); Text("\(String(format:"%.1f", d.currentCapacityKwh ?? 0)) kWh").font(.title3).bold() + Text(" -\(String(format:"%.1f", d.capacityDegradationPercent ?? 0))%").font(.caption).foregroundColor(.red) }
                        }.padding().background(.regularMaterial).clipShape(RoundedRectangle(cornerRadius: 16))

                        // Trend chart
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Degradation Trend").font(.subheadline).foregroundColor(.secondary)
                            Chart(d.history ?? [BatteryHealthPoint(date: "", capacityKwh: 0)], id: \.date) { p in
                                LineMark(x: .value("Date", p.date), y: .value("kWh", p.capacityKwh)).foregroundStyle(Color.blue)
                                PointMark(x: .value("Date", p.date), y: .value("kWh", p.capacityKwh)).foregroundStyle(Color.blue)
                            }.frame(height: 200)
                        }.padding().background(.regularMaterial).clipShape(RoundedRectangle(cornerRadius: 16))
                    }.padding()
                }
            }.navigationTitle("Battery Health").task { await load() }
        }
    }

    func load() async {
        loading = true
        loadError = nil
        data = nil
        if state.isMockMode {
            data = await state.mock.getBatteryHealth(state.currentCarId)
        } else if let api = state.real {
            do {
                data = try await api.fetch("/api/v1/cars/\(state.currentCarId)/battery-health")
            } catch {
                loadError = "Unable to load real battery health: \(error.localizedDescription)"
            }
        } else {
            loadError = "No TeslaMate instance is configured."
        }
        loading = false
    }
    func healthLabel(_ pct: Double) -> String { pct < 5 ? "Excellent" : pct < 10 ? "Good" : pct < 15 ? "Fair" : "Poor" }
    func healthColor(_ pct: Double) -> Color { pct < 5 ? .green : pct < 10 ? .blue : pct < 15 ? .orange : .red }
}
