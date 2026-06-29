import SwiftUI
import Charts

// MARK: - Chart Data Point
struct DriveDataPoint: Identifiable {
    let id = UUID()
    let minute: Double
    let speed: Double
    let power: Double
    let altitude: Double
    let insideTemp: Double
    let outsideTemp: Double
    let tireFL: Double
    let tireFR: Double
    let tireRL: Double
    let tireRR: Double
}

// MARK: - Drive Detail View
struct DriveDetailView: View {
    let drive: Drive

    @State private var selectedTab: DriveTab = .speed
    @State private var dataPoints: [DriveDataPoint] = []
    @State private var visibleRange: ClosedRange<Double> = 0...1

    private static let tabColors: [DriveTab: Color] = [
        .speed: Color(red: 0.231, green: 0.510, blue: 0.965),   // #3B82F6
        .power: Color(red: 0.961, green: 0.620, blue: 0.043),   // #F59E0B
        .altitude: Color(red: 0.063, green: 0.725, blue: 0.506),// #10B981
        .temp: Color(red: 0.937, green: 0.267, blue: 0.267),    // #EF4444
        .tires: Color(red: 0.545, green: 0.361, blue: 0.965),   // #8B5CF6
    ]

    enum DriveTab: String, CaseIterable {
        case speed, power, altitude, temp, tires

        var label: String {
            switch self {
            case .speed: return "Speed (km/h)"
            case .power: return "Power (kW)"
            case .altitude: return "Altitude (m)"
            case .temp: return "Temp (°C)"
            case .tires: return "Tire Pressure (bar)"
            }
        }

        var systemImage: String {
            switch self {
            case .speed: return "speedometer"
            case .power: return "bolt.fill"
            case .altitude: return "mountain.2.fill"
            case .temp: return "thermometer"
            case .tires: return "circle.dotted"
            }
        }
    }

