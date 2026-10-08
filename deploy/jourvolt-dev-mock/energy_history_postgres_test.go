package main

import (
    "context"
    "os"
    "testing"
    "time"
)

// Isolated PG16 end-to-end: actual provider event ingress -> persisted JSON
// completion -> bounded page scalars -> full detail. No production connection.
func TestEnergyPostgresBoundedChargeContractSurvivesRestart(t *testing.T) {
    dsn:=os.Getenv("JOURVOLT_TEST_DATABASE_URL")
    if dsn=="" { t.Skip("isolated PostgreSQL URL unavailable") }
    ctx:=context.Background()
    db,err:=openStore(ctx,dsn)
    if err!=nil {t.Fatal(err)}
    t.Cleanup(db.close)
    user:="energy_contract_"+mustRandomToken(t)
    if err:=db.ensureUser(ctx,user);err!=nil {t.Fatal(err)}
    t.Cleanup(func(){_ = db.deleteUser(context.Background(),user)})
    var car int
    err=db.pool.QueryRow(ctx,`INSERT INTO jourvolt_vehicles
        (user_id,provider_vehicle_id,vin_ciphertext,display_name,state,updated_at)
        VALUES($1,$2,'fixture','synthetic','online',now()) RETURNING id`,
        user,"synthetic-"+mustRandomToken(t)).Scan(&car)
    if err!=nil {t.Fatal(err)}
    ref:=telemetryVehicleRef{UserID:user,VehicleID:car,VINHash:"energy-hash-"+mustRandomToken(t)}
    svc:=&telemetryService{store:db,config:&telemetryConfig{StopDebounce:defaultDriveStopDebounce}}
    if err:=svc.registerVehicle(ctx,ref);err!=nil {t.Fatal(err)}
    start:=time.Date(2026,10,8,10,0,0,0,time.UTC)
    end:=start.Add(10*time.Minute)
    data:=[]struct{field string; value any; at time.Time}{
        {"DetailedChargeState","Charging",start},
        {"ChargerPhases",2.0,start},
        {"DCChargingEnergyIn",100.0,start},
        {"ACChargingEnergyIn",200.0,start},
        {"DCChargingEnergyIn",108.0,end},
        {"ACChargingEnergyIn",210.0,end},
        {"ChargerPhases",2.0,end},
        {"DetailedChargeState","Complete",end},
    }
    for i,item:=range data {
        record:=telemetryRecord{
            VINHash:ref.VINHash,FieldName:item.field,Value:item.value,
            ObservedAt:item.at,EventID: "energy-event-"+string(rune('a'+i)),
        }
        if n,e:=svc.ingest(ctx,record);e!=nil||n!=1 {t.Fatalf("ingest %d accepted=%d error=%v",i,n,e)}
    }
    restarted:=&telemetryService{store:db,config:&telemetryConfig{StopDebounce:defaultDriveStopDebounce}}
    rows,_,total,_,err:=restarted.historyPageContext(ctx,user,car,"charge",time.Time{},time.Time{},1,50)
    if err!=nil||total!=1||len(rows)!=1 {t.Fatalf("PG page total=%d err=%v",total,err)}
    chargeID,ok:=rows[0]["charge_id"].(int)
    if !ok||chargeID<=0 {t.Fatalf("invalid charge id: %#v",rows[0]["charge_id"])}
    detail,ok,err:=restarted.historyDetailContext(ctx,user,car,"charge",chargeID)
    if err!=nil||!ok {t.Fatalf("PG detail ok=%v err=%v",ok,err)}
    for _,item:=range []map[string]any{rows[0],detail} {
        if item["charge_energy_added"]!=8.0||item["charge_energy_used"]!=10.0 {
            t.Fatalf("qualified battery/AC energy lost: %#v",item)
        }
        contract,ok:=item["energy_contract"].(map[string]any)
        if !ok {t.Fatal("missing bounded energy contract")}
        if contract["ac_efficiency"]!=80.0 || contract["charge_mode"]!="ac" {t.Fatalf("AC mode/efficiency: %#v",contract)}
        metric:=contract["battery_input"].(map[string]any)
        if metric["measurement_point"]!="battery_input"||metric["quality"]!="reported"||
            metric["observed_start_at"]!=start.Format(time.RFC3339)||
            metric["observed_end_at"]!=end.Format(time.RFC3339) {
            t.Fatalf("PG observed source time mismatch: %#v",metric)
        }
    }
    foreign,_,count,_,err:=restarted.historyPageContext(ctx,"other",car,"charge",time.Time{},time.Time{},1,50)
    if err!=nil||count!=0||len(foreign)!=0 {t.Fatal("cross-user energy leak")}
}
