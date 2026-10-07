package main

import (
	"errors"
	"fmt"
	"math"
	"strings"
	"time"
)

// This versioned core is deliberately unwired. Callers must durably admit and
// deduplicate canonical events in their ownership scope before applying them.
// It has no Seen cache and makes no lifetime-deduplication claim. The fixed
// watermarks defensively reject non-advancing timestamps, as PostgreSQL does.
const compactTelemetryVersion = 1
const compactTelemetryFieldCount = 31

// Slots are part of version 1. A new/renamed field requires an explicit version
// and bootstrap compatibility decision; unknown inputs are never silently lost.
var compactTelemetryFields = [compactTelemetryFieldCount]string{
	"VehicleSpeed", "Location", "GpsHeading", "Soc", "Odometer", "EstBatteryRange", "RatedRange",
	"ACChargingEnergyIn", "DCChargingEnergyIn", "ACChargingPower", "DCChargingPower", "ChargeAmps", "ChargerPhases",
	"ChargeCurrentRequest", "ChargeCurrentRequestMax", "TimeToFullCharge", "FastChargerPresent", "PackVoltage", "PackCurrent",
	"DoorState", "Locked", "DetailedChargeState", "Gear", "InsideTemp", "OutsideTemp", "TpmsPressureFl", "TpmsPressureFr",
	"TpmsPressureRl", "TpmsPressureRr", "TpmsHardWarnings", "TpmsSoftWarnings",
}

type compactTelemetrySession struct {
	ID, Kind, Source, QualityState, QualityReason, CompletionKey string
	PublicID                                                     int
	StartAt                                                      time.Time
	EndAt                                                        *time.Time
	OdometerStart, OdometerEnd, EnergyAdded                      *float64
	RouteCount, ChargeCount                                      int64
}

type compactTelemetryState struct {
	Version           int
	Scope             telemetryKey
	Debounce          time.Duration
	Drive, Charge     *compactTelemetrySession
	StopCandidate     *time.Time
	ChargeEnergyStart *float64
	ChargeEnergyField string
	LastSpeed         *float64
	LastSpeedAt       time.Time
	LastHeading       *float64
	LastHeadingAt     time.Time
	CurrentGear       string
	CurrentGearAt     time.Time
	FieldTimes        [compactTelemetryFieldCount]time.Time
}

type compactTelemetryDelta struct {
	Applied                         bool
	Route                           *telemetryRoutePoint
	Charge                          *telemetryChargePoint
	RouteSessionID, ChargeSessionID string
	Completed                       [2]compactTelemetrySession
	CompletedCount                  int
}

func newCompactTelemetryState(scope telemetryKey, debounce time.Duration) (compactTelemetryState, error) {
	if debounce <= 0 {
		debounce = defaultDriveStopDebounce
	}
	s := compactTelemetryState{Version: compactTelemetryVersion, Scope: scope, Debounce: debounce}
	if err := validateCompactTelemetryState(s, scope); err != nil {
		return compactTelemetryState{}, err
	}
	s.Scope.UserID = strings.Clone(scope.UserID)
	return s, nil
}

func compactTelemetryFieldIndex(field string) int {
	for i, candidate := range compactTelemetryFields {
		if field == candidate {
			return i
		}
	}
	return -1
}

func finiteCompactNumbers(values ...*float64) bool {
	for _, value := range values {
		if value != nil && (math.IsNaN(*value) || math.IsInf(*value, 0)) {
			return false
		}
	}
	return true
}

