package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

func pendingTrendPost(t *testing.T, a *app, car int, token string, request batteryTrendImportRequest) *httptest.ResponseRecorder {
	t.Helper()
	body, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	path := fmt.Sprintf("/api/v1/cars/%d/history/archive/battery-trend/import", car)
	req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(body))
	req.Header.Set(archiveBindingHeader, token)
	rec := httptest.NewRecorder()
	a.batteryTrendArchiveImport(rec, req)
	return rec
}

func pendingTrendCounts(t *testing.T, db *store, user string, car int) (int, int) {
	t.Helper()
	var evidence, batches int
	err := db.pool.QueryRow(context.Background(), `SELECT (SELECT count(*) FROM jourvolt_battery_trend_evidence WHERE user_id=$1 AND vehicle_id=$2),(SELECT count(*) FROM jourvolt_battery_trend_batches WHERE user_id=$1 AND vehicle_id=$2)`, user, car).Scan(&evidence, &batches)
	if err != nil {
		t.Fatal(err)
	}
	return evidence, batches
}

func pendingTrendSequence(t *testing.T, db *store) (int64, bool) {
	t.Helper()
	var value int64
	var called bool
	if err := db.pool.QueryRow(context.Background(), `SELECT last_value,is_called FROM jourvolt_battery_trend_evidence_ingest_seq_seq`).Scan(&value, &called); err != nil {
		t.Fatal(err)
	}
	return value, called
}

func pendingTrendSecond(first batteryTrendObservation) batteryTrendObservation {
	second := first
	second.SourceRecordID = "teslamate:drive:43"
	second.StartedAt, second.EndedAt = "2026-10-29T01:00:00Z", "2026-10-29T01:30:00Z"
	second.Start, second.End = nil, nil
	return second
}

func TestBatteryTrendMissingArchiveReturnsPendingWithoutWritesThenSucceeds(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	user, car := boundedHistoryTestTenant(t, db, "battery-pending")
	_, request := batteryTrendTestRequest(t)
	token, binding, err := db.createArchiveBinding(context.Background(), user, car, archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID})
	if err != nil {
		t.Fatal(err)
	}
	firstID := insertBatteryTrendSession(t, db, binding, request.Observations[0])
	firstBefore := batteryTrendSessionDigest(t, db, firstID)
	second := pendingTrendSecond(request.Observations[0])
	request.Observations = append(request.Observations, second)
	request.PayloadSHA256, _ = batteryTrendDigest(request.payload())
	a := &app{store: db}
	beforeSequence, beforeCalled := pendingTrendSequence(t, db)
	response := pendingTrendPost(t, a, car, token, request)
	var body map[string]any
	if response.Code != http.StatusServiceUnavailable || json.Unmarshal(response.Body.Bytes(), &body) != nil || len(body) != 1 || body["error"] != "battery_trend_archive_pending" || response.Header().Get("Retry-After") == "" {
		t.Fatalf("missing archive was not signalled as pending: status %d", response.Code)
	}
	if evidence, batches := pendingTrendCounts(t, db, user, car); evidence != 0 || batches != 0 || batteryTrendSessionDigest(t, db, firstID) != firstBefore {
		t.Fatal("pending response changed evidence, receipt, or the existing session")
	}
	if sequence, called := pendingTrendSequence(t, db); sequence != beforeSequence || called != beforeCalled {
		t.Fatal("pending response consumed an evidence sequence")
	}
	insertBatteryTrendSession(t, db, binding, second)
	accepted := pendingTrendPost(t, a, car, token, request)
	replayed := pendingTrendPost(t, a, car, token, request)
	if accepted.Code != http.StatusOK || replayed.Code != http.StatusOK || !bytes.Equal(accepted.Body.Bytes(), replayed.Body.Bytes()) {
		t.Fatal("matching archive did not accept and idempotently replay the batch")
	}
	if evidence, batches := pendingTrendCounts(t, db, user, car); evidence != 2 || batches != 1 || batteryTrendSessionDigest(t, db, firstID) != firstBefore {
		t.Fatal("accepted batch has incorrect state or changed the source session")
	}
}

