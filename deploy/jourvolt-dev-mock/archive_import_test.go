package main

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestArchiveImportRequiresStableSourceBinding(t *testing.T) {
	err := validateArchiveImportRequest(historyImportRequest{
		Source: "teslamate",
		Drives: []historyImportSession{{SessionID: "drive-1", StartedAt: rfc3339(1), EndedAt: rfc3339(1)}},
	})
	if err == nil || err.Error() != "archive_source_binding_required" {
		t.Fatalf("validation error = %v, want archive_source_binding_required", err)
	}
}

func TestArchiveImportPreservesSourceAndChargeSamples(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "hash", ProviderVehicleID: "provider-1"}
	service.memory.registerVehicle(ref)
	energy := 8.5
	level := 73
	request := historyImportRequest{
		Source:           "teslamate",
		SourceInstanceID: "home-instance",
		SourceVehicleID:  "teslamate-car-1",
		ChunkID:          "home-instance-1",
		Charges: []historyImportSession{{
			SessionID: "charge-1", SourceRecordID: "47", StartedAt: rfc3339(1), EndedAt: rfc3339(1),
			EnergyAdded:  &energy,
			ChargePoints: []historyImportChargePoint{{Date: rfc3339(1), BatteryLevel: &level, EnergyAdded: &energy}},
		}},
	}
	if _, err := service.importHistory(context.Background(), ref.UserID, ref.VehicleID, request); err != nil {
		t.Fatal(err)
	}
	sessions := service.memory.sessions(ref.UserID, ref.VehicleID, "charge")
	if len(sessions) != 1 || sessions[0].Source != "teslamate_archive" || len(sessions[0].ChargePoints) != 1 {
		t.Fatalf("archive session = %#v", sessions)
	}
	if sessions[0].SourceInstanceID != "home-instance" || sessions[0].SourceVehicleID != "teslamate-car-1" || sessions[0].SourceRecordID != "47" {
		t.Fatalf("archive identity = %#v", sessions[0])
	}
}

func TestLegacyHistoryImportDoesNotAdoptArchiveEnvelope(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "hash", ProviderVehicleID: "provider-1"}
	service.memory.registerVehicle(ref)
	a := &app{telemetry: service, provider: testProvider{vehicles: map[string][]vehicle{"user-a": {{ID: 1}}}}}
	body := `{"source":"teslamate","source_instance_id":"home","source_vehicle_id":"1","chunk_id":"chunk","drives":[{"session_id":"legacy-1","source_record_id":"42","started_at":"2026-09-01T10:00:00Z","ended_at":"2026-09-01T10:01:00Z"}]}`
	recorder := httptest.NewRecorder()
	a.carResource(recorder, httptest.NewRequest(http.MethodPost, "/api/v1/cars/1/history/import", bytes.NewBufferString(body)), ref.UserID, "/api/v1/cars/1/history/import")
	if recorder.Code != http.StatusOK {
		t.Fatalf("legacy import status = %d, body=%s", recorder.Code, recorder.Body.String())
	}
	sessions := service.memory.sessions(ref.UserID, ref.VehicleID, "drive")
	if len(sessions) != 1 || sessions[0].Source != "local_import" {
		t.Fatalf("legacy session source = %#v", sessions)
	}
}

func TestArchiveImportKeepsTwoSourceRecordsWithTheSameStart(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "hash", ProviderVehicleID: "provider-1"}
	service.memory.registerVehicle(ref)
	request := historyImportRequest{
		Source: "teslamate", SourceInstanceID: "home", SourceVehicleID: "1", ChunkID: "chunk",
		Drives: []historyImportSession{
			{SessionID: "one", SourceRecordID: "drive:one", StartedAt: rfc3339(1), EndedAt: rfc3339(1)},
			{SessionID: "two", SourceRecordID: "drive:two", StartedAt: rfc3339(1), EndedAt: rfc3339(1)},
		},
	}
	if _, err := service.importHistory(context.Background(), ref.UserID, ref.VehicleID, request); err != nil {
		t.Fatal(err)
	}
	if got := len(service.memory.sessions(ref.UserID, ref.VehicleID, "drive")); got != 2 {
		t.Fatalf("archive sessions with same start = %d, want 2", got)
	}
}

func TestArchiveImportPreservesNullRouteCoordinates(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "hash", ProviderVehicleID: "provider-1"}
	service.memory.registerVehicle(ref)
	request := historyImportRequest{
		Source: "teslamate", SourceInstanceID: "home", SourceVehicleID: "1", ChunkID: "chunk",
		Drives: []historyImportSession{{SessionID: "drive", SourceRecordID: "drive:1", StartedAt: rfc3339(1), EndedAt: rfc3339(1), Route: []historyImportRoutePoint{{Date: rfc3339(1), Latitude: nil, Longitude: floatPointer(121.4)}}}},
	}
	if _, err := service.importHistory(context.Background(), ref.UserID, ref.VehicleID, request); err != nil {
		t.Fatal(err)
	}
	item := historySessionMap(service.memory.sessions(ref.UserID, ref.VehicleID, "drive")[0], "drive", 0)
	details := item["drive_details"].([]map[string]any)
	if len(details) != 1 || details[0]["latitude"] != nil || details[0]["longitude"] == nil {
		t.Fatalf("archive route nulls = %#v", details)
	}
}
