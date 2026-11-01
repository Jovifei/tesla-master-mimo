package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"reflect"
	"sync"
	"testing"
	"time"
)

type archiveRaceBody struct {
	reader  *bytes.Reader
	entered chan struct{}
	release chan struct{}
	once    sync.Once
}

func (b *archiveRaceBody) Read(p []byte) (int, error) {
	b.once.Do(func() { close(b.entered); <-b.release })
	return b.reader.Read(p)
}
func (*archiveRaceBody) Close() error { return nil }

func awaitArchiveRaceResponse(t *testing.T, a *app, req *http.Request, user string, change func() error) *httptest.ResponseRecorder {
	t.Helper()
	body, err := io.ReadAll(req.Body)
	if err != nil {
		t.Fatal(err)
	}
	gate := &archiveRaceBody{reader: bytes.NewReader(body), entered: make(chan struct{}), release: make(chan struct{})}
	var releaseOnce sync.Once
	release := func() { releaseOnce.Do(func() { close(gate.release) }) }
	defer release()
	req.Body = gate
	done := make(chan *httptest.ResponseRecorder, 1)
	go func() { out := httptest.NewRecorder(); a.archiveBindingResource(out, req, user); done <- out }()
	select {
	case <-gate.entered:
	case <-time.After(10 * time.Second):
		t.Fatal("request did not reach body")
	}
	if err = change(); err != nil {
		t.Fatal(err)
	}
	release()
	select {
	case out := <-done:
		return out
	case <-time.After(10 * time.Second):
		t.Fatal("request did not finish")
	}
	return nil
}

func seedArchiveMemoryState(s *telemetryMemoryStore, user string, car int) {
	start := time.Date(2026, 8, 1, 10, 0, 0, 0, time.UTC)
	end := start.Add(time.Minute)
	cost, power := 12.5, 7.0
	existing := telemetrySession{ID: "synthetic-existing", Kind: "drive", StartAt: start, EndAt: &end,
		Source: "local_import", QualityState: "incomplete", Cost: &cost,
		Route: []telemetryRoutePoint{{ObservedAt: start, Latitude: 1, Longitude: 2, Power: &power}}}
	s.importSessions(user, car, []telemetrySession{existing}, nil)
}

func TestArchiveImportBindingChangesDuringBodyMemory(t *testing.T) {
	for _, scenario := range []string{"revoke", "rotate", "expire", "scope"} {
		t.Run(scenario, func(t *testing.T) {
			service := newTelemetryServiceForTest("synthetic.example")
			ref := telemetryVehicleRef{UserID: "synthetic-owner", VehicleID: 1, VINHash: "synthetic-hash", ProviderVehicleID: "synthetic-provider"}
			if err := service.memory.registerVehicle(ref); err != nil {
				t.Fatal(err)
			}
			scope := archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: "synthetic-instance", SourceVehicleID: "1"}
			token, _, err := service.memory.createArchiveBinding(context.Background(), ref.UserID, ref.VehicleID, scope)
			if err != nil {
				t.Fatal(err)
			}
			seedArchiveMemoryState(service.memory, ref.UserID, ref.VehicleID)
			key := telemetryKey{UserID: ref.UserID, VehicleID: ref.VehicleID}
			service.memory.mu.Lock()
			before := cloneTelemetrySessions(service.memory.completed[key])
			started, next := service.memory.startedAt[key], service.memory.nextPublicID
			service.memory.mu.Unlock()
			a := &app{telemetry: service}
			expected := http.StatusForbidden
			out := awaitArchiveRaceResponse(t, a, archiveImportRequestForVehicleTest(token, ref.VehicleID, scope.SourceInstanceID, scope.SourceVehicleID), ref.UserID, func() error {
				switch scenario {
				case "revoke":
					return service.memory.revokeArchiveBinding(context.Background(), ref.UserID, ref.VehicleID, scope)
				case "rotate":
					expected = http.StatusUnauthorized
					_, _, err := service.memory.createArchiveBinding(context.Background(), ref.UserID, ref.VehicleID, scope)
					return err
				case "expire", "scope":
					service.memory.mu.Lock()
					defer service.memory.mu.Unlock()
					hash := archiveBindingTokenHash(token)
					b := service.memory.archiveBindings[hash]
					if scenario == "expire" {
						b.ExpiresAt = time.Now().UTC().Add(-time.Second)
					} else {
						b.SourceVehicleID = "different-source"
					}
					service.memory.archiveBindings[hash] = b
				}
				return nil
			})
			if out.Code != expected {
				t.Fatalf("status=%d want=%d", out.Code, expected)
			}
			service.memory.mu.Lock()
			defer service.memory.mu.Unlock()
			if !reflect.DeepEqual(before, service.memory.completed[key]) || started != service.memory.startedAt[key] || next != service.memory.nextPublicID {
				t.Fatal("rejected import changed existing sessions, route, cost, startedAt or nextPublicID")
			}
		})
	}
}

