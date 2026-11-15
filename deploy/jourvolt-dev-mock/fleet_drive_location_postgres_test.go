package main

import (
	"context"
	"encoding/json"
	"testing"
	"time"
)

func TestBoundedFleetDriveLocationPairAndTimeIsolation(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	start := time.Date(2026, 1, 2, 3, 0, 0, 123456000, time.UTC)
	end := start.Add(17 * time.Minute)
	date := func(value time.Time) string { return value.Format(time.RFC3339Nano) }
	upper := func(when any, lat, lon any) map[string]any {
		return map[string]any{"ObservedAt": when, "Latitude": lat, "Longitude": lon}
	}
	lower := func(when any, lat, lon any) map[string]any {
		return map[string]any{"observedAt": when, "latitude": lat, "longitude": lon}
	}
	zone := time.FixedZone("synthetic", 8*60*60)
	tests := []struct {
		name, source                       string
		points                             []map[string]any
		startLat, startLon, endLat, endLon any
	}{
		{"native uppercase", "telemetry_mqtt", []map[string]any{upper(date(start), 10.5, 20.5), upper(date(end), 11.5, 21.5)}, 10.5, 20.5, 11.5, 21.5},
		{"native lowercase offset", "telemetry_mqtt", []map[string]any{lower(date(start.Add(time.Nanosecond).In(zone)), 10.5, 20.5), lower(date(end.In(zone)), 11.5, 21.5)}, 10.5, 20.5, 11.5, 21.5},
		{"split axes", "telemetry_mqtt", []map[string]any{{"ObservedAt": date(start), "Latitude": 10.5, "longitude": 20.5}, upper(date(end), 11.5, 21.5)}, nil, nil, 11.5, 21.5},
		{"outside session", "telemetry_mqtt", []map[string]any{upper(date(start.Add(-time.Nanosecond)), 10.5, 20.5), upper(date(end.Add(time.Nanosecond)), 11.5, 21.5)}, nil, nil, nil, nil},
		{"missing and bad time", "telemetry_mqtt", []map[string]any{upper(nil, 10.5, 20.5), upper("2026-02-30T03:00:00Z", 11.5, 21.5)}, nil, nil, nil, nil},
		{"reversed time", "telemetry_mqtt", []map[string]any{upper(date(start.Add(2*time.Second)), 10.5, 20.5), upper(date(start.Add(time.Second)), 11.5, 21.5)}, nil, nil, nil, nil},
		{"conflicting same time", "telemetry_mqtt", []map[string]any{upper(date(start), 10.5, 20.5), upper(date(start), 11.5, 21.5)}, nil, nil, nil, nil},
		{"only start", "telemetry_mqtt", []map[string]any{upper(date(start), 10.5, 20.5), upper(nil, 11.5, 21.5)}, 10.5, 20.5, nil, nil},
		{"invalid numeric", "telemetry_mqtt", []map[string]any{upper(date(start), json.Number("1e1000"), 20.5), upper(date(end), "not-a-number", 21.5)}, nil, nil, nil, nil},
		{"underflow numeric", "telemetry_mqtt", []map[string]any{upper(date(start), json.Number("1e-1000"), 20.5), upper(date(end), 11.5, 21.5)}, nil, nil, 11.5, 21.5},
		{"fallback valid pair and equator", "telemetry_mqtt", []map[string]any{{"ObservedAt": date(start), "Latitude": 91, "Longitude": 22, "latitude": 10.5, "longitude": 20.5}, lower(date(end), 0, 21.5)}, 10.5, 20.5, 0.0, 21.5},
		{"archive unchanged", "teslamate_archive", []map[string]any{{"date": date(start), "latitude": 10.5, "longitude": 20.5}, {"date": date(end), "latitude": 11.5, "longitude": 21.5}}, 10.5, 20.5, 11.5, 21.5},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			user, car := boundedHistoryTestTenant(t, db, "fleet-drive-locations")
			boundedHistoryInsertSession(t, db, user, car, "drive", start, tt.source, "observed", tt.points, "")
			service := &telemetryService{store: db}
			rows, _, total, _, err := service.historyPageContext(context.Background(), user, car, "drive", time.Time{}, time.Time{}, 1, 1)
			if err != nil || total != 1 || len(rows) != 1 {
				t.Fatalf("list count=%d err=%v", total, err)
			}
			decoded, _ := chargeMapJSONForTest(t, rows[0])
			for key, want := range map[string]any{"start_latitude": tt.startLat, "start_longitude": tt.startLon, "end_latitude": tt.endLat, "end_longitude": tt.endLon} {
				if decoded[key] != want {
					t.Errorf("%s=%v, want %v", key, decoded[key], want)
				}
			}
		})
	}
}

func TestBoundedFleetDriveLocationRemainsScopedAndBounded(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	user, car := boundedHistoryTestTenant(t, db, "fleet-drive-owner")
	otherUser, otherCar := boundedHistoryTestTenant(t, db, "fleet-drive-other")
	start := time.Date(2026, 1, 2, 3, 0, 0, 0, time.UTC)
	points := make([]telemetryRoutePoint, 4000)
	for i := range points {
		points[i] = telemetryRoutePoint{ObservedAt: start.Add(time.Duration(i) * time.Millisecond), Latitude: 10.5, Longitude: 20.5}
	}
	id := boundedHistoryInsertSession(t, db, user, car, "drive", start, "telemetry_mqtt", "observed", points, "")
	service := &telemetryService{store: db}
	rows, _, total, _, err := service.historyPageContext(context.Background(), user, car, "drive", time.Time{}, time.Time{}, 1, 1)
	if err != nil || total != 1 || len(rows) != 1 {
		t.Fatalf("list total=%d err=%v", total, err)
	}
	decoded, encoded := chargeMapJSONForTest(t, rows[0])
	if decoded["start_latitude"] != 10.5 || decoded["end_latitude"] != 10.5 {
		t.Fatal("valid Fleet endpoints were not returned")
	}
	if points, ok := decoded["drive_details"].([]any); !ok || len(points) > 2 || len(encoded) > 4096 {
		t.Fatal("bounded list returned the full route")
	}
	for _, scope := range []struct {
		user string
		car  int
	}{{otherUser, car}, {user, otherCar}} {
		foreign, _, count, _, err := service.historyPageContext(context.Background(), scope.user, scope.car, "drive", time.Time{}, time.Time{}, 1, 1)
		if err != nil || count != 0 || len(foreign) != 0 {
			t.Fatal("cross-identity drive list leaked")
		}
		item, found, err := service.historyDetailContext(context.Background(), scope.user, scope.car, "drive", id)
		if err != nil || found || item != nil {
			t.Fatal("cross-identity drive detail leaked")
		}
	}
}
