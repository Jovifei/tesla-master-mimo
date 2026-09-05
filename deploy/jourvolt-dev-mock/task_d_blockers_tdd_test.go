package main

import (
	"bytes"
	"errors"
	"log"
	"os"
	"strings"
	"testing"
	"time"
)

func TestTaskDOfficialFleetTelemetryFieldSetIsExact(t *testing.T) {
	want := map[string]struct{}{
		"VehicleSpeed": {}, "Location": {}, "GpsHeading": {}, "Soc": {}, "Odometer": {}, "EstBatteryRange": {},
		"DoorState": {}, "Locked": {}, "DetailedChargeState": {}, "Gear": {}, "InsideTemp": {}, "OutsideTemp": {},
		"TpmsPressureFl": {}, "TpmsPressureFr": {}, "TpmsPressureRl": {}, "TpmsPressureRr": {},
		"TpmsHardWarnings": {}, "TpmsSoftWarnings": {},
	}
	if len(telemetryFieldSpecs) != len(want) {
		t.Fatalf("telemetry field count = %d, want %d: %#v", len(telemetryFieldSpecs), len(want), telemetryFieldSpecs)
	}
	for field := range want {
		if _, ok := telemetryFieldSpec(field); !ok {
			t.Fatalf("missing official Fleet Telemetry field %q", field)
		}
	}
	for _, forbidden := range []string{"Heading", "DoorsOpen", "WindowsOpen", "TPMSPressureFL", "ChargingState", "Time"} {
		if _, ok := telemetryFieldSpec(forbidden); ok {
			t.Fatalf("unsupported or non-canonical field %q is allowlisted", forbidden)
		}
	}
}

func TestTaskDTimeIsNotTreatedAsEpochAndReceiveTimeStaysAuthoritative(t *testing.T) {
	if _, ok := telemetryFieldSpec("Time"); ok {
		t.Fatal("Tesla Time must not be configured or parsed as an epoch timestamp")
	}
}

func TestTaskDQoS1RedeliveryAndRestartDoNotAdvanceLatestOrCompleteTwice(t *testing.T) {
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 7, VINHash: "vin-hash"}
	start := time.Date(2026, time.August, 30, 2, 0, 0, 0, time.UTC)
	store := newTelemetryMemoryStore()
	if err := store.registerVehicle(ref); err != nil {
		t.Fatal(err)
	}

	if accepted, err := store.ingest(telemetryRecord{VINHash: ref.VINHash, FieldName: "DetailedChargeState", Value: "Charging", ObservedAt: start, EventID: "delivery-1"}, defaultDriveStopDebounce); err != nil || accepted != 1 {
		t.Fatalf("first delivery accepted=%d err=%v", accepted, err)
	}
	before, ok := store.latestSnapshot(ref.UserID, ref.VehicleID)
	if !ok {
		t.Fatal("initial latest snapshot missing")
	}
	if accepted, err := store.ingest(telemetryRecord{VINHash: ref.VINHash, FieldName: "DetailedChargeState", Value: "Charging", ObservedAt: start.Add(time.Minute), EventID: "qos1-redelivery"}, defaultDriveStopDebounce); err != nil || accepted != 0 {
		t.Fatalf("identical QoS1 redelivery accepted=%d err=%v", accepted, err)
	}
	after, _ := store.latestSnapshot(ref.UserID, ref.VehicleID)
	if got := after.FieldObservedAt["DetailedChargeState"]; !got.Equal(before.FieldObservedAt["DetailedChargeState"]) {
		t.Fatalf("identical redelivery advanced observedAt: got %s want %s", got, before.FieldObservedAt["DetailedChargeState"])
	}

	key := telemetryKey{UserID: ref.UserID, VehicleID: ref.VehicleID}
	persistedLatest := store.latest[key]
	persistedMachine := store.machines[key].snapshot()
	restarted := newTelemetryMemoryStore()
	if err := restarted.registerVehicle(ref); err != nil {
		t.Fatal(err)
	}
	restarted.latest[key] = persistedLatest
	restarted.machines[key] = newTelemetrySessionMachineFromSnapshot(defaultDriveStopDebounce, persistedMachine)
	if accepted, err := restarted.ingest(telemetryRecord{VINHash: ref.VINHash, FieldName: "DetailedChargeState", Value: "Complete", ObservedAt: start.Add(2 * time.Minute), EventID: "completion-1"}, defaultDriveStopDebounce); err != nil || accepted != 1 {
		t.Fatalf("completion accepted=%d err=%v", accepted, err)
	}
	persistedMachine = restarted.machines[key].snapshot()
	persistedLatest = restarted.latest[key]
	redeliveredAfterRestart := newTelemetryMemoryStore()
	if err := redeliveredAfterRestart.registerVehicle(ref); err != nil {
		t.Fatal(err)
	}
	redeliveredAfterRestart.latest[key] = persistedLatest
	redeliveredAfterRestart.machines[key] = newTelemetrySessionMachineFromSnapshot(defaultDriveStopDebounce, persistedMachine)
	redeliveredAfterRestart.completed[key] = restarted.completed[key]
	if accepted, err := redeliveredAfterRestart.ingest(telemetryRecord{VINHash: ref.VINHash, FieldName: "DetailedChargeState", Value: "Complete", ObservedAt: start.Add(3 * time.Minute), EventID: "completion-qos1-redelivery"}, defaultDriveStopDebounce); err != nil || accepted != 0 {
		t.Fatalf("completion redelivery accepted=%d err=%v", accepted, err)
	}
	completed := redeliveredAfterRestart.sessions(ref.UserID, ref.VehicleID, "charge")
	if len(completed) != 1 || completed[0].ID != sessionID("charge", start) {
		t.Fatalf("completion key/session = %#v", completed)
	}
}

