package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"net/http/httptest"
	"os"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestHistoryMetadataMemoryIgnoresPayloadAndKeepsScope(t *testing.T) {
	s := newTelemetryServiceForTest("example.test")
	end := time.Date(2026, 1, 2, 0, 0, 0, 0, time.UTC)
	rows := []telemetrySession{
		{ID: "a", PublicID: 1, Kind: "drive", StartAt: end.Add(-3 * time.Hour), EndAt: &end, Source: "local_import", QualityState: "incomplete"},
		{ID: "b", PublicID: 2, Kind: "drive", StartAt: end.Add(-2 * time.Hour), EndAt: &end, Source: "teslamate_archive", QualityState: "observed", Route: make([]telemetryRoutePoint, 100000)},
		{ID: "q", PublicID: 3, Kind: "drive", StartAt: end.Add(-time.Hour), EndAt: &end, Source: "telemetry_mqtt", QualityState: "quarantined"},
		{ID: "open", PublicID: 4, Kind: "drive", StartAt: end, Source: "telemetry_mqtt"},
		{ID: "c", PublicID: 5, Kind: "charge", StartAt: end, EndAt: &end, Source: "fleet_api", QualityState: "derived"},
	}
	s.memory.completed[telemetryKey{UserID: "a", VehicleID: 1}] = rows
	meta, err := s.historyMetadata(context.Background(), "a", 1, "drive")
	if err != nil || meta.Total != 2 || meta.Qualified != 1 || meta.Quarantined != 1 || meta.ReadinessSource != "teslamate_archive" {
		t.Fatalf("metadata=%+v err=%v", meta, err)
	}
	for _, scope := range []struct {
		user string
		car  int
	}{{"b", 1}, {"a", 2}} {
		got, err := s.historyMetadata(context.Background(), scope.user, scope.car, "drive")
		if err != nil || got.Total != 0 || s.hasHistory(context.Background(), scope.user, scope.car, "drive") {
			t.Fatalf("scope leak: %+v", got)
		}
	}
	if !s.hasHistory(context.Background(), "a", 1, "drive") {
		t.Fatal("history missing")
	}
	a := &app{telemetry: s}
	items := a.vehicleItems(context.Background(), "a", []vehicle{{ID: 1}})
	if items[0]["teslamate_stats"].(map[string]any)["total_drives"] != 2 {
		t.Fatal("counter contract")
	}
	if len(rows[1].Route) != 100000 {
		t.Fatal("history changed")
	}
}

func TestHistoryReadCancellationAndUnavailableCounts(t *testing.T) {
	s := newTelemetryServiceForTest("example.test")
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := s.historyMetadata(ctx, "a", 1, "drive"); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	if _, err := s.historyExists(ctx, "a", 1, "drive"); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	if _, _, err := s.historyPage(ctx, "a", 1, "drive", historyPageOptions{}); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	if _, _, err := s.historyDetailContext(ctx, "a", 1, "drive", 1); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	a := &app{telemetry: s}
	counts := a.vehicleItems(ctx, "a", []vehicle{{ID: 1}})[0]["teslamate_stats"].(map[string]any)
	if counts["total_drives"] != nil || counts["total_charges"] != nil {
		t.Fatal("failed count presented as zero")
	}
	items := []dataReadinessItem{{Key: "drives", Status: "collecting"}}
	markHistoryReadinessError(items, "drives", context.Canceled)
	if items[0].Status != "telemetry_error" || items[0].MessageKey != "history_unavailable" {
		t.Fatal(items)
	}
}

