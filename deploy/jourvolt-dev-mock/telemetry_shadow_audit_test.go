package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
)

func nativeShadowAuditFixture(t *testing.T, kind string, points int) (*telemetryService, telemetryVehicleRef, int) {
	t.Helper()
	s, ref, start := nativeShadowFixture(t)
	field, value := "Gear", "D"
	if kind == "charge" {
		field, value = "DetailedChargeState", "Charging"
	}
	requireNativeShadowIngest(t, s, nativeShadowEvent(ref, start, 0, field, value), 1)
	for i := 1; i <= points; i++ {
		field, valueAny := "Location", any(map[string]any{"latitude": float64(i), "longitude": 0.0})
		if kind == "charge" {
			field, valueAny = "ACChargingPower", float64(i%5)
		}
		requireNativeShadowIngest(t, s, nativeShadowEvent(ref, start, i, field, valueAny), 1)
	}
	if kind == "drive" {
		requireNativeShadowIngest(t, s, nativeShadowEvent(ref, start, points+1, "Gear", "P"), 1)
		if n, err := s.finalizeDuePostgres(context.Background(), start.Add(time.Hour)); err != nil || n != 1 {
			t.Fatalf("finalizer=%d %v", n, err)
		}
	} else {
		requireNativeShadowIngest(t, s, nativeShadowEvent(ref, start, points+1, "DetailedChargeState", "Complete"), 1)
	}
	var publicID int
	if err := s.store.pool.QueryRow(context.Background(), `SELECT public_id FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2`, ref.UserID, ref.VehicleID).Scan(&publicID); err != nil {
		t.Fatal(err)
	}
	return s, ref, publicID
}

