# History and Analytics Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace partial and inconsistent history analytics with one complete, privacy-safe analysis data layer and truthful UI across driving, charging, efficiency, range, standby, and annual reports.

**Architecture:** Add a normalized `AnalysisHistoryRepository` that owns complete remote history, paging, de-duplication, windows, coverage, and no-data states. View models consume repository snapshots; local manual charge totals remain in DataStore keyed by car/charge. Keep the existing five analysis screens, but remove obsolete More entries and route driving history to individual drives.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Retrofit, Room/summary DAOs, DataStore, JUnit JVM tests, Gradle Debug APK.

---

### Task 1: Freeze the current baseline and add the implementation test harness

**Files:**
- Modify: `tasks/todo.md`
- Modify: `tasks/lessons.md`
- Test: `android/app/src/test/java/com/matelink/analytics/AnalysisHistoryRepositoryTest.kt`

- [ ] **Step 1: Record the baseline and clean index**

Run:

```powershell
git branch --show-current
git rev-parse HEAD
git status --short
```

Expected: branch `codex/app-mimo-ui-optimization`, HEAD `fc5b285aae45d5985a565c30b0554eb28ef805f0`, and no feature edits.

- [ ] **Step 2: Write failing history contracts**

Cover complete history, duplicate source IDs, date windows, empty-state reasons, and percentile direction using pure data fixtures. The first failing assertion must prove that a default seven-day window cannot be used as all history.

- [ ] **Step 3: Run the focused test**

```powershell
cd android
.\gradlew.bat :app:testDebugUnitTest --tests "com.matelink.analytics.AnalysisHistoryRepositoryTest"
```

Expected: FAIL because the repository and contracts do not exist.

### Task 2: Implement complete normalized analysis history

**Files:**
- Create: `android/app/src/main/java/com/matelink/domain/analytics/AnalysisHistory.kt`
- Create: `android/app/src/main/java/com/matelink/domain/analytics/AnalysisHistoryRepository.kt`
- Modify: `android/app/src/main/java/com/matelink/data/repository/TeslamateRepository.kt`
- Modify: `android/app/src/main/java/com/matelink/data/api/TeslaMateApi.kt`
- Test: `android/app/src/test/java/com/matelink/analytics/AnalysisHistoryRepositoryTest.kt`

- [ ] **Step 1: Add explicit normalized records and coverage**

Define immutable drive/charge records with source ID, dates, energy, distance, battery/range fields, cost, and provenance; add `HistoryCoverage` and `AnalysisWindow` with `ALL`, `LAST_90_DAYS`, `SUMMER`, `WINTER`, and `CUSTOM`.

- [ ] **Step 2: Add paged API reads**

Keep the existing API query parameters and add repository paging that requests page 1, then increments until the response is empty or the reported total is reached. Never silently stop after one page. De-duplicate by `driveId` or `chargeId` before returning.

- [ ] **Step 3: Implement window and no-data rules**

Use local calendar boundaries. Return `NoDataReason.NO_RECORDS`, `INSUFFICIENT_COVERAGE`, or `SOURCE_UNAVAILABLE` instead of zero-filled metrics. Preserve cached records with a freshness label when the network fails.

- [ ] **Step 4: Run history tests to green**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.matelink.analytics.AnalysisHistoryRepositoryTest"
```

Expected: PASS, including duplicate removal, all-history selection, and window boundaries.

### Task 3: Change charge overrides from unit price to per-session total amount

**Files:**
- Create: `android/app/src/main/java/com/matelink/domain/analytics/ManualChargeAmount.kt`
- Modify: `android/app/src/main/java/com/matelink/data/local/SettingsDataStore.kt`
- Modify: `android/app/src/main/java/com/matelink/ui/screens/charges/ChargeDetailViewModel.kt`
- Modify: `android/app/src/main/java/com/matelink/ui/screens/charges/ChargeDetailScreen.kt`
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/res/values-zh/strings.xml`
- Test: `android/app/src/test/java/com/matelink/charges/ManualChargeAmountTest.kt`

- [ ] **Step 1: Write failing precedence tests**

Assert that a finite non-negative manual total in yuan wins over TeslaMate cost, null restores the source, and invalid/negative/NaN/Infinity values are rejected.

- [ ] **Step 2: Store a total keyed by car and charge ID**

Use a new key namespace so existing unit-price values are not interpreted as yuan totals. Do not delete old keys; ignore them after migration and label the new value as manual total.

- [ ] **Step 3: Replace the dialog and labels**

Use `修改总价`, `本次充电总价`, `输入人民币金额`, and `总价已保存`; display `¥` and do not show “每度单价” on the charge-detail override path.

