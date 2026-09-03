package main

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strconv"
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
	expectedD2 := scopedImportedSessionID("user-a", 1, "drive", "d2")
	expectedD3 := scopedImportedSessionID("user-a", 1, "drive", "d3")
	if !seen[expectedD2] || !seen[expectedD3] {
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
	expectedV1D4 := scopedImportedSessionID("user-a", 1, "drive", "v1-d4")
	expectedV1D1 := scopedImportedSessionID("user-a", 1, "drive", "v1-d1")
	if !ids[expectedV1D4] || ids[expectedV1D1] {
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

func TestScopedImportedSessionIDUniquenessAndDeterminism(t *testing.T) {
	// 1. Same client ID + same user + same car -> deterministic
	id1 := scopedImportedSessionID("user-a", 1, "drive", "local-1")
	id2 := scopedImportedSessionID("user-a", 1, "drive", "local-1")
	if id1 != id2 {
		t.Fatalf("expected deterministic IDs, got %s and %s", id1, id2)
	}
	if !strings.HasPrefix(id1, "local_import:") {
		t.Fatalf("expected prefix local_import:, got %s", id1)
	}
	if strings.Contains(id1, "user-a") {
		t.Fatalf("session id should not contain plaintext user ID, got %s", id1)
	}

	// 2. Same client ID + user A / user B -> different
	idUserB := scopedImportedSessionID("user-b", 1, "drive", "local-1")
	if id1 == idUserB {
		t.Fatalf("expected distinct IDs across users, got %s", id1)
	}

	// 3. Same client ID + same user + car 1 / car 2 -> different
	idCar2 := scopedImportedSessionID("user-a", 2, "drive", "local-1")
	if id1 == idCar2 {
		t.Fatalf("expected distinct IDs across vehicles, got %s", id1)
	}

	// 4. Same client ID + drive / charge -> different
	idCharge := scopedImportedSessionID("user-a", 1, "charge", "local-1")
	if id1 == idCharge {
		t.Fatalf("expected distinct IDs across kinds, got %s", id1)
	}
}

func TestHistoryImportIdempotentSameClientSession(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "hash", ProviderVehicleID: "provider-1"}
	service.memory.registerVehicle(ref)
	a := &app{telemetry: service, provider: testProvider{vehicles: map[string][]vehicle{"user-a": {{ID: 1}}}}}

	importCall := func(energy *float64) {
		payload := importRequestJSON(t, []historyImportSession{{
			SessionID:   "drive-repeat-1",
			StartedAt:   rfc3339(1),
			EndedAt:     rfc3339(1),
			EnergyAdded: energy,
		}}, nil)
		recorder := httptest.NewRecorder()
		a.carResource(recorder, httptest.NewRequest(http.MethodPost, "/api/v1/cars/1/history/import", strings.NewReader(payload)), "user-a", "/api/v1/cars/1/history/import")
		if recorder.Code != http.StatusOK {
			t.Fatalf("import failed: %d %s", recorder.Code, recorder.Body.String())
		}
	}

	importCall(floatPointer(10.5))
	sessions := service.memory.sessions("user-a", 1, "drive")
	if len(sessions) != 1 {
		t.Fatalf("sessions after 1st import = %d, want 1", len(sessions))
	}
	publicID := sessions[0].PublicID
	if *sessions[0].EnergyAdded != 10.5 {
		t.Fatalf("energy = %v, want 10.5", *sessions[0].EnergyAdded)
	}

	importCall(floatPointer(20.0))
	sessions = service.memory.sessions("user-a", 1, "drive")
	if len(sessions) != 1 {
		t.Fatalf("sessions after 2nd import = %d, want 1 (idempotent)", len(sessions))
	}
	if sessions[0].PublicID != publicID {
		t.Fatalf("public ID changed from %d to %d", publicID, sessions[0].PublicID)
	}
	if *sessions[0].EnergyAdded != 20.0 {
		t.Fatalf("energy after update = %v, want 20.0", *sessions[0].EnergyAdded)
	}
}

func TestHistoryImportIsolationAcrossUsersAndVehiclesAndKinds(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	service.memory.registerVehicle(telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "hash-a1", ProviderVehicleID: "p-a1"})
	service.memory.registerVehicle(telemetryVehicleRef{UserID: "user-a", VehicleID: 2, VINHash: "hash-a2", ProviderVehicleID: "p-a2"})
	service.memory.registerVehicle(telemetryVehicleRef{UserID: "user-b", VehicleID: 1, VINHash: "hash-b1", ProviderVehicleID: "p-b1"})
	a := &app{
		telemetry: service,
		provider: testProvider{
			vehicles: map[string][]vehicle{
				"user-a": {{ID: 1}, {ID: 2}},
				"user-b": {{ID: 1}},
			},
		},
	}

	importItem := func(user string, car int, drives, charges []historyImportSession) {
		t.Helper()
		payload := importRequestJSON(t, drives, charges)
		recorder := httptest.NewRecorder()
		path := "/api/v1/cars/" + strconv.Itoa(car) + "/history/import"
		a.carResource(recorder, httptest.NewRequest(http.MethodPost, path, strings.NewReader(payload)), user, path)
		if recorder.Code != http.StatusOK {
			t.Fatalf("import failed for %s car %d: %d %s", user, car, recorder.Code, recorder.Body.String())
		}
	}

	importItem("user-a", 1, []historyImportSession{{SessionID: "local-1", StartedAt: rfc3339(1), EndedAt: rfc3339(1)}}, nil)
	importItem("user-b", 1, []historyImportSession{{SessionID: "local-1", StartedAt: rfc3339(1), EndedAt: rfc3339(1)}}, nil)
	importItem("user-a", 2, []historyImportSession{{SessionID: "local-1", StartedAt: rfc3339(1), EndedAt: rfc3339(1)}}, nil)
	importItem("user-a", 1, nil, []historyImportSession{{SessionID: "local-1", StartedAt: rfc3339(1), EndedAt: rfc3339(1)}})

	uaDrives := service.memory.sessions("user-a", 1, "drive")
	ubDrives := service.memory.sessions("user-b", 1, "drive")
	uaCar2Drives := service.memory.sessions("user-a", 2, "drive")
	uaCharges := service.memory.sessions("user-a", 1, "charge")

	if len(uaDrives) != 1 || len(ubDrives) != 1 || len(uaCar2Drives) != 1 || len(uaCharges) != 1 {
		t.Fatalf("expected 1 session each, got %d, %d, %d, %d", len(uaDrives), len(ubDrives), len(uaCar2Drives), len(uaCharges))
	}

	allIDs := map[string]bool{
		uaDrives[0].ID:     true,
		ubDrives[0].ID:     true,
		uaCar2Drives[0].ID: true,
		uaCharges[0].ID:    true,
	}
	if len(allIDs) != 4 {
		t.Fatalf("expected 4 distinct storage IDs, got %d: %#v", len(allIDs), allIDs)
	}
}

