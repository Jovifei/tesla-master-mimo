# Isolated interaction-browser startup repair

Scope: only the test harness on `codex/miniprogram-interaction-visuals-20261104` in Jovifei/tesla-master-mimo. Production components, providers, storage, session keys, package locks and original development branches are unchanged by this follow-up.

Run 42034776295 at 8a5a20e4857020d39d4683d60fd3cc6dffc0b46f passed the actual 992 Mini consumers, six resource/native-asset tests, types, WeChat bundle and four 52-case timezone gates. The rendering step failed before a browser connection: the ten-second marker-file loop returned no usable Chromium diagnostics. This is diagnostic evidence, not terminal approval.

The capture now starts a fresh isolated browser with an allocated loopback DevTools port and proves readiness with the real JSON endpoint. Startup is bounded to sixty seconds; early process errors, timeout and stdout/stderr are retained. Cleanup removes only the helper's new temporary profile and its own browser. Four Node regressions cover missing marker with real endpoint readiness, early failure, a hung process and invalid deadlines. A separate local smoke connected to actual Chromium, but that smoke is not a replacement for final CI.

All 46 image captures, rendered CJK glyph checks, clicks, zero/unknown scope reset, narrow/large-text non-overlap and foreground/reduced-motion assertions remain mandatory. The browser uses a test-only DOM adapter for actual React components, not native WeChat. Final full SHA/tree/parent, job/artifact identifiers and independently inspected screenshots belong in the final receipt after success.

Local reception follows `LOCAL-RECEIVE-INTERACTION-VISUALS-20261105.md`: App first and Mini second at the receipt's immutable SHAs; no local business-code rewrite, Owner workspace reset, phone changes, real authorization or production deployment. Ordinary source review is not a signed-install or natural Fleet acceptance gate.
