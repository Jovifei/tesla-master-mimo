package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"runtime"
	"strconv"
	"strings"
	"testing"
	"time"
)

func boundedHistoryTestStore(t *testing.T) *store {
	t.Helper()
	dsn := os.Getenv("JOURVOLT_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("JOURVOLT_TEST_DATABASE_URL is not set")
	}
	database, err := openStore(context.Background(), dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(database.close)
	return database
}

func boundedHistoryTestTenant(t *testing.T, database *store, prefix string) (string, int) {
	t.Helper()
	ctx := context.Background()
	userID := prefix + "_" + mustRandomToken(t)
	if err := database.ensureUser(ctx, userID); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = database.deleteUser(context.Background(), userID) })
	var vehicleID int
	if err := database.pool.QueryRow(ctx, `
INSERT INTO jourvolt_vehicles(user_id, provider_vehicle_id, vin_ciphertext, display_name, state, updated_at)
VALUES ($1, $2, 'ciphertext', 'Bounded history test', 'online', now())
RETURNING id
`, userID, prefix+"-provider-"+mustRandomToken(t)).Scan(&vehicleID); err != nil {
		t.Fatal(err)
	}
	return userID, vehicleID
}

func boundedHistoryInsertSession(
	t *testing.T,
	database *store,
	userID string,
	vehicleID int,
	kind string,
	start time.Time,
	source string,
	quality string,
	route any,
	sourceRecord string,
) int {
	t.Helper()
	routeJSON, err := json.Marshal(route)
	if err != nil {
		t.Fatal(err)
	}
	chargeJSON := []byte("[]")
	end := start.Add(17 * time.Minute)
	id := "bounded-" + mustRandomToken(t)
	sourceInstance, sourceVehicle := "", ""
	if source == "teslamate_archive" {
		sourceInstance = "archive-instance"
		sourceVehicle = "archive-vehicle"
		if sourceRecord == "" {
			sourceRecord = id
		}
	}
	var publicID int
	if err := database.pool.QueryRow(context.Background(), `
INSERT INTO jourvolt_telemetry_sessions(
    id, user_id, vehicle_id, kind, started_at, ended_at,
    odometer_start, odometer_end, energy_added, route_json, charge_points_json,
    source, quality_state, quality_reason,
    source_instance_id, source_vehicle_id, source_record_id,
    start_address, end_address, address, cost
)
VALUES ($1,$2,$3,$4,$5,$6,100.0,107.49,4.2,$7::jsonb,$8::jsonb,$9,$10,'bounded_test',$11,$12,$13,'Start','End','Address',NULL)
RETURNING public_id
`, id, userID, vehicleID, kind, start, end, routeJSON, chargeJSON, source, quality,
		sourceInstance, sourceVehicle, sourceRecord).Scan(&publicID); err != nil {
		t.Fatal(err)
	}
	return publicID
}

func intPointerForHistoryTest(value int) *int { return &value }
func floatPointerForHistoryTest(value float64) *float64 { return &value }

