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

// These generations are independent of the ingest shadow. Rebuild never updates
// source JSON or previous generations, and the selection is not a read cutover.
const nativeShadowRebuildSchema = `
CREATE TABLE IF NOT EXISTS jourvolt_telemetry_shadow_generations (
    id TEXT PRIMARY KEY,
    ordinal BIGSERIAL NOT NULL UNIQUE,
    session_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    vehicle_id INTEGER NOT NULL,
    public_id INTEGER NOT NULL,
    source_revision BIGINT NOT NULL,
    source_started_at TIMESTAMPTZ NOT NULL,
    source_ended_at TIMESTAMPTZ NOT NULL,
    source_completion_key TEXT NOT NULL,
    stream TEXT NOT NULL CHECK (stream IN ('route','charge')),
    encoding TEXT NOT NULL DEFAULT 'legacy-jsonb-array-v1' CHECK (encoding='legacy-jsonb-array-v1'),
    expected_samples INTEGER NOT NULL CHECK (expected_samples>=0),
    phase TEXT NOT NULL DEFAULT 'building' CHECK (phase IN ('building','verifying','verified')),
    next_chunk INTEGER NOT NULL DEFAULT 0 CHECK (next_chunk>=0),
    next_sample INTEGER NOT NULL DEFAULT 0 CHECK (next_sample BETWEEN 0 AND expected_samples),
    verify_chunk INTEGER NOT NULL DEFAULT 0 CHECK (verify_chunk BETWEEN 0 AND next_chunk),
    verify_sample INTEGER NOT NULL DEFAULT 0 CHECK (verify_sample BETWEEN 0 AND next_sample),
    content_revision BIGINT NOT NULL DEFAULT 0 CHECK (content_revision>=0),
    verified_content_revision BIGINT NOT NULL DEFAULT 0 CHECK (verified_content_revision>=0),
    chain_sha256 TEXT NOT NULL CHECK (chain_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (phase='building' OR next_sample=expected_samples),
    CHECK (phase<>'verified' OR (verify_chunk=next_chunk AND verify_sample=expected_samples)),
    UNIQUE (id,session_id,user_id,vehicle_id),
    FOREIGN KEY (session_id,user_id,vehicle_id)
        REFERENCES jourvolt_telemetry_sessions(id,user_id,vehicle_id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS jourvolt_telemetry_shadow_generation_chunks (
    generation_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    vehicle_id INTEGER NOT NULL,
    chunk_index INTEGER NOT NULL CHECK (chunk_index>=0),
    start_index INTEGER NOT NULL CHECK (start_index>=0),
    sample_count INTEGER NOT NULL CHECK (sample_count BETWEEN 1 AND 256),
    payload BYTEA NOT NULL CHECK (octet_length(payload) BETWEEN 2 AND 65536),
    payload_sha256 TEXT NOT NULL CHECK (payload_sha256=encode(sha256(payload),'hex')),
    CHECK (sample_count=jsonb_array_length(convert_from(payload,'UTF8')::jsonb)),
    PRIMARY KEY (generation_id,chunk_index),
    UNIQUE (generation_id,start_index),
    FOREIGN KEY (generation_id,session_id,user_id,vehicle_id)
        REFERENCES jourvolt_telemetry_shadow_generations(id,session_id,user_id,vehicle_id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS jourvolt_telemetry_shadow_generation_current (
    session_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    vehicle_id INTEGER NOT NULL,
    generation_id TEXT NOT NULL,
    ordinal BIGINT NOT NULL,
    source_revision BIGINT NOT NULL,
    content_revision BIGINT NOT NULL,
    PRIMARY KEY (session_id,user_id,vehicle_id),
    FOREIGN KEY (generation_id,session_id,user_id,vehicle_id)
        REFERENCES jourvolt_telemetry_shadow_generations(id,session_id,user_id,vehicle_id) ON DELETE CASCADE
);
CREATE OR REPLACE FUNCTION jourvolt_native_generation_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP='TRUNCATE' THEN
        RAISE EXCEPTION 'native generations cannot be truncated';
    ELSIF TG_OP='DELETE' THEN
        -- Plain visibility check only: never acquire an ancestor lock here.
        IF EXISTS(SELECT 1 FROM jourvolt_telemetry_sessions WHERE (id,user_id,vehicle_id)=(OLD.session_id,OLD.user_id,OLD.vehicle_id)) THEN
            RAISE EXCEPTION 'native generations cannot be deleted directly';
        END IF;
        RETURN OLD;
    END IF;
    IF (NEW.id,NEW.ordinal,NEW.session_id,NEW.user_id,NEW.vehicle_id,NEW.public_id,NEW.source_revision,NEW.source_started_at,NEW.source_ended_at,NEW.source_completion_key,NEW.stream,NEW.encoding,NEW.expected_samples)
        IS DISTINCT FROM (OLD.id,OLD.ordinal,OLD.session_id,OLD.user_id,OLD.vehicle_id,OLD.public_id,OLD.source_revision,OLD.source_started_at,OLD.source_ended_at,OLD.source_completion_key,OLD.stream,OLD.encoding,OLD.expected_samples) THEN
        RAISE EXCEPTION 'native generation identity is immutable';
    END IF;
    IF (OLD.phase='verified' AND NEW IS DISTINCT FROM OLD)
        OR (OLD.phase='verifying' AND NEW.phase='building') THEN
        RAISE EXCEPTION 'native generation phase cannot regress or change after verification';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE OR REPLACE TRIGGER jourvolt_native_generation_guard_write BEFORE UPDATE OR DELETE
    ON jourvolt_telemetry_shadow_generations FOR EACH ROW EXECUTE FUNCTION jourvolt_native_generation_guard();
CREATE OR REPLACE TRIGGER jourvolt_native_generation_no_truncate BEFORE TRUNCATE
    ON jourvolt_telemetry_shadow_generations FOR EACH STATEMENT EXECUTE FUNCTION jourvolt_native_generation_guard();
CREATE OR REPLACE FUNCTION jourvolt_native_generation_chunk_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        -- Lock the generation BEFORE unique-key arbitration on the chunk.
        -- This trigger never locks the source, a cursor or the current pointer.
        UPDATE jourvolt_telemetry_shadow_generations SET content_revision=content_revision+1
          WHERE (id,session_id,user_id,vehicle_id)=(NEW.generation_id,NEW.session_id,NEW.user_id,NEW.vehicle_id) AND phase='building';
        IF NOT FOUND THEN RAISE EXCEPTION 'native generation is absent or frozen'; END IF;
        RETURN NEW;
    ELSIF TG_OP='DELETE' THEN
        IF NOT EXISTS(SELECT 1 FROM jourvolt_telemetry_shadow_generations WHERE id=OLD.generation_id) THEN
            RETURN OLD;
        END IF;
    END IF;
    RAISE EXCEPTION 'native generation chunks are immutable';
END;
$$ LANGUAGE plpgsql;
CREATE OR REPLACE TRIGGER jourvolt_native_generation_chunk_guard_write BEFORE INSERT OR UPDATE OR DELETE
    ON jourvolt_telemetry_shadow_generation_chunks FOR EACH ROW EXECUTE FUNCTION jourvolt_native_generation_chunk_guard();
CREATE OR REPLACE TRIGGER jourvolt_native_generation_chunk_no_truncate BEFORE TRUNCATE
    ON jourvolt_telemetry_shadow_generation_chunks FOR EACH STATEMENT EXECUTE FUNCTION jourvolt_native_generation_chunk_guard();
`

