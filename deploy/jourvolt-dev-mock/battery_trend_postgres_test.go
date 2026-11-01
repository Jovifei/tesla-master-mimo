package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type batteryTrendCountingProvider struct {
	calls atomic.Int32
	claim int
}

func (p *batteryTrendCountingProvider) Vehicles(context.Context, string) ([]vehicle, error) {
	p.calls.Add(1)
	return []vehicle{{ID: p.claim}}, nil
}

func (p *batteryTrendCountingProvider) Status(context.Context, string, int) (vehicleStatus, error) {
	p.calls.Add(1)
	return vehicleStatus{}, nil
}

func TestBatteryTrendGETUsesOnlyStoredOwnership(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	user, car := boundedHistoryTestTenant(t, db, "battery-trend-owner")
	other, _ := boundedHistoryTestTenant(t, db, "battery-trend-foreign")
	provider := &batteryTrendCountingProvider{claim: car}
	a := &app{store: db, provider: provider}
	path := fmt.Sprintf("/api/v1/cars/%d/battery-trend/evidence", car)
	call := func(owner, route, method string) int {
		t.Helper()
		recorder := httptest.NewRecorder()
		a.carResource(recorder, httptest.NewRequest(method, route, nil), owner, route)
		return recorder.Code
	}
	if got := call(user, path, http.MethodGet); got != http.StatusOK {
		t.Fatalf("owned status %d", got)
	}
	if got := call(other, path, http.MethodGet); got != http.StatusNotFound {
		t.Fatalf("foreign status %d", got)
	}
	if got := call(user, "/api/v1/cars/1073741824/battery-trend/evidence", http.MethodGet); got != http.StatusNotFound {
		t.Fatalf("missing status %d", got)
	}
	if got := call(user, path, http.MethodPost); got != http.StatusNotFound {
		t.Fatalf("wrong method status %d", got)
	}
	if got := call(user, path+"/extra", http.MethodGet); got != http.StatusNotFound {
		t.Fatalf("wrong path status %d", got)
	}
	if provider.calls.Load() != 0 {
		t.Fatal("battery evidence GET called external provider")
	}
	withoutStore := &app{provider: provider}
	recorder := httptest.NewRecorder()
	withoutStore.carResource(recorder, httptest.NewRequest(http.MethodGet, path, nil), user, path)
	if recorder.Code != http.StatusServiceUnavailable || provider.calls.Load() != 0 {
		t.Fatal("missing local store called external provider")
	}
}

func TestBatteryTrendJSONBNumericHashRoundTrip(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	for _, test := range []struct {
		name  string
		value float64
		same  bool
	}{
		{"decimal", 320.5, true},
		{"positive-zero", 0, true},
		{"negative-zero", math.Copysign(0, -1), false},
		{"small-exponent", 1e-7, true},
		{"smallest-float", math.SmallestNonzeroFloat64, true},
		{"largest-float", math.MaxFloat64, true},
	} {
		t.Run(test.name, func(t *testing.T) {
			_, request := batteryTrendTestRequest(t)
			observation := request.Observations[0]
			observation.OutsideTempAvgC = &test.value
			original, err := batteryTrendDigest(observation)
			if err != nil {
				t.Fatal(err)
			}
			wire, err := json.Marshal(observation)
			if err != nil {
				t.Fatal(err)
			}
			var jsonb []byte
			if err = db.pool.QueryRow(context.Background(), `SELECT $1::jsonb`, wire).Scan(&jsonb); err != nil {
				t.Fatal(err)
			}
			var decoded batteryTrendObservation
			if err = json.Unmarshal(jsonb, &decoded); err != nil {
				t.Fatal(err)
			}
			roundTrip, err := batteryTrendDigest(decoded)
			if err != nil {
				t.Fatal(err)
			}
			if (original == roundTrip) != test.same {
				t.Fatalf("unexpected JSONB hash equality: %t", original == roundTrip)
			}
		})
	}
}

func TestBatteryTrendCanonicalTextRoundTripPreservesFloatBits(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	for _, test := range []struct {
		name  string
		value float64
	}{
		{"decimal", 320.5}, {"positive-zero", 0}, {"negative-zero", math.Copysign(0, -1)},
		{"small-exponent", 1e-7}, {"smallest-float", math.SmallestNonzeroFloat64}, {"largest-float", math.MaxFloat64},
	} {
		t.Run(test.name, func(t *testing.T) {
			user, car := boundedHistoryTestTenant(t, db, "battery-trend-text")
			_, request := batteryTrendTestRequest(t)
			token, binding, err := db.createArchiveBinding(context.Background(), user, car, archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID})
			if err != nil {
				t.Fatal(err)
			}
			request.Observations[0].OutsideTempAvgC = &test.value
			request.Observations[0].Start.RatedRangeKm = &test.value
			request.PayloadSHA256, _ = batteryTrendDigest(request.payload())
			if err = validateBatteryTrendImport(request, binding); err != nil {
				t.Fatal(err)
			}
			insertBatteryTrendSession(t, db, binding, request.Observations[0])
			if _, _, err = storeBatteryTrendBatch(context.Background(), db, binding, request, archiveBindingTokenHash(token)); err != nil {
				t.Fatal(err)
			}
			var raw, hash string
			if err = db.pool.QueryRow(context.Background(), `SELECT payload_json,payload_sha256 FROM jourvolt_battery_trend_evidence WHERE user_id=$1 AND vehicle_id=$2`, user, car).Scan(&raw, &hash); err != nil {
				t.Fatal(err)
			}
			observation, err := decodeBatteryTrendEvidence(raw, hash)
			if err != nil || observation.OutsideTempAvgC == nil || observation.Start == nil || observation.Start.RatedRangeKm == nil ||
				math.Float64bits(*observation.OutsideTempAvgC) != math.Float64bits(test.value) || math.Float64bits(*observation.Start.RatedRangeKm) != math.Float64bits(test.value) {
				t.Fatal("canonical text lost a valid numeric value")
			}
			a := &app{store: db}
			rec := httptest.NewRecorder()
			a.batteryTrendEvidenceList(rec, httptest.NewRequest(http.MethodGet, "/", nil), user, car)
			if rec.Code != http.StatusOK {
				t.Fatalf("valid evidence GET status %d", rec.Code)
			}
			var body struct {
				Data struct {
					Observations []struct {
						Observation batteryTrendObservation `json:"observation"`
					} `json:"observations"`
				} `json:"data"`
			}
			if json.Unmarshal(rec.Body.Bytes(), &body) != nil || len(body.Data.Observations) != 1 ||
				math.Float64bits(*body.Data.Observations[0].Observation.OutsideTempAvgC) != math.Float64bits(test.value) {
				t.Fatal("GET changed a valid numeric value")
			}
		})
	}
}

