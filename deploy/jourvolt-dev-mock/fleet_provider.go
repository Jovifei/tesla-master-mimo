package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"math"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

const milesToKilometres = 1.609344

var errTeslaBillingBlocked = errors.New("tesla_billing_blocked")

type fleetAPIError struct {
	class      string
	statusCode int
	cause      error
}

func (e *fleetAPIError) Error() string {
	return "fleet api error: " + e.class
}

func (e *fleetAPIError) Unwrap() error {
	return e.cause
}

type fleetProvider struct {
	store     *store
	tokens    fleetAccessTokens
	cipher    *tokenCipher
	client    *http.Client
	baseURL   string
	registrar *teslaPartnerRegistrar
	telemetry *telemetryService
}

type teslaVehicleListEnvelope struct {
	Response []teslaVehicleSummary `json:"response"`
}

type teslaVehicleSummary struct {
	ID          int64  `json:"id"`
	IDString    string `json:"id_s"`
	VehicleID   int64  `json:"vehicle_id"`
	VIN         string `json:"vin"`
	DisplayName string `json:"display_name"`
	State       string `json:"state"`
}

type teslaVehicleDataEnvelope struct {
	Response teslaVehicleData `json:"response"`
}

type teslaVehicleData struct {
	DisplayName   string             `json:"display_name"`
	State         string             `json:"state"`
	ChargeState   teslaChargeState   `json:"charge_state"`
	ClimateState  teslaClimateState  `json:"climate_state"`
	DriveState    teslaDriveState    `json:"drive_state"`
	GUISettings   teslaGUISettings   `json:"gui_settings"`
	VehicleConfig teslaVehicleConfig `json:"vehicle_config"`
	VehicleState  teslaVehicleState  `json:"vehicle_state"`
}

type teslaChargeState struct {
	BatteryLevel            *int     `json:"battery_level"`
	UsableBatteryLevel      *int     `json:"usable_battery_level"`
	BatteryRange            *float64 `json:"battery_range"`
	EstBatteryRange         *float64 `json:"est_battery_range"`
	IdealBatteryRange       *float64 `json:"ideal_battery_range"`
	ChargingState           *string  `json:"charging_state"`
	ChargeEnergyAdded       *float64 `json:"charge_energy_added"`
	ChargeLimitSOC          *int     `json:"charge_limit_soc"`
	ChargePortDoorOpen      *bool    `json:"charge_port_door_open"`
	ChargerActualCurrent    *int     `json:"charger_actual_current"`
	ChargerPhases           *int     `json:"charger_phases"`
	ChargerPower            *int     `json:"charger_power"`
	ChargerVoltage          *int     `json:"charger_voltage"`
	ChargeCurrentRequest    *int     `json:"charge_current_request"`
	ChargeCurrentRequestMax *int     `json:"charge_current_request_max"`
	TimeToFullCharge        *float64 `json:"time_to_full_charge"`
}

type teslaClimateState struct {
	IsClimateOn       *bool    `json:"is_climate_on"`
	InsideTemp        *float64 `json:"inside_temp"`
	OutsideTemp       *float64 `json:"outside_temp"`
	IsPreconditioning *bool    `json:"is_preconditioning"`
}

type teslaDriveState struct {
	ShiftState *string  `json:"shift_state"`
	Power      *int     `json:"power"`
	Speed      *float64 `json:"speed"`
	Heading    *int     `json:"heading"`
	Latitude   *float64 `json:"latitude"`
	Longitude  *float64 `json:"longitude"`
}

type teslaGUISettings struct {
	DistanceUnits    string `json:"gui_distance_units"`
	TemperatureUnits string `json:"gui_temperature_units"`
}

type teslaVehicleConfig struct {
	CarType       string `json:"car_type"`
	TrimBadging   string `json:"trim_badging"`
	ExteriorColor string `json:"exterior_color"`
	WheelType     string `json:"wheel_type"`
}

