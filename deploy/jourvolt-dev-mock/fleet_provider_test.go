package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
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

func TestSanitizeFleetLogDoesNotExposeResponseSecrets(t *testing.T) {
	vin := "5YJ3E1EA7KF123456"
	token := "bearer-secret-token"
	coordinates := "31.2304,121.4737"
	got := sanitizeFleetLog("GET", "/api/1/vehicles/"+vin+"/vehicle_data", http.StatusPaymentRequired, []byte(`{"error":"billing_blocked","authorization":"Bearer bearer-secret-token","vin":"5YJ3E1EA7KF123456","latitude":31.2304,"longitude":121.4737}`))
	for _, secret := range []string{vin, token, coordinates} {
		if strings.Contains(got, secret) {
			t.Fatalf("sanitized Fleet log contains sensitive value %q: %q", secret, got)
		}
	}
	if !strings.Contains(got, "endpoint=vehicle_data") || !strings.Contains(got, "error_class=billing_blocked") {
		t.Fatalf("sanitized Fleet log lacks safe endpoint/class: %q", got)
	}
}

func TestFleetBillingSubstringDoesNotClassifyAsBilling(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte(`{"message":"billing is temporarily unavailable"}`))
	}))
	defer server.Close()

	provider := &fleetProvider{tokens: &testAccessTokens{}, client: server.Client(), baseURL: server.URL}
	var payload teslaVehicleDataEnvelope
	err := provider.get(context.Background(), "user-1", "/api/1/vehicles", &payload)
	if errors.Is(err, errTeslaBillingBlocked) {
		t.Fatalf("unstructured billing text classified as billing: %v", err)
	}
	status, messageKey, action := readinessError(err)
	if status != "telemetry_error" || messageKey != "telemetry_error" || action != "retry_later" {
		t.Fatalf("unstructured billing readiness = %q, %q, %q", status, messageKey, action)
	}
}

func TestFleetBillingResponsePreservesTypedClassificationWithoutBody(t *testing.T) {
	vin := "5YJ3E1EA7KF123456"
	token := "bearer-secret-token"
	coordinates := "31.2304,121.4737"
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusPaymentRequired)
		_, _ = w.Write([]byte(`{"error":"billing_blocked","authorization":"Bearer bearer-secret-token","vin":"5YJ3E1EA7KF123456","latitude":31.2304,"longitude":121.4737}`))
	}))
	defer server.Close()

	provider := &fleetProvider{tokens: &testAccessTokens{}, client: server.Client(), baseURL: server.URL}
	var payload teslaVehicleDataEnvelope
	err := provider.get(context.Background(), "user-1", "/api/1/vehicles/"+vin+"/vehicle_data", &payload)
	var fleetErr *fleetAPIError
	if !errors.As(err, &fleetErr) || fleetErr.class != "billing_blocked" || fleetErr.statusCode != http.StatusPaymentRequired {
		t.Fatalf("Fleet billing error = %#v, want typed billing classification", err)
	}
	status, messageKey, action := readinessError(err)
	if status != "billing_blocked" || messageKey != "billing_blocked" || action != "resolve_billing" {
		t.Fatalf("billing readiness = %q, %q, %q for %v", status, messageKey, action, err)
	}
	for _, secret := range []string{vin, token, coordinates} {
		if strings.Contains(err.Error(), secret) {
			t.Fatalf("billing error contains sensitive value %q: %v", secret, err)
		}
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

func TestFleetVehicleDataPreservesObservedLocationAndTPMSPointers(t *testing.T) {
	latitude, longitude, pressure := 0.0, 0.0, 0.0
	softWarning := false

	status := mapTeslaVehicleStatus(teslaVehicleData{
		DriveState: teslaDriveState{Latitude: &latitude, Longitude: &longitude},
		VehicleState: teslaVehicleState{
			TPMSPressureFL:    &pressure,
			TPMSSoftWarningFL: &softWarning,
		},
	}, "Observed Tesla", "online")

	if status.Latitude == nil || *status.Latitude != 0 || status.Longitude == nil || *status.Longitude != 0 {
		t.Fatalf("observed zero location was not preserved: latitude=%#v longitude=%#v", status.Latitude, status.Longitude)
	}
	if status.TPMSPressureFL == nil || *status.TPMSPressureFL != 0 {
		t.Fatalf("observed zero TPMS pressure was not preserved: %#v", status.TPMSPressureFL)
	}
	if status.TPMSSoftWarningFL == nil || *status.TPMSSoftWarningFL {
		t.Fatalf("observed false TPMS warning was not preserved: %#v", status.TPMSSoftWarningFL)
	}
}

func TestFleetVehicleDataKeepsMissingLocationAndTPMSUnavailable(t *testing.T) {
	status := mapTeslaVehicleStatus(teslaVehicleData{}, "Offline Tesla", "offline")

	if status.Latitude != nil || status.Longitude != nil {
		t.Fatalf("missing location must remain nil: latitude=%#v longitude=%#v", status.Latitude, status.Longitude)
	}
	if status.TPMSPressureFL != nil || status.TPMSPressureFR != nil || status.TPMSPressureRL != nil || status.TPMSPressureRR != nil {
		t.Fatalf("missing TPMS pressure must remain nil: %#v", status)
	}
	if status.TPMSSoftWarningFL != nil || status.TPMSSoftWarningFR != nil || status.TPMSSoftWarningRL != nil || status.TPMSSoftWarningRR != nil {
		t.Fatalf("missing TPMS warning must remain nil: %#v", status)
	}
}