func TestHistoryMemoryHTTPPagination66Rows(t *testing.T) {
	s := newTelemetryServiceForTest("example.test")
	start := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	for i := 0; i < 66; i++ {
		ended := start.Add(time.Duration(i+1) * time.Hour)
		for _, kind := range []string{"drive", "charge"} {
			s.memory.completed[telemetryKey{UserID: "a", VehicleID: 1}] = append(s.memory.completed[telemetryKey{UserID: "a", VehicleID: 1}], telemetrySession{
				ID: fmt.Sprintf("%s-%d", kind, i), PublicID: i + 1, Kind: kind, StartAt: ended.Add(-time.Minute), EndAt: &ended, Source: "telemetry_mqtt", QualityState: "observed",
			})
		}
	}
	a := &app{telemetry: s}
	for _, kind := range []string{"drive", "charge"} {
		seen := map[float64]bool{}
		for page, want := range []int{20, 20, 20, 6} {
			r := httptest.NewRequest(http.MethodGet, fmt.Sprintf("/history?page=%d&show=20", page+1), nil)
			w := httptest.NewRecorder()
			a.telemetryHistory(w, r, "a", 1, kind, nil)
			var body struct {
				Data map[string]json.RawMessage `json:"data"`
			}
			if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &body) != nil {
				t.Fatal(w.Code, w.Body.String())
			}
			var rows []map[string]any
			var meta map[string]any
			_ = json.Unmarshal(body.Data[kind+"s"], &rows)
			_ = json.Unmarshal(body.Data["meta"], &meta)
			if len(rows) != want || meta["total"] != float64(66) || meta["total_pages"] != float64(4) {
				t.Fatalf("page %d: %d %+v", page, len(rows), meta)
			}
			for _, row := range rows {
				id := row[kind+"_id"].(float64)
				if seen[id] {
					t.Fatal("duplicate")
				}
				seen[id] = true
			}
		}
		if len(seen) != 66 {
			t.Fatal("lost rows")
		}
	}
	huge := historyPageOptionsFromRequest(httptest.NewRequest(http.MethodGet, "/history?page=9223372036854775807&show=2147483647", nil))
	rows, meta, err := s.historyPage(context.Background(), "a", 1, "drive", huge)
	if err != nil || len(rows) != 0 || meta["show"] != maxHistoryPageSize {
		t.Fatalf("unbounded page: %+v %v", meta, err)
	}
	rows, meta, err = s.historyPage(context.Background(), "a", 1, "drive", historyPageOptions{})
	if err != nil || len(rows) != defaultHistoryPageSize || meta["total"] != 66 {
		t.Fatal("unbounded default")
	}
}

func openHistoryQueryTestDB(t *testing.T) (*telemetryService, string, int) {
	t.Helper()
	dsn := os.Getenv("JOURVOLT_TEST_DATABASE_URL")
	if dsn == "" {
		if os.Getenv("JOURVOLT_REQUIRE_HISTORY_PG") == "1" {
			t.Fatal("required isolated PostgreSQL DSN missing")
		}
		t.Skip("JOURVOLT_TEST_DATABASE_URL not set")
	}
	ctx := context.Background()
	db, err := openStore(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.close)
	user := "history_queries_" + mustRandomToken(t)
	if err := db.ensureUser(ctx, user); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.deleteUser(context.Background(), user) })
	var car int
	err = db.pool.QueryRow(ctx, `INSERT INTO jourvolt_vehicles(user_id,provider_vehicle_id,vin_ciphertext,display_name,state,updated_at) VALUES($1,$2,'test','Synthetic','online',now()) RETURNING id`, user, "history-"+mustRandomToken(t)).Scan(&car)
	if err != nil {
		t.Fatal(err)
	}
	return &telemetryService{store: db}, user, car
}

func seedHistoryQueryRows(t *testing.T, s *telemetryService, user string, car, first, last, points int, kind string) {
	t.Helper()
	_, err := s.store.pool.Exec(context.Background(), `WITH payload AS (
        SELECT COALESCE(jsonb_agg(jsonb_build_object('date','2026-01-01T00:00:00Z','latitude',1.0,'longitude',2.0,'speed',0.0,'power',NULL)), '[]'::jsonb) AS route
        FROM generate_series(1,$6::int)
    ) INSERT INTO jourvolt_telemetry_sessions(id,user_id,vehicle_id,kind,started_at,ended_at,source,quality_state,quality_reason,route_json)
    SELECT $1||'-'||$5||'-'||i,$1,$2,$5,'2026-01-01'::timestamptz+i*interval '1 hour','2026-01-01'::timestamptz+i*interval '1 hour'+interval '30 minutes',
           'teslamate_archive','observed','archive_original',payload.route
    FROM generate_series($3::int,$4::int) i CROSS JOIN payload`, user, car, first, last, kind, points)
	if err != nil {
		t.Fatal(err)
	}
}

