package main

import (
	"context"
	"encoding/json"
	"testing"
	"time"
)

func TestFleetChargeLocationRequiresSessionTimestamp(t *testing.T) {
	start := time.Date(2026, 1, 2, 3, 0, 0, 123456000, time.UTC)
	end := start.Add(time.Hour)
	lat, lon, level := 10.5, 20.5, 48
	tests := []struct {
		name, source string
		observed     time.Time
		wantLocation bool
	}{
		{"missing", "telemetry_mqtt", time.Time{}, false},
		{"before start", "telemetry_mqtt", start.Add(-time.Nanosecond), false},
		{"at start", "telemetry_mqtt", start, true},
		{"within window", "telemetry_mqtt", start.Add(time.Minute), true},
		{"at end", "telemetry_mqtt", end, true},
		{"after end", "telemetry_mqtt", end.Add(time.Nanosecond), false},
		{"archive unchanged", "teslamate_archive", start.Add(-time.Minute), true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			session := telemetrySession{Kind: "charge", Source: tt.source, StartAt: start, EndAt: &end,
				ChargePoints: []telemetryChargePoint{{ObservedAt: tt.observed, Latitude: &lat, Longitude: &lon, BatteryLevel: &level}}}
			item, _ := chargeMapJSONForTest(t, historySessionMap(session, "charge", 0))
			if got := item["latitude"] != nil && item["longitude"] != nil; got != tt.wantLocation {
				t.Fatalf("location present=%t, want %t", got, tt.wantLocation)
			}
			details := item["charge_details"].([]any)
			if len(details) != 1 {
				t.Fatal("filtering location removed the battery observation")
			}
			detail := details[0].(map[string]any)
			if got := detail["latitude"] != nil && detail["longitude"] != nil; got != tt.wantLocation {
				t.Fatalf("detail location present=%t, want %t", got, tt.wantLocation)
			}
			if detail["battery_level"] != float64(level) {
				t.Fatal("battery observation was changed")
			}
			if tt.source == "telemetry_mqtt" && tt.observed.IsZero() && detail["date"] != nil {
				t.Fatal("missing observation time was presented as a real date")
			}
		})
	}
}

func TestBoundedFleetChargeLocationWindow(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	start := time.Date(2026, 1, 2, 3, 0, 0, 123456000, time.UTC)
	end := start.Add(17 * time.Minute)
	point := func(observed any, lat float64) map[string]any {
		return map[string]any{"ObservedAt": observed, "Latitude": lat, "Longitude": 20.5, "BatteryLevel": 48}
	}
	date := func(value time.Time) string { return value.Format(time.RFC3339Nano) }
	zone := time.FixedZone("synthetic", 8*60*60)
	tests := []struct {
		name, source string
		points       []map[string]any
		wantLatitude any
	}{
		{"before start", "telemetry_mqtt", []map[string]any{point(date(start.Add(-time.Nanosecond)), 10.5)}, nil},
		{"start inclusive", "telemetry_mqtt", []map[string]any{point(date(start), 10.5)}, 10.5},
		{"end inclusive", "telemetry_mqtt", []map[string]any{point(date(end), 10.5)}, 10.5},
		{"after end", "telemetry_mqtt", []map[string]any{point(date(end.Add(time.Nanosecond)), 10.5)}, nil},
		{"offset nanoseconds", "telemetry_mqtt", []map[string]any{point(date(start.Add(time.Nanosecond).In(zone)), 10.5)}, 10.5},
		{"missing time", "telemetry_mqtt", []map[string]any{point(nil, 10.5)}, nil},
		{"absent time", "telemetry_mqtt", []map[string]any{{"Latitude": 10.5, "Longitude": 20.5, "BatteryLevel": 48}}, nil},
		{"alternate time key", "telemetry_mqtt", []map[string]any{{"observedAt": date(start.Add(time.Second)), "Latitude": 10.5, "Longitude": 20.5}}, 10.5},
		{"before then valid", "telemetry_mqtt", []map[string]any{point(date(start.Add(-time.Nanosecond)), 30.5), point(date(start.Add(time.Second)), 10.5)}, 10.5},
		{"malformed then valid", "telemetry_mqtt", []map[string]any{point("2026-02-30T03:00:00Z", 30.5), point(date(start.Add(time.Second)), 10.5)}, 10.5},
		{"invalid types", "telemetry_mqtt", []map[string]any{point(9000000000, 10.5)}, nil},
		{"archive unchanged", "teslamate_archive", []map[string]any{point(date(start.Add(-time.Minute)), 10.5)}, 10.5},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			user, car := boundedHistoryTestTenant(t, db, "charge-window")
			id := insertChargeLocationsForTest(t, db, user, car, start, tt.source, nil)
			encoded, err := json.Marshal(tt.points)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := db.pool.Exec(context.Background(), `UPDATE jourvolt_telemetry_sessions SET charge_points_json=$1::jsonb
WHERE user_id=$2 AND vehicle_id=$3 AND public_id=$4`, encoded, user, car, id); err != nil {
				t.Fatal(err)
			}
			service := &telemetryService{store: db}
			rows, _, count, _, err := service.historyPageContext(context.Background(), user, car, "charge", time.Time{}, time.Time{}, 1, 1)
			if err != nil || count != 1 || len(rows) != 1 {
				t.Fatalf("list count=%d err=%v", count, err)
			}
			list, _ := chargeMapJSONForTest(t, rows[0])
			detail, found, err := service.historyDetailContext(context.Background(), user, car, "charge", id)
			if err != nil || !found {
				t.Fatalf("detail found=%t err=%v", found, err)
			}
			detailJSON, _ := chargeMapJSONForTest(t, detail)
			if list["latitude"] != tt.wantLatitude || detailJSON["latitude"] != tt.wantLatitude {
				t.Fatalf("list latitude=%v detail latitude=%v, want %v", list["latitude"], detailJSON["latitude"], tt.wantLatitude)
			}
		})
	}
}

func TestChargeLocationCaptureRejectsBeforeStart(t *testing.T) {
	start := time.Date(2026, 1, 2, 3, 0, 0, 0, time.UTC)
	machine := newTelemetrySessionMachine(time.Second)
	machine.apply(telemetrySessionEvent{FieldName: "DetailedChargeState", Value: "Charging", ObservedAt: start, EventID: "start"})
	location := map[string]any{"latitude": 10.5, "longitude": 20.5}
	machine.apply(telemetrySessionEvent{FieldName: "Location", Value: location, ObservedAt: start.Add(-time.Nanosecond), EventID: "early"})
	machine.apply(telemetrySessionEvent{FieldName: "Location", Value: location, ObservedAt: start, EventID: "start-location"})
	if machine.charge == nil || len(machine.charge.ChargePoints) != 1 || !machine.charge.ChargePoints[0].ObservedAt.Equal(start) {
		t.Fatal("charge session must retain only locations observed at or after its start")
	}
}
