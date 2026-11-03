# Mini review implementation delivery — 2026-11-03

Only `Jovifei/tesla-master-mimo`, branch `codex/miniprogram-e-migration-20261029`. Original review baseline `46ff7cda3f082a250c6a1468fa9aac6772767497`. Candidate remains **0.6.4**. App review reference is `41c8477c70957cecab1c740234dc98e4ef85a6e4` on its separate App branch; do not replace this branch's backend with the App branch's backend. Exact final receipt may supersede that reference only after a reviewed descendant passes its own full gates.

## Wired scope

Read `docs/REMOTE-AUDIT-MINI-20261103.md`. The real charge list and cost page separate qualified observed-part estimates from whole bills/estimates. Manual zero wins. Efficiency and drive curves do not request charge history, and charge curves do not request drives. Local history is published before provider completion and retained on empty responses without false fresh-coverage claims. The actual hero frame follows the App's 170/160 logical-pixel geometry. Native WeChat controls and complete every-screen visual equivalence are not claimed.

Existing optional WeChat binding/refresh/recovery remains the sole account-restore path. A valid-session module-reload regression preserves manual zero, trips and saved history without another login request. No storage key, account, credential, history schema or user setting is reset for this version. Four-timezone tests prove local-calendar rendering, not a hardcoded Shanghai display on all devices.

## Non-growing protected-user-data correction

Review of the new 800KiB total admission budget and 128KiB protected-user reserve found that legacy over-budget caches permitted non-growing replacements, while protected records were rejected even for a same-size correction. This follow-up allows an existing key's equal-size or smaller replacement for protected user data too. It does not increase either budget, admit new over-budget keys, expand values, weaken identity checks, or evict any other account's history.

`cache_budget.test.ts` invokes the actual `writeChargeCostOverride`, `readChargeCostOverride` and `clearChargeCostOverride` consumers. It first saves a fee, retains an oversized synthetic legacy archive, accepts a confirmed zero correction, rejects growth/new records/foreign account/invalidated identity, and permits the explicitly requested removal while preserving all unrelated keys and archive bytes.

Before commit, an isolated execution of the actual transpiled TypeScript modules reproduced RED on the prior budget rule and GREEN on the new rule; six existing native/resource tests also passed. This smoke used the available TypeScript compiler and is not a replacement for the locked full CI. The final commit must pass locked typecheck, full Vitest/JUnit, WeChat production build, all six native/resource tests and four timezone runs. Keep failures red; no retry/skip or older successful SHA substitutes for this final source.

## Exact source and evidence handoff

The final GitHub receipt supplies this commit's full SHA, tree and parent plus workflow/job/artifact IDs. Verify with `git rev-parse HEAD HEAD^{tree} HEAD^`, and receive **App first, Mini second**, following `docs/LOCAL-RECEIVE-APP-MINI-20261103.md` in separate clean worktrees. Baseline `80634ca6beac94eabb9fdb472ce4cd67403180eb` passed full Actions `41498937841` and four-timezone `41498937941`; those establish the inherited baseline, not this commit's test result.

| Gate | Required check | Preservation/rollback boundary |
| --- | --- | --- |
| Source | Exact receipt SHA/tree/parent and allowed changed paths | Original E-drive/Owner files untouched; no main merge or force push |
| Mini | Locked npm install, typecheck, real tests, resource tests, clean WeChat bundle | No test suppression; report actual counts and build outcome |
| Retention | Cold reload, valid session, manual zero, retained archives, capacity failure, account/source switch | Never clear all storage or silently evict old accounts |
| UI | Same synthetic data/viewports, real navigation, labels, edit target and hero frame | Source geometry is not complete native pixel acceptance |
| Account/device | Optional WeChat bind and Tesla authorization on separately approved real accounts | No password/token collection; explicit logout/revocation must remain effective |
| Release | Separate approval for upload, publish and production deployment | No upload/release/deploy from this source handoff |

Remaining engineering work includes full encrypted-library backup/restore, all-route durable navigation state and all-screen native App/Mini parity. Summary exports and a passing module-reload test do not prove complete disaster recovery. Genuine Fleet two-user isolation, natural drive/charge/TPMS/notifications, real WeChat configuration, signed phone installation and production backup/rollback stay separate acceptance gates. No production history deletion, historical SOC/TPMS writes, live bridge upgrade, vehicle wakeup, phone data change or credentials change occurred here.
