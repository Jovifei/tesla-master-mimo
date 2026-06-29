import Foundation

// MARK: - API Raw Types (matches TeslaMateApi v1.21.x response)

struct CarRaw: Codable {
    let carId: Int; let name: String
    let carDetails: CarDetails; let carExterior: CarExterior
    let carSettings: CarSettings
    let teslamateDetails: TeslamateDetails; let teslamateStats: TeslamateStats
    enum CodingKeys: String, CodingKey {
        case carId = "car_id"; case name
        case carDetails = "car_details"; case carExterior = "car_exterior"
        case carSettings = "car_settings"
        case teslamateDetails = "teslamate_details"; case teslamateStats = "teslamate_stats"
    }
}

struct CarDetails: Codable { let eid: Int; let vid: Int; let vin: String; let model: String; let trimBadging: String; let efficiency: Double
    enum CodingKeys: String, CodingKey { case eid, vid, vin, model; case trimBadging = "trim_badging"; case efficiency }
}

struct CarExterior: Codable { let exteriorColor: String; let spoilerType: String; let wheelType: String
    enum CodingKeys: String, CodingKey { case exteriorColor = "exterior_color"; case spoilerType = "spoiler_type"; case wheelType = "wheel_type" }
}

struct CarSettings: Codable { let suspendMin: Int; let suspendAfterIdleMin: Int; let reqNotUnlocked: Bool; let freeSupercharging: Bool; let useStreamingApi: Bool
    enum CodingKeys: String, CodingKey { case suspendMin = "suspend_min"; case suspendAfterIdleMin = "suspend_after_idle_min"; case reqNotUnlocked = "req_not_unlocked"; case freeSupercharging = "free_supercharging"; case useStreamingApi = "use_streaming_api" }
}

struct TeslamateDetails: Codable { let insertedAt: String; let updatedAt: String
    enum CodingKeys: String, CodingKey { case insertedAt = "inserted_at"; case updatedAt = "updated_at" }
}

struct TeslamateStats: Codable { let totalCharges: Int; let totalDrives: Int; let totalUpdates: Int
    enum CodingKeys: String, CodingKey { case totalCharges = "total_charges"; case totalDrives = "total_drives"; case totalUpdates = "total_updates" }
}

// MARK: - App Model (flattened)

struct Car: Identifiable, Equatable {
    let id: Int; let name: String; let vin: String; let model: String; let trim: String
    let color: String; let wheel: String; let efficiency: Double
    let totalCharges: Int; let totalDrives: Int; let totalUpdates: Int

    init(from raw: CarRaw) {
        self.id = raw.carId; self.name = raw.name; self.vin = raw.carDetails.vin
        self.model = raw.carDetails.model; self.trim = raw.carDetails.trimBadging
        self.color = raw.carExterior.exteriorColor; self.wheel = raw.carExterior.wheelType
        self.efficiency = raw.carDetails.efficiency
        self.totalCharges = raw.teslamateStats.totalCharges; self.totalDrives = raw.teslamateStats.totalDrives
        self.totalUpdates = raw.teslamateStats.totalUpdates
    }
}

struct CarApiResponse: Codable { let data: CarData
    struct CarData: Codable { let cars: [CarRaw] }
}
