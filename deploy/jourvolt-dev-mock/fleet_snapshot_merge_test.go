package main

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestFleetLocationSuccessPreservesCoreSnapshot(t *testing.T) {
	var paths []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.URL.RawQuery)
		if r.URL.Query().Get("endpoints") == "location_data" {
			_, _ = w.Write([]byte(`{"response":{"drive_state":{"latitude":30,"longitude":120,"heading":0}}}`))
			return
		}
		_, _ = w.Write([]byte(`{"response":{"display_name":"Test vehicle","charge_state":{"battery_level":0,"battery_range":150},"vehicle_config":{"car_type":"modely","exterior_color":"Blue"},"vehicle_state":{"odometer":1234,"locked":false},"drive_state":{"speed":0,"power":0}}}`))
	}))
	defer server.Close()
	provider := &fleetProvider{tokens: &testAccessTokens{}, client: server.Client(), baseURL: server.URL}
	payload, needsPermission, err := provider.vehicleData(context.Background(), "user-1", "test-vehicle")
	if err != nil || needsPermission {
		t.Fatalf("result: %v %v", needsPermission, err)
	}
	got := payload.Response
	if len(paths) != 2 || paths[0] != "" || got.VehicleConfig.CarType != "modely" || got.ChargeState.BatteryRange == nil || got.VehicleState.Odometer == nil {
		t.Fatalf("incomplete core snapshot: %+v requests=%v", got, paths)
	}
	if got.ChargeState.BatteryLevel == nil || *got.ChargeState.BatteryLevel != 0 || got.DriveState.Speed == nil || *got.DriveState.Speed != 0 || got.VehicleState.Locked == nil || *got.VehicleState.Locked {
		t.Fatal("observed zeros/false were erased by location response")
	}
	if got.DriveState.Latitude == nil || *got.DriveState.Latitude != 30 || got.DriveState.Heading == nil || *got.DriveState.Heading != 0 {
		t.Fatal("location was not merged")
	}
}

func TestFleetLocationTransportFailureKeepsCoreWithoutPermissionError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("endpoints") == "location_data" {
			w.WriteHeader(503)
			return
		}
		_, _ = w.Write([]byte(`{"response":{"charge_state":{"battery_level":71}}}`))
	}))
	defer server.Close()
	provider := &fleetProvider{tokens: &testAccessTokens{}, client: server.Client(), baseURL: server.URL}
	payload, permission, err := provider.vehicleData(context.Background(), "user-1", "test-vehicle")
	if err != nil || permission || payload.Response.ChargeState.BatteryLevel == nil {
		t.Fatalf("core lost: %+v %v %v", payload, permission, err)
	}
}

func TestFleetLocationNeverMasksCoreAuthorizationFailure(t *testing.T) {
	requests := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests++
		if r.URL.Query().Get("endpoints") == "location_data" {
			_, _ = w.Write([]byte(`{"response":{"drive_state":{"latitude":30,"longitude":120}}}`))
			return
		}
		w.WriteHeader(http.StatusForbidden)
	}))
	defer server.Close()
	provider := &fleetProvider{tokens: &testAccessTokens{}, client: server.Client(), baseURL: server.URL}
	_, _, err := provider.vehicleData(context.Background(), "user-1", "test-vehicle")
	if !errors.Is(err, errTeslaReauthorization) || requests != 1 {
		t.Fatalf("core rejection masked: %v calls=%d", err, requests)
	}
}

func TestFleetInvalidLocationDoesNotEraseObservedCoreCoordinates(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("endpoints") == "location_data" {
			_, _ = w.Write([]byte(`{"response":{"drive_state":{"latitude":0,"longitude":0}}}`))
			return
		}
		_, _ = w.Write([]byte(`{"response":{"drive_state":{"latitude":1,"longitude":2}}}`))
	}))
	defer server.Close()
	provider := &fleetProvider{tokens: &testAccessTokens{}, client: server.Client(), baseURL: server.URL}
	payload, _, err := provider.vehicleData(context.Background(), "user-1", "test-vehicle")
	if err != nil || payload.Response.DriveState.Latitude == nil || *payload.Response.DriveState.Latitude != 1 {
		t.Fatalf("valid location erased: %v", err)
	}
}
