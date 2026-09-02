package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type testProvider struct {
	vehicles    map[string][]vehicle
	statuses    map[string]vehicleStatus
	statusError map[string]error
}

func (p testProvider) Vehicles(_ context.Context, userID string) ([]vehicle, error) {
	return p.vehicles[userID], nil
}

func (p testProvider) Status(_ context.Context, userID string, _ int) (vehicleStatus, error) {
	if err := p.statusError[userID]; err != nil {
		return vehicleStatus{}, err
	}
	return p.statuses[userID], nil
}

func TestBearerToken(t *testing.T) {
	tests := []struct {
		name   string
		header string
		want   string
	}{
		{name: "valid", header: "Bearer access-token", want: "access-token"},
		{name: "case insensitive", header: "bearer access-token", want: "access-token"},
		{name: "missing value", header: "Bearer ", want: ""},
		{name: "wrong scheme", header: "Basic access-token", want: ""},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			req, err := http.NewRequest(http.MethodGet, "http://localhost", nil)
			if err != nil {
				t.Fatal(err)
			}
			req.Header.Set("Authorization", test.header)
			if got := bearerToken(req); got != test.want {
				t.Fatalf("bearerToken() = %q, want %q", got, test.want)
			}
		})
	}
}

func TestHealthzAndReadinessFailClosedWithoutStore(t *testing.T) {
	api := &app{mode: "test"}

	health := httptest.NewRecorder()
	api.ServeHTTP(health, httptest.NewRequest(http.MethodGet, "/healthz", nil))
	if health.Code != http.StatusOK {
		t.Fatalf("healthz status = %d, want %d", health.Code, http.StatusOK)
	}

	ready := httptest.NewRecorder()
	api.ServeHTTP(ready, httptest.NewRequest(http.MethodGet, "/readyz", nil))
	if ready.Code != http.StatusServiceUnavailable {
		t.Fatalf("readyz status = %d, want %d", ready.Code, http.StatusServiceUnavailable)
	}
}

func TestTelemetryReadinessSurfacesNilTelemetryInFleetMode(t *testing.T) {
	tests := []struct {
		name string
		mode string
		want string
	}{
		{name: "fleet without telemetry", mode: "fleet", want: "telemetry_not_configured"},
		{name: "fleet with debug mock without telemetry", mode: "fleet_with_debug_mock", want: "telemetry_not_configured"},
		{name: "mock only without telemetry", mode: "mock_only", want: ""},
		{name: "unconfigured without telemetry", mode: "unconfigured", want: ""},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			api := &app{mode: test.mode}
			if got := api.readinessTelemetryState(context.Background()); got != test.want {
				t.Fatalf("readinessTelemetryState() = %q, want %q", got, test.want)
			}
		})
	}
}

func TestMockProviderReturnsDevelopmentVehicle(t *testing.T) {
	vehicles, err := (mockProvider{}).Vehicles(context.Background(), "mock-user")
	if err != nil {
		t.Fatal(err)
	}
	if len(vehicles) != 1 || vehicles[0].DisplayName != "Development Model 3" {
		t.Fatalf("unexpected mock vehicles: %#v", vehicles)
	}
	if vehicles[0].Source != "mock_fixture" || vehicles[0].BatteryLevel != 76 {
		t.Fatalf("unexpected mock vehicle fields: %#v", vehicles[0])
	}
}

func TestMockProviderRejectsNonMockUser(t *testing.T) {
	vehicles, err := (mockProvider{}).Vehicles(context.Background(), "authenticated-user")
	if !errors.Is(err, errVehicleNotFound) || len(vehicles) != 0 {
		t.Fatalf("non-mock user mock vehicles = %#v, %v; want no vehicle and not found", vehicles, err)
	}
	if _, err := (mockProvider{}).Status(context.Background(), "authenticated-user", 1); !errors.Is(err, errVehicleNotFound) {
		t.Fatalf("non-mock user mock status error = %v, want not found", err)
	}
}

func TestMockHistoryReadinessReportsHistoryAvailable(t *testing.T) {
	a := &app{provider: mockProvider{}, mockEnabled: true, mockHistoryEnabled: true}
	response := dataReadinessResponseForTest(t, a, "mock-user", 1)
	items := readinessItemsByKey(response.Data.Items)
	for _, key := range []string{"drives", "charges"} {
		if items[key].Status != "available" || items[key].Source != "mock_fixture" {
			t.Fatalf("mock history readiness %s = %#v, want available", key, items[key])
		}
	}
}

func TestFleetProviderFailsClosed(t *testing.T) {
	if _, err := (unconfiguredProvider{}).Vehicles(context.Background(), "user-1"); err != errNotConfigured {
		t.Fatalf("Fleet provider error = %v, want %v", err, errNotConfigured)
	}
}

