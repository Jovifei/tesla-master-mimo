import SwiftUI
import Charts

// MARK: - Chart Data Model
struct ChargeSample: Identifiable {
    let id = UUID()
    let minute: Int
    let power: Double
    let voltage: Double
    let temperature: Double
}

// MARK: - Charge Detail View
struct ChargeDetailView: View {
    let charge: Charge
    @EnvironmentObject var state: AppState

    @State private var selectedTab: ChartTab = .power
    @State private var visibleRange: ClosedRange<Int> = 0...29
    @State private var isZoomed: Bool = false

    private let samples: [ChargeSample]
    private let sampleCount = 30

    enum ChartTab: String, CaseIterable {
        case power, voltage, temp

        var label: String {
            switch self {
            case .power: return "Power (kW)"
            case .voltage: return "Voltage (V)"
            case .temp: return "Temp (°C)"
            }
        }

        var color: Color {
            switch self {
            case .power: return .orange
            case .voltage: return .blue
            case .temp: return .red
            }
        }
    }

    init(charge: Charge) {
        self.charge = charge
        let duration = ChargeDetailView.computeDurationMinutes(charge: charge) ?? 45
        let isDC = charge.chargeType == "DC"
        var result: [ChargeSample] = []
        for i in 0..<30 {
            let minute = Int(round(Double(duration) * Double(i) / 29.0))
            let power: Double
            let voltage: Double
            if isDC {
                power = 50 + sin(Double(i) * 0.4) * 80 + Double.random(in: -7...7)
                voltage = 380 + sin(Double(i) * 0.2) * 20 + Double.random(in: -5...5)
            } else {
                power = 8 + sin(Double(i) * 0.5) * 3 + Double.random(in: -1...1)
                voltage = 230 + sin(Double(i) * 0.3) * 5 + Double.random(in: -1.5...1.5)
            }
            let temp = 32 + sin(Double(i) * 0.3) * 5 + Double.random(in: -1.5...1.5)
            result.append(ChargeSample(minute: minute, power: power, voltage: voltage, temperature: temp))
        }
        self.samples = result
    }

    // MARK: - Computed Properties

    private var isDC: Bool { charge.chargeType == "DC" }

    private var durationMinutes: Int? {
        ChargeDetailView.computeDurationMinutes(charge: charge)
    }

    private static func computeDurationMinutes(charge: Charge) -> Int? {
        guard let end = charge.endDate else { return nil }
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let s = iso.date(from: charge.startDate), let e = iso.date(from: end) {
            return Int(e.timeIntervalSince(s) / 60)
        }
        iso.formatOptions = [.withInternetDateTime]
        if let s = iso.date(from: charge.startDate), let e = iso.date(from: end) {
            return Int(e.timeIntervalSince(s) / 60)
        }
        return nil
    }

    private var efficiency: Double {
        guard charge.chargeEnergyUsed > 0 else { return 100 }
        return (charge.chargeEnergyAdded / charge.chargeEnergyUsed) * 100
    }

    private var visibleSamples: [ChargeSample] {
        let clampedLower = max(visibleRange.lowerBound, 0)
        let clampedUpper = min(visibleRange.upperBound, samples.count - 1)
        guard clampedLower <= clampedUpper else { return samples }
        return Array(samples[clampedLower...clampedUpper])
    }

