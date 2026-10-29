package main

import (
	"context"
	"encoding/json"
	"math"
	"regexp"
	"strings"
	"time"
	"unicode"
)

type diagnosticReadinessCar struct {
	CarID      int     `json:"car_id"`
	VehicleUID string  `json:"vehicle_uid"`
	Name       *string `json:"name"`
	CarDetails struct {
		Model *string `json:"model"`
	} `json:"car_details"`
}

type diagnosticObservedOdometer struct {
	Value      *float64 `json:"value"`
	Unit       string   `json:"unit"`
	ObservedAt *string  `json:"observed_at"`
	Source     *string  `json:"source"`
}

type diagnosticReadinessContext struct {
	Availability     string                     `json:"availability"`
	Readiness        *dataReadinessResponse     `json:"readiness"`
	Car              *diagnosticReadinessCar    `json:"car"`
	ObservedOdometer diagnosticObservedOdometer `json:"observed_odometer"`
}

var diagnosticStoredVINPattern = regexp.MustCompile(`(?i)[A-HJ-NPR-Z0-9]{17}`)
var diagnosticStoredUIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,256}$`)

func diagnosticStoredText(value string, userID string) *string {
	if value == "" || len(value) > 128 || strings.Contains(value, "://") || diagnosticStoredVINPattern.MatchString(value) || diagnosticContainsPrivate(&value, []string{userID}) {
		return nil
	}
	for _, r := range value {
		if unicode.IsControl(r) || !(unicode.IsLetter(r) || unicode.IsNumber(r) || unicode.IsSpace(r) || strings.ContainsRune("-_.()", r)) {
			return nil
		}
	}
	return &value
}

// This path has no provider, token, pairing, identity-allocation or write API.
// Keep it independent of currentVehicleStatus and telemetryReadinessForData.
func (a *app) readOnlyReadinessContext(ctx context.Context, userID string, carID int) *diagnosticReadinessContext {
	result := &diagnosticReadinessContext{Availability: "unavailable", ObservedOdometer: diagnosticObservedOdometer{Unit: "km"}}
	if a.store == nil || a.store.pool == nil {
		return result
	}
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	var providerID, name string
	var model *string
	err := a.store.pool.QueryRow(ctx, `SELECT provider_vehicle_id, display_name, model FROM jourvolt_vehicles WHERE id=$1 AND user_id=$2`, carID, userID).Scan(&providerID, &name, &model)
	// Fleet vehicle identifiers may be long decimal integers. Those are the
	// existing owner-scoped UID contract, rather than alphanumeric VINs.
	looksLikeVIN := diagnosticStoredVINPattern.MatchString(providerID) && strings.Trim(providerID, "0123456789") != ""
	if err != nil || !diagnosticStoredUIDPattern.MatchString(providerID) || looksLikeVIN || diagnosticContainsPrivate(&providerID, []string{userID}) {
		return result
	}
	uid, err := a.stableVehicleUID(ctx, userID, carID, providerID)
	if err != nil {
		return result
	}
	car := &diagnosticReadinessCar{CarID: carID, VehicleUID: providerID, Name: diagnosticStoredText(name, userID)}
	if model != nil {
		switch *model {
		case "3", "S", "X", "Y", "Cybertruck", "Roadster":
			car.CarDetails.Model = model
		}
	}
	result.Car = car
	result.Availability = "available"
	readiness := &dataReadinessResponse{CapabilityVersion: dataReadinessCapabilityVersion, VehicleUID: uid, Items: []dataReadinessItem{
		{Key: "live_status", Status: "unknown", Source: "telemetry_mqtt"},
		{Key: "location", Status: "unknown", Source: "telemetry_mqtt"},
		{Key: "tpms", Status: "unknown", Source: "telemetry_mqtt"},
		{Key: "drives", Status: "unknown", Source: "local_history"},
		{Key: "charges", Status: "unknown", Source: "local_history"},
		{Key: "battery_health", Status: "unsupported", Source: "telemetry_mqtt", MessageKey: "battery_health_unsupported", Action: "not_available"},
	}}
	result.Readiness = readiness
	snapshot, err := a.readinessDiagnosticSnapshot(ctx, userID, carID)
	if err != nil {
		result.Availability = "partial"
	} else {
		result.projectReadinessSnapshot(snapshot, time.Now().UTC())
	}
	reader := &telemetryService{store: a.store}
	for _, kind := range []string{"drive", "charge"} {
		key := kind + "s"
		index := 3
		if kind == "charge" {
			index = 4
		}
		exists, readErr := reader.historyExistsContext(ctx, userID, carID, kind)
		if readErr != nil {
			result.Availability = "partial"
			continue
		}
		item := historyReadinessItemWithAvailability(key, "local_history", exists)
		if exists {
			source, known, sourceErr := reader.historySourceForReadinessContext(ctx, userID, carID, kind)
			if sourceErr != nil {
				result.Availability = "partial"
				continue
			}
			if !known {
				result.Availability = "partial"
				continue
			}
			item.Source = source
		}
		readiness.Items[index] = item
	}
	return result
}

func (a *app) readinessDiagnosticSnapshot(ctx context.Context, userID string, carID int) (telemetrySnapshot, error) {
	rows, err := a.store.pool.Query(ctx, `SELECT field_name, value_json, observed_at, source FROM jourvolt_telemetry_latest
