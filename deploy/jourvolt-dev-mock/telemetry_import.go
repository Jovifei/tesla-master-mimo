package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

const (
	maxImportBodyBytes          = 10 << 20 // 10 MiB
	maxImportSessionsPerKind    = 200
	maxImportTotalSessions      = 400
	maxImportRoutePointsPerItem = 10000
	maxImportTotalRoutePoints   = 100000
)

func scopedImportedSessionID(userID string, vehicleID int, kind, clientSessionID string) string {
	h := sha256.New()
	h.Write([]byte(userID))
	h.Write([]byte{0})
	h.Write([]byte(strconv.Itoa(vehicleID)))
	h.Write([]byte{0})
	h.Write([]byte(kind))
	h.Write([]byte{0})
	h.Write([]byte(clientSessionID))
	return "local_import:" + hex.EncodeToString(h.Sum(nil))
}

// historyImportRequest is the payload for importing previously-collected local
// history into the cloud. The app uploads the history it already has on the
// phone (collected while self-hosted or before switching to a cloud account),
// and the cloud persists only the latest two calendar days per account.
type historyImportRequest struct {
	Drives  []historyImportSession `json:"drives"`
	Charges []historyImportSession `json:"charges"`
}

// historyImportSession is a single completed drive/charge record, including its
// full trajectory points so the cloud can reproduce the route on re-download.
type historyImportSession struct {
	SessionID     string                    `json:"session_id"`
	StartedAt     string                    `json:"started_at"`
	EndedAt       string                    `json:"ended_at"`
	OdometerStart *float64                  `json:"odometer_start"`
	OdometerEnd   *float64                  `json:"odometer_end"`
	EnergyAdded   *float64                  `json:"energy_added"`
	Route         []historyImportRoutePoint `json:"route"`
}

type historyImportRoutePoint struct {
	Date      string   `json:"date"`
	Latitude  *float64 `json:"latitude"`
	Longitude *float64 `json:"longitude"`
	Speed     *float64 `json:"speed"`
	Power     *float64 `json:"power"`
	Heading   *float64 `json:"heading"`
}

type historyImportResult struct {
	ImportedDrives  int      `json:"imported_drives"`
	ImportedCharges int      `json:"imported_charges"`
	RetainedDays    []string `json:"retained_days"`
}

// historyImportSessionValidationError describes a rejected import payload.
type historyImportSessionValidationError struct {
	Message string
}

func (e *historyImportSessionValidationError) Error() string { return e.Message }

func validateImportRequest(req historyImportRequest) error {
	if len(req.Drives) > maxImportSessionsPerKind || len(req.Charges) > maxImportSessionsPerKind ||
		len(req.Drives)+len(req.Charges) > maxImportTotalSessions {
		return &historyImportSessionValidationError{Message: "too_many_sessions"}
	}
	totalRoutePoints := 0
	for _, drive := range req.Drives {
		if len(drive.Route) > maxImportRoutePointsPerItem {
			return &historyImportSessionValidationError{Message: "too_many_route_points"}
		}
		totalRoutePoints += len(drive.Route)
	}
	for _, charge := range req.Charges {
		if len(charge.Route) > maxImportRoutePointsPerItem {
			return &historyImportSessionValidationError{Message: "too_many_route_points"}
		}
		totalRoutePoints += len(charge.Route)
	}
	if totalRoutePoints > maxImportTotalRoutePoints {
		return &historyImportSessionValidationError{Message: "too_many_route_points"}
	}
	return nil
}

func importRequestFromBody(w http.ResponseWriter, r *http.Request) (historyImportRequest, error) {
	reader := http.MaxBytesReader(w, r.Body, maxImportBodyBytes)
	var request historyImportRequest
	if err := json.NewDecoder(reader).Decode(&request); err != nil {
		var maxBytesErr *http.MaxBytesError
		if errors.As(err, &maxBytesErr) || strings.Contains(err.Error(), "request body too large") {
			return historyImportRequest{}, &historyImportSessionValidationError{Message: "request_body_too_large"}
		}
		return historyImportRequest{}, &historyImportSessionValidationError{Message: "invalid_json"}
	}
	if err := validateImportRequest(request); err != nil {
		return historyImportRequest{}, err
	}
	return request, nil
}

