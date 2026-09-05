import SwiftUI

// MARK: - Dashboard View (Apple-native rewrite)

struct DashboardView: View {
    @EnvironmentObject var state: AppState
    @Environment(\.carPalette) private var palette
    @State private var status: CarStatus?
    @State private var showCarSwitcher = false
    @State private var isRefreshing = false
    @State private var snapshotSource: String?
    @State private var loadError: String?
    @State private var dataReadiness: DataReadiness?
    @State private var showReadinessIntro = false

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                if let s = status {
                    dashboardContent(s)
                } else if let car = state.currentCar, loadError != nil {
                    PartialDashboardView(car: car, error: loadError!, onRefresh: { Task { await refresh() } })
                } else if let loadError {
                    EmptyStateView("Dashboard Unavailable",
                                   systemImage: "exclamationmark.triangle",
                                   message: loadError)
                        .padding(40)
                } else {
                    ProgressView("Loading...")
                        .frame(maxWidth: .infinity, minHeight: 300)
                }
            }
            .padding(.vertical)
        }
        .refreshable { await refresh() }
        .onReceive(Timer.publish(every: 5, on: .main, in: .common).autoconnect()) { _ in
            Task { await refresh() }
        }
        .task { await refresh() }
        .sheet(isPresented: $showCarSwitcher) { CarSwitcherView() }
        .sheet(isPresented: $showReadinessIntro) {
            ReadinessIntroSheet(readiness: dataReadiness, carId: state.currentCarId)
        }
    }

    // MARK: - Main Dashboard Content

    @ViewBuilder
    private func dashboardContent(_ s: CarStatus) -> some View {
        // 1. Header — car name, state badge, settings
                    DashboardHeader(status: s, snapshotSource: snapshotSource, showCarSwitcher: $showCarSwitcher)

        // 2. Hero Telemetry Card — battery %, range, speed, power
        TelemetryHeroCard(status: s, palette: palette)

        // 3. Status Chips — lock, plug, climate, sentry
        StatusChipRow(status: s, palette: palette)

        // 4. Door / Window Open Warning (mirrors Android openVehicleOpenings)
        if VehicleStatusPresentation.shouldShowOpeningPanel(s) {
            DoorWarning(status: s)
        }

        // 5. Location Map with Elevation
        NavigationLink {
            LocationDetailView(status: s)
        } label: {
            LocationMapCard(status: s, readinessLocation: dataReadiness?.item("location"))
        }
        .buttonStyle(.plain)

        // 6. Vehicle Info Cards — temperature, mileage
        VehicleInfoCards(status: s, palette: palette)

        // 7. Tire Pressure Grid
        NavigationLink(value: Route.tpmsTrend(carId: state.currentCarId)) {
            TirePressureGrid(status: s, palette: palette, readinessTpms: dataReadiness?.item("tpms"))
        }
        .buttonStyle(.plain)

        // 8. Charging Panel (only when actively charging — mirrors Android isCharging)
        if s.isCharging {
            NavigationLink {
                CurrentChargeView()
            } label: {
                ChargingPanel(status: s, palette: palette)
            }
            .buttonStyle(.plain)
        }

        // 9. Battery & Mileage Quick Access
        QuickAccessRow(palette: palette)

        // 10. High SOC Warning
        if s.chargeLimitSoc > 90 {
            HighSOCWarning()
        }
    }

    // MARK: - Refresh

    func refresh() async {
        guard !isRefreshing else { return }
        isRefreshing = true
        defer { isRefreshing = false }
        loadError = nil

        if state.isMockMode {
            status = await state.mock.mockStatus(state.currentCarId)
            snapshotSource = "mock"
        } else if let api = state.real {
            let carId = state.currentCarId
            do {
                let snap = try await api.getAdapterSnapshot(carId: carId)
                status = snap.status
                snapshotSource = snap.source
            } catch {
                do {
                    status = try await api.fetch("/api/v1/cars/\(carId)/status")
                    snapshotSource = "teslamate_api"
                } catch {
                    status = nil
                    snapshotSource = nil
                    loadError = "Unable to load vehicle status: \(error.localizedDescription)"
                }
            }
            // Data readiness (mirrors Android DashboardViewModel parallel fetch).
            // Failure is non-fatal: dashboard cards just show their own values.
            if let readiness = try? await api.getDataReadiness(carId: carId) {
                dataReadiness = readiness
                if !DataReadinessSeenStore.hasSeen(readiness, carId: carId) {
                    showReadinessIntro = true
                }
            }
        } else {
            status = nil
            loadError = "No TeslaMate instance is configured."
        }

        // Widget data + TPMS history samples (Android Worker equivalent)
        if let s = status {
            TpmsSampleStore.record(from: s)
            if let defaults = UserDefaults(suiteName: "group.com.matelink") {
                defaults.set(Int(s.batteryLevel), forKey: "widget_battery")
                defaults.set(Int(s.usableBatteryRangeKm), forKey: "widget_range")
                defaults.set(s.state.rawValue, forKey: "widget_state")
            }
        }
    }
}

