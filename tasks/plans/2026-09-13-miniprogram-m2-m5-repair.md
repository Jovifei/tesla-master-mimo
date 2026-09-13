# WeChat Mini Program M2-M5 repair plan

## Scope

Repair the audited M1 branch without changing the Android client or production deployment. Implement a real client/server contract behind feature-safe configuration, preserve source-quality boundaries, and keep platform/account gates explicit.

## Steps

- [x] Baseline current branch, source files, Go tests and Mini Program tests.
- [x] Extend the Go API with a feature-gated WeChat session exchange and account-link transaction contract; never expose AppSecret/session_key/Tesla tokens.
- [x] Extend the Mini Program API/session layer with wx.login, refresh/logout, typed errors, lifecycle-safe request generation, and account-scoped vehicle selection.
- [x] Connect readiness to key=telemetry and expose pairing/configure actions with safe status labels.
- [x] Add drives/charges/current/detail pagination adapters and truthful empty/partial/error states; keep placeholder pages from claiming readiness.
- [x] Add tests for auth errors, readiness key selection, pagination, account switching, duplicate history merge, and lifecycle cancellation.
- [x] Run Go tests/vet/build and Mini Program npm ci/typecheck/test/build/audit; review diff and secrets.
- [x] Rebase/push only this feature branch after verification; do not deploy production or submit the WeChat account.

## Review

Evidence will distinguish local source/build, mock API, public transport, WeChat developer tool, real WeChat device, Tesla OAuth and real telemetry. Current release remains blocked until AppID, legal/privacy data, backend secrets and real platform tests exist.

## Review 2026-09-13

- PASS: Go tests, vet and module verification; Mini Program typecheck, 32 Vitest tests, configured WeChat build and bundle secret/dynamic-environment scans.
- PASS: model/status metadata is returned on the first vehicle refresh; readiness preserves Tesla reauthorization errors; WebView OAuth bridge has a manual fallback; refresh/logout cannot resurrect a replaced session.
- BLOCKED: Docker Linux engine, temporary PostgreSQL DSN, formal AppID/business domains, real WeChat Android/iOS, Tesla OAuth/virtual key and first MQTT/event evidence are unavailable in this workspace.
