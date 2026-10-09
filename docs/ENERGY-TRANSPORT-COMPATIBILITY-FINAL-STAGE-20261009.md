# 2026-10-09 TLS transport, deployed bb09 compatibility and remaining acceptance gates

Status: **GitHub source/deployment-tool qualification only**. Candidate branch
`codex/reliable-data-stage-20261007` / Draft PR17. The exact final immutable
commit, tree, parent and completed CI run are recorded in PR17's final stage
comment, not inferred from a moving branch or this document. This is NOT a
production change request, physical-device PASS or authenticated-history PASS.

## 1. Evidence tiers and observation timestamps

- Previous product source `8625d28ecfaec920d39d8b6a56afa50e9854ae7a`;
  Android build46/2.1.27 same-signer `install-r` retained user data and
  passed independent local Debug719, Release719 (8 skipped), lint/R8, signed
  APK match, bounded launch FATAL/ANR0; Actions `37825196099` all green.
  Source: versioned local return `codex/energy-device-final-evidence-20261009@3e1962d652d5ca1d6d5ecef203f881b7cb6c3d92`
  and PR17's 2026-10-08 19:18 UTC comment.
- On 2026-10-08 at ~18:59 UTC / 2026-10-09 02:59 China,
  the phone's unauthenticated validated health transiently returned HTTP200;
  19:03:58 UTC / 03:03:58 China real authenticated app
  `history_context` failed **before HTTP**, `category=tls`,
  `tls_cause=certificate_path_validation`. ~19:06–19:09 UTC the
  phone sent correct expected SNI but saw one *unexpected self-issued* peer
  not covering the API hostname; its DER size was 433 bytes. No identities,
  routes, raw subjects/SAN, exception or trace are published.
  Desktop/server TLS1.2/1.3 had the intended public chain; phone's
  expected public root exists. Public phone curl ALSO failed an independent
  HTTPS control. These observations prove inconsistent peers/paths; they
  do NOT locate the router/proxy/server, establish missing trust anchor, or
  justify disabling certificate verification.
- Scoped read-only natural-source observations at 19:02 UTC found a
  3100-point Oct7 drive and a later 1596-point drive with source power
  coverage and SOC observations. During TLS failure, phone values were
  CACHE only and energy was unavailable. Not an end-to-end
  source -> cloud -> authenticated API -> Room -> UI PASS.
- Deployed API **remains** `bb09fac04d11796ce676555dad094776cd1ef0ce`,
  bridge and database unchanged; Owner dirty `main@f0dcd44`
  and all existing phone history/login unchanged.

## 2. Source-route review and falsifiable hypotheses

| Component | Observed source/contract | What would discriminate a cause |
| --- | --- | --- |
| Nginx HTTPS vhosts | `deploy/nginx/jourvolt.conf.template`: three explicit names each on IPv4/IPv6 port443 and shared active SSL include; other independently installed default vhosts are possible. No committed TLS `stream` interception was identified. | Compare **effective** active vhost/SNI selection and certificate fingerprint only with permission to read loaded Nginx config. A checked-in template is not a running config. Test separate A/AAAA paths only within an approved bounded validated probe. |
| Deployment script | Prior `setup-root.sh` always rewrote active include to self-signed on EVERY invocation, then nginx reload BEFORE LE renewal. An operationally unsafe transient downgrade even when previously using LE. | Review authorized deployment record, before/after active include hashes and reload times; no proof this script ran during phone failure. Candidate repair retains validated LE and refuses downgrade or unknown active config. |
| Public verification | Prior `verify-public.sh` used `curl -sk`, reported selfsigned as WARN and printed issuer. Could yield misleading PASS. | New candidate removes `-k` and treats strict certificate/hostname failure as FAIL. Do not confuse TCP or HTTP status seen after insecure negotiation with validated HTTPS. |
| Android client | `TeslamateApiFactory` cloud-mode HTTPS, system-default OkHttp TLS trust/hostname checks, fixed configured hostname, no trust-all policy or active insecure-certificate override. Safe TLS category classifies Java exception **types**, not peer origin. | Repeat genuine authenticated read after independently obtaining environment permission. No change to TLS trust roots/CA pinning or private network settings to conceal the problem. |
| Phone path | Correct SNI and unexpected self-issued peer on 2026-10-08, contrasted with desktop/server official chain. | Separate IPv4/IPv6/default-host/proxy/inspection vs network-vantage mismatch via authorized config receipts or a Jovi-approved bounded Wi-Fi→cellular→restore experiment; **no permission received** at this writing. |
| Access logs | Normal Nginx access-log content read was denied. | A privileged operator could separately approve a strictly redacted, scoped read. Do not bypass permissions and do not request auth tokens/paths/VIN to match logs. |

