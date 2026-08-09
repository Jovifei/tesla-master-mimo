# Git Commit Review - 2026-07-09

## Plan

- [x] Inspect current git status and repository ignore rules.
- [x] Classify untracked files as source/docs versus local environment or build output.
- [x] Add minimal ignore rules for files that should not be committed.
- [x] Stage only necessary repository files and create one commit.
- [x] Verify staged commit contents before commit.

## Findings

- `android/.gradle/` is Gradle cache/local state and should not be committed.
- `android/.idea/` is local IDE metadata for this checkout and should not be committed.
- `android/app/build/` is Android build output and should not be committed.
- `android/local.properties` is machine-local Android SDK configuration and should not be committed.

## Review

- `git status --short --ignored` shows only `.gitignore` and `tasks/` as untracked commit candidates.
- `git check-ignore -v` confirms `android/.gradle/`, `android/.idea/`, `android/app/build/`, and `android/local.properties` are ignored by the new root `.gitignore`.
- `git diff --cached --stat` shows only `.gitignore` and `tasks/todo.md` staged.

# App Completion Handoff - 2026-07-09

## Plan

- [x] Read docx handling guidance and app_mimo memory boundaries.
- [x] Inventory source documents from `docs/git_ref`, `docs/PLAN`, `docs/PRD`, the architecture doc, and the Word implementation plan.
- [x] Inspect current Android, iOS, shared, and web implementation status.
- [x] Assess feature completion, data configuration flow, and user guidance gaps.
- [x] Write phase handoff documentation under `app_mimo/docs`.
- [x] Verify generated docs and record remaining proof limits.

## Review

- Created `docs/PHASE-HANDOFF-2026-07-09.md`.
- Captured reviewed document list, completed/unfinished scope, platform status, data configuration model, and recommended user onboarding flow.
- Kept `E:/project/tesla_master/docs/git_ref/` read-only.
- Native Android/iOS build proof remains gated by local toolchain availability; this pass is source/document inspection plus documentation output.

# app_mimo Data Setup Implementation - 2026-07-09

## Plan

- [x] Create implementation branch.
- [x] Save implementation plan under `docs/superpowers/plans`.
- [x] Inspect Android/Web/iOS setup code and existing tests.
- [x] Add failing/targeted tests where feasible before behavior changes.
- [x] Implement Android stronger connection testing and first-run guidance.
- [x] Implement Web persisted real-data configuration and `/api/v1` paths.
- [x] Implement iOS source-level multi-instance management.
- [x] Update docs/handoff for completed and remaining work.
- [x] Run available verification and record proof limits.

## Review

- Branch: `codex/app-mimo-data-setup`.
- `E:/project/tesla_master/docs/git_ref/` remains read-only.
- Android unit test target was added for connection URL/outcome rules, but Gradle could not run because `JAVA_HOME`/`java` is unavailable.
- Web `npm run build` and `npm run lint` pass after restoring local `node_modules` with `npm install`.
- iOS multi-instance changes are source-level only on Windows; Xcode build/simulator proof remains required.

# Self-hosted TeslaMate Guidance - 2026-07-09

## Plan

- [x] Inspect current README, Settings, Onboarding, About, and map/key copy.
- [x] Update README with real-data deployment, server, security, TeslaMateApi, and AMap key guidance.
- [x] Update Android Settings/About strings for self-hosted TeslaMateApi-compatible API and AMap key ownership.
- [x] Update iOS Onboarding/Settings/About copy for API root address, server requirement, and AMap key ownership.
- [x] Update Web Onboarding/Settings/About copy for API root address, server requirement, and AMap key ownership.
- [x] Run available verification and record proof limits.
- [x] Report candidate files for Jovi approval before any Git staging or commit.

## Review

- Git staging/commit is explicitly blocked until Jovi approves the candidate file list.
- Implementation was delegated into bounded docs, Android, iOS, and Web slices, then integrated in the parent thread.
- Verification passed: `npm run lint`, `npm run build`, XML resource parsing, Web message JSON parsing, sensitive token/key scan, and `git diff --check`.
- Verification blocked: Android `testDebugUnitTest` cannot start because `JAVA_HOME` is unset and no `java` command is on PATH.
- iOS verification remains source-level on Windows; Xcode build/simulator proof requires Mac/Xcode.

# app_mimo Remaining Implementation Plan Update - 2026-07-09

## Plan

- [x] Read the existing data setup plan and current task record.
- [x] Rewrite the plan under `docs/superpowers/plans/2026-07-09-app-mimo-data-setup.md`.
- [x] Separate completed self-hosted/AMap guidance from unfinished verification and implementation work.
- [x] List remaining P0/P1/P2 tasks, proof boundaries, non-commit artifacts, and recommended commit scope.

## Review

