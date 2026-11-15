package main

import (
	"reflect"
	"testing"
	"time"
)

func TestAppReleaseCurrentSnapshotPreservesSessionBoundaries(t *testing.T) {
	level := 70
	item := map[string]any{
		"start_date":      "2026-10-27T10:00:00Z",
		"battery_details": map[string]any{"start_battery_level": 20, "end_battery_level": nil},
		"range_rated":     map[string]any{"start_range": 100.0, "end_range": 250.0},
	}
	mergeCurrentChargeStatus(item, vehicleStatus{BatteryLevel: &level, ObservedAt: time.Now().UTC()})
	battery := item["battery_details"].(map[string]any)
	if battery["start_battery_level"] != 20 {
		t.Fatalf("snapshot erased actual session start SOC: %#v", battery)
	}
	if item["range_rated"].(map[string]any)["start_range"] != 100.0 {
		t.Fatalf("snapshot erased actual start range: %#v", item["range_rated"])
	}
}

func TestAppReleaseCurrentSnapshotPreservesActualHistoricalPoints(t *testing.T) {
	power := 7
	points := []map[string]any{{"date": "2026-10-27T10:00:00Z", "battery_level": 20}}
	item := map[string]any{"charge_details": points}
	mergeCurrentChargeStatus(item, vehicleStatus{ChargerPower: &power, ObservedAt: time.Now().UTC()})
	if !reflect.DeepEqual(item["charge_details"], points) {
		t.Fatalf("instantaneous snapshot replaced stored curve: %#v", item["charge_details"])
	}
}

func TestAppReleaseCurrentSnapshotDoesNotCombineUnobservedCoordinatePair(t *testing.T) {
	lat := 11.0
	item := map[string]any{"latitude": 10.0, "longitude": 20.0}
	mergeCurrentChargeStatus(item, vehicleStatus{Latitude: &lat, ObservedAt: time.Now().UTC()})
	if item["latitude"] != 10.0 || item["longitude"] != 20.0 {
		t.Fatalf("partial snapshot replaced known session coordinate pair: %#v", item)
	}
}

func TestAppReleaseProviderSnapshotCannotInventSessionStart(t *testing.T) {
	for _, observed := range []time.Time{time.Time{}, time.Date(2026, 10, 27, 10, 0, 0, 0, time.UTC)} {
		item := currentChargeMapFromStatus(1, vehicleStatus{ObservedAt: observed, Source: "fleet_api"})
		if item["start_date"] != nil {
			t.Fatalf("latest observation is not plug-in time: %#v", item["start_date"])
		}
		if item["duration_min"] != nil {
			t.Fatalf("unknown session cannot have fabricated elapsed time: %#v", item["duration_min"])
		}
	}
}

func TestAppReleaseCurrentCounterReceiptRequiresAnObservedFleetValue(t *testing.T) {
	value := 12.0
	observed := time.Date(2026, 10, 27, 10, 0, 0, 0, time.UTC)
	for _, tc := range []struct {
		source string
		at     time.Time
		known  bool
	}{
		{"fleet_api", observed, true}, {"fleet_api", time.Time{}, false}, {"telemetry_mqtt", observed, false},
	} {
		item := currentChargeMapFromStatus(1, vehicleStatus{ObservedAt: tc.at, Source: tc.source, ChargeEnergyAdded: &value})
		_, exists := item["current_energy_receipt"]
		if exists != tc.known {
			t.Fatalf("unexpected counter qualification for %#v", tc)
		}
		if item["start_date"] != nil {
			t.Fatal("counter cannot invent a start time")
		}
	}
}
