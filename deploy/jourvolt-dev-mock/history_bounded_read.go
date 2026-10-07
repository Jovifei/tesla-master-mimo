package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

const boundedHistoryReadTimeout = 5 * time.Second

type boundedHistoryMetadata struct {
	Total       int
	Qualified   int
	Quarantined int
	StartedAt   *time.Time
	Source      string
}

type boundedHistoryPageRow struct {
	Session           telemetrySession
	StartBatteryLevel *int
	EndBatteryLevel   *int
	Metrics           historySampleMetrics
	Sequence          int
}

type historyContextReader struct {
	ctx context.Context
	r   *bytes.Reader
}

func (r *historyContextReader) Read(p []byte) (int, error) {
	if err := r.ctx.Err(); err != nil {
		return 0, err
	}
	return r.r.Read(p)
}

func decodeHistoryJSONContext(ctx context.Context, data []byte, target any) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	decoder := json.NewDecoder(&historyContextReader{ctx: ctx, r: bytes.NewReader(data)})
	if err := decoder.Decode(target); err != nil {
		return err
	}
	return ctx.Err()
}

func boundedHistoryContext(ctx context.Context) (context.Context, context.CancelFunc) {
	if ctx == nil {
		ctx = context.Background()
	}
	return context.WithTimeout(ctx, boundedHistoryReadTimeout)
}

func boundedHistoryBoundary(value time.Time) any {
	if value.IsZero() {
		return nil
	}
	return value
}

func (s *telemetryService) historyMetadataBoundedPostgres(ctx context.Context, userID string, vehicleID int, kind string) (boundedHistoryMetadata, error) {
	var meta boundedHistoryMetadata
	if s == nil || s.store == nil || s.store.pool == nil {
		meta.Source = "telemetry_mqtt"
		return meta, nil
	}
	err := s.store.pool.QueryRow(ctx, `
SELECT COUNT(*) FILTER (WHERE quality_state <> 'quarantined'),
       COUNT(*) FILTER (WHERE quality_state IN ('observed','derived')),
       COUNT(*) FILTER (WHERE quality_state = 'quarantined'),
       MIN(started_at) FILTER (WHERE quality_state <> 'quarantined'),
       COALESCE((
           SELECT source FROM jourvolt_telemetry_sessions
           WHERE user_id=$1 AND vehicle_id=$2 AND kind=$3
             AND ended_at IS NOT NULL AND quality_state <> 'quarantined'
             AND source <> ''
           ORDER BY started_at DESC, public_id DESC
           LIMIT 1
       ), 'telemetry_mqtt')
FROM jourvolt_telemetry_sessions
WHERE user_id=$1 AND vehicle_id=$2 AND kind=$3 AND ended_at IS NOT NULL
`, userID, vehicleID, kind).Scan(
		&meta.Total, &meta.Qualified, &meta.Quarantined, &meta.StartedAt, &meta.Source,
	)
	return meta, err
}

func (s *telemetryService) historyFilteredCountPostgres(ctx context.Context, userID string, vehicleID int, kind string, start, end time.Time) (int, error) {
	if s == nil || s.store == nil || s.store.pool == nil {
		return 0, nil
	}
	var count int
	err := s.store.pool.QueryRow(ctx, `
SELECT COUNT(*) FROM jourvolt_telemetry_sessions
WHERE user_id=$1 AND vehicle_id=$2 AND kind=$3
  AND ended_at IS NOT NULL AND quality_state <> 'quarantined'
  AND ($4::timestamptz IS NULL OR started_at >= $4)
  AND ($5::timestamptz IS NULL OR started_at < $5)
`, userID, vehicleID, kind, boundedHistoryBoundary(start), boundedHistoryBoundary(end)).Scan(&count)
	return count, err
}

