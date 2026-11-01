package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"math"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

const batteryTrendMaxBody = 256 << 10
const batteryTrendMaxBatch = 50
const batteryTrendMaxObservation = 16 << 10

var (
	errBatteryTrendInvalid        = errors.New("battery_trend_invalid")
	errBatteryTrendConflict       = errors.New("battery_trend_conflict")
	errBatteryTrendArchivePending = errors.New("battery_trend_archive_pending")
	batteryTrendBatchIDPattern    = regexp.MustCompile(`^[A-Za-z0-9_.:-]{1,128}$`)
	batteryTrendHashPattern       = regexp.MustCompile(`^[0-9a-f]{64}$`)
)

// The order and null fields are the versioned hash contract shared with the bridge.
type batteryTrendUnits struct {
	RatedRange  string `json:"rated_range"`
	Temperature string `json:"temperature"`
	Soc         string `json:"soc"`
}

type batteryTrendBoundary struct {
	SocPercent            *int     `json:"soc_percent"`
	SocPositionObservedAt *string  `json:"soc_position_observed_at"`
	SocPositionID         *int64   `json:"soc_position_id"`
	RatedRangeKm          *float64 `json:"rated_range_km"`
	RatedRangeSource      *string  `json:"rated_range_source"`
	RatedRangeObservedAt  *string  `json:"rated_range_observed_at"`
}

type batteryTrendObservation struct {
	SourceRecordID    string                `json:"source_record_id"`
	StartedAt         string                `json:"started_at"`
	EndedAt           string                `json:"ended_at"`
	Start             *batteryTrendBoundary `json:"start"`
	End               *batteryTrendBoundary `json:"end"`
	OutsideTempAvgC   *float64              `json:"outside_temp_avg_c"`
	TemperatureSource *string               `json:"temperature_source"`
}

type batteryTrendPayload struct {
	SchemaVersion    int                       `json:"schema_version"`
	Source           string                    `json:"source"`
	SourceInstanceID string                    `json:"source_instance_id"`
	SourceVehicleID  string                    `json:"source_vehicle_id"`
	Units            batteryTrendUnits         `json:"units"`
	Observations     []batteryTrendObservation `json:"observations"`
}

type batteryTrendImportRequest struct {
	SchemaVersion    int                       `json:"schema_version"`
	Source           string                    `json:"source"`
	SourceInstanceID string                    `json:"source_instance_id"`
	SourceVehicleID  string                    `json:"source_vehicle_id"`
	BatchID          string                    `json:"batch_id"`
	PayloadSHA256    string                    `json:"payload_sha256"`
	Units            batteryTrendUnits         `json:"units"`
	Observations     []batteryTrendObservation `json:"observations"`
}

func (r batteryTrendImportRequest) payload() batteryTrendPayload {
	return batteryTrendPayload{r.SchemaVersion, r.Source, r.SourceInstanceID, r.SourceVehicleID, r.Units, r.Observations}
}

func batteryTrendDigest(value any) (string, error) {
	encoded, err := json.Marshal(value)
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256(encoded)
	return hex.EncodeToString(sum[:]), nil
}

func batteryTrendTimestamp(value string) (time.Time, bool) {
	parsed, err := time.Parse(time.RFC3339Nano, value)
	return parsed, err == nil && parsed.UTC().Format(time.RFC3339Nano) == value
}

func validBatteryTrendBoundary(boundary *batteryTrendBoundary, reference time.Time, source string) bool {
	if boundary == nil {
		return true
	}
	if (boundary.SocPositionID == nil) != (boundary.SocPositionObservedAt == nil) {
		return false
	}
	if boundary.SocPercent != nil && (boundary.SocPositionID == nil || *boundary.SocPercent < 0 || *boundary.SocPercent > 100) {
		return false
	}
	if boundary.SocPositionID != nil {
		observed, ok := batteryTrendTimestamp(*boundary.SocPositionObservedAt)
		if !ok || *boundary.SocPositionID <= 0 || observed.Before(reference.Add(-time.Second)) || observed.After(reference.Add(time.Second)) {
			return false
		}
	}
	if boundary.RatedRangeObservedAt != nil {
		return false
	}
	if boundary.RatedRangeKm == nil {
		return boundary.RatedRangeSource == nil
	}
	return !math.IsNaN(*boundary.RatedRangeKm) && !math.IsInf(*boundary.RatedRangeKm, 0) &&
		*boundary.RatedRangeKm >= 0 && boundary.RatedRangeSource != nil && *boundary.RatedRangeSource == source
}

