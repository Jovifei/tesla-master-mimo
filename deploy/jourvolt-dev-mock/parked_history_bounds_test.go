package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"reflect"
	"testing"
	"time"
)

func TestParkedChargeSessionsSplitRechargesAndKeepUnknownEvidence(t *testing.T) {
	start := time.Date(2026, 10, 25, 9, 0, 0, 0, time.UTC)
	end := start.Add(2 * time.Hour)
	older := telemetrySession{Source: "teslamate_archive", SourceInstanceID: "archive-a", SourceVehicleID: "uid-a", EndAt: &start}
	newer := telemetrySession{StartAt: end}
	firstStart, firstEnd := start.Add(10*time.Minute), start.Add(30*time.Minute)
	secondStart, secondEnd := start.Add(time.Hour), start.Add(time.Hour+15*time.Minute)
	firstEnergy, secondEnergy := 7.0, 0.0
	first := telemetrySession{PublicID: 11, Kind: "charge", StartAt: firstStart, EndAt: &firstEnd,
		EnergyAdded: &firstEnergy, Source: older.Source, QualityState: "observed",
		SourceInstanceID: older.SourceInstanceID, SourceVehicleID: older.SourceVehicleID,
		ChargePoints: []telemetryChargePoint{
			{ObservedAt: firstStart, BatteryLevel: intPointerForHistoryTest(31)},
			{ObservedAt: firstEnd, BatteryLevel: intPointerForHistoryTest(51)},
		}}
	second := telemetrySession{PublicID: 12, Kind: "charge", StartAt: secondStart, EndAt: &secondEnd,
		EnergyAdded: &secondEnergy, Source: older.Source, QualityState: "observed",
		SourceInstanceID: older.SourceInstanceID, SourceVehicleID: older.SourceVehicleID}
	data := map[string]any{"linked_charge": nil}
	addParkedCharges(data, older, newer, []telemetrySession{second, first})
	items := data["linked_charges"].([]map[string]any)
	if len(items) != 2 || data["charge_sessions_complete"] != true ||
		items[0]["charge_id"] != 11 || items[1]["charge_id"] != 12 ||
		items[0]["energy_added_kwh"] != 7.0 || items[1]["energy_added_kwh"] != 0.0 ||
		items[0]["start_battery_level"] != 31 || items[0]["end_battery_level"] != 51 ||
		items[0]["start_soc_observed_at"] != firstStart.Format(time.RFC3339Nano) ||
		items[0]["end_soc_observed_at"] != firstEnd.Format(time.RFC3339Nano) {
		t.Fatalf("two charge sessions lost or fabricated: %#v", data)
	}
	if data["linked_charge"].(map[string]any)["charge_id"] != 11 {
		t.Fatal("legacy first charge alias changed")
	}

	foreign := first
	foreign.PublicID = 13
	foreign.SourceVehicleID = "other-uid"
	partial := first
	partial.PublicID = 14
	partial.StartAt = start.Add(-time.Minute)
	partial.QualityState = "incomplete"
	partial.ChargePoints = []telemetryChargePoint{
		{ObservedAt: partial.StartAt, BatteryLevel: intPointerForHistoryTest(31)},
		{ObservedAt: firstEnd, BatteryLevel: intPointerForHistoryTest(51)},
	}
	unknown := first
	unknown.PublicID = 15
	unknown.StartAt = start.Add(40 * time.Minute)
	unknown.EndAt = nil
	listed, complete := parkedLinkedCharges(older, newer, []telemetrySession{foreign, partial, unknown})
	if complete || len(listed) != 2 || listed[0]["energy_added_kwh"] != nil || listed[1]["end_date"] != nil {
		t.Fatalf("partial or foreign charge treated as complete: %#v %v", listed, complete)
	}
	if listed[0]["start_battery_level"] != 31 || listed[0]["end_battery_level"] != 51 {
		t.Fatal("exact recorded SOC boundaries missing")
	}
	overlap := second
	overlap.StartAt = firstStart.Add(5 * time.Minute)
	overlapEnd := firstEnd.Add(5 * time.Minute)
	overlap.EndAt = &overlapEnd
	if _, complete := parkedLinkedCharges(older, newer, []telemetrySession{first, overlap}); complete {
		t.Fatal("overlapping charge records cannot certify a clean interval split")
	}

	var crowded []telemetrySession
	for i := 0; i <= maxParkedChargeSessions; i++ {
		row := first
		row.PublicID = 1000 + i
		row.StartAt = start.Add(time.Duration(i) * time.Minute)
		stop := row.StartAt.Add(30 * time.Second)
		row.EndAt = &stop
		crowded = append(crowded, row)
	}
	bounded, complete := parkedLinkedCharges(older, newer, crowded)
	if complete || len(bounded) != maxParkedChargeSessions {
		t.Fatalf("bounded charge response failed: len=%d complete=%v", len(bounded), complete)
	}
}

