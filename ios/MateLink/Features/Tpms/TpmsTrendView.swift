import SwiftUI
import Charts

struct TpmsTrendView: View {
    @EnvironmentObject var state: AppState
    @State private var days = 7
    @State private var samples: [TpmsSample] = []

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Picker("Window", selection: $days) {
                    Text("7 days").tag(7)
                    Text("30 days").tag(30)
                }
                .pickerStyle(.segmented)
                .onChange(of: days) { _ in reload() }

                if samples.isEmpty {
                    EmptyStateView(
                        "No TPMS History Yet",
                        systemImage: MateIcons.tpms,
                        message: "Samples are stored when Dashboard refreshes. Open Dashboard while the car is online, then return here."
                    )
                } else {
                    Chart {
                        ForEach(samples) { s in
                            LineMark(x: .value("Time", s.recordedAt), y: .value("bar", s.fl)).foregroundStyle(by: .value("Tire", "FL"))
                            LineMark(x: .value("Time", s.recordedAt), y: .value("bar", s.fr)).foregroundStyle(by: .value("Tire", "FR"))
                            LineMark(x: .value("Time", s.recordedAt), y: .value("bar", s.rl)).foregroundStyle(by: .value("Tire", "RL"))
                            LineMark(x: .value("Time", s.recordedAt), y: .value("bar", s.rr)).foregroundStyle(by: .value("Tire", "RR"))
                        }
                    }
                    .frame(height: 240)

                    Text("Target \(String(format: "%.1f", TpmsSampleStore.targetBar)) bar · warn below \(String(format: "%.1f", TpmsSampleStore.lowBar)) or above \(String(format: "%.1f", TpmsSampleStore.highBar))")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    if let last = samples.last {
                        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                            tire("FL", last.fl)
                            tire("FR", last.fr)
                            tire("RL", last.rl)
                            tire("RR", last.rr)
                        }
                    }
                }
            }
            .padding()
        }
        .navigationTitle("TPMS Trend")
        .onAppear { reload() }
    }

    private func reload() {
        samples = TpmsSampleStore.samples(days: days)
    }

    private func tire(_ label: String, _ value: Double) -> some View {
        let warn = value > 0 && (value < TpmsSampleStore.lowBar || value > TpmsSampleStore.highBar)
        return VStack {
            Text(label).font(.caption).foregroundStyle(.secondary)
            Text(String(format: "%.2f", value))
                .font(MateFont.mono(.bold, size: 20))
                .foregroundStyle(warn ? MateColors.warning : .primary)
            Text("bar").font(.caption2).foregroundStyle(.tertiary)
        }
        .frame(maxWidth: .infinity)
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}
