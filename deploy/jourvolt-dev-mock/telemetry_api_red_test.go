package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func newTelemetryServiceForTest(partnerDomain string) *telemetryService {
	key := []byte(strings.Repeat("k", 32))
	cipher, _ := newTokenCipher(key)
	return &telemetryService{
		config:          &telemetryConfig{PartnerDomain: partnerDomain, PublicHost: "fleet.example.com", PublicPort: 4443, TopicBase: "jourvolt/telemetry", VINHashKey: []byte("test-vin-hash-key"), CommandTimeout: 5 * time.Second},
		memory:          newTelemetryMemoryStore(),
		vinHashKey:      []byte("test-vin-hash-key"),
		cipher:          cipher,
		caPEM:           "test-ca",
		httpClient:      http.DefaultClient,
		commandProxyURL: "",
	}
}

func telemetryRefWithVIN(service *telemetryService, userID string, vehicleID int, vin string) telemetryVehicleRef {
	encrypted, _ := service.cipher.encrypt(vin)
	return telemetryVehicleRef{UserID: userID, VehicleID: vehicleID, VINHash: keyedVINHash(service.vinHashKey, vin), VINCiphertext: encrypted, ProviderVehicleID: "provider-1"}
}

func timePointer(value time.Time) *time.Time { return &value }

var _ = base64.StdEncoding

