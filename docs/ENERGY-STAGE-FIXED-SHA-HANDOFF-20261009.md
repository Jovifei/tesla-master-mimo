# Energy, parking and charging full-stage release candidate — 2026-10-09

## Scope and immutable provenance
This is the release-candidate handoff for `codex/reliable-data-stage-20261007`, Draft PR #17. **Check out the final SHA recorded in the PR and remote handoff, not a moving branch.** Android baseline is `14ca59d54ba8c470abb45d01798a23919f47bb63` (2.1.26/build45), deployed API compatibility baseline is `bb09fac04d11796ce676555dad094776cd1ef0ce`. Candidate Android is 2.1.27/build46; existing signed install, app data, login and histories must be preserved. This document alone is not CI PASS or production approval.

## Root causes / actual code closures
1. Legacy drive energy rejected valid zero/negative net values; power integration capped long gaps and could over-credit coverage. Preserve physical sign, milliseconds and verified interval coverage; only source-qualified whole-window values drive kWh/100km, with unavailable remaining null.
2. Room average efficiency previously divided known-energy total by *all* distance, diluting averages and producing zero for all-unknown rows. Both all-time and window SQL now select precisely the same energy-known, positive-distance cohort. Independent SQLite oracle executes actual production Room SQL.
3. Summary, detail and fallback energy labels and source contracts distinguish reported, power-integration estimate, EnergyRemaining-delta estimate and unavailable; battery input, AC charger input and driving net are not interchangeable.
4. Charging formerly treated unknown energy like zero for short-charge visibility, and independent display/tariff consumers could read raw legacy energy despite explicit unknown contracts. The list, charts, detail and tariff projection use qualified battery input. Valid zero remains a value; unknown remains visible and unavailable for monetary energy estimation.
5. Go Fleet source counters (DCChargingEnergyIn battery-side, ACChargingEnergyIn charger-side) are independently retained with collector-receipt timestamps (not Tesla-native source sample timestamps), exact window and counter-reset/replay evidence. Partial observation does not become whole-session energy. Completed session metadata persists in existing JSON without production DDL; no SOC-capacity guess.
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
- Charging: full-window battery input vs AC charger input (not the same value), AC/DC, counter reset/replay, collector receipt time versus Tesla source time (unknown), and units, zero/null, manual/actual/free/estimated costs, date and cache boundaries.
- New natural drive and charge notifications: record event end, provider observation, ingestion, API available, mobile persistence, UI and system-notification delivery times independently. Do not open app/force sync to claim passive delivery. No manufactured vehicle events.
- Performance/resources/recovery: background permission denied/restored, network loss, app restart, 0/50/51/large pages, cancellation, user-switch races, CPU/memory ceilings, restart with pending events.
- Multiple real users and first *Fleet* event are separate PENDING_HUMAN / PENDING_NATURAL gates. TeslaMate personal archive and synthetic fixture do not satisfy them.

## Cleanup, deployment and rollback
Source-audit tracks candidate generated/build/received/log/archive artifacts with reference checks. The inspected tracked candidate count was zero; avoid deleting valid fixtures/tests/Room schemas/migrations, official configs, signing material, backups, rollback evidence or history for the sake of cleanup. Untracked Owner files must be preserved; old caches should only be archived with explicit reversible manifest. No production DB UPDATE/DDL, old six SOC or 7113 TPMS backfill, bridge deployment or vehicle actions in this stage.

Keep the current signed build45 APK, current bb09fac container image and exact active config/DB backup as rollback inputs. Before any separately authorized deployment, validate complete backup restore and idempotent rollback; do not silently migrate production. If build46 device validation fails, stop and reinstall the previously known-good **same-signed** build45 with `install -r` only if package downgrade/version constraints are safely satisfied; never force data loss. API remains on bb09fac until separately approved. Source merge to main follows local fixed-SHA installation PASS and doesn't imply production deployment.

## Outcome classification
A source PASS requires a single final immutable SHA + genuine finished Actions runs and independently matched Android/API source closures. Device PASS requires signed package and data-retention evidence. Natural Fleet and multi-user PASS requires genuinely observed separate human/provider events; otherwise explicit PENDING. No unverified zero/estimated energy upgraded to actual. All evidence uploaded to the *same* GitHub repo after privacy redaction; no VIN, trajectories, tokens, signing secrets, raw user databases or full private logs.