- Updated the plan as a remaining-work handoff, not a stale original task list.
- Android native verification remains blocked until JDK/Gradle can run.
- Web real-data smoke still needs a reachable TeslaMateApi or MateLink-compatible API root address.
- iOS and Widget proof remains gated by Mac/Xcode and device/simulator validation.

# Android Resource Warning Cleanup - 2026-07-09

## Plan

- [x] Inspect the `assembleDebug` warning output and locate the missing default string names.
- [x] Restore default `values/strings.xml` entries for legacy Settings resource names still present in localized files.
- [x] Rebuild or re-run the same Android task to confirm the warnings are gone.

## Review

- The warnings come from legacy Settings string keys that still exist in `values-zh/strings.xml` but no longer have default entries in `values/strings.xml`.
- The fix is compatibility-focused: add default aliases in `values/strings.xml` that point at the current canonical `settings_*` strings where possible.
- Re-running `android\\.\\gradlew.bat :app:assembleDebug` with `JAVA_HOME='D:\\Program Files\\Android\\Android Studio\\jbr'` succeeded and the previous `removing resource ... without required default value` warnings did not reappear.

# User Deployment Guide - 2026-07-09

## Plan

- [x] Inspect current docs wording and existing self-hosted TeslaMate guidance.
- [x] Add a plain-language deployment and configuration guide under `app_mimo/docs`.
- [x] Cover server setup, exact app fields, correct input examples, and common misconfiguration cases.

## Review

- Added `docs/USER-DEPLOYMENT-SETUP-GUIDE.md` as a user-facing guide rather than an engineering handoff.
- The guide explains the full route from self-hosted TeslaMate to MateLink, includes Mermaid diagrams, and highlights the most common mistakes such as using Grafana or TeslaMate Web UI URLs instead of the API root.
- High-level AMap/Gaode guidance is included as optional map enhancement, not a blocker for showing core vehicle data.

# Home Docker TeslaMate Template - 2026-07-09

## Plan

- [x] Check Docker CLI, Docker Compose, active context, and running containers.
- [x] Start Docker Desktop from the installed desktop app when the service was not reachable from the terminal.
- [x] Confirm no TeslaMate-related compose file is present in `app_mimo`.
- [x] Add a home-host Docker Compose template for TeslaMate + TeslaMateApi-compatible access.
- [x] Ensure local `.env` secrets under deploy templates are ignored by Git.

## Review

- Docker Desktop was installed but the `desktop-linux` engine was initially unreachable because `com.docker.service` was stopped.
- Starting Docker Desktop made `docker ps` work; existing running containers are unrelated to TeslaMate.
- Added `deploy/teslamate-home-docker/docker-compose.yml`, `.env.example`, and `README.md`.
- The template uses placeholders only. No real Tesla token, API token, database password, AMap/Gaode key, or personal domain was written.
- `docker compose --env-file .env.example config` passes for the template without starting containers.
- Local API root candidates after startup will be `http://192.168.31.195:8080` on Wi-Fi or `http://192.168.0.104:8080` on Ethernet, depending on the phone's network.

# Home Docker TeslaMate Runtime Setup - 2026-07-09

## Plan

- [x] Create local ignored `.env` with generated secrets.
- [x] Pull TeslaMate, PostgreSQL, Mosquitto, Grafana, and TeslaMateApi images.
- [x] Start the home Docker stack.
- [x] Verify TeslaMate Web UI and TeslaMateApi respond locally.
- [x] Open the TeslaMate sign-in page for user-owned Tesla authorization.

## Review

- `docker compose up -d` started all five services.
- TeslaMate Web UI responds on `http://localhost:4000` and currently shows `Sign in · TeslaMate`.
- TeslaMateApi responds on `http://localhost:8080/api/v1/cars` with `{"data":{"cars":null}}`, which means the API is alive but no Tesla account/vehicle data has been authorized into TeslaMate yet.
- This TeslaMateApi image accepted `/api/v1/cars` without a token in local smoke testing, so public exposure must rely on VPN/Tailscale/HTTPS reverse proxy controls rather than the sample API token alone.

# Android Real Vehicle First-Sync Fallback - 2026-07-09

## Plan

- [x] Probe the live home Docker API endpoints after Tesla authorization.
- [x] Confirm `/api/ping`, `/api/readyz`, and `/api/v1/cars` pass for MateLink connection testing.
- [x] Add a Dashboard fallback for the first-sync window where cars are available but `/api/v1/cars/{id}/status` is not ready.
- [x] Rebuild the Android debug APK for user testing.

## Review

- The home Docker API now returns one real vehicle from `/api/v1/cars`.
- `/api/v1/cars/1/status` currently returns an API-level "no info" response, which can happen before a useful live status snapshot is available.
- Dashboard now shows the detected vehicle and a clear "live status pending" message instead of only `No data` when status is temporarily unavailable.
- `:app:assembleDebug` succeeds with Android Studio JBR. The remaining warning is an unrelated deprecated `CompareArrows` icon in `RangeScreen.kt`.

