# F1 auth, vehicle selection and cached snapshot evidence

- Discovered: 2026-10-26, source review 01/09; original source version 2.1.53 (72).
- Working scope: E:\project\_worktrees\matelink-release-data-audit-20261026, parent baseline e53a2d01. Candidate target 2.1.54 (73), package/build/install owned by parent.
- Owner: /root/fix_app_auth_cache. No Gradle, commits, network/provider/device or private-data access in this subtask.
- UI gate: only value expressions and status semantics may change; all positions, layout, sizes, typography and colors remain frozen.

## Plan and current evidence

- [x] Read project guidance, relevant lessons, report01/report09/FIX-PLAN and scoped consumers.
- [x] Add actual onboarding ViewModel baseline regressions (first car 7, current account car 22, missing UID fail closed); await parent execution.
- [x] H01 persist a verified current selection before pairing/navigation, rejecting stale account/origin/generation and ambiguous ownership.
- [x] H02 store and reopen one scoped receipt with raw status, nullable same-response units, source, observedAt and fieldSources; update Dashboard, Map and Battery consumers; keep legacy receipts without guessed units.
- [x] H03 render missing speed/added energy/current as unknown while retaining observed zero.
- [x] H04 redirect authoritative cloud auth loss after a fresh-login launch.
- [x] H05 distinguish login identity from token rotation and atomically clear only the captured login identity; test refresh, same-user re-login, ABA and store recreation.
- [x] H06 recognize telemetry_mqtt through snapshot evidence and Dashboard source categorization.
- [x] H07 label recent/HTTP503 status honestly; only actual asleep state means asleep.
- [ ] Parent runs focused tests, reviews final diff and owns version/commit/build ledger.

## Findings and validation intent

H01: both initial onboarding and retry choose a current /cars fallback but never persist it. Dashboard later checks the old global ID and correctly refuses an absent car. This is a selection consistency bug, not proof of cross-account disclosure. Current cloud UID proof remains mandatory for the repair.

H02: existing v2/v3 stores persist only CarStatus plus observedAt. Dashboard fallback constructs Units(), Battery reads CarStatus without units, and Map can overwrite the same cache without forwarding response units. Raw preferred values must travel with their original unit context; legacy unitless data must remain retained, without relabeling raw miles/Fahrenheit/psi as metric.

H05: history identity generation intentionally increments on each refresh. Logout comparing full credentials therefore loses to same-account rotation. A separate persisted login identity epoch is necessary; the existing history/auth generation semantics must stay unchanged. Conditional cleanup must compare and clear under the store monitor.

Validation status: new tests are authored but not executed by this subtask. Parent audit11 H04/H06 RED is inherited evidence only; exact parent execution results and commit references remain pending.

## 2026-10-27 RED handoff

Parent reported actual `AppReleaseDataChainRegressionTest` baseline: 11 tests / 8 failures / 0 errors / 0 skipped, saved as baseline-red-01.xml. This subtask inherits that execution evidence and has not run Gradle.

H04 and H06 now have minimal source changes based on that RED evidence: auth-loss redirection accepts any initialized start destination, and both SnapshotEvidence and Dashboard source categorization recognize telemetry_mqtt. Existing redirect suppression and self-hosted routing gates remain.

Twelve new test methods are queued with the parent under `*AppRelease*`: AuthSelection (4), StatusReceipt (3), LogoutIdentity (3), DashboardValues (2). CacheReceipt API and login-identity API currently retain old behavior; HUD helpers currently reproduce the original value and badge expressions and are bound to the actual Compose consumers. The internal auth API constructor supplies controlled credential I/O in actual ViewModel tests while preserving the existing Hilt constructor. Product/test changes are frozen until the parent's new RED results.

Parent's first expanded run (`f1-red-01`): 23 tests / 13 failures / 0 errors / 3 skips. Store metadata, HUD/badge and actual logout ViewModel regressions failed on the intended old behavior. The three H01 cases were incorrectly skipped by a BuildConfig mock capability assumption; no H01 runtime claim is made from that run. The fixture now explicitly sets mockMode=false and returns a fake API for every cache key (no HTTP fallback). The parent is re-running the twelve methods together with F4/F6. Reopen login identity now directly tests a repeated process generation after exactly one save on a recreated store. Only tests changed in this correction window.

## 2026-10-27 implementation ready for parent verification

Inherited actual RED evidence: parent `groups-red-03` completed 49 tests / 36 failures / 0 errors / 0 skipped; F1 Auth 4/4 failures, Receipt 3/3, Logout 3/2, HUD 2/2. Parent archived XML under E:\Claude_allow\Download\matelink-app-release-fixes-20261027\groups-red-03-xml. H04/H06 now pass in the parent eleven-case regression. No Gradle was run by this subtask.

- H01: Settings keeps the compatible global selected ID and an origin/account/source namespace plus UID digest. Only unique positive current API rows and valid cloud UIDs can be selected. Initial/retry onboarding and Dashboard repair then persist selection before pairing/status/navigation. Onboarding publication uses durable login epoch + mode generation, rejecting a same-user new login and account ABA while allowing credential rotation. Dashboard retains its strict HistoryReadScope gates.
- H02: Atomic v4 receipt stores raw status plus nullable same-response units, source, observedAt and fieldSources under the existing verified scoped keys and digest alias. Legacy v2/v3 raw records and historical GPS remain; unitless values are retained in raw storage and withheld only from unit-dependent display fields. Dashboard consumes the receipt; Battery reads the same verified alias and forwards units; Map forwards all received context on writes. Legacy /status now uses existing ApiResult.Success.metadata rather than adding DTO constructor parameters.
- H03/H07: The existing HUD value bindings use tested formatters: null speed/added energy/current show unknown, observed zero remains zero. Badge sleep is based only on an explicit asleep vehicle state; recent snapshots use the already localized recent label. Existing colors, fonts, sizes, positions and layout code are unchanged.
- H05: A separate persisted login identity epoch increments on save/clear and is retained on rotation; existing history identity generation/epoch still advance exactly as before. Captured-login check and clear run under the same store monitor. Logout completion is also suppressed if a fresh session is already present.
- H04/H06: Navigation recognizes auth invalidation after any initialized start route, preserving self-hosted and suppression gates. telemetry_mqtt is recognized by evidence and Dashboard source categorization.

F1 regression target methods now total 15 (Auth 7, Receipt 3, Logout 3, HUD 2). Three added controls cover existing API-fake -> repository metadata forwarding, late same-user/ABA car lists, and duplicate authenticated numeric IDs; those controls were added during GREEN and are not claimed as prior RED. Parent will run all F1 classes plus VehicleStatusStoreScopeTest, JourVoltSessionConcurrencyTest, SessionExpiryNavigationTest, SnapshotEvidenceTest, DashboardSnapshotSourceTest and existing onboarding contracts. `git diff --check` scoped to F1 product files passed (only line-ending normalization warning). UI diff review found value/status changes only.

Battery handoff: F1 froze BatteryViewModel after adding readCachedBatteryReceipt and publishing cached displayStatus + units. computeStats, live status and trend logic were untouched by F1 and are available for the parent's F4 phase. Map and NavGraph F1 edits are also frozen. Candidate version/build and commit references remain parent-owned and pending; no build, installation, provider or production acceptance is asserted here.
