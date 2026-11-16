package main

import (
	"context"
	"encoding/json"
	"math"
	"sort"
	"strings"
	"time"
)

// Driving power integration (App parity: drive_power_integral).
//
// The App computes a drive's energy from the recorded power samples when no
// reported net-energy contract exists. List responses deliberately omit
// drive_details, so the client cannot do that itself for history rows. This
// file computes the same bounded integral on the server and publishes it as a
// qualified energy contract. It is always an estimate: coverage and sample
// spacing decide whether the contract is admissible.

const (
	drivePowerMaxGapSeconds      = 30.0
	drivePowerMaxSamples         = 20000
	drivePowerMinCoverageRatio   = 0.85
	drivePowerMaxEnergyKwh       = 200.0
	drivePowerContractMaxItems   = 100
	drivePowerSampleTimeField    = "drive_details.power"
	drivePowerContractMethod     = "drive_power_integral"
	drivePowerCoverageKind       = "time"
	drivePowerMeasurementPoint   = "drive_power"
	drivePowerContractVersionNum = 1
)

type drivePowerSample struct {
	At      time.Time
	PowerKW *float64
}

type drivePowerIntegral struct {
	EnergyKwh       float64
	CoverageSeconds float64
	WindowSeconds   float64
	Complete        bool
}

type drivePowerPointJSON struct {
	Date            string   `json:"date"`
	ObservedAt      string   `json:"ObservedAt"`
	ObservedAtSnake string   `json:"observed_at"`
	Power           *float64 `json:"power"`
	PowerUpper      *float64 `json:"Power"`
}

func drivePowerPointTime(point drivePowerPointJSON) (time.Time, bool) {
	raw := strings.TrimSpace(point.ObservedAt)
	if raw == "" {
		raw = strings.TrimSpace(point.ObservedAtSnake)
	}
	if raw == "" {
		raw = strings.TrimSpace(point.Date)
	}
	if raw == "" {
		return time.Time{}, false
	}
	at, err := time.Parse(time.RFC3339Nano, raw)
	if err != nil || at.IsZero() {
		return time.Time{}, false
	}
	return at.UTC(), true
}

func drivePowerPointValue(point drivePowerPointJSON) *float64 {
	value := point.Power
	if value == nil {
		value = point.PowerUpper
	}
	if value == nil {
		return nil
	}
	if math.IsNaN(*value) || math.IsInf(*value, 0) {
		return nil
	}
	return value
}

// drivePowerSamplesFromRoute decodes only the two fields the integral needs.
// A malformed point is retained as an unknown power sample so the surrounding
// gap is never silently bridged.
func drivePowerSamplesFromRoute(raw []byte) []drivePowerSample {
	if len(raw) == 0 || string(raw) == "null" {
		return nil
	}
	var points []drivePowerPointJSON
	if err := json.Unmarshal(raw, &points); err != nil || len(points) > drivePowerMaxSamples {
		return nil
	}
	samples := make([]drivePowerSample, 0, len(points))
	for _, point := range points {
		at, ok := drivePowerPointTime(point)
		if !ok {
			samples = append(samples, drivePowerSample{})
			continue
		}
		samples = append(samples, drivePowerSample{At: at, PowerKW: drivePowerPointValue(point)})
	}
	return samples
}

func drivePowerSamplesFromArchiveRoute(points []historyImportRoutePoint) []drivePowerSample {
	samples := make([]drivePowerSample, 0, len(points))
	for _, point := range points {
		at, err := time.Parse(time.RFC3339Nano, strings.TrimSpace(point.Date))
		if err != nil || at.IsZero() {
			samples = append(samples, drivePowerSample{})
			continue
		}
		power := point.Power
		if power == nil || math.IsNaN(*power) || math.IsInf(*power, 0) {
			samples = append(samples, drivePowerSample{At: at.UTC()})
			continue
		}
		value := *power
		samples = append(samples, drivePowerSample{At: at.UTC(), PowerKW: &value})
	}
	return samples
}

