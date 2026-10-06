package main

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

const parkedIntervalSource = "drive_history_interval"

// Only two scalar session headers are returned. The blocker considers ALL
// drives, including hidden, quarantined, cross-source and unfinished records.
// A single statement keeps pair validation and adjacency on one DB snapshot.
// No route/charge JSON, summary rebuild, write, or provider request is involved.
// Keep each source scope ordered by its existing partial index. OFFSET 0 is
// an optimizer boundary, not pagination: without it, EXISTS can choose a
// low-startup-cost scan of every tenant when no intervening drive exists.
// Every relevant scoped header remains eligible; no LIMIT hides a blocker.
const parkedIntervalSQL = `SELECT
    older.id, older.public_id, older.started_at, older.ended_at,
    older.source, older.quality_state, COALESCE(older.source_instance_id,''), COALESCE(older.source_vehicle_id,''), older.end_address,
    newer.id, newer.public_id, newer.started_at, newer.ended_at,
    newer.source, newer.quality_state, COALESCE(newer.source_instance_id,''), COALESCE(newer.source_vehicle_id,''), newer.start_address,
    (EXISTS (SELECT 1 FROM (
        SELECT public_id, started_at, ended_at FROM jourvolt_telemetry_sessions
        WHERE user_id=$1 AND vehicle_id=$2 AND kind='drive' AND source <> 'teslamate_archive'
          AND started_at <= newer.started_at
        ORDER BY started_at OFFSET 0
        ) blocker
        WHERE blocker.public_id NOT IN ($3,$4)
          AND (blocker.started_at >= older.started_at OR blocker.ended_at IS NULL OR blocker.ended_at > older.started_at))
     OR EXISTS (SELECT 1 FROM (
        SELECT public_id, started_at, ended_at FROM jourvolt_telemetry_sessions
        WHERE user_id=$1 AND vehicle_id=$2 AND kind='drive' AND source = 'teslamate_archive'
          AND started_at <= newer.started_at
        ORDER BY source_instance_id, source_vehicle_id, source_record_id OFFSET 0
        ) blocker
        WHERE blocker.public_id NOT IN ($3,$4)
          AND (blocker.started_at >= older.started_at OR blocker.ended_at IS NULL OR blocker.ended_at > older.started_at)))
    FROM jourvolt_telemetry_sessions older
    JOIN jourvolt_telemetry_sessions newer ON newer.public_id=$4
    WHERE older.public_id=$3
      AND older.user_id=$1 AND newer.user_id=$1
      AND older.vehicle_id=$2 AND newer.vehicle_id=$2
      AND older.kind='drive' AND newer.kind='drive'`

func (a *app) parkedDetail(w http.ResponseWriter, r *http.Request, userID string, carID int, path string, parts []string) {
	validID := func(raw string) (int, bool) {
		id, err := strconv.Atoi(raw)
		return id, err == nil && id > 0 && id <= 1<<31-1 && strconv.Itoa(id) == raw
	}
	if len(parts) != 4 || r.Method != http.MethodGet || carID <= 0 || carID > 1<<31-1 {
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	olderID, olderOK := validID(parts[2])
	newerID, newerOK := validID(parts[3])
	canonical := "/api/matelink/v1/cars/" + strconv.Itoa(carID) + "/parked/" + strconv.Itoa(olderID) + "/" + strconv.Itoa(newerID)
	if !olderOK || !newerOK || olderID == newerID || path != canonical || r.URL.EscapedPath() != canonical {
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	data, found, err := a.telemetry.parkedInterval(r.Context(), userID, carID, olderID, newerID)
	if err != nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "parked_history_unavailable"})
		return
	}
	if !found {
		a.json(w, http.StatusNotFound, map[string]string{"error": "parked_interval_not_found"})
		return
	}
	a.json(w, http.StatusOK, map[string]any{"data": data})
}

func (s *telemetryService) parkedInterval(ctx context.Context, userID string, carID, olderID, newerID int) (map[string]any, bool, error) {
	ctx, cancel := historyReadContext(ctx)
	defer cancel()
	if err := ctx.Err(); err != nil {
		return nil, false, err
	}
	if s == nil {
		return nil, false, errors.New("history_store_unavailable")
	}
	if s.memory != nil {
		return s.memory.parkedInterval(ctx, userID, carID, olderID, newerID)
	}
	if s.store == nil || s.store.pool == nil {
		return nil, false, errors.New("history_store_unavailable")
	}
	older, newer := telemetrySession{Kind: "drive"}, telemetrySession{Kind: "drive"}
	var blocked bool
	err := s.store.pool.QueryRow(ctx, parkedIntervalSQL, userID, carID, olderID, newerID).Scan(
		&older.ID, &older.PublicID, &older.StartAt, &older.EndAt,
		&older.Source, &older.QualityState, &older.SourceInstanceID, &older.SourceVehicleID, &older.EndAddress,
		&newer.ID, &newer.PublicID, &newer.StartAt, &newer.EndAt,
		&newer.Source, &newer.QualityState, &newer.SourceInstanceID, &newer.SourceVehicleID, &newer.StartAddress,
		&blocked,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, false, nil
	}
	if err != nil {
		return nil, false, err
	}
	return parkedIntervalData(older, newer, blocked)
}