func validBatteryTrendObservation(observation batteryTrendObservation) bool {
	rawID := strings.TrimPrefix(observation.SourceRecordID, "teslamate:drive:")
	id, idErr := strconv.ParseInt(rawID, 10, 64)
	start, startOK := batteryTrendTimestamp(observation.StartedAt)
	end, endOK := batteryTrendTimestamp(observation.EndedAt)
	if !strings.HasPrefix(observation.SourceRecordID, "teslamate:drive:") || idErr != nil || id <= 0 || strconv.FormatInt(id, 10) != rawID ||
		!startOK || !endOK || end.Before(start) ||
		!validBatteryTrendBoundary(observation.Start, start, "teslamate_drive_start_summary") ||
		!validBatteryTrendBoundary(observation.End, end, "teslamate_drive_end_summary") {
		return false
	}
	if observation.OutsideTempAvgC == nil {
		return observation.TemperatureSource == nil
	}
	return !math.IsNaN(*observation.OutsideTempAvgC) && !math.IsInf(*observation.OutsideTempAvgC, 0) &&
		observation.TemperatureSource != nil && *observation.TemperatureSource == "teslamate_drive_average"
}

func validateBatteryTrendImport(request batteryTrendImportRequest, binding archiveBinding) error {
	if request.SchemaVersion != 1 || request.Source != "teslamate" ||
		request.SourceInstanceID != binding.SourceInstanceID || request.SourceVehicleID != binding.SourceVehicleID ||
		len(request.SourceInstanceID) == 0 || len(request.SourceInstanceID) > 128 || strings.TrimSpace(request.SourceInstanceID) != request.SourceInstanceID || strings.ContainsRune(request.SourceInstanceID, '\x00') ||
		len(request.SourceVehicleID) == 0 || len(request.SourceVehicleID) > 128 || strings.TrimSpace(request.SourceVehicleID) != request.SourceVehicleID || strings.ContainsRune(request.SourceVehicleID, '\x00') ||
		!batteryTrendBatchIDPattern.MatchString(request.BatchID) || !batteryTrendHashPattern.MatchString(request.PayloadSHA256) ||
		request.Units != (batteryTrendUnits{RatedRange: "km", Temperature: "C", Soc: "percent"}) ||
		len(request.Observations) == 0 || len(request.Observations) > batteryTrendMaxBatch {
		return errBatteryTrendInvalid
	}
	seen := make(map[string]bool, len(request.Observations))
	for _, observation := range request.Observations {
		encoded, err := json.Marshal(observation)
		if !validBatteryTrendObservation(observation) || seen[observation.SourceRecordID] || err != nil || len(encoded) > batteryTrendMaxObservation {
			return errBatteryTrendInvalid
		}
		seen[observation.SourceRecordID] = true
	}
	digest, err := batteryTrendDigest(request.payload())
	if err != nil || digest != request.PayloadSHA256 {
		return errBatteryTrendInvalid
	}
	return nil
}

// This checksum detects accidental receipt corruption; it is not a device signature.
func decodeBatteryTrendEvidence(raw string, hash string) (batteryTrendObservation, error) {
	var observation batteryTrendObservation
	if len(raw) > batteryTrendMaxObservation || !batteryTrendHashPattern.MatchString(hash) {
		return observation, errBatteryTrendConflict
	}
	sum := sha256.Sum256([]byte(raw))
	if hex.EncodeToString(sum[:]) != hash {
		return observation, errBatteryTrendConflict
	}
	decoder := json.NewDecoder(strings.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&observation); err != nil {
		return observation, errBatteryTrendConflict
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) || !validBatteryTrendObservation(observation) {
		return observation, errBatteryTrendConflict
	}
	return observation, nil
}

func batteryTrendImportPath(path string) bool {
	parts := strings.Split(strings.Trim(path, "/"), "/")
	return len(parts) == 8 && parts[0] == "api" && parts[1] == "v1" && parts[2] == "cars" &&
		parts[4] == "history" && parts[5] == "archive" && parts[6] == "battery-trend" && parts[7] == "import"
}