func TestBatteryTrendGETRejectsCorruptTextWithoutDeletingEvidence(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	for _, scenario := range []string{"content", "hash", "malformed", "unknown-field", "record-mismatch", "oversize"} {
		t.Run(scenario, func(t *testing.T) {
			user, car := boundedHistoryTestTenant(t, db, "battery-trend-corrupt")
			_, request := batteryTrendTestRequest(t)
			token, binding, err := db.createArchiveBinding(context.Background(), user, car, archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID})
			if err != nil {
				t.Fatal(err)
			}
			insertBatteryTrendSession(t, db, binding, request.Observations[0])
			if _, _, err = storeBatteryTrendBatch(context.Background(), db, binding, request, archiveBindingTokenHash(token)); err != nil {
				t.Fatal(err)
			}
			var raw, hash string
			if err = db.pool.QueryRow(context.Background(), `SELECT payload_json,payload_sha256 FROM jourvolt_battery_trend_evidence WHERE user_id=$1 AND vehicle_id=$2`, user, car).Scan(&raw, &hash); err != nil {
				t.Fatal(err)
			}
			switch scenario {
			case "content":
				raw = strings.Replace(raw, `"outside_temp_avg_c":19.5`, `"outside_temp_avg_c":20.5`, 1)
			case "hash":
				hash = strings.Repeat("0", 64)
			case "malformed":
				raw = "{"
			case "unknown-field":
				raw = strings.TrimSuffix(raw, "}") + `,"unexpected":1}`
			case "record-mismatch":
				raw = strings.Replace(raw, `"source_record_id":"teslamate:drive:42"`, `"source_record_id":"teslamate:drive:43"`, 1)
			case "oversize":
				raw = strings.Repeat("x", batteryTrendMaxObservation+1)
			}
			if scenario != "content" && scenario != "hash" {
				sum := sha256.Sum256([]byte(raw))
				hash = hex.EncodeToString(sum[:])
			}
			if _, err = db.pool.Exec(context.Background(), `UPDATE jourvolt_battery_trend_evidence SET payload_json=$1,payload_sha256=$2 WHERE user_id=$3 AND vehicle_id=$4`, raw, hash, user, car); err != nil {
				t.Fatal(err)
			}
			var before string
			if err = db.pool.QueryRow(context.Background(), `SELECT md5(row_to_json(e)::text) FROM jourvolt_battery_trend_evidence e WHERE user_id=$1 AND vehicle_id=$2`, user, car).Scan(&before); err != nil {
				t.Fatal(err)
			}
			a := &app{store: db}
			rec := httptest.NewRecorder()
			a.batteryTrendEvidenceList(rec, httptest.NewRequest(http.MethodGet, "/", nil), user, car)
			if rec.Code != http.StatusConflict {
				t.Fatalf("corrupt evidence GET status %d", rec.Code)
			}
			var after string
			if err = db.pool.QueryRow(context.Background(), `SELECT md5(row_to_json(e)::text) FROM jourvolt_battery_trend_evidence e WHERE user_id=$1 AND vehicle_id=$2`, user, car).Scan(&after); err != nil {
				t.Fatal(err)
			}
			if before != after {
				t.Fatal("GET changed or deleted corrupt evidence")
			}
		})
	}
}

func TestBatteryTrendGETRejectsChangedArchiveWithinSnapshotWithoutDeletingEvidence(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	for _, scenario := range []string{"changed-window", "quarantined", "deleted"} {
		t.Run(scenario, func(t *testing.T) {
			db := boundedHistoryTestStore(t)
			user, car := boundedHistoryTestTenant(t, db, "battery-trend-source-change")
			_, request := batteryTrendTestRequest(t)
			token, binding, err := db.createArchiveBinding(context.Background(), user, car, archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID})
			if err != nil {
				t.Fatal(err)
			}
			insertBatteryTrendSession(t, db, binding, request.Observations[0])
			second := request.Observations[0]
			second.SourceRecordID = "teslamate:drive:43"
			second.StartedAt, second.EndedAt = "2026-10-29T01:00:00Z", "2026-10-29T01:30:00Z"
			second.Start, second.End = nil, nil
			secondID := insertBatteryTrendSession(t, db, binding, second)
			request.Observations = []batteryTrendObservation{request.Observations[0], second}
			request.PayloadSHA256, _ = batteryTrendDigest(request.payload())
			if _, _, err = storeBatteryTrendBatch(context.Background(), db, binding, request, archiveBindingTokenHash(token)); err != nil {
				t.Fatal(err)
			}
			var before string
			if err = db.pool.QueryRow(context.Background(), `SELECT md5(string_agg(row_to_json(e)::text,',' ORDER BY e.ingest_seq)) FROM jourvolt_battery_trend_evidence e WHERE user_id=$1 AND vehicle_id=$2`, user, car).Scan(&before); err != nil {
				t.Fatal(err)
			}
			a := &app{store: db, provider: &batteryTrendCountingProvider{claim: car}}
			path := fmt.Sprintf("/api/v1/cars/%d/battery-trend/evidence?limit=1", car)
			read := func() *httptest.ResponseRecorder {
				t.Helper()
				rec := httptest.NewRecorder()
				a.carResource(rec, httptest.NewRequest(http.MethodGet, path, nil), user, fmt.Sprintf("/api/v1/cars/%d/battery-trend/evidence", car))
				return rec
			}
			if rec := read(); rec.Code != http.StatusOK {
				t.Fatalf("initial GET status %d", rec.Code)
			}
			switch scenario {
			case "changed-window":
				_, err = db.pool.Exec(context.Background(), `UPDATE jourvolt_telemetry_sessions SET ended_at=ended_at+interval '1 second' WHERE id=$1`, secondID)
			case "quarantined":
				_, err = db.pool.Exec(context.Background(), `UPDATE jourvolt_telemetry_sessions SET quality_state='quarantined' WHERE id=$1`, secondID)
			case "deleted":
				_, err = db.pool.Exec(context.Background(), `DELETE FROM jourvolt_telemetry_sessions WHERE id=$1`, secondID)
			}
			if err != nil {
				t.Fatal(err)
			}
			if rec := read(); rec.Code != http.StatusConflict {
				t.Fatalf("changed source GET status %d", rec.Code)
			}
			var after string
			if err = db.pool.QueryRow(context.Background(), `SELECT md5(string_agg(row_to_json(e)::text,',' ORDER BY e.ingest_seq)) FROM jourvolt_battery_trend_evidence e WHERE user_id=$1 AND vehicle_id=$2`, user, car).Scan(&after); err != nil {
				t.Fatal(err)
			}
			if before != after {
				t.Fatal("source conflict rewrote or removed trend evidence")
			}
		})
	}
}

func insertBatteryTrendSession(t *testing.T, db *store, binding archiveBinding, observation batteryTrendObservation) string {
	t.Helper()
	start, _ := batteryTrendTimestamp(observation.StartedAt)
	end, _ := batteryTrendTimestamp(observation.EndedAt)
	id := scopedArchiveSessionID(binding.UserID, binding.VehicleID, "drive", binding.SourceInstanceID, binding.SourceVehicleID, observation.SourceRecordID)
	_, err := db.pool.Exec(context.Background(), `INSERT INTO jourvolt_telemetry_sessions(id,user_id,vehicle_id,kind,started_at,ended_at,source,quality_state,quality_reason,source_instance_id,source_vehicle_id,source_record_id,route_json,charge_points_json,cost) VALUES($1,$2,$3,'drive',$4,$5,'teslamate_archive','observed','synthetic',$6,$7,$8,'[{"sentinel":1}]'::jsonb,'[]'::jsonb,7.5)`, id, binding.UserID, binding.VehicleID, start, end, binding.SourceInstanceID, binding.SourceVehicleID, observation.SourceRecordID)
	if err != nil {
		t.Fatal(err)
	}
	return id
}

