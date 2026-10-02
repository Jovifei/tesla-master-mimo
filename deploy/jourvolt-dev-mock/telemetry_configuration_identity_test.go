package main

import (
	"encoding/json"
	"testing"
)

func telemetryVerifiedConfigFixture() string {
	body, _ := json.Marshal(map[string]any{"response": map[string]any{"synced": true, "config": officialFleetTelemetryConfiguration{Hostname: "fleet.example.com", Port: 4443, Fields: desiredTelemetryConfiguration().Fields}}})
	return string(body)
}

func TestTelemetrySyncedEmptyConfigIsNotConfigured(t *testing.T) {
	s := newTelemetryServiceForTest("partner.example.com")
	var empty officialFleetTelemetryResponse
	if err := json.Unmarshal([]byte(`{"response":{"synced":true,"config":null}}`), &empty); err != nil {
		t.Fatal(err)
	}
	if s.matchesDesiredTelemetryConfig(empty.Response.Config) {
		t.Fatal("empty config accepted")
	}
	var valid officialFleetTelemetryResponse
	if err := json.Unmarshal([]byte(telemetryVerifiedConfigFixture()), &valid); err != nil {
		t.Fatal(err)
	}
	if !s.matchesDesiredTelemetryConfig(valid.Response.Config) {
		t.Fatal("expected configuration rejected")
	}
	for _, config := range []any{
		officialFleetTelemetryConfiguration{Hostname: "other.example.com", Port: 4443, Fields: desiredTelemetryConfiguration().Fields},
		officialFleetTelemetryConfiguration{Hostname: "fleet.example.com", Port: 4444, Fields: desiredTelemetryConfiguration().Fields},
		officialFleetTelemetryConfiguration{Hostname: "fleet.example.com", Port: 4443},
	} {
		if s.matchesDesiredTelemetryConfig(config) {
			t.Fatal("wrong target or missing fields accepted")
		}
	}
}
