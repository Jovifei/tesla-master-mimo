package main

import (
	"context"
	"encoding/json"
	"net/http"
	"sync"
	"testing"
	"time"
)

func archiveReplayPayload(t *testing.T, f *trendV2Fixture, chargeID string) []byte {
	t.Helper()
	obs := f.request.Observations[0]
	amount := 100.0
	request := historyImportRequest{
		Source: "teslamate", SourceInstanceID: f.scope.SourceInstanceID, SourceVehicleID: f.scope.SourceVehicleID, ChunkID: "preserve-synthetic",
		Drives:  []historyImportSession{{SessionID: "drive", SourceRecordID: obs.SourceRecordID, StartedAt: obs.StartedAt, EndedAt: obs.EndedAt}},
		Charges: []historyImportSession{{SessionID: "charge", SourceRecordID: chargeID, StartedAt: "2026-11-01T01:00:00Z", EndedAt: "2026-11-01T02:00:00Z", Cost: &amount}},
	}
	body, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	return body
}

func TestArchiveImportPreservesRawArchiveOnReplay(t *testing.T) {
	for _, route := range []string{"import", "import-v2"} {
		t.Run(route, func(t *testing.T) {
			f := newTrendV2Fixture(t)
			f.app.telemetry = &telemetryService{store: f.db}
			charge := f.seedArchiveCharge(t)
			identity := f.identity(t, f.token)
			_, err := f.db.pool.Exec(context.Background(), `UPDATE jourvolt_telemetry_sessions SET route_json=$2::jsonb WHERE id=$1`, f.driveID, `[{"date":"2026-10-28T01:10:00Z","latitude":1.23,"longitude":4.56,"speed":55,"extra_route":"preserve"}]`)
			if err != nil {
				t.Fatal(err)
			}
			_, err = f.db.pool.Exec(context.Background(), `UPDATE jourvolt_telemetry_sessions SET charge_points_json=$2::jsonb WHERE id=$1`, charge, `[{"ObservedAt":"2026-11-01T01:10:00Z","BatteryLevel":70,"ChargerPower":7.2,"extra_charge":"preserve"}]`)
			if err != nil {
				t.Fatal(err)
			}
			driveBefore, chargeBefore := batteryTrendSessionDigest(t, f.db, f.driveID), batteryTrendSessionDigest(t, f.db, charge)
			body := archiveReplayPayload(t, f, "v2-old-charge")
			for n := 0; n < 2; n++ {
				rec := f.call(http.MethodPost, f.path(route), f.token, identity, body)
				if rec.Code != 200 {
					t.Fatalf("replay status=%d", rec.Code)
				}
				if batteryTrendSessionDigest(t, f.db, f.driveID) != driveBefore || batteryTrendSessionDigest(t, f.db, charge) != chargeBefore {
					t.Fatal("replay changed original route, charge points, cost or other columns")
				}
			}
			var wg sync.WaitGroup
			statuses := make(chan int, 4)
			for i := 0; i < 4; i++ {
				wg.Add(1)
				go func() {
					defer wg.Done()
					statuses <- f.call(http.MethodPost, f.path(route), f.token, identity, body).Code
				}()
			}
			wg.Wait()
			close(statuses)
			for status := range statuses {
				if status != 200 {
					t.Fatalf("parallel replay status=%d", status)
				}
			}
			if batteryTrendSessionDigest(t, f.db, f.driveID) != driveBefore || batteryTrendSessionDigest(t, f.db, charge) != chargeBefore {
				t.Fatal("parallel replay changed original evidence")
			}
		})
	}
}

func TestArchiveImportMalformedStoredPayloadRollsBack(t *testing.T) {
	for _, route := range []string{"import", "import-v2"} {
		for _, column := range []string{"route_json", "charge_points_json"} {
			t.Run(route+"/"+column, func(t *testing.T) {
				f := newTrendV2Fixture(t)
				charge := f.seedArchiveCharge(t)
				body := f.archivePayload(t)
				identity := f.identity(t, f.token)
				_, err := f.db.pool.Exec(context.Background(), "UPDATE jourvolt_telemetry_sessions SET "+column+"=$2::jsonb WHERE id=$1", charge, `{"invalid":"not-an-array"}`)
				if err != nil {
					t.Fatal(err)
				}
				digest := batteryTrendSessionDigest(t, f.db, charge)
				rec := f.call(http.MethodPost, f.path(route), f.token, identity, body)
				if rec.Code != http.StatusServiceUnavailable {
					t.Fatalf("invalid stored payload status=%d", rec.Code)
				}
				f.checkArchiveUnchanged(t, charge, digest)
			})
		}
	}
}