func TestHistoryQueriesPostgresScopePagingAndDetail(t *testing.T) {
	s, user, car := openHistoryQueryTestDB(t)
	ctx := context.Background()
	seedHistoryQueryRows(t, s, user, car, 1, 66, 9, "drive")
	seedHistoryQueryRows(t, s, user, car, 1, 5, 0, "charge")
	// Explicit null/zero endpoint evidence remains unchanged in the original archive.
	_, err := s.store.pool.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET quality_state='quarantined' WHERE user_id=$1 AND kind='charge' AND id=$2`, user, user+"-charge-5")
	if err != nil {
		t.Fatal(err)
	}
	metadata, err := s.historyMetadata(ctx, user, car, "charge")
	if err != nil || metadata.Total != 4 || metadata.Quarantined != 1 || metadata.ReadinessSource != "teslamate_archive" {
		t.Fatalf("%+v %v", metadata, err)
	}
	if exists, err := s.historyExists(ctx, "other-user", car, "drive"); err != nil || exists {
		t.Fatal("cross-account exists")
	}
	rows, meta, err := s.historyPage(ctx, user, car, "drive", historyPageOptions{Page: 4, Show: 20})
	if err != nil || len(rows) != 6 || meta["total"] != 66 {
		t.Fatalf("page rows=%d meta=%v err=%v", len(rows), meta, err)
	}
	if len(rows[0]["drive_details"].([]map[string]any)) != 0 {
		t.Fatal("list included route")
	}
	if rows[0]["source"] != "teslamate_archive" || rows[0]["start_latitude"] != float64(1) {
		t.Fatal("source/endpoint lost")
	}
	start := time.Date(2026, 1, 1, 2, 0, 0, 0, time.UTC)
	rows, meta, err = s.historyPage(ctx, user, car, "drive", historyPageOptions{Start: start, End: start.Add(time.Hour)})
	if err != nil || len(rows) != 1 || meta["total"] != 1 {
		t.Fatalf("date filter %v %v", meta, err)
	}
	id := rows[0]["drive_id"].(int)
	detail, ok, err := s.historyDetailContext(ctx, user, car, "drive", id)
	if err != nil || !ok || len(detail["drive_details"].([]map[string]any)) != 9 {
		t.Fatal("detail truncated", err)
	}
	first := detail["drive_details"].([]map[string]any)[0]
	if first["speed"] != float64(0) || first["power"] != nil {
		t.Fatal("zero/null lost")
	}
	if _, ok, err := s.historyDetailContext(ctx, "other-user", car, "drive", id); err != nil || ok {
		t.Fatal("detail leaked")
	}
	// Corrupt payloads outside the page must not be decoded by list or metadata.
	_, err = s.store.pool.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET route_json='[{"latitude":"invalid","longitude":2}]'::jsonb WHERE id=$1`, user+"-drive-1")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.historyMetadata(ctx, user, car, "drive"); err != nil {
		t.Fatal("metadata read JSON", err)
	}
	if _, _, err = s.historyPage(ctx, user, car, "drive", historyPageOptions{Page: 1, Show: 20}); err != nil {
		t.Fatal("list decoded an off-page route", err)
	}
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			m, e := s.historyMetadata(ctx, user, car, "drive")
			if e != nil || m.Total != 66 {
				t.Errorf("parallel metadata: %+v %v", m, e)
			}
		}()
	}
	wg.Wait()
}

func allocatedBytes(t *testing.T, fn func()) uint64 {
	t.Helper()
	runtime.GC()
	var before, after runtime.MemStats
	runtime.ReadMemStats(&before)
	fn()
	runtime.ReadMemStats(&after)
	return after.TotalAlloc - before.TotalAlloc
}

