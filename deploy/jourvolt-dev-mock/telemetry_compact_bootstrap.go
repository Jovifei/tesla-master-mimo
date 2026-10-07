package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

// This is the transaction-local entry gate for a future compact persistence
// adapter. It is deliberately unwired: it never enables a scope or writes state.
// The caller must retain the same transaction/locks through the activation write.
const compactBootstrapVersion = 1

type compactBootstrapExpectation struct {
	Scope           telemetryKey
	VINHash, Source string
	Version         int // Includes the fixed field-slot order in this version.
	Debounce        time.Duration
}

type compactBootstrapCandidate struct {
	State                  compactTelemetryState
	VINHash, Source        string
	PredecessorFingerprint string
}

// Reasons contain no private source payload or identifiers. These failures must
// be persistence/ineligibility errors, not acknowledged invalid input/duplicates.
type compactBootstrapError struct{ Reason string }

func (e *compactBootstrapError) Error() string { return "compact bootstrap: " + e.Reason }

// Verify the actual table/index capability, including key order, exact partial
// predicate, uniqueness, readiness and validity. A familiar name is insufficient.
// All catalog output is one boolean; even an unexpected expression is not sent
// to Go. pg_catalog qualification prevents a schema from replacing these names.
const compactBootstrapIndexSQL = `SELECT COALESCE((
    SELECT i.indisunique AND i.indisvalid AND i.indisready AND i.indislive AND i.indimmediate
      AND i.indnkeyatts=3 AND i.indnatts=3 AND i.indexprs IS NULL AND am.amname='btree'
      AND pg_catalog.pg_get_expr(i.indpred,i.indrelid)='(ended_at IS NULL)'
      AND ARRAY(SELECT a.attname::text
          FROM unnest(i.indkey) WITH ORDINALITY AS k(attnum,n)
          JOIN pg_catalog.pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=k.attnum ORDER BY k.n)
          = ARRAY['user_id','vehicle_id','kind']::text[]
    FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class c ON c.oid=i.indexrelid
    JOIN pg_catalog.pg_am am ON am.oid=c.relam
    WHERE i.indrelid='jourvolt_telemetry_sessions'::regclass
      AND c.relname='jourvolt_telemetry_open_session_idx'
),false)`

const compactBootstrapMappingSQL = `/* compact_bootstrap_mapping */ SELECT true
    FROM jourvolt_telemetry_vehicle_keys k
    JOIN jourvolt_vehicles v ON v.id=k.vehicle_id AND v.user_id=k.user_id
    WHERE k.user_id=$1 AND k.vehicle_id=$2 AND k.vin_hash=$3
    FOR UPDATE OF k FOR SHARE OF v`

// The two scalar defaults are the only historical values the legacy PostgreSQL
// loader uses that affect the reducer. Other fields contribute watermarks only.
// CASE guards bound bytes before transmission, including malformed persisted
// fields; LIMIT includes a sentinel rather than silently filtering unknown names.
// This bounds returned Go data, not PostgreSQL TOAST/scan/sort work.
const compactBootstrapLatestSQL = `SELECT
    CASE WHEN octet_length(field_name)<=64 THEN field_name END,
    observed_at, source=$3,
    CASE WHEN octet_length(value_hash)=64 THEN value_hash END,
    CASE WHEN field_name IN ('VehicleSpeed','GpsHeading') THEN
      CASE WHEN jsonb_typeof(value_json)='number' AND octet_length(value_json::text)<=1024
        THEN value_json::text END END
    FROM jourvolt_telemetry_latest
    WHERE user_id=$1 AND vehicle_id=$2 ORDER BY field_name LIMIT 32 FOR SHARE`

type compactBootstrapLatestRow struct {
	Field, Hash, Number *string
	ObservedAt          time.Time
	SupportedSource     bool
}

