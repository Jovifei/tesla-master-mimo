package main

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestVehicleKeyRecoveryRequiresTheCorrectVehicleAndThrottlesChecks(t *testing.T) {
	for _, tc := range []struct {
		name, body string
		confirmed  bool
	}{
		{"correct vehicle", `{"response":{"key_paired_vins":["5YJ3E1EA7KF123456"]}}`, true},
		{"another vehicle", `{"response":{"key_paired_vins":["5YJ3E1EA7KF654321"]}}`, false},
		{"unknown", `{"response":{}}`, false},
		{"unpaired", `{"response":{"key_paired_vins":[]}}`, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls++
				if r.Method != http.MethodPost || r.URL.Path != "/api/1/vehicles/fleet_status" {
					t.Error("unexpected provider request")
				}
				fmt.Fprint(w, tc.body)
			}))
			defer server.Close()
			s := newTelemetryServiceForTest("partner.example.com")
			s.fleetAPIBase = server.URL
			ref := telemetryRefWithVIN(s, "user-a", 1, "5YJ3E1EA7KF123456")
			s.memory.registerVehicle(ref)
			initial := telemetryPairing{Status: "pairing_required", ConfigSynced: boolPointer(false)}
			s.memory.setPairing(ref, initial)
			result := s.refreshConfirmedVehicleKey(context.Background(), ref.UserID, ref.VehicleID, initial)
			if (result.Status == "key_confirmed") != tc.confirmed {
				t.Fatalf("unexpected state %s", result.Status)
			}
			s.refreshConfirmedVehicleKey(context.Background(), ref.UserID, ref.VehicleID, initial)
			if calls != 1 {
				t.Fatalf("unbounded precondition checks: %d", calls)
			}
			if tc.confirmed && !shouldAutoConfigurePairing(telemetryPairingResponse{Status: result.Status, ConfigSynced: result.ConfigSynced}) {
				t.Fatal("confirmed key must resume setup")
			}
		})
	}
}
