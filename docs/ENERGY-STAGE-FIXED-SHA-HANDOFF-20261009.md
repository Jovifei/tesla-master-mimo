# Energy, parking and charging full-stage release candidate — 2026-10-09

## Scope and immutable provenance
This is the release-candidate handoff for `codex/reliable-data-stage-20261007`, Draft PR #17. **Check out the final SHA recorded in the PR and remote handoff, not a moving branch.** Android baseline is `14ca59d54ba8c470abb45d01798a23919f47bb63` (2.1.26/build45), deployed API compatibility baseline is `bb09fac04d11796ce676555dad094776cd1ef0ce`. Candidate Android is 2.1.27/build46; existing signed install, app data, login and histories must be preserved. This document alone is not CI PASS or production approval.

## Root causes / actual code closures
1. Legacy drive energy rejected valid zero/negative net values; power integration capped long gaps and could over-credit coverage. Preserve physical sign, milliseconds and verified interval coverage; only source-qualified whole-window values drive kWh/100km, with unavailable remaining null.
2. Room average efficiency previously divided known-energy total by *all* distance, diluting averages and producing zero for all-unknown rows. Both all-time and window SQL now select precisely the same energy-known, positive-distance cohort. Independent SQLite oracle executes actual production Room SQL.
3. Summary, detail and fallback energy labels and source contracts distinguish reported, power-integration estimate, EnergyRemaining-delta estimate and unavailable; battery input, AC charger input and driving net are not interchangeable.
4. Charging formerly treated unknown energy like zero for short-charge visibility, and independent display/tariff consumers could read raw legacy energy despite explicit unknown contracts. The list, charts, detail and tariff projection use qualified battery input. Valid zero remains a value; unknown remains visible and unavailable for monetary energy estimation.
5. Go Fleet source counters (DCChargingEnergyIn battery-side, ACChargingEnergyIn charger-side) are independently retained with observed source timestamps, exact window and counter-reset/replay evidence. Partial observation does not become whole-session energy. Completed session metadata persists in existing JSON without production DDL; no SOC-capacity guess.
6. Parking displays qualified EnergyRemaining delta only with source-valid endpoints, same identity/source, explicit non-driving/non-charging window; adjacent SOC alone is insufficient. Average power W is derived only from qualified kWh and positive observed hours, marked estimated.
7. Bounded PostgreSQL history paging accepts JSON null/scalar as empty array in array-specific SQL operations while preserving query tenant/vehicle/scope and avoiding unbounded raw history materialization. A real isolated PG16 ingest→restart→summary/detail test guards this path.

## Required evidence before local use
- For **the exact final commit SHA**, Actions must show Go all packages, isolated PostgreSQL 16, mandatory test manifest, `-race`, resource regressions, Web tests/build, Android Debug+Release unit tests, lint, Release R8 and source-audit all successful. Only use actual run ID and log/artifact counts; canceled or earlier-SHA runs are not PASS.
- Validate actual final SHA, tree and parent with `git rev-parse HEAD HEAD^{tree} HEAD^`; check `git diff --exit-code` for test checkout.
- Confirm API deployed baseline compatibility by comparing `deploy/jourvolt-dev-mock/` blobs against bb09fac, listing exact changed files. Do not overwrite Go with an older stage tree or replace Android with API tree.
- Bounded CI artifacts are synthetic/source evidence, not vehicle/provider/production/natural-event acceptance. Real Tesla owner permission and source provenance are separate human gates.

## Independent local verification (no business-code implementation)
Use two clean, **separate** worktrees or independent checkouts at final Android candidate SHA and immutable deployed API bb09fac. Preserve Owner's dirty working tree; no reset, clean, stash or cross-tree copy. Keep private signing properties outside the repository.

In a disposable isolated PostgreSQL 16 instance and test DSN only:
```bash
cd deploy/jourvolt-dev-mock
go test ./... -count=1
go test -race ./... -count=1
go vet ./...
go mod verify
go build ./...
```
Never point any test or `openStore` at production: startup has schema changes.

In the candidate Android checkout, with JDK 17 and SDK 35:
```powershell
cd android
.\gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease
.\build-pilot-apk.ps1 -ApiBaseUrl 'https://api.teslalink.joviluma.com/' -AuthHost 'auth.teslalink.joviluma.com' -PublicInfoBaseUrl 'https://auth.teslalink.joviluma.com' -SigningPropertiesPath '<PRIVATE_LOCAL_SIGNING_PROPERTIES>'
```
The host values above are build guard examples that must be verified against the **unchanged active production configuration** before signing. Never print keystore contents/credentials. Verify package `com.matelink`, versionCode 46, expected signing certificate, SHA-256 and signed/R8 status. Only after successful explicit local verification, use `adb install -r` on the authorized phone and compare signing cert, firstInstallTime, retained app data/login/history before and after. Never uninstall/clear data or run destructive instrumentation on that phone. A dry-run or unsigned CI APK is not install acceptance.

## Device and natural-data acceptance matrix
- Login/consent/vehicle confirmation: owner completes official Tesla flow; denial, pending, reauthorization, account switch and offline recovery are distinct cases.
- Driving: for already naturally occurring completed drive compare reported start/end SOC, observed max-speed and independently integrated kWh/100km against source/time/coverage; verify zero and net regeneration, no fabricated capacity-derived energy.
- Parking: adjacent eligible same-user/car/source drives, genuine uninterrupted idle interval, no charging; missing EnergyRemaining, ambiguous segments or crossed ownership must show unknown.
- Charging: full-window battery input vs AC charger input (not the same value), AC/DC, counter reset/replay, source time and units, zero/null, manual/actual/free/estimated costs, date and cache boundaries.
- New natural drive and charge notifications: record event end, provider observation, ingestion, API available, mobile persistence, UI and system-notification delivery times independently. Do not open app/force sync to claim passive delivery. No manufactured vehicle events.
- Performance/resources/recovery: background permission denied/restored, network loss, app restart, 0/50/51/large pages, cancellation, user-switch races, CPU/memory ceilings, restart with pending events.
- Multiple real users and first *Fleet* event are separate PENDING_HUMAN / PENDING_NATURAL gates. TeslaMate personal archive and synthetic fixture do not satisfy them.

## Cleanup, deployment and rollback
Source-audit tracks candidate generated/build/received/log/archive artifacts with reference checks. The inspected tracked candidate count was zero; avoid deleting valid fixtures/tests/Room schemas/migrations, official configs, signing material, backups, rollback evidence or history for the sake of cleanup. Untracked Owner files must be preserved; old caches should only be archived with explicit reversible manifest. No production DB UPDATE/DDL, old six SOC or 7113 TPMS backfill, bridge deployment or vehicle actions in this stage.

Keep the current signed build45 APK, current bb09fac container image and exact active config/DB backup as rollback inputs. Before any separately authorized deployment, validate complete backup restore and idempotent rollback; do not silently migrate production. If build46 device validation fails, stop and reinstall the previously known-good **same-signed** build45 with `install -r` only if package downgrade/version constraints are safely satisfied; never force data loss. API remains on bb09fac until separately approved. Source merge to main follows local fixed-SHA installation PASS and doesn't imply production deployment.

## Outcome classification
A source PASS requires a single final immutable SHA + genuine finished Actions runs and independently matched Android/API source closures. Device PASS requires signed package and data-retention evidence. Natural Fleet and multi-user PASS requires genuinely observed separate human/provider events; otherwise explicit PENDING. No unverified zero/estimated energy upgraded to actual. All evidence uploaded to the *same* GitHub repo after privacy redaction; no VIN, trajectories, tokens, signing secrets, raw user databases or full private logs.