func (req historyImportSession) toTelemetrySession(userID string, vehicleID int, kind string) (telemetrySession, error) {
	if kind != "drive" && kind != "charge" {
		return telemetrySession{}, &historyImportSessionValidationError{Message: "invalid_kind"}
	}
	start, err := time.Parse(time.RFC3339, req.StartedAt)
	if err != nil {
		return telemetrySession{}, &historyImportSessionValidationError{Message: "invalid_started_at"}
	}
	end, err := time.Parse(time.RFC3339, req.EndedAt)
	if err != nil {
		return telemetrySession{}, &historyImportSessionValidationError{Message: "invalid_ended_at"}
	}
	if end.Before(start) {
		return telemetrySession{}, &historyImportSessionValidationError{Message: "ended_at_before_started_at"}
	}
	rawID := req.SessionID
	if rawID == "" {
		rawID = sessionID(kind, start)
	}
	id := scopedImportedSessionID(userID, vehicleID, kind, rawID)
	route := make([]telemetryRoutePoint, 0, len(req.Route))
	for _, point := range req.Route {
		if point.Latitude == nil || point.Longitude == nil {
			continue
		}
		if *point.Latitude < -90 || *point.Latitude > 90 || *point.Longitude < -180 || *point.Longitude > 180 {
			continue
		}
		observedAt := start
		if point.Date != "" {
			if parsed, err := time.Parse(time.RFC3339, point.Date); err == nil {
				observedAt = parsed
			}
		}
		route = append(route, telemetryRoutePoint{
			ObservedAt: observedAt, Latitude: *point.Latitude, Longitude: *point.Longitude,
			Speed: point.Speed, Power: point.Power, Heading: point.Heading,
		})
	}
	return telemetrySession{
		ID: id, Kind: kind, StartAt: start, EndAt: &end,
		OdometerStart: req.OdometerStart, OdometerEnd: req.OdometerEnd, EnergyAdded: req.EnergyAdded,
		Route: route,
	}, nil
}

// importHistory persists locally-collected history into the cloud store and
// enforces the per-account rolling retention (keep only the latest two
// calendar days that actually have data).
func (s *telemetryService) importHistory(ctx context.Context, userID string, vehicleID int, request historyImportRequest) (historyImportResult, error) {
	if s == nil {
		return historyImportResult{}, errors.New("telemetry_not_configured")
	}
	drives := make([]telemetrySession, 0, len(request.Drives))
	for _, item := range request.Drives {
		session, err := item.toTelemetrySession(userID, vehicleID, "drive")
		if err != nil {
			return historyImportResult{}, err
		}
		drives = append(drives, session)
	}
	charges := make([]telemetrySession, 0, len(request.Charges))
	for _, item := range request.Charges {
		session, err := item.toTelemetrySession(userID, vehicleID, "charge")
		if err != nil {
			return historyImportResult{}, err
		}
		charges = append(charges, session)
	}
	if s.memory != nil {
		s.memory.importSessions(userID, vehicleID, drives, charges)
		retained := s.memory.retainLatestDays(userID)
		return historyImportResult{ImportedDrives: len(drives), ImportedCharges: len(charges), RetainedDays: retained}, nil
	}
	return s.importHistoryPostgres(ctx, userID, vehicleID, drives, charges)
}

