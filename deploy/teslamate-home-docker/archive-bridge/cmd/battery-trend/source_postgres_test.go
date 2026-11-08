package main

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strconv"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Only an explicitly named loopback fixture is allowed; all tables are TEMP.
func syntheticPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	dsn := os.Getenv("MATELINK_BATTERY_TREND_TEST_DSN")
	if dsn == "" {
		t.Skip("isolated PostgreSQL fixture not configured")
	}
	u, err := url.Parse(dsn)
	if err != nil || u.Scheme != "postgres" || u.User == nil || u.User.Username() != "bt_test" ||
		u.Path != "/matelink_battery_trend_test" || (u.Hostname() != "127.0.0.1" && u.Hostname() != "::1") {
		t.Fatal("refusing non-synthetic database")
	}
	port, err := strconv.Atoi(u.Port())
	if err != nil || port < 1024 || port == 5432 || net.ParseIP(u.Hostname()) == nil {
		t.Fatal("refusing database port")
	}
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		t.Fatal(err)
	}
	cfg.MaxConns = 1
	pool, err := pgxpool.NewWithConfig(context.Background(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	return pool
}

func TestPostgresReadsScopedBoundaryEvidenceAndBlocksOpenLowerID(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	pool := syntheticPool(t)
	_, err := pool.Exec(ctx, `CREATE TEMP TABLE drives (
 id bigint PRIMARY KEY, car_id bigint NOT NULL, start_date timestamptz, end_date timestamptz,
 start_rated_range_km numeric, end_rated_range_km numeric, outside_temp_avg numeric,
 start_position_id bigint, end_position_id bigint);
CREATE TEMP TABLE positions (
 id bigint PRIMARY KEY, car_id bigint, drive_id bigint, date timestamptz, battery_level integer);
INSERT INTO drives VALUES
 (1,8,'2026-10-30T00:00:00Z','2026-10-30T00:01:00Z',0,320.5,-4,101,102),
 (2,8,'2026-10-30T00:02:00Z','2026-10-30T00:03:00Z',NULL,NULL,NULL,201,NULL),
 (3,9,'2026-10-30T00:04:00Z','2026-10-30T00:05:00Z',999,999,99,NULL,NULL),
 (4,8,'2026-10-30T00:06:00Z',NULL,NULL,NULL,NULL,NULL,NULL),
 (5,8,'2026-10-30T00:08:00Z','2026-10-30T00:09:00Z',NULL,NULL,NULL,NULL,NULL);
INSERT INTO positions VALUES
 (101,8,1,'2026-10-30T00:00:00Z',0),
 (102,8,1,'2026-10-30T00:01:00Z',75),
 (201,9,2,'2026-10-30T00:02:00Z',50)`)
	if err != nil {
		t.Fatal(err)
	}
	source := postgresSource{pool: pool, carID: 8}
	high, err := source.HighWater(ctx, 0)
	if err != nil || high != 2 {
		t.Fatalf("wrong bounded high water: %d %v", high, err)
	}
	rows, err := source.Fetch(ctx, 0, 1, 50)
	if err != nil || len(rows) != 1 {
		t.Fatalf("source read failed: %v", err)
	}
	o, err := observationFromDrive(rows[0], 8)
	if err != nil || o.Start.SocPercent == nil || *o.Start.SocPercent != 0 ||
		o.End.RatedRangeKm == nil || *o.End.RatedRangeKm != 320.5 || o.End.RatedRangeObservedAt != nil ||
		o.OutsideTempAvgC == nil || *o.OutsideTempAvgC != -4 {
		t.Fatalf("lost source evidence: %v", err)
	}
	if _, err = source.Fetch(ctx, 1, 2, 50); err == nil {
		t.Fatal("foreign position was accepted")
	}
	if _, err = pool.Exec(ctx, `UPDATE drives SET end_date=NULL WHERE id=1`); err != nil {
		t.Fatal(err)
	}
	if rows, err = source.Fetch(ctx, 0, 2, 50); err != nil || len(rows) != 0 {
		t.Fatal("page crossed a drive reopened after the high-water snapshot")
	}
	if _, err = pool.Exec(ctx, `UPDATE drives SET end_date='2026-10-30T00:01:00Z' WHERE id=1`); err != nil {
		t.Fatal(err)
	}
	if _, err = pool.Exec(ctx, `UPDATE positions SET car_id=8, drive_id=99 WHERE id=201`); err != nil {
		t.Fatal(err)
	}
	if _, err = source.Fetch(ctx, 1, 2, 50); err == nil {
		t.Fatal("foreign drive position was accepted")
	}
	if _, err = pool.Exec(ctx, `UPDATE positions SET drive_id=2, date='2026-10-30T00:02:02Z' WHERE id=201`); err != nil {
		t.Fatal(err)
	}
	if _, err = source.Fetch(ctx, 1, 2, 50); err == nil {
		t.Fatal("out-of-window position was accepted")
	}
	if _, err = pool.Exec(ctx, `UPDATE positions SET date='2026-10-30T00:02:01Z', battery_level=NULL WHERE id=201`); err != nil {
		t.Fatal(err)
	}
	rows, err = source.Fetch(ctx, 1, 2, 50)
	if err != nil || len(rows) != 1 {
		t.Fatalf("valid null SOC rejected: %v", err)
	}
	o, err = observationFromDrive(rows[0], 8)
	if err != nil || o.Start.SocPercent != nil || o.Start.SocPositionID == nil || o.Start.SocPositionObservedAt == nil ||
		o.Start.RatedRangeKm != nil || o.Start.RatedRangeSource != nil {
		t.Fatal("null evidence was fabricated")
	}
	if _, err = source.HighWater(ctx, 2); err == nil {
		t.Fatal("open lower ID did not block later history")
	}
	if _, err = pool.Exec(ctx, `UPDATE drives SET end_date='2026-10-30T00:07:00Z' WHERE id=4`); err != nil {
		t.Fatal(err)
	}
	if high, err = source.HighWater(ctx, 2); err != nil || high != 5 {
		t.Fatal("completed history not reconsidered")
	}
	tx, err := pool.BeginTx(ctx, pgx.TxOptions{AccessMode: pgx.ReadOnly})
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(ctx)
	var readOnly string
	if err = tx.QueryRow(ctx, `SHOW transaction_read_only`).Scan(&readOnly); err != nil || readOnly != "on" {
		t.Fatal("source transaction is not read-only")
	}
	// PostgreSQL permits writes to TEMP tables even in read-only transactions.
	if _, err = tx.Exec(ctx, `CREATE TABLE battery_trend_readonly_guard(id bigint)`); err == nil {
		t.Fatal("read-only source transaction allowed permanent DDL")
	}
}

func TestPostgresNaiveTimestampIsReadAsUTC(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	pool := syntheticPool(t)
	_, err := pool.Exec(ctx, `CREATE TEMP TABLE drives (
 id bigint PRIMARY KEY, car_id bigint NOT NULL, start_date timestamp without time zone, end_date timestamp without time zone,
 start_rated_range_km numeric, end_rated_range_km numeric, outside_temp_avg numeric,
 start_position_id bigint, end_position_id bigint);
CREATE TEMP TABLE positions (
 id bigint PRIMARY KEY, car_id bigint, drive_id bigint, date timestamp without time zone, battery_level integer);
INSERT INTO drives VALUES (1,8,'2026-10-30 08:00:00.123456','2026-10-30 08:01:00.123456',10,9,NULL,101,102);
INSERT INTO positions VALUES (101,8,1,'2026-10-30 08:00:00.123456',0),(102,8,1,'2026-10-30 08:01:00.123456',1)`)
	if err != nil {
		t.Fatal(err)
	}
	rows, err := (postgresSource{pool: pool, carID: 8}).Fetch(ctx, 0, 1, 50)
	if err != nil || len(rows) != 1 {
		t.Fatalf("naive timestamp read failed: %v", err)
	}
	o, err := observationFromDrive(rows[0], 8)
	if err != nil || o.StartedAt != "2026-10-30T08:00:00.123456Z" || o.Start == nil ||
		o.Start.SocPositionObservedAt == nil || *o.Start.SocPositionObservedAt != o.StartedAt {
		t.Fatalf("naive UTC contract changed: %#v %v", o, err)
	}
}

func TestPostgresToHTTPKeepsOriginalBridgeStateAndIsIdempotent(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	pool := syntheticPool(t)
	_, err := pool.Exec(ctx, `CREATE TEMP TABLE drives (
 id bigint PRIMARY KEY, car_id bigint NOT NULL, start_date timestamp without time zone, end_date timestamp without time zone,
 start_rated_range_km numeric, end_rated_range_km numeric, outside_temp_avg numeric,
 start_position_id bigint, end_position_id bigint);
CREATE TEMP TABLE positions (
 id bigint PRIMARY KEY, car_id bigint, drive_id bigint, date timestamp without time zone, battery_level integer);
INSERT INTO drives VALUES
 (1,8,'2026-10-30 08:00:00','2026-10-30 08:01:00',320,315,19,101,102),
 (2,8,'2026-10-30 09:00:00','2026-10-30 09:01:00',NULL,NULL,NULL,NULL,NULL),
 (3,9,'2026-10-30 10:00:00','2026-10-30 10:01:00',999,999,99,NULL,NULL);
INSERT INTO positions VALUES (101,8,1,'2026-10-30 08:00:00',80),(102,8,1,'2026-10-30 08:01:00',75)`)
	if err != nil {
		t.Fatal(err)
	}
	var requests atomic.Int32
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var request trendRequest
		if json.NewDecoder(r.Body).Decode(&request) != nil {
			t.Error("invalid synthetic request")
			return
		}
		requests.Add(1)
		if len(request.Observations) != 2 || request.Observations[0].SourceRecordID != "teslamate:drive:1" ||
			request.Observations[1].SourceRecordID != "teslamate:drive:2" || request.Observations[0].Start == nil ||
			request.Observations[0].Start.SocPercent == nil || *request.Observations[0].Start.SocPercent != 80 ||
			request.Observations[1].Start == nil || request.Observations[1].Start.RatedRangeKm != nil {
			t.Error("source scope or nullable fields changed")
		}
		syntheticReceipt(w, request, 2, 0, 0)
	}))
	defer server.Close()
	c := syntheticConfig(t, server.URL)
	b := bridge{config: c, source: postgresSource{pool: pool, carID: 8}, client: server.Client()}
	if err = b.pollOnce(ctx); err != nil {
		t.Fatal(err)
	}
	if err = b.pollOnce(ctx); err != nil {
		t.Fatal(err)
	}
	state, err := loadState(c)
	if err != nil || state.LastDriveID != 2 || state.Pending != nil || requests.Load() != 1 {
		t.Fatal("synthetic replay did not remain idempotent")
	}
	original, _ := os.ReadFile(c.OriginalStateFile)
	if string(original) != "original sentinel" {
		t.Fatal("original bridge state changed")
	}
}
