package main

import (
	"context"
	"testing"
	"time"
)

func TestShouldAutoConfigureOnlyUntouchedPairingState(t *testing.T) {
	if !shouldAutoConfigurePairing(telemetryPairingResponse{Status: "pairing_required"}) {
		t.Fatal("fresh pairing-required state should get one automatic configure attempt")
	}
	if shouldAutoConfigurePairing(telemetryPairingResponse{Status: "pairing_required", UpdatedAt: "2026-09-07T00:00:00Z"}) {
		t.Fatal("persisted missing-key/error state must not be hammered on every vehicle discovery")
	}
	synced := true
	if shouldAutoConfigurePairing(telemetryPairingResponse{Status: "available", ConfigSynced: &synced}) {
		t.Fatal("synced telemetry must not be reconfigured")
	}
}

func TestShouldRetryAfterAuthorizationForUnsyncedPairingState(t *testing.T) {
	if !shouldRetryAfterAuthorization(telemetryPairingResponse{Status: "telemetry_error", UpdatedAt: "2026-09-07T00:00:00Z"}) {
		t.Fatal("a reauthorized account must retry a persisted telemetry error")
	}
	if !shouldRetryAfterAuthorization(telemetryPairingResponse{Status: "pairing_required", UpdatedAt: "2026-09-07T00:00:00Z"}) {
		t.Fatal("a reauthorized account must retry a persisted pairing state")
	}
	if !shouldRetryAfterAuthorization(telemetryPairingResponse{Status: "permission_required", UpdatedAt: "2026-09-07T00:00:00Z"}) {
		t.Fatal("a reauthorized account must retry a persisted permission state")
	}
	synced := true
	if shouldRetryAfterAuthorization(telemetryPairingResponse{Status: "available", ConfigSynced: &synced}) {
		t.Fatal("a verified telemetry configuration must not be retried")
	}
	if shouldRetryAfterAuthorization(telemetryPairingResponse{Status: "billing_blocked"}) {
		t.Fatal("a billing block is not fixed by user reauthorization")
	}
}

func TestRetryAfterAuthorizationConfiguresPersistedTelemetryError(t *testing.T) {
	proxy := newTestHTTPServer(t, func(method, _ string, _ []byte) (int, string) {
		if method == "POST" {
			return 200, `{"response":{"updated_vehicles":1,"skipped_vehicles":{"missing_key":[]}}}`
		}
		return 200, `{"response":{"synced":true,"config":{"hostname":"fleet.example.com"}}}`
	})
	defer proxy.Close()

	service := newTelemetryServiceForTest("partner.example.com")
	service.commandProxyURL = proxy.URL
	ref := telemetryRefWithVIN(service, "user-a", 1, "5YJ3E1EA7KF123456")
	if err := service.memory.registerVehicle(ref); err != nil {
		t.Fatal(err)
	}
	service.memory.setPairing(ref, telemetryPairing{
		Status:    "telemetry_error",
		UpdatedAt: time.Now().UTC().Add(-time.Minute),
	})

	service.retryAfterAuthorization(ref.UserID)
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		pairing, err := service.pairing(context.Background(), ref.UserID, ref.VehicleID)
		if err == nil && pairing.ConfigSynced != nil && *pairing.ConfigSynced {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	pairing, err := service.pairing(context.Background(), ref.UserID, ref.VehicleID)
	t.Fatalf("persisted telemetry error was not retried: %#v, err=%v", pairing, err)
}

func TestAuthorizationRetryFollowsAnAlreadyRunningConfigureGate(t *testing.T) {
	proxy := newTestHTTPServer(t, func(method, _ string, _ []byte) (int, string) {
		if method == "POST" {
			return 200, `{"response":{"updated_vehicles":1,"skipped_vehicles":{"missing_key":[]}}}`
		}
		return 200, `{"response":{"synced":true,"config":{"hostname":"fleet.example.com"}}}`
	})
	defer proxy.Close()

	service := newTelemetryServiceForTest("partner.example.com")
	service.commandProxyURL = proxy.URL
	ref := telemetryRefWithVIN(service, "user-a", 2, "5YJ3E1EA7KF123456")
	if err := service.memory.registerVehicle(ref); err != nil {
		t.Fatal(err)
	}
	service.memory.setPairing(ref, telemetryPairing{Status: "telemetry_error", UpdatedAt: time.Now().UTC().Add(-time.Minute)})
	key := telemetryAutoConfigureKey{userID: ref.UserID, vehicleID: ref.VehicleID}
	service.autoConfigure.Store(key, struct{}{})
	service.maybeAutoConfigureAfterAuthorization(ref.UserID, ref.VehicleID)
	time.Sleep(75 * time.Millisecond)
	service.autoConfigure.Delete(key)

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		pairing, err := service.pairing(context.Background(), ref.UserID, ref.VehicleID)
		if err == nil && pairing.ConfigSynced != nil && *pairing.ConfigSynced {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	pairing, err := service.pairing(context.Background(), ref.UserID, ref.VehicleID)
	t.Fatalf("authorization retry was lost behind an existing gate: %#v, err=%v", pairing, err)
}
