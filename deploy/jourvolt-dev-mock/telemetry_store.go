package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const telemetrySchema = `
CREATE SEQUENCE IF NOT EXISTS jourvolt_telemetry_session_public_id_seq AS integer START WITH 1;
CREATE TABLE IF NOT EXISTS jourvolt_telemetry_vehicle_keys (
    user_id TEXT NOT NULL REFERENCES jourvolt_users(id) ON DELETE CASCADE,
    vehicle_id INTEGER NOT NULL REFERENCES jourvolt_vehicles(id) ON DELETE CASCADE,
    vin_hash TEXT NOT NULL,
    PRIMARY KEY (user_id, vehicle_id),
    UNIQUE (vin_hash, user_id, vehicle_id)
);
CREATE INDEX IF NOT EXISTS jourvolt_telemetry_vehicle_keys_vin_hash_idx ON jourvolt_telemetry_vehicle_keys(vin_hash);
CREATE TABLE IF NOT EXISTS jourvolt_telemetry_latest (
    user_id TEXT NOT NULL REFERENCES jourvolt_users(id) ON DELETE CASCADE,
    vehicle_id INTEGER NOT NULL REFERENCES jourvolt_vehicles(id) ON DELETE CASCADE,
    field_name TEXT NOT NULL,
    value_json JSONB NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    source TEXT NOT NULL,
    value_hash TEXT NOT NULL,
    receive_sequence BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, vehicle_id, field_name)
);
CREATE TABLE IF NOT EXISTS jourvolt_telemetry_event_buffer (
    event_id TEXT NOT NULL,
    user_id TEXT NOT NULL REFERENCES jourvolt_users(id) ON DELETE CASCADE,
    vehicle_id INTEGER NOT NULL REFERENCES jourvolt_vehicles(id) ON DELETE CASCADE,
    field_name TEXT NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    receive_sequence BIGINT NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (event_id, user_id, vehicle_id)
);
CREATE INDEX IF NOT EXISTS jourvolt_telemetry_event_buffer_expiry_idx ON jourvolt_telemetry_event_buffer(expires_at);
CREATE TABLE IF NOT EXISTS jourvolt_telemetry_route_points (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES jourvolt_users(id) ON DELETE CASCADE,
    vehicle_id INTEGER NOT NULL REFERENCES jourvolt_vehicles(id) ON DELETE CASCADE,
    observed_at TIMESTAMPTZ NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    speed DOUBLE PRECISION,
    power DOUBLE PRECISION,
    heading DOUBLE PRECISION,
    UNIQUE (user_id, vehicle_id, observed_at)
);
CREATE INDEX IF NOT EXISTS jourvolt_telemetry_route_points_lookup_idx ON jourvolt_telemetry_route_points(user_id, vehicle_id, observed_at);
CREATE TABLE IF NOT EXISTS jourvolt_telemetry_pairing (
    user_id TEXT NOT NULL REFERENCES jourvolt_users(id) ON DELETE CASCADE,
    vehicle_id INTEGER NOT NULL REFERENCES jourvolt_vehicles(id) ON DELETE CASCADE,
    status TEXT NOT NULL,
    error_class TEXT NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, vehicle_id)
);
CREATE TABLE IF NOT EXISTS jourvolt_telemetry_sessions (
    id TEXT PRIMARY KEY,
	public_id INTEGER NOT NULL DEFAULT nextval('jourvolt_telemetry_session_public_id_seq') UNIQUE,
    user_id TEXT NOT NULL REFERENCES jourvolt_users(id) ON DELETE CASCADE,
    vehicle_id INTEGER NOT NULL REFERENCES jourvolt_vehicles(id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('drive', 'charge')),
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    stop_candidate_at TIMESTAMPTZ,
    odometer_start DOUBLE PRECISION,
    odometer_end DOUBLE PRECISION,
    energy_added DOUBLE PRECISION,
	completion_key TEXT UNIQUE,
    route_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    source TEXT NOT NULL DEFAULT 'telemetry_mqtt',
    UNIQUE (user_id, vehicle_id, kind, started_at)
);
CREATE UNIQUE INDEX IF NOT EXISTS jourvolt_telemetry_open_session_idx ON jourvolt_telemetry_sessions(user_id, vehicle_id, kind) WHERE ended_at IS NULL;
ALTER TABLE jourvolt_telemetry_event_buffer ADD COLUMN IF NOT EXISTS receive_sequence BIGINT NOT NULL DEFAULT 0;
ALTER TABLE jourvolt_telemetry_latest ADD COLUMN IF NOT EXISTS receive_sequence BIGINT NOT NULL DEFAULT 0;
ALTER TABLE jourvolt_telemetry_sessions ADD COLUMN IF NOT EXISTS completion_key TEXT;
ALTER TABLE jourvolt_telemetry_sessions ADD COLUMN IF NOT EXISTS public_id INTEGER;
ALTER TABLE jourvolt_telemetry_sessions ALTER COLUMN public_id SET DEFAULT nextval('jourvolt_telemetry_session_public_id_seq');
UPDATE jourvolt_telemetry_sessions SET public_id=nextval('jourvolt_telemetry_session_public_id_seq') WHERE public_id IS NULL;
ALTER TABLE jourvolt_telemetry_sessions ALTER COLUMN public_id SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS jourvolt_telemetry_sessions_completion_key_idx ON jourvolt_telemetry_sessions(completion_key) WHERE completion_key IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS jourvolt_telemetry_sessions_public_id_idx ON jourvolt_telemetry_sessions(public_id);
`

