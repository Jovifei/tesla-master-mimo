package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const nativeShadowAuditSchema = `
ALTER TABLE jourvolt_telemetry_detail_shadow ADD COLUMN IF NOT EXISTS content_revision BIGINT NOT NULL DEFAULT 0;
CREATE OR REPLACE FUNCTION jourvolt_native_shadow_chunk_revision() RETURNS trigger AS $$
BEGIN
    IF TG_OP='TRUNCATE' THEN
        RAISE EXCEPTION 'native shadow chunks cannot be truncated';
    END IF;
    IF TG_OP='UPDATE' AND (OLD.session_id,OLD.user_id,OLD.vehicle_id) IS DISTINCT FROM (NEW.session_id,NEW.user_id,NEW.vehicle_id) THEN
        RAISE EXCEPTION 'native shadow chunk ownership is immutable';
    END IF;
    IF TG_OP='DELETE' THEN
        UPDATE jourvolt_telemetry_detail_shadow SET content_revision=content_revision+1
          WHERE session_id=OLD.session_id AND user_id=OLD.user_id AND vehicle_id=OLD.vehicle_id;
    ELSE
        UPDATE jourvolt_telemetry_detail_shadow SET content_revision=content_revision+1
          WHERE session_id=NEW.session_id AND user_id=NEW.user_id AND vehicle_id=NEW.vehicle_id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE OR REPLACE TRIGGER jourvolt_native_shadow_chunk_revision_write
    AFTER INSERT OR UPDATE OR DELETE ON jourvolt_telemetry_detail_chunks
    FOR EACH ROW EXECUTE FUNCTION jourvolt_native_shadow_chunk_revision();
CREATE OR REPLACE TRIGGER jourvolt_native_shadow_no_truncate
    BEFORE TRUNCATE ON jourvolt_telemetry_detail_chunks
    FOR EACH STATEMENT EXECUTE FUNCTION jourvolt_native_shadow_chunk_revision();
CREATE TABLE IF NOT EXISTS jourvolt_telemetry_shadow_audits (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    vehicle_id INTEGER NOT NULL,
    public_id INTEGER NOT NULL,
    source_revision BIGINT NOT NULL,
    content_revision BIGINT NOT NULL,
    source_started_at TIMESTAMPTZ NOT NULL,
    source_ended_at TIMESTAMPTZ NOT NULL,
    source_completion_key TEXT NOT NULL,
    stream TEXT NOT NULL CHECK (stream IN ('route','charge')),
    expected_chunks INTEGER NOT NULL CHECK (expected_chunks>=0),
    expected_samples INTEGER NOT NULL CHECK (expected_samples>=0),
    next_chunk INTEGER NOT NULL DEFAULT 0 CHECK (next_chunk>=0 AND next_chunk<=expected_chunks),
    next_sample INTEGER NOT NULL DEFAULT 0 CHECK (next_sample>=0 AND next_sample<=expected_samples),
    chain_sha256 TEXT NOT NULL CHECK (chain_sha256 ~ '^[0-9a-f]{64}$'),
    verified BOOLEAN NOT NULL DEFAULT false,
    CHECK (NOT verified OR (next_chunk=expected_chunks AND next_sample=expected_samples)),
    FOREIGN KEY (session_id,user_id,vehicle_id)
        REFERENCES jourvolt_telemetry_detail_shadow(session_id,user_id,vehicle_id) ON DELETE CASCADE
);
`

const maxNativeShadowAuditChunks = 16

var errNativeShadowAuditIneligible = errors.New("native shadow comparison requires completed current coverage from creation")
var errNativeShadowAuditDrift = errors.New("native shadow comparison source or chunk revision changed; start a new comparison")
var errNativeShadowAuditMismatch = errors.New("native shadow comparison found a chunk integrity or legacy-data mismatch")

type nativeShadowAuditResult struct {
	JobID           string `json:"job_id"`
	NextChunk       int    `json:"next_chunk"`
	NextSample      int    `json:"next_sample"`
	TotalChunks     int    `json:"total_chunks"`
	TotalSamples    int    `json:"total_samples"`
	Done            bool   `json:"verified_at_revisions"`
	BatchChunks     int    `json:"batch_chunks"`
	BatchBytes      int    `json:"batch_bytes"`
	SourceRevision  int64  `json:"source_revision"`
	ContentRevision int64  `json:"content_revision"`
	ChainSHA256     string `json:"comparison_chain_sha256"`
}

