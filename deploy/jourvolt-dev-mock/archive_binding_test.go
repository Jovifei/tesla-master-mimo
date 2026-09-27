package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"
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
			Token          string   `json:"token"`
			Scope          string   `json:"scope"`
			Scopes         []string `json:"scopes"`
			VehicleID      int      `json:"vehicle_id"`
			SourceType     string   `json:"source_type"`
			SourceInstance string   `json:"source_instance_id"`
			SourceVehicle  string   `json:"source_vehicle_id"`
			ExpiresAt      string   `json:"expires_at"`
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
	var response struct {
		Data struct {
			Token string `json:"token"`
		} `json:"data"`
	}
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

func TestArchiveBindingStatusAndRevokeExposeState(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "status-hash", ProviderVehicleID: "provider-1"}
	service.memory.registerVehicle(ref)
	a := &app{telemetry: service, provider: testProvider{vehicles: map[string][]vehicle{"user-a": {{ID: 1}}}}}
	token := issueArchiveBindingForTest(t, a, ref.UserID, ref.VehicleID, "home-instance", "teslamate-car-1")
	queryPath := "/api/v1/cars/1/history/archive/bind?source_instance_id=home-instance&source_vehicle_id=teslamate-car-1"

	status := httptest.NewRecorder()
	a.carResource(status, httptest.NewRequest(http.MethodGet, queryPath, nil), ref.UserID, "/api/v1/cars/1/history/archive/bind")
	if status.Code != http.StatusOK || !strings.Contains(status.Body.String(), `"state":"active"`) || !strings.Contains(status.Body.String(), `"created_at"`) {
		t.Fatalf("active binding status = %d %s", status.Code, status.Body.String())
	}

	revoke := httptest.NewRecorder()
	a.carResource(revoke, httptest.NewRequest(http.MethodDelete, queryPath, nil), ref.UserID, "/api/v1/cars/1/history/archive/bind")
	if revoke.Code != http.StatusOK || !strings.Contains(revoke.Body.String(), `"state":"revoked"`) || !strings.Contains(revoke.Body.String(), `"revoked_at"`) {
		t.Fatalf("revoked binding response = %d %s", revoke.Code, revoke.Body.String())
	}

	request := archiveImportRequestForTest(token, "home-instance", "teslamate-car-1")
	imported := httptest.NewRecorder()
	a.carResource(imported, request, ref.UserID, "/api/v1/cars/1/history/archive/import")
	if imported.Code != http.StatusForbidden || !strings.Contains(imported.Body.String(), `"error":"archive_binding_revoked"`) {
		t.Fatalf("revoked archive import = %d %s", imported.Code, imported.Body.String())
	}
}

func TestArchiveBindingRejectsExpiredToken(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "expired-hash", ProviderVehicleID: "provider-1"}
	service.memory.registerVehicle(ref)
	a := &app{telemetry: service, provider: testProvider{vehicles: map[string][]vehicle{"user-a": {{ID: 1}}}}}
	token := mustRandomToken(t)
	service.memory.createArchiveBindingAt(token, ref.UserID, ref.VehicleID, archiveBindingRequest{
		SourceType: "teslamate", SourceInstanceID: "home-instance", SourceVehicleID: "teslamate-car-1",
	}, time.Now().UTC().Add(-2*time.Hour), time.Now().UTC().Add(-time.Hour))

	recorder := httptest.NewRecorder()
	a.carResource(recorder, archiveImportRequestForTest(token, "home-instance", "teslamate-car-1"), ref.UserID, "/api/v1/cars/1/history/archive/import")
	if recorder.Code != http.StatusForbidden || !strings.Contains(recorder.Body.String(), `"error":"archive_binding_expired"`) {
		t.Fatalf("expired archive import = %d %s", recorder.Code, recorder.Body.String())
	}
}

func TestArchiveImportRejectsUnknownAndWrongVehicleBindings(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "wrong-scope-hash", ProviderVehicleID: "provider-1"}
	service.memory.registerVehicle(ref)
	a := &app{telemetry: service, provider: testProvider{vehicles: map[string][]vehicle{"user-a": {{ID: 1}, {ID: 2}}}}}
	token := issueArchiveBindingForTest(t, a, ref.UserID, ref.VehicleID, "home-instance", "teslamate-car-1")

	unknown := archiveImportRequestForTest("not-issued", "home-instance", "teslamate-car-1")
	unknownRecorder := httptest.NewRecorder()
	a.ServeHTTP(unknownRecorder, unknown)
	if unknownRecorder.Code != http.StatusUnauthorized || !strings.Contains(unknownRecorder.Body.String(), `"error":"archive_binding_invalid"`) {
		t.Fatalf("unknown archive binding = %d %s", unknownRecorder.Code, unknownRecorder.Body.String())
	}

	wrongVehicle := archiveImportRequestForVehicleTest(token, 2, "home-instance", "teslamate-car-1")
	wrongVehicleRecorder := httptest.NewRecorder()
	a.ServeHTTP(wrongVehicleRecorder, wrongVehicle)
	if wrongVehicleRecorder.Code != http.StatusForbidden || !strings.Contains(wrongVehicleRecorder.Body.String(), `"error":"archive_binding_scope_mismatch"`) {
		t.Fatalf("wrong vehicle archive binding = %d %s", wrongVehicleRecorder.Code, wrongVehicleRecorder.Body.String())
	}
}