// Restoration accepts only this bounded native representation, not a legacy
// session or its arrays. Source/format/revision trust must be established by a
// future persistence adapter before constructing it; this function is not auth.
func validateCompactTelemetryState(s compactTelemetryState, scope telemetryKey) error {
	if s.Version != compactTelemetryVersion || s.Scope != scope || strings.TrimSpace(scope.UserID) == "" || len(scope.UserID) > 256 || scope.VehicleID < 1 || scope.VehicleID > math.MaxInt32 || s.Debounce <= 0 {
		return errors.New("invalid compact telemetry version, ownership scope or debounce")
	}
	if len(s.CurrentGear) > 16 || (s.ChargeEnergyField != "" && s.ChargeEnergyField != "ACChargingEnergyIn" && s.ChargeEnergyField != "DCChargingEnergyIn") || !finiteCompactNumbers(s.ChargeEnergyStart, s.LastSpeed, s.LastHeading) {
		return errors.New("invalid compact telemetry observation state")
	}
	for i, session := range [2]*compactTelemetrySession{s.Drive, s.Charge} {
		if session == nil {
			continue
		}
		kind := "drive"
		if i == 1 {
			kind = "charge"
		}
		if session.ID == "" || len(session.ID) > 256 || session.PublicID < 0 || session.PublicID > math.MaxInt32 || session.Kind != kind || session.Source != "telemetry_mqtt" || session.StartAt.IsZero() || session.EndAt != nil || session.CompletionKey != "" || session.RouteCount < 0 || session.ChargeCount < 0 || len(session.QualityState) > 64 || len(session.QualityReason) > 128 || !finiteCompactNumbers(session.OdometerStart, session.OdometerEnd, session.EnergyAdded) {
			return errors.New("invalid compact native session header")
		}
	}
	if s.Drive == nil && s.StopCandidate != nil || s.Charge == nil && (s.ChargeEnergyStart != nil || s.ChargeEnergyField != "") {
		return errors.New("compact telemetry state has an orphan session observation")
	}
	return nil
}

func cloneCompactTelemetrySession(s *compactTelemetrySession) *compactTelemetrySession {
	if s == nil {
		return nil
	}
	v := *s
	v.ID, v.Kind, v.Source = strings.Clone(s.ID), strings.Clone(s.Kind), strings.Clone(s.Source)
	v.QualityState, v.QualityReason, v.CompletionKey = strings.Clone(s.QualityState), strings.Clone(s.QualityReason), strings.Clone(s.CompletionKey)
	v.OdometerStart, v.OdometerEnd, v.EnergyAdded = cloneFloat(s.OdometerStart), cloneFloat(s.OdometerEnd), cloneFloat(s.EnergyAdded)
	if s.EndAt != nil {
		at := *s.EndAt
		v.EndAt = &at
	}
	return &v
}

func cloneCompactTelemetryState(s compactTelemetryState) compactTelemetryState {
	s.Scope.UserID = strings.Clone(s.Scope.UserID)
	s.CurrentGear, s.ChargeEnergyField = strings.Clone(s.CurrentGear), strings.Clone(s.ChargeEnergyField)
	s.Drive, s.Charge = cloneCompactTelemetrySession(s.Drive), cloneCompactTelemetrySession(s.Charge)
	s.ChargeEnergyStart, s.LastSpeed, s.LastHeading = cloneFloat(s.ChargeEnergyStart), cloneFloat(s.LastSpeed), cloneFloat(s.LastHeading)
	if s.StopCandidate != nil {
		at := *s.StopCandidate
		s.StopCandidate = &at
	}
	return s
}

func compactTelemetryQuality(s compactTelemetrySession) (string, string) {
	hasOdometer := s.OdometerStart != nil && s.OdometerEnd != nil && *s.OdometerEnd >= *s.OdometerStart
	if s.Kind == "drive" {
		if hasOdometer || s.RouteCount >= 2 {
			return "observed", "telemetry_evidence"
		}
		return "incomplete", "missing_route_or_odometer"
	}
	if s.EnergyAdded != nil && *s.EnergyAdded >= 0 && !math.IsNaN(*s.EnergyAdded) && !math.IsInf(*s.EnergyAdded, 0) {
		return "observed", "telemetry_energy_delta"
	}
	if s.RouteCount >= 2 {
		return "observed", "telemetry_evidence"
	}
	return "incomplete", "missing_charge_measurement"
}

func compactNewSession(s compactTelemetryState, kind string, at time.Time) *compactTelemetrySession {
	return &compactTelemetrySession{ID: scopedSessionID("telemetry_mqtt", s.Scope.UserID, s.Scope.VehicleID, kind, at.UTC().Format(time.RFC3339Nano)), Kind: kind, StartAt: at, Source: "telemetry_mqtt"}
}

