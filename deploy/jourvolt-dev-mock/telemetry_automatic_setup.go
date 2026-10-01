package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"
)

func (s *telemetryService) refreshConfirmedVehicleKey(ctx context.Context, userID string, vehicleID int, current telemetryPairing) telemetryPairing {
	if current.Status != "pairing_required" || s.fleetAPIBase == "" {
		return current
	}
	key := telemetryAutoConfigureKey{userID: userID, vehicleID: vehicleID}
	now := time.Now()
	previous, loaded := s.keyChecks.LoadOrStore(key, now)
	if loaded && (now.Sub(previous.(time.Time)) < time.Minute || !s.keyChecks.CompareAndSwap(key, previous, now)) {
		return current
	}
	vin, err := s.vinForVehicle(ctx, userID, vehicleID)
	if err != nil {
		return current
	}
	body, _ := json.Marshal(map[string]any{"vins": []string{vin}})
	response, err := s.commandProxyRequest(ctx, userID, http.MethodPost, strings.TrimRight(s.fleetAPIBase, "/")+"/api/1/vehicles/fleet_status", body)
	if err != nil {
		return current
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return current
	}
	var envelope struct {
		Response struct {
			Paired *[]string `json:"key_paired_vins"`
		} `json:"response"`
	}
	if json.NewDecoder(io.LimitReader(response.Body, 64<<10)).Decode(&envelope) != nil || envelope.Response.Paired == nil {
		return current
	}
	for _, paired := range *envelope.Response.Paired {
		if paired == vin {
			s.setPairingStatus(ctx, userID, vehicleID, "key_confirmed")
			current.Status = "key_confirmed"
			current.UpdatedAt = time.Now().UTC()
			current.ErrorClass = ""
			return current
		}
	}
	return current
}

// Recover already-authorized vehicles independently of phone activity. The
// existing per-vehicle gate and persisted attempt time own configuration retries.
func (s *telemetryService) startAutomaticSetupRecovery(ctx context.Context) {
	if s == nil || s.store == nil || s.store.pool == nil || s.fleetAPIBase == "" || !s.recoveryStarted.CompareAndSwap(false, true) {
		return
	}
	go func() {
		ticker := time.NewTicker(time.Minute)
		defer ticker.Stop()
		cursor := 0
		for {
			s.recoverPendingSetups(ctx, &cursor)
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
			}
		}
	}()
}

func (s *telemetryService) recoverPendingSetups(ctx context.Context, cursor *int) {
	queryCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	rows, err := s.store.pool.Query(queryCtx, `SELECT p.user_id,p.vehicle_id FROM jourvolt_telemetry_pairing p
JOIN jourvolt_vehicles v ON v.id=p.vehicle_id AND v.user_id=p.user_id
JOIN jourvolt_tesla_tokens t ON t.user_id=p.user_id
WHERE p.vehicle_id>$1 AND COALESCE(p.config_synced,false)=false
AND p.status IN ('pairing_required','key_confirmed','telemetry_not_configured','telemetry_error','waiting_vehicle','configuring')
ORDER BY p.vehicle_id LIMIT 100`, *cursor)
	if err != nil {
		return
	}
	var vehicles []telemetryAutoConfigureKey
	for rows.Next() {
		var ref telemetryAutoConfigureKey
		if rows.Scan(&ref.userID, &ref.vehicleID) != nil {
			rows.Close()
			return
		}
		vehicles = append(vehicles, ref)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return
	}
	if len(vehicles) == 0 {
		*cursor = 0
		return
	}
	if len(vehicles) < 100 {
		*cursor = 0
	} else {
		*cursor = vehicles[len(vehicles)-1].vehicleID
	}
	var workers sync.WaitGroup
	slots := make(chan struct{}, 4)
	for _, ref := range vehicles {
		select {
		case <-ctx.Done():
			workers.Wait()
			return
		case slots <- struct{}{}:
		}
		workers.Add(1)
		go func(ref telemetryAutoConfigureKey) {
			defer workers.Done()
			defer func() { <-slots }()
			s.maybeAutoConfigure(ref.userID, ref.vehicleID)
		}(ref)
	}
	workers.Wait()
}
