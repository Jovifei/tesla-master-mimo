package main

import (
	"context"
	"testing"
	"time"
)

func TestTelemetryPostgresIdleDriveFinalizationClosesWithoutAnotherEvent(t *testing.T) {
	s, user, car := openHistoryQueryTestDB(t)
	ctx := context.Background()
	s.config = &telemetryConfig{StopDebounce: defaultDriveStopDebounce}
	vin := "timer-" + mustRandomToken(t)
	if err := s.registerVehicle(ctx, telemetryVehicleRef{UserID: user, VehicleID: car, VINHash: vin}); err != nil {
		t.Fatal(err)
	}
	start := time.Date(2026, 9, 4, 0, 0, 0, 0, time.UTC)
	for index, entry := range []struct {
		field string
		value any
	}{{"Gear", "D"}, {"Location", map[string]any{"latitude": 31.0, "longitude": 121.0}}, {"Odometer", float64(100)}, {"Odometer", float64(102)}, {"Gear", "P"}} {
		if n, err := s.ingest(ctx, telemetryRecord{VINHash: vin, FieldName: entry.field, Value: entry.value, ObservedAt: start.Add(time.Duration(index) * time.Second), EventID: entry.field + time.Duration(index).String()}); err != nil || n != 1 {
			t.Fatalf("step%d accepted=%d err=%v", index, n, err)
		}
	}
	var id, rawHash string
	var publicID int
	if err := s.store.pool.QueryRow(ctx, `SELECT id,public_id,md5(route_json::text||charge_points_json::text) FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2`, user, car).Scan(&id, &publicID, &rawHash); err != nil {
		t.Fatal(err)
	}
	due := start.Add(4*time.Second + defaultDriveStopDebounce)
	if n, err := s.finalizeDuePostgres(ctx, due.Add(-time.Second)); err != nil || n != 0 {
		t.Fatalf("early count=%d err=%v", n, err)
	}
	if n, err := s.finalizeDuePostgres(ctx, due); err != nil || n != 1 {
		t.Fatalf("due count=%d err=%v", n, err)
	}
	var end time.Time
	var actualID, completionKey, afterHash string
	var actualPublicID int
	if err := s.store.pool.QueryRow(ctx, `SELECT id,public_id,ended_at,completion_key,md5(route_json::text||charge_points_json::text) FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2`, user, car).Scan(&actualID, &actualPublicID, &end, &completionKey, &afterHash); err != nil {
		t.Fatal(err)
	}
	want := sessionCompletionKey(telemetrySession{ID: id, Kind: "drive", EndAt: &due})
	if !end.Equal(due) || completionKey != want || actualID != id || actualPublicID != publicID || afterHash != rawHash {
		t.Fatal("timer changed identity, exact end boundary or raw history")
	}
	if n, err := s.finalizeDuePostgres(ctx, due.Add(time.Hour)); err != nil || n != 0 {
		t.Fatalf("repeat count=%d err=%v", n, err)
	}
}

func TestTelemetryPostgresIdleDriveFinalizationBoundedAndSkipsLocked(t *testing.T) {
	s, user, _ := openHistoryQueryTestDB(t)
	ctx := context.Background()
	s.config = &telemetryConfig{StopDebounce: defaultDriveStopDebounce}
	const extras = 3
	if _, err := s.store.pool.Exec(ctx, `WITH cars AS (
        INSERT INTO jourvolt_vehicles(user_id,provider_vehicle_id,vin_ciphertext,display_name,state,updated_at)
        SELECT $1,$1||'-timer-'||i,'synthetic','Timer fixture','online',now() FROM generate_series(1,$2::integer) i RETURNING id
    ) INSERT INTO jourvolt_telemetry_sessions(id,user_id,vehicle_id,kind,started_at,stop_candidate_at,source)
    SELECT $1||'-timer-'||id,$1,id,'drive','2026-09-05T00:00:00Z','2026-09-05T00:01:00Z','telemetry_mqtt' FROM cars`, user, maxTelemetryFinalizeBatch+extras); err != nil {
		t.Fatal(err)
	}
	locked, err := s.store.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer locked.Rollback(ctx)
	if _, err = locked.Exec(ctx, `SELECT id FROM jourvolt_telemetry_sessions WHERE user_id=$1 ORDER BY id LIMIT 1 FOR UPDATE`, user); err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 9, 5, 0, 2, 0, 0, time.UTC).Add(defaultDriveStopDebounce)
	canceled, cancel := context.WithCancel(ctx)
	cancel()
	if n, err := s.finalizeDuePostgres(canceled, now); err == nil || n != 0 {
		t.Fatalf("canceled count=%d err=%v", n, err)
	}
	for index, want := range []int{maxTelemetryFinalizeBatch, extras - 1, 0} {
		call, stop := context.WithTimeout(ctx, 2*time.Second)
		n, err := s.finalizeDuePostgres(call, now)
		stop()
		if err != nil || n != want {
			t.Fatalf("batch%d count=%d want=%d err=%v", index, n, want, err)
		}
	}
	if err = locked.Rollback(ctx); err != nil {
		t.Fatal(err)
	}
	if n, err := s.finalizeDuePostgres(ctx, now); err != nil || n != 1 {
		t.Fatalf("released row count=%d err=%v", n, err)
	}
	var completed, uniqueCompletions int
	if err = s.store.pool.QueryRow(ctx, `SELECT count(*),count(DISTINCT completion_key) FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND ended_at IS NOT NULL`, user).Scan(&completed, &uniqueCompletions); err != nil {
		t.Fatal(err)
	}
	if completed != maxTelemetryFinalizeBatch+extras || uniqueCompletions != completed {
		t.Fatalf("completed=%d unique=%d", completed, uniqueCompletions)
	}
}
