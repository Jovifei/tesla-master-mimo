package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

func TestTaskDPairingURLUsesTeslaVirtualKeyFormat(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	response, err := service.pairing(nil, "user-a", 1)
	if err != nil {
		t.Fatal(err)
	}
	if response.VirtualKeyURL != "https://tesla.com/_ak/partner.example.com" {
		t.Fatalf("virtual key URL = %q", response.VirtualKeyURL)
	}
}

func TestTaskDConfigureUsesOfficialFleetTelemetryConfigBodyAndPollsSynced(t *testing.T) {
	var postBody map[string]any
	gets := 0
	proxy := newTestHTTPServer(t, func(method, path string, body []byte) (int, string) {
		if method == "POST" {
			if path != "/api/1/vehicles/fleet_telemetry_config" {
				t.Fatalf("configure path = %q", path)
			}
			if err := json.Unmarshal(body, &postBody); err != nil {
				t.Fatal(err)
			}
			return 200, `{"response":{"updated_vehicles":1,"skipped_vehicles":{"missing_key":[]}}}`
		}
		gets++
		return 200, `{"response":{"synced":true,"config":{"hostname":"fleet.example.com"}}}`
	})
	defer proxy.Close()
	service := newTelemetryServiceForTest("partner.example.com")
	service.commandProxyURL = proxy.URL
	service.memory.registerVehicle(telemetryRefWithVIN(service, "user-a", 1, "5YJ3E1EA7KF123456"))
	if err := service.configure(nil, "user-a", 1); err != nil {
		t.Fatal(err)
	}
	config, ok := postBody["config"].(map[string]any)
	if !ok || config["hostname"] != "fleet.example.com" || config["port"] != float64(4443) || config["ca"] == "" {
		t.Fatalf("official config = %#v", postBody)
	}
	if _, ok := config["fields"].(map[string]any); !ok {
		t.Fatalf("fields must be an object: %#v", config["fields"])
	}
	if gets == 0 {
		t.Fatal("configure must poll/get synced state")
	}
}

func TestTaskDConfigureMapsSkippedMissingKeyWithoutExposingProviderBody(t *testing.T) {
	proxy := newTestHTTPServer(t, func(method, _ string, _ []byte) (int, string) {
		if method == "POST" {
			return 200, `{"response":{"updated_vehicles":0,"skipped_vehicles":{"missing_key":["5YJ3E1EA7KF123456"]}}}`
		}
		return 200, `{"response":{"synced":false}}`
	})
	defer proxy.Close()
	service := newTelemetryServiceForTest("partner.example.com")
	service.commandProxyURL = proxy.URL
	service.memory.registerVehicle(telemetryRefWithVIN(service, "user-a", 1, "5YJ3E1EA7KF123456"))
	err := service.configure(nil, "user-a", 1)
	if !errorsIsAny(err, errTelemetryPairing) {
		t.Fatalf("missing_key error = %v", err)
	}
	if strings.Contains(err.Error(), "5YJ3E1EA7KF123456") {
		t.Fatalf("missing_key error leaked VIN: %v", err)
	}
}

func TestTaskDMosquittoUsesDurablePersistence(t *testing.T) {
	data, err := os.ReadFile("fleet-telemetry/mosquitto.conf")
	if err != nil {
		t.Fatal(err)
	}
	text := string(data)
	if !strings.Contains(text, "persistence true") || !strings.Contains(text, "persistence_location") {
		t.Fatalf("mosquitto persistence config = %q", text)
	}
	if strings.Contains(text, "allow_anonymous true") {
		t.Fatal("mosquitto must not allow anonymous access")
	}
}

func TestTaskDFieldFreshnessIsPerField(t *testing.T) {
	now := time.Now().UTC()
	base := vehicleStatus{BatteryLevel: intPointer(10), Locked: boolPointer(true)}
	snapshot := telemetrySnapshot{Source: "telemetry_mqtt", Fields: map[string]any{
		"Soc": float64(20), "Locked": false,
	}, FieldObservedAt: map[string]time.Time{
		"Soc": now.Add(-time.Second), "Locked": now.Add(-10 * time.Minute),
	}}
	merged := mergeFreshTelemetryStatus(base, snapshot, now)
	if merged.BatteryLevel == nil || *merged.BatteryLevel != 20 || merged.Locked == nil || !*merged.Locked {
		t.Fatalf("field freshness merge = %#v", merged)
	}
}

func TestTaskDRoutePointPreservesObservedTimeSpeedAndHeading(t *testing.T) {
	point, ok := routePointFromLocation(telemetrySessionEvent{ObservedAt: time.Unix(10, 0).UTC(), Value: map[string]any{
		"latitude": 31.2, "longitude": 121.4, "speed": 12.5, "heading": 90.0,
	}})
	if !ok || point.Speed == nil || *point.Speed != 12.5 || point.Heading == nil || *point.Heading != 90 || !point.ObservedAt.Equal(time.Unix(10, 0).UTC()) {
		t.Fatalf("route point = %#v", point)
	}
}

func TestTaskDChargeDetailContainsPersistedSummaryData(t *testing.T) {
	energy := 12.5
	item := historySessionMap(telemetrySession{ID: "charge-1", Kind: "charge", StartAt: time.Unix(10, 0).UTC(), EndAt: timePointer(time.Unix(70, 0).UTC()), EnergyAdded: &energy}, "charge", 0)
	details, ok := item["charge_details"].([]map[string]any)
	if !ok || len(details) == 0 || item["charge_energy_added"] == nil {
		t.Fatalf("charge summary = %#v", item)
	}
}

func TestTaskDTeslaLogsContainOnlySafeErrorClass(t *testing.T) {
	unsafe := []byte(`{"error":"billing_blocked","vin":"5YJ3E1EA7KF123456","authorization":"Bearer secret-token","latitude":31.2304,"longitude":121.4737}`)
	got := teslaAPILogBody(unsafe)
	for _, secret := range []string{"5YJ3E1EA7KF123456", "secret-token", "31.2304", "121.4737"} {
		if strings.Contains(got, secret) {
			t.Fatalf("safe Tesla log contains %q: %s", secret, got)
		}
	}
}

func TestTaskDTrailingJSONContentIsRejected(t *testing.T) {
	if _, err := normalizeTelemetryValue("VehicleSpeed", json.RawMessage(`12.5 true`)); err == nil {
		t.Fatal("trailing JSON content must be rejected")
	}
}

func TestTaskDKeyFailureHasNoFixedFallbackKey(t *testing.T) {
	data, err := os.ReadFile("telemetry_config.go")
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), "jourvolt-telemetry-test-key") {
		t.Fatal("crypto/rand failure must not use a fixed fallback key")
	}
}

func newTestHTTPServer(t *testing.T, handler func(method, path string, body []byte) (int, string)) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		status, response := handler(r.Method, r.URL.Path, body)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = w.Write([]byte(response))
	}))
}

func errorsIsAny(err error, expected error) bool {
	return err != nil && (err == expected || strings.Contains(err.Error(), expected.Error()))
}
