package main

import (
	"context"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
)

func nativeRebuildFaultFixture(t *testing.T, phase string) (*telemetryService, telemetryVehicleRef, int, nativeShadowRebuildResult) {
	t.Helper()
	s, ref, id := nativeShadowAuditFixture(t, "drive", 1)
	_, err := s.store.pool.Exec(context.Background(), `UPDATE jourvolt_telemetry_sessions SET route_json=(
        SELECT jsonb_agg(jsonb_build_object('latitude',1,'longitude',i,'power',NULL,'speed',0)) FROM generate_series(1,1280)i)
        WHERE user_id=$1 AND vehicle_id=$2`, ref.UserID, ref.VehicleID)
	if err != nil {
		t.Fatal(err)
	}
	limit := 1
	if phase == "verifying" {
		limit = 16
	}
	r, err := rebuildNativeShadowBatch(context.Background(), s.store.pool, ref.UserID, ref.VehicleID, id, "", limit)
	if err != nil {
		t.Fatal(err)
	}
	if phase == "verifying" {
		r, err = rebuildNativeShadowBatch(context.Background(), s.store.pool, ref.UserID, ref.VehicleID, id, r.GenerationID, 1)
		if err != nil {
			t.Fatal(err)
		}
	}
	if r.Phase != phase {
		t.Fatalf("phase=%+v", r)
	}
	return s, ref, id, r
}

