package main

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
)

func compactBootstrapFixture(t *testing.T) (*telemetryService, compactBootstrapExpectation) {
	t.Helper()
	s, user, car := openHistoryQueryTestDB(t)
	s.config = &telemetryConfig{StopDebounce: defaultDriveStopDebounce}
	e := compactBootstrapExpectation{Scope: telemetryKey{UserID: user, VehicleID: car}, VINHash: "bootstrap-" + mustRandomToken(t), Source: "telemetry_mqtt", Version: 1, Debounce: defaultDriveStopDebounce}
	if err := s.registerVehicle(context.Background(), telemetryVehicleRef{UserID: user, VehicleID: car, VINHash: e.VINHash}); err != nil {
		t.Fatal(err)
	}
	return s, e
}

func compactBootstrapLatest(t *testing.T, s *telemetryService, e compactBootstrapExpectation, field, raw string, at time.Time) {
	t.Helper()
	_, err := s.store.pool.Exec(context.Background(), `INSERT INTO jourvolt_telemetry_latest(user_id,vehicle_id,field_name,value_json,observed_at,source,value_hash)
        VALUES($1,$2,$3,$4::jsonb,$5,'telemetry_mqtt',$6)`, e.Scope.UserID, e.Scope.VehicleID, field, raw, at, hashTelemetryValue(raw))
	if err != nil {
		t.Fatal(err)
	}
}

func compactBootstrapSourceHash(t *testing.T, s *telemetryService, e compactBootstrapExpectation) string {
	t.Helper()
	var hash string
	err := s.store.pool.QueryRow(context.Background(), `SELECT md5(
        COALESCE((SELECT jsonb_agg(to_jsonb(x) ORDER BY field_name)::text FROM jourvolt_telemetry_latest x WHERE user_id=$1 AND vehicle_id=$2),'') ||
        COALESCE((SELECT jsonb_agg(to_jsonb(x) ORDER BY id)::text FROM jourvolt_telemetry_sessions x WHERE user_id=$1 AND vehicle_id=$2),'') ||
        COALESCE((SELECT jsonb_agg(to_jsonb(x) ORDER BY event_id)::text FROM jourvolt_telemetry_event_buffer x WHERE user_id=$1 AND vehicle_id=$2),''))`, e.Scope.UserID, e.Scope.VehicleID).Scan(&hash)
	if err != nil {
		t.Fatal(err)
	}
	return hash
}