func auditShadowTestBatch(t *testing.T, s *telemetryService, ref telemetryVehicleRef, publicID int, job string, limit int) nativeShadowAuditResult {
	t.Helper()
	r, err := auditNativeShadowBatch(context.Background(), s.store.pool, ref.UserID, ref.VehicleID, publicID, job, limit)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func TestNativeShadowAuditProcessHelper(t *testing.T) {
	if os.Getenv("JOURVOLT_SHADOW_AUDIT_TEST_HELPER") != "1" {
		return
	}
	getenv := func(key string) string {
		if key == "DATABASE_URL" {
			return os.Getenv("JOURVOLT_TEST_DATABASE_URL")
		}
		return os.Getenv(key)
	}
	if err := runNativeShadowAudit(context.Background(), getenv, os.Stdout); err != nil {
		t.Fatal(err)
	}
}

func TestTelemetryPostgresNativeShadowAuditResumeAndProcess(t *testing.T) {
	for _, kind := range []string{"drive", "charge"} {
		t.Run(kind, func(t *testing.T) {
			s, ref, publicID := nativeShadowAuditFixture(t, kind, 19)
			original := nativeShadowFingerprint(t, s, ref)
			first := auditShadowTestBatch(t, s, ref, publicID, "", maxNativeShadowAuditChunks)
			if first.Done || first.NextChunk != 16 || first.NextSample != 16 || first.BatchChunks != 16 || first.BatchBytes > 16*maxNativeShadowChunkBytes {
				t.Fatalf("first=%+v", first)
			}
			cmd := exec.Command(os.Args[0], "-test.run=^TestNativeShadowAuditProcessHelper$", "-test.count=1")
			cmd.Env = append(os.Environ(), "JOURVOLT_SHADOW_AUDIT_TEST_HELPER=1", "JOURVOLT_SHADOW_AUDIT_USER_ID="+ref.UserID, "JOURVOLT_SHADOW_AUDIT_VEHICLE_ID="+strconv.Itoa(ref.VehicleID), "JOURVOLT_SHADOW_AUDIT_PUBLIC_ID="+strconv.Itoa(publicID), "JOURVOLT_SHADOW_AUDIT_JOB_ID="+first.JobID)
			output, err := cmd.CombinedOutput()
			if err != nil {
				t.Fatalf("new process: %v %s", err, output)
			}
			var next nativeShadowAuditResult
			if err = json.NewDecoder(bytes.NewReader(output)).Decode(&next); err != nil {
				t.Fatalf("decode: %v %s", err, output)
			}
			if !next.Done || next.NextChunk != 19 || next.NextSample != 19 || next.BatchChunks != 3 || next.JobID != first.JobID || next.ChainSHA256 == first.ChainSHA256 {
				t.Fatalf("next=%+v", next)
			}
			repeat := auditShadowTestBatch(t, s, ref, publicID, first.JobID, 16)
			if !repeat.Done || repeat.BatchChunks != 0 || repeat.ChainSHA256 != next.ChainSHA256 {
				t.Fatalf("repeat=%+v", repeat)
			}
			if nativeShadowFingerprint(t, s, ref) != original {
				t.Fatal("comparison changed history/chunks")
			}
		})
	}
	t.Run("empty", func(t *testing.T) {
		s, ref, id := nativeShadowAuditFixture(t, "drive", 0)
		r := auditShadowTestBatch(t, s, ref, id, "", 1)
		if !r.Done || r.NextSample != 0 || r.BatchBytes != 0 {
			t.Fatalf("empty=%+v", r)
		}
	})
}

func TestTelemetryPostgresNativeShadowAuditConcurrentResume(t *testing.T) {
	s, ref, id := nativeShadowAuditFixture(t, "drive", 5)
	first := auditShadowTestBatch(t, s, ref, id, "", 1)
	var wg sync.WaitGroup
	results := make(chan nativeShadowAuditResult, 2)
	errs := make(chan error, 2)
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			r, err := auditNativeShadowBatch(context.Background(), s.store.pool, ref.UserID, ref.VehicleID, id, first.JobID, 1)
			results <- r
			errs <- err
		}()
	}
	wg.Wait()
	close(results)
	close(errs)
	for err := range errs {
		if err != nil {
			t.Fatal(err)
		}
	}
	seen := map[int]bool{}
	for r := range results {
		seen[r.NextChunk] = true
		if r.BatchChunks != 1 {
			t.Fatalf("batch=%+v", r)
		}
	}
	if !seen[2] || !seen[3] {
		t.Fatal("cursor advancement skipped/repeated", seen)
	}
	last := auditShadowTestBatch(t, s, ref, id, first.JobID, 16)
	if !last.Done || last.NextSample != 5 {
		t.Fatalf("last=%+v", last)
	}
}

