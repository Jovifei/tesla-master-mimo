package main

import (
	"strings"
	"testing"
	"time"
)

func TestDriveEvidenceFromPayloadReadsEndpoints(t *testing.T) {
	payload := `{"start":{"soc_percent":75,"rated_range_km":298.9},"end":{"soc_percent":74,"rated_range_km":295.81},"outside_temp_avg_c":18.4,"inside_temp_avg_c":22.6}`
	evidence, ok := driveEvidenceFromPayload(payload)
	if !ok {
		t.Fatal("expected usable evidence")
	}
	if evidence.StartSOC == nil || *evidence.StartSOC != 75 || evidence.EndSOC == nil || *evidence.EndSOC != 74 {
		t.Fatalf("soc endpoints = %+v", evidence)
	}
	if evidence.StartRangeKm == nil || *evidence.StartRangeKm != 298.9 || evidence.EndRangeKm == nil || *evidence.EndRangeKm != 295.81 {
		t.Fatalf("range endpoints = %+v", evidence)
	}
	if evidence.OutsideTempAvgC == nil || *evidence.OutsideTempAvgC != 18.4 || evidence.InsideTempAvgC == nil || *evidence.InsideTempAvgC != 22.6 {
		t.Fatalf("temperature evidence = %+v", evidence)
	}
}

func TestDriveEvidenceFromPayloadAcceptsTemperatureOnly(t *testing.T) {
	evidence, ok := driveEvidenceFromPayload(`{"outside_temp_avg_c":-3.5}`)
	if !ok || evidence.OutsideTempAvgC == nil || *evidence.OutsideTempAvgC != -3.5 {
		t.Fatalf("temperature-only evidence = %+v ok=%v", evidence, ok)
	}
}

func TestEndpointTemperatureRejectsImplausibleValues(t *testing.T) {
	for _, value := range []float64{-61, 81} {
		if temperature, ok := endpointTemperature(&value); ok || temperature != nil {
			t.Fatalf("temperature %v accepted as %v", value, temperature)
		}
	}
	if temperature, ok := endpointTemperature(nil); ok || temperature != nil {
		t.Fatalf("nil temperature accepted as %v", temperature)
	}
}

func TestDriveEvidenceFromPayloadRejectsUnusableEndpoints(t *testing.T) {
	for _, payload := range []string{
		`not json`,
		`{"start":{"soc_percent":120,"rated_range_km":0},"end":{}}`,
		`{"start":{"soc_percent":-4,"rated_range_km":-4}}`,
	} {
		if evidence, ok := driveEvidenceFromPayload(payload); ok {
			t.Fatalf("%s decoded as %+v", payload, evidence)
		}
	}
}

func TestNearestAddressMatchesOnlyWithinThreshold(t *testing.T) {
	index := &historyPlaceIndex{Points: []historyPlacePoint{
		{Address: "近处", Latitude: 30.291023, Longitude: 119.975891},
		{Address: "远处", Latitude: 30.249741, Longitude: 120.046127},
	}}
	address, ok := index.nearestAddress(30.291000, 119.975900)
	if !ok || address != "近处" {
		t.Fatalf("nearest = %q ok=%v", address, ok)
	}
	if address, ok := index.nearestAddress(31.2304, 121.4737); ok {
		t.Fatalf("distant point matched %q", address)
	}
}

func TestChargeAddressAtRequiresSharedWindow(t *testing.T) {
	start := time.Date(2026, 11, 14, 12, 58, 23, 0, time.UTC)
	end := start.Add(28 * time.Minute)
	index := &historyPlaceIndex{Charges: []historyChargePlace{
		{Address: "同一地点", StartAt: start.Add(8 * time.Second), EndAt: end.Add(6 * time.Second)},
		{Address: "无关地点", StartAt: start.Add(-3 * time.Hour), EndAt: start.Add(-2 * time.Hour)},
	}}
	address, ok := index.chargeAddressAt(start, &end)
	if !ok || address != "同一地点" {
		t.Fatalf("charge address = %q ok=%v", address, ok)
	}
	later := start.Add(6 * time.Hour)
	if address, ok := index.chargeAddressAt(later, nil); ok {
		t.Fatalf("non-overlapping charge matched %q", address)
	}
}

