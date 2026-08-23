package main

import (
	"net/http"
	"strconv"
	"time"
)

func (a *app) hasMockHistory(userID string) bool {
	return a.mockEnabled && a.mockHistoryEnabled && userID == "mock-user"
}

func mockDriveFixtures() []map[string]any {
	base := time.Date(2026, time.May, 1, 8, 0, 0, 0, time.UTC)
	items := make([]map[string]any, 0, 18)
	odometer := 42000.0
	for index := 0; index < 6; index++ {
		for group, sample := range []struct {
			distance, energy, speed, temperature float64
			duration                             int
		}{
			{distance: 25, energy: 4.5, speed: 70, temperature: 20, duration: 22},
			{distance: 25, energy: 5.75, speed: 105, temperature: 20, duration: 15},
			{distance: 20, energy: 4.8, speed: 65, temperature: 0, duration: 24},
		} {
			id := 1001 + index*3 + group
			start := base.AddDate(0, 0, index*10+group*3)
			end := start.Add(time.Duration(sample.duration) * time.Minute)
			items = append(items, map[string]any{
				"drive_id": id, "start_date": start.Format(time.RFC3339), "end_date": end.Format(time.RFC3339),
				"start_address": "Mock development route", "end_address": "Mock development destination",
				"odometer_details": map[string]any{"odometer_start": odometer, "odometer_end": odometer + sample.distance, "odometer_distance": sample.distance},
				"duration_min":     sample.duration, "duration_str": strconv.Itoa(sample.duration) + " min",
				"speed_max": int(sample.speed + 18), "speed_avg": sample.speed,
				"power_max": 72, "power_min": -38,
				"battery_details":  map[string]any{"start_battery_level": 80, "end_battery_level": 72, "is_range_ideal": false},
				"outside_temp_avg": sample.temperature, "inside_temp_avg": 21.0,
				"energy_consumed_net": sample.energy, "consumption_net": sample.energy * 1000 / sample.distance,
			})
			odometer += sample.distance
		}
	}
	return items
}

func mockChargeFixtures() []map[string]any {
	base := time.Date(2026, time.May, 2, 20, 0, 0, 0, time.UTC)
	items := make([]map[string]any, 0, 5)
	for index := 0; index < 5; index++ {
		id := 2001 + index
		start := base.AddDate(0, 0, index*12)
		end := start.Add(75 * time.Minute)
		items = append(items, map[string]any{
			"charge_id": id, "start_date": start.Format(time.RFC3339), "end_date": end.Format(time.RFC3339),
			"address": "Mock development charger", "charge_energy_added": 11.5, "charge_energy_used": 14.0,
			"cost": 8.5, "duration_min": 75, "duration_str": "75 min",
			"battery_details":  map[string]any{"start_battery_level": 45, "end_battery_level": 68},
			"outside_temp_avg": 18.0, "odometer": 42000.0 + float64(index)*84.0,
			"latitude": 31.2304, "longitude": 121.4737,
		})
	}
	return items
}

func paginateFixture(items []map[string]any, r *http.Request) []map[string]any {
	page := positiveQueryInt(r, "page", 1)
	show := positiveQueryInt(r, "show", len(items))
	start := (page - 1) * show
	if start >= len(items) {
		return []map[string]any{}
	}
	end := min(start+show, len(items))
	return items[start:end]
}

func positiveQueryInt(r *http.Request, name string, fallback int) int {
	value, err := strconv.Atoi(r.URL.Query().Get(name))
	if err != nil || value <= 0 {
		return fallback
	}
	return value
}

func (a *app) mockDriveDetail(w http.ResponseWriter, rawID string) {
	id, err := strconv.Atoi(rawID)
	if err != nil {
		a.json(w, http.StatusNotFound, map[string]string{"error": "drive_not_found"})
		return
	}
	for _, drive := range mockDriveFixtures() {
		if drive["drive_id"] != id {
			continue
		}
		detail := cloneFixture(drive)
		start := detail["start_date"].(string)
		detail["drive_details"] = []map[string]any{
			{"date": start, "latitude": 31.2304, "longitude": 121.4737, "speed": 10, "power": 18, "elevation": 8, "climate_info": map[string]any{"inside_temp": 21.0, "outside_temp": detail["outside_temp_avg"], "is_climate_on": true}},
			{"date": start, "latitude": 31.2404, "longitude": 121.4837, "speed": detail["speed_avg"], "power": 52, "elevation": 36, "climate_info": map[string]any{"inside_temp": 22.0, "outside_temp": detail["outside_temp_avg"], "is_climate_on": true}},
			{"date": detail["end_date"], "latitude": 31.2504, "longitude": 121.4937, "speed": 0, "power": -22, "elevation": 18, "climate_info": map[string]any{"inside_temp": 21.5, "outside_temp": detail["outside_temp_avg"], "is_climate_on": false}},
		}
		a.json(w, http.StatusOK, map[string]any{"data": map[string]any{"car": map[string]any{"car_id": 1, "car_name": "Development Model 3"}, "drive": detail}})
		return
	}
	a.json(w, http.StatusNotFound, map[string]string{"error": "drive_not_found"})
}

func (a *app) mockChargeDetail(w http.ResponseWriter, rawID string) {
	id, err := strconv.Atoi(rawID)
	if err != nil {
		a.json(w, http.StatusNotFound, map[string]string{"error": "charge_not_found"})
		return
	}
	for index, charge := range mockChargeFixtures() {
		if charge["charge_id"] != id {
			continue
		}
		detail := cloneFixture(charge)
		isFast := index%2 == 1
		power, phases := 11, 3
		if isFast {
			power, phases = 120, 0
		}
		detail["charge_details"] = []map[string]any{
			{"date": detail["start_date"], "battery_level": 45, "charge_energy_added": 0.0, "outside_temp": 18.0, "charger_details": map[string]any{"charger_power": power, "charger_voltage": 400, "charger_actual_current": 16, "charger_phases": phases, "fast_charger_present": isFast, "fast_charger_brand": "Mock", "fast_charger_type": "Development"}},
			{"date": detail["end_date"], "battery_level": 68, "charge_energy_added": 11.5, "outside_temp": 17.5, "charger_details": map[string]any{"charger_power": power, "charger_voltage": 400, "charger_actual_current": 16, "charger_phases": phases, "fast_charger_present": isFast, "fast_charger_brand": "Mock", "fast_charger_type": "Development"}},
		}
		a.json(w, http.StatusOK, map[string]any{"data": map[string]any{"car": map[string]any{"car_id": 1, "car_name": "Development Model 3"}, "charge": detail}})
		return
	}
	a.json(w, http.StatusNotFound, map[string]string{"error": "charge_not_found"})
}

func cloneFixture(source map[string]any) map[string]any {
	clone := make(map[string]any, len(source)+1)
	for key, value := range source {
		clone[key] = value
	}
	return clone
}