func TestTelemetryPostgresNativeShadowRebuildRollbackCancellation(t *testing.T) {
	for _, phase := range []string{"building", "verifying"} {
		t.Run(phase, func(t *testing.T) {
			s, ref, id, first := nativeRebuildFaultFixture(t, phase)
			ctx := context.Background()
			original, checkpoint := nativeShadowFingerprint(t, s, ref), nativeShadowGenerationFingerprint(t, s, ref)
			name := "rebuild_fault_" + strings.ReplaceAll(mustRandomToken(t), "-", "_")
			fn, tr := pgx.Identifier{name}.Sanitize(), pgx.Identifier{name + "_trigger"}.Sanitize()
			defer func() {
				_, _ = s.store.pool.Exec(ctx, `DROP TRIGGER IF EXISTS `+tr+` ON jourvolt_telemetry_shadow_generations;DROP FUNCTION IF EXISTS `+fn+`() `)
			}()
			body := fmt.Sprintf(`CREATE FUNCTION %s() RETURNS trigger AS $$ BEGIN IF NEW.id='%s' AND (NEW.next_chunk<>OLD.next_chunk OR NEW.verify_chunk<>OLD.verify_chunk) THEN RAISE EXCEPTION 'synthetic rebuild checkpoint fault';END IF;RETURN NEW;END $$ LANGUAGE plpgsql;CREATE TRIGGER %s BEFORE UPDATE ON jourvolt_telemetry_shadow_generations FOR EACH ROW EXECUTE FUNCTION %s()`, fn, first.GenerationID, tr, fn)
			if _, err := s.store.pool.Exec(ctx, body); err != nil {
				t.Fatal(err)
			}
			if _, err := rebuildNativeShadowBatch(ctx, s.store.pool, ref.UserID, ref.VehicleID, id, first.GenerationID, 16); err == nil {
				t.Fatal("checkpoint fault accepted")
			}
			if nativeShadowGenerationFingerprint(t, s, ref) != checkpoint {
				t.Fatal("fault committed chunks/cursor/pointer")
			}
			key := time.Now().UnixNano() % 1_000_000_000
			body = fmt.Sprintf(`CREATE OR REPLACE FUNCTION %s() RETURNS trigger AS $$ BEGIN IF NEW.id='%s' AND (NEW.next_chunk<>OLD.next_chunk OR NEW.verify_chunk<>OLD.verify_chunk) THEN PERFORM pg_advisory_xact_lock(%d);PERFORM pg_sleep(30);END IF;RETURN NEW;END $$ LANGUAGE plpgsql`, fn, first.GenerationID, key)
			if _, err := s.store.pool.Exec(ctx, body); err != nil {
				t.Fatal(err)
			}
			callCtx, cancel := context.WithCancel(ctx)
			defer cancel()
			done := make(chan error, 1)
			go func() {
				_, err := rebuildNativeShadowBatch(callCtx, s.store.pool, ref.UserID, ref.VehicleID, id, first.GenerationID, 16)
				done <- err
			}()
			entered := false
			deadline := time.Now().Add(3 * time.Second)
			for time.Now().Before(deadline) {
				if err := s.store.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND classid=0 AND objid=$1::oid AND granted)`, key).Scan(&entered); err != nil {
					t.Fatal(err)
				}
				if entered {
					break
				}
				time.Sleep(5 * time.Millisecond)
			}
			if !entered {
				t.Fatal("checkpoint UPDATE not observed")
			}
			cancel()
			select {
			case err := <-done:
				if err == nil {
					t.Fatal("cancel succeeded")
				}
			case <-time.After(3 * time.Second):
				t.Fatal("cancellation stuck")
			}
			if nativeShadowGenerationFingerprint(t, s, ref) != checkpoint {
				t.Fatal("cancellation committed chunks/cursor/pointer")
			}
			if _, err := s.store.pool.Exec(ctx, `DROP TRIGGER `+tr+` ON jourvolt_telemetry_shadow_generations`); err != nil {
				t.Fatal(err)
			}
			r, err := rebuildNativeShadowBatch(ctx, s.store.pool, ref.UserID, ref.VehicleID, id, first.GenerationID, 16)
			if err != nil {
				t.Fatal(err)
			}
			if phase == "building" && r.Phase != "verifying" || phase == "verifying" && (r.Phase != "verified" || !r.Selected) {
				t.Fatalf("retry=%+v", r)
			}
			if nativeShadowFingerprint(t, s, ref) != original {
				t.Fatal("rebuild changed original history")
			}
		})
	}
}

func TestTelemetryPostgresNativeShadowRebuildParentLockOrder(t *testing.T) {
	for _, phase := range []string{"building", "verifying"} {
		t.Run(phase, func(t *testing.T) {
			s, ref, id, first := nativeRebuildFaultFixture(t, phase)
			ctx := context.Background()
			original := nativeShadowFingerprint(t, s, ref)
			hold, err := s.store.pool.Begin(ctx)
			if err != nil {
				t.Fatal(err)
			}
			defer hold.Rollback(ctx)
			if _, err = hold.Exec(ctx, `SELECT id FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2 FOR UPDATE`, ref.UserID, ref.VehicleID); err != nil {
				t.Fatal(err)
			}
			done := make(chan error, 1)
			go func() {
				_, err := rebuildNativeShadowBatch(ctx, s.store.pool, ref.UserID, ref.VehicleID, id, first.GenerationID, 1)
				done <- err
			}()
			waitNativeShadowAuditLock(t, s, "SELECT id,kind,source,%")
			if _, err = hold.Exec(ctx, `SELECT id FROM jourvolt_telemetry_shadow_generations WHERE id=$1 FOR UPDATE NOWAIT`, first.GenerationID); err != nil {
				t.Fatalf("generation locked before source: %v", err)
			}
			if _, err = hold.Exec(ctx, `DELETE FROM jourvolt_users WHERE id=$1`, ref.UserID); err != nil {
				t.Fatal(err)
			}
			var n int
			if err = hold.QueryRow(ctx, `SELECT count(*) FROM jourvolt_telemetry_shadow_generations WHERE id=$1`, first.GenerationID).Scan(&n); err != nil || n != 0 {
				t.Fatalf("cascade=%d %v", n, err)
			}
			if err = hold.Rollback(ctx); err != nil {
				t.Fatal(err)
			}
			select {
			case err = <-done:
				if err != nil {
					t.Fatal(err)
				}
			case <-time.After(3 * time.Second):
				t.Fatal("rebuild/account cascade cycle")
			}
			if nativeShadowFingerprint(t, s, ref) != original {
				t.Fatal("rolled-back cascade changed source")
			}
		})
	}
}

func TestTelemetryPostgresNativeShadowRebuildInsertLockOrder(t *testing.T) {
	s, ref, _, first := nativeRebuildFaultFixture(t, "building")
	ctx := context.Background()
	hold, err := s.store.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer hold.Rollback(ctx)
	var session string
	if err = hold.QueryRow(ctx, `SELECT session_id FROM jourvolt_telemetry_shadow_generations WHERE id=$1 FOR UPDATE`, first.GenerationID).Scan(&session); err != nil {
		t.Fatal(err)
	}
	insert := `INSERT INTO jourvolt_telemetry_shadow_generation_chunks(generation_id,session_id,user_id,vehicle_id,chunk_index,start_index,sample_count,payload,payload_sha256) VALUES($1,$2,$3,$4,$5,$6,1,'[null]'::bytea,encode(sha256('[null]'::bytea),'hex'))`
	args := []any{first.GenerationID, session, ref.UserID, ref.VehicleID, first.NextChunk, first.NextSample}
	writer := make(chan error, 1)
	go func() {
		_, err := s.store.pool.Exec(ctx, "/* generation_competing_insert */ "+insert, args...)
		writer <- err
	}()
	waitNativeShadowAuditLock(t, s, "/* generation_competing_insert */%")
	// The competing BEFORE INSERT must not yet own the next unique chunk key.
	bounded, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	if _, err = hold.Exec(bounded, insert, args...); err != nil {
		t.Fatalf("chunk key owned before generation lock: %v", err)
	}
	if err = hold.Rollback(ctx); err != nil {
		t.Fatal(err)
	}
	select {
	case err = <-writer:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("competing insert stuck")
	}
	var revision int64
	if err = s.store.pool.QueryRow(ctx, `SELECT content_revision FROM jourvolt_telemetry_shadow_generations WHERE id=$1`, first.GenerationID).Scan(&revision); err != nil || revision != first.ContentRevision+1 {
		t.Fatalf("revision=%d %v", revision, err)
	}
}

func TestTelemetryPostgresNativeShadowRebuildIndependentComparison(t *testing.T) {
	s, ref, id, first := nativeRebuildFaultFixture(t, "building")
	ctx := context.Background()
	original := nativeShadowFingerprint(t, s, ref)
	name := "zz_rebuild_corrupt_" + strings.ReplaceAll(mustRandomToken(t), "-", "_")
	fn, tr := pgx.Identifier{name}.Sanitize(), pgx.Identifier{name + "_trigger"}.Sanitize()
	defer func() {
		_, _ = s.store.pool.Exec(ctx, `DROP TRIGGER IF EXISTS `+tr+` ON jourvolt_telemetry_shadow_generation_chunks;DROP FUNCTION IF EXISTS `+fn+`() `)
	}()
	// Inject a valid array/count/hash with incorrect values during construction.
	// This proves the independent pass compares the source, not just build hashes.
	body := fmt.Sprintf(`CREATE FUNCTION %s() RETURNS trigger AS $$ BEGIN IF NEW.generation_id='%s' THEN
        NEW.payload := convert_to((SELECT jsonb_agg(jsonb_build_object('synthetic_wrong',true)) FROM generate_series(1,NEW.sample_count))::text,'UTF8');
        NEW.payload_sha256 := encode(sha256(NEW.payload),'hex'); END IF;RETURN NEW;END $$ LANGUAGE plpgsql;
        CREATE TRIGGER %s BEFORE INSERT ON jourvolt_telemetry_shadow_generation_chunks FOR EACH ROW EXECUTE FUNCTION %s()`, fn, first.GenerationID, tr, fn)
	if _, err := s.store.pool.Exec(ctx, body); err != nil {
		t.Fatal(err)
	}
	frozen := rebuildShadowTestBatch(t, s, ref, id, first.GenerationID, 16)
	if frozen.Phase != "verifying" || frozen.VerifyChunk != 0 {
		t.Fatalf("build did not freeze independently: %+v", frozen)
	}
	checkpoint := nativeShadowGenerationFingerprint(t, s, ref)
	if _, err := rebuildNativeShadowBatch(ctx, s.store.pool, ref.UserID, ref.VehicleID, id, frozen.GenerationID, 16); err == nil {
		t.Fatal("checksum-valid incorrect samples verified")
	}
	if nativeShadowGenerationFingerprint(t, s, ref) != checkpoint {
		t.Fatal("failed comparison advanced cursor or selected the bad generation")
	}
	if _, err := s.store.pool.Exec(ctx, `DROP TRIGGER `+tr+` ON jourvolt_telemetry_shadow_generation_chunks`); err != nil {
		t.Fatal(err)
	}
	fresh := rebuildShadowTestBatch(t, s, ref, id, "", 16)
	fresh = finishNativeShadowRebuild(t, s, ref, id, fresh)
	if !fresh.Selected || fresh.GenerationID == frozen.GenerationID {
		t.Fatalf("fresh independent recovery=%+v", fresh)
	}
	var oldPhase string
	if err := s.store.pool.QueryRow(ctx, `SELECT phase FROM jourvolt_telemetry_shadow_generations WHERE id=$1`, frozen.GenerationID).Scan(&oldPhase); err != nil || oldPhase != "verifying" {
		t.Fatalf("old candidate changed: %s %v", oldPhase, err)
	}
	if nativeShadowFingerprint(t, s, ref) != original {
		t.Fatal("comparison/recovery changed original history")
	}
}

func TestTelemetryPostgresNativeShadowRebuildConstraintRollback(t *testing.T) {
	s, ref, _, first := nativeRebuildFaultFixture(t, "building")
	ctx := context.Background()
	checkpoint := nativeShadowGenerationFingerprint(t, s, ref)
	for _, bad := range []struct {
		payload string
		count   int
		badHash bool
	}{
		{"[null]", 1, true}, {"[null]", 2, false}, {"{}", 1, false}, {`["` + strings.Repeat("x", 65536) + `"]`, 1, false},
	} {
		_, err := s.store.pool.Exec(ctx, `INSERT INTO jourvolt_telemetry_shadow_generation_chunks(generation_id,session_id,user_id,vehicle_id,chunk_index,start_index,sample_count,payload,payload_sha256)
            SELECT id,session_id,user_id,vehicle_id,next_chunk,next_sample,$2,$3::bytea,CASE WHEN $4 THEN repeat('0',64) ELSE encode(sha256($3::bytea),'hex') END
            FROM jourvolt_telemetry_shadow_generations WHERE id=$1`, first.GenerationID, bad.count, []byte(bad.payload), bad.badHash)
		if err == nil {
			t.Fatal("invalid generated chunk accepted")
		}
		if nativeShadowGenerationFingerprint(t, s, ref) != checkpoint {
			t.Fatal("constraint failure advanced revision or cursor")
		}
	}
	if _, err := s.store.pool.Exec(ctx, `INSERT INTO jourvolt_telemetry_shadow_generation_chunks SELECT * FROM jourvolt_telemetry_shadow_generation_chunks WHERE generation_id=$1`, first.GenerationID); err == nil {
		t.Fatal("duplicate chunk inserted")
	}
	if nativeShadowGenerationFingerprint(t, s, ref) != checkpoint {
		t.Fatal("duplicate-key rollback changed generation revision")
	}
}
