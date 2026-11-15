# App review implementation delivery — 2026-11-03

Repository: `Jovifei/tesla-master-mimo` only. Branch: `codex/app-foundation-ui-integrate-20261025`, Draft PR #27. Original review baseline: `10e501541f33f8856b787b24833e14677a02792d`. Candidate version: **2.1.65 / build 84**, unchanged application ID `com.matelink`.

## Wired source, not isolated helpers

- `CostViewModel` and the real Compose summary publish qualified observed-part estimates independently of confirmed bills and complete-session estimates. Partial energy never enters whole-session energy or unit-price denominators. Confirmed/manual zero remains zero. The open cost page observes local fee changes without another authentication or unrelated history download.
- `UnifiedHistoryRepository` and `AnalysisHistoryRepository` expose independent drive and charge reads, including offline-first reads. Actual drive/efficiency/range/battery consumers do not depend on charge endpoints; charge/cost consumers do not depend on drive endpoints. Combined analytics remain supported. Old raw receipts and scope/epoch/cancellation fences are retained.
- An empty or partial successful provider response does not certify retained local records as freshly observed. Verified opaque-cache to UID discovery can publish for the same stable local car; a rebound UID cannot replace another car's private projection.
- Battery health/status publishes before history finishes. Annual aggregates are reread transactionally after the awaited refresh. Tapping the selected top-level tab does not rebuild that route; changing the active car still uses fresh arguments. Parked titles and partial-estimate explanations are localized.
- `LatestHistoryLoad` records the new lazy job before starting it. Immediate/reentrant publication cannot overwrite ownership of the newest request and prevent cancellation. The regression exercises the actual loader, not a duplicate implementation.

## Corrective source and genuine evidence

The remote corrective runner normally pushed source commit `dcb0773de0a1a12203147c20f8e8f9af99f4a7d1`, tree `ddba6d0999766f94a7f21b3be43a30e866409ee0`, parent `efc2b711b2c280dfd49c4a0c4cbf23eda57c0676`. Actions run `41552854536`, job `125644472759`, completed successfully, including real Debug and Release consumer regressions and AndroidTest APK assembly before the source push.

Downloaded artifact `13193648117` (`app-fullgate-correction`) has SHA-256 `5304f60dffd1afc6faab5d2fd0ab8352c4e7d52b003b5d79219c7e778b1c576c`. Independent parsing of its actual XML found **63 Debug and 63 Release tests, zero failures/errors/skips in each**. This is focused corrective evidence, not the final full-suite acceptance.

Earlier full-gate failures remain diagnostic evidence. Independent history reads required tests to run their slow charge consumer explicitly instead of assuming the drive page still calls charges. Existing address, Room, isolation, energy and cancellation assertions remain. The calendar expansion test now waits for load-more completion and requires both actual in-window records, count two and exact amount 5.70. No test was suppressed to hide a failure.

The commit containing this document must itself pass the full repository workflow, fast Android regression and isolated API35 instrumentation. Derive its SHA/tree/parent with `git rev-parse HEAD HEAD^{tree} HEAD^`; the final GitHub receipt records run/job/artifact identifiers after those workflows finish. Do not substitute the focused run above, an older green SHA, or the prior installed APK. Classify XML variants by the `testDebugUnitTest`/`testReleaseUnitTest` DIRECTORY, not a class filename containing the word Release.

## Independent local receive and rollback

Follow `docs/LOCAL-RECEIVE-APP-MINI-20261103.md`, App first and Mini second, using the two full immutable SHAs from the final receipt. Preserve the original E-drive worktree, all Owner dirty/untracked files, phone data, settings, sessions and backups. Local Codex verifies/tests/builds; it does not implement business fixes or copy one branch's backend over the other.

| Gate | Required evidence | Failure/rollback rule |
| --- | --- | --- |
| Source | Exact SHA/tree/parent, ancestry and incremental diff | Stop on identity mismatch; no reset/clean/force push |
| JVM/build | Real Debug/Release XML, both lint, R8, APK and AndroidTest build | Report failures with sanitized traces; never remove assertions |
| Emulator | Real isolated Room/Compose, partial/zero cost and parked screens | Blank images or wrong units fail; synthetic evidence only |
| Upgrade | Separately authorized same-package/same-certificate replace, old/new record counts, critical amounts, settings and valid session checks | No uninstall, data clearing or unverified database downgrade |
| Recovery | Independently reviewed compatible package and data backup/restore evidence | Source revert is not database rollback; preserve current data on failure |
| Production | Separate service baseline, backup and explicit rollout authorization | No production operation from this source-stage receipt |

## Scope comparison and remaining work

This stage closes the wired cost, independent reads/freshness, immediate publication, selected-tab, localized parked label, and reentrant cancellation changes above. It deliberately does **not** claim a complete encrypted-library backup/restore implementation, persistent state for every tab/route, all-screen App/Mini native pixel equivalence, or every architecture item in the earlier audit. Those are remaining engineering work, not merely missing screenshots.

Separate human/provider/device gates remain: genuine Tesla consent and vehicle confirmation, optional WeChat binding configuration and real account restoration, two real user/source instances, natural trip/charge/notification/TPMS observations, signed phone installation, and production backup/deployment/rollback review. Personal TeslaMate archive and synthetic CI do not establish Fleet first-event or natural-data acceptance. Normal upgrades do not promise survival after explicit logout, authorization revocation, uninstall, storage clearing or device loss. Summary export is not a full recovery backup.

No production service/schema/history, credentials, live bridge, vehicle or phone data was changed by this review stage. Main remains unmerged. Do not request or publish tokens, VINs, private addresses or trajectories.