// position is a fixed internal SQL column, never request input. Only the two
// selected SOC scalars cross the DB connection; no array_agg of all SOC values.
func historySOCAtPosition(position string) string {
	point := "(CASE WHEN s.kind='charge' THEN s.charge_points_json ELSE s.route_json END)->((" + position + "-1)::integer)"
	return "(CASE WHEN s.kind='charge' THEN COALESCE(NULLIF((" + point + ")->'BatteryLevel','null'::jsonb),(" + point + ")->'battery_level') WHEN s.source='teslamate_archive' THEN (" + point + ")->'battery_level' ELSE (" + point + ")->'BatteryLevel' END #>> '{}')::integer"
}

func (s *telemetryService) historyPagePostgresBounded(ctx context.Context, userID string, vehicleID int, kind string, start, end time.Time, limit, offset int) ([]boundedHistoryPageRow, error) {
	if s == nil || s.store == nil || s.store.pool == nil || limit <= 0 {
		return []boundedHistoryPageRow{}, nil
	}
	rows, err := s.store.pool.Query(ctx, `
WITH ranked AS (
    SELECT id, started_at, public_id,
           ROW_NUMBER() OVER (ORDER BY started_at DESC, public_id DESC) - 1 AS sequence
    FROM jourvolt_telemetry_sessions
    WHERE user_id=$1 AND vehicle_id=$2 AND kind=$3
      AND ended_at IS NOT NULL AND quality_state <> 'quarantined'
), page_ids AS MATERIALIZED (
    SELECT id, started_at, public_id, sequence
    FROM ranked
    WHERE ($4::timestamptz IS NULL OR started_at >= $4)
      AND ($5::timestamptz IS NULL OR started_at < $5)
    ORDER BY started_at DESC, public_id DESC
    LIMIT $6 OFFSET $7
)
SELECT s.id, s.public_id, s.started_at, s.ended_at, s.odometer_start, s.odometer_end, s.energy_added,
       s.source, s.quality_state, s.quality_reason,
       COALESCE(s.source_instance_id,''), COALESCE(s.source_vehicle_id,''), COALESCE(s.source_record_id,''),
       s.start_address, s.end_address, s.address, s.cost,
       NULLIF(s.route_json->0->>'latitude','')::double precision,
       NULLIF(s.route_json->0->>'longitude','')::double precision,
       NULLIF(s.route_json->(jsonb_array_length(s.route_json)-1)->>'latitude','')::double precision,
       NULLIF(s.route_json->(jsonb_array_length(s.route_json)-1)->>'longitude','')::double precision,
       `+historySOCAtPosition("observed.first_position")+`,
       `+historySOCAtPosition("observed.last_position")+`,
       observed.speed_max, observed.speed_avg, observed.speed_count,
       observed.inside_temp_avg, observed.outside_temp_avg,
       p.sequence
FROM page_ids p
JOIN jourvolt_telemetry_sessions s ON s.id=p.id
LEFT JOIN LATERAL (
    SELECT MIN(ordinality) FILTER (WHERE level BETWEEN 0 AND 100) AS first_position,
           MAX(ordinality) FILTER (WHERE level BETWEEN 0 AND 100) AS last_position,
           MAX(speed) AS speed_max, AVG(speed) AS speed_avg, COUNT(speed) AS speed_count,
           AVG(inside_temp) AS inside_temp_avg, AVG(outside_temp) AS outside_temp_avg
    FROM (
        SELECT ordinality,
          CASE WHEN jsonb_typeof(soc)='number' AND soc::text ~ '^[0-9]{1,3}$' THEN (soc::text)::integer END AS level,
          CASE WHEN jsonb_typeof(speed)='number' THEN CASE WHEN (speed::text)::numeric BETWEEN 0 AND 2147483647 THEN (speed::text)::double precision END END AS speed,
          CASE WHEN jsonb_typeof(inside_temp)='number' THEN CASE WHEN (inside_temp::text)::numeric BETWEEN -1e308 AND 1e308 THEN (inside_temp::text)::double precision END END AS inside_temp,
          CASE WHEN jsonb_typeof(outside_temp)='number' THEN CASE WHEN (outside_temp::text)::numeric BETWEEN -1e308 AND 1e308 THEN (outside_temp::text)::double precision END END AS outside_temp
        FROM (
          SELECT point.ordinality,
            CASE WHEN s.kind='charge' THEN COALESCE(NULLIF(point.value->'BatteryLevel','null'::jsonb),point.value->'battery_level')
                 WHEN s.source='teslamate_archive' THEN point.value->'battery_level' ELSE point.value->'BatteryLevel' END AS soc,
            CASE WHEN s.kind='drive' THEN CASE WHEN s.source='teslamate_archive' THEN point.value->'speed' ELSE point.value->'Speed' END END AS speed,
            CASE WHEN s.kind='drive' THEN COALESCE(NULLIF(point.value->'climate_info'->'inside_temp','null'::jsonb),NULLIF(point.value->'inside_temp','null'::jsonb),point.value->'InsideTemp') END AS inside_temp,
            COALESCE(NULLIF(point.value->'climate_info'->'outside_temp','null'::jsonb),NULLIF(point.value->'outside_temp','null'::jsonb),point.value->'OutsideTemp') AS outside_temp
          FROM jsonb_array_elements(CASE WHEN s.kind='charge' THEN s.charge_points_json ELSE s.route_json END)
            WITH ORDINALITY AS point(value, ordinality)
        ) raw
    ) normalized
) observed ON true
ORDER BY p.started_at DESC, p.public_id DESC
`, userID, vehicleID, kind, boundedHistoryBoundary(start), boundedHistoryBoundary(end), limit, offset)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	result := make([]boundedHistoryPageRow, 0, limit)
	for rows.Next() {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		var row boundedHistoryPageRow
		var startLatitude, startLongitude, endLatitude, endLongitude *float64
		if err := rows.Scan(
			&row.Session.ID, &row.Session.PublicID, &row.Session.StartAt, &row.Session.EndAt,
			&row.Session.OdometerStart, &row.Session.OdometerEnd, &row.Session.EnergyAdded,
			&row.Session.Source, &row.Session.QualityState, &row.Session.QualityReason,
			&row.Session.SourceInstanceID, &row.Session.SourceVehicleID, &row.Session.SourceRecordID,
			&row.Session.StartAddress, &row.Session.EndAddress, &row.Session.Address, &row.Session.Cost,
			&startLatitude, &startLongitude, &endLatitude, &endLongitude,
			&row.StartBatteryLevel, &row.EndBatteryLevel,
			&row.Metrics.SpeedMax, &row.Metrics.SpeedAvg, &row.Metrics.SpeedCount,
			&row.Metrics.InsideTempAvg, &row.Metrics.OutsideTempAvg, &row.Sequence,
		); err != nil {
			return nil, err
		}
		row.Session.Kind = kind
		if startLatitude != nil && startLongitude != nil {
			row.Session.Route = append(row.Session.Route, telemetryRoutePoint{
				ObservedAt: row.Session.StartAt, Latitude: *startLatitude, Longitude: *startLongitude,
			})
		}
		if endLatitude != nil && endLongitude != nil {
			observedAt := row.Session.StartAt
			if row.Session.EndAt != nil {
				observedAt = *row.Session.EndAt
			}
			row.Session.Route = append(row.Session.Route, telemetryRoutePoint{
				ObservedAt: observedAt, Latitude: *endLatitude, Longitude: *endLongitude,
			})
		}
		result = append(result, row)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return result, ctx.Err()
}

func (s *telemetryService) historyExistsPostgresBounded(ctx context.Context, userID string, vehicleID int, kind string) (bool, error) {
	if s == nil || s.store == nil || s.store.pool == nil {
		return false, nil
	}
	var exists bool
	err := s.store.pool.QueryRow(ctx, `
SELECT EXISTS (
    SELECT 1 FROM jourvolt_telemetry_sessions
    WHERE user_id=$1 AND vehicle_id=$2 AND kind=$3
      AND ended_at IS NOT NULL AND quality_state <> 'quarantined'
)
`, userID, vehicleID, kind).Scan(&exists)
	return exists, err
}

func (s *telemetryService) historySourceForReadinessPostgres(ctx context.Context, userID string, vehicleID int, kind string) (string, bool, error) {
	if s == nil || s.store == nil || s.store.pool == nil {
		return "", false, nil
	}
	var source string
	err := s.store.pool.QueryRow(ctx, `
SELECT CASE
         WHEN source = '' THEN 'telemetry_mqtt'
         WHEN lower(btrim(source)) = 'local_import' THEN 'local_history'
         WHEN lower(btrim(source)) = 'local_history' THEN 'local_history'
         WHEN lower(btrim(source)) = 'telemetry_mqtt' THEN 'telemetry_mqtt'
         WHEN lower(btrim(source)) = 'fleet_api' THEN 'fleet_api'
       END
FROM jourvolt_telemetry_sessions
WHERE user_id=$1 AND vehicle_id=$2 AND kind=$3
  AND ended_at IS NOT NULL AND quality_state <> 'quarantined'
  AND (source = '' OR lower(btrim(source)) IN ('local_import','local_history','telemetry_mqtt','fleet_api'))
ORDER BY started_at DESC, public_id DESC
LIMIT 1
`, userID, vehicleID, kind).Scan(&source)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", false, nil
	}
	if err != nil {
		return "", false, err
	}
	return source, source != "", nil
}

