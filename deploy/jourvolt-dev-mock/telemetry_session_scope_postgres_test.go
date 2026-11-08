package main

import (
	"context"
	"fmt"
	"testing"
	"time"
)

// These cases require an explicitly supplied disposable PostgreSQL database.
// All identities, VIN hashes, and observations are synthetic.
func TestFleetSessionPostgresSimultaneousScopes(t *testing.T) {
	for _, kind := range []string{"drive", "charge"} {
		for _, relation := range []string{"two_owners_same_vin", "one_owner_two_cars", "different_owners_and_cars"} {
			t.Run(kind+"/"+relation, func(t *testing.T) {
				db := boundedHistoryTestStore(t)
				a, b := fleetScopeTestRefs(t, db, relation)
				service := &telemetryService{store: db, config: &telemetryConfig{StopDebounce: defaultDriveStopDebounce}}
				ctx := context.Background()
				for _, ref := range []telemetryVehicleRef{a, b} {
					if err := service.registerVehicle(ctx, ref); err != nil {
						t.Fatal(err)
					}
				}
				start := time.Now().UTC().Truncate(time.Microsecond)
				field, initial, terminal := "Gear", "D", "P"
				if kind == "charge" {
					field, initial, terminal = "DetailedChargeState", "Charging", "Complete"
				}
				refs := []telemetryVehicleRef{a, b}
				if relation == "two_owners_same_vin" {
					refs = refs[:1]
				}
				var records []telemetryRecord
				for _, ref := range refs {
					record := telemetryRecord{VINHash: ref.VINHash, FieldName: field, Value: initial, ObservedAt: start, EventID: mustRandomToken(t)}
					records = append(records, record)
					want := 1
					if relation == "two_owners_same_vin" {
						want = 2
					}
					if accepted, err := service.ingest(ctx, record); err != nil || accepted != want {
						t.Fatalf("start: accepted=%d want=%d err=%v", accepted, want, err)
					}
				}
				var ids []string
				var publics []int
				for _, ref := range []telemetryVehicleRef{a, b} {
					session, ok, err := service.openSession(ctx, ref.UserID, ref.VehicleID, kind)
					if err != nil || !ok {
						t.Errorf("scope has no durable open %s: ok=%t err=%v", kind, ok, err)
						continue
					}
					ids = append(ids, session.ID)
					publics = append(publics, session.PublicID)
				}
				if len(ids) != 2 || ids[0] == ids[1] || publics[0] == publics[1] {
					t.Errorf("independent scope identities: ids=%v public_ids=%v", ids, publics)
				}
				// Restart, then QoS replay and complete at the same source time.
				service = &telemetryService{store: db, config: service.config}
				for _, record := range records {
					if accepted, err := service.ingest(ctx, record); err != nil || accepted != 0 {
						t.Fatalf("start replay: accepted=%d err=%v", accepted, err)
					}
					record.Value, record.ObservedAt, record.EventID = terminal, start.Add(time.Minute), mustRandomToken(t)
					if _, err := service.ingest(ctx, record); err != nil {
						t.Fatal(err)
					}
					if accepted, err := service.ingest(ctx, record); err != nil || accepted != 0 {
						t.Fatalf("completion replay: accepted=%d err=%v", accepted, err)
					}
				}
				if kind == "drive" {
					if n, err := service.finalizeDuePostgres(ctx, start.Add(2*time.Minute)); err != nil || n != 2 {
						t.Errorf("due scopes=%d want=2 err=%v", n, err)
					}
					if n, err := service.finalizeDuePostgres(ctx, start.Add(3*time.Minute)); err != nil || n != 0 {
						t.Errorf("repeat finalizer=%d err=%v", n, err)
					}
				}
				keys := map[string]bool{}
				for _, ref := range []telemetryVehicleRef{a, b} {
					var count int
					var key string
					err := db.pool.QueryRow(ctx, `SELECT count(*), COALESCE(max(completion_key),'') FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2 AND kind=$3 AND ended_at IS NOT NULL`, ref.UserID, ref.VehicleID, kind).Scan(&count, &key)
					if err != nil || count != 1 || key == "" || keys[key] {
						t.Errorf("completed scope count=%d key=%q err=%v", count, key, err)
					}
					keys[key] = true
				}
			})
		}
	}
}

