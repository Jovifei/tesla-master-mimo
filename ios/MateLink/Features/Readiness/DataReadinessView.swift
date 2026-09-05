import SwiftUI

// MARK: - Data Readiness View (mirrors Android DataReadinessScreen.kt)

@MainActor
final class DataReadinessViewModel: ObservableObject {
    @Published var readiness: DataReadiness?
    @Published var pairing: TelemetryPairingStatus?
    @Published var isLoading = true
    @Published var isConfiguringTelemetry = false
    @Published var telemetryErrorCode: String?
    @Published var isTelemetryActivationPending = false
    @Published var pairingLinkUnavailable = false
    @Published var loadError: String?

    private var carId: Int = 1
    private var api: TeslaMateAPI?
    private var configureTask: Task<Void, Never>?
    private var configureGeneration: UInt64 = 0
    private let pollingPolicy = TelemetryPollingPolicy()

    func configure(carId: Int, api: TeslaMateAPI?) {
        self.carId = carId
        self.api = api
        Task { await load() }
    }

    func load() async {
        isLoading = readiness == nil
        loadError = nil
        guard let api else {
            isLoading = false
            return
        }
        do {
            readiness = try await api.getDataReadiness(carId: carId)
        } catch {
            loadError = error.localizedDescription
        }
        await loadPairing()
        isLoading = false
    }

    func refresh() async {
        await load()
    }

    func onScreenPaused() {
        configureTask?.cancel()
    }

    func onScreenResumed() {
        // Re-sync pairing when returning to the page (mirrors Android lifecycle observer).
        Task { await loadPairing() }
    }

    func loadPairing() async {
        guard let api else { return }
        do {
            let status = try await api.getTelemetryPairing(carId: carId)
            pairing = status
            telemetryErrorCode = nil
            isTelemetryActivationPending = status.status == "waiting_vehicle" || status.status == "collecting"
        } catch {
            pairing = nil
        }
    }

    func configureTelemetry() {
        guard !isConfiguringTelemetry, let api else { return }
        configureTask?.cancel()
        configureGeneration += 1
        let generation = configureGeneration
        isConfiguringTelemetry = true

        configureTask = Task { [weak self] in
            guard let self else { return }
            let startedAt = Date()
            defer { Task { @MainActor in self.isConfiguringTelemetry = false } }

            do {
                _ = try await api.configureTelemetry(carId: self.carId)
                self.isTelemetryActivationPending = true
            } catch {
                self.telemetryErrorCode = "telemetry_error"
                return
            }

            // Poll pairing for up to 30s at 5s intervals while the page is active
            // (mirrors Android TelemetryPollingPolicy).
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: TelemetryPollingPolicy.pollIntervalMs * 1_000_000)
                if Task.isCancelled { return }
                let elapsedMs = UInt64(Date().timeIntervalSince(startedAt) * 1000)
                guard self.pollingPolicy.shouldContinue(
                    elapsedMs: elapsedMs,
                    generation: generation,
                    currentGeneration: self.configureGeneration,
                    pageIsActive: true
                ) else { return }
                await self.loadPairing()
                if self.pairing?.configSynced == true { return }
            }
        }
    }

    func reportPairingLinkUnavailable() {
        pairingLinkUnavailable = true
    }
}

struct DataReadinessView: View {
    @EnvironmentObject var state: AppState
    @StateObject private var viewModel = DataReadinessViewModel()
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Group {
            if viewModel.isLoading {
                ProgressView(L10n.string("loading"))
                    .frame(maxWidth: .infinity, minHeight: 300)
            } else {
                ScrollView {
                    VStack(spacing: 14) {
                        Text(L10n.string("data_readiness_intro_body"))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)

                        if let readiness = viewModel.readiness {
                            DataReadinessRows(readiness: readiness)
                        } else {
                            Text(L10n.string("data_readiness_load_error"))
                                .font(.subheadline)
                                .foregroundStyle(MateColors.error)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }

                        FleetTelemetryCard(state: viewModel)

                        Button {
                            Task { await viewModel.refresh() }
                        } label: {
                            Text(L10n.string("refresh"))
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                    }
                    .padding(16)
                }
            }
        }
        .navigationTitle(L10n.string("data_readiness_title"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            viewModel.configure(carId: state.currentCarId, api: state.real)
        }
        .onDisappear { viewModel.onScreenPaused() }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .active: viewModel.onScreenResumed()
            case .background: viewModel.onScreenPaused()
            default: break
            }
        }
    }

    @Environment(\.scenePhase) private var scenePhase
}

// MARK: - Readiness Rows

/// The six readiness rows — reused on the dashboard intro dialog.
/// Mirrors Android `DataReadinessRows`.
struct DataReadinessRows: View {
    let readiness: DataReadiness

    var body: some View {
        VStack(spacing: 10) {
            ForEach(ReadinessKeys.all, id: \.self) { key in
                DataReadinessItemRow(
                    item: readiness.item(key),
                    title: L10n.string(ReadinessKeys.titleKey(for: key))
                )
            }
        }
    }
}