func TestBatteryTrendBridgeWireContract(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	fixture, err := os.ReadFile("../teslamate-home-docker/archive-bridge/cmd/battery-trend/testdata/server-golden.json")
	if err != nil {
		t.Fatal("bridge synthetic wire fixture unavailable")
	}
	var request batteryTrendImportRequest
	if err = json.Unmarshal(fixture, &request); err != nil {
		t.Fatal(err)
	}
	db := boundedHistoryTestStore(t)
	user, car := boundedHistoryTestTenant(t, db, "battery-trend-wire")
	token, binding, err := db.createArchiveBinding(context.Background(), user, car, archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID})
	if err != nil {
		t.Fatal(err)
	}
	id := insertBatteryTrendSession(t, db, binding, request.Observations[0])
	before := batteryTrendSessionDigest(t, db, id)
	a := &app{store: db, provider: testProvider{vehicles: map[string][]vehicle{}}}
	server := httptest.NewTLSServer(a)
	defer server.Close()
	endpoint := fmt.Sprintf("%s/api/v1/cars/%d/history/archive/battery-trend/import", server.URL, car)
	for i := 0; i < 2; i++ {
		req, err := http.NewRequest(http.MethodPost, endpoint, bytes.NewReader(fixture))
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set(archiveBindingHeader, token)
		resp, err := server.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		var body struct {
			Data struct {
				SchemaVersion int    `json:"schema_version"`
				BatchID       string `json:"batch_id"`
				PayloadSHA256 string `json:"payload_sha256"`
				Inserted      int    `json:"inserted"`
				Unchanged     int    `json:"unchanged"`
				Conflicted    int    `json:"conflicted"`
			} `json:"data"`
		}
		err = json.NewDecoder(resp.Body).Decode(&body)
		resp.Body.Close()
		if err != nil || resp.StatusCode != http.StatusOK || body.Data.SchemaVersion != 1 || body.Data.BatchID != request.BatchID || body.Data.PayloadSHA256 != request.PayloadSHA256 || body.Data.Inserted != 1 || body.Data.Unchanged != 0 || body.Data.Conflicted != 0 {
			t.Fatal("bridge wire or idempotent response mismatch")
		}
	}
	if after := batteryTrendSessionDigest(t, db, id); after != before {
		t.Fatal("bridge wire changed original session")
	}
	session, err := db.createSession(context.Background(), user)
	if err != nil {
		t.Fatal(err)
	}
	get, err := http.NewRequest(http.MethodGet, fmt.Sprintf("%s/api/v1/cars/%d/battery-trend/evidence", server.URL, car), nil)
	if err != nil {
		t.Fatal(err)
	}
	get.Header.Set("Authorization", "Bearer "+session.AccessToken)
	response, err := server.Client().Do(get)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var result struct {
		Data struct {
			SchemaVersion int               `json:"schema_version"`
			Units         batteryTrendUnits `json:"units"`
			Complete      bool              `json:"complete"`
			Observations  []struct {
				Observation batteryTrendObservation `json:"observation"`
			} `json:"observations"`
		} `json:"data"`
	}
	if err := json.NewDecoder(response.Body).Decode(&result); err != nil || response.StatusCode != http.StatusOK || !result.Data.Complete || result.Data.Units != request.Units || len(result.Data.Observations) != 1 {
		t.Fatal("authenticated evidence read failed")
	}
	got := result.Data.Observations[0].Observation
	if got.Start == nil || got.Start.SocPercent == nil || *got.Start.SocPercent != 80 || got.Start.RatedRangeKm == nil || *got.Start.RatedRangeKm != 320.5 || got.Start.RatedRangeObservedAt != nil {
		t.Fatal("bridge sample changed in server persistence")
	}
}

func batteryTrendSessionDigest(t *testing.T, db *store, id string) string {
	t.Helper()
	var digest string
	if err := db.pool.QueryRow(context.Background(), `SELECT md5(row_to_json(s)::text) FROM jourvolt_telemetry_sessions s WHERE id=$1`, id).Scan(&digest); err != nil {
		t.Fatal(err)
	}
	return digest
}

type batteryTrendBlockedBody struct {
	reader  *bytes.Reader
	entered chan struct{}
	release chan struct{}
	once    sync.Once
}

func (b *batteryTrendBlockedBody) Read(p []byte) (int, error) {
	b.once.Do(func() { close(b.entered); <-b.release })
	return b.reader.Read(p)
}
func (b *batteryTrendBlockedBody) Close() error { return nil }

func TestBatteryTrendBindingChangesDuringBodyNeverWriteOrAcknowledge(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	for _, scenario := range []string{"revoke", "rotate", "expire", "replay-revoke"} {
		t.Run(scenario, func(t *testing.T) {
			db := boundedHistoryTestStore(t)
			user, car := boundedHistoryTestTenant(t, db, "battery-trend-binding-race")
			_, request := batteryTrendTestRequest(t)
			scope := archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID}
			token, binding, err := db.createArchiveBinding(context.Background(), user, car, scope)
			if err != nil {
				t.Fatal(err)
			}
			driveID := insertBatteryTrendSession(t, db, binding, request.Observations[0])
			chargeID := "battery-trend-synthetic-charge-" + mustRandomToken(t)
			_, err = db.pool.Exec(context.Background(), `INSERT INTO jourvolt_telemetry_sessions(id,user_id,vehicle_id,kind,started_at,ended_at,source,quality_state,quality_reason,route_json,charge_points_json,cost) VALUES($1,$2,$3,'charge','2026-11-01T01:00:00Z','2026-11-01T02:00:00Z','teslamate_archive','observed','synthetic','[]'::jsonb,'[{"energy":1}]'::jsonb,34.56)`, chargeID, user, car)
			if err != nil {
				t.Fatal(err)
			}
			beforeDrive, beforeCharge := batteryTrendSessionDigest(t, db, driveID), batteryTrendSessionDigest(t, db, chargeID)
			payload, err := json.Marshal(request)
			if err != nil {
				t.Fatal(err)
			}
			path := fmt.Sprintf("/api/v1/cars/%d/history/archive/battery-trend/import", car)
			a := &app{store: db}
			if scenario == "replay-revoke" {
				initial := httptest.NewRecorder()
				req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(payload))
				req.Header.Set(archiveBindingHeader, token)
				a.batteryTrendArchiveImport(initial, req)
				if initial.Code != http.StatusOK {
					t.Fatalf("initial status %d", initial.Code)
				}
			}
			blocked := &batteryTrendBlockedBody{reader: bytes.NewReader(payload), entered: make(chan struct{}), release: make(chan struct{})}
			var releaseOnce sync.Once
			release := func() { releaseOnce.Do(func() { close(blocked.release) }) }
			defer release()
			req := httptest.NewRequest(http.MethodPost, path, nil)
			req.Body = blocked
			req.Header.Set(archiveBindingHeader, token)
			result := make(chan *httptest.ResponseRecorder, 1)
			go func() { rec := httptest.NewRecorder(); a.batteryTrendArchiveImport(rec, req); result <- rec }()
			select {
			case <-blocked.entered:
			case <-time.After(5 * time.Second):
				t.Fatal("body did not start")
			}
			switch scenario {
			case "revoke", "replay-revoke":
				err = db.revokeArchiveBinding(context.Background(), user, car, scope)
			case "rotate":
				_, _, err = db.createArchiveBinding(context.Background(), user, car, scope)
			case "expire":
				_, err = db.pool.Exec(context.Background(), `UPDATE jourvolt_history_archive_bindings SET expires_at=clock_timestamp()-interval '1 second' WHERE user_id=$1 AND vehicle_id=$2 AND source_instance_id=$3 AND source_vehicle_id=$4`, user, car, scope.SourceInstanceID, scope.SourceVehicleID)
			}
			if err != nil {
				t.Fatal(err)
			}
			release()
			select {
			case rec := <-result:
				if rec.Code != http.StatusForbidden {
					t.Fatalf("binding race status %d", rec.Code)
				}
			case <-time.After(5 * time.Second):
				t.Fatal("request did not finish")
			}
			var evidenceCount, batchCount int
			if err = db.pool.QueryRow(context.Background(), `SELECT (SELECT count(*) FROM jourvolt_battery_trend_evidence WHERE user_id=$1 AND vehicle_id=$2),(SELECT count(*) FROM jourvolt_battery_trend_batches WHERE user_id=$1 AND vehicle_id=$2)`, user, car).Scan(&evidenceCount, &batchCount); err != nil {
				t.Fatal(err)
			}
			wanted := 0
			if scenario == "replay-revoke" {
				wanted = 1
			}
			if evidenceCount != wanted || batchCount != wanted {
				t.Fatalf("race changed evidence/batches: %d/%d", evidenceCount, batchCount)
			}
			if batteryTrendSessionDigest(t, db, driveID) != beforeDrive || batteryTrendSessionDigest(t, db, chargeID) != beforeCharge {
				t.Fatal("race changed original drive or charge")
			}
		})
	}
}