func fleetScopeTestRefs(t *testing.T, db *store, relation string) (telemetryVehicleRef, telemetryVehicleRef) {
	t.Helper()
	u1, v1 := boundedHistoryTestTenant(t, db, "scope-a")
	u2, v2 := boundedHistoryTestTenant(t, db, "scope-b")
	if relation == "one_owner_two_cars" {
		u2 = u1
		if err := db.pool.QueryRow(context.Background(), `INSERT INTO jourvolt_vehicles(user_id,provider_vehicle_id,vin_ciphertext,display_name,state,updated_at) VALUES ($1,$2,'synthetic','Scope second car','online',now()) RETURNING id`, u1, mustRandomToken(t)).Scan(&v2); err != nil {
			t.Fatal(err)
		}
	}
	a := telemetryVehicleRef{UserID: u1, VehicleID: v1, VINHash: "scope-vin-" + mustRandomToken(t)}
	b := telemetryVehicleRef{UserID: u2, VehicleID: v2, VINHash: "scope-vin-" + mustRandomToken(t)}
	if relation == "two_owners_same_vin" {
		b.VINHash = a.VINHash
	}
	return a, b
}

func TestFleetSessionPostgresLegacyOpenAndCompletionRemainStable(t *testing.T) {
	for _, mode := range []string{"drive_event", "drive_finalizer", "charge_event"} {
		t.Run(mode, func(t *testing.T) {
			db := boundedHistoryTestStore(t)
			user, car := boundedHistoryTestTenant(t, db, "legacy-scope")
			ref := telemetryVehicleRef{UserID: user, VehicleID: car, VINHash: "legacy-vin-" + mustRandomToken(t)}
			ctx := context.Background()
			service := &telemetryService{store: db, config: &telemetryConfig{StopDebounce: defaultDriveStopDebounce}}
			if err := service.registerVehicle(ctx, ref); err != nil {
				t.Fatal(err)
			}
			kind := "drive"
			if mode == "charge_event" {
				kind = "charge"
			}
			start := time.Now().UTC().Truncate(time.Microsecond)
			legacyID := sessionID(kind, start)
			var publicID int
			if err := db.pool.QueryRow(ctx, `INSERT INTO jourvolt_telemetry_sessions(id,user_id,vehicle_id,kind,started_at,odometer_start,source) VALUES ($1,$2,$3,$4,$5,123,'telemetry_mqtt') RETURNING public_id`, legacyID, user, car, kind, start).Scan(&publicID); err != nil {
				t.Fatal(err)
			}
			loaded, ok, err := service.openSession(ctx, user, car, kind)
			if err != nil || !ok || loaded.ID != legacyID || loaded.PublicID != publicID {
				t.Fatalf("legacy load: %+v ok=%t err=%v", loaded, ok, err)
			}
			field, value := "Gear", "P"
			if kind == "charge" {
				field, value = "DetailedChargeState", "Complete"
			}
			park := start.Add(time.Minute)
			record := telemetryRecord{VINHash: ref.VINHash, FieldName: field, Value: value, ObservedAt: park, EventID: mustRandomToken(t)}
			if n, err := service.ingest(ctx, record); err != nil || n != 1 {
				t.Fatalf("legacy completion: accepted=%d err=%v", n, err)
			}
			end := park
			if kind == "drive" {
				end = park.Add(defaultDriveStopDebounce)
				if mode == "drive_finalizer" {
					if n, err := service.finalizeDuePostgres(ctx, end); err != nil || n != 1 {
						t.Fatalf("legacy finalizer: n=%d err=%v", n, err)
					}
				} else {
					if _, err := service.ingest(ctx, telemetryRecord{VINHash: ref.VINHash, FieldName: "VehicleSpeed", Value: float64(0), ObservedAt: end, EventID: mustRandomToken(t)}); err != nil {
						t.Fatal(err)
					}
				}
			}
			var id, key string
			var pid int
			if err := db.pool.QueryRow(ctx, `SELECT id,public_id,completion_key FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2 AND ended_at=$3`, user, car, end).Scan(&id, &pid, &key); err != nil {
				t.Fatal(err)
			}
			wantKey := sessionCompletionKey(telemetrySession{ID: legacyID, Kind: kind, EndAt: &end})
			if id != legacyID || pid != publicID || key != wantKey {
				t.Fatalf("legacy identity changed: %s/%d/%s", id, pid, key)
			}
			before := fleetScopeSessionRows(t, db, user, car)
			service = &telemetryService{store: db, config: service.config}
			if n, err := service.ingest(ctx, record); err != nil || n != 0 {
				t.Fatalf("legacy completed replay n=%d err=%v", n, err)
			}
			record.EventID = mustRandomToken(t)
			record.ObservedAt = end.Add(time.Minute)
			if _, err := service.ingest(ctx, record); err != nil {
				t.Fatal(err)
			}
			if n, err := service.finalizeDuePostgres(ctx, end.Add(time.Hour)); err != nil || n != 0 {
				t.Fatalf("legacy re-finalization n=%d err=%v", n, err)
			}
			if after := fleetScopeSessionRows(t, db, user, car); after != before {
				t.Fatal("completed legacy row changed on restart/replay")
			}
		})
	}
}