func TestParkedChargeBoundarySOCRequiresUnambiguousNearbyActualPoint(t *testing.T) {
	at := time.Date(2026, 10, 25, 10, 0, 0, 0, time.UTC)
	point := func(offset time.Duration, value int) telemetryChargePoint {
		return telemetryChargePoint{ObservedAt: at.Add(offset), BatteryLevel: intPointerForHistoryTest(value)}
	}
	near := point(4*time.Minute, 31)
	if level, observed := parkedChargeBoundarySOC([]telemetryChargePoint{point(6*time.Minute, 99), near}, at); level == nil || *level != 31 || observed == nil || !observed.Equal(near.ObservedAt) {
		t.Fatalf("nearest real point within tolerance lost: %v %v", level, observed)
	}
	if level, observed := parkedChargeBoundarySOC([]telemetryChargePoint{point(parkedChargeSOCTolerance, 32)}, at); level == nil || *level != 32 || observed == nil {
		t.Fatal("5-minute inclusive boundary was rejected")
	}
	for _, points := range [][]telemetryChargePoint{
		{point(parkedChargeSOCTolerance+time.Second, 30)},
		{point(-2*time.Minute, 30), point(2*time.Minute, 31)},
		{point(-2*time.Minute, 30), point(2*time.Minute, 30)},
		{point(time.Minute, 30), point(time.Minute, 31)},
	} {
		if level, observed := parkedChargeBoundarySOC(points, at); level != nil || observed != nil {
			t.Fatalf("out-of-window or ambiguous point accepted: %v %v", level, observed)
		}
	}
	if level, observed := parkedChargeBoundarySOC([]telemetryChargePoint{point(time.Minute, 30), point(time.Minute, 30)}, at); level == nil || *level != 30 || observed == nil {
		t.Fatal("duplicate identical observation should remain usable")
	}
}

func parkedHistoryTestPair(t *testing.T, db *store) (*telemetryService, string, int, int, int, time.Time) {
	t.Helper()
	user, car := boundedHistoryTestTenant(t, db, "parked-bounds")
	base := time.Date(2026, 10, 7, 0, 0, 0, 0, time.UTC)
	older := boundedHistoryInsertSession(t, db, user, car, "drive", base, "teslamate_archive", "observed", []historyImportRoutePoint{
		{Date: base.Format(time.RFC3339), Latitude: floatPointerForHistoryTest(31), Longitude: floatPointerForHistoryTest(121), BatteryLevel: intPointerForHistoryTest(80)},
		{Date: base.Add(17 * time.Minute).Format(time.RFC3339), Latitude: floatPointerForHistoryTest(31), Longitude: floatPointerForHistoryTest(121), BatteryLevel: intPointerForHistoryTest(0)},
	}, "parked-older")
	newer := boundedHistoryInsertSession(t, db, user, car, "drive", base.Add(time.Hour), "teslamate_archive", "observed", []historyImportRoutePoint{
		{Date: base.Add(time.Hour).Format(time.RFC3339), Latitude: floatPointerForHistoryTest(31), Longitude: floatPointerForHistoryTest(121), BatteryLevel: intPointerForHistoryTest(10)},
	}, "parked-newer")
	return &telemetryService{store: db}, user, car, older, newer, base
}

