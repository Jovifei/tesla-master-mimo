package main

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

func (f *trendV2Fixture) archivePayload(t *testing.T) []byte {
	t.Helper()
	f.app.telemetry = &telemetryService{store: f.db}
	newCost := 100.0
	request := historyImportRequest{Source: "teslamate", SourceInstanceID: f.scope.SourceInstanceID, SourceVehicleID: f.scope.SourceVehicleID, ChunkID: "synthetic-v2",
		Drives: []historyImportSession{{SessionID: "new-drive", SourceRecordID: "v2-new-drive", StartedAt: "2026-11-01T03:00:00Z", EndedAt: "2026-11-01T03:30:00Z"}},
		Charges: []historyImportSession{
			{SessionID: "old-charge", SourceRecordID: "v2-old-charge", StartedAt: "2026-11-01T01:00:00Z", EndedAt: "2026-11-01T02:00:00Z", Cost: &newCost},
			{SessionID: "new-charge", SourceRecordID: "v2-new-charge", StartedAt: "2026-11-01T04:00:00Z", EndedAt: "2026-11-01T05:00:00Z"},
		}}
	payload, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	return payload
}

func (f *trendV2Fixture) seedArchiveCharge(t *testing.T) string {
	t.Helper()
	id := scopedArchiveSessionID(f.user, f.car, "charge", f.scope.SourceInstanceID, f.scope.SourceVehicleID, "v2-old-charge")
	_, err := f.db.pool.Exec(context.Background(), `INSERT INTO jourvolt_telemetry_sessions(id,user_id,vehicle_id,kind,started_at,ended_at,source,quality_state,quality_reason,source_instance_id,source_vehicle_id,source_record_id,route_json,charge_points_json,cost) VALUES($1,$2,$3,'charge','2026-11-01T01:00:00Z','2026-11-01T02:00:00Z','teslamate_archive','observed','synthetic',$4,$5,'v2-old-charge','[]'::jsonb,'[]'::jsonb,34.56)`, id, f.user, f.car, f.scope.SourceInstanceID, f.scope.SourceVehicleID)
	if err != nil {
		t.Fatal(err)
	}
	return id
}
func (f *trendV2Fixture) checkArchiveRows(t *testing.T, oldCharge string, count int) {
	t.Helper()
	var n int
	var cost float64
	err := f.db.pool.QueryRow(context.Background(), `SELECT count(*),COALESCE(max(cost) FILTER (WHERE id=$3),0) FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2`, f.user, f.car, oldCharge).Scan(&n, &cost)
	if err != nil {
		t.Fatal(err)
	}
	if n != count || cost != 34.56 {
		t.Fatalf("session count=%d cost=%v", n, cost)
	}
	f.checkState(t, 0)
}

func (f *trendV2Fixture) checkArchiveUnchanged(t *testing.T, oldCharge, oldDigest string) {
	t.Helper()
	f.checkArchiveRows(t, oldCharge, 3)
	if batteryTrendSessionDigest(t, f.db, oldCharge) != oldDigest {
		t.Fatal("rejected request changed existing archive charge")
	}
}

