# F5 statistics, geographic cohort and Timeline duration

Discovery: 2026-10-26 reviews 05/10/11, source version 2.1.53 (72). Current repair target is 2.1.54 (73), branch codex/app-release-data-fixes-20261027 in E:\project\_worktrees\matelink-release-data-audit-20261026. Agent /root/fix_app_auth_cache. Build, tests, commits and final ledger remain parent-owned.

## Plan

- [x] Read reviews 05/10/11 and verify_geo_sql.py; retain retractions (Timeline energy already qualified; no literal 100mi label in Stats).
- [x] Confirm AC query schema: charge_detail_aggregates.chargerPhases is nullable Int; isFastCharger is Boolean. New getAcChargeIds will require phases BETWEEN 1 AND 3 and isFastCharger=0. No schema change.
- [x] Prepare actual Room/repository and real Geo VM race tests plus equivalent source seams; obtain parent's actual RED.
- [x] Reuse captured scope/local vehicle/calendar and one Room cohort for country, region, header and map; verify after awaits and clear stale UI projection.
- [x] Preserve qualified nullable totals with sample counts, real zero, valid coordinates, local year and chronological visit ordering.
- [x] Fix canonical km formatting and existing Timeline duration fallback without layout/style changes.
- [x] Preserve correct main Stats cost pairing and mode arbitration; only fix empty-energy totals through explicit sample counts/nullable presentation.
- [ ] Parent runs focused GREEN, SQL and broader checks; record actual source/build/commit boundaries.

## Confirmed root causes

Geo helpers resolve the current local ID separately for each DAO call and still use raw ISO lexical year ranges. Geo VMs lack identity/request fences, including units and country boundary children. Geo SQL uses primitive SUM and map queries omit summary quality. DeepStats sums no qualified energy samples into 0, despite correct AC/DC/UNKNOWN arbitration. Country/region canonical km use raw preferred-unit formatDistance. Timeline stores durationMin but renders only durationStr.

Requested necessary data-model/UI extension approved by parent: CarStats.kt compatibility/sample-count fields and StatsScreen.kt AcDcRatioCard existing value bindings only. No layouts, positions, sizes, fonts, colors, schema, provider/network/device or private data changes are authorized here. Existing F1 files remain handed off.

## Evidence status

No tests, SQL, Gradle or builds have been run by this subtask for F5. Review 11's seven SQL cases (4 failures, 3 controls) are inherited historical evidence only. New RED remains pending.

## 2026-10-27 first actual RED and next verification window

Inherited parent execution `runner05`: complete batch 77 tests / 17 failures / 0 errors / 0 skipped; F5 AppReleaseGeoRepositoryTest 6 tests / 5 failures, with the explicit AC phase control passing. Parent archived XML under E:\Claude_allow\Download\matelink-app-release-fixes-20261027\groups-red-green-05-xml. The repository RED covers unknown/zero distinction, local-year boundary, prior account/UID publication, quarantine/invalid markers and absent mode energy.

Based on that RED, repository source now reads one captured StatsRead/Room snapshot, projects the same qualified Drive/Charge cohort as main Stats, and binds country/header/region/map to it. Costs and legacy migration are skipped for this geographic read; existing main Stats cost and mode arbitration are unchanged. Deep energy retains compatible primitive fields and adds sample-count-qualified nullable getters. Country/region totals similarly expose nullable qualified values and counts. Explicit zero remains a sample.

VM constructor seams now route through GeoVisitedPageSource and the production snapshot, while deliberately retaining the old unfenced async publication, raw map-year comparison and first-visit sort for the next actual RED. Geo value helpers remain the old raw-distance formula; Timeline helper remains string-only. AppReleaseGeoConsumerTest adds eight actual VM/consumer cases to demonstrate those remaining failures before behavior changes.

The old country/region DAO aggregate queries are deprecated and removed from production geographic consumers. They are not reimplemented with a partial SQL JSON parser: modern apiEvidence contains versioned raw_json, scoped presentation and contract arbitration, so SQL SUM cannot duplicate the typed mapper safely. The legacy map SQL has an additional quality/coordinate guard. The historical verify_geo_sql.py tests those old aggregation SELECTs; it is not treated as proof of the new production snapshot's nullable/local-year semantics. Parent requested and approved the typed snapshot direction. Real Room/repository tests now cover those production semantics; no claim of seven legacy SQL cases passing is made.

Frozen targets for parent: AppReleaseGeoRepositoryTest (6) and AppReleaseGeoConsumerTest (8). No F5 tests, SQL, Gradle or builds have been executed by this agent.

## 2026-10-27 consumer GREEN implementation and currency publication RED

Inherited actual parent `runner06`: batch 86 tests / 31 failures / 0 errors / 0 skipped. GeoRepository 6/6 GREEN. GeoConsumer 8/8 failed on the intended unfenced VM, map year/mode/coordinate, chronological ordering, distance and duration behavior. XML archived as groups-red-green-06-xml. Based on that evidence, geographic VMs now capture one identity, pass it into the repository snapshot, cancel/invalidate prior requests, verify every units/boundary/data return, clear stale published projections on scope change, and protect sort/filter interactions. The captured calendar also invalidates an in-flight result after a system-zone change. Countries/regions keep the original Hilt constructor signatures.

