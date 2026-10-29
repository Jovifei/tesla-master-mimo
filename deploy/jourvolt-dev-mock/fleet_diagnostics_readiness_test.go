package main

import (
	"context"
	"math"
	"testing"
	"time"
)

func TestFleetDiagnosticsReadinessPreservesLongNumericVehicleUID(t *testing.T) {
	database, _ := diagnosticsTestStore(t, "", "", false, diagnosticStoreOption{providerID: "1234567890123456789"})
	result := (&app{store: database}).readOnlyReadinessContext(context.Background(), diagnosticsTestUser, 1)
	if result.Car == nil || result.Car.VehicleUID != "1234567890123456789" || result.Readiness == nil {
		t.Fatal("long numeric provider identity was mistaken for a VIN")
	}
}

func diagnosticReadinessForProjection() *diagnosticReadinessContext {
	return &diagnosticReadinessContext{Readiness: &dataReadinessResponse{Items: []dataReadinessItem{{Key: "live_status", Status: "unknown"}, {Key: "location", Status: "unknown"}, {Key: "tpms", Status: "unknown"}}}, ObservedOdometer: diagnosticObservedOdometer{Unit: "km"}}
}

func TestFleetDiagnosticsReadinessRejectsUnprovenFields(t *testing.T) {
	now := time.Now().UTC()
	for _, tc := range []struct {
		name, source string
		at           time.Time
		value        any
	}{
		{"missing clock", "telemetry_mqtt", time.Time{}, float64(100)},
		{"missing source", "", now, float64(100)},
		{"other producer", "fleet_api", now, float64(100)},
		{"future", "telemetry_mqtt", now.Add(time.Second), float64(100)},
		{"negative", "telemetry_mqtt", now, float64(-1)},
		{"nonfinite", "telemetry_mqtt", now, math.Inf(1)},
		{"raw string", "telemetry_mqtt", now, "100"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			d := diagnosticReadinessForProjection()
			d.projectReadinessSnapshot(telemetrySnapshot{Fields: map[string]any{"Odometer": tc.value}, FieldSources: map[string]string{"Odometer": tc.source}, FieldObservedAt: map[string]time.Time{"Odometer": tc.at}, ObservedAt: now, Source: "telemetry_mqtt"}, now)
			if d.ObservedOdometer.Value != nil || d.Readiness.Items[0].Status != "unknown" {
				t.Fatal("unproven field was promoted or assigned km")
			}
		})
	}
}

func TestFleetDiagnosticsReadinessUsesPerFieldClockAndCanonicalKm(t *testing.T) {
	now := time.Now().UTC()
	d := diagnosticReadinessForProjection()
	snapshot := telemetrySnapshot{Fields: map[string]any{"Odometer": float64(0), "Location": map[string]any{"latitude": float64(31), "longitude": float64(121)}, "TpmsPressureFl": float64(2.5)}, FieldSources: map[string]string{"Odometer": "telemetry_mqtt", "Location": "telemetry_mqtt", "TpmsPressureFl": "telemetry_mqtt"}, FieldObservedAt: map[string]time.Time{"Odometer": now.Add(-time.Hour), "Location": now.Add(-time.Minute), "TpmsPressureFl": now.Add(-time.Second)}, ObservedAt: now}
	d.projectReadinessSnapshot(snapshot, now)
	if d.ObservedOdometer.Value == nil || *d.ObservedOdometer.Value != 0 || d.ObservedOdometer.ObservedAt == nil || *d.ObservedOdometer.ObservedAt != now.Add(-time.Hour).Format(time.RFC3339) {
		t.Fatal("persisted canonical zero odometer lost")
	}
	if d.Readiness.Items[1].LastObservedAt == nil || *d.Readiness.Items[1].LastObservedAt != now.Add(-time.Minute).Format(time.RFC3339) || d.Readiness.Items[2].LastObservedAt == nil || *d.Readiness.Items[2].LastObservedAt != now.Add(-time.Second).Format(time.RFC3339) {
		t.Fatal("global clock replaced field proof")
	}
	for _, item := range d.Readiness.Items {
		if item.Status != "available" {
			t.Fatal("proven local observation lost")
		}
	}
	delete(snapshot.FieldObservedAt, "TpmsPressureFl")
	d = diagnosticReadinessForProjection()
	d.projectReadinessSnapshot(snapshot, now)
	if d.Readiness.Items[2].Status != "unknown" {
		t.Fatal("TPMS inherited unrelated snapshot clock")
	}
}

func TestFleetDiagnosticsReadinessMetadataFiltersSensitiveAndInvalidText(t *testing.T) {
	for _, value := range []string{diagnosticsTestVIN, "prefix " + diagnosticsTestVIN, "https://raw.example", "Bearer token\n", diagnosticsTestUser} {
		if diagnosticStoredText(value, diagnosticsTestUser) != nil {
			t.Error("unsafe metadata projected")
		}
	}
	if diagnosticStoredText("Jovi Model 3", diagnosticsTestUser) == nil {
		t.Error("ordinary stored car name lost")
	}
}