func drivePowerSamplesFromRoutePoints(points []telemetryRoutePoint) []drivePowerSample {
	samples := make([]drivePowerSample, 0, len(points))
	for _, point := range points {
		at := point.ObservedAt.UTC()
		if at.IsZero() {
			samples = append(samples, drivePowerSample{})
			continue
		}
		if point.Power == nil || math.IsNaN(*point.Power) || math.IsInf(*point.Power, 0) {
			samples = append(samples, drivePowerSample{At: at})
			continue
		}
		value := *point.Power
		samples = append(samples, drivePowerSample{At: at, PowerKW: &value})
	}
	return samples
}

// integrateDrivePower reproduces the App's trapezoidal rule: consecutive
// samples at most 30 s apart contribute over their overlap with the drive
// window; duplicate timestamps with conflicting values are unusable.
func integrateDrivePower(start, end time.Time, samples []drivePowerSample) (drivePowerIntegral, bool) {
	start, end = start.UTC(), end.UTC()
	if start.IsZero() || end.IsZero() || !end.After(start) {
		return drivePowerIntegral{}, false
	}
	window := end.Sub(start).Seconds()
	if window <= 0 {
		return drivePowerIntegral{}, false
	}

	type grouped struct {
		at    time.Time
		power *float64
		known bool
	}
	groups := make(map[int64]grouped, len(samples))
	for _, sample := range samples {
		if sample.At.IsZero() {
			// An unusable timestamp cannot be placed; it is not allowed to
			// extend coverage across the missing interval.
			continue
		}
		key := sample.At.UnixNano()
		previous, seen := groups[key]
		if !seen {
			groups[key] = grouped{at: sample.At, power: sample.PowerKW, known: true}
			continue
		}
		// Duplicate timestamps: equal values are safe, conflicting ones are not.
		if previous.power != nil && sample.PowerKW != nil && *previous.power == *sample.PowerKW {
			continue
		}
		groups[key] = grouped{at: previous.at, power: nil, known: true}
	}
	points := make([]grouped, 0, len(groups))
	for _, value := range groups {
		points = append(points, value)
	}
	sort.Slice(points, func(i, j int) bool { return points[i].at.Before(points[j].at) })

	energy := 0.0
	coverage := 0.0
	for index := 1; index < len(points); index++ {
		left, right := points[index-1], points[index]
		if left.power == nil || right.power == nil {
			continue
		}
		gap := right.at.Sub(left.at).Seconds()
		if gap <= 0 || gap > drivePowerMaxGapSeconds {
			continue
		}
		from, to := left.at, right.at
		if from.Before(start) {
			from = start
		}
		if to.After(end) {
			to = end
		}
		if !to.After(from) {
			continue
		}
		// Linear interpolation of the two sample values at the clamped ends.
		leftOffset := from.Sub(left.at).Seconds() / gap
		rightOffset := to.Sub(left.at).Seconds() / gap
		p0 := *left.power*(1-leftOffset) + *right.power*leftOffset
		p1 := *left.power*(1-rightOffset) + *right.power*rightOffset
		part := (p0/2 + p1/2) * to.Sub(from).Seconds() / 3600
		if math.IsNaN(part) || math.IsInf(part, 0) {
			return drivePowerIntegral{}, false
		}
		energy += part
		coverage += to.Sub(from).Seconds()
	}
	if coverage <= 0 || energy <= 0 || energy > drivePowerMaxEnergyKwh {
		return drivePowerIntegral{}, false
	}
	if coverage > window {
		coverage = window
	}
	ratio := coverage / window
	if ratio < drivePowerMinCoverageRatio {
		return drivePowerIntegral{}, false
	}
	if ratio > 1 {
		ratio = 1
	}
	return drivePowerIntegral{
		EnergyKwh:       energy,
		CoverageSeconds: coverage,
		WindowSeconds:   window,
		Complete:        ratio >= 1-1e-6,
	}, true
}

func drivePowerTimeBasis(source string) string {
	if source == "teslamate_archive" {
		return "source_sample_time"
	}
	return "collector_received_at"
}

