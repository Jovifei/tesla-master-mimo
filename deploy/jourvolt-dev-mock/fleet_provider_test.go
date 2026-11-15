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

func TestFleetVehicleCarriesProviderUID(t *testing.T) {
	got := fleetVehicleFromProvider(
		storedVehicle{ID: 7, Model: "Y"},
		"provider-vehicle-7",
		"Model Y",
		"online",
	)

	if got.VehicleUID != "provider-vehicle-7" {
		t.Fatalf("vehicle UID = %q, want provider-vehicle-7", got.VehicleUID)
	}
	encoded, err := json.Marshal(got)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(encoded), `"vehicle_uid":"provider-vehicle-7"`) {
		t.Fatalf("serialized vehicle lacks vehicle_uid: %s", encoded)
	}
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

func TestFleetVehicleDataMapsExtendedDisplayFields(t *testing.T) {
	windowOpen, windowClosed := 1, 0
	valet := true
	updateStatus, downloadPct, installPct, expectedSec := "downloading", 42, 0, 1800
	limitActive, limitPin := true, false
	limitMPH := 70.0
	mediaState, mediaTitle := "Playing", "Song"
	driverTemp, fan := 22.5, 3
	seatLevel := 2
	steeringHeater := true
	cable, latch := "IEC", "Engaged"
	heater := false
	scheduledStart := int64(1767225600000) // 2026-01-01T00:00:00Z in epoch ms
	scheduledDeparture := int64(1767240000000)
	minutesToFull := 95
	milesAdded := 10.0

	status := mapTeslaVehicleStatus(teslaVehicleData{
		ChargeState: teslaChargeState{
			ConnChargeCable: &cable, ChargePortLatch: &latch, BatteryHeaterOn: &heater,
			ScheduledChargingStart: &scheduledStart, ScheduledDeparture: &scheduledDeparture,
			MinutesToFullCharge: &minutesToFull, ChargeMilesAddedRated: &milesAdded,
		},
		ClimateState: teslaClimateState{
			DriverTempSetting: &driverTemp, FanStatus: &fan,
			SeatHeaterLeft: &seatLevel, SteeringWheelHeater: &steeringHeater,
		},
		VehicleState: teslaVehicleState{
			FrontLeftWindow: &windowClosed, FrontRightWindow: &windowOpen,
			RearLeftWindow: &windowClosed, RearRightWindow: &windowClosed,
			ValetMode: &valet,
			SoftwareUpdate: &teslaSoftwareUpd{
				Status: &updateStatus, DownloadPercent: &downloadPct,
				InstallPercent: &installPct, ExpectedDurationSec: &expectedSec,
			},
			SpeedLimitMode: &teslaSpeedLimit{
				Active: &limitActive, CurrentLimitMPH: &limitMPH, PinCodeRequired: &limitPin,
			},
			MediaInfo: &teslaMediaInfo{MediaState: &mediaState, NowPlayingTitle: &mediaTitle},
		},
	}, "Extended Tesla", "online")

	if status.WindowsOpen == nil || !*status.WindowsOpen {
		t.Fatalf("windows_open = %#v, want true when one window is open", status.WindowsOpen)
	}
	if status.ValetMode == nil || !*status.ValetMode {
		t.Fatalf("valet_mode = %#v", status.ValetMode)
	}
	if status.SoftwareUpdateStatus == nil || *status.SoftwareUpdateStatus != "downloading" ||
		status.SoftwareDownloadPercent == nil || *status.SoftwareDownloadPercent != 42 {
		t.Fatalf("software update = status %#v download %#v", status.SoftwareUpdateStatus, status.SoftwareDownloadPercent)
	}
	if status.SpeedLimitCurrentKMH == nil || *status.SpeedLimitCurrentKMH != 113 {
		t.Fatalf("speed limit = %#v, want 113 km/h for 70 mph", status.SpeedLimitCurrentKMH)
	}
	if status.MediaTitle == nil || *status.MediaTitle != "Song" || status.MediaArtist != nil {
		t.Fatalf("media = title %#v artist %#v", status.MediaTitle, status.MediaArtist)
	}
	if status.SeatHeaterLeft == nil || *status.SeatHeaterLeft != 2 ||
		status.SteeringWheelHeater == nil || !*status.SteeringWheelHeater {
		t.Fatalf("climate = seat %#v wheel %#v", status.SeatHeaterLeft, status.SteeringWheelHeater)
	}
	if status.ScheduledChargingStartTime == nil ||
		status.ScheduledChargingStartTime.Format("2006-01-02T15:04:05Z") != "2026-01-01T00:00:00Z" {
		t.Fatalf("scheduled start = %#v", status.ScheduledChargingStartTime)
	}
	if status.ScheduledDepartureTime == nil || status.MinutesToFullCharge == nil || *status.MinutesToFullCharge != 95 {
		t.Fatalf("schedule/minutes = %#v %#v", status.ScheduledDepartureTime, status.MinutesToFullCharge)
	}
	if status.ChargeRangeAddedRated == nil || *status.ChargeRangeAddedRated != 16.09344 {
		t.Fatalf("charge range added = %#v, want km conversion of 10 mi", status.ChargeRangeAddedRated)
	}
	if status.ConnChargeCable == nil || *status.ConnChargeCable != "IEC" || status.BatteryHeaterOn == nil || *status.BatteryHeaterOn {
		t.Fatalf("charge detail = cable %#v heater %#v", status.ConnChargeCable, status.BatteryHeaterOn)
	}
}

