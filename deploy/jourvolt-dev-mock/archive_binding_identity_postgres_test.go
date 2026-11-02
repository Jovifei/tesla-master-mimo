package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

type trendV2Fixture struct {
	db                        *store
	app                       *app
	user                      string
	car                       int
	token                     string
	binding                   archiveBinding
	scope                     archiveBindingRequest
	request                   batteryTrendImportRequest
	payload                   []byte
	driveID, chargeID         string
	driveBefore, chargeBefore string
}

func newTrendV2Fixture(t *testing.T) *trendV2Fixture {
	t.Helper()
	requireIsolatedChargeLocationDB(t)
	f := &trendV2Fixture{db: boundedHistoryTestStore(t)}
	f.user, f.car = boundedHistoryTestTenant(t, f.db, "trend-v2")
	_, f.request = batteryTrendTestRequest(t)
	f.scope = archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: f.request.SourceInstanceID, SourceVehicleID: f.request.SourceVehicleID}
	var err error
	f.token, f.binding, err = f.db.createArchiveBinding(context.Background(), f.user, f.car, f.scope)
	if err != nil {
		t.Fatal(err)
	}
	f.driveID = insertBatteryTrendSession(t, f.db, f.binding, f.request.Observations[0])
	f.chargeID = "trend-v2-charge-" + mustRandomToken(t)
	_, err = f.db.pool.Exec(context.Background(), `INSERT INTO jourvolt_telemetry_sessions(id,user_id,vehicle_id,kind,started_at,ended_at,source,quality_state,quality_reason,route_json,charge_points_json,cost) VALUES($1,$2,$3,'charge','2026-11-01T01:00:00Z','2026-11-01T02:00:00Z','teslamate_archive','observed','synthetic','[]'::jsonb,'[{"energy":1}]'::jsonb,34.56)`, f.chargeID, f.user, f.car)
	if err != nil {
		t.Fatal(err)
	}
	f.driveBefore, f.chargeBefore = batteryTrendSessionDigest(t, f.db, f.driveID), batteryTrendSessionDigest(t, f.db, f.chargeID)
	f.payload, err = json.Marshal(f.request)
	if err != nil {
		t.Fatal(err)
	}
	f.app = &app{store: f.db}
	return f
}
func (f *trendV2Fixture) path(suffix string) string {
	return fmt.Sprintf("/api/v1/cars/%d/history/archive/%s", f.car, suffix)
}
func (f *trendV2Fixture) call(method, path, token, identity string, body []byte) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, bytes.NewReader(body))
	if token != "" {
		req.Header.Set(archiveBindingHeader, token)
	}
	if identity != "" {
		req.Header.Set(archiveBindingIdentityHeader, identity)
	}
	rec := httptest.NewRecorder()
	f.app.ServeHTTP(rec, req)
	return rec
}
func (f *trendV2Fixture) identity(t *testing.T, token string) string {
	t.Helper()
	rec := f.call(http.MethodGet, f.path("binding-identity"), token, "", nil)
	if rec.Code != http.StatusOK || rec.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("identity status=%d", rec.Code)
	}
	var envelope struct {
		Data struct {
			SchemaVersion int    `json:"schema_version"`
			Identity      string `json:"binding_identity"`
		} `json:"data"`
	}
	if json.Unmarshal(rec.Body.Bytes(), &envelope) != nil || envelope.Data.SchemaVersion != 1 || !batteryTrendHashPattern.MatchString(envelope.Data.Identity) {
		t.Fatal("identity response contract")
	}
	return envelope.Data.Identity
}
func (f *trendV2Fixture) checkState(t *testing.T, want int) {
	t.Helper()
	var evidence, batches int
	err := f.db.pool.QueryRow(context.Background(), `SELECT (SELECT count(*) FROM jourvolt_battery_trend_evidence WHERE user_id=$1 AND vehicle_id=$2),(SELECT count(*) FROM jourvolt_battery_trend_batches WHERE user_id=$1 AND vehicle_id=$2)`, f.user, f.car).Scan(&evidence, &batches)
	if err != nil {
		t.Fatal(err)
	}
	if evidence != want || batches != want {
		t.Fatalf("evidence/batches=%d/%d want=%d", evidence, batches, want)
	}
	if batteryTrendSessionDigest(t, f.db, f.driveID) != f.driveBefore || batteryTrendSessionDigest(t, f.db, f.chargeID) != f.chargeBefore {
		t.Fatal("original drive, charge, route or cost changed")
	}
}

