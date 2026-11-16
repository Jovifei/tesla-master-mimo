package main

import (
	"encoding/json"
	"math"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func batteryHealthTestCapacities() []batteryCapacitySample {
	values := []float64{56.32, 56.56, 56.12, 59.03, 58.50, 60.00, 58.24, 58.59, 60.11, 62.14}
	base := time.Date(2026, 11, 14, 12, 0, 0, 0, time.UTC)
	samples := make([]batteryCapacitySample, 0, len(values))
	for index, value := range values {
		samples = append(samples, batteryCapacitySample{
			At:          base.Add(-time.Duration(index) * 24 * time.Hour),
			CapacityKwh: value,
		})
	}
	return samples
}

func TestDeriveBatteryHealthReportUsesRecentCapacityAndFreshestTelemetry(t *testing.T) {
	ranges := []batteryRangeSample{
		{At: time.Date(2026, 11, 2, 10, 0, 0, 0, time.UTC), FullRangeKm: 398.5},
		{At: time.Date(2026, 11, 1, 10, 0, 0, 0, time.UTC), FullRangeKm: 416.57},
		{At: time.Date(2026, 10, 31, 10, 0, 0, 0, time.UTC), FullRangeKm: 410},
		{At: time.Date(2026, 10, 30, 10, 0, 0, 0, time.UTC), FullRangeKm: 405},
		{At: time.Date(2026, 10, 29, 10, 0, 0, 0, time.UTC), FullRangeKm: 423.86},
	}
	telemetryAt := time.Date(2026, 11, 15, 16, 14, 0, 0, time.UTC)
	telemetry := &batteryRangeSample{At: telemetryAt, FullRangeKm: 317.07949785801617 * 100 / 79.21209989447766}

	report := deriveBatteryHealthReport(ranges, batteryHealthTestCapacities(), telemetry)
	if report == nil {
		t.Fatal("expected derived report")
	}
	if math.Abs(report.CurrentCapacityKwh-58.545) > 0.001 {
		t.Fatalf("current capacity = %v, want 58.545", report.CurrentCapacityKwh)
	}
	if report.MaxCapacityKwh < report.CurrentCapacityKwh {
		t.Fatalf("max capacity %v below current %v", report.MaxCapacityKwh, report.CurrentCapacityKwh)
	}
	if math.Abs(report.CurrentRangeKm-telemetry.FullRangeKm) > 0.001 {
		t.Fatalf("current range = %v, want telemetry %v", report.CurrentRangeKm, telemetry.FullRangeKm)
	}
	if report.MaxRangeKm < 420 || report.MaxRangeKm > 424 {
		t.Fatalf("max range = %v, want 95th percentile near 422", report.MaxRangeKm)
	}
	if math.Abs(report.RatedEfficiencyWhKm-(report.CurrentCapacityKwh*1000/report.CurrentRangeKm)) > 0.001 {
		t.Fatalf("rated efficiency = %v", report.RatedEfficiencyWhKm)
	}
	if report.HealthPercent > 100 || report.HealthPercent < 90 {
		t.Fatalf("health = %v, want 90..100", report.HealthPercent)
	}
	if report.CapacitySamples != 10 || report.RangeSamples != 5 {
		t.Fatalf("sample counts = %d/%d", report.CapacitySamples, report.RangeSamples)
	}
	if !report.ObservedAt.Equal(telemetryAt) {
		t.Fatalf("observed at = %v, want %v", report.ObservedAt, telemetryAt)
	}
}

func TestDeriveBatteryHealthReportRequiresCapacity(t *testing.T) {
	ranges := []batteryRangeSample{{At: time.Now(), FullRangeKm: 400}}
	if report := deriveBatteryHealthReport(ranges, nil, nil); report != nil {
		t.Fatalf("expected nil report, got %+v", report)
	}
}

func TestDeriveBatteryHealthReportNeverExceedsOneHundredPercent(t *testing.T) {
	capacities := []batteryCapacitySample{{At: time.Now(), CapacityKwh: 60}, {At: time.Now().Add(-time.Hour), CapacityKwh: 61}}
	report := deriveBatteryHealthReport(nil, capacities, nil)
	if report == nil {
		t.Fatal("expected report")
	}
	if report.HealthPercent > 100 || report.MaxCapacityKwh < report.CurrentCapacityKwh {
		t.Fatalf("report = %+v, want max >= current and health <= 100", report)
	}
	single := deriveBatteryHealthReport(nil, []batteryCapacitySample{{At: time.Now(), CapacityKwh: 60}}, nil)
	if single == nil || single.HealthPercent != 100 {
		t.Fatalf("single-sample report = %+v, want 100%%", single)
	}
}

func TestBatteryJSONNumberAcceptsNumberAndString(t *testing.T) {
	for _, raw := range []string{"79.21", `"79.21"`} {
		value, ok := batteryJSONNumber(raw)
		if !ok || math.Abs(value-79.21) > 1e-9 {
			t.Fatalf("%s decoded as %v ok=%v", raw, value, ok)
		}
	}
	if _, ok := batteryJSONNumber(`"not-a-number"`); ok {
		t.Fatal("non-numeric text should not decode")
	}
}

func TestBatteryHealthHandlerReturnsUnsupportedWithoutTelemetry(t *testing.T) {
	a := &app{}
	request := httptest.NewRequest(http.MethodGet, "/v1/cars/1/battery-health", nil)
	recorder := httptest.NewRecorder()
	a.batteryHealth(recorder, request, "user", 1)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d", recorder.Code)
	}
	var body struct {
		Data struct {
			BatteryHealth any            `json:"battery_health"`
			Meta          map[string]any `json:"meta"`
		} `json:"data"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body.Data.BatteryHealth != nil {
		t.Fatalf("battery_health = %v", body.Data.BatteryHealth)
	}
	if body.Data.Meta["availability"] != "unsupported" {
		t.Fatalf("meta = %v", body.Data.Meta)
	}
}