    // MARK: - Body

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                headerCard
                statsGrid
                if isDC {
                    dcInfoCard
                }
                chartSection
                    .id(selectedTab.rawValue)
            }
            .padding(.horizontal)
            .padding(.vertical, 8)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle("Charge Detail")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - Header Card

    private var headerCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: isDC ? "bolt.fill" : "powerplug.fill")
                    .font(.title3)
                    .foregroundColor(isDC ? .orange : .blue)

                Text(charge.address)
                    .font(.headline)
                    .fontWeight(.bold)
                    .lineLimit(2)

                Spacer()

                BadgeView(text: isDC ? "DC FAST" : "AC", color: isDC ? .orange : .blue)
            }

            HStack(spacing: 4) {
                Image(systemName: "clock")
                    .font(.caption2)
                Text(durationText)
            }
            .font(.caption)
            .foregroundColor(.secondary)
        }
        .padding()
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var durationText: String {
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let startDate: Date = iso.date(from: charge.startDate)
            ?? ({ iso.formatOptions = [.withInternetDateTime]; return iso.date(from: charge.startDate) }())
            ?? Date()
        let start = startDate.formatted(date: .abbreviated, time: .shortened)
        guard let endStr = charge.endDate,
              let endDate: Date = iso.date(from: endStr)
                ?? ({ iso.formatOptions = [.withInternetDateTime]; return iso.date(from: endStr) }())
        else {
            return "\(start)  ·  In progress..."
        }
        let end = endDate.formatted(date: .abbreviated, time: .shortened)
        if let mins = durationMinutes {
            return "\(start)  —  \(end)  ·  \(mins) min"
        }
        return "\(start)  —  \(end)"
    }

    // MARK: - Stats Grid

    private var statsGrid: some View {
        LazyVGrid(columns: [
            GridItem(.flexible(), spacing: 8),
            GridItem(.flexible(), spacing: 8)
        ], spacing: 8) {
            StatCardView(title: "Energy Added", value: "\(charge.chargeEnergyAdded, specifier: "%.1f") kWh")
            StatCardView(title: "Cost", value: charge.cost > 0 ? "¥\(charge.cost, specifier: "%.2f")" : "Free")
            StatCardView(title: "Efficiency", value: "\(efficiency, specifier: "%.1f")%")
            StatCardView(title: "Battery", value: "\(charge.startBatteryLevel)% → \(charge.endBatteryLevel.map(String.init) ?? "?")%")
        }
    }

    // MARK: - DC Info Card

    private var dcInfoCard: some View {
        HStack(spacing: 12) {
            Image(systemName: "bolt.car.fill")
                .font(.title3)
                .foregroundColor(.orange)

            VStack(alignment: .leading, spacing: 2) {
                Text("DC Fast Charging")
                    .font(.subheadline)
                    .fontWeight(.semibold)
                if let brand = charge.fastChargerBrand {
                    Text("\(brand)\(charge.fastChargerType.map { " · \($0)" } ?? "")")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }

            Spacer()
        }
        .padding()
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    // MARK: - Chart Section

    private var chartSection: some View {
        VStack(spacing: 12) {
            Picker("Chart Metric", selection: $selectedTab) {
                ForEach(ChartTab.allCases, id: \.self) { tab in
                    Text(tab.label).tag(tab)
                }
            }
            .pickerStyle(.segmented)

            chartContent
                .frame(height: 300)
                .chartYScale(domain: yDomain)

            if isZoomed {
                Button {
                    withAnimation(.easeInOut(duration: 0.25)) {
                        resetZoom()
                    }
                } label: {
                    Label("Reset Zoom", systemImage: "arrow.counterclockwise")
                        .font(.caption)
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .tint(selectedTab.color)
                .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
        }
        .padding()
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .animation(.easeInOut(duration: 0.25), value: isZoomed)
    }

    // MARK: - Chart Content

    private var chartContent: some View {
        Chart {
            ForEach(visibleSamples) { sample in
                LineMark(
                    x: .value("Time", sample.minute),
                    y: .value(selectedTab.label, value(for: sample))
                )
                .foregroundStyle(selectedTab.color.gradient)
                .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                .interpolationMethod(.catmullRom)
            }
        }
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 6)) { value in
                AxisValueLabel {
                    if let minute = value.as(Int.self) {
                        Text("\(minute) min")
                            .font(.caption2)
                    }
                }
                AxisGridLine()
            }
        }
        .chartYAxis {
            AxisMarks(position: .leading) { value in
                AxisValueLabel {
                    if let v = value.as(Double.self) {
                        Text(v, format: .number.precision(.fractionLength(0)))
                            .font(.caption2)
                    }
                }
                AxisGridLine()
            }
        }
        .chartOverlay { _ in
            GeometryReader { geo in
                Rectangle()
                    .fill(.clear)
                    .contentShape(Rectangle())
                    .gesture(
                        DragGesture(minimumDistance: 10)
                            .onEnded { drag in
                                let totalWidth = geo.size.width
                                let x1 = min(drag.startLocation.x, drag.location.x)
                                let x2 = max(drag.startLocation.x, drag.location.x)
                                let fraction1 = max(0, x1 / totalWidth)
                                let fraction2 = min(1, x2 / totalWidth)
                                let lower = Int(fraction1 * CGFloat(sampleCount - 1))
                                let upper = Int(fraction2 * CGFloat(sampleCount - 1))
                                withAnimation(.easeInOut(duration: 0.25)) {
                                    visibleRange = lower...upper
                                    isZoomed = lower > 0 || upper < sampleCount - 1
                                }
                            }
                    )
                    .onTapGesture(count: 2) {
                        if isZoomed {
                            withAnimation(.easeInOut(duration: 0.25)) {
                                resetZoom()
                            }
                        }
                    }
            }
        }
        .chartPlotStyle { plotArea in
            plotArea
                .background(.ultraThinMaterial.opacity(0.3))
        }
    }

    // MARK: - Helpers

    private func value(for sample: ChargeSample) -> Double {
        switch selectedTab {
        case .power:  return sample.power
        case .voltage: return sample.voltage
        case .temp:   return sample.temperature
        }
    }

    private var yDomain: ClosedRange<Double> {
        let vals = visibleSamples.map(value(for:))
        guard let min = vals.min(), let max = vals.max(), min < max else {
            return 0...100
        }
        let padding = (max - min) * 0.12
        return (min - padding)...(max + padding)
    }

    private func resetZoom() {
        visibleRange = 0...(sampleCount - 1)
        isZoomed = false
    }
}

// MARK: - Helper Views

struct BadgeView: View {
    let text: String
    let color: Color

    var body: some View {
        Text(text)
            .font(.caption2)
            .fontWeight(.semibold)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(color.opacity(0.18))
            .foregroundColor(color)
            .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
    }
}

struct StatCardView: View {
    let title: String
    let value: String

    var body: some View {
        VStack(spacing: 5) {
            Text(title)
                .font(.caption2)
                .foregroundColor(.secondary)
            Text(value)
                .font(.subheadline)
                .fontWeight(.bold)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 14)
        .padding(.horizontal, 6)
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

// MARK: - Preview

#Preview {
    let sample = Charge(
        id: 1, carId: 1,
        startDate: "2025-06-22T14:30:00.000Z",
        endDate: "2025-06-22T15:15:00.000Z",
        chargeEnergyAdded: 32.5,
        chargeEnergyUsed: 36.2,
        startBatteryLevel: 15,
        endBatteryLevel: 72,
        startIdealRangeKm: 45,
        endIdealRangeKm: 215,
        cost: 48.50,
        chargeType: "DC",
        address: "Tesla Supercharger, Shanghai",
        fastChargerBrand: "Tesla",
        fastChargerType: "V3"
    )
    let appState = AppState()
    NavigationStack {
        ChargeDetailView(charge: sample)
            .environmentObject(appState)
            .onAppear { appState.loadCars() }
    }
}
