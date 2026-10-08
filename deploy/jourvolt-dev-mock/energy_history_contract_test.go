package main

import (
    "testing"
    "time"
)

func TestEnergyCounterInterleavedACNeverOverridesBatteryInput(t *testing.T) {
    start := time.Date(2026,10,8,4,0,0,0,time.UTC)
    machine := newTelemetrySessionMachine(20*time.Second)
    add := func(field string, value any, when time.Time, id string) {
        machine.apply(telemetrySessionEvent{FieldName:field,Value:value,ObservedAt:when,EventID:id})
    }
    add("DetailedChargeState","charging",start,"open")
    add("ACChargingEnergyIn",40.0,start.Add(time.Second),"ac1")
    add("DCChargingEnergyIn",10.0,start.Add(time.Second),"dc1")
    add("ACChargingEnergyIn",50.0,start.Add(2*time.Second),"ac2")
    add("DCChargingEnergyIn",17.0,start.Add(2*time.Second),"dc2")
    add("DetailedChargeState","complete",start.Add(3*time.Second),"close")
    sessions:=machine.completedSessions()
    if len(sessions)!=1 || sessions[0].EnergyAdded==nil || *sessions[0].EnergyAdded!=7 {
        t.Fatalf("battery-side delta must remain 7 despite 10 kWh AC charger input: %#v",sessions)
    }
    result:=historySessionMap(sessions[0],"charge",0)
    contract,ok:=result["energy_contract"].(map[string]any)
    if !ok {t.Fatal("missing energy contract")}
    battery:=contract["battery_input"].(map[string]any)
    ac:=contract["ac_input"].(map[string]any)
    if battery["quality"]!="unknown" || ac["quality"]!="unknown" ||
        battery["covered_energy_kwh"]!=7.0 || ac["covered_energy_kwh"]!=10.0 ||
        result["charge_energy_used"]!=nil || contract["ac_efficiency"]!=nil {
        t.Fatalf("interior intervals cannot claim whole-session counters: %#v",result)
    }
    if result["charge_type"]!="ac" {t.Fatal("qualified observed AC type unavailable")}
}

func TestEnergyCounterResetInvalidatesWholeBatterySession(t *testing.T) {
    start:=time.Date(2026,10,8,5,0,0,0,time.UTC)
    machine:=newTelemetrySessionMachine(20*time.Second)
    add:=func(v float64, offset int, id string) {
        machine.apply(telemetrySessionEvent{FieldName:"DCChargingEnergyIn",Value:v,
            ObservedAt:start.Add(time.Duration(offset)*time.Second),EventID:id})
    }
    machine.apply(telemetrySessionEvent{FieldName:"DetailedChargeState",Value:"charging",ObservedAt:start,EventID:"start"})
    add(70,1,"a");add(72,2,"b");add(2,3,"reset");add(5,4,"after")
    machine.apply(telemetrySessionEvent{FieldName:"DetailedChargeState",Value:"complete",ObservedAt:start.Add(5*time.Second),EventID:"end"})
    session:=machine.completedSessions()[0]
    if session.EnergyAdded!=nil {t.Fatal("reset after positive delta must clear stale energy")}
    contract:=completedSessionEnergyContract(session)
    if contract["battery_input"].(map[string]any)["quality"]!="unknown" {
        t.Fatalf("reset accepted: %#v",contract)
    }
}

func TestEnergyCounterCompleteACWindowAndScope(t *testing.T) {
    start:=time.Date(2026,10,8,6,0,0,0,time.UTC)
    end:=start.Add(10*time.Minute)
    startAC,endAC:=100.0,110.0
    startDC,endDC:=20.0,29.0
    session:=telemetrySession{
        Kind:"charge",Source:"telemetry_mqtt",StartAt:start,EndAt:&end,
        ChargePoints:[]telemetryChargePoint{
            {ObservedAt:start, ACInputCounter:&startAC, BatteryCounter:&startDC},
            {ObservedAt:end, ACInputCounter:&endAC, BatteryCounter:&endDC},
        },
    }
    c:=completedSessionEnergyContract(session)
    if c["battery_input"].(map[string]any)["value_kwh"]!=9.0 ||
        c["ac_input"].(map[string]any)["value_kwh"]!=10.0 ||
        c["ac_efficiency"]!=90.0 {
        t.Fatalf("matched complete AC interval not qualified: %#v",c)
    }
    if c["ac_loss"].(map[string]any)["value_kwh"]!=1.0 {t.Fatal("AC loss wrong")}
    session.Kind="drive"
    if completedSessionEnergyContract(session)["net_energy"].(map[string]any)["quality"]!="unknown" {
        t.Fatal("battery input misrepresented as driving net energy")
    }
    session.Source="teslamate_archive"
    if completedSessionEnergyContract(session)!=nil {t.Fatal("archive must not masquerade as fleet measurement")}
}

func TestEnergyCounterSubsetAndReplayRejectFullCoverage(t *testing.T) {
    start:=time.Date(2026,10,8,7,0,0,0,time.UTC)
    end:=start.Add(time.Minute)
    a,b:=10.0,12.0
    session:=telemetrySession{Kind:"charge",Source:"telemetry_mqtt",StartAt:start,EndAt:&end,
      ChargePoints:[]telemetryChargePoint{{ObservedAt:start.Add(time.Second),BatteryCounter:&a},
        {ObservedAt:end.Add(-time.Second),BatteryCounter:&b}}}
    c:=completedSessionEnergyContract(session)
    if c["battery_input"].(map[string]any)["value_kwh"]!=nil {t.Fatal("partial counter published")}
    session.ChargePoints=append(session.ChargePoints,telemetryChargePoint{
        ObservedAt:end.Add(-time.Second),BatteryCounter:&b})
    c=completedSessionEnergyContract(session)
    if c["battery_input"].(map[string]any)["reason"]!="counter_replay_or_reset" {t.Fatal("same timestamp replay accepted")}
}