const maxNativeShadowRebuildChunks = 16

var errNativeShadowRebuildDrift = errors.New("native generation source or content changed; start a new generation")
var errNativeShadowRebuildMismatch = errors.New("native generation comparison failed")

type nativeShadowRebuildResult struct {
	GenerationID    string `json:"generation_id"`
	Phase           string `json:"phase"`
	Selected        bool   `json:"selected_at_revisions"`
	Ordinal         int64  `json:"ordinal"`
	NextChunk       int    `json:"next_chunk"`
	NextSample      int    `json:"next_sample"`
	VerifyChunk     int    `json:"verify_chunk"`
	VerifySample    int    `json:"verify_sample"`
	TotalSamples    int    `json:"total_samples"`
	TotalChunks     int    `json:"total_chunks"`
	BatchChunks     int    `json:"batch_chunks"`
	BatchBytes      int    `json:"batch_bytes"`
	SourceRevision  int64  `json:"source_revision"`
	ContentRevision int64  `json:"content_revision"`
	ChainSHA256     string `json:"comparison_chain_sha256"`
}

type nativeRebuildSource struct {
	Session, User, Stream, Completion string
	Vehicle, Public, Samples          int
	Revision                          int64
	Started, Ended                    time.Time
}

func nativeRebuildGenesis(id string, source nativeRebuildSource) string {
	frame, _ := json.Marshal(struct {
		Version    int
		Generation string
		Encoding   string
		Source     nativeRebuildSource
	}{1, id, "legacy-jsonb-array-v1", source})
	sum := sha256.Sum256(frame)
	return hex.EncodeToString(sum[:])
}

