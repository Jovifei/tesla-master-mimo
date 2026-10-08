package main

import (
    "testing"
    "time"
)

func TestIndependentCounterResetAtFinalTimestampMustRemainUnknown(t *testing.T) {
    start := time.Date(2026,10,9,0,0,0,0,time.UTC)
    end := start.Add(time.Minute)
    machine := newTelemetrySessionMachine(20*time.Second)
    add := func(field string, value any, at time.Time, id string) {
        machine.apply(telemetrySessionEvent{FieldName:field,Value:value,ObservedAt:at,EventID:id})
    }
    add("DetailedChargeState","charging",start,"open")
    add("DCChargingEnergyIn",100.0,start,"first")
    add("DCChargingEnergyIn",102.0,end,"last-before-reset")
    add("DCChargingEnergyIn",1.0,end,"reset-same-time")
    add("DetailedChargeState","complete",end,"close")
    sessions := machine.completedSessions()
    if len(sessions) != 1 { t.Fatalf("sessions=%d",len(sessions)) }
    metric := completedSessionEnergyContract(sessions[0])["battery_input"].(map[string]any)
    if metric["quality"] == "reported" || metric["value_kwh"] != nil {
        t.Fatalf("reset must not restore the stale pre-reset counter delta: %#v",metric)
    }
}

func TestIndependentZeroACCounterDoesNotProveACMode(t *testing.T) {
    start := time.Date(2026,10,9,1,0,0,0,time.UTC)
    end := start.Add(time.Minute)
    ac, dc0, dc1 := 0.0, 0.0, 8.0
    session := telemetrySession{Kind:"charge",Source:"telemetry_mqtt",StartAt:start,EndAt:&end,
        ChargePoints:[]telemetryChargePoint{
            {ObservedAt:start,ACInputCounter:&ac,BatteryCounter:&dc0},
            {ObservedAt:end,ACInputCounter:&ac,BatteryCounter:&dc1},
        }}
    result := map[string]any{}
    publishChargeEnergyContract(result,completedSessionEnergyContract(session))
    if result["charge_type"] == "ac" { t.Fatal("AC zero endpoint data is not an observed AC charging mode") }
}

func TestIndependentUnknownModeCannotPublishACBalance(t *testing.T) {
    start := time.Date(2026,10,9,2,0,0,0,time.UTC)
    end := start.Add(time.Minute)
    ac0, ac1, dc0, dc1 := 0.0, 10.0, 0.0, 9.0
    session := telemetrySession{Kind:"charge",Source:"telemetry_mqtt",StartAt:start,EndAt:&end,
        ChargePoints:[]telemetryChargePoint{
            {ObservedAt:start,ACInputCounter:&ac0,BatteryCounter:&dc0},
            {ObservedAt:end,ACInputCounter:&ac1,BatteryCounter:&dc1},
        }}
    contract := completedSessionEnergyContract(session)
    if contract["ac_loss"] != nil || contract["ac_efficiency"] != nil {
        t.Fatal("counter arithmetic cannot prove the entire observed window was AC rather than mixed AC/DC")
    }
}
