# Git Commit Review - 2026-07-09

## Plan

- [x] Inspect current git status and repository ignore rules.
- [x] Classify untracked files as source/docs versus local environment or build output.
- [x] Add minimal ignore rules for files that should not be committed.
- [x] Stage only necessary repository files and create one commit.
- [x] Verify staged commit contents before commit.

## Findings

- `android/.gradle/` is Gradle cache/local state and should not be committed.
- `android/.idea/` is local IDE metadata for this checkout and should not be committed.
- `android/app/build/` is Android build output and should not be committed.
- `android/local.properties` is machine-local Android SDK configuration and should not be committed.

## Review

- `git status --short --ignored` shows only `.gitignore` and `tasks/` as untracked commit candidates.
- `git check-ignore -v` confirms `android/.gradle/`, `android/.idea/`, `android/app/build/`, and `android/local.properties` are ignored by the new root `.gitignore`.
- `git diff --cached --stat` shows only `.gitignore` and `tasks/todo.md` staged.

# App Completion Handoff - 2026-07-09

## Plan

- [x] Read docx handling guidance and app_mimo memory boundaries.
- [x] Inventory source documents from `docs/git_ref`, `docs/PLAN`, `docs/PRD`, the architecture doc, and the Word implementation plan.
- [x] Inspect current Android, iOS, shared, and web implementation status.
- [x] Assess feature completion, data configuration flow, and user guidance gaps.
- [x] Write phase handoff documentation under `app_mimo/docs`.
- [x] Verify generated docs and record remaining proof limits.

## Review

- Created `docs/PHASE-HANDOFF-2026-07-09.md`.
- Captured reviewed document list, completed/unfinished scope, platform status, data configuration model, and recommended user onboarding flow.
- Kept `E:/project/tesla_master/docs/git_ref/` read-only.
- Native Android/iOS build proof remains gated by local toolchain availability; this pass is source/document inspection plus documentation output.

# app_mimo Data Setup Implementation - 2026-07-09

## Plan

- [x] Create implementation branch.
- [x] Save implementation plan under `docs/superpowers/plans`.
- [x] Inspect Android/Web/iOS setup code and existing tests.
- [x] Add failing/targeted tests where feasible before behavior changes.
- [x] Implement Android stronger connection testing and first-run guidance.
- [x] Implement Web persisted real-data configuration and `/api/v1` paths.
- [x] Implement iOS source-level multi-instance management.
- [x] Update docs/handoff for completed and remaining work.
- [x] Run available verification and record proof limits.

## Review

- Branch: `codex/app-mimo-data-setup`.
- `E:/project/tesla_master/docs/git_ref/` remains read-only.
- Android unit test target was added for connection URL/outcome rules, but Gradle could not run because `JAVA_HOME`/`java` is unavailable.
- Web `npm run build` and `npm run lint` pass after restoring local `node_modules` with `npm install`.
- iOS multi-instance changes are source-level only on Windows; Xcode build/simulator proof remains required.

# Self-hosted TeslaMate Guidance - 2026-07-09

## Plan

- [x] Inspect current README, Settings, Onboarding, About, and map/key copy.
- [x] Update README with real-data deployment, server, security, TeslaMateApi, and AMap key guidance.
- [x] Update Android Settings/About strings for self-hosted TeslaMateApi-compatible API and AMap key ownership.
- [x] Update iOS Onboarding/Settings/About copy for API root address, server requirement, and AMap key ownership.
- [x] Update Web Onboarding/Settings/About copy for API root address, server requirement, and AMap key ownership.
- [x] Run available verification and record proof limits.
- [x] Report candidate files for Jovi approval before any Git staging or commit.

## Review

- Git staging/commit is explicitly blocked until Jovi approves the candidate file list.
- Implementation was delegated into bounded docs, Android, iOS, and Web slices, then integrated in the parent thread.
- Verification passed: `npm run lint`, `npm run build`, XML resource parsing, Web message JSON parsing, sensitive token/key scan, and `git diff --check`.
- Verification blocked: Android `testDebugUnitTest` cannot start because `JAVA_HOME` is unset and no `java` command is on PATH.
- iOS verification remains source-level on Windows; Xcode build/simulator proof requires Mac/Xcode.