func TestArchiveImportBindingChangesDuringBodyPostgres(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	for _, scenario := range []string{"revoke", "rotate", "expire", "scope", "owner"} {
		t.Run(scenario, func(t *testing.T) {
			db := boundedHistoryTestStore(t)
			user, car := boundedHistoryTestTenant(t, db, "synthetic-archive-binding")
			scope := archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: "synthetic-instance", SourceVehicleID: "1"}
			token, _, err := db.createArchiveBinding(context.Background(), user, car, scope)
			if err != nil {
				t.Fatal(err)
			}
			_, err = db.pool.Exec(context.Background(), `INSERT INTO jourvolt_telemetry_sessions(id,user_id,vehicle_id,kind,started_at,ended_at,source,quality_state,route_json,charge_points_json,cost) VALUES($1,$2,$3,'drive','2026-08-01T10:00:00Z','2026-08-01T10:01:00Z','local_import','incomplete','[{"synthetic":1}]'::jsonb,'[]'::jsonb,12.5)`, "synthetic-sentinel", user, car)
			if err != nil {
				t.Fatal(err)
			}
			a := &app{store: db, telemetry: &telemetryService{store: db}}
			expected := http.StatusForbidden
			out := awaitArchiveRaceResponse(t, a, archiveImportRequestForVehicleTest(token, car, scope.SourceInstanceID, scope.SourceVehicleID), user, func() error {
				switch scenario {
				case "revoke":
					return db.revokeArchiveBinding(context.Background(), user, car, scope)
				case "rotate":
					expected = http.StatusUnauthorized
					_, _, err := db.createArchiveBinding(context.Background(), user, car, scope)
					return err
				case "expire":
					_, err := db.pool.Exec(context.Background(), `UPDATE jourvolt_history_archive_bindings SET expires_at=clock_timestamp()-interval '1 second' WHERE token_hash=$1`, archiveBindingTokenHash(token))
					return err
				case "scope":
					_, err := db.pool.Exec(context.Background(), `UPDATE jourvolt_history_archive_bindings SET source_vehicle_id='different-source' WHERE token_hash=$1`, archiveBindingTokenHash(token))
					return err
				case "owner":
					other := "synthetic-new-owner-" + mustRandomToken(t)
					if err := db.ensureUser(context.Background(), other); err != nil {
						return err
					}
					t.Cleanup(func() { _ = db.deleteUser(context.Background(), other) })
					_, err := db.pool.Exec(context.Background(), `UPDATE jourvolt_vehicles SET user_id=$1 WHERE id=$2`, other, car)
					return err
				}
				return nil
			})
			if out.Code != expected {
				t.Fatalf("status=%d want=%d", out.Code, expected)
			}
			var n int
			var cost float64
			var route string
			if err = db.pool.QueryRow(context.Background(), `SELECT count(*),COALESCE(max(cost),0),COALESCE(max(route_json::text),'') FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2`, user, car).Scan(&n, &cost, &route); err != nil {
				t.Fatal(err)
			}
			if n != 1 || cost != 12.5 || route != "[{\"synthetic\": 1}]" {
				t.Fatal("rejected import changed existing rows, cost or route")
			}
		})
	}
}

type archiveCancelAtPublishContext struct {
	context.Context
	cancel context.CancelFunc
	calls  int
}

func (c *archiveCancelAtPublishContext) Err() error {
	c.calls++
	if c.calls == 2 {
		c.cancel()
	}
	return c.Context.Err()
}

