package main

import (
	"context"
	"encoding/json"
	"math"
	"strconv"
	"strings"
	"time"
)

const (
	historyPlaceCacheTTL      = 10 * time.Minute
	historyPlaceMatchMeters   = 120.0
	historyPlaceMaxRows       = 400
	historyChargeOverlapShare = 0.5
)

type driveBatteryEvidence struct {
	StartSOC     *int
	EndSOC       *int
	StartRangeKm *float64
	EndRangeKm   *float64
	// Temperature is the drive average TeslaMate reports in its drive summary.
	// It is evidence about the drive window, not a route sample.
	OutsideTempAvgC *float64
	InsideTempAvgC  *float64
}

type historyPlacePoint struct {
	Address   string
	Latitude  float64
	Longitude float64
	At        time.Time
}

type historyChargePlace struct {
	Address     string
	StartAt     time.Time
	EndAt       time.Time
	EnergyAdded *float64
}

// historyDriveWindow is one archived drive. Imports and live telemetry can
// record the same physical drive with different timestamps; a shared window is
// evidence that both records describe that one drive, never a guess by name.
type historyDriveWindow struct {
	RecordID     string
	StartAt      time.Time
	EndAt        time.Time
	StartAddress string
	EndAddress   string
}

type historyPlaceIndex struct {
	Points  []historyPlacePoint
	Charges []historyChargePlace
	Drives  []historyDriveWindow
}

type historyPlaceCacheEntry struct {
	index   *historyPlaceIndex
	expires time.Time
}

func historyRoutePointLatitude(pointExpr string) string {
	return `COALESCE(NULLIF((` + pointExpr + `)->>'latitude',''), NULLIF((` + pointExpr + `)->>'Latitude',''))::double precision`
}

func historyRoutePointLongitude(pointExpr string) string {
	return `COALESCE(NULLIF((` + pointExpr + `)->>'longitude',''), NULLIF((` + pointExpr + `)->>'Longitude',''))::double precision`
}

const historyRoutePointsExpr = `(CASE WHEN jsonb_typeof(s.route_json)='array' THEN s.route_json ELSE '[]'::jsonb END)`
const historyRouteFirstPointExpr = historyRoutePointsExpr + `->0`
const historyRouteLastPointExpr = historyRoutePointsExpr + `->(jsonb_array_length(` + historyRoutePointsExpr + `)-1)`

func historyItemText(item map[string]any, key string) string {
	value, ok := item[key].(string)
	if !ok {
		return ""
	}
	return strings.TrimSpace(value)
}

func historyItemInteger(item map[string]any, key string) (int, bool) {
	switch value := item[key].(type) {
	case int:
		return value, true
	case int64:
		return int(value), true
	case float64:
		rounded := int(math.Round(value))
		if math.Abs(value-float64(rounded)) > 1e-9 {
			return 0, false
		}
		return rounded, true
	}
	return 0, false
}

func historyItemFloat(item map[string]any, key string) (float64, bool) {
	switch value := item[key].(type) {
	case float64:
		if math.IsNaN(value) || math.IsInf(value, 0) {
			return 0, false
		}
		return value, true
	case int:
		return float64(value), true
	case int64:
		return float64(value), true
	case json.Number:
		parsed, err := value.Float64()
		if err != nil || math.IsNaN(parsed) || math.IsInf(parsed, 0) {
			return 0, false
		}
		return parsed, true
	}
	return 0, false
}

func endpointSOC(endpoint batteryEvidenceEndpoint) (*int, bool) {
	if endpoint.SOCPercent == nil {
		return nil, false
	}
	soc := int(math.Round(*endpoint.SOCPercent))
	if soc < 0 || soc > 100 {
		return nil, false
	}
	return &soc, true
}

func endpointRatedRange(endpoint batteryEvidenceEndpoint) (*float64, bool) {
	if endpoint.RatedRangeKm == nil {
		return nil, false
	}
	value := *endpoint.RatedRangeKm
	if math.IsNaN(value) || value < 1 || value > 2000 {
		return nil, false
	}
	return &value, true
}

func endpointTemperature(value *float64) (*float64, bool) {
	if value == nil || math.IsNaN(*value) || math.IsInf(*value, 0) || *value < -60 || *value > 80 {
		return nil, false
	}
	temperature := *value
	return &temperature, true
}