func TestArchiveImportV2OwnerProofAndLegacyCompatibility(t *testing.T) {
	f := newTrendV2Fixture(t)
	oldCharge := f.seedArchiveCharge(t)
	oldDigest := batteryTrendSessionDigest(t, f.db, oldCharge)
	payload := f.archivePayload(t)
	identity := f.identity(t, f.token)
	path := f.path("import-v2")
	for _, tc := range []struct {
		name, token, identity string
		status                int
	}{
		{"missing-token", "", identity, 401}, {"missing-identity", f.token, "", 400}, {"invalid-identity", f.token, "bad", 400}, {"wrong-identity", f.token, strings.Repeat("0", 64), 403},
	} {
		t.Run(tc.name, func(t *testing.T) {
			rec := f.call(http.MethodPost, path, tc.token, tc.identity, payload)
			if rec.Code != tc.status {
				t.Fatalf("status=%d", rec.Code)
			}
			f.checkArchiveUnchanged(t, oldCharge, oldDigest)
		})
	}
	other := newTrendV2Fixture(t)
	foreignID := other.identity(t, other.token)
	rec := f.call(http.MethodPost, path, f.token, foreignID, payload)
	if rec.Code != 403 {
		t.Fatalf("cross-account status=%d", rec.Code)
	}
	f.checkArchiveUnchanged(t, oldCharge, oldDigest)
	altScope := f.scope
	altScope.SourceInstanceID = "other-instance"
	altToken, _, err := f.db.createArchiveBinding(context.Background(), f.user, f.car, altScope)
	if err != nil {
		t.Fatal(err)
	}
	altID := f.identity(t, altToken)
	rec = f.call(http.MethodPost, path, f.token, altID, payload)
	if rec.Code != 403 {
		t.Fatalf("cross-source status=%d", rec.Code)
	}
	f.checkArchiveUnchanged(t, oldCharge, oldDigest)
	for i := 0; i < 2; i++ {
		rec = f.call(http.MethodPost, path, f.token, identity, payload)
		var body struct {
			Data map[string]any `json:"data"`
		}
		if rec.Code != 200 || json.Unmarshal(rec.Body.Bytes(), &body) != nil || body.Data["binding_identity"] != identity || body.Data["imported_drives"] != float64(1) || body.Data["imported_charges"] != float64(2) {
			t.Fatalf("v2 import status=%d", rec.Code)
		}
		f.checkArchiveRows(t, oldCharge, 5)
	}
	legacy := f.call(http.MethodPost, f.path("import"), f.token, "", payload)
	if legacy.Code != 200 || strings.Contains(legacy.Body.String(), "binding_identity") {
		t.Fatalf("v1 status=%d", legacy.Code)
	}
	f.checkArchiveRows(t, oldCharge, 5)
}

func TestArchiveImportV2BindingChangesDuringBody(t *testing.T) {
	for _, scenario := range []string{"revoke", "renew", "rebind", "expire", "scope", "owner"} {
		t.Run(scenario, func(t *testing.T) {
			f := newTrendV2Fixture(t)
			oldCharge := f.seedArchiveCharge(t)
			oldDigest := batteryTrendSessionDigest(t, f.db, oldCharge)
			payload := f.archivePayload(t)
			identity := f.identity(t, f.token)
			gate := &batteryTrendBlockedBody{reader: bytes.NewReader(payload), entered: make(chan struct{}), release: make(chan struct{})}
			var once sync.Once
			release := func() { once.Do(func() { close(gate.release) }) }
			defer release()
			req := httptest.NewRequest(http.MethodPost, f.path("import-v2"), nil)
			req.Body = gate
			req.Header.Set(archiveBindingHeader, f.token)
			req.Header.Set(archiveBindingIdentityHeader, identity)
			done := make(chan int, 1)
			go func() { rec := httptest.NewRecorder(); f.app.ServeHTTP(rec, req); done <- rec.Code }()
			select {
			case <-gate.entered:
			case <-time.After(5 * time.Second):
				t.Fatal("body gate timeout")
			}
			var err error
			switch scenario {
			case "revoke":
				err = f.db.revokeArchiveBinding(context.Background(), f.user, f.car, f.scope)
			case "renew":
				_, err = f.db.pool.Exec(context.Background(), `UPDATE jourvolt_history_archive_bindings SET token_hash=$1 WHERE token_hash=$2`, archiveBindingTokenHash(mustRandomToken(t)), archiveBindingTokenHash(f.token))
			case "rebind":
				_, _, err = f.db.createArchiveBinding(context.Background(), f.user, f.car, f.scope)
			case "expire":
				_, err = f.db.pool.Exec(context.Background(), `UPDATE jourvolt_history_archive_bindings SET expires_at=clock_timestamp()-interval '1 second' WHERE token_hash=$1`, archiveBindingTokenHash(f.token))
			case "scope":
				_, err = f.db.pool.Exec(context.Background(), `UPDATE jourvolt_history_archive_bindings SET source_vehicle_id='another-source' WHERE token_hash=$1`, archiveBindingTokenHash(f.token))
			case "owner":
				other, _ := boundedHistoryTestTenant(t, f.db, "v2-other-owner")
				_, err = f.db.pool.Exec(context.Background(), `UPDATE jourvolt_vehicles SET user_id=$1 WHERE id=$2`, other, f.car)
			}
			if err != nil {
				t.Fatal(err)
			}
			release()
			select {
			case status := <-done:
				if status != 401 && status != 403 {
					t.Fatalf("binding race status=%d", status)
				}
			case <-time.After(5 * time.Second):
				t.Fatal("race response timeout")
			}
			f.checkArchiveUnchanged(t, oldCharge, oldDigest)
		})
	}
}

