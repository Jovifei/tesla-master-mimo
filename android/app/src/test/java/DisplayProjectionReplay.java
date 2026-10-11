import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import com.squareup.moshi.Moshi;
import com.matelink.data.api.models.*;
import com.matelink.data.local.entity.DriveSummary;
import com.matelink.domain.analytics.*;
import com.matelink.data.sync.SyncRepositoryKt;
import com.matelink.data.repository.UnifiedHistoryRepositoryKt;

public final class DisplayProjectionReplay {
  static Object withField(Object data, String fieldName, Object replacement) throws Exception {
    List<Field> fields = new ArrayList<>();
    for (Field f:data.getClass().getDeclaredFields()) if(!Modifier.isStatic(f.getModifiers())) fields.add(f);
    Method copy=Arrays.stream(data.getClass().getDeclaredMethods()).filter(m->m.getName().equals("copy")).findFirst().orElseThrow();
    Object[] values=new Object[copy.getParameterCount()];
    for(int i=0;i<values.length;i++) values[i]=data.getClass().getMethod("component"+(i+1)).invoke(data);
    int index=-1;for(int i=0;i<fields.size();i++) if(fields.get(i).getName().equals(fieldName))index=i;
    if(index<0)throw new IllegalArgumentException(fieldName);
    values[index]=replacement;return copy.invoke(data,values);
  }
  static boolean expected(DriveData d){return Objects.equals(d.getDistance(),2.0)&&Objects.equals(d.getStartBatteryLevel(),80)&&Objects.equals(d.getEndBatteryLevel(),70)&&Objects.equals(d.getEfficiencyWhKm(),500.0);}
  static void output(String stage,DriveData d){System.out.printf(Locale.ROOT,"%s distance=%s SOC=%s->%s efficiencyWhKm=%s netKWh=%s%n",stage,d.getDistance(),d.getStartBatteryLevel(),d.getEndBatteryLevel(),d.getEfficiencyWhKm(),d.getNetEnergyKwh());}
  public static void main(String[] args) throws Exception {
    String raw="{\"drive_id\":900001,\"start_date\":\"2026-01-01T00:00:00Z\",\"end_date\":\"2026-01-01T00:00:10Z\",\"source\":\"telemetry_mqtt\",\"quality_state\":\"observed\",\"odometer_details\":{\"odometer_distance\":1.0},\"battery_details\":null,\"energy_consumed_net\":8.0,\"opaque\":{\"fixture\":true}}";
    String detailJson="{\"drive_id\":900001,\"start_date\":\"2026-01-01T00:00:00Z\",\"end_date\":\"2026-01-01T00:00:10Z\",\"source\":\"telemetry_mqtt\",\"quality_state\":\"observed\",\"odometer_details\":{\"odometer_distance\":2.0},\"battery_details\":{\"start_battery_level\":80,\"end_battery_level\":70},\"energy_contract\":{\"version\":1,\"net_energy\":{\"value_kwh\":1.0,\"method\":\"api_reported_net\",\"measurement_point\":\"reported_net\",\"source\":\"telemetry_mqtt\",\"quality\":\"reported\",\"start_date\":\"2026-01-01T00:00:00Z\",\"end_date\":\"2026-01-01T00:00:10Z\"}}}";
    Moshi moshi=new Moshi.Builder().build();
    DriveData old=moshi.adapter(DriveData.class).fromJson(raw);
    DriveSummary initial=SyncRepositoryKt.toSyncSummary(old,-900001);
    initial=(DriveSummary)withField(initial,"apiEvidence",raw);
    DriveDetail detail=moshi.adapter(DriveDetail.class).fromJson(detailJson);
    ResolvedDriveEnergy energy=DriveEnergyBindingKt.resolveDriveEnergy(detail);
    DriveSummary enriched=DriveEnergyBindingKt.withResolvedDriveEnergy(initial,detail,energy);
    boolean receipt=raw.equals(HistorySummaryEvidenceCodec.INSTANCE.sourceJson(enriched.getApiEvidence()));
    System.out.printf("sourceReceiptRetained=%s directRoom distance=%s SOC=%s->%s efficiencyWhKm=%s%n",receipt,enriched.getDistance(),enriched.getStartBatteryLevel(),enriched.getEndBatteryLevel(),enriched.getEfficiency());
    DriveData restored=HistorySummaryMapperKt.toAnalysisDriveData(enriched);output("offlineProjection",restored);
    DriveSummary merged=UnifiedHistoryRepositoryKt.mergeStoredDrive(enriched,initial);
    DriveData afterMerge=HistorySummaryMapperKt.toAnalysisDriveData(merged);output("afterSameIdMerge",afterMerge);
    System.out.printf("mergedRoom distance=%s SOC=%s->%s efficiencyWhKm=%s%n",merged.getDistance(),merged.getStartBatteryLevel(),merged.getEndBatteryLevel(),merged.getEfficiency());
    System.out.println("expected distance=2.0 SOC=80->70 efficiencyWhKm=500.0 netKWh=1.0; exact raw receipt unchanged");
    System.out.println("fixtureSHA="+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((raw+detailJson).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    if(!receipt||!expected(restored)||!expected(afterMerge))throw new AssertionError("4bb display enrichment round-trip contract failed; synthetic inputs only");
  }
}
