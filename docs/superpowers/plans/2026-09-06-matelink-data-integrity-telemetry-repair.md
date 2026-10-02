# MateLink Data Integrity and Fleet Telemetry Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore trustworthy live location, trip, charging, and analytics data by completing the real Fleet Telemetry path and removing every synthetic value that is currently presented as vehicle evidence.

**Architecture:** Tesla Fleet Telemetry is the authoritative source for background driving and charging sessions; Fleet `vehicle_data` remains the on-demand live fallback. The server may retain only the latest two `Asia/Shanghai` data-days needed for delivery, while the phone keeps the longer archive. Android renders and analyzes only observed or explicitly labelled derived data; missing data remains unavailable. AMap receives normalized real coordinates and never manufactures a vehicle position.

**Tech Stack:** Kotlin/Compose, Room v19→v20, WorkManager, Retrofit/Moshi, AMap Android SDK, Go, Tesla Fleet Telemetry protobuf/MQTT, PostgreSQL 16, Docker Compose.

---

## Confirmed baseline and safety boundary

- Source baseline: clean `main@db35155b80e12b9a1e641e8181dbeac79ba2ac5d`.
- Device baseline: `com.matelink` 2.1.4/build 23 is installed, but it is `DEBUGGABLE`; preserve its Room/DataStore/session with same-signature `adb install -r` only.
- Production infrastructure is running, but `/readyz` returns `503 telemetry=mqtt_persistence_not_ready`; vehicle pairing has zero rows and Telemetry has produced zero `latest`, event, or route rows.
- Existing history is not production proof: 66 drives and 2 charges are all `local_import`; 62 drives are shorter than one minute, all 66 lack route and odometer evidence, and the newest drive is 2026-09-05 China time.
- Current source tests pass despite the data defects. Release JVM reports 490 tests, 0 failures/errors, 8 skipped. Full lint is blocked by Kotlin FIR analysis failures; production-only lint additionally reports one missing Chinese translation.
- Do not delete current server or phone records. Quarantine invalid evidence first, take a recoverable backup before deployment, and require separate Jovi authorization for commit, push, server apply, APK installation, or destructive cleanup.
- Retention assumption: the later two-data-day cloud bridge supersedes the older “never upload history” wording. Update the ADR/privacy text in the same delivery. If Jovi rejects bounded server retention, stop before Task 6 and redesign capture rather than silently changing this assumption.

## Current interface verdict

| Interface | Current verdict | Required outcome |
|---|---|---|
| `GET /api/v1/cars` | Runtime 200; vehicle identity/metadata is connected | Keep; add observed timestamp/build provenance |
| `GET /api/v1/cars/{id}/status` | Runtime 200; on-demand Fleet path works, but position is currently absent and Android duplicates requests | One bounded request; nullable fields and source timestamps preserved |
| `GET /api/matelink/v1/cars/{id}/snapshot` | Runtime 200 but falls back to Fleet because Telemetry tables are empty | Prefer fresh Telemetry, then one Fleet fallback; complete field-source metadata |
| Pairing/configure/readiness | Services exist, but production API runs old no-Bearer configure code; pairing table is empty | Current-token signed configure, persisted failure reason, `config_synced=true`, first-event state |
| `GET /drives` and detail | 200 but contains fragmented `local_import`; address is always null and route empty | Observed session with km units, route, first/last coordinates, honest quality/source |
| `GET /charges` and detail | 200 but only two imported energy summaries; no real electrical trace | Observed AC/DC/unknown type and real power/current/energy fields; no generated points |
| `GET /charges/current` | Fleet fallback discards the status fields it already fetched | Return the complete live charging snapshot with freshness |
| Battery health | Server explicitly returns unsupported; Android estimates can look authoritative | Show unavailable until measured inputs exist; derived result must be labelled |
| Standby/parked/updates | Standby is local collecting fallback; parked and updates are not implemented | Keep explicit unsupported/collecting semantics; never report connected |
| Stats/efficiency/range/cost | Algorithms run, but inputs contain fabricated trips/charges and UTC/date errors | Consume only qualified evidence; show coverage and unavailable states |

### Task 1: Create an isolated execution baseline and record RED evidence

**Files:**
- Modify during execution: `tasks/todo.md`
- Reference: `docs/ANDROID-RELEASE-LOG.md`
- Reference: `docs/ARCH-DECISION-2026-08-28-route3-fleet-multitenant.md`