// One explicit invocation performs one phase only. All progress and selection
// are transactional. The pointer is revision stamped; it is never a permanent
// certificate, and any future consumer must validate the source and generation.
func rebuildNativeShadowBatch(ctx context.Context, pool *pgxpool.Pool, user string, car, publicID int, generationID string, limit int) (nativeShadowRebuildResult, error) {
	var result nativeShadowRebuildResult
	if pool == nil || strings.TrimSpace(user) == "" || car <= 0 || publicID <= 0 || limit < 1 || limit > maxNativeShadowRebuildChunks {
		return result, errors.New("native rebuild requires explicit scope and chunk limit 1..16")
	}
	ctx, cancel := historyReadContext(ctx)
	defer cancel()
	tx, err := pool.Begin(ctx)
	if err != nil {
		return result, err
	}
	defer tx.Rollback(ctx)
	source := nativeRebuildSource{User: user, Vehicle: car, Public: publicID}
	var kind, origin string
	var ended *time.Time
	var summary int
	var count *int
	err = tx.QueryRow(ctx, `SELECT id,kind,source,started_at,ended_at,COALESCE(completion_key,''),history_summary_revision,history_summary_version,
        CASE WHEN kind='drive' THEN route_point_count ELSE charge_point_count END
        FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2 AND public_id=$3 FOR SHARE`, user, car, publicID).Scan(&source.Session, &kind, &origin, &source.Started, &ended, &source.Completion, &source.Revision, &summary, &count)
	if err != nil {
		return result, err
	}
	if ended == nil || ended.Before(source.Started) || source.Completion == "" || origin != "telemetry_mqtt" || summary != 1 || count == nil || *count < 0 || (kind != "drive" && kind != "charge") {
		return result, errors.New("native rebuild requires a completed native session with a valid materialized summary")
	}
	source.Ended, source.Samples, source.Stream = *ended, *count, "route"
	if kind == "charge" {
		source.Stream = "charge"
	}
	if generationID == "" {
		generationID, err = randomToken()
		if err != nil {
			return result, err
		}
		_, err = tx.Exec(ctx, `INSERT INTO jourvolt_telemetry_shadow_generations
            (id,session_id,user_id,vehicle_id,public_id,source_revision,source_started_at,source_ended_at,source_completion_key,stream,expected_samples,chain_sha256)
            VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)`, generationID, source.Session, user, car, publicID, source.Revision, source.Started, source.Ended, source.Completion, source.Stream, source.Samples, nativeRebuildGenesis(generationID, source))
		if err != nil {
			return result, err
		}
	}
	var pinned nativeRebuildSource
	var verificationRevision int64
	var encoding string
	err = tx.QueryRow(ctx, `SELECT session_id,user_id,vehicle_id,public_id,source_revision,source_started_at,source_ended_at,source_completion_key,stream,expected_samples,
        ordinal,phase,next_chunk,next_sample,verify_chunk,verify_sample,content_revision,verified_content_revision,chain_sha256,encoding
        FROM jourvolt_telemetry_shadow_generations WHERE id=$1 AND session_id=$2 AND user_id=$3 AND vehicle_id=$4 FOR UPDATE`, generationID, source.Session, user, car).Scan(&pinned.Session, &pinned.User, &pinned.Vehicle, &pinned.Public, &pinned.Revision, &pinned.Started, &pinned.Ended, &pinned.Completion, &pinned.Stream, &pinned.Samples, &result.Ordinal, &result.Phase, &result.NextChunk, &result.NextSample, &result.VerifyChunk, &result.VerifySample, &result.ContentRevision, &verificationRevision, &result.ChainSHA256, &encoding)
	if err != nil {
		return result, err
	}
	if pinned.Session != source.Session || pinned.User != user || pinned.Vehicle != car || pinned.Public != publicID || pinned.Revision != source.Revision || !pinned.Started.Equal(source.Started) || !pinned.Ended.Equal(source.Ended) || pinned.Completion != source.Completion || pinned.Stream != source.Stream || pinned.Samples != source.Samples || encoding != "legacy-jsonb-array-v1" || result.ContentRevision != int64(result.NextChunk) {
		return result, errNativeShadowRebuildDrift
	}
	result.GenerationID, result.SourceRevision, result.TotalSamples = generationID, source.Revision, source.Samples
	switch result.Phase {
	case "building":
		for result.BatchChunks < limit && result.NextSample < source.Samples {
			payload, n, e := readNativeRebuildSlice(ctx, tx, source, result.NextSample)
			if e != nil {
				return result, e
			}
			sum := sha256.Sum256(payload)
			_, err = tx.Exec(ctx, `INSERT INTO jourvolt_telemetry_shadow_generation_chunks
                (generation_id,session_id,user_id,vehicle_id,chunk_index,start_index,sample_count,payload,payload_sha256)
                VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)`, generationID, source.Session, user, car, result.NextChunk, result.NextSample, n, payload, hex.EncodeToString(sum[:]))
			if err != nil {
				return result, err
			}
			result.NextChunk++
			result.NextSample += n
			result.ContentRevision++
			result.BatchChunks++
			result.BatchBytes += len(payload)
		}
		if result.NextSample == source.Samples {
			result.Phase = "verifying"
			verificationRevision = result.ContentRevision
			result.VerifyChunk, result.VerifySample = 0, 0
			result.ChainSHA256 = nativeRebuildGenesis(generationID, source)
		}
	case "verifying":
		if verificationRevision != result.ContentRevision {
			return result, errNativeShadowRebuildDrift
		}
		for result.BatchChunks < limit && result.VerifyChunk < result.NextChunk {
			if err = verifyNativeRebuildChunk(ctx, tx, source, &result); err != nil {
				return result, err
			}
		}
		if result.VerifyChunk == result.NextChunk {
			var extra bool
			err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM jourvolt_telemetry_shadow_generation_chunks
                WHERE generation_id=$1 AND chunk_index>=$2)`, generationID, result.NextChunk).Scan(&extra)
			if err != nil {
				return result, err
			}
			if extra || result.VerifySample != source.Samples {
				return result, errNativeShadowRebuildMismatch
			}
			_, err = tx.Exec(ctx, `INSERT INTO jourvolt_telemetry_shadow_generation_current
                (session_id,user_id,vehicle_id,generation_id,ordinal,source_revision,content_revision) VALUES($1,$2,$3,$4,$5,$6,$7)
                ON CONFLICT(session_id,user_id,vehicle_id) DO UPDATE SET generation_id=EXCLUDED.generation_id,ordinal=EXCLUDED.ordinal,
                source_revision=EXCLUDED.source_revision,content_revision=EXCLUDED.content_revision
                WHERE jourvolt_telemetry_shadow_generation_current.ordinal<EXCLUDED.ordinal`, source.Session, user, car, generationID, result.Ordinal, source.Revision, result.ContentRevision)
			if err != nil {
				return result, err
			}
			result.Phase = "verified"
		}
	case "verified":
		if verificationRevision != result.ContentRevision || result.NextSample != source.Samples || result.VerifyChunk != result.NextChunk || result.VerifySample != source.Samples {
			return result, errNativeShadowRebuildDrift
		}
		// Repeated finished calls do not re-publish an older generation.
	default:
		return result, errNativeShadowRebuildMismatch
	}
	result.TotalChunks = result.NextChunk
	_, err = tx.Exec(ctx, `UPDATE jourvolt_telemetry_shadow_generations SET phase=$1,next_chunk=$2,next_sample=$3,
        verify_chunk=$4,verify_sample=$5,verified_content_revision=$6,chain_sha256=$7
        WHERE id=$8 AND session_id=$9 AND user_id=$10 AND vehicle_id=$11`, result.Phase, result.NextChunk, result.NextSample, result.VerifyChunk, result.VerifySample, verificationRevision, result.ChainSHA256, generationID, source.Session, user, car)
	if err != nil {
		return result, err
	}
	if result.Phase == "verified" {
		err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM jourvolt_telemetry_shadow_generation_current
            WHERE session_id=$1 AND user_id=$2 AND vehicle_id=$3 AND generation_id=$4 AND ordinal=$5 AND source_revision=$6 AND content_revision=$7)`, source.Session, user, car, generationID, result.Ordinal, source.Revision, result.ContentRevision).Scan(&result.Selected)
		if err != nil {
			return result, err
		}
	}
	return result, tx.Commit(ctx)
}

