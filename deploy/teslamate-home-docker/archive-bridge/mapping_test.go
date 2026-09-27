package main

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func TestMappingPreservesRouteChargePointsAddressesAndNulls(t *testing.T) {
	start := testTime(1)
	end := testTime(2)
	latitude, longitude := 31.2304, 121.4737
	zeroCost := 0.0
	drive := DriveRecord{
		ID:           42,
		StartedAt:    &start,
		EndedAt:      &end,
		StartAddress: stringPointer("Home"),
		EndAddress:   nil,
		Route: []RoutePoint{{
			Date: &start, Latitude: &latitude, Longitude: &longitude,
			Speed: nil, Power: floatPointer(12.5), Heading: nil,
		}},
	}
	chargePointDate := testTime(3)
	chargePoints := []ChargePoint{{
		Date:         &chargePointDate,
		BatteryLevel: nil,
		EnergyAdded:  floatPointer(0),
		ChargerPower: floatPointer(7.2),
		Latitude:     nil,
		Longitude:    nil,
	}}
	charge := ChargeRecord{
		ID:           9,
		StartedAt:    &start,
		EndedAt:      &end,
		EnergyAdded:  nil,
		Cost:         &zeroCost,
		Address:      nil,
		ChargePoints: chargePoints,
	}

	batch := buildArchiveBatch(testConfig("http://archive.test", "cursor.json"), Cursor{}, []DriveRecord{drive}, []ChargeRecord{charge})
	if batch.Drives[0].SourceRecordID != "teslamate:drive:42" || batch.Charges[0].SourceRecordID != "teslamate:charging_process:9" {
		t.Fatalf("source record IDs = %q, %q", batch.Drives[0].SourceRecordID, batch.Charges[0].SourceRecordID)
	}
	encoded, err := json.Marshal(batch)
	if err != nil {
		t.Fatal(err)
	}
	var body map[string]any
	if err := json.Unmarshal(encoded, &body); err != nil {
		t.Fatal(err)
	}
	driveJSON := body["drives"].([]any)[0].(map[string]any)
	if driveJSON["start_address"] != "Home" || driveJSON["end_address"] != nil {
		t.Fatalf("drive addresses = %#v", driveJSON)
	}
	point := driveJSON["route"].([]any)[0].(map[string]any)
	if point["speed"] != nil || point["heading"] != nil || point["power"] != 12.5 {
		t.Fatalf("route null preservation failed: %#v", point)
	}
	chargeJSON := body["charges"].([]any)[0].(map[string]any)
	if chargeJSON["energy_added"] != nil || chargeJSON["address"] != nil || chargeJSON["cost"] != 0.0 {
		t.Fatalf("charge null/zero preservation failed: %#v", chargeJSON)
	}
	chargePoint := chargeJSON["charge_points"].([]any)[0].(map[string]any)
	if chargePoint["battery_level"] != nil || chargePoint["energy_added"] != 0.0 {
		t.Fatalf("charge point null/zero preservation failed: %#v", chargePoint)
	}
}

func TestQueriesAreSelectOnly(t *testing.T) {
	for name, query := range map[string]string{"drives": drivesQuery, "charges": chargesQuery} {
		if !strings.HasPrefix(strings.ToUpper(strings.TrimSpace(query)), "SELECT ") {
			t.Errorf("%s query is not SELECT-only", name)
		}
	}
}

func testTime(offset int) time.Time          { return time.Date(2026, 9, 27, 0, 0, offset, 0, time.UTC) }
func timePointer(value time.Time) *time.Time { return &value }
func stringPointer(value string) *string     { return &value }
func floatPointer(value float64) *float64    { return &value }