func TestBatteryTrendBindingLockSerializesConcurrentRevoke(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	user, car := boundedHistoryTestTenant(t, db, "battery-trend-binding-lock")
	_, request := batteryTrendTestRequest(t)
	scope := archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID}
	token, binding, err := db.createArchiveBinding(context.Background(), user, car, scope)
	if err != nil {
		t.Fatal(err)
	}
	id := insertBatteryTrendSession(t, db, binding, request.Observations[0])
	before := batteryTrendSessionDigest(t, db, id)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	blocker, err := db.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer blocker.Rollback(context.Background())
	var locked string
	if err = blocker.QueryRow(ctx, `SELECT id FROM jourvolt_telemetry_sessions WHERE id=$1 FOR UPDATE`, id).Scan(&locked); err != nil {
		t.Fatal(err)
	}
	payload, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	path := fmt.Sprintf("/api/v1/cars/%d/history/archive/battery-trend/import", car)
	a := &app{store: db}
	response := make(chan *httptest.ResponseRecorder, 1)
	go func() {
		req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(payload))
		req.Header.Set(archiveBindingHeader, token)
		rec := httptest.NewRecorder()
		a.batteryTrendArchiveImport(rec, req)
		response <- rec
	}()
	waitForLock := func(pattern string) {
		t.Helper()
		for {
			var waiting int
			if err := db.pool.QueryRow(ctx, `SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE $1`, pattern).Scan(&waiting); err != nil {
				t.Fatal(err)
			}
			if waiting > 0 {
				return
			}
			select {
			case <-ctx.Done():
				t.Fatal("expected lock wait did not appear")
			case <-time.After(10 * time.Millisecond):
			}
		}
	}
	waitForLock("%SELECT id FROM jourvolt_telemetry_sessions%FOR SHARE%")
	revoked := make(chan error, 1)
	go func() { revoked <- db.revokeArchiveBinding(ctx, user, car, scope) }()
	waitForLock("%UPDATE jourvolt_history_archive_bindings SET revoked_at%")
	if err = blocker.Commit(ctx); err != nil {
		t.Fatal(err)
	}
	select {
	case rec := <-response:
		if rec.Code != http.StatusOK {
			t.Fatalf("authorized transaction status %d", rec.Code)
		}
	case <-ctx.Done():
		t.Fatal("authorized transaction did not finish")
	}
	select {
	case err = <-revoked:
		if err != nil {
			t.Fatal(err)
		}
	case <-ctx.Done():
		t.Fatal("revocation did not finish")
	}
	replay := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(payload))
	replay.Header.Set(archiveBindingHeader, token)
	rec := httptest.NewRecorder()
	a.batteryTrendArchiveImport(rec, replay)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("revoked replay status %d", rec.Code)
	}
	if after := batteryTrendSessionDigest(t, db, id); after != before {
		t.Fatal("original session changed")
	}
}

func TestBatteryTrendBindingExpiryWhileTransactionWaitsRollsBack(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	user, car := boundedHistoryTestTenant(t, db, "battery-trend-binding-expiry")
	_, request := batteryTrendTestRequest(t)
	scope := archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID}
	token, binding, err := db.createArchiveBinding(context.Background(), user, car, scope)
	if err != nil {
		t.Fatal(err)
	}
	id := insertBatteryTrendSession(t, db, binding, request.Observations[0])
	before := batteryTrendSessionDigest(t, db, id)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	blocker, err := db.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer blocker.Rollback(context.Background())
	var locked string
	if err = blocker.QueryRow(ctx, `SELECT id FROM jourvolt_telemetry_sessions WHERE id=$1 FOR UPDATE`, id).Scan(&locked); err != nil {
		t.Fatal(err)
	}
	_, err = db.pool.Exec(ctx, `UPDATE jourvolt_history_archive_bindings SET expires_at=clock_timestamp()+interval '3 seconds' WHERE user_id=$1 AND vehicle_id=$2 AND source_instance_id=$3 AND source_vehicle_id=$4`, user, car, scope.SourceInstanceID, scope.SourceVehicleID)
	if err != nil {
		t.Fatal(err)
	}
	payload, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	path := fmt.Sprintf("/api/v1/cars/%d/history/archive/battery-trend/import", car)
	a := &app{store: db}
	response := make(chan *httptest.ResponseRecorder, 1)
	go func() {
		req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(payload))
		req.Header.Set(archiveBindingHeader, token)
		rec := httptest.NewRecorder()
		a.batteryTrendArchiveImport(rec, req)
		response <- rec
	}()
	for {
		var waiting int
		if err = db.pool.QueryRow(ctx, `SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%SELECT id FROM jourvolt_telemetry_sessions%FOR SHARE%'`).Scan(&waiting); err != nil {
			t.Fatal(err)
		}
		if waiting > 0 {
			break
		}
		select {
		case rec := <-response:
			t.Fatalf("request ended before session wait: %d", rec.Code)
		case <-ctx.Done():
			t.Fatal("session wait did not appear")
		case <-time.After(10 * time.Millisecond):
		}
	}
	for {
		var expired bool
		if err = db.pool.QueryRow(ctx, `SELECT expires_at <= clock_timestamp() FROM jourvolt_history_archive_bindings WHERE user_id=$1 AND vehicle_id=$2 AND source_instance_id=$3 AND source_vehicle_id=$4`, user, car, scope.SourceInstanceID, scope.SourceVehicleID).Scan(&expired); err != nil {
			t.Fatal(err)
		}
		if expired {
			break
		}
		select {
		case <-ctx.Done():
			t.Fatal("binding did not expire")
		case <-time.After(20 * time.Millisecond):
		}
	}
	if err = blocker.Commit(ctx); err != nil {
		t.Fatal(err)
	}
	select {
	case rec := <-response:
		if rec.Code != http.StatusForbidden {
			t.Fatalf("expired transaction status %d", rec.Code)
		}
	case <-ctx.Done():
		t.Fatal("expired request did not finish")
	}
	var evidence, batches int
	if err = db.pool.QueryRow(ctx, `SELECT (SELECT count(*) FROM jourvolt_battery_trend_evidence WHERE user_id=$1 AND vehicle_id=$2),(SELECT count(*) FROM jourvolt_battery_trend_batches WHERE user_id=$1 AND vehicle_id=$2)`, user, car).Scan(&evidence, &batches); err != nil {
		t.Fatal(err)
	}
	if evidence != 0 || batches != 0 || batteryTrendSessionDigest(t, db, id) != before {
		t.Fatal("expired transaction changed evidence, receipt or original session")
	}
}