func TestBatteryTrendPendingUsesExistingBridgeWireFixture(t *testing.T) {
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
	user, car := boundedHistoryTestTenant(t, db, "battery-pending-wire")
	token, binding, err := db.createArchiveBinding(context.Background(), user, car, archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID})
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewTLSServer(&app{store: db, provider: testProvider{vehicles: map[string][]vehicle{}}})
	defer server.Close()
	endpoint := fmt.Sprintf("%s/api/v1/cars/%d/history/archive/battery-trend/import", server.URL, car)
	call := func() (int, map[string]any) {
		t.Helper()
		req, requestErr := http.NewRequest(http.MethodPost, endpoint, bytes.NewReader(fixture))
		if requestErr != nil {
			t.Fatal(requestErr)
		}
		req.Header.Set(archiveBindingHeader, token)
		response, requestErr := server.Client().Do(req)
		if requestErr != nil {
			t.Fatal(requestErr)
		}
		defer response.Body.Close()
		var body map[string]any
		if json.NewDecoder(response.Body).Decode(&body) != nil {
			t.Fatal("invalid synthetic response")
		}
		return response.StatusCode, body
	}
	if status, body := call(); status != http.StatusServiceUnavailable || body["error"] != "battery_trend_archive_pending" {
		t.Fatalf("bridge wire missing archive status %d", status)
	}
	if evidence, batches := pendingTrendCounts(t, db, user, car); evidence != 0 || batches != 0 {
		t.Fatal("bridge pending wire changed persisted evidence")
	}
	insertBatteryTrendSession(t, db, binding, request.Observations[0])
	status, body := call()
	data, ok := body["data"].(map[string]any)
	if status != http.StatusOK || !ok || data["batch_id"] != request.BatchID || data["payload_sha256"] != request.PayloadSHA256 {
		t.Fatal("same bridge wire did not succeed after archive arrived")
	}
}

func TestBatteryTrendMissingArchiveWithMatchingPriorEvidenceWaitsThenSucceeds(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	user, car := boundedHistoryTestTenant(t, db, "battery-pending-mixed-existing")
	_, request := batteryTrendTestRequest(t)
	token, binding, err := db.createArchiveBinding(context.Background(), user, car, archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID})
	if err != nil {
		t.Fatal(err)
	}
	first := request.Observations[0] // Not archived yet.
	second := pendingTrendSecond(first)
	secondID := insertBatteryTrendSession(t, db, binding, second)
	prior := request
	prior.BatchID, prior.Observations = "prior-complete", []batteryTrendObservation{second}
	prior.PayloadSHA256, _ = batteryTrendDigest(prior.payload())
	if _, _, err = storeBatteryTrendBatch(context.Background(), db, binding, prior, archiveBindingTokenHash(token)); err != nil {
		t.Fatal(err)
	}
	secondBefore := batteryTrendSessionDigest(t, db, secondID)
	sequenceBefore, calledBefore := pendingTrendSequence(t, db)
	a := &app{store: db}
	for order := 0; order < 2; order++ {
		request.Observations = []batteryTrendObservation{first, second}
		if order == 1 {
			request.Observations[0], request.Observations[1] = request.Observations[1], request.Observations[0]
		}
		request.PayloadSHA256, _ = batteryTrendDigest(request.payload())
		response := pendingTrendPost(t, a, car, token, request)
		var body map[string]any
		if response.Code != http.StatusServiceUnavailable || json.Unmarshal(response.Body.Bytes(), &body) != nil || body["error"] != "battery_trend_archive_pending" {
			t.Fatalf("matching existing evidence blocked pending order %d: status %d", order, response.Code)
		}
		if evidence, batches := pendingTrendCounts(t, db, user, car); evidence != 1 || batches != 1 || batteryTrendSessionDigest(t, db, secondID) != secondBefore {
			t.Fatal("pending batch changed existing evidence, receipts, or source session")
		}
		if sequence, called := pendingTrendSequence(t, db); sequence != sequenceBefore || called != calledBefore {
			t.Fatal("pending batch consumed an evidence sequence")
		}
	}
	insertBatteryTrendSession(t, db, binding, first)
	request.Observations = []batteryTrendObservation{first, second}
	request.PayloadSHA256, _ = batteryTrendDigest(request.payload())
	response := pendingTrendPost(t, a, car, token, request)
	var receipt struct {
		Data struct {
			Inserted  int `json:"inserted"`
			Unchanged int `json:"unchanged"`
		} `json:"data"`
	}
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &receipt) != nil || receipt.Data.Inserted != 1 || receipt.Data.Unchanged != 1 {
		t.Fatalf("mixed evidence did not recover: status %d", response.Code)
	}
	if evidence, batches := pendingTrendCounts(t, db, user, car); evidence != 2 || batches != 2 || batteryTrendSessionDigest(t, db, secondID) != secondBefore {
		t.Fatal("recovered batch changed prior evidence or source session")
	}
}

