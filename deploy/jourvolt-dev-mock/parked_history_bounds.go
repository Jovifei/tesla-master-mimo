package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"sort"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"
)

type parkedHistoryBound struct {
	Session          telemetrySession
	StartSOC, EndSOC *int
}

// This is a bound on the read response, not a claim that the archive captured
// every physical charge. charge_sessions_complete only says the scoped query
// returned every matching stored session without truncation or rejection.
const maxParkedChargeSessions = 64
const parkedChargeSOCTolerance = 5 * time.Minute

func parkedChargeBoundarySOC(points []telemetryChargePoint, at time.Time) (*int, *time.Time) {
	var levelAtBoundary *int
	var observedAt *time.Time
	var nearest time.Duration
	ambiguous := false
	for _, point := range points {
		level := observedRouteBatteryLevel(point.BatteryLevel)
		if level == nil {
			continue
		}
		distance := point.ObservedAt.Sub(at)
		if distance < 0 {
			distance = -distance
		}
		if distance > parkedChargeSOCTolerance {
			continue
		}
		if observedAt == nil || distance < nearest {
			value, timestamp := *level, point.ObservedAt
			levelAtBoundary, observedAt, nearest, ambiguous = &value, &timestamp, distance, false
		} else if distance == nearest && (!point.ObservedAt.Equal(*observedAt) || *level != *levelAtBoundary) {
			ambiguous = true
		}
	}
	if ambiguous {
		return nil, nil
	}
	return levelAtBoundary, observedAt
}

func parkedLinkedCharges(older, newer telemetrySession, candidates []telemetrySession) ([]map[string]any, bool) {
	start, end := *older.EndAt, newer.StartAt
	rows := append([]telemetrySession(nil), candidates...)
	sort.Slice(rows, func(i, j int) bool {
		if rows[i].StartAt.Equal(rows[j].StartAt) {
			return rows[i].PublicID < rows[j].PublicID
		}
		return rows[i].StartAt.Before(rows[j].StartAt)
	})
	result := make([]map[string]any, 0, min(len(rows), maxParkedChargeSessions))
	complete := true
	seen := make(map[int]bool)
	var previousEnd *time.Time
	for _, row := range rows {
		if row.Kind != "charge" || !row.StartAt.Before(end) || (row.EndAt != nil && !row.EndAt.After(start)) {
			continue
		}
		if row.Source != older.Source || row.SourceInstanceID != older.SourceInstanceID ||
			row.SourceVehicleID != older.SourceVehicleID || row.QualityState == "quarantined" || row.PublicID <= 0 {
			complete = false
			continue
		}
		if seen[row.PublicID] {
			complete = false
			continue
		}
		seen[row.PublicID] = true
		if row.EndAt != nil && !row.EndAt.After(row.StartAt) {
			complete = false
			continue
		}
		if previousEnd != nil && row.StartAt.Before(*previousEnd) {
			complete = false
		}
		if row.EndAt != nil {
			previousEnd = row.EndAt
		}
		if len(result) == maxParkedChargeSessions {
			complete = false
			continue
		}
		var endDate any
		if row.EndAt != nil {
			endDate = row.EndAt.UTC().Format(time.RFC3339Nano)
		}
		if row.StartAt.Before(start) || row.EndAt == nil || row.EndAt.After(end) {
			complete = false
		}
		var energy any
		if row.QualityState == "observed" && row.EndAt != nil {
			if amount := finiteHistorySample(row.EnergyAdded); amount != nil && *amount >= 0 {
				energy = *amount
			}
		}
		var firstSOC, lastSOC, firstSOCAt, lastSOCAt any
		if row.EndAt != nil {
			startLevel, startObserved := parkedChargeBoundarySOC(row.ChargePoints, row.StartAt)
			endLevel, endObserved := parkedChargeBoundarySOC(row.ChargePoints, *row.EndAt)
			firstSOC, lastSOC = nullableInt(startLevel), nullableInt(endLevel)
			if startObserved != nil {
				firstSOCAt = startObserved.UTC().Format(time.RFC3339Nano)
			}
			if endObserved != nil {
				lastSOCAt = endObserved.UTC().Format(time.RFC3339Nano)
			}
		}
		result = append(result, map[string]any{
			"charge_id":             row.PublicID,
			"start_date":            row.StartAt.UTC().Format(time.RFC3339Nano),
			"end_date":              endDate,
			"energy_added_kwh":      energy,
			"start_battery_level":   firstSOC,
			"end_battery_level":     lastSOC,
			"start_soc_observed_at": firstSOCAt,
			"end_soc_observed_at":   lastSOCAt,
			"source":                row.Source,
			"quality_state":         row.QualityState,
		})
	}
	return result, complete
}

func addParkedCharges(data map[string]any, older, newer telemetrySession, rows []telemetrySession) {
	charges, complete := parkedLinkedCharges(older, newer, rows)
	data["linked_charges"] = charges
	data["charge_sessions_complete"] = complete
	if len(charges) != 0 {
		data["linked_charge"] = charges[0]
	}
}

