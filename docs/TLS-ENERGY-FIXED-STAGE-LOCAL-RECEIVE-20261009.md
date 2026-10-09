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
  backfill, vehicle wake and manufactured trips remain separately gated.
  Jovi has authorized a future main merge **only after** the real
  acceptance/qualification conditions; those conditions are not yet met,
  and no main merge is performed in this source stage.

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

## Full-stage incremental local acceptance: source697 → final immutable SHA

The source697 candidate failed Android on the outdated literal-source
`SyncRepositoryApiEvidenceRedTest`; the prior 741/741 test totals and
Android-summary job are NOT passes. Its replacement executes the
actual source summary serializer plus same-ID DAO transaction executor
in isolation. Independently verify this new test and
`RawHistoryEvidencePersistenceTest` with sidecar coverage:
original raw8 and unknown opaque JSON properties recover byte-for-byte
from `HistorySummaryEvidenceCodec.sourceJson(apiEvidence)`,
the new 1-kWh example is a labeled *estimated drive-power integral*,
not a raw Fleet receipt, and charged battery AC/DC counters remain
physically separate. Confirm displayed unknown for raw-only old Fleet8,
valid zero and signed regenerative negative, car namespace isolation,
sidecar retention across weak repeat sync, and explicit unknown/wrong
source/window invalidation. The Android Debug and Release tests must
both compile and pass, including R8/lint; don't change assertions merely
to make them green.

The protected listener local ss classifier tests all six ports
(including 18080/18090) but does NOT expand public socket probing beyond
the original four allowed ports. Privileged Nginx fixes are synthetic
CI only and require independent Jovi approval for runtime use.
The production bb09 fractional-boundary serializer and old phone8625
stay unchanged until separately approved deployments. Keep previous
APK/signature, no uninstall/clear data, no network/DNS/proxy/VPN/CA
changes. Return one consolidated exact-SHA local build/sign/install,
UTC+China timestamped sanitized device/API/Room/UI receipt, with
natural and human gates separate.

The exact final SHA must additionally pass the malformed/future local
receipt tests: no invalid version, truncated envelope or unknown
`apiEvidence` may promote a previously numeric old Room placeholder
to battery kWh. Check the
`EnergyPersistenceRoundTripTest.detailEnergyKeepsOriginalRawAndSeparatelyPublishesQualifiedMeasurement`
case: original source value is preserved, a later signed regenerative
detail can be displayed only from separately qualified sidecar, and
the typed detail value is recoverable without falsely rewriting
the original source scalar. No Room migration or user-data reset.

## Full-stage 4bb independent behavior failure and new local acceptance

Before this new fixed SHA, the independent local source4bb run
successfully built and signed the release in 11m3s with Debug/Release
748 tests/0 failures (Release 8 skip), lint0errors/R8PASS. However,
the actual compiled standalone
`tools/energy-stage/independent-display-replay/DisplayProjectionReplay.java`
on evidence commit `d11973542c3944aa68ee7237d4e7a7c3b8689664`
failed: original 1 km receipt and new 2 km/SOC80→70/1-kWh
qualified detail decoded as 1 km/unknown/1000 Wh/km offline,
then same-ID Room merge rolled back current metadata. This is
**CHANGES_REQUIRED** for 4bb despite green CI. That signed 4bb APK
was NOT installed. Existing phone installed 8625/build46 retained
all user data/login/cache/history.

Receive ONLY the final new immutable source SHA/tree/parent in
the final PR17 handoff comment. In addition to the previous test
matrix, run the new `RawHistoryEvidencePersistenceTest` current
detail projection and `UnifiedHistoryDiscoveryRecoveryTest`
real `load()` current metadata/energy regressions. Independently
compile and execute the exact standalone Java replay against
**new compiled classes and generated Moshi adapters**; it must report
current 2 km, SOC 80→70, 500 Wh/km at direct/offline/next same-ID
merge with original raw receipt bytes unchanged. Report its actual
compile/run exit codes and fixture SHA as private sanitized evidence.
If any projection, release, lint or R8 check fails, do not sign/install.

Repeat for charge address, SOC including valid observed zero,
odometer/lat/long, physically qualifying battery-side charge
energy and AC counter separation, weak/unknown receipt preservation,
two distinct car namespaces, wrong-source and wrong-window
transplants. Never widen the whole-window threshold, manufacture
energy from SOC or recreate already-lost unknown raw fields.
Local Codex independently tests/builds/signs/installs only after
all evidence is PASS; no business implementation in Owner's dirty
tree. Main merge is authorized only after actual acceptance—not
implicitly on any source green result. Production bb09, Nginx,
bridge, DB, network, trust, old six SOC/7113 TPMS writes and phone
Wi-Fi/cellular choice all remain unchanged unless individually
approved under the documented exact reversible gates.

### Snapshot corruption and compatibility gate

Before local signed install, verify `raw_json` itself decodes to the
same ID/source as the current detail snapshot; copying a valid
local sidecar around a corrupt/mismatched raw record must yield
unknown energy. Re-run the negative forged-wrapper test after
independent compilation and repeat real-class Java behavior replay.
The restored projection must reproduce current distance/SOC/address/
speed and 500 Wh/km with original opaque bytes untouched.

Retain the existing signed APK and original app data as rollback
evidence; test in-place rollback **semantics** privately because the
older 8625 code predates the extended local detail envelope and
may not display new metadata after rollback. Do not uninstall,
clear cache/session, overwrite account/source namespace or
perform a destructive database rollback. A source-code-only
rollback is not evidence that all older cache consumers can read
the new versioned field. Report an explicit compatibility gate
if the old APK cannot safely read the new cache. No new production
API/DB/bridge/Nginx/network/Tesla operations are authorized.
