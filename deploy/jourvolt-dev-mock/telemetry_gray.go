package main

import (
	"context"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"
)

// Gray-release telemetry support (design 2026-11-05 §7).
//
// Candidate fields live in the separate grayTelemetryFieldSpecs
// registry, NOT in telemetryFieldSpecs: the production field set (and
// the desired configuration built from it) stays exactly as it was.
// telemetryFieldSpec consults both registries, so an allowlisted
// vehicle's stream is parsed instead of dropped, and the service
// counts accepted deliveries per vehicle. A field is promoted by
// moving its entry into telemetryFieldSpecs. Availability is
// evaluated via GET /api/v1/cars/{carId}/telemetry/gray-fields.

// grayTelemetryFieldSpecs holds the gray-release candidates. Names and
// kinds were verified against Tesla's official Fleet Telemetry
// "Available Data" dictionary on 2026-11-05. Fields that could not be
// verified there (e.g. AutopilotState, SteeringWheelAngle, a
// BlindSpotWarning event) are deliberately absent;
// BlindSpotCollisionWarningChime is the dictionary's blind-spot field
// (a chime setting, not an event).
var grayTelemetryFieldSpecs = map[string]telemetryFieldDefinition{
	"LifetimeEnergyUsed":             {Name: "LifetimeEnergyUsed", Interval: 60 * time.Second, Kind: "number"},
	"EnergyRemaining":                {Name: "EnergyRemaining", Interval: 60 * time.Second, Kind: "number"},
	"BrickVoltageMax":                {Name: "BrickVoltageMax", Interval: 60 * time.Second, Kind: "number"},
	"BrickVoltageMin":                {Name: "BrickVoltageMin", Interval: 60 * time.Second, Kind: "number"},
	"ModuleTempMax":                  {Name: "ModuleTempMax", Interval: 60 * time.Second, Kind: "number"},
	"ModuleTempMin":                  {Name: "ModuleTempMin", Interval: 60 * time.Second, Kind: "number"},
	"ForwardCollisionWarning":        {Name: "ForwardCollisionWarning", Interval: 60 * time.Second, Kind: "string"},
	"BlindSpotCollisionWarningChime": {Name: "BlindSpotCollisionWarningChime", Interval: 60 * time.Second, Kind: "bool"},
	"BrakePedal":                     {Name: "BrakePedal", Interval: 10 * time.Second, Kind: "bool"},
	"PedalPosition":                  {Name: "PedalPosition", Interval: 10 * time.Second, Kind: "number"},
	"LocatedAtHome":                  {Name: "LocatedAtHome", Interval: 60 * time.Second, Kind: "bool"},
	"LocatedAtWork":                  {Name: "LocatedAtWork", Interval: 60 * time.Second, Kind: "bool"},
	"LocatedAtFavorite":              {Name: "LocatedAtFavorite", Interval: 60 * time.Second, Kind: "bool"},
	"FdWindow":                       {Name: "FdWindow", Interval: 60 * time.Second, Kind: "string"},
	"FpWindow":                       {Name: "FpWindow", Interval: 60 * time.Second, Kind: "string"},
	"RdWindow":                       {Name: "RdWindow", Interval: 60 * time.Second, Kind: "string"},
	"RpWindow":                       {Name: "RpWindow", Interval: 60 * time.Second, Kind: "string"},
}

// telemetryFieldAvailability counts accepted deliveries of one field
// for one vehicle since process start (in-memory, like the command
// rate limiter; a restart restarts the evaluation window).
type telemetryFieldAvailability struct {
	Field     string    `json:"field"`
	Events    int64     `json:"events"`
	FirstSeen time.Time `json:"first_seen,omitempty"`
	LastSeen  time.Time `json:"last_seen,omitempty"`
}