func (a *app) batteryTrendArchiveImport(w http.ResponseWriter, r *http.Request) {
	if !batteryTrendImportPath(r.URL.Path) {
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	if r.Method != http.MethodPost {
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
	vehicleID, err := strconv.Atoi(parts[3])
	if err != nil || vehicleID <= 0 {
		a.json(w, http.StatusNotFound, map[string]string{"error": "vehicle_not_found"})
		return
	}
	if strings.TrimSpace(r.Header.Get(archiveBindingHeader)) == "" {
		a.archiveBindingError(w, errArchiveBindingRequired)
		return
	}
	repository := a.archiveBindingRepository()
	if repository == nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "archive_binding_unavailable"})
		return
	}
	binding, err := repository.resolveArchiveBinding(r.Context(), r.Header.Get(archiveBindingHeader))
	if err != nil {
		a.archiveBindingError(w, err)
		return
	}
	if binding.VehicleID != vehicleID {
		a.archiveBindingError(w, errArchiveBindingScopeMismatch)
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, batteryTrendMaxBody))
	if err != nil {
		var limit *http.MaxBytesError
		if errors.As(err, &limit) {
			a.json(w, http.StatusRequestEntityTooLarge, map[string]string{"error": "request_body_too_large"})
		} else {
			a.json(w, http.StatusBadRequest, map[string]string{"error": "invalid_json"})
		}
		return
	}
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	var request batteryTrendImportRequest
	if decoder.Decode(&request) != nil {
		a.json(w, http.StatusBadRequest, map[string]string{"error": "invalid_json"})
		return
	}
	var extra any
	if err = decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		a.json(w, http.StatusBadRequest, map[string]string{"error": "invalid_json"})
		return
	}
	if request.SourceInstanceID != binding.SourceInstanceID || request.SourceVehicleID != binding.SourceVehicleID || request.Source != binding.SourceType {
		a.archiveBindingError(w, errArchiveBindingScopeMismatch)
		return
	}
	if err = validateBatteryTrendImport(request, binding); err != nil {
		a.json(w, http.StatusUnprocessableEntity, map[string]string{"error": errBatteryTrendInvalid.Error()})
		return
	}
	if a.store == nil || a.store.pool == nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "battery_trend_unavailable"})
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
	defer cancel()
	inserted, unchanged, err := storeBatteryTrendBatch(ctx, a.store, binding, request, archiveBindingTokenHash(r.Header.Get(archiveBindingHeader)))
	if errors.Is(err, errBatteryTrendArchivePending) {
		w.Header().Set("Retry-After", "60")
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": errBatteryTrendArchivePending.Error()})
		return
	}
	if errors.Is(err, errBatteryTrendConflict) {
		a.json(w, http.StatusConflict, map[string]string{"error": errBatteryTrendConflict.Error()})
		return
	}
	if errors.Is(err, errArchiveBindingRevoked) || errors.Is(err, errArchiveBindingExpired) || errors.Is(err, errArchiveBindingScopeMismatch) {
		a.archiveBindingError(w, err)
		return
	}
	if err != nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "battery_trend_unavailable"})
		return
	}
	a.json(w, http.StatusOK, map[string]any{"data": map[string]any{"schema_version": 1, "batch_id": request.BatchID,
		"payload_sha256": request.PayloadSHA256, "inserted": inserted, "unchanged": unchanged, "conflicted": 0}})
}

func lockBatteryTrendBinding(ctx context.Context, tx pgx.Tx, binding archiveBinding, tokenHash string) (time.Time, error) {
	var user, instance, sourceVehicle string
	var vehicle int
	var expires time.Time
	var revoked *time.Time
	err := tx.QueryRow(ctx, `SELECT user_id,vehicle_id,source_instance_id,source_vehicle_id,expires_at,revoked_at FROM jourvolt_history_archive_bindings WHERE token_hash=$1 FOR SHARE`, tokenHash).
		Scan(&user, &vehicle, &instance, &sourceVehicle, &expires, &revoked)
	if errors.Is(err, pgx.ErrNoRows) {
		return time.Time{}, errArchiveBindingRevoked
	}
	if err != nil {
		return time.Time{}, err
	}
	if user != binding.UserID || vehicle != binding.VehicleID || instance != binding.SourceInstanceID || sourceVehicle != binding.SourceVehicleID {
		return time.Time{}, errArchiveBindingScopeMismatch
	}
	if revoked != nil {
		return time.Time{}, errArchiveBindingRevoked
	}
	if err = requireBatteryTrendBindingNotExpired(ctx, tx, expires); err != nil {
		return time.Time{}, err
	}
	return expires, nil
}

