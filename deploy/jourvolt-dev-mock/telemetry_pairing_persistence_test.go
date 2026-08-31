package main

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestTask2ConfigureFailsClosedWhenConfigTruthPersistenceFails(t *testing.T) {
	proxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			_, _ = w.Write([]byte(`{"response":{"updated_vehicles":1,"skipped_vehicles":{"missing_key":[]}}}`))
			return
		}
		_, _ = w.Write([]byte(`{"response":{"synced":true}}`))
	}))
	defer proxy.Close()

	service := newTelemetryServiceForTest("partner.example.com")
	service.commandProxyURL = proxy.URL
	ref := telemetryRefWithVIN(service, "user-a", 1, "5YJ3E1EA7KF123456")
	service.memory.registerVehicle(ref)
	service.memory.setPairing(ref, telemetryPairing{Status: "available", ConfigSynced: boolPointer(true)})
	service.pairingConfigTruthWriter = func(context.Context, string, int, string, bool) error {
		return errors.New("postgres write failed")
	}
	a := &app{telemetry: service, provider: testProvider{vehicles: map[string][]vehicle{"user-a": {{ID: 1}}}}}

	recorder := httptest.NewRecorder()
	a.carResource(recorder, httptest.NewRequest(http.MethodPost, "/api/v1/cars/1/telemetry/configure", strings.NewReader(`{}`)), ref.UserID, "/api/v1/cars/1/telemetry/configure")
	if recorder.Code != http.StatusBadGateway || !strings.Contains(recorder.Body.String(), `"error":"telemetry_error"`) {
		t.Fatalf("configure response = %d %s; want 502 telemetry_error", recorder.Code, recorder.Body.String())
	}

	pairing, err := service.pairing(context.Background(), ref.UserID, ref.VehicleID)
	if err != nil || pairing.ConfigSynced == nil || !*pairing.ConfigSynced {
		t.Fatalf("last persisted config truth after failed write = %#v, %v; want true", pairing, err)
	}
}
