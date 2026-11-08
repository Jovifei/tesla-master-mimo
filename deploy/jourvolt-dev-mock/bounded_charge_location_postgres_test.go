package main

import (
	"context"
	"encoding/json"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"
)

func requireIsolatedChargeLocationDB(t *testing.T) {
	t.Helper()
	if os.Getenv("MATELINK_TEST_ISOLATED_POSTGRES") != "1" {
		t.Skip("explicit isolated PostgreSQL opt-in not set")
	}
	dsn, err := url.Parse(os.Getenv("JOURVOLT_TEST_DATABASE_URL"))
	if err != nil || dsn == nil ||
		(dsn.Hostname() != "127.0.0.1" && dsn.Hostname() != "localhost" && dsn.Hostname() != "::1") ||
		!strings.HasPrefix(strings.TrimPrefix(dsn.Path, "/"), "matelink_partial_test_") {
		t.Fatal("test URL must name an isolated local matelink_partial_test_* database")
	}
}

func insertChargeLocationsForTest(t *testing.T, db *store, user string, car int, start time.Time, source string, points []telemetryChargePoint) int {
	t.Helper()
	id := boundedHistoryInsertSession(t, db, user, car, "charge", start, source, "observed",
		[]telemetryRoutePoint{}, "synthetic-location-"+mustRandomToken(t))
	encoded, err := json.Marshal(points)
	if err != nil {
		t.Fatal(err)
	}
	result, err := db.pool.Exec(context.Background(), `UPDATE jourvolt_telemetry_sessions
SET charge_points_json=$1::jsonb WHERE user_id=$2 AND vehicle_id=$3 AND public_id=$4`,
		encoded, user, car, id)
	if err != nil || result.RowsAffected() != 1 {
		t.Fatalf("fixture update rows=%v err=%v", result.RowsAffected(), err)
	}
	return id
}

func chargeMapJSONForTest(t *testing.T, value map[string]any) (map[string]any, []byte) {
	t.Helper()
	encoded, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	var decoded map[string]any
	if err := json.Unmarshal(encoded, &decoded); err != nil {
		t.Fatal(err)
	}
	return decoded, encoded
}

func TestBoundedChargeLocationListDetailAndTenantIsolation(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	userA, carA := boundedHistoryTestTenant(t, db, "charge-location-a")
	userB, carB := boundedHistoryTestTenant(t, db, "charge-location-b")
	base := time.Date(2026, 1, 2, 3, 0, 0, 0, time.UTC)
	lat, lon := 10.5, 20.5
	otherLat, otherLon := 30.5, 40.5
	invalidLat, zero := 91.0, 0.0
	points := []telemetryChargePoint{
		{ObservedAt: base, Latitude: &lat},
		{ObservedAt: base.Add(time.Second), Longitude: &lon},
		{ObservedAt: base.Add(2 * time.Second), Latitude: &invalidLat, Longitude: &lon},
		{ObservedAt: base.Add(3 * time.Second), Latitude: &zero, Longitude: &zero},
		{ObservedAt: base.Add(4 * time.Second), Latitude: &lat, Longitude: &lon},
		{ObservedAt: base.Add(5 * time.Second), Latitude: &otherLat, Longitude: &otherLon},
	}
	validID := insertChargeLocationsForTest(t, db, userA, carA, base, "telemetry_mqtt", points)
	noPairID := insertChargeLocationsForTest(t, db, userA, carA, base.Add(time.Hour), "telemetry_mqtt", points[:4])
	archiveID := insertChargeLocationsForTest(t, db, userA, carA, base.Add(2*time.Hour), "teslamate_archive",
		[]telemetryChargePoint{{ObservedAt: base.Add(2 * time.Hour), Latitude: &otherLat, Longitude: &otherLon}})
	equatorID := insertChargeLocationsForTest(t, db, userA, carA, base.Add(90*time.Minute), "telemetry_mqtt",
		[]telemetryChargePoint{{ObservedAt: base.Add(90 * time.Minute), Latitude: &zero, Longitude: &lon}})
	_ = insertChargeLocationsForTest(t, db, userB, carB, base.Add(3*time.Hour), "telemetry_mqtt",
		[]telemetryChargePoint{{ObservedAt: base.Add(3 * time.Hour), Latitude: &otherLat, Longitude: &otherLon}})

	ctx := context.Background()
	service := &telemetryService{store: db}
	rows, _, total, _, err := service.historyPageContext(ctx, userA, carA, "charge", time.Time{}, time.Time{}, 1, 10)
	if err != nil || total != 4 || len(rows) != 4 {
		t.Fatalf("list total=%d rows=%d err=%v", total, len(rows), err)
	}
	expected := map[int][2]float64{validID: {lat, lon}, archiveID: {otherLat, otherLon}, equatorID: {zero, lon}}
	for _, row := range rows {
		decoded, _ := chargeMapJSONForTest(t, row)
		id := int(decoded["charge_id"].(float64))
		detail, found, err := service.historyDetailContext(ctx, userA, carA, "charge", id)
		if err != nil || !found {
			t.Fatalf("detail %d found=%v err=%v", id, found, err)
		}
		detailJSON, _ := chargeMapJSONForTest(t, detail)
		if id == noPairID {
			if decoded["latitude"] != nil || decoded["longitude"] != nil ||
				detailJSON["latitude"] != nil || detailJSON["longitude"] != nil {
				t.Fatal("split, invalid, or zero coordinates became a location")
			}
		} else {
			want, ok := expected[id]
			if !ok || decoded["latitude"] != want[0] || decoded["longitude"] != want[1] ||
				detailJSON["latitude"] != want[0] || detailJSON["longitude"] != want[1] {
				t.Fatalf("list/detail coordinate mismatch for %d", id)
			}
		}
		if values, ok := decoded["charge_details"].([]any); !ok || len(values) > 1 {
			t.Fatal("bounded list returned the charge sample array")
		}
	}
	firstPage, _, count, _, err := service.historyPageContext(ctx, userA, carA, "charge", time.Time{}, time.Time{}, 1, 1)
	if err != nil || count != 4 || len(firstPage) != 1 || firstPage[0]["charge_id"] != archiveID {
		t.Fatalf("bounded pagination total=%d count=%d err=%v", count, len(firstPage), err)
	}
	for _, scope := range []struct {
		user string
		car  int
	}{{userB, carA}, {userA, carB}} {
		foreign, found, err := service.historyDetailContext(ctx, scope.user, scope.car, "charge", validID)
		if err != nil || found || foreign != nil {
			t.Fatal("cross-tenant charge detail leaked")
		}
		foreignRows, _, foreignCount, _, err := service.historyPageContext(ctx, scope.user, scope.car, "charge", time.Time{}, time.Time{}, 1, 10)
		if err != nil || foreignCount != 0 || len(foreignRows) != 0 {
			t.Fatal("cross-tenant charge list leaked")
		}
	}
}

