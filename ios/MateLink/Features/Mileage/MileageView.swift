import SwiftUI
import Charts

/// L1 "Mileage" destination — Stitch-aligned mileage drill-down shell.
/// Reached from MoreView. Uses Drive history + BatteryHealth odometer; no new backend.
/// Scope: summary cards + monthly breakdown + recent highlights (shell fidelity only).
struct MileageView: View {
    @EnvironmentObject var state: AppState
    @State private var drives: [Drive] = []
    @State private var battery: BatteryHealth?
    @State private var loading = true
    @State private var loadError: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                if loading {
                    ProgressView("Loading...").padding(40)
                } else if let loadError {
                    EmptyStateView("Mileage Unavailable", systemImage: "exclamationmark.triangle", message: loadError)
                        .padding(40)
                } else {
                    VStack(spacing: 16) {
                        summaryRow
                        monthlyCard
                        recentCard
                    }
                    .padding(.vertical)
                }
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Mileage")
            .navigationBarTitleDisplayMode(.large)
            .task { await load() }
        }
    }

    // MARK: - Summary

    private var summaryRow: some View {
        HStack(spacing: 12) {
            StatCard(title: "Total", value: totalMileageText, subtitle: "Lifetime km", color: .blue)
            StatCard(title: "Drives", value: "\(drives.count)", subtitle: "Sessions", color: .secondary)
            StatCard(title: "Energy", value: String(format: "%.0f kWh", totalEnergy), subtitle: "Consumed", color: .orange)
        }
        .padding(.horizontal)
    }

    private var totalMileageText: String {
        let km = battery?.odometer ?? drives.reduce(0) { $0 + $1.distanceKm }
        return Int(km).formatted()
    }

    private var totalEnergy: Double {
        drives.reduce(0) { $0 + $1.consumptionKwh }
    }

    private var avgEfficiency: Double {
        guard !drives.isEmpty else { return 0 }
        return drives.map(\.efficiency).reduce(0, +) / Double(drives.count)
    }

    // MARK: - Monthly breakdown

    private var monthlyItems: [MonthlyMileageItem] {
        var map: [String: Double] = [:]
        for d in drives {
            let key = String(d.startDate.prefix(7))
            map[key, default: 0] += d.distanceKm
        }
        return map.sorted { $0.key < $1.key }.map {
            MonthlyMileageItem(month: $0.key, distanceKm: $0.value)
        }
    }

    private var monthlyCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "chart.bar.fill").foregroundColor(.blue)
                Text("Monthly Breakdown").font(.headline)
            }
            if monthlyItems.isEmpty {
                Text("No drive data yet").font(.caption).foregroundColor(.secondary)
            } else {
                Chart(monthlyItems) { m in
                    BarMark(
                        x: .value("Month", m.label),
                        y: .value("km", m.distanceKm)
                    )
                    .foregroundStyle(Color.blue.gradient)
                }
                .frame(height: 220)

                ForEach(monthlyItems.suffix(6)) { m in
                    HStack {
                        Text(m.label).font(.subheadline)
                        Spacer()
                        Text("\(m.distanceKm, specifier: "%.1f") km")
                            .font(.subheadline).fontWeight(.medium)
                    }
                    .padding(.vertical, 4)
                    Divider()
                }
            }
        }
        .padding()
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .padding(.horizontal)
    }

    // MARK: - Recent highlights

    private var recentDrives: [Drive] {
        drives.sorted { $0.startDate > $1.startDate }.prefix(5).map { $0 }
    }

    private var recentCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: "clock.arrow.circlepath").foregroundColor(.blue)
                Text("Recent Highlights").font(.headline)
            }
            if recentDrives.isEmpty {
                Text("No recent trips").font(.caption).foregroundColor(.secondary).padding(.vertical, 4)
            } else {
                ForEach(recentDrives) { d in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(d.endAddress.isEmpty ? "Drive" : d.endAddress)
                                .font(.subheadline).fontWeight(.medium).lineLimit(1)
                            Spacer()
                            Text("\(d.distanceKm, specifier: "%.1f") km")
                                .font(.caption).foregroundColor(.secondary)
                        }
                        Text("\(d.startAddress) → \(d.endAddress) · \(d.durationMin) min · \(d.efficiency) Wh/km")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                            .lineLimit(1)
                    }
                    .padding(.vertical, 4)
                    Divider()
                }
                HStack {
                    Text("Avg efficiency").font(.caption).foregroundColor(.secondary)
                    Spacer()
                    Text("\(Int(avgEfficiency)) Wh/km").font(.caption).fontWeight(.medium)
                }
                .padding(.top, 4)
            }
        }
        .padding()
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .padding(.horizontal)
    }

    // MARK: - Load

    func load() async {
        loading = true
        loadError = nil
        let carId = state.currentCarId
        if state.isMockMode {
            drives = await state.mock.getDrives(carId)
            battery = await state.mock.getBatteryHealth(carId)
        } else if let api = state.real {
            do {
                drives = try await api.fetch("/api/v1/cars/\(carId)/drives")
                battery = try await api.fetch("/api/v1/cars/\(carId)/battery-health")
            } catch {
                drives = []
                battery = nil
                loadError = "Unable to load real mileage data: \(error.localizedDescription)"
            }
        } else {
            drives = []
            battery = nil
            loadError = "No TeslaMate instance is configured."
        }
        loading = false
    }
}

private struct MonthlyMileageItem: Identifiable {
    let id = UUID()
    let month: String      // "YYYY-MM"
    var label: String { String(month.suffix(2)) }  // "MM"
    let distanceKm: Double
}

#Preview {
    MileageView().environmentObject(AppState())
}
