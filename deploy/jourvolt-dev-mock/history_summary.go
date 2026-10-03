package main

import (
	"context"
	"fmt"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Version 0 is legacy/unprocessed, 1 is a materialized summary, and -1 means
// malformed legacy JSON. Invalid rows retain the old read/error behavior and
// are not retried forever by the bounded backfill. Raw details are never altered.
const historySummarySchema = `
ALTER TABLE jourvolt_telemetry_sessions ADD COLUMN IF NOT EXISTS history_summary_version SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE jourvolt_telemetry_sessions ADD COLUMN IF NOT EXISTS history_summary_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE jourvolt_telemetry_sessions ADD COLUMN IF NOT EXISTS route_point_count INTEGER;
ALTER TABLE jourvolt_telemetry_sessions ADD COLUMN IF NOT EXISTS charge_point_count INTEGER;
ALTER TABLE jourvolt_telemetry_sessions ADD COLUMN IF NOT EXISTS route_start_latitude DOUBLE PRECISION;
ALTER TABLE jourvolt_telemetry_sessions ADD COLUMN IF NOT EXISTS route_start_longitude DOUBLE PRECISION;
ALTER TABLE jourvolt_telemetry_sessions ADD COLUMN IF NOT EXISTS route_end_latitude DOUBLE PRECISION;
ALTER TABLE jourvolt_telemetry_sessions ADD COLUMN IF NOT EXISTS route_end_longitude DOUBLE PRECISION;
CREATE INDEX IF NOT EXISTS jourvolt_history_summary_pending_idx
    ON jourvolt_telemetry_sessions(public_id) WHERE history_summary_version=0;

CREATE OR REPLACE FUNCTION jourvolt_materialize_history_summary() RETURNS trigger AS $$
BEGIN
    NEW.history_summary_revision := CASE WHEN TG_OP='INSERT' THEN 1 ELSE OLD.history_summary_revision+1 END;
    BEGIN
        NEW.route_point_count := CASE WHEN NEW.route_json='null'::jsonb THEN 0 ELSE jsonb_array_length(NEW.route_json) END;
        NEW.charge_point_count := CASE WHEN NEW.charge_points_json='null'::jsonb THEN 0 ELSE jsonb_array_length(NEW.charge_points_json) END;
        -- Native telemetry uses Go field names; archive imports use lower case.
        -- JSON null deliberately wins over a differently-cased fallback value.
        NEW.route_start_latitude := NULLIF(COALESCE(NEW.route_json->0->'latitude', NEW.route_json->0->'Latitude')#>>'{}','')::double precision;
        NEW.route_start_longitude := NULLIF(COALESCE(NEW.route_json->0->'longitude', NEW.route_json->0->'Longitude')#>>'{}','')::double precision;
        NEW.route_end_latitude := NULLIF(COALESCE(NEW.route_json->-1->'latitude', NEW.route_json->-1->'Latitude')#>>'{}','')::double precision;
        NEW.route_end_longitude := NULLIF(COALESCE(NEW.route_json->-1->'longitude', NEW.route_json->-1->'Longitude')#>>'{}','')::double precision;
        NEW.history_summary_version := 1;
    EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range OR invalid_parameter_value THEN
        NEW.history_summary_version := -1;
        NEW.route_point_count := NULL;
        NEW.charge_point_count := NULL;
        NEW.route_start_latitude := NULL;
        NEW.route_start_longitude := NULL;
        NEW.route_end_latitude := NULL;
        NEW.route_end_longitude := NULL;
    END;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE OR REPLACE TRIGGER jourvolt_history_summary_write
    BEFORE INSERT OR UPDATE OF route_json, charge_points_json, history_summary_version
    ON jourvolt_telemetry_sessions FOR EACH ROW EXECUTE FUNCTION jourvolt_materialize_history_summary();
`

// CASE, rather than COALESCE, is essential: an observed null endpoint in a
// materialized summary must not make a list read/decompress the full JSON.
const historySummaryEndpointsSQL = `
       CASE WHEN history_summary_version=1 THEN route_start_latitude ELSE NULLIF(COALESCE(route_json->0->'latitude', route_json->0->'Latitude')#>>'{}','')::double precision END,
       CASE WHEN history_summary_version=1 THEN route_start_longitude ELSE NULLIF(COALESCE(route_json->0->'longitude', route_json->0->'Longitude')#>>'{}','')::double precision END,
       CASE WHEN history_summary_version=1 THEN route_end_latitude ELSE NULLIF(COALESCE(route_json->-1->'latitude', route_json->-1->'Latitude')#>>'{}','')::double precision END,
       CASE WHEN history_summary_version=1 THEN route_end_longitude ELSE NULLIF(COALESCE(route_json->-1->'longitude', route_json->-1->'Longitude')#>>'{}','')::double precision END`

const maxHistorySummaryBackfillBatch = 100

const historySummaryBackfillSQL = `WITH batch AS MATERIALIZED (
    SELECT id FROM jourvolt_telemetry_sessions
    WHERE history_summary_version=0 ORDER BY public_id
    LIMIT $1 FOR UPDATE SKIP LOCKED
)
UPDATE jourvolt_telemetry_sessions s SET history_summary_version=1
FROM batch WHERE s.id=batch.id`

// Explicit maintenance only: normal startup never scans/backfills raw history.
// Each invocation is one cancelable, atomic batch; it is not an unbounded loop.
func backfillHistorySummaries(ctx context.Context, pool *pgxpool.Pool, limit int) (int64, error) {
	if pool == nil || limit < 1 || limit > maxHistorySummaryBackfillBatch {
		return 0, fmt.Errorf("history summary backfill requires postgres and batch size 1..%d", maxHistorySummaryBackfillBatch)
	}
	ctx, cancel := historyReadContext(ctx)
	defer cancel()
	tag, err := pool.Exec(ctx, historySummaryBackfillSQL, limit)
	return tag.RowsAffected(), err
}