# Android Settings Entry Flow and WorkManager Fix - 2026-07-09

## Plan

- [x] Inspect the user-reported Settings screenshots and runtime error.
- [x] Fix WorkManager initialization after the manifest disabled the default initializer.
- [x] Add an explicit "Enter App" action after a successful connection test.
- [x] Surface TeslaMateApi status endpoint error text instead of a generic missing-status message.
- [x] Rebuild Android debug APK for retest.

## Review

- Save already intended to navigate to Dashboard, but `triggerImmediateSync()` called WorkManager first and failed because `MateLinkApplication` did not implement `Configuration.Provider`.
- TeslaMateApi currently returns cars and drive/charge data, but `/api/v1/cars/1/status` still returns `{"error":"no info on this car ID"}`; Dashboard can only show live status after that endpoint provides a status snapshot.
- `:app:assembleDebug` now succeeds after the WorkManager fix. Retest APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
- Settings now shows an explicit "Enter App" action after a successful connection test; it saves the server config and navigates to Dashboard.

# Android Dashboard Partial Real Data Fallback - 2026-07-09

## Plan

- [x] Trace Dashboard data flow to confirm which endpoint drives the visible card.
- [x] Add a real-data fallback that uses `/api/v1/cars` vehicle details when `/api/v1/cars/{id}/status` is not ready.
- [x] Keep the backend error visible as a diagnostic detail without making the app look disconnected.
- [x] Rebuild Android debug APK for user retest.

## Review

- Dashboard previously showed a sparse pending card whenever `/status` returned no snapshot, even though `/cars` already contained real vehicle identity and TeslaMate stats.
- The pending state now shows the real vehicle name, connected badge, model/trim, exterior/wheel info, total drives, total charges, data source, and the exact `/status` diagnostic.
- Live battery/location/lock/climate/TPMS cards still require `/api/v1/cars/{id}/status` to return a status snapshot.
- `:app:assembleDebug` succeeds. Retest APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

# Android Dashboard Copy and Trip Timeline Fix - 2026-07-09

## Plan

- [x] Trace Dashboard partial-data state and Trips/Drives history data flow.
- [x] Fix Dashboard partial-data copy so it is localized and does not imply live online status.
- [x] Add parked segments between drive records to the trip history timeline.
- [x] Verify Android debug build and local API assumptions.

## Review

- Dashboard partial-data state now uses localized Chinese resources and a "syncing" badge instead of implying full live online status.
- Drives history now builds a mixed timeline: drive items plus derived parked items between adjacent drive records.
- The local TeslaMateApi sample has 5 drive records and 4 derivable parked gaps, giving 9 timeline items before UI filters.
- `:app:assembleDebug` succeeds. Retest APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

# Vehicle Data and Analytics Delivery - 2026-07-11

## Plan

- [x] Add implementation design and execution plan documents.
- [ ] Add failing Android tests and test dependencies for sync, analytics, timeline, cost, and battery partial states.
- [x] Fix summary/detail synchronization, pagination termination, aggregate persistence, and progress reporting.
- [ ] Implement the Dockerized Go MateLink Adapter with PostgreSQL, MQTT, geocode, cost, and sentry contracts.
- [ ] Integrate adapter capability detection and source-aware Android data repositories.
- [ ] Rebuild Dashboard, drive/parked history, charge costs, Chinese addresses, and analytics screens.
- [ ] Verify unit tests, adapter tests, Docker integration, Android build, lint, and device smoke flow.
- [ ] Report candidate files without staging or committing.

## Review - 2026-07-11

- Added pagination guards for TeslaMateApi repeat-page behavior and persisted drive/charge detail aggregates before sync progress is advanced.
- Fixed manual WorkManager initialization after the manifest disabled WorkManagerInitializer; Dashboard refresh now also schedules `DataSyncWorker`.
- Updated the Dashboard and Battery partial-data paths so missing live MQTT snapshots do not become fake zero values or raw server-English UI.
- Applied the 500 m route threshold to drive display, summaries, and charts; short repositioning is represented by the surrounding parked interval and parked timestamps render in 24-hour form.
- Verified `:app:testDebugUnitTest` and `:app:assembleDebug`; installed the APK on device `6e4fa92f` and confirmed no WorkManager initialization/fatal startup log.
- Still pending: adapter-backed parked power/temperature, charge-cost overrides and Chinese geocoding, sentry events/media, capability-aware Android API client, and final visual calibration.

# P1.2 Device Room Recovery and AMap Runtime Test - 2026-07-26

## Plan

