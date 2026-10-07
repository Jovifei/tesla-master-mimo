package com.matelink.domain.analytics
import com.matelink.data.local.entity.TpmsPressureSample
import org.junit.Assert.*
import org.junit.Test
class TpmsChangeRuleTest {
 private fun sample(t:Long,p:Double?,origin:String="provider_observation")=TpmsPressureSample(-7,t,pressureFl=p,provenance=origin)
 @Test fun onlyRealFiniteConsecutiveObservedPressureCanTrigger(){
  val rule=TpmsChangeRule(true,0.3,24)
  assertEquals(1,pressureChanges(sample(1,2.9),sample(1000,2.5),rule).size)
  assertTrue(pressureChanges(sample(1,2.9,"legacy_unverified"),sample(1000,2.5),rule).isEmpty())
  assertTrue(pressureChanges(sample(1,null),sample(1000,2.5),rule).isEmpty())
  assertTrue(pressureChanges(sample(1,2.9),sample(1000,Double.NaN),rule).isEmpty())
  assertTrue(pressureChanges(sample(1,2.9),sample(1000,2.5),rule.copy(enabled=false)).isEmpty())
 }
 @Test fun scopeTimeWindowAndThresholdAreMandatory(){
  val a=sample(1,2.9);val b=sample(1000,2.5);val rule=TpmsChangeRule(true)
  assertTrue(pressureChanges(a,b.copy(carId=-8),rule).isEmpty())
  assertTrue(pressureChanges(a,b.copy(observedAt=1),rule).isEmpty())
  assertTrue(pressureChanges(a,b.copy(observedAt=86400002),rule).isEmpty())
  assertTrue(pressureChanges(a,b.copy(pressureFl=2.7),rule).isEmpty())
 }
}
