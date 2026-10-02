package main

import (
	"context"
	"errors"
	"net/http"
	"sort"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

// Metadata and list reads have separate contracts from full-resolution history.
// Never add route_json / charge_points_json to historyMetadataSQL or EXISTS.
const historyQueryTimeout = 5 * time.Second
const defaultHistoryPageSize = 50
const maxHistoryPageSize = 100

const historyMetadataSQL = `
SELECT COUNT(*) FILTER (WHERE quality_state <> 'quarantined'),
       COUNT(*) FILTER (WHERE quality_state IN ('observed','derived')),
       COUNT(*) FILTER (WHERE quality_state = 'quarantined'),
       MIN(started_at) FILTER (WHERE quality_state <> 'quarantined'),
       COALESCE((SELECT source FROM jourvolt_telemetry_sessions
           WHERE user_id=$1 AND vehicle_id=$2 AND kind=$3 AND ended_at IS NOT NULL
             AND quality_state <> 'quarantined' AND source <> ''
           ORDER BY started_at DESC, public_id DESC LIMIT 1), 'telemetry_mqtt'),
       COALESCE((SELECT CASE lower(btrim(source))
                  WHEN '' THEN 'telemetry_mqtt'
                  WHEN 'local_import' THEN 'local_history'
                  ELSE lower(btrim(source)) END
           FROM jourvolt_telemetry_sessions
           WHERE user_id=$1 AND vehicle_id=$2 AND kind=$3 AND ended_at IS NOT NULL
             AND quality_state <> 'quarantined'
             AND lower(btrim(source)) IN ('','local_import','local_history','telemetry_mqtt','fleet_api','teslamate_archive')
           ORDER BY started_at DESC, public_id DESC LIMIT 1), '')
FROM jourvolt_telemetry_sessions
WHERE user_id=$1 AND vehicle_id=$2 AND kind=$3 AND ended_at IS NOT NULL`

const historyExistsSQL = `SELECT EXISTS (
    SELECT 1 FROM jourvolt_telemetry_sessions
    WHERE user_id=$1 AND vehicle_id=$2 AND kind=$3
      AND ended_at IS NOT NULL AND quality_state <> 'quarantined')`

const historyPageCountSQL = `SELECT COUNT(*) FROM jourvolt_telemetry_sessions
    WHERE user_id=$1 AND vehicle_id=$2 AND kind=$3
      AND ended_at IS NOT NULL AND quality_state <> 'quarantined'
      AND ($4::timestamptz IS NULL OR started_at >= $4)
      AND ($5::timestamptz IS NULL OR started_at < $5)`

// Apply LIMIT before extracting JSON endpoints: only this page's large values
// may be accessed by PostgreSQL. No full route crosses the database connection.
// Persisted endpoint columns can remove even this bounded TOAST work later.
const historyPageSQL = `WITH page AS MATERIALIZED (
    SELECT id, public_id, started_at, ended_at, odometer_start, odometer_end, energy_added,
           source, quality_state, quality_reason, source_instance_id, source_vehicle_id, source_record_id,
           start_address, end_address, address, cost, route_json
    FROM jourvolt_telemetry_sessions
    WHERE user_id=$1 AND vehicle_id=$2 AND kind=$3
      AND ended_at IS NOT NULL AND quality_state <> 'quarantined'
      AND ($4::timestamptz IS NULL OR started_at >= $4)
      AND ($5::timestamptz IS NULL OR started_at < $5)
    ORDER BY started_at DESC, public_id DESC LIMIT $6 OFFSET $7
)
SELECT id, public_id, started_at, ended_at, odometer_start, odometer_end, energy_added,
       source, quality_state, quality_reason, COALESCE(source_instance_id,''), COALESCE(source_vehicle_id,''), COALESCE(source_record_id,''),
       start_address, end_address, address, cost,
       NULLIF(route_json->0->>'latitude','')::double precision,
       NULLIF(route_json->0->>'longitude','')::double precision,
       NULLIF(route_json->-1->>'latitude','')::double precision,
       NULLIF(route_json->-1->>'longitude','')::double precision
FROM page ORDER BY started_at DESC, public_id DESC`

type historyMetadata struct {
	Total, Qualified, Quarantined int
	StartedAt                     time.Time
	Source, ReadinessSource       string
}

type historyPageOptions struct {
	Page, Show int
	Start, End time.Time // inclusive start; exclusive end; zero means unbounded
}

func historyReadContext(ctx context.Context) (context.Context, context.CancelFunc) {
	if ctx == nil {
		ctx = context.Background()
	}
	return context.WithTimeout(ctx, historyQueryTimeout)
}

func validHistoryKind(kind string) bool { return kind == "drive" || kind == "charge" }

func (s *telemetryService) historyMetadata(ctx context.Context, userID string, vehicleID int, kind string) (historyMetadata, error) {
	ctx, cancel := historyReadContext(ctx)
	defer cancel()
	if err := ctx.Err(); err != nil {
		return historyMetadata{}, err
	}
	if !validHistoryKind(kind) {
		return historyMetadata{}, errors.New("invalid_history_kind")
	}
	if s == nil {
		return historyMetadata{}, nil
	}
	if s.memory != nil {
		s.memory.mu.Lock()
		defer s.memory.mu.Unlock()
		return metadataFromSessions(s.memory.completed[telemetryKey{UserID: userID, VehicleID: vehicleID}], kind), nil
	}
	if s.store == nil || s.store.pool == nil {
		return historyMetadata{}, errors.New("history_store_unavailable")
	}
	return queryHistoryMetadata(ctx, s.store.pool, userID, vehicleID, kind)
}

func queryHistoryMetadata(ctx context.Context, q queryer, userID string, vehicleID int, kind string) (historyMetadata, error) {
	var result historyMetadata
	var started *time.Time
	err := q.QueryRow(ctx, historyMetadataSQL, userID, vehicleID, kind).Scan(
		&result.Total, &result.Qualified, &result.Quarantined, &started, &result.Source, &result.ReadinessSource)
	if started != nil {
		result.StartedAt = *started
	}
	return result, err
}

func (s *telemetryService) historyExists(ctx context.Context, userID string, vehicleID int, kind string) (bool, error) {
	ctx, cancel := historyReadContext(ctx)
	defer cancel()
	if err := ctx.Err(); err != nil {
		return false, err
	}
	if !validHistoryKind(kind) {
		return false, errors.New("invalid_history_kind")
	}
	if s == nil {
		return false, nil
	}
	if s.memory != nil {
		s.memory.mu.Lock()
		defer s.memory.mu.Unlock()
		for _, session := range s.memory.completed[telemetryKey{UserID: userID, VehicleID: vehicleID}] {
			if session.Kind == kind && session.EndAt != nil && session.QualityState != "quarantined" {
				return true, nil
			}
		}
		return false, nil
	}
	if s.store == nil || s.store.pool == nil {
		return false, errors.New("history_store_unavailable")
	}
	var exists bool
	err := s.store.pool.QueryRow(ctx, historyExistsSQL, userID, vehicleID, kind).Scan(&exists)
	return exists, err
}

func normalizedHistorySource(source string) string {
	switch value := strings.ToLower(strings.TrimSpace(source)); value {
	case "":
		return "telemetry_mqtt"
	case "local_import":
		return "local_history"
	case "local_history", "telemetry_mqtt", "fleet_api", "teslamate_archive":
		return value
	default:
		return ""
	}
}

// Caller holds the memory-store lock. Inspect headers only, without cloning
// routes or constructing historySessionMap. This also makes memory tests honest.
func metadataFromSessions(sessions []telemetrySession, kind string) historyMetadata {
	out := historyMetadata{Source: "telemetry_mqtt"}
	var newest, newestRecognized *telemetrySession
	for index := range sessions {
		row := &sessions[index]
		if row.Kind != kind || row.EndAt == nil {
			continue
		}
		if row.QualityState == "quarantined" {
			out.Quarantined++
			continue
		}
		out.Total++
		if row.QualityState == "observed" || row.QualityState == "derived" {
			out.Qualified++
		}
		if out.StartedAt.IsZero() || row.StartAt.Before(out.StartedAt) {
			out.StartedAt = row.StartAt
		}
		newer := func(old *telemetrySession) bool {
			return old == nil || row.StartAt.After(old.StartAt) || (row.StartAt.Equal(old.StartAt) && row.PublicID > old.PublicID)
		}
		if row.Source != "" && newer(newest) {
			newest = row
			out.Source = row.Source
		}
		if source := normalizedHistorySource(row.Source); source != "" && newer(newestRecognized) {
			newestRecognized = row
			out.ReadinessSource = source
		}
	}
	return out
}

func historyPageOptionsFromRequest(r *http.Request) historyPageOptions {
	return normalizeHistoryPageOptions(historyPageOptions{
		Page: positiveQueryInt(r, "page", 1), Show: positiveQueryInt(r, "show", defaultHistoryPageSize),
		Start: parseHistoryBoundary(r.URL.Query().Get("startDate"), false),
		End:   parseHistoryBoundary(r.URL.Query().Get("endDate"), true),
	})
}

func normalizeHistoryPageOptions(options historyPageOptions) historyPageOptions {
	if options.Page < 1 {
		options.Page = 1
	}
	if options.Show < 1 {
		options.Show = defaultHistoryPageSize
	}
	if options.Show > maxHistoryPageSize {
		options.Show = maxHistoryPageSize
	}
	return options
}

func historyPageMeta(metadata historyMetadata, total int, options historyPageOptions) map[string]any {
	meta := map[string]any{"availability": "collecting", "source": metadata.Source,
		"quarantined_count": metadata.Quarantined, "page": options.Page, "show": options.Show,
		"total": total, "total_pages": pageCount(total, options.Show)}
	// Preserve the existing all-history coverage/collection scope, independently
	// of the requested page/date range. A blank page does not erase the archive.
	if metadata.Total > 0 {
		meta["availability"] = "available"
		meta["coverage_percent"] = float64(metadata.Qualified) * 100 / float64(metadata.Total)
		meta["collection_started_at"] = metadata.StartedAt.UTC().Format(time.RFC3339)
	}
	return meta
}

func (s *telemetryService) historyPage(ctx context.Context, userID string, vehicleID int, kind string, options historyPageOptions) ([]map[string]any, map[string]any, error) {
	ctx, cancel := historyReadContext(ctx)
	defer cancel()
	if err := ctx.Err(); err != nil {
		return nil, nil, err
	}
	if !validHistoryKind(kind) {
		return nil, nil, errors.New("invalid_history_kind")
	}
	options = normalizeHistoryPageOptions(options)
	if s == nil {
		return []map[string]any{}, historyPageMeta(historyMetadata{Source: "telemetry_mqtt"}, 0, options), nil
	}
	if s.memory != nil {
		return s.memoryHistoryPage(ctx, userID, vehicleID, kind, options)
	}
	if s.store == nil || s.store.pool == nil {
		return nil, nil, errors.New("history_store_unavailable")
	}
	// Count and page use one snapshot, so concurrent archive writes cannot make
	// this single response internally inconsistent. Cross-request cursors remain future work.
	tx, err := s.store.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.RepeatableRead, AccessMode: pgx.ReadOnly})
	if err != nil {
		return nil, nil, err
	}
	defer tx.Rollback(ctx)
	metadata, err := queryHistoryMetadata(ctx, tx, userID, vehicleID, kind)
	if err != nil {
		return nil, nil, err
	}
	var start, end any
	if !options.Start.IsZero() {
		start = options.Start
	}
	if !options.End.IsZero() {
		end = options.End
	}
	total := metadata.Total
	if start != nil || end != nil {
		if err := tx.QueryRow(ctx, historyPageCountSQL, userID, vehicleID, kind, start, end).Scan(&total); err != nil {
			return nil, nil, err
		}
	}
	items := []map[string]any{}
	// Check against total BEFORE multiplication: hostile large page integers
	// cannot overflow into a negative SQL OFFSET or a slice panic.
	if total > 0 && options.Page-1 <= (total-1)/options.Show {
		offset := (options.Page - 1) * options.Show
		rows, err := tx.Query(ctx, historyPageSQL, userID, vehicleID, kind, start, end, options.Show, offset)
		if err != nil {
			return nil, nil, err
		}
		for rows.Next() {
			var row telemetrySession
			var lat1, lon1, lat2, lon2 *float64
			err := rows.Scan(&row.ID, &row.PublicID, &row.StartAt, &row.EndAt, &row.OdometerStart, &row.OdometerEnd, &row.EnergyAdded,
				&row.Source, &row.QualityState, &row.QualityReason, &row.SourceInstanceID, &row.SourceVehicleID, &row.SourceRecordID,
				&row.StartAddress, &row.EndAddress, &row.Address, &row.Cost, &lat1, &lon1, &lat2, &lon2)
			if err != nil {
				rows.Close()
				return nil, nil, err
			}
			row.Kind = kind
			addSummaryEndpoints(&row, lat1, lon1, lat2, lon2)
			items = append(items, historySessionMap(row, kind, offset+len(items)))
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return nil, nil, err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, nil, err
	}
	summarizeTelemetryHistory(items, kind)
	return items, historyPageMeta(metadata, total, options), nil
}

