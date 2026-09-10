package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestFleetVehicleDataFallsBackWhenOnlyLocationAuthorizationIsMissing(t *testing.T) {
	requests := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests++
		if r.URL.Query().Get("endpoints") == "location_data" {
			w.WriteHeader(http.StatusForbidden)
			_, _ = w.Write([]byte(`{"error":"insufficient_scope"}`))
			return
		}
		battery := 71
		_ = json.NewEncoder(w).Encode(map[string]any{"response": map[string]any{
			"charge_state": map[string]any{"battery_level": battery},
		}})
	}))
	defer server.Close()

	provider := &fleetProvider{tokens: &testAccessTokens{}, client: server.Client(), baseURL: server.URL}
	payload, locationPermissionRequired, err := provider.vehicleData(context.Background(), "user-1", "vin")
	if err != nil {
		t.Fatal(err)
	}
	if !locationPermissionRequired {
		t.Fatal("location permission should be marked missing after the location-only 403 fallback")
	}
	if payload.Response.ChargeState.BatteryLevel == nil || *payload.Response.ChargeState.BatteryLevel != 71 {
		t.Fatalf("core vehicle data was not preserved: %#v", payload.Response.ChargeState.BatteryLevel)
	}
	if requests != 2 {
		t.Fatalf("requests = %d, want location request plus one core fallback", requests)
	}
}

func TestFleetVehicleDataStillRequiresReauthorizationWhenCoreDataIsRejected(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusForbidden)
	}))
	defer server.Close()

	provider := &fleetProvider{tokens: &testAccessTokens{}, client: server.Client(), baseURL: server.URL}
	_, _, err := provider.vehicleData(context.Background(), "user-1", "vin")
	if !errors.Is(err, errTeslaReauthorization) {
		t.Fatalf("error = %v, want reauthorization", err)
	}
}