func TestArchiveImportV2ExpiryAfterFirstWriteRollsBack(t *testing.T) {
	f := newTrendV2Fixture(t)
	oldCharge := f.seedArchiveCharge(t)
	oldDigest := batteryTrendSessionDigest(t, f.db, oldCharge)
	payload := f.archivePayload(t)
	identity := f.identity(t, f.token)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	_, err := f.db.pool.Exec(ctx, `CREATE OR REPLACE FUNCTION synthetic_archive_v2_gate() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.source_record_id='v2-new-charge' THEN PERFORM pg_advisory_xact_lock(91827466); END IF; RETURN NEW; END $$`)
	if err != nil {
		t.Fatal(err)
	}
	_, err = f.db.pool.Exec(ctx, `CREATE TRIGGER synthetic_archive_v2_gate BEFORE INSERT ON jourvolt_telemetry_sessions FOR EACH ROW EXECUTE FUNCTION synthetic_archive_v2_gate()`)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_, _ = f.db.pool.Exec(context.Background(), `DROP TRIGGER IF EXISTS synthetic_archive_v2_gate ON jourvolt_telemetry_sessions`)
		_, _ = f.db.pool.Exec(context.Background(), `DROP FUNCTION IF EXISTS synthetic_archive_v2_gate()`)
	})
	hold, err := f.db.pool.Acquire(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer hold.Release()
	if _, err = hold.Exec(ctx, `SELECT pg_advisory_lock(91827466)`); err != nil {
		t.Fatal(err)
	}
	defer hold.Exec(context.Background(), `SELECT pg_advisory_unlock(91827466)`)
	_, err = f.db.pool.Exec(ctx, `UPDATE jourvolt_history_archive_bindings SET expires_at=clock_timestamp()+interval '3 seconds' WHERE token_hash=$1`, archiveBindingTokenHash(f.token))
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan int, 1)
	go func() { done <- f.call(http.MethodPost, f.path("import-v2"), f.token, identity, payload).Code }()
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
			t.Fatalf("request before gate=%d", status)
		case <-ctx.Done():
			t.Fatal("write gate timeout")
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
			t.Fatal("expiry timeout")
		case <-time.After(20 * time.Millisecond):
		}
	}
	if _, err = hold.Exec(ctx, `SELECT pg_advisory_unlock(91827466)`); err != nil {
		t.Fatal(err)
	}
	select {
	case status := <-done:
		if status != 403 {
			t.Fatalf("expiry status=%d", status)
		}
	case <-ctx.Done():
		t.Fatal("response timeout")
	}
	f.checkArchiveUnchanged(t, oldCharge, oldDigest)
}

func TestArchiveImportV2MemoryFailsClosed(t *testing.T) {
	service := newTelemetryServiceForTest("synthetic.example")
	a := &app{telemetry: service}
	req := httptest.NewRequest(http.MethodPost, "/api/v1/cars/1/history/archive/import-v2", strings.NewReader("{}"))
	req.Header.Set(archiveBindingHeader, strings.Repeat("a", 64))
	req.Header.Set(archiveBindingIdentityHeader, strings.Repeat("b", 64))
	rec := httptest.NewRecorder()
	a.ServeHTTP(rec, req)
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("memory v2 status=%d", rec.Code)
	}
}

