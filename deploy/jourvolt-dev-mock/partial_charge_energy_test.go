package main

import (
	"encoding/json"
	"math"
	"testing"
	"time"
)

func partialChargeFixture() telemetrySession {
	start := time.Date(2026, 2, 3, 4, 5, 6, 123456789, time.UTC)
	end := start.Add(10 * time.Minute)
	first, last := 2.0, 12.0
	return telemetrySession{
		Kind: "charge", PublicID: 101, Source: "telemetry_mqtt",
		StartAt: start, EndAt: &end, QualityState: "incomplete",
		QualityReason: "partial_or_missing_charge_counter",
		ChargePoints: []telemetryChargePoint{
			{ObservedAt: start.Add(30 * time.Second), BatteryCounter: &first},
			{ObservedAt: end.Add(-45 * time.Second), BatteryCounter: &last},
		},
	}
}

func batteryMetricForTest(t *testing.T, item map[string]any) map[string]any {
	t.Helper()
	contract, ok := item["energy_contract"].(map[string]any)
	if !ok {
		t.Fatal("missing energy contract")
	}
	metric, ok := contract["battery_input"].(map[string]any)
	if !ok {
		t.Fatal("missing battery metric")
	}
	return metric
}

func assertPartialMetricForTest(t *testing.T, item map[string]any) {
	t.Helper()
	metric := batteryMetricForTest(t, item)
	if metric["quality"] != "unknown" || metric["value_kwh"] != nil ||
		item["charge_energy_added"] != nil || metric["reason"] != "partial_counter_window" {
		t.Fatalf("subset falsely claimed as complete: %#v", metric)
	}
	if metric["covered_energy_kwh"] != 10.0 {
		t.Fatalf("covered energy = %#v", metric["covered_energy_kwh"])
	}
	ratio, ok := metric["coverage_ratio"].(float64)
	if !ok || math.Abs(ratio-0.875) > 1e-9 {
		t.Fatalf("coverage ratio = %#v", metric["coverage_ratio"])
	}
	if metric["start_date"] != item["start_date"] || metric["end_date"] != item["end_date"] {
		t.Fatal("metric is not bound to the response session window")
	}
	if metric["time_basis"] != "collector_received_at" {
		t.Fatal("collector receipt time must not be attributed to the vehicle clock")
	}
	if _, err := json.Marshal(item); err != nil {
		t.Fatal(err)
	}
}

func TestPartialChargeEnergyDetailPreservesSubsetEvidence(t *testing.T) {
	item := historySessionMap(partialChargeFixture(), "charge", 0)
	assertPartialMetricForTest(t, item)
}

func TestPartialChargeEnergyStoredContractUsesDatabaseWindow(t *testing.T) {
	session := partialChargeFixture()
	stored := completedSessionEnergyContract(session)
	start := session.StartAt.Truncate(time.Microsecond)
	end := session.EndAt.Truncate(time.Microsecond)
	session.StartAt, session.EndAt, session.ChargePoints = start, &end, nil
	item := historySessionMap(session, "charge", 0)
	publishChargeEnergyContract(item, stored)
	assertPartialMetricForTest(t, item)
}

func TestPartialChargeEnergyDatabasePrecisionDoesNotInventFullCoverage(t *testing.T) {
	start := time.Date(2026, 2, 3, 4, 5, 6, 123456000, time.UTC)
	end := start.Add(time.Minute + 789*time.Nanosecond)
	first, last := 1.0, 2.0
	session := telemetrySession{Kind: "charge", Source: "telemetry_mqtt", StartAt: start, EndAt: &end,
		ChargePoints: []telemetryChargePoint{
			{ObservedAt: start, BatteryCounter: &first},
			{ObservedAt: end.Truncate(time.Microsecond), BatteryCounter: &last},
		}}
	stored := completedSessionEnergyContract(session)
	dbEnd := end.Truncate(time.Microsecond)
	session.EndAt, session.ChargePoints = &dbEnd, nil
	item := historySessionMap(session, "charge", 0)
	publishChargeEnergyContract(item, stored)
	metric := batteryMetricForTest(t, item)
	ratio, ok := metric["coverage_ratio"].(float64)
	if !ok || ratio >= 1 || ratio <= 0.99 || metric["value_kwh"] != nil || metric["quality"] != "unknown" {
		t.Fatalf("database precision must not invent full coverage: %#v", metric)
	}
}