func TestBatteryTrendSchemaRestartPreservesExistingData(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	user, car := boundedHistoryTestTenant(t, db, "battery-trend-restart")
	_, request := batteryTrendTestRequest(t)
	token, binding, err := db.createArchiveBinding(context.Background(), user, car, archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID})
	if err != nil {
		t.Fatal(err)
	}
	driveID := insertBatteryTrendSession(t, db, binding, request.Observations[0])
	chargeID := "battery-trend-restart-charge-" + mustRandomToken(t)
	_, err = db.pool.Exec(context.Background(), `INSERT INTO jourvolt_telemetry_sessions(id,user_id,vehicle_id,kind,started_at,ended_at,source,quality_state,quality_reason,route_json,charge_points_json,cost) VALUES($1,$2,$3,'charge','2026-11-01T01:00:00Z','2026-11-01T02:00:00Z','teslamate_archive','observed','synthetic','[]'::jsonb,'[{"energy":1}]'::jsonb,34.56)`, chargeID, user, car)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = storeBatteryTrendBatch(context.Background(), db, binding, request, archiveBindingTokenHash(token)); err != nil {
		t.Fatal(err)
	}
	readHashes := func(store *store) []string {
		t.Helper()
		queries := []struct {
			sql  string
			args []any
		}{
			{`SELECT md5(row_to_json(x)::text) FROM jourvolt_users x WHERE id=$1`, []any{user}},
			{`SELECT md5(row_to_json(x)::text) FROM jourvolt_vehicles x WHERE id=$1 AND user_id=$2`, []any{car, user}},
			{`SELECT md5(row_to_json(x)::text) FROM jourvolt_history_archive_bindings x WHERE user_id=$1 AND vehicle_id=$2`, []any{user, car}},
			{`SELECT md5(row_to_json(x)::text) FROM jourvolt_telemetry_sessions x WHERE id=$1`, []any{driveID}},
			{`SELECT md5(row_to_json(x)::text) FROM jourvolt_telemetry_sessions x WHERE id=$1`, []any{chargeID}},
			{`SELECT md5(row_to_json(x)::text) FROM jourvolt_battery_trend_evidence x WHERE user_id=$1 AND vehicle_id=$2`, []any{user, car}},
			{`SELECT md5(row_to_json(x)::text) FROM jourvolt_battery_trend_batches x WHERE user_id=$1 AND vehicle_id=$2`, []any{user, car}},
		}
		values := make([]string, 0, len(queries))
		for _, query := range queries {
			var value string
			if err := store.pool.QueryRow(context.Background(), query.sql, query.args...).Scan(&value); err != nil {
				t.Fatal(err)
			}
			values = append(values, value)
		}
		return values
	}
	before := readHashes(db)
	restarted, err := openStore(context.Background(), os.Getenv("JOURVOLT_TEST_DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	defer restarted.close()
	after := readHashes(restarted)
	if !reflect.DeepEqual(before, after) {
		t.Fatal("startup changed existing synthetic user data")
	}
}

func TestBatteryTrendReplayRequiresCurrentEvidenceAndSession(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	for _, scenario := range []string{"evidence-content", "evidence-hash", "evidence-content-and-hash", "evidence-oversize", "evidence-deleted", "evidence-window", "session-window", "session-quarantined", "session-deleted", "session-source"} {
		t.Run(scenario, func(t *testing.T) {
			db := boundedHistoryTestStore(t)
			user, car := boundedHistoryTestTenant(t, db, "battery-trend-replay")
			_, request := batteryTrendTestRequest(t)
			token, binding, err := db.createArchiveBinding(context.Background(), user, car, archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID})
			if err != nil {
				t.Fatal(err)
			}
			insertBatteryTrendSession(t, db, binding, request.Observations[0])
			second := request.Observations[0]
			second.SourceRecordID = "teslamate:drive:43"
			second.StartedAt, second.EndedAt = "2026-10-29T01:00:00Z", "2026-10-29T01:30:00Z"
			second.Start, second.End = nil, nil
			secondID := insertBatteryTrendSession(t, db, binding, second)
			request.Observations = []batteryTrendObservation{request.Observations[0], second}
			request.PayloadSHA256, _ = batteryTrendDigest(request.payload())
			a := &app{store: db}
			path := fmt.Sprintf("/api/v1/cars/%d/history/archive/battery-trend/import", car)
			call := func(batch batteryTrendImportRequest) *httptest.ResponseRecorder {
				t.Helper()
				body, marshalErr := json.Marshal(batch)
				if marshalErr != nil {
					t.Fatal(marshalErr)
				}
				req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(body))
				req.Header.Set(archiveBindingHeader, token)
				rec := httptest.NewRecorder()
				a.batteryTrendArchiveImport(rec, req)
				return rec
			}
			initial, replay := call(request), call(request)
			if initial.Code != http.StatusOK || replay.Code != http.StatusOK || !bytes.Equal(initial.Body.Bytes(), replay.Body.Bytes()) {
				t.Fatal("unchanged replay did not preserve the original receipt")
			}
			ctx := context.Background()
			switch scenario {
			case "evidence-content":
				_, err = db.pool.Exec(ctx, `UPDATE jourvolt_battery_trend_evidence SET payload_json=payload_json||' ' WHERE user_id=$1 AND vehicle_id=$2 AND source_record_id=$3`, user, car, second.SourceRecordID)
			case "evidence-hash":
				_, err = db.pool.Exec(ctx, `UPDATE jourvolt_battery_trend_evidence SET payload_sha256=$1 WHERE user_id=$2 AND vehicle_id=$3 AND source_record_id=$4`, strings.Repeat("0", 64), user, car, second.SourceRecordID)
			case "evidence-content-and-hash":
				var raw string
				if err = db.pool.QueryRow(ctx, `SELECT payload_json FROM jourvolt_battery_trend_evidence WHERE user_id=$1 AND vehicle_id=$2 AND source_record_id=$3`, user, car, second.SourceRecordID).Scan(&raw); err != nil {
					t.Fatal(err)
				}
				raw += " "
				sum := sha256.Sum256([]byte(raw))
				_, err = db.pool.Exec(ctx, `UPDATE jourvolt_battery_trend_evidence SET payload_json=$1,payload_sha256=$2 WHERE user_id=$3 AND vehicle_id=$4 AND source_record_id=$5`, raw, hex.EncodeToString(sum[:]), user, car, second.SourceRecordID)
			case "evidence-oversize":
				_, err = db.pool.Exec(ctx, `UPDATE jourvolt_battery_trend_evidence SET payload_json=$1 WHERE user_id=$2 AND vehicle_id=$3 AND source_record_id=$4`, strings.Repeat("x", batteryTrendMaxObservation+1), user, car, second.SourceRecordID)
			case "evidence-deleted":
				_, err = db.pool.Exec(ctx, `DELETE FROM jourvolt_battery_trend_evidence WHERE user_id=$1 AND vehicle_id=$2 AND source_record_id=$3`, user, car, second.SourceRecordID)
			case "evidence-window":
				_, err = db.pool.Exec(ctx, `UPDATE jourvolt_battery_trend_evidence SET ended_at=ended_at+interval '1 second' WHERE user_id=$1 AND vehicle_id=$2 AND source_record_id=$3`, user, car, second.SourceRecordID)
			case "session-window":
				_, err = db.pool.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET ended_at=ended_at+interval '1 second' WHERE id=$1`, secondID)
			case "session-quarantined":
				_, err = db.pool.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET quality_state='quarantined' WHERE id=$1`, secondID)
			case "session-deleted":
				_, err = db.pool.Exec(ctx, `DELETE FROM jourvolt_telemetry_sessions WHERE id=$1`, secondID)
			case "session-source":
				_, err = db.pool.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET source='telemetry_mqtt' WHERE id=$1`, secondID)
			}
			if err != nil {
				t.Fatal(err)
			}
			snapshot := func() [3]string {
				t.Helper()
				var state [3]string
				err := db.pool.QueryRow(ctx, `SELECT COALESCE((SELECT md5(string_agg(row_to_json(e)::text,',' ORDER BY e.ingest_seq)) FROM jourvolt_battery_trend_evidence e WHERE user_id=$1 AND vehicle_id=$2),''), COALESCE((SELECT md5(string_agg(row_to_json(b)::text,',' ORDER BY b.batch_id)) FROM jourvolt_battery_trend_batches b WHERE user_id=$1 AND vehicle_id=$2),''), COALESCE((SELECT md5(string_agg(row_to_json(s)::text,',' ORDER BY s.id)) FROM jourvolt_telemetry_sessions s WHERE user_id=$1 AND vehicle_id=$2),'')`, user, car).Scan(&state[0], &state[1], &state[2])
				if err != nil {
					t.Fatal(err)
				}
				return state
			}
			before := snapshot()
			failed := call(request)
			var result map[string]any
			if failed.Code != http.StatusConflict || json.Unmarshal(failed.Body.Bytes(), &result) != nil || result["error"] != "battery_trend_conflict" || result["data"] != nil {
				t.Fatalf("invalid replay acknowledged: status %d", failed.Code)
			}
			if strings.HasPrefix(scenario, "evidence-") && scenario != "evidence-deleted" {
				otherBatch := request
				otherBatch.BatchID = "different-retry"
				if code := call(otherBatch).Code; code != http.StatusConflict {
					t.Fatalf("corrupt duplicate in a new batch acknowledged: status %d", code)
				}
			}
			if before != snapshot() {
				t.Fatal("failed replay changed evidence, receipt, or source sessions")
			}
		})
	}
}

func TestBatteryTrendReplayWaitsForConcurrentEvidenceSessionOrReceiptChange(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	for _, scenario := range []string{"session", "evidence", "receipt"} {
		t.Run(scenario, func(t *testing.T) {
			db := boundedHistoryTestStore(t)
			user, car := boundedHistoryTestTenant(t, db, "battery-trend-replay-lock")
			_, request := batteryTrendTestRequest(t)
			token, binding, err := db.createArchiveBinding(context.Background(), user, car, archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID})
			if err != nil {
				t.Fatal(err)
			}
			sessionID := insertBatteryTrendSession(t, db, binding, request.Observations[0])
			if _, _, err = storeBatteryTrendBatch(context.Background(), db, binding, request, archiveBindingTokenHash(token)); err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			defer cancel()
			blocker, err := db.pool.Begin(ctx)
			if err != nil {
				t.Fatal(err)
			}
			defer blocker.Rollback(context.Background())
			pattern := "%SELECT id FROM jourvolt_telemetry_sessions%FOR SHARE%"
			switch scenario {
			case "session":
				_, err = blocker.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET ended_at=ended_at+interval '1 second' WHERE id=$1`, sessionID)
			case "evidence":
				pattern = "%SELECT started_at,ended_at,CASE WHEN octet_length(payload_json)%FROM jourvolt_battery_trend_evidence%FOR SHARE%"
				_, err = blocker.Exec(ctx, `UPDATE jourvolt_battery_trend_evidence SET payload_json=payload_json||' ' WHERE user_id=$1 AND vehicle_id=$2`, user, car)
			case "receipt":
				pattern = "%SELECT payload_sha256, inserted_count, unchanged_count FROM jourvolt_battery_trend_batches%FOR SHARE%"
				_, err = blocker.Exec(ctx, `DELETE FROM jourvolt_battery_trend_batches WHERE user_id=$1 AND vehicle_id=$2 AND batch_id=$3`, user, car, request.BatchID)
			}
			if err != nil {
				t.Fatal(err)
			}
			payload, err := json.Marshal(request)
			if err != nil {
				t.Fatal(err)
			}
			path := fmt.Sprintf("/api/v1/cars/%d/history/archive/battery-trend/import", car)
			a := &app{store: db}
			response := make(chan *httptest.ResponseRecorder, 1)
			go func() {
				req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(payload))
				req.Header.Set(archiveBindingHeader, token)
				rec := httptest.NewRecorder()
				a.batteryTrendArchiveImport(rec, req)
				response <- rec
			}()
			for {
				var waiting int
				if err = db.pool.QueryRow(ctx, `SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE $1`, pattern).Scan(&waiting); err != nil {
					t.Fatal(err)
				}
				if waiting > 0 {
					break
				}
				select {
				case rec := <-response:
					t.Fatalf("replay acknowledged before concurrent change committed: %d", rec.Code)
				case <-ctx.Done():
					t.Fatal("expected replay lock wait did not appear")
				case <-time.After(10 * time.Millisecond):
				}
			}
			if err = blocker.Commit(ctx); err != nil {
				t.Fatal(err)
			}
			select {
			case rec := <-response:
				if scenario == "receipt" {
					var body struct {
						Data struct {
							Inserted  int `json:"inserted"`
							Unchanged int `json:"unchanged"`
						} `json:"data"`
					}
					if rec.Code != http.StatusOK || json.Unmarshal(rec.Body.Bytes(), &body) != nil || body.Data.Inserted != 0 || body.Data.Unchanged != 1 {
						t.Fatalf("deleted receipt was not recreated from intact evidence: %d", rec.Code)
					}
					var count int
					if err = db.pool.QueryRow(ctx, `SELECT COUNT(*) FROM jourvolt_battery_trend_batches WHERE user_id=$1 AND vehicle_id=$2 AND batch_id=$3`, user, car, request.BatchID).Scan(&count); err != nil || count != 1 {
						t.Fatal("replacement receipt is not persisted")
					}
					break
				}
				if rec.Code != http.StatusConflict {
					t.Fatalf("concurrently changed replay returned %d", rec.Code)
				}
			case <-ctx.Done():
				t.Fatal("replay did not finish after concurrent change")
			}
		})
	}
}