func driveEvidenceFromPayload(payload string) (driveBatteryEvidence, bool) {
	var decoded batteryEvidencePayload
	if err := json.Unmarshal([]byte(payload), &decoded); err != nil {
		return driveBatteryEvidence{}, false
	}
	evidence := driveBatteryEvidence{}
	usable := false
	if soc, ok := endpointSOC(decoded.Start); ok {
		evidence.StartSOC = soc
		usable = true
	}
	if soc, ok := endpointSOC(decoded.End); ok {
		evidence.EndSOC = soc
		usable = true
	}
	if rated, ok := endpointRatedRange(decoded.Start); ok {
		evidence.StartRangeKm = rated
		usable = true
	}
	if rated, ok := endpointRatedRange(decoded.End); ok {
		evidence.EndRangeKm = rated
		usable = true
	}
	if temperature, ok := endpointTemperature(decoded.OutsideTempAvgC); ok {
		evidence.OutsideTempAvgC = temperature
		usable = true
	}
	if temperature, ok := endpointTemperature(decoded.InsideTempAvgC); ok {
		evidence.InsideTempAvgC = temperature
		usable = true
	}
	return evidence, usable
}

// driveBatteryEvidenceByRecord loads the archive evidence rows that describe
// drive endpoints: battery level and rated range at start/end.
func (s *telemetryService) driveBatteryEvidenceByRecord(ctx context.Context, userID string, vehicleID int, recordIDs []string) (map[string]driveBatteryEvidence, error) {
	result := make(map[string]driveBatteryEvidence, len(recordIDs))
	if len(recordIDs) == 0 {
		return result, nil
	}
	rows, err := s.store.pool.Query(ctx, `
SELECT source_record_id, payload_json
FROM jourvolt_battery_trend_evidence
WHERE user_id=$1 AND vehicle_id=$2 AND source_record_id = ANY($3)
`, userID, vehicleID, recordIDs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		var recordID, payload string
		if err := rows.Scan(&recordID, &payload); err != nil {
			return nil, err
		}
		if evidence, ok := driveEvidenceFromPayload(payload); ok {
			result[recordID] = evidence
		}
	}
	return result, rows.Err()
}

func (s *telemetryService) historyPlacePointsPostgres(ctx context.Context, userID string, vehicleID int) ([]historyPlacePoint, error) {
	rows, err := s.store.pool.Query(ctx, `
SELECT address, latitude, longitude, at FROM (
    SELECT s.start_address AS address,
           `+historyRoutePointLatitude(historyRouteFirstPointExpr)+` AS latitude,
           `+historyRoutePointLongitude(historyRouteFirstPointExpr)+` AS longitude,
           s.started_at AS at
    FROM jourvolt_telemetry_sessions s
    WHERE s.user_id=$1 AND s.vehicle_id=$2 AND s.kind='drive' AND s.source='teslamate_archive'
      AND s.start_address IS NOT NULL AND s.start_address <> ''
    ORDER BY s.started_at DESC
    LIMIT $3
) start_points
WHERE latitude IS NOT NULL AND longitude IS NOT NULL
UNION ALL
SELECT address, latitude, longitude, at FROM (
    SELECT s.end_address AS address,
           `+historyRoutePointLatitude(historyRouteLastPointExpr)+` AS latitude,
           `+historyRoutePointLongitude(historyRouteLastPointExpr)+` AS longitude,
           s.started_at AS at
    FROM jourvolt_telemetry_sessions s
    WHERE s.user_id=$1 AND s.vehicle_id=$2 AND s.kind='drive' AND s.source='teslamate_archive'
      AND s.end_address IS NOT NULL AND s.end_address <> ''
    ORDER BY s.started_at DESC
    LIMIT $3
) end_points
WHERE latitude IS NOT NULL AND longitude IS NOT NULL
`, userID, vehicleID, historyPlaceMaxRows)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	points := make([]historyPlacePoint, 0, 128)
	for rows.Next() {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		var address string
		var latitude, longitude *float64
		var at time.Time
		if err := rows.Scan(&address, &latitude, &longitude, &at); err != nil {
			return nil, err
		}
		address = strings.TrimSpace(address)
		if address == "" || latitude == nil || longitude == nil {
			continue
		}
		points = append(points, historyPlacePoint{
			Address:   address,
			Latitude:  *latitude,
			Longitude: *longitude,
			At:        at,
		})
	}
	return points, rows.Err()
}