func ensureTelemetrySchema(ctx context.Context, pool *pgxpool.Pool) error {
	if pool == nil {
		return errors.New("telemetry schema requires postgres")
	}
	_, err := pool.Exec(ctx, telemetrySchema)
	return err
}

func (s *telemetryService) ingestPostgres(ctx context.Context, record telemetryRecord) (int, error) {
	if s.store == nil || s.store.pool == nil {
		return 0, nil
	}
	rows, err := s.store.pool.Query(ctx, `SELECT user_id, vehicle_id FROM jourvolt_telemetry_vehicle_keys WHERE vin_hash=$1`, record.VINHash)
	if err != nil {
		return 0, err
	}
	refs := make([]telemetryVehicleRef, 0)
	for rows.Next() {
		var ref telemetryVehicleRef
		if err := rows.Scan(&ref.UserID, &ref.VehicleID); err != nil {
			rows.Close()
			return 0, err
		}
		refs = append(refs, ref)
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return 0, err
	}
	rows.Close()
	if len(refs) == 0 {
		return 0, nil
	}
	tx, err := s.store.pool.Begin(ctx)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback(ctx)
	accepted := 0
	for _, ref := range refs {
		valueHash := hashTelemetryValue(record.Value)
		var previousHash string
		previousErr := tx.QueryRow(ctx, `SELECT value_hash FROM jourvolt_telemetry_latest WHERE user_id=$1 AND vehicle_id=$2 AND field_name=$3 FOR UPDATE`, ref.UserID, ref.VehicleID, record.FieldName).Scan(&previousHash)
		if previousErr != nil && !errors.Is(previousErr, pgx.ErrNoRows) {
			return 0, previousErr
		}
		if previousErr == nil && previousHash == valueHash {
			continue
		}
		var inserted bool
		err = tx.QueryRow(ctx, `
INSERT INTO jourvolt_telemetry_event_buffer(event_id, user_id, vehicle_id, field_name, observed_at, receive_sequence, expires_at)
VALUES ($1, $2, $3, $4, $5::timestamptz, $6, $5::timestamptz + interval '24 hours')
ON CONFLICT (event_id, user_id, vehicle_id) DO NOTHING
RETURNING true`, record.EventID, ref.UserID, ref.VehicleID, record.FieldName, record.ObservedAt, record.ReceiveSequence).Scan(&inserted)
		if errors.Is(err, pgx.ErrNoRows) {
			continue
		}
		if err != nil {
			return 0, err
		}
		encoded, err := json.Marshal(record.Value)
		if err != nil {
			return 0, err
		}
		commandTag, err := tx.Exec(ctx, `
INSERT INTO jourvolt_telemetry_latest(user_id, vehicle_id, field_name, value_json, observed_at, source, value_hash, receive_sequence)
VALUES ($1, $2, $3, $4::jsonb, $5, $6, $7, $8)
ON CONFLICT (user_id, vehicle_id, field_name) DO UPDATE SET value_json=EXCLUDED.value_json, observed_at=EXCLUDED.observed_at, source=EXCLUDED.source, value_hash=EXCLUDED.value_hash, updated_at=now()
WHERE EXCLUDED.observed_at > jourvolt_telemetry_latest.observed_at`, ref.UserID, ref.VehicleID, record.FieldName, encoded, record.ObservedAt, record.Source, valueHash, record.ReceiveSequence)
		if err != nil {
			return 0, err
		}
		if commandTag.RowsAffected() == 0 {
			continue
		}
		accepted++
		if point, ok := routePointFromLocation(telemetrySessionEvent{Value: record.Value, ObservedAt: record.ObservedAt}); ok {
			if err := insertDownsampledRoutePoint(ctx, tx, ref, point); err != nil {
				return 0, err
			}
		}
		if err := applyPostgresSessionEvent(ctx, tx, ref, record, s.config.StopDebounce); err != nil {
			return 0, err
		}
	}
	if _, err := tx.Exec(ctx, `DELETE FROM jourvolt_telemetry_event_buffer WHERE expires_at < now()`); err != nil {
		return 0, err
	}
	if err := tx.Commit(ctx); err != nil {
		return 0, err
	}
	return accepted, nil
}

