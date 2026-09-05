import SwiftUI

/// Centralized route resolver — maps `Route` cases to destination views.
/// Implemented as a View so it can access `@EnvironmentObject`.
struct RouteDestinationView: View {
    let route: Route
    @EnvironmentObject var state: AppState

    var body: some View {
        switch route {
        // Drive
        case .driveDetail(let carId, let driveId):
            DriveDetailLoader(carId: carId, driveId: driveId)

        // Charge
        case .chargeDetail(let carId, let chargeId):
            ChargeDetailLoader(carId: carId, chargeId: chargeId)
        case .currentCharge:
            CurrentChargeView()

        // Vehicle
        case .battery:
            BatteryHealthView()
        case .mileage:
            MileageView()
        case .updates:
            UpdatesView()
        case .statistics:
            StatisticsView()

        // Analytics
        case .efficiency:
            EfficiencyView()
        case .cost:
            CostView()
        case .range:
            RangeView()
        case .vampire:
            VampireView()
        case .timeline:
            TimelineView()

        // History
        case .sentryHistory:
            SentryHistoryView()

        // Reports
        case .annualReport:
            AnnualReportPDFView()
        case .export:
            ExportView()

        // Trips
        case .trips(_):
            TripsView()
        case .tripDetail(_, _), .createTrip(_):
            TripsView()

        // TPMS
        case .tpmsTrend(_):
            TpmsTrendView()
        case .tpmsSettings:
            TpmsTrendView()

        // Countries
        case .countriesVisited(_), .regionsVisited(_, _, _):
            CountriesVisitedView()

        // Location
        case .whereWasI(_, _):
            WhereWasIView()

        // System
        case .settings:
            SettingsView()
        case .about:
            AboutView()
        case .tariffConfig:
            TariffConfigView()
        case .dataReadiness:
            DataReadinessView()
        }
    }
}

// MARK: - Detail Loaders (async data fetch for navigation)

private struct DriveDetailLoader: View {
    let carId: Int
    let driveId: Int
    @EnvironmentObject var state: AppState
    @State private var drive: Drive?
    @State private var loading = true

    var body: some View {
        Group {
            if let drive {
                DriveDetailView(drive: drive)
            } else if loading {
                ProgressView(L10n.string("loading"))
                    .frame(maxWidth: .infinity, minHeight: 300)
            } else {
                EmptyStateView("Drive Not Found",
                               systemImage: MateIcons.drives,
                               message: L10n.string("no_data"))
            }
        }
        .task { await load() }
    }

    private func load() async {
        defer { loading = false }
        if state.isMockMode {
            let all = await state.mock.getDrives(carId)
            drive = all.first { $0.id == driveId }
        } else if let api = state.real {
            drive = try? await api.getDriveDetailWithPositions(carId, driveId: driveId)
        }
    }
}

private struct ChargeDetailLoader: View {
    let carId: Int
    let chargeId: Int
    @EnvironmentObject var state: AppState
    @State private var charge: Charge?
    @State private var loading = true

    var body: some View {
        Group {
            if let charge {
                ChargeDetailView(charge: charge)
            } else if loading {
                ProgressView(L10n.string("loading"))
                    .frame(maxWidth: .infinity, minHeight: 300)
            } else {
                EmptyStateView("Charge Not Found",
                               systemImage: MateIcons.charges,
                               message: L10n.string("no_data"))
            }
        }
        .task { await load() }
    }

    private func load() async {
        defer { loading = false }
        if state.isMockMode {
            let all = await state.mock.getCharges(carId)
            charge = all.first { $0.id == chargeId }
        } else if let api = state.real {
            charge = try? await api.getChargeDetailWithPoints(carId, chargeId: chargeId)
        }
    }
}