func (s *telemetryService) historyChargePlacesPostgres(ctx context.Context, userID string, vehicleID int) ([]historyChargePlace, error) {
	rows, err := s.store.pool.Query(ctx, `
SELECT address, started_at, COALESCE(ended_at, started_at), energy_added
FROM jourvolt_telemetry_sessions
WHERE user_id=$1 AND vehicle_id=$2 AND kind='charge'
  AND address IS NOT NULL AND address <> ''
  AND ended_at IS NOT NULL
ORDER BY started_at DESC
LIMIT $3
`, userID, vehicleID, historyPlaceMaxRows)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	places := make([]historyChargePlace, 0, 64)
	for rows.Next() {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		var address string
		var startedAt, endedAt time.Time
		var energyAdded *float64
		if err := rows.Scan(&address, &startedAt, &endedAt, &energyAdded); err != nil {
			return nil, err
		}
		address = strings.TrimSpace(address)
		if address == "" {
			continue
		}
		places = append(places, historyChargePlace{Address: address, StartAt: startedAt, EndAt: endedAt, EnergyAdded: energyAdded})
	}
	return places, rows.Err()
}

func (s *telemetryService) historyDriveWindowsPostgres(ctx context.Context, userID string, vehicleID int) ([]historyDriveWindow, error) {
	rows, err := s.store.pool.Query(ctx, `
SELECT COALESCE(source_record_id,''), started_at, COALESCE(ended_at, started_at),
       COALESCE(start_address,''), COALESCE(end_address,'')
FROM jourvolt_telemetry_sessions
WHERE user_id=$1 AND vehicle_id=$2 AND kind='drive' AND source='teslamate_archive'
  AND ended_at IS NOT NULL AND quality_state <> 'quarantined'
ORDER BY started_at DESC
LIMIT $3
`, userID, vehicleID, historyPlaceMaxRows)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	windows := make([]historyDriveWindow, 0, 64)
	for rows.Next() {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		var window historyDriveWindow
		if err := rows.Scan(&window.RecordID, &window.StartAt, &window.EndAt, &window.StartAddress, &window.EndAddress); err != nil {
			return nil, err
		}
		if !window.EndAt.After(window.StartAt) {
			continue
		}
		window.StartAddress = strings.TrimSpace(window.StartAddress)
		window.EndAddress = strings.TrimSpace(window.EndAddress)
		windows = append(windows, window)
	}
	return windows, rows.Err()
}

func (s *telemetryService) historyPlaceIndexContext(ctx context.Context, userID string, vehicleID int) (*historyPlaceIndex, error) {
	if s == nil || s.store == nil || s.store.pool == nil {
		return nil, nil
	}
	key := userID + "\x00" + strconv.Itoa(vehicleID)
	if cached, ok := s.historyPlaceCache.Load(key); ok {
		if entry, ok := cached.(historyPlaceCacheEntry); ok && time.Now().Before(entry.expires) {
			return entry.index, nil
		}
	}
	readCtx, cancel := boundedHistoryContext(ctx)
	defer cancel()
	points, err := s.historyPlacePointsPostgres(readCtx, userID, vehicleID)
	if err != nil {
		return nil, err
	}
	charges, err := s.historyChargePlacesPostgres(readCtx, userID, vehicleID)
	if err != nil {
		return nil, err
	}
	drives, err := s.historyDriveWindowsPostgres(readCtx, userID, vehicleID)
	if err != nil {
		return nil, err
	}
	index := &historyPlaceIndex{Points: points, Charges: charges, Drives: drives}
	s.historyPlaceCache.Store(key, historyPlaceCacheEntry{index: index, expires: time.Now().Add(historyPlaceCacheTTL)})
	return index, nil
}

// haversineMeters is shared with vehicle_places.go: both measurements are
// metres on the same earth radius.

// nearestAddress returns the address of the closest labelled archive endpoint.
// Only places within historyPlaceMatchMeters qualify; a farther match would
// not be evidence that the vehicle stopped there.
func (index *historyPlaceIndex) nearestAddress(latitude, longitude float64) (string, bool) {
	if index == nil || latitude == 0 && longitude == 0 {
		return "", false
	}
	best := historyPlaceMatchMeters
	address := ""
	for _, point := range index.Points {
		distance := haversineMeters(latitude, longitude, point.Latitude, point.Longitude)
		if distance <= best {
			best = distance
			address = point.Address
		}
	}
	return address, address != ""
}

