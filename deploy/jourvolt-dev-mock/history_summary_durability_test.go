package main

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"reflect"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// This helper runs in a separate OS process. Without the private test phase it
// returns normally, so the ordinary suite never performs an accidental ingest.
func TestHistorySummaryProcessHelper(t *testing.T) {
	phase := os.Getenv("JOURVOLT_HISTORY_PROCESS_PHASE")
	if phase == "" {
		return
	}
	ctx := context.Background()
	pool, err := pgxpool.New(ctx, os.Getenv("JOURVOLT_TEST_DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	car, err := strconv.Atoi(os.Getenv("JOURVOLT_HISTORY_PROCESS_CAR"))
	if err != nil {
		t.Fatal(err)
	}
	user := os.Getenv("JOURVOLT_HISTORY_PROCESS_USER")
	vin := os.Getenv("JOURVOLT_HISTORY_PROCESS_VIN")
	s := &telemetryService{store: &store{pool: pool}, config: &telemetryConfig{StopDebounce: defaultDriveStopDebounce}}
	start := time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC)
	event := func(field string, value any, second int, id string) telemetryRecord {
		return telemetryRecord{VINHash: vin, FieldName: field, Value: value, ObservedAt: start.Add(time.Duration(second) * time.Second), EventID: id}
	}
	initial := []telemetryRecord{event("DetailedChargeState", "Charging", 0, "start"), event("ACChargingEnergyIn", float64(10), 1, "energy-baseline"), event("ACChargingEnergyIn", float64(15), 2, "energy-observed")}
	if phase == "start" {
		for _, record := range initial {
			if n, err := s.ingest(ctx, record); err != nil || n != 1 {
				t.Fatalf("initial accepted=%d err=%v", n, err)
			}
		}
		fmt.Println("HISTORY_PROCESS_DURABLE")
		for {
			time.Sleep(time.Hour)
		} // Parent intentionally kills without cleanup.
	}
	if phase != "resume" {
		t.Fatal("unknown helper phase")
	}
	before := readPersistedHistorySummary(t, s, user, car, "charge")
	for _, record := range initial {
		if n, err := s.ingest(ctx, record); err != nil || n != 0 {
			t.Fatalf("post-restart replay accepted=%d err=%v", n, err)
		}
	}
	if after := readPersistedHistorySummary(t, s, user, car, "charge"); !reflect.DeepEqual(before, after) {
		t.Fatal("replay after process death rewrote payload/summary")
	}
	fresh := event("ACChargingEnergyIn", float64(16), 3, "energy-after-restart")
	if n, err := s.ingest(ctx, fresh); err != nil || n != 1 {
		t.Fatalf("resume accepted=%d err=%v", n, err)
	}
	complete := event("DetailedChargeState", "Complete", 4, "complete")
	if n, err := s.ingest(ctx, complete); err != nil || n != 1 {
		t.Fatalf("complete accepted=%d err=%v", n, err)
	}
	closed := readPersistedHistorySummary(t, s, user, car, "charge")
	if n, err := s.ingest(ctx, complete); err != nil || n != 0 {
		t.Fatalf("completion replay accepted=%d err=%v", n, err)
	}
	if after := readPersistedHistorySummary(t, s, user, car, "charge"); !reflect.DeepEqual(closed, after) {
		t.Fatal("completion replay mutated evidence")
	}
}

func TestHistorySummaryPostgresSurvivesKilledProcess(t *testing.T) {
	s, user, car := openHistoryQueryTestDB(t)
	ctx := context.Background()
	vin := "process-" + mustRandomToken(t)
	if err := s.registerVehicle(ctx, telemetryVehicleRef{UserID: user, VehicleID: car, VINHash: vin}); err != nil {
		t.Fatal(err)
	}
	makeCommand := func(phase string) *exec.Cmd {
		cmd := exec.Command(os.Args[0], "-test.run=^TestHistorySummaryProcessHelper$", "-test.count=1")
		cmd.Env = append(os.Environ(), "JOURVOLT_HISTORY_PROCESS_PHASE="+phase, "JOURVOLT_HISTORY_PROCESS_USER="+user, "JOURVOLT_HISTORY_PROCESS_CAR="+strconv.Itoa(car), "JOURVOLT_HISTORY_PROCESS_VIN="+vin)
		return cmd
	}
	child := makeCommand("start")
	var stderr bytes.Buffer
	child.Stderr = &stderr
	stdout, err := child.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err = child.Start(); err != nil {
		t.Fatal(err)
	}
	waited := false
	defer func() {
		if !waited {
			_ = child.Process.Kill()
			_ = child.Wait()
		}
	}()
	ready := make(chan bool, 1)
	go func() {
		scanner := bufio.NewScanner(stdout)
		for scanner.Scan() {
			if scanner.Text() == "HISTORY_PROCESS_DURABLE" {
				ready <- true
				return
			}
		}
		ready <- false
	}()
	select {
	case ok := <-ready:
		if !ok {
			_ = child.Process.Kill()
			_ = child.Wait()
			waited = true
			t.Fatalf("child failed before durable marker: %s", stderr.String())
		}
	case <-time.After(15 * time.Second):
		t.Fatal("child did not persist within deadline")
	}
	open := readPersistedHistorySummary(t, s, user, car, "charge")
	if open.Version != 1 || open.ChargeCount != 1 {
		t.Fatalf("pre-kill summary=%+v", open)
	}
	if err = child.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	err = child.Wait()
	waited = true
	if err == nil {
		t.Fatal("test did not terminate child process")
	}
	if output, err := makeCommand("resume").CombinedOutput(); err != nil {
		t.Fatalf("fresh process: %v\n%s", err, output)
	}
	closed := readPersistedHistorySummary(t, s, user, car, "charge")
	var sessions, completed int
	var energy float64
	if err = s.store.pool.QueryRow(ctx, `SELECT count(*),count(*) FILTER(WHERE ended_at IS NOT NULL),max(energy_added) FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2`, user, car).Scan(&sessions, &completed, &energy); err != nil {
		t.Fatal(err)
	}
	if sessions != 1 || completed != 1 || energy != 6 || closed.Version != 1 || closed.ChargeCount != 2 || closed.Revision <= open.Revision {
		t.Fatalf("process recovery: sessions=%d completed=%d energy=%v summary=%+v", sessions, completed, energy, closed)
	}
	t.Log("PROCESS_RESTART_EVIDENCE killed_after_commit=true fresh_os_process=true qos1_replay_no_rewrite=true completed_sessions=1 energy_baseline_preserved=true broker_restart=false")
}

func TestHistorySummaryPostgresInFlightBackfillCancellationRollsBack(t *testing.T) {
	s, user, car := openHistoryQueryTestDB(t)
	ctx := context.Background()
	seedHistoryQueryRows(t, s, user, car, 1, 3, 4, "drive")
	withHistorySummaryTriggerDisabled(t, s, func(tx pgx.Tx) {
		_, err := tx.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET history_summary_version=0,history_summary_revision=0,route_point_count=NULL,charge_point_count=NULL,route_start_latitude=NULL,route_start_longitude=NULL,route_end_latitude=NULL,route_end_longitude=NULL WHERE user_id=$1`, user)
		if err != nil {
			t.Fatal(err)
		}
	})
	fingerprint := func() string {
		var hash string
		if err := s.store.pool.QueryRow(ctx, `SELECT md5(string_agg(row_to_json(s)::text,'|' ORDER BY id)) FROM jourvolt_telemetry_sessions s WHERE user_id=$1`, user).Scan(&hash); err != nil {
			t.Fatal(err)
		}
		return hash
	}
	before := fingerprint()
	name := "history_cancel_" + strings.ReplaceAll(mustRandomToken(t), "-", "_")
	function := pgx.Identifier{name}.Sanitize()
	trigger := pgx.Identifier{name + "_trigger"}.Sanitize()
	lockKey := time.Now().UnixNano() % 1_000_000_000
	// This synthetic-row-only trigger makes cancellation deterministic: the
	// advisory lock is visible only after the backfill has entered UPDATE.
	definition := fmt.Sprintf(`CREATE FUNCTION %s() RETURNS trigger AS $$ BEGIN IF NEW.user_id=%s THEN PERFORM pg_advisory_xact_lock(%d); PERFORM pg_sleep(30); END IF; RETURN NEW; END $$ LANGUAGE plpgsql`, function, "'"+strings.ReplaceAll(user, "'", "''")+"'", lockKey)
	if _, err := s.store.pool.Exec(ctx, definition); err != nil {
		t.Fatal(err)
	}
	defer func() { _, _ = s.store.pool.Exec(ctx, "DROP FUNCTION "+function+"() CASCADE") }()
	if _, err := s.store.pool.Exec(ctx, "CREATE TRIGGER "+trigger+" AFTER UPDATE OF history_summary_version ON jourvolt_telemetry_sessions FOR EACH ROW EXECUTE FUNCTION "+function+"()"); err != nil {
		t.Fatal(err)
	}
	active, cancel := context.WithCancel(ctx)
	defer cancel()
	result := make(chan error, 1)
	go func() { _, err := backfillHistorySummaries(active, s.store.pool, 3); result <- err }()
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
		t.Fatal("backfill never entered test-only blocking UPDATE trigger")
	}
	cancel()
	select {
	case err := <-result:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("in-flight error=%v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("in-flight cancellation did not return")
	}
	// A pool connection can report cancellation before the backend releases its
	// transaction. Wait for that exact advisory lock to disappear before checking.
	deadline = time.Now().Add(3 * time.Second)
	for entered && time.Now().Before(deadline) {
		if err := s.store.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND classid=0 AND objid=$1::oid AND granted)`, lockKey).Scan(&entered); err != nil {
			t.Fatal(err)
		}
		if entered {
			time.Sleep(5 * time.Millisecond)
		}
	}
	if entered {
		t.Fatal("canceled backend retained transaction lock")
	}
	if after := fingerprint(); after != before {
		t.Fatal("in-flight cancellation changed raw payload or summary columns")
	}
	if _, err := s.store.pool.Exec(ctx, "DROP TRIGGER "+trigger+" ON jourvolt_telemetry_sessions"); err != nil {
		t.Fatal(err)
	}
	if count, err := backfillHistorySummaries(ctx, s.store.pool, 3); err != nil || count != 3 {
		t.Fatalf("retry after canceled transaction count=%d err=%v", count, err)
	}
	t.Log("INFLIGHT_CANCEL_EVIDENCE postgres_update_entered=true transaction_lock_released=true full_rows_unchanged=true retry_materialized=3")
}
