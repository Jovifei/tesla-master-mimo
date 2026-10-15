package main

import (
	"context"
	"encoding/json"
	"os"
	"testing"
	"time"
)

// Shared with the actual Android resolver and Mini page/API/cache tests.
// Live receipts never manufacture persisted history or collector evidence.
func TestSafetySharedReceiptsAndActualScopedHistory(t *testing.T) {
	raw, err := os.ReadFile("../../fixtures/safety-stage-v1.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixtures struct {
		Version int `json:"version"`
		Cases   []struct {
			ID  string          `json:"id"`
			Raw json.RawMessage `json:"rawStatus"`
		} `json:"readiness_cases"`
	}
	if err = json.Unmarshal(raw, &fixtures); err != nil || fixtures.Version != 1 || len(fixtures.Cases) != 8 {
		t.Fatal("shared scenario set missing")
	}
	db := boundedHistoryTestStore(t)
	user, car := boundedHistoryTestTenant(t, db, "safety-shared")
	other, _ := boundedHistoryTestTenant(t, db, "safety-other")
	service := &telemetryService{store: db}
	for _, scenario := range fixtures.Cases {
		t.Run(scenario.ID, func(t *testing.T) {
			var input struct {
				ObservedAt time.Time `json:"observed_at"`
				Pressure   *float64  `json:"tpms_pressure_fl"`
				State      string    `json:"state"`
			}
			if len(scenario.Raw) > 0 && string(scenario.Raw) != "null" {
				if err := json.Unmarshal(scenario.Raw, &input); err != nil {
					t.Fatal(err)
				}
			}
			status := vehicleStatus{ObservedAt: input.ObservedAt, TPMSPressureFL: input.Pressure, State: input.State}
			items := readinessItemsWithHistory(status, "fleet_api", nil, false, false)
			response := dataReadinessResponse{CapabilityVersion: 1, VehicleUID: "synthetic-car-a", Items: items}
			wire, err := json.Marshal(response)
			if err != nil || !json.Valid(wire) {
				t.Fatal("actual response serialization failed")
			}
			for _, item := range items {
				if (item.Key == "drives" || item.Key == "charges") && item.Status == "available" {
					t.Fatal("live data promoted history")
				}
			}
			if _, ok := tpmsPressureEvidence(status)["time_basis"]; ok {
				t.Fatal("Fleet status became collector proof")
			}
			rows, _, total, _, err := service.historyPageContext(context.Background(), user, car, "drive", time.Time{}, time.Time{}, 1, 1)
			if err != nil || total != 0 || len(rows) != 0 {
				t.Fatal("live status created history")
			}
		})
	}
	at := time.Date(2026, 10, 15, 0, 0, 0, 0, time.UTC)
	id := boundedHistoryInsertSession(t, db, user, car, "drive", at, "teslamate_archive", "observed", []historyImportRoutePoint{}, "synthetic-correction")
	for _, tenant := range []string{user, other} {
		rows, _, total, _, err := service.historyPageContext(context.Background(), tenant, car, "drive", at, at.Add(time.Hour), 1, 1)
		if err != nil {
			t.Fatal(err)
		}
		if tenant == user {
			if total != 1 || len(rows) != 1 || rows[0]["drive_id"] != id {
				t.Fatal("actual history missing")
			}
		} else if total != 0 || len(rows) != 0 {
			t.Fatal("cross-account persistence leaked")
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, _, _, _, err := service.historyPageContext(ctx, user, car, "drive", at, at.Add(time.Hour), 1, 1); err == nil {
		t.Fatal("canceled consumer accepted")
	}
}
