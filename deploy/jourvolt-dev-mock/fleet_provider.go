package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

const milesToKilometres = 1.609344

type fleetProvider struct {
	store   *store
	tokens  fleetAccessTokens
	cipher  *tokenCipher
	client  *http.Client
	baseURL string
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
}

func (p *fleetProvider) Vehicles(ctx context.Context, userID string) ([]vehicle, error) {
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
		vehicles = append(vehicles, vehicle{
			ID: stored.ID, DisplayName: displayName, State: teslaVehicle.State,
			Source: "fleet_api", Model: stored.Model, TrimBadging: stored.TrimBadging,
			ExteriorColor: stored.ExteriorColor, WheelType: stored.WheelType,
		})
	}
	return vehicles, nil
}

func (p *fleetProvider) Status(ctx context.Context, userID string, vehicleID int) (vehicleStatus, error) {
	stored, err := p.store.fleetVehicle(ctx, userID, vehicleID)
	if err != nil {
		return vehicleStatus{}, errVehicleNotFound
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
		Version:                 data.VehicleState.CarVersion,
		DoorsOpen:               anyOpen(data.VehicleState.DriverFrontDoor, data.VehicleState.DriverRearDoor, data.VehicleState.PassengerFrontDoor, data.VehicleState.PassengerRearDoor),
		FrunkOpen:               openPointer(data.VehicleState.FrontTrunk),
		TrunkOpen:               openPointer(data.VehicleState.RearTrunk),
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
	switch response.StatusCode {
	case http.StatusUnauthorized, http.StatusForbidden:
		return errTeslaReauthorization
	case http.StatusNotFound:
		return errVehicleNotFound
	case http.StatusTooManyRequests:
		return errTeslaRateLimited
	case http.StatusRequestTimeout, http.StatusMisdirectedRequest:
		return errTeslaUnavailable
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 4096))
		return errTeslaUnavailable
	}
	if err := json.NewDecoder(io.LimitReader(response.Body, 4<<20)).Decode(target); err != nil {
		return fmt.Errorf("decode Fleet API response: %w", err)
	}
	return nil
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
