package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func parkedTestPair() (telemetrySession, telemetrySession) {
	start := time.Date(2026, 1, 1, 8, 0, 0, 123000000, time.UTC)
	olderEnd, newerStart, newerEnd := start.Add(30*time.Minute), start.Add(2*time.Hour), start.Add(150*time.Minute)
	oldAddress, newAddress := "Synthetic previous endpoint", "Synthetic next endpoint"
	older := telemetrySession{ID: "parked-old", PublicID: 11, Kind: "drive", StartAt: start, EndAt: &olderEnd,
		Source: "teslamate_archive", QualityState: "observed", SourceInstanceID: "source-a", SourceVehicleID: "car-a", EndAddress: &oldAddress}
	newer := telemetrySession{ID: "parked-new", PublicID: 12, Kind: "drive", StartAt: newerStart, EndAt: &newerEnd,
		Source: "teslamate_archive", QualityState: "observed", SourceInstanceID: "source-a", SourceVehicleID: "car-a", StartAddress: &newAddress}
	return older, newer
}

func parkedMemoryFixture() (*app, telemetrySession, telemetrySession) {
	older, newer := parkedTestPair()
	s := newTelemetryServiceForTest("example.test")
	s.memory.completed[telemetryKey{UserID: "owner", VehicleID: 7}] = []telemetrySession{newer, older}
	// A nil provider makes accidental live-discovery fallback fail this test.
	return &app{telemetry: s}, older, newer
}

func parkedAdapterRequest(a *app, method, path, user string) *httptest.ResponseRecorder {
	w := httptest.NewRecorder()
	a.adapterResource(w, httptest.NewRequest(method, path, nil), user, path)
	return w
}

func TestParkedIntervalHTTPReturnsKnownEndpointsWithoutInventingTelemetry(t *testing.T) {
	a, older, newer := parkedMemoryFixture()
	// This fixture also reaches the historical hard-coded response on the old
	// route, so the same test can show a data-loss RED rather than a nil panic.
	// The other route tests keep provider nil to reject discovery fallback.
	a.provider = testProvider{vehicles: map[string][]vehicle{"owner": {{ID: 7}}}}
	w := parkedAdapterRequest(a, "GET", "/api/matelink/v1/cars/7/parked/11/12", "owner")
	var body struct {
		Data map[string]any `json:"data"`
	}
	if w.Code != http.StatusOK || json.Unmarshal(w.Body.Bytes(), &body) != nil || body.Data == nil {
		t.Fatalf("known pair: status=%d body=%s", w.Code, w.Body.String())
	}
	d := body.Data
	if d["source"] != parkedIntervalSource || d["start_date"] != older.EndAt.Format(time.RFC3339Nano) ||
		d["end_date"] != newer.StartAt.Format(time.RFC3339Nano) || d["start_address"] != *older.EndAddress || d["end_address"] != *newer.StartAddress {
		t.Fatalf("wrong interval or endpoint provenance: %+v", d)
	}
	for _, key := range []string{"address", "start_battery_level", "end_battery_level", "battery_delta", "energy_kwh", "average_power_kw", "peak_power_kw", "inside_temp_average", "outside_temp_average", "linked_charge"} {
		value, exists := d[key]
		if !exists || value != nil {
			t.Errorf("%s must be explicit null; got %#v", key, value)
		}
	}
	for _, key := range []string{"sample_count", "coverage_seconds", "coverage_ratio"} {
		if d[key] != float64(0) {
			t.Errorf("no stationary observation: %s=%#v", key, d[key])
		}
	}
}

