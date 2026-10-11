package main

import (
	"encoding/json"
	"net/http/httptest"
	"os"
	"reflect"
	"testing"
	"time"
)

func TestTPMSWarningRecoveryHTTPMatchesAndroidMoshiFixture(t *testing.T) {
	now := time.Date(2026, 10, 11, 4, 0, 0, 0, time.UTC)
	fields := map[string]any{"TpmsSoftWarnings": map[string]any{"front_left": true, "front_right": false, "rear_left": false, "rear_right": false}}
	times := map[string]time.Time{"TpmsSoftWarnings": now.Add(-55 * time.Second)}
	sources := map[string]string{"TpmsSoftWarnings": "telemetry_mqtt"}
	for i, field := range []string{"TpmsPressureFl", "TpmsPressureFr", "TpmsPressureRl", "TpmsPressureRr"} {
		fields[field] = 2.5
		if i == 0 {
			fields[field] = 0.0
		}
		times[field] = now.Add(time.Duration(-60+i) * time.Second)
		sources[field] = "telemetry_mqtt"
	}
	status := mergeFreshTelemetryStatus(vehicleStatus{}, telemetrySnapshot{Fields: fields, FieldObservedAt: times, FieldSources: sources}, now)
	api := &app{provider: testProvider{statuses: map[string]vehicleStatus{"fake-warning-user": status}}}
	response := httptest.NewRecorder()
	api.status(response, httptest.NewRequest("GET", "/fake-status", nil), "fake-warning-user", 7, false)
	if response.Code != 200 {
		t.Fatalf("actual status handler: %d", response.Code)
	}
	var envelope map[string]any
	if err := json.Unmarshal(response.Body.Bytes(), &envelope); err != nil {
		t.Fatal(err)
	}
	actual := envelope["data"].(map[string]any)["status"].(map[string]any)["tpms_details"]
	raw, err := os.ReadFile("../../android/app/src/test/resources/tpms_warning_collector_contract.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture map[string]any
	if err = json.Unmarshal(raw, &fixture); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(actual, fixture) {
		t.Fatalf("Go HTTP output no longer matches actual Moshi fixture: actual=%v expected=%v", actual, fixture)
	}
}

func TestTPMSWarningRecoveryRejectsConflictingAliases(t *testing.T) {
	for _, raw := range []string{
		`{"front_left":true,"frontLeft":false}`,
		`{"front_right":false,"frontRight":true}`,
		`{"rear_left":true,"rearLeft":false}`,
		`{"rear_right":false,"rearRight":true}`,
	} {
		if _, err := normalizeTelemetryValue("TpmsSoftWarnings", json.RawMessage(raw)); err == nil {
			t.Fatalf("contradictory aliases acquired verified warning provenance: %s", raw)
		}
	}
	if _, err := normalizeTelemetryValue("TpmsSoftWarnings", json.RawMessage(`{"front_left":false,"frontLeft":false}`)); err != nil {
		t.Fatalf("consistent aliases should remain compatible: %v", err)
	}
}

func TestTPMSWarningRecoveryWarningOnlyActualHTTPProvenance(t *testing.T) {
	now := time.Date(2026, 10, 11, 4, 0, 0, 0, time.UTC)
	at := now.Add(-time.Second)
	status := mergeFreshTelemetryStatus(vehicleStatus{}, telemetrySnapshot{
		Fields:          map[string]any{"TpmsSoftWarnings": map[string]any{"front_left": true, "front_right": false}},
		FieldObservedAt: map[string]time.Time{"TpmsSoftWarnings": at},
		FieldSources:    map[string]string{"TpmsSoftWarnings": "telemetry_mqtt"},
	}, now)
	api := &app{provider: testProvider{statuses: map[string]vehicleStatus{"fake-warning-user": status}}}
	response := httptest.NewRecorder()
	api.status(response, httptest.NewRequest("GET", "/fake-status", nil), "fake-warning-user", 7, false)
	if response.Code != 200 {
		t.Fatalf("actual status handler: %d", response.Code)
	}
	var envelope map[string]any
	if err := json.Unmarshal(response.Body.Bytes(), &envelope); err != nil {
		t.Fatal(err)
	}
	details := envelope["data"].(map[string]any)["status"].(map[string]any)["tpms_details"].(map[string]any)
	if details["tpms_soft_warning_fl"] != true || details["tpms_soft_warning_fr"] != false ||
		details["warning_source"] != "telemetry_mqtt" || details["warning_time_basis"] != "collector_received_at" ||
		details["tpms_soft_warning_fl_observed_at"] != at.Format(time.RFC3339Nano) ||
		details["tpms_soft_warning_fr_observed_at"] != at.Format(time.RFC3339Nano) {
		t.Fatalf("warning-only HTTP lost independent true/false provenance: %v", details)
	}
	if details["source"] != nil || details["time_basis"] != nil || details["tpms_pressure_fl"] != nil ||
		details["tpms_pressure_fl_observed_at"] != nil || details["tpms_soft_warning_rl_observed_at"] != nil {
		t.Fatalf("warning-only HTTP fabricated pressure or missing-wheel evidence: %v", details)
	}
}

func TestTPMSWarningRecoveryActualParserStoreAndOutOfOrder(t *testing.T) {
	now := time.Date(2026, 10, 11, 4, 0, 0, 0, time.UTC)
	store := newTelemetryMemoryStore()
	ref := telemetryVehicleRef{UserID: "fake-warning-user", VehicleID: 7, VINHash: keyedVINHash([]byte("test-key"), "synthetic-vin")}
	store.registerVehicle(ref)
	for _, tc := range []struct {
		payload string
		at      time.Time
	}{
		{`{"front_left":true,"front_right":false,"rear_left":false,"rear_right":false}`, now.Add(-2 * time.Second)},
		{`{"front_left":false}`, now.Add(-time.Second)},
		{`{"front_left":true}`, now.Add(-3 * time.Second)},
	} {
		record, err := parseTelemetryPayloadWithKey([]byte("test-key"), "test/telemetry", "test/telemetry/synthetic-vin/v/TpmsSoftWarnings", []byte(tc.payload), tc.at)
		if err != nil {
			t.Fatal(err)
		}
		if _, err = store.ingest(record, defaultDriveStopDebounce); err != nil {
			t.Fatal(err)
		}
	}
	snapshot, ok := store.latestSnapshot(ref.UserID, ref.VehicleID)
	if !ok {
		t.Fatal("actual store has no warning receipt")
	}
	got := mergeFreshTelemetryStatus(vehicleStatus{}, snapshot, now)
	if got.TPMSSoftWarningFL == nil || *got.TPMSSoftWarningFL {
		t.Fatal("latest explicit false lost or older true overwrote it")
	}
	evidence := tpmsPressureEvidence(got)
	if evidence["tpms_soft_warning_fl_observed_at"] != now.Add(-time.Second).Format(time.RFC3339Nano) {
		t.Fatalf("collector receipt changed: %v", evidence)
	}
	if got.TPMSSoftWarningFR != nil {
		t.Fatal("absent wheel promoted from partial latest aggregate")
	}
	for _, bad := range []string{`0`, `false`, `{"front_left":0}`, `{"front_left":"false"}`} {
		if _, err := normalizeTelemetryValue("TpmsSoftWarnings", json.RawMessage(bad)); err == nil {
			t.Fatalf("invalid warning payload admitted: %s", bad)
		}
	}
}

func TestTPMSWarningRecoveryPartialRefreshPreservesKnownWheelReceipt(t *testing.T) {
	now := time.Date(2026, 10, 11, 4, 0, 0, 0, time.UTC)
	apply := func(base vehicleStatus, value map[string]any, at time.Time) vehicleStatus {
		return mergeFreshTelemetryStatus(base, telemetrySnapshot{Fields: map[string]any{"TpmsSoftWarnings": value},
			FieldObservedAt: map[string]time.Time{"TpmsSoftWarnings": at}, FieldSources: map[string]string{"TpmsSoftWarnings": "telemetry_mqtt"}}, now)
	}
	first := apply(vehicleStatus{}, map[string]any{"front_left": true, "front_right": true}, now.Add(-3*time.Second))
	second := apply(first, map[string]any{"front_left": false}, now.Add(-time.Second))
	if second.TPMSSoftWarningFR == nil || !*second.TPMSSoftWarningFR {
		t.Fatal("partial warning refresh cleared another wheel")
	}
	if second.TPMSFieldObservedAt["TpmsSoftWarningFr"] != now.Add(-3*time.Second) || first.TPMSFieldObservedAt["TpmsSoftWarningFl"] != now.Add(-3*time.Second) {
		t.Fatal("partial refresh borrowed clock or mutated previous projection")
	}
	older := apply(second, map[string]any{"front_left": true}, now.Add(-2*time.Second))
	if *older.TPMSSoftWarningFL {
		t.Fatal("older per-wheel warning overwrote fresh false")
	}
}

// Tesla's official TireLocation booleans must retain their own collector receipt,
// independent of the four asynchronously received pressure fields.
func TestTPMSWarningRecoveryIndependentReceipts(t *testing.T) {
	now := time.Date(2026, 10, 11, 4, 0, 0, 0, time.UTC)
	warningAt := now.Add(-55 * time.Second)
	fields := map[string]any{"TpmsSoftWarnings": map[string]any{"front_left": true, "front_right": false, "rear_left": false, "rear_right": false}}
	times := map[string]time.Time{"TpmsSoftWarnings": warningAt}
	sources := map[string]string{"TpmsSoftWarnings": "telemetry_mqtt"}
	for i, field := range []string{"TpmsPressureFl", "TpmsPressureFr", "TpmsPressureRl", "TpmsPressureRr"} {
		fields[field] = 2.5
		times[field] = now.Add(time.Duration(-60+i) * time.Second)
		sources[field] = "telemetry_mqtt"
	}
	got := mergeFreshTelemetryStatus(vehicleStatus{}, telemetrySnapshot{Fields: fields, FieldObservedAt: times, FieldSources: sources, ObservedAt: now, Source: "telemetry_mqtt"}, now)
	if got.TPMSSoftWarningFL == nil || !*got.TPMSSoftWarningFL || got.TPMSSoftWarningFR == nil || *got.TPMSSoftWarningFR {
		t.Fatal("real TireLocation true/false warning booleans lost")
	}
	evidence := tpmsPressureEvidence(got)
	if evidence["warning_source"] != "telemetry_mqtt" || evidence["warning_time_basis"] != "collector_received_at" {
		t.Fatalf("warning provenance missing: %v", evidence)
	}
	for _, wheel := range []string{"fl", "fr", "rl", "rr"} {
		if evidence["tpms_soft_warning_"+wheel+"_observed_at"] != warningAt.Format(time.RFC3339Nano) {
			t.Fatalf("warning clock borrowed pressure/global time: %v", evidence)
		}
	}
}

func TestTPMSWarningRecoveryPartialDoesNotInventOtherWheels(t *testing.T) {
	now := time.Date(2026, 10, 11, 4, 0, 0, 0, time.UTC)
	got := mergeFreshTelemetryStatus(vehicleStatus{}, telemetrySnapshot{
		Fields:          map[string]any{"TpmsSoftWarnings": map[string]any{"front_left": false}},
		FieldObservedAt: map[string]time.Time{"TpmsSoftWarnings": now}, FieldSources: map[string]string{"TpmsSoftWarnings": "telemetry_mqtt"},
		ObservedAt: now, Source: "telemetry_mqtt",
	}, now)
	if got.TPMSSoftWarningFL == nil || *got.TPMSSoftWarningFL {
		t.Fatal("explicit false was lost")
	}
	if got.TPMSSoftWarningFR != nil || got.TPMSSoftWarningRL != nil || got.TPMSSoftWarningRR != nil {
		t.Fatal("missing wheels became false")
	}
	if tpmsPressureEvidence(got)["tpms_soft_warning_fr_observed_at"] != nil {
		t.Fatal("missing wheel acquired a receipt")
	}
}

func TestTPMSWarningRecoveryRejectsUnqualifiedTimesSourcesAndTypes(t *testing.T) {
	now := time.Date(2026, 10, 11, 4, 0, 0, 0, time.UTC)
	for _, tc := range []struct {
		name, source string
		at           time.Time
		value        any
	}{
		{"missing_time", "telemetry_mqtt", time.Time{}, true},
		{"missing_source", "", now, true}, {"import", "local_import", now, true},
		{"future", "telemetry_mqtt", now.Add(time.Second), true},
		{"stale", "telemetry_mqtt", now.Add(-3 * time.Hour), true},
		{"numeric_zero", "telemetry_mqtt", now, 0.0}, {"text_false", "telemetry_mqtt", now, "false"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got := mergeFreshTelemetryStatus(vehicleStatus{}, telemetrySnapshot{
				Fields:          map[string]any{"TpmsSoftWarnings": map[string]any{"front_left": tc.value}, "Soc": 80.0},
				FieldObservedAt: map[string]time.Time{"TpmsSoftWarnings": tc.at, "Soc": now},
				FieldSources:    map[string]string{"TpmsSoftWarnings": tc.source, "Soc": "telemetry_mqtt"}, ObservedAt: now, Source: "telemetry_mqtt",
			}, now)
			if got.TPMSSoftWarningFL != nil || tpmsPressureEvidence(got)["tpms_soft_warning_fl_observed_at"] != nil {
				t.Fatal("unqualified warning promoted")
			}
		})
	}
}