type teslaVehicleState struct {
	Locked             *bool    `json:"locked"`
	SentryMode         *bool    `json:"sentry_mode"`
	IsUserPresent      *bool    `json:"is_user_present"`
	CenterDisplayState *int     `json:"center_display_state"`
	Odometer           *float64 `json:"odometer"`
	CarVersion         *string  `json:"car_version"`
	DriverFrontDoor    *int     `json:"df"`
	DriverRearDoor     *int     `json:"dr"`
	PassengerFrontDoor *int     `json:"pf"`
	PassengerRearDoor  *int     `json:"pr"`
	FrontTrunk         *int     `json:"ft"`
	RearTrunk          *int     `json:"rt"`
	TPMSPressureFL     *float64 `json:"tpms_pressure_fl"`
	TPMSPressureFR     *float64 `json:"tpms_pressure_fr"`
	TPMSPressureRL     *float64 `json:"tpms_pressure_rl"`
	TPMSPressureRR     *float64 `json:"tpms_pressure_rr"`
	TPMSSoftWarningFL  *bool    `json:"tpms_soft_warning_fl"`
	TPMSSoftWarningFR  *bool    `json:"tpms_soft_warning_fr"`
	TPMSSoftWarningRL  *bool    `json:"tpms_soft_warning_rl"`
	TPMSSoftWarningRR  *bool    `json:"tpms_soft_warning_rr"`
}

func (p *fleetProvider) Vehicles(ctx context.Context, userID string) ([]vehicle, error) {
	if err := p.registrar.ensure(ctx); err != nil {
		logFleetAPI("REGISTER", "/api/1/partner_accounts", 0, nil)
	}
	var payload teslaVehicleListEnvelope
	if err := p.get(ctx, userID, "/api/1/vehicles", &payload); err != nil {
		return nil, err
	}
	vehicles := make([]vehicle, 0, len(payload.Response))
	for _, teslaVehicle := range payload.Response {
		providerID := teslaVehicle.IDString
		if providerID == "" && teslaVehicle.VehicleID != 0 {
			providerID = strconv.FormatInt(teslaVehicle.VehicleID, 10)
		}
		if providerID == "" && teslaVehicle.ID != 0 {
			providerID = strconv.FormatInt(teslaVehicle.ID, 10)
		}
		if providerID == "" || teslaVehicle.VIN == "" {
			return nil, errTeslaUnavailable
		}
		displayName := strings.TrimSpace(teslaVehicle.DisplayName)
		if displayName == "" {
			displayName = "Tesla"
		}
		vinCiphertext, err := p.cipher.encrypt(teslaVehicle.VIN)
		if err != nil {
			return nil, err
		}
		stored, err := p.store.upsertFleetVehicle(
			ctx, userID, providerID, vinCiphertext, displayName, teslaVehicle.State,
		)
		if err != nil {
			return nil, err
		}
		if p.telemetry != nil {
			if err := p.telemetry.registerVehicle(ctx, telemetryVehicleRef{
				UserID: userID, VehicleID: stored.ID, VINHash: keyedVINHash(p.telemetry.vinHashKey, teslaVehicle.VIN), VINCiphertext: vinCiphertext,
				ProviderVehicleID: providerID, DisplayName: displayName,
			}); err != nil {
				return nil, err
			}
		}
		vehicles = append(vehicles, fleetVehicleFromProvider(
			stored, providerID, displayName, teslaVehicle.State,
		))
	}
	return vehicles, nil
}

func fleetVehicleFromProvider(stored storedVehicle, providerID, displayName, state string) vehicle {
	return vehicle{
		ID: stored.ID, VehicleUID: providerID, DisplayName: displayName, State: state,
		Source: "fleet_api", Model: stored.Model, TrimBadging: stored.TrimBadging,
		ExteriorColor: stored.ExteriorColor, WheelType: stored.WheelType,
	}
}