- [ ] **Step 1: Create an isolated worktree from the exact baseline**

Use the `using-git-worktrees` skill. Name the branch `codex/matelink-data-integrity-20260906`. Do not develop in another existing worktree and do not reset/stash/clean the owner checkout.

- [ ] **Step 2: Reconfirm the baseline**

Run:

```powershell
git rev-parse HEAD
git status --short --branch
```

Expected: HEAD `db35155b80e12b9a1e641e8181dbeac79ba2ac5d`; no tracked or untracked changes before the task ledger entry.

- [ ] **Step 3: Add this plan as the active checklist in `tasks/todo.md`**

Record the exact source commit, device version, server `/healthz` and `/readyz`, aggregate table counts, and the evidence boundary. Do not paste a token, VIN, key, precise coordinate, address, or raw log.

- [ ] **Step 4: Capture failing behavioral tests before implementation**

The first RED set must prove: official enum strings are not recognized, miles are not converted, invalid coordinates replace cache, snapshot engines manufacture records, missing phases become DC, and offset timestamps group into the wrong China date.

### Task 2: Normalize the official Tesla Telemetry protocol and units

**Files:**
- Create: `deploy/jourvolt-dev-mock/telemetry_normalization.go`
- Create: `deploy/jourvolt-dev-mock/telemetry_normalization_test.go`
- Modify: `deploy/jourvolt-dev-mock/telemetry_core.go`
- Modify: `deploy/jourvolt-dev-mock/telemetry_service.go`

- [ ] **Step 1: Write official-wire RED tests**

Use Tesla’s actual enum names and units, not shortened fixtures:

```go
func TestCanonicalGearAcceptsOfficialProtoNames(t *testing.T) {
    cases := map[string]string{
        "ShiftStateD": "D", "ShiftStateR": "R",
        "ShiftStateP": "P", "ShiftStateN": "N",
    }
    for input, want := range cases {
        if got, ok := canonicalGear(input); !ok || got != want {
            t.Fatalf("canonicalGear(%q)=(%q,%v), want (%q,true)", input, got, ok, want)
        }
    }
}

func TestCanonicalDetailedChargeStateAcceptsOfficialProtoNames(t *testing.T) {
    if got := canonicalDetailedChargeState("DetailedChargeStateCharging"); got != "charging" {
        t.Fatalf("state=%q", got)
    }
    if got := canonicalDetailedChargeState("DetailedChargeStateComplete"); got != "complete" {
        t.Fatalf("state=%q", got)
    }
}

func TestTelemetryMilesBecomeKilometres(t *testing.T) {
    if got := milesToKilometres(1); math.Abs(got-1.609344) > 1e-9 { t.Fatalf("got %v", got) }
}
```

Run:

```powershell
go test ./... -run 'TestCanonical|TestTelemetryMiles' -count=1
```

Expected before implementation: FAIL because the canonical functions do not exist or official strings are rejected.

- [ ] **Step 2: Implement one normalization boundary**

`telemetry_normalization.go` owns all wire normalization. Return null/invalid instead of defaulting unknown enums:

```go
func canonicalGear(value any) (string, bool)
func canonicalDetailedChargeState(value any) string
func normalizeTelemetryNumber(field string, milesValue float64) float64
```

Normalize `VehicleSpeed`, `Odometer`, `EstBatteryRange`, and `RatedRange` from miles/mph into km/km/h before values enter latest-state or session machines. Accept official names, numeric protobuf enum values, and existing short names only as backward compatibility.

- [ ] **Step 3: Expand the requested field set**

Add `ACChargingEnergyIn`, `DCChargingEnergyIn`, `ACChargingPower`, `DCChargingPower`, `ChargeAmps`, `ChargerPhases`, `ChargeCurrentRequest`, `ChargeCurrentRequestMax`, `TimeToFullCharge`, `FastChargerPresent`, `PackVoltage`, and `PackCurrent` with conservative intervals. Do not invent a `ChargeVoltage` field because it is not in the official schema.

- [ ] **Step 4: Attach contemporaneous values to route/session events**

The session state machine must retain the last normalized speed, heading, power and odometer. A `Location` event creates a route point using only values whose timestamps are within the configured freshness window. Charging energy is the observed delta of the appropriate AC/DC cumulative field; it must never be calculated from nominal battery capacity.

