# MateLink data foundation: remote execution, evidence and gates

Task: **c2c_8f2d / same original chat**. This is a source-stage report, not a signed-device acceptance, production authorization or Fleet first-event certificate.

## Authoritative code lineage

- Repository: `Jovifei/tesla-master-mimo`; owned source branch: `codex/apk-data-foundation-remote-20261024`.
- Parent of this remote stage: original WIP `889df3fcb5927a4b3591806be4c03ba2d3223d2c`, tree `392fc44a234bee569d8c56ffc2174f21c0b103e1`.
- Earlier independently CI-green APK source: PR #22 `1b838aaca810bd6643ce22a59bac39cb4a366c6c`; original main `49ee6c7423d6d61c50b511eb50f5055316ed691a`. Neither is overwritten or merged.
- Use `git rev-parse HEAD HEAD^{tree} HEAD^` from the exact final GitHub commit and matching Actions checkout. The commit authoring this report cannot embed its own SHA without changing that SHA.
- E: app_mimo refers to a different dirty Owner miniprogram workspace; all remote edits here were through GitHub objects or Contents API on **this repository only**. Local work remains untouched.
- The installed physical phone has been reported as build **67 / 2.1.48**. This source branch retains versionCode **62 / 2.1.43**. Do not interpret it as a device-upgrade package or replace the higher-build phone app.

## Executed product source and real consumer contracts

| Scope | Implemented in this remote branch | Exact negative conditions |
| --- | --- | --- |
| Cloud session & credential URLs | Preserve original WIP generation-fenced token refresh, late 200/401 protection and no bearer redirect. | A stale refresh may not overwrite a new account or replay a current token on a different redirect origin. No Tesla credentials/MFA are requested. |
| Current vehicle status | `VehicleStatusStore` keys cache by scoped account/origin/verified stable identity; raw sparse status is saved without merging prior GPS or fields; a separate historical-position receipt keeps original observed_at and unit/source. | Missing fresh UID: request-local status **can display** but must not write a stale cached identity; old GPS cannot gain new observed time; null and valid zero remain distinct. |
| Dashboard & map | Full dashboard obtains a fresh /cars UID before status caching, TPMS collection and address enrichment. Polling shows only request-current raw status, no UID-inferred persist or geocode. Map uses fresh UID for writes, with an independently labelled, older verified position fallback. Final fences after suspending address/API reads. | Same numeric car ID A→B, /cars failure, UID rebound, missing GPS, suspended geocoder, late response/old errors must not cross source. A live status without UID must not be wholly hidden. |
| Custom vehicle photo | New files/pointers scoped to account, origin and stable verified vehicle; writes are bounded and staged, rollback on a failed replacement. Old numeric-ID files remain untouched and **unbound**, not guessed onto the current vehicle. | Same numeric ID with different account/UID cannot show, clear or overwrite another photo. Failure preserves older scoped bytes. |
| Real trip temperature | Qualify only stored Room drive raw receipt, exact source and Instant window. A verified legacy TeslaMate trip may expose its saved real average even when energy quality is incomplete. Includes source label, last trip-start observation time and N/eligible counts in 7/30-day UI; empty reason distinguishes no saved drives from insufficient temperature receipt. | No sine/generated values, no old parked-time claims, no bare nullable legacy values/forged receipt, no local import/cross-car/unknown source promotion; raw 0°C and negative temperatures are valid. |
| Same numeric session source conflict | Existing Room v23 detail receipt table also holds private immutable `archived_charge_source` and `archived_drive_source` originals before main summary replacement, inside the same DAO transaction; duplicate source hashes are idempotent, bounded receipt/global archive limits fail-closed. | A→B→A with same numeric ID/source windows may not erase the only A/B original; old archive never qualifies a Fleet first event or the current charge energy. If an old original does not exist or budget is exceeded, abort replacement rather than silently discard it. |
| Scalar-only details | Same verified identity/window can persist valid session-level kWh/energy when API omits point array, while marking `charge_points_not_provided` / `drive_points_not_provided`. Bad/truncated/oversize provided point arrays still reject and roll back both writes. | No invented zero-point curve or guessed energy; true zero/negative net drive energy remains valid; failures cannot destroy an older complete detail receipt. |
| Full detail cache recovery | Scan up to five eligible complete v23 revisions under same identity, verifying encoded chunks/hashes; on corrupt latest use older readable revision labelled `detail_recovered_older_revision`. | Never claim recovered older data are latest; no cross-account/cross-source/window fallback, no unlimited retry or eviction of unique source receipts. |
| Annual PDF | One immutable `AnnualStatsSnapshot` for year, currency, charts and totals; read in one Room cohort. Scope/generation guards freeze export; login/source changes cancel loaders and clear previous report eligibility. | A→B identical carId, delayed available years, 2025→2026 export race, older task path publication all reject. |
| Drive/charge history lists | Subscribe to history scope changes, invalidate stale request generations and sensitive cached UI, verify remote proof after DAO reads; independent unit/status/free-charge helpers have scope fences. | A→B→A same numeric ID, late aggregate/setting/geocoder/units responses, unknown cost, or old helper error cannot republish previous user's values. |

