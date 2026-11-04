package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strings"
)

// Vehicle commands, phase 1 (design 2026-11-05 §6). Commands are dispatched
// through the deployment's existing Tesla vehicle-command proxy (the same
// channel telemetry configuration uses) with the vehicle owner's token.
//
// Safety shape, enforced in order for every request:
//  1. kill switch / gray list   -> commands_disabled
//  2. known command             -> unknown_command
//  3. granted scopes evaluated  -> scope_missing (only when evaluation ran)
//  4. high-tier confirmation    -> confirmation_required
//  5. rate budget               -> rate_limited
//  6. pre-state guards          -> vehicle_moving / not_plugged_in
//  7. dispatch, then readback verification against a fresh status fetch
// Every terminal outcome is written to the audit table (best effort).

type commandSpec struct {
	endpoint  string
	tier      string // "low" | "mid" | "high"
	sensitive bool   // sensitive rate bucket + extra care
	parked    bool   // require a parked vehicle (no speed, P gear when known)
	plugged   bool   // require the charge cable plugged in
	build     func(params map[string]any) (map[string]any, error)
}

var errInvalidCommandParams = errors.New("invalid_command_params")

func noParams(map[string]any) (map[string]any, error) { return map[string]any{}, nil }

var vehicleCommands = map[string]commandSpec{
	"lock":              {endpoint: "door_lock", tier: "low", build: noParams},
	"flash":             {endpoint: "flash_lights", tier: "low", build: noParams},
	"honk":              {endpoint: "honk_horn", tier: "low", build: noParams},
	"climate_on":        {endpoint: "auto_conditioning_start", tier: "low", build: noParams},
	"climate_off":       {endpoint: "auto_conditioning_stop", tier: "low", build: noParams},
	"charge_port_open":  {endpoint: "charge_port_door_open", tier: "low", build: noParams},
	"charge_port_close": {endpoint: "charge_port_door_close", tier: "low", build: noParams},
	"set_climate_temp":  {endpoint: "set_temps", tier: "low", build: buildSetTempsPayload},
	"charge_start":      {endpoint: "charge_start", tier: "mid", plugged: true, build: noParams},
	"charge_stop":       {endpoint: "charge_stop", tier: "mid", build: noParams},
	"set_charge_limit":  {endpoint: "set_charge_limit", tier: "mid", build: buildSetChargeLimitPayload},
	"windows_vent":      {endpoint: "window_control", tier: "mid", parked: true, build: fixedPayload("command", "vent")},
	"windows_close":     {endpoint: "window_control", tier: "mid", parked: true, build: fixedPayload("command", "close")},
	"sentry_on":         {endpoint: "set_sentry_mode", tier: "mid", build: fixedPayload("on", true)},
	"sentry_off":        {endpoint: "set_sentry_mode", tier: "mid", build: fixedPayload("on", false)},
	// Unlock is the only high-tier command in phase 1 (design D2 default):
	// parked-only, sensitive bucket, and the request must carry an explicit
	// confirmation flag (the app shows its own confirmation UI first).
	"unlock": {endpoint: "door_unlock", tier: "high", sensitive: true, parked: true, build: noParams},
}

func fixedPayload(key string, value any) func(map[string]any) (map[string]any, error) {
	return func(map[string]any) (map[string]any, error) {
		return map[string]any{key: value}, nil
	}
}

func buildSetTempsPayload(params map[string]any) (map[string]any, error) {
	payload := map[string]any{}
	for _, key := range []string{"driver_temp", "passenger_temp"} {
		raw, present := params[key]
		if !present {
			continue
		}
		value, ok := raw.(float64)
		if !ok || value < 15 || value > 28 {
			return nil, errInvalidCommandParams
		}
		payload[key] = value
	}
	if len(payload) == 0 {
		return nil, errInvalidCommandParams
	}
	return payload, nil
}

func buildSetChargeLimitPayload(params map[string]any) (map[string]any, error) {
	raw, present := params["percent"]
	value, ok := raw.(float64)
	if !present || !ok || value != float64(int(value)) || value < 50 || value > 100 {
		return nil, errInvalidCommandParams
	}
	return map[string]any{"percent": int(value)}, nil
}

// evaluateCommandPreconditions applies the pre-state guards of a command to
// a fresh status snapshot. It returns "" when dispatch may proceed.
func evaluateCommandPreconditions(spec commandSpec, status vehicleStatus) string {
	if spec.parked {
		if status.ShiftState != nil && *status.ShiftState != "" && *status.ShiftState != "P" {
			return "vehicle_moving"
		}
		if status.Speed != nil && *status.Speed > 0 {
			return "vehicle_moving"
		}
	}
	if spec.plugged && (status.PluggedIn == nil || !*status.PluggedIn) {
		return "not_plugged_in"
	}
	return ""
}