// CASE bounds bytes transferred, not PostgreSQL detoast/JSONB slicing work.
// At most nine candidate sizes (256..1) are attempted per output chunk.
func readNativeRebuildSlice(ctx context.Context, tx pgx.Tx, source nativeRebuildSource, start int) ([]byte, int, error) {
	n := min(maxNativeShadowChunkSamples, source.Samples-start)
	for n > 0 {
		var size int
		var payload []byte
		path := fmt.Sprintf("$[%d to %d]", start, start+n-1)
		err := tx.QueryRow(ctx, `WITH candidate AS MATERIALIZED (
            SELECT convert_to(jsonb_path_query_array(CASE WHEN kind='drive' THEN route_json ELSE charge_points_json END,$4::jsonpath)::text,'UTF8') AS payload
            FROM jourvolt_telemetry_sessions WHERE id=$1 AND user_id=$2 AND vehicle_id=$3)
            SELECT octet_length(payload),CASE WHEN octet_length(payload)<=65536 THEN payload ELSE NULL END FROM candidate`, source.Session, source.User, source.Vehicle, path).Scan(&size, &payload)
		if err != nil {
			return nil, 0, err
		}
		if size <= maxNativeShadowChunkBytes {
			var points []json.RawMessage
			if size < 2 || len(payload) != size || json.Unmarshal(payload, &points) != nil || len(points) != n {
				return nil, 0, errNativeShadowRebuildMismatch
			}
			return payload, n, nil
		}
		n /= 2
	}
	return nil, 0, errors.New("native rebuild sample exceeds 65536 encoded bytes")
}