These are candidate explanations, **not established root causes**. A self-signed
certificate of some size is insufficient to identify whose component supplied it.
Retain any off-repo effective routing and existing business vhosts untouched.

## 3. Source defects fixed and bounded verification

- `deploy/scripts/tls-include-policy.sh`: pure deterministic fail-closed
  policy for absent, selfsigned and active LE states; old LE may never
  fall back to selfsigned. Unknown managed include is rejected.
- `deploy/scripts/setup-root.sh`: before assigning the active include,
  calls `qualify-nginx-le.sh` for complete server chain, notBefore/
  notAfter, all three hostnames, current system CA and private/public
  key equality before selecting LE. Unknown active include or invalid
  previously active LE fails closed. `tls-nginx-transaction.sh` stages
  four named Nginx files with exact root-only backups, performs nginx-t,
  reload and atomic restore on any failure. `renew-qualified-reload.sh`
  gates certbot reload on verified chain/key and intended managed
  include; failure never selects placeholder. All are source candidates;
  running any deployment procedure remains a separate production gate.
- `deploy/scripts/verify-public.sh`: no insecure `curl -k`;
  all three TLS hostnames/chain/SNI checked; expected unauthenticated
  capabilities 401 is enforced as a hard gate (not WARN on 200/redirect);
  `PUBLIC_IP` must be a literal valid IPv4 value; bounded sockets run
  without shell interpolation; temporary response files are unique
  mode-0600 `mktemp` outputs rather than guessable /tmp paths.
- `tools/energy-stage/qualify-public-tls.py`: hardcoded approved
  unauthenticated public API `/healthz` only plus approved other-host
  TLS-only handshakes, normal system CA validation, hostname/SNI
  validation and DER leaf SHA-256 equality across at most
  **two** separate connections per named host, five-second bound each, optional
  independently verified public peer SHA-256 assertion. Closed output
  enum only; no response bodies, error strings, certificate details,
  authorization header, source path, vehicle operation, provider access,
  network change or insecure fallback. All printed PASS/FAIL applies to
  **that process and vantage only**.
- Offline tests: real localhost self-issued TLS rejection,
  incorrect expected leaf fails before HTTP, two-request limit,
  same peer acceptance, no hidden authorization, invalid-count/peer
  input refusal, strict context and deployment-policy guards.
  Real `tls-nginx-transaction.sh` is executed against disposable
  Nginx/systemctl/sudo replacements for apply/no-op/failed nginx-t/
  failed reload/symlink preservation. Real `verify-public.sh`
  is executed with sandboxed network mocks for 401, unexpected
  200/302/403/500/000, and injection-like IP rejection.
  Temporary OpenSSL test CA verifies positive trusted chain and
  negative incorrect hostname, missing chain, expired/not-yet-valid,
  untrusted root and mismatched key; no production cert or trust change.
  CI's optional live public vantage is a diagnostic not a fleet/device
  acceptance criterion. Offline tests are mandatory and fail the job.

### Commands — isolated/review-only, no production credentials

From a **clean independent checkout of the exact final SHA**, not the
dirty Owner tree:

```bash
git rev-parse HEAD 'HEAD^{tree}' HEAD^
git diff --exit-code
bash tools/energy-stage/test-tls-deployment-policy.sh
python3 -m unittest discover -s tools/energy-stage -p 'test_*.py' -v
# Optional explicitly bounded public health vantage, normal system TLS only:
python3 tools/energy-stage/qualify-public-tls.py --live --samples 2
# Only with independently verified public DER leaf fingerprint:
# python3 tools/energy-stage/qualify-public-tls.py --live --samples 2 --expected-peer-sha256 <approved-public-leaf-sha256>
```

On Windows local verification, use Git Bash/WSL solely for the Bash
policy tests, and a private Python environment for offline unit tests.
These commands never require Tesla authentication. If a public-vantage
check fails, the classified output does not reveal credentials or
identify the failed TLS hop. HTTP200 health proves no user history.

### Source-derived bb09 API compatibility (not natural provider evidence)

- `main.go`, `history_context.go`, `parked_history_bounds.go`
  source at deployed bb09 and candidate have identical route/identity
  semantics; `GET /api/matelink/v1/cars/{id}/history-context`
  is account/car persisted binding, not a Tesla wake/first Fleet event.
- bb09 `telemetry_http.go` uses `data.drives[]`, `data.charges[]`,
  `data.drive`, `data.charge`, and parked `data` wrappers; list
  summaries intentionally omit heavy route/charge details. `start_date`
  and `end_date` are RFC3339, nullable SOC remains nullable, observed
  `speed_max` is rounded to an integer, observed temperatures and
  `speed_avg` remain floating point. `energy_consumed_net` is
  unavailable for Fleet drives without driving-energy proof, not 0.