func historyOverlapShare(start, end, otherStart, otherEnd time.Time) float64 {
	overlapStart := start
	if otherStart.After(overlapStart) {
		overlapStart = otherStart
	}
	overlapEnd := end
	if otherEnd.Before(overlapEnd) {
		overlapEnd = otherEnd
	}
	overlap := overlapEnd.Sub(overlapStart)
	if overlap <= 0 {
		return 0
	}
	shorter := end.Sub(start)
	if other := otherEnd.Sub(otherStart); other < shorter {
		shorter = other
	}
	if shorter <= 0 {
		return 0
	}
	return float64(overlap) / float64(shorter)
}

// chargeAt matches a live or imported charge window to an archived charge
// window at the same location. Exact overlapping windows in this deployment
// start within seconds of each other; a shared window is required, not just
// proximity in time.
func (index *historyPlaceIndex) chargeAt(start time.Time, end *time.Time) *historyChargePlace {
	if index == nil {
		return nil
	}
	windowEnd := start
	if end != nil && end.After(start) {
		windowEnd = *end
	}
	bestOverlap := time.Duration(0)
	var best *historyChargePlace
	for position := range index.Charges {
		place := &index.Charges[position]
		if historyOverlapShare(start, windowEnd, place.StartAt, place.EndAt) < historyChargeOverlapShare {
			continue
		}
		overlapStart := start
		if place.StartAt.After(overlapStart) {
			overlapStart = place.StartAt
		}
		overlapEnd := windowEnd
		if place.EndAt.Before(overlapEnd) {
			overlapEnd = place.EndAt
		}
		if overlap := overlapEnd.Sub(overlapStart); overlap > bestOverlap {
			bestOverlap = overlap
			best = place
		}
	}
	return best
}

func (index *historyPlaceIndex) chargeAddressAt(start time.Time, end *time.Time) (string, bool) {
	place := index.chargeAt(start, end)
	if place == nil || place.Address == "" {
		return "", false
	}
	return place.Address, true
}

// archiveDriveAt returns the archived drive whose window overlaps this session.
// Both records describe one physical drive when their windows share at least
// half of the shorter window; drives of one car cannot overlap otherwise.
func (index *historyPlaceIndex) archiveDriveAt(start time.Time, end *time.Time) *historyDriveWindow {
	if index == nil || end == nil || !end.After(start) {
		return nil
	}
	bestOverlap := time.Duration(0)
	var best *historyDriveWindow
	for position := range index.Drives {
		window := &index.Drives[position]
		if historyOverlapShare(start, *end, window.StartAt, window.EndAt) < historyChargeOverlapShare {
			continue
		}
		overlapStart := start
		if window.StartAt.After(overlapStart) {
			overlapStart = window.StartAt
		}
		overlapEnd := *end
		if window.EndAt.Before(overlapEnd) {
			overlapEnd = window.EndAt
		}
		if overlap := overlapEnd.Sub(overlapStart); overlap > bestOverlap {
			bestOverlap = overlap
			best = window
		}
	}
	return best
}

func sessionEndpoint(session telemetrySession, start bool) (float64, float64, bool) {
	if len(session.Route) == 0 {
		return 0, 0, false
	}
	point := session.Route[0]
	if !start {
		point = session.Route[len(session.Route)-1]
	}
	if point.Latitude == 0 && point.Longitude == 0 {
		return 0, 0, false
	}
	return point.Latitude, point.Longitude, true
}

func historySourceOf(session telemetrySession) string {
	if source := strings.TrimSpace(session.Source); source != "" {
		return source
	}
	return "telemetry_mqtt"
}

func historyHasNumericEnergy(item map[string]any) bool {
	contract, _ := item["energy_contract"].(map[string]any)
	for _, key := range []string{"net_energy", "battery_input"} {
		metric, _ := contract[key].(map[string]any)
		if metric == nil || metric["value_kwh"] == nil {
			continue
		}
		switch metric["value_kwh"].(type) {
		case float64, int, int64, json.Number:
			return true
		}
	}
	return false
}

