package main

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"
)

func (a *app) telemetryResource(w http.ResponseWriter, r *http.Request, userID string, vehicleID int, parts []string) {
	if a.telemetry == nil || len(parts) == 0 {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "telemetry_not_configured"})
		return
	}
	switch parts[0] {
	case "pairing":
		if r.Method != http.MethodGet || len(parts) != 1 {
			a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
			return
		}
		a.telemetry.maybeAutoConfigure(userID, vehicleID)
		pairing, err := a.telemetry.pairing(r.Context(), userID, vehicleID)
		if err != nil {
			a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "telemetry_error"})
			return
		}
		a.json(w, http.StatusOK, map[string]any{"data": pairing})
	case "configure":
		if r.Method != http.MethodPost || len(parts) != 1 {
			a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
			return
		}
		if err := a.telemetry.configure(r.Context(), userID, vehicleID); err != nil {
			telemetryConfigureError(w, err)
			return
		}
		a.json(w, http.StatusOK, map[string]any{"data": map[string]string{"status": "waiting_vehicle"}})
	case "readiness":
		if r.Method != http.MethodGet || len(parts) != 1 {
			a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
			return
		}
		item := a.telemetryReadiness(r.Context(), userID, vehicleID)
		a.json(w, http.StatusOK, map[string]any{"data": item})
	default:
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
	}
}

func telemetryConfigureError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, errTelemetryConfigInProgress):
		(&app{}).json(w, http.StatusAccepted, map[string]any{"data": map[string]string{"status": "configuring"}})
	case errors.Is(err, errTelemetryPermission):
		writeTelemetryError(w, http.StatusForbidden, "permission_required")
	case errors.Is(err, errTelemetryPairing):
		writeTelemetryError(w, http.StatusConflict, "pairing_required")
	case errors.Is(err, errTelemetryBilling):
		writeTelemetryError(w, http.StatusPaymentRequired, "billing_blocked")
	case errors.Is(err, errNotConfigured):
		writeTelemetryError(w, http.StatusServiceUnavailable, "telemetry_not_configured")
	default:
		writeTelemetryError(w, http.StatusBadGateway, "telemetry_error")
	}
}

func writeTelemetryError(w http.ResponseWriter, status int, code string) {
	// The proxy body is deliberately never returned: it can contain VINs,
	// tokens, or provider-specific diagnostics.
	(&app{}).json(w, status, map[string]string{"error": code})
}

func (a *app) telemetryHistory(w http.ResponseWriter, r *http.Request, userID string, vehicleID int, kind string, parts []string) {
	if r.Method != http.MethodGet {
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	items, meta, err := a.telemetry.history(userID, vehicleID, kind)
	if err != nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "history_unavailable"})
		return
	}
	if len(parts) > 0 && parts[0] != "" {
		for _, item := range items {
			id := item["drive_id"]
			if kind == "charge" {
				id = item["charge_id"]
			}
			if strings.TrimSpace(parts[0]) == fmt.Sprint(id) {
				a.json(w, http.StatusOK, map[string]any{"data": map[string]any{kind: item}})
				return
			}
		}
		status := http.StatusNotFound
		code := "drive_not_found"
		if kind == "charge" {
			code = "charge_not_found"
		}
		a.json(w, status, map[string]string{"error": code})
		return
	}
	items = filterTelemetryHistoryByDate(items, r)
	total := len(items)
	items, page, show := paginateTelemetryHistory(items, r)
	meta["page"], meta["show"], meta["total"] = page, show, total
	meta["total_pages"] = pageCount(total, show)
	plural := kind + "s"
	a.json(w, http.StatusOK, map[string]any{"data": map[string]any{plural: items, "meta": meta}})
}