func completeCompactTelemetry(s *compactTelemetrySession, at time.Time, delta *compactTelemetryDelta) {
	s.EndAt = &at
	s.QualityState, s.QualityReason = compactTelemetryQuality(*s)
	s.CompletionKey = sessionCompletionKey(telemetrySession{ID: s.ID, Kind: s.Kind, EndAt: s.EndAt})
	delta.Completed[delta.CompletedCount] = *cloneCompactTelemetrySession(s)
	delta.CompletedCount++
}

func appendCompactCharge(s *compactTelemetryState, p telemetryChargePoint, delta *compactTelemetryDelta) error {
	if s.Charge == nil {
		return nil
	}
	if s.Charge.ChargeCount == math.MaxInt64 {
		return errors.New("compact charge sample count overflow")
	}
	s.Charge.ChargeCount++
	p.BatteryLevel, p.EnergyAdded, p.ChargerPower = cloneInt(p.BatteryLevel), cloneFloat(p.EnergyAdded), cloneFloat(p.ChargerPower)
	p.Latitude, p.Longitude = cloneFloat(p.Latitude), cloneFloat(p.Longitude)
	delta.Charge, delta.ChargeSessionID = &p, s.Charge.ID
	return nil
}

func compactRecent(value *float64, observedAt, eventAt time.Time) *float64 {
	if value == nil || observedAt.IsZero() || eventAt.Before(observedAt) || eventAt.Sub(observedAt) > telemetryStaleAfter {
		return nil
	}
	return value
}

