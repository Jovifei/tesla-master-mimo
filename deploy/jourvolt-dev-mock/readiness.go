package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
)

const dataReadinessCapabilityVersion = 1

type dataReadinessItem struct {
	Key            string  `json:"key"`
	Status         string  `json:"status"`
	Source         string  `json:"source"`
	LastObservedAt *string `json:"last_observed_at,omitempty"`
	MessageKey     string  `json:"message_key,omitempty"`
	Action         string  `json:"action,omitempty"`
}

type dataReadinessResponse struct {
	CapabilityVersion int                 `json:"capability_version"`
	VehicleUID        string              `json:"vehicle_uid"`
	Items             []dataReadinessItem `json:"items"`
}

func (a *app) dataReadiness(w http.ResponseWriter, r *http.Request, userID string, vehicleID int) {
	providerStatus, err := a.currentVehicleStatus(r.Context(), userID, vehicleID)
	source := providerSource(a, userID, providerStatus.Source)
	vehicleUID := ""
	if err == nil {
		vehicleUID, err = a.stableVehicleUID(r.Context(), userID, vehicleID, providerStatus.ProviderIdentity)
		if err != nil {
			a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "readiness_unavailable"})
			return
		}
	}
	response := dataReadinessResponse{
		CapabilityVersion: dataReadinessCapabilityVersion,
		VehicleUID:        vehicleUID,
		Items:             readinessItemsWithHistory(providerStatus, source, err, a.hasMockHistory(userID) || (a.telemetry != nil && a.telemetry.hasHistory(r.Context(), userID, vehicleID))),
	}
	if a.telemetry != nil {
		response.Items = append(response.Items, a.telemetryReadinessForData(r.Context(), userID, vehicleID))
	}
	a.json(w, http.StatusOK, map[string]any{"data": response})
}

func readinessItems(status vehicleStatus, source string, providerErr error) []dataReadinessItem {
	return readinessItemsWithHistory(status, source, providerErr, false)
}

func readinessItemsWithHistory(status vehicleStatus, source string, providerErr error, historyAvailable bool) []dataReadinessItem {
	if providerErr != nil {
		statusValue, messageKey, action := readinessError(providerErr)
		return []dataReadinessItem{
			{Key: "live_status", Status: statusValue, Source: source, MessageKey: messageKey, Action: action},
			{Key: "location", Status: statusValue, Source: source, MessageKey: messageKey, Action: action},
			{Key: "tpms", Status: statusValue, Source: source, MessageKey: messageKey, Action: action},
			historyReadinessItemWithAvailability("drives", source, historyAvailable),
			historyReadinessItemWithAvailability("charges", source, historyAvailable),
			{Key: "battery_health", Status: "unsupported", Source: source, MessageKey: "battery_health_unsupported", Action: "not_available"},
		}
	}

	lastObservedAt := observedTimestamp(status.ObservedAt)
	locationStatus, locationMessage, locationAction := "available", "", ""
	if status.Latitude == nil || status.Longitude == nil {
		locationStatus, locationMessage, locationAction = "waiting_vehicle", "location_waiting_vehicle", "wake_vehicle"
	}
	tpmsStatus, tpmsMessage, tpmsAction := "available", "", ""
	if !hasTPMSObservation(status) {
		tpmsStatus, tpmsMessage, tpmsAction = "waiting_vehicle", "tpms_waiting_vehicle", "wake_vehicle"
	}
	return []dataReadinessItem{
		{Key: "live_status", Status: "available", Source: source, LastObservedAt: lastObservedAt},
		{Key: "location", Status: locationStatus, Source: source, LastObservedAt: capabilityTimestamp(locationStatus, lastObservedAt), MessageKey: locationMessage, Action: locationAction},
		{Key: "tpms", Status: tpmsStatus, Source: source, LastObservedAt: capabilityTimestamp(tpmsStatus, lastObservedAt), MessageKey: tpmsMessage, Action: tpmsAction},
		historyReadinessItemWithAvailability("drives", source, historyAvailable),
		historyReadinessItemWithAvailability("charges", source, historyAvailable),
		{Key: "battery_health", Status: "unsupported", Source: source, MessageKey: "battery_health_unsupported", Action: "not_available"},
	}
}

func capabilityTimestamp(status string, observedAt *string) *string {
	if status != "available" {
		return nil
	}
	return observedAt
}

func historyReadinessItem(key, source string) dataReadinessItem {
	return historyReadinessItemWithAvailability(key, source, false)
}

func historyReadinessItemWithAvailability(key, source string, available bool) dataReadinessItem {
	if available {
		return dataReadinessItem{Key: key, Status: "available", Source: source}
	}
	return dataReadinessItem{Key: key, Status: "collecting", Source: source, MessageKey: "history_collecting", Action: "keep_vehicle_connected"}
}

func hasTPMSObservation(status vehicleStatus) bool {
	return status.TPMSPressureFL != nil || status.TPMSPressureFR != nil || status.TPMSPressureRL != nil || status.TPMSPressureRR != nil ||
		status.TPMSSoftWarningFL != nil || status.TPMSSoftWarningFR != nil || status.TPMSSoftWarningRL != nil || status.TPMSSoftWarningRR != nil
}

func readinessError(err error) (status, messageKey, action string) {
	switch {
	case errors.Is(err, errNotConfigured):
		return "permission_required", "provider_permission_required", "configure_provider"
	case errors.Is(err, errTeslaReauthorization):
		return "pairing_required", "tesla_pairing_required", "pair_tesla"
	case errors.Is(err, errTeslaBillingBlocked):
		return "billing_blocked", "billing_blocked", "resolve_billing"
	case errors.Is(err, errTeslaRateLimited), errors.Is(err, errTeslaUnavailable):
		return "telemetry_error", "telemetry_error", "retry_later"
	default:
		return "telemetry_error", "telemetry_error", "retry_later"
	}
}

func observedTimestamp(value time.Time) *string {
	if value.IsZero() {
		return nil
	}
	formatted := value.UTC().Format(time.RFC3339)
	return &formatted
}

func providerSource(a *app, userID, source string) string {
	if source != "" {
		return source
	}
	if a.mockEnabled && userID == "mock-user" {
		return "mock_fixture"
	}
	return "fleet_api"
}

func (a *app) stableVehicleUID(ctx context.Context, userID string, vehicleID int, providerIdentity string) (string, error) {
	identity := strings.TrimSpace(providerIdentity)
	if identity == "" && a.store != nil && a.store.pool != nil {
		if err := a.store.pool.QueryRow(ctx, `
SELECT provider_vehicle_id FROM jourvolt_vehicles
		WHERE id=$1 AND user_id=$2`, vehicleID, userID).Scan(&identity); err != nil {
			return "", fmt.Errorf("vehicle identity lookup: %w", err)
		}
		if identity = strings.TrimSpace(identity); identity == "" {
			return "", errors.New("vehicle identity unavailable")
		}
	}
	if identity == "" {
		identity = strconv.Itoa(vehicleID)
	}
	material := fmt.Sprintf("jourvolt.vehicle.uid.v1\x00%s\x00%s", userID, identity)
	sum := sha256.Sum256([]byte(material))
	return hex.EncodeToString(sum[:]), nil
}