    // MARK: - Body
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    headerCard
                    statsGrid
                    batteryBar
                    chartSection
                }
                .padding(.horizontal)
                .padding(.vertical, 8)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Drive Detail")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                dataPoints = generateDataPoints()
                visibleRange = 0...Double(drive.durationMin)
            }
        }
    }

    // MARK: - Header Card
    private var headerCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "car.fill")
                    .foregroundColor(.blue)
                    .font(.caption)
                Text("Drive")
                    .font(.caption.weight(.semibold))
                    .foregroundColor(.blue)
                Spacer()
            }

            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    Circle().fill(Color.blue).frame(width: 8, height: 8)
                    Text(drive.startAddress)
                        .font(.headline.weight(.semibold))
                }

                HStack(spacing: 8) {
                    Circle().fill(Color.red).frame(width: 8, height: 8)
                    Text(drive.endAddress)
                        .font(.headline.weight(.semibold))
                        .foregroundColor(.secondary)
                }
            }

            Divider()

            HStack(spacing: 16) {
                Label(formattedDate(drive.startDate), systemImage: "calendar")
                    .font(.caption)
                    .foregroundColor(.secondary)
                Label("\(drive.durationMin) min", systemImage: "clock")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
        .padding()
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    // MARK: - Stats Grid
    private var statsGrid: some View {
        let avgSpeed: Int = drive.durationMin > 0
            ? Int((drive.distanceKm / Double(drive.durationMin)) * 60)
            : 0
        let maxSpeed: Int = Int(Double(avgSpeed) * 1.5)

        let stats: [(label: String, value: String, icon: String, color: Color)] = [
            ("Distance", String(format: "%.1f km", drive.distanceKm), "road.lanes", .blue),
            ("Avg Speed", "\(avgSpeed) km/h", "speedometer", .orange),
            ("Max Speed", "\(maxSpeed) km/h", "gauge.open.with.lines.needle.67percent", .red),
            ("Energy", String(format: "%.1f kWh", drive.consumptionKwh), "bolt.fill", .green),
            ("Efficiency", "\(drive.efficiency) Wh/km", "leaf.fill", .purple),
        ]

        return LazyVGrid(
            columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 5),
            spacing: 8
        ) {
            ForEach(stats, id: \.label) { stat in
                VStack(spacing: 6) {
                    Image(systemName: stat.icon)
                        .font(.caption)
                        .foregroundColor(stat.color)
                    Text(stat.value)
                        .font(.system(size: 11, weight: .bold))
                        .minimumScaleFactor(0.65)
                        .lineLimit(1)
                    Text(stat.label)
                        .font(.system(size: 9))
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                }
                .padding(.vertical, 12)
                .padding(.horizontal, 4)
                .frame(maxWidth: .infinity)
                .background(.regularMaterial)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
        }
    }

    // MARK: - Battery Bar
    private var batteryBar: some View {
        let delta = drive.startBatteryLevel - drive.endBatteryLevel
        let usedFraction = Double(delta) / 100.0

        return HStack(spacing: 12) {
            // Icon
            Image(systemName: delta > 20 ? "battery.25" : "battery.75")
                .foregroundColor(delta > 20 ? .orange : .green)
                .font(.title3)

            VStack(alignment: .leading, spacing: 4) {
                Text("Battery Usage")
                    .font(.caption.weight(.medium))
                    .foregroundColor(.secondary)

                // Bar
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule()
                            .fill(Color(.systemGray5))
                            .frame(height: 10)

                        // Portion consumed
                        Capsule()
                            .fill(
                                LinearGradient(
                                    colors: [.green, delta > 20 ? .orange : .teal],
                                    startPoint: .leading,
                                    endPoint: .trailing
                                )
                            )
                            .frame(width: geo.size.width * CGFloat(usedFraction), height: 10)

                        // End slash marker (visual divider at end level)
                        Capsule()
                            .stroke(Color(.systemGray3), lineWidth: 1.5)
                            .frame(width: geo.size.width * CGFloat(1 - usedFraction), height: 10)
                            .offset(x: geo.size.width * CGFloat(usedFraction))
                    }
                }
                .frame(height: 10)
            }

            VStack(alignment: .trailing, spacing: 2) {
                Text("\(drive.startBatteryLevel)%")
                    .font(.subheadline.weight(.bold))
                    .foregroundColor(.green)
                Text("\(drive.endBatteryLevel)%")
                    .font(.caption.weight(.semibold))
                    .foregroundColor(.secondary)
            }

            Text("\u{2212}\(delta)%")
                .font(.caption.weight(.bold))
                .foregroundColor(delta > 0 ? .red : .green)
                .padding(.horizontal, 6)
                .padding(.vertical, 3)
                .background(delta > 0 ? Color.red.opacity(0.1) : Color.green.opacity(0.1))
                .clipShape(Capsule())
        }
        .padding()
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    // MARK: - Chart Section
    private var chartSection: some View {
        VStack(spacing: 14) {
            // Segmented Picker
            Picker("Chart", selection: $selectedTab) {
                ForEach(DriveTab.allCases, id: \.self) { tab in
                    Label(tab.label, systemImage: tab.systemImage)
                        .tag(tab)
                        .font(.caption2)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()

            // Chart
            chartContent
                .frame(height: 300)
                .chartXAxis {
                    AxisMarks(values: .automatic(desiredCount: 6)) { value in
                        if let mins = value.as(Double.self) {
                            AxisValueLabel("\(Int(mins)) min")
                            AxisGridLine()
                        }
                    }
                }
                .chartYAxis {
                    AxisMarks()
                }
                .chartLegend(position: .bottom, spacing: 8)
                .chartPlotStyle { plotArea in
                    plotArea
                        .background(Color(.systemGray6).opacity(0.3))
                        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                }
                // Zoom control (brush equivalent)
                .chartXVisibleDomain(
                    .init(min: visibleRange.lowerBound, max: visibleRange.upperBound)
                )

            // Brush / Zoom controls
            brushControls
        }
        .padding()
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    // MARK: - Brush Controls (zoom slider)
    private var brushControls: some View {
        let totalWidth = Double(drive.durationMin)
        let currentWidth = visibleRange.upperBound - visibleRange.lowerBound

        return VStack(spacing: 8) {
            // Zoom slider
            HStack(spacing: 12) {
                Image(systemName: "minus.magnifyingglass")
                    .font(.caption)
                    .foregroundColor(.secondary)

                Slider(
                    value: Binding(
                        get: { currentWidth / totalWidth },
                        set: { fraction in
                            let newWidth = max(3.0, totalWidth * fraction)
                            let center = (visibleRange.lowerBound + visibleRange.upperBound) / 2
                            let half = newWidth / 2
                            let lo = max(0, center - half)
                            let hi = min(totalWidth, center + half)
                            visibleRange = lo...hi
                        }
                    ),
                    in: 0.05...1.0
                )
                .tint(Self.tabColors[selectedTab] ?? .blue)

                Image(systemName: "plus.magnifyingglass")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            // Range labels
            HStack {
                Text("\(Int(visibleRange.lowerBound)) min")
                    .font(.caption2)
                    .foregroundColor(.secondary)
                Spacer()
                Text("\(Int(visibleRange.upperBound)) min")
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
        }
        .padding(.horizontal, 4)
    }

    // MARK: - Chart Content (per tab)
    @ViewBuilder
    private var chartContent: some View {
        switch selectedTab {
        case .speed:
            speedChart
        case .power:
            powerChart
        case .altitude:
            altitudeChart
        case .temp:
            tempChart
        case .tires:
            tiresChart
        }
    }

    // MARK: Speed Chart
    private var speedChart: some View {
        Chart(dataPoints) { dp in
            LineMark(
                x: .value("Time", dp.minute),
                y: .value("Speed", dp.speed)
            )
            .foregroundStyle(Self.tabColors[.speed] ?? .blue)
            .interpolationMethod(.catmullRom)
            .lineStyle(StrokeStyle(lineWidth: 2))

            AreaMark(
                x: .value("Time", dp.minute),
                y: .value("Speed", dp.speed)
            )
            .foregroundStyle(
                LinearGradient(
                    colors: [(Self.tabColors[.speed] ?? .blue).opacity(0.15), .clear],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
            .interpolationMethod(.catmullRom)
        }
    }

    // MARK: Power Chart
    private var powerChart: some View {
        Chart(dataPoints) { dp in
            LineMark(
                x: .value("Time", dp.minute),
                y: .value("Power", dp.power)
            )
            .foregroundStyle(Self.tabColors[.power] ?? .orange)
            .interpolationMethod(.catmullRom)
            .lineStyle(StrokeStyle(lineWidth: 2))

            AreaMark(
                x: .value("Time", dp.minute),
                y: .value("Power", dp.power)
            )
            .foregroundStyle(
                LinearGradient(
                    colors: [(Self.tabColors[.power] ?? .orange).opacity(0.15), .clear],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
            .interpolationMethod(.catmullRom)
        }
    }

    // MARK: Altitude Chart
    private var altitudeChart: some View {
        Chart(dataPoints) { dp in
            LineMark(
                x: .value("Time", dp.minute),
                y: .value("Altitude", dp.altitude)
            )
            .foregroundStyle(Self.tabColors[.altitude] ?? .green)
            .interpolationMethod(.catmullRom)
            .lineStyle(StrokeStyle(lineWidth: 2))

            AreaMark(
                x: .value("Time", dp.minute),
                y: .value("Altitude", dp.altitude)
            )
            .foregroundStyle(
                LinearGradient(
                    colors: [(Self.tabColors[.altitude] ?? .green).opacity(0.15), .clear],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
            .interpolationMethod(.catmullRom)
        }
    }

    // MARK: Temperature Chart
    private var tempChart: some View {
        Chart(dataPoints) { dp in
            LineMark(
                x: .value("Time", dp.minute),
                y: .value("Inside", dp.insideTemp)
            )
            .foregroundStyle(Self.tabColors[.temp] ?? .red)
            .interpolationMethod(.catmullRom)
            .lineStyle(StrokeStyle(lineWidth: 2))

            LineMark(
                x: .value("Time", dp.minute),
                y: .value("Outside", dp.outsideTemp)
            )
            .foregroundStyle(.orange)
            .interpolationMethod(.catmullRom)
            .lineStyle(StrokeStyle(lineWidth: 2))
        }
    }

    // MARK: Tires Pressure Chart
    private var tiresChart: some View {
        Chart(dataPoints) { dp in
            LineMark(
                x: .value("Time", dp.minute),
                y: .value("Front Left", dp.tireFL)
            )
            .foregroundStyle(.blue)
            .interpolationMethod(.catmullRom)
            .lineStyle(StrokeStyle(lineWidth: 1.5))

            LineMark(
                x: .value("Time", dp.minute),
                y: .value("Front Right", dp.tireFR)
            )
            .foregroundStyle(.red)
            .interpolationMethod(.catmullRom)
            .lineStyle(StrokeStyle(lineWidth: 1.5))

            LineMark(
                x: .value("Time", dp.minute),
                y: .value("Rear Left", dp.tireRL)
            )
            .foregroundStyle(.green)
            .interpolationMethod(.catmullRom)
            .lineStyle(StrokeStyle(lineWidth: 1.5))

            LineMark(
                x: .value("Time", dp.minute),
                y: .value("Rear Right", dp.tireRR)
            )
            .foregroundStyle(.orange)
            .interpolationMethod(.catmullRom)
            .lineStyle(StrokeStyle(lineWidth: 1.5))
        }
    }

    // MARK: - Data Generation
    private func generateDataPoints() -> [DriveDataPoint] {
        let n = 30
        let avgSpeed = drive.durationMin > 0
            ? drive.distanceKm / Double(drive.durationMin) * 60
            : 0

        return (0..<n).map { i in
            let t = Double(i) * Double(drive.durationMin) / Double(n)

            // Realistic curves using sin/cos with different phases
            let speed = max(0, avgSpeed + sin(Double(i) * 0.5) * avgSpeed * 0.4)
            let power = max(5, 20 + sin(Double(i) * 0.3) * 60 + cos(Double(i) * 0.7) * 20)
            let altitude = 50 + sin(Double(i) * 0.2) * 30 + cos(Double(i) * 0.15) * 20
            let insideTemp = 22 + sin(Double(i) * 0.1) * 3
            let outsideTemp = drive.outsideTempAvg + sin(Double(i) * 0.05) * 0.5
            let fl = 2.4 + sin(Double(i) * 0.3) * 0.05 + Double.random(in: 0...0.02)
            let fr = 2.5 + sin(Double(i) * 0.3 + 1) * 0.05 + Double.random(in: 0...0.02)
            let rl = 2.4 + sin(Double(i) * 0.3 + 2) * 0.05 + Double.random(in: 0...0.02)
            let rr = 2.5 + sin(Double(i) * 0.3 + 3) * 0.05 + Double.random(in: 0...0.02)

            return DriveDataPoint(
                minute: t,
                speed: speed,
                power: power,
                altitude: altitude,
                insideTemp: insideTemp,
                outsideTemp: outsideTemp,
                tireFL: fl,
                tireFR: fr,
                tireRL: rl,
                tireRR: rr
            )
        }
    }

    // MARK: - Date Formatting
    private func formattedDate(_ isoString: String) -> String {
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = iso.date(from: isoString) {
            let fmt = DateFormatter()
            fmt.dateStyle = .medium
            fmt.timeStyle = .short
            fmt.locale = Locale.autoupdatingCurrent
            return fmt.string(from: date)
        }
        // Try without fractional seconds
        iso.formatOptions = [.withInternetDateTime]
        if let date = iso.date(from: isoString) {
            let fmt = DateFormatter()
            fmt.dateStyle = .medium
            fmt.timeStyle = .short
            fmt.locale = Locale.autoupdatingCurrent
            return fmt.string(from: date)
        }
        return isoString
    }
}

// MARK: - Preview
#Preview {
    DriveDetailView(
        drive: Drive(
            id: 1, carId: 1,
            startDate: "2025-06-22T10:30:00.000Z",
            endDate: "2025-06-22T11:15:00.000Z",
            startAddress: "Home",
            endAddress: "Office",
            distanceKm: 35.2,
            durationMin: 45,
            efficiency: 185,
            startBatteryLevel: 85,
            endBatteryLevel: 72,
            outsideTempAvg: 22.5
        )
    )
}
