package main

import (
    "encoding/json"
    "math"
    "testing"
    "time"
)

// These are synthetic, privacy-free RFC3339Nano source boundaries. This tests
// the real serializer and JSON round trip that the Android Moshi consumer reads.
func TestHistorySessionMapPreservesFractionalObservedArchiveWindow(t *testing.T) {
    start := time.Date(2026, 10, 8, 1, 2, 3, 417000000, time.UTC)
    end := start.Add(120 * time.Second)
    power := 1.0
    samples := make([]historyImportRoutePoint, 5)
    for i := range samples {
        samples[i] = historyImportRoutePoint{
            Date: start.Add(time.Duration(i) * 30 * time.Second).Format(time.RFC3339Nano),
            Power: &power,
        }
    }
    session := telemetrySession{PublicID: 7, Kind: "drive", Source: "teslamate_archive",
        StartAt: start, EndAt: &end, ArchiveRoute: samples, QualityState: "observed"}
    actual := historySessionMap(session, "drive", 0)
    encoded, err := json.Marshal(map[string]any{"data": map[string]any{"drive": actual}})
    if err != nil { t.Fatal(err) }
    var decoded struct { Data struct {
        Drive struct {
            StartDate string `json:"start_date"`
            EndDate string `json:"end_date"`
            Positions []struct {
                Date string `json:"date"`
                Power *float64 `json:"power"`
            } `json:"drive_details"`
        } `json:"drive"`
    } `json:"data"` }
    if err := json.Unmarshal(encoded, &decoded); err != nil { t.Fatal(err) }
    detail := decoded.Data.Drive
    gotStart, e1 := time.Parse(time.RFC3339Nano, detail.StartDate)
    gotEnd, e2 := time.Parse(time.RFC3339Nano, detail.EndDate)
    if e1 != nil || e2 != nil || !gotStart.Equal(start) || !gotEnd.Equal(end) {
        t.Fatal("history API serializer lost source boundary fractional precision")
    }
    if len(detail.Positions) != len(samples) { t.Fatal("source route was not preserved") }
    duration, integral := 0.0, 0.0
    for i, point := range detail.Positions {
        at, err := time.Parse(time.RFC3339Nano, point.Date)
        if err != nil || point.Power == nil || !at.Equal(start.Add(time.Duration(i)*30*time.Second)) {
            t.Fatal("history route point lost source timestamp or power")
        }
        if i > 0 {
            prev, _ := time.Parse(time.RFC3339Nano, detail.Positions[i-1].Date)
            dt := at.Sub(prev).Seconds()
            if dt <= 0 || dt > 30 { t.Fatal("invalid synthetic sample interval") }
            duration += dt
            integral += (*point.Power + *detail.Positions[i-1].Power) / 2 * dt / 3600
        }
    }
    if math.Abs(duration-end.Sub(start).Seconds()) > 1e-9 ||
        math.Abs(integral-120.0/3600.0) > 1e-9 { t.Fatal("whole window source interval failed") }
    if time.RFC3339 == time.RFC3339Nano { t.Fatal("incorrect encoding constant") }
    if start.Format(time.RFC3339) == detail.StartDate {
        t.Fatal("the rounded legacy API timestamp unexpectedly matched observed fraction")
    }
}

// Charge session counters, observed bounds and route sample timestamps must all
// preserve precision; a rounded scalar bound cannot validate whole-session kWh.
func TestChargeEnergyContractAndJSONBoundaryPrecision(t *testing.T) {
    start := time.Date(2026, 10, 8, 4, 0, 0, 417000000, time.UTC)
    end := start.Add(90 * time.Second)
    b0, b1 := 5.0, 9.0
    a0, a1 := 8.0, 13.0
    session := telemetrySession{
        Kind: "charge", PublicID: 9, Source: "telemetry_mqtt",
        StartAt: start, EndAt: &end, QualityState: "observed",
        ChargePoints: []telemetryChargePoint{
            {ObservedAt: start, BatteryCounter: &b0, ACInputCounter: &a0, ChargeMode: "ac", ChargeModeField: "ChargerPhases"},
            {ObservedAt: end, BatteryCounter: &b1, ACInputCounter: &a1, ChargeMode: "ac", ChargeModeField: "ChargerPhases"},
        },
    }
    result := historySessionMap(session, "charge", 0)
    raw, err := json.Marshal(result)
    if err != nil { t.Fatal(err) }
    var decoded map[string]any
    if err := json.Unmarshal(raw, &decoded); err != nil { t.Fatal(err) }
    contract := decoded["energy_contract"].(map[string]any)
    metric := contract["battery_input"].(map[string]any)
    for _, key := range []string{"start_date", "observed_start_at"} {
        if value, ok := metric[key].(string); !ok || value != start.Format(time.RFC3339Nano) {
            t.Fatalf("fractional energy metric start mismatch: %s", key)
        }
    }
    for _, key := range []string{"end_date", "observed_end_at"} {
        if value, ok := metric[key].(string); !ok || value != end.Format(time.RFC3339Nano) {
            t.Fatalf("fractional energy metric end mismatch: %s", key)
        }
    }
    if decoded["start_date"] != metric["start_date"] || decoded["end_date"] != metric["end_date"] {
        t.Fatal("metric and session boundary mismatch")
    }
    if metric["value_kwh"] != 4.0 || metric["quality"] != "reported" ||
       decoded["charge_energy_added"] != 4.0 { t.Fatal("qualified charge energy lost") }
    if contract["ac_efficiency"] != 80.0 { t.Fatal("AC balance lost") }
}

func TestParkedArchiveAdjacentWindowPreservesFractionalEndPointsWithoutInventingEnergy(t *testing.T) {
    earlierStart := time.Date(2026, 10, 8, 1, 0, 0, 417000000, time.UTC)
    earlierEnd := earlierStart.Add(3 * time.Minute)
    laterStart := earlierEnd.Add(45 * time.Minute)
    laterEnd := laterStart.Add(2 * time.Minute)
    a0, a1, b0, b1 := 100.0, 101.0, 102.0, 103.0
    socEnd, socNext := 80, 79
    a := telemetrySession{
        PublicID: 50, Kind: "drive", Source: "teslamate_archive",
        QualityState: "observed", StartAt: earlierStart, EndAt: &earlierEnd,
        SourceInstanceID: "synthetic_source", SourceVehicleID: "synthetic_vehicle",
        OdometerStart: &a0, OdometerEnd: &a1,
    }
    b := telemetrySession{
        PublicID: 51, Kind: "drive", Source: "teslamate_archive",
        QualityState: "observed", StartAt: laterStart, EndAt: &laterEnd,
        SourceInstanceID: "synthetic_source", SourceVehicleID: "synthetic_vehicle",
        OdometerStart: &b0, OdometerEnd: &b1,
    }
    item := parkedHistoryBoundaryData(
        parkedHistoryBound{Session: a, EndSOC: &socEnd},
        parkedHistoryBound{Session: b, StartSOC: &socNext},
    )
    if item == nil { t.Fatal("qualified adjacent archive boundaries disappeared") }
    if item["start_date"] != earlierEnd.Format(time.RFC3339Nano) ||
       item["end_date"] != laterStart.Format(time.RFC3339Nano) {
        t.Fatal("parked boundaries rounded to seconds")
    }
    if item["energy_kwh"] != nil || item["average_power_kw"] != nil ||
       item["battery_delta"] != 1 {
        t.Fatal("adjacent SOC must not invent a battery kWh measurement")
    }
}
