package main

import("testing";"time")

func TestTPMSFieldScopedCollectorReceiptsAndNoGlobalTimestampPromotion(t *testing.T) {
  now:=time.Date(2026,10,11,1,0,0,0,time.UTC)
  fl,fr:=now.Add(-2*time.Second),now.Add(-time.Second)
  snapshot:=telemetrySnapshot{
    Fields:map[string]any{"TpmsPressureFl":2.7,"TpmsPressureFr":2.8,"Soc":80.0},
    FieldObservedAt:map[string]time.Time{"TpmsPressureFl":fl,"TpmsPressureFr":fr,"Soc":now},
    FieldSources:map[string]string{"TpmsPressureFl":"telemetry_mqtt","TpmsPressureFr":"telemetry_mqtt","Soc":"telemetry_mqtt"},
    ObservedAt:now,Source:"telemetry_mqtt",
  }
  merged:=mergeFreshTelemetryStatus(vehicleStatus{Source:"fleet_api"},snapshot,now)
  values:=tpmsPressureEvidence(merged)
  if values["tpms_pressure_fl_observed_at"]!=fl.Format(time.RFC3339Nano)||
    values["tpms_pressure_fr_observed_at"]!=fr.Format(time.RFC3339Nano)||
    values["pressure_unit"]!="bar"||values["source"]!="telemetry_mqtt"||
    values["time_basis"]!="collector_received_at" {t.Fatalf("incorrect per-wheel TPMS evidence: %v",values)}
  if _,ok:=values["tpms_pressure_rl_observed_at"];ok {t.Fatal("unseen wheel stamped")}
  latest:=mergeFreshTelemetryStatus(merged,telemetrySnapshot{
    Fields:map[string]any{"Soc":81.0},FieldObservedAt:map[string]time.Time{"Soc":now.Add(time.Second)},
    FieldSources:map[string]string{"Soc":"telemetry_mqtt"},ObservedAt:now.Add(time.Second),Source:"telemetry_mqtt",
  },now.Add(time.Second))
  if latest.TPMSFieldObservedAt["TpmsPressureFl"]!=fl {t.Fatal("SOC refresh rewrote wheel observation")}
}
func TestTPMSUnknownFleetTimestampImportAndStaleSnapshotFailClosed(t *testing.T){
 now:=time.Date(2026,10,11,1,0,0,0,time.UTC)
 v:=2.5
 base:=vehicleStatus{Source:"fleet_api",TPMSPressureFL:&v,ObservedAt:now}
 if got:=tpmsPressureEvidence(base);got["source"]!=nil {t.Fatal("Fleet poll time promoted")}
 for _,tc:=range []struct{source string;when time.Time}{
   {"local_import",now},{"telemetry_mqtt",now.Add(-3*time.Hour)},
 }{
   candidate:=mergeFreshTelemetryStatus(base,telemetrySnapshot{
       Fields:map[string]any{"TpmsPressureFl":2.6},
       FieldObservedAt:map[string]time.Time{"TpmsPressureFl":tc.when},
       FieldSources:map[string]string{"TpmsPressureFl":tc.source},Source:tc.source,ObservedAt:tc.when,
   },now)
   if got:=tpmsPressureEvidence(candidate);got["source"]!=nil {t.Fatal("wrong source or stale pressure verified")}
 }
}

func TestTPMSCannotInheritSnapshotWideTimeOrSourceFromFreshSOC(t *testing.T) {
    now:=time.Date(2026,10,11,1,0,0,0,time.UTC)
    value:=2.8
    base:=vehicleStatus{Source:"fleet_api",TPMSPressureFL:&value}
    for _,tc:=range []struct {
        name string
        fieldTimes map[string]time.Time
        fieldSources map[string]string
    }{
        {"no_per_wheel_time",map[string]time.Time{"Soc":now},map[string]string{"TpmsPressureFl":"telemetry_mqtt","Soc":"telemetry_mqtt"}},
        {"no_per_wheel_source",map[string]time.Time{"TpmsPressureFl":now,"Soc":now},map[string]string{"Soc":"telemetry_mqtt"}},
    } {
        t.Run(tc.name,func(t *testing.T) {
            merged:=mergeFreshTelemetryStatus(base,telemetrySnapshot{
                Fields:map[string]any{"TpmsPressureFl":2.9,"Soc":80.0},
                FieldObservedAt:tc.fieldTimes,FieldSources:tc.fieldSources,
                ObservedAt:now,Source:"telemetry_mqtt",
            },now)
            got:=tpmsPressureEvidence(merged)
            if got["source"]!=nil || got["time_basis"]!=nil {
                t.Fatalf("unproven wheel acquired source/clock from SOC: %v",got)
            }
            if merged.TPMSPressureFL==nil || *merged.TPMSPressureFL!=value {
                t.Fatal("base provider wheel was rewritten by an unproven receipt")
            }
        })
    }
}
