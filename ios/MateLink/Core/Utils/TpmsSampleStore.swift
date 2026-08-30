import Foundation

/// Local TPMS samples recorded on dashboard refresh — same role as Android
/// `TpmsHistoryRepository` Worker samples (not a TeslaMate list API).
struct TpmsSample: Codable, Identifiable {
    var id: Date { recordedAt }
    let recordedAt: Date
    let fl: Double
    let fr: Double
    let rl: Double
    let rr: Double
}

enum TpmsSampleStore {
    private static let key = "matelink.tpms.samples"
    static let targetBar = 2.9
    static let lowBar = 2.6
    static let highBar = 3.4

    static func record(from status: CarStatus) {
        guard status.tirePressureFrontLeft > 0 || status.tirePressureFrontRight > 0 else { return }
        var samples = load()
        let sample = TpmsSample(
            recordedAt: Date(),
            fl: status.tirePressureFrontLeft,
            fr: status.tirePressureFrontRight,
            rl: status.tirePressureRearLeft,
            rr: status.tirePressureRearRight
        )
        if let last = samples.last, sample.recordedAt.timeIntervalSince(last.recordedAt) < 240 {
            samples.removeLast()
        }
        samples.append(sample)
        let cutoff = Date().addingTimeInterval(-40 * 24 * 3600)
        samples.removeAll { $0.recordedAt < cutoff }
        save(samples)
    }

    static func samples(days: Int) -> [TpmsSample] {
        let cutoff = Date().addingTimeInterval(-Double(days) * 24 * 3600)
        return load().filter { $0.recordedAt >= cutoff }
    }

    private static func load() -> [TpmsSample] {
        guard let data = UserDefaults.standard.data(forKey: key),
              let decoded = try? JSONDecoder().decode([TpmsSample].self, from: data) else {
            return []
        }
        return decoded
    }

    private static func save(_ samples: [TpmsSample]) {
        guard let data = try? JSONEncoder().encode(samples) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }
}