func callCompactBootstrap(t *testing.T, s *telemetryService, e compactBootstrapExpectation) (compactBootstrapCandidate, error) {
	t.Helper()
	before := compactBootstrapSourceHash(t, s, e)
	tx, err := s.store.pool.Begin(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(context.Background())
	got, err := prepareCompactBootstrap(context.Background(), tx, e)
	if rollbackErr := tx.Rollback(context.Background()); rollbackErr != nil {
		t.Fatal(rollbackErr)
	}
	if after := compactBootstrapSourceHash(t, s, e); after != before {
		t.Fatal("read-only bootstrap changed predecessor history/latest/admission")
	}
	return got, err
}

func requireCompactBootstrapReason(t *testing.T, err error, reason string) {
	t.Helper()
	var ineligible *compactBootstrapError
	if !errors.As(err, &ineligible) || ineligible.Reason != reason {
		t.Fatalf("reason=%q err=%v", reason, err)
	}
}

func TestTelemetryPostgresCompactBootstrapPredecessor(t *testing.T) {
	s, e := compactBootstrapFixture(t)
	at := time.Date(2026, 10, 3, 0, 1, 2, 123456000, time.UTC)
	for _, field := range compactTelemetryFields {
		value := "0"
		if field == "GpsHeading" {
			value = "180.5"
		}
		if field == "Gear" {
			value = `"P"`
		}
		compactBootstrapLatest(t, s, e, field, value, at)
	}
	// Unused historical JSON must never be transferred just to discard it.
	if _, err := s.store.pool.Exec(context.Background(), `UPDATE jourvolt_telemetry_latest SET value_json=jsonb_build_object('unused',repeat('x',8388608)) WHERE user_id=$1 AND field_name='Location'`, e.Scope.UserID); err != nil {
		t.Fatal(err)
	}
	got, err := callCompactBootstrap(t, s, e)
	if err != nil {
		t.Fatal(err)
	}
	if got.State.Scope != e.Scope || got.State.Version != 1 || got.State.Debounce != e.Debounce || got.Source != e.Source || got.VINHash != e.VINHash || len(got.PredecessorFingerprint) != 64 {
		t.Fatalf("incorrect candidate contract: %+v", got)
	}
	if got.State.Drive != nil || got.State.Charge != nil || got.State.StopCandidate != nil || got.State.ChargeEnergyStart != nil {
		t.Fatal("bootstrap invented a session or charge baseline")
	}
	if got.State.LastSpeed == nil || *got.State.LastSpeed != 0 || got.State.LastHeading == nil || *got.State.LastHeading != 180.5 || !got.State.LastSpeedAt.Equal(at) || !got.State.LastHeadingAt.Equal(at) {
		t.Fatal("predecessor defaults lost zero or timestamp precision")
	}
	if got.State.CurrentGear != "" || !got.State.CurrentGearAt.IsZero() {
		t.Fatal("bootstrap changed legacy PostgreSQL Gear restoration")
	}
	for _, seen := range got.State.FieldTimes {
		if !seen.Equal(at) {
			t.Fatal("field watermark lost or rounded")
		}
	}
	again, err := callCompactBootstrap(t, s, e)
	if err != nil || again.PredecessorFingerprint != got.PredecessorFingerprint {
		t.Fatalf("unstable predecessor fingerprint: %v", err)
	}
	if _, err = s.store.pool.Exec(context.Background(), `UPDATE jourvolt_telemetry_latest SET value_json='1',observed_at=observed_at+interval '1 microsecond' WHERE user_id=$1 AND field_name='VehicleSpeed'`, e.Scope.UserID); err != nil {
		t.Fatal(err)
	}
	changed, err := callCompactBootstrap(t, s, e)
	if err != nil || changed.PredecessorFingerprint == got.PredecessorFingerprint || changed.State.LastSpeed == nil || *changed.State.LastSpeed != 1 {
		t.Fatalf("fingerprint did not pin actual reducer predecessor: %v", err)
	}
}

func TestTelemetryPostgresCompactBootstrapRejectsPersistedInputs(t *testing.T) {
	for _, tc := range []struct{ name, field, raw, reason string }{
		{"unknown", "FutureField", `{}`, "unsupported_latest_field"},
		{"oversize_name", strings.Repeat("x", 100000), `{}`, "unsupported_latest_field"},
		{"null_speed", "VehicleSpeed", `null`, "invalid_latest_observation"},
		{"string_speed", "VehicleSpeed", `"1"`, "invalid_latest_observation"},
		{"oversize_speed", "VehicleSpeed", `"` + strings.Repeat("x", 100000) + `"`, "invalid_latest_observation"},
		{"overflow_heading", "GpsHeading", `1e1000`, "invalid_latest_observation"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s, e := compactBootstrapFixture(t)
			compactBootstrapLatest(t, s, e, tc.field, tc.raw, time.Now().UTC())
			got, err := callCompactBootstrap(t, s, e)
			requireCompactBootstrapReason(t, err, tc.reason)
			if got.State.Version != 0 {
				t.Fatal("ineligible input returned a usable partial state")
			}
		})
	}
	for _, tc := range []struct{ name, update, reason string }{
		{"source", "source='unrecognized'", "unsupported_latest_source"},
		{"oversize_source", "source=repeat('x',100000)", "unsupported_latest_source"},
		{"hash", "value_hash=repeat('x',100000)", "invalid_latest_observation"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s, e := compactBootstrapFixture(t)
			compactBootstrapLatest(t, s, e, "VehicleSpeed", "0", time.Now().UTC())
			if _, err := s.store.pool.Exec(context.Background(), `UPDATE jourvolt_telemetry_latest SET `+tc.update+` WHERE user_id=$1`, e.Scope.UserID); err != nil {
				t.Fatal(err)
			}
			_, err := callCompactBootstrap(t, s, e)
			requireCompactBootstrapReason(t, err, tc.reason)
		})
	}
	for _, tc := range []struct {
		name   string
		change func(*compactBootstrapExpectation)
	}{
		{"version", func(e *compactBootstrapExpectation) { e.Version++ }},
		{"source", func(e *compactBootstrapExpectation) { e.Source = "local_import" }},
		{"debounce", func(e *compactBootstrapExpectation) { e.Debounce = 0 }},
	} {
		t.Run("contract_"+tc.name, func(t *testing.T) {
			s, e := compactBootstrapFixture(t)
			tc.change(&e)
			_, err := callCompactBootstrap(t, s, e)
			requireCompactBootstrapReason(t, err, "unsupported_contract")
		})
	}
}

