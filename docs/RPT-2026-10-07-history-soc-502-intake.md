# 2026-10-07 history, nullable SOC and 502 intake

Source verified locally: e372445c404c8f22a84ddbd37ff33741c014f04a, on codex/history-resource-stage2-20261002 / Draft PR12. Owner main remains f0dcd447 with its original dirty files preserved; isolated local branch was fast-forwarded, never reset.

## Actual observations (not live claims)

- At 2026-10-07 04:04-04:08 UTC (12:04-12:08 China), the archive source and scoped cloud each held 371 completed drives. The six completed China-October6 drives matched cloud rows, start/end timestamps and route sample counts. October7 had zero source/cloud drives; the user corrected the complaint date to October6.
- The user confirmed the latest October6 entry is visible on the existing phone. This is human display evidence, not a receipt proving a fresh authenticated network request; no current safe diagnostic response receipt was obtained.
- These six pre-upgrade drives have source SOC but no cloud SOC. A separate six-record / 25,114-point SOC-only proposal has private backups, precision-preserving candidates and an explicit exclusion of the original two repaired records. Owner approval is pending; no production SQL was executed. Real duplicate timestamps were checked: the only duplicate source group has identical SOC values, so no ambiguous SOC assignment was accepted.
- At 04:30 UTC, the latest three completed charge records matched source/cloud energy and SOC presence. Source cost was absent, so unknown cost is not a sync failure. Native charge-point JSON and archive route JSON use different case conventions; counts inspect both SOC aliases.
- Drive temperature exists at the source but is not represented in the current archive point contract. Direct total drive energy is intentionally not supplied by the bridge query. Both remain unknown; no zero or inferred value was written as measured data.
- Bridge inspection at 04:04 UTC confirmed binary e92d5da375d976360a8a340f1ab0d7161de5dac52dffe6f303eddc549c72a410, 14,943,359 bytes, owner0:0/mode0755, running, restart0 and current OOMfalse. Cursor bytes were unchanged during queries. No repeated upgrade occurred. Writable-layer persistence remains incomplete, and no post-upgrade natural drive SOC acceptance is claimed.
- At 04:10 UTC, the production API was running/healthy, restart_count1, started at 2026-10-06 16:22:05 UTC (October7 00:22:05 China). Public/loopback health/ready200 and unauthenticated history401. Nginx error/access files returned PermissionError under the existing role; no escalation was attempted. Window API logs contained no qualifying OOM/panic/upstream error. The earlier 502 and restart remain correlated in time only; root cause is not proven.
- Public health at 04:30 UTC reported build archive-soc-781c4025-tree-3f774ce1682b. No service redeploy or production data update happened in this intake.

## Remote source repair and local acceptance

Remote6282c67c restored VM-approved unknown-distance/0.3km cards but initially widened parking inputs. Local review returned CHANGES_REQUIRED. Remotee372445c separated visible cards from the prior known-distance>=0.5 parking qualification, retaining original parking adjacency/timing and using indices rather than identity-based matching.

Final local evidence at e372445c:
- Debug670 tests, zero failures/errors/skips.
- Release670 tests, zero failures/errors, 8 skipped.
- Four real history-builder regressions PASS, including unknown/short visible cards producing no new parked interval and qualified drives retaining the old parked segment.
- Release lint0 errors, 241 warnings, 7 information.
- Release/R8 build PASS; generated BuildConfig GIT_SHA=e372445, cloud login enabled, mock login false.
- Unsigned APK SHA256 E47BDF824034269FED8786DB17EDB386E026EEF15E3025796EF0A17AC12AF483. This artifact is not a signed/installed delivery.
- GitHub run37571622069: verify and android-debug jobs completed success at the source SHA, including Go/PG16/Web, parked scope/commit-visibility/race and Android gates.
- Independent synthetic PG16 six-SOC generator tests12/12 PASS, including exact Decimal precision, full-row CAS, retry, restore, stale identity/binding, partial transaction rollback and hostile table protections. Synthetic timings are not production performance acceptance. Production SQL execution remains unauthorized/pending.

Phone remains original com.matelink2.1.24/build43, original firstInstallTime2026-08-31; no new APK installed. Whole parked release still requires production compatibility and real-phone qualification. No main merge, vehicle wake, manufactured trip, identity/cache bypass, history deletion or TTL change.

## Next

Remote review this exact source and local evidence. Six-record SOC restoration requires the separate scoped approval and final fresh binding/row-hash freeze before any execution. Obtain a legitimate redacted proxy/exit-reason receipt for the 502 window; preserve pending authenticated fresh-request and natural post-upgrade SOC evidence. Evaluate bridge image persistence and whole parked release independently.
