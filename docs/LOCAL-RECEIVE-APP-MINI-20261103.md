# App then Mini — independent local reception (2026-11-03)

Repository: `Jovifei/tesla-master-mimo` ONLY. Receive the two full fixed SHAs from the remote terminal receipt; a newer branch tip is not an automatic substitute. App branch is `codex/app-foundation-ui-integrate-20261025`; Mini branch is `codex/miniprogram-e-migration-20261029`. The original review baselines are App `10e501541f33f8856b787b24833e14677a02792d`, Mini `46ff7cda3f082a250c6a1468fa9aac6772767497`.

## Preserve and verify

Use two independent clean worktrees/clones. Read AGENTS.md in each actual checkout; verify exact HEAD/tree/parent, ancestry and the complete baseline-to-delivery diff. Do not reset/clean the original E worktree, change Owner files, replace one branch's backend with the other, force-push, merge main, alter phone data or network settings, deploy or backfill. Never request passwords, MFA, tokens or signing secrets in the evidence. Local Codex independently tests/builds; it does not implement business-code repairs or start another task.

## App first

Read REMOTE-AUDIT-APP-20261103.md and REVIEW-20261103-ANALYTICS-IDENTITY.md from the App SHA. Candidate is 2.1.65/build84. Run Debug/Release tests, both lint, R8, APK/AndroidTest assembly; parse real JUnit XML, including established skips. Run an isolated emulator with actual native tests. Verify the real partial-cost Compose consumer, Room-first cold-to-live cloud identity, UID-rebind rejection, per-family request isolation, retained-history freshness, cancellation and reactive manual-zero fees. Compare source and synthetic evidence separately from real phone acceptance.

## Mini second

Read REMOTE-AUDIT-MINI-20261103.md at the Mini SHA. Candidate is 0.6.4. Use locked npm installs, no dependency auto-fix: npm ci --ignore-scripts --no-fund, npm ci --prefix test-tooling --ignore-scripts --no-fund. Run typecheck, npm test (native-resource tests plus all Vitest consumers), then clean build:weapp. Run the exact four-timezone commands in .github/workflows/mini-review-timezones.yml. Verify urlCheck remains true and vehicle asset hashes are unchanged. Test existing-session preservation across module reload, manual-zero preservation, failed-capacity writes retaining prior records, source-instance conflict isolation, separate full/partial estimates, local history before remote completion and the App logical hero geometry.

## Consolidated evidence and release matrix

Return one sanitized GitHub evidence receipt: both full SHA/tree/parent, command/exit status, actual XML totals/skips, artifact hashes, observed issues and no-success substitutions. Report failures to the same remote stage; no local business-code repair. Preserve evidence and stop on uncertain data migration or incompatible signing.

| Gate | Allowed verification | Separate permission / rollback boundary |
| --- | --- | --- |
| Source and CI | Read/download fixed source and isolated artifacts; rerun isolated tests | No main merge or production writes |
| App package | Build exact SHA and inspect package metadata | Signing uses existing local protected certificate; installation needs explicit device authorization |
| Phone update | After authorization, protect data and verify same package/certificate; in-place replacement only | Never uninstall/clear; older APK alone does not prove data rollback |
| Mini native UI | Synthetic DevTools/native rendering and scroll/edit/navigation comparison | No upload, experience-version change, release or real account operation from this source task |
| Login retention | Synthetic valid/expired/revoked sessions and scoped cache tests | Real Tesla consent and optional WeChat configuration must be completed by the user without exposing secrets |
| Production/API | Review isolated compatibility, backup and rollback candidates | App and Mini backend branches must not overwrite each other; no deployment, DDL or history repair |

## Completed-plan comparison and honest remaining scope

Implemented: qualified partial-cost page consumers and separate bill/estimate denominators; App independent history-family reads and cached-receipt freshness; early battery status, post-refresh annual Room snapshot, live cost edits; same-car cloud identity normalization with real rebind rejection; Mini local-first selective reads, source-conflict projection, date portability, logical hero geometry, user-data capacity reservation and update-retention regressions. Temporary implementation runners are removed from the terminal source; permanent tests stay.

Still engineering work, not a human-only gate: full encrypted archive backup/restore (summary export is insufficient), complete scoped cross-tab state restoration, every App route's Mini feature/pixel parity, broader shared design tokens, and a reconciled server release baseline. Existing dependency audit advisories also remain; locked toolchains were not silently upgraded. No claim that these roadmap items shipped in this bounded source stage.

Separate natural/human gates: real Fleet vehicle consent/first events/multi-user isolation, natural TPMS and notifications, real optional WeChat binding, native same-device visual comparison, data-preserving signed installation and production backup/deploy/rollback. Normal-update retention is not a guarantee after uninstall, explicit storage clearing, authorization revocation or device loss.