func TestArchiveDriveAtRequiresSharedWindow(t *testing.T) {
	start := time.Date(2026, 11, 2, 10, 54, 0, 0, time.UTC)
	end := start.Add(40 * time.Minute)
	index := &historyPlaceIndex{Drives: []historyDriveWindow{
		{RecordID: "teslamate:drive:496", StartAt: start.Add(110 * time.Second), EndAt: end.Add(104 * time.Second)},
		{RecordID: "teslamate:drive:500", StartAt: start.Add(3 * time.Hour), EndAt: end.Add(3 * time.Hour)},
	}}
	window := index.archiveDriveAt(start, &end)
	if window == nil || window.RecordID != "teslamate:drive:496" {
		t.Fatalf("window = %+v", window)
	}
	shorter := start.Add(2 * time.Minute)
	if window := index.archiveDriveAt(start, &shorter); window != nil {
		t.Fatalf("insufficient overlap matched %+v", window)
	}
}

func TestEnrichDriveItemAddsRangeSocAndAddress(t *testing.T) {
	service := &telemetryService{}
	item := map[string]any{
		"start_date": "2026-11-02T10:54:00Z", "end_date": "2026-11-02T11:20:00Z",
		"start_address": nil, "end_address": nil, "battery_details": nil,
	}
	session := telemetrySession{
		Source:         "teslamate_archive",
		SourceRecordID: "teslamate:drive:496",
		StartAt:        time.Date(2026, 11, 2, 10, 54, 0, 0, time.UTC),
		Route: []telemetryRoutePoint{
			{Latitude: 30.291023, Longitude: 119.975891},
			{Latitude: 30.28735, Longitude: 119.977559},
		},
	}
	evidence := map[string]driveBatteryEvidence{
		"teslamate:drive:496": {
			StartSOC:        intPointerForTest(75),
			EndSOC:          intPointerForTest(74),
			StartRangeKm:    floatPointerForTest(298.9),
			EndRangeKm:      floatPointerForTest(295.81),
			OutsideTempAvgC: floatPointerForTest(18.4),
		},
	}
	places := &historyPlaceIndex{Points: []historyPlacePoint{
		{Address: "起点", Latitude: 30.291023, Longitude: 119.975891},
		{Address: "终点", Latitude: 30.28735, Longitude: 119.977559},
	}}

	service.enrichDriveItem(item, session, nil, evidence, places, 58.545)

	rated, ok := item["range_rated"].(map[string]any)
	if !ok || rated["start_range"] != 298.9 || rated["end_range"] != 295.81 {
		t.Fatalf("range_rated = %v", item["range_rated"])
	}
	if _, exists := item["energy_consumed_net"]; exists {
		t.Fatalf("derived energy must not be published as a reported scalar: %v", item["energy_consumed_net"])
	}
	contract, ok := item["energy_contract"].(map[string]any)
	if !ok {
		t.Fatalf("energy_contract = %v", item["energy_contract"])
	}
	net, _ := contract["net_energy"].(map[string]any)
	if net["method"] != "soc_capacity_estimate" || net["source"] != "teslamate_archive" || net["quality"] != "estimated" {
		t.Fatalf("net = %v", net)
	}
	value, _ := net["value_kwh"].(float64)
	if value <= 0 || value > 1 || net["start_soc"] != 75 || net["end_soc"] != 74 {
		t.Fatalf("energy estimate = %v", net)
	}
	details, ok := item["battery_details"].(map[string]any)
	if !ok || details["start_battery_level"] != 75 || details["end_battery_level"] != 74 {
		t.Fatalf("battery_details = %v", item["battery_details"])
	}
	if item["start_address"] != "起点" || item["end_address"] != "终点" {
		t.Fatalf("addresses = %v -> %v", item["start_address"], item["end_address"])
	}
	if item["start_address_inferred"] != true || item["end_address_inferred"] != true {
		t.Fatal("inferred addresses must be marked")
	}
	if item["outside_temp_avg"] != 18.4 || item["outside_temp_avg_inferred"] != nil {
		t.Fatalf("outside temperature = %v inferred=%v", item["outside_temp_avg"], item["outside_temp_avg_inferred"])
	}
}

