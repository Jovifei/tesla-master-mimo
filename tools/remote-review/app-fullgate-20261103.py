from pathlib import Path

root=Path.cwd()
def rep(path, old, new, count=1):
 p=root/path; text=p.read_text(); assert text.count(old)==count,(path,old[:80],text.count(old),count); p.write_text(text.replace(old,new))
def rewrite_test(path,name,change):
 p=root/path; text=p.read_text()
 fn=text.index('fun '+name+'(')
 start=text.rfind('    @Test',0,fn)
 end=text.find('\n    @Test',fn)
 if end<0: end=text.rfind('\n}')
 part=text[start:end]; updated=change(part); assert part!=updated,name
 p.write_text(text[:start]+updated+text[end:])
def change_once(s,a,b):
 assert s.count(a)==1,(a[:80],s.count(a)); return s.replace(a,b)

base='android/app/src/test/java/com/matelink/'
live=base+'ui/screens/drives/Oct28LiveRoomDriveConsumerTest.kt'
rep(live,'            assertTrue(fixture.calls.any { it.endsWith("/charges") })','            assertFalse("Drive-only publication must not request charging history", fixture.calls.any { it.endsWith("/charges") })')
def first_mapping(s):
 s=change_once(s,'fun freshIdentityDiscoveryPublishesDrivesWhileChargesWaitAndAfterTheirTimeout()',
   'fun freshIdentityDiscoveryPublishesWithoutUnrelatedChargesOrFalseTimeout()')
 s=change_once(s,'            withTimeout(10_000) { chargesEntered.await() }','            awaitInitialLoad(vm)\n            assertFalse(chargesEntered.isCompleted)')
 s=change_once(s,'            // Exercise the real VM timeout without spending twenty seconds of wall time.','            // An unrelated endpoint is no longer requested and cannot manufacture a later timeout.')
 s=change_once(s,'            val timedOut = withTimeout(3_000) { vm.uiState.first { it.historySyncWarning != null } }','            val completed = vm.uiState.value')
 s=s.replace('timedOut.drives','completed.drives').replace('timedOut.summary','completed.summary')
 s=change_once(s,'            assertEquals("history_sync_timeout", timedOut.historySyncWarning)\n            assertEquals(R.string.history_sync_timeout,\n                historyReadWarningMessageRes(requireNotNull(timedOut.historySyncWarning)))',
 '            assertNull(completed.historySyncWarning)\n            assertFalse(completed.isLoading)\n            assertFalse(completed.isRefreshing)\n            assertFalse(fixture.calls.any { it.endsWith("/charges") })')
 return s
rewrite_test(live,'freshIdentityDiscoveryPublishesDrivesWhileChargesWaitAndAfterTheirTimeout',first_mapping)
def rebound(s):
 s=change_once(s,'fun uidRemappingAdoptsEmptyAndNonEmptyVehicleBeforeSlowChargesComplete()',
 'fun uidRemappingAdoptsEmptyAndNonEmptyVehicleWithoutUnrelatedCharges()')
 return change_once(s,'                withTimeout(10_000) { chargesEntered.await() }','                awaitInitialLoad(vm)\n                assertFalse("UID rebinding must not request unrelated charges", chargesEntered.isCompleted)')
rewrite_test(live,'uidRemappingAdoptsEmptyAndNonEmptyVehicleBeforeSlowChargesComplete',rebound)
def progress(s):
 s=change_once(s,'if (path.endsWith("/charges"))','if (path.endsWith("/drives"))')
 return s.replace('chargesEntered','historyEntered').replace('chargeGate','historyGate')
rewrite_test(live,'refreshProgressSurvivesLiveRoomUpdatesUntilSuccessOrTimeout',progress)
def late_address(s):
 s=change_once(s,'fun cachedAddressCannotCrossCarWhileChargeReadIsPending()',
 'fun cachedAddressCannotCrossCarWhileOwnHistoryReadIsPending()')
 s=change_once(s,'path.endsWith("/cars/1/charges")','path.endsWith("/cars/1/drives")')
 return s.replace('chargeEntered','historyEntered').replace('chargeRelease','historyRelease')
rewrite_test(live,'cachedAddressCannotCrossCarWhileChargeReadIsPending',late_address)

slow=base+'ui/screens/drives/Oct28SlowChargeDriveConsumerTest.kt'
rep(slow,'import kotlinx.coroutines.Job','import kotlinx.coroutines.Job\nimport kotlinx.coroutines.launch')
names=['samePendingChargeReadPublishesTheSuccessfulOct28DrivesBeforeItCompletes',
 'cachedDriveAddressResolvesWhileUnrelatedChargeReadIsStillPending',
 'cachedDriveAddressSurvivesUnrelatedChargeFailure',
 'newVerifiedDriveGetsAddressAndEnergyBeforeChargeReadCompletes',
 'verifiedCachedDriveGetsEnergyBeforeUnrelatedChargeCompletes']
def independent_charge(s):
 s=change_once(s,'        var page: DrivesViewModel? = null','        var page: DrivesViewModel? = null\n        var independentChargeRead: Job? = null')
 marker='            page = viewModel' if '            page = viewModel' in s else '            page = vm'
 s=change_once(s,marker,marker+'''\n            // A separate real charge consumer may be pending/failing. The drive VM must not join it.
            independentChargeRead = launch { fixture.repository().loadCharges(Oct28IndependentHistoryFixture.REMOTE_CAR) }''')
 if '            page?.viewModelScope?.cancel()' in s:
  s=change_once(s,'            page?.viewModelScope?.cancel()','            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()')
 s=change_once(s,'            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()',
 '            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()\n            independentChargeRead?.cancelAndJoin()')
 if 'fun samePendingChargeRead' in s:
  s=change_once(s,'            fixture.assertOct28DrivesPersistedInTheVerifiedCar()\n','')
  s=change_once(s,'            assertEquals(1, fixture.driveCalls)',
   '            fixture.assertOct28DrivesPersistedInTheVerifiedCar()\n            assertEquals(1, fixture.driveCalls)')
 return s
