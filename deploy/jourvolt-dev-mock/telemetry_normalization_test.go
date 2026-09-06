package main

import (
	"context"
	"math"
	"testing"
	"time"
)

func TestCanonicalGearAcceptsOfficialProtoNames(t *testing.T) {
	cases := map[string]string{
		"ShiftStateD": "D",
		"ShiftStateR": "R",
		"ShiftStateP": "P",
		"ShiftStateN": "N",
	}
	for input, want := range cases {
		got, ok := canonicalGear(input)
		if !ok || got != want {
			t.Fatalf("canonicalGear(%q)=(%q,%v), want (%q,true)", input, got, ok, want)
		}
	}
}

func TestCanonicalDetailedChargeStateAcceptsOfficialProtoNames(t *testing.T) {
	cases := map[string]string{
		"DetailedChargeStateCharging":     "charging",
		"DetailedChargeStateComplete":     "complete",
		"completed":                       "complete",
		"DetailedChargeStateDisconnected": "disconnected",
		"DetailedChargeStateStarting":     "starting",
	}
	for input, want := range cases {
		if got := canonicalDetailedChargeState(input); got != want {
			t.Fatalf("canonicalDetailedChargeState(%q)=%q, want %q", input, got, want)
		}
	}
	if got := canonicalDetailedChargeState("not-a-charge-state"); got != "" {
		t.Fatalf("unknown charge state=%q, want empty", got)
	}
}

func TestTelemetryMilesBecomeKilometres(t *testing.T) {
	for _, field := range []string{"VehicleSpeed", "Odometer", "EstBatteryRange", "RatedRange"} {
		value, err := normalizeTelemetryValue(field, []byte("1"))
		if err != nil {
			t.Fatalf("normalizeTelemetryValue(%s): %v", field, err)
		}
		got, ok := value.(float64)
		if !ok || math.Abs(got-1.609344) > 1e-9 {
			t.Fatalf("%s=%#v, want 1.609344", field, value)
		}
	}
}

func TestNormalizeTelemetryValueCanonicalizesOfficialEnums(t *testing.T) {
	gear, err := normalizeTelemetryValue("Gear", []byte(`"ShiftStateD"`))
	if err != nil || gear != "D" {
		t.Fatalf("gear=%#v err=%v, want D", gear, err)
	}
	charge, err := normalizeTelemetryValue("DetailedChargeState", []byte(`"DetailedChargeStateCharging"`))
	if err != nil || charge != "charging" {
		t.Fatalf("charge=%#v err=%v, want charging", charge, err)
	}
	gear, err = normalizeTelemetryValue("Gear", []byte("5"))
	if err != nil || gear != "D" {
		t.Fatalf("numeric gear=%#v err=%v, want D", gear, err)
	}
	charge, err = normalizeTelemetryValue("DetailedChargeState", []byte("4"))
	if err != nil || charge != "charging" {
		t.Fatalf("numeric charge=%#v err=%v, want charging", charge, err)
	}
}

func TestTelemetryFieldSpecsIncludeChargingObservations(t *testing.T) {
	for _, field := range []string{
		"ACChargingEnergyIn", "DCChargingEnergyIn", "ACChargingPower", "DCChargingPower",
		"ChargeAmps", "ChargerPhases", "ChargeCurrentRequest", "ChargeCurrentRequestMax",
		"TimeToFullCharge", "FastChargerPresent", "PackVoltage", "PackCurrent",
	} {
		if _, ok := telemetryFieldSpec(field); !ok {
			t.Fatalf("field %s is not configured", field)
		}
	}
}

func TestTelemetrySessionTracksObservedChargeEnergyDelta(t *testing.T) {
	start := time.Date(2026, time.September, 6, 1, 0, 0, 0, time.UTC)
	machine := newTelemetrySessionMachine(20 * time.Second)
	machine.apply(telemetrySessionEvent{FieldName: "DetailedChargeState", Value: "charging", ObservedAt: start, EventID: "charge-start"})
	machine.apply(telemetrySessionEvent{FieldName: "DCChargingEnergyIn", Value: 20.0, ObservedAt: start.Add(time.Second), EventID: "energy-start"})
	machine.apply(telemetrySessionEvent{FieldName: "DCChargingEnergyIn", Value: 25.5, ObservedAt: start.Add(2 * time.Second), EventID: "energy-progress"})
	machine.apply(telemetrySessionEvent{FieldName: "DetailedChargeState", Value: "complete", ObservedAt: start.Add(3 * time.Second), EventID: "charge-complete"})
	completed := machine.completedSessions()
	if len(completed) != 1 || completed[0].EnergyAdded == nil || *completed[0].EnergyAdded != 5.5 {
		t.Fatalf("completed=%#v, want observed 5.5 kWh delta", completed)
	}
}

