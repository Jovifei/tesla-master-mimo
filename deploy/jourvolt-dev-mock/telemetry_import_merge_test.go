package main

import (
	"context"
	"encoding/json"
	"os"
	"testing"
	"time"
)

func importMergeFixture(t *testing.T, id string) telemetrySession {
	t.Helper()
	row, err := (historyImportSession{SessionID: id, StartedAt: rfc3339(1), EndedAt: rfc3339(1),
		EnergyAdded: floatPointer(8), Route: []historyImportRoutePoint{{Date: rfc3339(1), Latitude: floatPointer(1), Longitude: floatPointer(2), Speed: floatPointer(0)}}}).toTelemetrySession("user-a", 1, "drive")
	if err != nil {
		t.Fatal(err)
	}
	return row
}

func TestImportMergePreservesPointsEvidenceAndCanonicalIdentity(t *testing.T) {
	old := importMergeFixture(t, "old-phone")
	old.PublicID = 99
	incoming := importMergeFixture(t, "new-phone")
	incoming.EnergyAdded = nil
	incoming.Route = nil
	got := mergeImportedSession(incoming, old)
	if got.ID != old.ID || got.PublicID != 99 || got.EnergyAdded == nil || *got.EnergyAdded != 8 || len(got.Route) != 1 {
		t.Fatalf("lost saved evidence: %+v", got)
	}
	incoming.Route = append(incoming.Route, telemetryRoutePoint{ObservedAt: old.StartAt.Add(time.Second), Latitude: 1, Longitude: 3})
	got = mergeImportedSession(incoming, got)
	if len(got.Route) != 2 || got.Route[0].Speed == nil || *got.Route[0].Speed != 0 {
		t.Fatal("lost observations or zero speed")
	}
	again := mergeImportedSession(incoming, got)
	if len(again.Route) != 2 || again.QualityState != "incomplete" {
		t.Fatal("duplicate/promotion")
	}
	old.Source = "telemetry_mqtt"
	old.QualityState = "observed"
	if protected := mergeImportedSession(incoming, old); protected.Source != "telemetry_mqtt" || protected.QualityState != "observed" {
		t.Fatal("native evidence overwritten")
	}
}

func TestMemoryImportAliasesDeduplicateWithinAccountOnly(t *testing.T) {
	s := newTelemetryServiceForTest("partner.example.com")
	a := importMergeFixture(t, "old-phone")
	b := importMergeFixture(t, "new-phone")
	b.EnergyAdded = nil
	b.Route = nil
	s.memory.importSessions("user-a", 1, []telemetrySession{a}, nil)
	s.memory.importSessions("user-a", 1, []telemetrySession{b}, nil)
	rows := s.memory.sessions("user-a", 1, "drive")
	if len(rows) != 1 || rows[0].ID != a.ID || len(rows[0].Route) != 1 {
		t.Fatalf("bad merge: %+v", rows)
	}
	if len(s.memory.sessions("user-b", 1, "drive")) != 0 {
		t.Fatal("cross-account history leak")
	}
}

func TestPostgresImportMergeRetainsArchiveAndProtectsNativeSession(t *testing.T) {
	dsn := os.Getenv("JOURVOLT_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("JOURVOLT_TEST_DATABASE_URL is not set")
	}
	ctx := context.Background()
	db, err := openStore(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.close)
	user := "import_merge_" + mustRandomToken(t)
	if err = db.ensureUser(ctx, user); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.deleteUser(context.Background(), user) })
	var car int
	err = db.pool.QueryRow(ctx, `INSERT INTO jourvolt_vehicles(user_id,provider_vehicle_id,vin_ciphertext,display_name,state,updated_at) VALUES($1,$2,'c','Test','online',now()) RETURNING id`, user, "merge-"+mustRandomToken(t)).Scan(&car)
	if err != nil {
		t.Fatal(err)
	}
	s := &telemetryService{store: db}
	rich := historyImportSession{SessionID: "old-phone", StartedAt: rfc3339(1), EndedAt: rfc3339(1), EnergyAdded: floatPointer(8), Route: []historyImportRoutePoint{{Date: rfc3339(1), Latitude: floatPointer(1), Longitude: floatPointer(2), Speed: floatPointer(0)}}}
	if _, err = s.importHistory(ctx, user, car, historyImportRequest{Drives: []historyImportSession{rich}}); err != nil {
		t.Fatal(err)
	}
	var originalID string
	var publicID int64
	if err = db.pool.QueryRow(ctx, `SELECT id,public_id FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2`, user, car).Scan(&originalID, &publicID); err != nil {
		t.Fatal(err)
	}
	weak := rich
	weak.SessionID = "new-phone"
	weak.EnergyAdded = nil
	weak.Route = nil
	for i := 0; i < 2; i++ {
		if _, err = s.importHistory(ctx, user, car, historyImportRequest{Drives: []historyImportSession{weak}}); err != nil {
			t.Fatal(err)
		}
	}
	var count int
	var energy *float64
	var raw []byte
	var currentPublic int64
	if err = db.pool.QueryRow(ctx, `SELECT count(*) FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2`, user, car).Scan(&count); err != nil || count != 1 {
		t.Fatalf("count=%d err=%v", count, err)
	}
	if err = db.pool.QueryRow(ctx, `SELECT energy_added,route_json,public_id FROM jourvolt_telemetry_sessions WHERE id=$1`, originalID).Scan(&energy, &raw, &currentPublic); err != nil {
		t.Fatal(err)
	}
	var route []telemetryRoutePoint
	if err = json.Unmarshal(raw, &route); err != nil {
		t.Fatal(err)
	}
	if energy == nil || *energy != 8 || len(route) != 1 || currentPublic != publicID {
		t.Fatal("cloud archive was overwritten")
	}
	// A local upload cannot mutate the native row, even with the same session times.
	if _, err = db.pool.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET source='telemetry_mqtt',quality_state='observed' WHERE id=$1`, originalID); err != nil {
		t.Fatal(err)
	}
	weak.EnergyAdded = floatPointer(999)
	if _, err = s.importHistory(ctx, user, car, historyImportRequest{Drives: []historyImportSession{weak}}); err != nil {
		t.Fatal(err)
	}
	var source, quality string
	if err = db.pool.QueryRow(ctx, `SELECT source,quality_state,energy_added FROM jourvolt_telemetry_sessions WHERE id=$1`, originalID).Scan(&source, &quality, &energy); err != nil {
		t.Fatal(err)
	}
	if source != "telemetry_mqtt" || quality != "observed" || energy == nil || *energy != 8 {
		t.Fatal("native evidence mutated")
	}
}