// verifyCommandReadback reports whether a post-command status confirms the
// intended effect, and whether this command has a readable effect at all.
// Commands whose effect is transient (flash, honk) or not exposed in the
// status payload return applicable=false and count as accepted-unverified
// by design, never as failures.
func verifyCommandReadback(name string, status vehicleStatus) (verified bool, applicable bool) {
	switch name {
	case "lock":
		return status.Locked != nil && *status.Locked, true
	case "unlock":
		return status.Locked != nil && !*status.Locked, true
	case "climate_on":
		return status.IsClimateOn != nil && *status.IsClimateOn, true
	case "climate_off":
		return status.IsClimateOn != nil && !*status.IsClimateOn, true
	case "sentry_on":
		return status.SentryMode != nil && *status.SentryMode, true
	case "sentry_off":
		return status.SentryMode != nil && !*status.SentryMode, true
	case "charge_start":
		return status.ChargingState != nil && *status.ChargingState == "Charging", true
	case "charge_stop":
		return status.ChargingState != nil && *status.ChargingState != "Charging", true
	case "windows_vent":
		return status.WindowsOpen != nil && *status.WindowsOpen, true
	case "windows_close":
		return status.WindowsOpen != nil && !*status.WindowsOpen, true
	case "charge_port_open":
		return status.ChargePortDoorOpen != nil && *status.ChargePortDoorOpen, true
	case "charge_port_close":
		return status.ChargePortDoorOpen != nil && !*status.ChargePortDoorOpen, true
	}
	return false, false
}

// vehicleCommandExecutor dispatches one command to one vehicle. The
// production implementation is the telemetry service (it owns the command
// proxy channel); tests substitute a fake.
type vehicleCommandExecutor interface {
	ExecuteVehicleCommand(ctx context.Context, userID string, vehicleID int, endpoint string, payload map[string]any) (int, []byte, error)
}

var errCommandChannelUnavailable = errors.New("vehicle command channel unavailable")

func (s *telemetryService) ExecuteVehicleCommand(ctx context.Context, userID string, vehicleID int, endpoint string, payload map[string]any) (int, []byte, error) {
	if s == nil || s.commandProxyURL == "" || s.store == nil || s.cipher == nil {
		return 0, nil, errCommandChannelUnavailable
	}
	stored, err := s.store.fleetVehicle(ctx, userID, vehicleID)
	if err != nil {
		if isVehicleLookupMiss(err) {
			return 0, nil, errVehicleNotFound
		}
		return 0, nil, err
	}
	vin, err := s.cipher.decrypt(stored.VINCiphertext)
	if err != nil {
		return 0, nil, err
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return 0, nil, err
	}
	requestURL := strings.TrimRight(s.commandProxyURL, "/") + "/api/1/vehicles/" + url.PathEscape(vin) + "/command/" + endpoint
	response, err := s.commandProxyRequest(ctx, userID, http.MethodPost, requestURL, body)
	if err != nil {
		return 0, nil, err
	}
	defer response.Body.Close()
	responseBody, err := io.ReadAll(io.LimitReader(response.Body, 64<<10))
	if err != nil {
		return response.StatusCode, nil, err
	}
	return response.StatusCode, responseBody, nil
}

// teslaCommandRejection interprets a command-proxy response: it returns ""
// when Tesla accepted the command, else the error class to report.
func teslaCommandRejection(statusCode int, body []byte) string {
	var envelope struct {
		Response *struct {
			Result bool   `json:"result"`
			Reason string `json:"reason"`
		} `json:"response"`
		Error            string `json:"error"`
		ErrorDescription string `json:"error_description"`
	}
	_ = json.Unmarshal(body, &envelope)
	detail := ""
	if envelope.Response != nil {
		detail = envelope.Response.Reason
	}
	if detail == "" {
		detail = envelope.Error
	}
	if statusCode >= 200 && statusCode < 300 {
		if envelope.Response != nil && !envelope.Response.Result {
			return classifyTeslaCommandReason(detail)
		}
		return ""
	}
	if statusCode == http.StatusUnauthorized || statusCode == http.StatusForbidden {
		return "scope_missing"
	}
	if statusCode == http.StatusTooManyRequests {
		return "rate_limited"
	}
	return classifyTeslaCommandReason(detail)
}

func classifyTeslaCommandReason(reason string) string {
	lower := strings.ToLower(reason)
	switch {
	case strings.Contains(lower, "not_paired"), strings.Contains(lower, "key_not"), strings.Contains(lower, "no key"):
		return "key_not_paired"
	case strings.Contains(lower, "in_service"), strings.Contains(lower, "service_mode"):
		return "vehicle_in_service"
	default:
		return "tesla_error"
	}
}

type commandRequest struct {
	Params  map[string]any `json:"params"`
	Confirm bool           `json:"confirm"`
}