func TestPartialChargeEnergyRejectsMismatchedOrInvalidEvidence(t *testing.T) {
	tests := []struct {
		name   string
		change func(map[string]any, map[string]any, map[string]any)
	}{
		{"wrong result source", func(item, _, _ map[string]any) { item["source"] = "teslamate_archive" }},
		{"wrong metric source", func(_, _, m map[string]any) { m["source"] = "foreign" }},
		{"wrong source field", func(_, _, m map[string]any) { m["source_field"] = "ACChargingEnergyIn" }},
		{"wrong unit", func(_, _, m map[string]any) { m["unit"] = "Wh" }},
		{"wrong version", func(_, c, _ map[string]any) { c["version"] = 1.5 }},
		{"wrong measurement point", func(_, _, m map[string]any) { m["measurement_point"] = "ac_charger_input" }},
		{"wrong time basis", func(_, _, m map[string]any) { m["time_basis"] = "vehicle_time" }},
		{"unexpected full value", func(_, _, m map[string]any) { m["value_kwh"] = 12.0 }},
		{"wrong session window", func(item, _, _ map[string]any) {
			at, _ := time.Parse(time.RFC3339Nano, item["start_date"].(string))
			item["start_date"] = at.Add(time.Microsecond).Format(time.RFC3339Nano)
		}},
		{"observation outside session", func(_, _, m map[string]any) { m["observed_start_at"] = m["end_date"] }},
		{"nonfinite value", func(_, _, m map[string]any) { m["covered_energy_kwh"] = math.NaN() }},
		{"negative value", func(_, _, m map[string]any) { m["covered_energy_kwh"] = -1.0 }},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			session := partialChargeFixture()
			item := historySessionMap(session, "charge", 0)
			contract := completedSessionEnergyContract(session)
			metric := contract["battery_input"].(map[string]any)
			tc.change(item, contract, metric)
			publishChargeEnergyContract(item, contract)
			if metric["value_kwh"] != nil || metric["quality"] != "unknown" ||
				metric["covered_energy_kwh"] != nil || metric["coverage_ratio"] != nil {
				t.Fatalf("invalid partial evidence was exposed: %#v", metric)
			}
		})
	}
}

func TestPartialChargeEnergyRetainsResetAndSinglePointFailures(t *testing.T) {
	for _, mode := range []string{"single", "reset", "duplicate"} {
		session := partialChargeFixture()
		switch mode {
		case "reset":
			last := 1.0
			session.ChargePoints[1].BatteryCounter = &last
		case "single":
			session.ChargePoints = session.ChargePoints[:1]
		case "duplicate":
			session.ChargePoints = append(session.ChargePoints, session.ChargePoints[1])
		}
		metric := batteryMetricForTest(t, historySessionMap(session, "charge", 0))
		if metric["covered_energy_kwh"] != nil || metric["value_kwh"] != nil ||
			metric["coverage_ratio"] != nil || metric["quality"] != "unknown" {
			t.Fatalf("unqualified counter was published: %#v", metric)
		}
	}
}

func TestPartialChargeEnergyRetainsRealZeroWithoutDeclaringFree(t *testing.T) {
	session := partialChargeFixture()
	zero := *session.ChargePoints[0].BatteryCounter
	session.ChargePoints[1].BatteryCounter = &zero
	item := historySessionMap(session, "charge", 0)
	metric := batteryMetricForTest(t, item)
	if metric["covered_energy_kwh"] != 0.0 || metric["value_kwh"] != nil ||
		metric["quality"] != "unknown" || item["charge_energy_added"] != nil {
		t.Fatalf("partial zero was lost or presented as complete: %#v", metric)
	}
}
