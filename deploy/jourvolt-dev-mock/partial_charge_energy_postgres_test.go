package main

import (
	"bytes"
	"context"
	"encoding/json"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"
)

// This test only targets an explicitly designated, disposable local database.
func TestPartialChargeEnergyPostgresListDetailAndIsolation(t *testing.T) {
	if os.Getenv("MATELINK_TEST_ISOLATED_POSTGRES") != "1" {
		t.Skip("explicit isolated PostgreSQL opt-in not set")
	}
	dsn, err := url.Parse(os.Getenv("JOURVOLT_TEST_DATABASE_URL"))
	if err != nil || dsn == nil || (dsn.Hostname() != "127.0.0.1" && dsn.Hostname() != "localhost" && dsn.Hostname() != "::1") ||
		!strings.HasPrefix(strings.TrimPrefix(dsn.Path, "/"), "matelink_partial_test_") {
		t.Fatal("test URL must name an isolated local matelink_partial_test_* database")
	}
	db := boundedHistoryTestStore(t)
	userA, carA := boundedHistoryTestTenant(t, db, "partial-a")
	userB, carB := boundedHistoryTestTenant(t, db, "partial-b")
	ctx := context.Background()
	session := partialChargeFixture()
	stored := completedSessionEnergyContract(session)
	session.ChargePoints[len(session.ChargePoints)-1].EnergyContract = stored
	points, err := json.Marshal(session.ChargePoints)
	if err != nil {
		t.Fatal(err)
	}
	var chargeID int
	err = db.pool.QueryRow(ctx, `
INSERT INTO jourvolt_telemetry_sessions(
    id, user_id, vehicle_id, kind, started_at, ended_at, route_json, charge_points_json,
    source, quality_state, quality_reason
) VALUES ($1,$2,$3,'charge',$4,$5,'[]'::jsonb,$6::jsonb,'telemetry_mqtt','incomplete','partial_or_missing_charge_counter')
RETURNING public_id`, "partial-"+mustRandomToken(t), userA, carA,
		session.StartAt, session.EndAt, points).Scan(&chargeID)
	if err != nil {
		t.Fatal(err)
	}
	var before []byte
	if err := db.pool.QueryRow(ctx, `SELECT charge_points_json FROM jourvolt_telemetry_sessions WHERE public_id=$1`, chargeID).Scan(&before); err != nil {
		t.Fatal(err)
	}
	service := &telemetryService{store: db}
	rows, _, total, _, err := service.historyPageContext(ctx, userA, carA, "charge", time.Time{}, time.Time{}, 1, 20)
	if err != nil || total != 1 || len(rows) != 1 {
		t.Fatalf("list total=%d rows=%d err=%v", total, len(rows), err)
	}
	detail, found, err := service.historyDetailContext(ctx, userA, carA, "charge", chargeID)
	if err != nil || !found {
		t.Fatalf("detail found=%v err=%v", found, err)
	}
	assertPartialMetricForTest(t, rows[0])
	assertPartialMetricForTest(t, detail)
	for _, scope := range []struct {
		user string
		car  int
	}{{userB, carA}, {userA, carB}} {
		foreign, found, err := service.historyDetailContext(ctx, scope.user, scope.car, "charge", chargeID)
		if err != nil || found || foreign != nil {
			t.Fatalf("cross-account or cross-vehicle detail found=%v err=%v", found, err)
		}
		foreignRows, _, count, _, err := service.historyPageContext(ctx, scope.user, scope.car, "charge", time.Time{}, time.Time{}, 1, 20)
		if err != nil || count != 0 || len(foreignRows) != 0 {
			t.Fatalf("cross-account or cross-vehicle list count=%d err=%v", count, err)
		}
	}
	var after []byte
	if err := db.pool.QueryRow(ctx, `SELECT charge_points_json FROM jourvolt_telemetry_sessions WHERE public_id=$1`, chargeID).Scan(&after); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(before, after) {
		t.Fatal("read changed stored charge evidence")
	}
}