- Deployed bb09 does not yet publish the additive `energy_contract`.
  Candidate Android keeps the **legacy-compatibility** scalar path only
  for absent-contract historical TeslaMate archives or old untagged
  self-hosted history. Deployed bb09 `telemetry_mqtt`, `fleet_api`
  or unverified `local_import` scalars are **unknown even if numeric**:
  the old pre-contract Fleet machine can mix AC/DC counter origin and
  retain a stale earlier positive delta after reset. With a present contract, value
  requires version, source, unit, method, measurement location, exact
  window, quality and coverage; explicit unknown masks stale scalar.
  Reported zero and negative net drive energy stay values. Power
  integration is an **estimate** only for a fully qualified observed
  window, never a battery-input measurement. For inferred route-power
  integration, Fleet MQTT `telemetry_mqtt` point timestamps are
  **collector receipt instants** and now publish
  `time_basis=collector_received_at`; TeslaMate archive source-point
  timestamps retain `source_sample_time`. An untagged legacy route
  is `route_timestamp_unverified`, not fabricated Tesla sample-time.
  This is a provenance-label correction, not a new provider measurement.
- Charge battery input/DC counter, charger-side AC input, and stored
  change are separate. AC balance needs boundary-matched, observed AC
  mode and cannot be derived from an unverified/mixed counter.
  SOC alone gives no kWh. Existing legacy Room numeric placeholders
  must not be misrepresented as real zero; round-trip `apiEvidence`
  preserves unknown and valid zero. Parked boundary SOC does not
  qualify battery kWh. Unknown tariff estimate remains unknown.
- New `DeployedBb09ConsumerCompatibilityTest` uses **synthetic JSON
  shaped directly from bb09's source**, runs the actual Moshi response
  models -> Room summary codec -> analysis models and charge
  detail UI stats, plus old deployed numeric Fleet scalar rejection and
  explicit newer unknown-contract masking. No
  claim it exercised an authenticated production response.
- Existing Go isolated PG16 ingest/restart/energy resource budget and
  Android 719+ tests still apply. Running these tests at a candidate
  SHA does not change the actual deployed API binary.

## 4. Separately gated runtime environment investigation and rollback matrix

**Current authorization = source/isolated CI/read-only public health only.**
Do NOT run the following production repair steps by merely reading them.

| Operation | Safe scope before approval | Additional human/environment gate | Backup / idempotence / rollback |
| --- | --- | --- | --- |
| Public TLS probe | Two normal TLS-validated unauthenticated `/healthz` connections on approved host. No credentials, no custom CA or insecure mode. | None for bounded public vantage; do not run unbounded repeated probes. | Read-only; no rollback; compare only `PASS/FAIL`, approved public digest, UTC timestamps and vantage labels, never raw cert/log. |
| Inspect effective nginx/SNI routing | Review committed template and hashes. Read-only effective `nginx -T` requires an operator with existing authorized permissions; content must stay private; no raw output in PR/chat. | **Permission to access live effective config**, currently not granted. Original access log read denied; do not bypass. | Before/after SHA-256 of **redacted selected snippets only**; report public boolean facts without host-private details. |
| Reconfigure 443 certificate | **Not performed.** Never run the old setup script. Initial scope would be only the affected managed include and named vhost; no global default, no unrelated vhost/stream. | Separate explicit Jovi approval AFTER demonstrating active config/peer mismatch and a specific change plan. | Operator first records active include/config, key pair paths and the unmodified public-chain validity in private mode-0600 directory with SHA-256 manifest. Copy active managed include and vhost with metadata. Require `nginx -t` and a verified public handshake BEFORE reload; no effective replacement for unknown/invalid state. On failure atomically restore exact backups, `nginx -t`, reload, confirm public validated peer, retain receipts. Repeat must be no-op when already correct. No private key content in logs/chat. |
| Temporary phone Wi-Fi → cellular → restore | **Not performed**; device network remains unchanged. No alteration of CA, proxy, VPN, DNS, TTL, trust, or app data. | Exact permission from Jovi pending, with restoration acceptance. | Private before/after network-state check and restore original Wi-Fi; compare two bounded validated peer categories and normal authenticated read only as user authorizes; send only sanitized outcomes, timestamps UTC+China. |
| Production API/DB/bridge or old SOC/TPMS history write | No execution; immutable source and isolated PG CI only. | Completely separate gate: production change approval, reviewed backups, rollback, exact scope. Old six SOC and 7113 TPMS writes remain **unapproved**. | No production DDL/deploy/backfill, no DB cleanup; maintain deployed bb09 and bridge. |
| Device signed build/install | Previous signed device PASS applies **only** to installed 8625. The new candidate changes Android energy source qualification: exact new SHA is **not yet installed**. | Original authorized local Codex verifies full SHA, Debug/Release/lint/R8, same signer and then performs separately controlled in-place install-r without uninstall or data reset, returning one consolidated receipt; any actual network experiment still needs Jovi's separate decision. | Retain previous signed APK, account/history/cache, firstInstallTime, signing certificate; compare hashes, preserve rollback APK, ensure bounded launch, authenticate afresh only under usual granted session and return category-only failures. |

