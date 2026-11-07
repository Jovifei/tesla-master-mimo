# F2 history response-unit metadata chain

Owner: /root/fix_app_auth_cache. Actual root E:\project\_worktrees\matelink-release-data-audit-20261026, App branch codex/app-release-data-fixes-20261027. Discovered during 2026-10-27 build73 repair (original release audit version2.1.53/build72). Source candidate2.1.54/build73; no package/device/provider acceptance in this subtask. No Git or builds run here.

## Plan and scope

- [x] Read exact Charge/Drive response capture, UHR raw/Room merge, Summary envelope and Detail receipt paths.
- [ ] Parent runs actual UHR/Room/detail consumer RED from AppReleaseHistoryUnitsTest.
- [ ] Preserve same-response lengthUnit and temperatureUnit, keep existing raw numeric JSON, gate sparse fallback by unit compatibility, and rebuild detail metadata without changing energy/cost/identity policy.
- [ ] Parent/F3 consume exact canonical getters or same-receipt preferred formatter, preserve new speed_sample_count metadata.
- [ ] Parent focused/broad GREEN; record actual commit/build gates.

## Confirmed and narrowed findings

ChargeData.startRatedRangeKm/endRatedRangeKm currently return raw preferred values and lack same-response length metadata. UHR Drive and Charge sparse merges fill missing temperature from cached records without checking the temperature unit; Charge also fills length-dependent fields without a unit gate. This can join a new F receipt with a cached C value.

The newly added serialized _matelink_received_temperature_unit already survives ordinary Moshi header/detail encoding; this is a control to retain, not a reason to rewrite the correct codec. Drive length metadata already has a dedicated envelope/receipt column and correct established missing/null/explicit-unknown contracts; preserve that existing chain. No broad energy, cost, identity or schema redesign.

Compat seams: ChargeData/ChargeDetail receive nullable lengthUnit serialized as _matelink_received_length_unit; canonical getter names startRatedRangeKm/endRatedRangeKm/startIdealRangeKm/endIdealRangeKm/odometerKm are currently equivalent raw-return stubs for RED. hasKnownLengthUnit uses the existing legacy km/mi contract. DriveData/DriveDetail additionally retain nullable speedSampleCount (wire speed_sample_count), requested by F3; no speed estimator changes here.

## Actual RED/GREEN history and the canonical aggregate extension

Parent runner14 executed the initial seven consumer cases: five actual failures (missing same-response charge length, raw mile range treated as km, C/F sparse joins, old length borrowing and persisted unit mismatch), two positive codec/zero controls passed. Based on that RED, Charge model canonical getters now require explicit km/mi, UHR sparse fallbacks are unit-gated, and detail presentation carries its own metadata. Existing stored raw receipt text/numeric payloads remain separate. Drive cached speedAvg now retains the method/count belonging to the retained value; an incoming non-null number does not borrow old metadata.

Parent runner16 subsequently verified F5 all sixteen and units all ten GREEN. The three added GREEN controls cover detail-presentation metadata/raw-source preservation, list/current/detail length capture with unknown/zero controls, and speed method/count pairing. Ordinary HistoryDetailCodec already serialized temperature metadata correctly, so it was retained rather than rewritten. Charge length metadata is serialized in its header, requiring no receipt column or schema change. Debug mock producer units are explicitly forwarded by the same response helpers.

Canonical-temperature scan found DetailAggregateMapper storing preferred F values in fields consumed as C, and eight StatsScreen canonical-C values using the raw formatter. Parent runner19: batch 178 tests / 17 failures / 0 errors / 0 skipped; new AppReleaseCanonicalTemperatureTest eight cases produced seven actual failures, with genuine C zero/negative control passing. Failed cases cover F normalization, unknown units, legacy reader qualification, processing-version selection, same-coordinate local-cache geocoding preservation, contradictory mode hints and C-to-F display. Original F5 sixteen and units ten remained GREEN.

## Final canonical/unit implementation pending parent verification

