package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestFleetGetClassifiesVehicleUnavailable(t *testing.T) {
	for _, tc := range []struct {
		status int
		want   bool
	}{
		{http.StatusRequestTimeout, true},
		{http.StatusMisdirectedRequest, false},
	} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(tc.status)
		}))
		provider := &fleetProvider{tokens: &testAccessTokens{}, client: server.Client(), baseURL: server.URL}
		var payload teslaVehicleDataEnvelope
		err := provider.get(context.Background(), "user-1", "/api/1/vehicles/VIN/vehicle_data", &payload)
		server.Close()
		if !errors.Is(err, errTeslaUnavailable) {
			t.Fatalf("status %d: error = %v, want errTeslaUnavailable", tc.status, err)
		}
		if got := isVehicleUnavailable(err); got != tc.want {
			t.Fatalf("status %d: isVehicleUnavailable = %v, want %v", tc.status, got, tc.want)
		}
	}
}

func shrinkWakeWindow(t *testing.T, interval, timeout time.Duration) {
	t.Helper()
	oldInterval, oldTimeout := wakePollInterval, wakePollTimeout
	wakePollInterval, wakePollTimeout = interval, timeout
	t.Cleanup(func() { wakePollInterval, wakePollTimeout = oldInterval, oldTimeout })
}

const wakeTestEnvelope = `{"response":{"display_name":"Sleeper","state":"online","charge_state":{},"climate_state":{},"drive_state":{},"gui_settings":{},"vehicle_config":{},"vehicle_state":{}}}`

func TestFleetVehicleDataWakesSleepingVehicle(t *testing.T) {
	shrinkWakeWindow(t, 5*time.Millisecond, 5*time.Second)
	var awake atomic.Bool
	var wakeCalls, dataCalls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/wake_up"):
			wakeCalls.Add(1)
			awake.Store(true)
			_ = json.NewEncoder(w).Encode(map[string]any{"response": map[string]any{"state": "online"}})
		case strings.Contains(r.URL.Path, "/vehicle_data"):
			dataCalls.Add(1)
			if !awake.Load() {
				w.WriteHeader(http.StatusRequestTimeout)
				return
			}
			_ = json.NewEncoder(w).Encode(json.RawMessage(wakeTestEnvelope))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer server.Close()

	provider := &fleetProvider{tokens: &testAccessTokens{}, client: server.Client(), baseURL: server.URL}
	payload, _, err := provider.vehicleData(context.Background(), "user-1", "VIN123")
	if err != nil {
		t.Fatalf("vehicleData after wake: %v", err)
	}
	if payload.Response.DisplayName != "Sleeper" {
		t.Fatalf("display name = %q, want Sleeper", payload.Response.DisplayName)
	}
	if got := wakeCalls.Load(); got != 1 {
		t.Fatalf("wake_up calls = %d, want exactly 1", got)
	}
	if got := dataCalls.Load(); got < 2 {
		t.Fatalf("vehicle_data calls = %d, want the failed attempt plus a retry", got)
	}
}

func TestFleetVehicleDataWakeWindowIsBounded(t *testing.T) {
	shrinkWakeWindow(t, 5*time.Millisecond, 45*time.Millisecond)
	var wakeCalls, dataCalls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/wake_up"):
			wakeCalls.Add(1)
			_ = json.NewEncoder(w).Encode(map[string]any{"response": map[string]any{"state": "asleep"}})
		case strings.Contains(r.URL.Path, "/vehicle_data"):
			dataCalls.Add(1)
			w.WriteHeader(http.StatusRequestTimeout)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer server.Close()

	provider := &fleetProvider{tokens: &testAccessTokens{}, client: server.Client(), baseURL: server.URL}
	start := time.Now()
	_, _, err := provider.vehicleData(context.Background(), "user-1", "VIN123")
	if !errors.Is(err, errTeslaUnavailable) {
		t.Fatalf("vehicleData error = %v, want errTeslaUnavailable after the wake window", err)
	}
	if elapsed := time.Since(start); elapsed > 2*time.Second {
		t.Fatalf("wake window took %v, want it bounded near the configured timeout", elapsed)
	}
	if got := wakeCalls.Load(); got != 1 {
		t.Fatalf("wake_up calls = %d, want exactly 1 (no wake loop)", got)
	}
	if got := dataCalls.Load(); got < 2 || got > 30 {
		t.Fatalf("vehicle_data calls = %d, want a bounded number of polls", got)
	}
}