func TestArchiveBindingIdentityScopeAndCanonicalEncoding(t *testing.T) {
	b, _ := batteryTrendTestRequest(t)
	id := strings.Repeat("a", 64)
	base, err := archiveBindingIdentity(id, b)
	if err != nil || len(base) != 32 {
		t.Fatal("valid synthetic scope rejected")
	}
	cases := []archiveBinding{b, b, b, b}
	cases[0].UserID += "other"
	cases[1].VehicleID++
	cases[2].SourceInstanceID += "other"
	cases[3].SourceVehicleID += "other"
	for _, changed := range cases {
		value, err := archiveBindingIdentity(id, changed)
		if err != nil || bytes.Equal(value, base) {
			t.Fatal("different scope shares identity")
		}
	}
	other, err := archiveBindingIdentity(strings.Repeat("b", 64), b)
	if err != nil || bytes.Equal(other, base) {
		t.Fatal("new binding shares identity")
	}
	again, err := archiveBindingIdentity(id, b)
	if err != nil || !bytes.Equal(again, base) {
		t.Fatal("same scope is not stable")
	}
	for _, badID := range []string{"", strings.Repeat("A", 64), strings.Repeat("a", 63)} {
		if _, err = archiveBindingIdentity(badID, b); err == nil {
			t.Fatal("invalid binding ID accepted")
		}
	}
	b.SourceInstanceID, b.SourceVehicleID = "ab", "c"
	first, err := archiveBindingIdentity(id, b)
	if err != nil {
		t.Fatal(err)
	}
	b.SourceInstanceID, b.SourceVehicleID = "a", "bc"
	second, err := archiveBindingIdentity(id, b)
	if err != nil || bytes.Equal(first, second) {
		t.Fatal("ambiguous scope framing")
	}
	b.SourceInstanceID += "\x00"
	if _, err = archiveBindingIdentity(id, b); err == nil {
		t.Fatal("invalid source accepted")
	}
}

func TestArchiveBindingIdentityRenewalRebindingAndAccess(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	f := newEnrollmentFixture(t)
	var rawBindingID string
	if err := f.db.pool.QueryRow(context.Background(), `SELECT id FROM jourvolt_history_archive_bindings WHERE user_id=$1 AND vehicle_id=$2`, f.owner.UserID, f.scope.VehicleID).Scan(&rawBindingID); err != nil {
		t.Fatal(err)
	}
	identity := func(token string) (*httptest.ResponseRecorder, string) {
		req := httptest.NewRequest(http.MethodGet, fmt.Sprintf("/api/v1/cars/%d/history/archive/binding-identity", f.scope.VehicleID), nil)
		if token != "" {
			req.Header.Set(archiveBindingHeader, token)
		}
		rec := httptest.NewRecorder()
		f.app.ServeHTTP(rec, req)
		var response struct {
			Data map[string]any `json:"data"`
		}
		_ = json.Unmarshal(rec.Body.Bytes(), &response)
		value, _ := response.Data["binding_identity"].(string)
		if rec.Code == http.StatusOK {
			if len(response.Data) != 2 || response.Data["schema_version"] != float64(1) || !batteryTrendHashPattern.MatchString(value) || strings.Contains(rec.Body.String(), token) || strings.Contains(rec.Body.String(), f.owner.UserID) || strings.Contains(rec.Body.String(), f.scope.SourceInstanceID) || strings.Contains(rec.Body.String(), f.scope.SourceVehicleID) || strings.Contains(rec.Body.String(), rawBindingID) {
				t.Fatal("identity returned extra/sensitive information")
			}
			if rec.Header().Get("Cache-Control") != "no-store" {
				t.Fatal("missing no-store")
			}
		}
		return rec, value
	}
	missing, _ := identity("")
	if missing.Code != http.StatusUnauthorized {
		t.Fatalf("missing status=%d", missing.Code)
	}
	duplicate := httptest.NewRequest(http.MethodGet, fmt.Sprintf("/api/v1/cars/%d/history/archive/binding-identity", f.scope.VehicleID), nil)
	duplicate.Header.Add(archiveBindingHeader, f.oldToken)
	duplicate.Header.Add(archiveBindingHeader, f.oldToken)
	duplicateResult := httptest.NewRecorder()
	f.app.ServeHTTP(duplicateResult, duplicate)
	if duplicateResult.Code != http.StatusUnauthorized {
		t.Fatalf("duplicate token status=%d", duplicateResult.Code)
	}
	initialRec, before := identity(f.oldToken)
	if initialRec.Code != http.StatusOK {
		t.Fatalf("initial status=%d", initialRec.Code)
	}
	e := f.create(f.scope)
	requireEnrollmentStatus(t, f.decide(e, true), 200)
	claimed := f.device(e, "claim")
	requireEnrollmentStatus(t, claimed, 200)
	renewed := enrollmentData(t, claimed)["credential"].(string)
	oldRec, _ := identity(f.oldToken)
	if oldRec.Code != http.StatusUnauthorized {
		t.Fatalf("old token status=%d", oldRec.Code)
	}
	newRec, after := identity(renewed)
	if newRec.Code != http.StatusOK || after != before {
		t.Fatal("same binding renewal changed identity")
	}
	scope := archiveBindingRequest{SourceInstanceID: f.scope.SourceInstanceID, SourceVehicleID: f.scope.SourceVehicleID}
	rebound, _, err := f.db.createArchiveBinding(context.Background(), f.owner.UserID, f.scope.VehicleID, scope)
	if err != nil {
		t.Fatal(err)
	}
	rebRec, rebID := identity(rebound)
	if rebRec.Code != http.StatusOK || rebID == before {
		t.Fatal("rebind did not change identity")
	}
	f.exec(`UPDATE jourvolt_history_archive_bindings SET expires_at=clock_timestamp()-interval '1 second' WHERE user_id=$1 AND vehicle_id=$2`, f.owner.UserID, f.scope.VehicleID)
	expiredRec, _ := identity(rebound)
	if expiredRec.Code != http.StatusForbidden {
		t.Fatalf("expired status=%d", expiredRec.Code)
	}
	f.exec(`UPDATE jourvolt_history_archive_bindings SET expires_at=clock_timestamp()+interval '1 day',revoked_at=clock_timestamp() WHERE user_id=$1 AND vehicle_id=$2`, f.owner.UserID, f.scope.VehicleID)
	revokedRec, _ := identity(rebound)
	if revokedRec.Code != http.StatusForbidden {
		t.Fatalf("revoked status=%d", revokedRec.Code)
	}
}