func parkedHistoryBoundaryData(older, newer parkedHistoryBound) map[string]any {
	a, b := older.Session, newer.Session
	if a.PublicID == b.PublicID || a.EndAt == nil || b.EndAt == nil || !a.StartAt.Before(b.StartAt) ||
		a.EndAt.Before(a.StartAt) || b.EndAt.Before(b.StartAt) || !a.EndAt.Before(b.StartAt) ||
		a.Source != "teslamate_archive" || b.Source != a.Source || a.QualityState != "observed" || b.QualityState != "observed" ||
		a.SourceInstanceID == "" || a.SourceVehicleID == "" || a.SourceInstanceID != b.SourceInstanceID || a.SourceVehicleID != b.SourceVehicleID {
		return nil
	}
	for _, session := range []telemetrySession{a, b} {
		distance := odometerDistance(session.OdometerStart, session.OdometerEnd)
		if finiteHistorySample(distance) == nil || *distance < 0.5 {
			return nil
		}
	}
	var delta any
	if older.EndSOC != nil && newer.StartSOC != nil {
		delta = *older.EndSOC - *newer.StartSOC
	}
	return map[string]any{
		"older_drive_id": a.PublicID, "newer_drive_id": b.PublicID,
		"start_date": a.EndAt.UTC().Format(time.RFC3339Nano), "end_date": b.StartAt.UTC().Format(time.RFC3339Nano),
		"address": a.EndAddress, "start_battery_level": nullableInt(older.EndSOC), "end_battery_level": nullableInt(newer.StartSOC),
		"battery_delta": delta, "energy_kwh": nil, "average_power_kw": nil, "peak_power_kw": nil,
		"inside_temp_average": nil, "outside_temp_average": nil, "linked_charge": nil,
		"linked_charges": []map[string]any{}, "charge_sessions_complete": false,
		"sample_count": 0, "coverage_seconds": 0, "coverage_ratio": 0.0,
		"source": "teslamate_archive", "evidence": "adjacent_observed_drive_bounds",
	}
}

func nullableInt(value *int) any {
	if value == nil {
		return nil
	}
	return *value
}

func interveningParkedDrive(row, older, newer telemetrySession) bool {
	if row.Kind != "drive" || row.ID == older.ID || row.ID == newer.ID {
		return false
	}
	return row.StartAt.Before(*newer.EndAt) && (row.EndAt == nil || row.EndAt.After(older.StartAt))
}

// Only boundary scalars are returned. A rejected middle drive is never omitted
// merely because it is short, quarantined, open, or from a different source.
func (s *telemetryService) parkedHistoryBounds(ctx context.Context, user string, car, olderID, newerID int) (map[string]any, error) {
	ctx, cancel := boundedHistoryContext(ctx)
	defer cancel()
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if s == nil || olderID <= 0 || newerID <= 0 || olderID == newerID {
		return nil, nil
	}
	if s.memory != nil {
		s.memory.mu.Lock()
		defer s.memory.mu.Unlock()
		owned := false
		for _, refs := range s.memory.vehicles {
			for _, ref := range refs {
				if ref.UserID == user && ref.VehicleID == car {
					owned = true
				}
			}
		}
		if !owned {
			return nil, nil
		}
		key := telemetryKey{UserID: user, VehicleID: car}
		var older, newer parkedHistoryBound
		count := 0
		for _, row := range s.memory.completed[key] {
			if row.Kind != "drive" {
				continue
			}
			if row.PublicID == olderID || row.PublicID == newerID {
				count++
				start, end := driveBatteryBounds(row)
				bound := parkedHistoryBound{Session: row, StartSOC: start, EndSOC: end}
				if row.PublicID == olderID {
					older = bound
				} else {
					newer = bound
				}
			}
		}
		if count != 2 {
			return nil, nil
		}
		data := parkedHistoryBoundaryData(older, newer)
		if data == nil {
			return nil, nil
		}
		for _, row := range s.memory.completed[key] {
			if interveningParkedDrive(row, older.Session, newer.Session) {
				return nil, nil
			}
		}
		if machine := s.memory.machines[key]; machine != nil && machine.drive != nil && interveningParkedDrive(*machine.drive, older.Session, newer.Session) {
			return nil, nil
		}
		var charges []telemetrySession
		for _, row := range s.memory.completed[key] {
			if row.Kind == "charge" {
				charges = append(charges, row)
			}
		}
		if machine := s.memory.machines[key]; machine != nil && machine.charge != nil {
			charges = append(charges, *machine.charge)
		}
		addParkedCharges(data, older.Session, newer.Session, charges)
		return data, ctx.Err()
	}
	if s.store == nil || s.store.pool == nil {
		return nil, errors.New("history_store_unavailable")
	}
	tx, err := s.store.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.RepeatableRead, AccessMode: pgx.ReadOnly})
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	var owned bool
	if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM jourvolt_vehicles WHERE user_id=$1 AND id=$2)`, user, car).Scan(&owned); err != nil {
		return nil, err
	}
	if !owned {
		return nil, nil
	}
	rows, err := tx.Query(ctx, `