func insertDownsampledRoutePoint(ctx context.Context, tx pgx.Tx, ref telemetryVehicleRef, point telemetryRoutePoint) error {
	var last time.Time
	err := tx.QueryRow(ctx, `SELECT COALESCE(max(observed_at), 'epoch'::timestamptz) FROM jourvolt_telemetry_route_points WHERE user_id=$1 AND vehicle_id=$2`, ref.UserID, ref.VehicleID).Scan(&last)
	if err != nil {
		return err
	}
	if !last.Equal(time.Unix(0, 0).UTC()) && point.ObservedAt.Sub(last) < 10*time.Second {
		return nil
	}
	_, err = tx.Exec(ctx, `INSERT INTO jourvolt_telemetry_route_points(user_id, vehicle_id, observed_at, latitude, longitude) VALUES ($1,$2,$3,$4,$5) ON CONFLICT DO NOTHING`, ref.UserID, ref.VehicleID, point.ObservedAt, point.Latitude, point.Longitude)
	return err
}

func applyPostgresSessionEvent(ctx context.Context, tx pgx.Tx, ref telemetryVehicleRef, record telemetryRecord, debounce time.Duration) error {
	rows, err := tx.Query(ctx, `SELECT id, public_id, kind, started_at, ended_at, stop_candidate_at, odometer_start, odometer_end, energy_added, route_json FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2 AND ended_at IS NULL ORDER BY started_at FOR UPDATE`, ref.UserID, ref.VehicleID)
	if err != nil {
		return err
	}
	snapshot := telemetrySessionMachineSnapshot{}
	for rows.Next() {
		var id, kind string
		var publicID int
		var startedAt time.Time
		var endedAt, stopCandidate *time.Time
		var odometerStart, odometerEnd, energyAdded *float64
		var routeJSON []byte
		if err := rows.Scan(&id, &publicID, &kind, &startedAt, &endedAt, &stopCandidate, &odometerStart, &odometerEnd, &energyAdded, &routeJSON); err != nil {
			rows.Close()
			return err
		}
		open := &telemetrySession{ID: id, PublicID: publicID, Kind: kind, StartAt: startedAt, EndAt: endedAt, OdometerStart: odometerStart, OdometerEnd: odometerEnd, EnergyAdded: energyAdded}
		_ = json.Unmarshal(routeJSON, &open.Route)
		if kind == "drive" {
			snapshot.Drive = open
		} else if kind == "charge" {
			snapshot.Charge = open
		}
		if kind == "drive" {
			snapshot.StopCandidate = stopCandidate
		}
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return err
	}
	rows.Close()
	machine := newTelemetrySessionMachineFromSnapshot(debounce, snapshot)
	machine.apply(telemetrySessionEvent{FieldName: record.FieldName, Value: record.Value, ObservedAt: record.ObservedAt, EventID: record.EventID})
	for _, kind := range []string{"drive", "charge"} {
		var previous *telemetrySession
		if kind == "drive" {
			previous = snapshot.Drive
		} else {
			previous = snapshot.Charge
		}
		var open *telemetrySession
		if kind == "drive" {
			open = machine.drive
		} else {
			open = machine.charge
		}
		if previous != nil && open == nil {
			completed, ok := lastCompletedSession(machine.completedSessions(), kind)
			if !ok {
				continue
			}
			route, _ := json.Marshal(completed.Route)
			if _, err := tx.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET ended_at=$1, odometer_start=$2, odometer_end=$3, energy_added=$4, route_json=$5::jsonb, stop_candidate_at=NULL, completion_key=$6 WHERE id=$7 AND ended_at IS NULL`, completed.EndAt, completed.OdometerStart, completed.OdometerEnd, completed.EnergyAdded, route, completed.CompletionKey, completed.ID); err != nil {
				return err
			}
			continue
		}
		if open == nil {
			continue
		}
		route, _ := json.Marshal(open.Route)
		if previous == nil {
			if err := tx.QueryRow(ctx, `INSERT INTO jourvolt_telemetry_sessions(id, user_id, vehicle_id, kind, started_at, odometer_start, energy_added, route_json) VALUES ($1,$2,$3,$4,$5,$6,$7,$8::jsonb) ON CONFLICT (id) DO UPDATE SET id=EXCLUDED.id RETURNING public_id`, open.ID, ref.UserID, ref.VehicleID, open.Kind, open.StartAt, open.OdometerStart, open.EnergyAdded, route).Scan(&open.PublicID); err != nil {
				return err
			}
			continue
		}
		candidate := machine.stopCandidate
		if kind == "charge" {
			candidate = nil
		}
		if _, err := tx.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET stop_candidate_at=$1, odometer_start=$2, odometer_end=$3, energy_added=$4, route_json=$5::jsonb WHERE id=$6`, candidate, open.OdometerStart, open.OdometerEnd, open.EnergyAdded, route, open.ID); err != nil {
			return err
		}
	}
	return nil
}