func addSummaryEndpoints(row *telemetrySession, lat1, lon1, lat2, lon2 *float64) {
	if lat1 != nil && lon1 != nil {
		row.Route = append(row.Route, telemetryRoutePoint{ObservedAt: row.StartAt, Latitude: *lat1, Longitude: *lon1})
	}
	if lat2 != nil && lon2 != nil {
		end := row.StartAt
		if row.EndAt != nil {
			end = *row.EndAt
		}
		row.Route = append(row.Route, telemetryRoutePoint{ObservedAt: end, Latitude: *lat2, Longitude: *lon2})
	}
}

func (s *telemetryService) memoryHistoryPage(ctx context.Context, userID string, vehicleID int, kind string, options historyPageOptions) ([]map[string]any, map[string]any, error) {
	s.memory.mu.Lock()
	defer s.memory.mu.Unlock()
	all := s.memory.completed[telemetryKey{UserID: userID, VehicleID: vehicleID}]
	metadata := metadataFromSessions(all, kind)
	selected := make([]int, 0)
	for i, row := range all {
		if row.Kind == kind && row.EndAt != nil && row.QualityState != "quarantined" &&
			(options.Start.IsZero() || !row.StartAt.Before(options.Start)) && (options.End.IsZero() || row.StartAt.Before(options.End)) {
			selected = append(selected, i)
		}
	}
	sort.Slice(selected, func(i, j int) bool {
		a, b := all[selected[i]], all[selected[j]]
		if a.StartAt.Equal(b.StartAt) {
			return a.PublicID > b.PublicID
		}
		return a.StartAt.After(b.StartAt)
	})
	total := len(selected)
	items := []map[string]any{}
	if total > 0 && options.Page-1 <= (total-1)/options.Show {
		start := (options.Page - 1) * options.Show
		end := start + options.Show
		if end > total {
			end = total
		}
		for _, index := range selected[start:end] {
			if err := ctx.Err(); err != nil {
				return nil, nil, err
			}
			original := all[index]
			row := original // headers only, never clone the archive
			row.Route = nil
			row.ArchiveRoute = nil
			row.ChargePoints = nil
			if len(original.Route) > 0 {
				first, last := original.Route[0], original.Route[len(original.Route)-1]
				addSummaryEndpoints(&row, &first.Latitude, &first.Longitude, &last.Latitude, &last.Longitude)
			} else if len(original.ArchiveRoute) > 0 {
				first, last := original.ArchiveRoute[0], original.ArchiveRoute[len(original.ArchiveRoute)-1]
				addSummaryEndpoints(&row, first.Latitude, first.Longitude, last.Latitude, last.Longitude)
			}
			items = append(items, historySessionMap(row, kind, start+len(items)))
		}
	}
	summarizeTelemetryHistory(items, kind)
	return items, historyPageMeta(metadata, total, options), nil
}
