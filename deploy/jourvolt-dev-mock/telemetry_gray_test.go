package main

import (
	"context"
	"reflect"
	"testing"
	"time"
)

// The gray candidate set is exactly the fields verified against Tesla's
// official Fleet Telemetry "Available Data" dictionary on 2026-11-05.
// Adding a name here requires re-verifying it in that dictionary.
func TestGrayFieldsVerifiedSet(t *testing.T) {
	want := []string{
		"BlindSpotCollisionWarningChime", "BrakePedal", "BrickVoltageMax", "BrickVoltageMin",
		"EnergyRemaining", "FdWindow", "ForwardCollisionWarning", "FpWindow",
		"LifetimeEnergyUsed", "LocatedAtFavorite", "LocatedAtHome", "LocatedAtWork",
		"ModuleTempMax", "ModuleTempMin", "PedalPosition", "RdWindow", "RpWindow",
	}
	if got := grayTelemetryFieldNames(); !reflect.DeepEqual(got, want) {
		t.Fatalf("gray field set = %v, want %v", got, want)
	}
}

func TestDesiredConfigExcludesGrayByDefault(t *testing.T) {
	base := desiredTelemetryConfiguration()
	if len(base.Fields) != 31 {
		t.Fatalf("production desired fields = %d, want the 31 baseline fields", len(base.Fields))
	}
	for _, name := range grayTelemetryFieldNames() {
		if _, ok := base.Fields[name]; ok {
			t.Fatalf("gray field %s leaked into the production desired configuration", name)
		}
	}
	withGray := desiredTelemetryConfigurationFor(true)
	if len(withGray.Fields) != 31+len(grayTelemetryFieldNames()) {
		t.Fatalf("gray desired fields = %d, want baseline + gray candidates", len(withGray.Fields))
	}
	if got := withGray.Fields["LifetimeEnergyUsed"].IntervalSeconds; got != 60 {
		t.Fatalf("LifetimeEnergyUsed interval = %d, want 60", got)
	}
	for name, field := range base.Fields {
		got := withGray.Fields[name]
		sameDelta := (field.MinimumDelta == nil && got.MinimumDelta == nil) ||
			(field.MinimumDelta != nil && got.MinimumDelta != nil && *field.MinimumDelta == *got.MinimumDelta)
		if got.IntervalSeconds != field.IntervalSeconds || !sameDelta {
			t.Fatalf("gray configuration changed baseline field %s", name)
		}
	}
}

func TestParseGrayFieldsAccepted(t *testing.T) {
	now := time.Now().UTC()
	cases := map[string]string{
		"LifetimeEnergyUsed":             "123.4",
		"EnergyRemaining":                "41.25",
		"BrickVoltageMin":                "3.61",
		"LocatedAtHome":                  "true",
		"BlindSpotCollisionWarningChime": "false",
		"BrakePedal":                     "true",
		"PedalPosition":                  "0.5",
		"ForwardCollisionWarning":        `"Early"`,
		"FdWindow":                       `"Closed"`,
	}
	for field, payload := range cases {
		record, err := parseTelemetryPayload("jourvolt", "jourvolt/5YJ3E1EA7KF123456/v/"+field, []byte(payload), now)
		if err != nil {
			t.Fatalf("gray field %s rejected: %v", field, err)
		}
		if record.FieldName != field {
			t.Fatalf("parsed field = %s, want %s", record.FieldName, field)
		}
	}
}

// Names that appear in design drafts but are NOT in Tesla's official
// dictionary must stay rejected: inventing them would silently collect
// nothing while looking configured.
func TestParseUnverifiedFieldsRejected(t *testing.T) {
	now := time.Now().UTC()
	for _, field := range []string{"AutopilotState", "SteeringWheelAngle", "BlindSpotWarning", "LifetimeEnergyUsedDrive"} {
		if _, err := parseTelemetryPayload("jourvolt", "jourvolt/5YJ3E1EA7KF123456/v/"+field, []byte("1"), now); err == nil {
			t.Fatalf("unverified field %s must be rejected", field)
		}
	}
}