func TestBatteryTrendV2IdentityRequiredIsolationAndReplay(t *testing.T) {
	f := newTrendV2Fixture(t)
	identity := f.identity(t, f.token)
	path := f.path("battery-trend/import-v2")
	for _, test := range []struct {
		name, token, identity string
		status                int
	}{
		{"no-token", "", identity, 401}, {"no-identity", f.token, "", 400}, {"malformed-identity", f.token, "wrong", 400}, {"foreign-identity", f.token, strings.Repeat("0", 64), 403},
	} {
		t.Run(test.name, func(t *testing.T) {
			rec := f.call(http.MethodPost, path, test.token, test.identity, f.payload)
			if rec.Code != test.status {
				t.Fatalf("status=%d want=%d", rec.Code, test.status)
			}
			f.checkState(t, 0)
		})
	}
	for _, name := range []string{archiveBindingHeader, archiveBindingIdentityHeader} {
		req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(f.payload))
		req.Header.Set(archiveBindingHeader, f.token)
		req.Header.Set(archiveBindingIdentityHeader, identity)
		req.Header.Add(name, strings.Repeat("a", 64))
		rec := httptest.NewRecorder()
		f.app.ServeHTTP(rec, req)
		if rec.Code != http.StatusUnauthorized && rec.Code != http.StatusBadRequest {
			t.Fatalf("duplicate header status=%d", rec.Code)
		}
		f.checkState(t, 0)
	}
	other := newTrendV2Fixture(t)
	foreign := f.call(http.MethodPost, path, f.token, other.identity(t, other.token), f.payload)
	if foreign.Code != http.StatusForbidden {
		t.Fatalf("foreign identity status=%d", foreign.Code)
	}
	f.checkState(t, 0)
	other.checkState(t, 0)
	for i := 0; i < 2; i++ {
		rec := f.call(http.MethodPost, path, f.token, identity, f.payload)
		if rec.Code != http.StatusOK || rec.Header().Get("Cache-Control") != "no-store" {
			t.Fatalf("v2 status=%d", rec.Code)
		}
		var response struct {
			Data map[string]any `json:"data"`
		}
		if json.Unmarshal(rec.Body.Bytes(), &response) != nil || response.Data["binding_identity"] != identity || response.Data["inserted"] != float64(1) {
			t.Fatal("v2 receipt or replay mismatch")
		}
		f.checkState(t, 1)
	}
	legacy := f.call(http.MethodPost, f.path("battery-trend/import"), f.token, "", f.payload)
	if legacy.Code != http.StatusOK || strings.Contains(legacy.Body.String(), "binding_identity") {
		t.Fatalf("v1 compatibility status=%d", legacy.Code)
	}
	f.checkState(t, 1)
}