func (s *telemetryService) importHistoryPostgres(ctx context.Context, userID string, vehicleID int, drives, charges []telemetrySession) (historyImportResult, error) {
	if s.store == nil || s.store.pool == nil {
		return historyImportResult{}, errors.New("telemetry_not_configured")
	}
	tx, err := s.store.pool.Begin(ctx)
	if err != nil {
		return historyImportResult{}, err
	}
	defer tx.Rollback(ctx)
	importedDrives := 0
	for _, session := range drives {
		if err := upsertImportedSessionPostgres(ctx, tx, userID, vehicleID, session); err != nil {
			return historyImportResult{}, err
		}
		importedDrives++
	}
	importedCharges := 0
	for _, session := range charges {
		if err := upsertImportedSessionPostgres(ctx, tx, userID, vehicleID, session); err != nil {
			return historyImportResult{}, err
		}
		importedCharges++
	}
	retained, err := retainLatestDaysPostgres(ctx, tx, userID)
	if err != nil {
		return historyImportResult{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return historyImportResult{}, err
	}
	return historyImportResult{ImportedDrives: importedDrives, ImportedCharges: importedCharges, RetainedDays: retained}, nil
}

func upsertImportedSessionPostgres(ctx context.Context, tx pgx.Tx, userID string, vehicleID int, session telemetrySession) error {
	route, err := json.Marshal(session.Route)
	if err != nil {
		return err
	}
	var endAt *time.Time = session.EndAt
	if endAt == nil {
		now := time.Now().UTC()
		endAt = &now
	}
	_, err = tx.Exec(ctx, `
INSERT INTO jourvolt_telemetry_sessions(id, user_id, vehicle_id, kind, started_at, ended_at, odometer_start, odometer_end, energy_added, route_json, source)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10::jsonb, 'local_import')
ON CONFLICT (id) DO UPDATE SET
    ended_at = EXCLUDED.ended_at,
    odometer_start = EXCLUDED.odometer_start,
    odometer_end = EXCLUDED.odometer_end,
    energy_added = EXCLUDED.energy_added,
    route_json = EXCLUDED.route_json,
    source = EXCLUDED.source`,
		session.ID, userID, vehicleID, session.Kind, session.StartAt, endAt,
		session.OdometerStart, session.OdometerEnd, session.EnergyAdded, route)
	return err
}

// retainLatestDaysPostgres deletes sessions whose started_at falls outside the
// account's two most recent calendar days that contain data. The window is
// per-account: all vehicles share the same two most recent data days.
func retainLatestDaysPostgres(ctx context.Context, tx pgx.Tx, userID string) ([]string, error) {
	rows, err := tx.Query(ctx, `SELECT DISTINCT (started_at AT TIME ZONE 'UTC')::date AS day FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND ended_at IS NOT NULL ORDER BY day DESC`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	days := make([]string, 0)
	for rows.Next() {
		var day time.Time
		if err := rows.Scan(&day); err != nil {
			return nil, err
		}
		days = append(days, day.Format("2006-01-02"))
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if len(days) <= 2 {
		return days, nil
	}
	retained := days[:2]
	cutoff := retained[len(retained)-1] // oldest retained day
	_, err = tx.Exec(ctx, `DELETE FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND ended_at IS NOT NULL AND (started_at AT TIME ZONE 'UTC')::date < $2::date`, userID, cutoff)
	if err != nil {
		return nil, err
	}
	return retained, nil
}

func (a *app) historyImport(w http.ResponseWriter, r *http.Request, userID string, vehicleID int) {
	if r.Method != http.MethodPost {
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	request, err := importRequestFromBody(w, r)
	if err != nil {
		var validationErr *historyImportSessionValidationError
		if errors.As(err, &validationErr) {
			if validationErr.Message == "request_body_too_large" {
				a.json(w, http.StatusRequestEntityTooLarge, map[string]string{"error": validationErr.Message})
				return
			}
			a.json(w, http.StatusBadRequest, map[string]string{"error": validationErr.Message})
			return
		}
		a.json(w, http.StatusBadRequest, map[string]string{"error": "invalid_request"})
		return
	}
	result, err := a.telemetry.importHistory(r.Context(), userID, vehicleID, request)
	if err != nil {
		var validationErr *historyImportSessionValidationError
		if errors.As(err, &validationErr) {
			a.json(w, http.StatusBadRequest, map[string]string{"error": validationErr.Message})
			return
		}
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "history_import_failed"})
		return
	}
	a.json(w, http.StatusOK, map[string]any{"data": result})
}