func TestArchiveImportMemoryCancellationAfterStagingPreservesState(t *testing.T) {
	service := newTelemetryServiceForTest("synthetic.example")
	user, car := "synthetic-owner", 1
	scope := archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: "synthetic-instance", SourceVehicleID: "1"}
	token, binding, err := service.memory.createArchiveBinding(context.Background(), user, car, scope)
	if err != nil {
		t.Fatal(err)
	}
	seedArchiveMemoryState(service.memory, user, car)
	key := telemetryKey{UserID: user, VehicleID: car}
	service.memory.mu.Lock()
	before := cloneTelemetrySessions(service.memory.completed[key])
	started, next := service.memory.startedAt[key], service.memory.nextPublicID
	service.memory.mu.Unlock()
	base, cancel := context.WithCancel(context.Background())
	defer cancel()
	ctx := &archiveCancelAtPublishContext{Context: base, cancel: cancel}
	start := time.Date(2026, 9, 1, 10, 0, 0, 0, time.UTC)
	incoming := telemetrySession{ID: "synthetic-new", Kind: "drive", StartAt: start, Source: "teslamate_archive"}
	err = service.memory.importBoundSessions(ctx, archiveImportGuard{binding: binding, tokenHash: archiveBindingTokenHash(token)}, []telemetrySession{incoming}, nil)
	if !errors.Is(err, context.Canceled) || ctx.calls != 2 {
		t.Fatalf("cancellation not applied after staging: %v", err)
	}
	service.memory.mu.Lock()
	defer service.memory.mu.Unlock()
	if !reflect.DeepEqual(before, service.memory.completed[key]) || started != service.memory.startedAt[key] || next != service.memory.nextPublicID {
		t.Fatal("cancelled staging changed existing sessions, route, cost, startedAt or nextPublicID")
	}
}

type archivePauseAtPublishContext struct {
	context.Context
	pause time.Duration
	calls int
}

func (c *archivePauseAtPublishContext) Err() error {
	c.calls++
	if c.calls == 2 {
		time.Sleep(c.pause)
	}
	return c.Context.Err()
}

func TestArchiveImportMemoryExpiryAfterStagingPreservesState(t *testing.T) {
	service := newTelemetryServiceForTest("synthetic.example")
	user, car := "synthetic-owner", 1
	scope := archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: "synthetic-instance", SourceVehicleID: "1"}
	now := time.Now().UTC()
	token := "synthetic-expiring-token"
	binding := service.memory.createArchiveBindingAt(token, user, car, scope, now, now.Add(1*time.Second))
	seedArchiveMemoryState(service.memory, user, car)
	key := telemetryKey{UserID: user, VehicleID: car}
	service.memory.mu.Lock()
	before := cloneTelemetrySessions(service.memory.completed[key])
	started, next := service.memory.startedAt[key], service.memory.nextPublicID
	service.memory.mu.Unlock()
	ctx := &archivePauseAtPublishContext{Context: context.Background(), pause: 1200 * time.Millisecond}
	incoming := telemetrySession{ID: "synthetic-new", Kind: "drive", StartAt: now, Source: "teslamate_archive"}
	err := service.memory.importBoundSessions(ctx, archiveImportGuard{binding: binding, tokenHash: archiveBindingTokenHash(token)}, []telemetrySession{incoming}, nil)
	if !errors.Is(err, errArchiveBindingExpired) || ctx.calls != 2 {
		t.Fatalf("expiry not checked after staging: %v", err)
	}
	service.memory.mu.Lock()
	defer service.memory.mu.Unlock()
	if !reflect.DeepEqual(before, service.memory.completed[key]) || started != service.memory.startedAt[key] || next != service.memory.nextPublicID {
		t.Fatal("expired staging changed existing sessions, route, cost, startedAt or nextPublicID")
	}
}

type archiveGateAtPublishContext struct {
	context.Context
	calls   int
	entered chan struct{}
	release chan struct{}
}

func (c *archiveGateAtPublishContext) Err() error {
	c.calls++
	if c.calls == 2 {
		close(c.entered)
		<-c.release
	}
	return c.Context.Err()
}