- [x] Audit the v13 identity mismatch and define a non-destructive v13-to-v14 migration.
- [x] Add the v14 schema, migration coverage, and patch version metadata.
- [x] Remove the remaining English AMap placeholder copy from user-visible screens.
- [x] Run targeted and full Android build verification.
- [x] Install the debug APK over the existing phone data and verify startup without clearing data.
- [ ] Verify the live AMap preview after a Key is saved again; current app state is unconfigured and the Key was not read or changed.

## Review

- Root cause of the crash was the v13 Room identity/default mismatch. The app now moves to v14 through a non-destructive migration that preserves drive summaries and detail aggregates; the migration instrumentation suite passed 2/2.
- Debug version is `versionCode 2` / `versionName 1.0.1`. JVM tests passed 116/116; the AMap setup content suite passed 4/4; `assembleDebug` and `assembleDebugAndroidTest` passed.
- The APK was installed with `adb install -r`; no uninstall or data clear was used. The current MateLink process remained foreground with no Room-integrity or fatal-exception marker.
- Setup now distinguishes `尚未保存 Key。` from `Key 已加密保存，内容已隐藏。`, and disables save for an empty input. It never shows the stored value.
- Live AMap verification is intentionally incomplete: the installed app reports `尚未配置高德地图`, so no saved Key is available to the SDK. The Key was neither read nor modified.

# P1.3 Navigation Recovery and AMap Key Verification Flow - 2026-07-26

## Plan

- [x] Remove the test-only runtime navigation handling that can trap the app on setup/preview pages.
- [x] Restore normal Dashboard, Drives, Charges, and More navigation after an app restart.
- [x] Replace direct Key save with a transient SDK test page: visible pending, pass, and failure states; persist only after pass.
- [x] When a Key is verified, hide the input and provide an explicit change-Key action.
- [x] Add focused tests, rebuild, preserve-data install, and exercise the repaired flows without reading a real Key.

## Review

- The app now starts at Dashboard and keeps all primary navigation destinations available.
- AMap candidates remain pending until a real Search SDK request succeeds; a synthetic invalid Key failed on the isolated emulator and was not stored.
- Existing verified Keys remain hidden and can only be replaced through the explicit change-and-test flow.

# P1.4 Configuration Preservation, Foreground Sync Crash, and Key Test - 2026-07-26

## Plan

- [x] Capture the real-device crash signature without reading user configuration.
- [x] Declare WorkManager's `dataSync` foreground service type in the merged manifest.
- [x] Keep the app's initial destination on Dashboard; configuration remains reachable from More.
- [x] Replace direct AMap Key storage with an isolated transient verification activity and explicit pass/fail UI states.
- [x] Exercise a fresh isolated emulator: initial synthetic connection save, Dashboard return, and Key verification failure path.
- [x] Build the final APK, then use only a preserve-data install and read-only crash check on Jovi's phone.

## Review

- The physical-phone foreground crash was `SystemForegroundService` rejecting the requested `dataSync` type because the app manifest had none. The fixed manifest declares `dataSync`.
- A fresh emulator saved synthetic connection settings and returned directly to Dashboard; an invalid AMap Key produced an explicit failure and was not stored.
- No stored Docker address, token, AMap Key, or database row was read or logged.

# P1.5 Room v14 Identity Recovery - 2026-07-26

## Plan

- [x] Capture the device Room identity-hash failure without reading configuration values.
- [x] Identify that a database already at user version 14 cannot re-run the v13-to-v14 migration.
- [x] Add and isolate-test a no-schema-change v14-to-v15 recovery migration.
- [x] Build v1.0.4 and perform a preserve-data installation plus read-only device crash check.

## Review

- The v15 migration performs no table or row mutation. It advances the database version so Room verifies the existing schema and replaces only its stale master-table identity metadata.
- The isolated emulator passed all three migration tests. JVM tests passed 117/117, and both required Debug APK assemblies passed.
- `adb install -r` updated the phone from v1.0.3 to v1.0.4 while preserving the original first-install time and the databases, DataStore, and shared-preferences directories.
- After normal launch and a Drives-history navigation tap, the same application process remained alive for 25 seconds with no new fatal or Room-integrity log marker.

# P1.6 Top-Level UI System and Navigation Polish - 2026-07-27

## Design direction

- UI/UX Pro Max: data-dense telemetry dashboard, restrained motion, semantic cyan primary, 4/8dp spacing, 48dp Android touch targets, explicit selected/disabled states, light/dark parity.
- Anthropic frontend-design: commit to one deliberate “precision vehicle telemetry” direction; avoid generic gradient/card decoration and keep typography, hierarchy, and interaction choices intentional.
- Scope is UI only. Do not change API, persistence, sync, AMap verification, Room, or data calculation behavior.

## Plan