func TestFleetSessionPostgresForeignIDConflictRollsBackIngress(t *testing.T) {
	for _, kind := range []string{"drive", "charge"} {
		t.Run(kind, func(t *testing.T) {
			db := boundedHistoryTestStore(t)
			ctx := context.Background()
			a, b := fleetScopeTestRefs(t, db, "different_owners_and_cars")
			_, c := fleetScopeTestRefs(t, db, "different_owners_and_cars")
			service := &telemetryService{store: db, config: &telemetryConfig{StopDebounce: defaultDriveStopDebounce}}
			if err := service.registerVehicle(ctx, a); err != nil {
				t.Fatal(err)
			}
			start := time.Now().UTC().Truncate(time.Microsecond)
			end := start.Add(time.Minute)
			// Cover the old and new algorithm without depending on the implementation.
			ids := []string{sessionID(kind, start), scopedSessionID("fleet_telemetry", a.UserID, a.VehicleID, kind, start.Format(time.RFC3339Nano))}
			for i, ref := range []telemetryVehicleRef{b, c} {
				if _, err := db.pool.Exec(ctx, `INSERT INTO jourvolt_telemetry_sessions(id,user_id,vehicle_id,kind,started_at,ended_at,source) VALUES ($1,$2,$3,$4,$5,$6,'telemetry_mqtt')`, ids[i], ref.UserID, ref.VehicleID, kind, start, end); err != nil {
					t.Fatal(err)
				}
			}
			beforeB, beforeC := fleetScopeSessionRows(t, db, b.UserID, b.VehicleID), fleetScopeSessionRows(t, db, c.UserID, c.VehicleID)
			field, value := "Gear", "D"
			if kind == "charge" {
				field, value = "DetailedChargeState", "Charging"
			}
			record := telemetryRecord{VINHash: a.VINHash, FieldName: field, Value: value, ObservedAt: start, EventID: mustRandomToken(t)}
			if n, err := service.ingest(ctx, record); err == nil || n != 0 {
				t.Errorf("foreign identity conflict must fail atomically: accepted=%d err=%v", n, err)
			}
			for _, table := range []string{"jourvolt_telemetry_sessions", "jourvolt_telemetry_latest", "jourvolt_telemetry_event_buffer"} {
				var n int
				if err := db.pool.QueryRow(ctx, fmt.Sprintf("SELECT count(*) FROM %s WHERE user_id=$1 AND vehicle_id=$2", table), a.UserID, a.VehicleID).Scan(&n); err != nil || n != 0 {
					t.Errorf("conflict retained %d rows in %s err=%v", n, table, err)
				}
			}
			if fleetScopeSessionRows(t, db, b.UserID, b.VehicleID) != beforeB || fleetScopeSessionRows(t, db, c.UserID, c.VehicleID) != beforeC {
				t.Fatal("foreign rows changed")
			}
		})
	}
}

