package main

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestAutomaticRecoveryIsRateBoundedAndNeverBypassesTrust(t *testing.T) {
	now := time.Date(2026, 9, 10, 0, 0, 0, 0, time.UTC)
	old := now.Add(-2 * time.Minute).Format(time.RFC3339)
	for _, class := range []string{"", "telemetry_error", "command_transport", "ca_unavailable", "rate_limited"} {
		p := telemetryPairingResponse{Status: "telemetry_error", ErrorClass: class, UpdatedAt: old}
		if !shouldAutoConfigurePairingAt(p, now) {
			t.Fatalf("no recovery for %q", class)
		}
		p.UpdatedAt = now.Add(-30 * time.Second).Format(time.RFC3339)
		if shouldAutoConfigurePairingAt(p, now) {
			t.Fatalf("hot loop for %q", class)
		}
	}
	for _, class := range []string{"unsupported_hardware", "unsupported_firmware", "max_configs", "configuration_invalid", "vehicle_identity_unavailable"} {
		if shouldAutoConfigurePairingAt(telemetryPairingResponse{Status: "telemetry_error", ErrorClass: class, UpdatedAt: old}, now) {
			t.Fatalf("permanent failure retried: %s", class)
		}
	}
	for _, status := range []string{"pairing_required", "permission_required", "billing_blocked", "waiting_vehicle", "configuring"} {
		if shouldAutoConfigurePairingAt(telemetryPairingResponse{Status: status, UpdatedAt: old}, now) {
			t.Fatalf("trust/pending boundary bypassed: %s", status)
		}
	}
	if shouldAutoConfigurePairingAt(telemetryPairingResponse{Status: "telemetry_error", UpdatedAt: "invalid"}, now) {
		t.Fatal("invalid retry clock must fail closed")
	}
}

func TestConfigureRejectsConcurrentManualAndAutomaticRequests(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	service.commandProxyURL = "https://proxy.example.com"
	key := telemetryAutoConfigureKey{userID: "user-a", vehicleID: 1}
	service.configureRequests.Store(key, struct{}{})
	err := service.configure(context.Background(), key.userID, key.vehicleID)
	if !errors.Is(err, errTelemetryConfigInProgress) {
		t.Fatalf("expected in-flight result, got %v", err)
	}
}