func (p *fleetProvider) Status(ctx context.Context, userID string, vehicleID int) (vehicleStatus, error) {
	stored, err := p.store.fleetVehicle(ctx, userID, vehicleID)
	if err != nil {
		if isVehicleLookupMiss(err) {
			return vehicleStatus{}, errVehicleNotFound
		}
		return vehicleStatus{}, err
	}
	vin, err := p.cipher.decrypt(stored.VINCiphertext)
	if err != nil {
		return vehicleStatus{}, err
	}
	var payload teslaVehicleDataEnvelope
	path := "/api/1/vehicles/" + url.PathEscape(vin) + "/vehicle_data"
	if err := p.get(ctx, userID, path, &payload); err != nil {
		return vehicleStatus{}, err
	}
	data := payload.Response
	displayName := strings.TrimSpace(data.DisplayName)
	if displayName == "" {
		displayName = stored.DisplayName
	}
	state := strings.TrimSpace(data.State)
	if state == "" {
		state = stored.State
	}
	model := publicModelName(data.VehicleConfig.CarType)
	if err := p.store.updateFleetVehicleMetadata(
		ctx, userID, vehicleID, displayName, state, model,
		data.VehicleConfig.TrimBadging, data.VehicleConfig.ExteriorColor, data.VehicleConfig.WheelType,
	); err != nil {
		return vehicleStatus{}, err
	}

	status := mapTeslaVehicleStatus(data, displayName, state)
	providerIdentity, err := p.storedProviderIdentity(ctx, userID, vehicleID)
	if err != nil {
		return vehicleStatus{}, err
	}
	status.ProviderIdentity = providerIdentity
	return status, nil
}

func mapTeslaVehicleStatus(data teslaVehicleData, displayName, state string) vehicleStatus {
	status := vehicleStatus{
		ObservedAt:              time.Now().UTC(),
		DisplayName:             displayName,
		State:                   state,
		Healthy:                 boolPointer(true),
		Locked:                  data.VehicleState.Locked,
		SentryMode:              data.VehicleState.SentryMode,
		IsUserPresent:           data.VehicleState.IsUserPresent,
		Odometer:                milesPointerToKilometres(data.VehicleState.Odometer),
		BatteryLevel:            data.ChargeState.BatteryLevel,
		UsableBatteryLevel:      data.ChargeState.UsableBatteryLevel,
		EstimatedBatteryRange:   milesPointerToKilometres(data.ChargeState.EstBatteryRange),
		RatedBatteryRange:       milesPointerToKilometres(data.ChargeState.BatteryRange),
		IdealBatteryRange:       milesPointerToKilometres(data.ChargeState.IdealBatteryRange),
		ChargingState:           data.ChargeState.ChargingState,
		ChargeEnergyAdded:       data.ChargeState.ChargeEnergyAdded,
		ChargeLimitSOC:          data.ChargeState.ChargeLimitSOC,
		ChargePortDoorOpen:      data.ChargeState.ChargePortDoorOpen,
		ChargerActualCurrent:    data.ChargeState.ChargerActualCurrent,
		ChargerPhases:           data.ChargeState.ChargerPhases,
		ChargerPower:            data.ChargeState.ChargerPower,
		ChargerVoltage:          data.ChargeState.ChargerVoltage,
		ChargeCurrentRequest:    data.ChargeState.ChargeCurrentRequest,
		ChargeCurrentRequestMax: data.ChargeState.ChargeCurrentRequestMax,
		TimeToFullCharge:        data.ChargeState.TimeToFullCharge,
		IsClimateOn:             data.ClimateState.IsClimateOn,
		InsideTemp:              data.ClimateState.InsideTemp,
		OutsideTemp:             data.ClimateState.OutsideTemp,
		IsPreconditioning:       data.ClimateState.IsPreconditioning,
		ShiftState:              data.DriveState.ShiftState,
		Power:                   data.DriveState.Power,
		Speed:                   milesPerHourPointerToKilometres(data.DriveState.Speed),
		Heading:                 data.DriveState.Heading,
		Latitude:                data.DriveState.Latitude,
		Longitude:               data.DriveState.Longitude,
		Version:                 data.VehicleState.CarVersion,
		DoorsOpen:               anyOpen(data.VehicleState.DriverFrontDoor, data.VehicleState.DriverRearDoor, data.VehicleState.PassengerFrontDoor, data.VehicleState.PassengerRearDoor),
		FrunkOpen:               openPointer(data.VehicleState.FrontTrunk),
		TrunkOpen:               openPointer(data.VehicleState.RearTrunk),
		TPMSPressureFL:          data.VehicleState.TPMSPressureFL,
		TPMSPressureFR:          data.VehicleState.TPMSPressureFR,
		TPMSPressureRL:          data.VehicleState.TPMSPressureRL,
		TPMSPressureRR:          data.VehicleState.TPMSPressureRR,
		TPMSSoftWarningFL:       data.VehicleState.TPMSSoftWarningFL,
		TPMSSoftWarningFR:       data.VehicleState.TPMSSoftWarningFR,
		TPMSSoftWarningRL:       data.VehicleState.TPMSSoftWarningRL,
		TPMSSoftWarningRR:       data.VehicleState.TPMSSoftWarningRR,
		Source:                  "fleet_api",
	}
	if data.ChargeState.ChargingState != nil {
		pluggedIn := !strings.EqualFold(*data.ChargeState.ChargingState, "Disconnected")
		status.PluggedIn = &pluggedIn
	}
	if data.VehicleState.CenterDisplayState != nil {
		value := strconv.Itoa(*data.VehicleState.CenterDisplayState)
		status.CenterDisplayState = &value
	}
	return status
}