func TestParkedIntervalHTTPRejectsInvalidPathAndScope(t *testing.T) {
	a, _, _ := parkedMemoryFixture()
	for _, path := range []string{
		"/api/matelink/v1/cars/7/parked", "/api/matelink/v1/cars/7/parked/11/12/extra",
		"/api/matelink/v1/cars/7/parked/0/12", "/api/matelink/v1/cars/7/parked/-11/12",
		"/api/matelink/v1/cars/7/parked/11/no", "/api/matelink/v1/cars/7/parked/11/2147483648",
		"/api/matelink/v1/cars/7/parked/11/11", "/api/matelink/v1/cars/7/parked/12/11",
		"/api/matelink/v1/cars/7/parked/011/12", "/api/matelink/v1/cars/07/parked/11/12",
		"/api/matelink/v1/cars/0/parked/11/12", "/api/matelink/v1/cars/8/parked/11/12",
		"/api/matelink/v1/cars/7/parked/11/99",
	} {
		t.Run(path, func(t *testing.T) {
			if w := parkedAdapterRequest(a, "GET", path, "owner"); w.Code != http.StatusNotFound {
				t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
			}
		})
	}
	for _, test := range []struct{ method, user string }{{"POST", "owner"}, {"GET", "other-owner"}} {
		if w := parkedAdapterRequest(a, test.method, "/api/matelink/v1/cars/7/parked/11/12", test.user); w.Code != http.StatusNotFound {
			t.Fatalf("invalid request %+v status=%d", test, w.Code)
		}
	}
}

func TestParkedIntervalRejectsUnsafePairs(t *testing.T) {
	for _, test := range []struct {
		name   string
		change func(*telemetrySession, *telemetrySession)
	}{
		{"open_old", func(a, b *telemetrySession) { a.EndAt = nil }},
		{"open_new", func(a, b *telemetrySession) { b.EndAt = nil }},
		{"overlap", func(a, b *telemetrySession) { b.StartAt = a.StartAt.Add(time.Minute) }},
		{"no_gap", func(a, b *telemetrySession) { b.StartAt = *a.EndAt }},
		{"invalid_old_duration", func(a, b *telemetrySession) { a.EndAt = &a.StartAt }},
		{"charge_endpoint", func(a, b *telemetrySession) { a.Kind = "charge" }},
		{"quarantined_endpoint", func(a, b *telemetrySession) { b.QualityState = "quarantined" }},
		{"different_source", func(a, b *telemetrySession) { b.Source = "telemetry_mqtt" }},
		{"different_instance", func(a, b *telemetrySession) { b.SourceInstanceID = "source-b" }},
		{"different_source_car", func(a, b *telemetrySession) { b.SourceVehicleID = "car-b" }},
		{"missing_archive_identity", func(a, b *telemetrySession) { a.SourceInstanceID = ""; b.SourceInstanceID = "" }},
		{"unrecognized_source", func(a, b *telemetrySession) { a.Source = "unknown"; b.Source = "unknown" }},
		{"empty_source", func(a, b *telemetrySession) { a.Source = ""; b.Source = "" }},
		{"noncanonical_source", func(a, b *telemetrySession) {
			a.Source = " TESLAMATE_ARCHIVE "
			b.Source = " TESLAMATE_ARCHIVE "
			a.SourceInstanceID = ""
			b.SourceInstanceID = ""
		}},
	} {
		t.Run(test.name, func(t *testing.T) {
			a, b := parkedTestPair()
			test.change(&a, &b)
			if _, found, err := parkedIntervalData(a, b, false); err != nil || found {
				t.Fatalf("unsafe pair accepted: found=%t err=%v", found, err)
			}
		})
	}
}

