import SwiftUI

struct ChargeListView: View {
    @EnvironmentObject var state: AppState
    @Environment(\.carPalette) private var palette
    @State private var allCharges: [Charge] = []
    @State private var loading = true
    @State private var status: CarStatus?
    @State private var loadError: String?
    @State private var dateFilter: HistoryDateFilter = .last7Days
    @State private var typeFilter: ChargeTypeFilter = .all
    @State private var costFilter: CostFilter = .all

    private var charges: [Charge] {
        allCharges.filter { charge in
            !ChargeFilterRules.isShortCharge(charge)
                && (typeFilter == .all || charge.chargingType == typeFilter.label)
                && costFilter.matches(charge.cost)
        }
    }

    private var showsCurrentCharge: Bool {
        guard let s = status else { return false }
        return s.isCharging || s.pluggedIn
    }

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading…")
                    .frame(maxWidth: .infinity, minHeight: 300)
            } else if let loadError {
                EmptyStateView("Charge History Unavailable",
                               systemImage: "exclamationmark.triangle",
                               message: loadError)
                    .padding(40)
            } else {
                listContent
            }
        }
        .navigationTitle("Charges")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Picker("Date", selection: $dateFilter) {
                        ForEach(HistoryDateFilter.allCases) { filter in
                            Text(filter.label).tag(filter)
                        }
                    }
                    Picker("Type", selection: $typeFilter) {
                        ForEach(ChargeTypeFilter.allCases) { filter in
                            Text(filter.label).tag(filter)
                        }
                    }
                    Picker("Cost", selection: $costFilter) {
                        ForEach(CostFilter.allCases) { filter in
                            Text(filter.label).tag(filter)
                        }
                    }
                } label: {
                    Image(systemName: "line.3.horizontal.decrease.circle")
                }
            }
        }
        .onChange(of: dateFilter) { _ in
            Task { await load() }
        }
        .refreshable { await load() }
        .task { await load() }
    }

    // MARK: - List Content

    private var listContent: some View {
        List {
            // Current charge banner
            if showsCurrentCharge {
                Section {
                    NavigationLink(value: Route.currentCharge(carId: state.currentCarId)) {
                        CurrentChargeBanner(status: status)
                    }
                }
            }

            // Summary
            if !charges.isEmpty {
                Section {
                    chargeSummary
                }
            }

            // Charge history
            Section {
                ForEach(charges) { ch in
                    NavigationLink(value: Route.chargeDetail(carId: ch.carId, chargeId: ch.id)) {
                        ChargeRow(charge: ch, palette: palette)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    // MARK: - Summary Card

    private var chargeSummary: some View {
        HStack(spacing: 16) {
            SummaryMetric(title: "Total Energy",
                          value: String(format: "%.0f", totalEnergy),
                          unit: "kWh",
                          color: palette.accent)
            SummaryMetric(title: "Total Cost",
                          value: totalCost > 0 ? "¥\(String(format: "%.0f", totalCost))" : "Free",
                          unit: "",
                          color: palette.acColor)
            SummaryMetric(title: "Sessions",
                          value: "\(charges.count)",
                          unit: "",
                          color: palette.onSurfaceVariant)
        }
        .padding(.vertical, 4)
    }

    private var totalEnergy: Double {
        charges.reduce(0) { $0 + $1.chargeEnergyAdded }
    }
    private var totalCost: Double {
        charges.compactMap(\.cost).reduce(0, +)
    }

    // MARK: - Data Loading

    func load() async {
        let carId = state.currentCarId
        loadError = nil
        if charges.isEmpty { loading = true }

        // Cache-first
        if let api = state.real, dateFilter == .allTime, let cached = await api.getCachedCharges(carId: carId) {
            allCharges = cached
            loading = false
        }

        let bounds = dateFilter.isoBounds()
        if state.isMockMode {
            allCharges = await state.mock.getCharges(carId)
            status = await state.mock.mockStatus(carId)
        } else if let api = state.real {
            do {
                let fresh: [Charge] = try await api.getAllCharges(
                    carId: carId,
                    startDate: bounds?.start,
                    endDate: bounds?.end
                )
                allCharges = fresh
                if dateFilter == .allTime {
                    await api.cacheCharges(fresh, carId: carId)
                }
            } catch {
                if charges.isEmpty {
                    loadError = error.localizedDescription
                }
            }
            do {
                status = try await api.fetch("/api/v1/cars/\(carId)/status")
            } catch {
                // Non-fatal
            }
        } else {
            loadError = "No TeslaMate instance is configured."
        }
        loading = false
    }
}

// MARK: - Current Charge Banner

private struct CurrentChargeBanner: View {
    let status: CarStatus?

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "bolt.circle.fill")
                .font(.title2)
                .foregroundStyle(MateColors.charging)

            VStack(alignment: .leading, spacing: 2) {
                Text("Current Charge")
                    .font(.subheadline.weight(.semibold))
                Text("Live charging session")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            if let s = status {
                Text("\(s.batteryLevel)% · \(String(format: "%.1f", s.chargerPower)) kW")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Charge Row

private struct ChargeRow: View {
    let charge: Charge
    let palette: CarColorPalette

    var body: some View {
        HStack(spacing: 12) {
            // AC/DC badge
            Image(systemName: charge.chargingType == "DC" ? "bolt.fill" : "powerplug.fill")
                .font(.title3)
                .foregroundStyle(charge.chargingType == "DC" ? palette.dcColor : palette.acColor)
                .frame(width: 32)

            VStack(alignment: .leading, spacing: 3) {
                Text(charge.address ?? "Unknown Location")
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)

                HStack(spacing: 6) {
                    Text("\(charge.chargeEnergyAdded, specifier: "%.1f") kWh")
                    Text("·")
                    Text("\(charge.startBatteryLevel)% → \(charge.endBatteryLevel ?? 0)%")
                    if (charge.cost ?? 0) > 0 {
                        Text("·")
                        Text("¥\(String(format: "%.2f", charge.cost ?? 0))")
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            Spacer()

            // AC/DC pill
            Text(charge.chargingType)
                .font(.caption2.weight(.bold))
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(charge.chargingType == "DC" ? palette.dcColor : palette.acColor)
                .foregroundStyle(.white)
                .clipShape(Capsule())
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Summary Metric (shared with DriveListView)

private struct SummaryMetric: View {
    let title: String
    let value: String
    let unit: String
    let color: Color

    var body: some View {
        VStack(spacing: 4) {
            Text(title)
                .font(.caption2)
                .foregroundStyle(.secondary)
            HStack(alignment: .lastTextBaseline, spacing: 2) {
                Text(value)
                    .font(MateFont.mono(.bold, size: 18))
                    .foregroundStyle(color)
                if !unit.isEmpty {
                    Text(unit)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
            }
        }
        .frame(maxWidth: .infinity)
    }
}