func TestParkedHistoryBoundsPostgresHTTPMemoryParityAndUnknown(t *testing.T) {
	db := boundedHistoryTestStore(t)
	s, user, car, older, newer, base := parkedHistoryTestPair(t, db)
	data, err := s.parkedHistoryBounds(context.Background(), user, car, older, newer)
	if err != nil || data == nil {
		t.Fatalf("data=%v err=%v", data, err)
	}
	if data["start_date"] != base.Add(17*time.Minute).Format(time.RFC3339) || data["end_date"] != base.Add(time.Hour).Format(time.RFC3339) || data["start_battery_level"] != 0 || data["end_battery_level"] != 10 || data["battery_delta"] != 10 {
		t.Fatalf("bounds=%v", data)
	}
	for _, key := range []string{"energy_kwh", "average_power_kw", "peak_power_kw", "inside_temp_average", "outside_temp_average", "linked_charge"} {
		if data[key] != nil {
			t.Fatalf("%s invented", key)
		}
	}
	if data["source"] != "teslamate_archive" || data["sample_count"] != 0 {
		t.Fatal("parking claimed live telemetry")
	}
	memory := newTelemetryServiceForTest("example.test")
	if err := memory.memory.registerVehicle(telemetryVehicleRef{UserID: user, VehicleID: car, VINHash: "synthetic-parked-hash"}); err != nil {
		t.Fatal(err)
	}
	for _, id := range []int{older, newer} {
		row, ok, err := s.historyDetailPostgresBounded(context.Background(), user, car, "drive", id)
		if err != nil || !ok {
			t.Fatal(err)
		}
		row.Kind = "drive"
		memory.memory.completed[telemetryKey{UserID: user, VehicleID: car}] = append(memory.memory.completed[telemetryKey{UserID: user, VehicleID: car}], row)
	}
	copyData, err := memory.parkedHistoryBounds(context.Background(), user, car, older, newer)
	if err != nil || !reflect.DeepEqual(data, copyData) {
		t.Fatalf("memory/PG differ %v %v %v", data, copyData, err)
	}
	// The compatibility endpoint never calls the live provider (nil here).
	a := &app{telemetry: s}
	path := fmt.Sprintf("/api/matelink/v1/cars/%d/parked/%d/%d", car, older, newer)
	w := httptest.NewRecorder()
	a.adapterResource(w, httptest.NewRequest(http.MethodGet, path, nil), user, path)
	if w.Code != http.StatusOK {
		t.Fatalf("http=%d body=%s", w.Code, w.Body.String())
	}
	var body map[string]json.RawMessage
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil || string(body["data"]) == "null" {
		t.Fatalf("HTTPdata=%s err=%v", w.Body.String(), err)
	}
	if _, err := db.pool.Exec(context.Background(), `UPDATE jourvolt_telemetry_sessions SET route_json='[]'::jsonb WHERE user_id=$1 AND vehicle_id=$2 AND public_id=$3`, user, car, older); err != nil {
		t.Fatal(err)
	}
	data, err = s.parkedHistoryBounds(context.Background(), user, car, older, newer)
	if err != nil || data == nil || data["start_battery_level"] != nil || data["battery_delta"] != nil || data["end_battery_level"] != 10 {
		t.Fatalf("missing SOC became zero: %v %v", data, err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := s.parkedHistoryBounds(ctx, user, car, older, newer); !errors.Is(err, context.Canceled) {
		t.Fatal("cancellation ignored")
	}
	other, _ := boundedHistoryTestTenant(t, db, "parked-other")
	if leaked, err := s.parkedHistoryBounds(context.Background(), other, car, older, newer); err != nil || leaked != nil {
		t.Fatal("owner scope leak")
	}
}

func TestParkedHistoryBoundsPostgresListsEveryScopedChargeSession(t *testing.T) {
	db := boundedHistoryTestStore(t)
	s, user, car, older, newer, base := parkedHistoryTestPair(t, db)
	firstStart, secondStart := base.Add(20*time.Minute), base.Add(40*time.Minute)
	first := boundedHistoryInsertSession(t, db, user, car, "charge", firstStart, "teslamate_archive", "observed", []historyImportRoutePoint{}, "charge-1")
	second := boundedHistoryInsertSession(t, db, user, car, "charge", secondStart, "teslamate_archive", "observed", []historyImportRoutePoint{}, "charge-2")
	firstEnd := firstStart.Add(17 * time.Minute)
	firstObserved, lastObserved := firstStart.Add(2*time.Minute), firstEnd.Add(-3*time.Minute)
	points, err := json.Marshal([]telemetryChargePoint{
		{ObservedAt: firstObserved, BatteryLevel: intPointerForHistoryTest(30)},
		{ObservedAt: lastObserved, BatteryLevel: intPointerForHistoryTest(55)},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.pool.Exec(context.Background(), `UPDATE jourvolt_telemetry_sessions
SET energy_added=5.0, charge_points_json=$4::jsonb
WHERE user_id=$1 AND vehicle_id=$2 AND public_id=$3`, user, car, first, points); err != nil {
		t.Fatal(err)
	}
	if _, err := db.pool.Exec(context.Background(), `UPDATE jourvolt_telemetry_sessions
SET energy_added=0.0 WHERE user_id=$1 AND vehicle_id=$2 AND public_id=$3`, user, car, second); err != nil {
		t.Fatal(err)
	}
	other, otherCar := boundedHistoryTestTenant(t, db, "parked-charge-other")
	boundedHistoryInsertSession(t, db, other, otherCar, "charge", base.Add(22*time.Minute), "teslamate_archive", "observed", []historyImportRoutePoint{}, "foreign")
	data, err := s.parkedHistoryBounds(context.Background(), user, car, older, newer)
	if err != nil {
		t.Fatal(err)
	}
	charges := data["linked_charges"].([]map[string]any)
	if len(charges) != 2 || data["charge_sessions_complete"] != true ||
		charges[0]["charge_id"] != first || charges[1]["charge_id"] != second ||
		charges[0]["energy_added_kwh"] != 5.0 || charges[1]["energy_added_kwh"] != 0.0 ||
		charges[0]["start_battery_level"] != 30 || charges[0]["end_battery_level"] != 55 ||
		charges[0]["start_soc_observed_at"] != firstObserved.Format(time.RFC3339Nano) ||
		charges[0]["end_soc_observed_at"] != lastObserved.Format(time.RFC3339Nano) {
		t.Fatalf("scoped split-charge read failed: %#v", data)
	}
}

func TestParkedHistoryBoundsRejectsUnsafePairs(t *testing.T) {
	db := boundedHistoryTestStore(t)
	for _, category := range []string{"mixed_source", "mixed_instance", "quarantined", "short", "overlap", "open_endpoint", "reverse", "middle_short", "middle_quarantined", "middle_open", "preceding_open", "earlier_overlap", "later_overlap"} {
		t.Run(category, func(t *testing.T) {
			s, user, car, older, newer, base := parkedHistoryTestPair(t, db)
			var query string
			extraID := 0
			switch category {
			case "mixed_source":
				query = "source='telemetry_mqtt'"
			case "mixed_instance":
				query = "source_instance_id='other-instance'"
			case "quarantined":
				query = "quality_state='quarantined'"
			case "short":
				query = "odometer_end=odometer_start+0.49"
			case "overlap":
				query = "started_at=started_at-interval '50 minutes'"
			case "open_endpoint":
				query = "ended_at=NULL"
			case "reverse":
				older, newer = newer, older
			default:
				when := base.Add(30 * time.Minute)
				if category == "preceding_open" {
					when = base.Add(-time.Hour)
				}
				if category == "earlier_overlap" {
					when = base.Add(-10 * time.Minute)
				}
				if category == "later_overlap" {
					when = base.Add(time.Hour + 10*time.Minute)
				}
				id := boundedHistoryInsertSession(t, db, user, car, "drive", when, "teslamate_archive", "observed", []telemetryRoutePoint{}, "middle")
				extraID = id
				change := "odometer_end=odometer_start+0.1"
				if category == "middle_quarantined" {
					change = "quality_state='quarantined'"
				}
				if category == "middle_open" || category == "preceding_open" {
					change = "ended_at=NULL"
				}
				if _, err := db.pool.Exec(context.Background(), "UPDATE jourvolt_telemetry_sessions SET "+change+" WHERE user_id=$1 AND vehicle_id=$2 AND public_id=$3", user, car, id); err != nil {
					t.Fatal(err)
				}
			}
			if query != "" {
				if _, err := db.pool.Exec(context.Background(), "UPDATE jourvolt_telemetry_sessions SET "+query+" WHERE user_id=$1 AND vehicle_id=$2 AND public_id=$3", user, car, newer); err != nil {
					t.Fatal(err)
				}
			}
			if data, err := s.parkedHistoryBounds(context.Background(), user, car, older, newer); err != nil || data != nil {
				t.Fatalf("unsafe %s accepted: %v %v", category, data, err)
			}
			if category == "earlier_overlap" || category == "later_overlap" {
				memory := newTelemetryServiceForTest("example.test")
				if err := memory.memory.registerVehicle(telemetryVehicleRef{UserID: user, VehicleID: car, VINHash: "overlap-fixture"}); err != nil {
					t.Fatal(err)
				}
				for _, id := range []int{older, newer, extraID} {
					row, ok, err := s.historyDetailPostgresBounded(context.Background(), user, car, "drive", id)
					if err != nil || !ok {
						t.Fatal(err)
					}
					memory.memory.completed[telemetryKey{UserID: user, VehicleID: car}] = append(memory.memory.completed[telemetryKey{UserID: user, VehicleID: car}], row)
				}
				if data, err := memory.parkedHistoryBounds(context.Background(), user, car, older, newer); err != nil || data != nil {
					t.Fatalf("memory overlap accepted %v %v", data, err)
				}
			}
		})
	}
}
