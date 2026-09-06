package main

import (
	"testing"
	"time"
)

func TestCurrentChargeMapPreservesLiveElectricalFields(t *testing.T) {
	observed := time.Date(2026, 9, 6, 8, 0, 0, 0, time.UTC)
	charging := "charging"
	power := 11.5
	current := 16.0
	status := vehicleStatus{
		ObservedAt: observed, Source: "fleet_api", ChargingState: &charging,
		BatteryLevel: intPointer(54), ChargeEnergyAdded: floatPointer(3.2),
		ACChargingPower: &power, ChargeAmps: &current, ChargerPhases: intPointer(3),
	}
	item := currentChargeMapFromStatus(7, status)
	if item["source"] != "fleet_api" || item["is_charging"] != true {
		t.Fatalf("current charge identity = %#v", item)
	}
	if item["charge_energy_added"] == nil || item["ac_charging_power"] == nil || item["charge_amps"] == nil {
		t.Fatalf("live electrical fields were dropped: %#v", item)
	}
	points, ok := item["charge_details"].([]map[string]any)
	if !ok || len(points) != 1 {
		t.Fatalf("observed point = %#v", item["charge_details"])
	}
}