func (p *fleetProvider) storedProviderIdentity(ctx context.Context, userID string, vehicleID int) (string, error) {
	if p.store == nil || p.store.pool == nil {
		return "", errVehicleNotFound
	}
	var providerID string
	err := p.store.pool.QueryRow(ctx, `
SELECT provider_vehicle_id FROM jourvolt_vehicles
WHERE id=$1 AND user_id=$2`, vehicleID, userID).Scan(&providerID)
	return providerID, err
}

func (p *fleetProvider) get(ctx context.Context, userID, path string, target any) error {
	accessToken, err := p.tokens.accessToken(ctx, userID, "")
	if err != nil {
		return err
	}
	response, err := p.request(ctx, path, accessToken)
	if err != nil {
		return err
	}
	if response.StatusCode == http.StatusUnauthorized {
		response.Body.Close()
		accessToken, err = p.tokens.accessToken(ctx, userID, accessToken)
		if err != nil {
			return err
		}
		response, err = p.request(ctx, path, accessToken)
		if err != nil {
			return err
		}
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, 4<<20))
	if err != nil {
		return fmt.Errorf("read Fleet API response: %w", err)
	}
	switch response.StatusCode {
	case http.StatusUnauthorized, http.StatusForbidden:
		if fleetResponseErrorClass(response.StatusCode, body) == "billing_blocked" {
			logFleetAPI("GET", path, response.StatusCode, body)
			return &fleetAPIError{class: "billing_blocked", statusCode: response.StatusCode, cause: errTeslaBillingBlocked}
		}
		logFleetAPI("GET", path, response.StatusCode, body)
		return errTeslaReauthorization
	case http.StatusNotFound:
		logFleetAPI("GET", path, response.StatusCode, body)
		return errVehicleNotFound
	case http.StatusTooManyRequests:
		logFleetAPI("GET", path, response.StatusCode, body)
		return errTeslaRateLimited
	case http.StatusRequestTimeout, http.StatusMisdirectedRequest:
		logFleetAPI("GET", path, response.StatusCode, body)
		return errTeslaUnavailable
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		if fleetResponseErrorClass(response.StatusCode, body) == "billing_blocked" {
			logFleetAPI("GET", path, response.StatusCode, body)
			return &fleetAPIError{class: "billing_blocked", statusCode: response.StatusCode, cause: errTeslaBillingBlocked}
		}
		logFleetAPI("GET", path, response.StatusCode, body)
		return errTeslaUnavailable
	}
	if err := json.Unmarshal(body, target); err != nil {
		return fmt.Errorf("decode Fleet API response: %w", err)
	}
	return nil
}

func logFleetAPI(method, path string, status int, body []byte) {
	log.Print(sanitizeFleetLog(method, path, status, body))
}

func sanitizeFleetLog(method, path string, status int, body []byte) string {
	return fmt.Sprintf("fleet api method=%s endpoint=%s status=%d error_class=%s", method, fleetEndpointLabel(path), status, fleetResponseErrorClass(status, body))
}

