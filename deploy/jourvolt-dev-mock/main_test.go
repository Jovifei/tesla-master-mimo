package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

type testProvider struct {
	vehicles map[string][]vehicle
}

func (p testProvider) Vehicles(_ context.Context, userID string) ([]vehicle, error) {
	return p.vehicles[userID], nil
}

func (testProvider) Status(context.Context, string, int) (vehicleStatus, error) {
	return vehicleStatus{}, nil
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

func TestMockProviderReturnsDevelopmentVehicle(t *testing.T) {
	vehicles, err := (mockProvider{}).Vehicles(context.Background(), "user-1")
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
