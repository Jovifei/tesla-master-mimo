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
- [x] Commit and push in session, history, auth, and test/docs layers without merging main or deploying production.

## Evidence boundary

Package-level 41-case runs are candidate evidence only. Full project tests,
platform OAuth, virtual-key confirmation, MQTT, PostgreSQL integration and
release security remain separate gates and must not be collapsed into PASS.

## Review 2026-09-13

- PASS: M3-A page wiring and M3-B proof/status/claim/cancel/atomic transaction source paths; full frontend Vitest 42/42 and configured build pass.
- PASS: Go test/vet/build/module verification; new PostgreSQL transaction/concurrency tests are present but SKIP without `JOURVOLT_TEST_DATABASE_URL`.
- BLOCKED: online npm audit reports 12 production vulnerabilities and full-tree advisories; real WeChat, Tesla, key, MQTT, device and production deployment remain unverified.

## Review 2026-09-14 M3.1

- PASS: auth commits `94fcb52` and history commit `31af6e8` close claim/cancel/logout ownership races, terminal recovery, strict callback handling, valid-prefix pagination, durable cache identity, and vehicle/page lifecycle races.
- PASS: release gate commit `4a111c9` increments the mini program to `0.2.2`, adds a mandatory PostgreSQL gate script, and records clean-install evidence.
- PASS: current project typecheck, Vitest 48/48, clean `npm ci` typecheck/Vitest/build, configured WeChat build, Go test/vet/build/module verification.
- BLOCKED: Go has 15 PostgreSQL skip events without `JOURVOLT_TEST_DATABASE_URL`; production dependency audit remains 12 vulnerabilities; real WeChat/Tesla/key/MQTT/device/history recovery and production deployment remain unverified.