func TestBoundedHistoryPostgresListDetailTenantSourceAndCancellation(t *testing.T) {
	database := boundedHistoryTestStore(t)
	userA, vehicleA := boundedHistoryTestTenant(t, database, "bounded-a")
	userB, vehicleB := boundedHistoryTestTenant(t, database, "bounded-b")
	service := &telemetryService{store: database}
	api := &app{telemetry: service}

	base := time.Date(2026, time.October, 7, 11, 0, 0, 0, time.UTC)
	archiveRoute := []historyImportRoutePoint{
		{Date: base.Add(2 * time.Hour).Format(time.RFC3339), Latitude: floatPointerForHistoryTest(31.10), Longitude: floatPointerForHistoryTest(121.10), BatteryLevel: intPointerForHistoryTest(72)},
		{Date: base.Add(2*time.Hour + 8*time.Minute).Format(time.RFC3339), Latitude: floatPointerForHistoryTest(31.20), Longitude: floatPointerForHistoryTest(121.20), BatteryLevel: intPointerForHistoryTest(68)},
		{Date: base.Add(2*time.Hour + 17*time.Minute).Format(time.RFC3339), Latitude: floatPointerForHistoryTest(31.30), Longitude: floatPointerForHistoryTest(121.30), BatteryLevel: intPointerForHistoryTest(64)},
	}
	latestID := boundedHistoryInsertSession(t, database, userA, vehicleA, "drive", base.Add(2*time.Hour), "teslamate_archive", "observed", archiveRoute, "archive-record")
	boundedHistoryInsertSession(t, database, userA, vehicleA, "drive", base.Add(time.Hour), "local_import", "incomplete", []telemetryRoutePoint{}, "")
	boundedHistoryInsertSession(t, database, userA, vehicleA, "drive", base, "telemetry_mqtt", "derived", []telemetryRoutePoint{}, "")
	boundedHistoryInsertSession(t, database, userA, vehicleA, "drive", base.Add(-time.Hour), "telemetry_mqtt", "quarantined", []telemetryRoutePoint{}, "")
	boundedHistoryInsertSession(t, database, userB, vehicleB, "drive", base.Add(3*time.Hour), "fleet_api", "observed", []telemetryRoutePoint{}, "")

	if count, err := service.historyCountContext(context.Background(), userA, vehicleA, "drive"); err != nil || count != 3 {
		t.Fatalf("count=%d err=%v, want 3", count, err)
	}
	if exists, err := service.historyExistsContext(context.Background(), userA, vehicleA, "drive"); err != nil || !exists {
		t.Fatalf("exists=%v err=%v, want true", exists, err)
	}
	if exists, err := service.historyExistsContext(context.Background(), userB, vehicleA, "drive"); err != nil || exists {
		t.Fatalf("cross-tenant exists=%v err=%v, want false", exists, err)
	}
	if source := api.historyReadinessSourceContext(context.Background(), userA, vehicleA, "drive", "telemetry_mqtt"); source != "local_history" {
		t.Fatalf("readiness source=%q, want local_history", source)
	}

	vehicleItems := api.vehicleItems(context.Background(), userA, []vehicle{{ID: vehicleA, VehicleUID: "bounded-uid"}})
	if len(vehicleItems) != 1 {
		t.Fatalf("vehicleItems len=%d", len(vehicleItems))
	}
	stats := vehicleItems[0]["teslamate_stats"].(map[string]any)
	if stats["total_drives"] != 3 {
		t.Fatalf("total_drives=%v, want 3", stats["total_drives"])
	}

	list := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/api/v1/cars/1/drives?page=1&show=2", nil)
	api.telemetryHistory(list, request, userA, vehicleA, "drive", nil)
	if list.Code != http.StatusOK {
		t.Fatalf("list status=%d body=%s", list.Code, list.Body.String())
	}
	var envelope struct {
		Data struct {
			Drives []map[string]any `json:"drives"`
			Meta   map[string]any   `json:"meta"`
		} `json:"data"`
	}
	if err := json.Unmarshal(list.Body.Bytes(), &envelope); err != nil {
		t.Fatal(err)
	}
	if len(envelope.Data.Drives) != 2 || int(envelope.Data.Drives[0]["drive_id"].(float64)) != latestID {
		t.Fatalf("page drives=%#v", envelope.Data.Drives)
	}
	if details, ok := envelope.Data.Drives[0]["drive_details"].([]any); !ok || len(details) != 0 {
		t.Fatalf("list drive_details=%#v, want empty", envelope.Data.Drives[0]["drive_details"])
	}
	if envelope.Data.Meta["total"] != float64(3) || envelope.Data.Meta["source"] != "teslamate_archive" || envelope.Data.Meta["quarantined_count"] != float64(1) {
		t.Fatalf("meta=%#v", envelope.Data.Meta)
	}
	if got := envelope.Data.Drives[0]["start_latitude"]; got != 31.10 {
		t.Fatalf("start_latitude=%v, want 31.10", got)
	}

	filtered := httptest.NewRecorder()
	filteredRequest := httptest.NewRequest(
		http.MethodGet,
		"/api/v1/cars/1/drives?page=1&show=10&startDate="+url.QueryEscape(base.Add(90*time.Minute).Format(time.RFC3339)),
		nil,
	)
	api.telemetryHistory(filtered, filteredRequest, userA, vehicleA, "drive", nil)
	if filtered.Code != http.StatusOK {
		t.Fatalf("filtered status=%d body=%s", filtered.Code, filtered.Body.String())
	}
	if err := json.Unmarshal(filtered.Body.Bytes(), &envelope); err != nil {
		t.Fatal(err)
	}
	if envelope.Data.Meta["total"] != float64(1) || len(envelope.Data.Drives) != 1 {
		t.Fatalf("filtered response=%s", filtered.Body.String())
	}
	if envelope.Data.Meta["source"] != "teslamate_archive" {
		t.Fatalf("filtered global source=%v, want teslamate_archive", envelope.Data.Meta["source"])
	}

	detail := httptest.NewRecorder()
	detailRequest := httptest.NewRequest(http.MethodGet, "/api/v1/cars/1/drives/"+strconv.Itoa(latestID), nil)
	api.telemetryHistory(detail, detailRequest, userA, vehicleA, "drive", []string{strconv.Itoa(latestID)})
	if detail.Code != http.StatusOK {
		t.Fatalf("detail status=%d body=%s", detail.Code, detail.Body.String())
	}
	var detailEnvelope struct {
		Data map[string]map[string]any `json:"data"`
	}
	if err := json.Unmarshal(detail.Body.Bytes(), &detailEnvelope); err != nil {
		t.Fatal(err)
	}
	drive := detailEnvelope.Data["drive"]
	points, ok := drive["drive_details"].([]any)
	if !ok || len(points) != 3 {
		t.Fatalf("detail points=%#v", drive["drive_details"])
	}
	firstPoint := points[0].(map[string]any)
	if firstPoint["battery_level"] != float64(72) {
		t.Fatalf("detail SOC=%v, want 72", firstPoint["battery_level"])
	}
	if drive["source_instance_id"] != "archive-instance" || drive["source_record_id"] != "archive-record" {
		t.Fatalf("detail source identity=%#v", drive)
	}
	if _, ok, err := service.historyDetailContext(context.Background(), userB, vehicleA, "drive", latestID); err != nil || ok {
		t.Fatalf("cross-tenant detail ok=%v err=%v, want false", ok, err)
	}

	for _, test := range []struct {
		name  string
		parts []string
	}{
		{name: "list"},
		{name: "detail", parts: []string{strconv.Itoa(latestID)}},
	} {
		t.Run("cancel_"+test.name, func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			cancel()
			r := httptest.NewRequest(http.MethodGet, "/api/v1/cars/1/drives", nil).WithContext(ctx)
			w := httptest.NewRecorder()
			api.telemetryHistory(w, r, userA, vehicleA, "drive", test.parts)
			if w.Code != http.StatusRequestTimeout || !strings.Contains(w.Body.String(), "history_unavailable") {
				t.Fatalf("cancel %s status=%d body=%s", test.name, w.Code, w.Body.String())
			}
		})
	}
}

