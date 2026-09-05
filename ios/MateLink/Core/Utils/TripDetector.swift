import Foundation

/// Highway/road-trip detector matching Android `TripDetector`.
struct DetectedTrip: Identifiable {
    let id: String
    let drives: [Drive]
    let dcCharges: [Charge]
    var startDate: String { drives.first?.startDate ?? "" }
    var endDate: String { drives.last?.endDate ?? drives.last?.startDate ?? "" }
    var distanceKm: Double { drives.reduce(0) { $0 + $1.distanceKm } }
    var drivingMin: Int { drives.reduce(0) { $0 + $1.durationMin } }
    var energyCharged: Double { dcCharges.reduce(0) { $0 + $1.chargeEnergyAdded } }
    var startAddress: String { drives.first?.startAddress ?? "" }
    var endAddress: String { drives.last?.endAddress ?? "" }
}

enum TripDetector {
    static let microDriveThresholdKm = 1.0
    static let minTripDistanceKm = 300.0
    static let maxDriveToChargeGapMin: TimeInterval = 15 * 60
    static let maxChargeToDriveGapMin: TimeInterval = 180 * 60
    static let maxDriveToDriveGapMin: TimeInterval = 30 * 60

    static func detect(drives: [Drive], dcCharges: [Charge]) -> [DetectedTrip] {
        let realDrives = drives.filter { $0.distanceKm >= microDriveThresholdKm }
        enum Event {
            case drive(Drive)
            case charge(Charge)
            var start: String {
                switch self {
                case .drive(let d): return d.startDate
                case .charge(let c): return c.startDate
                }
            }
            var end: String {
                switch self {
                case .drive(let d): return d.endDate
                case .charge(let c): return c.endDate ?? c.startDate
                }
            }
        }

        var events: [Event] = realDrives.map { .drive($0) } + dcCharges.map { .charge($0) }
        events.sort {
            (HistoryDateFilter.parseISO($0.start) ?? .distantPast)
                < (HistoryDateFilter.parseISO($1.start) ?? .distantPast)
        }

        var trips: [DetectedTrip] = []
        var currentDrives: [Drive] = []
        var currentCharges: [Charge] = []
        var lastEventEnd: Date?
        var lastWasDrive = false

        func emit() {
            let distance = currentDrives.reduce(0.0) { $0 + $1.distanceKm }
            if currentDrives.count >= 2, currentCharges.count >= 1, distance >= minTripDistanceKm {
                let id = currentDrives.first.map { "\($0.id)-\($0.startDate)" } ?? UUID().uuidString
                trips.append(DetectedTrip(id: id, drives: currentDrives, dcCharges: currentCharges))
            }
            currentDrives = []
            currentCharges = []
        }

        for event in events {
            guard let eventStart = HistoryDateFilter.parseISO(event.start) else { continue }
            if lastEventEnd == nil {
                if case .drive(let d) = event {
                    currentDrives.append(d)
                    lastEventEnd = HistoryDateFilter.parseISO(event.end)
                    lastWasDrive = true
                }
                continue
            }
            let gap = eventStart.timeIntervalSince(lastEventEnd!)
            switch event {
            case .charge(let c) where lastWasDrive && gap <= maxDriveToChargeGapMin:
                currentCharges.append(c)
                lastEventEnd = HistoryDateFilter.parseISO(event.end)
                lastWasDrive = false
            case .drive(let d) where !lastWasDrive && gap <= maxChargeToDriveGapMin:
                currentDrives.append(d)
                lastEventEnd = HistoryDateFilter.parseISO(event.end)
                lastWasDrive = true
            case .drive(let d) where lastWasDrive && gap <= maxDriveToDriveGapMin:
                currentDrives.append(d)
                lastEventEnd = HistoryDateFilter.parseISO(event.end)
                lastWasDrive = true
            case .charge(let c) where !lastWasDrive && gap <= maxChargeToDriveGapMin:
                currentCharges.append(c)
                lastEventEnd = HistoryDateFilter.parseISO(event.end)
                lastWasDrive = false
            default:
                emit()
                lastEventEnd = nil
                lastWasDrive = false
                if case .drive(let d) = event {
                    currentDrives.append(d)
                    lastEventEnd = HistoryDateFilter.parseISO(event.end)
                    lastWasDrive = true
                }
            }
        }
        emit()
        return trips.sorted {
            (HistoryDateFilter.parseISO($0.startDate) ?? .distantPast)
                > (HistoryDateFilter.parseISO($1.startDate) ?? .distantPast)
        }
    }
}