- [ ] **Step 5: Merge partial Telemetry with one Fleet fallback by field**

`currentVehicleStatus` must not return an otherwise empty status merely because one Telemetry field is fresh. Fetch Fleet at most once, then overlay each fresh Telemetry field together with its own timestamp/source. A missing Telemetry coordinate retains a usable Fleet coordinate; a stale Telemetry field cannot overwrite a newer Fleet value.

- [ ] **Step 6: Run focused and full Go gates**

```powershell
go test ./... -run 'TestCanonical|TestTelemetryMiles|TestOfficialTelemetry' -count=1
go test ./... -count=1
go vet ./...
```

Expected: all PASS; fixtures use `ShiftStateD`, `ShiftStateP`, `DetailedChargeStateCharging`, and `DetailedChargeStateComplete`.

### Task 3: Repair Telemetry configure, pairing diagnostics, and deployment identity

**Files:**
- Modify: `deploy/jourvolt-dev-mock/telemetry_service.go`
- Modify: `deploy/jourvolt-dev-mock/telemetry_http.go`
- Modify: `deploy/jourvolt-dev-mock/main.go`
- Modify: `deploy/jourvolt-dev-mock/docker-compose.pilot.ecs.yml`
- Test: `deploy/jourvolt-dev-mock/telemetry_api_red_test.go`

- [ ] **Step 1: Preserve the current main Bearer-token implementation with RED coverage**

Add tests that assert: the current user token is sent to the private command proxy; an unavailable token causes zero outbound requests; a 401 refreshes once and retries once; the token never appears in logs or persisted tables.

```go
func TestConfigureWithoutUserTokenMakesNoProxyRequest(t *testing.T) {
    calls := 0
    service := configuredTelemetryServiceForTest(func(*http.Request) (*http.Response, error) {
        calls++
        return nil, errors.New("unexpected request")
    })
    service.currentUserAccessToken = func(context.Context, string) (string, error) {
        return "", errTeslaReauthorization
    }
    if err := service.configure(context.Background(), "user-a", 1); !errors.Is(err, errTelemetryPermission) {
        t.Fatalf("err=%v", err)
    }
    if calls != 0 { t.Fatalf("proxy calls=%d", calls) }
}
```

- [ ] **Step 2: Persist every configure outcome**

Write `configuring` before the proxy call. Persist `permission_required`, `pairing_required`, `waiting_vehicle`, `available`, or `telemetry_error` for token, CA, transport, HTTP, skipped-vehicle and sync-check failures. Store only the classified error, never response bodies or credentials.

- [ ] **Step 3: Separate infrastructure readiness from first vehicle evidence**

`/healthz` is process/database liveness. `/readyz` returns 503 only when MQTT is disconnected, unsubscribed, or the schema is unavailable. A healthy subscriber with no first vehicle event returns 200 with `telemetry="awaiting_first_event"`. Per-vehicle pairing and last-event status stays in `/data-readiness`.

- [ ] **Step 4: Expose build provenance**

Add non-secret `build_sha` and `build_time` to health responses and label the Docker image with the same SHA. Tests assert the response SHA equals the built binary’s injected value.

- [ ] **Step 5: Preserve the secret boundary**

Keep the rendered Telemetry configuration on shared tmpfs, keep mode `0600`, keep MQTT and command proxy on the internal network, and expose only mTLS 4443. Compose tests must reject host bind mounts and `0640`/world-readable files.

### Task 4: Remove synthetic trip/charge generation and make history reads pure

**Files:**
- Modify: `android/app/src/main/java/com/matelink/ui/screens/dashboard/DashboardViewModel.kt`
- Modify: `android/app/src/main/java/com/matelink/data/repository/UnifiedHistoryRepository.kt`
- Delete after references are removed: `android/app/src/main/java/com/matelink/data/sync/SnapshotTripEngine.kt`
- Delete after references are removed: `android/app/src/main/java/com/matelink/data/sync/SnapshotChargeEngine.kt`
- Modify: `android/app/src/main/java/com/matelink/data/local/dao/DriveSummaryDao.kt`
- Modify: `android/app/src/main/java/com/matelink/data/local/dao/ChargeSummaryDao.kt`

- [ ] **Step 1: Write RED truthfulness tests**

