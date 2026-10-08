package main

import (
    "math"
    "sort"
    "time"
)

// Physical measurement locations are not interchangeable: DCChargingEnergyIn
// is the battery input counter for AC or DC; ACChargingEnergyIn is AC charger
// input and is ignored in DC mode. These fields are not driving net energy.
func isFiniteChargeCounterDelta(v float64) bool {
    return v >= 0 && !math.IsNaN(v) && !math.IsInf(v, 0)
}

type chargeCounterObservation struct {
    observedAt time.Time
    value float64
}

func chargeSessionCounterMetric(session telemetrySession, field, point string) (map[string]any, *float64, bool) {
    result := map[string]any{
        "value_kwh": nil, "unit": "kWh", "method": "session_counter_delta",
        "measurement_point": point, "source": "telemetry_mqtt", "source_field": field,
        "quality": "unknown", "reason": "missing_counter_bounds",
        "start_date": session.StartAt.UTC().Format(time.RFC3339), "end_date": nil,
        "observed_start_at": nil, "observed_end_at": nil,
        "time_basis": "source_sample_time", "coverage_kind": "endpoints",
        "coverage_seconds": nil, "coverage_ratio": nil, "covered_energy_kwh": nil,
    }
    if session.EndAt != nil {
        result["end_date"] = session.EndAt.UTC().Format(time.RFC3339)
    }
    if session.Source != "telemetry_mqtt" || session.EndAt == nil || !session.EndAt.After(session.StartAt) {
        result["reason"] = "unverified_or_open_session"
        return result, nil, false
    }
    observations := make([]chargeCounterObservation, 0, len(session.ChargePoints))
    for _, item := range session.ChargePoints {
        var counter *float64
        if field == "DCChargingEnergyIn" {
            counter = item.BatteryCounter
        } else if field == "ACChargingEnergyIn" {
            counter = item.ACInputCounter
        }
        if counter == nil {
            continue
        }
        if !isFiniteChargeCounterDelta(*counter) || item.ObservedAt.Before(session.StartAt) || item.ObservedAt.After(*session.EndAt) {
            result["reason"] = "invalid_counter_or_time"
            return result, nil, false
        }
        observations = append(observations, chargeCounterObservation{item.ObservedAt, *counter})
    }
    if len(observations) < 2 {
        return result, nil, false
    }
    sort.SliceStable(observations, func(i, j int) bool {return observations[i].observedAt.Before(observations[j].observedAt)})
    for i:=1; i<len(observations); i++ {
        if !observations[i].observedAt.After(observations[i-1].observedAt) ||
            observations[i].value < observations[i-1].value {
            result["reason"] = "counter_replay_or_reset"
            return result, nil, false
        }
    }
    first,last := observations[0],observations[len(observations)-1]
    delta := last.value-first.value
    if !isFiniteChargeCounterDelta(delta) {
        result["reason"] = "non_finite_counter_delta"
        return result, nil, false
    }
    value := delta
    result["observed_start_at"] = first.observedAt.UTC().Format(time.RFC3339)
    result["observed_end_at"] = last.observedAt.UTC().Format(time.RFC3339)
    result["covered_energy_kwh"] = delta
    result["coverage_seconds"] = last.observedAt.Sub(first.observedAt).Seconds()
    // An interior counter span does not prove a complete charging session.
    // Do not upgrade a covered subset by extrapolation or SOC-capacity guesses.
    if !first.observedAt.Equal(session.StartAt) || !last.observedAt.Equal(*session.EndAt) {
        result["reason"] = "partial_counter_window"
        return result, &value, false
    }
    result["value_kwh"] = delta
    result["quality"] = "reported"
    result["reason"] = nil
    result["coverage_ratio"] = 1.0
    return result, &value, true
}

func completedSessionEnergyContract(session telemetrySession) map[string]any {
    if session.Source != "telemetry_mqtt" {
        return nil
    }
    if session.Kind == "drive" {
        return map[string]any{"version": 1, "net_energy": map[string]any{
            "value_kwh": nil, "unit": "kWh", "source": "telemetry_mqtt",
            "quality": "unknown", "reason": "no_verified_driving_net_energy",
        }}
    }
    if session.Kind != "charge" {
        return nil
    }
    battery, batteryCovered, batteryOK := chargeSessionCounterMetric(session, "DCChargingEnergyIn", "battery_input")
    ac, acCovered, acOK := chargeSessionCounterMetric(session, "ACChargingEnergyIn", "ac_charger_input")
    contract := map[string]any{"version":1, "battery_input":battery, "ac_input":ac}
    if batteryOK && acOK && batteryCovered != nil && acCovered != nil && *acCovered > 0 && *batteryCovered <= *acCovered {
        loss := *acCovered-*batteryCovered
        if math.IsNaN(loss) || math.IsInf(loss,0) {
            return contract
        }
        contract["ac_loss"] = map[string]any{
            "value_kwh":loss, "unit":"kWh", "method":"compatible_ac_balance",
            "measurement_point":"ac_to_battery", "source":"telemetry_mqtt",
            "quality":"estimated", "reason":nil,
            "start_date":battery["start_date"], "end_date":battery["end_date"],
            "observed_start_at":battery["observed_start_at"], "observed_end_at":battery["observed_end_at"],
            "coverage_kind":"endpoints", "coverage_ratio":1.0, "time_basis":"source_sample_time",
        }
        contract["ac_efficiency"] = *batteryCovered / *acCovered * 100.0
    }
    return contract
}

func publishChargeEnergyContract(result map[string]any, contract map[string]any) {
    if contract == nil { return }
    result["energy_contract"] = contract
    // A covered subset is diagnostic, never the entire finished session.
    result["charge_energy_added"], result["charge_energy_used"] = nil, nil
    if battery, ok:=contract["battery_input"].(map[string]any); ok &&
        battery["quality"]=="reported" && battery["measurement_point"]=="battery_input" {
        result["charge_energy_added"]=battery["value_kwh"]
    }
    if ac, ok:=contract["ac_input"].(map[string]any); ok &&
        ac["quality"]=="reported" && ac["measurement_point"]=="ac_charger_input" {
        result["charge_energy_used"]=ac["value_kwh"]
    }
    if ac,ok:=contract["ac_input"].(map[string]any); ok && ac["covered_energy_kwh"]!=nil {
        result["charge_type"]="ac"
    }
}