func fleetEndpointLabel(path string) string {
	switch {
	case path == "/api/1/vehicles":
		return "vehicles"
	case strings.HasPrefix(path, "/api/1/vehicles/") && strings.HasSuffix(path, "/vehicle_data"):
		return "vehicle_data"
	case path == "/api/1/partner_accounts":
		return "partner_accounts"
	default:
		return "unknown"
	}
}

func fleetResponseErrorClass(status int, body []byte) string {
	if status == http.StatusPaymentRequired || fleetBodyIndicatesBilling(body) {
		return "billing_blocked"
	}
	switch status {
	case http.StatusUnauthorized, http.StatusForbidden:
		return "reauthorization"
	case http.StatusNotFound:
		return "vehicle_not_found"
	case http.StatusTooManyRequests:
		return "rate_limited"
	case http.StatusRequestTimeout, http.StatusMisdirectedRequest:
		return "unavailable"
	case 0:
		return "request_failed"
	case 200, 201, 202, 204:
		return "none"
	default:
		if status >= 200 && status < 300 {
			return "none"
		}
		return "upstream"
	}
}

func fleetBodyIndicatesBilling(body []byte) bool {
	var payload any
	if json.Unmarshal(body, &payload) != nil {
		return false
	}
	return fleetValueIndicatesBilling(payload, "")
}

func fleetValueIndicatesBilling(value any, field string) bool {
	switch value := value.(type) {
	case string:
		return fleetExplicitBillingCode(field, value)
	case []any:
		for _, item := range value {
			if fleetValueIndicatesBilling(item, field) {
				return true
			}
		}
	case map[string]any:
		for key, item := range value {
			key = strings.ToLower(strings.TrimSpace(key))
			if isFleetBillingField(key) && fleetValueIndicatesBilling(item, key) {
				return true
			}
			if (key == "error" || key == "errors" || key == "details") && fleetValueIndicatesBilling(item, key) {
				return true
			}
		}
	}
	return false
}

func isFleetBillingField(field string) bool {
	switch field {
	case "error", "error_code", "error_class", "code", "class", "type":
		return true
	default:
		return false
	}
}

func fleetExplicitBillingCode(field, value string) bool {
	normalized := strings.ToLower(strings.TrimSpace(value))
	normalized = strings.ReplaceAll(normalized, "-", "_")
	normalized = strings.ReplaceAll(normalized, " ", "_")
	switch field {
	case "class", "error_class", "type":
		return normalized == "billing" || normalized == "payment" || normalized == "billing_blocked" || normalized == "payment_required" || normalized == "payment_blocked"
	case "error", "error_code", "code":
		return normalized == "billing_blocked" || normalized == "billing_required" || normalized == "payment_required" || normalized == "payment_blocked"
	default:
		return false
	}
}

func (p *fleetProvider) request(ctx context.Context, path, accessToken string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, p.baseURL+path, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+accessToken)
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Content-Type", "application/json")
	response, err := p.client.Do(req)
	if err != nil {
		return nil, errTeslaUnavailable
	}
	return response, nil
}

func publicModelName(carType string) string {
	switch strings.ToLower(strings.TrimSpace(carType)) {
	case "models", "model_s":
		return "S"
	case "model3", "model_3":
		return "3"
	case "modelx", "model_x":
		return "X"
	case "modely", "model_y":
		return "Y"
	case "cybertruck":
		return "Cybertruck"
	default:
		return ""
	}
}

func milesPointerToKilometres(value *float64) *float64 {
	if value == nil {
		return nil
	}
	converted := *value * milesToKilometres
	return &converted
}

func milesPerHourPointerToKilometres(value *float64) *int {
	if value == nil {
		return nil
	}
	converted := int(math.Round(*value * milesToKilometres))
	return &converted
}

func boolPointer(value bool) *bool { return &value }

func intPointer(value int) *int { return &value }

func openPointer(value *int) *bool {
	if value == nil {
		return nil
	}
	return boolPointer(*value > 0)
}

func anyOpen(values ...*int) *bool {
	known := false
	for _, value := range values {
		if value == nil {
			continue
		}
		known = true
		if *value > 0 {
			return boolPointer(true)
		}
	}
	if !known {
		return nil
	}
	return boolPointer(false)
}
