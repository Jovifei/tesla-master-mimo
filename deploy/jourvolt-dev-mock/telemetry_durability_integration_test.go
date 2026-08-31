package main

import (
	"context"
	"os"
	"testing"
	"time"
)

func TestTelemetryPostgresQoS1RedeliverySurvivesRestartWithoutAdvancingOrCompletingTwice(t *testing.T) {
	dsn := os.Getenv("JOURVOLT_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("JOURVOLT_TEST_DATABASE_URL is not set")
	}
	ctx := context.Background()
	database, err := openStore(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(database.close)

	userID := "telemetry_durability_" + mustRandomToken(t)
	if err := database.ensureUser(ctx, userID); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = database.deleteUser(context.Background(), userID) })
	var vehicleID int
	if err := database.pool.QueryRow(ctx, `
INSERT INTO jourvolt_vehicles(user_id, provider_vehicle_id, vin_ciphertext, display_name, state, updated_at)
VALUES ($1, $2, 'ciphertext', 'Test Tesla', 'online', now())
RETURNING id`, userID, "durability-provider-"+mustRandomToken(t)).Scan(&vehicleID); err != nil {
		t.Fatal(err)
	}
	ref := telemetryVehicleRef{UserID: userID, VehicleID: vehicleID, VINHash: "durability-vin-hash-" + mustRandomToken(t)}
	service := &telemetryService{store: database, config: &telemetryConfig{StopDebounce: defaultDriveStopDebounce}}
	if err := service.registerVehicle(ctx, ref); err != nil {
		t.Fatal(err)
	}

	start := time.Date(2026, time.August, 30, 2, 0, 0, 0, time.UTC)
	first := telemetryRecord{VINHash: ref.VINHash, FieldName: "DetailedChargeState", Value: "Charging", ObservedAt: start, EventID: "first"}
	if accepted, err := service.ingest(ctx, first); err != nil || accepted != 1 {
		t.Fatalf("first delivery accepted=%d err=%v", accepted, err)
	}
	redelivery := first
	redelivery.EventID = "redelivery"
	redelivery.ObservedAt = start.Add(time.Minute)
	if accepted, err := service.ingest(ctx, redelivery); err != nil || accepted != 0 {
		t.Fatalf("identical QoS1 redelivery accepted=%d err=%v", accepted, err)
	}
	var observedAt time.Time
	if err := database.pool.QueryRow(ctx, `SELECT observed_at FROM jourvolt_telemetry_latest WHERE user_id=$1 AND vehicle_id=$2 AND field_name='DetailedChargeState'`, userID, vehicleID).Scan(&observedAt); err != nil {
		t.Fatal(err)
	}
	if !observedAt.Equal(start) {
		t.Fatalf("identical redelivery advanced persisted observed_at to %s; want %s", observedAt, start)
	}

	afterRestart := &telemetryService{store: database, config: &telemetryConfig{StopDebounce: defaultDriveStopDebounce}}
	complete := telemetryRecord{VINHash: ref.VINHash, FieldName: "DetailedChargeState", Value: "Complete", ObservedAt: start.Add(2 * time.Minute), EventID: "complete"}
	if accepted, err := afterRestart.ingest(ctx, complete); err != nil || accepted != 1 {
		t.Fatalf("completion after restart accepted=%d err=%v", accepted, err)
	}
	completedRestart := &telemetryService{store: database, config: &telemetryConfig{StopDebounce: defaultDriveStopDebounce}}
	complete.EventID = "complete-redelivery"
	complete.ObservedAt = start.Add(3 * time.Minute)
	if accepted, err := completedRestart.ingest(ctx, complete); err != nil || accepted != 0 {
		t.Fatalf("completion QoS1 redelivery accepted=%d err=%v", accepted, err)
	}
	var sessions int
	var completionKey string
	if err := database.pool.QueryRow(ctx, `SELECT count(*), coalesce(max(completion_key), '') FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2 AND kind='charge' AND ended_at IS NOT NULL`, userID, vehicleID).Scan(&sessions, &completionKey); err != nil {
		t.Fatal(err)
	}
	if sessions != 1 || completionKey == "" {
		t.Fatalf("completed sessions=%d completion_key=%q", sessions, completionKey)
	}
}
