package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"testing"
)

func rebuildShadowTestBatch(t *testing.T, s *telemetryService, ref telemetryVehicleRef, publicID int, generation string, limit int) nativeShadowRebuildResult {
	t.Helper()
	r, err := rebuildNativeShadowBatch(context.Background(), s.store.pool, ref.UserID, ref.VehicleID, publicID, generation, limit)
	if err != nil {
		t.Fatal(err)
	}
	if r.GenerationID == "" || (generation != "" && r.GenerationID != generation) || r.BatchChunks < 0 || r.BatchChunks > limit || r.BatchBytes < 0 || r.BatchBytes > limit*65536 {
		t.Fatalf("invalid bounded generation result: %+v", r)
	}
	return r
}

// Large fixtures are generated inside PostgreSQL, without thousands of ingest
// transactions or materializing an entire source array in the application.
func seedNativeShadowRebuildSource(t *testing.T, s *telemetryService, ref telemetryVehicleRef, samples int) {
	t.Helper()
	_, err := s.store.pool.Exec(context.Background(), `WITH source AS (
        SELECT COALESCE(jsonb_agg(jsonb_build_object('sequence',i,'Latitude',0,'unknown',jsonb_build_object('unicode','充電🚗','null',NULL,'zero',0)) ORDER BY i),'[]'::jsonb) AS payload
        FROM generate_series(1,$3::int) i
    ) UPDATE jourvolt_telemetry_sessions SET
        route_json=CASE WHEN kind='drive' THEN source.payload ELSE route_json END,
        charge_points_json=CASE WHEN kind='charge' THEN source.payload ELSE charge_points_json END
        FROM source WHERE user_id=$1 AND vehicle_id=$2`, ref.UserID, ref.VehicleID, samples)
	if err != nil {
		t.Fatal(err)
	}
}

func nativeShadowGenerationFingerprint(t *testing.T, s *telemetryService, ref telemetryVehicleRef) string {
	t.Helper()
	hash := sha256.New()
	for _, table := range []string{"jourvolt_telemetry_shadow_generations", "jourvolt_telemetry_shadow_generation_chunks", "jourvolt_telemetry_shadow_generation_current"} {
		rows, err := s.store.pool.Query(context.Background(), `SELECT row_to_json(t)::text FROM `+table+` t WHERE user_id=$1 AND vehicle_id=$2 ORDER BY 1`, ref.UserID, ref.VehicleID)
		if err != nil {
			t.Fatal(err)
		}
		for rows.Next() {
			var row string
			if err = rows.Scan(&row); err != nil {
				rows.Close()
				t.Fatal(err)
			}
			fmt.Fprintln(hash, table, row)
		}
		if err = rows.Err(); err != nil {
			rows.Close()
			t.Fatal(err)
		}
		rows.Close()
	}
	return hex.EncodeToString(hash.Sum(nil))
}

func requireNativeShadowGenerationCurrent(t *testing.T, s *telemetryService, ref telemetryVehicleRef, r nativeShadowRebuildResult) {
	t.Helper()
	var id string
	var ordinal, source, content int64
	if err := s.store.pool.QueryRow(context.Background(), `SELECT generation_id,ordinal,source_revision,content_revision FROM jourvolt_telemetry_shadow_generation_current WHERE user_id=$1 AND vehicle_id=$2`, ref.UserID, ref.VehicleID).Scan(&id, &ordinal, &source, &content); err != nil {
		t.Fatal(err)
	}
	if id != r.GenerationID || ordinal != r.Ordinal || source != r.SourceRevision || content != r.ContentRevision {
		t.Fatalf("current pointer=(%s,%d,%d,%d), candidate=%+v", id, ordinal, source, content, r)
	}
}

