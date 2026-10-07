package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5"
)

// This additive shadow stores the native session's derived detail samples, not
// every raw telemetry observation. Legacy JSON remains authoritative. There is
// deliberately no backfill, read cutover, retention change, or archive ACK here.
const nativeShadowSchema = `
CREATE UNIQUE INDEX IF NOT EXISTS jourvolt_telemetry_session_scope_idx
    ON jourvolt_telemetry_sessions(id,user_id,vehicle_id);
CREATE TABLE IF NOT EXISTS jourvolt_telemetry_detail_shadow (
    session_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    vehicle_id INTEGER NOT NULL,
    stream TEXT NOT NULL CHECK (stream IN ('route','charge')),
    schema_version INTEGER NOT NULL DEFAULT 1 CHECK (schema_version=1),
    encoding TEXT NOT NULL DEFAULT 'native-session-json-v1' CHECK (encoding='native-session-json-v1'),
    compression TEXT NOT NULL DEFAULT 'none' CHECK (compression='none'),
    prefix_count INTEGER NOT NULL CHECK (prefix_count>=0),
    sample_count INTEGER NOT NULL CHECK (sample_count>=prefix_count),
    chunk_count INTEGER NOT NULL DEFAULT 0 CHECK (chunk_count>=0),
    started_with_session BOOLEAN NOT NULL,
    source_revision BIGINT NOT NULL,
    stale BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (session_id,user_id,vehicle_id),
    FOREIGN KEY (session_id,user_id,vehicle_id)
        REFERENCES jourvolt_telemetry_sessions(id,user_id,vehicle_id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS jourvolt_telemetry_detail_chunks (
    session_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    vehicle_id INTEGER NOT NULL,
    chunk_index INTEGER NOT NULL CHECK (chunk_index>=0),
    start_index INTEGER NOT NULL CHECK (start_index>=0),
    sample_count INTEGER NOT NULL CHECK (sample_count BETWEEN 1 AND 256),
    payload BYTEA NOT NULL CHECK (octet_length(payload) BETWEEN 2 AND 65536),
    payload_sha256 TEXT NOT NULL CHECK (payload_sha256=encode(sha256(payload),'hex')),
    CHECK (sample_count=jsonb_array_length(convert_from(payload,'UTF8')::jsonb)),
    PRIMARY KEY (session_id,user_id,vehicle_id,chunk_index),
    UNIQUE (session_id,user_id,vehicle_id,start_index),
    FOREIGN KEY (session_id,user_id,vehicle_id)
        REFERENCES jourvolt_telemetry_detail_shadow(session_id,user_id,vehicle_id) ON DELETE CASCADE
);
`

const maxNativeShadowChunkSamples = 256
const maxNativeShadowChunkBytes = 64 * 1024

// Emit one bounded JSON array at a time. No whole-delta JSON buffer is created.
// Callers must run emit within their transaction so an error cannot acknowledge
// a partial append. Native ingestion currently contributes at most one sample
// per stream/event; the independent caps also protect future batch callers.
func encodeNativeShadowChunks[T any](points []T, emit func([]byte, int) error) error {
	if len(points) == 0 {
		return nil
	}
	payload := make([]byte, 1, 1024)
	payload[0] = '['
	count := 0
	flush := func() error {
		if count == 0 {
			return nil
		}
		if err := emit(append(payload, ']'), count); err != nil {
			return err
		}
		payload = payload[:1]
		count = 0
		return nil
	}
	for _, point := range points {
		encoded, err := json.Marshal(point)
		if err != nil {
			return fmt.Errorf("encode native detail shadow sample: %w", err)
		}
		if len(encoded)+2 > maxNativeShadowChunkBytes {
			return fmt.Errorf("native detail shadow sample exceeds %d bytes", maxNativeShadowChunkBytes)
		}
		separator := 0
		if count > 0 {
			separator = 1
		}
		if count == maxNativeShadowChunkSamples || len(payload)+separator+len(encoded)+1 > maxNativeShadowChunkBytes {
			if err := flush(); err != nil {
				return err
			}
		}
		if count > 0 {
			payload = append(payload, ',')
		}
		payload = append(payload, encoded...)
		count++
	}
	return flush()
}