func drivePowerEnergyContract(item map[string]any, session telemetrySession, integral drivePowerIntegral) map[string]any {
	startDate := historyItemText(item, "start_date")
	endDate := historyItemText(item, "end_date")
	source := historySourceOf(session)
	if startDate == "" || endDate == "" || source == "" {
		return nil
	}
	reason := "power_integral_short_trip"
	if integral.Complete {
		reason = "complete_power_window"
	}
	return map[string]any{
		"version": drivePowerContractVersionNum,
		"net_energy": map[string]any{
			"value_kwh":         batteryHealthRound(integral.EnergyKwh, 4),
			"unit":              "kWh",
			"method":            drivePowerContractMethod,
			"measurement_point": drivePowerMeasurementPoint,
			"source":            source,
			"source_field":      drivePowerSampleTimeField,
			"quality":           "estimated",
			"time_basis":        drivePowerTimeBasis(source),
			"coverage_kind":     drivePowerCoverageKind,
			"coverage_seconds":  batteryHealthRound(integral.CoverageSeconds, 2),
			"coverage_ratio":    batteryHealthRound(integral.CoverageSeconds/integral.WindowSeconds, 6),
			"covered_energy_kwh": batteryHealthRound(
				integral.EnergyKwh, 4),
			"start_date": startDate,
			"end_date":   endDate,
			"reason":     reason,
		},
	}
}

// attachDrivePowerEnergyContractFromSession is the detail/memory counterpart:
// the full route is already in memory, so no extra query is needed.
func attachDrivePowerEnergyContractFromSession(item map[string]any, session telemetrySession) {
	if session.Kind != "drive" || session.EndAt == nil || !session.EndAt.After(session.StartAt) || historyHasNumericEnergy(item) {
		return
	}
	var samples []drivePowerSample
	if session.Source == "teslamate_archive" && len(session.ArchiveRoute) > 0 {
		samples = drivePowerSamplesFromArchiveRoute(session.ArchiveRoute)
	} else {
		samples = drivePowerSamplesFromRoutePoints(session.Route)
	}
	integral, ok := integrateDrivePower(session.StartAt, *session.EndAt, samples)
	if !ok {
		return
	}
	if contract := drivePowerEnergyContract(item, session, integral); contract != nil {
		item["energy_contract"] = contract
	}
}

// attachDrivePowerEnergyContracts fills the one gap list rows cannot solve on
// their own: a drive without SOC endpoints or a reported energy contract.
// Rows that already carry numeric energy stay untouched.
func (s *telemetryService) attachDrivePowerEnergyContracts(ctx context.Context, userID string, vehicleID int, items []map[string]any, sessions []telemetrySession) {
	if s == nil || s.store == nil || s.store.pool == nil || len(items) == 0 || len(items) != len(sessions) {
		return
	}
	if ctx == nil {
		ctx = context.Background()
	}
	if err := ctx.Err(); err != nil {
		return
	}
	type candidate struct {
		index   int
		session telemetrySession
	}
	candidates := make([]candidate, 0, len(items))
	ids := make([]string, 0, len(items))
	for index, session := range sessions {
		if session.Kind != "drive" || session.EndAt == nil || !session.EndAt.After(session.StartAt) {
			continue
		}
		item := items[index]
		if historyHasNumericEnergy(item) {
			continue
		}
		startDate := historyItemText(item, "start_date")
		endDate := historyItemText(item, "end_date")
		if startDate == "" || endDate == "" {
			continue
		}
		candidates = append(candidates, candidate{index: index, session: session})
		ids = append(ids, session.ID)
		if len(candidates) >= drivePowerContractMaxItems {
			break
		}
	}
	if len(candidates) == 0 {
		return
	}
	rows, err := s.store.pool.Query(ctx, `
SELECT id, route_json
FROM jourvolt_telemetry_sessions
WHERE user_id=$1 AND vehicle_id=$2 AND id = ANY($3::text[])
`, userID, vehicleID, ids)
	if err != nil {
		return
	}
	defer rows.Close()

	routes := make(map[string][]byte, len(candidates))
	for rows.Next() {
		var id string
		var raw []byte
		if err := rows.Scan(&id, &raw); err != nil {
			return
		}
		routes[id] = raw
	}
	if err := rows.Err(); err != nil {
		return
	}
	for _, entry := range candidates {
		integral, ok := integrateDrivePower(entry.session.StartAt, *entry.session.EndAt, drivePowerSamplesFromRoute(routes[entry.session.ID]))
		if !ok {
			continue
		}
		if contract := drivePowerEnergyContract(items[entry.index], entry.session, integral); contract != nil {
			items[entry.index]["energy_contract"] = contract
		}
	}
}
