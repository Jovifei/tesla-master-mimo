package main

import (
	"context"
	"encoding/json"
	"errors"
	"math"
	"reflect"
	"runtime"
	"testing"
	"time"
)

func TestHistorySampleMetricsObservedZeroUnknownAndImportClimate(t *testing.T) {
	zero, eighty, invalid := 0, 80, 101
	zeroSpeed, speed, negative := 0.0, 80.0, -1.0
	inside, outside := 22.0, -4.0
	start := time.Date(2026, 10, 7, 0, 0, 0, 0, time.UTC)
	request := historyImportSession{SessionID: "sample", StartedAt: start.Format(time.RFC3339), EndedAt: start.Add(time.Minute).Format(time.RFC3339),
		Route: []historyImportRoutePoint{
			{Date: start.Format(time.RFC3339), Latitude: floatPointerForHistoryTest(31), Longitude: floatPointerForHistoryTest(121), Speed: &zeroSpeed, BatteryLevel: &zero, ClimateInfo: &historyImportClimateInfo{InsideTemp: &inside, OutsideTemp: &outside}},
			{Date: start.Add(time.Minute).Format(time.RFC3339), Latitude: floatPointerForHistoryTest(31), Longitude: floatPointerForHistoryTest(121), Speed: &speed, BatteryLevel: &eighty},
		},
		ChargePoints: []historyImportChargePoint{{Date: start.Format(time.RFC3339), BatteryLevel: &invalid}, {Date: start.Format(time.RFC3339), BatteryLevel: &zero, OutsideTemp: &outside}, {Date: start.Add(time.Minute).Format(time.RFC3339), BatteryLevel: &eighty}},
	}
	for _, source := range []string{"teslamate", "local_import"} {
		session, err := request.toTelemetrySession("sample-user", 1, "drive", source, "instance", "car")
		if err != nil {
			t.Fatal(err)
		}
		item := historySessionMap(session, "drive", 0)
		if item["speed_max"] != 80 || item["speed_avg"] != 40.0 || item["speed_sample_count"] != 2 || item["inside_temp_avg"] != 22.0 || item["outside_temp_avg"] != -4.0 {
			t.Fatalf("observed metrics lost: %v", item)
		}
		climate := item["drive_details"].([]map[string]any)[0]["climate_info"].(map[string]any)
		if climate["inside_temp"] != 22.0 || climate["outside_temp"] != -4.0 {
			t.Fatal("detail climate missing")
		}
	}
	charge, err := request.toTelemetrySession("sample-user", 1, "charge", "teslamate", "instance", "car")
	if err != nil {
		t.Fatal(err)
	}
	item := historySessionMap(charge, "charge", 0)
	if !reflect.DeepEqual(item["battery_details"], map[string]any{"start_battery_level": 0, "end_battery_level": 80}) {
		t.Fatalf("charge SOC=%v", item["battery_details"])
	}
	missing := historySessionMap(telemetrySession{Source: "teslamate_archive", ArchiveRoute: []historyImportRoutePoint{{Speed: &negative}}}, "drive", 0)
	if missing["speed_max"] != nil || missing["speed_avg"] != nil || missing["energy_consumed_net"] != nil || missing["outside_temp_avg"] != nil {
		t.Fatal("unknown became measured")
	}
	nonfinite := math.Inf(1)
	if finiteHistorySample(&nonfinite) != nil {
		t.Fatal("nonfinite sample accepted")
	}
}

