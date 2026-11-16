package main

import (
	"context"
	"encoding/json"
	"math"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	batteryHealthCacheTTL          = 5 * time.Minute
	batteryHealthEmptyCacheTTL     = time.Minute
	batteryHealthTelemetryFreshFor = 14 * 24 * time.Hour
	batteryHealthMaxEvidenceRows   = 800
	batteryHealthCapacitySampleMin = 10
	batteryHealthRangeSampleMin    = 5
	batteryHealthMinCapacityKwh    = 20.0
	batteryHealthMaxCapacityKwh    = 150.0
	batteryHealthMinFullRangeKm    = 200.0
	batteryHealthMaxFullRangeKm    = 800.0
	batteryHealthMinEfficiencyWhKm = 60.0
	batteryHealthMaxEfficiencyWhKm = 400.0
)

// batteryRangeSample is a full-range estimate derived from one rated-range
// endpoint (rated_range_km * 100 / soc_percent). Battery health is a derived
// report: it is never presented as an observed vehicle measurement.
type batteryRangeSample struct {
	At          time.Time
	FullRangeKm float64
}

// batteryCapacitySample is usable capacity estimated from the energy counter
// delta and battery-level delta inside one archived charge session.
type batteryCapacitySample struct {
	At          time.Time
	CapacityKwh float64
}

type batteryHealthReport struct {
	MaxRangeKm          float64
	CurrentRangeKm      float64
	MaxCapacityKwh      float64
	CurrentCapacityKwh  float64
	RatedEfficiencyWhKm float64
	HealthPercent       float64
	ObservedAt          time.Time
	RangeSamples        int
	CapacitySamples     int
}

type batteryHealthCacheEntry struct {
	report  *batteryHealthReport
	expires time.Time
}

type batteryEvidenceEndpoint struct {
	SOCPercent   *float64 `json:"soc_percent"`
	RatedRangeKm *float64 `json:"rated_range_km"`
}

type batteryEvidencePayload struct {
	Start           batteryEvidenceEndpoint `json:"start"`
	End             batteryEvidenceEndpoint `json:"end"`
	OutsideTempAvgC *float64                `json:"outside_temp_avg_c"`
	InsideTempAvgC  *float64                `json:"inside_temp_avg_c"`
}

func batteryHealthRound(value float64, places int) float64 {
	scale := math.Pow(10, float64(places))
	return math.Round(value*scale) / scale
}

func batteryMedian(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}
	sorted := append([]float64(nil), values...)
	sort.Float64s(sorted)
	middle := len(sorted) / 2
	if len(sorted)%2 == 1 {
		return sorted[middle]
	}
	return (sorted[middle-1] + sorted[middle]) / 2
}

func batteryPercentile(values []float64, fraction float64) float64 {
	if len(values) == 0 {
		return 0
	}
	sorted := append([]float64(nil), values...)
	sort.Float64s(sorted)
	if fraction <= 0 {
		return sorted[0]
	}
	if fraction >= 1 {
		return sorted[len(sorted)-1]
	}
	position := fraction * float64(len(sorted)-1)
	lower := int(math.Floor(position))
	upper := int(math.Ceil(position))
	if lower == upper {
		return sorted[lower]
	}
	weight := position - float64(lower)
	return sorted[lower]*(1-weight) + sorted[upper]*weight
}

func batteryValidRange(value float64) bool {
	return !math.IsNaN(value) && !math.IsInf(value, 0) &&
		value >= batteryHealthMinFullRangeKm && value <= batteryHealthMaxFullRangeKm
}

func batteryValidCapacity(value float64) bool {
	return !math.IsNaN(value) && !math.IsInf(value, 0) &&
		value >= batteryHealthMinCapacityKwh && value <= batteryHealthMaxCapacityKwh
}

