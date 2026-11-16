package main

import (
	"testing"
	"time"
)

func powerSamples(start time.Time, seconds []float64, powerKW float64) []drivePowerSample {
	samples := make([]drivePowerSample, 0, len(seconds))
	for _, offset := range seconds {
		value := powerKW
		samples = append(samples, drivePowerSample{At: start.Add(time.Duration(offset) * time.Second), PowerKW: &value})
	}
	return samples
}

func TestIntegrateDrivePowerCompleteWindow(t *testing.T) {
	start := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	end := start.Add(10 * time.Minute)
	offsets := make([]float64, 0, 61)
	for second := 0; second <= 600; second += 10 {
		offsets = append(offsets, float64(second))
	}
	integral, ok := integrateDrivePower(start, end, powerSamples(start, offsets, 20))
	if !ok {
		t.Fatal("complete window rejected")
	}
	if !integral.Complete || integral.CoverageSeconds != 600 {
		t.Fatalf("integral = %#v", integral)
	}
	if want := 20.0 * 600 / 3600; integral.EnergyKwh < want-1e-9 || integral.EnergyKwh > want+1e-9 {
		t.Fatalf("energy = %v, want %v", integral.EnergyKwh, want)
	}
}

func TestIntegrateDrivePowerRejectsSparseCoverage(t *testing.T) {
	start := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	end := start.Add(100 * time.Second)
	integral, ok := integrateDrivePower(start, end, powerSamples(start, []float64{0, 10, 20, 51, 60}, 20))
	if ok {
		t.Fatalf("sparse coverage accepted: %#v", integral)
	}
}

func TestIntegrateDrivePowerAcceptsEightyFivePercentCoverage(t *testing.T) {
	start := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	end := start.Add(100 * time.Second)
	integral, ok := integrateDrivePower(start, end, powerSamples(start, []float64{0, 10, 20, 30, 40, 50, 60, 70, 80, 90}, 20))
	if !ok {
		t.Fatal("90% coverage rejected")
	}
	if integral.Complete {
		t.Fatalf("incomplete window marked complete: %#v", integral)
	}
	if ratio := integral.CoverageSeconds / integral.WindowSeconds; ratio < 0.899 || ratio > 0.901 {
		t.Fatalf("ratio = %v", ratio)
	}
}

func TestIntegrateDrivePowerConflictingDuplicateBreaksCoverage(t *testing.T) {
	start := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	end := start.Add(600 * time.Second)
	first, second := 20.0, 30.0
	samples := []drivePowerSample{
		{At: start, PowerKW: &first},
		{At: start, PowerKW: &second},
		{At: start.Add(10 * time.Second), PowerKW: &first},
	}
	if integral, ok := integrateDrivePower(start, end, samples); ok {
		t.Fatalf("conflicting duplicate accepted: %#v", integral)
	}
}

func TestIntegrateDrivePowerClampsToDriveWindow(t *testing.T) {
	start := time.Date(2026, 1, 1, 0, 0, 5, 0, time.UTC)
	end := start.Add(50 * time.Second)
	// Samples extend beyond both ends; only the overlap counts.
	samples := powerSamples(start.Add(-10*time.Second), []float64{0, 10, 20, 30, 40, 50, 60, 70}, 10)
	integral, ok := integrateDrivePower(start, end, samples)
	if !ok {
		t.Fatal("clamped window rejected")
	}
	if integral.CoverageSeconds != 50 || !integral.Complete {
		t.Fatalf("integral = %#v", integral)
	}
}

func TestDrivePowerEnergyContractMatchesClientContract(t *testing.T) {
	start := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	end := start.Add(10 * time.Minute)
	session := telemetrySession{Kind: "drive", StartAt: start, EndAt: &end, Source: "telemetry_mqtt"}
	item := map[string]any{
		"start_date": start.Format(time.RFC3339Nano),
		"end_date":   end.Format(time.RFC3339Nano),
	}
	offsets := make([]float64, 0, 61)
	for second := 0; second <= 600; second += 10 {
		offsets = append(offsets, float64(second))
	}
	integral, ok := integrateDrivePower(start, end, powerSamples(start, offsets, 20))
	if !ok {
		t.Fatal("integral rejected")
	}
	contract := drivePowerEnergyContract(item, session, integral)
	if contract == nil {
		t.Fatal("contract nil")
	}
	metric, _ := contract["net_energy"].(map[string]any)
	if metric["method"] != "drive_power_integral" || metric["quality"] != "estimated" ||
		metric["measurement_point"] != "drive_power" || metric["coverage_kind"] != "time" ||
		metric["source"] != "telemetry_mqtt" || metric["source_field"] != "drive_details.power" {
		t.Fatalf("metric = %#v", metric)
	}
	for _, key := range []string{"start_date", "end_date"} {
		if metric[key] != item[key] {
			t.Fatalf("%s = %#v, want %#v", key, metric[key], item[key])
		}
	}
	ratio, _ := metric["coverage_ratio"].(float64)
	if ratio < 0.85 || ratio > 1 {
		t.Fatalf("coverage_ratio = %v", ratio)
	}
	if metric["value_kwh"] == nil || metric["reason"] != "complete_power_window" {
		t.Fatalf("metric = %#v", metric)
	}
}

func TestAttachDrivePowerEnergyContractFromSession(t *testing.T) {
	start := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	end := start.Add(120 * time.Second)
	points := make([]telemetryRoutePoint, 0, 13)
	for second := 0; second <= 120; second += 10 {
		power := 15.0
		points = append(points, telemetryRoutePoint{ObservedAt: start.Add(time.Duration(second) * time.Second), Power: &power})
	}
	session := telemetrySession{Kind: "drive", StartAt: start, EndAt: &end, Source: "telemetry_mqtt", Route: points}
	item := map[string]any{"start_date": start.Format(time.RFC3339Nano), "end_date": end.Format(time.RFC3339Nano)}
	attachDrivePowerEnergyContractFromSession(item, session)
	if _, ok := item["energy_contract"]; !ok {
		t.Fatal("contract not attached")
	}
	// A numeric contract is never replaced.
	item["energy_contract"] = map[string]any{"version": 1, "net_energy": map[string]any{"value_kwh": 1.0}}
	attachDrivePowerEnergyContractFromSession(item, session)
	if metric := item["energy_contract"].(map[string]any)["net_energy"].(map[string]any); metric["value_kwh"] != 1.0 {
		t.Fatalf("numeric contract replaced: %#v", metric)
	}
}