type unavailableProvider struct{ err error }

func (p unavailableProvider) Vehicles(context.Context, string) ([]vehicle, error) {
	return nil, p.err
}

func (p unavailableProvider) Status(context.Context, string, int) (vehicleStatus, error) {
	return vehicleStatus{}, p.err
}

func TestCurrentVehicleStatusServesLabelledTelemetryCacheWhenVehicleUnavailable(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryRefWithVIN(service, "user-a", 1, "5YJ3E1EA7KF123456")
	if err := service.memory.registerVehicle(ref); err != nil {
		t.Fatal(err)
	}
	observedAt := time.Now().UTC().Add(-3 * time.Hour)
	if _, err := service.memory.ingest(telemetryRecord{
		VINHash: ref.VINHash, FieldName: "Soc", Value: float64(72), ObservedAt: observedAt,
		EventID: "soc-before-sleep",
	}, service.stopDebounceOrDefault()); err != nil {
		t.Fatal(err)
	}
	api := &app{telemetry: service, provider: unavailableProvider{err: errTeslaUnavailable}}
	status, err := api.currentVehicleStatus(context.Background(), "user-a", 1)
	if err != nil {
		t.Fatalf("currentVehicleStatus with a 3h-old snapshot: %v", err)
	}
	if status.Source != "telemetry_cache" {
		t.Fatalf("source = %q, want telemetry_cache (never passed off as live)", status.Source)
	}
	if status.BatteryLevel == nil || *status.BatteryLevel != 72 {
		t.Fatalf("battery level = %v, want 72 from the cached snapshot", status.BatteryLevel)
	}
	if status.ObservedAt.IsZero() || status.ObservedAt.After(observedAt.Add(time.Minute)) {
		t.Fatalf("observed_at = %v, want the snapshot's real timestamp near %v", status.ObservedAt, observedAt)
	}
}

func TestCurrentVehicleStatusRejectsAncientTelemetryCache(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryRefWithVIN(service, "user-a", 1, "5YJ3E1EA7KF123456")
	if err := service.memory.registerVehicle(ref); err != nil {
		t.Fatal(err)
	}
	if _, err := service.memory.ingest(telemetryRecord{
		VINHash: ref.VINHash, FieldName: "Soc", Value: float64(72),
		ObservedAt: time.Now().UTC().Add(-48 * time.Hour), EventID: "soc-ancient",
	}, service.stopDebounceOrDefault()); err != nil {
		t.Fatal(err)
	}
	api := &app{telemetry: service, provider: unavailableProvider{err: errTeslaUnavailable}}
	if _, err := api.currentVehicleStatus(context.Background(), "user-a", 1); !errors.Is(err, errTeslaUnavailable) {
		t.Fatalf("currentVehicleStatus error = %v, want errTeslaUnavailable (48h-old snapshot must not be served)", err)
	}
}

func TestCurrentVehicleStatusDoesNotCacheOverAuthErrors(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryRefWithVIN(service, "user-a", 1, "5YJ3E1EA7KF123456")
	if err := service.memory.registerVehicle(ref); err != nil {
		t.Fatal(err)
	}
	if _, err := service.memory.ingest(telemetryRecord{
		VINHash: ref.VINHash, FieldName: "Soc", Value: float64(72),
		ObservedAt: time.Now().UTC().Add(-3 * time.Hour), EventID: "soc-before-sleep",
	}, service.stopDebounceOrDefault()); err != nil {
		t.Fatal(err)
	}
	api := &app{telemetry: service, provider: unavailableProvider{err: errTeslaReauthorization}}
	if _, err := api.currentVehicleStatus(context.Background(), "user-a", 1); !errors.Is(err, errTeslaReauthorization) {
		t.Fatalf("currentVehicleStatus error = %v, want the reauthorization error to surface", err)
	}
}
