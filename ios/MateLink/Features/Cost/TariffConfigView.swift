import SwiftUI

// MARK: - Reference Date for Normalizing Hour:Minute

/// A fixed reference date used to store time-of-day values (hour:minute only, date component is ignored).
private let referenceDate: Date = {
    Calendar.current.date(from: DateComponents(year: 2000, month: 1, day: 1))!
}()

/// Create a Date on the reference date representing the given hour and minute.
private func hm(_ hour: Int, _ minute: Int) -> Date {
    Calendar.current.date(bySettingHour: hour, minute: minute, second: 0, of: referenceDate)!
}

// MARK: - TimeRangeConfig

/// A single editable time range with start and end times (hour:minute only).
struct TimeRangeConfig: Codable, Equatable, Identifiable {
    var id = UUID()
    var startTime: Date
    var endTime: Date

    /// Human-readable representation, e.g. "10:00-15:00".
    var display: String {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        f.timeZone = TimeZone(identifier: "Asia/Shanghai")
        return "\(f.string(from: startTime))-\(f.string(from: endTime))"
    }

    /// Duration in hours (handles cross-midnight ranges).
    var hours: Double {
        let cal = Calendar.current
        let sh = cal.component(.hour, from: startTime) + cal.component(.minute, from: startTime) / 60
        let eh = cal.component(.hour, from: endTime) + cal.component(.minute, from: endTime) / 60
        return eh > sh ? eh - sh : (24 - sh) + eh
    }
}

// MARK: - TariffConfig

/// Full time-of-use electricity pricing configuration, persisted as JSON in UserDefaults.
struct TariffConfig: Codable {
    var isEnabled = true

    // Peak (峰)
    var peakRanges: [TimeRangeConfig] = [
        TimeRangeConfig(startTime: hm(10, 0), endTime: hm(15, 0)),
        TimeRangeConfig(startTime: hm(18, 0), endTime: hm(21, 0)),
    ]
    var peakPrice: Double = 1.0

    // Flat (平)
    var flatRanges: [TimeRangeConfig] = [
        TimeRangeConfig(startTime: hm(7, 0), endTime: hm(10, 0)),
        TimeRangeConfig(startTime: hm(15, 0), endTime: hm(18, 0)),
        TimeRangeConfig(startTime: hm(21, 0), endTime: hm(23, 0)),
    ]
    var flatPrice: Double = 0.7

    // Valley (谷)
    var valleyRanges: [TimeRangeConfig] = [
        TimeRangeConfig(startTime: hm(23, 0), endTime: hm(7, 0)),
    ]
    var valleyPrice: Double = 0.3
}

// MARK: - TariffConfigView

struct TariffConfigView: View {
    // MARK: Persistence

    /// JSON-encoded TariffConfig stored in UserDefaults.
    @AppStorage("tariffConfigJSON") private var storage: String = ""

    /// In-memory decoded config.
    @State private var config: TariffConfig = {
        let stored = UserDefaults.standard.string(forKey: "tariffConfigJSON") ?? ""
        guard let data = stored.data(using: .utf8),
              let decoded = try? JSONDecoder().decode(TariffConfig.self, from: data)
        else { return TariffConfig() }
        return decoded
    }()

    /// Encode current config and persist to UserDefaults.
    private func save() {
        guard let data = try? JSONEncoder().encode(config),
              let json = String(data: data, encoding: .utf8)
        else { return }
        storage = json
    }

    // MARK: Body