Never upload raw `nginx -T`, cert subjects/SAN, phone traces, tokens,
VIN, customer or location identifiers. Do not diagnose self-issued peer
by changing the global trust store, accepting insecure HTTP, installing
a user CA or applying a guessed workaround.

## 5. Genuine remaining acceptance matrix and local Codex receive packet

The only handoff bridge is `Jovifei/tesla-master-mimo`, Draft PR17,
branch `codex/reliable-data-stage-20261007`. The final PR comment
supplies **one full immutable HEAD/tree/parent and same-SHA Actions
run/jobs/artifacts**. Local Codex receives *that exact SHA*, not a
generic request to modify implementation files:

1. Independent separate clean checkout of immutable HEAD, verify tree/
   parent, all file hashes, source diff against phone 14ca/installed
   source 8625 and API bb09; preserve dirty Owner `main`. Rerun
   offline TLS, Go isolated PG16/race/vet/build, Android Debug/Release,
   lint/R8 as appropriate. Return one **consolidated** evidence packet
   with exact SHA, scoped PASS/FAIL and failure excerpts only. No local
   business-code repairs. The energy-source gate changes runtime; local Codex should requalify
   Debug/Release and do authorized same-signer in-place installation,
   preserve existing app history/login/cache and return a consolidated
   device receipt. Network/trust changes still require separate consent.
2. Following **separate Jovi permission** for any phone network
   diagnostic, record current network state privately, perform only the
   authorized bounded comparison and restore; retry authentic existing
   `history_context`, list and detail, charge and parking reads without
   vehicle wake. Compare source/actual API/Room/UI values with evidence
   for source, sample times, coverage, kWh/100km, SOC, speed,
   temperature and cost; unavailable remains null, 0 remains 0,
   regen negative remains signed. Distinguish cached warning from a fresh
   authenticated response. Report UTC and China time, sanitized codes.
3. Human Tesla login/consent/vehicle confirmation, two-user account/
   vehicle/source isolation, first genuinely natural Fleet drive/charge,
   30-day real observed-time TPMS/trends and meaningful alert change,
   durable natural notifications (late IDs, first import,
   permission/denial/recovery), long-period resource stability and
   any old parking-energy absence **remain separate natural/human
   gates**. TeslaMate personal archive is never Fleet first-event proof.
4. Keep Draft PR17 and `main` unchanged until actual human/natural
   acceptance, approved integration and backup/rollback review.
   CI PASS, signed install, health200, archive samples or cached SOC
   are not substitutes for authenticated natural UI acceptance.

### Fixed-stage closure rule
GitHub code/test/docs work can be marked **source-qualified** only after
final same-SHA Actions all required jobs succeed. The unresolved phone
transport **cannot** be marked PASS until an independently observed
valid authenticated request through the actual phone path occurs or
a concrete separately accepted environment gate is established. No
production or network repair is authorized by this source-stage closure.

## Appendix A — separately approved Nginx config/chain change and exact rollback

**Not executed. No production permission is implied.** The phone's
old self-issued 433-byte TLS peer is not independently traced to the
Nginx server, proxy or a specific script. Do NOT run setup-root merely
to troubleshoot an unknown peer. First obtain Jovi's exact scoped
approval for read-only effective config or a reviewed change window.
Review the current active vhost/SNI including IPv4/IPv6/default
selection and keep other-project vhosts untouched.

The candidate production-tool scope is only:
`/etc/nginx/conf.d/jourvolt.conf`,
`jourvolt-ssl.inc`,
`jourvolt-ssl.le.inc`,
`jourvolt-ssl.selfsigned.inc` and a separately qualified
certbot deploy hook. Neither the hostname, system CA, proxy, VPN,
network/DNS/TTL, provider credential nor product API/schema is changed.

Approved operator preflight (never execute as a silent part of chat):
1. Verify the current effective config and existing hashes privately,
   including active include and prior approved nginx master/running
   state; avoid printing raw `nginx -T` or certificates into CI/GitHub.
   Snapshot four files with metadata in a root-only (0700) directory.