// MARK: - Header

private struct DashboardHeader: View {
    let status: CarStatus
    let snapshotSource: String?
    @Binding var showCarSwitcher: Bool
    @EnvironmentObject var state: AppState

    var body: some View {
        HStack {
            Button(action: { showCarSwitcher.toggle() }) {
                HStack(spacing: 6) {
                    Text(state.currentCar?.name ?? "Tesla")
                        .font(.title2.bold())
                    Image(systemName: MateIcons.chevronDown)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()

            if let snapshotSource {
                SnapshotBadge(source: snapshotSource)
            }

            Text(status.state.localizedLabel)
                .font(.caption.weight(.medium))
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(status.state.color)
                .foregroundStyle(.white)
                .clipShape(Capsule())

            // Settings
            NavigationLink {
                SettingsView()
            } label: {
                Image(systemName: MateIcons.settings)
                    .font(.title3)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal)
    }
}

// MARK: - Telemetry Hero Card (Android's TelemetryPanel hero)

private struct TelemetryHeroCard: View {
    let status: CarStatus
    let palette: CarColorPalette

    var body: some View {
        VStack(spacing: 12) {
            // Large battery percentage + range
            HStack(alignment: .lastTextBaseline, spacing: 4) {
                Text("\(status.batteryLevel)")
                    .font(MateFont.mono(.bold, size: 72))
                    .foregroundStyle(palette.accent)
                Text("%")
                    .font(MateFont.mono(.medium, size: 28))
                    .foregroundStyle(palette.accentDim)
            }
            .modifier(MateAnimation.NumberTransition())
            .animation(MateAnimation.defaultSpring, value: status.batteryLevel)

            // Range
            Text("\(Int(status.usableBatteryRangeKm)) km range")
                .font(MateFont.inter(.medium, size: 15))
                .foregroundStyle(palette.onSurfaceVariant)

            // Battery progress bar
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 4)
                        .fill(palette.progressTrack)
                    RoundedRectangle(cornerRadius: 4)
                        .fill(palette.accent)
                        .frame(width: geo.size.width * CGFloat(status.batteryLevel) / 100)
                        .animation(MateAnimation.defaultSpring, value: status.batteryLevel)
                }
            }
            .frame(height: 8)

            // Telemetry strip: speed | power | shift
            HStack(spacing: 24) {
                TelemetryMetric(label: "Speed", value: "\(status.speed)", unit: "km/h")
                TelemetryMetric(label: "Power",
                                value: String(format: "%.1f", status.power),
                                unit: "kW",
                                color: status.power > 0 ? MateColors.charging : MateColors.online)
                TelemetryMetric(label: "Shift", value: status.shiftState ?? "—", unit: "")
            }

            // Charge limit
            if status.pluggedIn {
                HStack {
                    Text("Charge limit")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text("\(status.chargeLimitSoc)%")
                        .font(MateFont.mono(.medium, size: 14))
                        .foregroundStyle(palette.accent)
                }
            }
        }
        .padding()
        .background(palette.surface)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(palette.progressTrack, lineWidth: 1)
        )
        .padding(.horizontal)
    }
}