// driveEnergyEstimateContract publishes a SOC-based energy estimate using the
// same evidence contract the client validates: capacity comes from the derived
// battery-health report, endpoints from this drive's own or matched evidence.
func driveEnergyEstimateContract(item map[string]any, startSOC, endSOC int, capacityKwh float64, source string) map[string]any {
	startDate := historyItemText(item, "start_date")
	endDate := historyItemText(item, "end_date")
	if startDate == "" || endDate == "" || startSOC < 0 || startSOC > 100 || endSOC < 0 || endSOC > 100 || startSOC == endSOC {
		return nil
	}
	if !batteryValidCapacity(capacityKwh) {
		return nil
	}
	value := (float64(startSOC) - float64(endSOC)) / 100 * capacityKwh
	if value <= 0 || value > 150 {
		return nil
	}
	return map[string]any{
		"version": 1,
		"net_energy": map[string]any{
			"value_kwh":             value,
			"unit":                  "kWh",
			"method":                "soc_capacity_estimate",
			"measurement_point":     "battery_soc",
			"source":                source,
			"quality":               "estimated",
			"time_basis":            "source_report",
			"coverage_kind":         "endpoints",
			"coverage_ratio":        1.0,
			"capacity_kwh":          capacityKwh,
			"capacity_basis":        "api_reported_current_capacity",
			"capacity_source_field": "battery_health.current_capacity",
			"calculation_version":   1,
			"start_soc":             startSOC,
			"end_soc":               endSOC,
			"start_date":            startDate,
			"end_date":              endDate,
			"reason":                "soc_capacity_estimate",
		},
	}
}

// chargeEnergyEstimateContract reuses the archived session counter of the same
// charging window. It is a reported counter, not a capacity guess.
func chargeEnergyEstimateContract(item map[string]any, energyKwh float64, source string) map[string]any {
	startDate := historyItemText(item, "start_date")
	endDate := historyItemText(item, "end_date")
	if startDate == "" || endDate == "" || energyKwh <= 0 || energyKwh > 200 {
		return nil
	}
	return map[string]any{
		"version": 1,
		"battery_input": map[string]any{
			"value_kwh":         energyKwh,
			"unit":              "kWh",
			"method":            "session_counter_delta",
			"measurement_point": "battery_input",
			"source":            source,
			"quality":           "reported",
			"time_basis":        "source_report",
			"coverage_kind":     "endpoints",
			"coverage_ratio":    1.0,
			"start_date":        startDate,
			"end_date":          endDate,
		},
	}
}

func (s *telemetryService) enrichDriveItem(item map[string]any, session telemetrySession, window *historyDriveWindow, evidence map[string]driveBatteryEvidence, places *historyPlaceIndex, capacityKwh float64) {
	if window != nil {
		if historyItemText(item, "start_address") == "" && window.StartAddress != "" {
			item["start_address"] = window.StartAddress
			item["start_address_inferred"] = true
		}
		if historyItemText(item, "end_address") == "" && window.EndAddress != "" {
			item["end_address"] = window.EndAddress
			item["end_address_inferred"] = true
		}
	}
	found, hasEvidence := evidence[strings.TrimSpace(session.SourceRecordID)]
	evidenceFromMatchedWindow := false
	if !hasEvidence && window != nil && window.RecordID != "" {
		found, hasEvidence = evidence[window.RecordID]
		evidenceFromMatchedWindow = hasEvidence
	}
	if hasEvidence {
		if found.StartRangeKm != nil && found.EndRangeKm != nil {
			item["range_rated"] = map[string]any{
				"start_range": batteryHealthRound(*found.StartRangeKm, 2),
				"end_range":   batteryHealthRound(*found.EndRangeKm, 2),
			}
			if window != nil {
				item["range_rated_inferred"] = true
			}
		}
		// TeslaMate reports one average temperature per drive. It belongs to
		// this drive's window even when the drive came from live telemetry and
		// only the overlapping archive record carried the summary.
		if _, present := historyItemFloat(item, "outside_temp_avg"); !present && found.OutsideTempAvgC != nil {
			item["outside_temp_avg"] = batteryHealthRound(*found.OutsideTempAvgC, 1)
			if evidenceFromMatchedWindow {
				item["outside_temp_avg_inferred"] = true
			}
		}
		if _, present := historyItemFloat(item, "inside_temp_avg"); !present && found.InsideTempAvgC != nil {
			item["inside_temp_avg"] = batteryHealthRound(*found.InsideTempAvgC, 1)
			if evidenceFromMatchedWindow {
				item["inside_temp_avg_inferred"] = true
			}
		}
		details, _ := item["battery_details"].(map[string]any)
		if details == nil {
			if found.StartSOC != nil || found.EndSOC != nil {
				details = map[string]any{"start_battery_level": nil, "end_battery_level": nil}
				item["battery_details"] = details
			}
		}
		if details != nil {
			if details["start_battery_level"] == nil && found.StartSOC != nil {
				details["start_battery_level"] = *found.StartSOC
			}
			if details["end_battery_level"] == nil && found.EndSOC != nil {
				details["end_battery_level"] = *found.EndSOC
			}
		}
	}
	if historyItemText(item, "start_address") == "" {
		if latitude, longitude, ok := sessionEndpoint(session, true); ok {
			if address, found := places.nearestAddress(latitude, longitude); found {
				item["start_address"] = address
				item["start_address_inferred"] = true
			}
		}
	}
	if historyItemText(item, "end_address") == "" {
		if latitude, longitude, ok := sessionEndpoint(session, false); ok {
			if address, found := places.nearestAddress(latitude, longitude); found {
				item["end_address"] = address
				item["end_address_inferred"] = true
			}
		}
	}
	if capacityKwh > 0 && !historyHasNumericEnergy(item) {
		details, _ := item["battery_details"].(map[string]any)
		startSOC, startOK := historyItemInteger(details, "start_battery_level")
		endSOC, endOK := historyItemInteger(details, "end_battery_level")
		if startOK && endOK {
			if contract := driveEnergyEstimateContract(item, startSOC, endSOC, capacityKwh, historySourceOf(session)); contract != nil {
				item["energy_contract"] = contract
			}
		}
	}
}