func (s *telemetryService) historyDetailPostgresBounded(ctx context.Context, userID string, vehicleID int, kind string, publicID int) (telemetrySession, bool, error) {
	if s == nil || s.store == nil || s.store.pool == nil {
		return telemetrySession{}, false, nil
	}
	var session telemetrySession
	var routeJSON, chargePointsJSON []byte
	err := s.store.pool.QueryRow(ctx, `
SELECT id, public_id, started_at, ended_at, odometer_start, odometer_end, energy_added,
       route_json, charge_points_json, source, quality_state, quality_reason,
       COALESCE(source_instance_id,''), COALESCE(source_vehicle_id,''), COALESCE(source_record_id,''),
       start_address, end_address, address, cost
FROM jourvolt_telemetry_sessions
WHERE user_id=$1 AND vehicle_id=$2 AND kind=$3 AND public_id=$4
  AND ended_at IS NOT NULL AND quality_state <> 'quarantined'
`, userID, vehicleID, kind, publicID).Scan(
		&session.ID, &session.PublicID, &session.StartAt, &session.EndAt,
		&session.OdometerStart, &session.OdometerEnd, &session.EnergyAdded,
		&routeJSON, &chargePointsJSON, &session.Source, &session.QualityState, &session.QualityReason,
		&session.SourceInstanceID, &session.SourceVehicleID, &session.SourceRecordID,
		&session.StartAddress, &session.EndAddress, &session.Address, &session.Cost,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return telemetrySession{}, false, nil
	}
	if err != nil {
		return telemetrySession{}, false, err
	}
	session.Kind = kind
	if session.Source == "teslamate_archive" {
		if err := decodeHistoryJSONContext(ctx, routeJSON, &session.ArchiveRoute); err != nil {
			return telemetrySession{}, false, err
		}
		for index, point := range session.ArchiveRoute {
			if index%256 == 0 {
				if err := ctx.Err(); err != nil {
					return telemetrySession{}, false, err
				}
			}
			if point.Latitude == nil || point.Longitude == nil {
				continue
			}
			observedAt, parseErr := time.Parse(time.RFC3339, point.Date)
			if parseErr != nil {
				continue
			}
			session.Route = append(session.Route, telemetryRoutePoint{
				ObservedAt: observedAt, Latitude: *point.Latitude, Longitude: *point.Longitude,
				Speed: point.Speed, Power: point.Power, Heading: point.Heading,
				BatteryLevel: cloneInt(observedRouteBatteryLevel(point.BatteryLevel)),
			})
		}
	} else if err := decodeHistoryJSONContext(ctx, routeJSON, &session.Route); err != nil {
		return telemetrySession{}, false, err
	}
	if err := decodeHistoryJSONContext(ctx, chargePointsJSON, &session.ChargePoints); err != nil {
		return telemetrySession{}, false, err
	}
	return session, true, ctx.Err()
}