// Caller-visible memory path follows the same scalar-only rules as PostgreSQL.
// Do not use sessions()/historyDetail(), which clone/decode complete routes.
func (s *telemetryMemoryStore) parkedInterval(ctx context.Context, userID string, carID, olderID, newerID int) (map[string]any, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	key := telemetryKey{UserID: userID, VehicleID: carID}
	rows := s.completed[key]
	var older, newer telemetrySession
	for i := range rows {
		if err := ctx.Err(); err != nil {
			return nil, false, err
		}
		if rows[i].PublicID == olderID {
			older = rows[i]
		}
		if rows[i].PublicID == newerID {
			newer = rows[i]
		}
	}
	if !validParkedPair(older, newer) {
		return nil, false, nil
	}
	for i := range rows {
		if err := ctx.Err(); err != nil {
			return nil, false, err
		}
		if blocksParkedInterval(rows[i], older, newer) {
			return nil, false, nil
		}
	}
	if machine := s.machines[key]; machine != nil && machine.drive != nil && blocksParkedInterval(*machine.drive, older, newer) {
		return nil, false, nil
	}
	return parkedIntervalData(older, newer, false)
}

func validParkedPair(older, newer telemetrySession) bool {
	if older.Kind != "drive" || newer.Kind != "drive" || older.PublicID <= 0 || newer.PublicID <= 0 || older.PublicID == newer.PublicID ||
		older.EndAt == nil || newer.EndAt == nil || older.StartAt.IsZero() || newer.StartAt.IsZero() ||
		!older.EndAt.After(older.StartAt) || !newer.EndAt.After(newer.StartAt) || !newer.StartAt.After(*older.EndAt) ||
		older.QualityState == "quarantined" || newer.QualityState == "quarantined" {
		return false
	}
	// Different source streams may be incomplete or duplicate each other.
	// Never bridge those streams into an asserted parked interval.
	if older.Source != newer.Source ||
		older.SourceInstanceID != newer.SourceInstanceID || older.SourceVehicleID != newer.SourceVehicleID {
		return false
	}
	switch older.Source {
	case "teslamate_archive":
		if strings.TrimSpace(older.SourceInstanceID) == "" || strings.TrimSpace(older.SourceVehicleID) == "" {
			return false
		}
	case "telemetry_mqtt", "fleet_api", "local_import", "local_history":
		// These are canonical non-archive sources, not normalized aliases.
	default:
		return false
	}
	return true
}

func blocksParkedInterval(row, older, newer telemetrySession) bool {
	if row.Kind != "drive" || row.PublicID == older.PublicID || row.PublicID == newer.PublicID || row.StartAt.After(newer.StartAt) {
		return false
	}
	// Include nested/duplicate trips within the older trip, not just movement
	// in the open gap. Otherwise the selected endpoint pair is ambiguous.
	return !row.StartAt.Before(older.StartAt) || row.EndAt == nil || row.EndAt.After(older.StartAt)
}

func parkedIntervalData(older, newer telemetrySession, blocked bool) (map[string]any, bool, error) {
	if blocked || !validParkedPair(older, newer) {
		return nil, false, nil
	}
	return map[string]any{
		"older_drive_id": older.PublicID, "newer_drive_id": newer.PublicID,
		"start_date": older.EndAt.UTC().Format(time.RFC3339Nano), "end_date": newer.StartAt.UTC().Format(time.RFC3339Nano),
		"start_address": older.EndAddress, "end_address": newer.StartAddress,
		// Endpoints do not establish a stationary location or consumption.
		"address": nil, "source": parkedIntervalSource,
		"start_battery_level": nil, "end_battery_level": nil, "battery_delta": nil,
		"energy_kwh": nil, "average_power_kw": nil, "peak_power_kw": nil,
		"inside_temp_average": nil, "outside_temp_average": nil,
		"sample_count": 0, "coverage_seconds": 0, "coverage_ratio": 0.0, "linked_charge": nil,
	}, true, nil
}