2. `bash deploy/scripts/qualify-nginx-le.sh
   /etc/letsencrypt/live/jourvolt/fullchain.pem
   /etc/letsencrypt/live/jourvolt/privkey.pem
   teslalink.joviluma.com api.teslalink.joviluma.com
   auth.teslalink.joviluma.com` only succeeds for a trusted fullchain,
   valid notBefore/notAfter, all approved hostnames and matching key.
   Certbot `live` files may legitimately be symlinks; trust checks
   validate their contents. Self-issued/untrusted/wrong-SAN/wrong-key
   peer must be rejected; no `-k` or new CA is allowed.
3. After stage config diff and owner-specific review, the actual
   `tls-nginx-transaction.sh` installs those four managed files
   atomically from approved, rendered sources. It first validates
   baseline `nginx -t`, creates metadata-preserving backups, installs,
   checks `nginx -t`, reloads, and auto-restores **exact original
   on-disk files** and tries old-config reload on any command failure.
   A matching existing configuration returns NOOP without reload.
   Unknown active include, invalid prior LE, or unexpected extra
   directive stops instead of downgrading the certificate.
4. After a successful, **approved** reload, compare two normal
   certificate-validated no-credential public health checks and
   approved host/SNI peer results from the relevant vantage.
   A public Runner PASS never proves Android TLS/authenticated history.
   If postreload health fails, operator must revert to the exact
   retained root-only backup, `nginx -t`, reload, verify accepted
   peer/health again, retain a private hash matrix and report only
   sanitized statuses. A failed rollback is an explicit STOP/operator
   gate; do not delete backup or invent PASS.
5. Certbot rollout is separately gated: `renew-qualified-reload.sh`
   is a deploy hook candidate which checks the managed active LE
   include and complete certificate trust/key before reload. This
   does not restore Certbot's archive/live symlink history. A failed
   Certbot issuance/renewal requires separately reviewed certificate
   archive backup and operator decision; **never** silently repoint
   live symlinks or use a self-signed fallback as public success.

A full implementation/example path is in `deploy/scripts/setup-root.sh`
and `deploy/scripts/tls-nginx-transaction.sh`. Source CI tests execute
the actual transaction and public-check scripts only in a disposable
mock namespace, including forced validation and reload failures. No
effective production Nginx config or phone network was modified.

## Appendix B — real natural fractional boundary defect and gated minimal API update