// The caller holds the session lock and has written its new legacy JSON in this
// same transaction. A revision mismatch never silently certifies an old-writer
// rewrite: retain existing chunks, mark stale, and keep normal legacy ingestion.
func appendNativeSessionShadow(ctx context.Context, tx pgx.Tx, ref telemetryVehicleRef, before *telemetrySession, after telemetrySession, beforeRevision int64, fresh bool) error {
	stream, oldCount, newCount := "route", 0, len(after.Route)
	if after.Kind == "charge" {
		stream, newCount = "charge", len(after.ChargePoints)
	}
	if before != nil {
		if before.ID != after.ID || before.Kind != after.Kind {
			return fmt.Errorf("native detail shadow identity changed")
		}
		oldCount = len(before.Route)
		if stream == "charge" {
			oldCount = len(before.ChargePoints)
		}
	}
	if newCount < oldCount || (fresh && before != nil) {
		return fmt.Errorf("native detail shadow requires append-only samples")
	}
	var revision int64
	var storedCount int
	err := tx.QueryRow(ctx, `SELECT history_summary_revision,
        CASE WHEN kind='drive' THEN route_point_count ELSE charge_point_count END
        FROM jourvolt_telemetry_sessions
        WHERE id=$1 AND user_id=$2 AND vehicle_id=$3 AND kind=$4
          AND source='telemetry_mqtt' AND history_summary_version=1`, after.ID, ref.UserID, ref.VehicleID, after.Kind).Scan(&revision, &storedCount)
	if err != nil {
		return err
	}
	if storedCount != newCount {
		return fmt.Errorf("native detail shadow sample count differs from legacy write")
	}
	var shadowRevision int64
	var shadowCount, chunks int
	var stale bool
	var storedStream, encoding, compression string
	var version int
	err = tx.QueryRow(ctx, `SELECT source_revision,sample_count,chunk_count,stale,stream,schema_version,encoding,compression
        FROM jourvolt_telemetry_detail_shadow WHERE session_id=$1 AND user_id=$2 AND vehicle_id=$3 FOR UPDATE`, after.ID, ref.UserID, ref.VehicleID).Scan(&shadowRevision, &shadowCount, &chunks, &stale, &storedStream, &version, &encoding, &compression)
	if errors.Is(err, pgx.ErrNoRows) {
		_, err = tx.Exec(ctx, `INSERT INTO jourvolt_telemetry_detail_shadow
            (session_id,user_id,vehicle_id,stream,prefix_count,sample_count,started_with_session,source_revision)
            VALUES($1,$2,$3,$4,$5,$5,$6,$7)`, after.ID, ref.UserID, ref.VehicleID, stream, oldCount, fresh, beforeRevision)
		if err != nil {
			return err
		}
	} else if err != nil {
		return err
	} else if stale || shadowRevision != beforeRevision || shadowCount != oldCount || storedStream != stream || version != 1 || encoding != "native-session-json-v1" || compression != "none" {
		_, err = tx.Exec(ctx, `UPDATE jourvolt_telemetry_detail_shadow SET stale=true WHERE session_id=$1 AND user_id=$2 AND vehicle_id=$3`, after.ID, ref.UserID, ref.VehicleID)
		return err
	}
	next := oldCount
	emit := func(payload []byte, count int) error {
		digest := sha256.Sum256(payload)
		_, err := tx.Exec(ctx, `INSERT INTO jourvolt_telemetry_detail_chunks
            (session_id,user_id,vehicle_id,chunk_index,start_index,sample_count,payload,payload_sha256)
            VALUES($1,$2,$3,$4,$5,$6,$7,$8)`, after.ID, ref.UserID, ref.VehicleID, chunks, next, count, payload, hex.EncodeToString(digest[:]))
		if err == nil {
			chunks++
			next += count
		}
		return err
	}
	if stream == "route" {
		err = encodeNativeShadowChunks(after.Route[oldCount:], emit)
	} else {
		err = encodeNativeShadowChunks(after.ChargePoints[oldCount:], emit)
	}
	if err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `UPDATE jourvolt_telemetry_detail_shadow
        SET sample_count=$1,chunk_count=$2,source_revision=$3
        WHERE session_id=$4 AND user_id=$5 AND vehicle_id=$6`, next, chunks, revision, after.ID, ref.UserID, ref.VehicleID)
	return err
}