func requireNativeShadowRebuiltSource(t *testing.T, s *telemetryService, ref telemetryVehicleRef, r nativeShadowRebuildResult) {
	t.Helper()
	ctx := context.Background()
	rows, err := s.store.pool.Query(ctx, `SELECT chunk_index,start_index,sample_count,payload,payload_sha256 FROM jourvolt_telemetry_shadow_generation_chunks WHERE generation_id=$1 AND user_id=$2 AND vehicle_id=$3 ORDER BY chunk_index`, r.GenerationID, ref.UserID, ref.VehicleID)
	if err != nil {
		t.Fatal(err)
	}
	index, next := 0, 0
	for rows.Next() {
		var chunk, start, count int
		var payload []byte
		var hash string
		if err = rows.Scan(&chunk, &start, &count, &payload, &hash); err != nil {
			rows.Close()
			t.Fatal(err)
		}
		var samples []json.RawMessage
		digest := sha256.Sum256(payload)
		if err = json.Unmarshal(payload, &samples); err != nil {
			rows.Close()
			t.Fatal(err)
		}
		if chunk != index || start != next || count != len(samples) || count < 1 || count > 256 || len(payload) > 65536 || hash != hex.EncodeToString(digest[:]) {
			rows.Close()
			t.Fatalf("invalid generation chunk %d: start=%d count=%d bytes=%d hash=%s", chunk, start, count, len(payload), hash)
		}
		index++
		next += count
	}
	if err = rows.Err(); err != nil {
		rows.Close()
		t.Fatal(err)
	}
	rows.Close()
	if index != r.TotalChunks || next != r.TotalSamples || r.NextChunk != index || r.NextSample != next || r.VerifyChunk != index || r.VerifySample != next || r.Phase != "verified" {
		t.Fatalf("incomplete generation: chunks=%d samples=%d result=%+v", index, next, r)
	}
	// Compare in PostgreSQL so unknown fields, numeric values, nulls, ordering,
	// and every raw sample are tested without a typed telemetry round trip.
	var equal bool
	if err = s.store.pool.QueryRow(ctx, `SELECT
        COALESCE((SELECT jsonb_agg(p.sample ORDER BY c.chunk_index,p.n)
            FROM jourvolt_telemetry_shadow_generation_chunks c
            CROSS JOIN LATERAL jsonb_array_elements(convert_from(c.payload,'UTF8')::jsonb) WITH ORDINALITY p(sample,n)
            WHERE c.generation_id=$1 AND c.user_id=$2 AND c.vehicle_id=$3),'[]'::jsonb)
        = CASE WHEN s.kind='drive' THEN s.route_json ELSE s.charge_points_json END
        FROM jourvolt_telemetry_sessions s WHERE s.user_id=$2 AND s.vehicle_id=$3`, r.GenerationID, ref.UserID, ref.VehicleID).Scan(&equal); err != nil || !equal {
		t.Fatalf("rebuilt source differs: equal=%v err=%v", equal, err)
	}
	if len(r.ChainSHA256) != 64 {
		t.Fatalf("missing verification chain: %+v", r)
	}
}

func finishNativeShadowRebuild(t *testing.T, s *telemetryService, ref telemetryVehicleRef, publicID int, r nativeShadowRebuildResult) nativeShadowRebuildResult {
	t.Helper()
	for calls := 0; r.Phase != "verified"; calls++ {
		if calls > 20 {
			t.Fatalf("generation did not finish: %+v", r)
		}
		previous := r
		r = rebuildShadowTestBatch(t, s, ref, publicID, r.GenerationID, 16)
		if previous.Phase == "building" && (r.Phase == "verified" || r.VerifyChunk != 0 || r.VerifySample != 0) {
			t.Fatalf("build crossed committed verification boundary: before=%+v after=%+v", previous, r)
		}
		if previous.Phase == "verifying" && (r.NextChunk != previous.NextChunk || r.NextSample != previous.NextSample) {
			t.Fatalf("verification changed frozen build cursor: before=%+v after=%+v", previous, r)
		}
	}
	return r
}