func historyReadMeta(meta boundedHistoryMetadata) map[string]any {
	result := map[string]any{
		"availability":      "collecting",
		"source":            meta.Source,
		"quarantined_count": meta.Quarantined,
	}
	if result["source"] == "" {
		result["source"] = "telemetry_mqtt"
	}
	if meta.Total > 0 {
		result["availability"] = "available"
		result["coverage_percent"] = float64(meta.Qualified) * 100 / float64(meta.Total)
		if meta.StartedAt != nil {
			result["collection_started_at"] = meta.StartedAt.UTC().Format(time.RFC3339)
		}
	}
	return result
}

func filterHistoryMapsByBoundary(items []map[string]any, start, end time.Time) []map[string]any {
	if start.IsZero() && end.IsZero() {
		return items
	}
	filtered := make([]map[string]any, 0, len(items))
	for _, item := range items {
		value, ok := item["start_date"].(string)
		if !ok {
			continue
		}
		observed, err := time.Parse(time.RFC3339, value)
		if err != nil || (!start.IsZero() && observed.Before(start)) || (!end.IsZero() && !observed.Before(end)) {
			continue
		}
		filtered = append(filtered, item)
	}
	return filtered
}

func historySlicePage(items []map[string]any, page, show int) []map[string]any {
	if len(items) == 0 || show <= 0 || page <= 0 {
		return []map[string]any{}
	}
	if page-1 > (len(items)-1)/show {
		return []map[string]any{}
	}
	start := (page - 1) * show
	end := start + show
	if end > len(items) {
		end = len(items)
	}
	return items[start:end]
}

