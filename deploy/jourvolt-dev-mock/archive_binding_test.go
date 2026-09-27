package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestArchiveBindingReturnsScopedOneTimeToken(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "hash", ProviderVehicleID: "provider-1"}
	service.memory.registerVehicle(ref)
	a := &app{telemetry: service, provider: testProvider{vehicles: map[string][]vehicle{"user-a": {{ID: 1}}}}}

	recorder := httptest.NewRecorder()
	body := `{"source_type":"teslamate","source_instance_id":"home-instance","source_vehicle_id":"teslamate-car-1"}`
	a.carResource(recorder, httptest.NewRequest(http.MethodPost, "/api/v1/cars/1/history/archive/bind", strings.NewReader(body)), ref.UserID, "/api/v1/cars/1/history/archive/bind")
	if recorder.Code != http.StatusOK {
		t.Fatalf("bind status = %d, body=%s", recorder.Code, recorder.Body.String())
	}

	var envelope struct {
		Data struct {
			Token           string   `json:"token"`
			Scope           string   `json:"scope"`
			Scopes          []string `json:"scopes"`
			VehicleID       int      `json:"vehicle_id"`
			SourceType      string   `json:"source_type"`
			SourceInstance  string   `json:"source_instance_id"`
			SourceVehicle   string   `json:"source_vehicle_id"`
			ExpiresAt       string   `json:"expires_at"`
		} `json:"data"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &envelope); err != nil {
		t.Fatal(err)
	}
	if envelope.Data.Token == "" || len(envelope.Data.Token) != 64 {
		t.Fatalf("binding token = %q, want a 32-byte hex token", envelope.Data.Token)
	}
	if envelope.Data.Scope == "" || len(envelope.Data.Scopes) != 1 || envelope.Data.Scopes[0] != envelope.Data.Scope {
		t.Fatalf("binding scopes = %#v/%q", envelope.Data.Scopes, envelope.Data.Scope)
	}
	if envelope.Data.VehicleID != 1 || envelope.Data.SourceType != "teslamate" || envelope.Data.SourceInstance != "home-instance" || envelope.Data.SourceVehicle != "teslamate-car-1" || envelope.Data.ExpiresAt == "" {
		t.Fatalf("binding metadata = %#v", envelope.Data)
	}

	stored := fmt.Sprintf("%#v", service.memory)
	if strings.Contains(stored, envelope.Data.Token) || !strings.Contains(stored, hashToken(envelope.Data.Token)) {
		t.Fatalf("memory binding must retain only the token hash: %s", stored)
	}
}

func TestArchiveImportRequiresBindingBeforeVehicleLookup(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	a := &app{telemetry: service, provider: unconfiguredProvider{}}
	body := `{"source":"teslamate","source_instance_id":"home-instance","source_vehicle_id":"teslamate-car-1","chunk_id":"chunk-1","drives":[{"session_id":"drive-1","source_record_id":"record-1","started_at":"2026-09-01T10:00:00Z","ended_at":"2026-09-01T10:01:00Z"}]}`

	recorder := httptest.NewRecorder()
	a.carResource(recorder, httptest.NewRequest(http.MethodPost, "/api/v1/cars/1/history/archive/import", strings.NewReader(body)), "user-a", "/api/v1/cars/1/history/archive/import")
	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("missing binding status = %d, body=%s; want auth before vehicle lookup", recorder.Code, recorder.Body.String())
	}
}

func TestArchiveBindingAuthenticatesImportAndScopesSource(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "hash", ProviderVehicleID: "provider-1"}
	service.memory.registerVehicle(ref)
	a := &app{telemetry: service, provider: testProvider{vehicles: map[string][]vehicle{"user-a": {{ID: 1}}}}}

	bind := httptest.NewRecorder()
	a.carResource(bind, httptest.NewRequest(http.MethodPost, "/api/v1/cars/1/history/archive/bind", strings.NewReader(`{"source_type":"teslamate","source_instance_id":"home-instance","source_vehicle_id":"teslamate-car-1"}`)), ref.UserID, "/api/v1/cars/1/history/archive/bind")
	if bind.Code != http.StatusOK {
		t.Fatalf("bind status = %d, body=%s", bind.Code, bind.Body.String())
	}
	var response struct{ Data struct{ Token string `json:"token"` } `json:"data"` }
	if err := json.Unmarshal(bind.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}

	payload := `{"source":"teslamate","source_instance_id":"home-instance","source_vehicle_id":"teslamate-car-1","chunk_id":"chunk-1","drives":[{"session_id":"drive-1","source_record_id":"record-1","started_at":"2026-09-01T10:00:00Z","ended_at":"2026-09-01T10:01:00Z"}]}`
	record := httptest.NewRequest(http.MethodPost, "/api/v1/cars/1/history/archive/import", bytes.NewBufferString(payload))
	record.Header.Set("X-MateLink-Archive-Binding", response.Data.Token)
	recorder := httptest.NewRecorder()
	a.carResource(recorder, record, ref.UserID, "/api/v1/cars/1/history/archive/import")
	if recorder.Code != http.StatusOK {
		t.Fatalf("archive import status = %d, body=%s", recorder.Code, recorder.Body.String())
	}

	wrongSource := httptest.NewRequest(http.MethodPost, "/api/v1/cars/1/history/archive/import", strings.NewReader(strings.ReplaceAll(payload, "home-instance", "other-instance")))
	wrongSource.Header.Set("X-MateLink-Archive-Binding", response.Data.Token)
	wrongRecorder := httptest.NewRecorder()
	a.carResource(wrongRecorder, wrongSource, ref.UserID, "/api/v1/cars/1/history/archive/import")
	if wrongRecorder.Code != http.StatusForbidden {
		t.Fatalf("wrong source status = %d, body=%s", wrongRecorder.Code, wrongRecorder.Body.String())
	}

	_ = context.Background()
}