func TestHistoryImportDefensiveLimits(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	service.memory.registerVehicle(telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "hash", ProviderVehicleID: "p1"})
	a := &app{telemetry: service, provider: testProvider{vehicles: map[string][]vehicle{"user-a": {{ID: 1}}}}}

	// 1. Too many sessions (> 200 drives)
	tooManyDrives := make([]historyImportSession, 201)
	for i := range tooManyDrives {
		tooManyDrives[i] = historyImportSession{
			SessionID: strconv.Itoa(i),
			StartedAt: rfc3339(1),
			EndedAt:   rfc3339(1),
		}
	}
	payload := importRequestJSON(t, tooManyDrives, nil)
	recorder := httptest.NewRecorder()
	a.carResource(recorder, httptest.NewRequest(http.MethodPost, "/api/v1/cars/1/history/import", strings.NewReader(payload)), "user-a", "/api/v1/cars/1/history/import")
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for too many sessions, got %d: %s", recorder.Code, recorder.Body.String())
	}
	if !strings.Contains(recorder.Body.String(), "too_many_sessions") {
		t.Fatalf("expected too_many_sessions error, got %s", recorder.Body.String())
	}

	// 2. Too many route points in a session (> 10000)
	tooManyPoints := make([]historyImportRoutePoint, 10001)
	for i := range tooManyPoints {
		tooManyPoints[i] = historyImportRoutePoint{
			Date:      rfc3339(1),
			Latitude:  floatPointer(37.0),
			Longitude: floatPointer(-122.0),
		}
	}
	payloadPoints := importRequestJSON(t, []historyImportSession{{
		SessionID: "many-points",
		StartedAt: rfc3339(1),
		EndedAt:   rfc3339(1),
		Route:     tooManyPoints,
	}}, nil)
	recorder = httptest.NewRecorder()
	a.carResource(recorder, httptest.NewRequest(http.MethodPost, "/api/v1/cars/1/history/import", strings.NewReader(payloadPoints)), "user-a", "/api/v1/cars/1/history/import")
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for too many route points, got %d: %s", recorder.Code, recorder.Body.String())
	}
	if !strings.Contains(recorder.Body.String(), "too_many_route_points") {
		t.Fatalf("expected too_many_route_points error, got %s", recorder.Body.String())
	}

	// 3. Body size exceeds 10 MiB
	hugeBody := bytes.Repeat([]byte(" "), 11<<20) // 11 MiB
	recorder = httptest.NewRecorder()
	a.carResource(recorder, httptest.NewRequest(http.MethodPost, "/api/v1/cars/1/history/import", bytes.NewReader(hugeBody)), "user-a", "/api/v1/cars/1/history/import")
	if recorder.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("expected 413 for oversized body, got %d: %s", recorder.Code, recorder.Body.String())
	}
}