func TestBatteryTrendV2RenewalAllowsSameBindingPending(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	f := newEnrollmentFixture(t)
	_, request := batteryTrendTestRequest(t)
	request.SourceInstanceID = f.scope.SourceInstanceID
	request.SourceVehicleID = f.scope.SourceVehicleID
	request.PayloadSHA256, _ = batteryTrendDigest(request.payload())
	binding, err := f.db.resolveArchiveBinding(context.Background(), f.oldToken)
	if err != nil {
		t.Fatal(err)
	}
	driveID := insertBatteryTrendSession(t, f.db, binding, request.Observations[0])
	before := batteryTrendSessionDigest(t, f.db, driveID)
	helper := &trendV2Fixture{db: f.db, app: f.app, user: binding.UserID, car: binding.VehicleID}
	identity := helper.identity(t, f.oldToken)
	payload, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	enrollment := f.create(f.scope)
	requireEnrollmentStatus(t, f.decide(enrollment, true), 200)
	claim := f.device(enrollment, "claim")
	requireEnrollmentStatus(t, claim, 200)
	renewed := enrollmentData(t, claim)["credential"].(string)
	if next := helper.identity(t, renewed); next != identity {
		t.Fatal("same binding renewal changed identity")
	}
	rec := helper.call(http.MethodPost, helper.path("battery-trend/import-v2"), renewed, identity, payload)
	if rec.Code != 200 {
		t.Fatalf("renewed pending status=%d", rec.Code)
	}
	if batteryTrendSessionDigest(t, f.db, driveID) != before {
		t.Fatal("renewed pending changed original drive")
	}
	archive := archiveImportRequestForVehicleTest(renewed, f.scope.VehicleID, f.scope.SourceInstanceID, f.scope.SourceVehicleID)
	archive.URL.Path += "-v2"
	archive.Header.Set(archiveBindingIdentityHeader, identity)
	archiveResult := httptest.NewRecorder()
	f.app.ServeHTTP(archiveResult, archive)
	if archiveResult.Code != http.StatusOK {
		t.Fatalf("renewed archive status=%d", archiveResult.Code)
	}
	if batteryTrendSessionDigest(t, f.db, driveID) != before {
		t.Fatal("renewed archive changed unrelated original drive")
	}
}

func TestArchiveBindingV2VehicleLockThenRevocation(t *testing.T) {
	for _, kind := range []string{"trend", "archive"} {
		t.Run(kind, func(t *testing.T) {
			f := newTrendV2Fixture(t)
			identity := f.identity(t, f.token)
			oldCharge := f.seedArchiveCharge(t)
			payload, path := f.payload, f.path("battery-trend/import-v2")
			if kind == "archive" {
				payload, path = f.archivePayload(t), f.path("import-v2")
			}
			ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
			defer cancel()
			blocker, err := f.db.pool.Begin(ctx)
			if err != nil {
				t.Fatal(err)
			}
			defer blocker.Rollback(context.Background())
			var car int
			if err = blocker.QueryRow(ctx, `SELECT id FROM jourvolt_vehicles WHERE id=$1 FOR UPDATE`, f.car).Scan(&car); err != nil {
				t.Fatal(err)
			}
			done := make(chan int, 1)
			go func() { done <- f.call(http.MethodPost, path, f.token, identity, payload).Code }()
			for {
				var waiting bool
				if err = f.db.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%jourvolt_vehicles%FOR SHARE%')`).Scan(&waiting); err != nil {
					t.Fatal(err)
				}
				if waiting {
					break
				}
				select {
				case status := <-done:
					t.Fatalf("request before vehicle wait=%d", status)
				case <-ctx.Done():
					t.Fatal("vehicle wait timeout")
				case <-time.After(10 * time.Millisecond):
				}
			}
			if _, err = blocker.Exec(ctx, `UPDATE jourvolt_history_archive_bindings SET revoked_at=clock_timestamp() WHERE token_hash=$1`, archiveBindingTokenHash(f.token)); err != nil {
				t.Fatal(err)
			}
			if err = blocker.Commit(ctx); err != nil {
				t.Fatal(err)
			}
			select {
			case status := <-done:
				if status != 403 {
					t.Fatalf("revoked under vehicle wait=%d", status)
				}
			case <-ctx.Done():
				t.Fatal("response timeout")
			}
			f.checkArchiveRows(t, oldCharge, 3)
		})
	}
}

