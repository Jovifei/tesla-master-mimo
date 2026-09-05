package main

import (
	"encoding/json"
	"math"
	"strings"
	"testing"
	"time"
)

func TestTelemetryParserAcceptsNumericAndNumericStringValues(t *testing.T) {
	observedAt := time.Date(2026, time.August, 30, 1, 2, 3, 0, time.UTC)
	numeric, err := parseTelemetryPayload("jourvolt", "jourvolt/5YJ3E1EA7KF123456/v/VehicleSpeed", []byte("12.5"), observedAt)
	if err != nil {
		t.Fatal(err)
	}
	stringValue, err := parseTelemetryPayload("jourvolt", "jourvolt/5YJ3E1EA7KF123456/v/VehicleSpeed", []byte(`"12.5"`), observedAt)
	if err != nil {
		t.Fatal(err)
	}
	if got, ok := numeric.Value.(float64); !ok || got != 12.5 {
		t.Fatalf("numeric value = %#v, want float64(12.5)", numeric.Value)
	}
	if got, ok := stringValue.Value.(float64); !ok || got != 12.5 {
		t.Fatalf("numeric string value = %#v, want float64(12.5)", stringValue.Value)
	}
	if numeric.EventID != stringValue.EventID {
		t.Fatalf("equivalent values should have the same event identity: %q != %q", numeric.EventID, stringValue.EventID)
	}
	if numeric.ObservedAt != observedAt {
		t.Fatalf("observed_at = %v, want %v", numeric.ObservedAt, observedAt)
	}
}

func TestTelemetryParserRejectsOversizedInvalidAndNonFinitePayloads(t *testing.T) {
	for name, payload := range map[string][]byte{
		"non-finite":   []byte(`"NaN"`),
		"invalid-json": []byte(`{"value":`),
		"oversized":    []byte(strings.Repeat("9", maxTelemetryPayloadBytes+1)),
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := parseTelemetryPayload("jourvolt", "jourvolt/vin/v/VehicleSpeed", payload, time.Now().UTC()); err == nil {
				t.Fatal("expected payload rejection")
			}
		})
	}
	if _, err := normalizeTelemetryValue("VehicleSpeed", json.RawMessage([]byte("1e9999"))); err == nil {
		t.Fatal("overflowing numeric payload must be rejected")
	}
	if value, err := normalizeTelemetryValue("VehicleSpeed", json.RawMessage([]byte("0"))); err != nil || value.(float64) != 0 {
		t.Fatalf("zero numeric observation = %#v, %v", value, err)
	}
	if value, err := normalizeTelemetryValue("Locked", json.RawMessage([]byte("false"))); err != nil || value.(bool) {
		t.Fatalf("false boolean observation = %#v, %v", value, err)
	}
	if math.IsNaN(float64(0)) { // keep the test's intent explicit for future parser changes
		t.Fatal("unreachable")
	}
}

func TestTelemetryParserOnlyAllowsDocumentedFieldsAndIntervals(t *testing.T) {
	allowed, ok := telemetryFieldSpec("VehicleSpeed")
	if !ok || allowed.Interval != 10*time.Second {
		t.Fatalf("VehicleSpeed spec = %#v, want 10s", allowed)
	}
	allowed, ok = telemetryFieldSpec("Odometer")
	if !ok || allowed.Interval != time.Minute {
		t.Fatalf("Odometer spec = %#v, want 60s", allowed)
	}
	if _, ok := telemetryFieldSpec("PrivateToken"); ok {
		t.Fatal("unknown telemetry field must not be accepted")
	}
}

func TestTelemetryVINLookupIsKeyedAndRecordDoesNotRetainPlaintextVIN(t *testing.T) {
	vin := "5YJ3E1EA7KF123456"
	record, err := parseTelemetryPayload("jourvolt", "jourvolt/"+vin+"/v/Soc", []byte("0"), time.Now().UTC())
	if err != nil {
		t.Fatal(err)
	}
	if record.VINHash == "" || strings.Contains(record.VINHash, vin) {
		t.Fatalf("VIN hash = %q", record.VINHash)
	}
	if strings.Contains(record.EventID, vin) || strings.Contains(record.FieldName, vin) {
		t.Fatalf("record leaks VIN: %#v", record)
	}
	if strings.Contains(strings.TrimSpace(record.debugString()), vin) {
		t.Fatalf("record debug representation leaks VIN: %s", record.debugString())
	}
	if keyedVINHash([]byte("key-a"), vin) == keyedVINHash([]byte("key-b"), vin) {
		t.Fatal("VIN lookup hash must be keyed")
	}
}

func TestTelemetryLatestIgnoresDuplicateAndOutOfOrderEvents(t *testing.T) {
	store := newTelemetryMemoryStore()
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 7, VINHash: keyedVINHash([]byte("key"), "vin-a"), ProviderVehicleID: "provider-7"}
	store.registerVehicle(ref)
	newer := telemetryRecord{VINHash: ref.VINHash, FieldName: "Soc", Value: float64(42), ObservedAt: time.Unix(200, 0).UTC(), EventID: "newer"}
	older := telemetryRecord{VINHash: ref.VINHash, FieldName: "Soc", Value: float64(18), ObservedAt: time.Unix(100, 0).UTC(), EventID: "older"}
	duplicate := newer
	if _, err := store.ingest(newer, defaultDriveStopDebounce); err != nil {
		t.Fatal(err)
	}
	if _, err := store.ingest(duplicate, defaultDriveStopDebounce); err != nil {
		t.Fatal(err)
	}
	if _, err := store.ingest(older, defaultDriveStopDebounce); err != nil {
		t.Fatal(err)
	}
	latest, ok := store.latestSnapshot(ref.UserID, ref.VehicleID)
	if !ok || latest.Fields["Soc"].(float64) != 42 || latest.EventCount != 1 {
		t.Fatalf("latest = %#v, want only newer event", latest)
	}
}

