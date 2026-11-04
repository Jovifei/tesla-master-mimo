package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

type fakeCommandProvider struct {
	statuses []vehicleStatus
	calls    int
}

func (f *fakeCommandProvider) Vehicles(context.Context, string) ([]vehicle, error) {
	return nil, nil
}

func (f *fakeCommandProvider) Status(context.Context, string, int) (vehicleStatus, error) {
	status := f.statuses[min(f.calls, len(f.statuses)-1)]
	f.calls++
	return status, nil
}

type fakeCommandExecutor struct {
	endpoint string
	payload  map[string]any
	code     int
	body     []byte
	err      error
}

func (f *fakeCommandExecutor) ExecuteVehicleCommand(_ context.Context, _ string, _ int, endpoint string, payload map[string]any) (int, []byte, error) {
	f.endpoint = endpoint
	f.payload = payload
	if f.err != nil {
		return 0, nil, f.err
	}
	return f.code, f.body, nil
}

func commandTestApp(provider *fakeCommandProvider, executor *fakeCommandExecutor) *app {
	if executor == nil {
		executor = &fakeCommandExecutor{code: 200, body: []byte(`{"response":{"result":true,"reason":""}}`)}
	}
	return &app{
		provider: provider, commandExecutor: executor,
		commandsEnabled: true, commandLimiter: newCommandRateLimiter(),
	}
}

func postCommand(t *testing.T, application *app, name, body string) *httptest.ResponseRecorder {
	t.Helper()
	request := httptest.NewRequest(http.MethodPost, "/api/v1/cars/7/commands/"+name, strings.NewReader(body))
	recorder := httptest.NewRecorder()
	application.executeVehicleCommand(recorder, request, "user-1", 7, name)
	return recorder
}

func errorClass(t *testing.T, recorder *httptest.ResponseRecorder) string {
	t.Helper()
	var payload map[string]any
	if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil {
		t.Fatalf("response not json: %v (%s)", err, recorder.Body.String())
	}
	class, _ := payload["error"].(string)
	return class
}

func TestCommandKillSwitchAndUnknownCommand(t *testing.T) {
	provider := &fakeCommandProvider{statuses: []vehicleStatus{{}}}
	application := commandTestApp(provider, nil)
	application.commandsEnabled = false
	if recorder := postCommand(t, application, "lock", `{}`); recorder.Code != http.StatusForbidden || errorClass(t, recorder) != "commands_disabled" {
		t.Fatalf("disabled = %d %q", recorder.Code, errorClass(t, recorder))
	}
	application.commandsEnabled = true
	if recorder := postCommand(t, application, "self_destruct", `{}`); recorder.Code != http.StatusNotFound || errorClass(t, recorder) != "unknown_command" {
		t.Fatalf("unknown = %d %q", recorder.Code, errorClass(t, recorder))
	}
}

func TestCommandScopeGateAndConfirmation(t *testing.T) {
	provider := &fakeCommandProvider{statuses: []vehicleStatus{{CommandScopesMissing: []string{"vehicle_cmds"}}}}
	application := commandTestApp(provider, nil)
	if recorder := postCommand(t, application, "lock", `{}`); errorClass(t, recorder) != "scope_missing" {
		t.Fatalf("scope gate = %q", errorClass(t, recorder))
	}

	provider = &fakeCommandProvider{statuses: []vehicleStatus{{CommandScopesMissing: []string{}}}}
	application = commandTestApp(provider, nil)
	if recorder := postCommand(t, application, "unlock", `{}`); errorClass(t, recorder) != "confirmation_required" {
		t.Fatalf("unlock without confirm = %q", errorClass(t, recorder))
	}
}

func TestCommandPreconditionGuards(t *testing.T) {
	drive := "D"
	provider := &fakeCommandProvider{statuses: []vehicleStatus{{ShiftState: &drive}}}
	application := commandTestApp(provider, nil)
	if recorder := postCommand(t, application, "windows_vent", `{}`); recorder.Code != http.StatusConflict || errorClass(t, recorder) != "vehicle_moving" {
		t.Fatalf("moving guard = %d %q", recorder.Code, errorClass(t, recorder))
	}

	provider = &fakeCommandProvider{statuses: []vehicleStatus{{}}}
	application = commandTestApp(provider, nil)
	if recorder := postCommand(t, application, "charge_start", `{}`); errorClass(t, recorder) != "not_plugged_in" {
		t.Fatalf("plugged guard = %q", errorClass(t, recorder))
	}
}