Map year and available-year values now use the snapshot's local calendar; AC/DC filters use the existing mode arbiter's explicit mode and leave unknown separate. Markers validate finite/range coordinates including the 0,0 sentinel. First visits sort by parsed instants. The same existing UI value positions now consume canonical-km conversion and nullable/sample-count totals. Timeline's existing duration position falls back to nonnegative known minutes, preserving zero and string precedence. No Timeline energy changes or literal 100mi-label claim was introduced.

Review S06 currency labels are being checked through the actual StatsUiState.publishStats function. CarStats now carries the same selected currencyCode the repository used for its amount snapshot, with a compatible nullable default. The publisher is deliberately still unchanged for one new RED case: a USD amount snapshot applied to an existing CNY-symbol state must update both together. This changes no pricing qualification, override, tariff or conversion algorithm. AppReleaseGeoConsumerTest now has nine methods (the currency case is the only expected remaining RED), plus GeoRepository six.

## 2026-10-27 currency GREEN and credential-rotation recovery RED gate

Inherited parent runner09: batch 99 tests / 9 failures / 0 errors / 0 skipped. GeoRepository 6/6 and the original GeoConsumer eight cases are GREEN; only the newly added currency publication case failed (USD snapshot still displayed the prior ¥ symbol). XML saved as groups-red-green-09-xml.

The currency publisher now chooses its symbol from the same CarStats.currencyCode, retaining the code for unsupported symbols; initial settings cannot relabel a price snapshot that has already been published. Geographic nullable qualification and grouping now run on Dispatchers.Default after the one Room capture, preserving scope/calendar verification and cancellation semantics. No pricing, override, tariff or energy-mode arbitration was changed.

A seventh GeoRepository case now constructs actual SessionStore, Room, GeoRepository and both geographic VMs. It inserts a second qualified local record, rotates credentials without changing login identity, and requires both pages to re-read current data without re-entry. Optional units/boundary I/O is fake, and the fixture asserts no HTTP calls. Current implementation still only clears the invalid old scope; automatic re-read is intentionally pending this new actual RED. Parent runner10 owns that test execution. Source candidate has been advanced by parent to 2.1.54/build73; no APK, installation, device or provider acceptance is asserted here.

## 2026-10-27 final F5 source freeze

Inherited parent runner11: batch 114 tests / 14 failures. GeoRepository seven methods had exactly the new normal-credential recovery assertion fail; the original six stayed GREEN. GeoConsumer nine methods all passed, including same-snapshot currency publication. These are parent executions, not runs by this agent.

The final behavior now clears and cancels the invalid old scope, then starts a new request with the saved car/country/year target and a newly captured verified identity. It also retries on a new scope event after a prior identity-capture failure. It does not relax scope/generation/UID or calendar gates. The pending fake now deliberately holds the new-scope data read and supplies no new units so its assertions measure rejected old content, rather than rejecting a legitimate current response. Additional coordinate controls in the same consumer test include NaN, infinity, 0,0 and a valid equator point.

Frozen F5 product files: StatsRepository.kt; AggregateDao.kt; domain/model/CarStats.kt; CountriesVisitedViewModel.kt and CountriesVisitedScreen.kt; RegionsVisitedViewModel.kt and RegionsVisitedScreen.kt; StatsScreen.kt (AcDcRatioCard value bindings only); StatsViewModel.kt (currency publication/initial-label guard only); TimelineScreen.kt (existing duration binding only); new GeoStatsSnapshot.kt, GeoVisitedPageSource.kt and GeoValueFormatting.kt. No schema or resources changed. No F1 product files were touched after handoff.

Frozen tests: AppReleaseGeoRepositoryTest (7), AppReleaseGeoConsumerTest (9). Parent should run all sixteen plus StatsReadScopeAndYearTest, StatsMonthlyDriveAggregationTest, StatsMonthlyChargeAggregationTest, StatsChargeCostProjectionTest, ScopedAnalyticsPublicationTest, TimelineUnitsConsumerTest and TimelineTimezoneConsumerTest. Existing legacy DAO aggregate SQL remains deprecated/unused by production Geo; its historical JSON/lexical-year script is not misreported as new semantic acceptance.

Self-review: one Room cohort supplies header/region/markers; new-source/late units/boundary requests cannot restore old data; unknown totals and modes remain nullable; observed zero remains known; local-year and first-visit sorting use instants; canonical distance converts once; pricing/mode arbiters remain unchanged. Normal token rotation re-enters through a fresh identity, not a bypass. Layout, coordinates of parameters, typography, sizes and colors were not edited. Final parent GREEN, broad checks, commit references, package/build/device/provider evidence remain pending.