func filterTelemetryHistoryByDate(items []map[string]any, r *http.Request) []map[string]any {
	start := parseHistoryBoundary(r.URL.Query().Get("startDate"), false)
	end := parseHistoryBoundary(r.URL.Query().Get("endDate"), true)
	if start.IsZero() && end.IsZero() {
		return items
	}
	filtered := make([]map[string]any, 0, len(items))
	for _, item := range items {
		value, ok := item["start_date"].(string)
		if !ok {
			continue
		}
		observed, err := time.Parse(time.RFC3339, value)
		if err != nil || (!start.IsZero() && observed.Before(start)) || (!end.IsZero() && !observed.Before(end)) {
			continue
		}
		filtered = append(filtered, item)
	}
	return filtered
}

func parseHistoryBoundary(value string, end bool) time.Time {
	value = strings.TrimSpace(value)
	if value == "" {
		return time.Time{}
	}
	if parsed, err := time.Parse(time.RFC3339, value); err == nil {
		return parsed
	}
	if parsed, err := time.ParseInLocation("2006-01-02", value, chinaDataLocation); err == nil {
		if end {
			return parsed.Add(24 * time.Hour)
		}
		return parsed
	}
	return time.Time{}
}

func (a *app) currentCharge(w http.ResponseWriter, r *http.Request, userID string, vehicleID int) {
	if a.telemetry != nil {
		if session, ok, err := a.telemetry.openSession(r.Context(), userID, vehicleID, "charge"); err == nil && ok {
			item := currentChargeMap(session)
			if status, statusErr := a.currentVehicleStatus(r.Context(), userID, vehicleID); statusErr == nil {
				mergeCurrentChargeStatus(item, status)
			}
			a.json(w, http.StatusOK, map[string]any{"data": map[string]any{"car": a.currentChargeCar(a.telemetry, r.Context(), userID, vehicleID), "charge": item}})
			return
		}
	}
	status, err := a.provider.Status(r.Context(), userID, vehicleID)
	if err == nil && isChargingState(status.ChargingState) {
		item := currentChargeMapFromStatus(vehicleID, status)
		car := map[string]any{"car_id": vehicleID}
		if status.DisplayName != "" {
			car["car_name"] = status.DisplayName
		}
		a.json(w, http.StatusOK, map[string]any{"data": map[string]any{"car": car, "charge": item}})
		return
	}
	a.json(w, http.StatusOK, map[string]any{"data": nil, "error": "No active charging in progress."})
}

func (a *app) currentChargeCar(service *telemetryService, ctx context.Context, userID string, vehicleID int) map[string]any {
	car := map[string]any{"car_id": vehicleID}
	if ref, ok := service.vehicleRef(ctx, userID, vehicleID); ok && ref.DisplayName != "" {
		car["car_name"] = ref.DisplayName
	}
	return car
}

func currentChargeMap(session telemetrySession) map[string]any {
	item := historySessionMap(session, "charge", 0)
	item["is_charging"] = true
	return item
}

// currentChargeMapFromStatus preserves the complete live Fleet snapshot. It
// deliberately emits a single observed point only when the provider supplied
// an electrical field; no nominal voltage/current/curve is inferred.
func currentChargeMapFromStatus(vehicleID int, status vehicleStatus) map[string]any {
	start := status.ObservedAt
	if start.IsZero() {
		start = time.Now().UTC()
	}
	source := status.Source
	if source == "" {
		source = "fleet_api"
	}
	item := currentChargeMap(telemetrySession{
		PublicID: vehicleID, Kind: "charge", StartAt: start, Source: source,
		QualityState: "observed", QualityReason: "live_provider_snapshot", EnergyAdded: status.ChargeEnergyAdded,
	})
	mergeCurrentChargeStatus(item, status)
	return item
}