- [x] Unify global semantic colors, shapes, and typography; reserve the mono font for numeric telemetry instead of ordinary headings and labels.
- [x] Give the bottom navigation a clear cyan selected state while retaining four labeled top-level destinations.
- [x] Make Drives and Charges read as top-level destinations: consistent surface top bars and no redundant back arrow.
- [x] Recompose More into compact, touch-safe action groups; keep Settings and system entries easy to reach.
- [x] Add the missing Chinese Reports section resource and remove forced uppercase section labels.
- [x] Preserve accessible text labels for clickable cards and provide content descriptions for the four bottom-navigation icons.
- [x] Validate JVM tests, Debug APKs, light/dark mode, Chinese layout, narrow-phone rendering, and the existing portrait-only orientation policy.
- [x] Install with `adb install -r` on the phone, preserve configuration metadata, and run normal navigation smoke checks without instrumentation.

## Review

- Version advanced to `1.1.0` (`versionCode 6`) on branch `codex/app-mimo-ui-optimization`.
- UI/UX Pro Max and Anthropic frontend-design guided a restrained precision-telemetry system: semantic cyan selection/action color, 4/8dp rhythm, compact cards, explicit hierarchy, and mono type reserved for numeric telemetry.
- Chinese emulator review covered Dashboard, Drives, Charges, all scrollable More sections, dark mode, 130% font scale, and an approximately 343dp-wide viewport. The application is manifest-locked to portrait, so a forced rotation correctly retained the portrait layout.
- `:app:testDebugUnitTest`, `:app:assembleDebug`, and `:app:assembleDebugAndroidTest` passed; 117 JVM tests completed with 0 failures, 0 errors, and 0 skipped.
- `adb install -r` updated the ARM64 phone from `1.0.4` (`versionCode 5`) to `1.1.0` (`versionCode 6`). `firstInstallTime` and the metadata for `databases`, `files/datastore`, and `shared_prefs` remained unchanged.
- A normal phone launch followed by all four bottom-navigation taps kept the same process alive; fatal and Room-integrity markers were both 0. No instrumentation test ran, and no saved configuration value was read.

# P1.7 Vehicle Telemetry Panel Redesign - 2026-07-29

## Design direction

- Translate the supplied references into a native Android “night-drive telemetry” system rather than reproducing another app pixel-for-pixel.
- Keep one cyan interaction accent, green for healthy/efficient states, orange for time or battery-use warnings, and neutral graphite surfaces.
- Every major panel gets a semantic icon or code-drawn graphic. Numeric telemetry uses `MetricMono`; ordinary Chinese labels remain in the readable UI font.
- Preserve all existing API, Room, sync, map, calculation, filtering, and navigation behavior. Never invent a metric to fill the reference layout.
- Remotion is not used because these are static Compose screens. Reusable Material icons and deterministic Compose `Canvas` graphics are the correct production assets.
- Android’s experimental Compose Styles API is not enabled because it requires compileSdk 37 and alpha Compose dependencies; the stable theme/component architecture remains in place.

## Plan

- [x] Add stable shared telemetry panel, metric, gauge, route, and vehicle-hero components.
- [x] Recompose Dashboard around a vehicle hero, honest battery telemetry, status actions, location, temperature, tire pressure, and real charging data.
- [x] Recompose Drives into icon-led route cards with duration, distance, efficiency, and battery change; keep parked timeline items visually distinct.
- [x] Recompose Charges into icon-led session cards with duration, energy, cost, type, and battery change.
- [x] Strengthen Drive Detail and Charge Detail headers without changing their charts, maps, or calculations.
- [x] Add a prominent battery-health gauge and tighten capacity, degradation, and range hierarchy.
- [x] Keep More as a dense, icon-led launch grid aligned with the same panel system.
- [x] Validate Chinese/light/dark/narrow layouts on the emulator, run JVM/build gates, then preserve-data install and normal navigation checks on the phone.

## Review

- Version advanced to `1.2.0` (`versionCode 7`) on branch `codex/app-mimo-ui-optimization`; HEAD remained `c559d9e` and no file was staged, committed, or pushed.
- Added reusable native Compose telemetry panels, metric strips, route indicators, a health gauge, and a deterministic Canvas vehicle graphic. Remotion and generated raster assets were intentionally unnecessary.
- Dashboard, Drives, Charges, their detail headers, Battery Health, More, theme, typography, and bottom navigation now share one Chinese-first night-drive telemetry hierarchy.
- Removed the fabricated seven-day battery trend. Nullable vehicle states and charging values now render as unavailable instead of false or zero; unprocessed charge type is explicitly unknown.
- Emulator review covered Chinese dark mode, light-mode contrast, and a 900x2000 narrow portrait viewport. The partial-data Dashboard remained readable and scrollable without horizontal clipping.
- `:app:testDebugUnitTest`, `:app:assembleDebug`, and `:app:assembleDebugAndroidTest` passed.
- `adb install -r` updated the ARM64 phone from `1.1.0` (`versionCode 6`) to `1.2.0` (`versionCode 7`). `firstInstallTime` and the metadata for `databases`, `files/datastore`, and `shared_prefs` remained unchanged.
- A normal phone launch and all four bottom-navigation taps retained PID `13037`; fatal and Room/SQLite error markers were both 0. No instrumentation test ran and no saved configuration value was read.