func lastCompletedSession(sessions []telemetrySession, kind string) (telemetrySession, bool) {
	for index := len(sessions) - 1; index >= 0; index-- {
		if sessions[index].Kind == kind {
			return sessions[index], true
		}
	}
	return telemetrySession{}, false
}

func (s *telemetryService) latestPostgres(ctx context.Context, userID string, vehicleID int) (telemetrySnapshot, bool, error) {
	if s.store == nil || s.store.pool == nil {
		return telemetrySnapshot{}, false, nil
	}
	rows, err := s.store.pool.Query(ctx, `SELECT field_name, value_json, observed_at, source FROM jourvolt_telemetry_latest WHERE user_id=$1 AND vehicle_id=$2`, userID, vehicleID)
	if err != nil {
		return telemetrySnapshot{}, false, err
	}
	defer rows.Close()
	snapshot := telemetrySnapshot{Fields: map[string]any{}, FieldObservedAt: map[string]time.Time{}, Source: "telemetry_mqtt"}
	for rows.Next() {
		var field, source string
		var valueJSON []byte
		var observedAt time.Time
		if err := rows.Scan(&field, &valueJSON, &observedAt, &source); err != nil {
			return telemetrySnapshot{}, false, err
		}
		var value any
		if err := json.Unmarshal(valueJSON, &value); err != nil {
			return telemetrySnapshot{}, false, err
		}
		snapshot.Fields[field] = value
		snapshot.FieldObservedAt[field] = observedAt
		if observedAt.After(snapshot.ObservedAt) {
			snapshot.ObservedAt = observedAt
		}
		if source != "" {
			snapshot.Source = source
		}
	}
	if err := rows.Err(); err != nil {
		return telemetrySnapshot{}, false, err
	}
	return snapshot, len(snapshot.Fields) > 0, nil
}