// deriveBatteryHealthReport reproduces the TeslaMate battery-health contract
// from evidence available in this deployment:
//   - current capacity: median of the ten newest usable charge windows;
//   - maximum capacity: 95th percentile of all windows (plain max for tiny sets),
//     never below the current value so the reported health stays at or under 100%;
//   - current range: freshest telemetry full-range estimate, else newest evidence;
//   - maximum range: 95th percentile of rated-range endpoints (max for tiny sets);
//   - rated efficiency: current capacity * 1000 / current range (Wh/km).
func deriveBatteryHealthReport(rangeSamples []batteryRangeSample, capacitySamples []batteryCapacitySample, telemetry *batteryRangeSample) *batteryHealthReport {
	ranges := make([]batteryRangeSample, 0, len(rangeSamples))
	for _, sample := range rangeSamples {
		if batteryValidRange(sample.FullRangeKm) {
			ranges = append(ranges, sample)
		}
	}
	sort.Slice(ranges, func(i, j int) bool { return ranges[i].At.After(ranges[j].At) })

	capacities := make([]batteryCapacitySample, 0, len(capacitySamples))
	for _, sample := range capacitySamples {
		if batteryValidCapacity(sample.CapacityKwh) {
			capacities = append(capacities, sample)
		}
	}
	if len(capacities) == 0 {
		return nil
	}
	sort.Slice(capacities, func(i, j int) bool { return capacities[i].At.After(capacities[j].At) })

	recentValues := make([]float64, 0, batteryHealthCapacitySampleMin)
	for index := 0; index < len(capacities) && index < batteryHealthCapacitySampleMin; index++ {
		recentValues = append(recentValues, capacities[index].CapacityKwh)
	}
	currentCapacity := batteryMedian(recentValues)
	if currentCapacity <= 0 {
		return nil
	}

	capacityValues := make([]float64, 0, len(capacities))
	for _, sample := range capacities {
		capacityValues = append(capacityValues, sample.CapacityKwh)
	}
	maxCapacity := capacityValues[0]
	if len(capacityValues) >= batteryHealthCapacitySampleMin {
		maxCapacity = batteryPercentile(capacityValues, 0.95)
	} else {
		for _, value := range capacityValues {
			if value > maxCapacity {
				maxCapacity = value
			}
		}
	}
	if maxCapacity < currentCapacity {
		maxCapacity = currentCapacity
	}

	observedAt := capacities[0].At
	for _, sample := range capacities {
		if sample.At.After(observedAt) {
			observedAt = sample.At
		}
	}
	for _, sample := range ranges {
		if sample.At.After(observedAt) {
			observedAt = sample.At
		}
	}

	currentRange := 0.0
	maxRange := 0.0
	if len(ranges) > 0 {
		rangeValues := make([]float64, 0, len(ranges))
		for _, sample := range ranges {
			rangeValues = append(rangeValues, sample.FullRangeKm)
		}
		maxRange = rangeValues[0]
		if len(rangeValues) >= batteryHealthRangeSampleMin {
			maxRange = batteryPercentile(rangeValues, 0.95)
		} else {
			for _, value := range rangeValues {
				if value > maxRange {
					maxRange = value
				}
			}
		}
		currentRange = ranges[0].FullRangeKm
	}
	if telemetry != nil && batteryValidRange(telemetry.FullRangeKm) {
		currentRange = telemetry.FullRangeKm
		if telemetry.At.After(observedAt) {
			observedAt = telemetry.At
		}
	}
	if maxRange < currentRange {
		maxRange = currentRange
	}

	efficiency := 0.0
	if currentCapacity > 0 && currentRange > 0 {
		efficiency = currentCapacity * 1000 / currentRange
		if efficiency < batteryHealthMinEfficiencyWhKm || efficiency > batteryHealthMaxEfficiencyWhKm {
			efficiency = 0
		}
	}

	health := currentCapacity / maxCapacity * 100
	if health > 100 {
		health = 100
	}

	return &batteryHealthReport{
		MaxRangeKm:          maxRange,
		CurrentRangeKm:      currentRange,
		MaxCapacityKwh:      maxCapacity,
		CurrentCapacityKwh:  currentCapacity,
		RatedEfficiencyWhKm: efficiency,
		HealthPercent:       health,
		ObservedAt:          observedAt,
		RangeSamples:        len(ranges),
		CapacitySamples:     len(capacities),
	}
}

