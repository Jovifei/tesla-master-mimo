package main

import (
	"context"
	"errors"
	"fmt"
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

func TestTask2PostgresPairingConfigTruthRoundTripsAndSurvivesMQTTStatus(t *testing.T) {
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

	userID := "telemetry_task2_pairing_" + mustRandomToken(t)
	if err := database.ensureUser(ctx, userID); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = database.deleteUser(context.Background(), userID) })
	var vehicleID int
	if err := database.pool.QueryRow(ctx, `
INSERT INTO jourvolt_vehicles(user_id, provider_vehicle_id, vin_ciphertext, display_name, state, updated_at)
VALUES ($1, $2, 'ciphertext', 'Test Tesla', 'online', now())
RETURNING id`, userID, "task2-pairing-provider-"+mustRandomToken(t)).Scan(&vehicleID); err != nil {
		t.Fatal(err)
	}
	service := &telemetryService{store: database, config: &telemetryConfig{PartnerDomain: "partner.example.com"}}
	ref := telemetryVehicleRef{UserID: userID, VehicleID: vehicleID, VINHash: "task2-pairing-vin-" + mustRandomToken(t)}
	if err := service.registerVehicle(ctx, ref); err != nil {
		t.Fatal(err)
	}

	if result := service.updateStatusForVIN(ctx, ref.VINHash, "collecting"); result.Classification != telemetryPersistenceDurable {
		t.Fatalf("unknown status persistence = %#v", result)
	}
	if response, err := service.pairing(ctx, userID, vehicleID); err != nil || response.ConfigSynced != nil {
		t.Fatalf("Postgres unknown config truth = %#v, %v", response, err)
	}

	service.setPairingConfigTruth(ctx, userID, vehicleID, "available", true)
	if result := service.updateStatusForVIN(ctx, ref.VINHash, "waiting_vehicle"); result.Classification != telemetryPersistenceDurable {
		t.Fatalf("true status persistence = %#v", result)
	}
	if response, err := service.pairing(ctx, userID, vehicleID); err != nil || response.ConfigSynced == nil || !*response.ConfigSynced || response.Status != "waiting_vehicle" {
		t.Fatalf("Postgres true config roundtrip = %#v, %v", response, err)
	}

	service.setPairingConfigTruth(ctx, userID, vehicleID, "waiting_vehicle", false)
	if result := service.updateStatusForVIN(ctx, ref.VINHash, "telemetry_error"); result.Classification != telemetryPersistenceDurable {
		t.Fatalf("false status persistence = %#v", result)
	}
	if response, err := service.pairing(ctx, userID, vehicleID); err != nil || response.ConfigSynced == nil || *response.ConfigSynced || response.Status != "telemetry_error" {
		t.Fatalf("Postgres false config roundtrip = %#v, %v", response, err)
	}

	var nullable string
	if err := database.pool.QueryRow(ctx, `SELECT is_nullable FROM information_schema.columns WHERE table_name='jourvolt_telemetry_pairing' AND column_name='config_synced'`).Scan(&nullable); err != nil || nullable != "YES" {
		t.Fatalf("config_synced schema nullable=%q err=%v", nullable, err)
	}
}

func TestTask1PostgresMappingDeleteWaitsForAtomicTelemetryPersistence(t *testing.T) {
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

	userID := "telemetry_mapping_lock_" + mustRandomToken(t)
	if err := database.ensureUser(ctx, userID); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = database.deleteUser(context.Background(), userID) })
	var vehicleID int
	if err := database.pool.QueryRow(ctx, `
INSERT INTO jourvolt_vehicles(user_id, provider_vehicle_id, vin_ciphertext, display_name, state, updated_at)
VALUES ($1, $2, 'ciphertext', 'Test Tesla', 'online', now())
RETURNING id`, userID, "mapping-lock-provider-"+mustRandomToken(t)).Scan(&vehicleID); err != nil {
		t.Fatal(err)
	}
	ref := telemetryVehicleRef{UserID: userID, VehicleID: vehicleID, VINHash: "mapping-lock-vin-" + mustRandomToken(t)}
	service := &telemetryService{store: database, config: &telemetryConfig{StopDebounce: defaultDriveStopDebounce}}
	if err := service.registerVehicle(ctx, ref); err != nil {
		t.Fatal(err)
	}

	lockKey := time.Now().UnixNano()
	functionName := fmt.Sprintf("jourvolt_task1_mapping_lock_%d", lockKey)
	triggerName := fmt.Sprintf("jourvolt_task1_mapping_trigger_%d", lockKey)
	if _, err := database.pool.Exec(ctx, fmt.Sprintf(`
CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(%d);
  PERFORM pg_sleep(0.5);
  RETURN NEW;
END;
$$`, functionName, lockKey)); err != nil {
		t.Fatal(err)
	}
	if _, err := database.pool.Exec(ctx, fmt.Sprintf(`CREATE TRIGGER %s BEFORE INSERT ON jourvolt_telemetry_event_buffer FOR EACH ROW EXECUTE FUNCTION %s()`, triggerName, functionName)); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_, _ = database.pool.Exec(context.Background(), fmt.Sprintf("DROP TRIGGER IF EXISTS %s ON jourvolt_telemetry_event_buffer", triggerName))
		_, _ = database.pool.Exec(context.Background(), fmt.Sprintf("DROP FUNCTION IF EXISTS %s()", functionName))
	})

	result := make(chan telemetryPersistenceResult, 1)
	go func() {
		result <- service.persistMQTTRecord(ctx, telemetryRecord{VINHash: ref.VINHash, FieldName: "Soc", Value: float64(42), ObservedAt: time.Now().UTC(), EventID: "mapping-lock-event"})
	}()
	deadline := time.Now().Add(2 * time.Second)
	for {
		var acquired bool
		if err := database.pool.QueryRow(ctx, `SELECT pg_try_advisory_xact_lock($1)`, lockKey).Scan(&acquired); err != nil {
			t.Fatal(err)
		}
		if !acquired {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("telemetry persistence did not reach the deterministic concurrent-delete window")
		}
		time.Sleep(5 * time.Millisecond)
	}

	deleteCtx, cancelDelete := context.WithTimeout(ctx, 100*time.Millisecond)
	defer cancelDelete()
	if _, err := database.pool.Exec(deleteCtx, `DELETE FROM jourvolt_telemetry_vehicle_keys WHERE user_id=$1 AND vehicle_id=$2`, ref.UserID, ref.VehicleID); err == nil || !errors.Is(deleteCtx.Err(), context.DeadlineExceeded) {
		t.Fatalf("mapping delete err = %v, want a deadline while atomic telemetry persistence holds the mapping lock", err)
	}
	select {
	case outcome := <-result:
		if outcome.Classification != telemetryPersistenceDurable {
			t.Fatalf("atomic telemetry persistence outcome = %#v, want durable", outcome)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("atomic telemetry persistence did not complete")
	}
	var mapped bool
	if err := database.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM jourvolt_telemetry_vehicle_keys WHERE user_id=$1 AND vehicle_id=$2)`, ref.UserID, ref.VehicleID).Scan(&mapped); err != nil {
		t.Fatal(err)
	}
	if !mapped {
		t.Fatal("timed-out concurrent delete removed the mapping")
	}
}