# P1.8 Crash Elimination and Navigation Audit - 2026-07-29

## Plan

- [x] Reproduce the reported Battery Capacity crash on the emulator and capture only the app exception class, stack frames, and triggering route.
- [x] Build an emulator-only navigation matrix for the four top-level tabs and all 17 non-mutating More action cards; record each route's process/crash result without inspecting saved credentials.
- [x] Trace the failing code path, rank hypotheses, and add the narrowest regression test at the real failure seam before applying a production fix.
- [x] Apply the minimal crash fix without changing user configuration, TeslaMate data, Room rows, map keys, or unrelated UI behavior.
- [x] Re-run the exact emulator reproduction, the complete emulator navigation matrix, JVM tests, and Debug APK builds.
- [x] Perform a preserve-data `adb install -r` update on the real phone, then run normal navigation smoke checks and read-only fatal/Room-log verification. Never use instrumentation on the user phone.

## Review

- Reproduced two independent render-time crashes: Battery Health passed an integer to a resource with a literal `%`, and Software Updates passed an integer to a `%1$.1f` resource.
- Added focused red/green JVM format-contract tests for both resource contracts. The original tests failed first; the final full suite reports 121 tests with 0 failures, errors, or skipped tests.
- Emulator navigation verification covered all four bottom tabs, all 17 More action cards, Settings return, and AMap configuration return. No data-mutating, export, external-app, save, delete, or instrumentation action ran.
- `:app:assembleDebug` and `:app:assembleDebugAndroidTest` passed. The v1.2.1 (`versionCode 8`) Debug APK was installed with `adb install -r` on the real phone; `firstInstallTime` and data-directory metadata stayed unchanged. Normal launch and cold-start Battery route verification retained a live process with no app crash marker.

# P1.9 Dense Drive and Charge History UI - 2026-07-30

## Design direction

- Use the published `mobile-android-design` guidance for adaptive Jetpack Compose layout and the `UI/UX Pro Max` guidance for compact telemetry hierarchy and chart grouping.
- Increase information density without shrinking touch targets: compact internal padding and typography, use three- or four-column metric strips when the width supports them, and keep narrow screens readable.
- Keep source data honest. Shorter labels and compact timestamps must not invent duration, cost, address, energy, battery, power, or temperature values.
- Preserve API, Room, sync, stored connection settings, and AMap Key. Persist manual charge prices in an isolated per-car/per-session preference without touching connection credentials.

## Plan

- [x] Audit drive/charge list and detail data contracts, formatting, charts, and cost behavior.
- [x] Compact Drives history route, timestamp, filters, summary, and metric rows; use Chinese-first route text and rename user-visible efficiency to energy consumption.
- [x] Compact Drive Detail route, month/day timestamps, duration, metrics, and group each telemetry value with its corresponding curve.
- [x] Compact Charges history filters, summary, location row, session route, and metric rows without misleading per-day labels.
- [x] Compact Charge Detail time/duration and group energy, battery, power, temperature, and cost; add a clear per-session manual price/cost edit flow.
- [x] Add focused formatting/state tests, then run all JVM tests and both Debug APK build gates.
- [x] Validate the affected navigation paths on an emulator, install with `adb install -r` on the phone, and verify configuration metadata and crash logs without instrumentation.

## Review

- Version advanced to `1.3.0` (`versionCode 9`) on branch `codex/app-mimo-ui-optimization`; HEAD remained `c559d9e` and no file was staged, committed, or pushed.
- Drives and Charges now use compact four-column summaries, tighter panels, Chinese-first addresses, and consistent “能耗” wording. Detail timestamps omit the year, real duration is visible, and each available metric group is placed directly beside its corresponding curve.
- Charge filters use compact 7/30/90/all controls. Manual non-negative unit prices are stored by car/session and one shared resolver keeps list, summary, chart, and detail costs consistent; the dialog also supports returning to the recorded price.
- No distance curve was invented because the current drive samples do not expose a trustworthy cumulative-distance series.
- `git diff --check` and both string-resource XML parses passed. `:app:testDebugUnitTest`, `:app:assembleDebug`, and `:app:assembleDebugAndroidTest` passed with 130 JVM tests, 0 failures, 0 errors, and 0 skipped.
- Chinese dark-mode emulator review covered the compact Dashboard, Drives, and Charges layouts without a crash.
- `adb install -r` updated the ARM64 phone from `1.2.1` (`versionCode 8`) to `1.3.0` (`versionCode 9`). `firstInstallTime` and the metadata for `databases`, `files/datastore`, and `shared_prefs` remained unchanged.
- Normal phone checks opened Dashboard, Drives, the first Drive Detail, Charges, the first Charge Detail, the price dialog (cancelled without saving), More, and Battery Health. PID `26852` remained alive and final fatal, ANR, Room, and SQLite markers were 0. No instrumentation test ran and no saved configuration value was read.

