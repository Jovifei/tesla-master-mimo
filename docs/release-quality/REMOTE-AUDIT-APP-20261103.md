# App review implementation — 2026-11-03

Repository: `Jovifei/tesla-master-mimo`. Branch: `codex/app-foundation-ui-integrate-20261025`.
Baseline: `10e501541f33f8856b787b24833e14677a02792d`. Candidate: 2.1.65 / 84.

## Wired changes

Cost consumes qualified partial charging observations through actual Room, analysis, ViewModel and Compose consumers. The separate observed-part estimate preserves zero, displays approximation and never enters whole bills, whole energy or unit-price denominators. Manual fees are observed locally and reproject the open cost page without reauthentication or full history downloads.

Drive/efficiency/range/battery consumers read only drives, and charge/cost consumers read only charges. Unrelated endpoints and DAO operations are not invoked. The combined analysis path, identity/epoch fences, cancellation, raw receipts and evidence-preserving merges remain. Successful empty responses retain the archive but cannot certify retained receipts as fresh.

Battery health/status publishes before history completes. Annual aggregates are read in the existing Room transaction after the awaited refresh. Selected-tab taps do not rebuild their route, while fresh arguments still protect vehicle changes. Parking title and observed-part estimate explanations are localized. Existing page geometry, app ID, session storage and Room schema remain.

## Required verification

HistoryFamilyReadTest tests real repository reads, forbidden unrelated I/O, offline cache, empty-response retention, cancellation and scope changes. CostRoomFirstViewModelTest traverses real Room and the real ViewModel, including partial estimates and confirmed-zero reactive edits. PartialCostSummaryAndroidTest renders the actual Compose summary at 320dp. All existing Debug/Release, lint, R8, API35, Mini, Go/isolated PostgreSQL/race/TLS/Web checks remain mandatory at the final fixed SHA. Counts must be read from actual XML, not inferred from this document.

## Independent local handoff

Fetch final fixed SHA into a separate clean worktree and verify SHA/tree/parent/diff. Never reset/clean the original E-drive workspace or overwrite Owner files. Rebuild Debug/Release, both lint and R8; run isolated emulator tests. Compare persistent history, fees, settings and valid sessions during a separately authorized non-destructive upgrade. Signing/installation, production deployment, DDL and historical repair remain separate gates.

This source stage does not claim full encrypted-library restore, every-screen native Mini pixel parity, natural Fleet notifications/TPMS acceptance, or production WeChat configuration. It does not delete history, change credentials, migrate production data, or deploy services. Full-library backup and real-device/native visual acceptance require their own completed implementation/evidence; CI cannot substitute for those gates.