func TestCommandAcceptedAndVerifiedByReadback(t *testing.T) {
	unlocked, locked := false, true
	provider := &fakeCommandProvider{statuses: []vehicleStatus{{Locked: &unlocked}, {Locked: &locked}}}
	executor := &fakeCommandExecutor{code: 200, body: []byte(`{"response":{"result":true,"reason":""}}`)}
	application := commandTestApp(provider, executor)
	recorder := postCommand(t, application, "lock", `{}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("lock status = %d body %s", recorder.Code, recorder.Body.String())
	}
	var payload struct {
		Data struct {
			Result   string `json:"result"`
			Verified bool   `json:"verified"`
		} `json:"data"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if payload.Data.Result != "accepted" || !payload.Data.Verified {
		t.Fatalf("result = %#v", payload.Data)
	}
	if executor.endpoint != "door_lock" {
		t.Fatalf("endpoint = %q", executor.endpoint)
	}
}

func TestCommandAcceptedButUnverifiedWhenReadbackDisagrees(t *testing.T) {
	unlocked := false
	provider := &fakeCommandProvider{statuses: []vehicleStatus{{Locked: &unlocked}, {Locked: &unlocked}}}
	application := commandTestApp(provider, nil)
	recorder := postCommand(t, application, "lock", `{}`)
	var payload struct {
		Data struct {
			Result string `json:"result"`
		} `json:"data"`
	}
	_ = json.Unmarshal(recorder.Body.Bytes(), &payload)
	if payload.Data.Result != "unverified" {
		t.Fatalf("result = %q, want unverified", payload.Data.Result)
	}
}

func TestCommandTeslaRejectionMapping(t *testing.T) {
	provider := &fakeCommandProvider{statuses: []vehicleStatus{{}}}
	executor := &fakeCommandExecutor{code: 200, body: []byte(`{"response":{"result":false,"reason":"key_not_paired"}}`)}
	application := commandTestApp(provider, executor)
	recorder := postCommand(t, application, "lock", `{}`)
	if errorClass(t, recorder) != "key_not_paired" {
		t.Fatalf("rejection = %q", errorClass(t, recorder))
	}
	if class := teslaCommandRejection(403, []byte(`{"error":"insufficient_permissions"}`)); class != "scope_missing" {
		t.Fatalf("403 class = %q", class)
	}
	if class := teslaCommandRejection(200, []byte(`{"response":{"result":true}}`)); class != "" {
		t.Fatalf("accepted class = %q", class)
	}
}

func TestCommandPayloadBuilders(t *testing.T) {
	if _, err := buildSetTempsPayload(map[string]any{"driver_temp": 30.0}); err == nil {
		t.Fatal("out-of-range temperature must be rejected")
	}
	payload, err := buildSetTempsPayload(map[string]any{"driver_temp": 22.5})
	if err != nil || payload["driver_temp"] != 22.5 {
		t.Fatalf("temps payload = %#v err %v", payload, err)
	}
	if _, err := buildSetTempsPayload(map[string]any{}); err == nil {
		t.Fatal("empty temps must be rejected")
	}
	if _, err := buildSetChargeLimitPayload(map[string]any{"percent": 45.0}); err == nil {
		t.Fatal("limit below 50 must be rejected")
	}
	payload, err = buildSetChargeLimitPayload(map[string]any{"percent": 90.0})
	if err != nil || payload["percent"] != 90 {
		t.Fatalf("limit payload = %#v err %v", payload, err)
	}
}

func TestCommandSensitiveRateLimitThroughHandler(t *testing.T) {
	parked := "P"
	provider := &fakeCommandProvider{statuses: []vehicleStatus{{ShiftState: &parked, CommandScopesMissing: []string{}}}}
	application := commandTestApp(provider, nil)
	for i := 0; i < commandDailyLimitSensitive; i++ {
		application.commandLimiter.allow("user-1", 7, true)
	}
	recorder := postCommand(t, application, "unlock", `{"confirm":true}`)
	if recorder.Code != http.StatusTooManyRequests || errorClass(t, recorder) != "rate_limited" {
		t.Fatalf("rate limited = %d %q", recorder.Code, errorClass(t, recorder))
	}
}
