package com.matelink.data.local
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class TpmsProvenanceSourceContractTest {
 @Test fun oldObservationsAreRetainedAndUnverifiedNotUsedForTrendOrPrune(){
  val root=File("src/main/java/com/matelink")
  val db=File(root,"data/local/StatsDatabase.kt").readText()
  val migration=db.substringAfter("val MIGRATION_20_21").substringBefore("val MIGRATION_19_20")
  assertTrue(migration.contains("ADD COLUMN provenance"));assertTrue(migration.contains("legacy_unverified"))
  assertFalse(migration.contains("DELETE"));assertTrue(db.contains("MIGRATION_19_20, MIGRATION_20_21"))
  val dao=File(root,"data/local/dao/TpmsPressureSampleDao.kt").readText()
  assertTrue(dao.contains("WHERE provenance = 'provider_observation' AND carId = :carId AND observedAt < :cutoff"))
  val ui=File(root,"ui/screens/tpms/TpmsTrendViewModel.kt").readText()
  assertFalse(ui.contains("backfillAndLoad"));assertFalse(ui.contains("getOrDefault(carId)"))
  assertTrue(ui.contains("selectedWindow: TpmsTrendWindow = TpmsTrendWindow.THIRTY"))
 }
}
