import SwiftUI

struct Destination: Identifiable {
    let id = UUID()
    let name: String
    let count: Int
    let totalKm: Int
    let avgEff: Int
}

enum DestSort: String, CaseIterable { case count = "Visits", km = "Distance", eff = "Efficiency" }

func effColor(_ eff: Int) -> Color {
    eff < 150 ? Color(hex: "22C55E") : eff < 200 ? Color(hex: "F59E0B") : Color(hex: "EF4444")
}

struct DestinationsView: View {
    @EnvironmentObject var state: AppState
    @State private var destinations: [Destination] = []
    @State private var sort: DestSort = .count

    var sorted: [Destination] {
        switch sort {
        case .count: return destinations.sorted { $0.count > $1.count }
        case .km: return destinations.sorted { $0.totalKm > $1.totalKm }
        case .eff: return destinations.sorted { $0.avgEff < $1.avgEff }
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    // Header + Sort Picker
                    HStack {
                        Text("Top Destinations").font(.title2).bold()
                        Spacer()
                        Picker("Sort", selection: $sort) {
                            ForEach(DestSort.allCases, id: \.self) { s in
                                Text(s.rawValue).tag(s)
                            }
                        }
                        .pickerStyle(.segmented)
                        .frame(width: 240)
                    }
                    .padding(.horizontal)

                    // Destination List
                    if sorted.isEmpty {
                        ContentUnavailableView("No Destinations",
                            systemImage: "mappin.slash",
                            description: Text("Complete drives to build your destination history."))
                            .padding(.top, 60)
                    } else {
                        VStack(spacing: 0) {
                            ForEach(Array(sorted.prefix(20).enumerated()), id: \.element.id) { i, d in
                                HStack(spacing: 12) {
                                    // Rank
                                    Text("\(i + 1)")
                                        .font(.title3.weight(.bold))
                                        .foregroundColor(.secondary.opacity(0.5))
                                        .frame(width: 32)

                                    Image(systemName: "mappin.circle.fill")
                                        .font(.title2).foregroundColor(.accentColor)

                                    // Info
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(d.name).font(.subheadline.weight(.medium)).lineLimit(1)
                                        Text("\(d.count) visits").font(.caption2).foregroundColor(.secondary)
                                    }
                                    .frame(maxWidth: .infinity, alignment: .leading)

                                    // Distance
                                    VStack(alignment: .trailing, spacing: 1) {
                                        Text("\(d.totalKm)").font(.subheadline.weight(.medium))
                                        Text("km").font(.caption2).foregroundColor(.secondary)
                                    }
                                    .frame(width: 60)

                                    // Efficiency
                                    VStack(alignment: .trailing, spacing: 1) {
                                        Text("\(d.avgEff)").font(.subheadline.weight(.bold))
                                            .foregroundColor(effColor(d.avgEff))
                                        Text("Wh/km").font(.caption2).foregroundColor(.secondary)
                                    }
                                    .frame(width: 60)
                                }
                                .padding(.horizontal).padding(.vertical, 10)
                                if i < min(sorted.count, 20) - 1 { Divider() }
                            }
                        }
                        .background(.regularMaterial)
                        .clipShape(RoundedRectangle(cornerRadius: 16))
                        .padding(.horizontal)
                    }
                }
                .padding(.vertical)
            }
            .navigationBarTitleDisplayMode(.inline)
            .task { await loadData() }
        }
    }

    func loadData() async {
        let drives: [Drive] = await {
            if state.isMockMode {
                return await state.mock.getDrives(state.currentCarId)
            } else if let api = state.real {
                return (try? await api.fetch("/api/v1/cars/\(state.currentCarId)/drives")) ?? []
            }
            return []
        }()
        var map: [String: (count: Int, totalKm: Double, effSum: Int)] = [:]

        for d in drives {
            for addr in [d.startAddress, d.endAddress] {
                guard addr.count >= 2 else { continue }
                var entry = map[addr] ?? (0, 0, 0)
                entry.count += 1
                entry.totalKm += d.distanceKm
                entry.effSum += d.efficiency
                map[addr] = entry
            }
        }

        destinations = map.map { name, v in
            Destination(name: name, count: v.count, totalKm: Int(v.totalKm.rounded()), avgEff: v.effSum / v.count)
        }.sorted { $0.count > $1.count }
    }
}