func mergeCurrentChargeStatus(item map[string]any, status vehicleStatus) {
	item["battery_details"] = map[string]any{
		"current_battery_level": status.BatteryLevel,
	}
	if status.ChargeEnergyAdded != nil {
		item["charge_energy_added"] = status.ChargeEnergyAdded
	}
	if status.ChargingState != nil {
		item["is_charging"] = isChargingState(status.ChargingState)
	}
	item["range_ideal"] = map[string]any{"end_range": status.IdealBatteryRange}
	item["range_rated"] = map[string]any{"end_range": status.RatedBatteryRange}
	item["latitude"], item["longitude"] = status.Latitude, status.Longitude
	item["odometer"] = status.Odometer
	item["charge_limit_soc"] = status.ChargeLimitSOC
	item["time_to_full_charge"] = status.TimeToFullCharge
	item["charger_phases"] = status.ChargerPhases
	item["charger_power"] = status.ChargerPower
	item["charger_voltage"] = status.ChargerVoltage
	item["charger_actual_current"] = status.ChargerActualCurrent
	item["charge_current_request"] = status.ChargeCurrentRequest
	item["charge_current_request_max"] = status.ChargeCurrentRequestMax
	item["ac_charging_energy_in"] = status.ACChargingEnergyIn
	item["dc_charging_energy_in"] = status.DCChargingEnergyIn
	item["ac_charging_power"] = status.ACChargingPower
	item["dc_charging_power"] = status.DCChargingPower
	item["charge_amps"] = status.ChargeAmps
	item["fast_charger_present"] = status.FastChargerPresent
	item["pack_voltage"] = status.PackVoltage
	item["pack_current"] = status.PackCurrent
	item["observed_at"] = status.ObservedAt.UTC().Format(time.RFC3339)

	point := map[string]any{"date": status.ObservedAt.UTC().Format(time.RFC3339), "battery_level": status.BatteryLevel, "charge_energy_added": status.ChargeEnergyAdded}
	point["outside_temp"] = status.OutsideTemp
	point["charger_details"] = map[string]any{
		"charger_power": status.ChargerPower, "charger_voltage": status.ChargerVoltage,
		"charger_actual_current": status.ChargerActualCurrent, "charger_phases": status.ChargerPhases,
		"fast_charger_present": status.FastChargerPresent,
	}
	if status.ChargerPower != nil || status.ChargerVoltage != nil || status.ChargerActualCurrent != nil || status.ChargerPhases != nil || status.ChargeEnergyAdded != nil {
		item["charge_details"] = []map[string]any{point}
	} else {
		item["charge_details"] = []map[string]any{}
	}
}

func isChargingState(value *string) bool {
	if value == nil {
		return false
	}
	switch strings.ToLower(strings.TrimSpace(*value)) {
	case "charging", "starting", "charging_started":
		return true
	default:
		return false
	}
}

func paginateTelemetryHistory(items []map[string]any, r *http.Request) ([]map[string]any, int, int) {
	page := positiveQueryInt(r, "page", 1)
	show := positiveQueryInt(r, "show", len(items))
	start := (page - 1) * show
	if start >= len(items) {
		return []map[string]any{}, page, show
	}
	end := start + show
	if end > len(items) {
		end = len(items)
	}
	return items[start:end], page, show
}

func pageCount(total, show int) int {
	if total == 0 || show <= 0 {
		return 0
	}
	return (total + show - 1) / show
}

func (a *app) currentVehicleStatus(ctx context.Context, userID string, vehicleID int) (vehicleStatus, error) {
	base, providerErr := a.provider.Status(ctx, userID, vehicleID)
	if providerErr == nil {
		base.FieldSources = fieldSourcesForStatus(base)
	}
	if a.telemetry != nil {
		if ref, ok := a.telemetry.vehicleRef(ctx, userID, vehicleID); ok {
			snapshot, exists, err := a.telemetry.latest(ctx, userID, vehicleID)
			if err == nil && exists {
				if providerErr != nil {
					base = vehicleStatus{DisplayName: ref.DisplayName, State: "unknown", Source: "telemetry_mqtt", ProviderIdentity: ref.ProviderVehicleID}
				}
				merged := mergeFreshTelemetryStatus(base, snapshot, time.Now().UTC())
				if !merged.ObservedAt.IsZero() {
					merged.FieldSources = mergeStringMaps(base.FieldSources, snapshot.FieldSources)
					return merged, nil
				}
			}
		}
	}
	return base, providerErr
}