The independently supplied [local precision report](https://github.com/Jovifei/tesla-master-mimo/blob/92bad8b25e458b95a41c3ac06566707464c7c87a/docs/ENERGY-NATURAL-API-PRECISION-CHANGES-REQUIRED-20261009.md)
is on the separate evidence branch, not necessarily in this PR: natural
sample observation on **2026-10-09T01:11:53 UTC / 09:11:53 China**
found 3100 source points covering the fractional start/end window
with no missing power, conflicting replay or >30s gap. The old
deployed bb09 `historySessionMap` serializes session start/end using
`time.RFC3339`, discarding fractional seconds, while archive route
point `date` retains its fractions. Independent compiled-8625 JVM
replay demonstrated a synthetic 120-second whole window COMPLETE with
matching .417 fractions, versus old truncated boundaries leaving
0.417 seconds uncovered and rejecting power integration. This does not
establish a successful **authenticated phone API payload**.

The candidate makes the **minimum Go serialization repair** in
`telemetry_service.go`, `energy_history_contract.go` and adjacent-archive
`parked_history_bounds.go`: use
`time.RFC3339Nano` for session start/end, telemetry route/charge
sample timestamps and energy-contract observed/window endpoints.
Adjacent parking endpoint observations retain fractional UTC precision,
but SOC-only parking continues to publish null kWh/averageW; no
unsupported stored-energy attribution is added. Whole-second values
retain their old string spelling; JSON field
names, types, wrapper, provider source, account/car/vehicle scope,
charges, cost, energy-metric qualifications and database schema are
unchanged. It does NOT round sample dates or relax the Android exact
whole-window / 30s missing-interval / null / conflicting-replay checks.
Source-time vs collector-receipt labels remain distinct.

Cross-boundary regressions: actual Go `historySessionMap` ->
`encoding/json` tests for fractional observed drive, parked archive
boundaries and charge counter endpoints; actual synthetic Go-shaped JSON -> Android Moshi
`DriveDetailResponse` -> `DriveEnergyResolver` ->
Room summary evidence -> drive detail presentation verifies a complete
fractional window. Old rounded start/end must still yield
`incomplete_power_coverage` and **unknown whole-window energy**.
Matched valid 0 and negative source power remain genuine signed
estimated intervals; missing power remains unknown.
`DeployedBb09ConsumerCompatibilityTest` also covers Fleet positive
and zero unqualified legacy charge scalar rejection, separate from
personal archive compatibility. Production bb09 cannot be considered
repaired by this commit; its old fractional-boundary response remains
ineligible until separately approved API deployment.

### Minimal prospective production API gate (not authorized here)

- **Scope**: only qualified Go API image built from immutable candidate
  source, no Android baseline overwrite, no DB DDL, no migration,
  bridge change, historical SOC/TPMS write, Tesla authorization or
  fleet action. All API business handler compatibility and migrations
  must be independently source-reviewed against deployed
  `bb09fac04d11796ce676555dad094776cd1ef0ce`.
- **Preconditions**: Jovi's explicit, separate API rollout approval,
  current deployed exact image digest/tag/source SHA and health/readiness
  receipt; encrypted/least-privilege runtime config/backups privately
  verified, PostgreSQL snapshot/checksum and original container config
  preserved even though this intended change is serialization-only.
  Review `docker compose config` for scope without dumping secrets.
- **Idempotence/rollback**: stage new image separately, keep original
  immutable image and DB backup; do not alter bridge. Run isolated Go
  PG16, race, API contract plus negative/no-Fleet-wake route tests,
  then bounded, authorized canary. On timeout, metric mismatch, login
  failure, resource regression or rollback decision, restore the exact
  prior API image/config; verify existing health/readiness and client
  history warning. Do not restore/write DB from backup unless a
  separately approved and justified data rollback is required.
  Retain hashes, immutable SHA, named UTC/China timestamps, new/old
  response schema and privacy-safe outcome flags. **No production
  deployment has been performed in this source stage.**
- **Acceptance**: a genuine already-authorized API→phone authenticated
  detail read with the exact fractional start/end from the qualified
  source, Room persist and UI estimated-energy label, plus
  stable all-unknown and valid-zero behaviors. Fleet first events,
  second real user and notification/TPMS gates remain independent.

## Appendix C — code/test plan-to-completion comparison

| Target | Candidate source-level closure | Actual environment acceptance |
| --- | --- | --- |
| TLS peer and SNI | CA/hostname trust, allowlisted peer comparison, bounded sanitized public probes | Phone still requires the authorized comparison; prior unexpected peer remains unattributed |
| Safe Nginx config | Four-file transactional apply/rollback, active-LE no-downgrade, cert/key/window/CA proof, renewal validation | Not installed, no production Nginx access or change approval |
| Public check | Unauthenticated 401 is enforced, IPv4 literal and safe tempfile guard, no `curl -k` | Actual hostname/public trust snapshots may vary by vantage |
| Deployed bb09 energy | Legacy Fleet unproven scalar remains unavailable; explicit contract requires window and source. Personal TeslaMate separate | bb09 still deployed without additive contract and with boundary truncation |
| Fractional precision | Go RFC3339Nano serialization + Go/JSON and Moshi/Room/UI regressions | New API image not deployed; actual natural API→phone energy remains unaccepted |
| Source/integration | Go/PG16, race, Web, Android Debug/Release/lint/R8 and source audits must all pass **on the final SHA** | Same-signer physical install and true natural source→UI remain separate gates |
| Preserved state | No writes to Owner/main, prod API/DB/bridge, old 6 SOC/7113 TPMS, app data or network | Jovi's phone network experiment permission remains unanswered |

### Revised local Codex receive instructions

Use the **full fixed immutable HEAD, tree and parent recorded in the
last PR17 stage-delivery comment**, never `main` or a moving branch.
Independent clean checkout, exact CI logs/artifact verification, Go
PG16/race and targeted fractional boundary tests, Android Debug/Release
unit/lint/R8 and source compatibility/precision tests, no implementation
in the dirty Owner tree. Because candidate Android runtime source is
newer than installed 8625, perform only the previously approved
same-signer safe `adb install -r` flow **after** independent successful
build/review; preserve rollback APK, original firstInstallTime,
login/cache/history and source identity. Return one consolidated packet
with actual result counts and private-only signature/APK hash evidence.
Do not perform the Wi-Fi→cellular experiment, trust/DNS/proxy/VPN change,
production API/bridge/DDL or natural driving/charging on our behalf.
Authentic list/detail/parking and energy acceptance requires actual
successful authorized responses and correct vehicle/source comparison.

### Supplemental CI safety clarifications

The final source recognizes preexisting legacy **comment-free** active
LE/self-signed directives by normalized exact comparison with the
managed fragment; any extra directive fails. Certbot's normal
`live/` symlinks are accepted only after validating their real
certificate and key contents. No bearer token is sent by
`verify-public.sh`, including when an unrelated environment happens
to contain a session token; authentication tests require the separately
authorized device/API path. The optional public 200 health is always
labeled `runner_vantage_only`, never accepted as Fleet or phone
proof. All synthetic TLS certificate and mock network results are
isolated-test evidence, not server or natural-data acceptance.

## Appendix D — same-ID original history evidence recoverability repair

Independent local return
`codex/tls-5f-local-validation-20261009@679c54c94d15dc41e1c121d5614819f4cfb8ade0`
found **actual Actions Android FAILURE** at fixed `5f5a197d`:
HistoryRecoveryTest line71/117 used an allegedly strong 8-kWh Fleet
scalar lacking `EnergyContract`; the Android qualification correctly
rejects it. Both variants ran 730 tests with 2 failures; no new signed
install was completed. This is distinct from the proven raw-cache
persistence defect below. The old `8625d28e` signed APK remains the
installed version.

### Data contract before/after and non-destructive scope

**Prior source risk (not asserted as a production erase):**
`HistorySummaryMapper.toAnalysisDriveData()` masked unproved Fleet
8-kWh values to `null`, but foreground history merged and persisted
that projected DTO; `DriveSummaryDao.upsertPreservingEvidence()` used
`mergeStoredDrive()` which decoded/projected AGAIN, potentially
re-encoding `apiEvidence` with `null`, destroying the original
recoverable raw scalar and original JSON during same-ID upserts.
Background `toSyncSummary` and detail enrichment also encoded
display projections as raw evidence. The same pattern could affect
unqualified charge values.

**Candidate repair:**
- `toRawAnalysisDriveData` and `toRawAnalysisChargeData` are explicitly
  *for persistence merging only*: decode the original cached JSON,
  provenance, source, source quality, time, and nullable scalar exactly.
  The existing `toAnalysis...` functions remain physically qualified
  projections, masking numeric data that lacks a versioned contract.
- `UnifiedHistoryRepository.load` merges **raw** remote/local history
  and persists raw envelopes (without deleting any row or archive);
  separately returns a qualified drive/charge projection to UI.
  `mergeDrivesRaw` / `mergeChargesRaw` underlie the prior public
  `mergeDrives` / `mergeCharges` display methods. Quarantine, tenant,
  source quality and same-session guards remain in effect.
- `toLocalSummary`, `SyncRepository.toSyncSummary` and actual
  `DriveSummaryDao` / `ChargeSummaryDao` same-ID read/merge/upsert
  preserve raw `apiEvidence` (including an old unqualified Fleet
  8 kWh). Room analytic numeric columns use qualified values only:
  unverified means **null**, not an observed zero, and qualified
  zero/negative signed net energy remains valid. Existing old scalar
  columns aren't interpreted as proof just because they contain a number.
  Raw source bytes are retained byte-for-byte when the weaker incoming
  record contributes no new source evidence.
- Detail enrichment `withResolvedDriveEnergy` /
  `withDetailEvidence` now also retain earlier original raw values
  in the JSON when new detail has unknown energy. Explicit unknown
  contracts mask those raw values from display/aggregation; legitimate
  newly qualified detail values may update the analytic projection.
  `DrivesViewModel` always derives list energy metrics from
  `toAnalysisDriveData`, not a pre-contract Room scalar.
- Test fixtures with strong 8 kWh now attach a synthetic
  **source-complete estimated power-integral** contract,
  with explicit time basis and coverage; Fleet quality `observed`
  *by itself* is explicitly tested insufficient. The 8 kWh, source,
  quality, deduplication, aliases, valid-zero/negative and
  weak-unknown assertions remain. No fabricated Fleet report is claimed.
- New deterministic `RawHistoryEvidencePersistenceTest` drives the
  **same suspend read/merge/upsert orchestration called inside
  @Transaction DAO** against isolated in-memory storage: original
  same-ID raw Fleet8/JSON survives empty/weak remote and repeated
  upsert with count/identity/source intact; projection stays unknown.
  Covers changed weak observed row, distinct car IDs with same
  numeric ID, valid signed measured zero/negative, explicit unknown
  masking, raw charge zero/positive and both drive/charge detail
  unknown enrichment. No Android device, production DB, schema
  migration, or historical real-data write is performed.

**Recoverability boundary:** An original wire scalar remains recoverable
from the exact `apiEvidence` JSON (not repurposed as an analytics column).
A preexisting already-overwritten `apiEvidence` cannot be recreated
from missing data, and the candidate does **not** perform retroactive
history repair/backfill or fabricate old source readings. Old cached
scalar columns without sufficient provenance remain unqualified in
UI; production counts requiring historical cleanup stay separately
gated. Existing app data and all past trips are retained.

### Remaining TLS negative branches also repaired

- Existing-LE post-issuance path now invokes
  `reload-qualified-le.sh` which separately requires
  `nginx -t` **and** a successful reload before reporting
  `TLS_LE_ACTIVATION=VALIDATED_RELOAD`. A failed test/reload
  never sets activation PASS. Isolated tests invoke that real helper
  with a faulted Nginx test, failed reload, and valid success.
- `tls-nginx-transaction.sh` must verify **all four restored files**,
  catch cp/rm/pending cleanup failures and avoid a false
  `ROLLED_BACK`. If restore is incomplete, it returns
  `ROLLBACK_REQUIRES_OPERATOR` rather than reloading invalid files.
  Actual isolated script tests inject restore-copy and restore-removal
  failures in addition to prior normal, invalid Nginx, reload and
  new-warning paths. Only an independently verified exact restored
  disk and successful reload merits ROLLED_BACK.
- `verify-public.sh` probes each of the **four approved** private
  ports directly against the validated literal public IPv4,
  independently of localhost reachability; a public-only listener
  therefore fails qualification. An injected public-only port fixture
  must fail while a healthy 401 baseline passes. This is a bounded
  public check with no shell injection, unbounded scan or service write.

All three source fixes are **not** deployed to running Nginx. Do not
treat synthetic fault injection, validated public health, or
Android installation as real historical Tesla Fleet acceptance.

### Final acceptance remains evidence-tiered

Only the final PR17 **full HEAD/tree/parent and its completed same-SHA
Actions run** may qualify the source stage. Independent original local
Codex recompiles/tests/signs/installs in-place only after full verification
and confirms no Owner dirty-worktree or phone history mutation.
Production API (currently `bb09fac`) still requires a separately
reviewed, idempotent, backed-up reversible deployment gate for the
fractional-boundary serializer; there has been no API deployment.
The phone's original intermittent wrong TLS peer is unattributed,
Wi-Fi→cellular→restore permission is unanswered, and there is still
no accepted fresh source→authenticated API→Room→UI natural-energy
observation or multi-user/Fleet/TPMS/notification human acceptance.

### Final raw/source consumer regression audit

The recovered original receipt must not be confused with the presentation
model. `withSafeHistoryDisplay` is now the shared frontend projection
for both offline and freshly merged drives: synthetic raw address
formatting remains byte-recoverable in `apiEvidence`, while a
non-displayable address and an uncontracted Fleet energy scalar
never become a rendered label or a numeric metric.
`DriveSummary.toQualifiedHistoryMetrics` is the real drives-card
consumer: it reads only the qualified cached DTO rather than old
scalar columns. The isolated read/merge/upsert tests assert the
same behavior through that consumer.

The exact `UnifiedHistoryRepository.load` injected identity/scope
ports have an additional regression with one old Fleet raw-8-kWh
row and an empty remote response: the API receipt remains 8 in raw
JSON, the returned list is unknown, and the persisted analytic
numeric field is null. It is paired with same-ID Room transaction
I/O-ported tests, which also cover repeated weak remote data,
metadata, car isolation, exact raw JSON bytes, unknown detail
enrichment and original source addresses. These are synthetic unit
tests, **not** owner-data backfill or production Room reads.

The existing-8-fixture regression now requires a valid synthetic
whole-window power-integral contract for its *strong* row and
explicitly removes any inherited contract from the weak aliases.
The source-bound metric getters also reject an otherwise
plausible counter/net contract when the record's known source
and the measurement's source disagree. A nullable source retains
the limited old self-hosted compatibility path; mismatched
source is not allowed to masquerade as Fleet energy. This tightens
provenance without changing any raw evidence or the signed-zero/
regenerative estimates permitted by valid full-window contracts.

**Important:** Previously stored raw scalar columns might remain
numerically populated on old installations. They are never
considered authoritative source measurements. During a normal
successful qualified upsert, numeric **analysis** columns may
become null while original raw scalar and bytes remain in the
versioned `apiEvidence` JSON. This is a non-destructive
qualification projection, not a historical deletion/backfill.
No previously erased JSON may be recreated by guessing, and no
new migration/schema change is proposed.

The TLS remainder is separately covered by faulted existing-LE
nginx test/reload, cp/rm rollback failure with operator-only status,
and public-only private port tests; none of these runs against
production Nginx. The former wrong phone peer remains unattributed,
and real provider/Fleet and independent phone sign/install
acceptance remain separate from fixed-source CI.