## Independent CHANGES_REQUIRED closure (2026-10-09)
The independent local validation of the earlier `fafeac26` candidate was a genuine **CHANGES_REQUIRED**, not a passing Android build. Preserve the exact failed evidence in `docs/ENERGY-LOCAL-CHANGES-REQUIRED-20261009.md`. This new code stage repairs all root causes and runs the unchanged three independent tests from `energy_independent_counter_test.go`. Latest fixed SHA must be taken from PR #17 and matched to its actual Actions run before installing.

- **Kotlin compiler and null/negative data:** `AnalysisSummary` processes nullable energy/efficiency without illegal nullable numeric operations, unsafe `!!` or null-to-zero substitution; valid zero and negative recovery remain distinguishable from unknown. `AnalysisCoverage` and the actual `StatsRepository` DAO consumers count signed drive net values, zero charge energy and qualified AC-paired input correctly. A mix of estimated and reported drive energy is not labeled purely observed.
- **Final-timestamp reset:** `telemetry_core` retains the conflicting battery counter point in the existing persisted charge-point JSON; completion normalizes the legacy scalar only from qualified whole-window battery input. `telemetry_store` admits an equal-timestamp *different-value* safety observation into the idempotent event buffer and open-session reducer without overwriting the ambiguous latest field. Earlier timestamps, repeated event IDs and equal-time identical values remain no-op. Restart/replay and bounded PG list/detail must all agree.
- **Zero AC and mixed AC/DC:** mere `ACChargingEnergyIn` presence (even positive) does not establish charging type. `ChargerPhases` / `FastChargerPresent` explicit mode events must anchor both session boundaries without contradictions; otherwise mode and entire-session AC input/loss/efficiency stay unknown, with counter subset available only as diagnostics. A compatible complete AC mode may calculate estimated loss/efficiency; DC battery input remains separately reported when qualified.
- **All consumer paths:** `ChargeData` / `ChargeDetail` AC input value, tariff estimation, AC/DC filters, chart segments including unknown, TypeBadge, detail metrics and `AnalysisSummary` all honor the qualified source window/physical purpose. Invalid mode can never be rescued by legacy DAO DC IDs or scalar AC totals. No vehicle discovery, OAuth, production bridge or historical backfill is changed.
- **Physical and compatibility gates:** no current persisted Tesla source timestamp is inferred from MQTT collector receipt time. No `EnergyRemaining` / capacity is fabricated from archival SOC. Go Stage2 prototypes are not silently substituted for the deployed `bb09fac` product; only exact reviewed candidate Go file deltas are admissible.


### Central history merge and card presentation regression
The actual `UnifiedHistoryRepository.mergeDrives`/`mergeCharges` path retains a same-scope cached versioned `energy_contract` when a compatible remote response lacks it. An explicit unknown/new counter reset cannot be overwritten by an older raw `energy_consumed_net` or `charge_energy_added` scalar. The canonical merged result is normalized with `withQualifiedEnergy()` before Room persistence and list publication, retaining a valid reported zero and preserving raw provenance inside its contract. The `ChargesScreen` card and tariff use `batteryInputKwh`, not legacy charge scalars. `UnifiedEnergyContractMergeTest` and `ChargeQualifiedEnergyFlowTest` guard cache/re-login/merge and actual UI call sites without a destructive device test. These paths remain identity-scoped; the change neither migrates nor deletes existing user history.

### Mandatory fixed-SHA matrix
The final HEAD must have one *finished* Actions run with all jobs SUCCESS: Go full suite and isolated PG16 mandatory tests (including `TestIndependent*`, `TestEnergyPostgresResetReplayAndACModeScope`, `TestEnergyPostgresBoundedChargeContractSurvivesRestart`), -race, bounded resources, Web tests/build, Android Debug+Release unit tests, lint and signed-package-independent Release R8 builds. Collect actual run IDs, XML test counts, Kotlin compile diagnostics, tools versions, and SHA/tree/parent. Prior runs with Android NOT_RUN or canceled are not sufficient. Preserve and compare existing production build45 package, certificate and app data on install-r; signed install and human/natural Fleet acceptance remain distinct gates.