Tests must fail if production source contains `Math.random`, fixed `60.0` kWh capacity, `145.0` Wh/km, default `65` km/h, temperatures `28/22/25`, fixed charging prices, generated 20-point curves, hard-coded date `2026-09-05`, or generic station names used as observed data.

- [ ] **Step 2: Stop producing local pseudo-history**

Remove `recordSnapshot` calls from Dashboard polling and remove `consolidateFragmentedDrives`, `sanitizeExistingDrives`, `ensureBackfillToday`, and `reconstructChargesFromDrives` from read paths. Do not replace them with another foreground-only collector; real background history comes from Fleet Telemetry.

- [ ] **Step 3: Make `UnifiedHistoryRepository.load` read-only**

Remove `-1/1/2` cross-copy loops, synthetic backfill, and `cloud:fallback:car:<numeric>`. A failed cloud identity lookup returns `history_identity_unavailable`; it must not silently select another car’s archive.

- [ ] **Step 4: Scope every DAO mutation**

Replace unscoped deletes with composite-key operations:

```kotlin
@Query("DELETE FROM drives_summary WHERE carId = :carId AND driveId IN (:driveIds)")
suspend fun deleteDrivesByIds(carId: Int, driveIds: List<Int>)

@Query("DELETE FROM charges_summary WHERE carId = :carId AND chargeId = :chargeId")
suspend fun deleteCharge(carId: Int, chargeId: Int)
```

Add two-car tests proving a matching numeric ID in another car is untouched.

- [ ] **Step 5: Remove fake fallback vehicle metadata**

Delete the hard-coded `Jovi大鼠标`/Model Y fallback. Cached status may remain visible only with its original verified vehicle identity and observed timestamp; otherwise show an explicit unavailable state.

### Task 5: Add evidence quality and quarantine legacy pollution without deletion

**Files:**
- Create: `android/app/src/main/java/com/matelink/domain/history/HistoryQuality.kt`
- Modify: `android/app/src/main/java/com/matelink/data/local/entity/DriveSummary.kt`
- Modify: `android/app/src/main/java/com/matelink/data/local/entity/ChargeSummary.kt`
- Modify: `android/app/src/main/java/com/matelink/data/local/StatsDatabase.kt`
- Modify: `deploy/jourvolt-dev-mock/telemetry_store.go`
- Modify: `deploy/jourvolt-dev-mock/telemetry_import.go`

- [ ] **Step 1: Define a small shared quality vocabulary**

Use wire strings `observed`, `derived`, `incomplete`, and `quarantined`. `source` remains separate (`fleet_api`, `telemetry_mqtt`, `local_import`, `user_entered`). Unknown source is not observed.

- [ ] **Step 2: Add Room v20 quality columns**

Add `qualityState` and `qualityReason` with default `incomplete`. Migration rules:

```sql
UPDATE drives_summary
SET qualityState='quarantined', qualityReason='legacy_synthetic_drive'
WHERE energySource IN ('snapshot_session','snapshot_consolidated','snapshot_estimate','physical_model');

UPDATE charges_summary
SET qualityState='quarantined', qualityReason='legacy_synthetic_charge'
WHERE apiEvidence IN ('snapshot_charge','inferred_soc_jump');
```

Rows are preserved. Normal DAO queries exclude `quarantined`; a diagnostic count remains available.

- [ ] **Step 3: Add server-side quality columns**

Add the same fields to `jourvolt_telemetry_sessions`. Mark existing `local_import` rows without route and without odometer evidence as `quarantined/legacy_import_without_evidence`. API list/detail excludes quarantined records by default and reports `quarantined_count` in metadata.

- [ ] **Step 4: Prevent import echo and low-quality upload**

`HistoryUploadFilter` uploads only phone-origin rows carrying explicit observed provenance. Rows pulled from the server, synthesized legacy rows, blank evidence, and quarantined rows are never re-uploaded. Use stable `(user, vehicle, kind, source_session_id)` idempotency.

- [ ] **Step 5: Compute real coverage**

`historySessionMap` returns the session’s actual `source` and field coverage. Never hard-code `source=telemetry_mqtt` or `coverage_percent=100`. A drive with times only is `incomplete`, not available for efficiency analysis.

- [ ] **Step 6: Make retention source-safe**