- [ ] **Step 4: Run charge tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.matelink.charges.ManualChargeAmountTest"
```

Expected: PASS with the precedence matrix.

### Task 4: Route More to complete driving history and remove obsolete entries

**Files:**
- Modify: `android/app/src/main/java/com/matelink/ui/screens/more/MoreScreen.kt`
- Modify: `android/app/src/main/java/com/matelink/ui/navigation/NavGraph.kt`
- Modify: `android/app/src/main/java/com/matelink/ui/screens/drives/DrivesViewModel.kt`
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/res/values-zh/strings.xml`
- Test: `android/app/src/test/java/com/matelink/ui/screens/drives/DrivesHistoryContractTest.kt`

- [ ] **Step 1: Write the navigation and default-window regression test**

Assert that the More action is named `行程历史`, navigates to `Screen.Drives`, and the initial date filter is `ALL_TIME`. Assert that long-distance trips uses a separate label.

- [ ] **Step 2: Implement routing and labels**

Pass a new `onNavigateToDrives` callback to More, remove 3D and Current charge actions, and keep the existing Trips route only under `长途旅程` if retained.

- [ ] **Step 3: Make all-history the default without breaking saved filters**

Change only the default `DriveDateFilter` fallback to `ALL_TIME`; preserve a user-selected filter in `SavedStateHandle`.

- [ ] **Step 4: Run navigation tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.matelink.ui.screens.drives.DrivesHistoryContractTest"
```

Expected: PASS.

### Task 5: Rebuild efficiency, cost, range, and standby view-model metrics

**Files:**
- Modify: `android/app/src/main/java/com/matelink/ui/screens/efficiency/EfficiencyViewModel.kt`
- Modify: `android/app/src/main/java/com/matelink/ui/screens/cost/CostViewModel.kt`
- Modify: `android/app/src/main/java/com/matelink/ui/screens/range/RangeViewModel.kt`
- Modify: `android/app/src/main/java/com/matelink/ui/screens/vampire/VampireViewModel.kt`
- Create: `android/app/src/main/java/com/matelink/domain/analytics/Percentile.kt`
- Create: `android/app/src/main/java/com/matelink/domain/analytics/StandbyAttribution.kt`
- Test: `android/app/src/test/java/com/matelink/analytics/PercentileTest.kt`
- Test: `android/app/src/test/java/com/matelink/analytics/StandbyAttributionTest.kt`

- [ ] **Step 1: Test percentile direction and sample counts**

For sorted consumption `[100, 120, 150, 200]`, assert the highest is 100%, the lowest is 0%, and a user value reports its exact rank/sample count. Lower Wh/km must be marked better.

- [ ] **Step 2: Test standby evidence boundaries**

Assert that a parked interval with battery loss is reported as unknown cause without an explicit sentinel/overheat/climate event, and that an event-backed interval receives only its recorded cause.

- [ ] **Step 3: Consume the normalized repository**

Efficiency computes all-history, 90-day, summer, winter, speed bins, per-drive rows, personal percentile, and public benchmark percentile. Cost uses effective manual totals. Range reports rated-range loss versus actual distance. Standby analyzes every eligible interval and exposes coverage.

- [ ] **Step 4: Run metric tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.matelink.analytics.PercentileTest" --tests "com.matelink.analytics.StandbyAttributionTest"
```

Expected: PASS.

### Task 6: Rebuild the five analysis screens and annual reports

**Files:**
- Modify: `android/app/src/main/java/com/matelink/ui/screens/efficiency/EfficiencyScreen.kt`
- Modify: `android/app/src/main/java/com/matelink/ui/screens/cost/CostScreen.kt`
- Modify: `android/app/src/main/java/com/matelink/ui/screens/range/RangeScreen.kt`
- Modify: `android/app/src/main/java/com/matelink/ui/screens/vampire/VampireScreen.kt`
- Modify: `android/app/src/main/java/com/matelink/ui/screens/reports/AnnualReportViewModel.kt`
- Modify: `android/app/src/main/java/com/matelink/ui/screens/reports/AnnualReportScreen.kt`
- Modify: `android/app/src/main/java/com/matelink/data/repository/StatsRepository.kt`
- Test: `android/app/src/test/java/com/matelink/reports/AnnualReportYearContractTest.kt`

- [ ] **Step 1: Add current/prior-year failing contract**

For a clock date in 2026, assert available report years contain 2026 and 2025 even if one year has no records; assert empty state is not numeric zero.

- [ ] **Step 2: Implement report year source**

Derive current and prior years from `Clock.systemDefaultZone()`, merge with historical years from the normalized snapshot, and label missing years explicitly.

- [ ] **Step 3: Render truthful cards and charts**

Use compact cards for totals, trends, percentile band, coverage, source, and no-data messages. Keep route strings and dates in Chinese for the active locale.

