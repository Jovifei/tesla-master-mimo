package main

import (
	"context"
	"testing"
	"time"
)

func TestOnboardingPhaseGrading(t *testing.T) {
	synced := true
	unsynced := false
	cases := []struct {
		name   string
		status string
		synced *bool
		data   bool
		want   string
	}{
		{"unpaired wins over data", "pairing_required", &synced, true, "pairing_required"},
		{"permission", "permission_required", nil, false, "permission_required"},
		{"billing", "billing_blocked", nil, false, "blocked"},
		{"error wins over data", "telemetry_error", &synced, true, "error"},
		{"collecting is ready", "collecting", nil, false, "ready"},
		{"available is ready", "available", nil, false, "ready"},
		{"data arrived is ready", "key_confirmed", &synced, true, "ready"},
		{"synced without data awaits first event", "key_confirmed", &synced, false, "awaiting_first_event"},
		{"unsynced is configuring", "key_confirmed", &unsynced, false, "configuring"},
		{"unknown sync state is configuring", "waiting_vehicle", nil, false, "configuring"},
		{"not configured is configuring", "telemetry_not_configured", nil, false, "configuring"},
	}
	for _, tc := range cases {
		if got := onboardingPhase(tc.status, tc.synced, tc.data); got != tc.want {
			t.Fatalf("%s: onboardingPhase(%q) = %q, want %q", tc.name, tc.status, got, tc.want)
		}
	}
}

func TestPairingResponseCarriesGradedPhase(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ctx := context.Background()
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "hash", ProviderVehicleID: "provider-1"}
	if err := service.memory.registerVehicle(ref); err != nil {
		t.Fatal(err)
	}
	synced := true
	service.memory.setPairing(ref, telemetryPairing{Status: "key_confirmed", ConfigSynced: &synced, UpdatedAt: time.Now().UTC()})

	response, err := service.pairing(ctx, "user-a", 1)
	if err != nil {
		t.Fatal(err)
	}
	if response.Phase != "awaiting_first_event" {
		t.Fatalf("phase before first event = %q, want awaiting_first_event (status %q)", response.Phase, response.Status)
	}

	service.memory.putLatest(ref, "Soc", 55.0, time.Now().UTC())
	response, err = service.pairing(ctx, "user-a", 1)
	if err != nil {
		t.Fatal(err)
	}
	if response.Phase != "ready" {
		t.Fatalf("phase after first event = %q, want ready", response.Phase)
	}

	// A vehicle with no pairing record at all stays pairing_required.
	response, err = service.pairing(ctx, "user-a", 2)
	if err != nil {
		t.Fatal(err)
	}
	if response.Phase != "pairing_required" {
		t.Fatalf("phase without pairing record = %q, want pairing_required", response.Phase)
	}
}