func TestVehicleOwnershipIsUserScoped(t *testing.T) {
	a := &app{provider: testProvider{vehicles: map[string][]vehicle{
		"user-a": {{ID: 11}},
		"user-b": {{ID: 22}},
	}}}
	if owned, err := a.vehicleOwned(context.Background(), "user-a", 11); err != nil || !owned {
		t.Fatalf("user-a vehicle ownership = %v, %v; want true", owned, err)
	}
	if owned, err := a.vehicleOwned(context.Background(), "user-a", 22); err != nil || owned {
		t.Fatalf("cross-user vehicle ownership = %v, %v; want false", owned, err)
	}
}

func TestVehicleOwnedPropagatesPersistenceErrors(t *testing.T) {
	pool := canceledTestPool(t)
	defer pool.Close()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	a := &app{
		store:    &store{pool: pool},
		provider: testProvider{vehicles: map[string][]vehicle{"user-a": {{ID: 11}}}},
	}
	owned, err := a.vehicleOwned(ctx, "user-a", 11)
	if owned || err == nil {
		t.Fatalf("vehicle ownership = %v, %v; want persistence error without provider fallback", owned, err)
	}
}

func TestVehicleLookupOnlyFallsBackForNoRows(t *testing.T) {
	tests := []struct {
		name string
		err  error
		want bool
	}{
		{name: "database sql no rows", err: sql.ErrNoRows, want: true},
		{name: "pgx no rows", err: pgx.ErrNoRows, want: true},
		{name: "translated vehicle miss", err: errors.New("vehicle_not_found"), want: true},
		{name: "persistence error", err: errors.New("database connection lost"), want: false},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := isVehicleLookupMiss(test.err); got != test.want {
				t.Fatalf("isVehicleLookupMiss(%v) = %v, want %v", test.err, got, test.want)
			}
		})
	}
}

func TestDataReadinessFailsClosedOnVehicleIdentityLookupError(t *testing.T) {
	pool := canceledTestPool(t)
	defer pool.Close()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	a := &app{
		store: &store{pool: pool},
		provider: testProvider{statuses: map[string]vehicleStatus{
			"user-a": {Source: "fleet_api"},
		}},
	}
	recorder := httptest.NewRecorder()
	a.dataReadiness(recorder, httptest.NewRequest(http.MethodGet, "/api/v1/cars/11/data-readiness", nil).WithContext(ctx), "user-a", 11)
	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("identity lookup readiness status = %d, body=%s; want %d", recorder.Code, recorder.Body.String(), http.StatusServiceUnavailable)
	}
	if strings.Contains(recorder.Body.String(), `"vehicle_uid"`) || !strings.Contains(recorder.Body.String(), `"error":"readiness_unavailable"`) {
		t.Fatalf("identity lookup response = %s", recorder.Body.String())
	}
}

func canceledTestPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	config, err := pgxpool.ParseConfig("postgres://127.0.0.1:1/jourvolt")
	if err != nil {
		t.Fatal(err)
	}
	config.MaxConns = 1
	pool, err := pgxpool.NewWithConfig(context.Background(), config)
	if err != nil {
		t.Fatal(err)
	}
	return pool
}

func TestHistoryMetadataDistinguishesFixtureAndCollection(t *testing.T) {
	a := &app{mockEnabled: true, mockHistoryEnabled: true}

	available := a.historyMeta("mock-user")
	if available["availability"] != "available" || available["source"] != "mock_fixture" {
		t.Fatalf("fixture metadata = %#v", available)
	}
	if available["coverage_percent"] != 100.0 {
		t.Fatalf("fixture coverage = %#v, want 100", available["coverage_percent"])
	}

	collecting := a.historyMeta("real-user")
	if collecting["availability"] != "collecting" || collecting["source"] != "fleet_api" {
		t.Fatalf("collection metadata = %#v", collecting)
	}
	if _, exists := collecting["coverage_percent"]; exists {
		t.Fatalf("collecting metadata must not invent coverage: %#v", collecting)
	}

	unsupported := a.unsupportedMeta("real-user")
	if unsupported["availability"] != "unsupported" {
		t.Fatalf("unsupported metadata = %#v", unsupported)
	}
}

