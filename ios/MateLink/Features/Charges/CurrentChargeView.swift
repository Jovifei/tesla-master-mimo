import SwiftUI
import Charts

/// Live charging view — mirrors Android `CurrentChargeViewModel` polling + DC warnings.
struct CurrentChargeView: View {
    @EnvironmentObject var state: AppState
    @State private var status: CarStatus?
    @State private var currentCharge: Charge?
    @State private var loading = true
    @State private var loadError: String?
    @State private var now = Date()
    @State private var wasDcSession = false

    private let clockTimer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    private let approxCapacityKwh: Double = 75

    /// Mirrors Android: car reports charging but TeslaMate hasn't created the charge row yet.
    private var isChargeStarting: Bool {
        guard let s = status else { return false }
        return s.isCharging && currentCharge == nil
    }

    private var isDcFinishedPluggedIn: Bool {
        guard let s = status else { return false }
        return s.isChargeCompletePluggedIn && wasDcSession
    }

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading…").padding(40)
            } else if let loadError {
                EmptyStateView(
                    "Current Charge Unavailable",
                    systemImage: "exclamationmark.triangle",
                    message: loadError
                )
            } else if let s = status, shouldShowLiveCharge(s) {
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
        .task {
            await refresh(showLoading: true)
            while !Task.isCancelled {
                let fastPoll = isChargeStarting
                try? await Task.sleep(nanoseconds: fastPoll ? 4_000_000_000 : 30_000_000_000)
                await refresh(showLoading: false)
            }
        }
        .onReceive(clockTimer) { _ in now = Date() }
    }

    private func shouldShowLiveCharge(_ s: CarStatus) -> Bool {
        isChargeStarting || isDcFinishedPluggedIn || s.isCharging || s.pluggedIn || currentCharge != nil
    }

    private func content(for s: CarStatus) -> some View {
        ScrollView {
            VStack(spacing: 16) {
                if isDcFinishedPluggedIn {
                    dcUnplugWarning
                }
                headerCard(s)
                statsGrid(s)
                if s.isCharging || isChargeStarting {
                    powerProfileCard(s)
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 12)
        }
        .background(Color(.systemGroupedBackground))
    }

    private var dcUnplugWarning: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(MateColors.warning)
            Text("DC charge complete — please unplug to avoid idle fees")
                .font(.caption)
                .foregroundStyle(MateColors.warning)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(MateColors.warning.opacity(0.12))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - Header

    private func headerCard(_ s: CarStatus) -> some View {
        let accent: Color = s.isDcCharging ? palette.dcColor : palette.acColor
        return VStack(spacing: 12) {
            HStack {
                Label(isChargeStarting ? "Charge Starting…" : "Charging in Progress",
                      systemImage: "bolt.fill")
                    .font(.headline).foregroundStyle(accent)
                Spacer()
                Text(s.isDcCharging ? "DC FAST" : "AC")
                    .font(.caption2).fontWeight(.semibold)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(accent.opacity(0.18)).foregroundStyle(accent)
                    .clipShape(Capsule())
            }

            HStack(alignment: .lastTextBaseline) {
                VStack(spacing: 2) {
                    Text("\(startLevel(s))%").font(.title3).fontWeight(.semibold)
                    Text("Start").font(.caption2).foregroundStyle(.secondary)
                }
                Spacer()
                VStack(spacing: 2) {
                    Text("\(s.batteryLevel)%")
                        .font(MateFont.mono(.bold, size: 40))
                        .foregroundStyle(accent)
                    Text("Now").font(.caption2).foregroundStyle(accent)
                }
                Spacer()
                VStack(spacing: 2) {
                    Text("\(s.chargeLimitSoc)%").font(.title3).fontWeight(.semibold)
                    Text("Target").font(.caption2).foregroundStyle(.secondary)
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
            .foregroundStyle(.secondary)

            HStack(spacing: 16) {
                Label("\(String(format: "%.2f", s.chargeEnergyAdded)) kWh added", systemImage: "plus.bolt")
                    .font(.caption)
                Spacer()
                Label(elapsedText(s), systemImage: "clock")
                    .font(.caption)
            }
            .foregroundStyle(.secondary)
        }
        .padding()
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    @Environment(\.carPalette) private var palette

    // MARK: - Stats grid

    private func statsGrid(_ s: CarStatus) -> some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
            StatCardView(title: "Voltage", value: "\(s.chargerVoltage) V")
            StatCardView(title: "Current", value: "\(s.chargerActualCurrent) A")
            StatCardView(title: "Charge Port", value: s.chargePortDoorOpen ? "Open" : "Closed")
            StatCardView(title: "Inside", value: "\(Int(s.insideTemp))°C")
        }
    }

    // MARK: - Power profile

    private func powerProfileCard(_ s: CarStatus) -> some View {
        let samples = powerSamples(s)
        return VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "chart.xyaxis.line").foregroundStyle(palette.dcColor)
                Text("Power Profile").font(.headline)
            }
            Chart(samples) { p in
                LineMark(x: .value("Min", p.minute), y: .value("kW", p.power))
                    .foregroundStyle(palette.dcColor)
                    .interpolationMethod(.catmullRom)
                    .lineStyle(StrokeStyle(lineWidth: 2))
            }
            .frame(height: 180)

            Text(samplesLabel(s))
                .font(.caption2).foregroundStyle(.secondary)
        }
        .padding()
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private struct PowerPoint: Identifiable { let id = UUID(); let minute: Int; let power: Double }

    private func powerSamples(_ s: CarStatus) -> [PowerPoint] {
        if let points = currentCharge?.chargePoints, !points.isEmpty {
            let chronological = Array(points.reversed())
            let start = parseISO(s.since)
            return chronological.enumerated().map { index, point in
                let minute: Int
                if let start, let dateStr = point.date, let d = parseISO(dateStr) {
                    minute = Int(d.timeIntervalSince(start) / 60.0)
                } else {
                    minute = index
                }
                return PowerPoint(minute: max(0, minute), power: Double(point.chargerPower ?? 0))
            }
        }
        let peak = max(s.chargerPower, 1)
        return (0..<30).map { i in
            let fraction = Double(i) / 29.0
            let power = s.isDcCharging
                ? peak * (1.0 - 0.5 * fraction * fraction)
                : peak * (1.0 + sin(Double(i) * 0.4) * 0.03)
            return PowerPoint(minute: i, power: max(0, power))
        }
    }

    private func samplesLabel(_ s: CarStatus) -> String {
        if currentCharge?.chargePoints?.isEmpty == false {
            return "Live curve from charge session data."
        }
        if state.isMockMode {
            return "Demo curve derived from mock charger power."
        }
        return "Curve derived from live status power."
    }

    // MARK: - Helpers

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
        guard let start = parseISO(s.since) else { return "—" }
        let secs = Int(now.timeIntervalSince(start))
        if secs < 60 { return "\(secs)s" }
        let h = secs / 3600, m = (secs % 3600) / 60, s2 = secs % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s2) : String(format: "%d:%02d", m, s2)
    }

    private func parseISO(_ value: String) -> Date? {
        let full = ISO8601DateFormatter()
        full.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = full.date(from: value) { return d }
        let basic = ISO8601DateFormatter()
        basic.formatOptions = [.withInternetDateTime]
        return basic.date(from: value)
    }

    private func dcSessionKey(_ carId: Int) -> String { "matelink.dc_session.\(carId)" }

    // MARK: - Load

    func refresh(showLoading: Bool) async {
        if showLoading { loading = true }
        loadError = nil
        let carId = state.currentCarId

        if state.isMockMode {
            status = await state.mock.mockStatus(carId)
            currentCharge = nil
        } else if let api = state.real {
            do {
                status = try await api.fetch("/api/v1/cars/\(carId)/status")
                currentCharge = try await api.getCurrentCharge(carId)
            } catch {
                status = nil
                currentCharge = nil
                loadError = "Unable to load current charge: \(error.localizedDescription)"
            }
        } else {
            loadError = "No TeslaMate instance is configured."
        }

        if let s = status {
            if s.isDcCharging {
                wasDcSession = true
                UserDefaults.standard.set(true, forKey: dcSessionKey(carId))
            } else if !s.pluggedIn {
                wasDcSession = false
                UserDefaults.standard.set(false, forKey: dcSessionKey(carId))
            } else {
                wasDcSession = UserDefaults.standard.bool(forKey: dcSessionKey(carId))
            }
        }

        if showLoading { loading = false }
    }
}

private struct StatCardView: View {
    let title: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.headline)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

#Preview {
    CurrentChargeView().environmentObject(AppState())
}
