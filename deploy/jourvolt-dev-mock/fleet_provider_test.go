package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

type testAccessTokens struct {
	calls []string
}

func (t *testAccessTokens) accessToken(_ context.Context, _ string, rejected string) (string, error) {
	t.calls = append(t.calls, rejected)
	if rejected == "old-token" {
		return "new-token", nil
	}
	return "old-token", nil
}

func TestFleetGetRefreshesAndRetriesOnceAfter401(t *testing.T) {
	tokens := &testAccessTokens{}
	requests := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests++
		if got := r.Header.Get("Content-Type"); got != "application/json" {
			t.Errorf("Content-Type = %q, want application/json", got)
		}
		if r.Header.Get("Authorization") == "Bearer old-token" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"response": []any{}})
	}))
	defer server.Close()

	provider := &fleetProvider{tokens: tokens, client: server.Client(), baseURL: server.URL}
	var payload teslaVehicleListEnvelope
	if err := provider.get(context.Background(), "user-1", "/api/1/vehicles", &payload); err != nil {
		t.Fatal(err)
	}
	if requests != 2 {
		t.Fatalf("Fleet requests = %d, want 2", requests)
	}
	if len(tokens.calls) != 2 || tokens.calls[0] != "" || tokens.calls[1] != "old-token" {
		t.Fatalf("token calls = %#v", tokens.calls)
	}
}

func TestFleetGetMapsRateLimit(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusTooManyRequests)
	}))
	defer server.Close()
	provider := &fleetProvider{tokens: &testAccessTokens{}, client: server.Client(), baseURL: server.URL}
	var payload teslaVehicleListEnvelope
	if err := provider.get(context.Background(), "user-1", "/api/1/vehicles", &payload); !errors.Is(err, errTeslaRateLimited) {
		t.Fatalf("Fleet error = %v, want rate limited", err)
	}
}

func TestTeslaMilesAreConvertedForAndroidKilometres(t *testing.T) {
	miles := 100.0
	if got := *milesPointerToKilometres(&miles); got != 160.9344 {
		t.Fatalf("kilometres = %v", got)
	}
	if got := *milesPerHourPointerToKilometres(&miles); got != 161 {
		t.Fatalf("km/h = %v", got)
	}
}

func TestFleetVehicleDataMapsToReadOnlyAndroidStatus(t *testing.T) {
	charging := "Charging"
	shift := "D"
	locked := true
	sentry := false
	climate := true
	preconditioning := false
	batteryLevel := 80
	chargePortOpen := true
	odometer := 100.0
	estimatedRange := 200.0
	ratedRange := 210.0
	idealRange := 220.0
	energyAdded := 12.5
	insideTemp := 21.5
	outsideTemp := 18.0
	speed := 50.0
	power := 120
	heading := 90
	centerDisplay := 2
	frontDoor := 0
	rearDoor := 1

	status := mapTeslaVehicleStatus(teslaVehicleData{
		ChargeState: teslaChargeState{
			BatteryLevel: &batteryLevel, BatteryRange: &ratedRange,
			EstBatteryRange: &estimatedRange, IdealBatteryRange: &idealRange,
			ChargingState: &charging, ChargeEnergyAdded: &energyAdded,
			ChargeLimitSOC: &batteryLevel, ChargePortDoorOpen: &chargePortOpen,
		},
		ClimateState: teslaClimateState{
			IsClimateOn: &climate, InsideTemp: &insideTemp,
			OutsideTemp: &outsideTemp, IsPreconditioning: &preconditioning,
		},
		DriveState: teslaDriveState{
			ShiftState: &shift, Power: &power, Speed: &speed, Heading: &heading,
		},
		VehicleState: teslaVehicleState{
			Locked: &locked, SentryMode: &sentry, Odometer: &odometer,
			CenterDisplayState: &centerDisplay, DriverFrontDoor: &frontDoor,
			DriverRearDoor: &rearDoor,
		},
	}, "Test Model 3", "charging")

	if status.DisplayName != "Test Model 3" || status.State != "charging" {
		t.Fatalf("identity = %#v", status)
	}
	if status.BatteryLevel == nil || *status.BatteryLevel != 80 {
		t.Fatalf("battery = %#v", status.BatteryLevel)
	}
	if status.Odometer == nil || *status.Odometer != 160.9344 {
		t.Fatalf("odometer = %#v", status.Odometer)
	}
	if status.EstimatedBatteryRange == nil || *status.EstimatedBatteryRange != 321.8688 {
		t.Fatalf("estimated range = %#v", status.EstimatedBatteryRange)
	}
	if status.Speed == nil || *status.Speed != 80 {
		t.Fatalf("speed = %#v", status.Speed)
	}
	if status.PluggedIn == nil || !*status.PluggedIn || status.DoorsOpen == nil || !*status.DoorsOpen {
		t.Fatalf("charging/door state = plugged %v doors %v", status.PluggedIn, status.DoorsOpen)
	}
	if status.Source != "fleet_api" || status.Healthy == nil || !*status.Healthy {
		t.Fatalf("evidence = source %q healthy %v", status.Source, status.Healthy)
	}
	if status.CenterDisplayState == nil || *status.CenterDisplayState != "2" {
		t.Fatalf("center display = %#v", status.CenterDisplayState)
	}
	if status.ObservedAt.IsZero() {
		t.Fatal("observed time must be populated")
	}
}