func (endpoint batteryEvidenceEndpoint) fullRangeSample(at time.Time) (batteryRangeSample, bool) {
	if endpoint.SOCPercent == nil || endpoint.RatedRangeKm == nil {
		return batteryRangeSample{}, false
	}
	soc := *endpoint.SOCPercent
	rated := *endpoint.RatedRangeKm
	if math.IsNaN(soc) || math.IsNaN(rated) || soc < 10 || soc > 100 || rated < 50 || rated > 2000 {
		return batteryRangeSample{}, false
	}
	fullRange := rated * 100 / soc
	if !batteryValidRange(fullRange) {
		return batteryRangeSample{}, false
	}
	return batteryRangeSample{At: at, FullRangeKm: fullRange}, true
}

func batteryJSONNumber(raw string) (float64, bool) {
	var number float64
	if err := json.Unmarshal([]byte(raw), &number); err == nil && !math.IsNaN(number) && !math.IsInf(number, 0) {
		return number, true
	}
	var text string
	if err := json.Unmarshal([]byte(raw), &text); err == nil {
		parsed, err := strconv.ParseFloat(strings.TrimSpace(text), 64)
		if err == nil && !math.IsNaN(parsed) && !math.IsInf(parsed, 0) {
			return parsed, true
		}
	}
	return 0, false
}

func (s *telemetryService) batteryRangeSamplesPostgres(ctx context.Context, userID string, vehicleID int) ([]batteryRangeSample, error) {
	rows, err := s.store.pool.Query(ctx, `
SELECT started_at, payload_json
FROM jourvolt_battery_trend_evidence
WHERE user_id=$1 AND vehicle_id=$2
ORDER BY started_at DESC, ingest_seq DESC
LIMIT $3
`, userID, vehicleID, batteryHealthMaxEvidenceRows)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	samples := make([]batteryRangeSample, 0, 64)
	for rows.Next() {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		var startedAt time.Time
		var payload string
		if err := rows.Scan(&startedAt, &payload); err != nil {
			return nil, err
		}
		var decoded batteryEvidencePayload
		if err := json.Unmarshal([]byte(payload), &decoded); err != nil {
			continue
		}
		for _, endpoint := range []batteryEvidenceEndpoint{decoded.Start, decoded.End} {
			if sample, ok := endpoint.fullRangeSample(startedAt); ok {
				samples = append(samples, sample)
			}
		}
	}
	return samples, rows.Err()
}