func TestArchiveBindingIsolatesUsersAndVehicles(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	service.memory.registerVehicle(telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "user-a-hash", ProviderVehicleID: "provider-a"})
	service.memory.registerVehicle(telemetryVehicleRef{UserID: "user-b", VehicleID: 1, VINHash: "user-b-hash", ProviderVehicleID: "provider-b"})
	a := &app{telemetry: service, provider: testProvider{vehicles: map[string][]vehicle{
		"user-a": {{ID: 1}}, "user-b": {{ID: 1}},
	}}}
	token := issueArchiveBindingForTest(t, a, "user-a", 1, "home-instance", "teslamate-car-1")

	wrongUser := httptest.NewRecorder()
	a.carResource(wrongUser, archiveImportRequestForTest(token, "home-instance", "teslamate-car-1"), "user-b", "/api/v1/cars/1/history/archive/import")
	if wrongUser.Code != http.StatusForbidden || !strings.Contains(wrongUser.Body.String(), `"error":"archive_binding_scope_mismatch"`) {
		t.Fatalf("cross-user archive import = %d %s", wrongUser.Code, wrongUser.Body.String())
	}
	if got := len(service.memory.sessions("user-b", 1, "drive")); got != 0 {
		t.Fatalf("cross-user import created %d session(s)", got)
	}

	wrongUserRevoke := httptest.NewRecorder()
	path := "/api/v1/cars/1/history/archive/bind"
	request := httptest.NewRequest(http.MethodDelete, path+"?source_instance_id=home-instance&source_vehicle_id=teslamate-car-1", nil)
	a.carResource(wrongUserRevoke, request, "user-b", path)
	if wrongUserRevoke.Code != http.StatusNotFound {
		t.Fatalf("cross-user revoke = %d %s; want 404", wrongUserRevoke.Code, wrongUserRevoke.Body.String())
	}
	validOwner := httptest.NewRecorder()
	a.carResource(validOwner, archiveImportRequestForTest(token, "home-instance", "teslamate-car-1"), "user-a", "/api/v1/cars/1/history/archive/import")
	if validOwner.Code != http.StatusOK {
		t.Fatalf("owner archive import after cross-user revoke = %d %s", validOwner.Code, validOwner.Body.String())
	}
}

func TestArchiveImportWithoutBearerPreservesSourceRecordIdempotency(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "idempotency-hash", ProviderVehicleID: "provider-1"}
	service.memory.registerVehicle(ref)
	a := &app{telemetry: service, provider: testProvider{vehicles: map[string][]vehicle{"user-a": {{ID: 1}}}}}
	token := issueArchiveBindingForTest(t, a, ref.UserID, ref.VehicleID, "home-instance", "teslamate-car-1")

	first := archiveImportRequestForTest(token, "home-instance", "teslamate-car-1")
	firstRecorder := httptest.NewRecorder()
	a.ServeHTTP(firstRecorder, first)
	if firstRecorder.Code != http.StatusOK {
		t.Fatalf("bearerless archive import = %d %s", firstRecorder.Code, firstRecorder.Body.String())
	}
	second := archiveImportRequestForTest(token, "home-instance", "teslamate-car-1")
	secondRecorder := httptest.NewRecorder()
	a.ServeHTTP(secondRecorder, second)
	if secondRecorder.Code != http.StatusOK {
		t.Fatalf("replayed archive import = %d %s", secondRecorder.Code, secondRecorder.Body.String())
	}
	if got := len(service.memory.sessions(ref.UserID, ref.VehicleID, "drive")); got != 1 {
		t.Fatalf("idempotent archive import sessions = %d, want 1", got)
	}
}

func issueArchiveBindingForTest(t *testing.T, a *app, userID string, vehicleID int, sourceInstanceID, sourceVehicleID string) string {
	t.Helper()
	path := "/api/v1/cars/" + strconv.Itoa(vehicleID) + "/history/archive/bind"
	recorder := httptest.NewRecorder()
	a.carResource(recorder, httptest.NewRequest(http.MethodPost, path, strings.NewReader(`{"source_type":"teslamate","source_instance_id":"`+sourceInstanceID+`","source_vehicle_id":"`+sourceVehicleID+`"}`)), userID, path)
	if recorder.Code != http.StatusOK {
		t.Fatalf("issue archive binding status = %d, body=%s", recorder.Code, recorder.Body.String())
	}
	var response struct {
		Data struct {
			Token string `json:"token"`
		} `json:"data"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if response.Data.Token == "" {
		t.Fatal("archive binding response omitted token")
	}
	return response.Data.Token
}

func archiveImportRequestForTest(token, sourceInstanceID, sourceVehicleID string) *http.Request {
	return archiveImportRequestForVehicleTest(token, 1, sourceInstanceID, sourceVehicleID)
}

func archiveImportRequestForVehicleTest(token string, vehicleID int, sourceInstanceID, sourceVehicleID string) *http.Request {
	body := `{"source":"teslamate","source_instance_id":"` + sourceInstanceID + `","source_vehicle_id":"` + sourceVehicleID + `","chunk_id":"chunk-1","drives":[{"session_id":"drive-1","source_record_id":"record-1","started_at":"2026-09-01T10:00:00Z","ended_at":"2026-09-01T10:01:00Z"}]}`
	request := httptest.NewRequest(http.MethodPost, "/api/v1/cars/"+strconv.Itoa(vehicleID)+"/history/archive/import", strings.NewReader(body))
	request.Header.Set(archiveBindingHeader, token)
	return request
}