func TestBatteryTrendPostgresIdempotentScopedAndNonDestructive(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	user, car := boundedHistoryTestTenant(t, db, "battery-trend")
	otherUser, otherCar := boundedHistoryTestTenant(t, db, "battery-trend-other")
	_, request := batteryTrendTestRequest(t)
	token, binding, err := db.createArchiveBinding(context.Background(), user, car, archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID})
	if err != nil {
		t.Fatal(err)
	}
	tokenHash := archiveBindingTokenHash(token)
	id := insertBatteryTrendSession(t, db, binding, request.Observations[0])
	before := batteryTrendSessionDigest(t, db, id)
	ctx := context.Background()
	if inserted, unchanged, err := storeBatteryTrendBatch(ctx, db, binding, request, tokenHash); err != nil || inserted != 1 || unchanged != 0 {
		t.Fatalf("first insert = %d/%d, err=%v", inserted, unchanged, err)
	}
	if inserted, unchanged, err := storeBatteryTrendBatch(ctx, db, binding, request, tokenHash); err != nil || inserted != 1 || unchanged != 0 {
		t.Fatalf("same batch receipt = %d/%d, err=%v", inserted, unchanged, err)
	}
	replay := request
	replay.BatchID = "second-batch"
	if inserted, unchanged, err := storeBatteryTrendBatch(ctx, db, binding, replay, tokenHash); err != nil || inserted != 0 || unchanged != 1 {
		t.Fatalf("same observation replay = %d/%d, err=%v", inserted, unchanged, err)
	}
	changed := request
	changed.Observations = append([]batteryTrendObservation(nil), request.Observations...)
	changed.Observations[0].OutsideTempAvgC = floatPointerForHistoryTest(20.0)
	changed.PayloadSHA256, _ = batteryTrendDigest(changed.payload())
	if _, _, err := storeBatteryTrendBatch(ctx, db, binding, changed, tokenHash); !errors.Is(err, errBatteryTrendConflict) {
		t.Fatalf("batch collision: %v", err)
	}
	changed.BatchID = "different-batch"
	if _, _, err := storeBatteryTrendBatch(ctx, db, binding, changed, tokenHash); !errors.Is(err, errBatteryTrendConflict) {
		t.Fatalf("record collision: %v", err)
	}
	if after := batteryTrendSessionDigest(t, db, id); before != after {
		t.Fatal("existing session/route/cost changed")
	}

	second := request.Observations[0]
	second.SourceRecordID = "teslamate:drive:43"
	second.StartedAt, second.EndedAt = "2026-10-29T01:00:00Z", "2026-10-29T01:30:00Z"
	second.Start, second.End = nil, nil
	secondID := insertBatteryTrendSession(t, db, binding, second)
	secondBefore := batteryTrendSessionDigest(t, db, secondID)
	missing := second
	missing.SourceRecordID = "teslamate:drive:44"
	batch := request
	batch.BatchID = "atomic-conflict"
	batch.Observations = []batteryTrendObservation{second, missing}
	batch.PayloadSHA256, _ = batteryTrendDigest(batch.payload())
	if _, _, err := storeBatteryTrendBatch(ctx, db, binding, batch, tokenHash); !errors.Is(err, errBatteryTrendConflict) {
		t.Fatalf("missing identity: %v", err)
	}
	var count int
	if err := db.pool.QueryRow(ctx, `SELECT count(*) FROM jourvolt_battery_trend_evidence WHERE user_id=$1 AND vehicle_id=$2`, user, car).Scan(&count); err != nil || count != 1 {
		t.Fatalf("partial transaction count=%d err=%v", count, err)
	}
	if after := batteryTrendSessionDigest(t, db, secondID); secondBefore != after {
		t.Fatal("source session changed on conflict")
	}
	foreign := binding
	foreign.UserID, foreign.VehicleID = otherUser, otherCar
	if _, _, err := storeBatteryTrendBatch(ctx, db, foreign, request, tokenHash); !errors.Is(err, errArchiveBindingScopeMismatch) {
		t.Fatalf("foreign scope accepted: %v", err)
	}

	secondBatch := request
	secondBatch.BatchID = "concurrent-second"
	secondBatch.Observations = []batteryTrendObservation{second}
	secondBatch.PayloadSHA256, _ = batteryTrendDigest(secondBatch.payload())
	var group sync.WaitGroup
	errorsFound := make(chan error, 2)
	for i := 0; i < 2; i++ {
		group.Add(1)
		go func() {
			defer group.Done()
			inserted, unchanged, err := storeBatteryTrendBatch(ctx, db, binding, secondBatch, tokenHash)
			if err != nil || inserted != 1 || unchanged != 0 {
				errorsFound <- fmt.Errorf("concurrent receipt %d/%d: %v", inserted, unchanged, err)
			}
		}()
	}
	group.Wait()
	close(errorsFound)
	for err := range errorsFound {
		t.Error(err)
	}
	if after := batteryTrendSessionDigest(t, db, secondID); secondBefore != after {
		t.Fatal("source session changed on insert")
	}

	a := &app{store: db, provider: testProvider{vehicles: map[string][]vehicle{}}}
	read := func(owner string, vehicle int, query string) (int, map[string]any) {
		t.Helper()
		recorder := httptest.NewRecorder()
		a.batteryTrendEvidenceList(recorder, httptest.NewRequest(http.MethodGet, "/?"+query, nil), owner, vehicle)
		var result map[string]any
		if err := json.Unmarshal(recorder.Body.Bytes(), &result); err != nil {
			t.Fatal(err)
		}
		return recorder.Code, result
	}
	code, firstPage := read(user, car, "limit=1")
	if code != 200 {
		t.Fatalf("first page status %d", code)
	}
	firstData := firstPage["data"].(map[string]any)
	if firstData["complete"] != false || len(firstData["observations"].([]any)) != 1 {
		t.Fatal("first page incorrect")
	}
	snapshot := strconv.FormatInt(int64(firstData["snapshot_max_seq"].(float64)), 10)
	next := firstData["next_cursor"].(string)
	third := second
	third.SourceRecordID = "teslamate:drive:45"
	third.StartedAt, third.EndedAt = "2026-10-30T01:00:00Z", "2026-10-30T01:30:00Z"
	insertBatteryTrendSession(t, db, binding, third)
	thirdBatch := request
	thirdBatch.BatchID, thirdBatch.Observations = "after-snapshot", []batteryTrendObservation{third}
	thirdBatch.PayloadSHA256, _ = batteryTrendDigest(thirdBatch.payload())
	if _, _, err := storeBatteryTrendBatch(ctx, db, binding, thirdBatch, tokenHash); err != nil {
		t.Fatal(err)
	}
	code, page2 := read(user, car, "limit=1&cursor="+next+"&snapshot_max_seq="+snapshot)
	if code != 200 || page2["data"].(map[string]any)["complete"] != true || len(page2["data"].(map[string]any)["observations"].([]any)) != 1 {
		t.Fatal("second page incorrect")
	}
	code, fresh := read(user, car, "limit=100")
	if code != 200 || len(fresh["data"].(map[string]any)["observations"].([]any)) != 3 {
		t.Fatal("new snapshot did not include later insert")
	}
	code, foreignPage := read(otherUser, car, "limit=1")
	if code != http.StatusNotFound || foreignPage["error"] != "vehicle_not_found" {
		t.Fatal("cross-account evidence leaked")
	}
	route := fmt.Sprintf("/api/v1/cars/%d/battery-trend/evidence?limit=1", car)
	ownedResponse := httptest.NewRecorder()
	a.carResource(ownedResponse, httptest.NewRequest(http.MethodGet, route, nil), user, "/api/v1/cars/"+strconv.Itoa(car)+"/battery-trend/evidence")
	if ownedResponse.Code != http.StatusOK {
		t.Fatalf("owned route status %d", ownedResponse.Code)
	}
	foreignResponse := httptest.NewRecorder()
	a.carResource(foreignResponse, httptest.NewRequest(http.MethodGet, route, nil), otherUser, "/api/v1/cars/"+strconv.Itoa(car)+"/battery-trend/evidence")
	if foreignResponse.Code != http.StatusNotFound {
		t.Fatalf("foreign route status %d", foreignResponse.Code)
	}
}