    var body: some View {
        List {
            enableSection
            if config.isEnabled {
                peakSection
                flatSection
                valleySection
                savingsPreview
                resetButton
            }
        }
        .navigationTitle(Text("tariff.config"))
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - Sections

    private var enableSection: some View {
        Section {
            Toggle(isOn: Binding(
                get: { config.isEnabled },
                set: { config.isEnabled = $0; save() }
            )) {
                Label("分时电价", systemImage: "clock.badge.checkmark")
            }
        } footer: {
            if config.isEnabled {
                Text("按峰、平、谷三个时段分别计费，鼓励错峰充电。")
            }
        }
    }

    // MARK: Peak

    private var peakSection: some View {
        Section {
            periodPriceRow(label: "峰时段", color: .red, price: Binding(
                get: { config.peakPrice },
                set: { config.peakPrice = $0; save() }
            ))
            ForEach(Array(config.peakRanges.enumerated()), id: \.element.id) { idx, range in
                timeRangeRow(range: Binding(
                    get: { config.peakRanges[idx] },
                    set: { config.peakRanges[idx] = $0; save() }
                ), onDelete: {
                    config.peakRanges.remove(at: idx); save()
                })
            }
            Button(action: {
                config.peakRanges.append(TimeRangeConfig(startTime: hm(0, 0), endTime: hm(1, 0)))
                save()
            }) {
                Label("添加峰时段", systemImage: "plus.circle")
            }
        } header: {
            periodHeader(label: "峰", color: .red, systemImage: "sun.max.fill")
        }
    }

    // MARK: Flat

    private var flatSection: some View {
        Section {
            periodPriceRow(label: "平时段", color: .orange, price: Binding(
                get: { config.flatPrice },
                set: { config.flatPrice = $0; save() }
            ))
            ForEach(Array(config.flatRanges.enumerated()), id: \.element.id) { idx, range in
                timeRangeRow(range: Binding(
                    get: { config.flatRanges[idx] },
                    set: { config.flatRanges[idx] = $0; save() }
                ), onDelete: {
                    config.flatRanges.remove(at: idx); save()
                })
            }
            Button(action: {
                config.flatRanges.append(TimeRangeConfig(startTime: hm(0, 0), endTime: hm(1, 0)))
                save()
            }) {
                Label("添加平时段", systemImage: "plus.circle")
            }
        } header: {
            periodHeader(label: "平", color: .orange, systemImage: "sun.min.fill")
        }
    }

    // MARK: Valley

    private var valleySection: some View {
        Section {
            periodPriceRow(label: "谷时段", color: .blue, price: Binding(
                get: { config.valleyPrice },
                set: { config.valleyPrice = $0; save() }
            ))
            ForEach(Array(config.valleyRanges.enumerated()), id: \.element.id) { idx, range in
                timeRangeRow(range: Binding(
                    get: { config.valleyRanges[idx] },
                    set: { config.valleyRanges[idx] = $0; save() }
                ), onDelete: {
                    config.valleyRanges.remove(at: idx); save()
                })
            }
            Button(action: {
                config.valleyRanges.append(TimeRangeConfig(startTime: hm(0, 0), endTime: hm(1, 0)))
                save()
            }) {
                Label("添加谷时段", systemImage: "plus.circle")
            }
        } header: {
            periodHeader(label: "谷", color: .blue, systemImage: "moon.stars.fill")
        }
    }

    // MARK: - Savings Preview

    private var savingsPreview: some View {
        let totalKwh: Double = 50
        let savings = computeSavings(totalKwh: totalKwh)
        let isSaving = savings > 0

        return Section {
            VStack(spacing: 12) {
                // Savings amount
                HStack(alignment: .firstBaseline, spacing: 4) {
                    Text(isSaving ? "节省" : "费用")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                    Text("\u{00A5}\(abs(savings).formatted(.number.precision(.fractionLength(2))))")
                        .font(.title.weight(.bold))
                        .foregroundColor(isSaving ? .green : .red)
                }

                // Assumption breakdown
                VStack(spacing: 4) {
                    HStack {
                        Text("假设月充电 \(totalKwh.formatted(.number.precision(.fractionLength(0)))) kWh")
                            .font(.caption)
                        Spacer()
                    }
                    ForEach(periodBreakdown(), id: \.label) { row in
                        HStack {
                            Circle().fill(row.color).frame(width: 8, height: 8)
                            Text(row.label)
                                .font(.caption2)
                                .foregroundColor(.secondary)
                            Spacer()
                            Text(row.detail)
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }
                    }
                }
                .padding(10)
                .background(Color(.systemGray6))
                .clipShape(RoundedRectangle(cornerRadius: 8))
            }
            .padding(.vertical, 4)
            .frame(maxWidth: .infinity)
        } header: {
            Label("费用预览", systemImage: "yensign.circle")
        }
    }

    // MARK: - Reset

    private var resetButton: some View {
        Section {
            Button(role: .destructive, action: {
                config = TariffConfig()
                save()
            }) {
                Label("重置为上海默认值", systemImage: "arrow.counterclockwise")
            }
        }
    }

    // MARK: - Reusable Row Builders

    private func periodHeader(label: String, color: Color, systemImage: String) -> some View {
        HStack(spacing: 6) {
            Image(systemName: systemImage)
                .foregroundColor(color)
            Text(label)
                .font(.headline)
        }
    }

    private func periodPriceRow(label: String, color: Color, price: Binding<Double>) -> some View {
        HStack {
            Text("单价")
                .foregroundColor(.secondary)
            Spacer()
            HStack(spacing: 2) {
                Text("\u{00A5}")
                    .foregroundColor(.secondary)
                TextField("", value: price, format: .number.precision(.fractionLength(4)))
                    .keyboardType(.decimalPad)
                    .multilineTextAlignment(.trailing)
                    .frame(width: 80)
                Text("/kWh")
                    .foregroundColor(.secondary)
            }
        }
    }

    private func timeRangeRow(range: Binding<TimeRangeConfig>, onDelete: @escaping () -> Void) -> some View {
        HStack(spacing: 8) {
            DatePicker("", selection: range.startTime, displayedComponents: .hourAndMinute)
                .labelsHidden()
                .environment(\.locale, Locale(identifier: "zh_Hans_CN"))
                .onChange(of: range.wrappedValue.startTime) { _ in save() }

            Text("→")
                .foregroundColor(.secondary)

            DatePicker("", selection: range.endTime, displayedComponents: .hourAndMinute)
                .labelsHidden()
                .environment(\.locale, Locale(identifier: "zh_Hans_CN"))
                .onChange(of: range.wrappedValue.endTime) { _ in save() }

            Spacer(minLength: 4)

            Button(role: .destructive, action: onDelete) {
                Image(systemName: "trash")
                    .font(.caption)
            }
            .buttonStyle(.borderless)
        }
    }

    // MARK: - Helpers

    /// Computes the approximate monetary savings when using TOU pricing vs. a flat rate.
    /// Comparison flat rate is the simple average of the three configured prices.
    private func computeSavings(totalKwh: Double) -> Double {
        let peakH = config.peakRanges.reduce(0) { $0 + $1.hours }
        let flatH = config.flatRanges.reduce(0) { $0 + $1.hours }
        let valleyH = config.valleyRanges.reduce(0) { $0 + $1.hours }
        let totalH = peakH + flatH + valleyH
        guard totalH > 0 else { return 0 }

        let touCost = totalKwh * (
            peakH * config.peakPrice +
            flatH * config.flatPrice +
            valleyH * config.valleyPrice
        ) / totalH

        let flatRate = (config.peakPrice + config.flatPrice + config.valleyPrice) / 3.0
        let noTouCost = totalKwh * flatRate

        return max(noTouCost - touCost, 0)
    }

    private struct PeriodBreakdownRow: Identifiable {
        let id = UUID()
        let label: String
        let color: Color
        let detail: String
    }

    private func periodBreakdown() -> [PeriodBreakdownRow] {
        let peakH = config.peakRanges.reduce(0) { $0 + $1.hours }
        let flatH = config.flatRanges.reduce(0) { $0 + $1.hours }
        let valleyH = config.valleyRanges.reduce(0) { $0 + $1.hours }
        let totalH = peakH + flatH + valleyH
        guard totalH > 0 else { return [] }

        return [
            PeriodBreakdownRow(
                label: "峰 \(\u{00A5})\(config.peakPrice.formatted(.number.precision(.fractionLength(2))))/kWh",
                color: .red,
                detail: "\(Int(round(peakH / totalH * 100)))% · \(Int(peakH))h"
            ),
            PeriodBreakdownRow(
                label: "平 \(\u{00A5})\(config.flatPrice.formatted(.number.precision(.fractionLength(2))))/kWh",
                color: .orange,
                detail: "\(Int(round(flatH / totalH * 100)))% · \(Int(flatH))h"
            ),
            PeriodBreakdownRow(
                label: "谷 \(\u{00A5})\(config.valleyPrice.formatted(.number.precision(.fractionLength(2))))/kWh",
                color: .blue,
                detail: "\(Int(round(valleyH / totalH * 100)))% · \(Int(valleyH))h"
            ),
        ]
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        TariffConfigView()
    }
}
