import SwiftUI
import Charts

/// L1 "Current Charge" destination — live charging shell reached from Charges.
/// Uses the current `CarStatus` (chargerPower / chargeEnergyAdded / timeToFullCharge / SoC).
/// Real `/charges/current` endpoint is not yet exposed on iOS (see ApiClient TODO), so the
/// data is derived from `CarStatus` + mock fallback. Shell fidelity only.
struct CurrentChargeView: View {
    @EnvironmentObject var state: AppState
    @State private var status: CarStatus?
    @State private var currentCharge: Charge?
    @State private var loading = true
    @State private var loadError: String?
    @State private var now = Date()
    let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    /// Approximate usable battery capacity (kWh) used only to derive a notional start SoC
    /// from `chargeEnergyAdded` when the real start level is unavailable.
    private let approxCapacityKwh: Double = 75

    var body: some View {
        NavigationStack {
            Group {
                if loading {
                    ProgressView("Loading...").padding(40)
                } else if let loadError {
                    EmptyStateView(
                        "Current Charge Unavailable",
                        systemImage: "exclamationmark.triangle",
                        message: loadError
                    )
                } else if let s = status, (state.isMockMode ? (s.state == .charging || s.pluggedIn) : (currentCharge != nil)) {
                    content(for: s)
                } else {
                    EmptyStateView(
                        "Not Charging",
                        systemImage: "bolt.slash",
                        message: "Live charge data appears here when the vehicle is charging or plugged in."
                    )
                }
            }
            .navigationTitle("Current Charge")
            .navigationBarTitleDisplayMode(.inline)
            .task { await refresh() }
            .onReceive(timer) { _ in now = Date() }
        }
    }

    private func content(for s: CarStatus) -> some View {
        ScrollView {
            VStack(spacing: 16) {
                headerCard(s)
                statsGrid(s)
                if s.state == .charging { powerProfileCard(s) }
            }
            .padding(.horizontal)
            .padding(.vertical, 12)
        }
        .background(Color(.systemGroupedBackground))
    }

    // MARK: - Header

