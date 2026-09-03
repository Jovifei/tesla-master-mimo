package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func importRequestJSON(t *testing.T, drives, charges []historyImportSession) string {
	t.Helper()
	encoded, err := json.Marshal(historyImportRequest{Drives: drives, Charges: charges})
	if err != nil {
		t.Fatal(err)
	}
	return string(encoded)
}

func rfc3339(day int) string {
	return time.Date(2026, 9, day, 10, 0, 0, 0, time.UTC).Format(time.RFC3339)
}

func TestHistoryImportPersistsDriveWithRoutePoints(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "hash", ProviderVehicleID: "provider-1"}
	service.memory.registerVehicle(ref)
	a := &app{telemetry: service, provider: testProvider{vehicles: map[string][]vehicle{"user-a": {{ID: 1}}}}}

	payload := importRequestJSON(t, []historyImportSession{{
		SessionID: "drive-import-1",
		StartedAt: rfc3339(1), EndedAt: rfc3339(1),
		Route: []historyImportRoutePoint{
			{Date: rfc3339(1), Latitude: floatPointer(37.0), Longitude: floatPointer(-122.0), Speed: floatPointer(30.0)},
			{Date: rfc3339(1), Latitude: floatPointer(37.1), Longitude: floatPointer(-122.1), Speed: floatPointer(40.0)},
		},
	}}, nil)

	recorder := httptest.NewRecorder()
	a.carResource(recorder, httptest.NewRequest(http.MethodPost, "/api/v1/cars/1/history/import", strings.NewReader(payload)), "user-a", "/api/v1/cars/1/history/import")
	if recorder.Code != http.StatusOK {
		t.Fatalf("import response = %d %s", recorder.Code, recorder.Body.String())
	}
	var envelope struct {
		Data historyImportResult `json:"data"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &envelope); err != nil {
		t.Fatal(err)
	}
	if envelope.Data.ImportedDrives != 1 || envelope.Data.ImportedCharges != 0 {
		t.Fatalf("import result = %#v", envelope.Data)
	}

	sessions := service.memory.sessions("user-a", 1, "drive")
	if len(sessions) != 1 {
		t.Fatalf("stored sessions = %d, want 1", len(sessions))
	}
	if len(sessions[0].Route) != 2 || sessions[0].Route[0].Latitude != 37.0 {
		t.Fatalf("stored route = %#v", sessions[0].Route)
	}
}

func TestHistoryImportPersistsCharge(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "hash", ProviderVehicleID: "provider-1"}
	service.memory.registerVehicle(ref)
	a := &app{telemetry: service, provider: testProvider{vehicles: map[string][]vehicle{"user-a": {{ID: 1}}}}}

	payload := importRequestJSON(t, nil, []historyImportSession{{
		SessionID: "charge-import-1",
		StartedAt: rfc3339(2), EndedAt: rfc3339(2),
		EnergyAdded: floatPointer(40.0),
	}})

	recorder := httptest.NewRecorder()
	a.carResource(recorder, httptest.NewRequest(http.MethodPost, "/api/v1/cars/1/history/import", strings.NewReader(payload)), "user-a", "/api/v1/cars/1/history/import")
	if recorder.Code != http.StatusOK {
		t.Fatalf("import response = %d %s", recorder.Code, recorder.Body.String())
	}
	sessions := service.memory.sessions("user-a", 1, "charge")
	if len(sessions) != 1 || sessions[0].EnergyAdded == nil || *sessions[0].EnergyAdded != 40.0 {
		t.Fatalf("stored charge = %#v", sessions)
	}
}

func TestHistoryImportRetainsOnlyLatestTwoDataDaysPerAccount(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "hash", ProviderVehicleID: "provider-1"}
	service.memory.registerVehicle(ref)
	a := &app{telemetry: service, provider: testProvider{vehicles: map[string][]vehicle{"user-a": {{ID: 1}}}}}

	// Three distinct days of data. The account keeps only the latest two (9/3, 9/2).
	payload := importRequestJSON(t, []historyImportSession{
		{SessionID: "d1", StartedAt: rfc3339(1), EndedAt: rfc3339(1)},
		{SessionID: "d2", StartedAt: rfc3339(2), EndedAt: rfc3339(2)},
		{SessionID: "d3", StartedAt: rfc3339(3), EndedAt: rfc3339(3)},
	}, nil)

	recorder := httptest.NewRecorder()
	a.carResource(recorder, httptest.NewRequest(http.MethodPost, "/api/v1/cars/1/history/import", strings.NewReader(payload)), "user-a", "/api/v1/cars/1/history/import")
	if recorder.Code != http.StatusOK {
		t.Fatalf("import response = %d %s", recorder.Code, recorder.Body.String())
	}
	var envelope struct {
		Data historyImportResult `json:"data"`
	}
	_ = json.Unmarshal(recorder.Body.Bytes(), &envelope)
	if len(envelope.Data.RetainedDays) != 2 || envelope.Data.RetainedDays[0] != "2026-09-03" || envelope.Data.RetainedDays[1] != "2026-09-02" {
		t.Fatalf("retained days = %#v", envelope.Data.RetainedDays)
	}

	sessions := service.memory.sessions("user-a", 1, "drive")
	if len(sessions) != 2 {
		t.Fatalf("retained sessions = %d, want 2", len(sessions))
	}
	seen := map[string]bool{}
	for _, session := range sessions {
		seen[session.ID] = true
	}
	if !seen["d2"] || !seen["d3"] {
		t.Fatalf("retained session ids = %#v, want d2 and d3", seen)
	}
}

func TestHistoryImportRetentionIsPerAccountNotPerVehicle(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	// Two vehicles under the same account.
	service.memory.registerVehicle(telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "hash1", ProviderVehicleID: "p1"})
	service.memory.registerVehicle(telemetryVehicleRef{UserID: "user-a", VehicleID: 2, VINHash: "hash2", ProviderVehicleID: "p2"})
	a := &app{telemetry: service, provider: testProvider{vehicles: map[string][]vehicle{"user-a": {{ID: 1}, {ID: 2}}}}}

	// Vehicle 1 has day 1; vehicle 2 has day 3. Account keeps latest two (9/3, 9/1).
	importVehicle := func(vehicleID int, body string) {
		t.Helper()
		recorder := httptest.NewRecorder()
		a.carResource(recorder, httptest.NewRequest(http.MethodPost, "/api/v1/cars/"+strings.TrimSpace(string(rune(vehicleID+'0')))+"/history/import", strings.NewReader(body)), "user-a", "/api/v1/cars/"+strings.TrimSpace(string(rune(vehicleID+'0')))+"/history/import")
		if recorder.Code != http.StatusOK {
			t.Fatalf("import response = %d %s", recorder.Code, recorder.Body.String())
		}
	}
	importVehicle(1, importRequestJSON(t, []historyImportSession{{SessionID: "v1-d1", StartedAt: rfc3339(1), EndedAt: rfc3339(1)}}, nil))
	importVehicle(2, importRequestJSON(t, []historyImportSession{{SessionID: "v2-d3", StartedAt: rfc3339(3), EndedAt: rfc3339(3)}}, nil))

	// Both days are within the latest two, so both survive.
	if got := len(service.memory.sessions("user-a", 1, "drive")); got != 1 {
		t.Fatalf("vehicle 1 sessions = %d, want 1", got)
	}
	if got := len(service.memory.sessions("user-a", 2, "drive")); got != 1 {
		t.Fatalf("vehicle 2 sessions = %d, want 1", got)
	}

	// Now add day 4 to vehicle 1 — day 1 should be evicted account-wide.
	importVehicle(1, importRequestJSON(t, []historyImportSession{{SessionID: "v1-d4", StartedAt: rfc3339(4), EndedAt: rfc3339(4)}}, nil))
	vehicle1 := service.memory.sessions("user-a", 1, "drive")
	ids := map[string]bool{}
	for _, s := range vehicle1 {
		ids[s.ID] = true
	}
	if !ids["v1-d4"] || ids["v1-d1"] {
		t.Fatalf("vehicle 1 sessions after day 4 = %#v", ids)
	}
}

func TestHistoryImportRejectsInvalidTimeRange(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "hash", ProviderVehicleID: "provider-1"}
	service.memory.registerVehicle(ref)
	a := &app{telemetry: service, provider: testProvider{vehicles: map[string][]vehicle{"user-a": {{ID: 1}}}}}

	payload := importRequestJSON(t, []historyImportSession{{
		SessionID: "bad", StartedAt: rfc3339(2), EndedAt: rfc3339(1),
	}}, nil)
	recorder := httptest.NewRecorder()
	a.carResource(recorder, httptest.NewRequest(http.MethodPost, "/api/v1/cars/1/history/import", strings.NewReader(payload)), "user-a", "/api/v1/cars/1/history/import")
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("invalid range response = %d, want 400", recorder.Code)
	}
}

func TestHistoryImportRequiresPost(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	a := &app{telemetry: service, provider: testProvider{vehicles: map[string][]vehicle{"user-a": {{ID: 1}}}}}
	recorder := httptest.NewRecorder()
	a.carResource(recorder, httptest.NewRequest(http.MethodGet, "/api/v1/cars/1/history/import", nil), "user-a", "/api/v1/cars/1/history/import")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET import response = %d, want 404", recorder.Code)
	}
}

func floatPointer(value float64) *float64 { return &value }

var _ = context.Background
