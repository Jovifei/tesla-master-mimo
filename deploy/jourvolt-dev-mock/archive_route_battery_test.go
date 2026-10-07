package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"testing"
)

func archiveBatteryRequest(t *testing.T, levels []any) historyImportRequest {
	t.Helper()
	route := make([]map[string]any, len(levels))
	for i, level := range levels {
		route[i] = map[string]any{"date": fmt.Sprintf("2026-10-04T01:00:%02dZ", i), "latitude": nil, "longitude": 121.5, "battery_level": level}
	}
	raw, err := json.Marshal(map[string]any{"source": "teslamate", "source_instance_id": "synthetic-home", "source_vehicle_id": "synthetic-car", "chunk_id": "synthetic-soc", "drives": []any{map[string]any{"session_id": "drive-1", "source_record_id": "drive-1", "started_at": "2026-10-04T01:00:00Z", "ended_at": "2026-10-04T01:01:00Z", "route": route}}})
	if err != nil {
		t.Fatal(err)
	}
	var request historyImportRequest
	if err := json.Unmarshal(raw, &request); err != nil {
		t.Fatal(err)
	}
	return request
}

func assertArchiveBatteryWire(t *testing.T, item map[string]any, want []any) {
	t.Helper()
	raw, err := json.Marshal(item)
	if err != nil {
		t.Fatal(err)
	}
	var decoded map[string]any
	if err := json.Unmarshal(raw, &decoded); err != nil {
		t.Fatal(err)
	}
	route := decoded["drive_details"].([]any)
	if len(route) != len(want) {
		t.Fatalf("route length %d want %d", len(route), len(want))
	}
	for i, expected := range want {
		point := route[i].(map[string]any)
		if point["battery_level"] != expected || point["latitude"] != nil {
			t.Fatalf("point %d battery=%v want=%v latitude=%v", i, point["battery_level"], expected, point["latitude"])
		}
	}
	if decoded["energy_consumed_net"] != nil {
		t.Fatal("SOC fabricated drive kWh")
	}
}

func TestArchiveDriveBatteryForwardingMemory(t *testing.T) {
	for _, levels := range [][]any{{float64(61), float64(61)}, {float64(74), nil, float64(73)}, {float64(0), nil}, {nil, nil}} {
		s := newTelemetryServiceForTest("partner.example.com")
		s.memory.registerVehicle(telemetryVehicleRef{UserID: "synthetic", VehicleID: 1, VINHash: "synthetic", ProviderVehicleID: "synthetic"})
		if _, err := s.importHistory(context.Background(), "synthetic", 1, archiveBatteryRequest(t, levels)); err != nil {
			t.Fatal(err)
		}
		rows := s.memory.sessions("synthetic", 1, "drive")
		if len(rows) != 1 {
			t.Fatal("session missing")
		}
		assertArchiveBatteryWire(t, historySessionMap(rows[0], "drive", 0), levels)
		// Sparse retries cannot erase already imported source observations.
		if _, err := s.importHistory(context.Background(), "synthetic", 1, archiveBatteryRequest(t, []any{nil, nil})); err != nil {
			t.Fatal(err)
		}
		assertArchiveBatteryWire(t, historySessionMap(s.memory.sessions("synthetic", 1, "drive")[0], "drive", 0), levels)
	}
}