private struct TelemetryMetric: View {
    let label: String
    let value: String
    let unit: String
    var color: Color = .primary

    var body: some View {
        VStack(spacing: 2) {
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
            HStack(alignment: .lastTextBaseline, spacing: 2) {
                Text(value)
                    .font(MateFont.mono(.medium, size: 18))
                    .foregroundStyle(color)
                Text(unit)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
    }
}

// MARK: - Status Chips

private struct StatusChipRow: View {
    let status: CarStatus
    let palette: CarColorPalette

    var body: some View {
        HStack(spacing: 10) {
            StatusChip(icon: MateIcons.lock, label: "Lock",
                       isOn: status.locked, color: palette.accent)
            StatusChip(icon: MateIcons.plug, label: "Plug",
                       isOn: status.pluggedIn, color: palette.acColor)
            StatusChip(icon: MateIcons.climate, label: "Climate",
                       isOn: status.isClimateOn, color: palette.dcColor)
            StatusChip(icon: MateIcons.sentry, label: "Sentry",
                       isOn: status.sentryMode, color: MateColors.warning)
        }
        .padding(.horizontal)
    }
}

private struct StatusChip: View {
    let icon: String
    let label: String
    let isOn: Bool
    let color: Color

    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(isOn ? color : .secondary)
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(
            isOn ? RoundedRectangle(cornerRadius: 12)
                .stroke(color.opacity(0.4), lineWidth: 1) : nil
        )
    }
}

// MARK: - Door Warning

private struct DoorWarning: View {
    let status: CarStatus

    var body: some View {
        HStack {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(MateColors.warning)
            Text(VehicleStatusPresentation.openingWarningText(status))
                .font(.caption)
                .foregroundStyle(MateColors.warning)
        }
        .padding(.horizontal)
    }
}

// MARK: - Location Map Card

private struct LocationMapCard: View {
    let status: CarStatus
    var readinessLocation: DataReadinessItem?

    /// Location is considered unavailable until the source reports a real fix
    /// (mirrors Android: missing values are never displayed as 0).
    private var hasLocation: Bool {
        status.latitude != 0 || status.longitude != 0
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label("Location", systemImage: MateIcons.location)
                .font(.caption)
                .foregroundStyle(.secondary)

            if hasLocation {
                AmapView(latitude: status.latitude,
                         longitude: status.longitude,
                         title: "Current Location")
                    .frame(height: 160)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            } else {
                // Readiness fallback: show collection status instead of a blank map
                // (mirrors Android readinessDashboardValue).
                HStack(spacing: 8) {
                    Image(systemName: MateIcons.location)
                        .foregroundStyle(MateColors.muted)
                    Text(readinessDashboardValue(readinessLocation))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Spacer()
                }
                .frame(maxWidth: .infinity, minHeight: 60)
                .background(Color(.tertiarySystemGroupedBackground))
                .clipShape(RoundedRectangle(cornerRadius: 12))
            }

            HStack {
                Image(systemName: "arrow.up.arrow.down")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                Text("Elevation: \(Int(status.elevation))m")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .padding(.horizontal)
    }
}

// MARK: - Vehicle Info Cards

private struct VehicleInfoCards: View {
    let status: CarStatus
    let palette: CarColorPalette