func (s *telemetryService) historyPostgres(ctx context.Context, userID string, vehicleID int, kind string) ([]telemetrySession, time.Time, error) {
	if s.store == nil || s.store.pool == nil {
		return nil, time.Time{}, nil
	}
	rows, err := s.store.pool.Query(ctx, `SELECT id, public_id, started_at, ended_at, odometer_start, odometer_end, energy_added, route_json FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2 AND kind=$3 AND ended_at IS NOT NULL ORDER BY started_at DESC`, userID, vehicleID, kind)
	if err != nil {
		return nil, time.Time{}, err
	}
	defer rows.Close()
	result := make([]telemetrySession, 0)
	var startedAt time.Time
	for rows.Next() {
		var session telemetrySession
		var routeJSON []byte
		if err := rows.Scan(&session.ID, &session.PublicID, &session.StartAt, &session.EndAt, &session.OdometerStart, &session.OdometerEnd, &session.EnergyAdded, &routeJSON); err != nil {
			return nil, time.Time{}, err
		}
		_ = json.Unmarshal(routeJSON, &session.Route)
		result = append(result, session)
		if startedAt.IsZero() || session.StartAt.Before(startedAt) {
			startedAt = session.StartAt
		}
	}
	if err := rows.Err(); err != nil {
		return nil, time.Time{}, err
	}
	return result, startedAt, nil
}

func (s *telemetryService) openSessionPostgres(ctx context.Context, userID string, vehicleID int, kind string) (telemetrySession, bool, error) {
	if s.store == nil || s.store.pool == nil {
		return telemetrySession{}, false, nil
	}
	var session telemetrySession
	var routeJSON []byte
	err := s.store.pool.QueryRow(ctx, `SELECT id, public_id, started_at, odometer_start, odometer_end, energy_added, route_json FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2 AND kind=$3 AND ended_at IS NULL`, userID, vehicleID, kind).Scan(&session.ID, &session.PublicID, &session.StartAt, &session.OdometerStart, &session.OdometerEnd, &session.EnergyAdded, &routeJSON)
	if errors.Is(err, pgx.ErrNoRows) {
		return telemetrySession{}, false, nil
	}
	if err != nil {
		return telemetrySession{}, false, err
	}
	session.Kind = kind
	_ = json.Unmarshal(routeJSON, &session.Route)
	return session, true, nil
}

func (s *telemetryService) finalizeDuePostgres(ctx context.Context, now time.Time) (int, error) {
	if s.store == nil || s.store.pool == nil {
		return 0, nil
	}
	debounce := defaultDriveStopDebounce
	if s.config != nil && s.config.StopDebounce > 0 {
		debounce = s.config.StopDebounce
	}
	tx, err := s.store.pool.Begin(ctx)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback(ctx)
	rows, err := tx.Query(ctx, `SELECT id, public_id, started_at, stop_candidate_at FROM jourvolt_telemetry_sessions WHERE kind='drive' AND ended_at IS NULL AND stop_candidate_at IS NOT NULL FOR UPDATE`)
	if err != nil {
		return 0, err
	}
	defer rows.Close()
	completed := 0
	for rows.Next() {
		var session telemetrySession
		var candidate time.Time
		if err := rows.Scan(&session.ID, &session.PublicID, &session.StartAt, &candidate); err != nil {
			return 0, err
		}
		if now.Before(candidate.Add(debounce)) {
			continue
		}
		end := candidate.Add(debounce)
		session.Kind, session.EndAt = "drive", &end
		key := sessionCompletionKey(session)
		tag, err := tx.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET ended_at=$1, stop_candidate_at=NULL, completion_key=$2 WHERE id=$3 AND ended_at IS NULL`, end, key, session.ID)
		if err != nil {
			return 0, err
		}
		completed += int(tag.RowsAffected())
	}
	if err := rows.Err(); err != nil {
		return 0, err
	}
	if err := tx.Commit(ctx); err != nil {
		return 0, err
	}
	return completed, nil
}

func telemetryStoreError(operation string, err error) error {
	if err == nil {
		return nil
	}
	return fmt.Errorf("telemetry %s: %w", operation, err)
}
