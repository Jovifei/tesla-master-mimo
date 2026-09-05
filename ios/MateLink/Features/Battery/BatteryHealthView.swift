import SwiftUI
import Charts

struct BatteryHealthView: View {
    @EnvironmentObject var state: AppState
    @Environment(\.carPalette) private var palette
    @State private var data: BatteryHealth?
    @State private var loading = true
    @State private var loadError: String?

    var body: some View {
        ScrollView {
            if loading {
                ProgressView("Loading…")
                    .frame(maxWidth: .infinity, minHeight: 300)
            } else if let loadError {
                EmptyStateView("Battery Health Unavailable",
                               systemImage: "exclamationmark.triangle",
                               message: loadError)
                    .padding(40)
            } else if let d = data {
                VStack(spacing: 16) {
                    healthGauge(d)
                    capacityCard(d)
                    rangeCard(d)
                    if let history = d.history, !history.isEmpty {
                        trendChart(history)
                    }
                }
                .padding()
            }
        }
        .navigationTitle("Battery Health")
        .task { await load() }
    }

    // MARK: - Health Gauge

    private func healthGauge(_ d: BatteryHealth) -> some View {
        let healthPercent = 100 - (d.capacityDegradationPercent ?? 0)
        let healthColor: Color = {
            if healthPercent >= 90 { return MateColors.success }
            if healthPercent >= 75 { return MateColors.warning }
            return MateColors.error
        }()

        return VStack(spacing: 12) {
            ZStack {
                Circle()
                    .stroke(Color(.systemGray5), lineWidth: 12)
                    .frame(width: 140, height: 140)
                Circle()
                    .trim(from: 0, to: healthPercent / 100)
                    .stroke(healthColor, style: StrokeStyle(lineWidth: 12, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .frame(width: 140, height: 140)
                    .animation(MateAnimation.defaultSpring, value: healthPercent)

                VStack(spacing: 2) {
                    Text("\(Int(healthPercent))")
                        .font(MateFont.mono(.bold, size: 40))
                        .monospacedDigit()
                    Text("%")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text("Health")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
            }

            Text(healthStatusLabel(d.capacityDegradationPercent ?? 0))
                .font(.headline)
                .foregroundStyle(healthColor)

            Text("\(Int(d.mileageKm)) km driven")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    // MARK: - Capacity Card

    private func capacityCard(_ d: BatteryHealth) -> some View {
        HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Original")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(String(format: "%.1f kWh", d.originalCapacityKwh ?? 0))
                    .font(MateFont.mono(.bold, size: 20))
                    .monospacedDigit()
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Image(systemName: "arrow.right")
                .foregroundStyle(.tertiary)

            VStack(alignment: .trailing, spacing: 4) {
                Text("Current")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                HStack(alignment: .lastTextBaseline, spacing: 4) {
                    Text(String(format: "%.1f kWh", d.currentCapacityKwh ?? 0))
                        .font(MateFont.mono(.bold, size: 20))
                        .monospacedDigit()
                    Text(String(format: "−%.1f%%", d.capacityDegradationPercent ?? 0))
                        .font(.caption)
                        .foregroundStyle(MateColors.error)
                }
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    // MARK: - Range Card

    private func rangeCard(_ d: BatteryHealth) -> some View {
        HStack(spacing: 20) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Rated Range")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                HStack(alignment: .lastTextBaseline, spacing: 2) {
                    Text("\(Int(d.ratedRangeKm))")
                        .font(MateFont.mono(.bold, size: 24))
                        .monospacedDigit()
                    Text("km")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Divider().frame(height: 40)

            VStack(alignment: .leading, spacing: 4) {
                Text("Ideal Range")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                HStack(alignment: .lastTextBaseline, spacing: 2) {
                    Text("\(Int(d.idealRangeKm))")
                        .font(MateFont.mono(.bold, size: 24))
                        .monospacedDigit()
                    Text("km")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    // MARK: - Trend Chart

    private func trendChart(_ history: [BatteryHealthPoint]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Degradation Trend")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.secondary)

            Chart(history, id: \.date) { point in
                LineMark(
                    x: .value("Date", point.date),
                    y: .value("kWh", point.capacityKwh)
                )
                .foregroundStyle(palette.accent)
                .interpolationMethod(.catmullRom)

                PointMark(
                    x: .value("Date", point.date),
                    y: .value("kWh", point.capacityKwh)
                )
                .foregroundStyle(palette.accent)
                .symbolSize(30)
            }
            .frame(height: 200)
            .chartYAxis {
                AxisMarks()
            }
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    // MARK: - Helpers

    private func healthStatusLabel(_ degradation: Double) -> String {
        let health = 100 - degradation
        if health >= 90 { return "Excellent" }
        if health >= 80 { return "Good" }
        if health >= 70 { return "Fair" }
        return "Degraded"
    }

    // MARK: - Data Loading

    func load() async {
        loading = true
        loadError = nil
        data = nil
        if state.isMockMode {
            data = await state.mock.getBatteryHealth(state.currentCarId)
        } else if let api = state.real {
            do {
                data = try await api.getBatteryHealth(state.currentCarId)
            } catch {
                loadError = error.localizedDescription
            }
        }
        loading = false
    }
}