# P1.10 Embedded AMap Navigation Repair - 2026-08-01

## Root-cause hypothesis

- The saved Key is verified and the standalone preview loads, but Dashboard, Drive Detail, Charge Detail, Where Was I, and Regions still call legacy placeholder composables that discard coordinates and route points.
- Dashboard location navigation is additionally miswired to Drives, and its map card becomes a no-op when `stateSince` is absent.

## Plan

- [x] Reproduce the Dashboard and Drive Detail placeholders on the physical phone without reading the saved Key.
- [x] Verify the settings UI reports `地图已加载` and trace the coordinate/click data flow.
- [x] Add a failing contract test proving legacy wrappers do not delegate to the native AMap renderer.
- [x] Replace point, route, and multi-marker placeholders with one lifecycle-safe native AMap renderer and configuration gate.
- [x] Route the Dashboard location entry to the actual AMap preview and keep embedded maps interactive.
- [x] Run targeted tests, the full JVM suite, Debug APK builds, and static privacy checks.
- [x] Advance the patch version, install with `adb install -r`, and verify Dashboard, Drive Detail, Charge Detail, and configuration preservation on the phone.

## Review

- Root cause: `AmapPointView`, `AmapRouteView`, and `AmapComposeView` were legacy placeholders that ignored every coordinate and route argument. Dashboard location also navigated to Drives, its map card could become a `stateSince`-dependent no-op, and detail map cards redirected to external providers.
- The phone settings UI reported `地图已加载`, proving the saved Key and privacy/verification state were already valid; no Key value was read or logged.
- Added one native, lifecycle-safe AMap renderer for point, route/polyline, and multi-marker views, with a shared setup-state gate and redacted failure logging. Dashboard location now opens the real in-app AMap preview.
- The new contract test first failed 2/2 against the placeholder implementation, then passed after the fix. The complete suite reports 132 JVM tests with 0 failures, 0 errors, and 0 skipped; `assembleDebug` and `assembleDebugAndroidTest` passed.
- Version advanced from `1.3.0` (`versionCode 9`) to `1.3.1` (`versionCode 10`). `adb install -r` preserved `firstInstallTime` and the metadata for `databases`, `files/datastore`, and `shared_prefs`.
- Physical-device checks created native AMap views on Dashboard, Drive Detail, and Charge Detail; Dashboard Location opened `高德地图预览` with `地图已加载`. Legacy placeholder, map-state error, SDK auth, fatal, ANR, Room, and SQLite markers were all 0. No instrumentation, uninstall, data clear, saved-Key read, commit, or push occurred.
## P1.11 Runtime Reliability Regression Audit

- [x] Add failing contracts for notification formatting and AMap verification back handling.
- [x] Align notification resource placeholders with their callers.
- [x] Replace the deprecated verification Activity back override.
- [x] Run targeted tests, full JVM/build verification, and focused lint checks.
- [x] Install the versioned APK with `adb install -r` and repeat safe device smoke tests.

### Review

- Red contracts reproduced all three defects before the fix and passed afterward.
- Notification titles now preserve vehicle/tire context; literal percentage copy is explicitly non-formatting.
- AMap Key verification uses `OnBackPressedDispatcher` and still returns a canceled result without exposing the candidate Key.
- `:app:testDebugUnitTest`, `:app:assembleDebug`, and `:app:assembleDebugAndroidTest` passed; 135 JVM tests, 0 failures/errors.
- Focused Lint findings for `MissingSuperCall`, `StringFormatInvalid`, and `StringFormatCount`: 0.
- Installed version `1.3.2` (`versionCode 11`) with `adb install -r`; first-install timestamp remained unchanged.
- Physical-device smoke test kept Dashboard, Drives, Charges, and More alive/foreground; critical crash/auth/database log markers: 0.
- No instrumentation, app-data clear, uninstall, commit, or push was performed.

## P1.12 Complete-history analytics and honest report delivery

- [x] Define the unified all-history analysis source, separate long-distance grouping, manual per-session total charge amount, and empty-data semantics.
- [x] Add focused tests for history deduplication/windows, manual totals, percentile direction, standby attribution, drive routing, and annual current/previous years.
- [x] Switch Efficiency, Cost, Range, and Vampire view models to the unified history source; expose compact window/percentile/currency/no-data UI.
- [x] Keep More → 行程历史 on all individual drives and remove the 3D vehicle/current-charge report entries.
- [x] Run full JVM/lint/build gates, bump the app version, install with `adb install -r`, and complete normal physical-device navigation smoke tests.