func requireBatteryTrendBindingNotExpired(ctx context.Context, tx pgx.Tx, expires time.Time) error {
	var valid bool
	if err := tx.QueryRow(ctx, `SELECT $1::timestamptz > clock_timestamp()`, expires).Scan(&valid); err != nil {
		return err
	}
	if !valid {
		return errArchiveBindingExpired
	}
	return nil
}

const batteryTrendArchiveConflictQuery = `SELECT
EXISTS(SELECT 1 FROM jourvolt_telemetry_sessions WHERE id=$1)
OR EXISTS(SELECT 1 FROM jourvolt_telemetry_sessions WHERE user_id=$2 AND vehicle_id=$3 AND kind='drive' AND source='teslamate_archive' AND source_instance_id=$4 AND source_vehicle_id=$5 AND source_record_id=$6)
OR EXISTS(SELECT 1 FROM jourvolt_telemetry_sessions WHERE user_id=$2 AND vehicle_id=$3 AND kind='drive' AND source<>'teslamate_archive' AND source_instance_id=$4 AND source_vehicle_id=$5 AND source_record_id=$6)
OR EXISTS(SELECT 1 FROM jourvolt_telemetry_sessions WHERE user_id=$2 AND vehicle_id=$3 AND kind='drive' AND source IS NULL AND source_instance_id=$4 AND source_vehicle_id=$5 AND source_record_id=$6)`

func lockBatteryTrendArchiveSession(ctx context.Context, tx pgx.Tx, binding archiveBinding, observation batteryTrendObservation) error {
	start, _ := batteryTrendTimestamp(observation.StartedAt)
	end, _ := batteryTrendTimestamp(observation.EndedAt)
	id := scopedArchiveSessionID(binding.UserID, binding.VehicleID, "drive", binding.SourceInstanceID, binding.SourceVehicleID, observation.SourceRecordID)
	var matched string
	err := tx.QueryRow(ctx, `SELECT id FROM jourvolt_telemetry_sessions WHERE id=$1 AND user_id=$2 AND vehicle_id=$3 AND kind='drive' AND source='teslamate_archive' AND quality_state <> 'quarantined' AND source_instance_id=$4 AND source_vehicle_id=$5 AND source_record_id=$6 AND started_at=$7 AND ended_at=$8 FOR SHARE`, id, binding.UserID, binding.VehicleID, binding.SourceInstanceID, binding.SourceVehicleID, observation.SourceRecordID, start, end).Scan(&matched)
	if errors.Is(err, pgx.ErrNoRows) {
		var conflict bool
		err = tx.QueryRow(ctx, batteryTrendArchiveConflictQuery, id, binding.UserID, binding.VehicleID, binding.SourceInstanceID, binding.SourceVehicleID, observation.SourceRecordID).Scan(&conflict)
		if err != nil {
			return err
		}
		if conflict {
			return errBatteryTrendConflict
		}
		return errBatteryTrendArchivePending
	}
	return err
}

func storedBatteryTrendEvidenceMatches(ctx context.Context, tx pgx.Tx, binding archiveBinding, observation batteryTrendObservation, encoded []byte, hash string) (bool, error) {
	start, _ := batteryTrendTimestamp(observation.StartedAt)
	end, _ := batteryTrendTimestamp(observation.EndedAt)
	var storedStart, storedEnd time.Time
	var storedPayload *string
	var storedHash string
	err := tx.QueryRow(ctx, `SELECT started_at,ended_at,CASE WHEN octet_length(payload_json)<=$6 THEN payload_json END,payload_sha256 FROM jourvolt_battery_trend_evidence WHERE user_id=$1 AND vehicle_id=$2 AND source_instance_id=$3 AND source_vehicle_id=$4 AND source_record_id=$5 FOR SHARE`, binding.UserID, binding.VehicleID, binding.SourceInstanceID, binding.SourceVehicleID, observation.SourceRecordID, batteryTrendMaxObservation).Scan(&storedStart, &storedEnd, &storedPayload, &storedHash)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if !storedStart.Equal(start) || !storedEnd.Equal(end) || storedPayload == nil || *storedPayload != string(encoded) || storedHash != hash {
		return true, errBatteryTrendConflict
	}
	return true, nil
}

