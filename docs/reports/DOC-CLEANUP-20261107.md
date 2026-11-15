# Documentation and artifact cleanup record

Date: 2026-11-07
Owner authorization: Jovi explicitly authorized deletion of the six historical plan/handoff documents listed below.

## Deleted after authorization

- `docs/plans-handoffs/GPT56_COMMANDER_HANDOFF_2026-07-16.md`
- `docs/plans-handoffs/HANDOFF-2026-10-24-C2C-8F2D.md`
- `docs/plans-handoffs/PHASE-HANDOFF-2026-07-09.md`
- `docs/plans-handoffs/PLAN-2026-10-11-local-persistent-recovery.md`
- `docs/plans-handoffs/PLAN-2026-10-13-energy-to-miniprogram.md`
- `docs/plans-handoffs/PLAN-2026-10-18-compact-drive-history.md`

These were historical stage plans or handoffs. Current rules, production configuration, execution gates, architecture decisions, release evidence, and lessons remain in the root authority files and retained archives.

## Not deleted in this stage

- No APK, signed artifact, backup, database dump, token/configuration file, server log, or worktree was deleted.
- `docs/plans-handoffs/HANDOFF-2026-10-25-PR27-ROOM-FIRST-AND-PARKED-UI.md` retained for source/CI/device/data-boundary evidence.
- `docs/plans-handoffs/JOURVOLT-ANDROID-IMPLEMENTATION.md` retained for long-lived Android/auth boundaries.
- `docs/plans-handoffs/LESSONS-2026-10-02-history-resource-bounds.md` retained as experience rules.
- `docs/plans-handoffs/PLAN-2026-10-14-unified-visual-data-stage.md`, `PLAN-2026-10-24-data-foundation-remote-stage.md`, `PLAN-2026-10-25-room-first-analytics.md`, and `REMOTE-FULL-DATA-STAGE-MANDATE-20261007.md` retained for decisions, gates, and rollback/production boundaries.

Download artifacts and Git worktrees are audited separately. They require their own path-level disposition and are not implicitly approved by the six-document deletion authorization.

## Download and worktree audit

### Safe-to-delete candidates found

These four files were generated only for this cleanup/token-analysis stage and contain no APK, signature, backup, server evidence, or user data:

- `E:\Claude_allow\Download\plans-handoffs-review-20261107.json`
- `E:\Claude_allow\Download\analyze_tool_meta_20261107.py`
- `E:\Claude_allow\Download\analyze_tokens_20261107.py`
- `E:\Claude_allow\Download\matelink-doc-broken-links-20261107.json`

Deletion was attempted but the workstation safety policy blocked the filesystem delete operation. They remain pending explicit/manual cleanup.

### Must retain pending further approval

- All `matelink-*.apk`, `*.properties`, `*.key`, `*.dump.enc`, `*.tar.gz`, JSONL/log evidence, and screenshot/XML evidence in `E:\Claude_allow\Download` remain untouched. They may be required for signature comparison, rollback, audit, or data recovery.
- Existing non-prunable worktrees remain untouched, including the current checkout, `vehicle-home-images`, all `E:\project\_worktrees\matelink-*` directories, iOS, and Mini Program worktrees. Their branches or files may still contain code, uncommitted changes, or rollback evidence.

### Git worktree metadata cleanup

- `git worktree prune --verbose` removed 11 registrations whose gitdir files pointed to non-existent directories under `E:\Claude_allow\Download`.
- No existing worktree directory was removed.
- Remaining worktrees require a separate path-by-path owner decision before deletion.