func TestHistorySampleMetricsPostgresPageDetailChargeSOCAndScope(t *testing.T) {
	database := boundedHistoryTestStore(t)
	user, car := boundedHistoryTestTenant(t, database, "sample-fields")
	other, otherCar := boundedHistoryTestTenant(t, database, "sample-other")
	base := time.Date(2026, 10, 7, 0, 0, 0, 0, time.UTC)
	zero, forty := 0, 40
	speed0, speed80, inside, outside := 0.0, 80.0, 22.0, -4.0
	route := []historyImportRoutePoint{
		{Date: base.Format(time.RFC3339), Latitude: floatPointerForHistoryTest(31), Longitude: floatPointerForHistoryTest(121), BatteryLevel: &zero, Speed: &speed0, ClimateInfo: &historyImportClimateInfo{InsideTemp: &inside, OutsideTemp: &outside}},
		{Date: base.Add(time.Minute).Format(time.RFC3339), Latitude: floatPointerForHistoryTest(31), Longitude: floatPointerForHistoryTest(121), BatteryLevel: &forty, Speed: &speed80},
	}
	driveID := boundedHistoryInsertSession(t, database, user, car, "drive", base, "teslamate_archive", "observed", route, "sample-drive")
	boundedHistoryInsertSession(t, database, other, otherCar, "drive", base, "teslamate_archive", "observed", route, "sample-drive")
	chargeID := boundedHistoryInsertSession(t, database, user, car, "charge", base, "teslamate_archive", "observed", []telemetryRoutePoint{}, "sample-charge")
	bad := 101
	points := []telemetryChargePoint{{ObservedAt: base, BatteryLevel: &bad}, {ObservedAt: base, BatteryLevel: &zero, OutsideTemp: &outside}, {ObservedAt: base.Add(time.Minute), BatteryLevel: &forty}}
	encoded, err := json.Marshal(points)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := database.pool.Exec(context.Background(), `UPDATE jourvolt_telemetry_sessions SET charge_points_json=$1::jsonb WHERE user_id=$2 AND vehicle_id=$3 AND public_id=$4`, encoded, user, car, chargeID); err != nil {
		t.Fatal(err)
	}
	service := &telemetryService{store: database}
	for _, test := range []struct {
		kind string
		id   int
	}{{"drive", driveID}, {"charge", chargeID}} {
		rows, _, total, _, err := service.historyPageContext(context.Background(), user, car, test.kind, time.Time{}, time.Time{}, 1, 1)
		if err != nil || total != 1 || len(rows) != 1 {
			t.Fatalf("page kind=%s total=%d err=%v", test.kind, total, err)
		}
		detail, ok, err := service.historyDetailContext(context.Background(), user, car, test.kind, test.id)
		if err != nil || !ok {
			t.Fatalf("detail=%v err=%v", ok, err)
		}
		if !reflect.DeepEqual(rows[0]["battery_details"], detail["battery_details"]) || rows[0]["outside_temp_avg"] != detail["outside_temp_avg"] {
			t.Fatalf("page/detail mismatch: %v / %v", rows[0], detail)
		}
		if rows[0]["outside_temp_avg"] != -4.0 {
			t.Fatal("observed negative temperature lost")
		}
		if test.kind == "drive" {
			for _, key := range []string{"speed_max", "speed_avg", "speed_sample_count", "inside_temp_avg"} {
				if rows[0][key] != detail[key] {
					t.Fatalf("%s mismatch", key)
				}
			}
			if rows[0]["speed_max"] != 80 || rows[0]["speed_avg"] != 40.0 || rows[0]["energy_consumed_net"] != nil {
				t.Fatal("drive summary incorrectly measured")
			}
		}
	}
	rows, _, total, _, err := service.historyPageContext(context.Background(), other, car, "drive", time.Time{}, time.Time{}, 1, 1)
	if err != nil || total != 0 || len(rows) != 0 {
		t.Fatal("cross-account field leakage")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, _, _, _, err := service.historyPageContext(ctx, user, car, "charge", time.Time{}, time.Time{}, 1, 1); !errors.Is(err, context.Canceled) {
		t.Fatal("canceled read admitted")
	}
}

func TestHistorySampleMetricsPostgres200kScalarResourcesAndDeadline(t *testing.T) {
	db := boundedHistoryTestStore(t)
	user, car := boundedHistoryTestTenant(t, db, "sample-200k")
	base := time.Date(2026, 10, 7, 0, 0, 0, 0, time.UTC)
	boundedHistoryInsertSession(t, db, user, car, "drive", base, "teslamate_archive", "observed", []historyImportRoutePoint{}, "large-fields")
	if _, err := db.pool.Exec(context.Background(), `UPDATE jourvolt_telemetry_sessions SET route_json=(
 SELECT jsonb_agg(jsonb_build_object('date','2026-10-07T00:00:00Z','latitude',31,'longitude',121,
 'battery_level',CASE WHEN i=200000 THEN 0 ELSE 80 END,'speed',80,'inside_temp',20,'outside_temp',-4))
 FROM generate_series(1,200000) i) WHERE user_id=$1 AND vehicle_id=$2`, user, car); err != nil {
		t.Fatal(err)
	}
	s := &telemetryService{store: db}
	runtime.GC()
	var before, after runtime.MemStats
	runtime.ReadMemStats(&before)
	started := time.Now()
	rows, _, total, _, err := s.historyPageContext(context.Background(), user, car, "drive", time.Time{}, time.Time{}, 1, 1)
	elapsed := time.Since(started)
	runtime.ReadMemStats(&after)
	if err != nil || total != 1 || len(rows) != 1 {
		t.Fatalf("200k read failed %v", err)
	}
	if rows[0]["speed_sample_count"] != 200000 || rows[0]["speed_max"] != 80 || rows[0]["outside_temp_avg"] != -4.0 || !reflect.DeepEqual(rows[0]["battery_details"], map[string]any{"start_battery_level": 80, "end_battery_level": 0}) {
		t.Fatalf("200k scalars lost %v", rows[0])
	}
	if len(rows[0]["drive_details"].([]map[string]any)) > 2 {
		t.Fatal("full route crossed DB connection")
	}
	allocated := after.TotalAlloc - before.TotalAlloc
	if allocated > 5<<20 {
		t.Fatalf("scalar read allocated %d bytes", allocated)
	}
	t.Logf("synthetic_only points=200000 read_ms=%d go_alloc_bytes=%d", elapsed.Milliseconds(), allocated)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Millisecond)
	defer cancel()
	if _, _, _, _, err := s.historyPageContext(ctx, user, car, "drive", time.Time{}, time.Time{}, 1, 1); !errors.Is(err, context.DeadlineExceeded) && !errors.Is(ctx.Err(), context.DeadlineExceeded) {
		t.Fatalf("deadline not observed: %v", err)
	}
}