func TestBatteryTrendPendingNeverMasksConflictsOrPriorEvidence(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	for _, scenario := range []string{"wrong-window", "quarantined", "wrong-source", "wrong-instance", "wrong-vehicle", "wrong-record", "natural-key", "natural-key-wrong-source", "expected-id-foreign-owner", "corrupt-evidence", "orphan-evidence", "batch-replay-collision", "committed-replay-missing"} {
		t.Run(scenario, func(t *testing.T) {
			db := boundedHistoryTestStore(t)
			user, car := boundedHistoryTestTenant(t, db, "battery-pending-conflict")
			_, request := batteryTrendTestRequest(t)
			token, binding, err := db.createArchiveBinding(context.Background(), user, car, archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID})
			if err != nil {
				t.Fatal(err)
			}
			second := pendingTrendSecond(request.Observations[0])
			secondID := insertBatteryTrendSession(t, db, binding, second)
			ctx := context.Background()
			switch scenario {
			case "wrong-window":
				_, err = db.pool.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET ended_at=ended_at+interval '1 second' WHERE id=$1`, secondID)
			case "quarantined":
				_, err = db.pool.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET quality_state='quarantined' WHERE id=$1`, secondID)
			case "wrong-source":
				_, err = db.pool.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET source='telemetry_mqtt' WHERE id=$1`, secondID)
			case "wrong-instance":
				_, err = db.pool.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET source_instance_id='different-instance' WHERE id=$1`, secondID)
			case "wrong-vehicle":
				_, err = db.pool.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET source_vehicle_id='different-vehicle' WHERE id=$1`, secondID)
			case "wrong-record":
				_, err = db.pool.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET source_record_id='teslamate:drive:999' WHERE id=$1`, secondID)
			case "natural-key":
				_, err = db.pool.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET id=$1 WHERE id=$2`, "synthetic-other-identity-"+mustRandomToken(t), secondID)
			case "natural-key-wrong-source":
				_, err = db.pool.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET id=$1,source='telemetry_mqtt' WHERE id=$2`, "synthetic-other-identity-"+mustRandomToken(t), secondID)
			case "expected-id-foreign-owner":
				otherUser, otherCar := boundedHistoryTestTenant(t, db, "battery-pending-foreign")
				_, err = db.pool.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET user_id=$1,vehicle_id=$2 WHERE id=$3`, otherUser, otherCar, secondID)
			case "corrupt-evidence", "orphan-evidence", "batch-replay-collision", "committed-replay-missing":
				prior := request
				prior.BatchID, prior.Observations = "previous-batch", []batteryTrendObservation{second}
				prior.PayloadSHA256, _ = batteryTrendDigest(prior.payload())
				if _, _, err = storeBatteryTrendBatch(ctx, db, binding, prior, archiveBindingTokenHash(token)); err != nil {
					t.Fatal(err)
				}
				if scenario == "orphan-evidence" || scenario == "committed-replay-missing" {
					_, err = db.pool.Exec(ctx, `DELETE FROM jourvolt_telemetry_sessions WHERE id=$1`, secondID)
				}
				if scenario == "corrupt-evidence" {
					_, err = db.pool.Exec(ctx, `UPDATE jourvolt_battery_trend_evidence SET payload_json='{}' WHERE user_id=$1 AND vehicle_id=$2`, user, car)
				}
				if scenario == "batch-replay-collision" {
					request.BatchID = prior.BatchID
				}
				if scenario == "committed-replay-missing" {
					request = prior
				}
			}
			if err != nil {
				t.Fatal(err)
			}
			if scenario != "committed-replay-missing" {
				request.Observations = append(request.Observations, second)
			}
			beforeEvidence, beforeBatches := pendingTrendCounts(t, db, user, car)
			beforeSequence, beforeCalled := pendingTrendSequence(t, db)
			orders := 2
			if scenario == "committed-replay-missing" {
				orders = 1
			}
			for order := 0; order < orders; order++ {
				if order == 1 {
					request.Observations[0], request.Observations[1] = request.Observations[1], request.Observations[0]
				}
				request.PayloadSHA256, _ = batteryTrendDigest(request.payload())
				response := pendingTrendPost(t, &app{store: db}, car, token, request)
				if response.Code != http.StatusConflict {
					t.Fatalf("conflicting batch order %d did not stop: status %d", order, response.Code)
				}
				if evidence, batches := pendingTrendCounts(t, db, user, car); evidence != beforeEvidence || batches != beforeBatches {
					t.Fatal("conflicting batch changed evidence or receipts")
				}
				if sequence, called := pendingTrendSequence(t, db); sequence != beforeSequence || called != beforeCalled {
					t.Fatal("conflicting batch consumed an evidence sequence")
				}
			}
		})
	}
}

func TestBatteryTrendMissingArchiveNeverBypassesBinding(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	user, car := boundedHistoryTestTenant(t, db, "battery-pending-auth")
	_, request := batteryTrendTestRequest(t)
	scope := archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID}
	token, _, err := db.createArchiveBinding(context.Background(), user, car, scope)
	if err != nil {
		t.Fatal(err)
	}
	a := &app{store: db}
	if response := pendingTrendPost(t, a, car, "invalid", request); response.Code != http.StatusUnauthorized {
		t.Fatalf("invalid token status %d", response.Code)
	}
	if response := pendingTrendPost(t, a, car+1, token, request); response.Code != http.StatusForbidden {
		t.Fatalf("wrong vehicle status %d", response.Code)
	}
	wrong := request
	wrong.SourceInstanceID = "different-source"
	wrong.PayloadSHA256, _ = batteryTrendDigest(wrong.payload())
	if response := pendingTrendPost(t, a, car, token, wrong); response.Code != http.StatusForbidden {
		t.Fatalf("wrong scope status %d", response.Code)
	}
	wrongHash := request
	wrongHash.PayloadSHA256 = "0000000000000000000000000000000000000000000000000000000000000000"
	if response := pendingTrendPost(t, a, car, token, wrongHash); response.Code != http.StatusUnprocessableEntity {
		t.Fatalf("wrong request hash status %d", response.Code)
	}
	wrongRecord := request
	wrongRecord.Observations = append([]batteryTrendObservation(nil), request.Observations...)
	wrongRecord.Observations[0].SourceRecordID = "teslamate:drive:042"
	wrongRecord.PayloadSHA256, _ = batteryTrendDigest(wrongRecord.payload())
	if response := pendingTrendPost(t, a, car, token, wrongRecord); response.Code != http.StatusUnprocessableEntity {
		t.Fatalf("invalid record format status %d", response.Code)
	}
	if _, err = db.pool.Exec(context.Background(), `UPDATE jourvolt_history_archive_bindings SET expires_at=$1 WHERE user_id=$2 AND vehicle_id=$3`, time.Now().Add(-time.Minute), user, car); err != nil {
		t.Fatal(err)
	}
	if response := pendingTrendPost(t, a, car, token, request); response.Code != http.StatusForbidden {
		t.Fatalf("expired token status %d", response.Code)
	}
	token, _, err = db.createArchiveBinding(context.Background(), user, car, scope)
	if err != nil {
		t.Fatal(err)
	}
	if err = db.revokeArchiveBinding(context.Background(), user, car, scope); err != nil {
		t.Fatal(err)
	}
	if response := pendingTrendPost(t, a, car, token, request); response.Code != http.StatusForbidden {
		t.Fatalf("revoked token status %d", response.Code)
	}
	if evidence, batches := pendingTrendCounts(t, db, user, car); evidence != 0 || batches != 0 {
		t.Fatal("unauthorized requests changed evidence or receipts")
	}
}

func TestBatteryTrendNonQuarantinedArchiveStillEligible(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	user, car := boundedHistoryTestTenant(t, db, "battery-pending-quality")
	_, request := batteryTrendTestRequest(t)
	token, binding, err := db.createArchiveBinding(context.Background(), user, car, archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID})
	if err != nil {
		t.Fatal(err)
	}
	id := insertBatteryTrendSession(t, db, binding, request.Observations[0])
	if _, err = db.pool.Exec(context.Background(), `UPDATE jourvolt_telemetry_sessions SET quality_state='incomplete' WHERE id=$1`, id); err != nil {
		t.Fatal(err)
	}
	response := pendingTrendPost(t, &app{store: db}, car, token, request)
	if response.Code != http.StatusOK {
		t.Fatalf("non-quarantined archive was rejected: %d", response.Code)
	}
}

func TestBatteryTrendNullSourceNaturalKeyIsConflict(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	user, car := boundedHistoryTestTenant(t, db, "battery-pending-null-source")
	_, request := batteryTrendTestRequest(t)
	_, binding, err := db.createArchiveBinding(context.Background(), user, car, archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID})
	if err != nil {
		t.Fatal(err)
	}
	observation := request.Observations[0]
	id := insertBatteryTrendSession(t, db, binding, observation)
	before := batteryTrendSessionDigest(t, db, id)
	ctx := context.Background()
	tx, err := db.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(ctx)
	if _, err = tx.Exec(ctx, `ALTER TABLE jourvolt_telemetry_sessions ALTER COLUMN source DROP NOT NULL`); err != nil {
		t.Fatal(err)
	}
	if _, err = tx.Exec(ctx, `UPDATE jourvolt_telemetry_sessions SET id=$1,source=NULL WHERE id=$2`, "synthetic-null-source-"+mustRandomToken(t), id); err != nil {
		t.Fatal(err)
	}
	var conflict bool
	if err = tx.QueryRow(ctx, batteryTrendArchiveConflictQuery, id, user, car, binding.SourceInstanceID, binding.SourceVehicleID, observation.SourceRecordID).Scan(&conflict); err != nil || !conflict {
		t.Fatal("null source with matching natural key was not detected")
	}
	if err = tx.Rollback(ctx); err != nil || batteryTrendSessionDigest(t, db, id) != before {
		t.Fatal("synthetic null source test did not restore the session")
	}
}

func TestBatteryTrendConflictQueryRepresentativePlan(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	tx, err := db.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(ctx)
	_, err = tx.Exec(ctx, `CREATE TEMP TABLE plan_sessions (
id text PRIMARY KEY,user_id text NOT NULL,vehicle_id integer NOT NULL,kind text NOT NULL,
started_at timestamptz NOT NULL,source text NOT NULL,source_instance_id text,
source_vehicle_id text,source_record_id text) ON COMMIT DROP;
CREATE UNIQUE INDEX plan_native_idx ON plan_sessions(user_id,vehicle_id,kind,started_at) WHERE source<>'teslamate_archive';
CREATE UNIQUE INDEX plan_archive_idx ON plan_sessions(user_id,vehicle_id,kind,source_instance_id,source_vehicle_id,source_record_id) WHERE source='teslamate_archive';
INSERT INTO plan_sessions
SELECT 'synthetic-'||g::text,CASE WHEN g<=10000 THEN 'tenant-a' ELSE 'tenant-'||(g%1000)::text END,
CASE WHEN g<=10000 THEN 7 ELSE (g%1000)+100 END,'drive','2026-01-01 00:00:00+00'::timestamptz+g*interval '1 second',
CASE WHEN g%2=0 THEN 'teslamate_archive' ELSE 'local_import' END,'source-a','8','teslamate:drive:'||g::text
FROM generate_series(1,30000) g;
ANALYZE plan_sessions`)
	if err != nil {
		t.Fatal(err)
	}
	query := strings.ReplaceAll(batteryTrendArchiveConflictQuery, "jourvolt_telemetry_sessions", "plan_sessions")
	var raw []byte
	if err = tx.QueryRow(ctx, `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) `+query, "absent-id", "tenant-a", 7, "source-a", "8", "teslamate:drive:999999").Scan(&raw); err != nil {
		t.Fatal(err)
	}
	var decoded []map[string]any
	if err = json.Unmarshal(raw, &decoded); err != nil || len(decoded) != 1 {
		t.Fatal("synthetic plan could not be decoded")
	}
	indexes := map[string]bool{}
	var visit func(any)
	visit = func(value any) {
		switch value := value.(type) {
		case map[string]any:
			if name, ok := value["Index Name"].(string); ok {
				indexes[name] = true
			}
			for _, child := range value {
				visit(child)
			}
		case []any:
			for _, child := range value {
				visit(child)
			}
		}
	}
	visit(decoded[0])
	var conflict bool
	if err = tx.QueryRow(ctx, query, "absent-id", "tenant-a", 7, "source-a", "8", "teslamate:drive:999999").Scan(&conflict); err != nil || conflict {
		t.Fatal("absent synthetic identity was classified as a conflict")
	}
	t.Logf("30,000 synthetic sessions: observed indexes=%v, execution_ms=%v (planner choices are not a functional gate)", indexes, decoded[0]["Execution Time"])
}

func TestBatteryTrendPendingDoesNotLeakAnotherOwnerArchive(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	user, car := boundedHistoryTestTenant(t, db, "battery-pending-owner")
	otherUser, otherCar := boundedHistoryTestTenant(t, db, "battery-pending-other-owner")
	_, request := batteryTrendTestRequest(t)
	scope := archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID}
	token, _, err := db.createArchiveBinding(context.Background(), user, car, scope)
	if err != nil {
		t.Fatal(err)
	}
	_, other, err := db.createArchiveBinding(context.Background(), otherUser, otherCar, scope)
	if err != nil {
		t.Fatal(err)
	}
	id := insertBatteryTrendSession(t, db, other, request.Observations[0])
	before := batteryTrendSessionDigest(t, db, id)
	response := pendingTrendPost(t, &app{store: db}, car, token, request)
	if response.Code != http.StatusServiceUnavailable {
		t.Fatalf("other owner affected scoped pending response: %d", response.Code)
	}
	if evidence, batches := pendingTrendCounts(t, db, user, car); evidence != 0 || batches != 0 || batteryTrendSessionDigest(t, db, id) != before {
		t.Fatal("other owner data or candidate state changed")
	}
}

func TestBatteryTrendPendingRechecksVehicleOwnerAfterConcurrentChange(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	user, car := boundedHistoryTestTenant(t, db, "battery-pending-owner-race")
	otherUser, _ := boundedHistoryTestTenant(t, db, "battery-pending-new-owner")
	_, request := batteryTrendTestRequest(t)
	token, _, err := db.createArchiveBinding(context.Background(), user, car, archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	blocker, err := db.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer blocker.Rollback(context.Background())
	if _, err = blocker.Exec(ctx, `UPDATE jourvolt_vehicles SET user_id=$1 WHERE id=$2`, otherUser, car); err != nil {
		t.Fatal(err)
	}
	body, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	path := fmt.Sprintf("/api/v1/cars/%d/history/archive/battery-trend/import", car)
	req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(body))
	req.Header.Set(archiveBindingHeader, token)
	response := make(chan *httptest.ResponseRecorder, 1)
	go func() {
		rec := httptest.NewRecorder()
		(&app{store: db}).batteryTrendArchiveImport(rec, req)
		response <- rec
	}()
	for {
		var waiting int
		if err = db.pool.QueryRow(ctx, `SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%jourvolt_vehicles%FOR SHARE%'`).Scan(&waiting); err != nil {
			t.Fatal(err)
		}
		if waiting > 0 {
			break
		}
		select {
		case rec := <-response:
			t.Fatalf("owner change was not locked before responding: %d", rec.Code)
		case <-ctx.Done():
			t.Fatal("owner lock wait did not appear")
		case <-time.After(10 * time.Millisecond):
		}
	}
	if err = blocker.Commit(ctx); err != nil {
		t.Fatal(err)
	}
	select {
	case rec := <-response:
		if rec.Code != http.StatusForbidden {
			t.Fatalf("changed vehicle ownership returned %d", rec.Code)
		}
	case <-ctx.Done():
		t.Fatal("request did not finish after owner change")
	}
	if evidence, batches := pendingTrendCounts(t, db, user, car); evidence != 0 || batches != 0 {
		t.Fatal("owner race changed evidence or receipts")
	}
}

