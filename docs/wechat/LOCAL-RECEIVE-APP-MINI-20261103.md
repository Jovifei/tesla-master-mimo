# Independent local receive — App then Mini (2026-11-03)

Use only `Jovifei/tesla-master-mimo`. The remote final receipt supplies two immutable full SHAs, their trees/parents, run IDs and artifact IDs. Do not receive a branch-tip implicitly or substitute an older green commit. App branch is `codex/app-foundation-ui-integrate-20261025`; Mini branch is `codex/miniprogram-e-migration-20261029`. Original review baselines are App `10e501541f33f8856b787b24833e14677a02792d`, Mini `46ff7cda3f082a250c6a1468fa9aac6772767497`.

## Owner preservation and receipt

Read AGENTS.md in the received checkout. Keep the original E-drive worktree, untracked Owner files, app data, credentials, backups and source history untouched. Create two independent clean worktrees or clones, explicitly at the receipt SHAs. Verify `git rev-parse HEAD HEAD^{tree} HEAD^`, ancestry, and changed paths. No reset --hard, clean, force-push, main merge, production deployment, historical backfill, network configuration changes or new agent task. Do not cherry-pick the other branch's backend: App and Mini preserve separate API feature baselines.

## App first

Read `docs/REMOTE-AUDIT-APP-20261103.md`. Independently run Debug/Release JUnit, both lint, R8 and AndroidTest APK builds; parse actual XML and report all failures/errors/skips. Run isolated emulator native tests, including PartialCostSummaryAndroidTest and existing Room migration/identity/cancellation consumers. Verify full and partial cost labels, confirmed zero, on-page manual fee updates, unrelated-family network isolation, retained-cache freshness and selected-tab behavior. Same certificate, application ID and migration/data retention are separate device gates; do not install or clear data without explicit device permission. App candidate is 2.1.65/build84.

## Mini second

Read the Mini branch's `docs/REMOTE-AUDIT-MINI-20261103.md`. Run locked dependency installs, all Mini tests, typecheck, resource/native asset tests and clean WeChat build. Run the added timezone/retention workflow commands locally in UTC, Asia/Shanghai, Asia/Tokyo and America/Los_Angeles. Test cold module reload with preserved synthetic storage, valid login without another auth request, manual zero, user-data capacity admission, partial energy rejection cases, real list/cost consumers and App-equivalent hero geometry. Native WeChat visual validation uses synthetic data first; no upload, release or real login operation without its separate authorization.

## Evidence and rollback

Return one consolidated GitHub evidence receipt with source identities, exact commands/exit codes, parsed XML, artifact hashes, and failures with minimal sanitized traces. Do not implement business-code fixes locally; report failures to the same remote stage. No token, VIN, account identifiers, addresses or trajectories in public evidence. A missing or failed gate stays NOT_RUN/FAIL, not PASS. Never roll back by uninstalling or clearing user storage; source rollback and compatible data-preserving package rollback are different operations.

Real Fleet consent/vehicle confirmation, optional WeChat binding configuration, physical visual equivalence, signed installation, natural events/notifications and production backup/rollback remain separate gates. Normal-update retention does not promise survival after uninstall, explicit storage clearing, authorization revocation or device loss. Summary export is not a full recovery backup.