func cloneStringMap(values map[string]string) map[string]string {
	if len(values) == 0 {
		return nil
	}
	copyValues := make(map[string]string, len(values))
	for key, value := range values {
		copyValues[key] = value
	}
	return copyValues
}

func mergeStringMaps(first, second map[string]string) map[string]string {
	merged := cloneStringMap(first)
	if merged == nil {
		merged = make(map[string]string)
	}
	for key, value := range second {
		merged[key] = value
	}
	if len(merged) == 0 {
		return nil
	}
	return merged
}

func fieldSourcesForStatus(status vehicleStatus) map[string]string {
	source := status.Source
	if source == "" {
		return nil
	}
	values := map[string]any{
		"odometer": status.Odometer, "battery_level": status.BatteryLevel, "usable_battery_level": status.UsableBatteryLevel,
		"estimated_battery_range": status.EstimatedBatteryRange, "rated_battery_range": status.RatedBatteryRange,
		"ideal_battery_range": status.IdealBatteryRange, "charging_state": status.ChargingState,
		"charge_energy_added": status.ChargeEnergyAdded, "charger_power": status.ChargerPower,
		"charger_actual_current": status.ChargerActualCurrent, "charger_phases": status.ChargerPhases,
		"charger_voltage": status.ChargerVoltage, "latitude": status.Latitude, "longitude": status.Longitude,
		"speed": status.Speed, "heading": status.Heading, "shift_state": status.ShiftState,
	}
	result := make(map[string]string)
	for field, value := range values {
		if value != nil {
			result[field] = source
		}
	}
	return result
}

func (s *telemetryService) vehicleRef(ctx context.Context, userID string, vehicleID int) (telemetryVehicleRef, bool) {
	if s == nil {
		return telemetryVehicleRef{}, false
	}
	if s.memory != nil {
		s.memory.mu.Lock()
		defer s.memory.mu.Unlock()
		for _, refs := range s.memory.vehicles {
			for _, ref := range refs {
				if ref.UserID == userID && ref.VehicleID == vehicleID {
					return ref, true
				}
			}
		}
		return telemetryVehicleRef{}, false
	}
	if s.store == nil || s.store.pool == nil {
		return telemetryVehicleRef{}, false
	}
	var ref telemetryVehicleRef
	err := s.store.pool.QueryRow(ctx, `SELECT tk.user_id, tk.vehicle_id, tk.vin_hash, v.provider_vehicle_id, v.display_name FROM jourvolt_telemetry_vehicle_keys tk JOIN jourvolt_vehicles v ON v.id=tk.vehicle_id WHERE tk.user_id=$1 AND tk.vehicle_id=$2`, userID, vehicleID).Scan(&ref.UserID, &ref.VehicleID, &ref.VINHash, &ref.ProviderVehicleID, &ref.DisplayName)
	return ref, err == nil
}

func (a *app) telemetryReadiness(ctx context.Context, userID string, vehicleID int) dataReadinessItem {
	if a.telemetry == nil || a.telemetry.config == nil {
		return telemetryReadinessItem("pairing_required")
	}
	if snapshot, exists, err := a.telemetry.latest(ctx, userID, vehicleID); err == nil && exists && !snapshot.ObservedAt.Before(time.Now().UTC().Add(-telemetryStaleAfter)) {
		return telemetryReadinessItem("available")
	}
	pairing, err := a.telemetry.pairing(ctx, userID, vehicleID)
	if err != nil {
		return telemetryReadinessItem("telemetry_error")
	}
	return telemetryReadinessItem(pairing.Status)
}

func (a *app) telemetryReadinessForData(ctx context.Context, userID string, vehicleID int) dataReadinessItem {
	item := a.telemetryReadiness(ctx, userID, vehicleID)
	if item.Status == "" {
		item.Status = "telemetry_error"
	}
	return item
}
