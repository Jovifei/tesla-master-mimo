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
	items = paginateTelemetryHistory(items, r)
	plural := kind + "s"
	a.json(w, http.StatusOK, map[string]any{"data": map[string]any{plural: items, "meta": meta}})
}

func (a *app) currentCharge(w http.ResponseWriter, r *http.Request, userID string, vehicleID int) {
	if a.telemetry != nil {
		if session, ok, err := a.telemetry.openSession(r.Context(), userID, vehicleID, "charge"); err == nil && ok {
			a.json(w, http.StatusOK, map[string]any{"data": map[string]any{"car": a.currentChargeCar(a.telemetry, r.Context(), userID, vehicleID), "charge": currentChargeMap(session)}})
			return
		}
	}
	status, err := a.provider.Status(r.Context(), userID, vehicleID)
	if err == nil && isChargingState(status.ChargingState) {
		a.json(w, http.StatusOK, map[string]any{"data": map[string]any{"car": map[string]any{"car_id": vehicleID}, "charge": currentChargeMap(telemetrySession{PublicID: vehicleID, Kind: "charge", StartAt: status.ObservedAt})}})
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

func paginateTelemetryHistory(items []map[string]any, r *http.Request) []map[string]any {
	page := positiveQueryInt(r, "page", 1)
	show := positiveQueryInt(r, "show", len(items))
	start := (page - 1) * show
	if start >= len(items) {
		return []map[string]any{}
	}
	end := start + show
	if end > len(items) {
		end = len(items)
	}
	return items[start:end]
}

func (a *app) currentVehicleStatus(ctx context.Context, userID string, vehicleID int) (vehicleStatus, error) {
	if a.telemetry != nil {
		if ref, ok := a.telemetry.vehicleRef(ctx, userID, vehicleID); ok {
			snapshot, exists, err := a.telemetry.latest(ctx, userID, vehicleID)
			if err == nil && exists {
				base := vehicleStatus{DisplayName: ref.DisplayName, State: "unknown", Source: "telemetry_mqtt", ProviderIdentity: ref.ProviderVehicleID}
				merged := mergeFreshTelemetryStatus(base, snapshot, time.Now().UTC())
				if merged.Source == "telemetry_mqtt" && (!merged.ObservedAt.IsZero()) {
					return merged, nil
				}
			}
		}
	}
	return a.provider.Status(ctx, userID, vehicleID)
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
