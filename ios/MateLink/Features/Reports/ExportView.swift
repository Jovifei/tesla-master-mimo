import SwiftUI

enum ExportFormat: String, CaseIterable {
    case csv = "CSV"
    case json = "JSON"

    var fileExtension: String { rawValue.lowercased() }
    var mimeType: String {
        switch self {
        case .csv: return "text/csv"
        case .json: return "application/json"
        }
    }
}

enum ExportDataType: String, CaseIterable {
    case drives = "Drives"
    case charges = "Charges"
    case both = "Both"
}

struct ExportView: View {
    @EnvironmentObject var state: AppState

    @State private var format: ExportFormat = .csv
    @State private var dataType: ExportDataType = .both
    @State private var selectedYear: Int? = nil
    @State private var availableYears: [Int] = []
    @State private var drives: [Drive] = []
    @State private var charges: [Charge] = []
    @State private var isExporting = false
    @State private var shareURL: ShareableURL? = nil
    @State private var showError = false
    @State private var errorMessage = ""

    var filteredDrives: [Drive] {
        guard let year = selectedYear else { return drives }
        return drives.filter { $0.startDate.hasPrefix("\(year)") }
    }

    var filteredCharges: [Charge] {
        guard let year = selectedYear else { return charges }
        return charges.filter { $0.startDate.hasPrefix("\(year)") }
    }

    var body: some View {
        List {
            // T-201: Format Selection
            Section("Export Format") {
                Picker("Format", selection: $format) {
                    ForEach(ExportFormat.allCases, id: \.self) { f in
                        Text(f.rawValue).tag(f)
                    }
                }
                .pickerStyle(.segmented)
            }

            // Data Type Selection
            Section("Data to Export") {
                Picker("Data Type", selection: $dataType) {
                    ForEach(ExportDataType.allCases, id: \.self) { t in
                        Text(t.rawValue).tag(t)
                    }
                }
                .pickerStyle(.segmented)
            }

            // T-202: Date Range Selection
            Section("Date Range") {
                HStack {
                    Text("Year")
                    Spacer()
                    Menu {
                        Button("All Time") { selectedYear = nil }
                        ForEach(availableYears, id: \.self) { year in
                            Button("\(year)") { selectedYear = year }
                        }
                    } label: {
                        HStack {
                            Text(selectedYear.map { "\($0)" } ?? "All Time")
                            Image(systemName: "chevron.down")
                                .font(.caption)
                        }
                        .foregroundColor(.accentColor)
                    }
                }
            }

            // Export Summary
            Section("Export Summary") {
                LabeledContent("Format", value: format.rawValue)
                LabeledContent("Data", value: dataType.rawValue)
                LabeledContent("Range", value: selectedYear.map { "\($0)" } ?? "All Time")
                LabeledContent("Drives", value: "\(filteredDrives.count)")
                LabeledContent("Charges", value: "\(filteredCharges.count)")
            }

            // T-203: Export & Share Button
            Section {
                Button {
                    exportAndShare()
                } label: {
                    HStack {
                        if isExporting {
                            ProgressView()
                                .progressViewStyle(.circular)
                        }
                        Text(isExporting ? "Exporting..." : "Export & Share")
                    }
                    .frame(maxWidth: .infinity)
                }
                .disabled(isExporting || (filteredDrives.isEmpty && filteredCharges.isEmpty))
            }

            if showError {
                Section {
                    Text(errorMessage)
                        .foregroundColor(.red)
                }
            }
        }
        .navigationTitle("Export Data")
        .task { await loadData() }
        .sheet(item: $shareURL, onDismiss: { shareURL = nil }) { wrapper in
            ShareSheet(activityItems: [wrapper.url])
        }
    }

    private func loadData() async {
        let carId = state.currentCarId
        showError = false
        errorMessage = ""
        if state.isMockMode {
            drives = await state.mock.getDrives(carId)
            charges = await state.mock.getCharges(carId)
        } else if let api = state.real {
            do {
                drives = try await api.fetch("/api/v1/cars/\(carId)/drives")
                charges = try await api.fetch("/api/v1/cars/\(carId)/charges")
            } catch {
                drives = []
                charges = []
                errorMessage = "Unable to load real export data: \(error.localizedDescription)"
                showError = true
            }
        } else {
            drives = []
            charges = []
            errorMessage = "No TeslaMate instance is configured."
            showError = true
        }

        var years = Set<Int>()
        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        dateFormatter.timeZone = TimeZone(secondsFromGMT: 0)
        for d in drives {
            if let date = dateFormatter.date(from: d.startDate) {
                years.insert(Calendar.current.component(.year, from: date))
            }
        }
        for c in charges {
            if let date = dateFormatter.date(from: c.startDate) {
                years.insert(Calendar.current.component(.year, from: date))
            }
        }
        availableYears = years.sorted(by: >)
    }