func TestBatteryTrendPendingOwnerLockCancellationIsGenericFailure(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	user, car := boundedHistoryTestTenant(t, db, "battery-pending-owner-cancel")
	_, request := batteryTrendTestRequest(t)
	token, _, err := db.createArchiveBinding(context.Background(), user, car, archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	blocker, err := db.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer blocker.Rollback(context.Background())
	var locked int
	if err = blocker.QueryRow(ctx, `SELECT id FROM jourvolt_vehicles WHERE id=$1 FOR UPDATE`, car).Scan(&locked); err != nil {
		t.Fatal(err)
	}
	body, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	requestCtx, stopRequest := context.WithCancel(context.Background())
	defer stopRequest()
	path := fmt.Sprintf("/api/v1/cars/%d/history/archive/battery-trend/import", car)
	req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(body)).WithContext(requestCtx)
	req.Header.Set(archiveBindingHeader, token)
	response := make(chan *httptest.ResponseRecorder, 1)
	go func() {
		rec := httptest.NewRecorder()
		(&app{store: db}).batteryTrendArchiveImport(rec, req)
		response <- rec
	}()
	for {
		var waiting int
		if err = db.pool.QueryRow(ctx, `SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%jourvolt_vehicles%FOR SHARE%'`).Scan(&waiting); err != nil {
			t.Fatal(err)
		}
		if waiting > 0 {
			break
		}
		select {
		case rec := <-response:
			t.Fatalf("owner lock did not block request: %d", rec.Code)
		case <-ctx.Done():
			t.Fatal("owner lock wait did not appear")
		case <-time.After(10 * time.Millisecond):
		}
	}
	stopRequest()
	select {
	case rec := <-response:
		var result map[string]any
		if rec.Code != http.StatusServiceUnavailable || json.Unmarshal(rec.Body.Bytes(), &result) != nil || result["error"] != "battery_trend_unavailable" {
			t.Fatalf("cancelled owner lock was classified as pending: %d", rec.Code)
		}
	case <-ctx.Done():
		t.Fatal("cancelled request did not finish")
	}
	if evidence, batches := pendingTrendCounts(t, db, user, car); evidence != 0 || batches != 0 {
		t.Fatal("cancelled owner lock changed evidence or receipts")
	}
}