The bounded-retention query may delete only rows governed by the same retention policy. Import cleanup is limited to `source='local_import'`; it must never delete `telemetry_mqtt` sessions. Telemetry retention is a separate explicit policy and test. Use `Asia/Shanghai` data-day boundaries.

### Task 6: Repair live position, AMap coordinate handling, and address enrichment

**Files:**
- Create: `android/app/src/main/java/com/matelink/domain/map/VehiclePositionResolver.kt`
- Create: `android/app/src/main/java/com/matelink/data/repository/AmapCoordinateTransformer.kt`
- Modify: `android/app/src/main/java/com/matelink/ui/screens/dashboard/DashboardViewModel.kt`
- Modify: `android/app/src/main/java/com/matelink/ui/screens/map/AmapMapViewModel.kt`
- Modify: `android/app/src/main/java/com/matelink/ui/screens/map/AmapMapView.kt`
- Modify: `android/app/src/main/java/com/matelink/data/repository/AmapReverseGeocoder.kt`

- [ ] **Step 1: Write RED position-precedence tests**

Cover adapter, Fleet fallback and cache with null, one-sided, `(0,0)`, NaN, infinity, out-of-range and valid pairs. An invalid successful response must not erase a valid cached position. Position source and observed timestamp move together.

- [ ] **Step 2: Implement one resolver**

```kotlin
data class VehiclePosition(
    val latitude: Double,
    val longitude: Double,
    val source: String,
    val observedAt: Instant?
)

fun resolveVehiclePosition(
    telemetry: VehiclePosition?,
    fleet: VehiclePosition?,
    cached: VehiclePosition?
): VehiclePosition? = listOf(telemetry, fleet, cached).firstOrNull { it?.isUsable() == true }
```

Dashboard and map page must use this resolver. Do not call `/status` a second time merely because `/snapshot` omitted latitude; the snapshot response must already describe its Fleet fallback or the repository performs one bounded fallback.

Add a per-car in-flight request coalescer and state-aware interval: driving 8–10 seconds, charging 15 seconds, asleep/unavailable at least 60 seconds with exponential backoff. A 408 must not cause two immediate `vehicle_data` calls; verify the previous 24-hour 408 storm cannot recur.

- [ ] **Step 3: Make the map page reactive**

Observe the shared vehicle-status flow, refresh on car change, and update when a position arrives after initial composition. A successful status object with invalid coordinates must fall back to a valid cached position.

- [ ] **Step 4: Normalize GPS coordinates once**

For China coordinates, convert Tesla GPS/WGS84 with AMap `CoordinateConverter(...).from(CoordType.GPS)` before both marker rendering and reverse geocoding. Outside AMap’s supported region, keep the original WGS84 point. Store coordinate-system metadata and prevent a GCJ-02 point from being converted twice.

- [ ] **Step 5: Enrich history from route endpoints**

The server returns start/end coordinates from the first/last valid route point. Android resolves them independently through AMap and stores the two addresses locally. No coordinate produces `位置数据未采集`, not `未知 → 未知` and not an empty string marked as processed.

- [ ] **Step 6: Remove location leakage**

Remove precise coordinates and resolved addresses from `AmapReverseGeocoder` logs. Log only result class and a non-reversible request correlation ID.

### Task 7: Make current and historical charging truthful

**Files:**
- Modify: `deploy/jourvolt-dev-mock/telemetry_http.go`
- Modify: `deploy/jourvolt-dev-mock/telemetry_service.go`
- Modify: `android/app/src/main/java/com/matelink/ui/screens/charges/ChargeStatsCalculator.kt`
- Modify: `android/app/src/main/java/com/matelink/ui/screens/charges/ChargeDetailViewModel.kt`
- Modify: `android/app/src/main/java/com/matelink/ui/screens/charges/CurrentChargeViewModel.kt`

- [ ] **Step 1: Introduce tri-state charge type**

Replace boolean DC inference with:

```kotlin
enum class ChargeType { AC, DC, UNKNOWN }
```

`chargerPhases == null` is `UNKNOWN`, never DC. Prefer explicit `FastChargerPresent`, AC/DC power/energy field presence, then phases. Conflicting inputs remain unknown and are logged only as a safe diagnostic.

- [ ] **Step 2: Preserve live Fleet fields in `/charges/current`**