func TestHistoryImportPostgresScopedAndIdempotent(t *testing.T) {
	dsn := os.Getenv("JOURVOLT_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("JOURVOLT_TEST_DATABASE_URL is not set")
	}
	ctx := context.Background()
	database, err := openStore(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(database.close)

	userA := "test_import_a_" + mustRandomToken(t)
	userB := "test_import_b_" + mustRandomToken(t)
	if err := database.ensureUser(ctx, userA); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = database.deleteUser(context.Background(), userA) })
	if err := database.ensureUser(ctx, userB); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = database.deleteUser(context.Background(), userB) })

	var vA1, vA2, vB1 int
	if err := database.pool.QueryRow(ctx, `INSERT INTO jourvolt_vehicles(user_id, provider_vehicle_id, vin_ciphertext, display_name, state, updated_at) VALUES ($1, $2, 'c', 'V1', 'online', now()) RETURNING id`, userA, "p-a1-"+mustRandomToken(t)).Scan(&vA1); err != nil {
		t.Fatal(err)
	}
	if err := database.pool.QueryRow(ctx, `INSERT INTO jourvolt_vehicles(user_id, provider_vehicle_id, vin_ciphertext, display_name, state, updated_at) VALUES ($1, $2, 'c', 'V2', 'online', now()) RETURNING id`, userA, "p-a2-"+mustRandomToken(t)).Scan(&vA2); err != nil {
		t.Fatal(err)
	}
	if err := database.pool.QueryRow(ctx, `INSERT INTO jourvolt_vehicles(user_id, provider_vehicle_id, vin_ciphertext, display_name, state, updated_at) VALUES ($1, $2, 'c', 'V1', 'online', now()) RETURNING id`, userB, "p-b1-"+mustRandomToken(t)).Scan(&vB1); err != nil {
		t.Fatal(err)
	}

	service := &telemetryService{store: database, config: &telemetryConfig{StopDebounce: defaultDriveStopDebounce}}

	req := historyImportRequest{
		Drives: []historyImportSession{{
			SessionID: "shared-client-id",
			StartedAt: rfc3339(1),
			EndedAt:   rfc3339(1),
		}},
	}

	// User A Car 1: repeated import -> idempotent
	res1, err := service.importHistory(ctx, userA, vA1, req)
	if err != nil {
		t.Fatal(err)
	}
	if res1.ImportedDrives != 1 {
		t.Fatalf("first import got %d", res1.ImportedDrives)
	}
	res2, err := service.importHistory(ctx, userA, vA1, req)
	if err != nil {
		t.Fatal(err)
	}
	if res2.ImportedDrives != 1 {
		t.Fatalf("second import got %d", res2.ImportedDrives)
	}

	var countA1 int
	if err := database.pool.QueryRow(ctx, `SELECT count(*) FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2 AND kind='drive'`, userA, vA1).Scan(&countA1); err != nil {
		t.Fatal(err)
	}
	if countA1 != 1 {
		t.Fatalf("expected 1 drive for user A car 1, got %d", countA1)
	}

	// User B Car 1: same client ID -> distinct row
	if _, err := service.importHistory(ctx, userB, vB1, req); err != nil {
		t.Fatal(err)
	}
	var countB1 int
	if err := database.pool.QueryRow(ctx, `SELECT count(*) FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2 AND kind='drive'`, userB, vB1).Scan(&countB1); err != nil {
		t.Fatal(err)
	}
	if countB1 != 1 {
		t.Fatalf("expected 1 drive for user B car 1, got %d", countB1)
	}

	// User A Car 2: same client ID -> distinct row
	if _, err := service.importHistory(ctx, userA, vA2, req); err != nil {
		t.Fatal(err)
	}
	var countA2 int
	if err := database.pool.QueryRow(ctx, `SELECT count(*) FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2 AND kind='drive'`, userA, vA2).Scan(&countA2); err != nil {
		t.Fatal(err)
	}
	if countA2 != 1 {
		t.Fatalf("expected 1 drive for user A car 2, got %d", countA2)
	}

	// User A Car 1: charge with same client ID -> distinct row
	reqCharge := historyImportRequest{
		Charges: []historyImportSession{{
			SessionID: "shared-client-id",
			StartedAt: rfc3339(1),
			EndedAt:   rfc3339(1),
		}},
	}
	if _, err := service.importHistory(ctx, userA, vA1, reqCharge); err != nil {
		t.Fatal(err)
	}
	var countA1Charge int
	if err := database.pool.QueryRow(ctx, `SELECT count(*) FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2 AND kind='charge'`, userA, vA1).Scan(&countA1Charge); err != nil {
		t.Fatal(err)
	}
	if countA1Charge != 1 {
		t.Fatalf("expected 1 charge for user A car 1, got %d", countA1Charge)
	}
}

var _ = context.Background