// Only the opaque job ID comes from the caller; persisted progress is locked and
// checked inside PostgreSQL. Raw history and chunks are never changed here.
// Go receives at most one 64KiB chunk at a time. PostgreSQL may still detoast the
// legacy JSON value when comparing a slice: these are not database memory caps.
func auditNativeShadowBatch(ctx context.Context, pool *pgxpool.Pool, user string, car, publicID int, jobID string, limit int) (nativeShadowAuditResult, error) {
	var result nativeShadowAuditResult
	if pool == nil || strings.TrimSpace(user) == "" || car <= 0 || publicID <= 0 || limit < 1 || limit > maxNativeShadowAuditChunks {
		return result, fmt.Errorf("native shadow comparison requires explicit scope and chunk limit 1..%d", maxNativeShadowAuditChunks)
	}
	ctx, cancel := historyReadContext(ctx)
	defer cancel()
	tx, err := pool.Begin(ctx)
	if err != nil {
		return result, err
	}
	defer tx.Rollback(ctx)
	// Lock parents before the cursor, matching ingestion and cascade deletion.
	var sessionID, kind, source, completionKey string
	var started time.Time
	var ended *time.Time
	var revision int64
	var summaryVersion int
	var samples *int
	err = tx.QueryRow(ctx, `SELECT id,kind,source,started_at,ended_at,COALESCE(completion_key,''),history_summary_revision,history_summary_version,
        CASE WHEN kind='drive' THEN route_point_count ELSE charge_point_count END
        FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2 AND public_id=$3 FOR SHARE`, user, car, publicID).Scan(&sessionID, &kind, &source, &started, &ended, &completionKey, &revision, &summaryVersion, &samples)
	if err != nil {
		return result, err
	}
	if ended == nil || ended.Before(started) || completionKey == "" || source != "telemetry_mqtt" || summaryVersion != 1 || samples == nil {
		return result, errNativeShadowAuditIneligible
	}
	stream := "route"
	if kind == "charge" {
		stream = "charge"
	} else if kind != "drive" {
		return result, errNativeShadowAuditIneligible
	}
	var shadowRevision, contentRevision int64
	var prefix, count, chunks, version int
	var fresh, stale bool
	var storedStream, encoding, compression string
	err = tx.QueryRow(ctx, `SELECT source_revision,content_revision,prefix_count,sample_count,chunk_count,
        started_with_session,stale,stream,schema_version,encoding,compression
        FROM jourvolt_telemetry_detail_shadow WHERE session_id=$1 AND user_id=$2 AND vehicle_id=$3 FOR SHARE`, sessionID, user, car).Scan(&shadowRevision, &contentRevision, &prefix, &count, &chunks, &fresh, &stale, &storedStream, &version, &encoding, &compression)
	if errors.Is(err, pgx.ErrNoRows) {
		return result, errNativeShadowAuditIneligible
	}
	if err != nil {
		return result, err
	}
	if stale || !fresh || prefix != 0 || shadowRevision != revision || count != *samples || storedStream != stream || version != 1 || encoding != "native-session-json-v1" || compression != "none" {
		return result, errNativeShadowAuditIneligible
	}
	result.SourceRevision, result.ContentRevision = revision, contentRevision
	result.TotalChunks, result.TotalSamples = chunks, count
	if jobID == "" {
		jobID, err = randomToken()
		if err != nil {
			return result, err
		}
		digest := sha256.Sum256(nil)
		result.ChainSHA256 = hex.EncodeToString(digest[:])
		_, err = tx.Exec(ctx, `INSERT INTO jourvolt_telemetry_shadow_audits
            (id,session_id,user_id,vehicle_id,public_id,source_revision,content_revision,source_started_at,source_ended_at,source_completion_key,stream,expected_chunks,expected_samples,chain_sha256)
            VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14)`, jobID, sessionID, user, car, publicID, revision, contentRevision, started, *ended, completionKey, stream, chunks, count, result.ChainSHA256)
		if err != nil {
			return result, err
		}
	} else {
		var jobSession, jobStream, jobCompletion string
		var jobPublic, jobChunks, jobSamples int
		var jobSource, jobContent int64
		var jobStart, jobEnd time.Time
		err = tx.QueryRow(ctx, `SELECT session_id,public_id,source_revision,content_revision,source_started_at,source_ended_at,source_completion_key,stream,
            expected_chunks,expected_samples,next_chunk,next_sample,chain_sha256,verified
            FROM jourvolt_telemetry_shadow_audits WHERE id=$1 AND user_id=$2 AND vehicle_id=$3 FOR UPDATE`, jobID, user, car).Scan(&jobSession, &jobPublic, &jobSource, &jobContent, &jobStart, &jobEnd, &jobCompletion, &jobStream, &jobChunks, &jobSamples, &result.NextChunk, &result.NextSample, &result.ChainSHA256, &result.Done)
		if err != nil {
			return result, err
		}
		if jobSession != sessionID || jobPublic != publicID || jobSource != revision || jobContent != contentRevision || !jobStart.Equal(started) || !jobEnd.Equal(*ended) || jobCompletion != completionKey || jobStream != stream || jobChunks != chunks || jobSamples != count {
			return result, errNativeShadowAuditDrift
		}
	}
	result.JobID = jobID
	if result.Done {
		return result, tx.Commit(ctx)
	}
	for result.BatchChunks < limit && result.NextChunk < chunks {
		var index, start, n, bytes int
		var payload []byte
		var hash string
		// Plain MVCC reads are deliberate. Locking chunk rows after their
		// manifest could deadlock with a chunk edit's revision trigger.
		err = tx.QueryRow(ctx, `SELECT chunk_index,start_index,sample_count,octet_length(payload),
            CASE WHEN octet_length(payload)<=65536 THEN payload ELSE NULL END,payload_sha256
            FROM jourvolt_telemetry_detail_chunks
            WHERE session_id=$1 AND user_id=$2 AND vehicle_id=$3 AND chunk_index=$4`, sessionID, user, car, result.NextChunk).Scan(&index, &start, &n, &bytes, &payload, &hash)
		if err != nil {
			return result, err
		}
		if index != result.NextChunk || start != result.NextSample || n < 1 || n > maxNativeShadowChunkSamples || bytes < 2 || bytes > maxNativeShadowChunkBytes || n > count-start {
			return result, errNativeShadowAuditMismatch
		}
		digest := sha256.Sum256(payload)
		var points []json.RawMessage
		if hex.EncodeToString(digest[:]) != hash || json.Unmarshal(payload, &points) != nil || len(points) != n {
			return result, errNativeShadowAuditMismatch
		}
		path := fmt.Sprintf("$[%d to %d]", start, start+n-1)
		var equal bool
		err = tx.QueryRow(ctx, `SELECT jsonb_path_query_array(CASE WHEN kind='drive' THEN route_json ELSE charge_points_json END,$4::jsonpath)=$5::jsonb
            FROM jourvolt_telemetry_sessions WHERE id=$1 AND user_id=$2 AND vehicle_id=$3`, sessionID, user, car, path, payload).Scan(&equal)
		if err != nil {
			return result, err
		}
		if !equal {
			return result, errNativeShadowAuditMismatch
		}
		frame, _ := json.Marshal(struct {
			Version                    int
			Session, User              string
			Vehicle                    int
			Stream                     string
			Index, Start, Count, Bytes int
			PayloadSHA, PreviousSHA    string
		}{1, sessionID, user, car, stream, index, start, n, bytes, hash, result.ChainSHA256})
		digest = sha256.Sum256(frame)
		result.ChainSHA256 = hex.EncodeToString(digest[:])
		result.NextChunk++
		result.NextSample += n
		result.BatchChunks++
		result.BatchBytes += bytes
	}
	if result.NextChunk == chunks {
		var extra bool
		err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM jourvolt_telemetry_detail_chunks WHERE session_id=$1 AND user_id=$2 AND vehicle_id=$3 AND chunk_index>=$4)`, sessionID, user, car, chunks).Scan(&extra)
		if err != nil {
			return result, err
		}
		if result.NextSample != count || extra {
			return result, errNativeShadowAuditMismatch
		}
		result.Done = true
	}
	_, err = tx.Exec(ctx, `UPDATE jourvolt_telemetry_shadow_audits SET next_chunk=$1,next_sample=$2,chain_sha256=$3,verified=$4
        WHERE id=$5 AND session_id=$6 AND user_id=$7 AND vehicle_id=$8`, result.NextChunk, result.NextSample, result.ChainSHA256, result.Done, jobID, sessionID, user, car)
	if err != nil {
		return result, err
	}
	return result, tx.Commit(ctx)
}

func runNativeShadowAudit(ctx context.Context, getenv func(string) string, output io.Writer) error {
	if getenv("JOURVOLT_BACKFILL_HISTORY_SUMMARIES") == "1" {
		return errors.New("select one explicit maintenance mode")
	}
	dsn := getenv("DATABASE_URL")
	if strings.TrimSpace(dsn) == "" {
		return errors.New("native shadow comparison requires explicit DATABASE_URL")
	}
	car, err := strconv.Atoi(getenv("JOURVOLT_SHADOW_AUDIT_VEHICLE_ID"))
	if err != nil || car <= 0 {
		return errors.New("native shadow comparison requires positive vehicle ID")
	}
	publicID, err := strconv.Atoi(getenv("JOURVOLT_SHADOW_AUDIT_PUBLIC_ID"))
	if err != nil || publicID <= 0 {
		return errors.New("native shadow comparison requires positive session public ID")
	}
	user := getenv("JOURVOLT_SHADOW_AUDIT_USER_ID")
	if strings.TrimSpace(user) == "" {
		return errors.New("native shadow comparison requires user scope")
	}
	pool, err := pgxpool.New(ctx, dsn)
	if err != nil {
		return err
	}
	defer pool.Close()
	result, err := auditNativeShadowBatch(ctx, pool, user, car, publicID, getenv("JOURVOLT_SHADOW_AUDIT_JOB_ID"), maxNativeShadowAuditChunks)
	if err != nil {
		return err
	}
	return json.NewEncoder(output).Encode(result)
}
