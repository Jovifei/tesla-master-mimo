package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func batteryTrendTestRequest(t *testing.T) (archiveBinding, batteryTrendImportRequest) {
	t.Helper()
	binding := archiveBinding{UserID: "test-user", VehicleID: 17, SourceType: "teslamate", SourceInstanceID: "source-a", SourceVehicleID: "source-car"}
	start, end := "2026-10-28T01:00:00Z", "2026-10-28T01:30:00Z"
	startSOC, endSOC, startID, endID := 80, 72, int64(101), int64(102)
	startRange, endRange, temp := 320.5, 290.25, 19.5
	startSource, endSource, tempSource := "teslamate_drive_start_summary", "teslamate_drive_end_summary", "teslamate_drive_average"
	request := batteryTrendImportRequest{SchemaVersion: 1, Source: "teslamate", SourceInstanceID: binding.SourceInstanceID, SourceVehicleID: binding.SourceVehicleID,
		BatchID: "test-batch-1", Units: batteryTrendUnits{RatedRange: "km", Temperature: "C", Soc: "percent"},
		Observations: []batteryTrendObservation{{SourceRecordID: "teslamate:drive:42", StartedAt: start, EndedAt: end,
			Start:           &batteryTrendBoundary{SocPercent: &startSOC, SocPositionObservedAt: &start, SocPositionID: &startID, RatedRangeKm: &startRange, RatedRangeSource: &startSource},
			End:             &batteryTrendBoundary{SocPercent: &endSOC, SocPositionObservedAt: &end, SocPositionID: &endID, RatedRangeKm: &endRange, RatedRangeSource: &endSource},
			OutsideTempAvgC: &temp, TemperatureSource: &tempSource}}}
	request.PayloadSHA256, _ = batteryTrendDigest(request.payload())
	return binding, request
}

func TestBatteryTrendValidationAndCanonicalPayload(t *testing.T) {
	binding, request := batteryTrendTestRequest(t)
	if err := validateBatteryTrendImport(request, binding); err != nil {
		t.Fatal(err)
	}
	if request.PayloadSHA256 != "7a5fe2298155881c59cbd5598b6043ad8783551ca89823aa1bf35b820ac310de" {
		t.Fatalf("canonical synthetic payload changed: %s", request.PayloadSHA256)
	}
	encoded, err := json.Marshal(request.payload())
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains(encoded, []byte(`"rated_range_observed_at":null`)) || !bytes.Contains(encoded, []byte(`"rated_range":"km","temperature":"C","soc":"percent"`)) {
		t.Fatal("canonical payload dropped source metadata or units")
	}
	if bytes.Contains(encoded, []byte(`"batch_id"`)) || bytes.Contains(encoded, []byte(`"payload_sha256"`)) {
		t.Fatal("canonical hash input is self-referential")
	}

	cases := []struct {
		name   string
		change func(*batteryTrendImportRequest)
	}{
		{"units", func(r *batteryTrendImportRequest) { r.Units.Temperature = "F" }},
		{"source", func(r *batteryTrendImportRequest) { r.SourceInstanceID = "other-source" }},
		{"record", func(r *batteryTrendImportRequest) { r.Observations[0].SourceRecordID = "teslamate:drive:042" }},
		{"duplicate", func(r *batteryTrendImportRequest) { r.Observations = append(r.Observations, r.Observations[0]) }},
		{"soc-without-position", func(r *batteryTrendImportRequest) { r.Observations[0].Start.SocPositionID = nil }},
		{"wrong-boundary", func(r *batteryTrendImportRequest) {
			value := "2026-10-28T01:00:02Z"
			r.Observations[0].Start.SocPositionObservedAt = &value
		}},
		{"invented-range-time", func(r *batteryTrendImportRequest) {
			value := r.Observations[0].StartedAt
			r.Observations[0].Start.RatedRangeObservedAt = &value
		}},
		{"temperature-source", func(r *batteryTrendImportRequest) { r.Observations[0].TemperatureSource = nil }},
		{"noncanonical-time", func(r *batteryTrendImportRequest) { r.Observations[0].StartedAt = "2026-10-28T09:00:00+08:00" }},
		{"wrong-hash", func(r *batteryTrendImportRequest) { r.PayloadSHA256 = strings.Repeat("0", 64) }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			b, r := batteryTrendTestRequest(t)
			tc.change(&r)
			if tc.name != "wrong-hash" {
				r.PayloadSHA256, _ = batteryTrendDigest(r.payload())
			}
			if validateBatteryTrendImport(r, b) == nil {
				t.Fatal("invalid evidence was accepted")
			}
		})
	}
	// A provider drive summary is valid even when its position does not carry rated range.
	request.Observations[0].End = &batteryTrendBoundary{}
	request.PayloadSHA256, _ = batteryTrendDigest(request.payload())
	if err := validateBatteryTrendImport(request, binding); err != nil {
		t.Fatal(err)
	}
	zeroSOC, zeroRange := 0, 0.0
	boundaryTime := "2026-10-28T01:00:01Z"
	request.Observations[0].Start.SocPercent = &zeroSOC
	request.Observations[0].Start.SocPositionObservedAt = &boundaryTime
	request.Observations[0].Start.RatedRangeKm = &zeroRange
	request.PayloadSHA256, _ = batteryTrendDigest(request.payload())
	if err := validateBatteryTrendImport(request, binding); err != nil {
		t.Fatal("valid zero or exact one-second boundary was discarded")
	}
	boundaryTime = "2026-10-28T01:00:01.000001Z"
	request.PayloadSHA256, _ = batteryTrendDigest(request.payload())
	if err := validateBatteryTrendImport(request, binding); err == nil {
		t.Fatal("outside one-second boundary was accepted")
	}
}

func TestBatteryTrendImportRouteIsExactAndRequiresBinding(t *testing.T) {
	path := "/api/v1/cars/17/history/archive/battery-trend/import"
	if !batteryTrendImportPath(path) || batteryTrendImportPath(path+"/extra") || batteryTrendImportPath("/api/v1/cars/17/history/archive/import") {
		t.Fatal("unexpected archive route match")
	}
	a := &app{}
	recorder := httptest.NewRecorder()
	a.batteryTrendArchiveImport(recorder, httptest.NewRequest(http.MethodPost, path, strings.NewReader(`{}`)))
	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("missing binding status = %d", recorder.Code)
	}
}

func TestBatteryTrendPageParameters(t *testing.T) {
	for _, value := range []string{"-1", "+1", "01", "1.0", "9223372036854775808"} {
		if _, ok := batteryTrendPageNumber(value, 0); ok {
			t.Fatalf("accepted %q", value)
		}
	}
	if value, ok := batteryTrendPageNumber("25", 0); !ok || value != 25 {
		t.Fatal("valid cursor rejected")
	}
}
