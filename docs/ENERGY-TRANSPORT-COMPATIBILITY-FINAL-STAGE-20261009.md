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
  verifies an existing LE fullchain/private-key pair is nonempty,
  expiry exceeds 24h and all three expected hostnames match. Keeps
  valid LE through `SKIP_CERT`, absent ACME email or failed renewal;
  invalid active LE and unrecognized custom includes fail closed. Nginx
  reload is still separately gated by the deploying operator. The source
  repair does not assert installed server config, and no production
  deployment was attempted.
- `deploy/scripts/verify-public.sh`: removes insecure `curl -k`
  from ordinary public self-test and a selfsigned-WARN success path;
  a certificate-invalid public peer is a FAIL, not success.
- `tools/energy-stage/qualify-public-tls.py`: hardcoded approved
  unauthenticated public `/healthz` only, normal system CA validation,
  hostname/SNI validation and DER leaf SHA-256 equality across at most
  **two** separate connections, five-second bound each, optional
  independently verified public peer SHA-256 assertion. Closed output
  enum only; no response bodies, error strings, certificate details,
  authorization header, source path, vehicle operation, provider access,
  network change or insecure fallback. All printed PASS/FAIL applies to
  **that process and vantage only**.
- Offline tests: real localhost self-issued TLS rejection,
  incorrect expected leaf fails before HTTP, two-request limit,
  same peer acceptance, no hidden authorization, invalid-count/peer
  input refusal, strict context and deployment-policy guards.
  CI's optional live public vantage is a diagnostic not a fleet/device
  acceptance criterion. Offline tests are mandatory and fail the job.

### Commands — isolated/review-only, no production credentials

From a **clean independent checkout of the exact final SHA**, not the
dirty Owner tree:

```bash
git rev-parse HEAD 'HEAD^{tree}' HEAD^
git diff --exit-code
bash tools/energy-stage/test-tls-deployment-policy.sh
python3 -m unittest discover -s tools/energy-stage -p 'test_tls_qualification.py' -v
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
  window, never a battery-input measurement.
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

## Appendix A — exact **approval-gated** Nginx scope, backup and rollback

The following is a **nonexecuted operator procedure**, not permission
to modify production. It is only applicable after independently proving that
the *managed* `jourvolt-ssl.inc` selects the incorrect public leaf on a
specific authorized TLS endpoint, and Jovi separately approves a
change-window, Nginx access, named files and rollback. A single unexpected
phone peer without a mapped active Nginx config is **not** that proof.

Allowed scope: `/etc/nginx/conf.d/jourvolt-ssl.inc` only for an already
installed, trusted, valid and correct LE chain; `jourvolt.conf` backup
is comparison-only and must not be overwritten. No other
server block, `stream`, system CA, DNS, firewall, proxy, account, database,
token, vehicle, production API or phone network modification. Do **not**
rerun all of `setup-root.sh` as a supposed minimal hotfix.

```bash
# CONTROLLED PRODUCTION CHANGE ONLY AFTER JOVI'S SEPARATE EXPLICIT APPROVAL.
set -euo pipefail
umask 077
conf=/etc/nginx/conf.d
# Backups are root-only and stored away from the public ACME directory.
backup="$(sudo mktemp -d /root/jourvolt-tls-snapshot.XXXXXXXX)"
sudo chmod 0700 "$backup"
sudo cp -a "$conf/jourvolt.conf" "$backup/jourvolt.conf"
sudo cp -a "$conf/jourvolt-ssl.inc" "$backup/jourvolt-ssl.inc"
# Record hashes privately. Never paste configuration/certificate bodies.
sudo sha256sum "$conf/jourvolt.conf" "$conf/jourvolt-ssl.inc" \
    | sudo tee "$backup/prechange-sha256.txt" >/dev/null
sudo cmp -s "$backup/jourvolt-ssl.inc" "$conf/jourvolt-ssl.inc"
sudo nginx -t >/dev/null 2>&1

# Reconfirm *before* approval-gated mutation:
# 1. Active include is exactly one known managed fragment (or STOP).
# 2. Cert/key exist, match, satisfy hostname and expiry policy (or STOP).
# 3. Approved unrelated Nginx vhosts and routes are unchanged (or STOP).
# 4. Public hostname TLS/health read has classified peer evidence (no -k).
# A valid LE include that is already active means NO-OP; retain backup.
if sudo cmp -s "$conf/jourvolt-ssl.inc" "$conf/jourvolt-ssl.le.inc"; then
  echo 'TLS_REPAIR=NOOP_ALREADY_MANAGED_LE'
elif sudo cmp -s "$conf/jourvolt-ssl.inc" "$conf/jourvolt-ssl.selfsigned.inc"; then
  echo 'TLS_REPAIR=APPROVAL_AND_VERIFIED_LE_CERT_REQUIRED'
  # With all preconditions and *separate approval* satisfied only:
  # sudo install -o root -g root -m 0644 "$conf/jourvolt-ssl.le.inc" "$conf/jourvolt-ssl.inc.pending"
  # sudo mv -f "$conf/jourvolt-ssl.inc.pending" "$conf/jourvolt-ssl.inc"
  # sudo nginx -t >/dev/null 2>&1 && sudo systemctl reload nginx
  # python3 tools/energy-stage/qualify-public-tls.py --live --samples 2
else
  echo 'TLS_REPAIR=STOP_UNRECOGNIZED_INCLUDE'
  exit 1
fi

# Rollback block, to be used ONLY if an approved change has actually failed:
# sudo cp -a "$backup/jourvolt-ssl.inc" "$conf/jourvolt-ssl.inc"
# sudo nginx -t >/dev/null 2>&1 && sudo systemctl reload nginx
# sudo cmp -s "$backup/jourvolt-ssl.inc" "$conf/jourvolt-ssl.inc"
# python3 tools/energy-stage/qualify-public-tls.py --live --samples 2
# Retain backup directory, private hash manifest, gate ticket, sanitized
# UTC/China timestamps, before/after verified TLS result for review.
```

Stop if any precondition or verification fails; do not reinterpret an
unrecognized include, certificate error, denied permissions or tool failure
as permission to replace a trust chain. If an approved change does not
restore TLS from the intended phone vantage, revert to exact file backup,
preserve observed results and investigate the actual route instead.