func TestTelemetryPostgresCompactBootstrapOwnershipAndActiveSessions(t *testing.T) {
	s, e := compactBootstrapFixture(t)
	if _, err := callCompactBootstrap(t, s, e); err != nil {
		t.Fatal(err)
	}
	wrong := e
	wrong.VINHash += "-changed"
	_, err := callCompactBootstrap(t, s, wrong)
	requireCompactBootstrapReason(t, err, "ownership_or_binding_mismatch")
	other := "bootstrap-other-" + mustRandomToken(t)
	if err = s.store.ensureUser(context.Background(), other); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = s.store.deleteUser(context.Background(), other) })
	wrong = e
	wrong.Scope.UserID = other
	// Existing separate foreign keys allow this pair; the gate must reject it.
	if err = s.registerVehicle(context.Background(), telemetryVehicleRef{UserID: other, VehicleID: e.Scope.VehicleID, VINHash: e.VINHash}); err != nil {
		t.Fatal(err)
	}
	_, err = callCompactBootstrap(t, s, wrong)
	requireCompactBootstrapReason(t, err, "ownership_or_binding_mismatch")
	for _, kind := range []string{"drive", "charge"} {
		if _, err = s.store.pool.Exec(context.Background(), `INSERT INTO jourvolt_telemetry_sessions(id,user_id,vehicle_id,kind,started_at,route_json) VALUES($1,$2,$3,$4,now(),'[{}]')`, e.Scope.UserID+kind, e.Scope.UserID, e.Scope.VehicleID, kind); err != nil {
			t.Fatal(err)
		}
		_, err = callCompactBootstrap(t, s, e)
		requireCompactBootstrapReason(t, err, "legacy_active_requires_drain")
	}
}

