import SwiftUI

struct ChargeListView: View {
    @EnvironmentObject var state: AppState; @State private var charges: [Charge] = []; @State private var loading = true
    @State private var status: CarStatus?
    @State private var loadError: String?

    private var showsCurrentCharge: Bool {
        guard let s = status else { return false }
        return s.state == .charging || s.pluggedIn
    }

    var body: some View {
        NavigationStack {
            Group {
                if loading { ProgressView("Loading...").padding() }
                else if let loadError {
                    EmptyStateView("Charge History Unavailable", systemImage: "exclamationmark.triangle", message: loadError)
                        .padding()
                }
                else {
                    List {
                        if showsCurrentCharge {
                            Section {
                                NavigationLink(destination: CurrentChargeView()) {
                                    HStack {
                                        Image(systemName: "bolt.circle.fill").foregroundColor(.orange)
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text("Current Charge").font(.subheadline).bold()
                                            Text("Live charging session").font(.caption).foregroundColor(.secondary)
                                        }
                                        Spacer()
                                        if let s = status {
                                            Text("\(s.batteryLevel)% · \(String(format: "%.1f", s.chargerPower)) kW")
                                                .font(.caption).foregroundColor(.secondary)
                                        }
                                    }
                                }
                            }
                        }
                        Section {
                            ForEach(charges) { ch in
                                NavigationLink(destination: ChargeDetailView(charge: ch)) {
                                    HStack {
                                        Image(systemName: ch.chargeType == "DC" ? "bolt.fill" : "powerplug.fill")
                                            .foregroundColor(ch.chargeType == "DC" ? .orange : .blue)
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(ch.address ?? "Unknown").font(.subheadline).bold()
                                            Text("\(ch.chargeEnergyAdded, specifier: "%.1f") kWh · \(ch.startBatteryLevel)% → \(ch.endBatteryLevel ?? 0)% \((ch.cost ?? 0) > 0 ? "· ¥\(String(format:"%.2f", ch.cost ?? 0))" : "")").font(.caption).foregroundColor(.secondary)
                                        }
                                    }
                                }
                            }
                        }
                    }.listStyle(.plain)
                }
            }.navigationTitle("Charge History").refreshable { await load() }.task { await load() }
        }
    }

    func load() async {
        let carId = state.currentCarId
        loadError = nil
        if charges.isEmpty {
            loading = true
        }
        // Cache-first (F‑015)
        if let api = state.real, let cached = await api.getCachedCharges(carId: carId) {
            charges = cached
            loading = false
        }
        if state.isMockMode {
            charges = await state.mock.getCharges(carId)
            status = await state.mock.mockStatus(carId)
        } else if let api = state.real {
            do {
                let fresh: [Charge] = try await api.fetch("/api/v1/cars/\(carId)/charges")
                charges = fresh; await api.cacheCharges(fresh, carId: carId)
            } catch {
                if charges.isEmpty {
                    loadError = "Unable to load real charge history: \(error.localizedDescription)"
                }
            }
            do {
                status = try await api.fetch("/api/v1/cars/\(carId)/status")
            } catch {
                if loadError == nil && charges.isEmpty {
                    loadError = "Unable to load real vehicle status: \(error.localizedDescription)"
                }
            }
        } else {
            loadError = "No TeslaMate instance is configured."
        }
        loading = false
    }
}