func TestArchiveDriveBatteryPostgresReloadScopeAndLegacyCompatibility(t *testing.T) {
	s, owner, car := openHistoryQueryTestDB(t)
	ctx := context.Background()
	want := []any{float64(74), nil, float64(73), float64(0)}
	if _, err := s.importHistory(ctx, owner, car, archiveBatteryRequest(t, want)); err != nil {
		t.Fatal(err)
	}
	var publicID int
	if err := s.store.pool.QueryRow(ctx, `SELECT public_id FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2`, owner, car).Scan(&publicID); err != nil {
		t.Fatal(err)
	}
	// New pool/reconstructed service verifies stored JSON, not an in-memory reply.
	db, err := openStore(ctx, os.Getenv("JOURVOLT_TEST_DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.close()
	reloaded := &telemetryService{store: db}
	row, found, err := reloaded.historyDetailPostgres(ctx, owner, car, "drive", publicID)
	if err != nil || !found {
		t.Fatalf("reload found=%t err=%v", found, err)
	}
	assertArchiveBatteryWire(t, historySessionMap(row, "drive", 0), want)
	if _, found, err := reloaded.historyDetailPostgres(ctx, "foreign", car, "drive", publicID); err != nil || found {
		t.Fatalf("cross-owner read found=%t err=%v", found, err)
	}
	auth, err := db.createSession(ctx, owner)
	if err != nil {
		t.Fatal(err)
	}
	a := &app{store: db, telemetry: reloaded, provider: &failingHistoryDiscoveryProvider{err: errTeslaUnavailable}}
	w := historyContextRequest(a, http.MethodGet, fmt.Sprintf("/api/v1/cars/%d/drives/%d", car, publicID), auth.AccessToken)
	var body struct {
		Data struct {
			Drive map[string]any `json:"drive"`
		} `json:"data"`
	}
	if w.Code != http.StatusOK || json.Unmarshal(w.Body.Bytes(), &body) != nil {
		t.Fatalf("authenticated detail status=%d", w.Code)
	}
	assertArchiveBatteryWire(t, body.Data.Drive, want)
	// This patch does not turn list reads into full-array detail reads.
	page, _, err := reloaded.historyPage(ctx, owner, car, "drive", historyPageOptions{Show: 20})
	if err != nil || len(page) != 1 || len(page[0]["drive_details"].([]map[string]any)) != 0 {
		t.Fatalf("bounded list changed: err=%v", err)
	}
	if _, err := reloaded.importHistory(ctx, owner, car, archiveBatteryRequest(t, []any{nil, nil})); err != nil {
		t.Fatal(err)
	}
	row, found, err = reloaded.historyDetailPostgres(ctx, owner, car, "drive", publicID)
	if err != nil || !found {
		t.Fatal(err)
	}
	assertArchiveBatteryWire(t, historySessionMap(row, "drive", 0), want)
}

func TestImportedRouteBatteryLocalMergeCloneAndInvalidValues(t *testing.T) {
	s := newTelemetryServiceForTest("partner.example.com")
	s.memory.registerVehicle(telemetryVehicleRef{UserID: "synthetic", VehicleID: 1, VINHash: "synthetic", ProviderVehicleID: "synthetic"})
	req := archiveBatteryRequest(t, []any{float64(0), float64(73)})
	req.Source = ""
	for i := range req.Drives[0].Route {
		req.Drives[0].Route[i].Latitude = floatPointer(31)
	}
	if _, err := s.importHistory(context.Background(), "synthetic", 1, req); err != nil {
		t.Fatal(err)
	}
	read := func() telemetrySession { return s.memory.sessions("synthetic", 1, "drive")[0] }
	copy := read()
	if len(copy.Route) != 2 || copy.Route[0].BatteryLevel == nil || *copy.Route[0].BatteryLevel != 0 {
		t.Fatal("local SOC dropped")
	}
	*copy.Route[0].BatteryLevel = 99
	if *read().Route[0].BatteryLevel != 0 {
		t.Fatal("returned SOC aliases stored route")
	}
	for i := range req.Drives[0].Route {
		req.Drives[0].Route[i].BatteryLevel = nil
	}
	if _, err := s.importHistory(context.Background(), "synthetic", 1, req); err != nil {
		t.Fatal(err)
	}
	if *read().Route[0].BatteryLevel != 0 || *read().Route[1].BatteryLevel != 73 {
		t.Fatal("sparse local replay erased SOC")
	}
	for _, level := range []int{-1, 101} {
		invalid := archiveBatteryRequest(t, []any{float64(level), nil})
		row, err := invalid.Drives[0].toTelemetrySession("synthetic", 1, "drive", "teslamate", "home", "car")
		if err != nil {
			t.Fatal(err)
		}
		assertArchiveBatteryWire(t, historySessionMap(row, "drive", 0), []any{nil, nil})
	}
	raw, err := json.Marshal(telemetryRoutePoint{})
	if err != nil {
		t.Fatal(err)
	}
	var oldShape map[string]any
	if err := json.Unmarshal(raw, &oldShape); err != nil {
		t.Fatal(err)
	}
	if _, present := oldShape["BatteryLevel"]; present {
		t.Fatal("unobserved native field changed old JSON shape")
	}
}