func TestArchiveBindingIdentityMixedStoresFailClosed(t *testing.T) {
	f := newTrendV2Fixture(t)
	identity := f.identity(t, f.token)
	f.app.telemetry = newTelemetryServiceForTest("synthetic.example")
	for _, tc := range []struct {
		method, path string
		body         []byte
	}{
		{http.MethodGet, "binding-identity", nil},
		{http.MethodPost, "battery-trend/import-v2", f.payload},
		{http.MethodPost, "import-v2", []byte("{}")},
	} {
		rec := f.call(tc.method, f.path(tc.path), f.token, identity, tc.body)
		if rec.Code != http.StatusServiceUnavailable {
			t.Fatalf("mixed store %s status=%d", tc.path, rec.Code)
		}
	}
	f.checkState(t, 0)
}

func TestBatteryTrendV2CancelAfterEvidenceInsertRollsBack(t *testing.T) {
	f := newTrendV2Fixture(t)
	identity := f.identity(t, f.token)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	_, err := f.db.pool.Exec(ctx, `CREATE OR REPLACE FUNCTION synthetic_v2_cancel_gate() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.batch_id='test-batch-1' THEN PERFORM pg_advisory_xact_lock(91827467); END IF; RETURN NEW; END $$`)
	if err != nil {
		t.Fatal(err)
	}
	_, err = f.db.pool.Exec(ctx, `CREATE TRIGGER synthetic_v2_cancel_gate BEFORE INSERT ON jourvolt_battery_trend_batches FOR EACH ROW EXECUTE FUNCTION synthetic_v2_cancel_gate()`)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_, _ = f.db.pool.Exec(context.Background(), `DROP TRIGGER IF EXISTS synthetic_v2_cancel_gate ON jourvolt_battery_trend_batches`)
		_, _ = f.db.pool.Exec(context.Background(), `DROP FUNCTION IF EXISTS synthetic_v2_cancel_gate()`)
	})
	hold, err := f.db.pool.Acquire(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer hold.Release()
	if _, err = hold.Exec(ctx, `SELECT pg_advisory_lock(91827467)`); err != nil {
		t.Fatal(err)
	}
	defer hold.Exec(context.Background(), `SELECT pg_advisory_unlock(91827467)`)
	requestCtx, stop := context.WithCancel(ctx)
	defer stop()
	req := httptest.NewRequest(http.MethodPost, f.path("battery-trend/import-v2"), bytes.NewReader(f.payload)).WithContext(requestCtx)
	req.Header.Set(archiveBindingHeader, f.token)
	req.Header.Set(archiveBindingIdentityHeader, identity)
	done := make(chan int, 1)
	go func() { rec := httptest.NewRecorder(); f.app.ServeHTTP(rec, req); done <- rec.Code }()
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
			t.Fatalf("request before gate=%d", status)
		case <-ctx.Done():
			t.Fatal("cancel gate timeout")
		case <-time.After(10 * time.Millisecond):
		}
	}
	stop()
	if _, err = hold.Exec(ctx, `SELECT pg_advisory_unlock(91827467)`); err != nil {
		t.Fatal(err)
	}
	select {
	case status := <-done:
		if status != http.StatusServiceUnavailable {
			t.Fatalf("cancel status=%d", status)
		}
	case <-ctx.Done():
		t.Fatal("cancel response timeout")
	}
	f.checkState(t, 0)
}