## Android 11 authenticated-history TLS qualification gate (2026-10-09)
See `docs/ENERGY-LOCAL-DEVICE-QUALIFICATION-20261009.md` for the actual signed installed build46 evidence and `docs/ENERGY-TLS-DEVICE-DIAGNOSTIC-20261009.md` for the full safe type-code map, independent verification command matrix and acceptance gate. The exact source of four `history_context http=none category=tls` failures remains **unknown**; public server health and desktop certificate-chain verification do not rule out phone-specific TLS, route or client environment issues. The additive source change leaves platform system trust, hostname checks, cloud HTTPS selection, session refresh and data ownership untouched. Only fixed-size Java exception *types* reach the optional `tls_cause` value (no throwable messages/certificates/URLs/tokens), and the generic user message no longer wrongly assumes certificate failure. This handoff is **not authenticated-history PASS**. The matching-signer re-install and real scoped API history test must be rerun against the final source SHA, with a short privacy-redacted `HistorySync` observation and actual UI result, before considering `main` merge. Do not repair certificate validation by trusting user CAs, insecure HTTP or changing VPN/proxy/DNS.


## 2026-10-09 TLS transport continuation (source only)

The installed device receipt above remains true for source 8625d28e but its
next-stage TLS qualifier is in
[ENERGY-TRANSPORT-COMPATIBILITY-FINAL-STAGE-20261009.md](ENERGY-TRANSPORT-COMPATIBILITY-FINAL-STAGE-20261009.md).
A repeat `setup-root.sh` could previously drop an active Let's Encrypt
certificate to a self-signed placeholder before reload; insecure
`verify-public.sh` could conceal it with curl `-k`. The new candidate fixes
these source-level deployment-tool defects and adds strict, redacted bounded
TLS qualification plus bb09 consumer-source compatibility tests. **No evidence
shows these defects caused the phone's Oct8 unexpected peer**. The production
API, active nginx, network/trust and original installed APK are unchanged.

Immutable stage HEAD/tree/parent and same-SHA job evidence are supplied by the
final PR17 comment; do not rely on an older green run or mark physical device
or natural Tesla history accepted. Source-only CI is not production permission.

**Post-bb09 compatibility correction (source candidate after 8625):** Source
review of production bb09 `telemetry_core.go` found that the old precontract
MQTT charge scalar can mix AC/DC origins and retain an earlier positive delta
after a reset; the installed bb09 lacks `energy_contract` to disprove the
whole-window claim. The new Android candidate therefore refuses unqualified
legacy numeric energy for Fleet telemetry, Fleet API and local unverified
imports; previously compatible personal TeslaMate archives and untagged
legacy self-hosted responses retain their explicit compatibility path. This
**changes Android runtime source** compared with the old signed install at
8625. That prior independent physical-device receipt remains historically
valid for 8625 only. Original authorized local Codex must independently
rebuild/sign/verify exact final SHA and do a safe same-signer `install-r`
with no app data clear or network modifications, then report fresh device
results. No simulated/old cached UI result proves live authenticated history.


### Full remaining-stage precision and TLS closure addendum

Read [the current complete transport, deployed-bb09, natural precision,
transactional TLS and rollback ledger](ENERGY-TRANSPORT-COMPATIBILITY-FINAL-STAGE-20261009.md)
and the [immutable-SHA local receiving contract](TLS-ENERGY-FIXED-STAGE-LOCAL-RECEIVE-20261009.md).
The previous 29384c91 source failed to compile the new Kotlin test without
`com.matelink.data.sync.toSyncSummary`; this failure must remain historical
FAIL rather than being silently reclassified. A separately confirmed natural
source 3100-point fractional window proved old bb09 whole-second session
boundary formatting misses 0.417 seconds. Candidate Go RFC3339Nano date
serializer/energy metric and actual Kotlin Moshi/Room/consumer regressions
repair that source defect **only in candidate**, never in still deployed bb09.
New TLS scripts cover privileged four-file Nginx rollback, trusted fullchain,
validity windows/key match including normal Certbot live symlinks, explicit
unauth 401 failure, no bearer-token public check and literal-IP-only socket
probe, with synthetic negative tests. Network snapshot 200 is not history
acceptance, and prior unexpected phone peer remains unattributed. All
production deployments, phone network changes and old backfills remain
separately gated. The final immutable SHA and same-SHA actual CI/job evidence
must come from PR17's completed-stage comment and current Actions.