SELECT s.id,s.public_id,s.started_at,s.ended_at,s.odometer_start,s.odometer_end,
 s.source,s.quality_state,COALESCE(s.source_instance_id,''),COALESCE(s.source_vehicle_id,''),s.end_address,
 (s.route_json->((soc.first_position-1)::integer)->>'battery_level')::integer,
 (s.route_json->((soc.last_position-1)::integer)->>'battery_level')::integer
FROM jourvolt_telemetry_sessions s
LEFT JOIN LATERAL (
 SELECT MIN(ordinal) FILTER(WHERE level BETWEEN 0 AND 100) AS first_position,
        MAX(ordinal) FILTER(WHERE level BETWEEN 0 AND 100) AS last_position
 FROM (SELECT ordinal,CASE WHEN jsonb_typeof(value->'battery_level')='number'
     AND (value->'battery_level')::text ~ '^[0-9]{1,3}$' THEN (value->>'battery_level')::integer END AS level
   FROM jsonb_array_elements(s.route_json) WITH ORDINALITY AS point(value,ordinal)) normalized
) soc ON true
WHERE s.user_id=$1 AND s.vehicle_id=$2 AND s.kind='drive' AND s.public_id IN ($3,$4)
LIMIT 3`, user, car, olderID, newerID)
	if err != nil {
		return nil, err
	}
	var older, newer parkedHistoryBound
	count := 0
	for rows.Next() {
		var bound parkedHistoryBound
		r := &bound.Session
		if err := rows.Scan(&r.ID, &r.PublicID, &r.StartAt, &r.EndAt, &r.OdometerStart, &r.OdometerEnd, &r.Source, &r.QualityState, &r.SourceInstanceID, &r.SourceVehicleID, &r.EndAddress, &bound.StartSOC, &bound.EndSOC); err != nil {
			rows.Close()
			return nil, err
		}
		count++
		if r.PublicID == olderID {
			older = bound
		} else {
			newer = bound
		}
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return nil, err
	}
	if count != 2 {
		return nil, nil
	}
	data := parkedHistoryBoundaryData(older, newer)
	if data == nil {
		return nil, nil
	}
	var intervening bool
	err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM jourvolt_telemetry_sessions
 WHERE user_id=$1 AND vehicle_id=$2 AND kind='drive' AND id NOT IN ($3,$4)
 AND started_at < $6 AND (ended_at IS NULL OR ended_at > $5))`, user, car, older.Session.ID, newer.Session.ID, older.Session.StartAt, *newer.Session.EndAt).Scan(&intervening)
	if err != nil {
		return nil, err
	}
	if intervening {
		return nil, nil
	}
	chargeRows, err := tx.Query(ctx, `
SELECT public_id,started_at,ended_at,energy_added,charge_points_json,
       source,quality_state,COALESCE(source_instance_id,''),COALESCE(source_vehicle_id,'')
FROM jourvolt_telemetry_sessions
WHERE user_id=$1 AND vehicle_id=$2 AND kind='charge'
  AND started_at<$4 AND (ended_at IS NULL OR ended_at>$3)
ORDER BY started_at,public_id
LIMIT $5`, user, car, *older.Session.EndAt, newer.Session.StartAt, maxParkedChargeSessions+1)
	if err != nil {
		return nil, err
	}
	var charges []telemetrySession
	for chargeRows.Next() {
		var charge telemetrySession
		var points []byte
		if err := chargeRows.Scan(&charge.PublicID, &charge.StartAt, &charge.EndAt,
			&charge.EnergyAdded, &points, &charge.Source, &charge.QualityState,
			&charge.SourceInstanceID, &charge.SourceVehicleID); err != nil {
			chargeRows.Close()
			return nil, err
		}
		charge.Kind = "charge"
		if err := json.Unmarshal(points, &charge.ChargePoints); err != nil {
			chargeRows.Close()
			return nil, err
		}
		charges = append(charges, charge)
	}
	err = chargeRows.Err()
	chargeRows.Close()
	if err != nil {
		return nil, err
	}
	addParkedCharges(data, older.Session, newer.Session, charges)
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return data, nil
}

func (a *app) parkedHistoryResource(w http.ResponseWriter, r *http.Request, user string, car int, parts []string) {
	if r.Method != http.MethodGet || len(parts) != 4 {
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	older, err1 := strconv.Atoi(parts[2])
	newer, err2 := strconv.Atoi(parts[3])
	if err1 != nil || err2 != nil || older <= 0 || newer <= 0 || older == newer {
		a.json(w, http.StatusBadRequest, map[string]string{"error": "invalid_drive_pair"})
		return
	}
	data, err := a.telemetry.parkedHistoryBounds(r.Context(), user, car, older, newer)
	if err != nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "history_unavailable"})
		return
	}
	if data == nil {
		a.json(w, http.StatusOK, map[string]any{"data": nil, "error": "parked_history_bounds_unavailable"})
		return
	}
	a.json(w, http.StatusOK, map[string]any{"data": data})
}
