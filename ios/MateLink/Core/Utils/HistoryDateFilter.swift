import Foundation

/// Calendar-day filters matching Android `DateFilter` / `DriveDateFilter`.
enum HistoryDateFilter: String, CaseIterable, Identifiable {
    case today
    case last7Days
    case last30Days
    case last90Days
    case lastYear
    case allTime

    var id: String { rawValue }

    var label: String {
        switch self {
        case .today: return "Today"
        case .last7Days: return "Last 7 days"
        case .last30Days: return "Last 30 days"
        case .last90Days: return "Last 90 days"
        case .lastYear: return "Last year"
        case .allTime: return "All time"
        }
    }

    /// Android: `TODAY(0)`, `LAST_7_DAYS(7)`, … `ALL_TIME(null)`.
    var days: Int? {
        switch self {
        case .today: return 0
        case .last7Days: return 7
        case .last30Days: return 30
        case .last90Days: return 90
        case .lastYear: return 365
        case .allTime: return nil
        }
    }

    /// Inclusive local-day RFC3339 bounds. `allTime` returns nil.
    /// 7 days = today plus the previous 6 days (`end.minusDays(days - 1)`).
    func isoBounds(now: Date = Date(), calendar: Calendar = .current) -> (start: String, end: String)? {
        guard let days else { return nil }
        let today = calendar.startOfDay(for: now)
        let startDay: Date
        if days == 0 {
            startDay = today
        } else {
            startDay = calendar.date(byAdding: .day, value: -(days - 1), to: today) ?? today
        }
        let endExclusive = calendar.date(byAdding: .day, value: 1, to: today) ?? today
        return (Self.rfc3339(startDay), Self.rfc3339(endExclusive.addingTimeInterval(-1)))
    }

    static func parseISO(_ value: String) -> Date? {
        let full = ISO8601DateFormatter()
        full.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = full.date(from: value) { return d }
        let basic = ISO8601DateFormatter()
        basic.formatOptions = [.withInternetDateTime]
        if let d = basic.date(from: value) { return d }
        let prefix = String(value.prefix(19))
        let local = DateFormatter()
        local.locale = Locale(identifier: "en_US_POSIX")
        local.timeZone = TimeZone(secondsFromGMT: 0)
        local.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        return local.date(from: prefix)
    }

    private static func rfc3339(_ date: Date) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f.string(from: date)
    }
}

enum DriveDistanceFilter: String, CaseIterable, Identifiable {
    case all, commute, dayTrip, roadTrip
    var id: String { rawValue }

    var label: String {
        switch self {
        case .all: return "All distances"
        case .commute: return "Commute (< 10 km)"
        case .dayTrip: return "Day trip (10–100 km)"
        case .roadTrip: return "Road trip (> 100 km)"
        }
    }

    func matches(_ km: Double) -> Bool {
        switch self {
        case .all: return true
        case .commute: return km < 10
        case .dayTrip: return km >= 10 && km <= 100
        case .roadTrip: return km > 100
        }
    }
}

enum ChargeTypeFilter: String, CaseIterable, Identifiable {
    case all, ac, dc
    var id: String { rawValue }
    var label: String {
        switch self {
        case .all: return "All types"
        case .ac: return "AC"
        case .dc: return "DC"
        }
    }
}

enum CostFilter: String, CaseIterable, Identifiable {
    case all, hasCost, noCost
    var id: String { rawValue }
    var label: String {
        switch self {
        case .all: return "All costs"
        case .hasCost: return "Has cost"
        case .noCost: return "No cost"
        }
    }

    func matches(_ cost: Double?) -> Bool {
        switch self {
        case .all: return true
        case .hasCost: return (cost ?? 0) > 0
        case .noCost: return (cost ?? 0) <= 0
        }
    }
}

enum AnalysisWindow: String, CaseIterable, Identifiable {
    case allTime, last90, summer, winter
    var id: String { rawValue }
    var label: String {
        switch self {
        case .allTime: return "All time"
        case .last90: return "Last 90 days"
        case .summer: return "Summer (Jun–Aug)"
        case .winter: return "Winter (Dec–Feb)"
        }
    }

    func contains(_ date: Date, now: Date = Date(), calendar: Calendar = .current) -> Bool {
        switch self {
        case .allTime:
            return true
        case .last90:
            let start = calendar.date(byAdding: .day, value: -89, to: calendar.startOfDay(for: now)) ?? now
            return date >= start && date <= now
        case .summer:
            let m = calendar.component(.month, from: date)
            return m >= 6 && m <= 8
        case .winter:
            let m = calendar.component(.month, from: date)
            return m == 12 || m == 1 || m == 2
        }
    }
}

enum DriveFilterRules {
    static let minDurationMinutes = 1
    static let minRouteDistanceKm = 0.5

    static func isShortDrive(_ drive: Drive) -> Bool {
        drive.durationMin < minDurationMinutes || drive.distanceKm < minRouteDistanceKm
    }
}

enum ChargeFilterRules {
    static let minEnergyKwh = 0.1

    static func isShortCharge(_ charge: Charge) -> Bool {
        charge.chargeEnergyAdded < minEnergyKwh
    }
}