func TestTelemetryPostgresNativeShadowAuditDriftAndMismatch(t *testing.T) {
	for _, mutation := range []string{"source", "update", "delete", "insert", "completion"} {
		t.Run(mutation, func(t *testing.T) {
			s, ref, id := nativeShadowAuditFixture(t, "drive", 3)
			first := auditShadowTestBatch(t, s, ref, id, "", 1)
			ctx := context.Background()
			var statement string
			switch mutation {
			case "source":
				statement = `UPDATE jourvolt_telemetry_sessions SET route_json=jsonb_set(route_json,'{0,Latitude}','44') WHERE user_id=$1 AND vehicle_id=$2`
			case "completion":
				statement = `UPDATE jourvolt_telemetry_sessions SET completion_key=completion_key||'-changed' WHERE user_id=$1 AND vehicle_id=$2`
			case "update":
				statement = `UPDATE jourvolt_telemetry_detail_chunks SET payload=convert_to('[{"ObservedAt":"2026-09-07T00:00:01Z","Latitude":44}]','UTF8'),payload_sha256=encode(sha256(convert_to('[{"ObservedAt":"2026-09-07T00:00:01Z","Latitude":44}]','UTF8')),'hex') WHERE user_id=$1 AND vehicle_id=$2 AND chunk_index=0`
			case "delete":
				statement = `DELETE FROM jourvolt_telemetry_detail_chunks WHERE user_id=$1 AND vehicle_id=$2 AND chunk_index=0`
			case "insert":
				statement = `INSERT INTO jourvolt_telemetry_detail_chunks(session_id,user_id,vehicle_id,chunk_index,start_index,sample_count,payload,payload_sha256) SELECT session_id,user_id,vehicle_id,99,99,sample_count,payload,payload_sha256 FROM jourvolt_telemetry_detail_chunks WHERE user_id=$1 AND vehicle_id=$2 AND chunk_index=0`
			}
			if _, err := s.store.pool.Exec(ctx, statement, ref.UserID, ref.VehicleID); err != nil {
				t.Fatal(err)
			}
			if _, err := auditNativeShadowBatch(ctx, s.store.pool, ref.UserID, ref.VehicleID, id, first.JobID, 16); err == nil {
				t.Fatal("revision/identity drift accepted")
			}
			var cursor int
			if err := s.store.pool.QueryRow(ctx, `SELECT next_chunk FROM jourvolt_telemetry_shadow_audits WHERE id=$1`, first.JobID).Scan(&cursor); err != nil || cursor != 1 {
				t.Fatalf("cursor=%d err=%v", cursor, err)
			}
			if mutation == "update" || mutation == "delete" || mutation == "insert" {
				if _, err := auditNativeShadowBatch(ctx, s.store.pool, ref.UserID, ref.VehicleID, id, "", 16); err == nil {
					t.Fatal("fresh comparison accepted mismatched/gapped/extra chunks")
				}
			}
		})
	}
	t.Run("completed_stamp", func(t *testing.T) {
		s, ref, id := nativeShadowAuditFixture(t, "drive", 1)
		r := auditShadowTestBatch(t, s, ref, id, "", 16)
		if _, err := s.store.pool.Exec(context.Background(), `UPDATE jourvolt_telemetry_detail_chunks SET payload=payload WHERE user_id=$1 AND vehicle_id=$2`, ref.UserID, ref.VehicleID); err != nil {
			t.Fatal(err)
		}
		if _, err := auditNativeShadowBatch(context.Background(), s.store.pool, ref.UserID, ref.VehicleID, id, r.JobID, 16); !errors.Is(err, errNativeShadowAuditDrift) {
			t.Fatalf("completed stamp current after edit: %v", err)
		}
	})
}