func TestFleetSessionPostgresCompletedIdentityReplay(t *testing.T) {
	for _, kind := range []string{"drive", "charge"} {
		for _, identity := range []string{"legacy", "scoped", "local_import"} {
			t.Run(kind+"/"+identity, func(t *testing.T) {
				db := boundedHistoryTestStore(t)
				user, car := boundedHistoryTestTenant(t, db, "completed-scope")
				ctx := context.Background()
				ref := telemetryVehicleRef{UserID: user, VehicleID: car, VINHash: "completed-vin-" + mustRandomToken(t)}
				service := &telemetryService{store: db, config: &telemetryConfig{StopDebounce: defaultDriveStopDebounce}}
				if err := service.registerVehicle(ctx, ref); err != nil {
					t.Fatal(err)
				}
				start := time.Now().UTC().Truncate(time.Microsecond)
				end := start.Add(time.Minute)
				id, source := sessionID(kind, start), "telemetry_mqtt"
				if identity == "scoped" {
					id = scopedSessionID("fleet_telemetry", user, car, kind, start.Format(time.RFC3339Nano))
				}
				if identity == "local_import" {
					id, source = "local-"+mustRandomToken(t), "local_import"
				}
				key := sessionCompletionKey(telemetrySession{ID: id, Kind: kind, EndAt: &end})
				if _, err := db.pool.Exec(ctx, `INSERT INTO jourvolt_telemetry_sessions(id,user_id,vehicle_id,kind,started_at,ended_at,completion_key,source) VALUES ($1,$2,$3,$4,$5,$6,$7,$8)`, id, user, car, kind, start, end, key, source); err != nil {
					t.Fatal(err)
				}
				before := fleetScopeSessionRows(t, db, user, car)
				field, value := "Gear", "D"
				if kind == "charge" {
					field, value = "DetailedChargeState", "Charging"
				}
				record := telemetryRecord{VINHash: ref.VINHash, FieldName: field, Value: value, ObservedAt: start, EventID: mustRandomToken(t)}
				n, err := service.ingest(ctx, record)
				if identity == "local_import" {
					if err == nil || n != 0 {
						t.Fatalf("native collision must preserve import: accepted=%d err=%v", n, err)
					}
				} else {
					if err != nil || n != 1 {
						t.Fatalf("completed identity replay: accepted=%d err=%v", n, err)
					}
					if n, err := service.ingest(ctx, record); err != nil || n != 0 {
						t.Fatalf("completed exact replay: accepted=%d err=%v", n, err)
					}
				}
				if after := fleetScopeSessionRows(t, db, user, car); after != before {
					t.Fatal("completed/native collision changed existing history")
				}
			})
		}
	}
}

func fleetScopeSessionRows(t *testing.T, db *store, user string, car int) string {
	t.Helper()
	var rows string
	if err := db.pool.QueryRow(context.Background(), `SELECT COALESCE(jsonb_agg(to_jsonb(s) ORDER BY id),'[]'::jsonb)::text FROM jourvolt_telemetry_sessions s WHERE user_id=$1 AND vehicle_id=$2`, user, car).Scan(&rows); err != nil {
		t.Fatal(err)
	}
	return rows
}
