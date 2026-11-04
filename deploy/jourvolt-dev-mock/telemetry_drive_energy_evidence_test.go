package main

import (
	"testing"
	"time"
)

func TestDriveRouteCapturesSocAndCalculatedPower(t *testing.T) {
	start := time.Unix(7000, 0).UTC()
	machine := newTelemetrySessionMachine(20 * time.Second)

	machine.apply(telemetrySessionEvent{FieldName: "Gear", Value: "D", ObservedAt: start, EventID: "gear-d"})
	machine.apply(telemetrySessionEvent{FieldName: "VehicleSpeed", Value: float64(45), ObservedAt: start.Add(time.Second), EventID: "speed-1"})
	machine.apply(telemetrySessionEvent{FieldName: "Soc", Value: float64(75), ObservedAt: start.Add(time.Second), EventID: "soc-1"})
	machine.apply(telemetrySessionEvent{FieldName: "PackVoltage", Value: float64(380.0), ObservedAt: start.Add(2 * time.Second), EventID: "volt-1"})
	machine.apply(telemetrySessionEvent{FieldName: "PackCurrent", Value: float64(50.0), ObservedAt: start.Add(2 * time.Second), EventID: "curr-1"})
	machine.apply(telemetrySessionEvent{FieldName: "Location", Value: map[string]any{"latitude": 30.25, "longitude": 120.15}, ObservedAt: start.Add(3 * time.Second), EventID: "loc-1"})

	if machine.drive == nil || len(machine.drive.Route) != 1 {
		t.Fatalf("expected 1 drive route point, got: %#v", machine.drive)
	}
	point := machine.drive.Route[0]
	if point.BatteryLevel == nil || *point.BatteryLevel != 75 {
		t.Fatalf("expected route point BatteryLevel 75, got: %#v", point.BatteryLevel)
	}
	// 380V * 50A / 1000 = 19.0 kW
	if point.Power == nil || *point.Power != 19.0 {
		t.Fatalf("expected route point Power 19.0 kW, got: %#v", point.Power)
	}

	machine.apply(telemetrySessionEvent{FieldName: "Location", Value: map[string]any{"latitude": 30.26, "longitude": 120.16}, ObservedAt: start.Add(29 * time.Second), EventID: "loc-2"})
	machine.apply(telemetrySessionEvent{FieldName: "Soc", Value: float64(74), ObservedAt: start.Add(30 * time.Second), EventID: "soc-2"})
	machine.apply(telemetrySessionEvent{FieldName: "Gear", Value: "P", ObservedAt: start.Add(31 * time.Second), EventID: "gear-p"})

	if !machine.finalizeDue(start.Add(52 * time.Second)) {
		t.Fatal("drive was not finalized")
	}
	completed := machine.completedSessions()
	if len(completed) != 1 {
		t.Fatalf("expected 1 completed drive session, got %d", len(completed))
	}
	first, last := driveBatteryBounds(completed[0])
	if first == nil || *first != 75 || last == nil || *last != 74 {
		t.Fatalf("expected driveBatteryBounds (75, 74), got: first=%v, last=%v", *first, *last)
	}
}