WHERE user_id=$1 AND vehicle_id=$2 AND field_name IN ('Soc','Location','Odometer','TpmsPressureFl','TpmsPressureFr','TpmsPressureRl','TpmsPressureRr','TpmsSoftWarnings') LIMIT 8`, userID, carID)
	if err != nil {
		return telemetrySnapshot{}, err
	}
	defer rows.Close()
	snapshot := telemetrySnapshot{Fields: map[string]any{}, FieldObservedAt: map[string]time.Time{}, FieldSources: map[string]string{}, Source: "telemetry_mqtt"}
	for rows.Next() {
		var field, source string
		var value []byte
		var at time.Time
		if err := rows.Scan(&field, &value, &at, &source); err != nil {
			return telemetrySnapshot{}, err
		}
		if len(value) > 4096 {
			continue
		}
		var decoded any
		if json.Unmarshal(value, &decoded) != nil {
			continue
		}
		snapshot.Fields[field], snapshot.FieldObservedAt[field], snapshot.FieldSources[field] = decoded, at, source
	}
	return snapshot, rows.Err()
}

func (d *diagnosticReadinessContext) projectReadinessSnapshot(snapshot telemetrySnapshot, now time.Time) {
	if d.Readiness == nil {
		return
	}
	var latest, location, tpms time.Time
	for field, value := range snapshot.Fields {
		at := snapshot.FieldObservedAt[field]
		if snapshot.FieldSources[field] != "telemetry_mqtt" || at.IsZero() || at.After(now) {
			continue
		}
		valid, isLocation, isTPMS := false, false, false
		switch field {
		case "Soc", "Odometer", "TpmsPressureFl", "TpmsPressureFr", "TpmsPressureRl", "TpmsPressureRr":
			// The normalized producer persists JSON numbers, never raw numeric
			// strings whose unit/provenance could be a legacy provider value.
			number, ok := value.(float64)
			if !ok || math.IsNaN(number) || math.IsInf(number, 0) || number < 0 {
				continue
			}
			if field == "Soc" && number > 100 {
				continue
			}
			valid = true
			isTPMS = strings.HasPrefix(field, "TpmsPressure")
			if field == "Odometer" {
				// The telemetry_mqtt producer normalizes Odometer to km before
				// persisting it. Never apply that conversion a second time.
				source := "telemetry_mqtt"
				d.ObservedOdometer = diagnosticObservedOdometer{Value: &number, Unit: "km", ObservedAt: observedTimestamp(at), Source: &source}
			}
		case "Location":
			point, ok := value.(map[string]any)
			if !ok {
				continue
			}
			lat, latOK := point["latitude"].(float64)
			lon, lonOK := point["longitude"].(float64)
			valid = latOK && lonOK && validFleetCoordinates(&lat, &lon)
			isLocation = true
		case "TpmsSoftWarnings":
			wheels, ok := value.(map[string]any)
			if !ok {
				continue
			}
			for _, wheel := range []string{"front_left", "front_right", "rear_left", "rear_right", "frontLeft", "frontRight", "rearLeft", "rearRight"} {
				if _, ok := wheels[wheel].(bool); ok {
					valid = true
				}
			}
			isTPMS = true
		}
		if !valid || at.Before(now.Add(-telemetryStaleAfter)) {
			continue
		}
		if at.After(latest) {
			latest = at
		}
		if isLocation && at.After(location) {
			location = at
		}
		if isTPMS && at.After(tpms) {
			tpms = at
		}
	}
	for index, at := range []time.Time{latest, location, tpms} {
		if !at.IsZero() {
			d.Readiness.Items[index] = dataReadinessItem{Key: d.Readiness.Items[index].Key, Status: "available", Source: "telemetry_mqtt", LastObservedAt: observedTimestamp(at)}
		}
	}
}