- Processing version CURRENT/DRIVE_ENERGY is now 7 with TEMPERATURE_C=7; this is not the Room database schema version. Existing bounded selectors/budgets request fresh detail for older aggregates. No data cleanup or migration is run by this agent.
- The mapper normalizes point temperatures from that detail's own C/F metadata, retaining raw header/points unchanged. Unknown point units do not become C; only the fixed-C cloud producer contract retains its legacy fallback. Zero and negative temperatures remain valid. Existing non-temperature calculations are preserved.
- Stats' reader masks only four drive/two charge temperature fields of old version aggregates in memory. Database rows, elevation, power, type hints, geocoding and counts remain available. No summary or later status is used to guess old point units.
- Stats' actual value formatter converts canonical C to preferred F once. The existing value positions/styles are unchanged. The two old local-year regression fixture factories now explicitly carry version7 for their intentionally known-C fields; their assertions were not removed. Dedicated legacy-version tests still require unknown temperatures and retained stored values.
- New charge aggregates reuse the actual detail classifier's existing contract/conflict arbitration. Only detected DC sets isFastCharger; observed AC phases are kept only for detected AC; UNKNOWN writes false plus null phases. Power/voltage/current sample maxima and raw header remain unchanged. The legacy Boolean/phase index cannot encode every old conflict or phase-less AC claim, so old hints are not retroactively invented; normal version7 processing is bounded by existing budgets.
- Sync aggregate writers restore geocoding solely from the current valid coordinate pair's existing local cache. Drive uses a valid point from this detail. Charge may fall back only to the full pair in an eligible, same-source/window/record genuine summary accepted by the existing detail gate. Coordinates are never mixed, and status/adjacent drives/old unrelated names are not borrowed. Scope is reverified after the cache await and before writes. No network geocoding call or request-budget change was added.
- Existing cost, energy proof and identity arbitration were retained. The narrowly added ChargeEnergyBinding presentation gates do not alter the energy/price selection branches. RecommendationDriveSample now receives outsideTempC, rather than a preferred-unit scalar.

Frozen verification targets: AppReleaseHistoryUnitsTest (10), AppReleaseCanonicalTemperatureTest (8), AppReleaseGeoRepositoryTest (7), AppReleaseGeoConsumerTest (9), plus existing DriveResponseUnitsConsumerTest, SparseReceiptProvenanceTest, StatsReadScopeAndYearTest and detail-receipt/sync controls. Parent owns all execution, broad QA, version ledger/commits and final package acceptance. This agent has not run Gradle, Git, devices or provider/network calls. Final GREEN and commit references remain pending.

## Full Debug QA22 compatibility triage (test-only correction)

Parent reported full Debug22: 1555 tests / 17 failures / 0 errors / 0 skipped, with XML archived under E:\Claude_allow\Download\matelink-app-release-fixes-20261027\full-debug-22-xml. This subtask owns five failing methods across four test files. Scoped source/XML review found no reason to weaken production unit/identity or qualified-energy gates.

- EnergyDurableConsumerTest still asserted the former processing versions6/5. It now asserts7 while retaining an intentionally old v5 aggregate. Its real selector/merge/write loop runs twice and proves exactly one computation, no second stored-row mutation, no Room database-version change and the original0.6kWh result.
- TaskCHistoryBehaviorTest and SyncSummaryEvidenceRoundTripTest expected positive nested fusion while omitting valid same-session timestamps; remote sync projections also lacked matching trusted quality. Those do not satisfy the current explicit sameHistorySession/grade preconditions. Positive fixtures now supply the same complete window/source and explicit km where intended. All original100/101/0 and SOC0/79 checks remain. Added raw-decoder assertions explicitly retain100 and0 through the evidence round trip. A separate unproven Fleet zero remains0 in raw evidence but unknown as qualified whole-session energy; the established pre-source-tag legacy zero compatibility is also retained.
- DetailAggregateMapperTest's intentionally known Celsius samples had no temperature metadata. It now suppliesC in both known-C fixtures, retaining the original23/16°C, DC and charger-extrema assertions. Unitless/unsupported negative cases remain in AppReleaseCanonicalTemperatureTest.
- The Stats formatter literal already uses the existing no-space degree style; numeric68/32/negative assertions remain unchanged. No production formatting/style change was made during this triage.

Only these four test files changed in the QA22 correction window. Parent focused23 and subsequent full QA remain pending; this agent ran no build/Git/device/provider operations and does not claim those executions.