func TestBoundedChargeLocationListDoesNotReturnSamples(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	user, car := boundedHistoryTestTenant(t, db, "charge-location-bounded")
	base := time.Date(2026, 1, 2, 3, 0, 0, 0, time.UTC)
	points := make([]telemetryChargePoint, 4000)
	for i := range points {
		points[i].ObservedAt = base.Add(time.Duration(i) * time.Millisecond)
	}
	lat, lon := 10.5, 20.5
	points[len(points)-1].Latitude, points[len(points)-1].Longitude = &lat, &lon
	insertChargeLocationsForTest(t, db, user, car, base, "telemetry_mqtt", points)
	service := &telemetryService{store: db}
	rows, _, total, _, err := service.historyPageContext(context.Background(), user, car, "charge", time.Time{}, time.Time{}, 1, 1)
	if err != nil || total != 1 || len(rows) != 1 {
		t.Fatalf("bounded list total=%d err=%v", total, err)
	}
	decoded, encoded := chargeMapJSONForTest(t, rows[0])
	if decoded["latitude"] != lat || decoded["longitude"] != lon {
		t.Fatal("valid last location missing")
	}
	if values, ok := decoded["charge_details"].([]any); !ok || len(values) > 1 {
		t.Fatal("bounded list returned its 4000 charge samples")
	}
	if len(encoded) > 4096 {
		t.Fatalf("bounded list response too large: %d", len(encoded))
	}
}

func TestBoundedChargeLocationListSkipsMalformedPairs(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	user, car := boundedHistoryTestTenant(t, db, "charge-location-malformed")
	base := time.Date(2026, 1, 2, 3, 0, 0, 0, time.UTC)
	id := insertChargeLocationsForTest(t, db, user, car, base, "telemetry_mqtt", nil)
	raw, err := json.Marshal([]map[string]any{
		{"ObservedAt": base, "Latitude": "invalid", "Longitude": 20.0},
		{"ObservedAt": base.Add(time.Second), "Latitude": 1000.0, "Longitude": 20.0},
		{"ObservedAt": base.Add(2 * time.Second), "latitude": 0.0, "longitude": 90.0},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.pool.Exec(context.Background(), `UPDATE jourvolt_telemetry_sessions SET charge_points_json=$1::jsonb
WHERE user_id=$2 AND vehicle_id=$3 AND public_id=$4`, raw, user, car, id); err != nil {
		t.Fatal(err)
	}
	service := &telemetryService{store: db}
	rows, _, _, _, err := service.historyPageContext(context.Background(), user, car, "charge", time.Time{}, time.Time{}, 1, 1)
	if err != nil || len(rows) != 1 {
		t.Fatalf("malformed location made list unavailable: %v", err)
	}
	decoded, _ := chargeMapJSONForTest(t, rows[0])
	if decoded["latitude"] != 0.0 || decoded["longitude"] != 90.0 {
		t.Fatal("valid lower-case coordinate pair was not selected")
	}
}