When no open Telemetry session exists but Fleet says charging, map the fetched battery level, energy added, power, current, phases, requested/max current, port state, limit SOC and time-to-full into the response. Do not create an empty `telemetrySession` whose start time is “now”.

- [ ] **Step 3: Remove generated charge curves**

Delete `synthesizeChargePoints`. With fewer than two observed points, the UI shows “充电曲线数据未采集”. Never generate 20 points, 220/400 V, taper, current, outside temperature, connector type, brand, or fast-charger status.

- [ ] **Step 4: Separate observed cost from estimate**

Persist only provider/user-entered cost as actual. If an estimate is shown, return a separate nullable derived value with the tariff source and `DERIVED` label; do not write 1.10/1.50 rates into the observed summary.

- [ ] **Step 5: Add AC, DC, unknown and incomplete tests**

Cover an actual AC sample, actual DC sample, missing phases, conflicting fields, completed charge with only energy, and no electrical points. Charts and AC/DC totals must exclude unknown records rather than assigning them to DC.

- [ ] **Step 6: Align the Android route-point number types**

Change route speed/power models to accept the server’s normalized decimal values (`Double?`). Convert only at the presentation boundary; Moshi must not reject a real fractional speed.

### Task 8: Correct history timestamps, readiness and analytics inputs

**Files:**
- Modify: `android/app/src/main/java/com/matelink/util/DateTimeParse.kt`
- Modify: `android/app/src/main/java/com/matelink/domain/analytics/HistorySummaryMapper.kt`
- Modify: `android/app/src/main/java/com/matelink/ui/screens/drives/DrivesViewModel.kt`
- Modify: `android/app/src/main/java/com/matelink/ui/screens/charges/ChargesViewModel.kt`
- Modify: `deploy/jourvolt-dev-mock/readiness.go`
- Modify: `deploy/jourvolt-dev-mock/telemetry_import.go`

- [ ] **Step 1: Centralize time parsing**

Parse RFC3339/offset timestamps to `Instant`, then convert to `ZoneId.systemDefault()` for Android grouping and `Asia/Shanghai` for the server’s two-data-day retention. Never use `LocalDateTime.parse` directly on a `Z`/offset timestamp and never take an `OffsetDateTime` date before zone conversion.

- [ ] **Step 2: Write midnight boundary tests**

Use `2026-09-05T16:30:00Z`, which is 2026-09-06 00:30 in China. Assert it belongs to September 6 in drive, charge, efficiency, range, cost, report and retention paths.

- [ ] **Step 3: Split drive and charge readiness**

Compute `hasDriveHistory` and `hasChargeHistory` independently. One kind must not make the other `available`.

- [ ] **Step 4: Enforce the complete HTTP resource contract**

For every Retrofit route, add method/path/auth/vehicle-ownership/response-shape tests. `/cars/{id}` returns only that owned car; list/detail/current routes reject unsupported methods. `startDate`/`endDate` filter PostgreSQL before pagination, and pagination metadata reflects the filtered set. Remove the authenticated `/api/readyz` ping alias or rename it so it cannot be confused with service readiness.

- [ ] **Step 5: Gate every analysis on evidence quality**

Stats, efficiency, range, cost, battery, standby, TPMS trends and reports consume only observed/qualified rows. Missing distance, energy, cost, coordinates or sample coverage stays null/unavailable. Battery health remains unsupported unless measured capacity evidence exists.

- [ ] **Step 6: Add cross-screen consistency tests**

The same record set must produce the same valid sample counts and totals on summary cards, detail, reports and recommendations. Quarantined rows contribute zero samples, not zero-valued measurements.

### Task 9: Restore release-package isolation and close static gates

**Files:**
- Modify: `android/app/build.gradle.kts`
- Modify: `android/app/src/main/res/values-zh/strings.xml`
- Modify: `docs/SOP-ANDROID-DEVICE-DEPLOYMENT-AND-VERIFICATION.md`
- Modify: `docs/ANDROID-RELEASE-LOG.md`

- [ ] **Step 1: Restore a safe debug identity**

Debug defaults to `applicationIdSuffix = ".test.mock"` and app name `MateLink Test`. Only release may use `com.matelink`. Add a build assertion that a release APK is not debuggable and that a debug APK cannot overwrite the owner package.

Set the release backup policy to exclude the session, encrypted preferences, AMap key material, Room database, and precise vehicle history. Prefer `allowBackup=false` for the owner package unless an encrypted, tested restore design is added in a separate change.

