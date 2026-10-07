package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
)

func TestNativeShadowChunkBounds(t *testing.T) {
	for _, test := range []struct {
		name      string
		points    []string
		counts    []int
		wantError bool
	}{
		{"empty", nil, nil, false},
		{"sample_limit", make([]string, maxNativeShadowChunkSamples+1), []int{256, 1}, false},
		{"exact_bytes", []string{strings.Repeat("x", maxNativeShadowChunkBytes-4)}, []int{1}, false},
		{"over_bytes", []string{strings.Repeat("x", maxNativeShadowChunkBytes-3)}, nil, true},
		{"byte_split", []string{strings.Repeat("x", 40000), strings.Repeat("y", 40000)}, []int{1, 1}, false},
	} {
		t.Run(test.name, func(t *testing.T) {
			var counts []int
			var decoded []string
			err := encodeNativeShadowChunks(test.points, func(payload []byte, count int) error {
				if len(payload) > maxNativeShadowChunkBytes || count > maxNativeShadowChunkSamples {
					t.Fatal("unbounded chunk")
				}
				var part []string
				if err := json.Unmarshal(payload, &part); err != nil {
					return err
				}
				if len(part) != count {
					t.Fatal("incorrect sample count")
				}
				counts = append(counts, count)
				decoded = append(decoded, part...)
				return nil
			})
			if (err != nil) != test.wantError || !reflect.DeepEqual(counts, test.counts) {
				t.Fatalf("counts=%v err=%v", counts, err)
			}
			if !test.wantError && !reflect.DeepEqual(decoded, test.points) {
				t.Fatal("chunking changed samples")
			}
		})
	}
	if err := encodeNativeShadowChunks([]float64{math.NaN()}, func([]byte, int) error { t.Fatal("invalid sample emitted"); return nil }); err == nil {
		t.Fatal("invalid numeric sample accepted")
	}
	want := errors.New("emit failed")
	if err := encodeNativeShadowChunks([]int{1}, func([]byte, int) error { return want }); !errors.Is(err, want) {
		t.Fatal(err)
	}
}

type nativeShadowEvidence struct {
	ID, Stream                         string
	Prefix, Count, Chunks, StoredCount int
	Started, Stale, Ended              bool
	Revision, CurrentRevision          int64
	Samples, Legacy                    []any
}

func readNativeShadowEvidence(t *testing.T, s *telemetryService, ref telemetryVehicleRef) nativeShadowEvidence {
	t.Helper()
	ctx := context.Background()
	var e nativeShadowEvidence
	var legacy []byte
	err := s.store.pool.QueryRow(ctx, `SELECT d.session_id,d.stream,d.prefix_count,d.sample_count,d.chunk_count,d.started_with_session,d.stale,d.source_revision,
        s.history_summary_revision,s.ended_at IS NOT NULL,
        CASE WHEN s.kind='drive' THEN s.route_point_count ELSE s.charge_point_count END,
        CASE WHEN s.kind='drive' THEN s.route_json ELSE s.charge_points_json END
        FROM jourvolt_telemetry_detail_shadow d JOIN jourvolt_telemetry_sessions s
          ON (s.id,s.user_id,s.vehicle_id)=(d.session_id,d.user_id,d.vehicle_id)
        WHERE d.user_id=$1 AND d.vehicle_id=$2`, ref.UserID, ref.VehicleID).Scan(&e.ID, &e.Stream, &e.Prefix, &e.Count, &e.Chunks, &e.Started, &e.Stale, &e.Revision, &e.CurrentRevision, &e.Ended, &e.StoredCount, &legacy)
	if err != nil {
		t.Fatal(err)
	}
	if err = json.Unmarshal(legacy, &e.Legacy); err != nil {
		t.Fatal(err)
	}
	rows, err := s.store.pool.Query(ctx, `SELECT chunk_index,start_index,sample_count,payload,payload_sha256 FROM jourvolt_telemetry_detail_chunks WHERE session_id=$1 AND user_id=$2 AND vehicle_id=$3 ORDER BY chunk_index`, e.ID, ref.UserID, ref.VehicleID)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	index, next := 0, e.Prefix
	for rows.Next() {
		var chunk, start, count int
		var payload []byte
		var hash string
		if err = rows.Scan(&chunk, &start, &count, &payload, &hash); err != nil {
			t.Fatal(err)
		}
		digest := sha256.Sum256(payload)
		var part []any
		if err = json.Unmarshal(payload, &part); err != nil {
			t.Fatal(err)
		}
		if chunk != index || start != next || count != len(part) || count > maxNativeShadowChunkSamples || len(payload) > maxNativeShadowChunkBytes || hash != hex.EncodeToString(digest[:]) {
			t.Fatal("invalid ordered chunk/hash/limits")
		}
		e.Samples = append(e.Samples, part...)
		index++
		next += count
	}
	if err = rows.Err(); err != nil {
		t.Fatal(err)
	}
	if index != e.Chunks || next != e.Count {
		t.Fatalf("manifest/chunks mismatch: %+v", e)
	}
	return e
}