for name in names: rewrite_test(slow,name,independent_charge)

settings=base+'ui/screens/settings/SettingsExperienceContractTest.kt'
rep(settings,'        assertEquals(83, code)','        assertEquals(84, code)')
rep(settings,'        assertEquals("2.1.64", version)','        assertEquals("2.1.65", version)')
rep(settings,'        assertTrue(en.contains("estimated", ignoreCase = true))','''        assertTrue(en.contains("estimated", ignoreCase = true))
        assertTrue(zh.contains("部分估算") && zh.contains("独立"))
        assertTrue(en.contains("partial estimates", ignoreCase = true) && en.contains("independent", ignoreCase = true))''')
for directory,extra in [('values','\\n• Partial estimates remain separate from bills. Independent history reads, automatic local fee updates and cold-to-live same-vehicle analytics improve page responsiveness without resetting saved data.'),('values-zh','\\n• 已采集部分估算与账单分开；行程和充电独立读取，手工费用自动更新，同车冷缓存到在线分析正常刷新，不清空已有资料。')]:
 p=root/f'android/app/src/main/res/{directory}/strings.xml'; text=p.read_text(); mark='<string name="settings_release_notes_body" formatted="false">'; start=text.index(mark); end=text.index('</string>',start); p.write_text(text[:end]+extra+text[end:])

# Install the new job before executing inline callbacks; reentrant load()/invalidate() must
# never have its tracked job overwritten when the older immediate launch returns.
main='android/app/src/main/java/com/matelink/domain/history/LatestHistoryLoad.kt'
rep(main,'import kotlinx.coroutines.CoroutineScope','import kotlinx.coroutines.CoroutineScope\nimport kotlinx.coroutines.CoroutineStart')
rep(main,'        job = scope.launch { request.block() }','''        val next = scope.launch(start = CoroutineStart.LAZY) { request.block() }
        job = next
        next.start()''')
t=base+'domain/history/LatestHistoryLoadTest.kt'
rep(t,'import kotlinx.coroutines.CompletableDeferred','''import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher''')
p=root/t;s=p.read_text().rstrip();assert s.endswith('}');p.write_text(s[:-1]+'''
    @Test fun inlinePublicationStartingAnotherLoadKeepsTheNewJobAsCancellationOwner() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val loader = LatestHistoryLoad()
        var entered = false
        var cancelled = false
        try {
            loader.launch(scope) {
                loader.launch(scope) {
                    try { entered = true; awaitCancellation() } finally { cancelled = true }
                }
            }
            assertTrue(entered)
            loader.invalidate()
            assertTrue("The latest reentrant request, not the completed parent, must be cancelled", cancelled)
        } finally { scope.cancel() }
    }
}
''')
# Finish the initial read before flipping the synthetic identity authority. This assertion
# is about a later refresh, not about a StateFlow callback interrupting the first publication.
notice=base+'ui/screens/charges/ChargeHistoryNoticeIdentityTest.kt'
rep(notice,'import kotlinx.coroutines.ExperimentalCoroutinesApi','import kotlinx.coroutines.ExperimentalCoroutinesApi\nimport kotlinx.coroutines.Job\nimport kotlinx.coroutines.delay')
rep(notice,'            allowed.set(false)','''            val loader = ChargesViewModel::class.java.getDeclaredField("latestLoad")
                .apply { isAccessible = true }.get(page)
            val jobField = loader.javaClass.getDeclaredField("job").apply { isAccessible = true }
            withTimeout(10_000) {
                while (jobField.get(loader) == null) delay(10)
                (jobField.get(loader) as Job).join()
            }
            allowed.set(false)''')

notes=root/'docs/REVIEW-20261103-FULL-GATE-CORRECTIONS.md';notes.write_text('''# Full Android gate correction (same review stage)

At App `8721b721fcf3307b809a14c2126122e4b5d9d574`, the full gate found Debug 1876/18 failures and Release 1876/19 failures (8 established skips), despite passing focused and API35 gates. No source approval is assigned to that SHA. The complete failing XML is retained in Actions run 41491959142.

The drive-only contract deliberately removes charging requests from DrivesViewModel. Old tests that required the removed endpoint were corrected without removing any test: actual combined-history timeout tests remain unchanged; five drive/charge concurrency tests now start a separate real charge-family caller and keep their original cancellation, energy, address and Room assertions. Live Room publication still checks all ID/source/window/parking cases, additionally proving no charging request. Own-family refresh progress/timeouts and late-address car switching now gate the actual drive endpoint instead of the unrelated one.

Review also found that LatestHistoryLoad stored an immediately launched job only after its block could publish and reenter load(). A reentrant newer job could therefore be replaced by the older completed job. The next lazy job is now installed before start; an actual immediate-dispatch cancellation regression proves the newer job remains owned. The notice refresh test waits for initial load completion before revoking its synthetic identity, preserving the intended unavailable-identity assertions instead of relying on mid-publication timing.

Build84 release metadata assertions and localized release descriptions now match the shipped candidate. All numerical, isolation, cancellation and version assertions remain, no tests were disabled. Full Debug/Release/lint/R8/API35 and isolated cross-layer gates must pass at the later terminal SHA. Mini source and backend/production/device state are not changed by this correction.
''')
print('Full-gate consumer repairs applied with exact assertions; no tests removed or skipped.')