    /// Remove stale matelink_export_* files from the temp directory to prevent accumulation.
    private func cleanupStaleExports() {
        let tmpDir = FileManager.default.temporaryDirectory
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: tmpDir, includingPropertiesForKeys: nil
        ) else { return }
        for file in files where file.lastPathComponent.hasPrefix("matelink_export_") {
            try? FileManager.default.removeItem(at: file)
        }
    }

    private func exportAndShare() {
        isExporting = true
        // Snapshot @State properties before dispatching to background thread
        let snapDrives = filteredDrives
        let snapCharges = filteredCharges
        let snapFormat = format
        let snapDataType = dataType
        DispatchQueue.global(qos: .userInitiated).async {
            self.cleanupStaleExports()

            let timestamp = Int(Date().timeIntervalSince1970)
            let fileName = "matelink_export_\(timestamp).\(snapFormat.fileExtension)"
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)

            do {
                let content: String
                switch snapFormat {
                case .csv:
                    content = Self.buildCSV(drives: snapDrives, charges: snapCharges, dataType: snapDataType)
                case .json:
                    content = try Self.buildJSON(drives: snapDrives, charges: snapCharges, dataType: snapDataType)
                }
                try content.write(to: url, atomically: true, encoding: .utf8)
                DispatchQueue.main.async {
                    isExporting = false
                    shareURL = ShareableURL(url: url)
                }
            } catch {
                DispatchQueue.main.async {
                    isExporting = false
                    errorMessage = error.localizedDescription
                    showError = true
                }
            }
        }
    }

    private static func buildCSV(drives: [Drive], charges: [Charge], dataType: ExportDataType) -> String {
        var csv = ""

        if dataType == .drives || dataType == .both {
            csv += "Type,Start Date,End Date,Distance (km),Energy (kWh),Efficiency (Wh/km),Max Speed (km/h),Duration (min)\n"
            for d in drives {
                let energyKwh = d.distanceKm * d.efficiency / 1000.0
                csv += "DRIVE,\(csvEscape(d.startDate)),\(csvEscape(d.endDate)),\(d.distanceKm),\(String(format: "%.2f", energyKwh)),\(d.efficiency),\(d.speedMax),\(d.durationMin)\n"
            }
        }
        if dataType == .charges || dataType == .both {
            if dataType == .both { csv += "\n" }
            csv += "Type,Start Date,End Date,Energy Added (kWh),Cost,Duration (min),Start Battery %,End Battery %\n"
            for c in charges {
                csv += "CHARGE,\(csvEscape(c.startDate)),\(csvEscape(c.endDate ?? "")),\(c.chargeEnergyAdded),\(c.cost.map { String($0) } ?? ""),\(c.durationMin),\(c.startBatteryLevel),\(c.endBatteryLevel.map { "\($0)" } ?? "")\n"
            }
        }
        return csv
    }

    /// RFC 4180 escaping: wrap in quotes if value contains comma, quote, or newline.
    private static func csvEscape(_ value: String) -> String {
        if value.contains(",") || value.contains("\"") || value.contains("\n") {
            let escaped = value.replacingOccurrences(of: "\"", with: "\"\"")
            return "\"\(escaped)\""
        }
        return value
    }

    private static func buildJSON(drives: [Drive], charges: [Charge], dataType: ExportDataType) throws -> String {
        var dict: [String: Any] = [
            "exportDate": ISO8601DateFormatter().string(from: Date())
        ]

        if dataType == .drives || dataType == .both {
            dict["drives"] = drives.map { d in
                [
                    "startDate": d.startDate,
                    "endDate": d.endDate,
                    "distanceKm": d.distanceKm,
                    "energyKwh": d.distanceKm * d.efficiency / 1000.0,
                    "efficiencyWhKm": d.efficiency,
                    "maxSpeedKmh": d.speedMax,
                    "durationMin": d.durationMin
                ] as [String: Any]
            }
        }
        if dataType == .charges || dataType == .both {
            dict["charges"] = charges.map { c in
                let obj: [String: Any] = [
                    "startDate": c.startDate,
                    "endDate": c.endDate as Any? ?? NSNull(),
                    "energyAddedKwh": c.chargeEnergyAdded,
                    "cost": c.cost as Any? ?? NSNull(),
                    "durationMin": c.durationMin,
                    "startBatteryPercent": c.startBatteryLevel,
                    "endBatteryPercent": c.endBatteryLevel as Any? ?? NSNull()
                ]
                return obj
            }
        }

        let data = try JSONSerialization.data(withJSONObject: dict, options: [.prettyPrinted, .sortedKeys])
        return String(data: data, encoding: .utf8) ?? "{}"
    }
}

struct ShareableURL: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}