func nativeShadowFixture(t *testing.T) (*telemetryService, telemetryVehicleRef, time.Time) {
	t.Helper()
	s, user, car := openHistoryQueryTestDB(t)
	s.config = &telemetryConfig{StopDebounce: defaultDriveStopDebounce}
	ref := telemetryVehicleRef{UserID: user, VehicleID: car, VINHash: "shadow-" + mustRandomToken(t)}
	if err := s.registerVehicle(context.Background(), ref); err != nil {
		t.Fatal(err)
	}
	return s, ref, time.Date(2026, 9, 7, 0, 0, 0, 0, time.UTC)
}

func nativeShadowEvent(ref telemetryVehicleRef, start time.Time, step int, field string, value any) telemetryRecord {
	return telemetryRecord{VINHash: ref.VINHash, FieldName: field, Value: value, ObservedAt: start.Add(time.Duration(step) * time.Second), EventID: fmt.Sprintf("shadow-%d", step)}
}

func requireNativeShadowIngest(t *testing.T, s *telemetryService, record telemetryRecord, want int) {
	t.Helper()
	if n, err := s.ingest(context.Background(), record); err != nil || n != want {
		t.Fatalf("accepted=%d want=%d err=%v", n, want, err)
	}
}

func nativeShadowFingerprint(t *testing.T, s *telemetryService, ref telemetryVehicleRef) string {
	t.Helper()
	hash := sha256.New()
	for _, table := range []string{"jourvolt_telemetry_event_buffer", "jourvolt_telemetry_latest", "jourvolt_telemetry_route_points", "jourvolt_telemetry_sessions", "jourvolt_telemetry_detail_shadow", "jourvolt_telemetry_detail_chunks"} {
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

func TestTelemetryPostgresNativeShadowRoundTripAndReplay(t *testing.T) {
	for _, kind := range []string{"drive", "charge"} {
		t.Run(kind, func(t *testing.T) {
			s, ref, start := nativeShadowFixture(t)
			fields := []string{"Gear", "Location", "VehicleSpeed", "Location", "Gear"}
			values := []any{"D", map[string]any{"latitude": 0.0, "longitude": 121.0}, 0.0, map[string]any{"latitude": 31.0, "longitude": 0.0}, "P"}
			if kind == "charge" {
				fields = []string{"DetailedChargeState", "ACChargingEnergyIn", "ACChargingEnergyIn", "ACChargingPower", "Location", "Soc", "DetailedChargeState"}
				values = []any{"Charging", 10.0, 12.0, 0.0, map[string]any{"latitude": 0.0, "longitude": 121.0}, 0.0, "Complete"}
			}
			var events []telemetryRecord
			for i, field := range fields {
				r := nativeShadowEvent(ref, start, i, field, values[i])
				events = append(events, r)
				requireNativeShadowIngest(t, s, r, 1)
			}
			if kind == "drive" {
				if n, err := s.finalizeDuePostgres(context.Background(), start.Add(time.Hour)); err != nil || n != 1 {
					t.Fatalf("finalized=%d err=%v", n, err)
				}
			}
			e := readNativeShadowEvidence(t, s, ref)
			if e.Prefix != 0 || !e.Started || e.Stale || !e.Ended || e.Revision != e.CurrentRevision || e.Count != e.StoredCount || !reflect.DeepEqual(e.Samples, e.Legacy) {
				t.Fatalf("incomplete/different shadow: %+v", e)
			}
			before := nativeShadowFingerprint(t, s, ref)
			// Reconstruct the service, preserving only its durable store/config.
			s = &telemetryService{store: s.store, config: s.config}
			for _, event := range events {
				requireNativeShadowIngest(t, s, event, 0)
			}
			if after := nativeShadowFingerprint(t, s, ref); after != before {
				t.Fatal("replay changed committed data")
			}
		})
	}
}

func TestTelemetryPostgresNativeShadowLegacyPrefixAndOldWriter(t *testing.T) {
	s, ref, start := nativeShadowFixture(t)
	ctx := context.Background()
	id := "legacy-shadow-" + mustRandomToken(t)
	initial := []telemetryRoutePoint{{ObservedAt: start, Latitude: 1, Longitude: 2}}
	payload, _ := json.Marshal(initial)
	var publicID int
	if err := s.store.pool.QueryRow(ctx, `INSERT INTO jourvolt_telemetry_sessions(id,user_id,vehicle_id,kind,started_at,route_json,source) VALUES($1,$2,$3,'drive',$4,$5::jsonb,'telemetry_mqtt') RETURNING public_id`, id, ref.UserID, ref.VehicleID, start, payload).Scan(&publicID); err != nil {
		t.Fatal(err)
	}
	requireNativeShadowIngest(t, s, nativeShadowEvent(ref, start, 1, "Location", map[string]any{"latitude": 3.0, "longitude": 4.0}), 1)
	e := readNativeShadowEvidence(t, s, ref)
	if e.ID != id || e.Prefix != 1 || e.Started || e.Stale || e.Count != 2 || !reflect.DeepEqual(e.Samples, e.Legacy[1:]) {
		t.Fatalf("legacy prefix wrongly claimed: %+v", e)
	}
	if _, err := s.store.pool.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET route_json=jsonb_set(route_json,'{0,Latitude}','9'::jsonb) WHERE id=$1`, id); err != nil {
		t.Fatal(err)
	}
	changed := readNativeShadowEvidence(t, s, ref)
	if changed.Revision == changed.CurrentRevision {
		t.Fatal("old writer not detectable")
	}
	requireNativeShadowIngest(t, s, nativeShadowEvent(ref, start, 2, "VehicleSpeed", 0.0), 1)
	requireNativeShadowIngest(t, s, nativeShadowEvent(ref, start, 3, "Location", map[string]any{"latitude": 5.0, "longitude": 6.0}), 1)
	after := readNativeShadowEvidence(t, s, ref)
	if !after.Stale || after.Count != e.Count || !reflect.DeepEqual(after.Samples, e.Samples) || len(after.Legacy) != 3 {
		t.Fatalf("stale shadow rebased: %+v", after)
	}
	var retained int
	if err := s.store.pool.QueryRow(ctx, `SELECT public_id FROM jourvolt_telemetry_sessions WHERE id=$1`, id).Scan(&retained); err != nil || retained != publicID {
		t.Fatal("legacy public ID changed", err)
	}
}

func TestTelemetryPostgresNativeShadowRollbackAndMalformedLegacy(t *testing.T) {
	s, ref, start := nativeShadowFixture(t)
	ctx := context.Background()
	requireNativeShadowIngest(t, s, nativeShadowEvent(ref, start, 0, "Gear", "D"), 1)
	before := nativeShadowFingerprint(t, s, ref)
	name := "shadow_fault_" + strings.ReplaceAll(mustRandomToken(t), "-", "_")
	function, trigger := pgx.Identifier{name}.Sanitize(), pgx.Identifier{name + "_trigger"}.Sanitize()
	definition := fmt.Sprintf(`CREATE FUNCTION %s() RETURNS trigger AS $$ BEGIN IF NEW.user_id=%s THEN RAISE EXCEPTION 'synthetic shadow fault'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql`, function, "'"+strings.ReplaceAll(ref.UserID, "'", "''")+"'")
	if _, err := s.store.pool.Exec(ctx, definition); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_, _ = s.store.pool.Exec(context.Background(), `DROP TRIGGER IF EXISTS `+trigger+` ON jourvolt_telemetry_detail_chunks; DROP FUNCTION IF EXISTS `+function+`()`)
	})
	if _, err := s.store.pool.Exec(ctx, `CREATE TRIGGER `+trigger+` BEFORE INSERT ON jourvolt_telemetry_detail_chunks FOR EACH ROW EXECUTE FUNCTION `+function+`() `); err != nil {
		t.Fatal(err)
	}
	r := nativeShadowEvent(ref, start, 1, "Location", map[string]any{"latitude": 1.0, "longitude": 2.0})
	if n, err := s.ingest(ctx, r); err == nil || n != 0 {
		t.Fatalf("chunk failure accepted=%d err=%v", n, err)
	}
	if after := nativeShadowFingerprint(t, s, ref); after != before {
		t.Fatal("partial write after chunk failure")
	}
	if _, err := s.store.pool.Exec(ctx, `DROP TRIGGER `+trigger+` ON jourvolt_telemetry_detail_chunks`); err != nil {
		t.Fatal(err)
	}
	requireNativeShadowIngest(t, s, r, 1)
	e := readNativeShadowEvidence(t, s, ref)
	if e.Count != 1 || e.Chunks != 1 || e.Revision != e.CurrentRevision {
		t.Fatalf("retry=%+v", e)
	}
	if _, err := s.store.pool.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET route_json='{"malformed":true}'::jsonb WHERE id=$1`, e.ID); err != nil {
		t.Fatal(err)
	}
	before = nativeShadowFingerprint(t, s, ref)
	if n, err := s.ingest(ctx, nativeShadowEvent(ref, start, 2, "Location", map[string]any{"latitude": 3.0, "longitude": 4.0})); err == nil || n != 0 {
		t.Fatalf("malformed legacy accepted=%d err=%v", n, err)
	}
	if after := nativeShadowFingerprint(t, s, ref); after != before {
		t.Fatal("malformed legacy was rewritten")
	}
}

func TestTelemetryPostgresNativeShadowScopeAndConstraints(t *testing.T) {
	sA, a, start := nativeShadowFixture(t)
	sB, b, _ := nativeShadowFixture(t)
	for _, fixture := range []struct {
		s   *telemetryService
		ref telemetryVehicleRef
		lat float64
	}{{sA, a, 1}, {sB, b, 9}} {
		requireNativeShadowIngest(t, fixture.s, nativeShadowEvent(fixture.ref, start, 0, "Gear", "D"), 1)
		requireNativeShadowIngest(t, fixture.s, nativeShadowEvent(fixture.ref, start, 1, "Location", map[string]any{"latitude": fixture.lat, "longitude": 2.0}), 1)
	}
	ea, eb := readNativeShadowEvidence(t, sA, a), readNativeShadowEvidence(t, sB, b)
	if ea.ID == eb.ID || reflect.DeepEqual(ea.Samples, eb.Samples) {
		t.Fatal("scopes collided")
	}
	ctx := context.Background()
	for _, scope := range []telemetryVehicleRef{{UserID: a.UserID, VehicleID: b.VehicleID}, {UserID: b.UserID, VehicleID: a.VehicleID}} {
		var count int
		if err := sA.store.pool.QueryRow(ctx, `SELECT count(*) FROM jourvolt_telemetry_detail_chunks WHERE user_id=$1 AND vehicle_id=$2`, scope.UserID, scope.VehicleID).Scan(&count); err != nil || count != 0 {
			t.Fatalf("scope leaked: %d %v", count, err)
		}
	}
	if _, err := sA.store.pool.Exec(ctx, `INSERT INTO jourvolt_telemetry_detail_shadow(session_id,user_id,vehicle_id,stream,prefix_count,sample_count,started_with_session,source_revision) VALUES($1,$2,$3,'route',0,0,true,0)`, ea.ID, b.UserID, b.VehicleID); err == nil {
		t.Fatal("mismatched owner FK accepted")
	}
	if _, err := sA.store.pool.Exec(ctx, `UPDATE jourvolt_telemetry_detail_chunks SET payload_sha256=repeat('0',64) WHERE session_id=$1`, ea.ID); err == nil {
		t.Fatal("incorrect checksum accepted")
	}
	if _, err := sA.store.pool.Exec(ctx, `UPDATE jourvolt_telemetry_detail_chunks SET sample_count=2 WHERE session_id=$1`, ea.ID); err == nil {
		t.Fatal("incorrect count accepted")
	}
	if _, err := sA.store.pool.Exec(ctx, `UPDATE jourvolt_telemetry_detail_shadow SET schema_version=2 WHERE session_id=$1`, ea.ID); err == nil {
		t.Fatal("unknown schema accepted")
	}
}

func TestTelemetryPostgresNativeShadowConcurrentReplay(t *testing.T) {
	s, ref, start := nativeShadowFixture(t)
	run := func(record telemetryRecord) {
		t.Helper()
		var wg sync.WaitGroup
		results := make(chan int, 12)
		errors := make(chan error, 12)
		for i := 0; i < 12; i++ {
			wg.Add(1)
			go func() { defer wg.Done(); n, err := s.ingest(context.Background(), record); results <- n; errors <- err }()
		}
		wg.Wait()
		close(results)
		close(errors)
		total := 0
		for n := range results {
			total += n
		}
		for err := range errors {
			if err != nil {
				t.Fatal(err)
			}
		}
		if total != 1 {
			t.Fatalf("concurrent event accepted %d times", total)
		}
	}
	run(nativeShadowEvent(ref, start, 0, "Gear", "D"))
	run(nativeShadowEvent(ref, start, 1, "Location", map[string]any{"latitude": 1.0, "longitude": 2.0}))
	e := readNativeShadowEvidence(t, s, ref)
	if !e.Started || e.Stale || e.Count != 1 || e.Chunks != 1 || e.Revision != e.CurrentRevision {
		t.Fatalf("concurrent shadow=%+v", e)
	}
}

func TestTelemetryPostgresNativeShadowExistingIDIsNotFresh(t *testing.T) {
	s, ref, start := nativeShadowFixture(t)
	id := scopedSessionID("telemetry_mqtt", ref.UserID, ref.VehicleID, "drive", start.UTC().Format(time.RFC3339Nano))
	if _, err := s.store.pool.Exec(context.Background(), `INSERT INTO jourvolt_telemetry_sessions(id,user_id,vehicle_id,kind,started_at,ended_at,source) VALUES($1,$2,$3,'drive',$4,$4,'telemetry_mqtt')`, id, ref.UserID, ref.VehicleID, start); err != nil {
		t.Fatal(err)
	}
	requireNativeShadowIngest(t, s, nativeShadowEvent(ref, start, 0, "Gear", "D"), 1)
	var count int
	if err := s.store.pool.QueryRow(context.Background(), `SELECT count(*) FROM jourvolt_telemetry_detail_shadow WHERE session_id=$1`, id).Scan(&count); err != nil || count != 0 {
		t.Fatalf("existing ID blessed as fresh: %d %v", count, err)
	}
}