func TestBatteryTrendHTTPBindingAndValidation(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	user, car := boundedHistoryTestTenant(t, db, "battery-trend-http")
	_, request := batteryTrendTestRequest(t)
	token, binding, err := db.createArchiveBinding(context.Background(), user, car, archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID})
	if err != nil {
		t.Fatal(err)
	}
	insertBatteryTrendSession(t, db, binding, request.Observations[0])
	a := &app{store: db}
	call := func(path, providedToken string, payload any) *httptest.ResponseRecorder {
		t.Helper()
		data, err := json.Marshal(payload)
		if err != nil {
			t.Fatal(err)
		}
		req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(data))
		req.Header.Set(archiveBindingHeader, providedToken)
		rec := httptest.NewRecorder()
		a.batteryTrendArchiveImport(rec, req)
		return rec
	}
	path := fmt.Sprintf("/api/v1/cars/%d/history/archive/battery-trend/import", car)
	if got := call(path, "invalid", request); got.Code != 401 {
		t.Fatalf("invalid token status %d", got.Code)
	}
	if got := call(fmt.Sprintf("/api/v1/cars/%d/history/archive/battery-trend/import", car+1), token, request); got.Code != 403 {
		t.Fatalf("wrong vehicle status %d", got.Code)
	}
	wrong := request
	wrong.SourceInstanceID = "different-source"
	wrong.PayloadSHA256, _ = batteryTrendDigest(wrong.payload())
	if got := call(path, token, wrong); got.Code != 403 {
		t.Fatalf("wrong binding status %d", got.Code)
	}
	if got := call(path, token, request); got.Code != 200 {
		t.Fatalf("valid import status %d: %s", got.Code, got.Body.String())
	}
	tooLarge := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(bytes.Repeat([]byte(" "), batteryTrendMaxBody+1)))
	tooLarge.Header.Set(archiveBindingHeader, token)
	largeResponse := httptest.NewRecorder()
	a.batteryTrendArchiveImport(largeResponse, tooLarge)
	if largeResponse.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("oversize status %d", largeResponse.Code)
	}
	if _, err := db.pool.Exec(context.Background(), `UPDATE jourvolt_history_archive_bindings SET expires_at=$1 WHERE user_id=$2 AND vehicle_id=$3`, time.Now().Add(-time.Minute), user, car); err != nil {
		t.Fatal(err)
	}
	if got := call(path, token, request); got.Code != 403 {
		t.Fatalf("expired binding status %d", got.Code)
	}
}

