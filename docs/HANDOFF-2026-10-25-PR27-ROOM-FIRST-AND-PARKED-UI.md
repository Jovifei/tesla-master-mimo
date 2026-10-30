# PR #27 — Room-first analytics and parked-session UI handoff (2026-10-25)

## Stage and source

Only `Jovifei/tesla-master-mimo` branch `codex/app-foundation-ui-integrate-20261025` is authorized for this remote stage; Draft PR #27 is not permission to merge or deploy. Baseline `84e3ca91328043eb21209de9be51db477f8c6ce4` integrates the previously qualified data foundation and Owner's build70 UI into an uninstalled build71/2.1.52 candidate. The **terminal fixed commit, tree and parent** must be taken from actual GitHub branch/commit metadata after CI finishes, not inferred from this document. The phone still runs build70.

## Actual implementation and review scope

1. Battery, efficiency and cost pages use verified account/API-origin/stable-vehicle history context and existing Room receipts for offline-first publication. Analytics uses the same qualified projections for fresh and stale data; no fabricated battery capacity or estimated kWh promoted to observation. Cache is cleared or fenced upon identity changes; remote late results cannot publish under a new scope.
2. Parked windows use adjacent verified same-car drive boundaries. Charging sessions remain separated, even after stop and restart. Per-charge recorded kWh divided by its own valid duration is kWh/h; unqualified/unobserved is unknown, and actual 0 stays 0. Noncharging SOC rate requires all stored sessions and nearby non-overlapping real SOC boundaries, is labeled **percentage points/hour estimate**, not parked kWh or power.
3. PG18 upstream TeslaMate fixture contains two same-vehicle charge processes (IDs 501 and 502), plus an isolated foreign car. Archive bridge regression checks both, zero/negative/unknown charge points and actual five TLS archive submissions; adapter has separate PG18 checks. No production schema operation is part of these tests.
4. API35 synthetic full/partial parked screens are asserted through actual UI navigation including an all-history filter for the fixed July 18 fixture. The test captures Compose root pixels rather than the headless shell display; rejects near-uniform blank content. The isolated workflow uploads PNGs and a real native JUnit XML. A valid PNG header **alone** is not visual acceptance.
5. No project production service, legacy archive, credentials, Tesla vehicle, phone app data, Owner E: dirty files, backup or main branch is changed by this stage. PR22 and PR26 remain Draft.

## Independent CI evidence and stop conditions

Required for **one identical final SHA**: fast Room/identity Android; comprehensive Android Debug and Release JUnit, both lint, R8, APK/AndroidTest assembly; actual API35 Compose/Room assertions and nonblank synthetic image inspection; Go isolated PG16/PG18, race, TLS, Web; Mini; production image compilation; source audit. Gather exact `run_id`/`job_id` and XML totals from each run and record them in the final handoff. Earlier failed/cancelled runs are diagnostic and do not qualify the final SHA.

If any test fails, leave Draft and investigate root cause on the branch. Neither retries, prior local build71 qualifications, nor an APK built at an older SHA can substitute for passing CI. Do not assert signed APK or physical-device screen acceptance solely from Actions.

## Local Codex independent verification, preserving Owner state

| Gate | Local instruction | Pass evidence / rollback boundary |
| --- | --- | --- |
| Exact source | Fetch only final fixed GitHub SHA on an **isolated** worktree, verify SHA/tree/parent, PR27 branch and compare diff | Never reset, clean, force push, or overwrite Owner's 586 untracked E: files |
| Android unit | Rebuild fixed SHA Debug/Release JVM suites and parse both real JUnit XML; lint both; R8 and APK/AndroidTest APK | Zero new failures/errors, report established skips; no test suppression |
| Emulator UI | Inspect synthetic screenshots of parked full/partial, typography, numbers, units, scroll, dark/large text as applicable | Blank/black PNG is **failure**, do not use real vehicle address screenshots |
| Signing | Rebuild fixed SHA release at original Android application ID and verify non-debuggable, version 71/2.1.52 and same certificate as installed build70 | Sign and install only after separate device authorization/Owner evidence; do not disclose keystore |
| Phone upgrade | If Owner authorizes, first protect app data and backups, verify package/signature, install as **in-place replace** without clearing storage; record original firstInstallTime and startup | On issue stop and preserve original data, configuration, certificate, APK and evidence; rollback only using separately reviewed compatible app package, never uninstall/clear |
| Provider/production | Obtain genuine Tesla authorization and vehicle confirmation, natural trip/charge/parking observations and multi-user isolation | Synthetic UI or TeslaMate personal history cannot establish Fleet first event or live provider PASS |
| Database changes | Separate explicit consent, backups, precise row scope, idempotent replay and rollback rehearsal before any DDL/history repair | No production DDL, historical repair or six-SOC/7113-TPMS write from this source stage |

## External human/natural data gates

Remain OPEN: genuine Tesla Fleet vehicle first event and account consent, TPMS 30-day observed sequence, non-synthetic energy/charging and natural notification deduplication, physical build71 UI and auth persistence, App/Web/Mini parity under two real users and source instances, hardware future interface, production backup/deploy/rollback review. The TeslaMate personal archive remains valuable but does not close any Fleet acceptance gate.

For each gate, distinguish source inspection, isolated/synthetic CI, emulator, signed local-device, provider, production, and human approval evidence. Unknown stays unknown; valid zero remains zero. Do not solicit passwords, MFA, VIN or raw trajectories in public PR evidence.