func legacyReadinessSourceFromHistoryMaps(items []map[string]any, fallback string) string {
	for _, item := range items {
		raw, _ := item["source"].(string)
		switch strings.ToLower(strings.TrimSpace(raw)) {
		case "local_import", "local_history":
			return "local_history"
		case "telemetry_mqtt":
			return "telemetry_mqtt"
		case "fleet_api":
			return "fleet_api"
		}
	}
	return fallback
}

func TestBoundedHistoryReadinessExactEmptySourceMatchesLegacyMemoryAndPostgres(t *testing.T) {
	base := time.Date(2026, time.October, 7, 3, 0, 0, 0, time.UTC)
	olderEnd, newerEnd := base.Add(10*time.Minute), base.Add(time.Hour+10*time.Minute)

	memory := newTelemetryMemoryStore()
	key := telemetryKey{UserID: "memory-readiness-user", VehicleID: 7}
	memory.completed[key] = []telemetrySession{
		{ID: "memory-new", PublicID: 2, Kind: "drive", StartAt: base.Add(time.Hour), EndAt: &newerEnd, Source: "", QualityState: "observed"},
		{ID: "memory-old", PublicID: 1, Kind: "drive", StartAt: base, EndAt: &olderEnd, Source: "local_import", QualityState: "incomplete"},
	}
	memoryService := &telemetryService{memory: memory}
	legacyItems, _, err := memoryService.history(key.UserID, key.VehicleID, "drive")
	if err != nil { t.Fatal(err) }
	legacySource := legacyReadinessSourceFromHistoryMaps(legacyItems, "fleet_api")
	memorySource, ok, err := memoryService.historySourceForReadinessContext(context.Background(), key.UserID, key.VehicleID, "drive")
	if err != nil || !ok || memorySource != legacySource || memorySource != "telemetry_mqtt" {
		t.Fatalf("memory source=%q legacy=%q ok=%v err=%v", memorySource, legacySource, ok, err)
	}

	database := boundedHistoryTestStore(t)
	userID, vehicleID := boundedHistoryTestTenant(t, database, "readiness-empty")
	service := &telemetryService{store: database}
	boundedHistoryInsertSession(t, database, userID, vehicleID, "drive", base, "local_import", "incomplete", []telemetryRoutePoint{}, "")
	boundedHistoryInsertSession(t, database, userID, vehicleID, "drive", base.Add(time.Hour), "", "observed", []telemetryRoutePoint{}, "")
	legacyItems, _, err = service.history(userID, vehicleID, "drive")
	if err != nil { t.Fatal(err) }
	legacySource = legacyReadinessSourceFromHistoryMaps(legacyItems, "fleet_api")
	pgSource, ok, err := service.historySourceForReadinessContext(context.Background(), userID, vehicleID, "drive")
	if err != nil || !ok || pgSource != legacySource || pgSource != "telemetry_mqtt" {
		t.Fatalf("postgres source=%q legacy=%q ok=%v err=%v", pgSource, legacySource, ok, err)
	}
	meta, err := service.historyMetadataBoundedPostgres(context.Background(), userID, vehicleID, "drive")
	if err != nil { t.Fatal(err) }
	if meta.Source != "local_import" {
		t.Fatalf("metadata source=%q, want legacy skip-empty local_import", meta.Source)
	}
}