func TestFleetVehicleDataExtendedFieldsStayNilWhenAbsent(t *testing.T) {
	status := mapTeslaVehicleStatus(teslaVehicleData{}, "Offline Tesla", "offline")

	if status.WindowsOpen != nil || status.ValetMode != nil || status.MediaTitle != nil ||
		status.SoftwareUpdateStatus != nil || status.SpeedLimitActive != nil {
		t.Fatalf("absent vehicle fields must stay nil: %#v", status)
	}
	if status.DriverTempSetting != nil || status.SeatHeaterLeft != nil || status.SteeringWheelHeater != nil {
		t.Fatalf("absent climate fields must stay nil: %#v", status)
	}
	if status.ScheduledChargingStartTime != nil || status.ScheduledDepartureTime != nil ||
		status.ConnChargeCable != nil || status.MinutesToFullCharge != nil || status.ChargeRangeAddedRated != nil {
		t.Fatalf("absent charge fields must stay nil: %#v", status)
	}
}

func TestFleetVehicleDataDecodesExtendedJSONFields(t *testing.T) {
	var envelope teslaVehicleDataEnvelope
	body := []byte(`{"response":{
		"charge_state":{"conn_charge_cable":"IEC","scheduled_charging_start_time":1767225600000,"minutes_to_full_charge":95},
		"climate_state":{"driver_temp_setting":22.5,"seat_heater_left":2,"steering_wheel_heater":true},
		"vehicle_state":{"fd_window":0,"fp_window":1,"valet_mode":true,
			"software_update":{"status":"downloading","download_percent":42},
			"speed_limit_mode":{"active":true,"current_limit_mph":70},
			"media_info":{"media_state":"Playing","now_playing_title":"Song"}}
	}}`)
	if err := json.Unmarshal(body, &envelope); err != nil {
		t.Fatalf("decode: %v", err)
	}
	data := envelope.Response
	if data.ChargeState.ConnChargeCable == nil || *data.ChargeState.ConnChargeCable != "IEC" ||
		data.ChargeState.ScheduledChargingStart == nil || *data.ChargeState.ScheduledChargingStart != 1767225600000 {
		t.Fatalf("charge decode = %#v", data.ChargeState)
	}
	if data.VehicleState.FrontRightWindow == nil || *data.VehicleState.FrontRightWindow != 1 ||
		data.VehicleState.SoftwareUpdate == nil || data.VehicleState.SoftwareUpdate.DownloadPercent == nil ||
		data.VehicleState.MediaInfo == nil || data.VehicleState.MediaInfo.NowPlayingTitle == nil {
		t.Fatalf("vehicle decode = %#v", data.VehicleState)
	}
}

func TestFleetVehicleDataPathRequestsLocationData(t *testing.T) {
	path := fleetVehicleDataPath("VIN/with space")
	if !strings.Contains(path, "/api/1/vehicles/VIN%2Fwith%20space/vehicle_data?") {
		t.Fatalf("vehicle data path = %q", path)
	}
	if !strings.Contains(path, "endpoints=location_data") {
		t.Fatalf("location_data endpoint missing: %q", path)
	}
}