func TestHistoryQueriesPostgresLargeArchiveMemory(t *testing.T) {
	s, user, car := openHistoryQueryTestDB(t)
	ctx := context.Background()
	// Exact reported SIZE, synthetic data only: 413 trips, 632055 points;
	// largest trip=29583. Not the user's actual locations or sampling frequency.
	seedHistoryQueryRows(t, s, user, car, 1, 1, 29583, "drive")
	seedHistoryQueryRows(t, s, user, car, 2, 129, 1463, "drive")
	seedHistoryQueryRows(t, s, user, car, 130, 413, 1462, "drive")
	seedHistoryQueryRows(t, s, user, car, 1, 53, 0, "charge")
	var originalHash string
	err := s.store.pool.QueryRow(ctx, `SELECT md5(route_json::text) FROM jourvolt_telemetry_sessions WHERE id=$1`, user+"-drive-1").Scan(&originalHash)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		if _, err := s.historyMetadata(ctx, user, car, "drive"); err != nil {
			t.Fatal(err)
		}
	}
	metaBytes := allocatedBytes(t, func() {
		for i := 0; i < 5; i++ {
			m, err := s.historyMetadata(ctx, user, car, "drive")
			if err != nil || m.Total != 413 {
				t.Fatalf("metadata %+v %v", m, err)
			}
			if exists, err := s.historyExists(ctx, user, car, "drive"); err != nil || !exists {
				t.Fatal(err)
			}
		}
	}) / 5
	if metaBytes > 2<<20 {
		t.Fatalf("metadata allocated %d bytes per call pair", metaBytes)
	}
	pageBytes := allocatedBytes(t, func() {
		rows, meta, err := s.historyPage(ctx, user, car, "drive", historyPageOptions{Page: 1, Show: 20})
		if err != nil || len(rows) != 20 || meta["total"] != 413 {
			t.Fatalf("%v %v", meta, err)
		}
	})
	if pageBytes > 8<<20 {
		t.Fatalf("list allocated %d bytes", pageBytes)
	}
	var legacyBytes uint64
	if os.Getenv("JOURVOLT_RUN_LEGACY_MEMORY_BENCH") == "1" {
		legacyBytes = allocatedBytes(t, func() {
			rows, _, err := s.history(user, car, "drive")
			if err != nil || len(rows) != 413 {
				t.Fatal(err)
			}
			runtime.KeepAlive(rows)
		})
		if legacyBytes <= metaBytes*10 {
			t.Fatal("unexpected legacy benchmark; inspect fixture")
		}
	}
	var id int
	if err := s.store.pool.QueryRow(ctx, `SELECT public_id FROM jourvolt_telemetry_sessions WHERE id=$1`, user+"-drive-1").Scan(&id); err != nil {
		t.Fatal(err)
	}
	detail, ok, err := s.historyDetailContext(ctx, user, car, "drive", id)
	if err != nil || !ok || len(detail["drive_details"].([]map[string]any)) != 29583 {
		t.Fatal("full route not recoverable", err)
	}
	var currentHash string
	var total int
	if err := s.store.pool.QueryRow(ctx, `SELECT md5(route_json::text) FROM jourvolt_telemetry_sessions WHERE id=$1`, user+"-drive-1").Scan(&currentHash); err != nil {
		t.Fatal(err)
	}
	if err := s.store.pool.QueryRow(ctx, `SELECT SUM(jsonb_array_length(route_json)) FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND kind='drive'`, user).Scan(&total); err != nil {
		t.Fatal(err)
	}
	if currentHash != originalHash || total != 632055 {
		t.Fatal("archive was modified")
	}
	t.Logf("MEMORY_EVIDENCE synthetic_sessions=413 route_points=%d metadata_plus_exists_bytes_per_op=%d page20_bytes=%d legacy_full_history_bytes=%d", total, metaBytes, pageBytes, legacyBytes)
}

func TestHistoryMetadataSQLAndBounds(t *testing.T) {
	for _, query := range []string{historyMetadataSQL, historyExistsSQL, historyPageCountSQL} {
		if strings.Contains(query, "route_json") || strings.Contains(query, "charge_points_json") {
			t.Fatal("metadata SQL references payload")
		}
		if !strings.Contains(query, "user_id=$1") || !strings.Contains(query, "vehicle_id=$2") || !strings.Contains(query, "kind=$3") {
			t.Fatal("unscoped query")
		}
	}
	if !strings.Contains(historyPageSQL, "LIMIT $6 OFFSET $7") {
		t.Fatal("unbounded page query")
	}
	got := normalizeHistoryPageOptions(historyPageOptions{Page: math.MaxInt, Show: math.MaxInt})
	if got.Show != maxHistoryPageSize {
		t.Fatal(got)
	}
}