func TestParkedIntervalNeverSkipsHiddenOrOverlappingDrives(t *testing.T) {
	for _, test := range []struct {
		name   string
		change func(*telemetrySession, telemetrySession, telemetrySession)
	}{
		{"short_trip", func(row *telemetrySession, a, b telemetrySession) {}},
		{"quarantined", func(row *telemetrySession, a, b telemetrySession) { row.QualityState = "quarantined" }},
		{"different_source", func(row *telemetrySession, a, b telemetrySession) { row.Source = "telemetry_mqtt" }},
		{"unfinished", func(row *telemetrySession, a, b telemetrySession) { row.EndAt = nil }},
		{"overlapping", func(row *telemetrySession, a, b telemetrySession) { row.StartAt = a.StartAt.Add(-time.Hour) }},
		{"nested_in_older", func(row *telemetrySession, a, b telemetrySession) {
			row.StartAt = a.StartAt.Add(10 * time.Minute)
			end := a.StartAt.Add(20 * time.Minute)
			row.EndAt = &end
		}},
		{"overlap_older_only", func(row *telemetrySession, a, b telemetrySession) {
			row.StartAt = a.StartAt.Add(-time.Hour)
			end := a.StartAt.Add(time.Minute)
			row.EndAt = &end
		}},
		{"duplicate_older_start", func(row *telemetrySession, a, b telemetrySession) { row.StartAt = a.StartAt; row.EndAt = a.EndAt }},
		{"same_start_as_newer", func(row *telemetrySession, a, b telemetrySession) { row.StartAt = b.StartAt }},
	} {
		t.Run(test.name, func(t *testing.T) {
			a, older, newer := parkedMemoryFixture()
			end := older.EndAt.Add(2 * time.Minute)
			row := telemetrySession{ID: "hidden", PublicID: 13, Kind: "drive", StartAt: older.EndAt.Add(time.Minute), EndAt: &end, Source: "teslamate_archive", QualityState: "observed"}
			test.change(&row, older, newer)
			key := telemetryKey{UserID: "owner", VehicleID: 7}
			a.telemetry.memory.completed[key] = append(a.telemetry.memory.completed[key], row)
			if w := parkedAdapterRequest(a, "GET", "/api/matelink/v1/cars/7/parked/11/12", "owner"); w.Code != 404 {
				t.Fatalf("hidden drive ignored: %d %s", w.Code, w.Body.String())
			}
		})
	}
}

