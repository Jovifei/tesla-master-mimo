package main

import (
	"context"
	"errors"
	"reflect"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
)

type persistedHistorySummary struct {
	Version, RouteCount, ChargeCount   int
	Revision                           int64
	StartLat, StartLon, EndLat, EndLon *float64
	RouteHash, ChargeHash              string
}

func readPersistedHistorySummary(t *testing.T, s *telemetryService, user string, car int, kind string) persistedHistorySummary {
	t.Helper()
	var result persistedHistorySummary
	err := s.store.pool.QueryRow(context.Background(), `SELECT history_summary_version,history_summary_revision,
        route_point_count,charge_point_count,route_start_latitude,route_start_longitude,route_end_latitude,route_end_longitude,
        md5(route_json::text),md5(charge_points_json::text)
        FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2 AND kind=$3`, user, car, kind).Scan(
		&result.Version, &result.Revision, &result.RouteCount, &result.ChargeCount, &result.StartLat, &result.StartLon, &result.EndLat, &result.EndLon, &result.RouteHash, &result.ChargeHash)
	if err != nil {
		t.Fatal(err)
	}
	return result
}

func TestHistorySummaryPostgresImportReplayAndRollback(t *testing.T) {
	s, user, car := openHistoryQueryTestDB(t)
	ctx := context.Background()
	request := historyImportRequest{Drives: []historyImportSession{{SessionID: "persisted", StartedAt: rfc3339(1), EndedAt: rfc3339(1),
		Route: []historyImportRoutePoint{{Date: rfc3339(1), Latitude: floatPointer(0), Longitude: floatPointer(2)}}}}}
	for i := 0; i < 2; i++ {
		if _, err := s.importHistory(ctx, user, car, request); err != nil {
			t.Fatal(err)
		}
	}
	before := readPersistedHistorySummary(t, s, user, car, "drive")
	if before.Version != 1 || before.RouteCount != 1 || before.ChargeCount != 0 || before.StartLat == nil || *before.StartLat != 0 || before.EndLon == nil || *before.EndLon != 2 || before.Revision < 2 {
		t.Fatalf("summary=%+v", before)
	}
	var count int
	if err := s.store.pool.QueryRow(ctx, `SELECT count(*) FROM jourvolt_telemetry_sessions WHERE user_id=$1`, user).Scan(&count); err != nil || count != 1 {
		t.Fatalf("replay count=%d err=%v", count, err)
	}
	tx, err := s.store.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(ctx)
	if _, err = tx.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET route_json='[{"latitude":7,"longitude":8},{"latitude":9,"longitude":10}]'::jsonb WHERE user_id=$1`, user); err != nil {
		t.Fatal(err)
	}
	var inTxCount int
	if err = tx.QueryRow(ctx, `SELECT route_point_count FROM jourvolt_telemetry_sessions WHERE user_id=$1`, user).Scan(&inTxCount); err != nil || inTxCount != 2 {
		t.Fatalf("transaction count=%d err=%v", inTxCount, err)
	}
	if err = tx.Rollback(ctx); err != nil {
		t.Fatal(err)
	}
	if after := readPersistedHistorySummary(t, s, user, car, "drive"); !reflect.DeepEqual(before, after) {
		t.Fatalf("rollback changed raw/summary: before=%+v after=%+v", before, after)
	}
	if err = ensureTelemetrySchema(ctx, s.store.pool); err != nil {
		t.Fatal(err)
	}
	if after := readPersistedHistorySummary(t, s, user, car, "drive"); !reflect.DeepEqual(before, after) {
		t.Fatal("schema reapply changed materialized history")
	}
	if rows, _, err := s.historyPage(ctx, "other-user", car, "drive", historyPageOptions{}); err != nil || len(rows) != 0 {
		t.Fatalf("tenant leak/error: rows=%d err=%v", len(rows), err)
	}
}

func TestHistorySummaryPostgresArchiveNullAndChargeCounts(t *testing.T) {
	s, user, car := openHistoryQueryTestDB(t)
	ctx := context.Background()
	request := historyImportRequest{Source: "teslamate", SourceInstanceID: "synthetic-home", SourceVehicleID: "1", ChunkID: "synthetic-summary",
		Drives: []historyImportSession{{SessionID: "archive-drive", SourceRecordID: "drive:1", StartedAt: rfc3339(1), EndedAt: rfc3339(1), Route: []historyImportRoutePoint{
			{Date: rfc3339(1), Latitude: nil, Longitude: floatPointer(0)},
			{Date: rfc3339(1), Latitude: floatPointer(3), Longitude: floatPointer(4)},
			{Date: rfc3339(1), Latitude: floatPointer(0), Longitude: floatPointer(5)},
		}}},
		Charges: []historyImportSession{{SessionID: "archive-charge", SourceRecordID: "charge:1", StartedAt: rfc3339(2), EndedAt: rfc3339(2), ChargePoints: []historyImportChargePoint{
			{Date: rfc3339(2), EnergyAdded: floatPointer(0)},
		}}},
	}
	if _, err := s.importHistory(ctx, user, car, request); err != nil {
		t.Fatal(err)
	}
	drive := readPersistedHistorySummary(t, s, user, car, "drive")
	if drive.RouteCount != 3 || drive.StartLat != nil || drive.StartLon == nil || *drive.StartLon != 0 || drive.EndLat == nil || *drive.EndLat != 0 {
		t.Fatalf("archive null/zero/count lost: %+v", drive)
	}
	charge := readPersistedHistorySummary(t, s, user, car, "charge")
	if charge.Version != 1 || charge.RouteCount != 0 || charge.ChargeCount != 1 || charge.StartLat != nil {
		t.Fatalf("charge=%+v", charge)
	}
	rows, _, err := s.historyPage(ctx, user, car, "drive", historyPageOptions{})
	if err != nil || len(rows) != 1 {
		t.Fatalf("page=%v err=%v", rows, err)
	}
	detail, ok, err := s.historyDetailContext(ctx, user, car, "drive", rows[0]["drive_id"].(int))
	if err != nil || !ok || len(detail["drive_details"].([]map[string]any)) != 3 {
		t.Fatalf("detail truncated: %v", err)
	}
	if after := readPersistedHistorySummary(t, s, user, car, "drive"); after.RouteHash != drive.RouteHash {
		t.Fatal("detail/list changed raw archive")
	}
}

func TestHistorySummaryPostgresTelemetryRestartAndReplay(t *testing.T) {
	s, user, car := openHistoryQueryTestDB(t)
	ctx := context.Background()
	ref := telemetryVehicleRef{UserID: user, VehicleID: car, VINHash: "summary-" + mustRandomToken(t)}
	s.config = &telemetryConfig{StopDebounce: defaultDriveStopDebounce}
	if err := s.registerVehicle(ctx, ref); err != nil {
		t.Fatal(err)
	}
	start := time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC)
	first := telemetryRecord{VINHash: ref.VINHash, FieldName: "DetailedChargeState", Value: "Charging", ObservedAt: start, EventID: "start"}
	if accepted, err := s.ingest(ctx, first); err != nil || accepted != 1 {
		t.Fatalf("start %d %v", accepted, err)
	}
	open := readPersistedHistorySummary(t, s, user, car, "charge")
	if open.Version != 1 {
		t.Fatalf("open=%+v", open)
	}
	fresh := &telemetryService{store: s.store, config: s.config}
	if accepted, err := fresh.ingest(ctx, first); err != nil || accepted != 0 {
		t.Fatalf("replay %d %v", accepted, err)
	}
	if after := readPersistedHistorySummary(t, s, user, car, "charge"); !reflect.DeepEqual(open, after) {
		t.Fatal("QoS1 replay rewrote summary")
	}
	complete := telemetryRecord{VINHash: ref.VINHash, FieldName: "DetailedChargeState", Value: "Complete", ObservedAt: start.Add(time.Minute), EventID: "complete"}
	if accepted, err := fresh.ingest(ctx, complete); err != nil || accepted != 1 {
		t.Fatalf("completion %d %v", accepted, err)
	}
	closed := readPersistedHistorySummary(t, s, user, car, "charge")
	if closed.Version != 1 || closed.Revision <= open.Revision {
		t.Fatalf("completion summary=%+v open=%+v", closed, open)
	}
	if accepted, err := fresh.ingest(ctx, complete); err != nil || accepted != 0 {
		t.Fatalf("completion replay %d %v", accepted, err)
	}
	if after := readPersistedHistorySummary(t, s, user, car, "charge"); !reflect.DeepEqual(closed, after) {
		t.Fatal("completion replay rewrote summary")
	}
}

// Test-only corruption/migration fixtures are made atomically while holding the
// table DDL lock; rollback restores trigger state if fixture construction fails.
func withHistorySummaryTriggerDisabled(t *testing.T, s *telemetryService, fn func(pgx.Tx)) {
	t.Helper()
	ctx := context.Background()
	tx, err := s.store.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(ctx)
	if _, err = tx.Exec(ctx, `ALTER TABLE jourvolt_telemetry_sessions DISABLE TRIGGER jourvolt_history_summary_write`); err != nil {
		t.Fatal(err)
	}
	fn(tx)
	if _, err = tx.Exec(ctx, `ALTER TABLE jourvolt_telemetry_sessions ENABLE TRIGGER jourvolt_history_summary_write`); err != nil {
		t.Fatal(err)
	}
	if err = tx.Commit(ctx); err != nil {
		t.Fatal(err)
	}
}

func TestHistorySummaryPostgresMaterializedReadSkipsRawPayload(t *testing.T) {
	s, user, car := openHistoryQueryTestDB(t)
	ctx := context.Background()
	seedHistoryQueryRows(t, s, user, car, 1, 1, 3, "drive")
	withHistorySummaryTriggerDisabled(t, s, func(tx pgx.Tx) {
		_, err := tx.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET route_json='[{"latitude":"must-not-decode","longitude":2}]'::jsonb,route_start_latitude=NULL,route_start_longitude=NULL,route_end_latitude=NULL,route_end_longitude=NULL WHERE user_id=$1`, user)
		if err != nil {
			t.Fatal(err)
		}
	})
	if rows, _, err := s.historyPage(ctx, user, car, "drive", historyPageOptions{}); err != nil || len(rows) != 1 {
		t.Fatalf("materialized page touched raw JSON: rows=%d err=%v", len(rows), err)
	}
	if rows, _, err := s.historySummariesPostgres(ctx, user, car, "drive"); err != nil || len(rows) != 1 || len(rows[0].Route) != 0 {
		t.Fatalf("summary null triggered fallback: rows=%v err=%v", rows, err)
	}
}