func TestArchiveImportMemoryRevokeWaitsForBoundCommit(t *testing.T) {
	service := newTelemetryServiceForTest("synthetic.example")
	user, car := "synthetic-owner", 1
	scope := archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: "synthetic-instance", SourceVehicleID: "1"}
	token, binding, err := service.memory.createArchiveBinding(context.Background(), user, car, scope)
	if err != nil {
		t.Fatal(err)
	}
	seedArchiveMemoryState(service.memory, user, car)
	key := telemetryKey{UserID: user, VehicleID: car}
	service.memory.mu.Lock()
	before := cloneTelemetrySessions(service.memory.completed[key])
	oldNext := service.memory.nextPublicID
	service.memory.mu.Unlock()
	gate := &archiveGateAtPublishContext{Context: context.Background(), entered: make(chan struct{}), release: make(chan struct{})}
	var once sync.Once
	release := func() { once.Do(func() { close(gate.release) }) }
	defer release()
	start := time.Date(2026, 7, 1, 10, 0, 0, 0, time.UTC)
	incoming := telemetrySession{ID: "synthetic-new", Kind: "drive", StartAt: start, Source: "teslamate_archive"}
	imported := make(chan error, 1)
	go func() {
		imported <- service.memory.importBoundSessions(gate, archiveImportGuard{binding: binding, tokenHash: archiveBindingTokenHash(token)}, []telemetrySession{incoming}, nil)
	}()
	select {
	case <-gate.entered:
	case <-time.After(5 * time.Second):
		t.Fatal("import did not reach commit gate")
	}
	revokeStarted := make(chan struct{})
	revoked := make(chan error, 1)
	go func() {
		close(revokeStarted)
		revoked <- service.memory.revokeArchiveBinding(context.Background(), user, car, scope)
	}()
	<-revokeStarted
	select {
	case <-revoked:
		t.Fatal("revocation completed while import still holds mutex")
	case <-time.After(100 * time.Millisecond):
	}
	release()
	select {
	case err := <-imported:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("import did not finish")
	}
	select {
	case err := <-revoked:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("revocation did not finish")
	}
	service.memory.mu.Lock()
	defer service.memory.mu.Unlock()
	got := service.memory.completed[key]
	if len(got) != len(before)+1 || !reflect.DeepEqual(got[0], before[0]) || service.memory.nextPublicID != oldNext+1 || !service.memory.startedAt[key].Equal(start) {
		t.Fatal("committed import changed existing route/cost or misallocated new state")
	}
}