func TestBatteryTrendV2BindingChangesWhileReadingBody(t *testing.T) {
	for _, scenario := range []string{"revoke", "renew", "rebind", "expire"} {
		t.Run(scenario, func(t *testing.T) {
			f := newTrendV2Fixture(t)
			identity := f.identity(t, f.token)
			blocked := &batteryTrendBlockedBody{reader: bytes.NewReader(f.payload), entered: make(chan struct{}), release: make(chan struct{})}
			var once sync.Once
			release := func() { once.Do(func() { close(blocked.release) }) }
			defer release()
			req := httptest.NewRequest(http.MethodPost, f.path("battery-trend/import-v2"), nil)
			req.Body = blocked
			req.Header.Set(archiveBindingHeader, f.token)
			req.Header.Set(archiveBindingIdentityHeader, identity)
			result := make(chan int, 1)
			go func() { rec := httptest.NewRecorder(); f.app.ServeHTTP(rec, req); result <- rec.Code }()
			select {
			case <-blocked.entered:
			case <-time.After(5 * time.Second):
				t.Fatal("request did not reach body")
			}
			var err error
			switch scenario {
			case "revoke":
				err = f.db.revokeArchiveBinding(context.Background(), f.user, f.car, f.scope)
			case "renew":
				_, err = f.db.pool.Exec(context.Background(), `UPDATE jourvolt_history_archive_bindings SET token_hash=$1,expires_at=clock_timestamp()+interval '1 day' WHERE token_hash=$2`, archiveBindingTokenHash(mustRandomToken(t)), archiveBindingTokenHash(f.token))
			case "rebind":
				_, _, err = f.db.createArchiveBinding(context.Background(), f.user, f.car, f.scope)
			case "expire":
				_, err = f.db.pool.Exec(context.Background(), `UPDATE jourvolt_history_archive_bindings SET expires_at=clock_timestamp()-interval '1 second' WHERE token_hash=$1`, archiveBindingTokenHash(f.token))
			}
			if err != nil {
				t.Fatal(err)
			}
			release()
			select {
			case status := <-result:
				if status != http.StatusForbidden {
					t.Fatalf("race status=%d", status)
				}
			case <-time.After(5 * time.Second):
				t.Fatal("request did not return")
			}
			f.checkState(t, 0)
		})
	}
}

func TestBatteryTrendV2ExpiryAfterEvidenceInsertRollsBack(t *testing.T) {
	f := newTrendV2Fixture(t)
	identity := f.identity(t, f.token)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	_, err := f.db.pool.Exec(ctx, `CREATE OR REPLACE FUNCTION synthetic_v2_commit_gate() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.batch_id='test-batch-1' THEN PERFORM pg_advisory_xact_lock(91827465); END IF; RETURN NEW; END $$`)
	if err != nil {
		t.Fatal(err)
	}
	_, err = f.db.pool.Exec(ctx, `CREATE TRIGGER synthetic_v2_commit_gate BEFORE INSERT ON jourvolt_battery_trend_batches FOR EACH ROW EXECUTE FUNCTION synthetic_v2_commit_gate()`)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_, _ = f.db.pool.Exec(context.Background(), `DROP TRIGGER IF EXISTS synthetic_v2_commit_gate ON jourvolt_battery_trend_batches`)
		_, _ = f.db.pool.Exec(context.Background(), `DROP FUNCTION IF EXISTS synthetic_v2_commit_gate()`)
	})
	hold, err := f.db.pool.Acquire(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer hold.Release()
	if _, err = hold.Exec(ctx, `SELECT pg_advisory_lock(91827465)`); err != nil {
		t.Fatal(err)
	}
	defer hold.Exec(context.Background(), `SELECT pg_advisory_unlock(91827465)`)
	_, err = f.db.pool.Exec(ctx, `UPDATE jourvolt_history_archive_bindings SET expires_at=clock_timestamp()+interval '3 seconds' WHERE token_hash=$1`, archiveBindingTokenHash(f.token))
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan int, 1)
	go func() {
		done <- f.call(http.MethodPost, f.path("battery-trend/import-v2"), f.token, identity, f.payload).Code
	}()
	for {
		var waiting bool
		if err = f.db.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND NOT granted)`).Scan(&waiting); err != nil {
			t.Fatal(err)
		}
		if waiting {
			break
		}
		select {
		case status := <-done:
			t.Fatalf("request ended before gate: %d", status)
		case <-ctx.Done():
			t.Fatal("request missed evidence gate")
		case <-time.After(10 * time.Millisecond):
		}
	}
	for {
		var expired bool
		if err = f.db.pool.QueryRow(ctx, `SELECT expires_at<=clock_timestamp() FROM jourvolt_history_archive_bindings WHERE token_hash=$1`, archiveBindingTokenHash(f.token)).Scan(&expired); err != nil {
			t.Fatal(err)
		}
		if expired {
			break
		}
		select {
		case <-ctx.Done():
			t.Fatal("binding did not expire")
		case <-time.After(20 * time.Millisecond):
		}
	}
	if _, err = hold.Exec(ctx, `SELECT pg_advisory_unlock(91827465)`); err != nil {
		t.Fatal(err)
	}
	select {
	case status := <-done:
		if status != http.StatusForbidden {
			t.Fatalf("expired status=%d", status)
		}
	case <-ctx.Done():
		t.Fatal("expired request did not finish")
	}
	f.checkState(t, 0)
}
