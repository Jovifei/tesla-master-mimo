package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"
)

// Explicit, synthetic-only qualification. This benchmark is not part of ordinary
// tests and must run with -benchtime=1x. The process includes the handler driver,
// serialization, Go runtime and sampler; it is not a production-only API RSS.
func BenchmarkHistoryResourceConcurrent(b *testing.B) {
	if os.Getenv("JOURVOLT_RUN_RESOURCE_MATRIX") != "1" {
		b.Fatal("set JOURVOLT_RUN_RESOURCE_MATRIX=1 and an isolated JOURVOLT_TEST_DATABASE_URL")
	}
	dsn := os.Getenv("JOURVOLT_TEST_DATABASE_URL")
	if dsn == "" {
		b.Fatal("isolated PostgreSQL DSN required")
	}
	for _, concurrency := range []int{2, 4, 8, 16} {
		b.Run(fmt.Sprintf("c%d", concurrency), func(b *testing.B) {
			if b.N != 1 {
				b.Fatal("resource qualification requires -benchtime=1x")
			}
			qualifyHistoryResourceConcurrency(b, dsn, concurrency)
		})
	}
}

type matrixFixture struct {
	user          string
	car, publicID int
}
type matrixSample struct {
	kind     string
	code     int
	duration time.Duration
	bytes    int
}
type matrixDiscardWriter struct {
	headers     http.Header
	code, bytes int
}

func (w *matrixDiscardWriter) Header() http.Header  { return w.headers }
func (w *matrixDiscardWriter) WriteHeader(code int) { w.code = code }
func (w *matrixDiscardWriter) Write(value []byte) (int, error) {
	if w.code == 0 {
		w.code = http.StatusOK
	}
	w.bytes += len(value)
	return len(value), nil
}

type matrixDBStats struct{ Reads, Hits, TempBytes int64 }

func flushMatrixDBStats(b *testing.B, db *store) {
	b.Helper()
	// All workers have joined. Ask every idle pooled backend to publish its
	// own pending counters at command end before taking a database snapshot.
	connections := db.pool.AcquireAllIdle(context.Background())
	defer func() {
		for _, connection := range connections {
			connection.Release()
		}
	}()
	for _, connection := range connections {
		if _, err := connection.Exec(context.Background(), `SELECT pg_stat_force_next_flush()`); err != nil {
			b.Fatal(err)
		}
	}
}

func readMatrixDBStats(b *testing.B, db *store) matrixDBStats {
	b.Helper()
	ctx := context.Background()
	if _, err := db.pool.Exec(ctx, `SELECT pg_stat_clear_snapshot()`); err != nil {
		b.Fatal(err)
	}
	var result matrixDBStats
	if err := db.pool.QueryRow(ctx, `SELECT blks_read,blks_hit,temp_bytes FROM pg_stat_database WHERE datname=current_database()`).Scan(&result.Reads, &result.Hits, &result.TempBytes); err != nil {
		b.Fatal(err)
	}
	return result
}

func matrixRSS() uint64 {
	value, err := os.ReadFile("/proc/self/statm")
	if err != nil {
		return 0
	}
	parts := strings.Fields(string(value))
	if len(parts) < 2 {
		return 0
	}
	pages, _ := strconv.ParseUint(parts[1], 10, 64)
	return pages * uint64(os.Getpagesize())
}
func matrixCPUSeconds() float64 {
	var usage syscall.Rusage
	if syscall.Getrusage(syscall.RUSAGE_SELF, &usage) != nil {
		return -1
	}
	return float64(usage.Utime.Sec+usage.Stime.Sec) + float64(usage.Utime.Usec+usage.Stime.Usec)/1e6
}
func matrixPercentile(values []time.Duration, p float64) float64 {
	if len(values) == 0 {
		return 0
	}
	sort.Slice(values, func(i, j int) bool { return values[i] < values[j] })
	index := int(float64(len(values)-1)*p + 0.5)
	return float64(values[index]) / float64(time.Millisecond)
}

// When PostgreSQL runs on this same isolated host, read CPU ticks from the
// explicitly supplied postmaster and its direct child processes. In Docker or
// remote-DB CI this is deliberately unavailable, never fabricated as zero.
func matrixPostgresCPU() (int64, bool) {
	pid, err := strconv.Atoi(os.Getenv("JOURVOLT_TEST_POSTGRES_PID"))
	if err != nil || pid <= 0 {
		return 0, false
	}
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return 0, false
	}
	var total int64
	foundPostmaster := false
	for _, entry := range entries {
		current, err := strconv.Atoi(entry.Name())
		if err != nil {
			continue
		}
		value, err := os.ReadFile("/proc/" + entry.Name() + "/stat")
		if err != nil {
			continue
		}
		end := strings.LastIndexByte(string(value), ')')
		if end < 0 {
			continue
		}
		fields := strings.Fields(string(value[end+1:]))
		if len(fields) < 13 {
			continue
		}
		parent, err := strconv.Atoi(fields[1])
		if err != nil || (current != pid && parent != pid) {
			continue
		}
		user, userErr := strconv.ParseInt(fields[11], 10, 64)
		system, systemErr := strconv.ParseInt(fields[12], 10, 64)
		if userErr != nil || systemErr != nil {
			return 0, false
		}
		total += user + system
		if current == pid {
			foundPostmaster = true
		}
	}
	return total, foundPostmaster
}