func TestEnrichDriveItemKeepsExistingTemperature(t *testing.T) {
	service := &telemetryService{}
	item := map[string]any{"outside_temp_avg": 9.5}
	session := telemetrySession{Source: "teslamate_archive", SourceRecordID: "teslamate:drive:9"}
	evidence := map[string]driveBatteryEvidence{
		"teslamate:drive:9": {OutsideTempAvgC: floatPointerForTest(18.4)},
	}

	service.enrichDriveItem(item, session, nil, evidence, nil, 58.545)

	if item["outside_temp_avg"] != 9.5 {
		t.Fatalf("route-sample temperature replaced: %v", item["outside_temp_avg"])
	}
}

func TestEnrichDriveItemMarksBorrowedTemperature(t *testing.T) {
	service := &telemetryService{}
	start := time.Date(2026, 11, 2, 10, 54, 0, 0, time.UTC)
	end := start.Add(26 * time.Minute)
	item := map[string]any{"outside_temp_avg": nil}
	session := telemetrySession{Source: "telemetry_mqtt", SourceRecordID: "mqtt:drive:7", StartAt: start, EndAt: &end}
	window := &historyDriveWindow{RecordID: "teslamate:drive:42", StartAt: start.Add(110 * time.Second), EndAt: end.Add(104 * time.Second)}
	evidence := map[string]driveBatteryEvidence{
		"teslamate:drive:42": {OutsideTempAvgC: floatPointerForTest(-2.4)},
	}

	service.enrichDriveItem(item, session, window, evidence, nil, 58.545)

	if item["outside_temp_avg"] != -2.4 || item["outside_temp_avg_inferred"] != true {
		t.Fatalf("borrowed temperature = %v inferred=%v", item["outside_temp_avg"], item["outside_temp_avg_inferred"])
	}
}

func TestEnrichDriveItemMatchesCrossSourceArchiveWindow(t *testing.T) {
	service := &telemetryService{}
	start := time.Date(2026, 11, 2, 10, 54, 0, 0, time.UTC)
	end := start.Add(26 * time.Minute)
	item := map[string]any{
		"start_date": start.Format(time.RFC3339Nano), "end_date": end.Format(time.RFC3339Nano),
		"start_address": nil, "end_address": nil, "battery_details": nil,
		"energy_contract": map[string]any{"version": 1, "net_energy": map[string]any{"value_kwh": nil}},
	}
	session := telemetrySession{Source: "telemetry_mqtt", SourceRecordID: "mqtt:drive:7", StartAt: start, EndAt: &end}
	window := &historyDriveWindow{
		RecordID: "teslamate:drive:42", StartAt: start.Add(110 * time.Second), EndAt: end.Add(104 * time.Second),
		StartAddress: "归档起点", EndAddress: "归档终点",
	}
	evidence := map[string]driveBatteryEvidence{
		"teslamate:drive:42": {
			StartSOC: intPointerForTest(80), EndSOC: intPointerForTest(72),
			StartRangeKm: floatPointerForTest(350), EndRangeKm: floatPointerForTest(330),
		},
	}

	service.enrichDriveItem(item, session, window, evidence, nil, 58.545)

	if item["start_address"] != "归档起点" || item["end_address"] != "归档终点" {
		t.Fatalf("addresses = %v -> %v", item["start_address"], item["end_address"])
	}
	if item["start_address_inferred"] != true || item["end_address_inferred"] != true || item["range_rated_inferred"] != true {
		t.Fatal("cross-source values must be marked inferred")
	}
	details, _ := item["battery_details"].(map[string]any)
	if details["start_battery_level"] != 80 || details["end_battery_level"] != 72 {
		t.Fatalf("battery_details = %v", item["battery_details"])
	}
	contract, _ := item["energy_contract"].(map[string]any)
	net, _ := contract["net_energy"].(map[string]any)
	if net["source"] != "telemetry_mqtt" || net["method"] != "soc_capacity_estimate" {
		t.Fatalf("net = %v", net)
	}
	value, _ := net["value_kwh"].(float64)
	if value <= 0 || value > 10 {
		t.Fatalf("energy estimate = %v", value)
	}
}