func prepareCompactBootstrap(ctx context.Context, tx pgx.Tx, expected compactBootstrapExpectation) (compactBootstrapCandidate, error) {
	var empty compactBootstrapCandidate
	ineligible := func(reason string) (compactBootstrapCandidate, error) {
		return empty, &compactBootstrapError{Reason: reason}
	}
	if tx == nil || expected.Version != compactBootstrapVersion || compactTelemetryVersion != compactBootstrapVersion || compactTelemetryFieldCount != 31 || expected.Source != "telemetry_mqtt" || expected.Debounce <= 0 || strings.TrimSpace(expected.VINHash) == "" || len(expected.VINHash) > 256 {
		return ineligible("unsupported_contract")
	}
	state, err := newCompactTelemetryState(expected.Scope, expected.Debounce)
	if err != nil {
		return ineligible("unsupported_contract")
	}
	ctx, cancel := historyReadContext(ctx)
	defer cancel()
	var isolation string
	if err = tx.QueryRow(ctx, `SELECT current_setting('transaction_isolation')`).Scan(&isolation); err != nil {
		return empty, err
	}
	if isolation != "read committed" {
		return ineligible("unsupported_transaction_isolation")
	}
	// This rare, explicit bootstrap serializes with concurrent index maintenance
	// until the caller finishes. Ordinary legacy DML remains compatible. A fresh
	// READ COMMITTED snapshot is required after waiting for prior mapped ingest.
	if _, err = tx.Exec(ctx, `LOCK TABLE jourvolt_telemetry_sessions IN SHARE UPDATE EXCLUSIVE MODE`); err != nil {
		return empty, err
	}
	var valid bool
	if err = tx.QueryRow(ctx, compactBootstrapIndexSQL).Scan(&valid); err != nil {
		return empty, err
	}
	if !valid {
		return ineligible("invalid_active_index")
	}
	if err = tx.QueryRow(ctx, compactBootstrapMappingSQL, expected.Scope.UserID, expected.Scope.VehicleID, expected.VINHash).Scan(&valid); errors.Is(err, pgx.ErrNoRows) {
		return ineligible("ownership_or_binding_mismatch")
	} else if err != nil {
		return empty, err
	}
	// Mapping FOR UPDATE precedes all predecessor state. Legacy ingestion holds
	// KEY SHARE on this mapping until commit, so prior ingestion must drain first.
	rows, err := tx.Query(ctx, `SELECT CASE WHEN octet_length(kind)<=8 THEN kind END
        FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2 AND ended_at IS NULL
        ORDER BY kind LIMIT 3 FOR SHARE`, expected.Scope.UserID, expected.Scope.VehicleID)
	if err != nil {
		return empty, err
	}
	var active [3]*string
	n := 0
	for rows.Next() {
		if n == len(active) {
			rows.Close()
			return ineligible("invalid_active_sessions")
		}
		if err = rows.Scan(&active[n]); err != nil {
			rows.Close()
			return empty, err
		}
		n++
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return empty, err
	}
	if n > 2 {
		return ineligible("invalid_active_sessions")
	}
	for i := 0; i < n; i++ {
		if active[i] == nil || (*active[i] != "drive" && *active[i] != "charge") || (i > 0 && *active[i] == *active[i-1]) {
			return ineligible("invalid_active_sessions")
		}
	}
	if n != 0 {
		return ineligible("legacy_active_requires_drain")
	}

	rows, err = tx.Query(ctx, compactBootstrapLatestSQL, expected.Scope.UserID, expected.Scope.VehicleID, expected.Source)
	if err != nil {
		return empty, err
	}
	var latest [compactTelemetryFieldCount + 1]compactBootstrapLatestRow
	n = 0
	for rows.Next() {
		if n == len(latest) {
			rows.Close()
			return ineligible("too_many_latest_fields")
		}
		row := &latest[n]
		if err = rows.Scan(&row.Field, &row.ObservedAt, &row.SupportedSource, &row.Hash, &row.Number); err != nil {
			rows.Close()
			return empty, err
		}
		n++
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return empty, err
	}
	if n > compactTelemetryFieldCount {
		return ineligible("too_many_latest_fields")
	}
	var hashes [compactTelemetryFieldCount]string
	for _, row := range latest[:n] {
		if row.Field == nil {
			return ineligible("unsupported_latest_field")
		}
		slot := compactTelemetryFieldIndex(*row.Field)
		if slot < 0 {
			return ineligible("unsupported_latest_field")
		}
		if !row.SupportedSource {
			return ineligible("unsupported_latest_source")
		}
		if row.Hash == nil || row.ObservedAt.IsZero() || !state.FieldTimes[slot].IsZero() {
			return ineligible("invalid_latest_observation")
		}
		if digest, err := hex.DecodeString(*row.Hash); err != nil || len(digest) != sha256.Size {
			return ineligible("invalid_latest_observation")
		}
		state.FieldTimes[slot], hashes[slot] = row.ObservedAt, *row.Hash
		if *row.Field == "VehicleSpeed" || *row.Field == "GpsHeading" {
			if row.Number == nil {
				return ineligible("invalid_latest_observation")
			}
			value, err := strconv.ParseFloat(*row.Number, 64)
			if err != nil || !finiteCompactNumbers(&value) {
				return ineligible("invalid_latest_observation")
			}
			if *row.Field == "VehicleSpeed" {
				state.LastSpeed, state.LastSpeedAt = &value, row.ObservedAt
			} else {
				state.LastHeading, state.LastHeadingAt = &value, row.ObservedAt
			}
		}
	}
	if err = validateCompactTelemetryState(state, expected.Scope); err != nil {
		return empty, err
	}
	// This digest pins only the bounded reducer predecessor and its declared
	// hashes. It is not a complete-payload hash, database revision or fence token.
	// It cannot make this candidate safe to reuse after the transaction ends.
	encoded, err := json.Marshal(struct {
		Expected compactBootstrapExpectation
		State    compactTelemetryState
		Hashes   [compactTelemetryFieldCount]string
	}{expected, state, hashes})
	if err != nil {
		return empty, fmt.Errorf("encode compact predecessor: %w", err)
	}
	digest := sha256.Sum256(encoded)
	return compactBootstrapCandidate{State: state, VINHash: strings.Clone(expected.VINHash), Source: "telemetry_mqtt", PredecessorFingerprint: hex.EncodeToString(digest[:])}, nil
}