func TestBatteryTrendSnapshotDoesNotMissLateCommit(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	user, car := boundedHistoryTestTenant(t, db, "battery-trend-order")
	_, request := batteryTrendTestRequest(t)
	token, binding, err := db.createArchiveBinding(context.Background(), user, car, archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID})
	if err != nil {
		t.Fatal(err)
	}
	tokenHash := archiveBindingTokenHash(token)
	first := request.Observations[0]
	second := first
	second.SourceRecordID = "teslamate:drive:43"
	second.StartedAt, second.EndedAt = "2026-10-29T01:00:00Z", "2026-10-29T01:30:00Z"
	second.Start, second.End = nil, nil
	insertBatteryTrendSession(t, db, binding, first)
	insertBatteryTrendSession(t, db, binding, second)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	tx, err := db.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(context.Background())
	lock := scopedSessionID("battery-trend", user, car, "scope", "")
	if _, err = tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1,0))`, lock); err != nil {
		t.Fatal(err)
	}
	encoded, _ := json.Marshal(first)
	hash, _ := batteryTrendDigest(first)
	start, _ := batteryTrendTimestamp(first.StartedAt)
	end, _ := batteryTrendTimestamp(first.EndedAt)
	if _, err = tx.Exec(ctx, `INSERT INTO jourvolt_battery_trend_evidence(user_id,vehicle_id,source_instance_id,source_vehicle_id,source_record_id,started_at,ended_at,payload_json,payload_sha256) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)`, user, car, binding.SourceInstanceID, binding.SourceVehicleID, first.SourceRecordID, start, end, string(encoded), hash); err != nil {
		t.Fatal(err)
	}
	request.BatchID, request.Observations = "pending-snapshot", []batteryTrendObservation{second}
	request.PayloadSHA256, _ = batteryTrendDigest(request.payload())
	done := make(chan error, 1)
	go func() { _, _, err := storeBatteryTrendBatch(ctx, db, binding, request, tokenHash); done <- err }()
	for {
		var waiters int
		if err = db.pool.QueryRow(ctx, `SELECT COUNT(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted`).Scan(&waiters); err != nil {
			t.Fatal(err)
		}
		if waiters > 0 {
			break
		}
		select {
		case err = <-done:
			t.Fatalf("second commit was not serialized: %v", err)
		case <-ctx.Done():
			t.Fatal("no waiting transaction")
		case <-time.After(10 * time.Millisecond):
		}
	}
	a := &app{store: db}
	read := func(query string) map[string]any {
		t.Helper()
		recorder := httptest.NewRecorder()
		a.batteryTrendEvidenceList(recorder, httptest.NewRequest(http.MethodGet, "/?"+query, nil), user, car)
		if recorder.Code != http.StatusOK {
			t.Fatalf("GET status %d", recorder.Code)
		}
		var result map[string]any
		if err := json.Unmarshal(recorder.Body.Bytes(), &result); err != nil {
			t.Fatal(err)
		}
		return result["data"].(map[string]any)
	}
	before := read("limit=50")
	if before["snapshot_max_seq"].(float64) != 0 || len(before["observations"].([]any)) != 0 {
		t.Fatal("uncommitted evidence became visible")
	}
	if err = tx.Commit(ctx); err != nil {
		t.Fatal(err)
	}
	if err = <-done; err != nil {
		t.Fatal(err)
	}
	oldSnapshot := read("limit=50&snapshot_max_seq=0")
	if len(oldSnapshot["observations"].([]any)) != 0 {
		t.Fatal("old snapshot changed")
	}
	fresh := read("limit=50")
	if len(fresh["observations"].([]any)) != 2 {
		t.Fatal("new snapshot missed a committed record")
	}
}