func TestDriveSessionRestartsAndCompletesExactlyOnce(t *testing.T) {
	start := time.Unix(1000, 0).UTC()
	machine := newTelemetrySessionMachine(20 * time.Second)
	machine.apply(telemetrySessionEvent{FieldName: "Gear", Value: "D", ObservedAt: start, EventID: "gear-start"})
	machine.apply(telemetrySessionEvent{FieldName: "VehicleSpeed", Value: float64(12), ObservedAt: start.Add(10 * time.Second), EventID: "speed-start"})
	persisted := machine.snapshot()
	resumed := newTelemetrySessionMachineFromSnapshot(20*time.Second, persisted)
	resumed.apply(telemetrySessionEvent{FieldName: "Gear", Value: "P", ObservedAt: start.Add(30 * time.Second), EventID: "gear-stop"})
	resumed.apply(telemetrySessionEvent{FieldName: "VehicleSpeed", Value: float64(0), ObservedAt: start.Add(55 * time.Second), EventID: "speed-stop"})
	resumed.apply(telemetrySessionEvent{FieldName: "Speed", Value: float64(0), ObservedAt: start.Add(55 * time.Second), EventID: "ignored"})
	resumed.apply(telemetrySessionEvent{FieldName: "Gear", Value: "P", ObservedAt: start.Add(55 * time.Second), EventID: "gear-stop"})
	completed := resumed.completedSessions()
	if len(completed) != 1 || completed[0].Kind != "drive" {
		t.Fatalf("completed sessions = %#v", completed)
	}
	if completed[0].StartAt != start || completed[0].EndAt == nil || !completed[0].EndAt.Equal(start.Add(55*time.Second)) {
		t.Fatalf("drive session timing = %#v", completed[0])
	}
}

func TestChargeSessionEndsOnCompleteAndDuplicateDoesNotCreateAnother(t *testing.T) {
	start := time.Unix(3000, 0).UTC()
	machine := newTelemetrySessionMachine(20 * time.Second)
	machine.apply(telemetrySessionEvent{FieldName: "DetailedChargeState", Value: "Charging", ObservedAt: start, EventID: "charge-start"})
	machine.apply(telemetrySessionEvent{FieldName: "DetailedChargeState", Value: "Complete", ObservedAt: start.Add(time.Minute), EventID: "charge-end"})
	machine.apply(telemetrySessionEvent{FieldName: "DetailedChargeState", Value: "Complete", ObservedAt: start.Add(time.Minute), EventID: "charge-end"})
	completed := machine.completedSessions()
	if len(completed) != 1 || completed[0].Kind != "charge" || completed[0].EndAt == nil {
		t.Fatalf("charge sessions = %#v", completed)
	}
}

func TestRouteDownsamplingPreservesFirstAndTimeSeparatedPoints(t *testing.T) {
	base := time.Unix(5000, 0).UTC()
	points := []telemetryRoutePoint{
		{ObservedAt: base, Latitude: 31.0, Longitude: 121.0},
		{ObservedAt: base.Add(5 * time.Second), Latitude: 31.1, Longitude: 121.1},
		{ObservedAt: base.Add(10 * time.Second), Latitude: 31.2, Longitude: 121.2},
		{ObservedAt: base.Add(11 * time.Second), Latitude: 31.3, Longitude: 121.3},
	}
	got := downsampleRoutePoints(points, 10*time.Second)
	if len(got) != 2 || got[0].Latitude != 31.0 || got[1].Latitude != 31.2 {
		t.Fatalf("downsampled route = %#v", got)
	}
}

func TestMergeTelemetryStatusPreservesObservedNilZeroAndFalseValues(t *testing.T) {
	base := vehicleStatus{BatteryLevel: intPointer(76), Locked: boolPointer(true), Speed: intPointer(12)}
	zero, falseValue := 0.0, false
	snapshot := telemetrySnapshot{ObservedAt: time.Unix(9000, 0).UTC(), Source: "telemetry_mqtt", Fields: map[string]any{
		"Soc":          float64(0),
		"Locked":       falseValue,
		"VehicleSpeed": zero,
		"OutsideTemp":  float64(0),
		"Latitude":     zero,
		"Longitude":    zero,
	}}
	merged := mergeTelemetryStatus(base, snapshot)
	if merged.BatteryLevel == nil || *merged.BatteryLevel != 0 || merged.Locked == nil || *merged.Locked || merged.Speed == nil || *merged.Speed != 0 {
		t.Fatalf("merged zero/false values lost: %#v", merged)
	}
	if merged.OutsideTemp == nil || *merged.OutsideTemp != 0 || merged.Latitude == nil || *merged.Latitude != 0 || merged.Longitude == nil || *merged.Longitude != 0 {
		t.Fatalf("merged telemetry values lost: %#v", merged)
	}
	if merged.Source != "telemetry_mqtt" || !merged.ObservedAt.Equal(snapshot.ObservedAt) {
		t.Fatalf("merged provenance = %q %v", merged.Source, merged.ObservedAt)
	}
}