func TestTelemetryPairingReturnsConfiguredVirtualKeyURLWithoutVIN(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryRefWithVIN(service, "user-a", 1, "5YJ3E1EA7KF123456")
	service.memory.registerVehicle(ref)
	service.memory.setPairing(ref, telemetryPairing{Status: "pairing_required"})
	a := &app{telemetry: service, provider: testProvider{vehicles: map[string][]vehicle{"user-a": {{ID: 1}}}}}
	recorder := httptest.NewRecorder()
	a.carResource(recorder, httptest.NewRequest(http.MethodGet, "/api/v1/cars/1/telemetry/pairing", nil), "user-a", "/api/v1/cars/1/telemetry/pairing")
	if recorder.Code != http.StatusOK {
		t.Fatalf("pairing status = %d, body=%s", recorder.Code, recorder.Body.String())
	}
	var envelope struct {
		Data telemetryPairingResponse `json:"data"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &envelope); err != nil {
		t.Fatal(err)
	}
	if envelope.Data.Status != "pairing_required" || envelope.Data.VirtualKeyURL != "https://tesla.com/_ak/partner.example.com" {
		t.Fatalf("pairing response = %#v", envelope.Data)
	}
	if strings.Contains(recorder.Body.String(), "5YJ3E1EA7KF123456") {
		t.Fatal("pairing response leaked plaintext VIN")
	}
}

func TestTelemetryConfigureCallsProxyWithDesiredIntervalsAndMapsErrorsWithoutBody(t *testing.T) {
	var received struct {
		Config telemetryDesiredConfiguration `json:"config"`
	}
	proxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/api/1/vehicles/fleet_telemetry_config" {
			t.Errorf("proxy request = %s %s", r.Method, r.URL.Path)
		}
		if err := json.NewDecoder(r.Body).Decode(&received); err != nil {
			t.Errorf("proxy request body: %v", err)
		}
		w.WriteHeader(http.StatusPaymentRequired)
		_, _ = w.Write([]byte(`{"error":"billing_blocked","vin":"5YJ3E1EA7KF123456"}`))
	}))
	defer proxy.Close()
	service := newTelemetryServiceForTest("partner.example.com")
	service.commandProxyURL = proxy.URL
	ref := telemetryRefWithVIN(service, "user-a", 1, "5YJ3E1EA7KF123456")
	service.memory.registerVehicle(ref)
	a := &app{telemetry: service, provider: testProvider{vehicles: map[string][]vehicle{"user-a": {{ID: 1}}}}}
	recorder := httptest.NewRecorder()
	a.carResource(recorder, httptest.NewRequest(http.MethodPost, "/api/v1/cars/1/telemetry/configure", strings.NewReader(`{}`)), "user-a", "/api/v1/cars/1/telemetry/configure")
	if recorder.Code != http.StatusPaymentRequired || strings.Contains(recorder.Body.String(), "billing_blocked") == false {
		t.Fatalf("configure response = %d %s", recorder.Code, recorder.Body.String())
	}
	if received.Config.Fields["VehicleSpeed"].IntervalSeconds != 10 || received.Config.Fields["Odometer"].IntervalSeconds != 60 || len(received.Config.Fields) == 0 {
		t.Fatalf("desired telemetry config = %#v", received.Config)
	}
	if strings.Contains(recorder.Body.String(), "5YJ3E1EA7KF123456") || strings.Contains(recorder.Body.String(), "vin") {
		t.Fatalf("configure response leaked proxy body: %s", recorder.Body.String())
	}
}

func TestGeneratedSessionsAreVisibleInDriveAndChargeHistoryWithCollectionMetadata(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "hash", ProviderVehicleID: "provider-1"}
	service.memory.registerVehicle(ref)
	start := time.Unix(100, 0).UTC()
	service.memory.addCompletedSession(ref, telemetrySession{ID: "drive-1", Kind: "drive", StartAt: start, EndAt: timePointer(start.Add(time.Minute))})
	service.memory.addCompletedSession(ref, telemetrySession{ID: "charge-1", Kind: "charge", StartAt: start, EndAt: timePointer(start.Add(2 * time.Hour))})
	a := &app{telemetry: service, provider: testProvider{vehicles: map[string][]vehicle{"user-a": {{ID: 1}}}}}
	for path, key := range map[string]string{"/api/v1/cars/1/drives": "drives", "/api/v1/cars/1/charges": "charges"} {
		recorder := httptest.NewRecorder()
		a.carResource(recorder, httptest.NewRequest(http.MethodGet, path, nil), "user-a", path)
		if recorder.Code != http.StatusOK || !strings.Contains(recorder.Body.String(), `"`+key+`"`) || !strings.Contains(recorder.Body.String(), `"availability":"available"`) {
			t.Fatalf("%s response = %d %s", path, recorder.Code, recorder.Body.String())
		}
	}
}

func TestTelemetryReadinessMapsAllOperationalStates(t *testing.T) {
	for _, state := range []string{"pairing_required", "waiting_vehicle", "collecting", "available", "telemetry_error", "billing_blocked"} {
		if got := telemetryReadinessItem(state); got.Status != state {
			t.Fatalf("readiness(%q) = %#v", state, got)
		}
	}
}

func TestTelemetryStatusUsesFallbackOnlyWhenSnapshotIsStale(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "hash", ProviderVehicleID: "provider-1", DisplayName: "Tesla"}
	service.memory.registerVehicle(ref)
	observed := time.Now().UTC().Add(-time.Second)
	service.memory.putLatest(ref, "Soc", float64(0), observed)
	base := vehicleStatus{BatteryLevel: intPointer(76), Locked: boolPointer(true), Source: "fleet_api"}
	merged, fallback := service.mergeOrFallback(context.Background(), ref, base, time.Now().UTC())
	if fallback || merged.BatteryLevel == nil || *merged.BatteryLevel != 0 || merged.Locked == nil || !*merged.Locked {
		t.Fatalf("fresh snapshot fallback/merge = %v %#v", fallback, merged)
	}
	service.memory.putLatest(ref, "Soc", float64(0), time.Now().UTC().Add(-10*time.Minute))
	_, fallback = service.mergeOrFallback(context.Background(), ref, base, time.Now().UTC())
	if !fallback {
		t.Fatal("stale telemetry must use one-shot fallback")
	}
}

func TestTelemetryStatusDoesNotCallVehicleDataWhenFreshTelemetryExists(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "hash", ProviderVehicleID: "provider-1", DisplayName: "Tesla"}
	service.memory.registerVehicle(ref)
	service.memory.putLatest(ref, "Soc", float64(0), time.Now().UTC())
	a := &app{
		telemetry: service,
		provider:  testProvider{statusError: map[string]error{"user-a": errNotConfigured}},
	}
	recorder := httptest.NewRecorder()
	a.status(recorder, httptest.NewRequest(http.MethodGet, "/api/v1/cars/1/status", nil), "user-a", 1, true)
	if recorder.Code != http.StatusOK || !strings.Contains(recorder.Body.String(), `"battery_level":0`) {
		t.Fatalf("fresh telemetry status = %d %s", recorder.Code, recorder.Body.String())
	}
}
