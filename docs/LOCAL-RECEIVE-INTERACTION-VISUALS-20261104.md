# MateLink interaction visuals — independent App then Mini reception

Repository: `Jovifei/tesla-master-mimo` only. Work stays in the original remote stage; this is not authorization for a new agent implementation task.

## Source identity

Receive two full immutable commit SHAs from the terminal remote receipt, not floating branch tips. App branch: `codex/app-interaction-visuals-20261104`, descended from `984196d8c1a6deaff124eaa8dd6c45f55c40e700` on `codex/app-collection-recovery-20261029`. Mini branch: `codex/miniprogram-interaction-visuals-20261104`, descended from `55d49b921f541c5c1d793c88e64dfaac51ee32b2` on `codex/miniprogram-e-migration-20261029`. These original development branches and main are not modified by this stage.

For each fixed source verify `git show -s --format='%H%n%T%n%P' HEAD`, ancestry, the full baseline diff, and artifact SHA-256. Use a separate clean worktree or clone for each source. Do not reset/clean/stash the original E-drive workspace, overwrite Owner dirty or untracked files, or replace one branch's backend with the other. Read AGENTS.md in the actual checked-out worktree. Follow the local project-hub reporting contract only from its actual local installation and worktree; never claim the remote environment executed that reporting tool.

## App first

Run the exact source's full `tools/energy-stage/android-ci.sh` in an isolated build environment, including real Debug and Release tests, both lint variants, R8, and APK assembly. Supply the existing explicit non-secret release endpoints; do not remove Release guards or request private signing material. Parse XML by directory (`testDebugUnitTest` versus `testReleaseUnitTest`), not class names. Compare actual totals/failures/errors/skips against the final receipt.

Run isolated API35 instrumentation with `tools/energy-stage/android-instrumentation-ci.sh`; do not point it at an Owner phone. It refuses non-emulators and exports only named synthetic fixtures. Verify real clicks for four-wheel selection/deselection and A/B focus, qualified zero versus missing data, scope-reset removal, narrow 320dp layout at font scales 1 and 1.8, light/dark screenshots, authorization states and recorded charge intervals. Read `InteractionVisualAndroidTest`, `InteractionVisualStateTest`, and the shared visual contract.

Preserve existing package ID, Room schema, account session keys and original brand loading logo. This source does not upgrade an installed phone. After separate installation authorization, compare package/certificate and version compatibility; use in-place replacement only, never uninstall or clear data. Source revert is not a proven database downgrade.

## Mini second

Preserve the owner's 0.6.5 package versions and locks. Use `npm ci --ignore-scripts --no-fund` and `npm ci --prefix test-tooling --ignore-scripts --no-fund`, typecheck, complete `npm test`, clean `npm run build:weapp`, and the permanent four-timezone interaction workflow. Verify urlCheck remains true, no real fixture mode is enabled, old storage keys/schemas/sessions remain, and existing resource tests still pass.

Use the real WeChat DevTools native bundle for final platform rendering: same viewport, locale, theme and synthetic fixture as App. Browser renders of actual React consumers are useful visual regression evidence but are not WeChat-native acceptance. Check capsule clearance and original navigation; the stage does not redesign those controls. Do not upload, change experience versions, publish, or exercise real Tesla/WeChat accounts from this source receipt.

## Acceptance matrix

| Area | Required behavior | Negative cases |
| --- | --- | --- |
| Binding and authorization | Existing state drives compact connection art; original consent/actions remain | Animation elapsed time never marks success; error/pending/action-required outrank stale success |
| TPMS | Qualified per-wheel values, timestamps and existing rule warnings; click focuses corresponding recorded chart | Zero remains zero; unknown/future/stale/source conflict never becomes green normal; 320dp and large text do not overlap the car |
| A/B | Local endpoint focus and explicit schematic disclaimer; original real map remains | Missing endpoint stays unknown; no new route coordinates or automatic vehicle movement |
| Parking | Only actual returned/saved charge intervals are rendered | Missing end, duplicate/conflicting source, gaps and partial coverage cannot manufacture a stopped/charging event |
| Openings | Category-level report controls overlay and text | Aggregate door/window flag never identifies a particular door/window; missing stays unknown |
| Empty/loading | Original reasons, actions and brand logo; bounded or lifecycle-paused motion | Existing cached content is not deleted, no fake measurements, no network or storage side effects |
| Data/upgrade | Same identity, receipts, user fees, settings and valid sessions | No migration, backfill, global clear, credentials change or provider configuration change |

## Consolidated return

Return one sanitized GitHub evidence receipt with both full SHA/tree/parent, commands and exit codes, actual JUnit totals, artifact IDs/hashes, synthetic screenshots and observed failures. Distinguish source, synthetic JVM, emulator, browser, native Mini, device, provider and production evidence. Local Codex independently verifies and builds; it does not implement business fixes or silently change expected values. Report failures to the same remote stage.

No main merge, force-push, production deployment, DDL, archive repair, live bridge change, vehicle wakeup, phone/network changes, or secret disclosure. Genuine authorization/WeChat binding, natural Fleet multi-user events, real TPMS/notifications and data-preserving installation remain separate human/provider/device gates. Keep existing backups and full history.