func (s *telemetryService) enrichChargeItem(item map[string]any, session telemetrySession, places *historyPlaceIndex) {
	if historySourceOf(session) == "teslamate_archive" {
		return
	}
	place := places.chargeAt(session.StartAt, session.EndAt)
	if place == nil {
		return
	}
	if historyItemText(item, "address") == "" && place.Address != "" {
		item["address"] = place.Address
		item["address_inferred"] = true
	}
	if place.EnergyAdded != nil && !historyHasNumericEnergy(item) {
		if contract := chargeEnergyEstimateContract(item, *place.EnergyAdded, historySourceOf(session)); contract != nil {
			item["energy_contract"] = contract
		}
	}
}

// enrichHistorySessions is additive and best-effort: history must still load
// when evidence tables or place indexes are unavailable. Each query is bounded
// by the same read budget as the page itself.
func (s *telemetryService) enrichHistorySessions(ctx context.Context, userID string, vehicleID int, kind string, items []map[string]any, sessions []telemetrySession) {
	if s == nil || s.store == nil || s.store.pool == nil || len(items) == 0 || len(sessions) != len(items) {
		return
	}
	if kind != "drive" && kind != "charge" {
		return
	}
	if ctx == nil {
		ctx = context.Background()
	}
	if err := ctx.Err(); err != nil {
		return
	}
	enrichCtx, cancel := boundedHistoryContext(ctx)
	defer cancel()

	places, _ := s.historyPlaceIndexContext(enrichCtx, userID, vehicleID)

	capacityKwh := 0.0
	if kind == "drive" {
		if report, err := s.batteryHealthReportContext(enrichCtx, userID, vehicleID); err == nil && report != nil {
			capacityKwh = report.CurrentCapacityKwh
		}
	}

	evidence := map[string]driveBatteryEvidence{}
	windows := make([]*historyDriveWindow, len(sessions))
	if kind == "drive" {
		recordIDs := make([]string, 0, len(sessions))
		seen := make(map[string]bool, len(sessions))
		addRecord := func(recordID string) {
			recordID = strings.TrimSpace(recordID)
			if recordID == "" || seen[recordID] {
				return
			}
			seen[recordID] = true
			recordIDs = append(recordIDs, recordID)
		}
		for index, session := range sessions {
			if historySourceOf(session) == "teslamate_archive" {
				addRecord(session.SourceRecordID)
				continue
			}
			// A live or imported record can describe the same drive as an
			// archived record. Its window, not its label, is the evidence.
			if places != nil {
				if window := places.archiveDriveAt(session.StartAt, session.EndAt); window != nil {
					windows[index] = window
					addRecord(window.RecordID)
				}
			}
		}
		if len(recordIDs) > 0 {
			if found, err := s.driveBatteryEvidenceByRecord(enrichCtx, userID, vehicleID, recordIDs); err == nil {
				evidence = found
			}
		}
	}

	for index, item := range items {
		if err := enrichCtx.Err(); err != nil {
			return
		}
		session := sessions[index]
		switch kind {
		case "drive":
			s.enrichDriveItem(item, session, windows[index], evidence, places, capacityKwh)
		case "charge":
			s.enrichChargeItem(item, session, places)
		}
	}
}