func TestTaskDAPIContainersMountConfiguredCertificatePathReadOnly(t *testing.T) {
	for _, composeFile := range []string{"docker-compose.yml", "docker-compose.pilot.ecs.yml"} {
		data, err := os.ReadFile(composeFile)
		if err != nil {
			t.Fatal(err)
		}
		text := string(data)
		apiStart := strings.Index(text, "  jourvolt-dev-api:")
		apiEnd := strings.Index(text[apiStart+1:], "\n  jourvolt-mqtt:")
		if apiStart < 0 || apiEnd < 0 {
			t.Fatalf("%s does not contain the expected API service boundary", composeFile)
		}
		api := text[apiStart : apiStart+1+apiEnd]
		if !strings.Contains(api, "TELEMETRY_CA_CHAIN_FILE") || strings.Contains(api, "TELEMETRY_CERT_DIR") {
			t.Fatalf("%s must mount only the telemetry CA chain read-only into the API", composeFile)
		}
		if !strings.Contains(api, "TELEMETRY_CA_CERT_PATH: /run/secrets/fleet/ca.pem") {
			t.Fatalf("%s does not expose the fixed CA path to the API", composeFile)
		}
	}
}

func TestTaskDOAuthIDTokenFailureLogCapturesOnlySafeClass(t *testing.T) {
	var output bytes.Buffer
	previous := log.Writer()
	log.SetOutput(&output)
	t.Cleanup(func() { log.SetOutput(previous) })

	unsafeIssuer := "https://issuer.example/5YJ3E1EA7KF123456?lat=31.2304&lon=121.4737"
	unsafeToken := "eyJhbGciOiJIUzI1NiJ9.eyJ2aW4iOiI1WUozRTFFQTdLRjEyMzQ1NiJ9.signature"
	logTeslaIDTokenVerificationFailure(unsafeIssuer, unsafeToken, errors.New("verifier response contains secret-token and coordinates 31.2304,121.4737"))
	got := output.String()
	for _, raw := range []string{unsafeIssuer, unsafeToken, "5YJ3E1EA7KF123456", "31.2304", "121.4737", "secret-token"} {
		if strings.Contains(got, raw) {
			t.Fatalf("ID-token failure log leaked %q: %s", raw, got)
		}
	}
	if !strings.Contains(got, "tesla oauth id_token verify failed class=id_token_invalid issuer=unrecognized") {
		t.Fatalf("safe ID-token failure log = %q", got)
	}
}
