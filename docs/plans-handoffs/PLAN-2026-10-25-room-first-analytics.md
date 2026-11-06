# PR #27 — 2026-10-25 offline-first analytics stage

## Ownership and source gate
Branch `codex/app-foundation-ui-integrate-20261025` is owned by remote ChatGPT; parent checkpoint `84e3ca91328043eb21209de9be51db477f8c6ce4`. Main, PR #22/#26, installed build 70, untracked E: work, identities, Room, live services and backups are untouched. No DDL or provider access is part of this source stage.

## Baseline audit and issues
- Build 71 UI already has the right-aligned trip SOC and separated parked charge sessions. Adjacent SOC is not measured kWh and time-integrated unqualified power is not 100% coverage.
- Backend parked `battery_delta` had reversed sign relative to the explicit start and end SOC. Same-ID local/server charge time conflicts could appear as two apparent charging sessions. Both defects receive source corrections and assertions.
- The draft PR branch was absent from all three Actions push filters, so its supplied HEAD had no commit-associated run. Trigger and job inclusion must match the owned branch.
- `AnalysisHistoryRepository` and `UnifiedHistoryRepository` already use verified owner/origin/vehicle Room history, but analytics screens waited for provider history pages. Battery health similarly delayed already-saved history and independently scoped last-status display. Extend the one verified cache path; do not invent a second persistence format.

## Contracts and execution
1. Read-only `UnifiedHistoryRepository.loadCached` admits only previously verified identity mapping, account, origin, mode and stable vehicle; double-check after DAO reads. No provider request, no legacy link, no write, no cross-account numeric ID fallback. Missing Room is unknown.
2. Feed the same eligible evidence projection to `AnalysisHistoryRepository.loadCached` with `STALE` freshness. Keep real zero and signed recovery, exclude quarantined samples and unqualified raw kWh exactly as the remote projection does.
3. Efficiency and cost must publish persisted scoped history before remote loading completes; later fresh data supersedes it. Network failure must retain clearly stale saved values. Cost reloads current currency and locally saved manual overrides for the same stable vehicle and charge ID; it never prices missing energy as zero.
4. Battery may preload local historic trend and the existing scoped status receipt. Saved status/trend receives a visible offline banner, never an apparently live health-capacity claim. When remote data is unavailable, health capacity remains unavailable. No guessed SOC→kWh.
5. When account/origin/vehicle changes, synchronous identity checks and load generations reject both cached and delayed remote emissions, including ABA. Retain existing list/detail and charger provenance contracts.

## Verification and human gates
- Run focused Room/identity and page negative tests, full Debug and Release unit XML/lint/R8, API35 native Compose/Room, Go with isolated PG16 and upstream PG18, race/TLS/Web, Mini, image and audit jobs at the final exact SHA.
- API35 synthetic fixtures, screenshot/layout evidence and CI are not physical-device or real provider acceptance. Report screenshot availability separately if screenshots cannot be inspected.
- Local Codex receives only the final fixed SHA for independent verify/sign/install preserving app data; no installation or phone mutation from this stage. Natural Tesla Fleet authorization, parking sessions/energy, 30-day TPMS, notifications, provider/source-instance cross-client equivalence, Web/Mini product parity and production backup/rollback remain separate gates.