func TestParseGrayVehicleIDs(t *testing.T) {
	ids, err := parseGrayVehicleIDs(" 12, 34 ,12 ")
	if err != nil || len(ids) != 2 || !ids[12] || !ids[34] {
		t.Fatalf("parse = %v, err = %v", ids, err)
	}
	if ids, err := parseGrayVehicleIDs(""); err != nil || ids != nil {
		t.Fatalf("empty allowlist = %v, err = %v", ids, err)
	}
	for _, raw := range []string{"abc", "0", "-3", "1.5"} {
		if _, err := parseGrayVehicleIDs(raw); err == nil {
			t.Fatalf("allowlist %q must be rejected", raw)
		}
	}
}

func TestLoadTelemetryConfigGrayAllowlist(t *testing.T) {
	values := telemetryTestEnv()
	values["TELEMETRY_GRAY_VEHICLE_IDS"] = "7, 9"
	config, err := loadTelemetryConfig(func(key string) string { return values[key] })
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	if !config.GrayVehicleIDs[7] || !config.GrayVehicleIDs[9] || len(config.GrayVehicleIDs) != 2 {
		t.Fatalf("gray allowlist = %v", config.GrayVehicleIDs)
	}
	values["TELEMETRY_GRAY_VEHICLE_IDS"] = "seven"
	if _, err := loadTelemetryConfig(func(key string) string { return values[key] }); err == nil {
		t.Fatal("invalid gray allowlist must fail configuration loading")
	}
}

func TestGrayAvailabilityCounting(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	service.config.GrayVehicleIDs = map[int]bool{1: true}
	ctx := context.Background()
	vin := "5YJ3E1EA7KF123456"
	base := time.Now().UTC().Add(-time.Hour)

	first, err := parseTelemetryPayload("jourvolt", "jourvolt/"+vin+"/v/LifetimeEnergyUsed", []byte("100.5"), base)
	if err != nil {
		t.Fatal(err)
	}
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: first.VINHash, ProviderVehicleID: "provider-1"}
	if err := service.memory.registerVehicle(ref); err != nil {
		t.Fatal(err)
	}
	second, err := parseTelemetryPayload("jourvolt", "jourvolt/"+vin+"/v/LifetimeEnergyUsed", []byte("101.0"), base.Add(time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	soc, err := parseTelemetryPayload("jourvolt", "jourvolt/"+vin+"/v/Soc", []byte("55"), base)
	if err != nil {
		t.Fatal(err)
	}
	for _, record := range []telemetryRecord{first, second, soc} {
		if _, err := service.ingest(ctx, record); err != nil {
			t.Fatalf("ingest %s: %v", record.FieldName, err)
		}
	}

	enabled, fields := service.grayFieldAvailability(ctx, "user-a", 1)
	if !enabled {
		t.Fatal("vehicle 1 is on the gray allowlist")
	}
	byField := map[string]telemetryFieldAvailability{}
	for _, field := range fields {
		byField[field.Field] = field
	}
	if len(fields) != len(grayTelemetryFieldNames()) {
		t.Fatalf("availability entries = %d, want one per gray candidate", len(fields))
	}
	if got := byField["LifetimeEnergyUsed"]; got.Events != 2 || !got.FirstSeen.Equal(base) || !got.LastSeen.Equal(base.Add(time.Minute)) {
		t.Fatalf("LifetimeEnergyUsed availability = %+v", got)
	}
	if got := byField["LocatedAtWork"]; got.Events != 0 {
		t.Fatalf("never-delivered gray field must report zero events, got %+v", got)
	}
	if _, ok := byField["Soc"]; ok {
		t.Fatal("baseline fields are not gray candidates and must not be listed")
	}
	if enabled, _ := service.grayFieldAvailability(ctx, "user-a", 2); enabled {
		t.Fatal("vehicle 2 is not on the gray allowlist")
	}
}

// A gray vehicle's live configuration carries extra fields; the
// desired-config match (which drives auto-configure) must still pass
// so allowlisted vehicles are not reconfigured in a loop.
func TestMatchesDesiredIgnoresGrayExtras(t *testing.T) {
	service := &telemetryService{config: &telemetryConfig{PublicHost: "fleet.example.com", PublicPort: 4443}}
	value := officialFleetTelemetryConfiguration{
		Hostname: "fleet.example.com", Port: 4443,
		Fields: desiredTelemetryConfigurationFor(true).Fields,
	}
	if !service.matchesDesiredTelemetryConfig(value) {
		t.Fatal("a configuration with gray extras must match the production desired set")
	}
}
