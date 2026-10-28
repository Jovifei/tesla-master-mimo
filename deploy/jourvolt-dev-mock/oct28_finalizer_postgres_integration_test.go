package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// Run only with JOURVOLT_TEST_DATABASE_URL pointing at this task's disposable,
// isolated PostgreSQL database. boundedHistoryTestStore skips without that
// explicit variable and uses openStore; all tenants below are synthetic and
// removed by the existing tenant helper. No broker or Fleet API is involved.
func TestOct28FinalizerPostgresIngestPersistsCompletedHistoryAcrossRestart(t *testing.T) {
	database := boundedHistoryTestStore(t)
	userA, vehicleA := boundedHistoryTestTenant(t, database, "oct28-finalizer-a")
	userB, vehicleB := boundedHistoryTestTenant(t, database, "oct28-finalizer-b")
	ctx := context.Background()
	config := &telemetryConfig{StopDebounce: defaultDriveStopDebounce}
	service := &telemetryService{store: database, config: config}
	refA := telemetryVehicleRef{UserID: userA, VehicleID: vehicleA, VINHash: "oct28-finalizer-hash-a-" + mustRandomToken(t)}
	refB := telemetryVehicleRef{UserID: userB, VehicleID: vehicleB, VINHash: "oct28-finalizer-hash-b-" + mustRandomToken(t)}
	for _, ref := range []telemetryVehicleRef{refA, refB} {
		if err := service.registerVehicle(ctx, ref); err != nil {
			t.Fatal(err)
		}
	}
	ingestGear := func(ref telemetryVehicleRef, gear string, at time.Time) {
		t.Helper()
		record := telemetryRecord{
			VINHash: ref.VINHash, FieldName: "Gear", Value: gear,
			ObservedAt: at, EventID: "oct28-finalizer-event-" + mustRandomToken(t), Source: "telemetry_mqtt",
		}
		if accepted, err := service.ingest(ctx, record); err != nil || accepted != 1 {
			t.Fatalf("synthetic Gear %s ingest: accepted=%d err=%v", gear, accepted, err)
		}
	}
	// PostgreSQL timestamps have microsecond precision. Distinct observation
	// times also keep these synthetic native session IDs independent of other tests.
	start := time.Now().UTC().Truncate(time.Microsecond)
	parked := start.Add(time.Minute)
	end := parked.Add(20 * time.Second)
	ingestGear(refA, "D", start)
	ingestGear(refA, "P", parked)
	// A different tenant's later candidate must remain open when A becomes due.
	startB, parkedB := start.Add(time.Hour), parked.Add(time.Hour)
	ingestGear(refB, "D", startB)
	ingestGear(refB, "P", parkedB)

	readSession := func(ref telemetryVehicleRef) (int, time.Time, *time.Time, *time.Time, string) {
		t.Helper()
		var publicID int
		var startedAt time.Time
		var endedAt, candidate *time.Time
		var completionKey string
		if err := database.pool.QueryRow(ctx, `
SELECT public_id, started_at, ended_at, stop_candidate_at, COALESCE(completion_key,'')
FROM jourvolt_telemetry_sessions
WHERE user_id=$1 AND vehicle_id=$2 AND kind='drive'
`, ref.UserID, ref.VehicleID).Scan(&publicID, &startedAt, &endedAt, &candidate, &completionKey); err != nil {
			t.Fatal(err)
		}
		return publicID, startedAt, endedAt, candidate, completionKey
	}
	// Exercise the production history handler with real PostgreSQL reads; this
	// is handler integration, not an authenticated HTTP server or provider test.
	assertHistory := func(reader *telemetryService, user string, car int, expectedIDs ...int) {
		t.Helper()
		var completedRows int
		if err := database.pool.QueryRow(ctx, `
SELECT COUNT(*) FROM jourvolt_telemetry_sessions
WHERE user_id=$1 AND vehicle_id=$2 AND kind='drive' AND ended_at IS NOT NULL
`, user, car).Scan(&completedRows); err != nil {
			t.Fatal(err)
		}
		if completedRows != len(expectedIDs) {
			t.Fatalf("completed database rows=%d, want %d", completedRows, len(expectedIDs))
		}
		path := fmt.Sprintf("/api/v1/cars/%d/drives?page=1&show=10", car)
		recorder := httptest.NewRecorder()
		api := &app{telemetry: reader}
		api.telemetryHistory(recorder, httptest.NewRequest(http.MethodGet, path, nil), user, car, "drive", nil)
		if recorder.Code != http.StatusOK {
			t.Fatalf("history handler status=%d", recorder.Code)
		}
		var envelope struct {
			Data struct {
				Drives []struct {
					ID      int     `json:"drive_id"`
					EndDate *string `json:"end_date"`
				} `json:"drives"`
				Meta struct {
					Total int `json:"total"`
				} `json:"meta"`
			} `json:"data"`
		}
		if err := json.Unmarshal(recorder.Body.Bytes(), &envelope); err != nil {
			t.Fatal(err)
		}
		if len(envelope.Data.Drives) != len(expectedIDs) || envelope.Data.Meta.Total != len(expectedIDs) {
			t.Fatalf("visible history count=%d total=%d, want %d", len(envelope.Data.Drives), envelope.Data.Meta.Total, len(expectedIDs))
		}
		for index, id := range expectedIDs {
			if drive := envelope.Data.Drives[index]; drive.ID != id || drive.EndDate == nil {
				t.Fatalf("history row=%+v, want completed drive %d", drive, id)
			}
		}
	}

	publicID, startedAt, endedAt, candidate, completionKey := readSession(refA)
	if publicID <= 0 || !startedAt.Equal(start) || endedAt != nil || candidate == nil || !candidate.Equal(parked) || completionKey != "" {
		t.Fatal("Gear D -> P did not persist exactly one open drive with its parking candidate")
	}
	assertHistory(service, userA, vehicleA)
	assertHistory(service, userB, vehicleB)
	if completed, err := service.finalizeDuePostgres(ctx, end.Add(-time.Microsecond)); err != nil || completed != 0 {
		t.Fatalf("before 20-second deadline: completed=%d err=%v", completed, err)
	}
	_, _, endedAt, candidate, completionKey = readSession(refA)
	if endedAt != nil || candidate == nil || !candidate.Equal(parked) || completionKey != "" {
		t.Fatal("not-due finalizer changed the open drive")
	}
	assertHistory(service, userA, vehicleA)

	// A new service instance resumes from PostgreSQL rather than memory state.
	restarted := &telemetryService{store: database, config: config}
	if completed, err := restarted.finalizeDuePostgres(ctx, end); err != nil || completed != 1 {
		t.Fatalf("at 20-second deadline: completed=%d err=%v", completed, err)
	}
	persistedID, persistedStart, persistedEnd, candidate, persistedKey := readSession(refA)
	wantKey := sessionCompletionKey(telemetrySession{ID: sessionID("drive", start), Kind: "drive", StartAt: start, EndAt: &end})
	if persistedID != publicID || !persistedStart.Equal(start) || persistedEnd == nil || !persistedEnd.Equal(end) || candidate != nil || persistedKey == "" || persistedKey != wantKey {
		t.Fatal("due finalizer did not persist the original drive ID, deadline and completion key")
	}
	assertHistory(restarted, userA, vehicleA, publicID)
	assertHistory(restarted, userB, vehicleB)
	assertHistory(restarted, userB, vehicleA)
	assertHistory(restarted, userA, vehicleB)

	if completed, err := restarted.finalizeDuePostgres(ctx, end.Add(time.Second)); err != nil || completed != 0 {
		t.Fatalf("repeat finalizer: completed=%d err=%v", completed, err)
	}
	repeatedID, _, repeatedEnd, _, repeatedKey := readSession(refA)
	if repeatedID != publicID || repeatedEnd == nil || !repeatedEnd.Equal(end) || repeatedKey != persistedKey {
		t.Fatal("repeat finalizer changed the persisted completion")
	}
	assertHistory(restarted, userA, vehicleA, publicID)
	_, _, endedB, candidateB, keyB := readSession(refB)
	if endedB != nil || candidateB == nil || !candidateB.Equal(parkedB) || keyB != "" {
		t.Fatal("finalizer prematurely completed another tenant's later candidate")
	}
}
