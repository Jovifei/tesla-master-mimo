package main

import "testing"

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