func TestTelemetryPostgresNativeShadowRebuildLegacyCoverage(t *testing.T) {
	for _, kind := range []string{"drive", "charge"} {
		for _, state := range []string{"missing", "prefix", "stale"} {
			t.Run(kind+"/"+state, func(t *testing.T) {
				s, ref, publicID := nativeShadowAuditFixture(t, kind, 3)
				seedNativeShadowRebuildSource(t, s, ref, 3)
				statement := `DELETE FROM jourvolt_telemetry_detail_shadow WHERE user_id=$1 AND vehicle_id=$2`
				if state == "prefix" {
					statement = `UPDATE jourvolt_telemetry_detail_shadow SET prefix_count=1,started_with_session=false WHERE user_id=$1 AND vehicle_id=$2`
				} else if state == "stale" {
					statement = `UPDATE jourvolt_telemetry_detail_shadow SET stale=true WHERE user_id=$1 AND vehicle_id=$2`
				}
				if _, err := s.store.pool.Exec(context.Background(), statement, ref.UserID, ref.VehicleID); err != nil {
					t.Fatal(err)
				}
				original := nativeShadowFingerprint(t, s, ref)
				first := rebuildShadowTestBatch(t, s, ref, publicID, "", 16)
				if first.Phase != "verifying" || first.Selected || first.VerifyChunk != 0 || first.VerifySample != 0 || first.NextSample != 3 || first.BatchChunks != 1 {
					t.Fatalf("build did not stop before verification: %+v", first)
				}
				var pointers int
				if err := s.store.pool.QueryRow(context.Background(), `SELECT count(*) FROM jourvolt_telemetry_shadow_generation_current WHERE user_id=$1`, ref.UserID).Scan(&pointers); err != nil || pointers != 0 {
					t.Fatalf("unverified generation selected: count=%d err=%v", pointers, err)
				}
				last := finishNativeShadowRebuild(t, s, ref, publicID, first)
				if !last.Selected || last.TotalSamples != 3 {
					t.Fatalf("generation not selected: %+v", last)
				}
				requireNativeShadowRebuiltSource(t, s, ref, last)
				requireNativeShadowGenerationCurrent(t, s, ref, last)
				beforeRepeat := nativeShadowGenerationFingerprint(t, s, ref)
				repeat := rebuildShadowTestBatch(t, s, ref, publicID, last.GenerationID, 16)
				if repeat.Phase != "verified" || repeat.BatchChunks != 0 || repeat.BatchBytes != 0 || repeat.ChainSHA256 != last.ChainSHA256 || nativeShadowGenerationFingerprint(t, s, ref) != beforeRepeat {
					t.Fatalf("verified resume changed generation: %+v", repeat)
				}
				if nativeShadowFingerprint(t, s, ref) != original {
					t.Fatal("rebuild changed authoritative history or legacy shadow")
				}
			})
		}
	}
}

func TestNativeShadowRebuildProcessHelper(t *testing.T) {
	if os.Getenv("JOURVOLT_SHADOW_REBUILD_TEST_HELPER") != "1" {
		return
	}
	getenv := func(key string) string {
		if key == "DATABASE_URL" {
			return os.Getenv("JOURVOLT_TEST_DATABASE_URL")
		}
		return os.Getenv(key)
	}
	if err := runNativeShadowRebuild(context.Background(), getenv, os.Stdout); err != nil {
		t.Fatal(err)
	}
}

func nativeShadowRebuildProcess(t *testing.T, ref telemetryVehicleRef, publicID int, generation string) nativeShadowRebuildResult {
	t.Helper()
	cmd := exec.Command(os.Args[0], "-test.run=^TestNativeShadowRebuildProcessHelper$", "-test.count=1")
	cmd.Env = append(os.Environ(), "JOURVOLT_SHADOW_REBUILD_TEST_HELPER=1", "JOURVOLT_REBUILD_NATIVE_SHADOW=1", "JOURVOLT_SHADOW_REBUILD_USER_ID="+ref.UserID, "JOURVOLT_SHADOW_REBUILD_VEHICLE_ID="+strconv.Itoa(ref.VehicleID), "JOURVOLT_SHADOW_REBUILD_PUBLIC_ID="+strconv.Itoa(publicID), "JOURVOLT_SHADOW_REBUILD_GENERATION_ID="+generation)
	output, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("resume in fresh process: %v %s", err, output)
	}
	var result nativeShadowRebuildResult
	if err = json.NewDecoder(bytes.NewReader(output)).Decode(&result); err != nil {
		t.Fatalf("decode fresh process result: %v %s", err, output)
	}
	return result
}