    var body: some View {
        HStack(spacing: 12) {
            NavigationLink {
                BatteryHealthView()
            } label: {
                InfoCard(title: "Battery",
                         value: "\(Int(status.batteryLevel))%",
                         subtitle: "\(Int(status.usableBatteryRangeKm)) km",
                         icon: MateIcons.battery,
                         color: palette.accent)
            }
            .buttonStyle(.plain)

            NavigationLink {
                MileageView()
            } label: {
                InfoCard(title: "Odometer",
                         value: "\(status.odometer.formatted())",
                         subtitle: "km",
                         icon: MateIcons.mileage,
                         color: .secondary)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal)
    }
}

private struct InfoCard: View {
    let title: String
    let value: String
    let subtitle: String
    let icon: String
    let color: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Image(systemName: icon)
                    .font(.caption)
                    .foregroundStyle(color)
                Text(title)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text(value)
                .font(MateFont.mono(.bold, size: 24))
                .foregroundStyle(.primary)
                .monospacedDigit()
            Text(subtitle)
                .font(.caption2)
                .foregroundStyle(.tertiary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}

// MARK: - Tire Pressure Grid

private struct TirePressureGrid: View {
    let status: CarStatus
    let palette: CarColorPalette
    var readinessTpms: DataReadinessItem?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Tire Pressure", systemImage: MateIcons.tpms)
                .font(.caption)
                .foregroundStyle(.secondary)

            HStack(spacing: 10) {
                TireCard(label: "FL", value: status.tirePressureFrontLeft, color: palette.accent,
                         readinessValue: tireReadinessValue)
                TireCard(label: "FR", value: status.tirePressureFrontRight, color: palette.accent,
                         readinessValue: tireReadinessValue)
                TireCard(label: "RL", value: status.tirePressureRearLeft, color: palette.acColor,
                         readinessValue: tireReadinessValue)
                TireCard(label: "RR", value: status.tirePressureRearRight, color: palette.dcColor,
                         readinessValue: tireReadinessValue)
            }
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .padding(.horizontal)
    }

    /// Mirrors Android: tire cards fall back to the tpms readiness status when
    /// the sensor data has not been collected yet.
    private var tireReadinessValue: String? {
        let hasAnyPressure = [status.tirePressureFrontLeft, status.tirePressureFrontRight,
                              status.tirePressureRearLeft, status.tirePressureRearRight]
            .contains { $0 != 0 }
        return hasAnyPressure ? nil : readinessDashboardValue(readinessTpms)
    }
}

private struct TireCard: View {
    let label: String
    let value: Double
    let color: Color
    var readinessValue: String? = nil