func TestParkedIntervalPreservesMissingAddressesAndIgnoresRoutePayload(t *testing.T) {
	a, older, newer := parkedMemoryFixture()
	older.EndAddress = nil
	newer.StartAddress = nil
	older.Route = make([]telemetryRoutePoint, 100000)
	a.telemetry.memory.completed[telemetryKey{UserID: "owner", VehicleID: 7}] = []telemetrySession{older, newer}
	w := parkedAdapterRequest(a, "GET", "/api/matelink/v1/cars/7/parked/11/12", "owner")
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"start_address":null`) || !strings.Contains(w.Body.String(), `"end_address":null`) || strings.Contains(w.Body.String(), "drive_details") {
		t.Fatalf("scalar-only response changed: %d %s", w.Code, w.Body.String())
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, _, err := a.telemetry.parkedInterval(ctx, "owner", 7, 11, 12); err != context.Canceled {
		t.Fatalf("cancellation lost: %v", err)
	}
	if w := parkedAdapterRequest(&app{}, "GET", "/api/matelink/v1/cars/7/parked/11/12", "owner"); w.Code != 503 {
		t.Fatalf("missing store status=%d", w.Code)
	}
}

func TestParkedIntervalAllowsUnrelatedRowsButRejectsOpenMemoryDrive(t *testing.T) {
	a, older, _ := parkedMemoryFixture()
	end := older.EndAt.Add(2 * time.Minute)
	row := telemetrySession{ID: "other", PublicID: 13, Kind: "drive", StartAt: older.EndAt.Add(time.Minute), EndAt: &end}
	a.telemetry.memory.completed[telemetryKey{UserID: "other-owner", VehicleID: 7}] = []telemetrySession{row}
	a.telemetry.memory.completed[telemetryKey{UserID: "owner", VehicleID: 8}] = []telemetrySession{row}
	charge := row
	charge.Kind = "charge"
	key := telemetryKey{UserID: "owner", VehicleID: 7}
	a.telemetry.memory.completed[key] = append(a.telemetry.memory.completed[key], charge)
	path := "/api/matelink/v1/cars/7/parked/11/12"
	if w := parkedAdapterRequest(a, "GET", path, "owner"); w.Code != 200 {
		t.Fatalf("unrelated row blocked interval: %d %s", w.Code, w.Body.String())
	}
	row.EndAt = nil
	a.telemetry.memory.machines[key] = &telemetrySessionMachine{drive: &row}
	if w := parkedAdapterRequest(a, "GET", path, "owner"); w.Code != 404 {
		t.Fatalf("open drive ignored: %d %s", w.Code, w.Body.String())
	}
}

func TestParkedIntervalPostgresAuthenticatedScopeAndNoPayloadDecode(t *testing.T) {
	s, user, car := openHistoryQueryTestDB(t)
	ctx := context.Background()
	older, newer := parkedTestPair()
	for _, row := range []*telemetrySession{&older, &newer} {
		err := s.store.pool.QueryRow(ctx, `INSERT INTO jourvolt_telemetry_sessions
            (id,user_id,vehicle_id,kind,started_at,ended_at,source,quality_state,source_instance_id,source_vehicle_id,source_record_id,start_address,end_address,route_json)
            VALUES($1,$2,$3,'drive',$4,$5,$6,$7,$8,$9,$1,$10,$11,'{"not_an_array":true}'::jsonb) RETURNING public_id`,
			user+row.ID, user, car, row.StartAt, row.EndAt, row.Source, row.QualityState, row.SourceInstanceID, row.SourceVehicleID, row.StartAddress, row.EndAddress).Scan(&row.PublicID)
		if err != nil {
			t.Fatal(err)
		}
	}
	session, err := s.store.createSession(ctx, user)
	if err != nil {
		t.Fatal(err)
	}
	a := &app{store: s.store, telemetry: s}
	path := fmt.Sprintf("/api/matelink/v1/cars/%d/parked/%d/%d", car, older.PublicID, newer.PublicID)
	if w := historyContextRequest(a, "GET", path, session.AccessToken); w.Code != 200 || !strings.Contains(w.Body.String(), parkedIntervalSource) {
		t.Fatalf("scalar PG pair: %d %s", w.Code, w.Body.String())
	}
	if w := historyContextRequest(a, "GET", path, ""); w.Code != 401 {
		t.Fatalf("unauthenticated status=%d", w.Code)
	}
	_, other, _ := openHistoryQueryTestDB(t)
	otherSession, err := s.store.createSession(ctx, other)
	if err != nil {
		t.Fatal(err)
	}
	if w := historyContextRequest(a, "GET", path, otherSession.AccessToken); w.Code != 404 {
		t.Fatalf("foreign owner status=%d", w.Code)
	}
	if _, err := s.store.pool.Exec(ctx, `INSERT INTO jourvolt_telemetry_sessions
        (id,user_id,vehicle_id,kind,started_at,ended_at,source,quality_state)
        VALUES($1,$2,$3,'drive',$4,$5,'telemetry_mqtt','quarantined')`, user+"-hidden", user, car, older.EndAt.Add(time.Minute), older.EndAt.Add(2*time.Minute)); err != nil {
		t.Fatal(err)
	}
	if w := historyContextRequest(a, "GET", path, session.AccessToken); w.Code != 404 {
		t.Fatalf("quarantined hidden trip ignored: %d %s", w.Code, w.Body.String())
	}
}

func TestParkedIntervalPostgresCancellationReturnsControlledError(t *testing.T) {
	s, user, car := openHistoryQueryTestDB(t)
	tx, err := s.store.pool.Begin(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(context.Background())
	if _, err := tx.Exec(context.Background(), `LOCK TABLE jourvolt_telemetry_sessions IN ACCESS EXCLUSIVE MODE`); err != nil {
		t.Fatal(err)
	}
	path := fmt.Sprintf("/api/matelink/v1/cars/%d/parked/11/12", car)
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	r := httptest.NewRequest("GET", path, nil).WithContext(ctx)
	w := httptest.NewRecorder()
	(&app{telemetry: s}).adapterResource(w, r, user, path)
	if w.Code != 503 || strings.TrimSpace(w.Body.String()) != `{"error":"parked_history_unavailable"}` {
		t.Fatalf("cancellation contract: status=%d body=%s", w.Code, w.Body.String())
	}
}