func (s *telemetryService) historyPageContext(ctx context.Context, userID string, vehicleID int, kind string, start, end time.Time, page, show int) ([]map[string]any, map[string]any, int, int, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	if err := ctx.Err(); err != nil {
		return nil, nil, 0, show, err
	}
	if s == nil {
		return []map[string]any{}, historyReadMeta(boundedHistoryMetadata{Source: "telemetry_mqtt"}), 0, show, nil
	}
	if s.memory != nil {
		items, meta, err := s.history(userID, vehicleID, kind)
		if err != nil {
			return nil, nil, 0, show, err
		}
		if err := ctx.Err(); err != nil {
			return nil, nil, 0, show, err
		}
		filtered := filterHistoryMapsByBoundary(items, start, end)
		total := len(filtered)
		resolvedShow := show
		if resolvedShow <= 0 {
			resolvedShow = total
		}
		return historySlicePage(filtered, page, resolvedShow), meta, total, resolvedShow, nil
	}

	readCtx, cancel := boundedHistoryContext(ctx)
	defer cancel()
	meta, err := s.historyMetadataBoundedPostgres(readCtx, userID, vehicleID, kind)
	if err != nil {
		return nil, nil, 0, show, err
	}
	total, err := s.historyFilteredCountPostgres(readCtx, userID, vehicleID, kind, start, end)
	if err != nil {
		return nil, nil, 0, show, err
	}
	resolvedShow := show
	if resolvedShow <= 0 {
		resolvedShow = total
	}
	resultMeta := historyReadMeta(meta)
	if total == 0 || resolvedShow <= 0 || page <= 0 || page-1 > (total-1)/resolvedShow {
		return []map[string]any{}, resultMeta, total, resolvedShow, readCtx.Err()
	}
	offset := (page - 1) * resolvedShow
	rows, err := s.historyPagePostgresBounded(readCtx, userID, vehicleID, kind, start, end, resolvedShow, offset)
	if err != nil {
		return nil, nil, 0, resolvedShow, err
	}
	items := make([]map[string]any, 0, len(rows))
	for _, row := range rows {
		if err := readCtx.Err(); err != nil {
			return nil, nil, 0, resolvedShow, err
		}
		item := historySessionMap(row.Session, kind, row.Sequence)
		item["battery_details"] = driveBatteryDetails(row.StartBatteryLevel, row.EndBatteryLevel)
		applyHistorySampleMetrics(item, row.Metrics, kind)
		items = append(items, item)
	}
	return items, resultMeta, total, resolvedShow, readCtx.Err()
}

