import SwiftUI

struct ChargeListView: View {
    @EnvironmentObject var state: AppState; @State private var charges: [Charge] = []; @State private var loading = true

    var body: some View {
        NavigationStack {
            Group {
                if loading { ProgressView("Loading...").padding() }
                else {
                    List(charges) { ch in
                        NavigationLink(destination: ChargeDetailView(charge: ch)) {
                            HStack {
                                Image(systemName: ch.chargeType == "DC" ? "bolt.fill" : "powerplug.fill")
                                    .foregroundColor(ch.chargeType == "DC" ? .orange : .blue)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(ch.address).font(.subheadline).bold()
                                    Text("\(ch.chargeEnergyAdded, specifier: "%.1f") kWh · \(ch.startBatteryLevel)% → \(ch.endBatteryLevel ?? 0)% \(ch.cost > 0 ? "· ¥\(String(format:"%.2f",ch.cost))" : "")").font(.caption).foregroundColor(.secondary)
                                }
                            }
                        }
                    }.listStyle(.plain)
                }
            }.navigationTitle("Charge History").refreshable { await load() }.task { await load() }
        }
    }

    func load() async {
        loading = true
        let carId = state.currentCarId
        // Cache-first (F‑015)
        if let api = state.real, let cached = await api.getCachedCharges(carId: carId) { charges = cached }
        if state.isMockMode {
            charges = await state.mock.getCharges(carId)
        } else if let api = state.real {
            do {
                let fresh: [Charge] = try await api.fetch("api/v1/cars/\(carId)/charges")
                charges = fresh; await api.cacheCharges(fresh, carId: carId)
            } catch { /* stale cache stays visible */ }
        }
        loading = false
    }
}
