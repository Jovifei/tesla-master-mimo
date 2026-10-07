package main

import (
	"context"
	"fmt"
	"testing"
	"time"
)

func TestTelemetryPostgresNativeIdentitySeparatesTenantsAtSameTimestamp(t *testing.T) {
	t.Run("different_users", func(t *testing.T) { verifyNativeScopeAtSameTimestamp(t, false) })
	t.Run("same_user_two_vehicles", func(t *testing.T) { verifyNativeScopeAtSameTimestamp(t, true) })
}

func verifyNativeScopeAtSameTimestamp(t *testing.T, sameUser bool) {
	sA, userA, carA := openHistoryQueryTestDB(t)
	sB, userB, carB := openHistoryQueryTestDB(t)
	ctx := context.Background()
	if sameUser {
		if _, err := sA.store.pool.Exec(ctx, `UPDATE jourvolt_vehicles SET user_id=$1 WHERE id=$2`, userA, carB); err != nil {
			t.Fatal(err)
		}
		userB = userA
	}
	for _, s := range []*telemetryService{sA, sB} {
		s.config = &telemetryConfig{StopDebounce: defaultDriveStopDebounce}
	}
	vinA, vinB := "native-a-"+mustRandomToken(t), "native-b-"+mustRandomToken(t)
	for _, fixture := range []struct {
		s         *telemetryService
		user, vin string
		car       int
	}{{sA, userA, vinA, carA}, {sB, userB, vinB, carB}} {
		if err := fixture.s.registerVehicle(ctx, telemetryVehicleRef{UserID: fixture.user, VehicleID: fixture.car, VINHash: fixture.vin}); err != nil {
			t.Fatal(err)
		}
	}
	start := time.Date(2026, 9, 2, 0, 0, 0, 0, time.UTC)
	fixtures := []struct {
		s           *telemetryService
		user, vin   string
		car         int
		energy, lat float64
	}{{sA, userA, vinA, carA, 5, 31}, {sB, userB, vinB, carB, 8, 41}}
	for step, field := range []string{"DetailedChargeState", "ACChargingEnergyIn", "ACChargingEnergyIn", "Location", "DetailedChargeState"} {
		for index, f := range fixtures {
			var value any
			switch step {
			case 0:
				value = "Charging"
			case 1:
				value = float64(100)
			case 2:
				value = 100 + f.energy
			case 3:
				value = map[string]any{"latitude": f.lat, "longitude": 121.0}
			case 4:
				value = "Complete"
			}
			record := telemetryRecord{VINHash: f.vin, FieldName: field, Value: value, ObservedAt: start.Add(time.Duration(step) * time.Second), EventID: fmt.Sprintf("tenant-%d-event-%d", index, step)}
			if accepted, err := f.s.ingest(ctx, record); err != nil || accepted != 1 {
				t.Fatalf("fixture%d step%d accepted=%d err=%v", index, step, accepted, err)
			}
		}
	}
	var ids []string
	for index, f := range fixtures {
		var count int
		var energy, latitude *float64
		var id *string
		err := f.s.store.pool.QueryRow(ctx, `SELECT count(*),max(id),max(energy_added),max((charge_points_json->-1->>'Latitude')::double precision) FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2 AND kind='charge' AND ended_at IS NOT NULL`, f.user, f.car).Scan(&count, &id, &energy, &latitude)
		if err != nil {
			t.Fatal(err)
		}
		value := func(p *float64) any {
			if p == nil {
				return nil
			}
			return *p
		}
		t.Logf("NATIVE_SCOPE same_user=%t fixture=%d completed=%d energy=%v latitude=%v", sameUser, index, count, value(energy), value(latitude))
		if count != 1 || energy == nil || *energy != f.energy || latitude == nil || *latitude != f.lat {
			t.Errorf("fixture%d lost or crossed native evidence: completed=%d energy=%v latitude=%v", index, count, energy, latitude)
		}
		if id != nil {
			ids = append(ids, *id)
		}
	}
	if len(ids) == 2 && ids[0] == ids[1] {
		t.Error("two tenants share global session ID")
	}
	if sameUser {
		return
	}
	for _, scope := range []struct {
		user string
		car  int
	}{{userA, carB}, {userB, carA}} {
		if m, err := sA.historyMetadata(ctx, scope.user, scope.car, "charge"); err != nil || m.Total != 0 {
			t.Fatalf("cross-owner read leaked: count=%d err=%v", m.Total, err)
		}
	}
}

func TestTelemetryPostgresNativeIdentityPreservesLegacyOpenSession(t *testing.T) {
	s, user, car := openHistoryQueryTestDB(t)
	ctx := context.Background()
	s.config = &telemetryConfig{StopDebounce: defaultDriveStopDebounce}
	vin := "legacy-native-" + mustRandomToken(t)
	if err := s.registerVehicle(ctx, telemetryVehicleRef{UserID: user, VehicleID: car, VINHash: vin}); err != nil {
		t.Fatal(err)
	}
	start := time.Date(2026, 9, 3, 0, 0, 0, 0, time.UTC)
	legacyID := sessionID("charge", start)
	var originalPublicID int
	if err := s.store.pool.QueryRow(ctx, `INSERT INTO jourvolt_telemetry_sessions(id,user_id,vehicle_id,kind,started_at,charge_energy_start,charge_energy_field,source) VALUES($1,$2,$3,'charge',$4,100,'ACChargingEnergyIn','telemetry_mqtt') RETURNING public_id`, legacyID, user, car, start).Scan(&originalPublicID); err != nil {
		t.Fatal(err)
	}
	for index, record := range []telemetryRecord{
		{VINHash: vin, FieldName: "ACChargingEnergyIn", Value: float64(105), ObservedAt: start.Add(time.Second), EventID: "legacy-energy"},
		{VINHash: vin, FieldName: "DetailedChargeState", Value: "Complete", ObservedAt: start.Add(2 * time.Second), EventID: "legacy-complete"},
	} {
		if n, err := s.ingest(ctx, record); err != nil || n != 1 {
			t.Fatalf("legacy step%d accepted=%d err=%v", index, n, err)
		}
	}
	var id, completionKey string
	var publicID, count int
	var energy float64
	if err := s.store.pool.QueryRow(ctx, `SELECT id,public_id,completion_key,energy_added FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2 AND ended_at IS NOT NULL`, user, car).Scan(&id, &publicID, &completionKey, &energy); err != nil {
		t.Fatal(err)
	}
	if err := s.store.pool.QueryRow(ctx, `SELECT count(*) FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2`, user, car).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if id != legacyID || publicID != originalPublicID || count != 1 || energy != 5 || completionKey == "" {
		t.Fatalf("legacy identity/evidence changed: id=%s public=%d count=%d energy=%v", id, publicID, count, energy)
	}
}
