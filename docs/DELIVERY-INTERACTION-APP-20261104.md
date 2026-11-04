# App interaction visuals: wired scope and review

Branch `codex/app-interaction-visuals-20261104`; baseline `984196d8c1a6deaff124eaa8dd6c45f55c40e700`. This is an additive presentational stage, not a redesign or a provider/data migration. Existing version/package/schema, UI navigation, metrics, history and authorization actions remain.

## Implemented consumers

1. Tesla login and vehicle pairing consume existing verified/pending/action-required/error state. Public bundled real vehicle artwork is explicitly an illustration, never claimed as the bound vehicle. The original official authorization flow remains authoritative.
2. TPMS shows a car-shaped position schematic with four independent qualified reading controls. Clicking focuses the existing corresponding trend; clicking again restores all wheels. A reported zero is preserved; stale/unknown are distinct from observed and no pressure thresholds are invented.
3. Trip details add A/B focus within the existing endpoints area. The schematic is explicitly not a GPS route; existing maps and coordinates are not modified.
4. Parked charge rows show only recorded start/end interval rails. Unknown end and missing gaps never create charge/stop events or estimated energy.
5. Existing dashboard openings warnings can expand a category-level vehicle diagram. Door/window flags are aggregate; no particular opening is inferred.
6. Existing drive/charge empty states receive small ornaments using bundled artwork, without replacing original reasons or adding fake values/actions.
7. The original brand loading mark is retained; its motion follows lifecycle and system animation settings. No data is written by visual components.

## Review corrections

Initial absolute-overlay four-wheel layout could collide with the car at narrow widths or large fonts. The actual consumer now uses independent weighted side columns with a bounded center image. API35 regression checks actual semantic bounds at 320dp, fontScale 1 and 1.8, and real selection behavior. This changes only the new diagram, not existing metric cards or chart calculations.

The shared JSON/SVG/vector contract is checked by `tools/interaction-stage/verify-visual-contract.py`. App uses existing real bundled `myj_PPSW_WY19P.png` artwork where a vehicle photo is appropriate; the neutral top-view asset is identified as a position schematic, not exact model identification. All numerical values and labels remain native UI text.

## Evidence and handoff

Focused predecessor runner `41783472003` successfully tested both JVM variants and AndroidTest compilation before normally pushing source `bdad99e372bf2d2e46b2969a49d743b22714e4b4`. Artifact `13301130336` has SHA-256 `1799dc1f2009cee7c2d99cfcb13db9e7c7c0a2a5846bb768619f295be3732d41`. This is precursor evidence only, not this final source's full acceptance.

Permanent workflows run complete Android, Mini compatibility, isolated Go/PostgreSQL/race/TLS/Web, source verification, and API35 consumers on the final fixed source. Terminal receipt must record this exact SHA/tree/parent and actual job/artifact/XML results. Queued jobs or an older green SHA cannot establish approval. Temporary opaque implementation transports/runners are removed after application source publication; permanent tests remain.

Follow `LOCAL-RECEIVE-INTERACTION-VISUALS-20261104.md`, App first and Mini second. Do not touch original development branches, main, Owner worktrees, phone data or production. Native Mini rendering, real provider authorization, separately authorized signed installation and genuine natural-data acceptance are not established by these synthetic component screenshots.
