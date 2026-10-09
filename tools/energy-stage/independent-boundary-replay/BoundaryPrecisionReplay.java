import java.util.*;
import com.matelink.domain.analytics.*;
class BoundaryPrecisionReplay {
 public static void main(String[] args) {
  List<DrivePowerSample> p = new ArrayList<>();
  for (int s=0;s<=120;s+=30) {
   String t=java.time.Instant.parse("2026-01-01T00:00:00.417Z").plusSeconds(s).toString();
   p.add(new DrivePowerSample(t,1.0));
  }
  DriveEnergyResult full=DriveEnergyCalculator.INSTANCE.calculate(p,"2026-01-01T00:00:00.417Z","2026-01-01T00:02:00.417Z");
  DriveEnergyResult rounded=DriveEnergyCalculator.INSTANCE.calculate(p,"2026-01-01T00:00:00Z","2026-01-01T00:02:00Z");
  if (!full.getComplete() || rounded.getComplete()) throw new AssertionError("precision boundary replay unexpected");
  System.out.println("{\"fixture\":\"synthetic fraction-qualified replay\",\"full_precision_complete\":"+full.getComplete()+",\"rounded_complete\":"+rounded.getComplete()+",\"rounded_reason\":\""+rounded.getQualityReason()+"\",\"rounded_missing_seconds\":"+(rounded.getWindowSeconds()-rounded.getCoverageSecondsExact())+",\"actual_product_classes\":\"8625 debug build\"}");
 }
}