    var body: some View {
        VStack(spacing: 4) {
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
            if let readinessValue {
                Text(readinessValue)
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(MateColors.accent)
                    .lineLimit(3)
                    .multilineTextAlignment(.center)
            } else {
                Text(String(format: "%.1f", value))
                    .font(MateFont.mono(.medium, size: 16))
                    .monospacedDigit()
                Text("bar")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .background(Color(.tertiarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}

// MARK: - Charging Panel

private struct ChargingPanel: View {
    let status: CarStatus
    let palette: CarColorPalette

    var body: some View {
        VStack(spacing: 12) {
            // Live badge
            HStack {
                Circle()
                    .fill(MateColors.charging)
                    .frame(width: 8, height: 8)
                    .opacity(0.8)
                    .animation(.easeInOut(duration: 1).repeatForever(), value: true)
                Text("Charging in Progress")
                    .font(.headline)
                    .foregroundStyle(MateColors.charging)
                Spacer()
                if status.isDcCharging {
                    Text("DC")
                        .font(.caption2.weight(.bold))
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(MateColors.dcColor)
                        .foregroundStyle(.white)
                        .clipShape(Capsule())
                }
            }

            HStack(spacing: 20) {
                MetricColumn(title: "Power",
                             value: String(format: "%.1f", status.chargerPower),
                             unit: "kW")
                MetricColumn(title: "Added",
                             value: String(format: "%.1f", status.chargeEnergyAdded),
                             unit: "kWh")
                MetricColumn(title: "Remaining",
                             value: "\(Int(status.timeToFullCharge * 60))",
                             unit: "min")
            }
        }
        .padding()
        .background(MateColors.charging.opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .padding(.horizontal)
    }
}

private struct MetricColumn: View {
    let title: String
    let value: String
    let unit: String

    var body: some View {
        VStack(spacing: 4) {
            Text(title)
                .font(.caption2)
                .foregroundStyle(.secondary)
            HStack(alignment: .lastTextBaseline, spacing: 2) {
                Text(value)
                    .font(MateFont.mono(.bold, size: 20))
                    .monospacedDigit()
                Text(unit)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Quick Access Row

private struct QuickAccessRow: View {
    let palette: CarColorPalette

    var body: some View {
        HStack(spacing: 12) {
            NavigationLink {
                BatteryHealthView()
            } label: {
                QuickAccessCard(title: "Battery Health",
                                icon: MateIcons.battery,
                                color: palette.accent)
            }
            .buttonStyle(.plain)

            NavigationLink {
                MileageView()
            } label: {
                QuickAccessCard(title: "Mileage",
                                icon: MateIcons.mileage,
                                color: palette.onSurfaceVariant)
            }
            .buttonStyle(.plain)

            NavigationLink {
                StatisticsView()
            } label: {
                QuickAccessCard(title: "Statistics",
                                icon: MateIcons.statistics,
                                color: palette.accent)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal)
    }
}

private struct QuickAccessCard: View {
    let title: String
    let icon: String
    let color: Color

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundStyle(color)
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 16)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

// MARK: - High SOC Warning

private struct HighSOCWarning: View {
    var body: some View {
        HStack {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(MateColors.warning)
            Text("High charge level — consider reducing to 80–90% for daily use")
                .font(.caption)
                .foregroundStyle(MateColors.warning)
        }
        .padding(.horizontal)
    }
}

// MARK: - Partial Dashboard (status unavailable — mirrors Android PartialVehicleDashboard)

private struct PartialDashboardView: View {
    let car: Car
    let error: String
    let onRefresh: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text(car.name)
                    .font(.title2.bold())
                Spacer()
                Text("Partial")
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(MateColors.warning.opacity(0.2))
                    .foregroundStyle(MateColors.warning)
                    .clipShape(Capsule())
            }

            VStack(alignment: .leading, spacing: 8) {
                Label("Model \(car.model)", systemImage: "car.fill")
                Label(car.color, systemImage: "paintpalette")
                Label("\(car.totalDrives) drives", systemImage: "road.lanes")
            }
            .font(.subheadline)
            .foregroundStyle(.secondary)

            Text(error)
                .font(.caption)
                .foregroundStyle(MateColors.error)

            Button(action: onRefresh) {
                Label("Retry", systemImage: MateIcons.refresh)
            }
            .buttonStyle(.borderedProminent)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Snapshot source badge (Android SnapshotBadge)

private struct SnapshotBadge: View {
    let source: String

    private var kind: (label: String, color: Color) {
        switch source {
        case "live_mqtt", "teslamate_api", "fleet_api":
            return ("Live", MateColors.charging)
        case "mqtt_latest":
            return ("Recent", MateColors.warning)
        case "database_latest":
            return ("History", MateColors.warning)
        case "mock":
            return ("Mock", MateColors.warning)
        default:
            return (source, .secondary)
        }
    }

    var body: some View {
        Text(kind.label)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .foregroundStyle(kind.color)
            .background(kind.color.opacity(0.15))
            .clipShape(Capsule())
    }
}

// MARK: - Car Switcher Sheet

struct CarSwitcherView: View {
    @EnvironmentObject var state: AppState
    @Environment(\.dismiss) var dismiss

    var body: some View {
        NavigationStack {
            List(state.cars) { car in
                Button(action: {
                    state.selectCar(car.id)
                    dismiss()
                }) {
                    HStack {
                        VStack(alignment: .leading) {
                            Text(car.name).font(.headline)
                            Text("\(car.model) · \(car.totalDrives) drives")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        if car.id == state.currentCarId {
                            Image(systemName: "checkmark")
                                .foregroundStyle(.accentColor)
                        }
                    }
                }
            }
            .navigationTitle("Select Vehicle")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

// MARK: - stitchCard Extension (legacy compat, migrate away)

extension View {
    func stitchCard() -> some View {
        self
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color(.separator), lineWidth: 1))
    }
}