func TestTelemetryPostgresCompactBootstrapSchemaAndRowCaps(t *testing.T) {
	for _, index := range []string{
		"", "CREATE INDEX jourvolt_telemetry_open_session_idx ON jourvolt_telemetry_sessions(user_id,vehicle_id,kind) WHERE ended_at IS NULL",
		"CREATE UNIQUE INDEX jourvolt_telemetry_open_session_idx ON jourvolt_telemetry_sessions(user_id,kind,vehicle_id) WHERE ended_at IS NULL",
		"CREATE UNIQUE INDEX jourvolt_telemetry_open_session_idx ON jourvolt_telemetry_sessions(user_id,vehicle_id,kind) WHERE ended_at IS NOT NULL",
		"CREATE UNIQUE INDEX jourvolt_telemetry_open_session_idx ON jourvolt_telemetry_sessions(user_id,vehicle_id,kind)",
	} {
		t.Run(fmt.Sprint(len(index))+index, func(t *testing.T) {
			s, e := compactBootstrapFixture(t)
			tx, err := s.store.pool.Begin(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			defer tx.Rollback(context.Background())
			if _, err = tx.Exec(context.Background(), `CREATE TEMP TABLE jourvolt_telemetry_sessions(user_id text,vehicle_id integer,kind text,ended_at timestamptz)`); err != nil {
				t.Fatal(err)
			}
			if index != "" {
				if _, err = tx.Exec(context.Background(), index); err != nil {
					t.Fatal(err)
				}
			}
			_, err = prepareCompactBootstrap(context.Background(), tx, e)
			requireCompactBootstrapReason(t, err, "invalid_active_index")
		})
	}
	t.Run("latest_sentinel", func(t *testing.T) {
		s, e := compactBootstrapFixture(t)
		for _, field := range compactTelemetryFields {
			compactBootstrapLatest(t, s, e, field, "0", time.Now().UTC())
		}
		compactBootstrapLatest(t, s, e, "FutureField", "0", time.Now().UTC())
		_, err := callCompactBootstrap(t, s, e)
		requireCompactBootstrapReason(t, err, "too_many_latest_fields")
	})
	for _, kind := range []string{"park", "third"} {
		t.Run("invalid_active_"+kind, func(t *testing.T) {
			s, e := compactBootstrapFixture(t)
			tx, err := s.store.pool.Begin(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			defer tx.Rollback(context.Background())
			if _, err = tx.Exec(context.Background(), `CREATE TEMP TABLE jourvolt_telemetry_sessions(user_id text,vehicle_id integer,kind text,ended_at timestamptz);
                CREATE UNIQUE INDEX jourvolt_telemetry_open_session_idx ON jourvolt_telemetry_sessions(user_id,vehicle_id,kind) WHERE ended_at IS NULL`); err != nil {
				t.Fatal(err)
			}
			kinds := []string{"park"}
			if kind == "third" {
				kinds = []string{"drive", "charge", "park"}
			}
			for _, value := range kinds {
				if _, err = tx.Exec(context.Background(), `INSERT INTO jourvolt_telemetry_sessions(user_id,vehicle_id,kind) VALUES($1,$2,$3)`, e.Scope.UserID, e.Scope.VehicleID, value); err != nil {
					t.Fatal(err)
				}
			}
			_, err = prepareCompactBootstrap(context.Background(), tx, e)
			requireCompactBootstrapReason(t, err, "invalid_active_sessions")
		})
	}
	t.Run("duplicate_latest", func(t *testing.T) {
		s, e := compactBootstrapFixture(t)
		tx, err := s.store.pool.Begin(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		defer tx.Rollback(context.Background())
		if _, err = tx.Exec(context.Background(), `CREATE TEMP TABLE jourvolt_telemetry_latest(LIKE public.jourvolt_telemetry_latest INCLUDING DEFAULTS)`); err != nil {
			t.Fatal(err)
		}
		if _, err = tx.Exec(context.Background(), `INSERT INTO jourvolt_telemetry_latest(user_id,vehicle_id,field_name,value_json,observed_at,source,value_hash)
            SELECT $1,$2,'VehicleSpeed','0',now(),'telemetry_mqtt',repeat('0',64) FROM generate_series(1,2)`, e.Scope.UserID, e.Scope.VehicleID); err != nil {
			t.Fatal(err)
		}
		_, err = prepareCompactBootstrap(context.Background(), tx, e)
		requireCompactBootstrapReason(t, err, "invalid_latest_observation")
	})
	for _, level := range []pgx.TxIsoLevel{pgx.RepeatableRead, pgx.Serializable} {
		t.Run(string(level), func(t *testing.T) {
			s, e := compactBootstrapFixture(t)
			tx, err := s.store.pool.BeginTx(context.Background(), pgx.TxOptions{IsoLevel: level})
			if err != nil {
				t.Fatal(err)
			}
			defer tx.Rollback(context.Background())
			_, err = prepareCompactBootstrap(context.Background(), tx, e)
			requireCompactBootstrapReason(t, err, "unsupported_transaction_isolation")
		})
	}
}

func waitCompactBootstrapPIDLock(t *testing.T, s *telemetryService, pid uint32) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		var waiting bool
		if err := s.store.pool.QueryRow(context.Background(), `SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=$1 AND wait_event_type='Lock')`, pid).Scan(&waiting); err != nil {
			t.Fatal(err)
		}
		if waiting {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatal("bootstrap lock wait was not observed")
}

func TestTelemetryPostgresCompactBootstrapLockCancellationAndLegacyRace(t *testing.T) {
	t.Run("wait_then_read_committed_predecessor", func(t *testing.T) {
		s, e := compactBootstrapFixture(t)
		at := time.Now().UTC().Truncate(time.Microsecond)
		compactBootstrapLatest(t, s, e, "VehicleSpeed", "0", at)
		hold, err := s.store.pool.Begin(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		defer hold.Rollback(context.Background())
		if _, err = hold.Exec(context.Background(), `SELECT user_id FROM jourvolt_telemetry_vehicle_keys WHERE user_id=$1 FOR KEY SHARE`, e.Scope.UserID); err != nil {
			t.Fatal(err)
		}
		tx, err := s.store.pool.Begin(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		defer tx.Rollback(context.Background())
		ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
		defer cancel()
		type result struct {
			candidate compactBootstrapCandidate
			err       error
		}
		done := make(chan result, 1)
		go func() { candidate, err := prepareCompactBootstrap(ctx, tx, e); done <- result{candidate, err} }()
		waitCompactBootstrapPIDLock(t, s, tx.Conn().PgConn().PID())
		if _, err = hold.Exec(context.Background(), `UPDATE jourvolt_telemetry_latest SET value_json='12',observed_at=$2 WHERE user_id=$1`, e.Scope.UserID, at.Add(time.Microsecond)); err != nil {
			t.Fatal(err)
		}
		if err = hold.Commit(context.Background()); err != nil {
			t.Fatal(err)
		}
		select {
		case r := <-done:
			if r.err != nil || r.candidate.State.LastSpeed == nil || *r.candidate.State.LastSpeed != 12 || !r.candidate.State.LastSpeedAt.Equal(at.Add(time.Microsecond)) {
				t.Fatalf("stale predecessor after lock wait: %+v %v", r.candidate, r.err)
			}
		case <-time.After(3 * time.Second):
			t.Fatal("bootstrap did not resume")
		}
	})
	t.Run("schema_lock_allows_unrelated_legacy_ingest", func(t *testing.T) {
		s, e := compactBootstrapFixture(t)
		other, otherExpected := compactBootstrapFixture(t)
		tx, err := s.store.pool.Begin(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		defer tx.Rollback(context.Background())
		if _, err = prepareCompactBootstrap(context.Background(), tx, e); err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		n, err := other.ingest(ctx, telemetryRecord{VINHash: otherExpected.VINHash, FieldName: "Gear", Value: "D", ObservedAt: time.Now().UTC(), EventID: "unrelated-bootstrap-lock", Source: "telemetry_mqtt"})
		if err != nil || n != 1 {
			t.Fatalf("unrelated legacy ROW EXCLUSIVE ingest blocked: %d %v", n, err)
		}
	})
	t.Run("cancel_waiting_for_mapping_before_latest", func(t *testing.T) {
		s, e := compactBootstrapFixture(t)
		compactBootstrapLatest(t, s, e, "VehicleSpeed", "0", time.Now().UTC())
		before := compactBootstrapSourceHash(t, s, e)
		hold, err := s.store.pool.Begin(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		defer hold.Rollback(context.Background())
		if _, err = hold.Exec(context.Background(), `SELECT user_id FROM jourvolt_telemetry_vehicle_keys WHERE user_id=$1 FOR KEY SHARE`, e.Scope.UserID); err != nil {
			t.Fatal(err)
		}
		tx, err := s.store.pool.Begin(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		defer tx.Rollback(context.Background())
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		done := make(chan error, 1)
		go func() { _, err := prepareCompactBootstrap(ctx, tx, e); done <- err }()
		waitCompactBootstrapPIDLock(t, s, tx.Conn().PgConn().PID())
		if _, err = hold.Exec(context.Background(), `SELECT field_name FROM jourvolt_telemetry_latest WHERE user_id=$1 FOR UPDATE NOWAIT`, e.Scope.UserID); err != nil {
			t.Fatalf("latest locked before mapping: %v", err)
		}
		cancel()
		select {
		case err = <-done:
			if !errors.Is(err, context.Canceled) {
				t.Fatalf("cancellation=%v", err)
			}
		case <-time.After(3 * time.Second):
			t.Fatal("cancellation did not finish")
		}
		_ = tx.Rollback(context.Background())
		_ = hold.Rollback(context.Background())
		if compactBootstrapSourceHash(t, s, e) != before {
			t.Fatal("cancellation mutated source")
		}
		if _, err = callCompactBootstrap(t, s, e); err != nil {
			t.Fatalf("retry=%v", err)
		}
	})
	t.Run("candidate_retains_mapping_lock_until_caller_finishes", func(t *testing.T) {
		s, e := compactBootstrapFixture(t)
		tx, err := s.store.pool.Begin(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		defer tx.Rollback(context.Background())
		if _, err = prepareCompactBootstrap(context.Background(), tx, e); err != nil {
			t.Fatal(err)
		}
		done := make(chan error, 1)
		ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
		defer cancel()
		go func() {
			n, err := s.ingest(ctx, telemetryRecord{VINHash: e.VINHash, FieldName: "Gear", Value: "D", ObservedAt: time.Now().UTC(), EventID: "bootstrap-race", Source: "telemetry_mqtt"})
			if err == nil && n != 1 {
				err = fmt.Errorf("accepted=%d", n)
			}
			done <- err
		}()
		waitNativeShadowAuditLock(t, s, "SELECT user_id, vehicle_id FROM jourvolt_telemetry_vehicle_keys WHERE vin_hash=%")
		var n int
		if err = tx.QueryRow(context.Background(), `SELECT count(*) FROM jourvolt_telemetry_event_buffer WHERE user_id=$1`, e.Scope.UserID).Scan(&n); err != nil || n != 0 {
			t.Fatalf("ingest passed gate: %d %v", n, err)
		}
		if err = tx.Commit(context.Background()); err != nil {
			t.Fatal(err)
		}
		select {
		case err = <-done:
			if err != nil {
				t.Fatal(err)
			}
		case <-time.After(3 * time.Second):
			t.Fatal("legacy ingest did not resume")
		}
		_, err = callCompactBootstrap(t, s, e)
		requireCompactBootstrapReason(t, err, "legacy_active_requires_drain")
	})
}

func TestTelemetryPostgresCompactBootstrapFencesIndexDrop(t *testing.T) {
	for _, mode := range []string{"", "CONCURRENTLY "} {
		t.Run("drop_"+strings.TrimSpace(mode), func(t *testing.T) {
			s, e := compactBootstrapFixture(t)
			ctx := context.Background()
			name := "bootstrap_schema_" + mustRandomToken(t)
			schema := pgx.Identifier{name}.Sanitize()
			if _, err := s.store.pool.Exec(ctx, `CREATE SCHEMA `+schema+`;CREATE TABLE `+schema+`.jourvolt_telemetry_sessions(user_id text,vehicle_id integer,kind text,ended_at timestamptz);
                CREATE UNIQUE INDEX jourvolt_telemetry_open_session_idx ON `+schema+`.jourvolt_telemetry_sessions(user_id,vehicle_id,kind) WHERE ended_at IS NULL`); err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				if _, err := s.store.pool.Exec(ctx, `DROP SCHEMA `+schema+` CASCADE`); err != nil {
					t.Error(err)
				}
			})
			hold, err := s.store.pool.Begin(ctx)
			if err != nil {
				t.Fatal(err)
			}
			defer hold.Rollback(ctx)
			if _, err = hold.Exec(ctx, `SET LOCAL search_path TO `+schema+`,public`); err != nil {
				t.Fatal(err)
			}
			if _, err = prepareCompactBootstrap(ctx, hold, e); err != nil {
				t.Fatal(err)
			}
			conn, err := s.store.pool.Acquire(ctx)
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Release()
			call, cancel := context.WithCancel(ctx)
			defer cancel()
			done := make(chan error, 1)
			go func() {
				_, err := conn.Exec(call, `DROP INDEX `+mode+schema+`.jourvolt_telemetry_open_session_idx`)
				done <- err
			}()
			waitCompactBootstrapPIDLock(t, s, conn.Conn().PgConn().PID())
			var valid bool
			if err = hold.QueryRow(ctx, compactBootstrapIndexSQL).Scan(&valid); err != nil || !valid {
				t.Fatalf("index invalidated while bootstrap holds schema lock: %v", err)
			}
			cancel()
			select {
			case err = <-done:
				if !errors.Is(err, context.Canceled) {
					t.Fatalf("drop cancellation=%v", err)
				}
			case <-time.After(3 * time.Second):
				t.Fatal("index drop cancellation stuck")
			}
			if err = hold.QueryRow(ctx, compactBootstrapIndexSQL).Scan(&valid); err != nil || !valid {
				t.Fatalf("canceled index drop changed capability: %v", err)
			}
		})
	}
}