func TestTelemetryPostgresNativeShadowRebuildResumeAndProcess(t *testing.T) {
	for _, kind := range []string{"drive", "charge"} {
		t.Run(kind, func(t *testing.T) {
			s, ref, publicID := nativeShadowAuditFixture(t, kind, 0)
			seedNativeShadowRebuildSource(t, s, ref, 17*256+3)
			original := nativeShadowFingerprint(t, s, ref)
			first := rebuildShadowTestBatch(t, s, ref, publicID, "", 16)
			if first.Phase != "building" || first.NextChunk != 16 || first.NextSample != 16*256 || first.BatchChunks != 16 || first.VerifyChunk != 0 || first.Selected {
				t.Fatalf("first batch=%+v", first)
			}
			built := nativeShadowRebuildProcess(t, ref, publicID, first.GenerationID)
			if built.GenerationID != first.GenerationID || built.Phase != "verifying" || built.NextChunk != 18 || built.NextSample != 17*256+3 || built.BatchChunks != 2 || built.VerifyChunk != 0 || built.VerifySample != 0 || built.Selected {
				t.Fatalf("build resume=%+v", built)
			}
			verifiedPrefix := nativeShadowRebuildProcess(t, ref, publicID, first.GenerationID)
			if verifiedPrefix.Phase != "verifying" || verifiedPrefix.VerifyChunk != 16 || verifiedPrefix.VerifySample != 16*256 || verifiedPrefix.BatchChunks != 16 || verifiedPrefix.Selected {
				t.Fatalf("verification prefix=%+v", verifiedPrefix)
			}
			last := nativeShadowRebuildProcess(t, ref, publicID, first.GenerationID)
			if last.Phase != "verified" || last.BatchChunks != 2 || !last.Selected || last.ContentRevision != built.ContentRevision || last.SourceRevision != first.SourceRevision || last.Ordinal != first.Ordinal || last.ChainSHA256 == verifiedPrefix.ChainSHA256 {
				t.Fatalf("verification resume=%+v", last)
			}
			requireNativeShadowRebuiltSource(t, s, ref, last)
			requireNativeShadowGenerationCurrent(t, s, ref, last)
			if nativeShadowFingerprint(t, s, ref) != original {
				t.Fatal("process resume changed original data")
			}
		})
	}
}

func TestTelemetryPostgresNativeShadowRebuildConcurrentResume(t *testing.T) {
	s, ref, publicID := nativeShadowAuditFixture(t, "drive", 0)
	seedNativeShadowRebuildSource(t, s, ref, 4*256+1)
	first := rebuildShadowTestBatch(t, s, ref, publicID, "", 1)
	var wg sync.WaitGroup
	results := make(chan nativeShadowRebuildResult, 2)
	errs := make(chan error, 2)
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			r, err := rebuildNativeShadowBatch(context.Background(), s.store.pool, ref.UserID, ref.VehicleID, publicID, first.GenerationID, 1)
			results <- r
			errs <- err
		}()
	}
	wg.Wait()
	close(results)
	close(errs)
	for err := range errs {
		if err != nil {
			t.Fatal(err)
		}
	}
	seen := map[int]bool{}
	for r := range results {
		seen[r.NextChunk] = true
		if r.Phase != "building" || r.BatchChunks != 1 || r.NextSample != r.NextChunk*256 {
			t.Fatalf("concurrent batch=%+v", r)
		}
	}
	if !seen[2] || !seen[3] || len(seen) != 2 {
		t.Fatalf("concurrent resume skipped or repeated cursor: %v", seen)
	}
	last := finishNativeShadowRebuild(t, s, ref, publicID, first)
	requireNativeShadowRebuiltSource(t, s, ref, last)
	var generations int
	if err := s.store.pool.QueryRow(context.Background(), `SELECT count(*) FROM jourvolt_telemetry_shadow_generations WHERE user_id=$1`, ref.UserID).Scan(&generations); err != nil || generations != 1 {
		t.Fatalf("resume created duplicate generation: count=%d err=%v", generations, err)
	}
}