func TestBatteryTrendPendingLocksVehicleBeforeRecheckingBinding(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	user, car := boundedHistoryTestTenant(t, db, "battery-pending-lock-order")
	_, request := batteryTrendTestRequest(t)
	token, _, err := db.createArchiveBinding(context.Background(), user, car, archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	blocker, err := db.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer blocker.Rollback(context.Background())
	var locked int
	if err = blocker.QueryRow(ctx, `SELECT id FROM jourvolt_vehicles WHERE id=$1 FOR UPDATE`, car).Scan(&locked); err != nil {
		t.Fatal(err)
	}
	body, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	path := fmt.Sprintf("/api/v1/cars/%d/history/archive/battery-trend/import", car)
	req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(body))
	req.Header.Set(archiveBindingHeader, token)
	response := make(chan *httptest.ResponseRecorder, 1)
	go func() {
		rec := httptest.NewRecorder()
		(&app{store: db}).batteryTrendArchiveImport(rec, req)
		response <- rec
	}()
	for {
		var waiting int
		if err = db.pool.QueryRow(ctx, `SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%jourvolt_vehicles%FOR SHARE%'`).Scan(&waiting); err != nil {
			t.Fatal(err)
		}
		if waiting > 0 {
			break
		}
		select {
		case rec := <-response:
			t.Fatalf("vehicle lock did not block request: %d", rec.Code)
		case <-ctx.Done():
			t.Fatal("vehicle lock wait did not appear")
		case <-time.After(10 * time.Millisecond):
		}
	}
	mutationCtx, mutationCancel := context.WithTimeout(ctx, 2*time.Second)
	defer mutationCancel()
	if _, err = blocker.Exec(mutationCtx, `UPDATE jourvolt_history_archive_bindings SET revoked_at=clock_timestamp() WHERE user_id=$1 AND vehicle_id=$2`, user, car); err != nil {
		t.Fatal("binding update blocked behind a request waiting on the vehicle lock")
	}
	if err = blocker.Commit(ctx); err != nil {
		t.Fatal(err)
	}
	select {
	case rec := <-response:
		if rec.Code != http.StatusForbidden {
			t.Fatalf("binding revoked during vehicle wait returned %d", rec.Code)
		}
	case <-ctx.Done():
		t.Fatal("request did not finish after binding revocation")
	}
	if evidence, batches := pendingTrendCounts(t, db, user, car); evidence != 0 || batches != 0 {
		t.Fatal("binding revocation changed evidence or receipts")
	}
}

