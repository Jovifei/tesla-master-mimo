package main

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"
)

type parkedHistoryBound struct {
	Session          telemetrySession
	StartSOC, EndSOC *int
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
		"start_date": a.EndAt.UTC().Format(time.RFC3339), "end_date": b.StartAt.UTC().Format(time.RFC3339),
		"address": a.EndAddress, "start_battery_level": nullableInt(older.EndSOC), "end_battery_level": nullableInt(newer.StartSOC),
		"battery_delta": delta, "energy_kwh": nil, "average_power_kw": nil, "peak_power_kw": nil,
		"inside_temp_average": nil, "outside_temp_average": nil, "linked_charge": nil,
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
