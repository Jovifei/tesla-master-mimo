package main

import (
    "context"
    "os"
    "testing"
    "time"
)

// Exercises the real PG16 ingress, open-session restart, completed JSON,
// bounded list/detail and replay. Fixture tenants/vehicle IDs are synthetic.
func TestEnergyPostgresResetReplayAndACModeScope(t *testing.T) {
    dsn := os.Getenv("JOURVOLT_TEST_DATABASE_URL")
    if dsn == "" { t.Skip("isolated PG16 URL unavailable") }
    ctx := context.Background()
    db, err := openStore(ctx, dsn)
    if err != nil { t.Fatal(err) }
    t.Cleanup(db.close)

    type signal struct { field string; value any; second int }
    cases := []struct {
        name string
        signals []signal
        wantBattery any
        wantAC any
        wantMode string
        hasBalance bool
    }{
        {"reset_at_final_receipt", []signal{
            {"DCChargingEnergyIn",100.0,0}, {"DCChargingEnergyIn",102.0,60},
            {"DCChargingEnergyIn",1.0,60},
        }, nil,nil,"",false},
        {"zero_ac_counter_only", []signal{
            {"ACChargingEnergyIn",0.0,0}, {"DCChargingEnergyIn",0.0,0},
            {"ACChargingEnergyIn",0.0,60}, {"DCChargingEnergyIn",8.0,60},
        },8.0,nil,"",false},
        {"mixed_ac_dc_modes", []signal{
            {"ChargerPhases",2.0,0},
            {"ACChargingEnergyIn",0.0,0}, {"DCChargingEnergyIn",0.0,0},
            {"FastChargerPresent",true,30},
            {"FastChargerPresent",false,60},
            {"ACChargingEnergyIn",10.0,60}, {"DCChargingEnergyIn",9.0,60},
        },9.0,nil,"",false},
        {"verified_ac_whole_window", []signal{
            {"ChargerPhases",2.0,0},
            {"ACChargingEnergyIn",20.0,0}, {"DCChargingEnergyIn",30.0,0},
            {"ACChargingEnergyIn",30.0,60}, {"DCChargingEnergyIn",39.0,60},
            {"ChargerPhases",2.0,60},
        },9.0,10.0,"ac",true},
    }
    for _, tt := range cases {
        t.Run(tt.name, func(t *testing.T) {
            user := "energy_e2e_" + mustRandomToken(t)
            if err := db.ensureUser(ctx,user); err != nil { t.Fatal(err) }
            t.Cleanup(func(){ _=db.deleteUser(context.Background(),user) })
            var car int
            err := db.pool.QueryRow(ctx,`INSERT INTO jourvolt_vehicles
                (user_id,provider_vehicle_id,vin_ciphertext,display_name,state,updated_at)
                VALUES($1,$2,'fixture','synthetic','online',now()) RETURNING id`,
                user,"synthetic-"+mustRandomToken(t)).Scan(&car)
            if err != nil { t.Fatal(err) }
            ref := telemetryVehicleRef{
                UserID:user, VehicleID:car,VINHash:"sha-only-"+mustRandomToken(t),
            }
            ingest := &telemetryService{
                store:db,config:&telemetryConfig{StopDebounce:defaultDriveStopDebounce},
            }
            if err := ingest.registerVehicle(ctx,ref); err != nil { t.Fatal(err) }
            start := time.Date(2026,10,9,0,0,0,0,time.UTC)
            event := func(field string, value any, seconds int, id string) telemetryRecord {
                return telemetryRecord{
                    VINHash:ref.VINHash, FieldName:field, Value:value,
                    ObservedAt:start.Add(time.Duration(seconds)*time.Second),
                    EventID:tt.name+"-"+id,
                }
            }
            open := event("DetailedChargeState","Charging",0,"open")
            if n,err:=ingest.ingest(ctx,open); err!=nil||n!=1 {t.Fatalf("open n=%d err=%v",n,err)}
            // Independent restart during an open session must not lose counter/mode evidence.
            for i,s := range tt.signals {
                record := event(s.field,s.value,s.second,string(rune('a'+i)))
                restarted := &telemetryService{store:db,config:&telemetryConfig{StopDebounce:defaultDriveStopDebounce}}
                if n,err:=restarted.ingest(ctx,record); err!=nil||n!=1 {
                    t.Fatalf("ingest %d n=%d err=%v",i,n,err)
                }
            }
            closeEvent := event("DetailedChargeState","Complete",60,"close")
            if n,err:=ingest.ingest(ctx,closeEvent); err!=nil||n!=1 {t.Fatalf("complete n=%d err=%v",n,err)}
            repeat := &telemetryService{store:db,config:&telemetryConfig{StopDebounce:defaultDriveStopDebounce}}
            if n,err:=repeat.ingest(ctx,closeEvent); err!=nil||n!=0 {t.Fatalf("replay n=%d err=%v",n,err)}
            list,_,total,_,err:=repeat.historyPageContext(ctx,user,car,"charge",time.Time{},time.Time{},1,50)
            if err!=nil||total!=1||len(list)!=1 {t.Fatalf("list=%d total=%d err=%v",len(list),total,err)}
            id,ok:=list[0]["charge_id"].(int)
            if !ok {t.Fatalf("charge id %#v",list[0])}
            detail,found,err:=repeat.historyDetailContext(ctx,user,car,"charge",id)
            if !found||err!=nil {t.Fatalf("detail found=%v err=%v",found,err)}
            for _,actual:=range []map[string]any{list[0],detail} {
                if actual["charge_energy_added"]!=tt.wantBattery || actual["charge_energy_used"]!=tt.wantAC {
                    t.Fatalf("incorrect physical counters for %s: added=%v ac=%v",tt.name,actual["charge_energy_added"],actual["charge_energy_used"])
                }
                if tt.wantMode=="" {
                    if actual["charge_type"]!=nil {t.Fatalf("unsupported mode published %#v",actual["charge_type"])}
                } else if actual["charge_type"]!=tt.wantMode {t.Fatalf("mode %v",actual["charge_type"])}
                contract,ok:=actual["energy_contract"].(map[string]any)
                if !ok {t.Fatalf("missing persisted contract: %#v",actual)}
                if (contract["ac_loss"]!=nil)!=tt.hasBalance || (contract["ac_efficiency"]!=nil)!=tt.hasBalance {
                    t.Fatalf("invented or missing AC balance: %#v",contract)
                }
                if tt.name=="reset_at_final_receipt" {
                    battery:=contract["battery_input"].(map[string]any)
                    if battery["quality"]=="reported" || battery["value_kwh"]!=nil {
                        t.Fatalf("stale delta reappeared after restart: %#v",battery)
                    }
                }
            }
            foreign,_,count,_,err:=repeat.historyPageContext(ctx,"wrong-user",car,"charge",time.Time{},time.Time{},1,50)
            if err!=nil||count!=0||len(foreign)!=0 {t.Fatal("tenant-scope breach")}
        })
    }
}
