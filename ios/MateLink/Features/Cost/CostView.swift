import SwiftUI
import Charts

struct MonthlyCostItem: Identifiable {
    let id = UUID()
    let month: String
    let acCost: Double
    let dcCost: Double
    var total: Double { acCost + dcCost }
}

struct LocationCost: Identifiable {
    let id = UUID()
    let address: String
    let cost: Double
    let kWh: Double
    let pricePerKwh: Double
    let count: Int
}

struct CostView: View {
    @EnvironmentObject var state: AppState
    @State private var monthlyData: [MonthlyCostItem] = []
    @State private var ranking: [LocationCost] = []
    @State private var loadError: String?

    var totalCost: Double { monthlyData.map(\.total).reduce(0, +) }
    var acTotal: Double { monthlyData.map(\.acCost).reduce(0, +) }
    var dcTotal: Double { monthlyData.map(\.dcCost).reduce(0, +) }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    // Header
                    Text("Charging Cost")
                        .font(.title2).bold()
                        .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal)

                    if let loadError {
                        EmptyStateView("Cost Data Unavailable", systemImage: "exclamationmark.triangle", message: loadError)
                            .padding(.top, 24)
                    }

                    // Summary Cards
                    HStack(spacing: 12) {
                        StatCard(title: "Total Cost", value: "\u{00A5}\(totalCost.formatted(.number.precision(.fractionLength(2))))", subtitle: "", color: .primary)
                        StatCard(title: "Home AC", value: "\u{00A5}\(acTotal.formatted(.number.precision(.fractionLength(2))))", subtitle: "", color: .blue)
                        StatCard(title: "DC Fast", value: "\u{00A5}\(dcTotal.formatted(.number.precision(.fractionLength(2))))", subtitle: "", color: .orange)
                    }.padding(.horizontal)

                    // Stacked Bar Chart
                    if !monthlyData.isEmpty {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Monthly Cost Breakdown")
                                .font(.subheadline.weight(.medium)).foregroundColor(.secondary)
                            Chart(monthlyData) { m in
                                BarMark(x: .value("Month", m.month), y: .value("Cost", m.acCost))
                                    .foregroundStyle(.blue)
                                BarMark(x: .value("Month", m.month), y: .value("Cost", m.dcCost))
                                    .foregroundStyle(.orange)
                            }
                            .chartForegroundStyleScale(["AC": .blue, "DC": .orange])
                            .frame(height: 250)
                        }
                        .padding()
                        .background(.regularMaterial)
                        .clipShape(RoundedRectangle(cornerRadius: 16))
                        .padding(.horizontal)
                    }

                    // Location Ranking
                    VStack(alignment: .leading, spacing: 0) {
                        Text("Location Ranking (\u{00A5}/kWh)")
                            .font(.caption.weight(.semibold)).foregroundColor(.secondary)
                            .padding(.horizontal).padding(.vertical, 10)
                        Divider()
                        ForEach(Array(ranking.prefix(10).enumerated()), id: \.element.id) { i, loc in
                            HStack(spacing: 12) {
                                Text("\(i + 1)").font(.headline).foregroundColor(.secondary).frame(width: 24)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(loc.address).font(.subheadline).lineLimit(1)
                                    Text("\(loc.count)\u{00D7} \u{00B7} \(loc.kWh.formatted(.number.precision(.fractionLength(1)))) kWh")
                                        .font(.caption2).foregroundColor(.secondary)
                                }
                                Spacer()
                                Text("\u{00A5}\(loc.pricePerKwh.formatted(.number.precision(.fractionLength(2))))/kWh")
                                    .font(.subheadline.weight(.medium)).foregroundColor(.green)
                            }
                            .padding(.horizontal).padding(.vertical, 8)
                            if i < min(ranking.count, 10) - 1 { Divider() }
                        }
                    }
                    .background(.regularMaterial)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .padding(.horizontal)
                }
                .padding(.vertical)
            }
            .navigationBarTitleDisplayMode(.inline)
            .task { await loadData() }
        }
    }

    func loadData() async {
        loadError = nil
        let charges: [Charge]
        do {
            if state.isMockMode {
                charges = await state.mock.getCharges(state.currentCarId)
            } else if let api = state.real {
                charges = try await api.fetch("/api/v1/cars/\(state.currentCarId)/charges")
            } else {
                throw URLError(.notConnectedToInternet)
            }
        } catch {
            monthlyData = []
            ranking = []
            loadError = "Unable to load real charge data: \(error.localizedDescription)"
            return
        }
        let completedCharges = charges.filter { $0.endDate != nil }

        // Monthly aggregation
        var monthMap: [String: (ac: Double, dc: Double)] = [:]
        for c in completedCharges {
            let month = String(c.startDate.prefix(7))
            var entry = monthMap[month] ?? (0, 0)
            if c.chargeType == "DC" { entry.dc += c.cost ?? 0 } else { entry.ac += c.cost ?? 0 }
            monthMap[month] = entry
        }
        monthlyData = monthMap.sorted { $0.key < $1.key }.map {
            MonthlyCostItem(month: String($0.key.suffix(2)), acCost: ($0.value.ac * 100).rounded() / 100, dcCost: ($0.value.dc * 100).rounded() / 100)
        }

        // Location ranking
        var locMap: [String: (cost: Double, kWh: Double, count: Int)] = [:]
        for c in completedCharges {
            let addr = c.address ?? "Unknown"
            var entry = locMap[addr] ?? (0, 0, 0)
            entry.cost += c.cost ?? 0
            entry.kWh += c.chargeEnergyAdded
            entry.count += 1
            locMap[addr] = entry
        }
        ranking = locMap.map { addr, v in
            LocationCost(address: addr, cost: v.cost, kWh: v.kWh,
                         pricePerKwh: (v.cost / max(v.kWh, 0.1) * 100).rounded() / 100, count: v.count)
        }.sorted { $0.pricePerKwh < $1.pricePerKwh }
    }
}