func TestTelemetryPostgresNativeShadowRebuildByteBoundsAndEmpty(t *testing.T) {
	for _, test := range []struct {
		name       string
		values     []string
		wantChunks int
		wantBytes  int
		wantError  bool
	}{
		{"empty", []string{}, 0, 0, false},
		{"exact_ascii_bytes", []string{strings.Repeat("x", 65532)}, 1, 65536, false},
		{"exact_utf8_bytes", []string{strings.Repeat("界", 21844)}, 1, 65536, false},
		{"utf8_byte_split", []string{strings.Repeat("界", 14000), strings.Repeat("充", 14000)}, 2, 84008, false},
		{"oversize_ascii", []string{strings.Repeat("x", 65533)}, 0, 0, true},
		{"oversize_utf8", []string{strings.Repeat("界", 21844) + "x"}, 0, 0, true},
	} {
		t.Run(test.name, func(t *testing.T) {
			s, ref, publicID := nativeShadowAuditFixture(t, "drive", 0)
			payload, err := json.Marshal(test.values)
			if err != nil {
				t.Fatal(err)
			}
			if _, err = s.store.pool.Exec(context.Background(), `UPDATE jourvolt_telemetry_sessions SET route_json=$3::jsonb WHERE user_id=$1 AND vehicle_id=$2`, ref.UserID, ref.VehicleID, payload); err != nil {
				t.Fatal(err)
			}
			original := nativeShadowFingerprint(t, s, ref)
			before := nativeShadowGenerationFingerprint(t, s, ref)
			first, err := rebuildNativeShadowBatch(context.Background(), s.store.pool, ref.UserID, ref.VehicleID, publicID, "", 16)
			if test.wantError {
				if err == nil || nativeShadowGenerationFingerprint(t, s, ref) != before || nativeShadowFingerprint(t, s, ref) != original {
					t.Fatalf("oversize sample accepted or partially committed: result=%+v err=%v", first, err)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if first.Phase != "verifying" || first.VerifyChunk != 0 || first.NextChunk != test.wantChunks || first.BatchChunks != test.wantChunks || first.BatchBytes != test.wantBytes {
				t.Fatalf("incorrect byte/count boundary: %+v", first)
			}
			last := finishNativeShadowRebuild(t, s, ref, publicID, first)
			requireNativeShadowRebuiltSource(t, s, ref, last)
			if len(test.values) == 0 {
				emptyDigest := sha256.Sum256(nil)
				if last.ChainSHA256 == hex.EncodeToString(emptyDigest[:]) {
					t.Fatal("empty generation used an unscoped empty hash")
				}
				other := finishNativeShadowRebuild(t, s, ref, publicID, rebuildShadowTestBatch(t, s, ref, publicID, "", 16))
				if other.ChainSHA256 == last.ChainSHA256 || other.GenerationID == last.GenerationID || other.Ordinal <= last.Ordinal {
					t.Fatal("empty-generation chain is not bound to generation identity")
				}
			}
			if nativeShadowFingerprint(t, s, ref) != original {
				t.Fatal("byte-bound rebuild changed source")
			}
		})
	}
}

func TestTelemetryPostgresNativeShadowRebuildSourceDriftAndScope(t *testing.T) {
	for _, phase := range []string{"building", "verifying", "verified"} {
		for _, mutation := range []string{"source", "started", "ended", "completion", "source_kind"} {
			t.Run(phase+"/"+mutation, func(t *testing.T) {
				s, ref, publicID := nativeShadowAuditFixture(t, "drive", 0)
				seedNativeShadowRebuildSource(t, s, ref, 257)
				first := rebuildShadowTestBatch(t, s, ref, publicID, "", 1)
				if phase != "building" {
					first = rebuildShadowTestBatch(t, s, ref, publicID, first.GenerationID, 16)
				}
				if phase == "verified" {
					first = finishNativeShadowRebuild(t, s, ref, publicID, first)
				}
				if first.Phase != phase {
					t.Fatalf("fixture phase=%+v", first)
				}
				assignment := map[string]string{
					"source":      `route_json=jsonb_set(route_json,'{0,sequence}','999999')`,
					"started":     `started_at=started_at-interval '1 second'`,
					"ended":       `ended_at=ended_at+interval '1 second'`,
					"completion":  `completion_key=completion_key||'-changed'`,
					"source_kind": `source='local_import'`,
				}[mutation]
				if _, err := s.store.pool.Exec(context.Background(), `UPDATE jourvolt_telemetry_sessions SET `+assignment+` WHERE user_id=$1 AND vehicle_id=$2`, ref.UserID, ref.VehicleID); err != nil {
					t.Fatal(err)
				}
				before := nativeShadowGenerationFingerprint(t, s, ref)
				original := nativeShadowFingerprint(t, s, ref)
				if _, err := rebuildNativeShadowBatch(context.Background(), s.store.pool, ref.UserID, ref.VehicleID, publicID, first.GenerationID, 16); err == nil {
					t.Fatal("source identity/revision drift accepted")
				}
				if nativeShadowGenerationFingerprint(t, s, ref) != before || nativeShadowFingerprint(t, s, ref) != original {
					t.Fatal("rejected source drift advanced state")
				}
			})
		}
	}
	t.Run("scope", func(t *testing.T) {
		s, ref, publicID := nativeShadowAuditFixture(t, "charge", 0)
		first := rebuildShadowTestBatch(t, s, ref, publicID, "", 1)
		before := nativeShadowGenerationFingerprint(t, s, ref)
		for _, scope := range []struct {
			user   string
			car    int
			public int
		}{
			{"other-user", ref.VehicleID, publicID},
			{ref.UserID, ref.VehicleID + 100000, publicID},
			{ref.UserID, ref.VehicleID, publicID + 100000},
		} {
			if _, err := rebuildNativeShadowBatch(context.Background(), s.store.pool, scope.user, scope.car, scope.public, first.GenerationID, 16); err == nil {
				t.Fatal("cross-owner/vehicle/public ID resume accepted")
			}
		}
		if nativeShadowGenerationFingerprint(t, s, ref) != before {
			t.Fatal("invalid scope changed generation")
		}
	})
}

func TestTelemetryPostgresNativeShadowRebuildImmutableAndCascadeGuards(t *testing.T) {
	s, ref, publicID := nativeShadowAuditFixture(t, "drive", 0)
	seedNativeShadowRebuildSource(t, s, ref, 257)
	building := rebuildShadowTestBatch(t, s, ref, publicID, "", 1)
	ctx := context.Background()
	for _, assignment := range []string{
		`id=id||'-changed'`, `session_id=session_id||'-changed'`, `user_id=user_id||'-changed'`, `vehicle_id=vehicle_id+1`, `public_id=public_id+1`, `ordinal=ordinal+1`,
		`source_revision=source_revision+1`, `source_started_at=source_started_at-interval '1 second'`, `source_ended_at=source_ended_at+interval '1 second'`,
		`source_completion_key=source_completion_key||'-changed'`, `stream='charge'`, `expected_samples=expected_samples+1`,
	} {
		if _, err := s.store.pool.Exec(ctx, `UPDATE jourvolt_telemetry_shadow_generations SET `+assignment+` WHERE id=$1`, building.GenerationID); err == nil {
			t.Fatalf("generation identity pin was mutable: %s", assignment)
		}
	}
	for _, statement := range []string{
		`UPDATE jourvolt_telemetry_shadow_generation_chunks SET payload=payload WHERE generation_id=$1`,
		`DELETE FROM jourvolt_telemetry_shadow_generation_chunks WHERE generation_id=$1`,
		`DELETE FROM jourvolt_telemetry_shadow_generations WHERE id=$1`,
	} {
		if _, err := s.store.pool.Exec(ctx, statement, building.GenerationID); err == nil {
			t.Fatalf("immutable generated data accepted DML: %s", statement)
		}
	}
	if _, err := s.store.pool.Exec(ctx, `TRUNCATE jourvolt_telemetry_shadow_generation_chunks`); err == nil {
		t.Fatal("generated chunks allowed TRUNCATE")
	}
	frozen := rebuildShadowTestBatch(t, s, ref, publicID, building.GenerationID, 16)
	for _, phase := range []string{"verifying", "verified"} {
		if frozen.Phase != phase {
			t.Fatalf("unexpected frozen phase=%+v", frozen)
		}
		before := nativeShadowGenerationFingerprint(t, s, ref)
		for _, statement := range []string{
			`INSERT INTO jourvolt_telemetry_shadow_generation_chunks(generation_id,session_id,user_id,vehicle_id,chunk_index,start_index,sample_count,payload,payload_sha256)
                SELECT generation_id,session_id,user_id,vehicle_id,chunk_index,start_index,sample_count,payload,payload_sha256
                FROM jourvolt_telemetry_shadow_generation_chunks WHERE generation_id=$1 AND chunk_index=0 ON CONFLICT DO NOTHING`,
			`INSERT INTO jourvolt_telemetry_shadow_generation_chunks(generation_id,session_id,user_id,vehicle_id,chunk_index,start_index,sample_count,payload,payload_sha256)
                SELECT generation_id,session_id,user_id,vehicle_id,99,99999,sample_count,payload,payload_sha256
                FROM jourvolt_telemetry_shadow_generation_chunks WHERE generation_id=$1 AND chunk_index=0`,
		} {
			if _, err := s.store.pool.Exec(ctx, statement, frozen.GenerationID); err == nil {
				t.Fatalf("%s generation accepted insertion (including duplicate conflict): %s", phase, statement)
			}
		}
		if nativeShadowGenerationFingerprint(t, s, ref) != before {
			t.Fatal("rejected frozen insert changed revision or chunks")
		}
		if phase == "verifying" {
			frozen = finishNativeShadowRebuild(t, s, ref, publicID, frozen)
		}
	}
	original := nativeShadowFingerprint(t, s, ref)
	generationOriginal := nativeShadowGenerationFingerprint(t, s, ref)
	tx, err := s.store.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(ctx)
	if _, err = tx.Exec(ctx, `DELETE FROM jourvolt_users WHERE id=$1`, ref.UserID); err != nil {
		t.Fatalf("account cascade blocked by generated-data guard: %v", err)
	}
	for _, table := range []string{"jourvolt_telemetry_sessions", "jourvolt_telemetry_shadow_generations", "jourvolt_telemetry_shadow_generation_chunks", "jourvolt_telemetry_shadow_generation_current"} {
		var count int
		if err = tx.QueryRow(ctx, `SELECT count(*) FROM `+table+` WHERE user_id=$1`, ref.UserID).Scan(&count); err != nil || count != 0 {
			t.Fatalf("cascade left %s count=%d err=%v", table, count, err)
		}
	}
	if err = tx.Rollback(ctx); err != nil {
		t.Fatal(err)
	}
	if nativeShadowFingerprint(t, s, ref) != original || nativeShadowGenerationFingerprint(t, s, ref) != generationOriginal {
		t.Fatal("rolled-back account deletion changed original/generation state")
	}
	requireNativeShadowGenerationCurrent(t, s, ref, frozen)
}

func TestTelemetryPostgresNativeShadowRebuildPublicationOrdering(t *testing.T) {
	for _, order := range []string{"older_first", "newer_first"} {
		t.Run(order, func(t *testing.T) {
			s, ref, publicID := nativeShadowAuditFixture(t, "drive", 2)
			original := nativeShadowFingerprint(t, s, ref)
			older := rebuildShadowTestBatch(t, s, ref, publicID, "", 16)
			newer := rebuildShadowTestBatch(t, s, ref, publicID, "", 16)
			if newer.Ordinal <= older.Ordinal || newer.GenerationID == older.GenerationID {
				t.Fatalf("generation order is not monotonic: older=%+v newer=%+v", older, newer)
			}
			if order == "older_first" {
				older = finishNativeShadowRebuild(t, s, ref, publicID, older)
				if !older.Selected {
					t.Fatal("first verified generation not selected")
				}
				requireNativeShadowGenerationCurrent(t, s, ref, older)
				newer = finishNativeShadowRebuild(t, s, ref, publicID, newer)
			} else {
				newer = finishNativeShadowRebuild(t, s, ref, publicID, newer)
				older = finishNativeShadowRebuild(t, s, ref, publicID, older)
				if older.Selected || older.Phase != "verified" {
					t.Fatalf("older loser was selected or rejected: %+v", older)
				}
			}
			if !newer.Selected {
				t.Fatal("newer verified generation not selected")
			}
			requireNativeShadowRebuiltSource(t, s, ref, older)
			requireNativeShadowRebuiltSource(t, s, ref, newer)
			requireNativeShadowGenerationCurrent(t, s, ref, newer)
			beforeRepeat := nativeShadowGenerationFingerprint(t, s, ref)
			repeat := rebuildShadowTestBatch(t, s, ref, publicID, older.GenerationID, 16)
			if repeat.Phase != "verified" || repeat.Selected || repeat.BatchChunks != 0 || repeat.ChainSHA256 != older.ChainSHA256 || nativeShadowGenerationFingerprint(t, s, ref) != beforeRepeat {
				t.Fatalf("old verified resume republished or changed stamp: %+v", repeat)
			}
			requireNativeShadowGenerationCurrent(t, s, ref, newer)
			if nativeShadowFingerprint(t, s, ref) != original {
				t.Fatal("competing generations changed original source")
			}
		})
	}
}

func TestNativeShadowRebuildCommandRequiresExplicitInputs(t *testing.T) {
	valid := map[string]string{"JOURVOLT_REBUILD_NATIVE_SHADOW": "1", "DATABASE_URL": "not-a-dsn", "JOURVOLT_SHADOW_REBUILD_USER_ID": "user", "JOURVOLT_SHADOW_REBUILD_VEHICLE_ID": "1", "JOURVOLT_SHADOW_REBUILD_PUBLIC_ID": "1"}
	for _, missing := range []string{"DATABASE_URL", "JOURVOLT_SHADOW_REBUILD_USER_ID", "JOURVOLT_SHADOW_REBUILD_VEHICLE_ID", "JOURVOLT_SHADOW_REBUILD_PUBLIC_ID"} {
		t.Run(missing, func(t *testing.T) {
			var output bytes.Buffer
			if err := runNativeShadowRebuild(context.Background(), func(key string) string {
				if key == missing {
					return ""
				}
				return valid[key]
			}, &output); err == nil || !strings.Contains(err.Error(), "requires explicit database") || output.Len() != 0 {
				t.Fatal("incomplete explicit maintenance scope accepted")
			}
		})
	}
	for _, flag := range []string{"JOURVOLT_BACKFILL_HISTORY_SUMMARIES", "JOURVOLT_AUDIT_NATIVE_SHADOW"} {
		t.Run(flag, func(t *testing.T) {
			var output bytes.Buffer
			err := runNativeShadowRebuild(context.Background(), func(key string) string {
				if key == flag {
					return "1"
				}
				return valid[key]
			}, &output)
			if err == nil || err.Error() != "select one explicit maintenance mode" || output.Len() != 0 {
				t.Fatalf("mixed maintenance modes were not rejected before connection: %v", err)
			}
		})
	}
	for _, limit := range []int{0, 17} {
		if _, err := rebuildNativeShadowBatch(context.Background(), nil, "user", 1, 1, "", limit); err == nil {
			t.Fatalf("invalid rebuild bound %d accepted", limit)
		}
	}
}
