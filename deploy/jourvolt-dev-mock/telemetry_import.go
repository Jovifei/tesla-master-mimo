package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"sort"
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

var chinaDataLocation = func() *time.Location {
	location, err := time.LoadLocation("Asia/Shanghai")
	if err != nil {
		return time.FixedZone("Asia/Shanghai", 8*60*60)
	}
	return location
}()

func scopedImportedSessionID(userID string, vehicleID int, kind, clientSessionID string) string {
	return scopedSessionID("local_import", userID, vehicleID, kind, clientSessionID)
}

func scopedArchiveSessionID(userID string, vehicleID int, kind, sourceInstanceID, sourceVehicleID, sourceRecordID string) string {
	return scopedSessionID("teslamate_archive", userID, vehicleID, kind, sourceInstanceID+"\x00"+sourceVehicleID+"\x00"+sourceRecordID)
}

func scopedSessionID(prefix, userID string, vehicleID int, kind, clientSessionID string) string {
	h := sha256.New()
	h.Write([]byte(userID))
	h.Write([]byte{0})
	h.Write([]byte(strconv.Itoa(vehicleID)))
	h.Write([]byte{0})
	h.Write([]byte(kind))
	h.Write([]byte{0})
	h.Write([]byte(clientSessionID))
	return prefix + ":" + hex.EncodeToString(h.Sum(nil))
}

// historyImportRequest is the payload for importing previously-collected local
// history into the cloud. The app uploads the history it already has on the
// phone (collected while self-hosted or before switching to a cloud account).
type historyImportRequest struct {
	Source           string                 `json:"source,omitempty"`
	SourceInstanceID string                 `json:"source_instance_id,omitempty"`
	SourceVehicleID  string                 `json:"source_vehicle_id,omitempty"`
	ChunkID          string                 `json:"chunk_id,omitempty"`
	Drives           []historyImportSession `json:"drives"`
	Charges          []historyImportSession `json:"charges"`
}

// historyImportSession is a single completed drive/charge record, including its
// full trajectory points so the cloud can reproduce the route on re-download.
type historyImportSession struct {
	SessionID      string                     `json:"session_id"`
	SourceRecordID string                     `json:"source_record_id,omitempty"`
	StartedAt      string                     `json:"started_at"`
	EndedAt        string                     `json:"ended_at"`
	OdometerStart  *float64                   `json:"odometer_start"`
	OdometerEnd    *float64                   `json:"odometer_end"`
	EnergyAdded    *float64                   `json:"energy_added"`
	StartAddress   *string                    `json:"start_address,omitempty"`
	EndAddress     *string                    `json:"end_address,omitempty"`
	Address        *string                    `json:"address,omitempty"`
	Cost           *float64                   `json:"cost,omitempty"`
	Route          []historyImportRoutePoint  `json:"route"`
	ChargePoints   []historyImportChargePoint `json:"charge_points,omitempty"`
}

type historyImportRoutePoint struct {
	Date      string   `json:"date"`
	Latitude  *float64 `json:"latitude"`
	Longitude *float64 `json:"longitude"`
	Speed     *float64 `json:"speed"`
	Power     *float64 `json:"power"`
	Heading   *float64 `json:"heading"`
}

type historyImportChargePoint struct {
	Date              string   `json:"date"`
	BatteryLevel      *int     `json:"battery_level"`
	EnergyAdded       *float64 `json:"energy_added"`
	ChargeEnergyAdded *float64 `json:"charge_energy_added"`
	ChargerPower      *float64 `json:"charger_power"`
	Latitude          *float64 `json:"latitude"`
	Longitude         *float64 `json:"longitude"`
}