// Applied transitions return fresh owned state/deltas. Errors and rejected
// non-advancing timestamps return input state unchanged. At most one sample and
// two completions are emitted.
// Event timestamps retain their supplied time.Time precision; no unit guessing,
// global event-time sorting, or cross-field clock correction is performed.
func transitionCompactTelemetry(state compactTelemetryState, scope telemetryKey, event telemetrySessionEvent) (compactTelemetryState, compactTelemetryDelta, error) {
	var delta compactTelemetryDelta
	if err := validateCompactTelemetryState(state, scope); err != nil {
		return state, delta, err
	}
	field := compactTelemetryFieldIndex(event.FieldName)
	if field < 0 || event.EventID == "" || len(event.EventID) > 256 || event.ObservedAt.IsZero() {
		return state, delta, errors.New("unsupported or incomplete admitted compact event")
	}
	if value, ok := event.Value.(float64); ok && (math.IsNaN(value) || math.IsInf(value, 0)) {
		return state, delta, errors.New("compact event must have canonical finite numeric values")
	}
	if !event.ObservedAt.After(state.FieldTimes[field]) {
		return state, delta, nil
	}
	s := cloneCompactTelemetryState(state)
	s.FieldTimes[field], delta.Applied = event.ObservedAt, true
	var err error
	switch event.FieldName {
	case "Gear", "VehicleSpeed":
		if event.FieldName == "Gear" {
			if gear, ok := canonicalGear(event.Value); ok {
				s.CurrentGear, s.CurrentGearAt = gear, event.ObservedAt
			}
		} else if speed, ok := numberFromJSONValue(event.Value); ok {
			s.LastSpeed, s.LastSpeedAt = &speed, event.ObservedAt
		}
		if s.Drive == nil {
			if isDriveEvidence(event) {
				s.Drive = compactNewSession(s, "drive", event.ObservedAt)
				s.StopCandidate = nil
			}
		} else if event.FieldName == "Gear" {
			gear, _ := canonicalGear(event.Value)
			if gear == "P" || gear == "N" {
				if s.StopCandidate == nil {
					at := event.ObservedAt
					s.StopCandidate = &at
				}
			} else {
				s.StopCandidate = nil
			}
		} else if speed, ok := numberFromJSONValue(event.Value); ok && speed > 0 {
			s.StopCandidate = nil
		}
	case "DetailedChargeState":
		value := strings.ToLower(strings.TrimSpace(fmt.Sprint(event.Value)))
		if s.Charge == nil && (value == "charging" || value == "starting") {
			s.Charge = compactNewSession(s, "charge", event.ObservedAt)
			s.ChargeEnergyStart, s.ChargeEnergyField = nil, ""
		} else if s.Charge != nil && (value == "disconnected" || value == "complete" || value == "completed" || value == "stopped") {
			completeCompactTelemetry(s.Charge, event.ObservedAt, &delta)
			s.Charge, s.ChargeEnergyStart, s.ChargeEnergyField = nil, nil, ""
		}
	case "ACChargingEnergyIn", "DCChargingEnergyIn":
		if s.Charge != nil {
			if value, ok := numberFromJSONValue(event.Value); ok {
				if s.ChargeEnergyField != event.FieldName || s.ChargeEnergyStart == nil {
					s.ChargeEnergyStart, s.ChargeEnergyField = &value, event.FieldName
				} else {
					energy := value - *s.ChargeEnergyStart
					if energy >= 0 && !math.IsNaN(energy) && !math.IsInf(energy, 0) {
						s.Charge.EnergyAdded = &energy
						err = appendCompactCharge(&s, telemetryChargePoint{ObservedAt: event.ObservedAt, EnergyAdded: &energy}, &delta)
					}
				}
			}
		}
	case "Soc":
		if s.Charge != nil {
			if value, ok := numberFromJSONValue(event.Value); ok && value >= 0 && value <= 100 {
				level := int(math.Round(value))
				err = appendCompactCharge(&s, telemetryChargePoint{ObservedAt: event.ObservedAt, BatteryLevel: &level}, &delta)
			}
		}
	case "GpsHeading":
		if value, ok := numberFromJSONValue(event.Value); ok {
			s.LastHeading, s.LastHeadingAt = &value, event.ObservedAt
		}
	case "ACChargingPower", "DCChargingPower":
		if value, ok := numberFromJSONValue(event.Value); ok {
			err = appendCompactCharge(&s, telemetryChargePoint{ObservedAt: event.ObservedAt, ChargerPower: &value}, &delta)
		}
	case "Odometer":
		if s.Drive != nil {
			if value, ok := event.Value.(float64); ok {
				if s.Drive.OdometerStart == nil {
					s.Drive.OdometerStart = &value
				} else {
					s.Drive.OdometerEnd = &value
				}
			}
		}
	case "Location":
		if point, ok := routePointFromLocationWithDefaults(event, compactRecent(s.LastSpeed, s.LastSpeedAt, event.ObservedAt), nil, compactRecent(s.LastHeading, s.LastHeadingAt, event.ObservedAt)); ok && s.Drive != nil {
			if s.Drive.RouteCount == math.MaxInt64 {
				err = errors.New("compact route sample count overflow")
			} else {
				s.Drive.RouteCount++
				delta.Route, delta.RouteSessionID = &point, s.Drive.ID
			}
		} else if point, ok := routePointFromLocation(event); ok && s.Charge != nil {
			latitude, longitude := point.Latitude, point.Longitude
			err = appendCompactCharge(&s, telemetryChargePoint{ObservedAt: event.ObservedAt, Latitude: &latitude, Longitude: &longitude}, &delta)
		}
	}
	if err != nil {
		return state, compactTelemetryDelta{}, err
	}
	if s.Drive != nil && s.StopCandidate != nil && event.ObservedAt.Sub(*s.StopCandidate) >= s.Debounce {
		completeCompactTelemetry(s.Drive, event.ObservedAt, &delta)
		s.Drive, s.StopCandidate = nil, nil
	}
	return s, delta, nil
}

func finalizeCompactTelemetry(state compactTelemetryState, scope telemetryKey, now time.Time) (compactTelemetryState, compactTelemetryDelta, error) {
	var delta compactTelemetryDelta
	if err := validateCompactTelemetryState(state, scope); err != nil {
		return state, delta, err
	}
	if now.IsZero() {
		return state, delta, errors.New("compact finalization requires explicit time")
	}
	if state.Drive == nil || state.StopCandidate == nil || now.Before(state.StopCandidate.Add(state.Debounce)) {
		return state, delta, nil
	}
	s := cloneCompactTelemetryState(state)
	completeCompactTelemetry(s.Drive, s.StopCandidate.Add(s.Debounce), &delta)
	s.Drive, s.StopCandidate, delta.Applied = nil, nil, true
	return s, delta, nil
}