func requireStoredBatteryTrendEvidence(ctx context.Context, tx pgx.Tx, binding archiveBinding, observation batteryTrendObservation, encoded []byte, hash string) error {
	found, err := storedBatteryTrendEvidenceMatches(ctx, tx, binding, observation, encoded, hash)
	if err != nil {
		return err
	}
	if !found {
		return errBatteryTrendConflict
	}
	return nil
}

func storeBatteryTrendBatch(ctx context.Context, store *store, binding archiveBinding, request batteryTrendImportRequest, tokenHash string) (int, int, error) {
	tx, err := store.pool.Begin(ctx)
	if err != nil {
		return 0, 0, err
	}
	defer tx.Rollback(ctx)
	// Serialize a vehicle's commits so a paged snapshot cannot miss a late lower sequence.
	lock := scopedSessionID("battery-trend", binding.UserID, binding.VehicleID, "scope", "")
	if _, err = tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1,0))`, lock); err != nil {
		return 0, 0, err
	}
	var owner int
	err = tx.QueryRow(ctx, `SELECT id FROM jourvolt_vehicles WHERE id=$1 AND user_id=$2 FOR SHARE`, binding.VehicleID, binding.UserID).Scan(&owner)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, 0, errArchiveBindingScopeMismatch
	}
	if err != nil {
		return 0, 0, err
	}
	expires, err := lockBatteryTrendBinding(ctx, tx, binding, tokenHash)
	if err != nil {
		return 0, 0, err
	}
	var existingHash string
	var inserted, unchanged int
	err = tx.QueryRow(ctx, `SELECT payload_sha256, inserted_count, unchanged_count FROM jourvolt_battery_trend_batches WHERE user_id=$1 AND vehicle_id=$2 AND source_instance_id=$3 AND source_vehicle_id=$4 AND batch_id=$5 FOR SHARE`, binding.UserID, binding.VehicleID, binding.SourceInstanceID, binding.SourceVehicleID, request.BatchID).Scan(&existingHash, &inserted, &unchanged)
	if err == nil {
		if existingHash != request.PayloadSHA256 {
			return 0, 0, errBatteryTrendConflict
		}
		// A prior receipt is only valid while every corresponding source session
		// and its exact canonical evidence remain present and unchanged.
		for _, observation := range request.Observations {
			if err = lockBatteryTrendArchiveSession(ctx, tx, binding, observation); err != nil {
				if errors.Is(err, errBatteryTrendArchivePending) {
					return 0, 0, errBatteryTrendConflict
				}
				return 0, 0, err
			}
			encoded, encodeErr := json.Marshal(observation)
			if encodeErr != nil {
				return 0, 0, encodeErr
			}
			sum := sha256.Sum256(encoded)
			if err = requireStoredBatteryTrendEvidence(ctx, tx, binding, observation, encoded, hex.EncodeToString(sum[:])); err != nil {
				return 0, 0, err
			}
		}
		if err = requireBatteryTrendBindingNotExpired(ctx, tx, expires); err != nil {
			return 0, 0, err
		}
		return inserted, unchanged, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return 0, 0, err
	}
	type preparedObservation struct {
		observation batteryTrendObservation
		encoded     []byte
		hash        string
		exists      bool
	}
	prepared := make([]preparedObservation, 0, len(request.Observations))
	missing := false
	for _, observation := range request.Observations {
		err = lockBatteryTrendArchiveSession(ctx, tx, binding, observation)
		sessionMissing := errors.Is(err, errBatteryTrendArchivePending)
		if sessionMissing {
			missing = true
		} else if err != nil {
			return 0, 0, err
		}
		encoded, err := json.Marshal(observation)
		if err != nil {
			return 0, 0, err
		}
		sum := sha256.Sum256(encoded)
		hash := hex.EncodeToString(sum[:])
		exists, err := storedBatteryTrendEvidenceMatches(ctx, tx, binding, observation, encoded, hash)
		if err != nil {
			return 0, 0, err
		}
		if sessionMissing && exists {
			return 0, 0, errBatteryTrendConflict
		}
		prepared = append(prepared, preparedObservation{observation: observation, encoded: encoded, hash: hash, exists: exists})
	}
	if missing {
		if err = requireBatteryTrendBindingNotExpired(ctx, tx, expires); err != nil {
			return 0, 0, err
		}
		return 0, 0, errBatteryTrendArchivePending
	}
	for _, item := range prepared {
		observation, encoded, hash := item.observation, item.encoded, item.hash
		if item.exists {
			unchanged++
			continue
		}
		start, _ := batteryTrendTimestamp(observation.StartedAt)
		end, _ := batteryTrendTimestamp(observation.EndedAt)
		result, err := tx.Exec(ctx, `INSERT INTO jourvolt_battery_trend_evidence(user_id,vehicle_id,source_instance_id,source_vehicle_id,source_record_id,started_at,ended_at,payload_json,payload_sha256) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9) ON CONFLICT(user_id,vehicle_id,source_instance_id,source_vehicle_id,source_record_id) DO NOTHING`, binding.UserID, binding.VehicleID, binding.SourceInstanceID, binding.SourceVehicleID, observation.SourceRecordID, start, end, string(encoded), hash)
		if err != nil {
			return 0, 0, err
		}
		if result.RowsAffected() == 1 {
			inserted++
			continue
		}
		if err = requireStoredBatteryTrendEvidence(ctx, tx, binding, observation, encoded, hash); err != nil {
			return 0, 0, err
		}
		unchanged++
	}
	_, err = tx.Exec(ctx, `INSERT INTO jourvolt_battery_trend_batches(user_id,vehicle_id,source_instance_id,source_vehicle_id,batch_id,payload_sha256,inserted_count,unchanged_count) VALUES($1,$2,$3,$4,$5,$6,$7,$8)`, binding.UserID, binding.VehicleID, binding.SourceInstanceID, binding.SourceVehicleID, request.BatchID, request.PayloadSHA256, inserted, unchanged)
	if err != nil {
		return 0, 0, err
	}
	if err = requireBatteryTrendBindingNotExpired(ctx, tx, expires); err != nil {
		return 0, 0, err
	}
	if err = tx.Commit(ctx); err != nil {
		return 0, 0, err
	}
	return inserted, unchanged, nil
}

func batteryTrendPageNumber(raw string, defaultValue int64) (int64, bool) {
	if raw == "" {
		return defaultValue, true
	}
	value, err := strconv.ParseInt(raw, 10, 64)
	return value, err == nil && value >= 0 && strconv.FormatInt(value, 10) == raw
}

func (a *app) batteryTrendEvidenceList(w http.ResponseWriter, r *http.Request, userID string, vehicleID int) {
	if a.store == nil || a.store.pool == nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "battery_trend_unavailable"})
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
	defer cancel()
	tx, err := a.store.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.RepeatableRead, AccessMode: pgx.ReadOnly})
	if err != nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "battery_trend_unavailable"})
		return
	}
	defer tx.Rollback(ctx)
	var owned int
	err = tx.QueryRow(ctx, `SELECT 1 FROM jourvolt_vehicles WHERE id=$1 AND user_id=$2`, vehicleID, userID).Scan(&owned)
	if errors.Is(err, pgx.ErrNoRows) {
		a.json(w, http.StatusNotFound, map[string]string{"error": "vehicle_not_found"})
		return
	}
	if err != nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "battery_trend_unavailable"})
		return
	}
	query := r.URL.Query()
	if len(query["cursor"]) > 1 || len(query["snapshot_max_seq"]) > 1 || len(query["limit"]) > 1 {
		a.json(w, http.StatusBadRequest, map[string]string{"error": "battery_trend_invalid_page"})
		return
	}
	cursor, ok := batteryTrendPageNumber(query.Get("cursor"), 0)
	if !ok {
		a.json(w, http.StatusBadRequest, map[string]string{"error": "battery_trend_invalid_page"})
		return
	}
	limit, ok := batteryTrendPageNumber(query.Get("limit"), 50)
	if !ok || limit < 1 || limit > 100 {
		a.json(w, http.StatusBadRequest, map[string]string{"error": "battery_trend_invalid_page"})
		return
	}
	snapshot, ok := batteryTrendPageNumber(query.Get("snapshot_max_seq"), -1)
	if !ok || snapshot < -1 {
		a.json(w, http.StatusBadRequest, map[string]string{"error": "battery_trend_invalid_page"})
		return
	}
	if snapshot == -1 {
		if cursor != 0 {
			a.json(w, http.StatusBadRequest, map[string]string{"error": "battery_trend_invalid_page"})
			return
		}
		if err := tx.QueryRow(ctx, `SELECT COALESCE(MAX(ingest_seq),0) FROM jourvolt_battery_trend_evidence WHERE user_id=$1 AND vehicle_id=$2`, userID, vehicleID).Scan(&snapshot); err != nil {
			a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "battery_trend_unavailable"})
			return
		}
	}
	if cursor > snapshot {
		a.json(w, http.StatusBadRequest, map[string]string{"error": "battery_trend_invalid_page"})
		return
	}
	var mismatched bool
	err = tx.QueryRow(ctx, `SELECT EXISTS(
		SELECT 1 FROM jourvolt_battery_trend_evidence e
		WHERE e.user_id=$1 AND e.vehicle_id=$2 AND e.ingest_seq<=$3
		AND NOT EXISTS (SELECT 1 FROM jourvolt_telemetry_sessions s
			WHERE s.user_id=e.user_id AND s.vehicle_id=e.vehicle_id AND s.kind='drive'
			AND s.source='teslamate_archive' AND s.quality_state <> 'quarantined'
			AND s.source_instance_id=e.source_instance_id AND s.source_vehicle_id=e.source_vehicle_id
			AND s.source_record_id=e.source_record_id AND s.started_at=e.started_at AND s.ended_at=e.ended_at)
	)`, userID, vehicleID, snapshot).Scan(&mismatched)
	if err != nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "battery_trend_unavailable"})
		return
	}
	if mismatched {
		a.json(w, http.StatusConflict, map[string]string{"error": errBatteryTrendConflict.Error()})
		return
	}
	rows, err := tx.Query(ctx, `SELECT ingest_seq,source_instance_id,source_vehicle_id,source_record_id,started_at,ended_at,CASE WHEN octet_length(payload_json)<=$6 THEN payload_json END,payload_sha256 FROM jourvolt_battery_trend_evidence WHERE user_id=$1 AND vehicle_id=$2 AND ingest_seq>$3 AND ingest_seq<=$4 ORDER BY ingest_seq LIMIT $5`, userID, vehicleID, cursor, snapshot, limit+1, batteryTrendMaxObservation)
	if err != nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "battery_trend_unavailable"})
		return
	}
	observations := make([]map[string]any, 0, limit)
	last := cursor
	more := false
	for rows.Next() {
		var sequence int64
		var instance, sourceVehicle, record, hash string
		var start, end time.Time
		var raw *string
		if err = rows.Scan(&sequence, &instance, &sourceVehicle, &record, &start, &end, &raw, &hash); err != nil {
			break
		}
		if int64(len(observations)) == limit {
			more = true
			break
		}
		if raw == nil {
			err = errBatteryTrendConflict
			break
		}
		observation, decodeErr := decodeBatteryTrendEvidence(*raw, hash)
		if decodeErr != nil {
			err = decodeErr
			break
		}
		observationStart, _ := batteryTrendTimestamp(observation.StartedAt)
		observationEnd, _ := batteryTrendTimestamp(observation.EndedAt)
		if observation.SourceRecordID != record || !observationStart.Equal(start) || !observationEnd.Equal(end) {
			err = errBatteryTrendConflict
			break
		}
		observations = append(observations, map[string]any{"source_instance_id": instance, "source_vehicle_id": sourceVehicle, "payload_sha256": hash, "observation": observation})
		last = sequence
	}
	rows.Close()
	if errors.Is(err, errBatteryTrendConflict) {
		a.json(w, http.StatusConflict, map[string]string{"error": errBatteryTrendConflict.Error()})
		return
	}
	if err != nil || rows.Err() != nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "battery_trend_unavailable"})
		return
	}
	if err = tx.Commit(ctx); err != nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "battery_trend_unavailable"})
		return
	}
	var next any
	if more {
		next = strconv.FormatInt(last, 10)
	}
	a.json(w, http.StatusOK, map[string]any{"data": map[string]any{"schema_version": 1,
		"units":            batteryTrendUnits{RatedRange: "km", Temperature: "C", Soc: "percent"},
		"snapshot_max_seq": snapshot, "next_cursor": next, "complete": !more, "observations": observations}})
}