- [ ] **Step 4: Run report tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.matelink.reports.AnnualReportYearContractTest"
```

Expected: PASS.

### Task 7: Full verification, APK versioning, install, and device smoke test

**Files:**
- Modify: `android/app/build.gradle.kts`
- Modify: `tasks/todo.md`
- Modify: `tasks/lessons.md`

- [ ] **Step 1: Bump version**

Increase versionCode by one and versionName from `1.3.2` to `1.4.0` only after the feature tests pass.

- [ ] **Step 2: Run full checks**

```powershell
cd android
$env:ANDROID_HOME='C:\Users\Admin\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Expected: all tasks pass; report JVM test count and zero failures/errors.

- [ ] **Step 3: Verify diff and APK privacy hygiene**

Run `git diff --check`, inspect changed paths, and scan for secrets, `.env`, VINs, addresses, generated reports, and build outputs. Do not stage those artifacts.

- [ ] **Step 4: Preserve-data installation**

Run `adb install -r android/app/build/outputs/apk/debug/app-debug.apk` on serial `6e4fa92f`. Confirm package version and unchanged first-install timestamp; never uninstall, clear data, or run instrumentation.

- [ ] **Step 5: Device smoke test**

Launch normally and tap Dashboard, Drives, Charges, More, Efficiency, Cost, Range, Standby, and Annual Report. Confirm process remains alive and scan logcat for FATAL, ANR, Room/SQLite, format, and network-auth errors.

- [ ] **Step 6: Review and commit the implementation**

Update `tasks/todo.md` with exact test/build/install evidence, then commit only reviewed source, tests, docs, and version metadata. Do not push unless separately requested.

## Plan Self-Review

- Complete history, all-history default, and separate long-distance trips are covered by Tasks 2 and 4.
- Per-session yuan totals are covered by Task 3.
- Percentile direction, public sample counts, seasonal windows, and personal position are covered by Task 5.
- Standby evidence-only attribution is covered by Task 5.
- Current/prior annual reports and explicit no-data states are covered by Task 6.
- Obsolete More entries and privacy boundaries are covered by Tasks 4 and 7.
- No placeholder steps or unspecified file paths are used in the plan.

## 2026-08-21 Current Worktree Status

The implementation described by Tasks 2 through 6 is present in the current worktree and the focused contracts pass. The authoritative local evidence is:

- `AnalysisHistory.kt` and `AnalysisHistoryRepository.kt`: all-time/90-day/season/custom windows, source-ID de-duplication, paged history loading, and explicit no-record coverage.
- `ManualChargeAmount.kt` and `ChargeCostOverrideStore`: per-session manual total precedence and car/charge scoping.
- `DrivesViewModel.kt` and `MoreScreen.kt`: all-time drive history default and routing to the complete drives screen.
- `Percentile.kt`, `StandbyAttribution.kt`, `AnnualReportYears.kt`: transparent metric boundaries and dedicated contracts.
- Focused Gradle run on 2026-08-21: `AnalysisHistoryRepositoryTest`, `ManualChargeAmountTest`, `DrivesHistoryContractTest`, `PercentileTest`, `StandbyAttributionTest`, and `AnnualReportYearsTest` passed.
- Network failure handling now preserves the last successful in-process snapshot as `STALE` and shows a localized cache banner on efficiency, cost, range, standby, and annual report screens; no cached snapshot still remains an error.

Remaining items are intentionally not marked complete: real Fleet/Telemetry history, formal signing, public DNS/HTTPS, Tesla approval, server backup restore, and real-device acceptance. The existing Room drive/charge summaries now provide a persistent stale fallback across app processes; this implementation is covered by mapper/repository tests, but a process-death device E2E was not claimed. The current product status remains `APP STRUCTURE READY / LOCAL MOCK HISTORY PASS / REAL TESLA PILOT BLOCKED`.

## 2026-08-21 Local delivery closeout

- `HistorySummaryMapper.kt` normalizes stored Room drive/charge summaries into the shared analysis model while preserving unknown placeholders and real zero cost.
- `AnalysisHistoryRepository.kt` now uses the existing Room summaries after network failure, labels the result `STALE`, and lets a fresh server response replace it; no schema migration was introduced.
- `StatsScreen.kt` keeps the existing cards and visual language and adds a 96dp bottom content inset so the final recommendation action remains above the persistent navigation bar when scrolled to the end.
- Android evidence: `202` JVM tests, failures/errors/skips `0`; Debug, Debug AndroidTest, Release and Release lint passed; `MissingTranslation=0`, no lint baseline. Lint's remaining `256` issues are recorded as multi-language coverage/release-gate work, not runtime bugs.
- The unsigned `com.matelink` Release artifact is version `1.4.2`, SHA-256 `704593A8EEC463DBABCAF20E9BD338016708C9901CEC9906C9121A69F84972F1`. Real Tesla OAuth/Fleet, DNS/HTTPS, formal signing, server and physical-device acceptance remain external gates.