// batteryCapacitySamplesPostgres derives usable capacity from archived charge
// sessions. EnergyAdded in archive charge points is a session counter, so the
// first/last values bound the same window as the battery-level endpoints.
func (s *telemetryService) batteryCapacitySamplesPostgres(ctx context.Context, userID string, vehicleID int) ([]batteryCapacitySample, error) {
	rows, err := s.store.pool.Query(ctx, `
WITH points AS (
    SELECT s.public_id, s.started_at, p.ordinality AS ord,
           (p.value->>'BatteryLevel')::int AS battery_level,
           (p.value->>'EnergyAdded')::float8 AS energy_added
    FROM jourvolt_telemetry_sessions s,
         jsonb_array_elements(CASE WHEN jsonb_typeof(s.charge_points_json)='array' THEN s.charge_points_json ELSE '[]'::jsonb END) WITH ORDINALITY AS p(value, ordinality)
    WHERE s.user_id=$1 AND s.vehicle_id=$2 AND s.kind='charge' AND s.source='teslamate_archive'
      AND s.ended_at IS NOT NULL AND s.quality_state <> 'quarantined'
), bounds AS (
    SELECT public_id, started_at,
           (array_agg(battery_level ORDER BY ord) FILTER (WHERE battery_level IS NOT NULL))[1] AS start_soc,
           (array_agg(battery_level ORDER BY ord DESC) FILTER (WHERE battery_level IS NOT NULL))[1] AS end_soc,
           (array_agg(energy_added ORDER BY ord) FILTER (WHERE energy_added IS NOT NULL))[1] AS start_energy,
           (array_agg(energy_added ORDER BY ord DESC) FILTER (WHERE energy_added IS NOT NULL))[1] AS end_energy
    FROM points
    GROUP BY public_id, started_at
)
SELECT started_at, start_soc, end_soc, start_energy, end_energy
FROM bounds
ORDER BY started_at DESC
`, userID, vehicleID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	samples := make([]batteryCapacitySample, 0, 64)
	for rows.Next() {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		var startedAt time.Time
		var startSOC, endSOC *int
		var startEnergy, endEnergy *float64
		if err := rows.Scan(&startedAt, &startSOC, &endSOC, &startEnergy, &endEnergy); err != nil {
			return nil, err
		}
		if startSOC == nil || endSOC == nil || startEnergy == nil || endEnergy == nil {
			continue
		}
		socDelta := *endSOC - *startSOC
		energyDelta := *endEnergy - *startEnergy
		if socDelta < 10 || energyDelta < 5 {
			continue
		}
		capacity := energyDelta * 100 / float64(socDelta)
		if !batteryValidCapacity(capacity) {
			continue
		}
		samples = append(samples, batteryCapacitySample{At: startedAt, CapacityKwh: capacity})
	}
	return samples, rows.Err()
}

func (s *telemetryService) batteryTelemetryRangePostgres(ctx context.Context, userID string, vehicleID int) (*batteryRangeSample, error) {
	rows, err := s.store.pool.Query(ctx, `
SELECT field_name, value_json::text, observed_at
FROM jourvolt_telemetry_latest
WHERE user_id=$1 AND vehicle_id=$2 AND field_name IN ('Soc','RatedRange')
  AND observed_at >= $3
`, userID, vehicleID, time.Now().UTC().Add(-batteryHealthTelemetryFreshFor))
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var soc, rated float64
	var observedAt time.Time
	for rows.Next() {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		var fieldName, raw string
		var at time.Time
		if err := rows.Scan(&fieldName, &raw, &at); err != nil {
			return nil, err
		}
		value, ok := batteryJSONNumber(raw)
		if !ok {
			continue
		}
		switch fieldName {
		case "Soc":
			if value > 0 && value <= 100 {
				soc = value
				if at.After(observedAt) {
					observedAt = at
				}
			}
		case "RatedRange":
			if value > 0 && value <= 2000 {
				rated = value
			}
		}
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if soc <= 0 || rated <= 0 {
		return nil, nil
	}
	fullRange := rated * 100 / soc
	if !batteryValidRange(fullRange) {
		return nil, nil
	}
	return &batteryRangeSample{At: observedAt, FullRangeKm: fullRange}, nil
}

func (s *telemetryService) batteryHealthReportContext(ctx context.Context, userID string, vehicleID int) (*batteryHealthReport, error) {
	if s == nil || s.store == nil || s.store.pool == nil {
		return nil, nil
	}
	key := userID + "\x00" + strconv.Itoa(vehicleID)
	if cached, ok := s.batteryHealthCache.Load(key); ok {
		if entry, ok := cached.(batteryHealthCacheEntry); ok && time.Now().Before(entry.expires) {
			return entry.report, nil
		}
	}
	readCtx, cancel := boundedHistoryContext(ctx)
	defer cancel()

	ranges, err := s.batteryRangeSamplesPostgres(readCtx, userID, vehicleID)
	if err != nil {
		return nil, err
	}
	capacities, err := s.batteryCapacitySamplesPostgres(readCtx, userID, vehicleID)
	if err != nil {
		return nil, err
	}
	telemetry, err := s.batteryTelemetryRangePostgres(readCtx, userID, vehicleID)
	if err != nil {
		return nil, err
	}
	report := deriveBatteryHealthReport(ranges, capacities, telemetry)
	ttl := batteryHealthCacheTTL
	if report == nil {
		ttl = batteryHealthEmptyCacheTTL
	}
	s.batteryHealthCache.Store(key, batteryHealthCacheEntry{report: report, expires: time.Now().Add(ttl)})
	return report, nil
}

// batteryHealthReadinessContext reports whether the battery-health endpoint
// can produce a derived report without running the full capacity query.
func (s *telemetryService) batteryHealthReadinessContext(ctx context.Context, userID string, vehicleID int) (bool, time.Time, bool) {
	if s == nil || s.store == nil || s.store.pool == nil {
		return false, time.Time{}, false
	}
	readCtx, cancel := boundedHistoryContext(ctx)
	defer cancel()
	var evidenceAt, capacityAt *time.Time
	err := s.store.pool.QueryRow(readCtx, `
SELECT
  (SELECT MAX(started_at) FROM jourvolt_battery_trend_evidence WHERE user_id=$1 AND vehicle_id=$2),
  (SELECT MAX(started_at) FROM jourvolt_telemetry_sessions
    WHERE user_id=$1 AND vehicle_id=$2 AND kind='charge' AND source='teslamate_archive'
      AND ended_at IS NOT NULL
      AND jsonb_array_length(CASE WHEN jsonb_typeof(charge_points_json)='array' THEN charge_points_json ELSE '[]'::jsonb END) >= 2)
`, userID, vehicleID).Scan(&evidenceAt, &capacityAt)
	if err != nil {
		return false, time.Time{}, false
	}
	observedAt := time.Time{}
	if evidenceAt != nil {
		observedAt = *evidenceAt
	}
	if capacityAt != nil && capacityAt.After(observedAt) {
		observedAt = *capacityAt
	}
	return evidenceAt != nil || capacityAt != nil, observedAt, true
}

func (a *app) batteryHealth(w http.ResponseWriter, r *http.Request, userID string, vehicleID int) {
	units := map[string]any{"unit_of_length": "km", "unit_of_temperature": "C"}
	if a.telemetry == nil {
		a.json(w, http.StatusOK, map[string]any{"data": map[string]any{
			"battery_health": nil,
			"units":          units,
			"meta": map[string]any{
				"availability": "unsupported",
				"source":       "battery_trend_evidence",
			},
		}})
		return
	}
	report, err := a.telemetry.batteryHealthReportContext(r.Context(), userID, vehicleID)
	if err != nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "battery_health_unavailable"})
		return
	}
	if report == nil {
		a.json(w, http.StatusOK, map[string]any{"data": map[string]any{
			"battery_health": nil,
			"units":          units,
			"meta": map[string]any{
				"availability":   "collecting",
				"source":         "battery_trend_evidence",
				"quality_state":  "unknown",
				"quality_reason": "insufficient_capacity_or_range_samples",
			},
		}})
		return
	}

	health := map[string]any{
		"car_id":                    vehicleID,
		"max_capacity":              batteryHealthRound(report.MaxCapacityKwh, 2),
		"current_capacity":          batteryHealthRound(report.CurrentCapacityKwh, 2),
		"battery_health_percentage": batteryHealthRound(report.HealthPercent, 2),
	}
	if report.MaxRangeKm > 0 {
		health["max_range"] = batteryHealthRound(report.MaxRangeKm, 1)
	}
	if report.CurrentRangeKm > 0 {
		health["current_range"] = batteryHealthRound(report.CurrentRangeKm, 1)
	}
	if report.RatedEfficiencyWhKm > 0 {
		health["rated_efficiency"] = batteryHealthRound(report.RatedEfficiencyWhKm, 1)
	}

	meta := map[string]any{
		"availability":     "available",
		"source":           "battery_trend_evidence",
		"quality_state":    "derived",
		"quality_reason":   "rated_range_and_charge_capacity",
		"capacity_samples": report.CapacitySamples,
		"range_samples":    report.RangeSamples,
	}
	if !report.ObservedAt.IsZero() {
		meta["observed_at"] = report.ObservedAt.UTC().Format(time.RFC3339)
	}
	a.json(w, http.StatusOK, map[string]any{"data": map[string]any{
		"battery_health": health,
		"units":          units,
		"meta":           meta,
	}})
}
