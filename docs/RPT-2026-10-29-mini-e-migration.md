# 2026-10-29 Mini migration to the E original directory

## Source and scope

- Workspace: `E:\project\tesla_master\app_mimo`; branch: `codex/miniprogram-e-migration-20261029`.
- Starting commit: `cdfe0b26a7abfe593df9e30b17be60d5cdfd70da`; imported source: `39aa883b160d6229cb9162779af2caea87c486c3` (Mini 0.6.1).
- Imported the Mini client, its vehicle-asset tools and fixture, and the Mini OAuth/WeChat binding backend. The six Mini-only `.gitattributes` rules were restored to preserve generated files' required LF line endings. Three imported files had whitespace-only cleanup.
- Existing Android/iOS changes and the original Android branch remain untouched. The existing Mini `.env`, dependencies and `dist` remain in place. Original changed files and targeted pre-migration files were recorded under `E:\Claude_allow\Download\matelink-mini-e-migration-20261029-188e0735`.

## Verification

- Mini: 60 Vitest files, 805 tests passed; six vehicle-asset tests passed; TypeScript check passed.
- Backend: 467 Go test results passed with zero skips against a temporary PostgreSQL 16 database; `go vet ./...` passed. The temporary container was stopped.
- WeChat build: passed from a 383-file, hash-checked copy of the E-root sources; 197 output files. This was an isolated compilation check using `https://build-validation.invalid`, not a release package.
- Integrity: 640 pre-existing dirty files and 957 protected Android/iOS and old `dist` files retained their prior hashes; 376 imported files match the source Git blobs exactly, with only three whitespace-only differences.

## Boundaries

No production deployment, device operation or WeChat upload was performed. Real WeChat binding and same-account App/Mini acceptance remain separate from these local checks. Project Hub revision could not be read because the service refused the connection; no Hub event was sent.

Deletion of old files awaits identification of the exact directory and review of any unique or ignored content.