func grayTelemetryFieldNames() []string {
	names := make([]string, 0, len(grayTelemetryFieldSpecs))
	for name := range grayTelemetryFieldSpecs {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}

// countGrayDelivery records one accepted delivery of a gray field. It
// runs on the service ingest path so it covers both the in-memory and
// the Postgres-backed stores, keyed by VIN hash (one vehicle).
func (s *telemetryService) countGrayDelivery(record telemetryRecord) {
	if _, ok := grayTelemetryFieldSpecs[record.FieldName]; !ok || record.VINHash == "" {
		return
	}
	s.grayMu.Lock()
	defer s.grayMu.Unlock()
	if s.grayCounts == nil {
		s.grayCounts = map[string]map[string]*telemetryFieldAvailability{}
	}
	fields := s.grayCounts[record.VINHash]
	if fields == nil {
		fields = map[string]*telemetryFieldAvailability{}
		s.grayCounts[record.VINHash] = fields
	}
	stat := fields[record.FieldName]
	if stat == nil {
		stat = &telemetryFieldAvailability{Field: record.FieldName, FirstSeen: record.ObservedAt}
		fields[record.FieldName] = stat
	}
	stat.Events++
	if record.ObservedAt.Before(stat.FirstSeen) {
		stat.FirstSeen = record.ObservedAt
	}
	if record.ObservedAt.After(stat.LastSeen) {
		stat.LastSeen = record.ObservedAt
	}
}

// grayVINHash resolves the VIN hash registered for a vehicle, from the
// in-memory bindings when present, otherwise from the telemetry
// vehicle-keys table.
func (s *telemetryService) grayVINHash(ctx context.Context, userID string, vehicleID int) (string, bool) {
	if s.memory != nil {
		s.memory.mu.Lock()
		defer s.memory.mu.Unlock()
		for vinHash, refs := range s.memory.vehicles {
			for _, ref := range refs {
				if ref.UserID == userID && ref.VehicleID == vehicleID {
					return vinHash, true
				}
			}
		}
		return "", false
	}
	if s.store == nil || s.store.pool == nil {
		return "", false
	}
	var vinHash string
	err := s.store.pool.QueryRow(ctx, `
SELECT vin_hash FROM jourvolt_telemetry_vehicle_keys WHERE user_id = $1 AND vehicle_id = $2`, userID, vehicleID).Scan(&vinHash)
	if err != nil || strings.TrimSpace(vinHash) == "" {
		return "", false
	}
	return vinHash, true
}

// grayFieldAvailability reports the allowlist state plus per-field
// delivery counts for one vehicle: one entry per gray candidate,
// including zero-count entries for fields that have never arrived.
func (s *telemetryService) grayFieldAvailability(ctx context.Context, userID string, vehicleID int) (bool, []telemetryFieldAvailability) {
	if s == nil {
		return false, nil
	}
	enabled := s.grayVehicle(vehicleID)
	vinHash, resolved := s.grayVINHash(ctx, userID, vehicleID)
	s.grayMu.Lock()
	defer s.grayMu.Unlock()
	var counted map[string]*telemetryFieldAvailability
	if resolved {
		counted = s.grayCounts[vinHash]
	}
	out := make([]telemetryFieldAvailability, 0, len(grayTelemetryFieldNames()))
	for _, name := range grayTelemetryFieldNames() {
		if stat, ok := counted[name]; ok && stat != nil {
			out = append(out, *stat)
			continue
		}
		out = append(out, telemetryFieldAvailability{Field: name})
	}
	return enabled, out
}

// parseGrayVehicleIDs parses the TELEMETRY_GRAY_VEHICLE_IDS allowlist:
// a comma-separated list of vehicle ids. Empty input means no vehicle
// is allowlisted.
func parseGrayVehicleIDs(raw string) (map[int]bool, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil, nil
	}
	out := map[int]bool{}
	for _, part := range strings.Split(raw, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		id, err := strconv.Atoi(part)
		if err != nil || id <= 0 {
			return nil, fmt.Errorf("TELEMETRY_GRAY_VEHICLE_IDS must be a comma-separated list of positive vehicle ids")
		}
		out[id] = true
	}
	return out, nil
}

func (s *telemetryService) grayVehicle(vehicleID int) bool {
	if s == nil || s.config == nil {
		return false
	}
	return s.config.GrayVehicleIDs[vehicleID]
}