### Review

- Design and implementation plan committed before code changes; no source data, `.env`, API Key, or vehicle identifier was read or logged.
- 147 JVM tests passed with 0 failures/errors/skips; `assembleDebug` and `assembleDebugAndroidTest` passed.
- Lint remains blocked by the repository's existing 842 missing-translation errors and 254 warnings; no new touched-file format or crash error was found in the report.
- Version advanced to `1.4.0` (`versionCode 12`); `adb install -r` preserved `firstInstallTime` and the configuration directory metadata.
- Normal device checks covered More analysis/report routes, annual 2025/2026 switching, Battery capacity, Dashboard AMap preview, Drives, and Charges. PID remained alive and critical crash/ANR/Room/SQLite/format/AMap markers were 0.

# P1.13 Approved history-analysis completion - 2026-08-01

## Plan

- [x] Add one reusable all/90-day/summer/winter/custom date selector and pass inclusive custom boundaries to Efficiency, Cost, Range, and Standby.
- [x] Make Efficiency show a trend, personal percentile range with bucket counts, per-drive expandable positions, and verified-public-sample-unavailable state; use average speed for speed buckets.
- [x] Make Cost distinguish valid cost/energy coverage from missing values, keep manual total precedence, and show manual-total count in the summary.
- [x] Make Range summarize seasonal and average-speed prediction accuracy; keep missing values as no-data text.
- [x] Make Standby include every computable parked interval, expose kWh/day, location, confidence, and unknown-cause wording without inferring attribution.
- [x] Make Annual report metrics honest when only one data family exists; always show effective cost and standby coverage states.
- [x] Add percentile tie semantics and custom-window boundary tests; bump to version 1.4.1 (versionCode 13).
- [x] Run the full JVM suite and both Debug APK build gates, then preserve-data install and complete physical-device regression across all approved analysis routes.

## Review

- Targeted analytics/charges/reports/domain tests passed after the implementation; compileDebugKotlin passed.
- No verified public same-model aggregate source exists in this repository, so no ranking, sample count, median, or fabricated value is displayed; the UI explicitly reports public-sample insufficiency.
- The final full gate reports 152 JVM tests with 0 failures, errors, or skipped tests; both Debug APK build gates passed after clearing the generated KSP cache.
- Version 1.4.1 (versionCode 13) was installed with `adb install -r -d`; firstInstallTime remained `2026-07-26 21:35:02`, and the process stayed alive through analysis, report, history, and long-distance routes.
- Physical checks covered all four analysis pages, custom date dialog, current/previous annual reports, 行程历史, and 长途旅程. Fatal/app, Room/corruption, format, ANR, and AMap markers were 0. No instrumentation, uninstall, data clear, Key read, commit, or push occurred.

# Repository consolidation and stable publish - 2026-08-09

## Plan

- [x] Publish the verified `app_mimo` stable line to its `main` branch.
- [x] Remove the retired `app_glm` submodule from the parent repository.
- [x] Update the parent `app_mimo` gitlink to the published stable commit.
- [x] Keep local secret files and generated state out of Git.
- [x] Push both repository `main` branches and the stable tag.

## Review

- `app_mimo/main` is `3140b0e`; `testDebugUnitTest`, `assembleDebug`, and `assembleDebugAndroidTest` passed with the Android SDK supplied only to the process environment.
- `v1.4.2` remains the stable release marker and is published to the `app_mimo` remote.
- `tesla_master/main` is `6166b6a`; its commit removes `app_glm` and points `app_mimo` to `3140b0e`.
- `app_glm` was removed from `.gitmodules`, the parent gitlink, the working tree, and local submodule metadata. The separate remote repository was not deleted.
- `android/.kotlin/` and `deploy/*.env` are ignored. No `.env` content, API key, token, or user configuration was read or staged.
- Existing unrelated parent-worktree deletions and the legacy `app_mimo` worktree changes remain uncommitted and preserved.

# P1.14 Branch consolidation - 2026-08-09

## Plan

- [x] Merge `codex/app-mimo-data-setup` into the latest `main` line without discarding unique qualification tests or public-information assets.
- [x] Resolve overlapping UI/resource/build conflicts in favor of the latest `main` implementation and retain the data-setup verification gates.
- [x] Re-run JVM tests, Debug APK builds, AndroidTest APK build, and foreground-service manifest validation.

## Review

- Merge validation passed: `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and `:app:verifyDebugForegroundServiceType`.
- `git diff --cached --check` passed and no merge markers remain.
- No `.env` content, API key, token, vehicle identifier, or user configuration was read or staged.