func (s *telemetryService) historyCountContext(ctx context.Context, userID string, vehicleID int, kind string) (int, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	if err := ctx.Err(); err != nil {
		return 0, err
	}
	if s == nil {
		return 0, nil
	}
	if s.memory != nil {
		count := 0
		for _, session := range s.memory.sessions(userID, vehicleID, kind) {
			if session.QualityState != "quarantined" {
				count++
			}
		}
		return count, ctx.Err()
	}
	readCtx, cancel := boundedHistoryContext(ctx)
	defer cancel()
	return s.historyFilteredCountPostgres(readCtx, userID, vehicleID, kind, time.Time{}, time.Time{})
}

func (s *telemetryService) historyExistsContext(ctx context.Context, userID string, vehicleID int, kind string) (bool, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	if err := ctx.Err(); err != nil {
		return false, err
	}
	if s == nil {
		return false, nil
	}
	if s.memory != nil {
		for _, session := range s.memory.sessions(userID, vehicleID, kind) {
			if session.QualityState != "quarantined" {
				return true, nil
			}
		}
		return false, ctx.Err()
	}
	readCtx, cancel := boundedHistoryContext(ctx)
	defer cancel()
	return s.historyExistsPostgresBounded(readCtx, userID, vehicleID, kind)
}

func normalizeReadinessHistorySource(raw string) string {
	// Legacy historySessionMap maps only an exact empty source to telemetry_mqtt.
	// Whitespace-only values remain unrecognized after trimming.
	if raw == "" {
		return "telemetry_mqtt"
	}
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "local_import", "local_history":
		return "local_history"
	case "telemetry_mqtt":
		return "telemetry_mqtt"
	case "fleet_api":
		return "fleet_api"
	default:
		return ""
	}
}

func (s *telemetryService) historySourceForReadinessContext(ctx context.Context, userID string, vehicleID int, kind string) (string, bool, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	if err := ctx.Err(); err != nil {
		return "", false, err
	}
	if s == nil {
		return "", false, nil
	}
	if s.memory != nil {
		sessions := s.memory.sessions(userID, vehicleID, kind)
		for _, session := range sessions {
			if session.QualityState == "quarantined" {
				continue
			}
			if source := normalizeReadinessHistorySource(session.Source); source != "" {
				return source, true, nil
			}
		}
		return "", false, ctx.Err()
	}
	readCtx, cancel := boundedHistoryContext(ctx)
	defer cancel()
	return s.historySourceForReadinessPostgres(readCtx, userID, vehicleID, kind)
}

func (s *telemetryService) historyDetailContext(ctx context.Context, userID string, vehicleID int, kind string, publicID int) (map[string]any, bool, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	if err := ctx.Err(); err != nil {
		return nil, false, err
	}
	if s == nil {
		return nil, false, nil
	}
	if s.memory != nil {
		for _, session := range s.memory.sessions(userID, vehicleID, kind) {
			if err := ctx.Err(); err != nil {
				return nil, false, err
			}
			if session.PublicID == publicID && session.QualityState != "quarantined" {
				item := historySessionMap(session, kind, 0)
				return item, true, ctx.Err()
			}
		}
		return nil, false, nil
	}
	readCtx, cancel := boundedHistoryContext(ctx)
	defer cancel()
	session, ok, err := s.historyDetailPostgresBounded(readCtx, userID, vehicleID, kind, publicID)
	if err != nil || !ok {
		return nil, ok, err
	}
	item := historySessionMap(session, kind, 0)
	if err := readCtx.Err(); err != nil {
		return nil, false, err
	}
	return item, true, nil
}