func qualifyHistoryResourceConcurrency(b *testing.B, dsn string, concurrency int) {
	b.Helper()
	b.StopTimer()
	ctx := context.Background()
	db, err := openStore(ctx, dsn)
	if err != nil {
		b.Fatal(err)
	}
	defer db.close()
	points := 5000
	if value := os.Getenv("JOURVOLT_RESOURCE_POINTS"); value != "" {
		points, err = strconv.Atoi(value)
		if err != nil || points < 1 || points > maxImportRoutePointsPerItem {
			b.Fatalf("JOURVOLT_RESOURCE_POINTS must be 1..%d", maxImportRoutePointsPerItem)
		}
	}
	const rounds = 8
	fixtures := make([]matrixFixture, concurrency)
	for index := range fixtures {
		seed, err := randomToken()
		if err != nil {
			b.Fatal(err)
		}
		fixture := matrixFixture{user: "resource_matrix_" + seed}
		if err = db.ensureUser(ctx, fixture.user); err != nil {
			b.Fatal(err)
		}
		defer db.deleteUser(context.Background(), fixture.user)
		err = db.pool.QueryRow(ctx, `INSERT INTO jourvolt_vehicles(user_id,provider_vehicle_id,vin_ciphertext,display_name,state,updated_at) VALUES($1,$2,'synthetic','Synthetic matrix','online',now()) RETURNING id`, fixture.user, "matrix-"+seed).Scan(&fixture.car)
		if err != nil {
			b.Fatal(err)
		}
		err = db.pool.QueryRow(ctx, `WITH payload AS (SELECT jsonb_agg(jsonb_build_object('date','2026-09-01T00:00:00Z','latitude',1.0,'longitude',2.0,'speed',0.0,'power',NULL)) AS route FROM generate_series(1,$4::integer)) INSERT INTO jourvolt_telemetry_sessions(id,user_id,vehicle_id,kind,started_at,ended_at,source,quality_state,quality_reason,route_json) SELECT $1,$2,$3,'drive','2026-09-01T00:00:00Z','2026-09-01T01:00:00Z','teslamate_archive','observed','synthetic_matrix',route FROM payload RETURNING public_id`, "matrix-"+seed, fixture.user, fixture.car, points).Scan(&fixture.publicID)
		if err != nil {
			b.Fatal(err)
		}
		fixtures[index] = fixture
	}
	service := &telemetryService{store: db}
	a := &app{telemetry: service}
	// Warm metadata only and establish pool connections. Detail/page plans and
	// serialization are included in the measured workload.
	var warm sync.WaitGroup
	for _, fixture := range fixtures {
		warm.Add(1)
		go func(f matrixFixture) { defer warm.Done(); _, _ = service.historyMetadata(ctx, f.user, f.car, "drive") }(fixture)
	}
	warm.Wait()
	flushMatrixDBStats(b, db)
	dbBefore := readMatrixDBStats(b, db)
	runtime.GC()
	var before runtime.MemStats
	runtime.ReadMemStats(&before)
	baselineRSS := matrixRSS()
	peakRSS, peakHeap := baselineRSS, before.HeapAlloc
	stopSample := make(chan struct{})
	samplerDone := make(chan struct{})
	go func() {
		defer close(samplerDone)
		ticker := time.NewTicker(2 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-stopSample:
				return
			case <-ticker.C:
				var m runtime.MemStats
				runtime.ReadMemStats(&m)
				if m.HeapAlloc > peakHeap {
					peakHeap = m.HeapAlloc
				}
				if rss := matrixRSS(); rss > peakRSS {
					peakRSS = rss
				}
			}
		}
	}()
	results := make(chan matrixSample, concurrency*rounds*3)
	startGate := make(chan struct{})
	var workers sync.WaitGroup
	for _, fixture := range fixtures {
		workers.Add(1)
		go func(f matrixFixture) {
			defer workers.Done()
			<-startGate
			for round := 0; round < rounds; round++ {
				for _, kind := range []string{"detail", "page", "metadata"} {
					start := time.Now()
					sample := matrixSample{kind: kind}
					if kind == "metadata" {
						_, err := service.historyMetadata(ctx, f.user, f.car, "drive")
						sample.code = http.StatusOK
						if err != nil {
							sample.code = http.StatusServiceUnavailable
						}
					} else {
						writer := &matrixDiscardWriter{headers: make(http.Header)}
						request := httptest.NewRequest(http.MethodGet, "/synthetic/history?show=20", nil)
						var parts []string
						if kind == "detail" {
							parts = []string{strconv.Itoa(f.publicID)}
						}
						a.telemetryHistory(writer, request, f.user, f.car, "drive", parts)
						sample.code = writer.code
						sample.bytes = writer.bytes
					}
					sample.duration = time.Since(start)
					results <- sample
				}
			}
		}(fixture)
	}
	pgBefore, pgAvailable := matrixPostgresCPU()
	cpuBefore := matrixCPUSeconds()
	b.StartTimer()
	start := time.Now()
	close(startGate)
	workers.Wait()
	elapsed := time.Since(start)
	cpuAfter := matrixCPUSeconds()
	pgAfter, pgAfterAvailable := matrixPostgresCPU()
	b.StopTimer()
	close(stopSample)
	<-samplerDone
	close(results)
	var after runtime.MemStats
	runtime.ReadMemStats(&after)
	if after.HeapAlloc > peakHeap {
		peakHeap = after.HeapAlloc
	}
	if rss := matrixRSS(); rss > peakRSS {
		peakRSS = rss
	}
	flushMatrixDBStats(b, db)
	dbAfter := readMatrixDBStats(b, db)
	counts := map[string]int{}
	durations := map[string][]time.Duration{}
	bytesWritten := 0
	for sample := range results {
		key := fmt.Sprintf("%s_%d", sample.kind, sample.code)
		counts[key]++
		durations[sample.kind] = append(durations[sample.kind], sample.duration)
		durations[key] = append(durations[key], sample.duration)
		bytesWritten += sample.bytes
		if sample.code != http.StatusOK && !(sample.kind == "detail" && sample.code == http.StatusTooManyRequests) {
			b.Fatalf("unexpected handler status: %+v", sample)
		}
	}
	if counts["page_200"] != concurrency*rounds || counts["metadata_200"] != concurrency*rounds || counts["detail_200"] == 0 {
		b.Fatalf("lightweight requests failed or no details admitted: %v", counts)
	}
	globalHistoryResourceBudget.mu.Lock()
	active := globalHistoryResourceBudget.active
	users := len(globalHistoryResourceBudget.users)
	vehicles := len(globalHistoryResourceBudget.vehicles)
	globalHistoryResourceBudget.mu.Unlock()
	if active != 0 || users != 0 || vehicles != 0 {
		b.Fatal("admission permit leaked")
	}
	var remainingPoints int
	for _, f := range fixtures {
		var count int
		if err = db.pool.QueryRow(ctx, `SELECT jsonb_array_length(route_json) FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2`, f.user, f.car).Scan(&count); err != nil || count != points {
			b.Fatalf("raw archive changed count=%d err=%v", count, err)
		}
		remainingPoints += count
	}
	var pgTicks any
	if pgAvailable && pgAfterAvailable && pgAfter >= pgBefore {
		pgTicks = pgAfter - pgBefore
	}
	evidence := map[string]any{
		"scope": "isolated_synthetic_http_handlers_including_driver_and_sampler", "concurrency": concurrency, "rounds_per_worker": rounds, "points_per_session": points, "original_points_after": remainingPoints,
		"statuses": counts, "elapsed_ms": float64(elapsed) / float64(time.Millisecond), "go_total_alloc_delta_bytes": after.TotalAlloc - before.TotalAlloc, "go_baseline_heap_bytes": before.HeapAlloc, "go_peak_sampled_heap_bytes": peakHeap, "process_baseline_rss_bytes": baselineRSS, "process_peak_sampled_rss_bytes": peakRSS, "sample_interval_ms": 2, "percentile_method": "nearest_index_round_p_times_n_minus_1",
		"process_cpu_seconds": cpuAfter - cpuBefore, "postgres_process_tree_cpu_ticks": pgTicks, "postgres_clock_ticks_per_second": os.Getenv("JOURVOLT_TEST_CLK_TCK"),
		"postgres_database_blocks_read_delta": dbAfter.Reads - dbBefore.Reads, "postgres_database_blocks_hit_delta": dbAfter.Hits - dbBefore.Hits, "postgres_database_temp_bytes_delta": dbAfter.TempBytes - dbBefore.TempBytes,
		"detail_all_p95_ms": matrixPercentile(durations["detail"], .95), "detail_success_p95_ms": matrixPercentile(durations["detail_200"], .95), "detail_success_p99_ms": matrixPercentile(durations["detail_200"], .99), "page_p95_ms": matrixPercentile(durations["page"], .95), "metadata_p95_ms": matrixPercentile(durations["metadata"], .95), "metadata_p99_ms": matrixPercentile(durations["metadata"], .99), "page_p99_ms": matrixPercentile(durations["page"], .99), "serialized_bytes": bytesWritten, "permits_after": active, "transport": "in_process_response_discard", "database_cache": "metadata_warmed_synthetic_compressible_cluster_cache_not_reset", "sessions_per_worker": 1, "requested_page_size": 20, "page_rows_per_response": 1, "metadata_transport": "direct_service_call", "go_max_procs": runtime.GOMAXPROCS(0), "postgres_pool_max_connections": db.pool.Config().MaxConns}
	encoded, err := json.Marshal(evidence)
	if err != nil {
		b.Fatal(err)
	}
	b.Logf("RESOURCE_MATRIX %s", encoded)
}