func TestDataReadinessReportsOneShotCapabilities(t *testing.T) {
	observedAt := time.Date(2026, 8, 30, 12, 0, 0, 0, time.UTC)
	latitude, longitude, pressure := 31.23, 121.47, 0.0
	provider := testProvider{
		vehicles: map[string][]vehicle{"user-a": {{ID: 11}}},
		statuses: map[string]vehicleStatus{"user-a": {
			ObservedAt: observedAt, Source: "fleet_api", ProviderIdentity: "fleet-vehicle-a",
			Latitude: &latitude, Longitude: &longitude, TPMSPressureFL: &pressure,
		}},
	}
	a := &app{provider: provider}

	response := dataReadinessResponseForTest(t, a, "user-a", 11)
	if response.Data.CapabilityVersion != 1 || response.Data.VehicleUID == "" {
		t.Fatalf("readiness identity = %#v", response.Data)
	}
	if response.Data.VehicleUID == "fleet-vehicle-a" || response.Data.VehicleUID == "VIN" {
		t.Fatalf("readiness must expose an opaque vehicle UID: %q", response.Data.VehicleUID)
	}
	items := readinessItemsByKey(response.Data.Items)
	for key, want := range map[string]string{
		"live_status":    "available",
		"location":       "available",
		"tpms":           "available",
		"drives":         "collecting",
		"charges":        "collecting",
		"battery_health": "unsupported",
	} {
		if items[key].Status != want {
			t.Fatalf("%s status = %q, want %q; all items=%#v", key, items[key].Status, want, items)
		}
	}
	if items["live_status"].LastObservedAt == nil || *items["live_status"].LastObservedAt != observedAt.Format(time.RFC3339) {
		t.Fatalf("live status timestamp = %#v", items["live_status"].LastObservedAt)
	}
	if items["location"].LastObservedAt == nil || items["tpms"].LastObservedAt == nil {
		t.Fatalf("observed capability timestamps = location %#v tpms %#v", items["location"].LastObservedAt, items["tpms"].LastObservedAt)
	}
}

func TestDataReadinessWaitsForMissingVehicleTelemetry(t *testing.T) {
	a := &app{provider: testProvider{
		vehicles: map[string][]vehicle{"user-wait": {{ID: 7}}},
		statuses: map[string]vehicleStatus{"user-wait": {ObservedAt: time.Now().UTC(), Source: "fleet_api"}},
	}}

	response := dataReadinessResponseForTest(t, a, "user-wait", 7)
	items := readinessItemsByKey(response.Data.Items)
	for _, key := range []string{"location", "tpms"} {
		if items[key].Status != "waiting_vehicle" || items[key].MessageKey == "" || items[key].Action == "" {
			t.Fatalf("%s missing-telemetry item = %#v", key, items[key])
		}
		if items[key].LastObservedAt != nil {
			t.Fatalf("%s must not invent timestamp: %#v", key, items[key].LastObservedAt)
		}
	}
}

func TestDataReadinessReportsPairingRequiredWithoutLeakingProviderError(t *testing.T) {
	a := &app{provider: testProvider{
		vehicles:    map[string][]vehicle{"user-pair": {{ID: 8}}},
		statusError: map[string]error{"user-pair": errTeslaReauthorization},
	}}

	response := dataReadinessResponseForTest(t, a, "user-pair", 8)
	items := readinessItemsByKey(response.Data.Items)
	if items["live_status"].Status != "pairing_required" || items["live_status"].Action == "" {
		t.Fatalf("pairing readiness item = %#v", items["live_status"])
	}
}

func TestDataReadinessIsolatesVehiclesByAccount(t *testing.T) {
	a := &app{provider: testProvider{vehicles: map[string][]vehicle{
		"user-a": {{ID: 11}},
		"user-b": {{ID: 22}},
	}}}
	recorder := httptest.NewRecorder()
	a.carResource(recorder, httptest.NewRequest(http.MethodGet, "/api/v1/cars/11/data-readiness", nil), "user-b", "/api/v1/cars/11/data-readiness")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("cross-account readiness status = %d, want %d", recorder.Code, http.StatusNotFound)
	}
}

type readinessResponseForTest struct {
	Data struct {
		CapabilityVersion int                    `json:"capability_version"`
		VehicleUID        string                 `json:"vehicle_uid"`
		Items             []readinessItemForTest `json:"items"`
	} `json:"data"`
}

type readinessItemForTest struct {
	Key            string  `json:"key"`
	Status         string  `json:"status"`
	Source         string  `json:"source"`
	LastObservedAt *string `json:"last_observed_at"`
	MessageKey     string  `json:"message_key"`
	Action         string  `json:"action"`
}

func dataReadinessResponseForTest(t *testing.T, a *app, userID string, carID int) readinessResponseForTest {
	t.Helper()
	recorder := httptest.NewRecorder()
	path := "/api/v1/cars/" + strconv.Itoa(carID) + "/data-readiness"
	a.carResource(recorder, httptest.NewRequest(http.MethodGet, path, nil), userID, path)
	if recorder.Code != http.StatusOK {
		t.Fatalf("readiness status = %d, body=%s", recorder.Code, recorder.Body.String())
	}
	var response readinessResponseForTest
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatalf("decode readiness response: %v; body=%s", err, recorder.Body.String())
	}
	return response
}

func readinessItemsByKey(items []readinessItemForTest) map[string]readinessItemForTest {
	result := make(map[string]readinessItemForTest, len(items))
	for _, item := range items {
		result[item.Key] = item
	}
	return result
}