func TestEnrichDriveItemKeepsExistingAddressesAndSkipsNegativeRange(t *testing.T) {
	service := &telemetryService{}
	item := map[string]any{"start_address": "已有起点", "end_address": "已有终点"}
	session := telemetrySession{
		Source:         "teslamate_archive",
		SourceRecordID: "teslamate:drive:1",
		Route:          []telemetryRoutePoint{{Latitude: 30.291023, Longitude: 119.975891}},
	}
	evidence := map[string]driveBatteryEvidence{
		"teslamate:drive:1": {
			StartRangeKm: floatPointerForTest(290),
			EndRangeKm:   floatPointerForTest(300),
		},
	}
	places := &historyPlaceIndex{Points: []historyPlacePoint{{Address: "别处", Latitude: 30.291023, Longitude: 119.975891}}}

	service.enrichDriveItem(item, session, nil, evidence, places, 58.545)

	if item["start_address"] != "已有起点" || item["end_address"] != "已有终点" {
		t.Fatalf("address overwritten: %v / %v", item["start_address"], item["end_address"])
	}
	if _, exists := item["energy_contract"]; exists {
		t.Fatalf("missing SOC endpoints produced energy: %v", item["energy_contract"])
	}
	rated, ok := item["range_rated"].(map[string]any)
	if !ok || rated["start_range"] != 290.0 || rated["end_range"] != 300.0 {
		t.Fatalf("range_rated = %v", item["range_rated"])
	}
}

func TestEnrichChargeItemBorrowsOverlappingArchiveAddress(t *testing.T) {
	service := &telemetryService{}
	start := time.Date(2026, 11, 14, 12, 41, 37, 0, time.UTC)
	end := start.Add(16 * time.Minute)
	item := map[string]any{"address": nil, "start_date": start.Format(time.RFC3339Nano), "end_date": end.Format(time.RFC3339Nano)}
	session := telemetrySession{Source: "telemetry_mqtt", StartAt: start, EndAt: &end}
	places := &historyPlaceIndex{Charges: []historyChargePlace{
		{Address: "FamilyMart, Lianchuang Street", StartAt: start.Add(time.Second), EndAt: end.Add(time.Second), EnergyAdded: floatPointerForTest(12.3)},
	}}

	service.enrichChargeItem(item, session, places)
	if address, _ := item["address"].(string); !strings.Contains(address, "Lianchuang") {
		t.Fatalf("address = %v", item["address"])
	}
	if item["address_inferred"] != true {
		t.Fatal("inferred charge address must be marked")
	}
	contract, _ := item["energy_contract"].(map[string]any)
	net, _ := contract["battery_input"].(map[string]any)
	if net["method"] != "session_counter_delta" || net["measurement_point"] != "battery_input" || net["source"] != "telemetry_mqtt" {
		t.Fatalf("net = %v", net)
	}
	if value, _ := net["value_kwh"].(float64); value != 12.3 {
		t.Fatalf("energy = %v", net["value_kwh"])
	}
}

func TestEnrichChargeItemKeepsNumericContract(t *testing.T) {
	service := &telemetryService{}
	start := time.Date(2026, 11, 14, 12, 41, 37, 0, time.UTC)
	end := start.Add(16 * time.Minute)
	existing := map[string]any{"version": 1, "battery_input": map[string]any{"value_kwh": 9.9}}
	item := map[string]any{"address": "已有地点", "start_date": start.Format(time.RFC3339Nano), "end_date": end.Format(time.RFC3339Nano), "energy_contract": existing}
	session := telemetrySession{Source: "telemetry_mqtt", StartAt: start, EndAt: &end}
	places := &historyPlaceIndex{Charges: []historyChargePlace{
		{Address: "归档地点", StartAt: start, EndAt: end, EnergyAdded: floatPointerForTest(12.3)},
	}}

	service.enrichChargeItem(item, session, places)
	contract, _ := item["energy_contract"].(map[string]any)
	if contract["battery_input"].(map[string]any)["value_kwh"] != 9.9 {
		t.Fatalf("existing contract replaced: %v", contract)
	}
	if item["address"] != "已有地点" {
		t.Fatalf("address replaced: %v", item["address"])
	}
}

func TestHistoryRouteCoordinateExpressionReadsBothKeyStyles(t *testing.T) {
	expression := historyRoutePointLatitude(`s.route_json->0`)
	if !strings.Contains(expression, `'latitude'`) || !strings.Contains(expression, `'Latitude'`) {
		t.Fatalf("expression misses a key style: %s", expression)
	}
}

func intPointerForTest(value int) *int { return &value }

func floatPointerForTest(value float64) *float64 { return &value }