private struct DataReadinessItemRow: View {
    let item: DataReadinessItem?
    let title: String

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Text(statusText)
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(MateColors.accent)
            }

            if let item {
                if let hint = ReadinessActionHint.forItem(item) {
                    Text(hint.localizedLabel)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                if let observed = item.lastObservedAt, !observed.isEmpty {
                    Text(L10n.format("data_readiness_last_observed", observed))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if !item.source.isEmpty {
                    Text(L10n.format("data_readiness_source", readinessSourceLabel(item.source)))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private var statusText: String {
        if let item {
            return ReadinessItemStatus(item.status).localizedLabel
        }
        return L10n.string("data_readiness_status_waiting_vehicle")
    }
}

// MARK: - Fleet Telemetry Card

/// Mirrors Android `FleetTelemetryCard` — pairing state, config sync state,
/// and the presentation-driven action buttons.
private struct FleetTelemetryCard: View {
    @ObservedObject var state: DataReadinessViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L10n.string("telemetry_setup_title"))
                .font(.subheadline.weight(.semibold))

            Text(presentation.localizedLabel)
                .font(.footnote.weight(.medium))
                .foregroundStyle(MateColors.accent)

            Text(L10n.format("telemetry_config_sync_state", configSync.localizedLabel))
                .font(.caption)
                .foregroundStyle(.secondary)

            if let updated = state.pairing?.updatedAt, !updated.isEmpty {
                Text(L10n.format("data_readiness_last_observed", updated))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            actions

            if state.pairingLinkUnavailable {
                Text(L10n.string("telemetry_virtual_key_unavailable"))
                    .font(.caption)
                    .foregroundStyle(MateColors.error)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private var rawStatus: String? {
        state.telemetryErrorCode ?? state.pairing?.status
    }

    private var presentation: TelemetrySetupPresentation {
        let mapped = telemetrySetupPresentation(rawStatus, configSynced: state.pairing?.configSynced)
        if state.isTelemetryActivationPending,
           state.telemetryErrorCode == nil,
           state.pairing?.configSynced != true {
            return .waitingVehicle
        }
        return mapped
    }

    private var configureAction: TelemetryConfigureActionPresentation {
        telemetryConfigureActionPresentation(rawStatus, configSynced: state.pairing?.configSynced)
    }

    private var configSync: TelemetryConfigSyncPresentation {
        telemetryConfigSyncPresentation(state.pairing?.configSynced)
    }

    @ViewBuilder
    private var actions: some View {
        switch presentation {
        case .pairingRequired:
            Button {
                openVirtualKey()
            } label: {
                Text(L10n.string("telemetry_action_open_virtual_key"))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            if configureAction == .configure {
                Button {
                    state.configureTelemetry()
                } label: {
                    Text(L10n.string("telemetry_action_configure"))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .disabled(state.isConfiguringTelemetry)
            }
        case .permissionRequired:
            Button {
                ReauthorizeFlow.request()
            } label: {
                Text(L10n.string("tesla_account_reauthorize"))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
        default:
            if configureAction == .configure {
                Button {
                    state.configureTelemetry()
                } label: {
                    Text(L10n.string("telemetry_action_retry_configuration"))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .disabled(state.isConfiguringTelemetry)
            }
        }
    }

    private func openVirtualKey() {
        let official = officialTeslaVirtualKeyUrlOrNull(state.pairing?.virtualKeyUrl)
        guard let official, let url = URL(string: official) else {
            state.reportPairingLinkUnavailable()
            return
        }
        UIApplication.shared.open(url)
    }
}

/// Reauthorization hook — iOS Tesla OAuth is wired via Settings; deep-link the
/// user there instead of spawning a second flow (Android reuses its login screen).
enum ReauthorizeFlow {
    static func request() {
        NotificationCenter.default.post(name: .mateLinkReauthorizeRequested, object: nil)
    }
}

extension Notification.Name {
    static let mateLinkReauthorizeRequested = Notification.Name("mateLink.reauthorizeRequested")
}

// MARK: - Dashboard Intro Sheet (mirrors Android readiness intro AlertDialog)

/// First-login explanation shown on the Dashboard when readiness data arrives
/// for a vehicle the user has not seen the intro for yet.
struct ReadinessIntroSheet: View {
    let readiness: DataReadiness?
    let carId: Int
    @EnvironmentObject var state: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var navigateToDetails = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text(L10n.string("data_readiness_intro_title"))
                        .font(.headline)

                    Text(L10n.string("data_readiness_intro_body"))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    if let readiness {
                        DataReadinessRows(readiness: readiness)
                    }

                    Button {
                        DataReadinessSeenStore.markSeen(readiness, carId: carId)
                        navigateToDetails = true
                    } label: {
                        Text(L10n.string("data_readiness_intro_open"))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(readiness == nil)

                    Button {
                        DataReadinessSeenStore.markSeen(readiness, carId: carId)
                        dismiss()
                    } label: {
                        Text(L10n.string("data_readiness_intro_continue"))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
                .padding(16)
            }
            .navigationDestination(isPresented: $navigateToDetails) {
                DataReadinessView()
            }
            .navigationTitle(L10n.string("data_readiness_title"))
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
    }
}

// MARK: - Seen Store (mirrors Android DataReadinessStore, simplified namespace)

/// Persists which readiness payloads the user has already been introduced to,
/// keyed by vehicle identity + capability version so a payload change
/// re-triggers the intro exactly like Android's account/mode namespacing.
enum DataReadinessSeenStore {
    private static func key(_ readiness: DataReadiness, carId: Int) -> String {
        "dataReadiness.seen.\(readiness.vehicleUid ?? "unknown").\(readiness.capabilityVersion ?? 0).\(carId)"
    }

    static func hasSeen(_ readiness: DataReadiness, carId: Int) -> Bool {
        UserDefaults.standard.bool(forKey: key(readiness, carId: carId))
    }

    static func markSeen(_ readiness: DataReadiness?, carId: Int) {
        guard let readiness else { return }
        UserDefaults.standard.set(true, forKey: key(readiness, carId: carId))
    }
}