func TestHistorySummaryPostgresBackfillBoundedCancelableAndNonDestructive(t *testing.T) {
	s, user, car := openHistoryQueryTestDB(t)
	ctx := context.Background()
	seedHistoryQueryRows(t, s, user, car, 1, 4, 3, "drive")
	withHistorySummaryTriggerDisabled(t, s, func(tx pgx.Tx) {
		_, err := tx.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET history_summary_version=0,history_summary_revision=0,route_point_count=NULL,charge_point_count=NULL,route_start_latitude=NULL,route_start_longitude=NULL,route_end_latitude=NULL,route_end_longitude=NULL WHERE user_id=$1`, user)
		if err != nil {
			t.Fatal(err)
		}
		_, err = tx.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET route_json='[{"latitude":"invalid","longitude":2}]'::jsonb WHERE id=$1`, user+"-drive-4")
		if err != nil {
			t.Fatal(err)
		}
	})
	// Unprocessed legacy rows remain readable before any backfill runs.
	if rows, _, err := s.historyPage(ctx, user, car, "drive", historyPageOptions{Page: 2, Show: 2}); err != nil || len(rows) != 2 || rows[0]["start_latitude"] != float64(1) {
		t.Fatalf("legacy fallback=%v err=%v", rows, err)
	}
	var before string
	if err := s.store.pool.QueryRow(ctx, `SELECT md5(string_agg(id||route_json::text||charge_points_json::text,'|' ORDER BY id)) FROM jourvolt_telemetry_sessions WHERE user_id=$1`, user).Scan(&before); err != nil {
		t.Fatal(err)
	}
	canceled, cancel := context.WithCancel(ctx)
	cancel()
	if count, err := backfillHistorySummaries(canceled, s.store.pool, 1); !errors.Is(err, context.Canceled) || count != 0 {
		t.Fatalf("cancel count=%d err=%v", count, err)
	}
	for _, limit := range []int{0, -1, maxHistorySummaryBackfillBatch + 1} {
		if count, err := backfillHistorySummaries(ctx, s.store.pool, limit); err == nil || count != 0 {
			t.Fatalf("accepted invalid batch %d", limit)
		}
	}
	locked, err := s.store.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer locked.Rollback(ctx)
	if _, err = locked.Exec(ctx, `SELECT id FROM jourvolt_telemetry_sessions WHERE id=$1 FOR UPDATE`, user+"-drive-1"); err != nil {
		t.Fatal(err)
	}
	if count, err := backfillHistorySummaries(ctx, s.store.pool, 1); err != nil || count != 1 {
		t.Fatalf("skip locked count=%d err=%v", count, err)
	}
	var firstVersion, secondVersion int
	if err = s.store.pool.QueryRow(ctx, `SELECT history_summary_version FROM jourvolt_telemetry_sessions WHERE id=$1`, user+"-drive-1").Scan(&firstVersion); err != nil {
		t.Fatal(err)
	}
	if err = s.store.pool.QueryRow(ctx, `SELECT history_summary_version FROM jourvolt_telemetry_sessions WHERE id=$1`, user+"-drive-2").Scan(&secondVersion); err != nil {
		t.Fatal(err)
	}
	if firstVersion != 0 || secondVersion != 1 {
		t.Fatalf("locked/batch boundary violated: %d %d", firstVersion, secondVersion)
	}
	if err = locked.Rollback(ctx); err != nil {
		t.Fatal(err)
	}
	if count, err := backfillHistorySummaries(ctx, s.store.pool, 2); err != nil || count != 2 {
		t.Fatalf("batch2 count=%d err=%v", count, err)
	}
	if count, err := backfillHistorySummaries(ctx, s.store.pool, 2); err != nil || count != 1 {
		t.Fatalf("invalid row batch count=%d err=%v", count, err)
	}
	if count, err := backfillHistorySummaries(ctx, s.store.pool, 2); err != nil || count != 0 {
		t.Fatalf("backfill failed to converge count=%d err=%v", count, err)
	}
	var valid, invalid, points int
	var after string
	if err = s.store.pool.QueryRow(ctx, `SELECT count(*) FILTER(WHERE history_summary_version=1),count(*) FILTER(WHERE history_summary_version=-1),sum(route_point_count),md5(string_agg(id||route_json::text||charge_points_json::text,'|' ORDER BY id)) FROM jourvolt_telemetry_sessions WHERE user_id=$1`, user).Scan(&valid, &invalid, &points, &after); err != nil {
		t.Fatal(err)
	}
	if valid != 3 || invalid != 1 || points != 9 || before != after {
		t.Fatalf("backfill corrupted evidence: valid=%d invalid=%d points=%d hashes_equal=%v", valid, invalid, points, before == after)
	}
	// An ordinary later payload correction re-materializes invalid rows.
	if _, err = s.store.pool.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET route_json='[]'::jsonb WHERE id=$1`, user+"-drive-4"); err != nil {
		t.Fatal(err)
	}
	if err = s.store.pool.QueryRow(ctx, `SELECT history_summary_version FROM jourvolt_telemetry_sessions WHERE id=$1`, user+"-drive-4").Scan(&firstVersion); err != nil || firstVersion != 1 {
		t.Fatalf("repair version=%d err=%v", firstVersion, err)
	}
}