func TestArchiveImportPostgresLockedCommitAndExpiry(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	for _, scenario := range []string{"revoke", "expire", "cancel", "owner"} {
		t.Run(scenario, func(t *testing.T) {
			db := boundedHistoryTestStore(t)
			user, car := boundedHistoryTestTenant(t, db, "synthetic-archive-lock")
			scope := archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: "synthetic-instance", SourceVehicleID: "1"}
			token, binding, err := db.createArchiveBinding(context.Background(), user, car, scope)
			if err != nil {
				t.Fatal(err)
			}
			start := time.Date(2026, 9, 1, 10, 0, 0, 0, time.UTC)
			sessionID := scopedArchiveSessionID(user, car, "drive", binding.SourceInstanceID, binding.SourceVehicleID, "record-1")
			_, err = db.pool.Exec(context.Background(), `INSERT INTO jourvolt_telemetry_sessions(id,user_id,vehicle_id,kind,started_at,ended_at,source,quality_state,source_instance_id,source_vehicle_id,source_record_id,route_json,charge_points_json,cost) VALUES($1,$2,$3,'drive',$4,$5,'teslamate_archive','observed',$6,$7,'record-1','[]'::jsonb,'[]'::jsonb,12.5)`, sessionID, user, car, start, start.Add(time.Minute), scope.SourceInstanceID, scope.SourceVehicleID)
			if err != nil {
				t.Fatal(err)
			}
			if scenario == "expire" {
				_, err = db.pool.Exec(context.Background(), `UPDATE jourvolt_history_archive_bindings SET expires_at=clock_timestamp()+interval '3 seconds' WHERE token_hash=$1`, archiveBindingTokenHash(token))
				if err != nil {
					t.Fatal(err)
				}
			}
			blocker, err := db.pool.Begin(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			defer blocker.Rollback(context.Background())
			if _, err = blocker.Exec(context.Background(), `UPDATE jourvolt_telemetry_sessions SET cost=cost WHERE id=$1`, sessionID); err != nil {
				t.Fatal(err)
			}
			req := archiveImportRequestForVehicleTest(token, car, scope.SourceInstanceID, scope.SourceVehicleID)
			var payload historyImportRequest
			if err = json.NewDecoder(req.Body).Decode(&payload); err != nil {
				t.Fatal(err)
			}
			secondStart := start.Add(2 * time.Hour)
			payload.Drives = append(payload.Drives, historyImportSession{
				SessionID: "synthetic-second", SourceRecordID: "record-2",
				StartedAt: secondStart.Format(time.RFC3339), EndedAt: secondStart.Add(time.Minute).Format(time.RFC3339),
			})
			encoded, err := json.Marshal(payload)
			if err != nil {
				t.Fatal(err)
			}
			req = httptest.NewRequest(http.MethodPost, req.URL.Path, bytes.NewReader(encoded))
			req.Header.Set(archiveBindingHeader, token)
			reqCtx, cancel := context.WithCancel(req.Context())
			defer cancel()
			req = req.WithContext(reqCtx)
			a := &app{store: db, telemetry: &telemetryService{store: db}}
			responses := make(chan *httptest.ResponseRecorder, 1)
			go func() { out := httptest.NewRecorder(); a.archiveBindingResource(out, req, user); responses <- out }()
			probe, err := db.pool.Acquire(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			defer probe.Release()
			lockName := scopedImportedSessionID(user, car, "drive", start.Format(time.RFC3339Nano))
			deadline := time.Now().Add(5 * time.Second)
			held := false
			for time.Now().Before(deadline) {
				var acquired bool
				if err = probe.QueryRow(context.Background(), `SELECT pg_try_advisory_lock(hashtextextended($1,0))`, lockName).Scan(&acquired); err != nil {
					t.Fatal(err)
				}
				if !acquired {
					held = true
					break
				}
				if _, err = probe.Exec(context.Background(), `SELECT pg_advisory_unlock(hashtextextended($1,0))`, lockName); err != nil {
					t.Fatal(err)
				}
				time.Sleep(10 * time.Millisecond)
			}
			if !held {
				t.Fatal("import never acquired session advisory lock")
			}
			var revokeDone chan error
			if scenario == "revoke" {
				revokeDone = make(chan error, 1)
				started := make(chan struct{})
				go func() { close(started); revokeDone <- db.revokeArchiveBinding(context.Background(), user, car, scope) }()
				<-started
				select {
				case <-revokeDone:
					t.Fatal("revocation completed before bound import")
				case <-time.After(100 * time.Millisecond):
				}
			}
			var ownerDone chan error
			if scenario == "owner" {
				other := "synthetic-owner-waits-" + mustRandomToken(t)
				if err = db.ensureUser(context.Background(), other); err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { _ = db.deleteUser(context.Background(), other) })
				ownerDone = make(chan error, 1)
				started := make(chan struct{})
				go func() {
					close(started)
					_, err := db.pool.Exec(context.Background(), `UPDATE jourvolt_vehicles SET user_id=$1 WHERE id=$2`, other, car)
					ownerDone <- err
				}()
				<-started
				select {
				case <-ownerDone:
					t.Fatal("owner changed before bound import completed")
				case <-time.After(100 * time.Millisecond):
				}
			}
			if scenario == "expire" {
				time.Sleep(3200 * time.Millisecond)
			}
			if scenario == "cancel" {
				cancel()
			}
			if err = blocker.Commit(context.Background()); err != nil {
				t.Fatal(err)
			}
			var out *httptest.ResponseRecorder
			select {
			case out = <-responses:
			case <-time.After(10 * time.Second):
				t.Fatal("import did not finish")
			}
			switch scenario {
			case "revoke":
				if out.Code != http.StatusOK {
					t.Fatalf("import status=%d", out.Code)
				}
				select {
				case err := <-revokeDone:
					if err != nil {
						t.Fatal(err)
					}
				case <-time.After(5 * time.Second):
					t.Fatal("revoke did not finish")
				}
				after := httptest.NewRecorder()
				a.archiveBindingResource(after, archiveImportRequestForVehicleTest(token, car, scope.SourceInstanceID, scope.SourceVehicleID), user)
				if after.Code != http.StatusForbidden {
					t.Fatalf("revoked status=%d", after.Code)
				}
			case "owner":
				if out.Code != http.StatusOK {
					t.Fatalf("import status=%d", out.Code)
				}
				select {
				case err := <-ownerDone:
					if err != nil {
						t.Fatal(err)
					}
				case <-time.After(5 * time.Second):
					t.Fatal("owner update did not finish")
				}
				after := httptest.NewRecorder()
				a.archiveBindingResource(after, archiveImportRequestForVehicleTest(token, car, scope.SourceInstanceID, scope.SourceVehicleID), user)
				if after.Code != http.StatusForbidden {
					t.Fatalf("changed owner status=%d", after.Code)
				}
			case "expire":
				if out.Code != http.StatusForbidden {
					t.Fatalf("expired status=%d", out.Code)
				}
			case "cancel":
				if out.Code != http.StatusServiceUnavailable {
					t.Fatalf("cancelled status=%d", out.Code)
				}
			}
			var n int
			var cost float64
			if err = db.pool.QueryRow(context.Background(), `SELECT count(*),COALESCE(max(cost),0) FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2`, user, car).Scan(&n, &cost); err != nil {
				t.Fatal(err)
			}
			expectedCount := 1
			if scenario == "revoke" || scenario == "owner" {
				expectedCount = 2
			}
			if n != expectedCount || cost != 12.5 {
				t.Fatal("locked import changed the sentinel or left a partial new row")
			}
		})
	}
}