- [ ] **Step 2: Fix the current localization error**

Add the Chinese `tpms_trend_conclusion_insufficient` value and rerun production lint.

- [ ] **Step 3: Resolve the full-lint FIR crash without suppressing product lint**

Reproduce with `LINT_PRINT_STACKTRACE=true`. Keep production-source lint enabled. If the crash remains limited to test-source UAST, isolate the triggering test declaration or align Kotlin/AGP versions; do not add a lint baseline and do not globally disable lint. Record the exact workaround and upstream tool version.

- [ ] **Step 4: Prepare 2.1.5/build 24**

Increment version only after all source tests pass. Build a signed release through `build-pilot-apk.ps1`; verify `com.matelink`, non-debuggable flag, signing certificate match, three production URLs, and absence of Mock/loopback markers.

- [ ] **Step 5: Correct the evidence ledger**

Record 490 tests with 8 skipped accurately; do not write “490/490 passed”. Separate source test, built APK, installed package, server runtime and real vehicle evidence.

### Task 10: Verify locally, deploy exact bytes, and run one real data loop

**Files:**
- Update after evidence exists: `tasks/todo.md`
- Update after evidence exists: `tasks/lessons.md`
- Update after evidence exists: `docs/ANDROID-RELEASE-LOG.md`
- Update after evidence exists: `docs/ARCH-DECISION-2026-08-28-route3-fleet-multitenant.md`

- [ ] **Step 1: Run the complete local gate**

```powershell
go test ./... -count=1
go vet ./...
docker compose -f docker-compose.yml config
docker compose -f docker-compose.pilot.ecs.yml --profile telemetry config
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
git diff --check
```

Expected: zero failures/errors; skips reported explicitly; no fabricated-value contract match; no `MissingTranslation`; full lint completes without FIR crash.

- [ ] **Step 2: Review the exact diff before any external action**

Use code-review-graph `detect_changes`, `get_affected_flows`, impact radius and tests-for when available. Verify no secret, key, token, VIN, address, coordinate, APK, database or binary enters Git. Obtain Jovi’s separate commit/push/deploy/install authorization.

- [ ] **Step 3: Back up and quarantine production data**

Take a timestamped `pg_dump` and record its SHA-256. Apply only the additive quality migration and quarantine update; do not delete the 66 drive/2 charge rows. Verify counts before/after and that default APIs exclude quarantined rows.

- [ ] **Step 4: Deploy an exact source closure**

Build the API image from the approved commit; compare normalized hashes for all Go/module/Compose inputs; deploy by digest. `/healthz` and `/readyz` must return the expected `build_sha`. Preserve tmpfs and existing PostgreSQL volume.

- [ ] **Step 5: Complete the human-only Tesla activation**

Jovi adds/verifies the virtual key and taps the explicit Telemetry configure action. Required evidence: pairing row exists, `config_synced=true`, Fleet Telemetry logs `socket_connected`, MQTT application publish count increases, and PostgreSQL `telemetry_latest` receives fields. No credentials appear in evidence.

- [ ] **Step 6: Validate one real drive**

Acceptance requires: a new session dated today China time, duration and distance consistent with odometer, at least two valid route points, real first/last coordinates, AMap marker, two independently resolved addresses, no micro-session fragments, and source `telemetry_mqtt/observed`.

- [ ] **Step 7: Validate charging without fabrication**

During the next available real charge, verify current SOC, energy, power/current, phase/type and freshness. If AC/DC evidence or two time points are absent, the UI must show unknown/unavailable and no generated curve. A second AC or DC session is a later qualification item, not a reason to fabricate data.

- [ ] **Step 8: Replace the debuggable owner package safely**

Verify candidate/device package and signing certificate match, then use only `adb install -r` after Jovi’s install authorization. Confirm first-install time and Room/config/session preservation. Do not run instrumentation, uninstall, or clear data.

- [ ] **Step 9: Final truth audit**

For cars, status, snapshot, readiness, drives, drive detail, charges, current charge, charge detail, battery, standby, efficiency, range, cost, TPMS and reports, record one of: `RUNTIME_OBSERVED`, `DERIVED_FROM_OBSERVED`, `UNAVAILABLE`, or `NOT_PERFORMED`. “HTTP 200” alone is not a data-correctness pass.
