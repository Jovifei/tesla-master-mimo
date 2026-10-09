# Immutable-source local receipt — full TLS, energy and history stage (2026-10-09)

**For original local Codex only.** This is not a new project/task, a
code-implementation assignment, a network experiment authorization, an
API rollout instruction or proof of product acceptance. Keep the existing
chat and Owner worktree. The exact fixed HEAD, tree, parent, CI run, job
and artifact IDs are in the latest **completed** PR17 full-stage comment
on [Jovifei/tesla-master-mimo PR17](https://github.com/Jovifei/tesla-master-mimo/pull/17).
Reject a moving branch, an older green run or a mid-stage SHA. Never
publish credentials, VIN, vehicle/account IDs, private route, TLS subjects
or raw phone trace in the evidence return.

## Original installation and current gates

- Existing installed Android: independent same-signer `8625d28e`,
  build46/2.1.27, user data, firstInstallTime, login and cache retained.
  Its former Debug719/Release719/R8 and actual install receipt are
  historical evidence for **that** SHA only.
- API production: `bb09fac04d11796ce676555dad094776cd1ef0ce`
  still running; no API binary/DB/bridge/schema deployment was approved.
- This candidate changes Android energy qualification and Go API
  fractional time serialization, plus unexecuted TLS deployment
  tools/tests. Do not accept an APK simply because it is build46.
- Prior natural 3100-point Oct7 source window and compiled replay
  proved .417-second boundary truncation; this is independent of
  unresolved phone TLS peer mismatch. Old production still truncates
  until an authorized API source rollout. Source tests do not prove
  live provider or a successful authenticated phone history response.
- Phone temporary Wi-Fi→cellular→restore permission unanswered.
  No network/proxy/VPN/DNS/TTL/CA/trust changes. TLS log access denial
  is a hard boundary, no bypass.
- Old 6 SOC / 7113 TPMS writes, production DDL, schema, bridge,
  backfill, vehicle wake, manufactured trip and main merge NOT authorized.

## Fixed-SHA local verification / artifact matrix

| Stage | Allowed check | Required evidence |
| --- | --- | --- |
| Source | Fresh **separate clean worktree** at exact SHA, not dirty main; `git rev-parse HEAD HEAD^{tree} HEAD^`, `git diff --exit-code` | Exact full SHA/tree/parent, active code diff against phone14ca, installed8625 and APIbb09, plus source tree equivalence |
| TLS tools | `bash tools/energy-stage/test-tls-deployment-policy.sh`; `python3 -m unittest discover -s tools/energy-stage -p 'test_*.py' -v` | Actual positive/negative counts, no failed/skipped checks; only offline fixture CA and isolated mock Nginx, no production access |
| TLS vantage | Optional, two certificate-validated unauthenticated public API health calls; no `-k`, token or trust modifications | Host/vantage/UTC+China observation, two peer-consistency outcomes and boolean failure category **only**; not phone history PASS |
| Go API | Isolated **disposable PostgreSQL 16** DSN only; Go all-package test, race/resource target, vet, mod verify and build | Actual fixed-SHA test counts, fractional serializer window + counters, PG tenant/vehicle/source isolation, restart, budget and cancellation. Never run tests against production DB because startup may write schema |
| Android | Debug and Release unit tests incl. `DeployedBb09ConsumerCompatibilityTest`, `HistoryBoundaryPrecisionConsumerTest`, `EnergyContractWiringTest`; lint Debug/Release, assemble Debug/Release with JDK17/SDK35, R8 | Exact per-variant pass/fail/skip, lint errors, signed candidate hashes, no compiler skip or flaky green claim |
| Phone | After independent successful fixed-SHA build and same original signature, authorized `adb install -r` with backup APK maintained; no uninstall/clear app data | Release non-debuggable, cert match, installed APK SHA, unchanged firstInstallTime/session/cache/history, bounded launch FATAL/ANR and source consistency |
| Natural acceptance | Existing normal Tesla-authorized read (without vehicle wake), only within already-approved operations | Distinguish source read, cloud API, authenticated history-context/list/detail, Room/UI fresh energy, parking and charge values, legitimate 0/signed recovery, coverage/units/source labels/cost; cache != fresh. UTC+China timestamps. If TLS fails, give typed category, NOT auth PASS |
| Human gates | User consent/permission/real Fleet events and 2-user isolation plus persistent notifications/TPMS trends, network experiment or API rollout approvals | Independently observed data or explicit Jovi decision; never replace missing evidence with historical TeslaMate archive |

The exact new signed APK must be generated in a clean checkout with the
already-approved signing identity and original build guards. Do not send
keystore files, secrets or raw cert metadata to ChatGPT/GitHub. If all
code CI succeeds, but device authenticated history fails, keep Draft
PR17 and report **CHANGES_REQUIRED / ENVIRONMENT_GATE**, preserving
source-quality closure separately. Do not implement business code
locally to work around failing CI; send one consolidated typed failure
receipt back to the original execution chat for remote implementation.

## Direct comparison and rollback

Only the original installed signed APK and separately held backups are
the phone rollback candidate; preserve previous source and signing
identity. A failed updated installation must not trigger data clearing
or uninstall. For API, keep the deployed bb09 image/config, private
backups, status and resource baseline; **no automatic API upgrade**.
For Nginx, source-only `tls-nginx-transaction.sh` tests use a disposable
filesystem. Real `setup-root.sh`, certbot, Nginx and renew-hook changes
require a separately scoped review/backup/change window and rollback
authorization; even a public TLS PASS does not approve that rollout.

## Consolidated return to remote

Report exactly one packet: fixed full SHA/tree/parent, branch PR17
Draft status, CI run/job/artifact evidence checked, changed-source
matrix, local test/build/lint/sign/app install status with actual
pass/fail/skip, preservation receipts, true natural source/phone/API/UI
qualifier outcomes and explicit unresolved human/environment decisions.
Label source, synthetic CI, local device, historical TeslaMate,
production and natural Fleet evidence separately. No new microtasks or
unrequested background work.

## Additional mandatory same-ID evidence qualification (post-5f)

The previous 5f run Android result is **FAIL**, regardless of its
`android-summary` or backend green badges:
`HistoryRecoveryTest` had two NPE fixtures assuming an uncontracted
Fleet 8 kWh was a physically proven measurement. Remote fixes must
retain a synthetic qualified 8 kWh power-integral contract and an
independent uncontracted Fleet unknown test. Local testing must verify
the new `RawHistoryEvidencePersistenceTest` and both
`HistoryRecoveryTest` cases, on **the exact new final SHA**.

The persistence safeguard is as important as the display guard:
`apiEvidence` must retain historical raw JSON and the original scalar
(e.g. Fleet 8 kWh) through same-ID weak/offline read-merge-upsert,
while analytics/Room projection/UI remain `null` when unsupported.
A pre-contract numeric Room column does not prove a Fleet measurement.
Test exact byte retention for a weak same-ID update, verified source
and quality, no row-count change, two cars with the same numeric ID,
valid reported zero and signed recovery, unknown details and
charge-source preservation. Candidate source changes both foreground
history and background sync/detail paths; if any test fails,
**do not install**. No historical backfill/repair is authorized.

The TLS checks now also explicitly fail when existing active LE
post-issuance `nginx -t` fails, when any of four files cannot be
restored on rollback, or when a public-only private-port listener
exists without loopback presence. Only source-isolated fault-injection
tests are authorized; do not run deployment scripts against production.

Go Nano precision and parking boundaries are source-qualified ONLY.
Deployed API bb09 continues to truncate fractional boundaries and
requires a separately approved minimum Go-image rollout with private
existing image/config/DB backups and rollback, never implicit code-stage
approval. The old failed intermittent phone certificate peer and
the unanswered Wi-Fi→cellular→restore decision remain separate.

### Required source-receipt assertions before same-signer installation

Independently verify the entire new SHA after Android CI completes,
including `UnifiedHistoryDiscoveryRecoveryTest` raw offline load,
`RawHistoryEvidencePersistenceTest` exact same-ID DAO-transaction
algorithm, raw source-address preservation, card projection
(`toQualifiedHistoryMetrics`), uncontracted Fleet 8 unknown and
synthetic fully evidenced Fleet 8, valid measured 0 and signed negative.
Do not strip the new regression files to make builds pass.
`HistoryRecoveryTest` weak imported aliases must have **no inherited
strong `EnergyContract`**, and cross-source metric mismatch must
stay unavailable in list, detail, cache and statistics.

All upstream Go/PG and TLS CI is source-level; no new Android source is
installed until exact new final SHA Debug/Release/lint/R8 and existing
signing identity are independently qualified. Previous installed
`8625d28e` remains the rollback candidate without uninstall/reset.
Production bb09 remains old and still needs a separate backed-up,
idempotent, reversible API rollout decision for fractional boundaries.

**Additional local review evidence:** confirm the same-ID
history detail-enrichment checks preserve the raw source address
and original raw cost in `apiEvidence`, but leave the visible
address cleared when invalid and the unqualified cost scalar
null. The actual drives-card metric consumer must remain
source/energy-qualified even if old Room scalar columns are
non-null. A compile, source-only artifact or green
`android-summary` without a successful Android job is NOT PASS.