func TestArchiveImportRejectsConflictingStoredSource(t *testing.T) {
	for _, route := range []string{"import", "import-v2"} {
		for _, column := range []string{"source_instance_id", "source_vehicle_id", "source_record_id"} {
			t.Run(route+"/"+column, func(t *testing.T) {
				f := newTrendV2Fixture(t)
				charge := f.seedArchiveCharge(t)
				body := f.archivePayload(t)
				identity := f.identity(t, f.token)
				_, err := f.db.pool.Exec(context.Background(), "UPDATE jourvolt_telemetry_sessions SET "+column+"='conflicting-synthetic-source' WHERE id=$1", charge)
				if err != nil {
					t.Fatal(err)
				}
				digest := batteryTrendSessionDigest(t, f.db, charge)
				rec := f.call(http.MethodPost, f.path(route), f.token, identity, body)
				if rec.Code != http.StatusServiceUnavailable {
					t.Fatalf("conflicting stored source status=%d", rec.Code)
				}
				f.checkArchiveUnchanged(t, charge, digest)
			})
		}
	}
}

func TestArchiveImportSameIDWrongOwnerVehicleKindOrStartRejected(t *testing.T) {
	f := newTrendV2Fixture(t)
	other := newTrendV2Fixture(t)
	obs := f.request.Observations[0]
	start, _ := batteryTrendTimestamp(obs.StartedAt)
	original := batteryTrendSessionDigest(t, f.db, f.driveID)
	for _, test := range []struct {
		name  string
		user  string
		car   int
		kind  string
		shift bool
	}{
		{"owner", other.user, f.car, "drive", false},
		{"vehicle", f.user, other.car, "drive", false},
		{"kind", f.user, f.car, "charge", false},
		{"start", f.user, f.car, "drive", true},
	} {
		t.Run(test.name, func(t *testing.T) {
			incoming := telemetrySession{ID: f.driveID, Kind: test.kind, StartAt: start, Source: "teslamate_archive",
				SourceInstanceID: f.scope.SourceInstanceID, SourceVehicleID: f.scope.SourceVehicleID, SourceRecordID: obs.SourceRecordID}
			if test.shift {
				incoming.StartAt = incoming.StartAt.Add(time.Minute)
			}
			tx, err := f.db.pool.Begin(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			defer tx.Rollback(context.Background())
			if err = upsertImportedSessionPostgres(context.Background(), tx, test.user, test.car, incoming); err == nil {
				t.Fatal("mismatched identity accepted")
			}
			if err = tx.Rollback(context.Background()); err != nil {
				t.Fatal(err)
			}
			if batteryTrendSessionDigest(t, f.db, f.driveID) != original {
				t.Fatal("mismatched identity changed existing row")
			}
		})
	}
}

func TestArchiveImportDifferentEndDoesNotOverwriteExisting(t *testing.T) {
	for _, route := range []string{"import", "import-v2"} {
		t.Run(route, func(t *testing.T) {
			f := newTrendV2Fixture(t)
			f.app.telemetry = &telemetryService{store: f.db}
			identity := f.identity(t, f.token)
			obs := f.request.Observations[0]
			request := historyImportRequest{Source: "teslamate", SourceInstanceID: f.scope.SourceInstanceID, SourceVehicleID: f.scope.SourceVehicleID, ChunkID: "different-end",
				Drives: []historyImportSession{{SessionID: "same", SourceRecordID: obs.SourceRecordID, StartedAt: obs.StartedAt, EndedAt: "2026-10-28T01:31:00Z"}}}
			body, err := json.Marshal(request)
			if err != nil {
				t.Fatal(err)
			}
			before := batteryTrendSessionDigest(t, f.db, f.driveID)
			rec := f.call(http.MethodPost, f.path(route), f.token, identity, body)
			if rec.Code != http.StatusOK {
				t.Fatalf("existing end replay status=%d", rec.Code)
			}
			if batteryTrendSessionDigest(t, f.db, f.driveID) != before {
				t.Fatal("different end overwrote existing archive")
			}
		})
	}
}