func verifyNativeRebuildChunk(ctx context.Context, tx pgx.Tx, source nativeRebuildSource, result *nativeShadowRebuildResult) error {
	var index, start, n, size int
	var payload []byte
	var hash string
	err := tx.QueryRow(ctx, `SELECT chunk_index,start_index,sample_count,octet_length(payload),
        CASE WHEN octet_length(payload)<=65536 THEN payload ELSE NULL END,payload_sha256
        FROM jourvolt_telemetry_shadow_generation_chunks WHERE generation_id=$1 AND session_id=$2 AND user_id=$3 AND vehicle_id=$4 AND chunk_index=$5`, result.GenerationID, source.Session, source.User, source.Vehicle, result.VerifyChunk).Scan(&index, &start, &n, &size, &payload, &hash)
	if err != nil {
		return err
	}
	if index != result.VerifyChunk || start != result.VerifySample || n < 1 || n > 256 || n > source.Samples-start || size < 2 || size > 65536 || len(payload) != size {
		return errNativeShadowRebuildMismatch
	}
	sum := sha256.Sum256(payload)
	var points []json.RawMessage
	if hex.EncodeToString(sum[:]) != hash || json.Unmarshal(payload, &points) != nil || len(points) != n {
		return errNativeShadowRebuildMismatch
	}
	var equal bool
	path := fmt.Sprintf("$[%d to %d]", start, start+n-1)
	err = tx.QueryRow(ctx, `SELECT jsonb_path_query_array(CASE WHEN kind='drive' THEN route_json ELSE charge_points_json END,$4::jsonpath)=$5::jsonb
        FROM jourvolt_telemetry_sessions WHERE id=$1 AND user_id=$2 AND vehicle_id=$3`, source.Session, source.User, source.Vehicle, path, payload).Scan(&equal)
	if err != nil {
		return err
	}
	if !equal {
		return errNativeShadowRebuildMismatch
	}
	frame, _ := json.Marshal(struct {
		Version                    int
		Generation, PreviousSHA    string
		Index, Start, Count, Bytes int
		PayloadSHA                 string
	}{1, result.GenerationID, result.ChainSHA256, index, start, n, size, hash})
	sum = sha256.Sum256(frame)
	result.ChainSHA256 = hex.EncodeToString(sum[:])
	result.VerifyChunk++
	result.VerifySample += n
	result.BatchChunks++
	result.BatchBytes += size
	return nil
}

func runNativeShadowRebuild(ctx context.Context, getenv func(string) string, output io.Writer) error {
	if getenv("JOURVOLT_BACKFILL_HISTORY_SUMMARIES") == "1" || getenv("JOURVOLT_AUDIT_NATIVE_SHADOW") == "1" {
		return errors.New("select one explicit maintenance mode")
	}
	dsn, user := getenv("DATABASE_URL"), getenv("JOURVOLT_SHADOW_REBUILD_USER_ID")
	car, carErr := strconv.Atoi(getenv("JOURVOLT_SHADOW_REBUILD_VEHICLE_ID"))
	publicID, idErr := strconv.Atoi(getenv("JOURVOLT_SHADOW_REBUILD_PUBLIC_ID"))
	if strings.TrimSpace(dsn) == "" || strings.TrimSpace(user) == "" || carErr != nil || idErr != nil || car <= 0 || publicID <= 0 {
		return errors.New("native rebuild requires explicit database, user, vehicle and public session scope")
	}
	pool, err := pgxpool.New(ctx, dsn)
	if err != nil {
		return err
	}
	defer pool.Close()
	result, err := rebuildNativeShadowBatch(ctx, pool, user, car, publicID, getenv("JOURVOLT_SHADOW_REBUILD_GENERATION_ID"), maxNativeShadowRebuildChunks)
	if err != nil {
		return err
	}
	return json.NewEncoder(output).Encode(result)
}