func TestTelemetryPostgresNativeShadowAuditEligibilityAndScope(t *testing.T) {
	s, ref, id := nativeShadowAuditFixture(t, "charge", 2)
	ctx := context.Background()
	for _, limit := range []int{0, 17} {
		if _, err := auditNativeShadowBatch(ctx, s.store.pool, ref.UserID, ref.VehicleID, id, "", limit); err == nil {
			t.Fatal("invalid cap accepted")
		}
	}
	r := auditShadowTestBatch(t, s, ref, id, "", 1)
	if _, err := auditNativeShadowBatch(ctx, s.store.pool, "other-user", ref.VehicleID, id, r.JobID, 16); err == nil {
		t.Fatal("cross-user job accepted")
	}
	if _, err := auditNativeShadowBatch(ctx, s.store.pool, ref.UserID, ref.VehicleID+10000, id, r.JobID, 16); err == nil {
		t.Fatal("cross-vehicle job accepted")
	}
	for _, statement := range []string{
		`UPDATE jourvolt_telemetry_detail_shadow SET prefix_count=1 WHERE user_id=$1`,
		`UPDATE jourvolt_telemetry_detail_shadow SET started_with_session=false WHERE user_id=$1`,
		`UPDATE jourvolt_telemetry_detail_shadow SET stale=true WHERE user_id=$1`,
		`UPDATE jourvolt_telemetry_sessions SET ended_at=NULL WHERE user_id=$1`,
		`UPDATE jourvolt_telemetry_sessions SET source='local_import' WHERE user_id=$1`,
	} {
		tx, err := s.store.pool.Begin(ctx)
		if err != nil {
			t.Fatal(err)
		}
		if _, err = tx.Exec(ctx, statement, ref.UserID); err != nil {
			tx.Rollback(ctx)
			t.Fatal(err)
		}
		// The caller's connection cannot read uncommitted state; commit this
		// single fixture mutation, then restore the recorded eligible metadata.
		if err = tx.Commit(ctx); err != nil {
			t.Fatal(err)
		}
		if _, err = auditNativeShadowBatch(ctx, s.store.pool, ref.UserID, ref.VehicleID, id, "", 16); !errors.Is(err, errNativeShadowAuditIneligible) {
			t.Fatalf("ineligible accepted: %v", err)
		}
		if _, err = s.store.pool.Exec(ctx, `UPDATE jourvolt_telemetry_detail_shadow SET prefix_count=0,started_with_session=true,stale=false WHERE user_id=$1;`, ref.UserID); err != nil {
			t.Fatal(err)
		}
		if _, err = s.store.pool.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET ended_at=started_at+interval '3 seconds',source='telemetry_mqtt' WHERE user_id=$1`, ref.UserID); err != nil {
			t.Fatal(err)
		}
	}
}

func TestTelemetryPostgresNativeShadowAuditRollbackAndCancellation(t *testing.T) {
	s, ref, id := nativeShadowAuditFixture(t, "drive", 4)
	ctx := context.Background()
	first := auditShadowTestBatch(t, s, ref, id, "", 1)
	original := nativeShadowFingerprint(t, s, ref)
	name := "audit_fault_" + strings.ReplaceAll(mustRandomToken(t), "-", "_")
	fn, tr := pgx.Identifier{name}.Sanitize(), pgx.Identifier{name + "_trigger"}.Sanitize()
	defer func() {
		_, _ = s.store.pool.Exec(ctx, `DROP TRIGGER IF EXISTS `+tr+` ON jourvolt_telemetry_shadow_audits;DROP FUNCTION IF EXISTS `+fn+`() `)
	}()
	if _, err := s.store.pool.Exec(ctx, `CREATE FUNCTION `+fn+`() RETURNS trigger AS $$ BEGIN IF NEW.id='`+first.JobID+`' THEN RAISE EXCEPTION 'synthetic audit fault'; END IF;RETURN NEW;END $$ LANGUAGE plpgsql;CREATE TRIGGER `+tr+` BEFORE UPDATE ON jourvolt_telemetry_shadow_audits FOR EACH ROW EXECUTE FUNCTION `+fn+`() `); err != nil {
		t.Fatal(err)
	}
	if _, err := auditNativeShadowBatch(ctx, s.store.pool, ref.UserID, ref.VehicleID, id, first.JobID, 16); err == nil {
		t.Fatal("cursor-write fault accepted")
	}
	var next int
	if err := s.store.pool.QueryRow(ctx, `SELECT next_chunk FROM jourvolt_telemetry_shadow_audits WHERE id=$1`, first.JobID).Scan(&next); err != nil || next != 1 {
		t.Fatalf("partial checkpoint=%d %v", next, err)
	}
	lockKey := time.Now().UnixNano() % 1_000_000_000
	definition := fmt.Sprintf(`CREATE OR REPLACE FUNCTION %s() RETURNS trigger AS $$ BEGIN IF NEW.id='%s' THEN PERFORM pg_advisory_xact_lock(%d);PERFORM pg_sleep(30);END IF;RETURN NEW;END $$ LANGUAGE plpgsql`, fn, first.JobID, lockKey)
	if _, err := s.store.pool.Exec(ctx, definition); err != nil {
		t.Fatal(err)
	}
	callCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	result := make(chan error, 1)
	go func() {
		_, err := auditNativeShadowBatch(callCtx, s.store.pool, ref.UserID, ref.VehicleID, id, first.JobID, 16)
		result <- err
	}()
	deadline := time.Now().Add(3 * time.Second)
	entered := false
	for time.Now().Before(deadline) {
		if err := s.store.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND classid=0 AND objid=$1::oid AND granted)`, lockKey).Scan(&entered); err != nil {
			t.Fatal(err)
		}
		if entered {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	if !entered {
		t.Fatal("audit did not reach checkpoint update")
	}
	cancel()
	select {
	case err := <-result:
		if err == nil {
			t.Fatal("canceled audit succeeded")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("canceled audit stuck")
	}
	if err := s.store.pool.QueryRow(ctx, `SELECT next_chunk FROM jourvolt_telemetry_shadow_audits WHERE id=$1`, first.JobID).Scan(&next); err != nil || next != 1 {
		t.Fatalf("canceled checkpoint=%d %v", next, err)
	}
	if _, err := s.store.pool.Exec(ctx, `DROP TRIGGER `+tr+` ON jourvolt_telemetry_shadow_audits`); err != nil {
		t.Fatal(err)
	}
	last := auditShadowTestBatch(t, s, ref, id, first.JobID, 16)
	if !last.Done {
		t.Fatal("retry incomplete")
	}
	if nativeShadowFingerprint(t, s, ref) != original {
		t.Fatal("audit changed original data")
	}
}

func TestNativeShadowAuditCommandRequiresExplicitInputs(t *testing.T) {
	for _, input := range []map[string]string{
		{},
		{"DATABASE_URL": "not-a-dsn"},
		{"DATABASE_URL": "not-a-dsn", "JOURVOLT_SHADOW_AUDIT_VEHICLE_ID": "1"},
		{"DATABASE_URL": "not-a-dsn", "JOURVOLT_SHADOW_AUDIT_VEHICLE_ID": "1", "JOURVOLT_SHADOW_AUDIT_PUBLIC_ID": "1"},
		{"JOURVOLT_BACKFILL_HISTORY_SUMMARIES": "1"},
	} {
		var output bytes.Buffer
		if err := runNativeShadowAudit(context.Background(), func(k string) string { return input[k] }, &output); err == nil || output.Len() != 0 {
			t.Fatal("invalid maintenance inputs accepted")
		}
	}
}

func waitNativeShadowAuditLock(t *testing.T, s *telemetryService, pattern string) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		var waiting bool
		if err := s.store.pool.QueryRow(context.Background(), `SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE $1)`, pattern).Scan(&waiting); err != nil {
			t.Fatal(err)
		}
		if waiting {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatal("expected database lock wait not observed")
}

func TestTelemetryPostgresNativeShadowAuditParentLockOrder(t *testing.T) {
	s, ref, id := nativeShadowAuditFixture(t, "drive", 2)
	first := auditShadowTestBatch(t, s, ref, id, "", 1)
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
	result := make(chan error, 1)
	go func() {
		_, err := auditNativeShadowBatch(ctx, s.store.pool, ref.UserID, ref.VehicleID, id, first.JobID, 1)
		result <- err
	}()
	waitNativeShadowAuditLock(t, s, "SELECT id,kind,source,%")
	// The auditor must not hold its child cursor while waiting on its parent.
	if _, err = hold.Exec(ctx, `SELECT id FROM jourvolt_telemetry_shadow_audits WHERE id=$1 FOR UPDATE NOWAIT`, first.JobID); err != nil {
		t.Fatalf("cursor-first lock inversion: %v", err)
	}
	if _, err = hold.Exec(ctx, `DELETE FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2`, ref.UserID, ref.VehicleID); err != nil {
		t.Fatal(err)
	}
	var jobs int
	if err = hold.QueryRow(ctx, `SELECT count(*) FROM jourvolt_telemetry_shadow_audits WHERE id=$1`, first.JobID).Scan(&jobs); err != nil || jobs != 0 {
		t.Fatalf("cascade retained cursor=%d err=%v", jobs, err)
	}
	// Exercise deletion/cascade locks without committing any history deletion.
	if err = hold.Rollback(ctx); err != nil {
		t.Fatal(err)
	}
	select {
	case err = <-result:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("audit/delete lock cycle")
	}
	if nativeShadowFingerprint(t, s, ref) != original {
		t.Fatal("rolled-back cascade changed data")
	}
}

func TestTelemetryPostgresNativeShadowAuditChunkWriterLockOrder(t *testing.T) {
	s, ref, id := nativeShadowAuditFixture(t, "drive", 3)
	first := auditShadowTestBatch(t, s, ref, id, "", 1)
	ctx := context.Background()
	hold, err := s.store.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer hold.Rollback(ctx)
	if _, err = hold.Exec(ctx, `SELECT session_id FROM jourvolt_telemetry_detail_shadow WHERE user_id=$1 AND vehicle_id=$2 FOR SHARE`, ref.UserID, ref.VehicleID); err != nil {
		t.Fatal(err)
	}
	changed := make(chan error, 1)
	go func() {
		_, err := s.store.pool.Exec(ctx, `/* native_audit_chunk_writer */ UPDATE jourvolt_telemetry_detail_chunks SET payload=payload WHERE user_id=$1 AND vehicle_id=$2 AND chunk_index=0`, ref.UserID, ref.VehicleID)
		changed <- err
	}()
	waitNativeShadowAuditLock(t, s, "/* native_audit_chunk_writer */%")
	// The writer owns the chunk row while its revision trigger waits on our
	// SHARE lock. A plain MVCC chunk read must still finish at the old revision.
	r := auditShadowTestBatch(t, s, ref, id, first.JobID, 1)
	if r.NextChunk != 2 {
		t.Fatalf("progress=%+v", r)
	}
	if err = hold.Rollback(ctx); err != nil {
		t.Fatal(err)
	}
	select {
	case err = <-changed:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("chunk writer stuck")
	}
	if _, err = auditNativeShadowBatch(ctx, s.store.pool, ref.UserID, ref.VehicleID, id, first.JobID, 16); !errors.Is(err, errNativeShadowAuditDrift) {
		t.Fatalf("committed mutation not detected: %v", err)
	}
}

func TestTelemetryPostgresNativeShadowAuditRevisionGuards(t *testing.T) {
	s, a, id := nativeShadowAuditFixture(t, "drive", 1)
	_, b, _ := nativeShadowAuditFixture(t, "drive", 0)
	ctx := context.Background()
	before := nativeShadowFingerprint(t, s, a)
	tx, err := s.store.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(ctx)
	if _, err = tx.Exec(ctx, `UPDATE jourvolt_telemetry_detail_chunks SET payload=payload WHERE user_id=$1`, a.UserID); err != nil {
		t.Fatal(err)
	}
	if err = tx.Rollback(ctx); err != nil {
		t.Fatal(err)
	}
	if nativeShadowFingerprint(t, s, a) != before {
		t.Fatal("rolled-back mutation advanced revision")
	}
	if _, err = s.store.pool.Exec(ctx, `UPDATE jourvolt_telemetry_detail_chunks SET session_id=(SELECT session_id FROM jourvolt_telemetry_detail_shadow WHERE user_id=$2 AND vehicle_id=$3),user_id=$2,vehicle_id=$3 WHERE user_id=$1`, a.UserID, b.UserID, b.VehicleID); err == nil || !strings.Contains(err.Error(), "ownership is immutable") {
		t.Fatalf("chunk ownership guard: %v", err)
	}
	if _, err = s.store.pool.Exec(ctx, `TRUNCATE jourvolt_telemetry_detail_chunks`); err == nil || !strings.Contains(err.Error(), "cannot be truncated") {
		t.Fatalf("truncate guard: %v", err)
	}
	r := auditShadowTestBatch(t, s, a, id, "", 16)
	if !r.Done {
		t.Fatal("failed DML poisoned comparison")
	}
}