func (a *app) commandsAllowedFor(userID string) bool {
	if !a.commandsEnabled {
		return false
	}
	if len(a.commandsGrayUsers) == 0 {
		return true
	}
	return a.commandsGrayUsers[userID]
}

func (a *app) auditCommand(ctx context.Context, entry commandAuditEntry) {
	if a.store == nil {
		return
	}
	// Best effort by design (§6.4): the command outcome is already decided;
	// an audit-store failure must not rewrite the answer the user gets.
	_ = a.store.insertCommandAudit(ctx, entry)
}

func (a *app) executeVehicleCommand(w http.ResponseWriter, r *http.Request, userID string, carID int, name string) {
	fail := func(status int, class string, extra map[string]any, outcome string) {
		a.auditCommand(r.Context(), commandAuditEntry{
			UserID: userID, VehicleID: carID, Command: name, Outcome: outcome, ErrorClass: class,
		})
		payload := map[string]any{"error": class}
		for key, value := range extra {
			payload[key] = value
		}
		a.json(w, status, payload)
	}
	if !a.commandsAllowedFor(userID) {
		fail(http.StatusForbidden, "commands_disabled", nil, "rejected")
		return
	}
	spec, known := vehicleCommands[name]
	if !known {
		fail(http.StatusNotFound, "unknown_command", nil, "rejected")
		return
	}
	var request commandRequest
	if r.Body != nil {
		body, err := io.ReadAll(io.LimitReader(r.Body, 16<<10))
		if err == nil && len(body) > 0 {
			if err := json.Unmarshal(body, &request); err != nil {
				fail(http.StatusBadRequest, "invalid_params", nil, "rejected")
				return
			}
		}
	}
	if request.Params == nil {
		request.Params = map[string]any{}
	}
	payload, err := spec.build(request.Params)
	if err != nil {
		fail(http.StatusBadRequest, "invalid_params", nil, "rejected")
		return
	}
	if spec.tier == "high" && !request.Confirm {
		fail(http.StatusBadRequest, "confirmation_required", nil, "rejected")
		return
	}
	// One status fetch serves both the scope gate and the pre-state guards.
	preStatus, err := a.provider.Status(r.Context(), userID, carID)
	if err != nil {
		a.providerError(w, err)
		return
	}
	if preStatus.CommandScopesMissing != nil && len(preStatus.CommandScopesMissing) > 0 {
		fail(http.StatusForbidden, "scope_missing", map[string]any{"missing_scopes": preStatus.CommandScopesMissing}, "rejected")
		return
	}
	if class := evaluateCommandPreconditions(spec, preStatus); class != "" {
		status := http.StatusConflict
		fail(status, class, nil, "rejected")
		return
	}
	limiter := a.commandLimiter
	if limiter == nil {
		limiter = newCommandRateLimiter()
		a.commandLimiter = limiter
	}
	if !limiter.allow(userID, carID, spec.sensitive) {
		fail(http.StatusTooManyRequests, "rate_limited", nil, "rejected")
		return
	}
	if a.commandExecutor == nil {
		fail(http.StatusServiceUnavailable, "commands_disabled", nil, "rejected")
		return
	}
	statusCode, responseBody, err := a.commandExecutor.ExecuteVehicleCommand(r.Context(), userID, carID, spec.endpoint, payload)
	if err != nil {
		if errors.Is(err, errVehicleNotFound) {
			fail(http.StatusNotFound, "vehicle_not_found", nil, "rejected")
			return
		}
		fail(http.StatusBadGateway, "tesla_unavailable", nil, "tesla_error")
		return
	}
	if class := teslaCommandRejection(statusCode, responseBody); class != "" {
		httpStatus := http.StatusBadGateway
		if class == "scope_missing" {
			httpStatus = http.StatusForbidden
		} else if class == "rate_limited" {
			httpStatus = http.StatusTooManyRequests
		}
		a.auditCommand(r.Context(), commandAuditEntry{
			UserID: userID, VehicleID: carID, Command: name, Tier: spec.tier, Outcome: "tesla_error", ErrorClass: class,
		})
		a.json(w, httpStatus, map[string]any{"error": class})
		return
	}
	// Readback verification against a fresh status fetch (§6.3). A mismatch
	// is reported honestly as unverified; it never rewrites an accepted
	// command into a failure.
	verified, applicable := false, false
	if postStatus, statusErr := a.provider.Status(r.Context(), userID, carID); statusErr == nil {
		verified, applicable = verifyCommandReadback(name, postStatus)
	}
	outcome := "accepted"
	result := "accepted"
	if applicable && !verified {
		outcome = "unverified"
		result = "unverified"
	}
	a.auditCommand(r.Context(), commandAuditEntry{
		UserID: userID, VehicleID: carID, Command: name, Tier: spec.tier, Outcome: outcome,
	})
	a.json(w, http.StatusOK, map[string]any{"data": map[string]any{
		"command": name, "result": result, "verified": verified,
	}})
}