**Boundary of the temperature work:** it reuses an upstream /drives history response and the existing server-to-Room saved session receipt; it does not claim Tesla Fleet has emitted a new time series, create a new server route, import historical points, or add production DDL. TeslaMate v4.3.0's historical-temperature dashboard is only UI/contract inspiration. Fleet `InsideTemp`, `OutsideTemp`, `EnergyRemaining` and per-wheel TPMS are supported fields, but their presence in the field catalog does not prove the current provider configured/sent or saved those measurements. The Fleet documentation cautions about TPMS timestamp interpretation: do not infer valid per-wheel observation time from a collector's phone read.

## Actual test gates and receipt integrity

- Local WIP focus before this remote stage: 20 Android focused tests succeeded; not an all-variant proof.
- New real Room tests: `VehicleStatusStoreScopeTest` (cross-account, old GPS time, sparse live zero), `VehiclePhotoStoreScopeTest` (stable vehicle/photo isolation and failed staged write), `HistorySourceConflictArchiveRoomTest` (charge+drive A→B→A with reopen), `ChargeSourceInstanceAdmissionTest` (same ID conflict preserved), `HistoryDetailRoomIntegrationTest` (valid scalar with no points, corrupted-newest offline revision recovery), `TemperatureRoomHistoryConsumerTest` (original source/time/null/0/negative persists after Room reopen); existing `TemperatureTrendTruthTest` guards forged and stale responses.
- Actual broader source CI uses `.github/workflows/history-memory-review.yml` with an **owned `codex/apk-*` push**: `tools/energy-stage/android-ci.sh` runs Debug+Release JUnit, lint and R8; verification also covers Go/isolated PG16/race/TLS/Web, PG18 upstream compatibility, Mini type/tests/build, image checks and source audit.
- CI green at a **different** SHA is not a pass. For final delivery record one complete same-SHA run ID, each job and artifact, error/skip counts and actual checkout SHA/tree/parent. If the final run fails, fix genuine source and rerun on another fixed SHA; never invent a pass.
- Android native Room/Compose emulator invocation is a distinct gate from JUnit and AndroidTest APK assembly. The repo's connected-test guard must remain in force; target only a disposable isolated Android 35 emulator.
- Provider/phone: real 64-charge list/detail 967→920 mismatch has no aligned privacy-redacted wire+Room revision ledger. A synthetic 64-page fixture is not the observed user's recovery. A real Fleet first trip/charge/TPMS increment or notification, user authorization and two users sharing numeric IDs are separate human acceptance gates.

## Local verification / release / rollback matrix

| Layer | Local verifier task after receiving **fixed GitHub SHA only** | Rollback and gate |
| --- | --- | --- |
| Git identity | Fetch only this repository, check exact branch, commit/tree/parent and reviewed diff; verify no unrelated Owner tree touched. | No merge, no force push, no overriding dirty work. |
| Android source | Build Debug/Release, repeat JUnit, Android Lint and R8 with per-suite failure/skip evidence. Run new Room reopen and A→B source tests. | Fail-closed build is not a repair by clearing app DB. |
| Emulator | Isolated API35 emulator: actually run Room migration v21→22→23, scope/Flow/worker/Compose consumers and offline detail recovery; produce redacted screenshots. | Emulator data can be disposable; owner's real app data cannot. |
| APK signing | Independently verify intended actual higher-phone code integration, applicationId `com.matelink`, versionCode, keystore certificate and package SHA256. | Prior versionCode62 branch is **not** authorized to replace phone build67. |
| Physical device | **BLOCKED**: `allowBackup=false`, signed Release `run-as` denied, no verified Room export/restore path; no install, uninstall, clear data, wake, or irreversible schema migration. | A lower APK may not read a newer schema; installing old APK is not an accepted rollback. Need independent reversible backup/restore and Jovi approval. |
| Server / provider | Go/PG/TLS/Web isolated tests only; no production schema/API/bridge change, no historical backfill or auth changes. | Production DDL, Fleet consent/vehicle confirmations and live calls require separate human gate. |
| Product merge | Draft/source review only until full fixed-SHA CI and independent local tests plus explicit Jovi release approval. | PR #22 and main untouched. |

## Work still not certified

- Current installed phone build67 is a different code stream than this reviewed GitHub branch; physical screenshot/menu/state and historical 64 receipts are unknown until authorized private device review.
- The old local app's numeric-photo content stays physically present but deliberately unbound. Explicit, UID-verified migration of a selected legacy image is a separate consented UI operation; it is not silently copied.
- The new archived same-ID raw receipt is cold evidence, not a second counted source row; it needs further resource/lifecycle evaluation before claiming infinite offline storage or full-user export.
- Temperature history counts can be small due to actual available qualified trip receipts. A real sparse time series and point-source consent still need provider coverage.
- TPMS natural trends, durable notifications and multi-user Fleet acceptance cannot be substituted with unit tests, Jovi's personal TeslaMate archive or green web image builds.
- The old AGENTS file contains a local E: parent reference; the remote GitHub execution cannot read that Owner path and must not pretend it did.

## Original-chat handoff

Do not start another ChatGPT/Codex task. The local Codex role is independently fetch/verify/test/build/sign (after policy gate) against the exact GitHub SHA, returning one consolidated result or demonstrated blocker. Business-code implementation and CI repair remain the remote stage's responsibility. Human phone, account, permission, resource and production decisions return to Jovi with real evidence, not inferred approvals.