func TestBatteryTrendPendingRechecksBindingExpiryAfterOwnerLockWait(t *testing.T) {
	requireIsolatedChargeLocationDB(t)
	db := boundedHistoryTestStore(t)
	user, car := boundedHistoryTestTenant(t, db, "battery-pending-expiry-wait")
	_, request := batteryTrendTestRequest(t)
	token, _, err := db.createArchiveBinding(context.Background(), user, car, archiveBindingRequest{SourceType: "teslamate", SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if _, err = db.pool.Exec(ctx, `UPDATE jourvolt_history_archive_bindings SET expires_at=clock_timestamp()+interval '3 seconds' WHERE user_id=$1 AND vehicle_id=$2`, user, car); err != nil {
		t.Fatal(err)
	}
	blocker, err := db.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer blocker.Rollback(context.Background())
	var locked int
	if err = blocker.QueryRow(ctx, `SELECT id FROM jourvolt_vehicles WHERE id=$1 FOR UPDATE`, car).Scan(&locked); err != nil {
		t.Fatal(err)
	}
	body, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	path := fmt.Sprintf("/api/v1/cars/%d/history/archive/battery-trend/import", car)
	req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(body))
	req.Header.Set(archiveBindingHeader, token)
	response := make(chan *httptest.ResponseRecorder, 1)
	go func() {
		rec := httptest.NewRecorder()
		(&app{store: db}).batteryTrendArchiveImport(rec, req)
		response <- rec
	}()
	for {
		var waiting int
		if err = db.pool.QueryRow(ctx, `SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%jourvolt_vehicles%FOR SHARE%'`).Scan(&waiting); err != nil {
			t.Fatal(err)
		}
		if waiting > 0 {
			break
		}
		select {
		case rec := <-response:
			t.Fatalf("owner lock did not block request: %d", rec.Code)
		case <-ctx.Done():
			t.Fatal("owner lock wait did not appear")
		case <-time.After(10 * time.Millisecond):
		}
	}
	for {
		var expired bool
		if err = db.pool.QueryRow(ctx, `SELECT expires_at<=clock_timestamp() FROM jourvolt_history_archive_bindings WHERE user_id=$1 AND vehicle_id=$2`, user, car).Scan(&expired); err != nil {
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
			t.Fatalf("expired binding returned %d", rec.Code)
		}
	case <-ctx.Done():
		t.Fatal("expired request did not finish")
	}
	if evidence, batches := pendingTrendCounts(t, db, user, car); evidence != 0 || batches != 0 {
		t.Fatal("expired pending request changed evidence or receipts")
	}
}