type historyImportResult struct {
	ImportedDrives   int      `json:"imported_drives"`
	ImportedCharges  int      `json:"imported_charges"`
	RetainedDays     []string `json:"retained_days"`
	QuarantinedCount int      `json:"quarantined_count"`
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

func validateArchiveImportRequest(req historyImportRequest) error {
	if req.Source != "teslamate" || strings.TrimSpace(req.SourceInstanceID) == "" || strings.TrimSpace(req.SourceVehicleID) == "" {
		return &historyImportSessionValidationError{Message: "archive_source_binding_required"}
	}
	if strings.TrimSpace(req.ChunkID) == "" {
		return &historyImportSessionValidationError{Message: "archive_chunk_id_required"}
	}
	for _, item := range append(append([]historyImportSession{}, req.Drives...), req.Charges...) {
		if strings.TrimSpace(item.SourceRecordID) == "" {
			return &historyImportSessionValidationError{Message: "archive_source_record_id_required"}
		}
	}
	return validateImportRequest(req)
}

func importRequestFromBody(w http.ResponseWriter, r *http.Request) (historyImportRequest, error) {
	reader := http.MaxBytesReader(w, r.Body, maxImportBodyBytes)
	decoder := json.NewDecoder(reader)
	var request historyImportRequest
	if err := decoder.Decode(&request); err != nil {
		var maxBytesErr *http.MaxBytesError
		if errors.As(err, &maxBytesErr) || strings.Contains(err.Error(), "request body too large") {
			return historyImportRequest{}, &historyImportSessionValidationError{Message: "request_body_too_large"}
		}
		return historyImportRequest{}, &historyImportSessionValidationError{Message: "invalid_json"}
	}
	var trailing any
	if err := decoder.Decode(&trailing); err == nil {
		return historyImportRequest{}, &historyImportSessionValidationError{Message: "trailing_json"}
	} else if !errors.Is(err, io.EOF) {
		return historyImportRequest{}, &historyImportSessionValidationError{Message: "invalid_json"}
	}
	if err := validateImportRequest(request); err != nil {
		return historyImportRequest{}, err
	}
	return request, nil
}

func (req historyImportSession) toTelemetrySession(userID string, vehicleID int, kind string, sourceArgs ...string) (telemetrySession, error) {
	source, sourceInstanceID, sourceVehicleID := "", "", ""
	if len(sourceArgs) > 0 {
		source = sourceArgs[0]
	}
	if len(sourceArgs) > 1 {
		sourceInstanceID = sourceArgs[1]
	}
	if len(sourceArgs) > 2 {
		sourceVehicleID = sourceArgs[2]
	}
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
	if source == "teslamate" {
		rawID = sourceInstanceID + "\x00" + sourceVehicleID + "\x00" + req.SourceRecordID
	}
	if rawID == "" {
		rawID = sessionID(kind, start)
	}
	id := scopedImportedSessionID(userID, vehicleID, kind, rawID)
	if source == "teslamate" {
		id = scopedArchiveSessionID(userID, vehicleID, kind, sourceInstanceID, sourceVehicleID, req.SourceRecordID)
	}
	route := make([]telemetryRoutePoint, 0, len(req.Route))
	for _, point := range req.Route {
		if point.Latitude == nil || point.Longitude == nil {
			continue
		}
		if *point.Latitude < -90 || *point.Latitude > 90 || *point.Longitude < -180 || *point.Longitude > 180 || (*point.Latitude == 0 && *point.Longitude == 0) {
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
		Route: route, ArchiveRoute: append([]historyImportRoutePoint(nil), req.Route...), ChargePoints: importChargePoints(req.ChargePoints, start),
		Source:           map[bool]string{true: "teslamate_archive", false: "local_import"}[source == "teslamate"],
		QualityState:     map[bool]string{true: "observed", false: "incomplete"}[source == "teslamate"],
		QualityReason:    map[bool]string{true: "teslamate_archive", false: "local_import_unverified"}[source == "teslamate"],
		SourceInstanceID: sourceInstanceID, SourceVehicleID: sourceVehicleID, SourceRecordID: req.SourceRecordID,
		StartAddress: req.StartAddress, EndAddress: req.EndAddress, Address: req.Address, Cost: req.Cost,
	}, nil
}

func importChargePoints(points []historyImportChargePoint, start time.Time) []telemetryChargePoint {
	result := make([]telemetryChargePoint, 0, len(points))
	for _, point := range points {
		observedAt := start
		if point.Date != "" {
			if parsed, err := time.Parse(time.RFC3339, point.Date); err == nil {
				observedAt = parsed
			}
		}
		energy := point.EnergyAdded
		if energy == nil {
			energy = point.ChargeEnergyAdded
		}
		result = append(result, telemetryChargePoint{
			ObservedAt: observedAt, BatteryLevel: point.BatteryLevel, EnergyAdded: energy,
			ChargerPower: point.ChargerPower, Latitude: point.Latitude, Longitude: point.Longitude,
		})
	}
	return result
}

// importHistory persists locally-collected history into the cloud store.
func (s *telemetryService) importHistory(ctx context.Context, userID string, vehicleID int, request historyImportRequest) (historyImportResult, error) {
	if s == nil {
		return historyImportResult{}, errors.New("telemetry_not_configured")
	}
	drives := make([]telemetrySession, 0, len(request.Drives))
	for _, item := range request.Drives {
		session, err := item.toTelemetrySession(userID, vehicleID, "drive", request.Source, request.SourceInstanceID, request.SourceVehicleID)
		if err != nil {
			return historyImportResult{}, err
		}
		drives = append(drives, session)
	}
	charges := make([]telemetrySession, 0, len(request.Charges))
	for _, item := range request.Charges {
		session, err := item.toTelemetrySession(userID, vehicleID, "charge", request.Source, request.SourceInstanceID, request.SourceVehicleID)
		if err != nil {
			return historyImportResult{}, err
		}
		charges = append(charges, session)
	}
	if s.memory != nil {
		s.memory.importSessions(userID, vehicleID, drives, charges)
		return historyImportResult{ImportedDrives: len(drives), ImportedCharges: len(charges)}, nil
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
	if err := tx.Commit(ctx); err != nil {
		return historyImportResult{}, err
	}
	return historyImportResult{ImportedDrives: importedDrives, ImportedCharges: importedCharges}, nil
}

func upsertImportedSessionPostgres(ctx context.Context, tx pgx.Tx, userID string, vehicleID int, session telemetrySession) error {
	// Serialize even the first insert for this scoped ID. No production deletion.
	if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1, 0))`, scopedImportedSessionID(userID, vehicleID, session.Kind, session.StartAt.UTC().Format(time.RFC3339Nano))); err != nil {
		return err
	}
	var old telemetrySession
	var owner string
	var car int
	var oldRoute []byte
	var oldChargePoints []byte
	lookup := `SELECT id, public_id, user_id, vehicle_id, kind, started_at, ended_at,
        odometer_start, odometer_end, energy_added, route_json, charge_points_json, source, quality_state, quality_reason,
        COALESCE(source_instance_id,''), COALESCE(source_vehicle_id,''), COALESCE(source_record_id,''), start_address, end_address, address, cost
        FROM jourvolt_telemetry_sessions
        WHERE id=$1 OR (user_id=$2 AND vehicle_id=$3 AND kind=$4 AND started_at=$5)
		ORDER BY (id=$1) DESC LIMIT 1 FOR UPDATE`
	args := []any{session.ID, userID, vehicleID, session.Kind, session.StartAt}
	if session.Source == "teslamate_archive" {
		lookup = strings.Replace(lookup, "WHERE id=$1 OR (user_id=$2 AND vehicle_id=$3 AND kind=$4 AND started_at=$5)\n\t\tORDER BY (id=$1) DESC", "WHERE id=$1\n\t\tORDER BY id", 1)
		args = []any{session.ID}
	}
	err := tx.QueryRow(ctx, lookup, args...).Scan(
		&old.ID, &old.PublicID, &owner, &car, &old.Kind, &old.StartAt, &old.EndAt,
		&old.OdometerStart, &old.OdometerEnd, &old.EnergyAdded, &oldRoute, &oldChargePoints, &old.Source, &old.QualityState, &old.QualityReason,
		&old.SourceInstanceID, &old.SourceVehicleID, &old.SourceRecordID, &old.StartAddress, &old.EndAddress, &old.Address, &old.Cost)
	if err == nil {
		if owner != userID || car != vehicleID {
			return errors.New("history_identity_mismatch")
		}
		if (old.Source != "local_import" && old.Source != "teslamate_archive") || old.QualityState == "quarantined" {
			return nil // Do not rewrite native or quarantined records from a local import.
		}
		if old.Kind != session.Kind || !old.StartAt.Equal(session.StartAt) {
			return errors.New("history_session_identity_mismatch")
		}
		if old.Source == "teslamate_archive" {
			if err = json.Unmarshal(oldRoute, &old.ArchiveRoute); err != nil {
				return err
			}
			for _, point := range old.ArchiveRoute {
				if point.Latitude == nil || point.Longitude == nil || point.Date == "" {
					continue
				}
				observedAt, parseErr := time.Parse(time.RFC3339, point.Date)
				if parseErr != nil {
					continue
				}
				old.Route = append(old.Route, telemetryRoutePoint{ObservedAt: observedAt, Latitude: *point.Latitude, Longitude: *point.Longitude, Speed: point.Speed, Power: point.Power, Heading: point.Heading})
			}
		} else if err = json.Unmarshal(oldRoute, &old.Route); err != nil {
			return err
		}
		if err = json.Unmarshal(oldChargePoints, &old.ChargePoints); err != nil {
			return err
		}
		session = mergeImportedSession(session, old)
	} else if !errors.Is(err, pgx.ErrNoRows) {
		return err
	}
	routeValue := any(session.Route)
	if session.Source == "teslamate_archive" && session.ArchiveRoute != nil {
		routeValue = session.ArchiveRoute
	}
	route, err := json.Marshal(routeValue)
	if err != nil {
		return err
	}
	chargePoints, err := json.Marshal(session.ChargePoints)
	if err != nil {
		return err
	}
	var endAt *time.Time = session.EndAt
	if endAt == nil {
		now := time.Now().UTC()
		endAt = &now
	}
	_, err = tx.Exec(ctx, `
INSERT INTO jourvolt_telemetry_sessions(id, user_id, vehicle_id, kind, started_at, ended_at, odometer_start, odometer_end, energy_added, route_json, charge_points_json, source, quality_state, quality_reason, source_instance_id, source_vehicle_id, source_record_id, start_address, end_address, address, cost)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10::jsonb, $11::jsonb, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21)
ON CONFLICT (id) DO UPDATE SET
    ended_at = EXCLUDED.ended_at,
    odometer_start = EXCLUDED.odometer_start,
    odometer_end = EXCLUDED.odometer_end,
    energy_added = EXCLUDED.energy_added,
    route_json = EXCLUDED.route_json,
    charge_points_json = EXCLUDED.charge_points_json,
    source = EXCLUDED.source, quality_state = EXCLUDED.quality_state, quality_reason = EXCLUDED.quality_reason,
    source_instance_id = EXCLUDED.source_instance_id, source_vehicle_id = EXCLUDED.source_vehicle_id, source_record_id = EXCLUDED.source_record_id,
    start_address = EXCLUDED.start_address, end_address = EXCLUDED.end_address, address = EXCLUDED.address, cost = EXCLUDED.cost
WHERE jourvolt_telemetry_sessions.user_id = EXCLUDED.user_id
  AND jourvolt_telemetry_sessions.vehicle_id = EXCLUDED.vehicle_id
  AND jourvolt_telemetry_sessions.kind = EXCLUDED.kind
  AND jourvolt_telemetry_sessions.started_at = EXCLUDED.started_at
  AND jourvolt_telemetry_sessions.source IN ('local_import', 'teslamate_archive')
  AND jourvolt_telemetry_sessions.quality_state <> 'quarantined'`,
		session.ID, userID, vehicleID, session.Kind, session.StartAt, endAt,
		session.OdometerStart, session.OdometerEnd, session.EnergyAdded, route, chargePoints,
		session.Source, session.QualityState, session.QualityReason,
		session.SourceInstanceID, session.SourceVehicleID, session.SourceRecordID,
		session.StartAddress, session.EndAddress, session.Address, session.Cost)
	return err
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
	// The legacy endpoint intentionally ignores archive-only envelope fields.
	// Archive callers must use /history/archive/import so old clients cannot
	// change source provenance or quality semantics by adding new JSON fields.
	request.Source, request.SourceInstanceID, request.SourceVehicleID, request.ChunkID = "", "", "", ""
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

func (a *app) historyArchiveImport(w http.ResponseWriter, r *http.Request, userID string, vehicleID int) {
	// Keep the archive path behind the binding-only authentication boundary even
	// when a caller reaches this helper directly from an internal route.
	a.archiveBindingResource(w, r, userID)
}

// mergeImportedSession preserves absent fields and all previously received points.
// A duplicate client ID cannot promote an import or overwrite native/quarantined evidence.
func mergeImportedSession(incoming, cached telemetrySession) telemetrySession {
	if cached.Source != "local_import" || cached.QualityState == "quarantined" ||
		cached.Kind != incoming.Kind || !cached.StartAt.Equal(incoming.StartAt) {
		return *cloneTelemetrySession(&cached)
	}
	merged := *cloneTelemetrySession(&incoming)
	merged.ID = cached.ID
	merged.PublicID = cached.PublicID
	if merged.SourceInstanceID == "" {
		merged.SourceInstanceID = cached.SourceInstanceID
	}
	if merged.SourceVehicleID == "" {
		merged.SourceVehicleID = cached.SourceVehicleID
	}
	if merged.SourceRecordID == "" {
		merged.SourceRecordID = cached.SourceRecordID
	}
	if merged.StartAddress == nil {
		merged.StartAddress = cached.StartAddress
	}
	if merged.EndAddress == nil {
		merged.EndAddress = cached.EndAddress
	}
	if merged.Address == nil {
		merged.Address = cached.Address
	}
	if merged.Cost == nil {
		merged.Cost = cached.Cost
	}
	if len(merged.ArchiveRoute) == 0 {
		merged.ArchiveRoute = append([]historyImportRoutePoint(nil), cached.ArchiveRoute...)
	}
	if merged.OdometerStart == nil {
		merged.OdometerStart = cached.OdometerStart
	}
	if merged.OdometerEnd == nil {
		merged.OdometerEnd = cached.OdometerEnd
	}
	if merged.EnergyAdded == nil {
		merged.EnergyAdded = cached.EnergyAdded
	}
	if merged.EndAt == nil {
		merged.EndAt = cached.EndAt
	}
	type pointKey struct {
		date                string
		latitude, longitude float64
	}
	points := make([]telemetryRoutePoint, 0, len(cached.Route)+len(incoming.Route))
	indices := make(map[pointKey]int)
	for _, point := range append(append([]telemetryRoutePoint(nil), cached.Route...), incoming.Route...) {
		key := pointKey{point.ObservedAt.UTC().Format(time.RFC3339Nano), point.Latitude, point.Longitude}
		if index, found := indices[key]; found {
			old := points[index]
			if point.Speed == nil {
				point.Speed = old.Speed
			}
			if point.Power == nil {
				point.Power = old.Power
			}
			if point.Heading == nil {
				point.Heading = old.Heading
			}
			points[index] = point
		} else if len(points) < maxImportRoutePointsPerItem || len(points) < len(cached.Route) {
			indices[key] = len(points)
			points = append(points, point)
		}
	}
	sort.SliceStable(points, func(i, j int) bool { return points[i].ObservedAt.Before(points[j].ObservedAt) })
	merged.Route = points
	chargePoints := make([]telemetryChargePoint, 0, len(cached.ChargePoints)+len(incoming.ChargePoints))
	chargeKeys := make(map[string]int)
	for _, point := range append(append([]telemetryChargePoint(nil), cached.ChargePoints...), incoming.ChargePoints...) {
		key := point.ObservedAt.UTC().Format(time.RFC3339Nano)
		if index, found := chargeKeys[key]; found {
			old := chargePoints[index]
			if point.BatteryLevel == nil {
				point.BatteryLevel = old.BatteryLevel
			}
			if point.EnergyAdded == nil {
				point.EnergyAdded = old.EnergyAdded
			}
			if point.ChargerPower == nil {
				point.ChargerPower = old.ChargerPower
			}
			if point.Latitude == nil {
				point.Latitude = old.Latitude
			}
			if point.Longitude == nil {
				point.Longitude = old.Longitude
			}
			chargePoints[index] = point
		} else {
			chargeKeys[key] = len(chargePoints)
			chargePoints = append(chargePoints, point)
		}
	}
	sort.SliceStable(chargePoints, func(i, j int) bool { return chargePoints[i].ObservedAt.Before(chargePoints[j].ObservedAt) })
	merged.ChargePoints = chargePoints
	return merged
}
