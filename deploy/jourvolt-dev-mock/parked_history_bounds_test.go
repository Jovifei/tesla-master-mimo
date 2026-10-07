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
	if data["start_date"] != base.Add(17*time.Minute).Format(time.RFC3339) || data["end_date"] != base.Add(time.Hour).Format(time.RFC3339) || data["start_battery_level"] != 0 || data["end_battery_level"] != 10 || data["battery_delta"] != -10 {
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