func TestBoundedHistoryDriveBatterySummaryHTTPPreservesValidZeroAndUnknown(t *testing.T) {
	database := boundedHistoryTestStore(t)
	userID, vehicleID := boundedHistoryTestTenant(t, database, "drive-soc")
	service := &telemetryService{store: database}
	api := &app{telemetry: service}
	base := time.Date(2026, time.October, 7, 4, 0, 0, 0, time.UTC)

	level80, level75 := 80, 75
	nativeID := boundedHistoryInsertSession(t, database, userID, vehicleID, "drive", base.Add(3*time.Hour), "telemetry_mqtt", "observed", []telemetryRoutePoint{
		{ObservedAt: base.Add(3 * time.Hour), Latitude: 31.1, Longitude: 121.1, BatteryLevel: &level80},
		{ObservedAt: base.Add(3*time.Hour + 17*time.Minute), Latitude: 31.2, Longitude: 121.2, BatteryLevel: &level75},
	}, "")
	zero, level40 := 0, 40
	archiveID := boundedHistoryInsertSession(t, database, userID, vehicleID, "drive", base.Add(2*time.Hour), "teslamate_archive", "observed", []historyImportRoutePoint{
		{Date: base.Add(2 * time.Hour).Format(time.RFC3339), Latitude: floatPointerForHistoryTest(31.3), Longitude: floatPointerForHistoryTest(121.3), BatteryLevel: &zero},
		{Date: base.Add(2*time.Hour + 17*time.Minute).Format(time.RFC3339), Latitude: floatPointerForHistoryTest(31.4), Longitude: floatPointerForHistoryTest(121.4), BatteryLevel: &level40},
	}, "archive-soc-zero")
	missingID := boundedHistoryInsertSession(t, database, userID, vehicleID, "drive", base.Add(time.Hour), "telemetry_mqtt", "observed", []telemetryRoutePoint{
		{ObservedAt: base.Add(time.Hour), Latitude: 31.5, Longitude: 121.5},
		{ObservedAt: base.Add(time.Hour + 17*time.Minute), Latitude: 31.6, Longitude: 121.6},
	}, "")
	invalidLow, invalidHigh := -1, 101
	invalidID := boundedHistoryInsertSession(t, database, userID, vehicleID, "drive", base, "telemetry_mqtt", "observed", []telemetryRoutePoint{
		{ObservedAt: base, Latitude: 31.7, Longitude: 121.7, BatteryLevel: &invalidLow},
		{ObservedAt: base.Add(17 * time.Minute), Latitude: 31.8, Longitude: 121.8, BatteryLevel: &invalidHigh},
	}, "")

	w := httptest.NewRecorder()
	r := httptest.NewRequest(http.MethodGet, "/api/v1/cars/1/drives?page=1&show=10", nil)
	api.telemetryHistory(w, r, userID, vehicleID, "drive", nil)
	if w.Code != http.StatusOK { t.Fatalf("list status=%d body=%s", w.Code, w.Body.String()) }
	var envelope struct {
		Data struct {
			Drives []map[string]any `json:"drives"`
		} `json:"data"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &envelope); err != nil { t.Fatal(err) }
	byID := map[int]map[string]any{}
	for _, drive := range envelope.Data.Drives {
		byID[int(drive["drive_id"].(float64))] = drive
	}
	assertBattery := func(id int, wantStart, wantEnd any) {
		t.Helper()
		drive := byID[id]
		if drive == nil { t.Fatalf("drive %d missing from list", id) }
		if wantStart == nil && wantEnd == nil {
			if drive["battery_details"] != nil { t.Fatalf("drive %d battery_details=%#v, want null", id, drive["battery_details"]) }
			return
		}
		details, ok := drive["battery_details"].(map[string]any)
		if !ok { t.Fatalf("drive %d battery_details=%#v", id, drive["battery_details"]) }
		if details["start_battery_level"] != wantStart || details["end_battery_level"] != wantEnd {
			t.Fatalf("drive %d battery_details=%#v want start=%v end=%v", id, details, wantStart, wantEnd)
		}
	}
	assertBattery(nativeID, float64(80), float64(75))
	assertBattery(archiveID, float64(0), float64(40))
	assertBattery(missingID, nil, nil)
	assertBattery(invalidID, nil, nil)

	for _, tc := range []struct {
		name string
		id int
		wantFirst float64
		wantLast float64
	}{
		{name:"native", id:nativeID, wantFirst:80, wantLast:75},
		{name:"archive_zero", id:archiveID, wantFirst:0, wantLast:40},
	} {
		t.Run("detail_"+tc.name, func(t *testing.T) {
			w := httptest.NewRecorder()
			r := httptest.NewRequest(http.MethodGet, "/api/v1/cars/1/drives/"+strconv.Itoa(tc.id), nil)
			api.telemetryHistory(w, r, userID, vehicleID, "drive", []string{strconv.Itoa(tc.id)})
			if w.Code != http.StatusOK { t.Fatalf("detail status=%d body=%s", w.Code, w.Body.String()) }
			var detail struct {
				Data struct {
					Drive map[string]any `json:"drive"`
				} `json:"data"`
			}
			if err := json.Unmarshal(w.Body.Bytes(), &detail); err != nil { t.Fatal(err) }
			points := detail.Data.Drive["drive_details"].([]any)
			if len(points) != 2 { t.Fatalf("detail points=%#v", points) }
			if points[0].(map[string]any)["battery_level"] != tc.wantFirst || points[1].(map[string]any)["battery_level"] != tc.wantLast {
				t.Fatalf("detail point SOC=%#v", points)
			}
			details := detail.Data.Drive["battery_details"].(map[string]any)
			if details["start_battery_level"] != tc.wantFirst || details["end_battery_level"] != tc.wantLast {
				t.Fatalf("detail battery_details=%#v", details)
			}
		})
	}
}

func TestBoundedHistoryVehicleItemsDistinguishesZeroCountsFromUnknown(t *testing.T) {
	database := boundedHistoryTestStore(t)
	userID, vehicleID := boundedHistoryTestTenant(t, database, "vehicle-count")
	api := &app{telemetry: &telemetryService{store: database}}
	vehicles := []vehicle{{ID: vehicleID, VehicleUID: "count-contract"}}

	zeroItems := api.vehicleItems(context.Background(), userID, vehicles)
	zeroStats := zeroItems[0]["teslamate_stats"].(map[string]any)
	if zeroStats["total_drives"] != 0 || zeroStats["total_charges"] != 0 {
		t.Fatalf("true zero stats=%#v", zeroStats)
	}
	if _, present := zeroStats["history_counts_status"]; present {
		t.Fatalf("true zero unexpectedly marked unavailable: %#v", zeroStats)
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	unknownItems := api.vehicleItems(ctx, userID, vehicles)
	unknownStats := unknownItems[0]["teslamate_stats"].(map[string]any)
	if unknownStats["total_drives"] != nil || unknownStats["total_charges"] != nil {
		t.Fatalf("failed counts were presented as known: %#v", unknownStats)
	}
	if unknownStats["history_counts_status"] != "unavailable" {
		t.Fatalf("failed count status=%#v", unknownStats)
	}
	encoded, err := json.Marshal(unknownItems)
	if err != nil { t.Fatal(err) }
	if !strings.Contains(string(encoded), `"total_drives":null`) || !strings.Contains(string(encoded), `"total_charges":null`) {
		t.Fatalf("unknown counts JSON=%s", encoded)
	}
}


func historyTestAllocatedBytes(t *testing.T, run func()) uint64 {
	t.Helper()
	runtime.GC()
	var before, after runtime.MemStats
	runtime.ReadMemStats(&before)
	run()
	runtime.ReadMemStats(&after)
	return after.TotalAlloc - before.TotalAlloc
}

func TestBoundedHistoryPostgresSyntheticAllocationReductionAndResponseParity(t *testing.T) {
	database := boundedHistoryTestStore(t)
	userID, vehicleID := boundedHistoryTestTenant(t, database, "bounded-alloc")
	service := &telemetryService{store: database}

	const sessionCount = 12
	const pointsPerSession = 4000
	speed, power := 42.0, 8.0
	base := time.Date(2026, time.October, 7, 0, 0, 0, 0, time.UTC)
	largeRoute := make([]telemetryRoutePoint, pointsPerSession)
	for i := range largeRoute {
		largeRoute[i] = telemetryRoutePoint{
			ObservedAt: base.Add(time.Duration(i) * time.Second),
			Latitude: 31.0 + float64(i%100)/10000,
			Longitude: 121.0 + float64(i%100)/10000,
			Speed: &speed,
			Power: &power,
		}
	}
	for i := 0; i < sessionCount; i++ {
		boundedHistoryInsertSession(t, database, userID, vehicleID, "drive", base.Add(time.Duration(i)*time.Hour), "telemetry_mqtt", "observed", largeRoute, "")
	}

	var oldItems []map[string]any
	oldAlloc := historyTestAllocatedBytes(t, func() {
		items, _, err := service.history(userID, vehicleID, "drive")
		if err != nil {
			t.Fatal(err)
		}
		oldItems = items
	})
	if len(oldItems) != sessionCount {
		t.Fatalf("old full history len=%d, want %d", len(oldItems), sessionCount)
	}
	oldItems = nil
	runtime.GC()

	var newItems []map[string]any
	var newTotal, newShow int
	newAlloc := historyTestAllocatedBytes(t, func() {
		items, _, total, show, err := service.historyPageContext(context.Background(), userID, vehicleID, "drive", time.Time{}, time.Time{}, 1, 2)
		if err != nil {
			t.Fatal(err)
		}
		newItems, newTotal, newShow = items, total, show
	})
	if len(newItems) != 2 || newTotal != sessionCount || newShow != 2 {
		t.Fatalf("bounded page len=%d total=%d show=%d", len(newItems), newTotal, newShow)
	}
	if oldAlloc < 8<<20 {
		t.Fatalf("synthetic full-history allocation=%d bytes; fixture did not exercise heavy path", oldAlloc)
	}
	if newAlloc*3 >= oldAlloc {
		t.Fatalf("bounded allocation did not materially improve: old=%d new=%d", oldAlloc, newAlloc)
	}
	t.Logf("synthetic-only allocation evidence: sessions=%d points_per_session=%d old_full_alloc_bytes=%d bounded_page_alloc_bytes=%d", sessionCount, pointsPerSession, oldAlloc, newAlloc)

	if count, err := service.historyCountContext(context.Background(), userID, vehicleID, "drive"); err != nil || count != sessionCount {
		t.Fatalf("scalar count=%d err=%v", count, err)
	}
	if exists, err := service.historyExistsContext(context.Background(), userID, vehicleID, "drive"); err != nil || !exists {
		t.Fatalf("scalar exists=%v err=%v", exists, err)
	}
	if source, ok, err := service.historySourceForReadinessContext(context.Background(), userID, vehicleID, "drive"); err != nil || !ok || source != "telemetry_mqtt" {
		t.Fatalf("scalar source=%q ok=%v err=%v", source, ok, err)
	}
	_ = fmt.Sprintf("%d/%d", oldAlloc, newAlloc)
}
