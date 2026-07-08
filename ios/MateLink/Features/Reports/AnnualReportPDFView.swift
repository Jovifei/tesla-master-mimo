import SwiftUI
import UIKit
import Charts

// MARK: - Annual Report PDF View

struct AnnualReportPDFView: View {
    @EnvironmentObject var state: AppState
    @State private var drives: [Drive] = []
    @State private var charges: [Charge] = []
    @State private var loading = true
    @State private var selectedYear: Int = Calendar.current.component(.year, from: Date())
    @State private var pdfURL: URL?
    @State private var showShare = false
    @State private var showError = false
    @State private var errorMessage = ""
    @State private var loadError: String?

    private let calendar = Calendar.current

    private static let isoFormatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    private var availableYears: [Int] {
        var years = Set<Int>()
        for d in drives {
            if let date = parseDate(d.startDate) {
                years.insert(calendar.component(.year, from: date))
            }
        }
        for c in charges {
            if let date = parseDate(c.startDate) {
                years.insert(calendar.component(.year, from: date))
            }
        }
        return years.sorted(by: >)
    }

    private var yearDrives: [Drive] {
        drives.filter { d in
            guard let date = parseDate(d.startDate) else { return false }
            return calendar.component(.year, from: date) == selectedYear
        }
    }

    private var yearCharges: [Charge] {
        charges.filter { c in
            guard let date = parseDate(c.startDate) else { return false }
            return calendar.component(.year, from: date) == selectedYear
        }
    }

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading report data...")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let loadError {
                EmptyStateView(
                    "Annual Report Unavailable",
                    systemImage: "exclamationmark.triangle",
                    message: loadError
                )
            } else if yearDrives.isEmpty && yearCharges.isEmpty {
                EmptyStateView(
                    "No Data for \(selectedYear)",
                    systemImage: "doc.richtext",
                    message: "Drive and charge data is needed to generate a report."
                )
            } else {
                ScrollView {
                    VStack(spacing: 16) {
                        // Year picker
                        if availableYears.count > 1 {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 8) {
                                    ForEach(availableYears, id: \.self) { year in
                                        Button {
                                            selectedYear = year
                                        } label: {
                                            Text("\(year)")
                                                .font(.subheadline.weight(.semibold))
                                                .padding(.horizontal, 16)
                                                .padding(.vertical, 8)
                                                .background(selectedYear == year ? Color.blue : Color(.systemGray5))
                                                .foregroundColor(selectedYear == year ? .white : .primary)
                                                .clipShape(Capsule())
                                        }
                                    }
                                }
                                .padding(.horizontal)
                            }
                        }

                        // Preview summary
                        summaryCard

                        // Generate button
                        Button {
                            generateAndShare()
                        } label: {
                            Label("Generate & Share PDF", systemImage: "square.and.arrow.up")
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                                .padding()
                        }
                        .buttonStyle(.borderedProminent)
                        .padding(.horizontal)

                        Spacer(minLength: 32)
                    }
                    .padding(.vertical)
                }
            }
        }
        .navigationTitle("Annual Report \(selectedYear)")
        .navigationBarTitleDisplayMode(.inline)
        .background(Color(.systemGroupedBackground))
        .task { await load() }
        .sheet(isPresented: $showShare) {
            if let url = pdfURL {
                ShareSheet(activityItems: [url])
            }
        }
        .alert("PDF Generation Failed", isPresented: $showError) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage)
        }
    }

    // MARK: - Summary Card

    private var summaryCard: some View {
        let stats = computeStats(drives: yearDrives)

        return VStack(alignment: .leading, spacing: 12) {
            Text("Annual Report \(selectedYear)")
                .font(.title2.weight(.bold))

            Text("Preview of your driving and charging data.")
                .font(.subheadline)
                .foregroundColor(.secondary)

            Divider()

            HStack(spacing: 24) {
                statItem(value: "\(Int(stats.totalKm))", unit: "km")
                Divider().frame(height: 32)
                statItem(value: "\(stats.driveCount)", unit: "drives")
                Divider().frame(height: 32)
                statItem(value: String(format: "%.1f", stats.totalKwh), unit: "kWh")
            }
            .frame(maxWidth: .infinity)

            HStack(spacing: 24) {
                statItem(value: "\(yearCharges.count)", unit: "charges")
                Divider().frame(height: 32)
                statItem(value: String(format: "%.1f", chargeEnergyAdded), unit: "kWh added")
                Divider().frame(height: 32)
                statItem(value: String(format: "%.0f", stats.avgEfficiency), unit: "Wh/km")
            }
            .frame(maxWidth: .infinity)

            HStack(spacing: 24) {
                statItem(value: String(format: "%.2f", totalCost), unit: "cost")
                Divider().frame(height: 32)
                let ac = acChargeCount; let dc = dcChargeCount
                statItem(value: "\(ac)/\(dc)", unit: "AC/DC")
                Divider().frame(height: 32)
                statItem(value: String(format: "%.0f", avgChargeDuration), unit: "avg min")
            }
            .frame(maxWidth: .infinity)
        }
        .padding()
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(.horizontal)
    }

    private var chargeEnergyAdded: Double {
        yearCharges.reduce(0) { $0 + $1.chargeEnergyAdded }
    }

    private var totalCost: Double {
        yearCharges.reduce(0) { $0 + ($1.cost ?? 0) }
    }

    private var acChargeCount: Int {
        yearCharges.filter { $0.chargingType.uppercased() == "AC" }.count
    }

    private var dcChargeCount: Int {
        yearCharges.filter { $0.chargingType.uppercased() == "DC" }.count
    }

    private var avgChargeDuration: Double {
        yearCharges.isEmpty ? 0 : yearCharges.reduce(0) { $0 + Double($1.durationMin) } / Double(yearCharges.count)
    }

    private var mostCommonChargeHour: Int? {
        var hourCounts = [Int: Int]()
        for c in yearCharges {
            guard let date = parseDate(c.startDate) else { continue }
            let hour = calendar.component(.hour, from: date)
            hourCounts[hour, default: 0] += 1
        }
        return hourCounts.max(by: { $0.value < $1.value })?.key
    }

    private func statItem(value: String, unit: String) -> some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.headline.weight(.bold))
            Text(unit)
                .font(.caption2)
                .foregroundColor(.secondary)
        }
    }

    // MARK: - Data Loading

    private func load() async {
        loading = true
        loadError = nil
        if state.isMockMode {
            drives = await state.mock.getDrives(state.currentCarId)
            charges = await state.mock.getCharges(state.currentCarId)
        } else if let api = state.real {
            do {
                drives = try await api.fetch("/api/v1/cars/\(state.currentCarId)/drives")
                charges = try await api.fetch("/api/v1/cars/\(state.currentCarId)/charges")
            } catch {
                drives = []
                charges = []
                loadError = "Unable to load real report data: \(error.localizedDescription)"
            }
        } else {
            drives = []
            charges = []
            loadError = "No TeslaMate instance is configured."
        }
        if availableYears.isEmpty {
            // no data
        } else if !availableYears.contains(selectedYear) {
            selectedYear = availableYears.first ?? selectedYear
        }
        loading = false
    }

    // MARK: - Stats Computation

    private struct AnnualStats {
        let totalKm: Double
        let driveCount: Int
        let totalKwh: Double
        let avgEfficiency: Double
        let avgDriveMinutes: Double
        let maxSpeed: Double
        let longestDriveKm: Double
        let longestDriveDate: String
        let fastestDriveSpeed: Double
        let fastestDriveDate: String
        let drivingDays: Int
    }

    private func computeStats(drives: [Drive]) -> AnnualStats {
        let totalKm = drives.reduce(0) { $0 + $1.distanceKm }
        let totalKwh = drives.reduce(0) { $0 + $1.consumptionKwh }
        let avgEff = drives.isEmpty ? 0 : drives.reduce(0) { $0 + $1.efficiency } / Double(drives.count)
        let avgMin = drives.isEmpty ? 0 : drives.reduce(0) { $0 + Double($1.durationMin) } / Double(drives.count)
        let maxSpd = drives.map(\.speedMax).max() ?? 0

        let longest = drives.max(by: { $0.distanceKm < $1.distanceKm })
        let fastest = drives.max(by: { $0.speedMax < $1.speedMax })

        var uniqueDays = Set<String>()
        for d in drives {
            uniqueDays.insert(String(d.startDate.prefix(10)))
        }

        return AnnualStats(
            totalKm: totalKm,
            driveCount: drives.count,
            totalKwh: totalKwh,
            avgEfficiency: avgEff,
            avgDriveMinutes: avgMin,
            maxSpeed: maxSpd,
            longestDriveKm: longest?.distanceKm ?? 0,
            longestDriveDate: formatDate(longest?.startDate),
            fastestDriveSpeed: fastest?.speedMax ?? 0,
            fastestDriveDate: formatDate(fastest?.startDate),
            drivingDays: uniqueDays.count
        )
    }

    // MARK: - Monthly Aggregation

    private struct MonthData {
        let month: Int
        let label: String
        let distance: Double
        let energy: Double
        let driveCount: Int
        let chargeEnergy: Double
    }

    private func aggregateMonthly() -> [MonthData] {
        let monthNames = ["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"]

        return (1...12).map { month in
            let md = yearDrives.filter { d in
                guard let date = parseDate(d.startDate) else { return false }
                return calendar.component(.month, from: date) == month
            }
            let mc = yearCharges.filter { c in
                guard let date = parseDate(c.startDate) else { return false }
                return calendar.component(.month, from: date) == month
            }
            return MonthData(
                month: month,
                label: monthNames[month - 1],
                distance: md.reduce(0) { $0 + $1.distanceKm },
                energy: md.reduce(0) { $0 + $1.consumptionKwh },
                driveCount: md.count,
                chargeEnergy: mc.reduce(0) { $0 + $1.chargeEnergyAdded }
            )
        }
    }

    // MARK: - PDF Generation & Sharing

    private func generateAndShare() {
        let stats = computeStats(drives: yearDrives)
        let monthly = aggregateMonthly()
        let year = selectedYear

        Task.detached {
            // Clean up previous PDF for this year before generating a new one
            let fileName = "matelink_annual_report_\(year).pdf"
            let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)
            if FileManager.default.fileExists(atPath: tempURL.path) {
                try? FileManager.default.removeItem(at: tempURL)
            }

            let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 595, height: 842))

            do {
                try renderer.writePDF(to: tempURL) { context in
                let pageRect = CGRect(x: 0, y: 0, width: 595, height: 842)
                var y: CGFloat = 50
                let margin: CGFloat = 50
                let contentWidth = pageRect.width - 2 * margin

                func newPage() {
                    context.beginPage()
                    y = margin
                }

                func drawText(_ text: String, font: UIFont, color: UIColor = .black, spacing: CGFloat = 20) -> Bool {
                    if y + spacing > pageRect.height - margin { newPage() }
                    let attrs: [NSAttributedString.Key: Any] = [
                        .font: font,
                        .foregroundColor: color
                    ]
                    let nsStr = text as NSString
                    nsStr.draw(at: CGPoint(x: margin, y: y), withAttributes: attrs)
                    y += spacing
                    return true
                }

                func drawDivider(spacing: CGFloat = 12) {
                    if y + spacing > pageRect.height - margin { newPage() }
                    let path = UIBezierPath()
                    path.move(to: CGPoint(x: margin, y: y + 4))
                    path.addLine(to: CGPoint(x: pageRect.width - margin, y: y + 4))
                    UIColor.lightGray.setStroke()
                    path.lineWidth = 0.5
                    path.stroke()
                    y += spacing
                }

                func drawBarChart(values: [CGFloat], labels: [String], chartHeight: CGFloat = 100) {
                    let totalHeight = chartHeight + 24
                    if y + totalHeight > pageRect.height - margin { newPage() }

                    let maxVal = values.max() ?? 1
                    guard maxVal > 0 else { y += totalHeight; return }

                    let barWidth = contentWidth / CGFloat(values.count * 2)
                    let baseY = y + chartHeight

                    for (i, v) in values.enumerated() {
                        let bh = (v / maxVal) * chartHeight
                        let x = margin + CGFloat(i) * (contentWidth / CGFloat(values.count)) + barWidth / 2
                        let rect = CGRect(x: x, y: baseY - bh, width: barWidth, height: bh)
                        UIColor(red: 0.118, green: 0.533, blue: 0.898, alpha: 1).setFill()
                        UIBezierPath(roundedRect: rect, cornerRadius: 2).fill()
                    }
                    y = baseY + 4

                    let labelFont = UIFont.systemFont(ofSize: 8)
                    let step = contentWidth / CGFloat(values.count)
                    for (i, l) in labels.enumerated() {
                        let attrs: [NSAttributedString.Key: Any] = [
                            .font: labelFont,
                            .foregroundColor: UIColor.gray
                        ]
                        let nsStr = l as NSString
                        let w = nsStr.size(withAttributes: attrs).width
                        nsStr.draw(at: CGPoint(x: margin + CGFloat(i) * step + step / 2 - w / 2, y: y), withAttributes: attrs)
                    }
                    y += 16
                }

                // === Page 1: Title + Summary ===
                newPage()

                _ = drawText("Annual Report \(selectedYear)",
                             font: UIFont.boldSystemFont(ofSize: 28), spacing: 36)
                _ = drawText("MateLink Vehicle Summary",
                             font: UIFont.systemFont(ofSize: 10),
                             color: .gray, spacing: 18)
                drawDivider(spacing: 20)

                // Summary stats
                _ = drawText("Annual Summary",
                             font: UIFont.boldSystemFont(ofSize: 18), spacing: 28)
                _ = drawText("Total Distance: \(String(format: "%,.0f", stats.totalKm)) km",
                             font: UIFont.systemFont(ofSize: 12))
                _ = drawText("Total Drives: \(stats.driveCount)",
                             font: UIFont.systemFont(ofSize: 12))
                _ = drawText("Energy Used: \(String(format: "%,.1f", stats.totalKwh)) kWh",
                             font: UIFont.systemFont(ofSize: 12))
                _ = drawText("Avg Efficiency: \(String(format: "%.0f", stats.avgEfficiency)) Wh/km",
                             font: UIFont.systemFont(ofSize: 12))
                _ = drawText("Charges: \(yearCharges.count)",
                             font: UIFont.systemFont(ofSize: 12))
                _ = drawText("Energy Added: \(String(format: "%,.1f", chargeEnergyAdded)) kWh",
                             font: UIFont.systemFont(ofSize: 12))

                // Records
                if stats.longestDriveKm > 0 || stats.fastestDriveSpeed > 0 {
                    drawDivider(spacing: 16)
                    _ = drawText("Records",
                                 font: UIFont.boldSystemFont(ofSize: 18), spacing: 28)
                    if stats.longestDriveKm > 0 {
                        _ = drawText("Longest Drive: \(String(format: "%.1f", stats.longestDriveKm)) km (\(stats.longestDriveDate))",
                                     font: UIFont.systemFont(ofSize: 12))
                    }
                    if stats.fastestDriveSpeed > 0 {
                        _ = drawText("Fastest Drive: \(String(format: "%.0f", stats.fastestDriveSpeed)) km/h (\(stats.fastestDriveDate))",
                                     font: UIFont.systemFont(ofSize: 12))
                    }
                }

                // Monthly trends
                drawDivider(spacing: 16)
                _ = drawText("Monthly Trends",
                             font: UIFont.boldSystemFont(ofSize: 18), spacing: 28)

                let monthLabels = monthly.map(\.label)
                let distances = monthly.map { CGFloat($0.distance) }
                let energies = monthly.map { CGFloat($0.energy) }
                let chargeEnergies = monthly.map { CGFloat($0.chargeEnergy) }

                if distances.contains(where: { $0 > 0 }) {
                    _ = drawText("Distance by Month (km)",
                                 font: UIFont.boldSystemFont(ofSize: 14), spacing: 22)
                    drawBarChart(values: distances, labels: monthLabels)
                    y += 8
                }

                if energies.contains(where: { $0 > 0 }) {
                    _ = drawText("Energy Consumed by Month (kWh)",
                                 font: UIFont.boldSystemFont(ofSize: 14), spacing: 22)
                    drawBarChart(values: energies, labels: monthLabels)
                    y += 8
                }

                if chargeEnergies.contains(where: { $0 > 0 }) {
                    _ = drawText("Energy Added by Month (kWh)",
                                 font: UIFont.boldSystemFont(ofSize: 14), spacing: 22)
                    drawBarChart(values: chargeEnergies, labels: monthLabels)
                }

                // Driving habits
                drawDivider(spacing: 16)
                _ = drawText("Driving Habits",
                             font: UIFont.boldSystemFont(ofSize: 18), spacing: 28)
                _ = drawText("Avg Drive Duration: \(String(format: "%.0f", stats.avgDriveMinutes)) min",
                             font: UIFont.systemFont(ofSize: 12))
                _ = drawText("Driving Days: \(stats.drivingDays)",
                             font: UIFont.systemFont(ofSize: 12))
                _ = drawText("Top Speed: \(String(format: "%.0f", stats.maxSpeed)) km/h",
                             font: UIFont.systemFont(ofSize: 12))

                if stats.avgEfficiency > 0 {
                    let rating: String
                    switch stats.avgEfficiency {
                    case ..<150: rating = "Excellent"
                    case ..<180: rating = "Good"
                    case ..<220: rating = "Average"
                    default: rating = "High"
                    }
                    _ = drawText("Efficiency Rating: \(rating)",
                                 font: UIFont.systemFont(ofSize: 12))
                }

                // Charging habits
                if !yearCharges.isEmpty {
                    drawDivider(spacing: 16)
                    _ = drawText("Charging Habits",
                                 font: UIFont.boldSystemFont(ofSize: 18), spacing: 28)
                    _ = drawText("Total Cost: \(String(format: "%.2f", totalCost))",
                                 font: UIFont.systemFont(ofSize: 12))
                    _ = drawText("AC Charges: \(acChargeCount)  |  DC Charges: \(dcChargeCount)",
                                 font: UIFont.systemFont(ofSize: 12))
                    _ = drawText("Avg Charge Duration: \(String(format: "%.0f", avgChargeDuration)) min",
                                 font: UIFont.systemFont(ofSize: 12))
                    if let hour = mostCommonChargeHour {
                        _ = drawText("Most Common Charge Time: \(String(format: "%02d:00", hour))",
                                     font: UIFont.systemFont(ofSize: 12))
                    }
                }

                // Footer
                y += 24
                drawDivider(spacing: 12)
                _ = drawText("Generated by MateLink",
                             font: UIFont.systemFont(ofSize: 10),
                             color: .gray, spacing: 14)
            }

            await MainActor.run {
                pdfURL = tempURL
                showShare = true
            }
        } catch {
            await MainActor.run {
                errorMessage = error.localizedDescription
                showError = true
            }
        }
        }
    }

    // MARK: - Date Helpers

    private func parseDate(_ iso: String) -> Date? {
        if let d = Self.isoFormatter.date(from: iso) { return d }
        let f2 = ISO8601DateFormatter()
        f2.formatOptions = [.withInternetDateTime]
        return f2.date(from: iso)
    }

    private func formatDate(_ iso: String?) -> String {
        guard let iso = iso else { return "N/A" }
        let prefix = String(iso.prefix(10))
        return prefix.isEmpty ? "N/A" : prefix
    }
}

// MARK: - Share Sheet (UIKit bridge)

struct ShareSheet: UIViewControllerRepresentable {
    let activityItems: [Any]
    var applicationActivities: [UIActivity]? = nil

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: applicationActivities)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