func TestCurrentVehicleStatusKeepsFleetPositionWhenTelemetryIsPartial(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryRefWithVIN(service, "user-a", 1, "5YJ3E1EA7KF123456")
	if err := service.memory.registerVehicle(ref); err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC()
	if _, err := service.memory.ingest(telemetryRecord{
		VINHash: ref.VINHash, FieldName: "Soc", Value: float64(72), ObservedAt: now,
		EventID: "soc-only",
	}, service.stopDebounceOrDefault()); err != nil {
		t.Fatal(err)
	}
	latitude, longitude := 30.25, 120.17
	api := &app{
		telemetry: service,
		provider: testProvider{
			vehicles: map[string][]vehicle{"user-a": {{ID: 1}}},
			statuses: map[string]vehicleStatus{"user-a": {Latitude: &latitude, Longitude: &longitude}},
		},
	}
	status, err := api.currentVehicleStatus(context.Background(), "user-a", 1)
	if err != nil {
		t.Fatal(err)
	}
	if status.Latitude == nil || status.Longitude == nil || *status.Latitude != latitude || *status.Longitude != longitude {
		t.Fatalf("partial telemetry erased Fleet position: %#v", status)
	}
}

func TestLocationDoesNotReuseStaleDrivingObservations(t *testing.T) {
	start := time.Date(2026, time.September, 6, 1, 0, 0, 0, time.UTC)
	speed := 30.0
	machine := newTelemetrySessionMachine(20 * time.Second)
	machine.lastSpeed = &speed
	machine.lastSpeedAt = start
	point, ok := routePointFromLocationWithDefaults(
		telemetrySessionEvent{ObservedAt: start.Add(telemetryStaleAfter + time.Second), Value: map[string]any{"latitude": 30.0, "longitude": 120.0}},
		machine.recentObservation(machine.lastSpeed, machine.lastSpeedAt, start.Add(telemetryStaleAfter+time.Second)), nil, nil,
	)
	if !ok || point.Speed != nil {
		t.Fatalf("stale speed=%#v, want nil", point.Speed)
	}
}

func TestTelemetrySnapshotKeepsPerFieldSources(t *testing.T) {
	store := newTelemetryMemoryStore()
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "hash"}
	if err := store.registerVehicle(ref); err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC()
	if _, err := store.ingest(telemetryRecord{VINHash: ref.VINHash, FieldName: "Soc", Value: float64(72), ObservedAt: now, EventID: "soc", Source: "telemetry_mqtt"}, defaultDriveStopDebounce); err != nil {
		t.Fatal(err)
	}
	snapshot, ok := store.latestSnapshot(ref.UserID, ref.VehicleID)
	if !ok || snapshot.FieldSources["Soc"] != "telemetry_mqtt" {
		t.Fatalf("snapshot sources=%#v, want Soc telemetry_mqtt", snapshot.FieldSources)
	}
}

func TestTelemetryLocationUsesFreshDrivingDefaults(t *testing.T) {
	start := time.Date(2026, time.September, 6, 1, 0, 0, 0, time.UTC)
	machine := newTelemetrySessionMachine(20 * time.Second)
	machine.apply(telemetrySessionEvent{FieldName: "Gear", Value: "D", ObservedAt: start, EventID: "gear"})
	machine.apply(telemetrySessionEvent{FieldName: "VehicleSpeed", Value: 40.0, ObservedAt: start.Add(time.Second), EventID: "speed"})
	machine.apply(telemetrySessionEvent{FieldName: "Location", Value: map[string]any{"latitude": 30.0, "longitude": 120.0}, ObservedAt: start.Add(2 * time.Second), EventID: "location"})
	if machine.drive == nil || len(machine.drive.Route) != 1 || machine.drive.Route[0].Speed == nil || *machine.drive.Route[0].Speed != 40.0 {
		t.Fatalf("drive route=%#v, want fresh speed", machine.drive)
	}
}