    private func headerCard(_ s: CarStatus) -> some View {
        let accent: Color = isDC(s) ? .orange : .green
        return VStack(spacing: 12) {
            HStack {
                Label("Charging in Progress", systemImage: "bolt.fill")
                    .font(.headline).foregroundColor(accent)
                Spacer()
                Text(isDC(s) ? "DC FAST" : "AC")
                    .font(.caption2).fontWeight(.semibold)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(accent.opacity(0.18)).foregroundColor(accent)
                    .clipShape(Capsule())
            }

            // SoC row: start → current → target
            HStack(alignment: .lastTextBaseline) {
                VStack(spacing: 2) {
                    Text("\(startLevel(s))%").font(.title3).fontWeight(.semibold)
                    Text("Start").font(.caption2).foregroundColor(.secondary)
                }
                Spacer()
                VStack(spacing: 2) {
                    Text("\(s.batteryLevel)%").font(.system(size: 40, weight: .heavy)).foregroundColor(accent)
                    Text("Now").font(.caption2).foregroundColor(accent)
                }
                Spacer()
                VStack(spacing: 2) {
                    Text("\(s.chargeLimitSoc)%").font(.title3).fontWeight(.semibold)
                    Text("Target").font(.caption2).foregroundColor(.secondary)
                }
            }

            ProgressView(value: Double(s.batteryLevel), total: 100)
                .tint(accent)

            Divider()

            HStack(spacing: 16) {
                Label("\(String(format: "%.1f", s.chargerPower)) kW", systemImage: "bolt.fill")
                    .font(.subheadline).fontWeight(.medium)
                Spacer()
                if s.timeToFullCharge > 0 {
                    Label(etaText(s), systemImage: "timer")
                        .font(.subheadline)
                }
            }
            .foregroundColor(.secondary)

            HStack(spacing: 16) {
                Label("\(String(format: "%.2f", s.chargeEnergyAdded)) kWh added", systemImage: "plus.bolt")
                    .font(.caption)
                Spacer()
                Label(elapsedText(s), systemImage: "clock")
                    .font(.caption)
            }
            .foregroundColor(.secondary)
        }
        .padding()
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    // MARK: - Stats grid

    private func statsGrid(_ s: CarStatus) -> some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
            StatCardView(title: "Voltage", value: "\(s.chargerVoltage) V")
            StatCardView(title: "Current", value: "\(s.chargerActualCurrent) A")
            StatCardView(title: "Charge Port", value: s.chargePortDoorOpen ? "Open" : "Closed")
            StatCardView(title: "Inside", value: "\(Int(s.insideTemp))°C")
        }
    }

    // MARK: - Power profile (derived)

    private func powerProfileCard(_ s: CarStatus) -> some View {
        let samples = derivedPowerSamples(s)
        return VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "chart.xyaxis.line").foregroundColor(.orange)
                Text("Power Profile").font(.headline)
            }
            Chart(samples) { p in
                LineMark(x: .value("Min", p.minute), y: .value("kW", p.power))
                    .foregroundStyle(.orange)
                    .interpolationMethod(.catmullRom)
                    .lineStyle(StrokeStyle(lineWidth: 2))
            }
            .frame(height: 180)

            Text(state.isMockMode ? "Demo curve derived from mock charger power." : "Session verified by /charges/current; curve derived from live status power.")
                .font(.caption2).foregroundColor(.secondary)
        }
        .padding()
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private struct PowerPoint: Identifiable { let id = UUID(); let minute: Int; let power: Double }

    /// Build a notional 30-min taper/constant curve anchored to the current `chargerPower`.
    private func derivedPowerSamples(_ s: CarStatus) -> [PowerPoint] {
        let peak = max(s.chargerPower, 1)
        return (0..<30).map { i in
            let fraction = Double(i) / 29.0
            let power: Double
            if isDC(s) {
                power = peak * (1.0 - 0.5 * fraction * fraction)
            } else {
                power = peak * (1.0 + sin(Double(i) * 0.4) * 0.03)
            }
            return PowerPoint(minute: i, power: max(0, power))
        }
    }

    // MARK: - Helpers

    private func isDC(_ s: CarStatus) -> Bool { s.chargerVoltage >= 200 && s.chargerPower >= 30 }

    /// Derived start SoC: current SoC minus the percent implied by energy added / capacity.
    private func startLevel(_ s: CarStatus) -> Int {
        let addedPct = Int((s.chargeEnergyAdded / approxCapacityKwh) * 100)
        return max(0, s.batteryLevel - addedPct)
    }

    private func etaText(_ s: CarStatus) -> String {
        let mins = Int(s.timeToFullCharge * 60)
        if mins <= 0 { return "—" }
        let end = now.addingTimeInterval(TimeInterval(mins * 60))
        let fmt = DateFormatter(); fmt.timeStyle = .short
        return "\(fmt.string(from: end)) · \(mins)m"
    }

    private func elapsedText(_ s: CarStatus) -> String {
        let full = ISO8601DateFormatter()
        full.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let basic = ISO8601DateFormatter()
        basic.formatOptions = [.withInternetDateTime]
        guard let start = full.date(from: s.since) ?? basic.date(from: s.since) else { return "—" }
        let secs = Int(now.timeIntervalSince(start))
        if secs < 60 { return "\(secs)s" }
        let h = secs / 3600, m = (secs % 3600) / 60, s2 = secs % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s2) : String(format: "%d:%02d", m, s2)
    }

    // MARK: - Load

    func refresh() async {
        loading = true
        loadError = nil
        currentCharge = nil
        status = nil
        if state.isMockMode {
            status = await state.mock.mockStatus(state.currentCarId)
        } else if let api = state.real {
            do {
                currentCharge = try await api.getCurrentCharge(state.currentCarId)
                status = try await api.fetch("/api/v1/cars/\(state.currentCarId)/status")
            } catch {
                status = nil
                loadError = "Unable to load the real current-charge endpoint: \(error.localizedDescription)"
            }
        } else {
            loadError = "No TeslaMate instance is configured."
        }
        loading = false
    }
}

#Preview {
    CurrentChargeView().environmentObject(AppState())
}
