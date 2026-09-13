# M3 auth, page scope and history repair

## Scope

Continue from `5adaa47dea8889a3babb07a1475696c5becccd81` in the isolated
`feature/wechat-miniprogram` worktree. Apply the reviewed M3 candidates only
after their baseline checks, then connect them to the existing pages. Keep the
Android client, production deployment, AppID and real Tesla data out of scope.

## Steps

- [x] Verify the review package hashes, fetch origin, and confirm the clean M3 baseline.
- [x] Wire request scope/lane guards into vehicle, drives, charges and auth pages; handle hide, unload, selection, detail and old finally paths.
- [x] Make cache keys include API/account/vehicle/session scope, validate schema, and report write failures without hiding online data.
- [x] Migrate history evidence merge and pagination completeness into page state and tests.
- [x] Add server-side WeChat transaction ownership, same-user reauthorization, safe status lookup/claim, and atomic grant/link completion.
- [x] Add temporary PostgreSQL failure/concurrency coverage where the local environment permits.
- [x] Run typecheck, Vitest, configured WeChat build/scans, Go tests/vet/build and dependency audits; record skips and blockers.
- [ ] Commit and push in session, history, auth, and test/docs layers without merging main or deploying production.

## Evidence boundary

Package-level 41-case runs are candidate evidence only. Full project tests,
platform OAuth, virtual-key confirmation, MQTT, PostgreSQL integration and
release security remain separate gates and must not be collapsed into PASS.

## Review 2026-09-13

- PASS: M3-A page wiring and M3-B proof/status/claim/cancel/atomic transaction source paths; full frontend Vitest 42/42 and configured build pass.
- PASS: Go test/vet/build/module verification; new PostgreSQL transaction/concurrency tests are present but SKIP without `JOURVOLT_TEST_DATABASE_URL`.
- BLOCKED: online npm audit reports 12 production vulnerabilities and full-tree advisories; real WeChat, Tesla, key, MQTT, device and production deployment remain unverified.
